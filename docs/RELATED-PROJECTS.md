# Related projects (GitHub scan, 2026-10-22)

**Scope.** Projects active in 2025-2026 that could help Pyre (the QEMU/TCG backend) or scion-avf (XNU as a pKVM/crosvm guest). The projects you already know (Inferno, darwin-vm, ios-sweet, HackiOS, qemu-ios, Asahi) appear only where a fork or derivative adds something. Stars and push dates are from the GitHub API on 2026-10-22. "NOASSERTION" means GitHub could not detect the licence; where I read the licence file myself, I give the real one.

**Licence facts that affect what you can reuse:**
- **Pyre** is GPL-3.0 for the QEMU-derived code and AGPL-3.0 for Inferno's and Pyre's own code (Pyre's README and LICENSE). It is not GPL-2.0. That means it can take in GPL-3/AGPL-3 code from other Inferno forks, plus MIT, BSD and Apache-2.0 code. It cannot take GPL-2.0-only code.
- **The XNU kernel is APSL-2.0.** APSL is generally treated as GPL-incompatible, so Linux driver code (GPL-2.0) should not be copied into XNU kexts. BSD-2-Clause-Patent, MIT and BSD kexts are fine.
- **scion-avf** is AGPL-3.0.

**Exclusions.** I left out jailbreak and exploit tooling and key or firmware decryption tools. Where a useful repo also contains such material, the caveat says so.

---

## Ranked list

### 1. Droid-VM/UniXNU-droidvm, with the Droid-VM/crosvm fork
- **URL:** https://github.com/Droid-VM/UniXNU-droidvm (and https://github.com/Droid-VM/crosvm, branch `wip/virtio-mmio`)
- **Licence:** BSD-2-Clause-Patent (crosvm fork: BSD-3-Clause)
- **Last push / stars:** 2026-10-22 / 1★ (crosvm fork 9★; created 2026-10-21)
- **What it does:** XNU running iPadOS 17 as a **crosvm guest on a phone hypervisor** (Gunyah, on SM8650 and SM8850). This is the same architecture as scion-avf, and it is further along. Its status table reports:
  - The boot chain UEFI → UniXNU → DarwinBoot → XNU works, with 4 KiB pages.
  - The original OS and SystemOS images are mounted read-only over virtio-mmio block and verified.
  - It reaches the lock screen about one minute after boot with 4 vCPUs, and the home screen after a Home press.
  - The Data volume persists through a sparse store disk.
  - Touch and absolute-pointer input work over virtio-input.
  - `en0` runs over virtio-net, bridged to the phone's Wi-Fi with DHCP. It is built as an `IOEthernetController` on IONetworkingFamily-177, compiled from Apple's open source.
  - A paravirtual Adreno GPU is in progress (`DroidVMGpu.cpp`: NativeAdreno's user-client ABI over the host's KGSL through a crosvm `--droidvm-gpu` device).
  - `scripts/make-droidvm-adt.py` converts crosvm's own device tree (from `--dump-device-tree-blob`) into Apple's device-tree format (ADT).
  - The crosvm fork adds `--virtio-mmio-input`, `--virtio-mmio-net` and `--virtio-mmio-block`.
- **Helps scion-avf, directly:**
  - Virtio block, net and input drivers to compare against or lift (`package/DroidVMVirtioNet.c`, `DroidVMVirtioEthernet.cpp`, `DroidVMVirtioInput.c`).
  - The FDT→ADT generator.
  - A host-side "rig" that reads the kernel log and panics through guest RAM.
  - Hard-won notes. Every virtio-mmio device crosvm exposes must appear in the ADT, or the phone hangs or watchdog-resets. A touch starting in the bottom 4% must be treated as an edge swipe so the Home gesture works.
- **Caveats:**
  - It uses virtio-mmio, while Scion uses virtio-pci, and Gunyah rather than pKVM, though it is the same crosvm.
  - iPadOS 17 rather than iOS 16.5.
  - The IPSW is fetched locally by the user; no Apple binaries are in the repo.
  - The GPU work is Adreno/KGSL-specific, which doesn't fit the Pixel 10's PowerVR GPU.
  - Very new, and it changes daily.

### 2. UniXNU/UniXNU and its kits
- **URLs:**
  - Main: https://github.com/UniXNU/UniXNU
  - Kits: [NativeVirtioBlock](https://github.com/UniXNU/NativeVirtioBlock), [NativeVirtioConsole](https://github.com/UniXNU/NativeVirtioConsole), [ArmVirtPlatform](https://github.com/UniXNU/ArmVirtPlatform), [ArmCpu](https://github.com/UniXNU/ArmCpu), [ArmDTPlatform](https://github.com/UniXNU/ArmDTPlatform), [NativeHidInput](https://github.com/UniXNU/NativeHidInput), [NativeMobileDisplay](https://github.com/UniXNU/NativeMobileDisplay), [NativeRamBlock](https://github.com/UniXNU/NativeRamBlock), [DarwinBoot](https://github.com/UniXNU/DarwinBoot), [NativePrelink](https://github.com/UniXNU/NativePrelink), [ArmSmmu4K](https://github.com/UniXNU/ArmSmmu4K)
- **Licence:** BSD-2-Clause-Patent, read from the LICENSE files (GitHub says NOASSERTION). ArmSmmu4K and NativeMetal are MIT.
- **Last push / stars:** 2026-10-22 / 620★ (created 2026-10-19). Documentation at unixnu.com, with source in `docs/`.
- **What it does:** a framework for running iOS and macOS on non-Apple ARM64 through UEFI. It has a build driver (`./unixnu defconfig/fetch/build kernel/payload`), XNU 4 KiB page patches, a DarwinBoot UEFI loader, and Apple device-tree generation. Its `scripts/qemu-entry/make-dt.py` has options `--armvirt`, `--armcpu`, `--smp`, `--qemu-vmm-present` and `--qemu-accel hvf`. The drivers are independent repos: an ARM CPU/GICv3/timer driver, a device-tree platform expert, a QEMU `virt` platform, virtio-mmio block and console, HID input for IOHIDFamily, display scanout with a vsync timer, a RAM block device with a persistent overlay, and a prelinker.
- **Helps scion-avf:**
  - It is essentially a generic-ARM XNU platform layer with virtio. ArmCpu, ArmDTPlatform and ArmVirtPlatform address the same problem as Scion's platform expert and device-tree work.
  - NativeVirtioBlock and NativeVirtioConsole give a second reference implementation.
  - NativeMobileDisplay is a good template for driving a framebuffer, or a virtio-gpu scanout, as an IOMobileFramebuffer-family display with a timer standing in for vsync.
  - `docs/xnu/four-k-page-tables.md` matters if the pKVM guest uses a 4K granule.
- **Caveats:**
  - The kits are tiny and only days old (3-67 KB each).
  - The kernel build needs macOS and Xcode.
  - The IPSW is fetched by the user. Do not use BigfootACA/unixnu-droidvm-ci, which publishes encrypted artefacts containing Apple kernelcache components.

### 3. MakrSas/Inferno (branch `ios`) and MakrSas/Inferno-iOS
- **URL:** https://github.com/MakrSas/Inferno and https://github.com/MakrSas/Inferno-iOS
- **Licence:** GPL-3.0 / AGPL-3.0, the same as Pyre, so it merges cleanly.
- **Last push / stars:** 2026-09-24 / 38★ (Inferno-iOS 152★)
- **What it does:** an Inferno fork that runs as a library inside an iPhone app, 39 commits ahead of upstream `dev`. Pyre is 0 commits behind upstream, so all of this is new to Pyre.
  - **Networking:** `hw/net/apple-ncm-host.c` (about 1150 lines) is a USB host inside QEMU. It switches the guest into CDC-NCM mode (vendor request 0x52, wIndex=3), selects the configuration and alternate setting, and unpacks NTH16/NDP16 blocks into slirp (`-netdev user`). Guest internet works with no companion VM.
  - **Audio:** instantiates `aop-audio`, swaps in the AOP MCA and microphone device-tree nodes, adds an output route, fixes MCA reset and DMA FIFO drain, a `cs42l77` bounds bug and SIO transfers. It also adds haptics, SMC battery reporting and an `adp_v4` PCC mailbox doorbell.
  - **TCG/MTTCG:** a second way in the jump cache, cross-CPU TLB flushes run on the owning CPU, recovery of dropped A13 IPIs (fixes lost wakeups), no framebuffer scanning while the display pipe drives it, and coalesced UART output.
  - **Memory:** a `physmem` subpage change saves about 800 MB at `-smp 4`.
  - **Docs (Inferno-iOS):** `ANDROID-PORT.md` reports the same source booting to the iOS desktop on a **Pixel 7**, with bionic patches for memfd, `close_range` and PT_TLS. It advises pinning vCPUs to the big cores and using 16 KB pages, and reports translation-cache size mattering: 64 MB gave 8-11 fps, 256 MB gave 21-25 fps. `MTTCG-HANDOFF.md` and `FINDINGS.md` add more.
- **Helps Pyre:** USB networking (this is Scion's planned in-process NCM design, already written), audio, MTTCG fixes and Android port notes.
- **Caveats:**
  - The iOS-host parts (CoreAudio backend, embedding) need replacing with AAudio and Android surfaces.
  - Docs are partly in Russian.
  - The app contains some jailbreak-oriented helpers (Cydia); ignore those.

### 4. emkey1/qemu (branch `aok-gadgets`) and emkey1/aok-vm
- **URL:** https://github.com/emkey1/qemu (code in `target/arm/tcg/aok/`) and https://github.com/emkey1/aok-vm
- **Licence:** GPL-2.0-or-later in the file headers, so usable under GPL-3. aok-vm is Apache-2.0, with its `aok/` directory under GPLv3/GPLv2.
- **Last push / stars:** 2026-10-21 / 0★ (aok-vm 6★)
- **What it does:** an **A64→A64 front end** that runs alongside TCG (QEMU 11.1.2):
  - It handles the instructions it can prove correct and leaves the rest to TCG; `QEMU_AOK=0` switches it off for comparison.
  - Scalar FP and AdvSIMD run as native host instructions. The host FPCR is set to the guest's while they run, and host FPSR flags are folded back into the guest's FP status before any call into C (`aok_fp_end`, `TCGCPUOps.aok_fold_fpsr`).
  - Loads and stores use the softmmu fast path. Indirect branches probe the jump cache inside generated code.
  - It reports 2.1-3.4x fewer host cycles than QEMU's interpreter. An earlier comparison has it beating QEMU's real JIT on FP (0.6x the cycles).
  - `aok/bench/simdtest.c` checks **all 560,640 scalar-FP/AdvSIMD encodings, flags included**, against stock QEMU.
- **Helps Pyre:** a working design for the native-FP and same-ISA track. The FPSR-fold scheme and the encoding test suite can be reused as they are, and the test suite makes a good correctness gate.
- **Caveats:**
  - It emits threaded gadgets because iOS forbids JIT. Pyre has a real JIT on Android, so port the ideas into native tcg/aarch64 emission.
  - Tested only with `-cpu cortex-a72`. PAC, BTI and LSE/LSE2 fall back to TCG, which matters for arm64e XNU.
  - QEMU 11.1 base, while Inferno is QEMU 10.x.

### 5. tctiSH/qemu (branch `tctish-edition`)
- **URL:** https://github.com/tctiSH/qemu
- **Licence:** QEMU's (GPL-2.0-or-later)
- **Last push / stars:** 2026-10-22 / 57★
- **What it does:** changes to generic TCG code that also help the native aarch64 JIT:
  - A two-way jump cache, four times larger; the author found 78% of misses were collisions (commit `c45d010a73`).
  - An inlined `tb_lookup()` hit path. Invalidating one TB no longer flushes every jump cache. Cheaper liveness analysis at branches and calls.
  - An **inline float-helper registry** (`b7068e3923`, FMA in `a50e64d938`): the tcg/aarch64 backend emits host fadd/fmul/fdiv/fmin/fmax/FMA itself when the result and flags provably match softfloat (round-to-nearest, inexact already raised, normal operands). So far it is registered for the x86 guest only.
  - slirp checksum/TSO offload and a configurable MTU for `-netdev user`.
- **Helps Pyre:** registering target/arm's VFP/NEON helpers with the inline float registry is a direct route to native FP. The jump-cache changes also overlap MakrSas's.
- **Caveats:**
  - Built on QEMU 11.1's new TCG backend interface; the author says it is hard to backport to QEMU 10.
  - Much of the rest of the tree is specific to TCTI (iOS no-JIT).

### 6. xVanTuring/UniXNU-qemu
- **URL:** https://github.com/xVanTuring/UniXNU-qemu
- **Licence:** BSD-2-Clause-Patent
- **Last push / stars:** 2026-10-21 / 1★ (created 2026-10-21)
- **What it does:** XNU on the **QEMU AArch64 `virt` machine** booting iPadOS 17 to the home screen and Settings, with Touch and Home working.
  - Under TCG it takes about 5 minutes to SpringBoard; under HVF on an M3 Max, about 100 s.
  - `package/VirtioInput.cpp` and `TouchState.cpp` turn QEMU's virtio tablet and keyboard into NativeHidInput touch and Home.
  - `QemuRamfbPkg` sets the ramfb size, so a portrait panel works.
  - `qemu-ps.py` reads the guest's process list (`allproc`) and kernel log through the QEMU monitor.
  - **QemuMetal** publishes a Metal device in the guest and runs its work on the host GPU, over a shared-memory mailbox window found through fw_cfg. It implements NativeMetal's bridge ABI and includes its own accelerator and per-process grants.
- **Helps scion-avf** for bring-up on a desktop (the same platform as `virt`), virtio input to HID, and display sizing. QemuMetal is a design template for **API-remoted Metal across a shared window**, which could become the scion-avf GPU path with metal2vulkan or Vulkan on the Android host.
- **Caveats:** WIP; the host side of QemuMetal is macOS Metal.

### 7. okhsunrog/pocketvm
- **URL:** https://github.com/okhsunrog/pocketvm
- **Licence:** Apache-2.0
- **Last push / stars:** 2026-08-21 / 2★
- **What it does:** a mainline Linux 7.2 `Image` booting in AVF on a Pixel 8 Pro running Android 17, in 0.2 s, with a Rust init. It also forks Google's Terminal app into a plain Gradle APK installed with `adb install`.
  - `docs/mainline-kernel.md` lists **exactly what the AVF guest sees**. The UART is a device-tree `ns16550a` at 0x3f8 with `stdout-path`, so the guest is silent without `SERIAL_OF_PLATFORM`. An MPAM system-register access traps as UNDEF at t=0.
  - Separate docs cover the SELinux rules and hidden APIs a normal app needs, and guest-image pitfalls (the balloon corrupting memory, NIC naming).
- **Helps scion-avf** with booting XNU as an arm64 Linux `Image`: the same console and system-register traps will hit XNU. Helps the app backend through the Terminal-fork build recipe and its MCP/adb agent bridge.
- **Caveats:** Linux guest only; small project.

### 8. MakrSas/inferno-vgpu-kext and MakrSas/metal2vulkan-inferno
- **URL:** https://github.com/MakrSas/inferno-vgpu-kext and https://github.com/MakrSas/metal2vulkan-inferno
- **Licence:** none (metal2vulkan-inferno: LGPL-3.0)
- **Last push / stars:** 2026-07-31 / 0★ (metal2vulkan-inferno 2026-07-26 / 1★)
- **What it does:** on **Inferno with an iOS 14 guest (t8030)**, a custom kext, a Metal bridge dylib, and a patch to `MTLCreateSystemDefaultDevice` in the dyld shared cache. Metal compute and render pipelines run through metal2vulkan and reims-vgpu's Vulkan engine, and a triangle was presented into the emulated display pipe.
- **Helps Pyre's GPU track:** the closest prior art, on the same machine and iOS version.
- **Caveats:**
  - No licence, so treat it as reference only.
  - It patches kernelcache and shared-cache bytes.
  - It contains symbol dumps and blobs that may be Apple-derived.
  - CAMetalLayer and the render server are not wired up.
  - Stale since July.

### 9. SoftShim/Inferno
- **URL:** https://github.com/SoftShim/Inferno
- **Licence:** GPL-3/AGPL-3 (Inferno's)
- **Last push / stars:** 2026-09-25 / 0★
- **What it does:** one large commit that boots iOS 17 on t8030 under HVF:
  - A USB Ethernet link-up fix: `AppleUSBEthernetDevice` otherwise stays at IOLinkStatus=1.
  - A SEP warm-reboot fix in `apple_sep_reset_hold` ("SEP/OS failed to boot at stage 1").
  - DART changes (+368 lines), `mt-spi` (+232), AIC tick latency, PCIe enumeration.
  - GXF/PAC/TPRO handling.
- **Helps Pyre** with USB networking, SEP reset, and DART/AIC fixes.
- **Caveats:** HVF/macOS-oriented, so the generic parts must be picked out by hand; DART→SEP mirroring is only needed under HVF.

### 10. steelbrain/metal2vulkan and steelbrain/reims-vgpu
- **URL:** https://github.com/steelbrain/metal2vulkan and https://github.com/steelbrain/reims-vgpu
- **Licence:** NOASSERTION. reims-vgpu's README says GPL-2.0-or-later OR LGPL-3.0-or-later.
- **Last push / stars:** 2026-10-22 / 287★ and 612★
- **What it does:**
  - metal2vulkan: a metallib/AIR → SPIR-V translator plus a Rust Metal-style device on Vulkan (alpha).
  - reims-vgpu: a replacement for the host side of Apple's ParavirtualizedGraphics. It decodes the command stream from the guest's AppleParavirtGPU driver and runs it on Metal, or on Vulkan through metal2vulkan, and includes an IOSurface host device.
- **Helps:** the shader-translation engine behind UniXNU NativeMetal and MakrSas's kext, for both Pyre GPU and scion-avf GPU. reims is a model for a paravirtual GPU protocol.
- **Caveats:**
  - iOS 14/16 kernels have no AppleParavirtGPU driver (Inferno maintainer, issue #316), so the guest side must be written.
  - reims builds for x86-64 Linux only, and its Vulkan shaders are metallibs compiled with Apple's toolchain.

### 11. DarcJC/Viruasior
- **URL:** https://github.com/DarcJC/Viruasior
- **Licence:** none
- **Last push / stars:** 2026-10-22 / 0★
- **What it does:** an Android 17 app that runs a **custom AVF VM** with:
  - A root display bridge that submits the guest's AHardwareBuffer to SurfaceFlinger with no CPU pixel copy.
  - **Venus** (Vulkan) guest GPU on a Mali host; KWin runs on Zink/Venus.
  - Absolute touchpad capture fed to a virtio trackpad, virtio-fs sharing, and resolution changes that update display and input geometry.
  - Docs on Venus and gfxstream validation, and on SELinux limits on attaching a Surface directly.
- **Helps scion-avf** with display and touch through AVF, and Venus GPU on a non-Adreno host (closer to PowerVR than the Adreno-only work elsewhere).
- **Caveats:** no licence, needs KernelSU root for the display bridge, Linux guest only.

### 12. google/gfxstream (open PRs #203-207 by ikicha)
- **URL:** https://github.com/google/gfxstream (for example https://github.com/google/gfxstream/pull/204)
- **Licence:** Apache-2.0
- **Last push / stars:** 2026-10-19 / 62★
- **What it does:** the PRs (2026-10-14) add an Android-host AHardwareBuffer backend: formats, DRM-modifier emulation and buffer export. This is the path the Pixel 10 Terminal app uses for GPU acceleration.
- **Helps scion-avf GPU:** the realistic host side on the Pixel 10. **Mesa has no driver for the Pixel 10's DXT GPU** (its PowerVR driver covers AXE, BXS and BXM parts only), so the host must use the phone's proprietary Vulkan driver through gfxstream. The guest side would need gfxstream's guest Vulkan driver (in Mesa) built for Darwin.
- **Caveats:** the PRs are unmerged, and press reports say only 47 of 142 Vulkan extensions reach the VM.

### 13. hashFactory/Inferno
- **URL:** https://github.com/hashFactory/Inferno
- **Licence:** GPL-3/AGPL-3
- **Last push / stars:** 2026-05-19 / 1★
- **What it does:**
  - An `inferno_gpu` fake AGX MMIO device, with kernel patches that make AGX matchable.
  - A filtered hijack of AGX's `newUserClient`.
  - A guest-to-host hypercall transport (HINT #127/#126).
  - A DisplayPipe v4 path that copies frames to the framebuffer (+820 lines).
- **Helps Pyre's GPU track:** a paravirtual-GPU alternative to an AGX scaffold.
- **Caveats:** 334 commits behind upstream; relies on kernel patching.

### 14. medfa12/apple-silicon-anyos-project
- **URL:** https://github.com/medfa12/apple-silicon-anyos-project
- **Licence:** GPL-2.0
- **Last push / stars:** 2026-08-17 / 13★
- **What it does:** macOS 26 under QEMU `vmapple` on an **ARM64 Linux KVM host**. It fixes pointer authentication across vCPUs, GIC decoding, Apple hypercalls and timers, and adds a `pvg-vulkan` device plus AIR→SPIR-V translation.
- **Helps scion-avf:** a catalogue of the traps XNU hits under KVM on non-Apple ARM (PAC keys, GIC, timers), which you will meet under pKVM too. Also an example of a GPU path from paravirtual Metal to Vulkan on an ARM host.
- **Caveats:**
  - GPL-2.0, so use it as reference only for XNU-side code.
  - macOS rather than iOS, and it needs Apple's AVPBooter supplied by the user.
  - Tested only on the lavapipe CPU renderer.

### 15. ExTV/Podroid
- **URL:** https://github.com/ExTV/Podroid
- **Licence:** GPL-2.0
- **Last push / stars:** 2026-10-20 / 3588★
- **What it does:** a rootless Android app that boots an Alpine VM either through QEMU or through **AVF custom VMs** on pKVM devices (Pixel 8/9/10). It grants `MANAGE_VIRTUAL_MACHINE` and `USE_CUSTOM_VIRTUAL_MACHINE` with `pm grant` and reaches `VirtualMachineCustomImageConfig` by reflection (`engine/avf/`). It has an X11 viewer with touch, keyboard, mouse and audio, USB passthrough and port forwarding.
- **Helps Scion's app backend:** a mature, popular example of a third-party app driving AVF custom VMs on the Pixel 10, with both a QEMU and an AVF backend in one app, the same shape as Scion.
- **Caveats:** GPL-2.0 (only the ideas transfer to AGPL Scion); Linux guest; the display goes through X11/VNC rather than a virtio-gpu surface.

### 16. freundTech/android-avf-stubs
- **URL:** https://github.com/freundTech/android-avf-stubs
- **Licence:** Apache-2.0
- **Last push / stars:** 2026-02-16 / 9★
- **What it does:** `compileOnly` stubs for the hidden AVF framework APIs (`android.system.virtualmachine`), with manifest and permission instructions for custom VMs and Microdroid payloads.
- **Helps the app backend:** lets you build against AVF with a normal Gradle setup instead of reflection.
- **Caveats:** the hidden-API surface shifts between Android releases; check it against Android 17.

### 17. KawaiiHachimi/TerminalAppPlus
- **URL:** https://github.com/KawaiiHachimi/TerminalAppPlus
- **Licence:** none
- **Last push / stars:** 2026-10-09 / 16★
- **What it does:** the Android 17 AOSP Terminal app as a standalone Gradle project (SDK 37, AGP 9.4). It grants the AVF permissions by `pm grant` or through Shizuku, and documents a guest screen-capture service and custom U-Boot/Linux images.
- **Helps the app backend:** a second up-to-date Terminal-app fork, with Shizuku-based permission granting so users need no PC.
- **Caveats:** no licence; tested on MediaTek GenieZone.

### 18. merckhung/darwin_embedded
- **URL:** https://github.com/merckhung/darwin_embedded
- **Licence:** BSD-2-Clause
- **Last push / stars:** 2026-10-19 / 13★
- **What it does:** builds **XNU 12377.121.6 (Darwin 25.5) on 64-bit Linux with Bazel** for x86_64 and ARM64, derived from PureDarwin. It boots a native Mach-O userland (BusyBox, Xfce4, Chromium) on QEMU `raspi4b` and `q35`, and includes a Pi 4B platform module (`BCM2711Platform`).
- **Helps scion-avf** with building XNU on Linux, so CI wouldn't need a Mac, and gives another generic-ARM platform expert.
- **Caveats:** macOS 26-era XNU rather than 8796 (iOS 16.5); the repo is about 300 MB with Chromium in Git LFS, so read it through the API.

### 19. taowen/arlinux-xnu
- **URL:** https://github.com/taowen/arlinux-xnu
- **Licence:** none in the repo; it carries in-tree GPL-3 Darling code.
- **Last push / stars:** 2026-10-22 / 0★
- **What it does:** runs iOS 16.7 UIKit, Foundation and frameworks (non-arm64e) on Linux ARM64 with **no XNU kernel**. It uses a hard fork of darlingserver for Mach IPC and Wayland for display and input. Apple's Calculator binary passes its test runs; the suite passes 19/23 journeys. Its docs review UniXNU NativeMetal, indium and metal2vulkan.
- **Helps Scion's app backend:** an alternative to running a full kernel, which could fit the Android host directly.
- **Caveats:**
  - Its README lists **decrypted-IPA download sites**; don't follow or copy that.
  - It needs iOS OS volumes from an IPSW.
  - CI-only build; Android is unverified.

### 20. VibeDarling (Darling development fork)
- **URLs:** [darling](https://github.com/VibeDarling/darling), [darlingserver](https://github.com/VibeDarling/darlingserver), [indium](https://github.com/VibeDarling/indium), [darling-metal](https://github.com/VibeDarling/darling-metal), [darling-cocotron](https://github.com/VibeDarling/darling-cocotron), [mslc](https://github.com/VibeDarling/mslc)
- **Licence:** GPL-3.0 (indium 0BSD, cocotron MIT, mslc MIT, darling-metal none)
- **Last push / stars:** 2026-10-22 / 20★
- **What it does:** an active Darling fork with ARM64 Mach-O loading and an ARM64 build of darlingserver.
  - The indium fork revives its Iridium AIR→SPIR-V translator for LLVM 23.
  - darling-metal is an ABI-compatible Metal framework.
  - The cocotron fork adds QuartzCore work: CAMetalLayer present pacing and CASpringAnimation.
  - Upstream darlinghq/darling's `feature/arm-support` branch has been idle since 2024-06.
- **Helps the app backend:** Darwin syscall and Mach IPC services on Linux, plus Metal and CoreAnimation reimplementations.
- **Caveats:** AI-agent-driven development; the CAMetalLayer path has not been run with a real app; macOS rather than iOS frameworks.

### 21. jelqedagain/ib-2-3-android
- **URL:** https://github.com/jelqedagain/ib-2-3-android
- **Licence:** MIT
- **Last push / stars:** 2026-10-19 / 20★
- **What it does:** runs ARM64 iOS Unreal Engine 3 games **natively on the Android CPU**, using its own Mach-O loader, libc, pthreads, an Objective-C runtime, and Foundation/UIKit subsets over GLES, AAudio and MediaCodec.
- **Helps the app backend:** a working proof of high-level emulation of 64-bit iOS on Android (touchHLE is 32-bit only). The Mach-O loader and Objective-C runtime glue are reusable.
- **Caveats:** narrow scope (two games); users supply their own IPAs.

### 22. kieroid/badapple
- **URL:** https://github.com/kieroid/badapple
- **Licence:** GPL-2.0+ OR MIT
- **Last push / stars:** 2026-10-22 / 1★
- **What it does:** mainline Linux on A12 (T8020), the closest SoC to t8030. Its `docs/` has register-level notes on ANS2 NVMe, SART v1, DART (4K pages, no protection bits), AIC, SMC (battery, buttons, power-off), the H11P display pipe, touch over SPI with firmware load, a DWC2 USB gadget, PCIe and the GPU coprocessor.
- **Helps Pyre** with peripheral modelling (display, SMC, touch, USB).
- **Caveats:** the `linux-*` patches are GPL-2.0, so reference only; the docs and `m1n1-*` patches are usable. The boot chain uses an exploit; ignore that part.

### 23. satyamisme/iEmu
- **URL:** https://github.com/satyamisme/iEmu
- **Licence:** AGPL-3 (Inferno-derived)
- **Last push / stars:** 2026-05-31 / 1★
- **What it does:** a t8030 derivative booting iOS 16 and 18.5. It adds an IOMobileFramebufferLegacy display path (device-tree properties on `disp0`, an H12P workaround), a GP_STATUS fix, an `hcd-dwc2` SFTDISCON fix, MMIO tracing, and a SEP-sim boot path; `docs/STATUS.md` is useful.
- **Helps Pyre** with display and USB fixes, and newer iOS on t8030.
- **Caveats:** mostly kernel patches; the "canonical" upstream repo is gone.

### 24. raafatabualazm/ios-linux-emulator (branch `linux-restore`)
- **URL:** https://github.com/raafatabualazm/ios-linux-emulator
- **Licence:** GPL-3/AGPL-3
- **Last push / stars:** 2026-10-17 / 0★
- **What it does:** a display scanout timer for restores with no viewer attached, a fix for A13 interrupts between CPU clusters, NVMe interrupt changes (`hw/nvme/ctrl.c` +68), and offline APFS patch tools for Linux.
- **Helps Pyre** with small NVMe and IPI fixes and restore without a Mac.
- **Caveats:** small; the APFS patching works on user-supplied images.

### 25. abdubanna/rusbmux and jkcoxson/idevice
- **URL:** https://github.com/abdubanna/rusbmux and https://github.com/jkcoxson/idevice
- **Licence:** Apache-2.0 / MIT
- **Last push / stars:** 2026-10-20 / 16★ and 2026-10-19 / 545★
- **What it does:** a pure-Rust drop-in usbmuxd, and a Rust lockdown/usbmux/services client.
- **Helps Pyre's USB-networking and tooling track:** both can run on Android without root, against Pyre's emulated usbmux socket, for SSH, AFC and device services.
- **Caveats:** these cover usbmux only; IP networking still needs the NCM path (item 3).

### 26. owngoal-dev/OpenSEP and OpenAVB
- **URL:** https://github.com/owngoal-dev/OpenSEP and https://github.com/owngoal-dev/OpenAVB
- **Licence:** MIT (OpenAVB: other)
- **Last push / stars:** 2026-10-19 / 13★ each
- **What it does:** clean-room C17 replacements for Apple's virtual-platform AP and SEP boot ROMs. OpenSEP implements the SEP mailbox protocol through AKE. Its `PLATFORM=t8110` build targets a non-public QEMU iPhone14,4 machine called "iphone-cli", which reaches the activation page on iOS 15-26. `docs/platforms.md` documents the SEP handoff ABI.
- **Helps Pyre's SEP model:** a reference for the SEPROM mailbox and handoff behaviour on a newer chip.
- **Caveats:**
  - **The t8110 build embeds a SEP firmware key table** (`src/platform/platform.c`); don't import it.
  - The ecosystem around it is jailbreak and custom firmware, and iphone-cli is not public.

### 27. dougallj/applegpu
- **URL:** https://github.com/dougallj/applegpu
- **Licence:** BSD-3-Clause
- **Last push / stars:** 2026-10-06 / 718★
- **What it does:** G13 (M1) GPU instruction-set documentation, a disassembler, an assembler and a Python emulator.
- **Helps Pyre's GPU track** if you ever decode or translate AGX shaders.
- **Caveats:** A13's GPU is G12 and its encodings may differ. No public G12 emulator exists.

### 28. hack-different/apple-knowledge and AsahiLinux/docs
- **URL:** https://github.com/hack-different/apple-knowledge and https://github.com/AsahiLinux/docs
- **Licence:** MIT / CC-BY-3.0
- **Last push / stars:** 2026-10-13 / 1417★ and 2026-10-18 / 2318★
- **What it does:**
  - apple-knowledge: RTKit, SEP, SEP memory-map and USB-mode notes, plus device YAML for iPhone12,1 that lists cs42l77.
  - Asahi docs: `hw/soc/` pages on aic, aop, asc, sep, smc, spmi, DCP, agx and apcie, and a reworked m1n1 hypervisor guide (2026-07).
- **Helps Pyre** with peripheral protocols.
- **Caveats:** apple-knowledge has exploit and key pages; skip those. Asahi docs describe M-series parts.

### 29. LeR33/ios-tegu
- **URL:** https://github.com/LeR33/ios-tegu
- **Licence:** none
- **Last push / stars:** 2026-10-21 / 0★
- **What it does:** a port of ios-sweet to the **Pixel 9a (Tensor G4)**. CI builds `kernel.development.tegu` from the 81-patch series on xnu-8796.141.3. It records the Tensor register map (GICv3 at 0x10400000, Exynos UFS, 24.576 MHz timer) taken from Android 17 firmware.
- **Helps:** a reference for Tensor-family hardware if Scion ever goes bare metal; little direct use for QEMU or AVF.
- **Caveats:** nothing has run on a device yet.

### 30. anurodhp's Raspberry Pi 3 XNU stack
- **URLs:** [xnu-iokit-pi3](https://github.com/anurodhp/xnu-iokit-pi3), [xnu-pi3-macos11](https://github.com/anurodhp/xnu-pi3-macos11), [qemu-xnu-arm](https://github.com/anurodhp/qemu-xnu-arm), [u-boot](https://github.com/anurodhp/u-boot)
- **Licence:** none (qemu-xnu-arm is QEMU's GPL)
- **Last push / stars:** 2026-10-18 / 16★
- **What it does:** macOS 11 XNU on a Pi 3. IOKit drivers are compiled from Apple's open-source IOHIDFamily, IOGraphics and USB families, with compat shims, plus Wi-Fi and Bluetooth drivers and CoreAudio work. The stack also includes a QEMU `raspi3` model with an `xnu-smp` spin table, and a U-Boot `bootxnu` command.
- **Helps scion-avf:** shows how to build Apple's open-source IOKit families (IOHIDFamily and others) into a kernel for generic ARM, the same approach DroidVM uses for IONetworkingFamily.
- **Caveats:** no licences; macOS 11 era.

### 31. litecreator/virtio-gpu-macos
- **URL:** https://github.com/litecreator/virtio-gpu-macos
- **Licence:** MIT
- **Last push / stars:** 2026-04-07 / 6★
- **What it does:** an IOKit virtio-gpu PCI kext (2D framebuffer, control and cursor queues, feature negotiation).
- **Helps scion-avf:** starter code for virtio-gpu over PCI under XNU.
- **Caveats:** alpha, built on IOFramebuffer (macOS) rather than IOMobileFramebuffer, no 3D.

### 32. joshuaswarren/metal2vk
- **URL:** https://github.com/joshuaswarren/metal2vk
- **Licence:** MIT
- **Last push / stars:** 2026-10-16 / 64★
- **What it does:** compiles MSL compute kernels to SPIR-V with clang, a `metal_stdlib` shim and clspv; 610 of 610 kernels from two ML engines compile.
- **Helps the app backend** for apps that ship MSL source.
- **Caveats:** compute only; it cannot read metallibs.

### 33. qualcomm/qemu-profiler
- **URL:** https://github.com/qualcomm/qemu-profiler
- **Licence:** BSD-3-Clause-Clear
- **Last push / stars:** 2026-10-13 / 0★
- **What it does:** a Rust TCG plugin that profiles every translation block.
- **Helps Pyre's TCG track:** find the hot guest TBs during an iOS boot before choosing fast paths. It is a separate plugin, so its licence doesn't touch Pyre.

### 34. Wawona/wwn-iomfb-rs and EthanArbuckle/ios-rendering-docs
- **URL:** https://github.com/Wawona/wwn-iomfb-rs and https://github.com/EthanArbuckle/ios-rendering-docs
- **Licence:** MIT / none
- **Last push / stars:** 2026-10-10 / 0★ and 2025-08-09 / 195★
- **What it does:** a reconstruction of the IOMobileFramebuffer userspace ABI (`docs/ABI.md` lists 153 exports), and documentation of the iOS render path (CAContext, the render server, fences, CAMetalLayer).
- **Helps scion-avf display:** the contract your IOMobileFramebuffer service must satisfy for the render server.
- **Caveats:** wwn-iomfb-rs was reverse-engineered on a jailbroken iOS 26.1 VM and targets TrollStore and jailbreak use; read the docs only.

### 35. Gunyah tooling: Droid-VM/DroidVM, Droid-VM/edk2-gunyah, code002-2/librevm, AnyLaySys/qemu-gunyah
- **URLs:** [DroidVM](https://github.com/Droid-VM/DroidVM), [edk2-gunyah](https://github.com/Droid-VM/edk2-gunyah), [librevm](https://github.com/code002-2/librevm), [qemu-gunyah](https://github.com/AnyLaySys/qemu-gunyah)
- **Licence:** GPL-3.0 (DroidVM, librevm), BSD-2-Clause-Patent (edk2-gunyah), other (qemu-gunyah)
- **Last push / stars:** 2026-10-19 / 929★, 26★, 5★, 21★
- **What it does:**
  - DroidVM: a VM manager app for Gunyah, GenieZone and KVM, with VirGL, gfxstream and VNC display and display casting.
  - edk2-gunyah: UEFI firmware for crosvm.
  - librevm: a Rust crosvm manager.
  - qemu-gunyah: QEMU with a Gunyah accelerator and Android AGL, AAudio and TAP backends.
- **Helps the app backend:** UI and lifecycle patterns. edk2-gunyah is an option if scion-avf ever boots through UEFI rather than as an `Image`. qemu-gunyah's AAudio and AGL backends are a reference for Pyre's Android audio and display.
- **Caveats:** Snapdragon/Gunyah-specific; most need root.

### 36. Lower priority: TCG and dynarec references
- **DynamoRIO/dynamorio** (BSD-style, 2026-10-22, 3187★) and **QBDI/QBDI** (Apache-2.0, 2026-09-02, 1830★): same-ISA AArch64 code-cache techniques (stolen registers, PC-relative rewriting, inline indirect-branch lookup). Apache-2.0 code is compatible with Pyre's GPL-3 code.
- **FEX-Emu/FEX** and **ptitSeb/box64** (MIT, 8.3k★ and 5.7k★): block linking and host-FP ideas. x86 guests only.
- **avisi-group/captive** (MIT, 2025-12-11, 47★): a full-system DBT that runs translated guest code inside a KVM VM and uses the host MMU. Concept only; it needs `/dev/kvm`.
- **AlexZorzi/Inferno-Android** (Inferno licence, 2026-07-01, 25★): Inferno bionic build for an Android 17 Snapdragon device; useful to cross-check Pyre's build. The author calls it "very slow".
- **AsahiLinux/m1n1** (MIT): `src/hv/hv_sprr.c` and `gxf_asm.S` for checking SPRR/GXF semantics, and for justifying caching SPRR permission decisions in TB flags instead of checking them on every access.

---

## Known projects: derivatives worth noting
- **MakrSas/ios-topaz** (fork of ios-sweet, no licence, 500★, 2026-10-20) adds display-controller scanout of IOSurfaces. Its Adreno 610 GPU acceleration uses HackiOS patches.
- **ios-sweet/HackiOS ports:** dozens exist (ios-renoir, ios-dm3q, ios-fajita and others). None add virtualisation work; skip them.
- **Inferno forks:** about 330 exist. Only MakrSas, SoftShim, hashFactory, satyamisme/iEmu and raafatabualazm carry substantive peripheral or TCG changes (items 3, 9, 13, 23, 24).
- **qemu-t8030** (TrungNguyen1909) has been idle since 2022. The darwin-vm forks are mirrors with nothing added.

## Excluded or not recommended
- **BigfootACA/unixnu-droidvm-ci:** publishes encrypted artefacts containing Apple kernelcache components.
- **Lakr233/vphone-cli** (15.9k★) and its ecosystem: Mac-host only, patches Apple firmware, with jailbreak variants. It does confirm that Apple's iOS 26 PCC research VMs ship paravirtual GPU support, which iOS 14 and 16 retail kernels lack.
- **MarbleLabs1/IOS-emulator:** README claims (iPhone 14/15 emulation from one command) look unfounded.
- **Exploit and key tools:** usbliter8ra1n, NyanSatan/Anya, T8020 jailbreak repos.
- **Not found anywhere:**
  - an XNU Venus or virtio-gpu native-context guest driver;
  - an AGX G12 emulator;
  - a Mesa Vulkan driver for the PowerVR DXT GPU;
  - a QEMU fork with an SPRR/GXF fast path;
  - an open Corellium-like project;
  - another public t8030/t8101 machine besides Pyre.
