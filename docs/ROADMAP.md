# Roadmap

Ordered roughly by payoff over effort. "Measured" items need a booted VM on a
Pixel 10 first, so numbers come later.

## 0. First boot on the phone
- [ ] Boot a Mac-prepared iOS 14 VM end to end on a Pixel 10. Record boot time,
      the log, and anything bionic-specific that breaks at runtime (as opposed
      to compile time).
- [ ] Check that the TCG code buffer can be RWX inside an app process. If not,
      default `split-wx=on` (memfd double mapping).
- [ ] Confirm the phantom-process killer leaves the QEMU child alone while the
      foreground service runs. If it doesn't, document
      `adb shell settings put global settings_enable_monitor_phantom_procs false`,
      or move to the in-process design in §2.

## 1. CPU (Tensor G5)
- [x] Pin the 6 AP vCPUs to the big cores (cpu2–7) and the SEP vCPU to the
      little cores (`QEMU_VCPU_AFFINITY`).
- [ ] Measure X4-only vs. X4+A725 placement for vCPU0. On XNU, CPU0 handles
      most interrupts.
- [ ] Profile with simpleperf. Hot spots usually show up in TB lookup,
      softmmu TLB fills and the guest's 16K-page MMU walks (t8030 sets
      `minimum_page_bits = 14`). Then tune `tb-size` and the TLB size.
- [ ] Try the 16 KB page size kernel (Pixel developer option) so host pages
      match guest pages.
- [x] PGO/LTO builds of QEMU using a boot trace as the profile (opt-in, measured in `docs/PERF.md`, "Compiler and linker options"; not on by default).
- [ ] Use Android's performance hints (`APerformanceHintManager`) for the
      vCPU threads instead of hard affinity.

## 2. Display path
Currently: QEMU VNC → raw RFB over a unix socket → `Bitmap.setPixels` → HWUI.
That's simple and works, but it copies every frame several times.
- [ ] Add a QEMU UI backend (`ui/android.c`) that blits the console surface
      straight into an `ANativeWindow`. QEMU then runs in-process (a `.so`
      loaded in a dedicated `:vm` service process) instead of as a child process.
      This also fixes the phantom-process concern.
- [ ] Upload the framebuffer as a GLES/Vulkan texture through
      `AHardwareBuffer`, so the PowerVR GPU does scaling and colour conversion.
- [ ] Multi-touch: the RFB pointer is single-touch. With an in-process
      backend, pass the Android `MotionEvent` pointers straight to
      `mt-spi` (it already models path stages per finger).

## 3. Guest GPU (Imagination PowerVR)
See [GPU.md](GPU.md) for the design: options (AGX emulation, paravirtual GL through
virtio-gpu + gfxstream, cheaper wins), the recommendation, and the first milestone
measured on the phone (an AVF guest drawing with the PowerVR through gfxstream).

Inferno has no Apple GPU emulation. iOS runs with software rendering enabled
by filesystem patches, so all of SpringBoard's compositing happens on the
emulated CPU. Moving that work onto the Pixel's PowerVR GPU is the biggest
speed-up available, and also the biggest project:
- [ ] Short term: nothing to do on the host. The guest never issues GPU work.
- [ ] Research: a paravirtual Metal path. That means a guest IOKit/Metal
      driver (needs a jailbroken guest) that forwards command buffers to a
      host renderer translating Metal → Vulkan, running on the PowerVR Vulkan
      driver. Comparable in scope to MoltenVK in reverse. Prior art worth
      studying: virtio-gpu Venus, and Asahi's AGX reverse-engineering
      (for command-stream semantics).
- [ ] The Pixel 10 kernel's PowerVR driver is a Rogue-family DDK
      (`private/google-modules/powervr`, BVNC `71.2.2448.1212`). It's useful
      for understanding Vulkan extension support on the host side.

## 4. Storage and setup
- [ ] Copy VM disks into internal storage (no FUSE) on request, and measure
      the difference.
- [ ] Lazy qcow2 cluster allocation and `discard=unmap`, so the image doesn't
      grow forever.
- [ ] Support the IPSW restore in the app once upstream finishes its
      "restore without companion VM" work.
