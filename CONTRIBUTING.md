# Contributing

Three rules, and the build is three commands. If a change breaks one of the
rules, open an issue first — the rules are the design.

## The rules

1. **The JDK is the entire dependency list.** No Maven, no Gradle, no JUnit, no
   JMH, not even as a test dependency. `src/main/java` imports only
   `java.lang.invoke`, `java.util`, `java.util.concurrent`, and that is the whole
   story; `src/test/java` adds `java.util.concurrent.atomic`. A build that needs a
   download is a build someone has to trust rather than run. If you think a
   dependency earns its place, say what it replaces — `Harness.java` is 123 lines
   and it is the test framework, and `Bench.java` is the benchmark harness.
2. **Every claim is a test or a measurement.** "Order-preserving" is
   `singleProducerSingleConsumerOrder` and the `P:4 C:4` round in
   `TurnstileTest`; "no allocation per element" is `retainedElements` and the
   constructor. "About as fast as `ArrayBlockingQueue`" is not a claim anyone can
   make, so it is a table in `bench/` produced by a committed program and a
   transcript that is checked in next to it.
3. **No fabricated performance, including your own.** If a run says
   `ArrayBlockingQueue` is faster at eight producers, that row goes in the README.
   A number that flatters the code and does not reproduce is worse than no number.
   Re-run before you quote, and quote the run you committed.

Two smaller conventions:

- `javac -Xlint:all -Werror` is the bar, on every JDK in the matrix. Warnings are
  not noise to be suppressed with `@SuppressWarnings` unless the reason fits on one
  line above the annotation.
- Javadoc on the public surface, comments for the non-obvious *why*. The two
  longest comments in `Turnstile.java` explain the two-slot floor and the
  happens-before edge; neither explains what the next line of code does. The
  matrix earns its keep here: JDK 21's doclint rejects a block that opens with
  `@param` or `@return` and has no leading sentence (`warning: no main
  description`), JDK 17's accepts the same file, and `-Werror` is what makes the
  difference a build failure on the JDK 21 legs instead of a line in the log.

## Build, test, bench

Any JDK 17 or newer. There is no build tool to install.

```console
$ ./build.sh            # compiles main, tests and bench into out/classes
$ ./build.sh test       # ... and runs the 17-case suite
$ ./build.sh bench      # ... and runs the measurement program
```

Or the two compile commands the script wraps. They are two, not one, because the
public API is held to `-Xdoclint:all/protected` and the test and bench sources
are not — a missing `@return` on `poll()` is a defect in shipped documentation, a
missing one on a helper is not:

```console
$ javac -Xlint:all -Xdoclint:all/protected -Werror -d out/classes \
      $(find src/main/java -name '*.java')
$ javac -Xlint:all -Werror -cp out/classes -d out/classes \
      $(find src/test/java bench/java -name '*.java')
$ java -cp out/classes turnstile.TurnstileTest
$ java -cp out/classes turnstile.Bench
```

The suite prints `ok`/`FAIL` per case and exits non-zero on any failure, so it
needs no CI integration to be usable. It also has a watchdog: a case that hangs
turns into a `TIMEOUT` and exit code 2 after
`-Dturnstile.watchdog.minutes` (5 by default) instead of holding a runner.

Concurrent cases are not flaky by construction, but they are concurrent: if you
see a failure, run the suite ten times and say which case and which JDK. A
`P:4 C:4` round that reports a lost element is the most interesting bug this
repository can contain, and it will be treated as one.

## Pull requests

- One behaviour per change, with the test that pins it.
- `CHANGELOG.md` gets an entry under *Unreleased*; the format is Keep a Changelog.
- If you touch the protocol in `Turnstile.offer`/`poll`, re-run
  `java turnstile.SequenceStrideExperiment` too — it exercises the same loop, and
  a change that helps one layout usually helps the other.
- `main` is the release. No force-pushes after a tag is public.
