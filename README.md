# iOS on Pixel

Run an emulated iPhone 11 on a Google Pixel 10, using a Tensor-tuned Android
port of the [Inferno](https://github.com/ChefKissInc/Inferno) QEMU fork.

> **Aim:** experimentation, debugging and performance research. See
> [docs/ETHICS.md](docs/ETHICS.md) for what this project deliberately doesn't
> do, and [docs/HANDOFF.md](docs/HANDOFF.md) for the current technical state.

> **Status: early work in progress.** The app, the IPSW probe and the
> Android build pipeline are in place. Booting iOS on the phone has not been
> tested end to end yet. Expect emulation to be *slow*: every Apple CPU
> instruction runs through QEMU's TCG JIT, and the guest GPU is software-rendered.

This project ships **no Apple software**. No IPSWs, keys, firmware, ROMs or
pre-made disk images, and nothing that downloads or patches them for you. You
prepare those yourself on a Mac by following the upstream Inferno guide. This
repo then gets the result running on the phone.

## What runs where

```
 Mac (one-time setup)                         Pixel 10
 ─────────────────────                        ─────────────────────────────────────
 Inferno guide: restore + FS patches   adb    app ── foreground service
 tools/ipsw_probe.py   (pick an IPSW)  ───▶     └─ libqemu-system-aarch64.so  (t8030)
 tools/package_vm.py   (pack for phone)           ├─ VNC on a unix socket ─▶ ScreenView (touch → MT-SPI)
                                                  └─ HMP monitor socket ◀─ button bar (F2–F6)
```

* **`third_party/inferno`** is [dylanneve1/Inferno](https://github.com/dylanneve1/Inferno)'s
  `pixel` branch: upstream Inferno plus the Android/Tensor patches (see below).
* **`native/build-android.sh`** cross-compiles QEMU and its static deps (glib,
  pixman, nettle+GMP, libtasn1, lzfse) with the NDK, and drops the binary into
  `app/src/main/jniLibs`.
* **`app/`** is a dependency-free Kotlin app. It launches QEMU, shows the screen,
  and maps touches and the hardware buttons.

## Which IPSWs work?

Only the **iPhone 11** (`iPhone12,1`, board `n104ap`) gets as far as the UI in Inferno.

| iOS | Verdict | Why |
|---|---|---|
| 14.x | ✅ supported | Documented to boot to SpringBoard. The guide uses 14.0 beta 5 (`18A5351d`). |
| 13, 15–18, 26 | 🧪 experimental | The t8030 machine recognises the kernel, but reaching the UI isn't documented. |
| anything else (e.g. 27) | ❌ unsupported | Unknown kernel version; QEMU cannot boot it. |

There's one more hard requirement: preparing a build needs the **SEP firmware
decryption key for that exact build**. This project doesn't provide, find or
fetch keys, firmware or ROMs.

`tools/ipsw_probe.py` checks a specific IPSW. It reads the zip index and
`BuildManifest.plist` (from a local file, or from a URL via HTTP range
requests, about 600 KiB), then reports a verdict and every file the guide's
steps need:

```console
$ tools/ipsw_probe.py --list                    # iPhone12,1 versions with verdicts (metadata only)
$ tools/ipsw_probe.py ~/Downloads/iPhone11,8,iPhone12,1_14.8_18H17_Restore.ipsw
✅ SUPPORTED: iOS 14.8 (18H17)  [iPhone12,1, iPhone11,8]
   files for the Inferno setup steps:
     ✓ kernel_research          kernelcache.research.iphone12b
     ✓ device_tree              Firmware/all_flash/DeviceTree.n104ap.im4p
     ✓ sep_firmware             Firmware/all_flash/sep-firmware.n104.RELEASE.im4p
     ✓ restore_ramdisk_erase    018-61661-017.dmg
     ✓ restore_trustcache       Firmware/018-61661-017.dmg.trustcache
     …
```

The app also checks the prepared files before booting. It verifies the
IMG4/IM4P/IM4M headers and payload types, for example that the SEP firmware
was decrypted and re-packed as `rsep`, plus the `sep_nvram` size and that
every disk is present.

## Setup

**[docs/PHONE-QUICKSTART.md](docs/PHONE-QUICKSTART.md)** is the end-to-end
guide (Mac → Pixel): checking the IPSW, preparing the VM, packaging, installing
the app, importing, starting, the display and buttons, the debug bundle, and
current limits. In short:

1. **Prepare the VM on a Mac.** Follow the upstream
   [Inferno manual](https://chefkiss.dev/guides/inferno/) all the way through:
   restore, filesystem patches, and the first boot with data migration. Doing
   this by hand is upstream's explicit request, so this repo doesn't automate it.
2. **Package it:** `tools/package_vm.py ~/InfernoData` writes `phone-vm`
   (root disk → qcow2, plus `vm.properties`). Add `--push` to `adb push` it
   too, or copy the folder to the phone and use **Import VM folder…** in the app.
3. **Build and install the app:**
   ```sh
   git submodule update --init
   native/build-android.sh            # first run builds deps too (~10 min)
   ./gradlew installDebug
   ```
4. Open **iOS on Pixel**, check that everything is ✓, keep vCPUs at 4
   ([#33](https://github.com/dylanneve1/ios-on-pixel/issues/33)), then tap
   **Start**. If it fails, tap **Share debug bundle** and send the zip.

## Tensor G5 notes

These are taken from the Pixel 10 kernel source
([azagramac/pixel10-kernel-aosp](https://github.com/azagramac/pixel10-kernel-aosp), SoC "lga"):

| cpu | core | max clock |
|---|---|---|
| 0–1 | Cortex-A520 | 2.25 GHz |
| 2–6 | Cortex-A725 | 3.05 GHz |
| 7 | Cortex-X4 | 3.78 GHz |

GPU: Imagination (Rogue-family DDK, BVNC `71.2.2448.1212`), 396–1094 MHz.

* **vCPU pinning:** the iPhone 11 has 6 application cores and 1 SEP core
  (`-smp 7`). The `pixel` branch adds `QEMU_VCPU_AFFINITY`, and the app puts the
  6 AP vCPUs on the 6 big cores and the SEP vCPU on the little ones by default
  (`2-7;2-7;2-7;2-7;2-7;2-7;0-1`, editable in the app).
* **Compiler tuning:** `-march=armv8.2-a+crypto+dotprod+rcpc -mtune=cortex-x4`.
  This is the baseline every Tensor G5 core supports. SVE is left to runtime detection.
* **Hardware virtualization is a separate, experimental track.** Pixels ship
  pKVM through Android's Virtualization Framework (AVF), which a sideloaded app
  can use after an adb permission grant. But AVF runs VMs in the system's own
  crosvm, and Apple-only CPU instructions don't exist on Tensor cores, so it
  isn't a drop-in replacement. The default backend is QEMU's TCG JIT; the AVF
  backend is being explored in
  [ios-on-pixel-avf](https://github.com/dylanneve1/ios-on-pixel-avf) and will
  become a selectable option in the app (#19).

[docs/ROADMAP.md](docs/ROADMAP.md) has the performance plan, including what
using the PowerVR GPU would take.

## Patches on the Inferno `pixel` branch

* Android build fixes: bionic has no `shm_open`, so it uses `memfd_create` instead.
* `QEMU_VCPU_AFFINITY` vCPU pinning for big.LITTLE hosts.
* Upstream branding removed, as the
  [branding notice](https://github.com/ChefKissInc/Inferno/blob/master/ui/icons/CKBrandingNotice.md)
  requires for derivative builds. The boot splash is now optional (black
  screen without it), so the build no longer needs libpng.

## Credits and license

The emulator is the work of the **ChefKiss Inferno** team (built on
TrungNguyen1909's `qemu-t8030` and on QEMU). This project is not affiliated
with or endorsed by ChefKiss, and Apple is not affiliated with either. Please
consider [supporting upstream](https://ko-fi.com/chefkiss). Upstream asks that
contributions to Inferno itself be written by humans, so keep that in mind
before sending anything from here upstream.

This repo is licensed under the **AGPL-3.0**, matching Inferno's own code.
The Inferno fork keeps its GPL-3.0/AGPL-3.0 licensing.

You're responsible for complying with Apple's license agreements and the laws
where you live.
