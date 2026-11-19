# Security

## What counts as a vulnerability here

`turnstile` is 312 lines of concurrency code with no I/O, no parsing, no
deserialisation and no dependencies. That removes most of the usual categories, so
the list is short and specific.

**Report it privately** (GitHub → Security → *Report a vulnerability*) if a
release lets an attacker do any of these:

1. **Unbounded memory growth from a producer that is not supposed to be able to
   grow anything.** The queue allocates exactly two arrays at construction and
   nothing per element. A crafted sequence of `offer` calls that makes the
   structure allocate — or that keeps references alive in slots nobody can drain —
   breaks the only resource guarantee the class makes. `retainedElements()` exists
   so the suite can assert the drain path (`a drained slot stops retaining its
   element`); a hole in it is a vulnerability, not a bug.
2. **A livelock reachable from one honest thread.** The protocol spins in two
   places: waiting for a slot's turn marker, and retrying a lost
   `compareAndSet`. Both are supposed to be bounded by another thread's *single*
   completed operation. If a configuration exists where a producer and a consumer,
   each making forward progress, leave a third thread unable to finish, that is
   a denial-of-service vector in the only place this library can cause one.
3. **Loss, duplication or reordering of elements without any caller-side bug.**
   This is the correctness contract, and the test suite is written so that a
   violation fails rather than passes quietly. It is listed here because a queue
   that silently drops messages is a security property of whatever uses it:
   audit logs, rate-limit tokens, idempotency keys.
4. **A broken interrupt contract.** `offer(item, timeout, unit)` and
   `poll(timeout, unit)` must not swallow an `InterruptedException` and must leave
   the status cleared when they throw it. A thread that cannot be stopped because
   of a library is a trap in every program that uses it.

## What is not a vulnerability

- **`offer` returning `false` under pressure.** That is the design. A bounded queue
  that refuses is the point; the caller's response to `false` is the caller's
  problem, and `SECURITY.md` is not a promise about your back-pressure policy.
- **Elements taking longer than expected because the wait path polls instead of
  being signalled.** The timed API parks on a timer and discovers the event on the
  next look; `ArrayBlockingQueue` is told by a condition signal. The README's
  *wait policy* table measures the difference — 4.0–6.3× against this class's own
  spinning path, 5.7–9.5× against `ArrayBlockingQueue`'s timed call, on the machine
  in the transcripts — and the recommendation is to use `ArrayBlockingQueue` when
  the job is waiting rather than passing. Predictable, documented, and not a hole.
- **`size()` being approximate.** It is read from two cursors that are not updated
  atomically together, and the Javadoc says so. Do not build a limit or an
  authorisation check on it; `offer`'s return value is the exact answer.
- **Head-of-line behaviour under an unbalanced producer/consumer count.** Nothing
  here is fair, and no promise of fairness is made.
- **Anything requiring the attacker to already control your threads' code.** Once
  arbitrary execution in the JVM is available, a queue is not the interesting
  target.

## Version policy

`main` is the release. Fixes for the categories above ship as a patch tag, and the
advisory, if the report is accepted, is published on the repository's Security tab
so that `dependabot`-style tooling can see it. There is nothing on Maven Central or
a registry to update: the artifact is a git tag, and the tag is the release.

## Acknowledgements

No bounties, no safe-harbour theatre. A private report that reproduces gets a
fix, a changelog line naming you if you want it, and a closed advisory.
