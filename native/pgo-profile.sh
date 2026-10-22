#!/usr/bin/env bash
# Generates a PGO profile for the Android QEMU from the iOS 14.8 restore boot
# (docs/PERF.md, "Compiler and linker options"):
#   1. builds an instrumented QEMU (native/build/qemu-pgo-gen),
#   2. boots the restore environment RUNS times on a device with tools/restore_bench.py,
#   3. pulls the .profraw counters and merges them with the NDK's llvm-profdata.
#
# Usage: native/pgo-profile.sh OUT.profdata --serial <adb serial> --kernel ... --dtb ... \
#          --trustcache ... --ramdisk ... --sep-rom ... --sep-fw ...
# Everything after OUT is passed to restore_bench.py (your own kit files; see
# docs/PERF.md). Then: QEMU_PGO_PROFILE=OUT.profdata native/build-android.sh qemu
#
# The profile holds only QEMU's own branch and call counts, keyed by QEMU function
# names. Static functions are keyed by their path relative to the build tree, so
# use it with the standard layout (third_party/inferno, native/build/qemu*).
#
# Env: RUNS (default 3), SMP (default 4), DEVICE_DIR (default /data/local/tmp/qemu-pgo).
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
[[ $# -ge 2 ]] || { sed -n '2,20p' "$0" >&2; exit 2; }
OUT="$1"; shift
RUNS="${RUNS:-3}" SMP="${SMP:-4}" DEVICE_DIR="${DEVICE_DIR:-/data/local/tmp/qemu-pgo}"
SERIAL=""
args=("$@")
for ((i = 0; i < ${#args[@]}; i++)); do
  [[ "${args[i]}" == --serial ]] && SERIAL="${args[i + 1]:-}"
done
[[ -n "$SERIAL" ]] || { echo "pass --serial <adb serial>" >&2; exit 2; }
PROF_DIR="$DEVICE_DIR/profiles"

QEMU_PGO_GENERATE="$PROF_DIR" NO_QEMU_LIB=1 "$ROOT/native/build-android.sh" qemu
adb -s "$SERIAL" shell "rm -rf $PROF_DIR && mkdir -p $PROF_DIR"
# Counters are written when QEMU exits normally; restore_bench stops it with SIGINT.
python3 "$ROOT/tools/restore_bench.py" --runs "$RUNS" --smp "$SMP" --device-dir "$DEVICE_DIR" \
  --qemu "$ROOT/native/build/qemu-pgo-gen/qemu-system-aarch64" "$@"

raw="$(mktemp -d)"
trap 'rm -rf "$raw"' EXIT
adb -s "$SERIAL" pull -q "$PROF_DIR/." "$raw/"
ls "$raw"/*.profraw >/dev/null 2>&1 || { echo "no .profraw from the device (did QEMU exit normally?)" >&2; exit 1; }
"$ROOT/native/build-android.sh" pgo-merge "$OUT" "$raw"/*.profraw
echo "wrote $OUT; build with: QEMU_PGO_PROFILE=$OUT native/build-android.sh qemu"
