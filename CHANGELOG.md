# Changelog

All notable changes to this project are documented here. The format is Keep a
Changelog; the version numbers are `MAJOR.MINOR.PATCH` and `0.x` means the API can
still change. This project does not follow semantic versioning promises it has not
tested: 0.1.0 is the first release anyone outside the author's machine can install.

## [Unreleased]

Nothing pending.

## [0.1.0] - 2026-11-19

First release: one class, one test harness, one measurement program, one experiment.

### Added

- `Turnstile<E>`: a bounded multiple-producer multiple-consumer queue over a fixed
  ring, using the turn-based protocol with per-slot sequence words. `offer`,
  `poll`, both timed variants, `drainTo`, `size`, `isEmpty`, `capacity`. Built on
  `VarHandle` acquire/release and `compareAndSet`; no `synchronized`, no
  `Unsafe`, no dependencies, no allocation per element.
- `Harness` and `TurnstileTest`: 17 cases, stdlib-only, run by `java
  turnstile.TurnstileTest`, exit code 0 only when all pass. The concurrent rounds
  assert delivery exactly once, per-producer order at every consumer, bounded
  `size()`, and zero retained references after a drain. A watchdog turns a hung
  case into a failure instead of a hung runner.
- `Bench`: throughput matrix (6 producer/consumer shapes against
  `ArrayBlockingQueue` and `ConcurrentLinkedQueue`), a ring-depth table, a
  wait-policy comparison, a handoff-latency ping-pong, and a false-sharing
  measurement. Median of 5 reps after a discarded warm-up, with a checksum of
  everything consumed so the work cannot be optimised away. Committed
  transcripts: `bench/bench-run-1.txt`, `bench/bench-run-2.txt`,
  `bench/bench-run-3.txt` (three separate JVM launches of the same program) and
  `bench/stride-run-1.txt`, `bench/stride-run-2.txt`.
- `SequenceStrideExperiment`: the A/B that asks whether padding each slot's
  sequence word to its own cache line pays. It is in the repository because the
  README quotes it, and the README quotes it because the answer was not what the
  design note expected.
- `docs/ALGORITHMS.md`: the protocol table, the two-slot floor argument, the
  happens-before edge, and what the timed API does not do.
- CI: 6 JDK/OS combinations running the suite twice, plus lint-and-doclint-strict
  compiles, an import allowlist that fails on any non-JDK import, a javadoc render,
  and `build.sh` from a clean tree.
- `README.md`, `CONTRIBUTING.md`, `SECURITY.md`, `.gitignore`, `build.sh`,
  dual MIT/Apache-2.0 licensing.

### Fixed

- A `Turnstile` constructed with a capacity that rounded to one accepted more
  elements than it could hold. The protocol cannot distinguish "free for position
  `p`" from "published by position `p - 1`" when both readings look at the same
  slot, so a ring of one let a second `offer` succeed while the first element was
  still queued, and a consumer could then claim a position nobody had published —
  which livelocked the reader in the retry branch instead of corrupting data.
  Found by `offer timeout expires on a ring nobody drains` and by the 4-minute
  watchdog on the run that followed it. The constructor now rounds up to a minimum
  of two slots, `docs/ALGORITHMS.md` section 2 proves why two is the real floor,
  and `an illegal capacity is refused before allocating` plus
  `every capacity from 1 to 1025 round-trips a live round` pin it.
- The capacity ceiling check ran after the two-slot floor, so a request above 2^30
  overflowed to a negative `int` and was silently clamped *up* to two instead of
  rejected: `new Turnstile<>((1 << 30) + 1)` returned a usable queue of capacity 2.
  The rounding is now done in `long`, the ceiling is checked before the floor, and
  the rejection message quotes both the request and what it would have rounded to.
- The producer and consumer compared the sequence word against the wrong side of
  the position (`seq == p + 1` for producers and `seq == p` for consumers, swapped).
  Every `offer` on an empty ring returned `false` and every `poll` on an empty ring
  consumed a position. Two lines, and the first test run of 17 showed it
  immediately.
- Timing assertions in the suite divided nanoseconds by 1,000,000 while comparing
  against microsecond bounds, so `a 30 ms poll on an empty ring` failed on a run
  that had waited exactly 30 ms. The unit is now named in the constant and every
  bound is expressed in it.

[Unreleased]: https://github.com/fj-8/turnstile/compare/v0.1.0...HEAD
[0.1.0]: https://github.com/fj-8/turnstile/releases/tag/v0.1.0
