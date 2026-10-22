# iOS 27 on t8030: what's missing (2026-10-21)

**Short version:** iOS 27.0.1 is the first iPhone 11 release that boots
through **SPTM/TXM**, which Inferno doesn't emulate yet. Upstream says the
prerequisites are done but unpushed, coming in the "Gaia" release
([ChefKissInc/Inferno#268](https://github.com/ChefKissInc/Inferno/issues/268),
11 Oct: "iOS 27 still in the `restore dies at the install_splat step`
state"). The plan is to **track Gaia and rebase `pixel-dev` onto it**, not to
build SPTM support ourselves. Meanwhile, this doc records what was measured.

All inputs below were local research copies (kernelcaches range-fetched from
three IPSWs into a scratch directory). Nothing Apple-owned is in the repo.

## Progress (updated 2026-10-21)

Despite the plan above, the boot chain is being brought up in the fork, one
PR per step. Every step is opt-in: the iOS 27 path only runs with
`-M t8030,sptm=…,txm=…` and an iOS 27 kernel. **iOS 14 boots take none of
these paths.**

| Step | Fork PR | State |
|---|---|---|
| GXF/SPTM system registers (GL1) | [Inferno#1](https://github.com/dylanneve1/pyre/pull/1) | merged |
| `sptm=` load path: SPTM runs and reaches its memory-map lookups (Milestone 0) | [Inferno#2](https://github.com/dylanneve1/pyre/pull/2) | merged, measured (SPEC §2.6) |
| `txm=` load and map | [Inferno#3](https://github.com/dylanneve1/pyre/pull/3) | merged |
| iOS 27 `/chosen` + `dart-sep` TXM properties | [Inferno#5](https://github.com/dylanneve1/pyre/pull/5) | merged |
| `a13-test` bench machine + register test in CI; **fix**: the #1 placeholders had replaced five A13 registers (MMU/FED error status, `S3_4_c15_c0_5`, `AMX_STATUS`, `AMX_CTL`) with plain storage on release builds | [Inferno#17](https://github.com/dylanneve1/pyre/pull/17) | merged; app bumped in [#73](https://github.com/dylanneve1/ios-on-pixel/pull/73) |
| iBoot-style image layout + `/chosen/memory-map` image regions, `cl4-entropy`, patch-check dry-run | [Inferno#18](https://github.com/dylanneve1/pyre/pull/18) | merged; dry-run passes on the real images, **not yet run with a DT** |

### What SPTM needs from the boot chain (static analysis of the 27.0.1 t8030 SPTM)

Structural facts only, read from a local research copy:

- **Image regions.** `init_get_image_region(name, required)` returns a 16-byte
  `{paddr, length}` from `/chosen/memory-map`; any other size panics.
  `validate_region_order()` walks a fixed table and requires each present
  region to start exactly where the previous one ends:
  `TXM-ro TXM-rx TXM-bx TrustCache [AuxKC-ro AuxKC-rx] BootKC-rx BootKC-bx
  BootKC-ro BootKC-rs [CL4-rx CL4-ro] DeviceTree SPTM-ro SPTM-rx SPTM-rw`.
  It also requires `TXM-rw/-le`, `BootKC-rw/-le`, `SPTM-rm/-le`, `BootArgs`,
  `slide`, and `{TXM,BootKC,CL4}-{entry,virt}`. Entry points are computed
  as `ptov(<image>-ro.paddr) + entry - virt` (CL4: `CL4-rx.paddr + entry - virt`).
- **`BootKC-rs` belongs to SPTM.** `init_xnu_ro_data()` zeroes it and puts
  XNU's read-only data (exception vector, CTRR dispatch table, RO page-table
  bounds) in it, then checks those pointers fall in
  `[BootKC-ro.paddr, BootKC-rs.end)`.
- **Entropy.** `sptm_init()` copies `/chosen/random-seed` (1 to 0x100 bytes) and
  `/chosen/cl4-entropy` into its own data and clears them. `cl4-entropy` must be
  **exactly 0xc0 bytes**, or SPTM panics with "Not enough SK entropy".
- **Free memory.** SPTM registers `[topOfKernelData, physBase + memSize)` from
  the boot args as `AVAILABLE`, so every image and carve-out has to sit below
  `topOfKernelData`. Optional regions (`RAMDisk`, `SEPFW`, `preoslog`,
  `SPTMDebug`, …) are registered only if present.
- **Exclaves.** The `CL4-*` lookups are unconditional, but SPTM only starts the
  exclaves kernel ("Starting SK…") when `exclaves-enabled` is set. t8030
  doesn't set it, so a one-page placeholder `CL4-rx` is enough.
- **TXM** reads about a dozen `/chosen` properties (`debug-enabled`,
  `chip-id`, `cs-system-policy`, `research-enabled`, `osenvironment`,
  `/chosen/iBoot/iboot-build-variant`, …). Each missing one only gets a log
  line and a default, not a panic. Its trust cache comes from
  `memory-map/TrustCache`, as on XNU.

### Checking a layout without a device tree

```sh
qemu-system-aarch64 -M t8030,patch-check=on,sptm=<sptm.im4p>,txm=<txm.im4p> \
    -kernel <kernelcache> -display none -m 1G
```

This lays the three images out, publishes the regions into a scratch
memory-map and re-runs SPTM's checks. On the 27.0.1 research kernelcache it
reports `the image regions pass SPTM's checks`.

### Blocked / next

- **A real SPTM run against the new layout** needs the 27.0.1
  `DeviceTree.n104ap` and restore trust cache on the build host. The owner
  provides them. Expected next stop: whatever comes after `sptm_init()`'s
  region setup (PAPT registration, then "Starting TXM...").
- **TXM's arguments (SPEC OPEN-2) look like SPTM's job, not the loader's.**
  After "Starting TXM...", SPTM builds TXM's argument block in its own `__bss`
  and enters TXM through its domain-switch path, using the entry it computed
  from `TXM-entry`/`TXM-virt`. The emulator only has to provide the TXM
  regions. A run still has to confirm this.
- After that: SPTM's hand-off to the kernel trampoline (`x0` ∈ {4, 5}).

## 1. Manifests: SPTM/TXM is new in 27

Comparing the `n104ap` erase identities of 26.6.2 (`23G90`) and 27.0.1 (`24A446`):

| Only in 27.0.1 | Path |
|---|---|
| `Ap,SecurePageTableMonitor` | `Firmware/sptm.t8030.release.im4p` |
| `Ap,RestoreSecurePageTableMonitor` | `Firmware/sptm.t8030.release.im4p` |
| `Ap,TrustedExecutionMonitor` | `Firmware/txm.iphoneos.release.im4p` |
| `Ap,RestoreTrustedExecutionMonitor` | `Firmware/txm.iphoneos.release.im4p` |

27.0.1 also ships `txm.iphoneos.research.im4p`. Nothing was removed relative
to 26.6.2. Both builds' OS images are `.aea` (encrypted). `tools/ipsw_probe.py`
now reports both facts.

## 2. Kernels: Inferno couldn't load 26 or 27 at all

The 26.6.2 and 27.0.1 kernelcache IM4Ps carry an extra context-tagged `[0]`
**PAYP** (payload properties) element after the compression info (185 and
332 bytes; 14.8 has none). Inferno's IM4P ASN.1 definition didn't allow it,
so DER decoding failed and `extract_im4p_payload` silently fell back to
treating the whole file as a raw payload. That's never a valid Mach-O.
Fixed on `pixel-dev` (`hw/arm/img4.asn1` + regenerated `img4.h`).

With that fix, all three decompress (LZFSE) to valid Mach-O. 14.8 is
`MH_EXECUTE`; 26.6.2 and 27.0.1 are `MH_FILESET`. The 27 kernel has the
`__DATA_SPTM` / `__TEXT_BOOT_EXEC` segments, and t8030 now uses those to
reject SPTM kernels with an explicit error.

## 3. Kernel patches: what still matches

Measured with the new triage mode on the Android build, research kernels:

```sh
qemu-system-aarch64 -M t8030,patch-check=on -kernel <kernelcache> -display none -m 1G
```

A patch counts as ✅ if any of its fallback variants matched (shown after the tick).

| Patch | 14.8 | 26.6.2 | 27.0.1 |
|---|---|---|---|
| bypass root authentication | ✅ | ✅ | ✅ |
| allow mounting root as R/W | ✅ | ✅ (new) | ✅ (new) |
| bypass root hash authentication | ❌ | ✅ | ✅ |
| disable APFS snapshots | ✅ | ✅ | ✅ |
| allow SHA1 signatures in AMFI | ✅ | ✅ | ❌ |
| all binaries in TrustCache (AMFI) | ❌ | ✅ | ✅ |
| increase SCOT size to 0x10000 to use it as TRAC | ✅ | ✅ (new) | ✅ |
| allow unsigned firmware in img4_firmware_evaluate | ✅ | ✅ | ✅ |
| allow remounting rootfs, union mounts | ✅ (new) | ❌ | ❌ |
| force enable kprintf | ✅ | ❌ | ❌ |
| disable AMX | ✅ | ✅ (newer) | ❌ |
| bypass code signature checks | ✅ | ✅ | ✅ |
| enable hactivation | ✅ | ✅ | ✅ |
| all binaries in trustcache | ✅ | ✅ (iOS 16+) | ✅ (iOS 16+) |
| bypass pmap_cs_enforce | ✅ | ❌ | ❌ |

Also: **27.0.1 has no `__PPLTEXT.__text`**, so the PPL-targeted patches fall back
to the main kernel text.

How to read this:

* **14.8's two ❌ are expected.** It's the known-good version; those patches
  target newer kernels.
* **Already failing on 26.6.2:** union mounts, kprintf and `pmap_cs_enforce`.
  That fits upstream calling 26 work-in-progress. Some of these are probably
  covered by unpushed upstream patches.
* **New on 27.0.1:** AMFI SHA1 and AMX stop matching, and PPL is gone.
* **The bigger structural point:** on 27, trust-cache and code-signing
  enforcement move out of the kernel into **TXM**, a separate firmware image.
  Kernel-side patches (and the ✅ for "all binaries in trustcache", which may
  just be a pattern coincidence now) aren't enough. Equivalent patches would
  have to target TXM, and SPTM has to exist first.

## 4. Emulation gap

Inferno already emulates **GXF** at **GL1** (`a13_gxf.c`: `GXF_*_EL1`,
`*_GL11` registers), which is what PPL uses. The pasted assessment that
"Inferno has no GXF emulation" was wrong on that point. What's missing for 27:

1. The guarded level SPTM runs at, plus its system registers.
2. A boot flow that loads SPTM and TXM before the kernel and passes them the
   boot arguments they expect.
3. Patches re-targeted at TXM (§3), and the AEA image step on the Mac side.
4. A SEP firmware key for 27.0.1. The project doesn't source keys; whether 27
   gets anywhere without SEP is untested.

## 5. What to do when Gaia lands

1. Rebase `pixel-dev` onto it. Expect conflicts in `hw/arm/t8030.c` around the
   version switch and the SPTM gate (drop the gate once upstream boots SPTM).
2. Re-run patch-check on the 27 kernel and compare against §3.
3. Re-run `tools/ipsw_probe.py` and move SPTM/TXM from "issue" to "note" once
   it's supported.
