<!--
Short is fine. A failing case first, then the fix that makes it pass, is the ideal PR here.
-->

## What changes

## Why it is not expressible today

## Verification

- [ ] `sh ./build.sh test` passes, run at least twice, including the new case
- [ ] both compile stages are warning-free (`-Xlint:all -Werror`), and the public API survives `-Xdoclint:all/protected` on JDK 17 and JDK 21
- [ ] `src/main/java` still imports only `java.lang.invoke`, `java.util`, `java.util.concurrent`
- [ ] `sh ./build.sh experiment` was re-run if `offer` or `poll` moved
- [ ] `CHANGELOG.md` has an entry under *Unreleased*

## If this touches the protocol

Paste `sh ./build.sh bench` before and after, same machine, and say how many
launches each number came from: the README quotes the range across the launches,
not the best of them. The row where `ArrayBlockingQueue` wins belongs in the table
too — the tables are the claim, the headline is not. No numbers, no merge.

## Notes

- One class, no dependencies. `synchronized`, `Unsafe` and a download are equally out.
- `null` is not a legal element and `size()` is approximate on purpose; if a change wants
  either, say what the third cursor would have to agree about.
- A case that passes once proves less than it looks like it does. Say how many times you
  ran it, on which JDK, and on which OS if the answer is not the machine you are on.
