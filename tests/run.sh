#!/bin/bash
# Host tests: pure-Java core/daemon + tests with plain javac, native tests with host gcc.
set -e
cd "$(dirname "$0")/.."
OUT=build/hosttest
rm -rf "$OUT"; mkdir -p "$OUT"
SRCS=$(find src/com/clean/stickman/core src/com/clean/stickman/daemon tests/java -name '*.java' 2>/dev/null)
javac --release 8 -nowarn -Xlint:none -d "$OUT" $SRCS
CLASSES=$(cd tests/java && find . -name '*Test.java' | sed 's#^\./##; s#\.java$##; s#/#.#g' | sort)
java -cp "$OUT" T $CLASSES
for f in native/test_*.c; do
  [ -e "$f" ] || continue
  n=$(basename "$f" .c)
  gcc -O0 -Wall -o "build/$n" "$f" native/layers.c
  "build/$n"
done
tools/api22check.sh
echo "ALL TESTS OK"
