#!/bin/bash
# libgfxstream_backend.so for the APK (app/src/main/jniLibs, which git ignores): gfxstream's host
# renderer for Android arm64, which the AVF GPU (SCGPU, app/src/main/cpp/scgpu.c) loads at run time.
# It is built by hackios-xnu-kernel's tools/gfxstream/mk-host-android.sh (gfxstream at the commit the
# guest's encoder is generated from, with android-host.patch); this runs that and copies the result.
#
#   native/fetch-gfxstream.sh                 HAKIOS=path/to/hackios-xnu-kernel (default: a sibling of this repo)
set -euo pipefail
ROOT=$(cd "$(dirname "$0")/.." && pwd)
HAKIOS=${HAKIOS:-$(dirname "$ROOT")/hackios-xnu-kernel}
[ -x "$HAKIOS/tools/gfxstream/mk-host-android.sh" ] || { echo "fetch-gfxstream.sh: no hackios-xnu-kernel at $HAKIOS (set HAKIOS=)" >&2; exit 1; }
"$HAKIOS/tools/gfxstream/mk-host-android.sh"
mkdir -p "$ROOT/app/src/main/jniLibs/arm64-v8a"
cp "$HAKIOS/xnu-deps/build/gfxstream-host-android/libgfxstream_backend.so" "$ROOT/app/src/main/jniLibs/arm64-v8a/"
ls -l "$ROOT/app/src/main/jniLibs/arm64-v8a/libgfxstream_backend.so"
