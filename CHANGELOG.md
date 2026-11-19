# Changelog

All notable changes to this project are documented here. The format is Keep a
Changelog; the version numbers are `MAJOR.MINOR.PATCH` and `0.x` means the API can
still change. This project does not follow semantic versioning promises it has not
tested: 0.1.0 is the first release anyone outside the author's machine can install.

## [Unreleased]

Nothing pending.

## [0.1.1] - 2026-11-19

Two shipped documentation defects, one packaging file, one test that could fail
when nothing was wrong, and one test that reads the documentation. Nothing in the
queue or in the measurements changed, and no benchmark number in the README moved.

### Fixed

- The constructor and `capacity()` carried Javadoc that opened straight into
  `@param` and `@return` with no leading sentence. JDK 17's doclint accepts that
  shape, JDK 21's says `warning: no main description`, and the compile command
  the README tells you to run carries `-Werror` — so `v0.1.0`'s own source did
  not build on JDK 21 with the documented command. Both comments have a first
  line now; `CONTRIBUTING.md` explains why the matrix has two JDKs in it rather
  than the one this machine has.
- `build.sh` is committed with mode `100755` again. It dropped to `100644` in the
  commit after `v0.1.0`, because that working copy sits on a filesystem that does
  not report the executable bit while `core.fileMode` was still `true`, and a
  mode change nobody made rode into a documentation commit unnoticed. No released
  artifact carried it: the `script-hygiene` leg refused it on `main` first.
- A root `LICENSE` file now states both grants, the SPDX identifier that already
  heads every source file, and the contribution clause. The two full texts were
  always in `LICENSE-APACHE` and `LICENSE-MIT`; this is the file tools look for
  when they do not read the README.
- `size stays inside [0, capacity] under traffic` stopped depending on being lucky.
  It asserted that the sampler had seen a non-empty ring, and on the second suite
  run of `ubuntu-latest / JDK 21` it reported the largest reading `0`: one producer
  and one consumer draining a pre-existing backlog leave an empty ring almost all
  of the time, so `size()` was right and the case was unsound. It now constructs
  the condition instead of racing for it — the ring is full before any thread
  starts, the consumer stays gated until the sampler has published its first
  reading, and that first reading must be `capacity`; traffic is proven by the
  producer's and consumer's own counters rather than by a racy observation. The
  queue never had the defect the case could report.
- The suite is 18 cases, and the last one reads the repository rather than the
  queue: `the documentation quotes this checkout` compares the numbers the README,
  `CONTRIBUTING.md` and `docs/ALGORITHMS.md` quote with the tree they describe —
  the number of registered cases, the length of `Turnstile.java`, `Harness.java`
  and `build.sh`, and the five transcripts the README says are committed. It found
  something on its first run: the Javadoc fix further up this list added two lines
  to `Turnstile.java` and the README went on saying 312, which no test could see
  because nothing in the suite opened the README. It was verified by breaking it —
  with the count put back to 315 the case reports `which is 316 lines long, is
  quoted as: the queue, 315 lines` and the run exits 1.
- The CI section said two runs had both macOS legs cancelled. Five consecutive
  commits whose runs completed did — `a09c74e`, `5f0ae82`, `a6836b2`, `fa7a568`
  and `ae06561` — and the section now separates the two shapes it was lumping
  together: on `a6836b2` and `ae06561` every job that got a runner went green and
  the run still reported `failure`, while on `a09c74e`, `5f0ae82` and `fa7a568`
  the starved legs arrived beside a real one. Three further runs — `13ad613`,
  `b674b80`, `7627612` — ended `cancelled` with no job started at all. Same
  reason as the line count above: a number in a document is re-taken from the
  thing it describes, not from the note it was copied into.

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
