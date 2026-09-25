#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/../.."
# shellcheck disable=SC1091
source .scratch/localtest/env.sh
mkdir -p "$LTEST/out"
python3 "$GEN_R" "$SRC_RES" "$RKT" >/dev/null

MAIN_LIST="$LTEST/out/main.map"
find "$SRC_APP" -name '*.kt' | sort > "$MAIN_LIST"
ls "$LTEST"/shim/*.kt "$LTEST"/stubs/*.kt 2>/dev/null | sort >> "$MAIN_LIST"
echo "$RKT" >> "$MAIN_LIST"

"$JAVA" -cp "$KERNEL" org.jetbrains.kotlin.cli.jvm.K2JVMCompiler \
  -no-stdlib -no-reflect -language-version 2.0 -api-version 2.0 -jvm-target 17 -no-jdk \
  -cp "$CP_BASE" \
  -d "$LTEST/out/main.jar" \
  @"$MAIN_LIST" 2>&1 | grep -v "^warning: language version 2.0 is deprecated" | tail -50

TEST_LIST="$LTEST/out/test.map"
find "$SRC_TEST" -name '*.kt' | sort > "$TEST_LIST"
echo "$LTEST/shim/junit.kt" >> "$TEST_LIST"
echo "$LTEST/shim/orgjson.kt" >> "$TEST_LIST"
echo "$LTEST/shim/runner.kt" >> "$TEST_LIST"
TEST_CP="$LTEST/out/main.jar:$CP_BASE"
"$JAVA" -cp "$KERNEL" org.jetbrains.kotlin.cli.jvm.K2JVMCompiler \
  -no-stdlib -no-reflect -language-version 2.0 -api-version 2.0 -jvm-target 17 -no-jdk \
  -cp "$TEST_CP" \
  -d "$LTEST/out/test.jar" \
  @"$TEST_LIST" 2>&1 | grep -v "^warning: language version 2.0 is deprecated" | tail -50

RUN_CP="$LTEST/out/test.jar:$LTEST/out/main.jar:$CP_BASE"
TESTS=$(grep -rl "org.junit.Test" "$SRC_TEST" --include='*.kt' | while read -r f; do \
  pkg=$(awk '/^package /{print $2; exit}' "$f"); pkg=${pkg%$'\r'}; \
  cls=$(awk '/^class [A-Za-z]/ {print $2; exit}' "$f"); cls=${cls%%(*}; cls=${cls%%:*}; \
  if [ -n "$pkg" ] && [ -n "$cls" ]; then echo "$pkg.$cls"; fi; \
done)
"$JAVA" -cp "$RUN_CP" RunnerKt $TESTS
