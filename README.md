# turnstile

**A bounded queue that says no instead of growing.**

<p>
  <a href="https://github.com/fj-8/turnstile/actions/workflows/ci.yml"><img src="https://github.com/fj-8/turnstile/actions/workflows/ci.yml/badge.svg" alt="CI"></a>
  <a href="#license"><img src="https://img.shields.io/badge/license-MIT%20OR%20Apache--2.0-blue" alt="License: MIT OR Apache-2.0"></a>
  <img src="https://img.shields.io/badge/dependencies-0-informational" alt="Zero dependencies">
  <img src="https://img.shields.io/badge/java-17%2B-orange" alt="Java 17+">
  <img src="https://img.shields.io/badge/tests-18%20passing-brightgreen" alt="18 tests">
  <img src="https://img.shields.io/badge/locks-0-green" alt="No locks">
</p>

One class: `Turnstile<E>`, a multiple-producer multiple-consumer queue over a
fixed ring of slots, using the turn-based protocol Dmitry Vyukov published on
1024cores.net. Every slot carries the turn number it is waiting for, so a
producer claims a slot by advancing one shared cursor and publishes by releasing
that slot's word. There is no lock, no allocation per element, and no unbounded
growth: **`offer` returns `false` when the ring is full**, which is the whole
point — back-pressure becomes a value the caller can act on rather than a memory
curve nobody watches.

The dependencies are `java.lang.invoke`, `java.util` and `java.util.concurrent`.
Not one of them is a download. The test framework is 123 lines of
[`Harness.java`](src/test/java/turnstile/Harness.java), because a build that
needs JUnit is a build someone has to trust instead of run.

```java
Turnstile<Event> queue = new Turnstile<>(1024);   // capacity() says what you got

if (!queue.offer(event)) {                        // full: decide, don't melt
    metrics.dropped();
}

Event taken = queue.poll();                       // null means empty, never "a null arrived"
```

---

## Table of contents

- [Build and run](#build-and-run)
- [The API](#the-api)
- [What it guarantees](#what-it-guarantees)
- [Measured, not promised](#measured-not-promised)
- [The honest part: where it loses](#the-honest-part-where-it-loses)
- [Why the timed API is not a wait strategy](#why-the-timed-api-is-not-a-wait-strategy)
- [The padding experiment that did not ship](#the-padding-experiment-that-did-not-ship)
- [How it stays correct](#how-it-stays-correct)
- [When not to use this](#when-not-to-use-this)
- [Development](#development)
- [Elsewhere in this account](#elsewhere-in-this-account)
- [License](#license)

## Build and run

Any JDK 17 or newer. No Maven, no Gradle, nothing to fetch.

```console
$ ./build.sh              # compiles main, tests and bench with -Werror
$ ./build.sh test         # ... and runs the 18-case suite
$ ./build.sh bench        # ... and runs the measurement program
$ ./build.sh experiment   # ... and runs the sequence-stride A/B
```

`build.sh` is 41 lines of POSIX shell: two `javac` invocations and a `case`. The
first compiles `src/main/java` with `-Xlint:all -Xdoclint:all/protected -Werror`,
so the public API has to survive the documentation linter as well as the code
one; the second compiles the tests and the bench with `-Xlint:all -Werror`.
Both stages treat a warning as a build failure, which is also the bar on every
combination in CI.

On Windows the script runs in Git Bash. Without a shell, the same two compiles
need the file list spelled out — `cmd` and PowerShell do not expand `*.java` the
way `sh` does, and `javac` will not do it for them:

```console
> javac -Xlint:all -Xdoclint:all/protected -Werror -d out/classes src\main\java\turnstile\Turnstile.java
> javac -Xlint:all -Werror -cp out/classes -d out/classes src\test\java\turnstile\Harness.java src\test\java\turnstile\TurnstileTest.java bench\java\turnstile\Bench.java bench\java\turnstile\SequenceStrideExperiment.java
> java -cp out/classes turnstile.TurnstileTest
```

## The API

| Method | Blocks? | On empty / full |
| --- | --- | --- |
| `offer(E)` | never | returns `false` when the ring is full |
| `offer(E, long, TimeUnit)` | up to the timeout | returns `false` when it expires |
| `poll()` | never | returns `null` when the ring is empty |
| `poll(long, TimeUnit)` | up to the timeout | returns `null` when it expires |
| `drainTo(Collection, int)` | never | moves what is there, returns the count |
| `size()` / `isEmpty()` | never | approximate, clamped to `[0, capacity()]` |
| `capacity()` | never | the request rounded up to a power of two, floor of 2 |

`null` is not a legal element, which is what makes `null` from `poll()` mean
"nothing was available" rather than "a null arrived". Elements are held strongly
while queued and the slot is cleared as the element leaves, so a drained ring
retains nothing — that is asserted, not assumed
(`a drained slot stops retaining its element`).

Both timed methods throw `InterruptedException` and leave the interrupt status
cleared, and both spin 64 rounds before parking, about a microsecond at a time,
so a starved consumer stops burning a core without adding scheduling latency to
a busy one.

## What it guarantees

- **Delivery exactly once.** Every element that `offer` accepts is returned by
  exactly one `poll`. `P:4 C:4 delivers 200k exactly once, in order` counts each
  element's arrivals across four consumers and fails with the number lost, the
  number duplicated and the first out-of-order slot.
- **Per-producer order.** Elements from one producer reach any given consumer in
  the order they were offered. Different producers interleave — no queue of this
  shape can promise otherwise without a total order, and this one does not pay
  for one.
- **A bounded footprint.** Two arrays at construction, `capacity` slots, and
  nothing per element. `size()` never exceeds `capacity()`, asserted by sampling
  it nine hundred thousand times while two threads hammer the ring.
- **No lock, no `synchronized`, no `Unsafe`.** `VarHandle` `compareAndSet` for
  the two cursors, `setRelease`/`getAcquire` on the slot words, `setOpaque` for
  the element slots themselves.

## Measured, not promised

Three separate JVM launches of
[`bench/java/turnstile/Bench.java`](bench/java/turnstile/Bench.java) on a 20-core
Windows 11 machine under JDK 17.0.20.1: 2,000,000 elements per transfer, capacity
1,024 unless the row says otherwise, median of five timed repetitions after one
discarded warm-up. The transcripts are committed
([`bench-run-1.txt`](bench/bench-run-1.txt),
[`bench-run-2.txt`](bench/bench-run-2.txt),
[`bench-run-3.txt`](bench/bench-run-3.txt)) and every number below is the range
across all three, not the best of them. Nanoseconds per element, lower is better.

| shape | turnstile | `ArrayBlockingQueue` | which wins |
| --- | --- | --- | --- |
| 1 producer, 1 consumer | 77–107 | 139–151 | turnstile, by 1.34–1.96× |
| 2 and 2 | 133–152 | 93–100 | `ArrayBlockingQueue`, by 1.34–1.60× |
| 4 and 4 | 146–148 | 98–102 | `ArrayBlockingQueue`, by 1.44–1.51× |
| 8 and 8 | 190–286 | 111–117 | `ArrayBlockingQueue`, by 1.68–2.58× |
| 1 and 4 | 141–187 | 137–143 | `ArrayBlockingQueue`, by 1.01–1.36× |
| 4 and 1 | 138–173 | 192–221 | turnstile, by 1.11–1.60× |

What the class is actually for is the second table: the same transfer at
different ring depths, four producers and four consumers.

| capacity | turnstile | `ArrayBlockingQueue` |
| --- | --- | --- |
| 2 | 246–352 | 12,611–13,622 |
| 16 | 227–266 | 2,106–2,188 |
| 64 | 207–248 | 546–591 |
| 256 | 162–205 | 170–186 |
| 1,024 | 147–211 | 100–108 |
| 4,096 | 187–194 | 95–108 |

Read the two columns as shapes, not as a winner. `ArrayBlockingQueue` spans more
than two orders of magnitude across those six rows — 130× to 140× between its best
and worst — because every operation takes a lock and touches two condition queues,
and at depth 2 with eight threads that lock *is* the workload. This queue spans
under 2.5× from its shallowest to its deepest ring, because a slot claim is one
`compareAndSet` on a cursor and the ring's depth only changes how often a producer
waits for its turn. From depth 1,024 the lock stops being the bottleneck and the
condition-signal wake-ups win it back.

Every row above comes from one program, one machine and the same three launches;
the requested capacity is the only thing the depth rows change. A capacity-2
queue is the two-slot floor the constructor needs, so the
first row is not a hypothetical: it is what you get if you ask for a one-slot
ring and the class tells you so by rounding up.

## The honest part: where it loses

- At every **symmetric** shape with two or more threads per side,
  `ArrayBlockingQueue` is faster — by 1.34–1.60× at 2 and 2, 1.44–1.51× at 4 and
  4, 1.68–2.58× at 8 and 8. A lock that batches a thread's worth of waiting beats
  a CAS that every thread has to win, once enough threads contend for one cursor.
- At **1 and 1**, an unbounded `ConcurrentLinkedQueue` is 1.28–2.33× faster. It
  also allocates a node per element and never returns `false`, so it is not
  enforcing the bound you asked for; the row is in the transcript because leaving
  it out would be the kind of number people later find and stop trusting.
- Its **timed API is slower than its own untimed one** by 4.0–6.3×, and that gap
  is the subject of the next section.
- At **8 and 8** the turnstile's own spread is wide (190–286 ns). Eight producers
  and eight consumers on a 20-core machine are competing with each other for
  cores as much as for the ring, and the class has no answer for that. It is a
  promise of bounded memory and a return value, not a promise of scaling.

The claim the measurements support is narrower than "fastest queue": a bounded
MPMC queue that allocates nothing per element, refuses instead of growing, and
costs roughly the same whether the ring holds 2 slots or 4,096.

## Why the timed API is not a wait strategy

| at 2 and 2, same workload | ns per element |
| --- | --- |
| `offer`/`poll`, spinning | 147–155 |
| `offer(timeout)`/`poll(timeout)` | 613–939 |
| `ArrayBlockingQueue`, timed | 99–107 |

The timed path is 4.0–6.3× the spin path and 5.7–9.5× slower than the same timed
call on the lock-based queue. The reason is structural, not an implementation
slip: this class has no way to be told anything. A waiter discovers an empty ring
by looking, parks for a round, and looks again, so a producer's publish and a
consumer's wake-up are two unrelated events. `ArrayBlockingQueue`'s producer
signals a condition, and the consumer is on a run queue before it would next have
polled.

The handoff-latency row in the transcripts shows the other half: with one element
in flight and two threads alternating, the three queues are within 30% of each
other (turnstile 102–124 ns per hop, its timed variant 92–103,
`ArrayBlockingQueue` 109–111). When there is work, waiting does not happen, so the
cost of the wait policy is invisible. The gap is what you pay when there is not.
Do not read the timed variant's ping-pong row as an invitation to park on this
queue — the same variant is 4–6× behind the spin path on the throughput workload
above, and the ping-pong ordering of those two rows does flip between runs.

If your consumers should sleep until work arrives, that is a different data
structure and `ArrayBlockingQueue` is the right one. If you own the cores and want
a bounded, allocation-free handoff with back-pressure you can see, that is this
one.

## The padding experiment that did not ship

Two questions, two measurements, and only one of them changed the code.

**Do the two cursors need to be on different cache lines?** Yes. `head` and `tail`
are each a `Counter` with four dead `long`s on either side of the value, so they
cannot land in the same line. `Bench` measures the assumption instead of resting
on it: two threads each `compareAndSet` a word of their own, a million times,
counters one word apart versus 128 bytes apart — **57–63 ns/op unpadded, 31–35 ns
padded, 1.63–1.90× faster**. That is the whole justification for the dead fields,
and it is why they are not marked `@Contended`: that annotation is
`jdk.internal`, so a portable library cannot compile with it, let alone ship it.

**Does each slot's sequence word need the same treatment?** No, so the class does
not do it. [`SequenceStrideExperiment.java`](bench/java/turnstile/SequenceStrideExperiment.java)
runs the identical protocol twice in one JVM, with the sequence array laid out one
word per slot and eight words per slot, and the gain per cell is
`stride 1 / stride 8` — above 1 means padding won:

| capacity | 1 producer | 2 producers | 4 producers | 8 producers |
| --- | --- | --- | --- | --- |
| 64 | 0.86–1.09 | 1.26–1.27 | 1.18–1.19 | 0.95–1.02 |
| 1,024 | 0.98–0.99 | 1.07–1.10 | 1.08–1.11 | 0.94–0.95 |
| 4,096 | 0.95–1.07 | 1.24–1.29 | 1.06–1.10 | 0.87–0.88 |

Two runs, [`stride-run-1.txt`](bench/stride-run-1.txt) and
[`stride-run-2.txt`](bench/stride-run-2.txt), agree to within 0.05× in every cell
that has two or more producers; the one-producer cells straddle 1.0, which is what
a wash looks like when a 5 ns difference is 5% of the ratio. Padding is worth
7–29% at two producers and 6–19% at four, nothing at one, and at eight producers
it ranges from neutral to 13% slower — while costing eight times the sequence
memory, 32 KB to 256 KB at a capacity of 4,096. A change that helps one shape,
hurts another, and multiplies the footprint to do it is not a change this class
can sell as a default. `docs/ALGORITHMS.md` section 3 records the same conclusion
next to the parts of the layout that did ship.

## How it stays correct

[`docs/ALGORITHMS.md`](docs/ALGORITHMS.md) is the reasoning, written to be
checkable: a three-row table of what a slot's sequence word can mean, the
argument that two slots is the protocol's real floor rather than a preference,
and the happens-before edge that lets an element travel on a plain-looking array
write.

The short version of the memory model, since it is the part people get wrong:

```
producer   slots[index]     = item      setOpaque   — nothing to order yet
           sequences[index] = p + 1     setRelease  — publishes the line above

consumer   sequences[index]             getAcquire  — the matching edge
           item = slots[index]          getOpaque   — sees what was written
           slots[index]     = null      setOpaque   — nothing retained once drained
           sequences[index] = q + cap   setRelease  — the slot's next turn
```

One release store and one acquire load *of the same word* carry both the reference
and the element's own mutable fields. That is why the acquire sits on the sequence
array rather than on the slot: `sequences[index]` is the one word both sides touch
for a slot, and the consumer's claim condition can only be satisfied by the
release that published the item it is about to read. The slot itself needs no
ordering edge at all — it is only ever read by the thread that won the claim.

The 18 cases are not all concurrency. Most of them are the boring contract —
capacity rounding, rejected nulls, a full ring that refuses, an interrupt that
propagates — and they are the ones that caught the first two real bugs, both
recorded in [`CHANGELOG.md`](CHANGELOG.md) rather than quietly fixed: a swapped
comparison in the gap arithmetic that made every `offer` on an empty ring return
`false`, and a capacity of one that the protocol cannot express at all.

The last case reads the rest of the repository instead of the queue: it counts the
suite's own registrations, the three file lengths this README uses as arguments,
and the five transcripts it claims are committed, and it fails when a document and
the tree disagree. That one is not hypothetical either. The Javadoc fix that made
the tag build on JDK 21 added two lines to `Turnstile.java`, the README went on
saying 312, and nothing in the suite could notice, because nothing in the suite
read the README. A number that only a human can check is a number that drifts.

## When not to use this

- **You want consumers to sleep until work arrives.** Use `ArrayBlockingQueue`.
  Its producers signal a condition; this queue's waiters discover events on their
  next look, which is measurably several times worse for that job.
- **You want an unbounded queue.** This one refuses. If a full ring should be
  impossible rather than interesting, `ConcurrentLinkedQueue` is the tool, at the
  cost of an allocation per element and no back-pressure at all.
- **You need `size()` to be exact, or fairness, or a persistent format.** None of
  the three is here, and each is listed as a deliberate non-goal with the reason
  in `docs/ALGORITHMS.md` section 5.
- **You have one producer and one consumer and want the last nanosecond.** A
  single-producer single-consumer ring needs no CAS at all, only ordered cursor
  reads, and will beat this on the shape it is built for.

## Development

```console
$ ./build.sh test          # 18 cases, exit 0 only when all pass
$ ./build.sh bench         # the throughput, depth, wait-policy and latency tables
$ ./build.sh experiment    # the sequence-stride A/B
```

- `src/main/java/turnstile/Turnstile.java` — the queue, 316 lines, one class.
- `src/test/java/turnstile/Harness.java` — the test framework: cases, a watchdog,
  exit codes.
- `src/test/java/turnstile/TurnstileTest.java` — the 18 cases.
- `bench/java/turnstile/Bench.java` — the measurement program whose output is in
  `bench/`.
- `bench/java/turnstile/SequenceStrideExperiment.java` — the padding A/B.

`.github/workflows/ci.yml` is ten jobs: the 18-case suite run **twice** per
platform on JDK 17 and 21 across Linux, Windows and macOS (six), then four
hygiene checks — an import allowlist that fails on any package outside the JDK,
`javadoc -Xdoclint:all` on the public API, `build.sh test` and `build.sh
experiment` from a clean tree, and a check that `build.sh` is still committed
mode `100755` so `sh ./build.sh` keeps working on the runners that care.

Three of those have already caught something real. The mode check failed a commit
whose own message was about documentation: the working copy sits on a filesystem
that does not carry the executable bit, and a mode change the author never made
rode along into the tree. And JDK 21's doclint rejects a Javadoc block that opens
straight into `@param` or `@return` with no leading sentence, while JDK 17's
accepts the identical file — so one leg of the matrix compiled and the other
did not. And the twice-per-platform suite failed on its second pass on a case
that asserted it had seen a non-empty ring: one producer and one consumer
draining a backlog leave the queue empty almost all of the time, so `size()` was
right and the assertion was the defect — the case constructs that condition now
instead of racing for it. None of the three is a defect in the queue: one is a
mode bit, one a comment with no first sentence, one an assertion that needed
luck. All three needed a second opinion to be visible — a different JDK, a check
that reads the index instead of the source, or the same suite run a second time.

The macOS legs have been the opposite kind of red: a runner problem rather than a
result. Two runs on `main` reported both of them `cancelled` with no runner ever
assigned to the job — no steps, no start time — while the same two JDKs on Linux
and Windows completed, and the run for `7627612` ended `cancelled` having started
nothing at all, which is what a queued `main` run becomes when a newer push
arrives before it begins. None of that is a statement about the queue, and a badge
cannot tell it apart from a statement that is, so it is written down here.

The whole suite runs twice on every platform, because a concurrency test that
passes once proves less than it looks like it does. A case
that hangs becomes a `TIMEOUT` and exit code 2 after
`-Dturnstile.watchdog.minutes` (5 by default) instead of holding a runner
forever — which is how the capacity-of-one livelock was first noticed.

## Elsewhere in this account

The same rules applied in three other languages — zero dependencies, and every
number in the README measured in the same checkout:

- [`karg`](https://github.com/fj-8/karg) — dependency-free Rust argument parser
  with typed accessors and errors that name the nearest valid flag.
- [`bitlane`](https://github.com/fj-8/bitlane) — header-only C++20 compressed
  bitmap index over `uint32_t` sets, with a versioned, FNV-1a-64-checked wire
  format.
- [`sift`](https://github.com/fj-8/sift) — pure-stdlib Python 3.11+ streaming
  estimators (HyperLogLog, Count-Min, t-digest, Welford, reservoir) and a CLI.

## License

Dual-licensed under either of [Apache-2.0](LICENSE-APACHE) or
[MIT](LICENSE-MIT), at your option.

### Contribution

Unless you state otherwise, any contribution you submit for inclusion in the
work shall be dual-licensed as above, without any additional terms or
conditions.
