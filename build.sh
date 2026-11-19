#!/bin/sh
# Build without a build tool. Requires a JDK 17 or newer on PATH, or JAVA_HOME
# pointing at one. Nothing is downloaded, nothing is generated.
#
#   ./build.sh              compile main, tests and bench, strictly
#   ./build.sh test         ... and run the suite
#   ./build.sh bench        ... and run the measurement program
#   ./build.sh experiment   ... and run the sequence-stride A/B
set -eu

cd "$(dirname "$0")"
OUT=${OUT:-out/classes}

if [ -n "${JAVA_HOME:-}" ] && [ -x "$JAVA_HOME/bin/javac" ]; then
  JAVAC="$JAVA_HOME/bin/javac"
  JAVA="$JAVA_HOME/bin/java"
else
  JAVAC=${JAVAC:-javac}
  JAVA=${JAVA:-java}
fi

# -Xlint:all -Werror is the bar on every JDK in the CI matrix, so a warning is a
# build failure here too rather than something a reviewer sees only on GitHub.
# The public API additionally has to survive doclint: /protected limits the check
# to what a reader of the Javadoc can see, because a private field with no comment
# is not a defect anyone using the library can be hurt by.
mkdir -p "$OUT"
"$JAVAC" -Xlint:all -Xdoclint:all/protected -Werror -d "$OUT" \
  $(find src/main/java -name '*.java' | sort)
"$JAVAC" -Xlint:all -Werror -cp "$OUT" -d "$OUT" \
  $(find src/test/java bench/java -name '*.java' | sort)
echo "compiled to $OUT"

case "${1:-build}" in
  build) ;;
  test) "$JAVA" -cp "$OUT" turnstile.TurnstileTest ;;
  bench) "$JAVA" -cp "$OUT" turnstile.Bench ;;
  experiment) "$JAVA" -cp "$OUT" turnstile.SequenceStrideExperiment ;;
  *) echo "usage: $0 [build|test|bench|experiment]" >&2
     exit 64 ;;
esac
