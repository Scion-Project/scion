# Phone quickstart: iOS 14 on a Pixel (proof of concept)

This page takes you from a finished Inferno setup on a Mac to the emulated
iPhone 11 running in the app on your Pixel, and tells you what to send back
when something goes wrong.

> **Nobody has booted iOS end to end on a phone yet.** Every step below has
> been checked against the scripts and the app as they are now, using a
> synthetic VM folder on the Android emulator, but not with a real iOS image on
> a real Pixel. Your first run is the experiment. If it fails, the
> [debug bundle](#8-when-something-goes-wrong-share-a-debug-bundle) is the most
> useful thing you can send.

Read [ETHICS.md](ETHICS.md) first. This project ships no Apple software, and
neither this page nor the tools tell you where to get firmware, keys or ROMs.
You prepare those yourself, following upstream's manual.

## What you need

* A **Mac** that can run Inferno (see upstream's *Host Setup*).
* A **Pixel**. The app is tuned for the Pixel 10 (Tensor G5). Any arm64 phone
  on Android 12 or newer should install it, but only the Pixel 10 is targeted.
* An **iPhone 11 (`iPhone12,1`) iOS 14.x IPSW** that you already have, plus
  everything else upstream's manual asks for. iOS 14 is the only version
  documented to reach the UI. See the README's
  [IPSW table](../README.md#which-ipsws-work).
* Room on the phone for the packaged VM (a few GiB, mostly the root disk). The
  in-app import needs space for the copy it makes, on top of wherever the
  source folder sits.
* `adb` on the Mac is optional but makes everything easier
  (`brew install --cask android-platform-tools`). With more than one device
  attached (a phone and an emulator, say), pick one first:
  `export ANDROID_SERIAL=<serial from adb devices>`.

## 1. Check your IPSW (Mac)

```sh
tools/ipsw_probe.py path/to/your.ipsw     # verdict + every file the manual's steps need
tools/ipsw_probe.py --list                # iPhone12,1 versions with verdicts (metadata only)
```

You want `✅ SUPPORTED` and a `✓` for every listed file. The probe only reads
the zip index and `BuildManifest.plist`. It never extracts, decrypts or
patches anything.

## 2. Prepare the VM on the Mac (upstream manual)

Follow the **[Inferno Manual](https://chefkiss.dev/guides/inferno/)** all the
way through, by hand:
[Host Setup](https://chefkiss.dev/guides/inferno/host-setup/),
[File Setup](https://chefkiss.dev/guides/inferno/file-setup/),
[Running & Restoring](https://chefkiss.dev/guides/inferno/running-restoring/),
[Filesystem Patches](https://chefkiss.dev/guides/inferno/fs-patches/), and the
first boot on the Mac. Use its
[Troubleshooting](https://chefkiss.dev/guides/inferno/troubleshooting/) page
when stuck.

Upstream explicitly asks that this part not be automated, so nothing in this
repo does it for you. Only continue once **iOS boots on the Mac**: the phone
can't fix a VM that doesn't boot there, and debugging it on the Mac is far
faster. The result is the manual's `InfernoData` folder.

## 3. Package it for the phone (Mac)

```sh
tools/package_vm.py ~/InfernoData            # writes ~/InfernoData/phone-vm
```

This only rearranges files you prepared:

* It picks the kernel (a `kernelcache.research.*` if there is one), the
  device tree and the trustcache via `Restore/BuildManifest.plist`.
* It picks the re-packed SEP firmware (`sep-firmware.*.new.img4`) and the SEP
  ROM (`AppleSEPROM-Cebu-B1*`), and hard-links the small disks.
* It converts the 32 GiB sparse `root` disk to `root.qcow2`, so only used
  blocks are copied.
* It writes `vm.properties`, which the app reads.

It needs `qemu-img`. It uses the one from your Inferno build
(`InfernoData/Inferno/build/qemu-img`), then one on `PATH`; or pass
`--qemu-img /path/to/qemu-img`. If it can't pick a file on its own, it says so;
pass it explicitly (`--kernel`, `--trustcache`, `--sep-fw`, `--sep-rom`).
`--raw-root` keeps the full-size raw disk instead of qcow2. That's not
recommended for the phone.

## 4. Install the app (phone)

There's no published release yet, so build it:

```sh
git clone https://github.com/dylanneve1/ios-on-pixel && cd ios-on-pixel
git submodule update --init                # our Inferno fork, in third_party/inferno
native/build-android.sh                    # QEMU + static deps for Android arm64 (~10 min the first time)
./gradlew installDebug                     # phone connected with USB debugging on
```

`build-android.sh` needs the Android NDK (newest under
`~/Library/Android/sdk/ndk`, or set `ANDROID_NDK_HOME`), plus `meson ninja
pkg-config cmake git curl python3` (`brew install meson ninja pkg-config
cmake`). Gradle needs JDK 17 or newer; Android Studio's bundled one works:
`export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"`.

If a release APK appears on the
[Releases page](https://github.com/dylanneve1/ios-on-pixel/releases), install
that instead with `adb install -r <apk>`, or open it on the phone.

Open **iOS on Pixel** once, and allow notifications: the running VM lives in a
foreground service with a "VM running" notification. The top of the main
screen shows the VM folder path and a checklist. `✓ QEMU binary bundled`
should already be ticked.

## 5. Put the VM on the phone

**With adb** (fastest):

```sh
tools/package_vm.py ~/InfernoData --push
# or, if you've already packaged it:
adb push --sync ~/InfernoData/phone-vm/. /sdcard/Android/data/io.github.dylanneve1.iosonpixel/files/vm
```

**Without adb**: copy the `phone-vm` folder onto the phone any way you like.
For example, use USB file transfer into `Download/phone-vm`. Then, in the app:

1. Tap **Import VM folder…**.
2. Open `Download` and then `phone-vm` (Android won't let apps pick `Download`
   itself), and tap **Use this folder**, then **Allow**.
3. The app streams the files into its own VM folder. It shows progress,
   refuses to start if there isn't enough free space, and can be cancelled
   (**Cancel import**, or the notification). It ends with `Imported N files (X GiB)`.
4. Delete the copy in `Download` afterwards to get the space back.

Either way, the checklist should now read `✓ VM folder looks complete`. If not,
it names what's wrong:
* a missing file or `vm.properties` key;
* a file whose IMG4/IM4P header or payload type isn't what the manual's steps
  produce (for example, SEP firmware that wasn't decrypted and re-packed);
* a `sep_nvram` that isn't 64 KiB.

**Start** stays disabled until every line is `✓`.

## 6. Start

The settings on the main screen are saved when you tap **Start**:

| Setting | Default | Notes |
|---|---|---|
| Guest RAM (GiB) | 4 | 1–12 |
| vCPUs | **4** | **Keep it at 4 for now** (3 application cores + 1 SEP core). 7 matches the real iPhone 11 but currently aborts on Android ([#33](https://github.com/dylanneve1/ios-on-pixel/issues/33)) |
| JIT translation cache (MiB) | 1024 | 64–4096 |
| vCPU → Tensor G5 core pinning | `auto` | `auto` derives it from the vCPU count (application vCPUs → `2-7`, SEP vCPU → `0-1`; the effective string is shown under the field). Or one cpu-set per vCPU, `;`-separated, the last one repeats. Empty turns pinning off |
| Split W^X JIT mapping | off | try it only if QEMU fails to allocate its code buffer |

Tap **Start**. The status goes *starting → running*, the notification
appears, and the display opens. QEMU's output scrolls under **QEMU output** on
the main screen and is also written to `qemu.log`. That includes the guest's
serial console, so the boot log is there.

## 7. Display and buttons

* **Touch**: one finger on the screen is one touch in the guest (single touch
  only). The picture is scaled to fit.
* **Buttons** along the bottom:

  | Button | Does |
  |---|---|
  | Vol− / Vol+ | volume down / up |
  | Home | home button |
  | Apps | double-presses Home (app switcher) |
  | Power | tap = press; **long-press = hold for 3 s** |

  They're sent to QEMU as the F-keys upstream documents in
  [Using the Device's Buttons](https://chefkiss.dev/guides/inferno/device-buttons/).
* The screen stays on while the display is open. Leave it and come back with
  **Display** on the main screen, or by tapping the notification.
* If QEMU exits, the display shows `VM stopped (exit N); see the log`, and the
  main screen shows `Status: stopped (last exit N)`.

**To stop**, tap **Stop** on the main screen or in the notification. The app
asks QEMU to quit cleanly so the disk images are flushed, and only kills it
if it doesn't exit within about 15 s. Don't force-stop the app while a VM is
running unless it's hung.

## 8. When something goes wrong: share a debug bundle

Tap **Share debug bundle** on the main screen and send the zip wherever you
like, for example attached to a GitHub issue. It contains:

* `qemu.log` (QEMU's output, including the guest serial console);
* the exact QEMU command line and the last exit code (both survive the app
  being closed);
* your settings;
* `vm.properties` and the VM folder's file names and sizes;
* app, device, Android and SoC versions;
* CPU topology, and free RAM and storage.

It **never** contains disk images, kernels, device trees, firmware or any
other file from the VM folder. `README.txt` inside the zip lists exactly what
is included.

With adb you can also read the log directly:
`adb shell cat /sdcard/Android/data/io.github.dylanneve1.iosonpixel/files/qemu.log`.

## Current limits

* **`-smp 4` until [#33](https://github.com/dylanneve1/ios-on-pixel/issues/33).**
  With 7 vCPUs, QEMU creates enough memory mappings to hit Android's
  per-process limit and aborts.
* **Slow.** Every Apple CPU instruction goes through QEMU's TCG JIT, and the
  guest GPU is software-rendered. Expect the boot to take minutes and the UI
  to be sluggish. Nobody has timed it on a phone yet.
* **iOS 14 only.** Other versions are experimental or unsupported (see the
  [README](../README.md#which-ipsws-work)).
* **Touch.** Each finger is passed through as its own contact (over QMP) when
  the bundled Inferno has multitouch support in its touch controller
  (dylanneve1/Inferno#12); otherwise only one finger works. There's no
  hardware keyboard input beyond the buttons above. The app doesn't configure audio or networking.
* **Long runs are untested.** We don't yet know how Android's background
  limits (the phantom-process killer) treat a long boot with the screen off.
  Keep the app or the display in front during the first runs.
* **AVF (hardware virtualization)** appears on the main screen but can't be
  selected. That work lives in
  [ios-on-pixel-avf](https://github.com/dylanneve1/ios-on-pixel-avf).
