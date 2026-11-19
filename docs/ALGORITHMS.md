# How the queue works

This is the reasoning behind `Turnstile.java`. It assumes you have read the
README's *what this is* and is written to be checkable: every claim here is either
a line of the source or a case in `src/test/java/turnstile/TurnstileTest.java`.

The algorithm is the turn-based bounded MPMC ring published by Dmitry Vyukov on
1024cores.net, reproduced independently in Java with `VarHandle`. That site has not
been maintained since around 2014, so the description below is the reference: the
point is not who wrote it first but that the invariant is small enough to prove to
yourself in ten minutes.

## 1. The one idea

A ring buffer of `capacity` slots where **each slot carries the number of the turn
it is currently in for**. Not a count of items — a turn number, from the same
monotonically increasing sequence the producers and consumers draw their positions
from. That single array is what lets both sides decide, from one word, whether the
slot in front of them is theirs.

Let `p` be a position (an absolute element index, `0, 1, 2, …`), `index = p & mask`
the slot it lives in, and `S[index]` that slot's sequence word. Then exactly three
states exist, and they are distinguished by one comparison:

| `S[index]` equals | Meaning | Who may act |
| --- | --- | --- |
| `p` | the slot is free and it is position `p`'s turn to fill it | a producer claiming `p` |
| `p + 1` | position `p` has been filled and not yet taken | a consumer claiming `p` |
| `p + capacity` | the element at `p` was taken; the slot is ready for the next lap | a producer claiming `p + capacity` |

Producer, for its candidate position `p`:

```
gap = S[index] - p
gap == 0   claim (CAS head p -> p+1), store the element, S[index] = p + 1
gap  < 0   the ring is full from here on: give up, return false
gap  > 0   another producer already claimed p and has not published: re-read head
```

Consumer, for its candidate position `q`:

```
gap = S[index] - (q + 1)
gap == 0   claim (CAS tail q -> q+1), read the element, clear the slot, S[index] = q + capacity
gap  < 0   nothing has been published at q: the ring is empty, return null
gap  > 0   another consumer is mid-claim at q: re-read tail
```

Both sides leave with the slot's word holding a value that is only correct for the
*next* party in line. That is why there is no third state to handle and no
end-of-lap special case: the arithmetic is the protocol.

## 2. Why two slots is the floor

`capacity = 1` is not a degenerate case of this protocol, it is a broken case.
A producer looks for `S[0] == p`, and the previous producer left `S[0] = p` behind
when it published position `p - 1` (because publishing writes `p - 1 + 1`). With a
single slot the index never changes, so the "free for `p`" marker and the
"published by `p - 1`" marker are the same word, and a full ring accepts another
element.

From two slots onwards `index(p - 1) != index(p)`, so the two readings live in
different words and cannot collide. That is the whole argument, and it is why the
constructor rounds a request of one up to two instead of either pretending to
support one or rejecting it.

This was not known when the first draft of the class shipped a `rounded < 1` check:
the test `an illegal capacity is refused before allocating` and
`every capacity from 1 to 1025 round-trips a live round` are what found it, and the
first version's failure looked like this — `offer` on a ring of one returned `true`
for the second element. It is recorded in `CHANGELOG.md` under the entry for this
release rather than quietly fixed.

## 3. Why the plain-looking element accesses are safe

Java's memory model gives a happens-before edge from a release store to an acquire
load **of the same field**. The queue only needs one such edge per element, and it
deliberately puts it on the sequence word rather than on the element slot:

```
producer   slots[index] = item     setOpaque   — nothing to order yet
           S[index]     = p + 1    setRelease  — publishes the line above

consumer   S[index]                getAcquire  — the matching edge
           item = slots[index]     getOpaque   — sees what was written
           slots[index] = null     setOpaque   — nothing retained once drained
           S[index]     = q + cap  setRelease  — the slot's next turn
```

The two release stores are different obligations: one publishes an element into a
slot nobody had read yet, the other returns a drained slot to the ring. The
acquire in the middle is the only edge an element needs, and the three lines under
it are everything `poll` does between winning the claim and handing the value
back.

Everything the producer did before the release store — including the element
reference itself *and the element's own mutable fields*, which is the part people
forget when they replace the acquire with a volatile read of the slot — is visible
to the consumer after the matching acquire. So a non-final `byte[] payload` inside
an element that was filled in before `offer` is fully formed on the other side, and
`singleProducerSingleConsumerOrder` plus the `P:4 C:4` round are what make that a
statement about this code rather than about a paper.

Three details that are *not* safety-critical but are load-bearing for performance
and are easy to get wrong:

- `getOpaque` for the initial cursor read. The `compareAndSet` that follows is the
  linearising point; reading a slightly stale `head` only costs a failed CAS, and a
  volatile read on the hot path would be a fence for no reason.
- `setOpaque(slots, index, null)` in `poll`, not `setRelease`. The consumer has
  already won the CAS on `tail`, so nobody else reads that slot again until a
  producer claims it, and the producer's own acquire of `S[index]` will order it.
  Clearing it is the no-retention guarantee: `retainedElements()` must be `0` after
  a drain, and `a drained slot stops retaining its element` asserts exactly that,
  100 cycles at a time.
- The dead `long` fields around `Counter.value`. They are an attempt at spatial
  isolation without `@Contended`, which is `jdk.internal` and off-limits. Two
  measurements bear on them: `Bench`'s *false sharing* table puts two
  compare-and-set counters one word apart and then 128 bytes apart, which is the
  effect the padding is meant to buy, and `SequenceStrideExperiment` asks whether
  the same trick applied to the per-slot sequence words pays for the 8x memory it
  costs. The first says yes, the second says "not consistently", on the machines in
  the transcripts.

## 4. Full, empty, and what the API returns

`offer(E)` and `poll()` never block, never grow, never allocate: full and empty are
values (`false`, `null`), not exceptions. That is the property the structure exists
for — a producer that outruns its consumers is *visible* to the producer, which is
the only way back-pressure can be handled deliberately.

The timed overloads spin 64 rounds and then park for at most a microsecond per
attempt until the deadline. There is no wait list and no `unpark`, which means a
waiting consumer discovers an arrival on its next look rather than being woken by
the producer. Consequences, measured rather than assumed:

- The park path is roughly an order of magnitude worse than the spin path for the
  same throughput (`Bench`'s *wait policy* table), because a `parkNanos` of 1 µs
  costs a scheduling round trip on Windows, where the timer granularity is nowhere
  near a microsecond.
- A queue whose consumers are *supposed* to sleep until work appears is the wrong
  tool: `ArrayBlockingQueue` conditions are signalled by the producer, and on the
  same table it is several times faster at exactly that job.

`offer(item, timeout, unit)` and `poll(timeout, unit)` throw
`InterruptedException` and leave the interrupt status cleared, which is what lets a
caller re-interrupt or propagate instead of losing the signal. Both are tested
(`an interrupted wait throws and clears its status`,
`an interrupted offer wait throws too`).

## 5. What is deliberately not here

- **No fairness.** A producer that lost a CAS competes again immediately. Nothing
  bounds how long a specific element waits, only that every element that gets in
  comes out in order.
- **No exact `size()`.** It is `head - tail` clamped into `[0, capacity]`, read from
  two words that are not updated together. An exact count needs a third protocol;
  `offer`'s return value is the exact answer to the only question that matters.
  `size stays inside [0, capacity] under traffic` samples it half a million times
  while two threads hammer the ring and asserts the clamp.
- **No batch operations on the protocol.** `drainTo` is a loop over `poll`, because
  claiming `k` positions at once would need the sequence words of `k` slots to be
  advanced as a unit, which is a different algorithm.
- **No `null` elements.** `poll()` returning `null` has to mean one thing.
- **No persistence.** A ring of live threads is not a wire format. The sibling
  project [`bitlane`](https://github.com/fj-8/bitlane) is where serialized,
  checksummed structures live, and its framing rules are the model for what a
  hostile byte would have to survive.

## 6. Reproducing any of it

```console
$ ./build.sh test            # 18 cases, exit 0 only when all pass
$ ./build.sh bench           # the throughput, depth, wait-policy and latency tables
$ ./build.sh experiment      # the sequence-stride A/B behind section 3
```

The concurrent rounds are sized to be boring on a laptop (about 1.6 s for the whole
suite) and to fail loudly rather than flakily: a `P:4 C:4` round reports the number
of lost elements, the number duplicated, and the first slot that arrived out of
order at a given consumer.
