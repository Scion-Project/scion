#!/usr/bin/env bash
# Cross-compiles the Inferno QEMU fork (and its dependencies) for Android arm64,
# tuned for Tensor G5, and drops the result into app/src/main/jniLibs.
#
# Usage: native/build-android.sh [deps|qemu|all]   (default: all)
#
# Env overrides:
#   ANDROID_NDK_HOME  NDK root (default: newest under ~/Library/Android/sdk/ndk)
#   API               Android API level to target (default: 31)
#   TUNE_CFLAGS       CPU tuning flags (default: Tensor G5 / Cortex-X4 tuning)
#   JOBS              parallel jobs
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
NATIVE="$ROOT/native"
WORK="$NATIVE/build"          # downloads + build trees (gitignored)
PREFIX="$WORK/sysroot"        # static deps install here
SRC_QEMU="$ROOT/third_party/inferno"
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

cat > "$WORK/android-arm64.cross" <<EOF
[binaries]
c = '$CC'
cpp = '$CXX'
ar = '$AR'
nm = '$NM'
strip = '$STRIP'
pkg-config = '$WORK/pkg-config'
cmake = '$(command -v cmake)'

[host_machine]
system = 'android'
cpu_family = 'aarch64'
cpu = 'aarch64'
endian = 'little'

[built-in options]
c_args = [$(printf "'%s'," $OPT_CFLAGS -I"$PREFIX/include")]
cpp_args = [$(printf "'%s'," $OPT_CFLAGS -I"$PREFIX/include")]
c_link_args = ['-L$PREFIX/lib']
cpp_link_args = ['-L$PREFIX/lib']
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
EOF

fetch() { # url sha256 -> path of the downloaded file
  local url="$1" sum="$2" out="$WORK/dl/${1##*/}"
  [[ -f "$out" ]] || curl -fL --retry 3 -o "$out" "$url"
  echo "$sum  $out" | shasum -a 256 -c - >/dev/null || { echo "checksum mismatch: $out" >&2; exit 1; }
  echo "$out"
}

unpack() { # archive -> fresh source dir under $WORK/src
  local dir="$WORK/src/$2"
  rm -rf "$dir" && mkdir -p "$dir"
  tar -xf "$1" -C "$dir" --strip-components=1
  echo "$dir"
}

autotools_env() {
  export CFLAGS="$OPT_CFLAGS -I$PREFIX/include" LDFLAGS="-L$PREFIX/lib"
}

build_gmp() {
  local s; s="$(unpack "$(fetch https://ftpmirror.gnu.org/gnu/gmp/gmp-6.3.0.tar.xz \
    a3c2b80201b89e68616f4ad30bc66aee4927c3ce50e33929ca819d5c43538898)" gmp)"
  (cd "$s" && autotools_env && ./configure --host="$TRIPLE" --prefix="$PREFIX" \
     --enable-static --disable-shared --disable-cxx && make -j"$JOBS" && make install)
}

build_nettle() {
  local s; s="$(unpack "$(fetch https://ftpmirror.gnu.org/gnu/nettle/nettle-3.10.2.tar.gz \
    fe9ff51cb1f2abb5e65a6b8c10a92da0ab5ab6eaf26e7fc2b675c45f1fb519b5)" nettle)"
  (cd "$s" && autotools_env && ./configure --host="$TRIPLE" --prefix="$PREFIX" --libdir="$PREFIX/lib" \
     --enable-static --disable-shared --disable-documentation --disable-openssl \
     --enable-arm64-crypto --with-include-path="$PREFIX/include" --with-lib-path="$PREFIX/lib" \
     && make -j"$JOBS" && make install)
}

build_tasn1() {
  local s; s="$(unpack "$(fetch https://ftpmirror.gnu.org/libtasn1/libtasn1-4.21.0.tar.gz \
    1d8a444a223cc5464240777346e125de51d8e6abf0b8bac742ac84609167dc87)" libtasn1)"
  (cd "$s" && autotools_env && ./configure --host="$TRIPLE" --prefix="$PREFIX" --libdir="$PREFIX/lib" \
     --enable-static --disable-shared --disable-doc --disable-valgrind-tests \
     && make -j"$JOBS" -C lib && make -C lib install)
}

build_lzfse() {
  local s; s="$(unpack "$(fetch https://github.com/lzfse/lzfse/archive/refs/tags/lzfse-1.0.tar.gz \
    cf85f373f09e9177c0b21dbfbb427efaedc02d035d2aade65eb58a3cbf9ad267)" lzfse)"
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
  local s; s="$(unpack "$(fetch https://download.gnome.org/sources/glib/2.88/glib-2.88.4.tar.xz \
    25fd169f3b2f91fe2391ad297883ce0fcf30c69fc789ffd1c9672d1332b1c5a8)" glib)"
  meson_dep "$s" -Dtests=false -Dintrospection=disabled -Dnls=disabled -Dselinux=disabled \
    -Dxattr=false -Dlibmount=disabled -Dman-pages=disabled -Ddocumentation=false \
    -Dsysprof=disabled -Dlibelf=disabled -Dglib_debug=disabled -Dglib_assert=false \
    -Dforce_fallback_for=pcre2,libffi,proxy-libintl
}

build_pixman() {
  local s; s="$(unpack "$(fetch https://www.cairographics.org/releases/pixman-0.46.4.tar.xz \
    a098c33924754ad43f981b740f6d576c70f9ed1006e12221b1845431ebce1239)" pixman)"
  meson_dep "$s" -Dtests=disabled -Ddemos=disabled -Dgtk=disabled -Dlibpng=disabled \
    -Da64-neon=enabled -Darm-simd=disabled -Dneon=disabled
}

build_deps() {
  build_gmp
  build_nettle
  build_tasn1
  build_lzfse
  build_glib
  build_pixman
}

configure_qemu() { # builddir
  # Inferno dev dropped QEMU's ./configure wrapper; drive meson directly.
  # It only builds aarch64-softmmu now, so there is no target list to pass.
  # dev pulls libyuv (CMake), mlib and keycodemapdb in as meson subprojects.
  env -u AR -u NM -u RANLIB -u STRIP -u OBJCOPY -u LD CC="$WORK/host-cc" CXX="$WORK/host-c++" \
    meson setup "$1" "$SRC_QEMU" --cross-file "$WORK/android-arm64.cross" \
    --buildtype=release --wrap-mode=default -Dwerror=false -Ddebug=false \
    -Ddefault_library=static -Dtools=disabled -Dqom_cast_debug=false \
    -Dkvm=disabled -Dhvf=disabled -Dwhpx=disabled -Dtcg=enabled \
    -Dvnc=enabled -Dvnc_jpeg=disabled -Dpng=disabled -Dvnc_sasl=disabled \
    -Dgnutls=disabled -Dsdl=disabled -Dsdl_image=disabled -Dgtk=disabled -Dvte=disabled \
    -Dcurses=disabled -Dcocoa=disabled -Dslirp=disabled -Dlibssh=disabled \
    -Dzstd=disabled -Dlzo=disabled -Dsnappy=disabled -Dbzip2=disabled -Dcurl=disabled \
    -Dlinux_aio=disabled -Dlinux_io_uring=disabled -Dlibudev=disabled -Dlibusb=disabled \
    -Dusb_redir=disabled -Dseccomp=disabled -Dcap_ng=disabled -Dxkbcommon=disabled \
    -Dvde=disabled -Dalsa=disabled -Doss=disabled -Dpa=disabled -Dpipewire=disabled \
    -Djack=disabled -Dsndio=disabled -Dfuse=disabled -Dmultiprocess=disabled \
    -Dvalgrind=disabled -Dlibkeyutils=disabled -Dauth_pam=disabled -Dbrlapi=disabled
}

build_qemu() {
  [[ -f "$SRC_QEMU/meson.build" ]] || { echo "run: git submodule update --init" >&2; exit 1; }
  local b="$WORK/qemu"
  # Reconfigure only on the first build or with FRESH=1; otherwise rebuild incrementally.
  if [[ -n "${FRESH:-}" || ! -f "$b/build.ninja" ]]; then
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
}

case "${1:-all}" in
  deps) build_deps ;;
  qemu) build_qemu ;;
  all) build_deps; build_qemu ;;
  gmp|nettle|tasn1|lzfse|glib|pixman) "build_$1" ;;
  *) echo "usage: $0 [deps|qemu|all|gmp|nettle|tasn1|lzfse|glib|pixman]" >&2; exit 2 ;;
esac
