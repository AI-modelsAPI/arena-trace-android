#!/usr/bin/env bash
# Rebuilds the offline compile-check harness + toolchain into .scratch/
# (untracked; git never touches it). Safe to re-run: every step is skipped
# when its product already exists. Requires: python3+pip, gh (authenticated),
# node (only for the JS smoke tests), curl.
#
# Usage:  bash scripts/localtest/bootstrap.sh
# Then:   bash .scratch/localtest/run.sh        (full: compile + tests)
#         bash .scratch/localtest/compilecheck.sh
set -euo pipefail
cd "$(dirname "$0")/../.."
ROOT=$PWD
TOOLS="$ROOT/.scratch/tools"
LTEST="$ROOT/.scratch/localtest"
mkdir -p "$TOOLS" "$LTEST/shim" "$LTEST/stubs" "$LTEST/out"

# Untracked dirs must not be committed; .git/info/exclude survives only per
# sandbox run, so re-add every time.
grep -qxF '.scratch/' .git/info/exclude 2>/dev/null || printf '.scratch/\n' >> .git/info/exclude
grep -qxF 'image-search/' .git/info/exclude 2>/dev/null || printf 'image-search/\n' >> .git/info/exclude

# ---- harness sources (tracked) → .scratch (untracked)
cp scripts/localtest/src/shim/*.kt "$LTEST/shim/"
cp scripts/localtest/src/stubs/*.kt "$LTEST/stubs/"
cp scripts/localtest/src/gen_r.py "$LTEST/gen_r.py"
cp scripts/localtest/run.sh scripts/localtest/compilecheck.sh "$LTEST/"
chmod +x "$LTEST/run.sh" "$LTEST/compilecheck.sh"

# env.sh is generated with the absolute repo path baked in
cat > "$LTEST/env.sh" <<EOF
ROOT=$ROOT
TOOLS=$TOOLS
LTEST=$LTEST
JAVA="$TOOLS/py/jdk4py/java-runtime/bin/java"
KERNEL="$TOOLS/py/run_kotlin_kernel/jars/kotlin-jupyter-kernel-0.19.0-944-all.jar"
STDLIB="$TOOLS/py/run_kotlin_kernel/jars/kotlin-stdlib-2.3.10-RC.jar"
ANDROID_JAR="$TOOLS/android-34.jar"
AAR_DIR="$TOOLS/aar"
AARS=\$(find "\$AAR_DIR" -maxdepth 1 -name '*.jar' | sort | tr '\n' ':')
COROUTINES="$TOOLS/coroutines.jar"
CP_BASE="\$ANDROID_JAR:\$STDLIB:\$COROUTINES:\$AARS"
GEN_R="$LTEST/gen_r.py"
RKT="$LTEST/out/R.kt"
SRC_APP="$ROOT/app/src/main/java"
SRC_RES="$ROOT/app/src/main/res"
SRC_TEST="$ROOT/app/src/test/java"
EOF

# ---- toolchain downloads (skip when present)
if [ ! -x "$TOOLS/py/jdk4py/java-runtime/bin/java" ]; then
  echo "==> pip: jdk4py + kotlin-jupyter-kernel"
  pip install --quiet --target "$TOOLS/py" jdk4py kotlin-jupyter-kernel
fi
if [ ! -s "$TOOLS/android-34.jar" ]; then
  echo "==> android-34 platform jar"
  gh api -H "Accept: application/vnd.github.raw" \
    repos/Sable/android-platforms/git/blobs/923bafccf73aef996495c559e919ad8be3cabd58 \
    > "$TOOLS/android-34.jar"
fi
AAR_COUNT=$(find "$TOOLS/aar" -maxdepth 1 -name '*.jar' 2>/dev/null | wc -l)
if [ "$AAR_COUNT" -lt 40 ]; then
  echo "==> androidx/material jars"
  OUT="$TOOLS/aar" python3 scripts/localtest/src/fetch_aar.py | tail -3
fi
if [ ! -s "$TOOLS/coroutines.jar" ]; then
  echo "==> kotlinx-coroutines (extracted from the kernel's all-jar)"
  mkdir -p "$TOOLS/coroutines-extract"
  unzip -o -q "$TOOLS/py/run_kotlin_kernel/jars/kotlin-jupyter-kernel-0.19.0-944-all.jar" \
    'kotlinx/coroutines/*' 'META-INF/kotlinx-coroutines-core.kotlin_module' \
    -d "$TOOLS/coroutines-extract"
  (cd "$TOOLS/coroutines-extract" && zip -q -r "$TOOLS/coroutines.jar" .)
fi
echo "BOOTSTRAP READY"
