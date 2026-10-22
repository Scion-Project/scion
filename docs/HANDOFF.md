# Handoff notes (2026-10-21)

State of the project, everything verified so far, and what to do next. Read
[ETHICS.md](ETHICS.md) first: its constraints (no Apple files, no
instructions for getting them, no automating firmware prep) apply to all
future work.

## Repositories

| Repo | Branch | Role |
|---|---|---|
| `dylanneve1/scion` | `main` | This repo: Android app, build scripts, tools, docs |
| `dylanneve1/Inferno` | `pixel` | Fork of `ChefKissInc/Inferno` with the Android/Tensor patches; submodule at `third_party/inferno` |
| `dylanneve1/qemu-ios` | — | Fork of `devos50/qemu-ios` (legacy iPod/iPhone emulator), for reference only |
| `dylanneve1/darwin-vm` | — | Fork of `jprx/darwin-vm` (boots modern iOS to a shell, no UI), for reference only |

`TrungNguyen1909/qemu-t8030` is in the same GitHub fork network as Inferno,
so `dylanneve1/Inferno` covers it too. That upstream is archived (2022).
Inferno is the active successor: last push Sep 2026, ~4k stars.

## Moving to Inferno `dev` (2026-10-21)

The submodule tracks `dylanneve1/Inferno@master`, our main line (fast-forwarded from `pixel-dev`): upstream `dev` (314
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

**Android build of `dev`** (verified on macOS with NDK r28, 1056/1056 steps)
needed three more things beyond the above:

* **`liblz4`** is now a hard dependency (`hw/usb`, USB uplink); built by
  `build_lz4`, checksum cross-checked against Homebrew's formula.
* **QEMU must see `host_os == linux`.** Without `./configure`, meson takes the
  host OS from the cross file (`android`), so `CONFIG_LINUX` is unset and
  `qemu_get_thread_id`/`qemu_kill_thread` (`SYS_gettid`, `__NR_tgkill`) and the
  `block/file-posix.c` sysfs helpers fail to compile. The script derives
  `android-arm64-qemu.cross` with `system = 'linux'` for QEMU only; deps keep
  `android`.
* **Three `qemu_build_not_reached()` switch defaults** (softfloat rounding,
  AArch32 SRS `amode`, the arm64 TCG branch conditions) are compile errors with
  the NDK's clang because it can't prove them dead; they switch on runtime
  values, so they're now `assert_not_reached()`.

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
| VM folder import via the SAF folder picker (`ImportService`) | ✅ on a zero-filled 150 MiB folder: byte-identical copy, cancel, free-space refusal, "no vm.properties" refusal; multi-GB not tried (emulator has ~600 MB free) |
| `ipsw_probe.py` against real IPSWs over HTTP range requests | ✅ iOS 14.0 / 14.8 → supported, ~600 KiB fetched; iOS 27 → unsupported |
| `ipsw_probe.py` SPTM/TXM + AEA detection | ✅ 27.0.1 → SPTM/TXM flagged; 26.6.2 and 27.0.1 OS images flagged `.aea` |
| RFB display in the app (`DisplayActivity` via `attach`) | ✅ renders QEMU's console text, scaled, over the unix socket; client gets 32 bpp / depth 24 / LE as requested |
| Touch → QEMU input | ✅ an `adb` tap + swipe arrived as 2× button-down / 2× button-up and 28 absolute x/y pairs (`-trace input_event_*`) |
| Button bar → HMP `sendkey` | ✅ Home → `f6` down/up, Power → `f5` down/up |
| `QEMU_VCPU_AFFINITY` | ✅ `1;2;3;4-7` with `-smp 4` gave exactly those per-thread `Cpus_allowed_list` values; non-vCPU threads stay on 0–7 (8-core emulator, not yet a real Tensor G5) |
| Inferno `dev` + our patches build for Android | ✅ see "Android build of `dev`" above |
| Kernel patch-check on real 14.8 / 26.6.2 / 27.0.1 kernels | ✅ see [IOS27.md](IOS27.md) |

## Not yet verified

1. **Display/touch with a real t8030 guest.** The path itself is verified
   (table above), but that test used the old `master`-based binary because
   `dev` no longer has `-M virt`. To repeat it, start any QEMU that has `virt`
   on the app's sockets, then attach the viewer. `DisplayActivity` isn't
   exported, so go through the launcher activity:
   ```sh
   P=io.github.dylanneve1.iosonpixel; F=/data/data/$P/files
   adb shell "run-as $P sh -c 'cd $F && <qemu> -L $F/qemu-data -M virt -cpu max -m 256 \
       -device ramfb -device qemu-xhci -device usb-tablet -display none \
       -vnc unix:$F/vnc.sock -monitor unix:$F/monitor.sock,server=on,wait=off \
       -trace \"input_event_*\" > $F/virt.log 2>&1 &'"
   adb shell am start -n $P/.MainActivity --ez attach true
   ```
2. **Real iOS 14 boot on a Pixel 10.** Needs a VM folder the user prepared on
   a Mac, plus `tools/package_vm.py --push`. Nothing about this was tested.
3. **Phantom-process killer** behaviour on a real Pixel during long runs.
4. **`QEMU_VCPU_AFFINITY`** on real Tensor G5 cores. Pinning works on an
   8-core emulator; the performance effect on a Pixel hasn't been measured.
   Android's cpusets may narrow the mask when the app is in the background;
   watch for its `warn_report`.

## Developer hooks (debuggable builds only)

* `--ez attach true` on `.MainActivity` opens the viewer (see "Not yet verified").
* **Extra QEMU arguments.** If `files/qemu-extra-args.txt` exists in the app's
  internal files dir, its whitespace-separated tokens (newlines count; no
  quoting or comments) are appended to the end of the QEMU command line, and the
  run log notes it. Release builds ignore the file.
  ```sh
  adb shell run-as <pkg> sh -c 'cat > files/qemu-extra-args.txt'   # then type/pipe the args
  ```
  Example, SEP diagnostics (engine `docs/SEP-NOTES.md`):
  ```
  -trace apple_sep_aess_* -trace apple_sep_ssc_* -trace apple_sep_pka_* -d guest_errors,unimp -D /data/user/0/<pkg>/files/sep-trace.log
  ```

## Engine bump

Engine bump (`third_party/inferno` → pyre `f6ec19c`, master): GPU milestone-0 handshake behind `-M t8030,gpu=on` (off by default), touch controller accepts data-channel packets silently (pyre#50), SEP trace points + bounds fix (pyre#51).

## Architecture recap

* **Backends:** the VM engine sits behind `VmBackend` (start/stop, readiness
  checks, display, buttons), chosen by `VmSettings.backend`. `QemuBackend` is
  the only implementation; AVF is listed on the main screen but disabled
  until scion-avf has something that boots. Everything below is the
  QEMU backend.
* QEMU runs as a **child process** (`libqemu-system-aarch64.so` in
  `nativeLibraryDir`, `useLegacyPackaging = true` so it's extracted and
  executable), launched by `QemuBackend`. `VmService` is a `specialUse`
  foreground service that holds the running instance and a partial wake lock
  and pipes stdout/stderr to `qemu.log` and to `VmState`.
* **In-process variant** (setting "Run QEMU in-process", default off): the
  same QEMU built as `libqemu-system-aarch64-shared.so` (Inferno meson option
  `system_shared_lib`), loaded by `QemuVmService` in a separate
  `android:process=":vm"` through `native/qemu_jni.c`. VmService binds it with
  `BIND_IMPORTANT` (so it runs at foreground priority, and the phantom-process
  killer doesn't apply) and passes argv/env/cwd plus a console pipe over the
  binder. QEMU's `exit()` ends the :vm process. The pipe's EOF marks the exit,
  and the status comes from `ActivityManager.getHistoricalProcessExitReasons`.
  Its display is **`-display android`** (Inferno `ui/android.c`): DisplayActivity's
  `SurfaceScreen` passes its Surface to the :vm process over the binder, and
  QEMU draws the dirty rectangle of the console straight into it on each refresh
  (no VNC). Touch (absolute pointer) and the buttons (`f2`–`f6` key events) go in
  through QEMU's input layer the same way, and framebuffer size changes come
  back for the aspect ratio. The monitor socket stays for `quit`.
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
* **Two TCG speed-ups are on by default and can be turned off for comparison:**
  `-accel tcg,native-fp=off` (guest FP on softfloat instead of the host FPU) and
  `-accel tcg,asid-tlbs=0` (drop TLBs on every ASID switch, the old behaviour).
  `-accel tcg,x-tlb-bits=6` restores the old 64-entry softmmu TLB floor (default 10).
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
* GKI `CONFIG_KVM=y` (pKVM), exposed to apps through AVF only (crosvm, adb-granted
  development permissions). The QEMU/TCG backend stays the default; AVF is explored in
  [scion-avf](https://github.com/dylanneve1/scion-avf).

## Patches on `dylanneve1/Inferno@pixel`

1. `util/oslib-posix.c`: `qemu_shm_alloc` uses `memfd_create` on Android.
2. `accel/tcg/tcg-accel-ops-mttcg.c`: `QEMU_VCPU_AFFINITY` per-vCPU cpu-sets.
3. `hw/display/apple_displaypipe_v4.c`: boot splash optional; no libpng needed.
4. Branding: artwork deleted, `VERSION` → `ios-on-pixel-…`, thread names, SDL title.
5. `hw/arm/apple-silicon/boot.c`: reject non-Mach-O kernels cleanly.
6. `hw/arm/apple-silicon/t8030.c`: unknown kernel version → error instead of UB.

On `pixel-dev` (paths are `hw/arm/…` there), additionally:

7. `fpu/softfloat.c`, `target/arm/tcg/translate.c`, `tcg/aarch64/tcg-target.c.inc`:
   runtime `assert_not_reached()` instead of `qemu_build_not_reached()`.
8. `hw/arm/img4.asn1` + regenerated `img4.h`: accept the `[0]` PAYP element in
   IM4P. Without it, **iOS 26/27 kernelcaches don't load at all**: DER decoding
   failed and the loader silently fell back to a "raw" payload.
9. `hw/arm/t8030.c`: `patch-check=on` triage mode, and an explicit error for
   SPTM/TXM kernels (detected by their `__DATA_SPTM` / `__TEXT_BOOT_EXEC`
   segments).

## Suggested next steps

1. Track upstream's "Gaia" release (SPTM/TXM, ChefKissInc/Inferno#268) and
   rebase `pixel-dev` onto it; re-run patch-check (see [IOS27.md](IOS27.md)).
2. Add CI: a GitHub Actions job running `native/build-android.sh` and
   `./gradlew assembleDebug`, uploading the APK as an artifact. The APK
   contains only our code plus GPL/AGPL QEMU.
3. With the user's own prepared VM: first iOS boot on a Pixel 10. Capture
   `qemu.log` and timing.
4. Then work through `docs/ROADMAP.md`, starting with the in-process QEMU
   plus `ANativeWindow` display backend, and multi-touch.

## Reproduce the build

```sh
git clone --recurse-submodules https://github.com/dylanneve1/scion
cd ios-on-pixel
brew install meson ninja pkgconf cmake    # plus Android SDK + NDK r28
native/build-android.sh                   # deps + QEMU → app/src/main/jniLibs
JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" ./gradlew assembleDebug
```
