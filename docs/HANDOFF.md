# Handoff notes (2026-10-21)

State of the project, everything verified so far, and what to do next. Read
[ETHICS.md](ETHICS.md) first: its constraints (no Apple files, no
instructions for getting them, no automating firmware prep) apply to all
future work.

## Repositories

| Repo | Branch | Role |
|---|---|---|
| `dylanneve1/ios-on-pixel` | `main` | This repo: Android app, build scripts, tools, docs |
| `dylanneve1/Inferno` | `pixel` | Fork of `ChefKissInc/Inferno` with the Android/Tensor patches; submodule at `third_party/inferno` |
| `dylanneve1/qemu-ios` | — | Fork of `devos50/qemu-ios` (legacy iPod/iPhone emulator), for reference only |
| `dylanneve1/darwin-vm` | — | Fork of `jprx/darwin-vm` (boots modern iOS to a shell, no UI), for reference only |

`TrungNguyen1909/qemu-t8030` is in the same GitHub fork network as Inferno,
so `dylanneve1/Inferno` covers it too. That upstream is archived (2022).
Inferno is the active successor: last push Sep 2026, ~4k stars.

## Moving to Inferno `dev` (2026-10-21)

The submodule now tracks `dylanneve1/Inferno@pixel-dev`: upstream `dev` (314
commits past `master`) with our Android/Tensor patches rebased on top. `dev`
is a large restructure, and several things the old notes relied on are gone:

* **No `./configure`.** `native/build-android.sh` now calls `meson setup`
  directly with the NDK cross file. Every `-D` option it passes was checked
  against `dev`'s `meson_options.txt`.
* **No `pc-bios/keymaps`.** QEMU's VNC server still loads `en-us`, so a copy
  is vendored at `native/keymaps/en-us` and bundled as an asset.
* **No `ticket=` machine property.** Upstream now extracts the AP ticket from
  the firmware. The app no longer passes it and the VM folder no longer needs
  `root_ticket.der`.
* **Only `t8030` and `s8000` machines remain.** The generic `virt` machine and
  `-nodefaults` were deleted, so the "Linux guest on `-M virt`" test in step 1
  of *Not yet verified* no longer works with this binary. Test the RFB/touch
  path with a stock QEMU build, or with a real t8030 VM.
* **nettle >= 3.10 is required** (`hw/arm/sep/trng.c` uses the DRBG-CTR API).
  The Android build already uses 3.10.2. On Ubuntu 24.04 hosts (3.9.1),
  install 3.10 into a private prefix and point `PKG_CONFIG_PATH` at it.
  Lowering the meson requirement only moves the failure to load time.

Host (Linux x86_64) build of `pixel-dev` verified: links, `--version` shows
the de-branded string, `-M help` lists `t8030` and `s8000`.

## What's verified

| Item | Result |
|---|---|
| Static deps cross-compile (glib 2.88.4, pixman 0.46.4, nettle 3.10.2 + GMP 6.3.0, libtasn1 4.21.0, lzfse 1.0) | ✅ checksums verified against upstream-published hashes |
| Inferno `pixel` builds for `aarch64-linux-android31` (NDK r28) | ✅ 37 MB PIE; needs only libc/libm/libdl/libz; 16 KB-aligned |
| Gradle build (AGP 9.3.3, Gradle 9.7.1, JDK 21) | ✅ |
| QEMU runs from `nativeLibraryDir` in the app sandbox (Android 16 arm64 emulator) | ✅ `--version`, `-M help` lists `t8030` and `s8000` |
| TCG JIT works in an app domain (`runas_app`), both `split-wx=off` and `on` | ✅ translated and ran guest code for 4 s without faults |
| App → foreground service → exec QEMU as `untrusted_app` → log capture → exit code | ✅ |
| Bad kernel file gives a clean error (was a SIGSEGV) | ✅ after the `boot.c` patch |
| Main screen checks (binary present, VM folder complete, IMG4 header sniffing) | ✅ on a synthetic folder |
| `ipsw_probe.py` against real IPSWs over HTTP range requests | ✅ iOS 14.0 / 14.8 → supported, ~600 KiB fetched; iOS 27 → unsupported |

## Not yet verified

1. **RFB display + touch path.** I was about to test this when the session
   ended. The `attach` extra exists for exactly this:
   ```sh
   P=io.github.dylanneve1.iosonpixel
   Q=$(adb shell pm dump $P | grep -m1 legacyNativeLibraryDir= | sed 's/^[^=]*=//' | tr -d '\r')/arm64/libqemu-system-aarch64.so
   F=/data/data/$P/files
   # keymap must exist under -L (the service copies it from assets on first Start)
   adb shell "run-as $P sh -c 'cd $F && $Q -L $F/qemu-data -M virt -cpu max -m 128 -nodefaults \
       -device ramfb -display none -vnc unix:$F/vnc.sock \
       -monitor unix:$F/monitor.sock,server=on,wait=off'" &
   adb shell am start -n $P/.DisplayActivity --ez attach true
   ```
   Expect QEMU's "Guest has not initialized the display" console text.
2. **Real iOS 14 boot on a Pixel 10.** Needs a VM folder the user prepared on
   a Mac, plus `tools/package_vm.py --push`. Nothing about this was tested.
3. **Phantom-process killer** behaviour on a real Pixel during long runs.
4. **`QEMU_VCPU_AFFINITY`** on real Tensor G5 cores. It compiles; the effect
   hasn't been measured. Watch logcat for its `warn_report` if Android's
   cpuset refuses the mask.

## Architecture recap

* QEMU runs as a **child process** (`libqemu-system-aarch64.so` in
  `nativeLibraryDir`, `useLegacyPackaging = true` so it's extracted and
  executable). `VmService` is a `specialUse` foreground service that holds a
  partial wake lock and pipes stdout/stderr to `qemu.log` and to `VmState`.
* **Display:** `-display none -vnc unix:files/vnc.sock`, read by `rfb/RfbClient.kt`
  (RFB 3.8, no auth, raw + DesktopSize). Pixels are requested as LE 0x00RRGGBB
  and ORed to opaque ARGB.
* **Touch:** Inferno's `mt-spi` registers a legacy *absolute* mouse handler;
  left button = finger down. VNC pointer events map to it directly
  (`hw/arm/apple-silicon/mt-spi.c`, `apple_mt_spi_mouse_event`). Single
  touch only.
* **Buttons:** Inferno maps device buttons to F-keys (F2 ringer, F3/F4
  volume, F5 power, F6 home). The app sends `sendkey fN <holdms>` to the HMP
  monitor socket.
* **Keymap:** QEMU's VNC server refuses to start without the `en-us` keymap
  under `-L`. It's bundled in `assets/qemu-data/keymaps/` and copied to
  `files/qemu-data` at start.
* **Command line:** mirrors upstream's sample. `-smp 7` = 6 AP cores + 1 SEP
  core (the machine reserves one vCPU for SEP when `sep-fw` is set). All
  drives use `file.locking=off` because `/sdcard` is FUSE. The root disk can
  be qcow2 (from `package_vm.py`) so only used blocks are transferred.

## Findings worth knowing

* **IPSW compatibility comes from source, not guesswork.**
  `t8030_init` accepts kernel SDK majors 13–18 and 26. Anything else hit
  `assert_not_reached()`. Only iOS 14.x is documented to reach SpringBoard.
  Device is `iPhone12,1` / `n104ap` only; `s8000` (6s Plus) boots without UI.
* **Inferno release builds compile out all asserts.** `meson.build` adds
  `-DNDEBUG -DG_DISABLE_ASSERT` when `debug` is off, and its own `assert_*`
  macros become no-ops, with `assert_not_reached()` becoming
  `__builtin_unreachable()`. Upstream's release builds behave the same. Expect
  more "assert would have caught it" crashes on bad input. The `pixel` branch
  adds real checks for the kernel Mach-O magic and the kernel version.
* **Upstream needs libpng only for the branded boot splash.** The `pixel`
  branch makes it optional (black screen without it) and deletes the artwork,
  as the branding notice requires.
* **Removed or renamed configure options** in Inferno vs. stock QEMU 10.2:
  no `--disable-docs` (it's still in `configure` but meson rejects it), no
  `--disable-plugins`, `--disable-capstone` or `--disable-rust`.
* **`util/mlib` is a nested submodule** that's required to build. The build
  script initialises only it, not QEMU's ROM submodules.
* **bionic incompatibilities found so far:** `shm_open` (now uses
  `memfd_create`), and 9p's use of `st_atime_nsec` (bionic macro; fixed with
  `--disable-virtfs`). Expect more at runtime rather than compile time.
* **Host toolchain gotcha on this Mac:** macOS 27 updated the Command Line
  Tools SDK, but `xcode-select` points at Xcode 26.0, whose linker can't read
  the new SDK's `.tbd` files. `build-android.sh` probes for a working host
  `cc`. Updating Xcode is the real fix.

## Tensor G5 facts (from the Pixel 10 kernel source)

Source: `azagramac/pixel10-kernel-aosp` (a "muzel" kernel tarball covering
frankel/blazer/mustang/rango). Paths are in that repo.

* `private/devices/google/lga/dts/lga-b0-cpu.dtsi`: `hayes_0..1` (A520, ≤2.246 GHz,
  cpu0–1), `hunter_0..4` (A725 / "Hunter" family, ≤3.052 GHz, cpu2–6),
  `hunterelp_0` (X4, ≤3.782 GHz, cpu7).
* `private/devices/google/lga/dts/lga-gpu-b0.dtsi`: GPU 396–1094 MHz.
* `private/google-modules/powervr/`: Imagination Rogue-family DDK.
  `pixel/lga/*/config_kernel.h` lists `RGX_BVNC_CORE_KM_HEADER "cores/rgxcore_km_71.2.2448.1212.h"`.
* GKI `CONFIG_KVM=y` (pKVM), which apps can't use. TCG is the only option.

## Patches on `dylanneve1/Inferno@pixel`

1. `util/oslib-posix.c`: `qemu_shm_alloc` uses `memfd_create` on Android.
2. `accel/tcg/tcg-accel-ops-mttcg.c`: `QEMU_VCPU_AFFINITY` per-vCPU cpu-sets.
3. `hw/display/apple_displaypipe_v4.c`: boot splash optional; no libpng needed.
4. Branding: artwork deleted, `VERSION` → `ios-on-pixel-…`, thread names, SDL title.
5. `hw/arm/apple-silicon/boot.c`: reject non-Mach-O kernels cleanly.
6. `hw/arm/apple-silicon/t8030.c`: unknown kernel version → error instead of UB.

## Suggested next steps

1. Test the RFB/touch path with the `attach` command above, then fix whatever it shows.
2. Add CI: a GitHub Actions job running `native/build-android.sh` and
   `./gradlew assembleDebug`, uploading the APK as an artifact. The APK
   contains only our code plus GPL/AGPL QEMU.
3. With the user's own prepared VM: first iOS boot on a Pixel 10. Capture
   `qemu.log` and timing.
4. Then work through `docs/ROADMAP.md`, starting with the in-process QEMU
   plus `ANativeWindow` display backend, and multi-touch.

## Reproduce the build

```sh
git clone --recurse-submodules https://github.com/dylanneve1/ios-on-pixel
cd ios-on-pixel
brew install meson ninja pkgconf          # plus Android SDK + NDK r28
native/build-android.sh                   # deps + QEMU → app/src/main/jniLibs
JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" ./gradlew assembleDebug
```
