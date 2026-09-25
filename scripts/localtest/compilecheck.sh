#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/../.."
# shellcheck disable=SC1091
source .scratch/localtest/env.sh
mkdir -p "$LTEST/out"
python3 "$GEN_R" "$SRC_RES" "$RKT" >/dev/null
MAP="$LTEST/out/main.map"
find "$SRC_APP" -name '*.kt' | sort > "$MAP"
ls "$LTEST"/shim/*.kt "$LTEST"/stubs/*.kt 2>/dev/null | sort >> "$MAP"
echo "$RKT" >> "$MAP"
"$JAVA" -cp "$KERNEL" org.jetbrains.kotlin.cli.jvm.K2JVMCompiler \
  -no-stdlib -no-reflect -language-version 2.0 -api-version 2.0 -jvm-target 17 -no-jdk \
  -cp "$CP_BASE" \
  -d "$LTEST/out/main.jar" \
  @"$MAP" 2>&1 | grep -v "^warning: language version 2.0 is deprecated" | tail -50
echo COMPILE_OK
