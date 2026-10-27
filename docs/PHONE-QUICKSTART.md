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
git clone https://github.com/Scion-Project/scion && cd scion
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
[Releases page](https://github.com/Scion-Project/scion/releases), install
that instead with `adb install -r <apk>`, or open it on the phone.

Open **Scion** once, and allow notifications: the running VM lives in a
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

That's the first install's folder. The app can hold several installs, for
example the QEMU iOS 14 VM and an AVF one, each with its own folder, engine and
settings: see [Several installs](#several-installs).

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
| vCPUs | **4** | **Keep it at 4 for now** (3 application cores + 1 SEP core). 7 matches the real iPhone 11 but currently aborts on Android ([#33](https://github.com/Scion-Project/scion/issues/33)) |
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

* **`-smp 4` until [#33](https://github.com/Scion-Project/scion/issues/33).**
  With 7 vCPUs, QEMU creates enough memory mappings to hit Android's
  per-process limit and aborts.
* **Slow.** Every Apple CPU instruction goes through QEMU's TCG JIT, and the
  guest GPU is software-rendered. Expect the boot to take minutes and the UI
  to be sluggish. Nobody has timed it on a phone yet.
* **iOS 14 only.** Other versions are experimental or unsupported (see the
  [README](../README.md#which-ipsws-work)).
* **Touch.** Each finger is passed through as its own contact (over QMP) when
  the bundled Inferno has multitouch support in its touch controller
  (Scion-Project/pyre#12); otherwise only one finger works. There's no
  hardware keyboard input beyond the buttons above. The app doesn't configure audio or networking.
* **Long runs are untested.** We don't yet know how Android's background
  limits (the phantom-process killer) treat a long boot with the screen off.
  Keep the app or the display in front during the first runs.
* **AVF (hardware virtualization)** boots a custom kernel, not iOS yet, and has
  no display. See [AVF backend](#avf-backend-experimental) below and
  [scion-avf](https://github.com/Scion-Project/scion-avf).

## Several installs

The **Installs** card at the top of the main screen lists the VMs the app
knows about. Each install has its own VM folder, engine (QEMU or AVF) and
settings, plus its own sockets and logs. Only one runs at a time, so switching
is disabled while a VM runs.

* **Upgrading from a single-VM build:** on first launch the existing VM becomes
  install 1, "iOS 14 (iPhone 11)", with its settings. Its folder stays exactly
  where it was (`files/vm`, or `Android/data/…/files/vm` in shared storage);
  nothing in it is moved, copied or rewritten. The old settings stay too, kept
  in step with install 1, so an older build still finds the same VM.
* **Add** makes a new install with an empty folder,
  `/data/user/0/io.github.dylanneve1.iosonpixel/files/installs/<id>`. Fill it
  with **Import VM folder…**, or `adb push` to its shared folder (shown under
  VM files) and move it in. Import and move always act on the selected install.
* **Remove** only forgets the install. Its files are deleted only if you tick
  **Also delete its files**. That box is only offered for folders the app
  created for that install (`installs/<id>`), never for install 1's `files/vm`.
* Logs: `Android/data/io.github.dylanneve1.iosonpixel/files/logs/<id>/`
  (`qemu.log`, `avf-console.log`). The debug bundle covers the selected install.

## AVF backend (experimental)

**Settings → Advanced → VM backend → AVF** (per install) runs the guest as a non-protected
custom-kernel VM under the Android Virtualization Framework (pKVM + crosvm)
instead of QEMU. Today that means a Linux-style kernel + initrd with a
console only: the iOS kernel for AVF is still being ported, and there's no
display yet. The guest console shows in the app's output view and in
`Android/data/io.github.dylanneve1.iosonpixel/files/logs/<install id>/avf-console.log`.

**One-time adb setup.** The main screen lists whatever is missing, with these
commands:

```sh
PKG=io.github.dylanneve1.iosonpixel
adb shell pm grant $PKG android.permission.MANAGE_VIRTUAL_MACHINE
adb shell pm grant $PKG android.permission.USE_CUSTOM_VIRTUAL_MACHINE
# The custom-kernel API is @hide. This setting is global (all apps) and applies to
# processes started after it, hence the force-stop.
adb shell settings put global hidden_api_blacklist_exemptions \
  "'Landroid/system/virtualmachine/VirtualMachineCustomImageConfig,Landroid/system/virtualmachine/VirtualMachineConfig\$Builder;->setCustomImageConfig('"
adb shell am force-stop $PKG
```

To undo: `adb shell settings delete global hidden_api_blacklist_exemptions`
and `pm revoke` the two permissions.

**The guest** is described by `avf.properties` in the VM folder, next to
QEMU's `vm.properties` (so one folder can hold both and share disk images).
Paths are relative to the folder:

```properties
kernel=Image              # required
initrd=initrd.img         # optional
params=console=hvc0 earlycon=uart8250,mmio,0x3f8 panic=-1   # this is the default
disk.0=root.img           # optional virtio-blk disks, in order (vda, vdb, ...)
disk.0.writable=true      # read-only unless set
memory_mib=2048           # default: the Guest RAM setting
cpus=host                 # 1 or host; default: 1 if vCPUs is 1, else host
display=1125x2436         # with the experimental display: the screen size asked for
```

**Guest security** (Settings → Advanced) is appended to the kernel command
line as `security=full|bringup|off`, replacing any `security=` in `params`.
The kernel decides what the levels mean. The default is the most secure level
the kernel boots with; until the kernel defines its levels, that's `bringup`.

**Experimental AVF display** (Settings → Advanced, per install, off by
default) shows the guest's screen and takes touch, the way scion-avf's display
test app does (its `docs/DISPLAY-INPUT.md` and `display/SCFB.md`). The app
lends the VM an ashmem region as a slice of guest RAM (the "SCFB" shared
framebuffer), adds a virtio-input multi-touch device, and appends
`scfb.addr=… scfb.size=… scfb.w=… scfb.h=…` to the kernel command line. The
region sits 4 MiB below the top of guest RAM; a Linux guest has to keep out of
it with `mem=` in `params`, while avfboot handles that for XNU. Frames are
copied into the display as they're published (XR24 into a BGRA buffer, XB24
into RGBX, a plain `memcpy` either way).

The Java API has no setters for those two devices, so the app adds them to
its own VM's config in its own process, through the framework's internal
VirtualizationService. That needs two more prefixes in the exemption:

```sh
adb shell settings put global hidden_api_blacklist_exemptions \
  "'Landroid/system/virtualmachine/VirtualMachineCustomImageConfig,Landroid/system/virtualmachine/VirtualMachineConfig\$Builder;->setCustomImageConfig(,Landroid/system/virtualmachine/VirtualizationService;,Landroid/system/virtualizationservice/'"
adb shell am force-stop $PKG
```

**Network** (Settings → Advanced, per install, off by default) asks AVF for a
virtio-net card on Android's VM tethering (`useNetwork`; see
[NETWORKING.md](NETWORKING.md)). It needs nothing more than the first
exemption, but for the main screen to show whether AVF has networking at all
(`VirtualMachineManager.isFeatureEnabled`, blocked for sideloaded apps) add one
more prefix. Without it the check says so, and the VM asks for the card anyway
and logs Android's answer. Everything at once (display and network):

```sh
adb shell settings put global hidden_api_blacklist_exemptions \
  "'Landroid/system/virtualmachine/VirtualMachineCustomImageConfig,Landroid/system/virtualmachine/VirtualMachineConfig\$Builder;->setCustomImageConfig(,Landroid/system/virtualmachine/VirtualizationService;,Landroid/system/virtualizationservice/,Landroid/system/virtualmachine/VirtualMachineManager;->isFeatureEnabled('"
adb shell am force-stop $PKG
```

If a member is missing, or Android refuses the devices (virtmgr rejects the
config, or the VM dies at once with them), the app says "AVF display
unavailable on this Android build: <reason>" and boots console-only.

Stop is crosvm's hard stop, like pulling the plug: there's no clean-shutdown
request for custom VMs yet.

**Developer hook (debug builds):** run a backend and folder for one session
without changing the saved settings, and press Start:

```sh
adb shell am start -n $PKG/.MainActivity --es backend avf \
  --es vm_dir /data/user/0/$PKG/files/<folder> --ez start true
```

Add `--ez avf_display true` (or `false`) to override the experimental display
setting for that session.

Files the app reads must be owned by the app. A folder that `adb shell mkdir`
created belongs to shell, and the app can't read what's pushed into it. Push
into a folder the app made (open the app once first), or stream files in with
`adb exec-in "run-as $PKG sh -c 'cat > files/<folder>/Image'" < Image`.
