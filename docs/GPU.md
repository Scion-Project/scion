# GPU: getting iOS's UI onto the Pixel's GPU

Status: design plus milestones M1 and M2, measured on the phone (2026-10-22). Work is paused
here; see §8 for where it stands and what comes next. Scope: the two
Scion backends, **QEMU/Pyre** (emulated iPhone 11 / t8030, iOS 14, Apple's kernel) and
**AVF/HackiOS** (open-source XNU + iOS 16.5 userland in a pKVM VM). The host is a Pixel 10:
Tensor G5, Imagination **PowerVR D-Series DXT-48-1536**, OpenGL ES 3.2 and Vulkan 1.3
(driver build 26.1). [ETHICS.md](ETHICS.md) applies throughout. No Apple binaries,
firmware, shader binaries or captures go into any repository.

## Summary

| | (A) Emulate the A13 GPU (AGX) in Pyre | (B) Paravirtual GL through virtio-gpu + gfxstream | (C) Cheaper wins |
|---|---|---|---|
| Guest changes | none (Apple's own driver and firmware interface) | a guest driver and a GL library behind CoreAnimation | settings, or none |
| Backends | QEMU/Pyre only | **AVF now**; QEMU with a pipe device later | mostly QEMU |
| Effort | 18–35 person-months (Pyre scoping study); M0 under way | **~3–5 PM** to CoreAnimation on the GPU on AVF; +2–4 PM for Pyre | days to weeks each |
| UI speedup | full GPU speed once M3 is done. Until then (M1, software TBDR) it's *slower* than today | compositing measured **~22x** cheaper than the guest CPU at native speed; much more against TCG | 1.2–3x, workload-dependent |
| Metal apps | yes, eventually (the real driver) | not by this path; later via gfxstream-Vulkan | no |
| Risk | G12P encodings undocumented; firmware-interface churn | the CoreAnimation GL path (proven by HackiOS on Adreno); upload bandwidth | low |

**Recommendation: (B) on AVF first.** We control the kernel and the userland there, and the
Pixel's own crosvm already ships the host half: **gfxstream**, drawing with the PowerVR
through EGL/GLES or Vulkan. The first milestone is implemented and works on the phone. A
guest under AVF brings up a virtio-gpu 3D context and opens a gfxstream pipe. The
PowerVR draws a picture (cleared, scissored, a shaded triangle) that is read back and
checked, 7/7. A CoreAnimation-shaped compositing load (12 full-screen alpha-blended textured
layers at 828×1792) takes **0.78 ms per frame on the GPU against 17.6 ms on the guest CPU**,
with NEON at native KVM speed, and the two pictures agree to 0.2/255 on average. Keep (A)
on its M0-gated research track for Pyre. Take the cheap (C) items for Pyre now. Bring (B)
to Pyre once the AVF guest library exists, since the guest side carries over.

---

## 1. Where rendering happens today

**QEMU/Pyre.** There's no GPU. `gfx-asc` is stripped from the device tree, and the guest
runs with software rendering forced by filesystem patches (ROADMAP §3). CoreAnimation's
render server (backboardd) rasterizes and composites every layer on the emulated CPU into
IOSurfaces, and `disp0` scans them out. Every pixel of every layer, blur and shadow goes
through TCG. That is the main reason the UI is slow. Nobody has measured UI frame times on
Pyre yet. A `perf` profile of a SpringBoard swipe (share of CoreAnimation/CoreGraphics
blitters) is the first number to take (R7 of the Pyre study).

**AVF/HackiOS.** HackiOS replaces the window server's `EAGLContext`
(`hackios-xnu-userland/userland/src/eagl.c`, injected into backboardd as
`libredwood-gl.dylib`). CoreAnimation's render server tries Metal, then OpenGL ES (an
`EAGLContext` of API 3), then its own software rasterizer. eagl.c answers the OpenGL ES
request with a table of `gl*` functions at the offsets of iOS's own OpenGLES
(`getMacroContextPrivate`). It also covers the two IOSurface hooks
(`texImageIOSurface:` and `attachImage:toCoreSurface:`) and some fixups for what CA
expects of Apple's library: BGRA, pixel-coordinate samplers, program-less draws. Behind it
is Mesa 25.0.7 through OSMesa (`tools/mk-mesa.sh`). On the Poco X5 Pro that's freedreno on
the Adreno 642L, through `redwood-drm`, a libdrm stand-in that talks to the kernel's
RedwoodGPU. Without a GPU it's softpipe. **On the Pixel there's no Adreno**, so today an
AVF guest would composite with softpipe, or with CA's software path, on the vCPUs.

What this tells us: iOS 16.5's CoreAnimation renders its whole UI through OpenGL ES if
offered one. HackiOS has shown that on real hardware, with lock screen, home screen,
Control Center and apps. So the guest work for a GPU path on AVF is the same bridge,
pointed at a different GL library.

## 2. What the Pixel's AVF offers (measured)

From `com.google.android.virt` on build `DP11.260918.006` (Android 17) and from VMs run by
`scion-avf/gpu3d/run-on-device.sh`:

* **crosvm is built with gfxstream, not virglrenderer.** It has gfxstream's host GL and
  Vulkan decoders (`gfxstream::host::gl`, `::vk`) and rutabaga's `virgl`, `venus`,
  `cross-domain`, `gfxstream-gles`, `gfxstream-vulkan` and `gfxstream-composer` context
  names. There are no virglrenderer (`vrend_*`) symbols. So Mesa's virgl and Venus guest
  drivers have nothing to talk to. **gfxstream is the protocol.**
* **The custom-VM API exposes it.** `VirtualMachineCustomImageConfig.GpuConfig` (`@hide`,
  covered by the hidden-API exemption Scion already sets) has `setBackend`,
  `setContextTypes`, `setRendererUseEgl/Gles/Surfaceless/Vulkan` and `setRendererFeatures`.
  virtmgr turns it into
  `--gpu=backend=gfxstream,context-types=gfxstream-gles,egl=true,gles=true,surfaceless=true`.
  The `vm` shell tool has no GPU option, so an app is needed.
* **The renderer is the PowerVR.** gfxstream logs `Graphics Adapter Android Emulator OpenGL
  ES Translator (PowerVR D-Series DXT-48-1536)`, `OpenGL ES 3.2 build 26.1@6967606`, and
  loads `libvulkan.so` too. The guest sees vendor `Google (Imagination Technologies)` and
  version `OpenGL ES 3.1`.
* **The device**: virtio-gpu over virtio-pci (`1af4:1050`), features VIRGL, EDID,
  RESOURCE_UUID, **RESOURCE_BLOB**, **CONTEXT_INIT**, and a **host-visible shared-memory
  region of 8 GiB** (virtio-pci shm id 1, at IPA 0x2_0000_0000). It has 16 scanouts, the
  first enabled at 1280×1024 even without a display. One capset is offered per requested
  context type, e.g. `gfxstream-gles` v0 (ring 12288, buffer 1 MiB, blob alignment 4096).
* **Pitfall**: crosvm activates virtio-gpu only after **both** queues (control and cursor)
  are set up ("pcivirtio-gpu activate failed: expected 2 queues, got 1").
* With no `DisplayConfig` it logs "failed to open display" and runs headless. Showing a
  scanout needs the display service, which is agent D's 2D display work (`avf/display`).

## 3. The options

### (A) Emulate the A13 GPU (AGX) in Pyre

The guest's own `AGXG12P` kext, the IOGPU family and Metal run unmodified. Pyre high-level
emulates the `gfx-asc` firmware (RTKit, initdata, channels, work queues, microsequences, UAT)
and translates the submitted work: control streams, then the USC shader ISA → IR → SPIR-V,
then Vulkan on the PowerVR. The full plan is Pyre's `docs/GPU-EMULATION-SCOPE.md` (cd985f1).

* **What runs where:** guest = Apple's Metal stack, unchanged. Pyre (in the app process) =
  AGX device model, firmware HLE, shader translator, Vulkan backend. PowerVR = the
  translated work.
* **State:** M0 is under way (#46, #48). `-M t8030,gpu=on` keeps `gfx-asc`, RTBuddy resumes
  the "pre-loaded" firmware, the RTKit handshake completes, and the driver sends MSG_INIT
  with an initdata pointer that the UAT walker resolves. The G12P initdata top level matches
  m1n1's G13 `InitData`. After MSG_INIT there's no further GPU traffic in a restore boot.
* **Effort:** 18–35 PM (M0 2–4, M1 6–12, M2 4–7, M3 6–12). The ranges are wide because the
  G12P control-stream and ISA encodings are undocumented (all public RE is G13+).
* **Speedup:** the only option that ends with *Metal apps* on the GPU. Until M3 there's no
  gain: M1's software TBDR on the emulated machine would be slower than CA's own software
  path. At M3 the UI could approach host-GPU speed, but the TCG CPU remains, and the
  CPU→GPU coherency (R5) costs readbacks.
* **Compatibility:** best in principle (unmodified guest, every app). It's tied to one
  firmware-interface version per guest build (R2), and it **does nothing for AVF**: crosvm
  offers no way to add a custom device model.
* **Verdict:** keep it as the long-term research track, gated on M0's trace corpus, as the
  study recommends. It's not the way to a faster UI this year.

### (B) Paravirtual: a guest GL bridge forwarding to the host GPU

The guest keeps CoreAnimation's OpenGL ES path, as on HackiOS today, but the GL library
behind `eagl.c` **encodes calls** instead of rendering them. The encoded calls travel over
virtio-gpu to the host, where gfxstream decodes them onto the PowerVR's own GLES driver. The
picture CA renders lives in a host colour buffer, and the display scans that buffer out
without a copy through the guest.

```
 guest (AVF VM, iOS 16.5 userland on XNU)                  host (Android, crosvm process)
 ──────────────────────────────────────────               ───────────────────────────────────
 backboardd ─ CoreAnimation render server                  crosvm virtio-gpu (rutabaga)
   └ EAGLContext → eagl.c (libredwood-gl)                    └ gfxstream frontend
       └ GLES encoder (gfxstream wire format)                    ├ render thread per context:
           └ /dev or IOUserClient → kernel                       │   renderControl + GLES decoders
               └ redwood_vgpu3d: ctx, pipe/ASG ring,  ═virtio═▶ │   → EGL/GLES (PowerVR)
                  blobs, scanout                                 └ colour buffers ─▶ scanout
                                                                    (AVF display, agent D)
```

* **Transport choices** (all gfxstream, all offered by the Pixel's crosvm):
  1. **Pipe** (done): a `PIPE_BUFFER` resource with bind `VIRGL_BIND_CUSTOM` is a byte
     stream. Writes are `TRANSFER_TO_HOST_3D` and replies `TRANSFER_FROM_HOST_3D`. It
     needs only VIRGL. It's simple, but each transfer is a synchronous control-queue
     command (≈60–90 µs round trip measured).
  2. **ASG ring** (next): a host-visible blob (`RESOURCE_CREATE_BLOB` HOST3D, mapped
     through the 8 GiB shm BAR) holding a ring the render thread polls, kicked with
     `GFXSTREAM_CONTEXT_PING`. It needs RESOURCE_BLOB plus the shm region (both offered)
     and lets encoding run without control-queue round trips. This is what gfxstream's own
     Linux and Android guests use.
  3. **gfxstream-Vulkan** (later, for Metal): the guest Vulkan encoder (Mesa's
     `gfxstream_vk`), with Zink for GL if wanted. It's the base for running translated
     Metal (HackiOS's AIR→NIR work, `docs/air.md`) through Vulkan instead of ir3.
* **Guest pieces:**
  - *Kernel*: `bsd/dev/redwood_vgpu3d.c` (done, see §4). It covers the virtio-gpu 3D
    commands, the gfxstream pipe, the renderControl/GLES2 encoding and a self-test, on one
    `ctrl()` hook into the display driver's control queue. Next comes an IOUserClient for
    userland: context create, resources, transfers or blob maps, waits.
  - *Userland*: a GLES library for eagl.c. Two candidates: (i) **gfxstream's guest GLES
    encoder** (`guest/GLESv2_enc`, `renderControl_enc`, `OpenglSystemCommon`), ported to
    Darwin with a `VirtGpuDevice` for our user client. GLSL goes to the host as text, so
    there's no compiler in the guest, and the PowerVR's own compiler does the work.
    (ii) Mesa with a new gallium winsys. Mesa has no gfxstream-GLES gallium driver, so
    that means Zink on `gfxstream_vk`: more layers, more risk. **(i) is the plan.** eagl.c
    already isolates "the library behind us" (`redwood_gl.h`), so the swap is a library and
    a handful of hooks (`redwood_gl_surface_texture`, frame end).
  - *IOSurfaces*: CA textures from IOSurfaces (app windows, CG-drawn content) and renders
    into the framebuffer IOSurface. First, copies: `glTexSubImage2D` through the stream
    (measured: 256 KiB in 0.2–0.5 ms, roughly 0.5–1 GB/s) and readback for surfaces the
    guest reads (305–330 MB/s). Then zero-copy: IOSurfaces backed by host-visible blobs, so
    gfxstream colour buffers alias guest-visible memory.
  - *Scanout*: CA's framebuffer as a virtio-gpu 3D resource (`RESOURCE_CREATE_3D`,
    `BIND_SCANOUT`, which gfxstream makes a colour buffer with the same handle), then
    `SET_SCANOUT` + `RESOURCE_FLUSH`. That path is implemented and accepted by the host,
    and becomes visible once agent D's display service is attached.
* **Effort (AVF):** kernel user client and ASG ring 3–4 weeks. Darwin port of the gfxstream
  GLES encoder plus eagl.c integration 4–8 weeks (HackiOS's fixups for BGRA, pixel
  samplers and program-less draws carry over). IOSurface blobs and scanout 3–5 weeks.
  **About 3–5 PM to SpringBoard composited on the PowerVR.** Metal for apps through
  gfxstream-Vulkan is a separate 6–12 PM project.
* **Speedup (AVF):** measured on the phone (§4). Compositing is **~11x** (4 layers) to
  **~22x** (12 layers) cheaper than doing it on the guest CPU with NEON at native speed. The
  GPU frame cost grows only from 0.55 to 0.78 ms as layers triple, so it is per-frame
  overhead bound, not fill bound. In practice CoreAnimation's render server stops being the
  bottleneck, and the UI frame rate is set by app CPU work, CA commit and IPC. Expect a
  locked 60 Hz for compositing-heavy screens (blur, Control Center, app switcher) that
  would take tens of ms per frame on the CPU.
* **Compatibility:** anything CoreAnimation draws (the whole system UI, UIKit apps) goes
  through it. Metal apps and Metal-only frameworks don't (as on HackiOS today). The bridge
  depends on CA's GL path, which exists in iOS 16.5 (proven by HackiOS). It needs eagl.c's
  injection into backboardd, which HackiOS already does.
* **QEMU/Pyre variant:** the same guest library and wire format. Pyre would link
  gfxstream's host library into the app (an NDK build of gfxstream, not trivial) and expose
  a pipe to the guest. t8030 has no PCI, so that means either a **virtio-mmio** virtio-gpu
  (the same kernel layer, but iOS 14's kernel needs a kext: hard on a prelinked
  kernelcache) or a **userland hypercall** that the TCG translator intercepts. For the
  hypercall, a reserved `HINT` encoding executed by backboardd passes a guest VA and
  length, and Pyre copies the bytes to the render thread through the guest MMU, with no
  guest kernel change. iOS 14's CA must still offer the GLES path (to check), and the final
  frame goes from the host colour buffer straight to the Android surface. That skips the
  `disp0` readback, which becomes a Pyre display-path change. **+2–4 PM after the AVF
  library**, and the gain is larger than on AVF because the CPU it relieves is the emulated
  one.

### (C) Cheaper wins (mostly for QEMU/Pyre)

| Item | What | Effort | Expected effect |
|---|---|---|---|
| C1 Reduce Transparency / Reduce Motion | accessibility settings in the user's own image: no backdrop blurs, no parallax/zoom animations | hours | Blurs are CA's most expensive effect (multi-pass, full-screen). On blur-heavy screens (Control Center, folders, Notification Center, dock) **~1.5–3x** fewer pixels touched. Cosmetic cost only |
| C2 Render at reduced scale | lower the guest's display scale (e.g. render at 2x instead of 3x pixels) and let the Pixel GPU upscale in the display path (ROADMAP §2, `AHardwareBuffer`) | days | Pixel count drops 2.25x, roughly that much less raster work. Text is softer |
| C3 TCG NEON coverage for CA's blitters | profile backboardd under Pyre, then make sure the hot NEON ops (widening multiply-accumulate, narrowing shifts, `uzp/zip`, `tbl`) use TCG gvec host-vector code, not per-element helpers | 2–4 weeks | Only on the CA share of the profile. Plausibly 1.2–2x there, since Pyre's native-FP work showed what helper-to-host lowering gives |
| C4 More display planes in `disp0` | let CA "detach" full-screen layers (video, fullscreen apps) onto IOMFB hardware layers that Pyre composes on the host GPU | 3–6 weeks | Big for full-screen video/games, little for the home screen. CA's plane-assignment heuristics decide |
| C5 Display path (ROADMAP §2) | in-process `ANativeWindow` blit, GPU scaling | 1–2 weeks | Removes host-side copies (not guest CPU time): smoother presentation, fewer dropped frames |

C1 and C2 can be done this week and stack. C3 needs the profile first. None of these makes
Pyre's UI fast; they make it less slow until (B) or (A) arrives. "Host-side compositing of
CoreAnimation layers" at the layer-tree level would need CA's private render protocol
(server-side `CA::Render` objects) intercepted and replayed on the host. That's more fragile
than (B), which takes the same layers at the GL level that CA already offers.

## 4. Milestone 1 (implemented): virtio-gpu 3D on AVF, the PowerVR drawing for a guest

Code:

* **hackios-xnu-kernel `avf/gpu3d`**
  - `xnu/files/bsd/sys/redwood_vgpu3d.h`, `xnu/files/bsd/dev/redwood_vgpu3d.c`: the
    portable layer. Plain C with no allocation and no FP (GL floats as bit patterns), so it
    builds both into the kernel (`-mkernel`) and freestanding. It covers capsets,
    `CTX_CREATE` with `context_init`, the pipe, the renderControl/GLES2 encoders and
    `vgpu3d_selftest()`.
  - `xnu/files/bsd/dev/redwood_vgpu3d_xnu.c` + `xnu/patches/1300-virtio-gpu-3d.patch`: the
    kernel side. Boot-arg `gpu3d=1` runs the bring-up and self-test in a kernel thread over
    the `vio_*` transport (avf/virtio). It drives the device itself until the display
    driver exports its control queue (§6).
  - `avf/gpu3d/probe/`: a **bare-metal AVF guest**, an arm64 Image reusing `avf/shim`. It
    turns the MMU on, enumerates PCI (crosvm CAM / QEMU ECAM), drives virtio-pci 1.x and
    runs the same layer. `bench.c` runs the GPU-against-CPU compositing comparison.
* **scion-avf `gpu3d/probe`**: `gpu3d/app` (package `io.github.dylanneve1.gpu3dprobe`), a
  harness that boots a custom VM with a `GpuConfig`, and `gpu3d/run-on-device.sh`, which
  takes the phone lock, grants, sets and restores the exemption, and pulls the console and
  logcat.

Sequence (all in the guest): negotiate VIRGL | CONTEXT_INIT | RESOURCE_BLOB. Then
`GET_CAPSET_INFO`/`GET_CAPSET` (gfxstream-gles). Then `CTX_CREATE`(capset 8) and a 1 MiB
`PIPE_BUFFER` resource with `ATTACH_BACKING` and `CTX_ATTACH_RESOURCE`. Write
`"pipe:opengles"` and the flags word. Then `rcChooseConfig`, `rcCreateContext`(GLES2),
`rcCreateWindowSurface`, `rcCreateColorBuffer`, `rcSetWindowColorBuffer` and
`rcMakeCurrent`. Compile and link GLSL on the host. Draw a clear, three scissored bars and
a vertex-coloured triangle. Then `rcFlushWindowColorBuffer` and `rcReadColorBuffer` back
into the guest, check the pixels and time everything.

Results, Pixel 10, non-protected AVF VM, 1 vCPU, 828×1792 (the iPhone 11's points × 2):

| | |
|---|---|
| host GL renderer, as the guest reads it | `Android Emulator OpenGL ES Translator (PowerVR D-Series DXT-48-1536)` |
| bring-up (context, pipe, connect) | 7.7 ms |
| self-test picture | **7/7 checks** (background, three bars, the triangle's interpolated centre, `glGetError`, link status) |
| one round trip guest → render thread → guest | 60–93 µs |
| frame: clear + 3 scissored clears + shaded triangle + flush, waited for | 0.55 ms (≈1800 fps) |
| full-frame readback (5.9 MB) | 18–19 ms (305–330 MB/s) |
| control-queue commands for the whole run | 379 (avg 250 µs each, including host work) |

The picture as read back from the host (downsampled; R/G/B = pure bars, r/g/b = dominant
channel of the shaded triangle):

```
RRRRRRRRRRRRRRRRGGGGGGGGGGGGGGGGBBBBBBBBBBBBBBBB
.......................BB.......................
...................bbbbbbbbbb...................
...............bbbbbbbbbbbbbbbbbb...............
..............rrrrrrbbbbbbbbgggggg..............
..........rrrrrrrrrrrrrrgggggggggggggg..........
......RRRRRRrrrrrrrrrrrrggggggggggggGGGGGG......
................................................
```

**The same test inside XNU (M2).** The kernel built by CI on `avf/gpu3d` was booted by avfboot under AVF on
the phone (boot-args `gpu3d=1 -v`, `hello` as the RAM disk). It found the virtio-gpu through
the `vio_*` transport, created the gfxstream context and passed the self-test with the same
picture (identical FNV-1a `2b12064a`): **7/7, `GPU3D-DONE pass`**. Its timings were round
trip 111 µs, frame 0.59 ms, readback 25 ms and the whole self-test 179 ms, before process
1 started. The 3D layer is therefore working in Apple's open-source XNU on the Pixel's
hypervisor.

Compositing comparison (`bench.c`). It runs N full-screen textured quads (a 256×256 RGBA
texture, nearest, src-alpha blending) per frame, on the host GPU through the stream with the
guest waiting for each frame. It compares against the same frame blended by the guest CPU
(`cpublend.c`, NEON at -O3, natively under KVM on the Cortex-X4/A725):

| layers | GPU frame | guest CPU frame | ratio | GPU vs CPU picture |
|---|---|---|---|---|
| 4 | 0.55–0.58 ms | 6.5–6.6 ms | **11.5x** | mean \|diff\| 0.23/255 |
| 12 | 0.78–0.82 ms | 17.5–17.6 ms | **22x** | mean \|diff\| 0.22/255 |

Notes. The CPU number is a *native-speed* NEON blender. Under Pyre the same work runs
through TCG, several times slower, so the ratio there would be correspondingly larger. CA's
real frames add blurs and shadows, which cost the CPU far more than plain blends and cost
the GPU little. The GPU number includes the full round trip, so it's what CA's frame end
would wait for.

## 5. Plan

| # | Milestone | Where | Exit criterion |
|---|---|---|---|
| **M1** | virtio-gpu 3D bring-up, PowerVR draws for the guest (**done**) | probe + kernel layer | self-test 7/7 on the phone; timings above |
| **M2** | the kernel layer in XNU on the phone (`gpu3d=1`) (**done in standalone mode**); next, move it onto agent D's control queue | hackios-xnu-kernel | `GPU3D-DONE pass` in the XNU console (**met**, see §4); picture on the AVF display once D's display service is up |
| M3 | ASG ring + blobs; an IOUserClient for userland | kernel | encode without control-queue round trips; ≥1 GB/s texture upload |
| M4 | Darwin port of gfxstream's GLES encoder as eagl.c's library | hackios-xnu-userland | backboardd draws the lock screen through the PowerVR; HackiOS's GL fixups pass |
| M5 | IOSurfaces as host-visible blobs; CA framebuffer = scanout resource | both | no per-frame guest copies; SpringBoard swipe at 60 Hz with frame times logged (`glframes=1`) |
| M6 | Pyre: gfxstream host in the app + EL0 hypercall pipe; same library on iOS 14 | pyre, scion | iOS 14 SpringBoard composited on the PowerVR under QEMU |
| later | Metal via gfxstream-Vulkan (AIR → NIR → SPIR-V) | userland | a Metal sample app |

Meanwhile: C1/C2 for Pyre users now, then C3 after a profile. (A) continues to its M0
decision point.

## 6. Coordination

* **Agent D (virtio-gpu 2D display, `avf/display`)** owns the device. The 3D layer needs
  three things from D's driver. First, negotiate VIRGL (bit 0) and CONTEXT_INIT (bit 4)
  when offered, and RESOURCE_BLOB (bit 3) for M3. Second, export one serialized
  control-queue call (`ctrl(cmd, len, resp, len)`). Third, keep resource IDs below 0x10000,
  since the 3D layer allocates from 0x10000. Until then, `gpu3d=1` is a bring-up mode that
  drives the device itself, and D's driver should leave the device alone when it sees it.
* **Agent B (virtio-pci transport, avf/virtio)**: M3 needs the virtio-pci shared-memory
  capability (cfg_type 8, shmid 1, the host-visible region) from the transport.
* Kernel patches for this track are numbered **1300–1399** (`xnu/patches/README.md`).

## 7. Related public work (from a GitHub scan; not yet evaluated in depth)

| Project | What | Use to us |
|---|---|---|
| steelbrain/metal2vulkan, steelbrain/reims-vgpu | metallib → SPIR-V translator, a Metal-like device on Vulkan, a paravirtual GPU host | Most relevant to the later Metal step (gfxstream-Vulkan). **Licence unclear**: check it before copying anything |
| xVanTuring/UniXNU-qemu (BSD-2-Clause-Patent) | a guest Metal device on generic-ARM XNU, with the work run on the host GPU through a shared-memory window | Closest prior art to (B) for Metal. The licence allows reuse; compare its window protocol with gfxstream's ASG ring |
| google/gfxstream (Apache-2.0) | the host renderer in AVF's crosvm, and the guest encoders we port | The protocol of record. Open PRs #203–207 add an Android buffer backend, which matters for zero-copy scanout |
| MakrSas/inferno-vgpu-kext, metal2vulkan-inferno | Metal inside an Inferno iOS 14 guest | Reference only: no licence, it patches kernelcache bytes and may contain Apple-derived dumps. **Don't copy** |
| DarcJC/Viruasior | a zero-copy display bridge plus Venus on a Mali host | Reference only (no licence). Its display bridge is relevant to M5 |

None of these changes the recommendation: Mesa has no Pixel 10 GPU driver, so on the host
gfxstream is the realistic route, and the Pixel's crosvm already ships it. They matter for
the Metal step after M5, and for the Pyre variant (UniXNU's shared-memory window is
another way to get a pipe without virtio).

## 8. Status and next steps (paused 2026-10-22)

* **Done:** M1, a bare-metal AVF guest drawing with the PowerVR through gfxstream, checked
  and timed. M2, the same layer in XNU on the phone, passing with `gpu3d=1` in standalone
  mode. The compositing comparison: 11–22x cheaper than the guest CPU at native speed.
* **Next:**
  1. Put the 3D layer on agent D's control queue once `avf/display` exports it (`ctrl()`,
     VIRGL/CONTEXT_INIT negotiation, resource IDs ≥ 0x10000), and show the scanout
     resource on the AVF display.
  2. M3: the ASG ring over a host-visible blob, which needs the shm region from agent B's
     transport, and an IOUserClient.
  3. M4: the Darwin port of gfxstream's GLES encoder behind eagl.c.
* **Open questions:** whether iOS 14's CoreAnimation still offers the GLES path (this
  decides the Pyre variant); the licences of the projects in §7.

## 9. Reproducing

```sh
# bare-metal probe on the phone
hackios-xnu-kernel/avf/gpu3d/probe/build.sh                 # clang + ld.lld -> out/Image
(cd scion-avf/gpu3d/app && ./gradlew assembleDebug)
scion-avf/gpu3d/run-on-device.sh --image hackios-xnu-kernel/avf/gpu3d/probe/out/Image \
    [--params "gpu3d.size=1170x2532"] [-s SERIAL]
# XNU (M2): avfboot + a payload whose boot-args contain gpu3d=1
python3 xnuloader/mkpayload.py -o payload.bin kernel=mach.development.bcm2837 ramdisk=hello --cmdline "gpu3d=1 -v"
scion-avf/gpu3d/run-on-device.sh --image hackios-xnu-kernel/avf/shim/out/Image --initrd payload.bin
# QEMU smoke test of the transport (QEMU's virtio-gpu is 2D: stops at "no 3D")
qemu-system-aarch64 -M virt -cpu cortex-a76 -m 256 -nographic \
    -kernel hackios-xnu-kernel/avf/gpu3d/probe/out/Image -device virtio-gpu-pci
```

The run script takes `/data/local/tmp/pixel.lock` (waiting for it) and installs only its own
package. On exit it restores `hidden_api_blacklist_exemptions`, revokes its grants and
releases the lock.
