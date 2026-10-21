#!/usr/bin/env bash
# Cross-compiles the Inferno QEMU fork (and its dependencies) for Android arm64,
# tuned for Tensor G5, and drops the result into app/src/main/jniLibs.
#
# Usage: native/build-android.sh [deps|qemu|qemu-lib|all]   (default: all)
#
# `qemu` builds the executable and, unless NO_QEMU_LIB=1, also the in-process
# variant: QEMU as a shared library plus the JNI shim that calls it.
#
# Env overrides:
#   ANDROID_NDK_HOME  NDK root (default: newest under ~/Library/Android/sdk/ndk)
#   API               Android API level to target (default: 31)
#   TUNE_CFLAGS       CPU tuning flags (default: Tensor G5 / Cortex-X4 tuning)
#   INFERNO_SRC       Inferno source tree (default: third_party/inferno)
#   JOBS              parallel jobs
#
# Compiler options for QEMU's own code (the deps always use OPT_CFLAGS). See
# docs/PERF.md, "Compiler and linker options", for what each one measured.
#   QEMU_OPT          optimisation level (default: -O2)
#   LTO               thin | off: ThinLTO for QEMU, linked by the NDK's lld (default: off)
#   PGO_PROFILE       merged .profdata to optimise with (-fprofile-use); see `pgo-merge`
#   PGO_GENERATE      device directory: build an instrumented QEMU into $WORK/qemu-pgo-gen
#                     that writes .profraw files there (not installed into the app)
# A build tree is reconfigured by itself when these (or the tuning) change.
#
#   native/build-android.sh pgo-merge OUT.profdata RAW...  merges .profraw files
#   with the NDK's llvm-profdata (it must match the NDK's clang).
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
NATIVE="$ROOT/native"
WORK="$NATIVE/build"          # downloads + build trees (gitignored)
PREFIX="$WORK/sysroot"        # static deps install here
SRC_QEMU="${INFERNO_SRC:-$ROOT/third_party/inferno}"  # any Inferno dev checkout
JNI_OUT="$ROOT/app/src/main/jniLibs/arm64-v8a"
ASSET_OUT="$ROOT/app/src/main/assets/qemu-data"

API="${API:-31}"
JOBS="${JOBS:-$(sysctl -n hw.logicalcpu 2>/dev/null || nproc)}"

# Tensor G5: 1x Cortex-X4, 5x Cortex-A725, 2x Cortex-A520 (all Armv9.2).
# We target the Armv8.2 baseline + extensions every core has, and tune for the
# X4/A725 that the vCPU threads get pinned to. SVE is deliberately not enabled
# at compile time; TCG's host backend does its own runtime feature detection.
TUNE_CFLAGS="${TUNE_CFLAGS:--march=armv8.2-a+crypto+dotprod+rcpc -mtune=cortex-x4}"
OPT_CFLAGS="-O2 -fPIC -ffunction-sections -fdata-sections $TUNE_CFLAGS"

QEMU_OPT="${QEMU_OPT:--O2}"
LTO="${LTO:-off}"
PGO_PROFILE="${PGO_PROFILE:-}"
PGO_GENERATE="${PGO_GENERATE:-}"
case "$LTO" in thin|off) ;; *) echo "LTO must be thin or off" >&2; exit 2 ;; esac
# meson's release buildtype adds -O3, but these come later on the command line and win.
QEMU_CFLAGS="$QEMU_OPT -fPIC -ffunction-sections -fdata-sections $TUNE_CFLAGS"
# The -O level on the link line sets lld's LTO optimisation level.
QEMU_LDFLAGS="$QEMU_OPT"
if [[ -n "$PGO_GENERATE" ]]; then
  # Atomic counter updates: several vCPU threads run the same code at once.
  QEMU_CFLAGS+=" -fprofile-generate=$PGO_GENERATE -fprofile-update=atomic"
  QEMU_LDFLAGS+=" -fprofile-generate=$PGO_GENERATE"
fi
if [[ -n "$PGO_PROFILE" ]]; then
  [[ -f "$PGO_PROFILE" ]] || { echo "PGO_PROFILE: no such file: $PGO_PROFILE" >&2; exit 1; }
  PGO_PROFILE="$(cd "$(dirname "$PGO_PROFILE")" && pwd)/$(basename "$PGO_PROFILE")"
  # A profile from an older Inferno still helps for the code that didn't change;
  # functions that did are compiled without it, so don't warn about each one.
  QEMU_CFLAGS+=" -fprofile-use=$PGO_PROFILE -Wno-profile-instr-out-of-date -Wno-profile-instr-unprofiled"
  QEMU_CFLAGS+=" -Wno-backend-plugin"
fi

if [[ -z "${ANDROID_NDK_HOME:-}" ]]; then
  ANDROID_NDK_HOME="$(ls -d "$HOME"/Library/Android/sdk/ndk/* 2>/dev/null | sort -V | tail -1)"
fi
[[ -d "$ANDROID_NDK_HOME" ]] || { echo "NDK not found; set ANDROID_NDK_HOME" >&2; exit 1; }
TC="$(ls -d "$ANDROID_NDK_HOME"/toolchains/llvm/prebuilt/* | head -1)"
TRIPLE="aarch64-linux-android"
export CC="$TC/bin/$TRIPLE$API-clang"
export CXX="$TC/bin/$TRIPLE$API-clang++"
export AR="$TC/bin/llvm-ar" NM="$TC/bin/llvm-nm" RANLIB="$TC/bin/llvm-ranlib"
export STRIP="$TC/bin/llvm-strip" OBJCOPY="$TC/bin/llvm-objcopy" LD="$TC/bin/ld.lld"

for tool in meson ninja pkg-config cmake git curl python3; do
  command -v "$tool" >/dev/null || { echo "missing host tool: $tool" >&2; exit 1; }
done

mkdir -p "$WORK/dl" "$PREFIX" "$JNI_OUT" "$ASSET_OUT/keymaps"

# A few configure steps need a compiler for the build machine. On macOS the
# default `cc` can pair Xcode's linker with a newer Command Line Tools SDK and
# fail to link anything, so probe for a combination that actually works.
pick_host_cc() {
  local probe="$WORK/host-probe" dev
  printf 'int main(void){return 0;}\n' > "$probe.c"
  for dev in "" /Library/Developer/CommandLineTools "$(xcode-select -p 2>/dev/null)"; do
    if DEVELOPER_DIR="$dev" /usr/bin/cc "$probe.c" -o "$probe" 2>/dev/null && "$probe"; then
      printf '#!/bin/sh\n%sexec /usr/bin/%s "$@"\n' "${dev:+DEVELOPER_DIR=$dev }" cc > "$WORK/host-cc"
      printf '#!/bin/sh\n%sexec /usr/bin/%s "$@"\n' "${dev:+DEVELOPER_DIR=$dev }" c++ > "$WORK/host-c++"
      chmod +x "$WORK/host-cc" "$WORK/host-c++"
      return
    fi
  done
  echo "no working host C compiler found" >&2; exit 1
}
pick_host_cc
export CC_FOR_BUILD="$WORK/host-cc"

# Only ever resolve against our own static sysroot, and always ask for the
# static link line (Libs.private), since nothing here ships as a .so.
cat > "$WORK/pkg-config" <<EOF
#!/bin/sh
export PKG_CONFIG_LIBDIR="$PREFIX/lib/pkgconfig:$PREFIX/share/pkgconfig"
export PKG_CONFIG_PATH=
exec pkg-config --static "\$@"
EOF
chmod +x "$WORK/pkg-config"
export PKG_CONFIG="$WORK/pkg-config"

cross_file() { # system cflags ldflags
  cat <<EOF
[binaries]
c = '$CC'
cpp = '$CXX'
ar = '$AR'
nm = '$NM'
strip = '$STRIP'
pkg-config = '$WORK/pkg-config'
cmake = '$(command -v cmake)'

[host_machine]
system = '$1'
cpu_family = 'aarch64'
cpu = 'aarch64'
endian = 'little'

[built-in options]
c_args = [$(printf "'%s'," $2 -I"$PREFIX/include")]
cpp_args = [$(printf "'%s'," $2 -I"$PREFIX/include")]
c_link_args = [$(printf "'%s'," -L"$PREFIX/lib" $3)]
cpp_link_args = [$(printf "'%s'," -L"$PREFIX/lib" $3)]
default_library = 'static'
prefix = '$PREFIX'
libdir = 'lib'

[properties]
needs_exe_wrapper = true

# For CMake-based subprojects (libyuv): CMake's Android platform module needs
# to be told where the NDK is and which ABI/API to target.
[cmake]
CMAKE_ANDROID_NDK = '$ANDROID_NDK_HOME'
CMAKE_ANDROID_ARCH_ABI = 'arm64-v8a'
CMAKE_SYSTEM_VERSION = '$API'
# Never let CMake find host (e.g. Homebrew) headers or libraries: libyuv's
# find_package(JPEG) picked up /opt/homebrew/lib/libjpeg.a and exported
# /opt/homebrew/include to QEMU, where a Homebrew nettle 4 header shadowed our
# nettle 3.10 (trng.c then called a symbol our libnettle lacks). Search only
# our sysroot (+ the NDK's) for libs/headers/packages; host programs are fine.
CMAKE_FIND_ROOT_PATH = '$PREFIX'
CMAKE_FIND_ROOT_PATH_MODE_LIBRARY = 'ONLY'
CMAKE_FIND_ROOT_PATH_MODE_INCLUDE = 'ONLY'
CMAKE_FIND_ROOT_PATH_MODE_PACKAGE = 'ONLY'
CMAKE_FIND_ROOT_PATH_MODE_PROGRAM = 'NEVER'
# libyuv's JPEG paths aren't used (vnc_jpeg is disabled); don't look for it.
CMAKE_DISABLE_FIND_PACKAGE_JPEG = 'TRUE'
EOF
}
cross_file android "$OPT_CFLAGS" "" > "$WORK/android-arm64.cross"

# QEMU itself is built as a "linux" host: bionic is Linux, and QEMU only enables
# its Linux code paths (gettid, tgkill, sysfs, ...) when meson reports linux.
cross_file linux "$QEMU_CFLAGS" "$QEMU_LDFLAGS" > "$WORK/android-arm64-qemu.cross"

download() { # url out; never leaves a partial file behind
  curl -fL --retry 5 --retry-all-errors --retry-delay 3 -o "$2" "$1" || { rm -f "$2"; return 1; }
}

fetch() { # url sha256 -> path of the downloaded file
  local url="$1" sum="$2" out="$WORK/dl/${1##*/}" rel
  if [[ ! -f "$out" ]] && ! download "$url" "$out"; then
    # ftpmirror.gnu.org redirects to a random mirror, and some mirrors have
    # broken TLS. Fall back to the GNU master site (same file, same checksum).
    [[ "$url" == https://ftpmirror.gnu.org/* ]] || { echo "download failed: $url" >&2; return 1; }
    rel="${url#https://ftpmirror.gnu.org/}"; rel="${rel#gnu/}"
    echo "mirror failed, retrying from ftp.gnu.org: $rel" >&2
    download "https://ftp.gnu.org/gnu/$rel" "$out" || { echo "download failed: $url" >&2; return 1; }
  fi
  echo "$sum  $out" | shasum -a 256 -c - >/dev/null || { echo "checksum mismatch: $out" >&2; rm -f "$out"; return 1; }
  echo "$out"
}

unpack() { # archive name -> fresh source dir under $WORK/src
  local dir="$WORK/src/$2"
  { rm -rf "$dir" && mkdir -p "$dir" && tar -xf "$1" -C "$dir" --strip-components=1; } \
    || { echo "unpack failed: $1" >&2; return 1; }
  echo "$dir"
}

# Command substitutions don't inherit `set -e` (and macOS bash 3.2 has no
# inherit_errexit), so check each step explicitly: a failed download must stop
# the build here, not later at `./configure: No such file or directory`.
source_dir() { # url sha256 name -> fresh, unpacked source dir
  local tb
  tb="$(fetch "$1" "$2")" || return 1
  unpack "$tb" "$3"
}

autotools_env() {
  export CFLAGS="$OPT_CFLAGS -I$PREFIX/include" LDFLAGS="-L$PREFIX/lib"
}

build_gmp() {
  local s; s="$(source_dir https://ftpmirror.gnu.org/gnu/gmp/gmp-6.3.0.tar.xz \
    a3c2b80201b89e68616f4ad30bc66aee4927c3ce50e33929ca819d5c43538898 gmp)" || exit 1
  (cd "$s" && autotools_env && ./configure --host="$TRIPLE" --prefix="$PREFIX" \
     --enable-static --disable-shared --disable-cxx && make -j"$JOBS" && make install)
}

build_nettle() {
  local s; s="$(source_dir https://ftpmirror.gnu.org/gnu/nettle/nettle-3.10.2.tar.gz \
    fe9ff51cb1f2abb5e65a6b8c10a92da0ab5ab6eaf26e7fc2b675c45f1fb519b5 nettle)" || exit 1
  (cd "$s" && autotools_env && ./configure --host="$TRIPLE" --prefix="$PREFIX" --libdir="$PREFIX/lib" \
     --enable-static --disable-shared --disable-documentation --disable-openssl \
     --enable-arm64-crypto --with-include-path="$PREFIX/include" --with-lib-path="$PREFIX/lib" \
     && make -j"$JOBS" && make install)
}

build_tasn1() {
  local s; s="$(source_dir https://ftpmirror.gnu.org/libtasn1/libtasn1-4.21.0.tar.gz \
    1d8a444a223cc5464240777346e125de51d8e6abf0b8bac742ac84609167dc87 libtasn1)" || exit 1
  (cd "$s" && autotools_env && ./configure --host="$TRIPLE" --prefix="$PREFIX" --libdir="$PREFIX/lib" \
     --enable-static --disable-shared --disable-doc --disable-valgrind-tests \
     && make -j"$JOBS" -C lib && make -C lib install)
}

build_lz4() {
  local s; s="$(source_dir https://github.com/lz4/lz4/archive/refs/tags/v1.10.0.tar.gz \
    537512904744b35e232912055ccf8ec66d768639ff3abe5788d90d792ec5f48b lz4)" || exit 1
  (cd "$s/lib" && make -j"$JOBS" liblz4.a liblz4.pc CC="$CC" AR="$AR" CFLAGS="$OPT_CFLAGS" \
       PREFIX="$PREFIX" LIBDIR="$PREFIX/lib" \
     && make install BUILD_SHARED=no PREFIX="$PREFIX" LIBDIR="$PREFIX/lib")
}

build_lzfse() {
  local s; s="$(source_dir https://github.com/lzfse/lzfse/archive/refs/tags/lzfse-1.0.tar.gz \
    cf85f373f09e9177c0b21dbfbb427efaedc02d035d2aade65eb58a3cbf9ad267 lzfse)" || exit 1
  (cd "$s" && for f in src/lzfse_*.c src/lzvn_*.c; do
       "$CC" $OPT_CFLAGS -Isrc -c "$f" -o "${f%.c}.o"; done \
     && "$AR" rcs "$PREFIX/lib/liblzfse.a" src/lzfse_*.o src/lzvn_*.o \
     && cp src/lzfse.h "$PREFIX/include/")
}

meson_dep() { # srcdir [meson args...]
  local s="$1"; shift
  # In cross mode meson reads CC/CXX/AR from the environment for the *build*
  # machine, so point those at the host toolchain; the NDK comes from the cross file.
  env -u AR -u NM -u RANLIB -u STRIP -u OBJCOPY -u LD CC="$WORK/host-cc" CXX="$WORK/host-c++" \
    meson setup "$s/_build" "$s" --cross-file "$WORK/android-arm64.cross" \
    --buildtype=release --wrap-mode=default "$@"
  ninja -C "$s/_build" -j"$JOBS"
  meson install -C "$s/_build" --quiet
}

build_glib() {
  local s; s="$(source_dir https://download.gnome.org/sources/glib/2.88/glib-2.88.4.tar.xz \
    25fd169f3b2f91fe2391ad297883ce0fcf30c69fc789ffd1c9672d1332b1c5a8 glib)" || exit 1
  meson_dep "$s" -Dtests=false -Dintrospection=disabled -Dnls=disabled -Dselinux=disabled \
    -Dxattr=false -Dlibmount=disabled -Dman-pages=disabled -Ddocumentation=false \
    -Dsysprof=disabled -Dlibelf=disabled -Dglib_debug=disabled -Dglib_assert=false \
    -Dforce_fallback_for=pcre2,libffi,proxy-libintl
}

build_pixman() {
  local s; s="$(source_dir https://www.cairographics.org/releases/pixman-0.46.4.tar.xz \
    a098c33924754ad43f981b740f6d576c70f9ed1006e12221b1845431ebce1239 pixman)" || exit 1
  meson_dep "$s" -Dtests=disabled -Ddemos=disabled -Dgtk=disabled -Dlibpng=disabled \
    -Da64-neon=enabled -Darm-simd=disabled -Dneon=disabled
}

build_deps() {
  build_gmp
  build_nettle
  build_tasn1
  build_lz4
  build_lzfse
  build_glib
  build_pixman
}

# Fetch the meson wrap subprojects up front, with retries. libyuv comes from
# chromium.googlesource.com, which intermittently answers 503; inside
# `meson setup` that one failed clone kills the whole build. Already-present
# subprojects are skipped, so this is a no-op after the first success.
prefetch_wraps() {
  local i
  for i in 1 2 3 4 5; do
    meson subprojects download --sourcedir "$SRC_QEMU" && return 0
    echo "meson subprojects download failed (attempt $i/5); retrying in $((i * 15))s" >&2
    sleep $((i * 15))
  done
  return 1
}

configure_qemu() { # builddir [extra meson args...]
  local b="$1"; shift
  prefetch_wraps
  # Inferno dev dropped QEMU's ./configure wrapper; drive meson directly.
  # It only builds aarch64-softmmu now, so there is no target list to pass.
  # dev pulls libyuv (CMake), mlib and keycodemapdb in as meson subprojects.
  env -u AR -u NM -u RANLIB -u STRIP -u OBJCOPY -u LD CC="$WORK/host-cc" CXX="$WORK/host-c++" \
    meson setup "$b" "$SRC_QEMU" --cross-file "$WORK/android-arm64-qemu.cross" \
    --buildtype=release --wrap-mode=default -Dwerror=false -Ddebug=false \
    -Ddefault_library=static -Dtools=disabled -Dqom_cast_debug=false -Dtrace_backends=log \
    -Dkvm=disabled -Dhvf=disabled -Dwhpx=disabled -Dtcg=enabled \
    -Dvnc=enabled -Dvnc_jpeg=disabled -Dpng=disabled -Dvnc_sasl=disabled \
    -Dgnutls=disabled -Dsdl=disabled -Dsdl_image=disabled -Dgtk=disabled -Dvte=disabled \
    -Dcurses=disabled -Dcocoa=disabled -Dslirp=disabled -Dlibssh=disabled \
    -Dzstd=disabled -Dlzo=disabled -Dsnappy=disabled -Dbzip2=disabled -Dcurl=disabled \
    -Dlinux_aio=disabled -Dlinux_io_uring=disabled -Dlibudev=disabled -Dlibusb=disabled \
    -Dusb_redir=disabled -Dseccomp=disabled -Dcap_ng=disabled -Dxkbcommon=disabled \
    -Dvde=disabled -Dalsa=disabled -Doss=disabled -Dpa=disabled -Dpipewire=disabled \
    -Djack=disabled -Dsndio=disabled -Dfuse=disabled -Dmultiprocess=disabled \
    -Dvalgrind=disabled -Dlibkeyutils=disabled -Dauth_pam=disabled -Dbrlapi=disabled \
    $(lto_args) "$@"
  qemu_config_id "$@" > "$b/.android-config"
}

lto_args() {
  [[ "$LTO" == off ]] || echo "-Db_lto=true -Db_lto_mode=$LTO"
}

# Everything that decides how QEMU is compiled, so a build tree configured with
# other options (tuning, -O level, LTO, PGO) gets reconfigured instead of reused.
qemu_config_id() { # [extra meson args...]
  cat "$WORK/android-arm64-qemu.cross"
  echo "$(lto_args) $*"
}

needs_configure() { # builddir [extra meson args...]
  local b="$1"; shift
  [[ -n "${FRESH:-}" || ! -f "$b/build.ninja" ]] || ! qemu_config_id "$@" | cmp -s - "$b/.android-config"
}

build_qemu() {
  [[ -f "$SRC_QEMU/meson.build" ]] || { echo "run: git submodule update --init" >&2; exit 1; }
  local b="$WORK/qemu"
  if [[ -n "$PGO_GENERATE" ]]; then
    build_qemu_pgo_gen
    return
  fi
  # Reconfigure on the first build, with FRESH=1, or when the compiler options
  # changed; otherwise rebuild incrementally.
  if needs_configure "$b"; then
    rm -rf "$b"
    configure_qemu "$b"
  fi
  ninja -C "$b" -j"$JOBS" qemu-system-aarch64
  # Android only lets apps exec files the package manager extracted to
  # nativeLibraryDir, which only accepts lib*.so names.
  "$STRIP" -o "$JNI_OUT/libqemu-system-aarch64.so" "$b/qemu-system-aarch64"
  # Inferno dev deleted pc-bios/keymaps, but QEMU's VNC server still loads
  # en-us at start, so ship our vendored copy (GPL-2.0, from QEMU).
  cp "$NATIVE/keymaps/en-us" "$ASSET_OUT/keymaps/"
  echo "installed $JNI_OUT/libqemu-system-aarch64.so"
  [[ -n "${NO_QEMU_LIB:-}" ]] || build_qemu_lib
}

# The in-process variant (app setting "Run QEMU in-process"): the same QEMU as
# a shared library exporting qemu_android_main(), which QemuVmService calls
# through native/qemu_jni.c. It needs PIC static libraries, which would change
# the executable's code (PIC instead of PIE), so it gets its own build tree.
build_qemu_lib() {
  local b="$WORK/qemu-shared"
  if ! grep -q "system_shared_lib" "$SRC_QEMU/meson_options.txt"; then
    echo "skipping the in-process library: this Inferno has no system_shared_lib option yet" >&2
    return
  fi
  if needs_configure "$b" -Dsystem_shared_lib=true -Db_staticpic=true; then
    rm -rf "$b"
    configure_qemu "$b" -Dsystem_shared_lib=true -Db_staticpic=true
  fi
  ninja -C "$b" -j"$JOBS" libqemu-system-aarch64-shared.so
  "$STRIP" -o "$JNI_OUT/libqemu-system-aarch64-shared.so" "$b/libqemu-system-aarch64-shared.so"
  "$CC" $OPT_CFLAGS -shared -Wl,-z,max-page-size=16384 -Wl,--no-undefined -o "$JNI_OUT/libqemu_jni.so" \
    "$NATIVE/qemu_jni.c" -landroid
  echo "installed $JNI_OUT/libqemu-system-aarch64-shared.so and libqemu_jni.so"
}

# An instrumented QEMU for collecting a PGO profile (docs/PERF.md). It writes
# $PGO_GENERATE/default_<id>.profraw when it exits normally (SIGINT/SIGTERM/quit,
# not SIGKILL); runs of the same binary accumulate in that file.
build_qemu_pgo_gen() {
  local b="$WORK/qemu-pgo-gen"
  if needs_configure "$b"; then
    rm -rf "$b"
    configure_qemu "$b"
  fi
  ninja -C "$b" -j"$JOBS" qemu-system-aarch64
  echo "instrumented QEMU: $b/qemu-system-aarch64 (profiles go to $PGO_GENERATE on the device)"
}

pgo_merge() { # out.profdata raw.profraw...
  [[ $# -ge 2 ]] || { echo "usage: $0 pgo-merge OUT.profdata RAW.profraw..." >&2; exit 2; }
  "$TC/bin/llvm-profdata" merge -o "$@"
  "$TC/bin/llvm-profdata" show "$1" | tail -3
}

case "${1:-all}" in
  deps) build_deps ;;
  qemu) build_qemu ;;
  qemu-lib) build_qemu_lib ;;
  pgo-merge) shift; pgo_merge "$@" ;;
  all) build_deps; build_qemu ;;
  gmp|nettle|tasn1|lz4|lzfse|glib|pixman) "build_$1" ;;
  *) echo "usage: $0 [deps|qemu|qemu-lib|all|pgo-merge|gmp|nettle|tasn1|lz4|lzfse|glib|pixman]" >&2; exit 2 ;;
esac
