# iOS 27 (t8030) SPTM/TXM boot-chain specification

Status: **analysis / design** (no implementation yet). Target: let `hw/arm/t8030.c`
+ `hw/arm/boot.c` load **SPTM** (Secure Page Table Monitor) and **TXM** (Trusted
Execution Monitor) before the XNU kernel on iOS 27.0.1, or map this onto upstream's
"Gaia" release (ChefKissInc/Inferno#268) when it lands.

All firmware facts below come from **local research copies** (read-only, in a
scratch dir). No Apple binary bytes, keys, or decrypted payloads are reproduced
here — only structural facts (segment names, load addresses, offsets, register
names, control-flow descriptions), the same class of information already in
`hw/arm/kernel_patches.c`.

Inputs examined (build 24A446 / n104ap):
- `27.0.1_sptm.t8030.release.bin`  — SPTM, Mach-O arm64e, `MH_EXECUTE`
- `27.0.1_txm.iphoneos.release.bin` — TXM, Mach-O arm64e, `MH_EXECUTE`
- `27.0.1_kernelcache.research.macho` — kernel, `MH_FILESET`
- `27.0.1_DeviceTree.n104ap.bin` vs `26.6.2_DeviceTree.n104ap.bin`
- Inferno `hw/arm/t8030.c`, `hw/arm/boot.c`, `hw/arm/mem.c`, `include/hw/arm/boot.h`

Tooling: `otool -h/-l`, NDK `llvm-objdump --macho`, a small Apple-DT parser.

---

## 0. Summary (5 lines)

1. SPTM and TXM are standalone PIE arm64e `MH_EXECUTE` Mach-Os (not kernel filesets);
   each self-relocates via a `__TEXT,__chain_starts` table and has a fixed VA window
   distinct from the kernel's (`…27000000` / `…17000000` vs kernel `…07000000`).
2. The current t8030 boot path loads only the kernel and hard-fails on any kernel
   carrying `__DATA_SPTM`/`__TEXT_BOOT_EXEC`; the handoff struct it already has
   (`AppleMonitorBootArgs`, 0x60 bytes) is the *old* monitor ABI and is far too small.
3. SPTM is entered with `x0 → sptm_bootinfo`: a version at `+0x00` (requires ≥3),
   fields at `+0x08/+0x10/+0x18`, and at `+0x480` a pointer to a ~0x17c0-byte shared
   region it stamps with tag `"SPTM"`+`5`. The real struct is ≥ 0x488 bytes.
4. SPTM→kernel ABI is visible in the kernel's `__TEXT_BOOT_EXEC` trampoline:
   `x0` = handoff selector (4 or 5), `x1` = boot-args ptr, `x2` = aux; it does
   `x0=x1; x1=x2` then enters the normal kernel `_start`.
5. **Biggest blocker:** the 0x18…0x480 body of `sptm_bootinfo` (region descriptors +
   page-table pool) is unmapped and gates the SPTM→kernel path. The *execution level*
   is likely **not** a blocker: GENTER/GEXIT counts (§2.5) suggest SPTM/TXM run at the
   guarded level Inferno's `a13_gxf.c` already models (GL1), so the **boot flow is the
   critical path**, not new CPU state. This spec is written to hold either way.

---

## 1. Mach-O layout and virtual load map

Both monitors: `magic 0xfeedfacf`, `cputype 0x0100000c` (ARM64), `cpusubtype 2`
+ `caps 0xc0` → **arm64e** with the PtrAuth ABI version bits set; `filetype 0x2`
(`MH_EXECUTE`); `flags 0x00200001` = `MH_NOUNDEFS | MH_PIE`.

Position-independence: neither carries `LC_DYLD_CHAINED_FIXUPS` or a classic
rebase table — `__LINKEDIT` is a 32-byte stub with a single symbol. Instead each
`__TEXT` segment has a `__chain_starts` section (`reserved1 = 1`) holding the
chained-fixup start table. **iBoot relocates the image in place by walking that
chain and adding the load slide.** So both are fully position-independent; the
VAs below are the *link-preferred* addresses.

### 1.1 SPTM — image base `0xfffffff027004000`, entry `0xfffffff027098450`

| Segment       | vmaddr               | vmsize   | fileoff  | filesize | initprot |
|---------------|----------------------|----------|----------|----------|----------|
| `__TEXT`       | `0xfffffff027004000` | `0x14000`| `0`       | `0x14000`| r-x (5)  |
| `__DATA_CONST` | `0xfffffff027018000` | `0x8000` | `0x14000` | `0x8000` | rw- (3)  |
| `__LATE_CONST` | `0xfffffff027020000` | `0x74000`| `0x1c000` | `0x74000`| rw- (3)  |
| `__TEXT_EXEC`  | `0xfffffff027094000` | `0x54000`| `0x90000` | `0x54000`| r-x (5)  |
| `__LAST`       | `0xfffffff0270e8000` | `0x4000` | `0xe4000` | `0x4000` | rw- (3)  |
| `__DATA`       | `0xfffffff0270ec000` | `0x10000`| `0xe8000` | `0x10000`| rw- (3)  |
| `__BOOTDATA`   | `0xfffffff0270fc000` | `0x18000`| `0xf8000` | `0x18000`| rw- (3)  |
| `__LINKEDIT`   | `0xfffffff027114000` | `0x4000` | `0x110000`| `0x20`   | r-- (1)  |

- Segments are **contiguous** from base to `0xfffffff027118000` → total VM span
  `0x114000` (file is 1,114,144 bytes). The region nominally starts at
  `…27000000`, with the first `0x4000` left unmapped below `__TEXT` (header page gap).
- `LC_UNIXTHREAD` pc = `0xfffffff027098450` → inside `__TEXT_EXEC` (`base + 0x94450`).
  It is a trampoline: `b 0x…2709b000`, i.e. the real `_start` is at `+0x97000`.
  `__LATE_CONST` (0x74000) is the dominant segment — pre-reserved space SPTM fills at
  runtime (page-table shadow / frame table).
- `LC_SOURCE_VERSION` 820.0.22. Sections of note: `__TEXT,__binname`,
  `__TEXT,__chain_starts`, `__LAST,__pinst`, `__DATA,__auth_ptr`.

### 1.2 TXM — image base `0xfffffff017004000`, entry `0xfffffff017084000`

| Segment           | vmaddr               | vmsize   | fileoff  | filesize | initprot |
|-------------------|----------------------|----------|----------|----------|----------|
| `__TEXT`           | `0xfffffff017004000` | `0x1c000`| `0`       | `0x1c000`| **r-- (1)** |
| `__DATA_CONST`     | `0xfffffff017020000` | `0x10000`| `0x1c000` | `0x10000`| rw- (3)  |
| `__TEXT_EXEC`      | `0xfffffff017030000` | `0x50000`| `0x2c000` | `0x50000`| r-x (5)  |
| `__TEXT_BOOT_EXEC` | `0xfffffff017080000` | `0x8000` | `0x7c000` | `0x8000` | r-x (5)  |
| `__DATA`           | `0xfffffff017088000` | `0x4000` | `0x84000` | `0x4000` | rw- (3)  |
| `__LINKEDIT`       | `0xfffffff01708c000` | `0x4000` | `0x88000` | `0x20`   | r-- (1)  |

- Contiguous base → `0xfffffff017090000`, total VM span `0x8c000` (file 557,088 bytes).
- `__TEXT` is **read-only** (unlike SPTM's r-x `__TEXT`); all TXM code lives in
  `__TEXT_EXEC` / `__TEXT_BOOT_EXEC`. `__TEXT,__info_plist` present.
- `LC_UNIXTHREAD` pc = `0xfffffff017084000` = `__TEXT_BOOT_EXEC base + 0x4000`.
  The boot body (`__TEXT_BOOT_EXEC,__bootcode` at `+0x4060`) begins `bti c; cbz x0, …`
  then a long structured `ldp/ldr/str` sequence → TXM receives a pointer in `x0`
  and copies fields into its `__DATA`. The entry word at `0x…84000` branches
  back into `__TEXT_EXEC`. `LC_SOURCE_VERSION` 217.0.2.
- A separate `txm.iphoneos.research.bin` ships (byte-identical size); same layout
  expected (not re-dumped).

### 1.3 Kernel segments relevant to SPTM (`MH_FILESET`, base `0xfffffff007004000`)

| Segment            | vmaddr               | vmsize    | initprot | sections |
|--------------------|----------------------|-----------|----------|----------|
| `__DATA_SPTM`       | `0xfffffff0080fc000` | `0x4c000` | r-- (1)  | **0** (raw region) |
| `__TEXT_BOOT_EXEC`  | `0xfffffff00aa08000` | `0x8000`  | r-x (5)  | **0** (raw region) |

- The kernel's `LC_UNIXTHREAD` pc = `0xfffffff00aa08000` = `__TEXT_BOOT_EXEC` base.
  On classic XNU the entry was `_start` in `__TEXT_EXEC`; on SPTM kernels the entry
  is this trampoline, **entered by SPTM**, not by the loader directly.
- `__DATA_SPTM` is a section-less 0x4c000 region the kernel exposes for SPTM to own
  (page-table/dispatch metadata). It is an ordinary kernel segment, so Inferno's
  existing fileset loader already maps it — no separate reservation needed.

---

## 2. The handoff contract

### 2.1 What Inferno provides today

`t8030_load_kernelcache()` (`hw/arm/t8030.c`):
1. `apple_boot_get_kc_bounds()` → `kc_base`, `kc_end`, read-only bounds.
2. `get_kaslr_slides()` → `g_phys_slide`, `g_virt_slide`; `g_phys_base = DRAM_BASE`
   (`0x800000000`), `g_virt_base = kc_base + (g_virt_slide - g_phys_slide)`.
3. `apple_boot_load_macho(kernel, as, /chosen/memory-map, g_phys_base+g_phys_slide,
   g_virt_slide)` maps every segment to `phys_base + (vmaddr - kc_base)` and returns
   the `LC_UNIXTHREAD` pc (→ `info->kern_entry`). For the 27 kernel that pc is the
   `__TEXT_BOOT_EXEC` trampoline.
4. Bumps `phys_ptr` for ramdisk, TZ0, SEPFW, **kernel boot-args (0x4000)**, device
   tree; `apple_boot_setup_bootargs()` writes an `AppleKernelBootArgsRev3` (rev 3
   for major ≥ 17) at `info->kern_boot_args_addr`.
5. `t8030_cpu_reset()` starts CPU0 via `arm_set_cpu_on(affinity, kern_entry,
   context_id = kern_boot_args_addr, …)` → PC = `kern_entry`, **x0 = boot-args PA**.

There is already a *monitor* path, but it is legacy and unused by t8030:
- `AppleMonitorBootArgs` (`include/hw/arm/boot.h`): `version, virt_base, phys_base,
  mem_size, kern_args, kern_entry, kern_phys_base, kern_phys_slide, kern_virt_slide,
  kern_text_section_off, random_bytes[0x10]` → **0x60 bytes**.
- `apple_boot_setup_monitor_boot_args()` writes it with `version = 4`.
- Only `hw/arm/s8000.c` uses it (the TZ1 "AP secure monitor", iOS ≤ ~11). The
  `secure_monitor` out-param of `apple_boot_load_kernel()` is only populated for
  legacy **LZSS** `complzss` kernels (monitor appended after the payload); modern
  LZFSE kernelcaches never hit that path, and SPTM/TXM ship as separate IM4Ps.
- The SPTM gate: `t8030_kernel_uses_sptm()` returns true if `__DATA_SPTM` **or**
  `__TEXT_BOOT_EXEC` exists, and `t8030_init` `error_fatal`s. This is the thing to
  replace.

### 2.2 What SPTM actually expects (measured from `_start`)

SPTM entry trampoline `0x…98450 → _start 0x…9b000`. At `_start`:

```
msr OSLAR_EL1, xzr ; msr DAIFSet, #0xf      ; mask debug + interrupts
mov x20, x0                                  ; x20 = sptm_bootinfo pointer (from x0)
ldr x0, [x20]            ; [+0x00] = version
cmp x0, #3 ; b.lt …                          ; require version >= 3
ldr x5, [x20, #0x480]    ; [+0x480] = pointer to shared handoff region (if v>=3)
  <zero 0x17c0 bytes at x5>                   ; memset the region
  <store tag "SPTM" at [x5+0x00], value 5 at [x5+0x08]>
… (x1..x4 = adrp &sptm_bss_flags, +8/+10/+18)  ; one-time init guard in __LATE_CONST
msr VBAR_EL1, &exc_vectors(0x…99000)
ldr x22, [x20, #0x08]    ; [+0x08]
ldr x23, [x20, #0x10]    ; [+0x10]
ldr x24, [x20, #0x18]    ; [+0x18]
adrp x25, 0x…27020000    ; SPTM __LATE_CONST base  → early page-table build
adrp x26, 0x…270fc000    ; SPTM __BOOTDATA base
… builds translation tables, launches TXM, enters kernel …
```

Confirmed `sptm_bootinfo` fields:

| Offset  | Meaning (confidence)                                             |
|---------|------------------------------------------------------------------|
| `+0x00` | `version` u64, **must be ≥ 3** (Inferno sets 4 today for monitor)|
| `+0x08` | consumed into `x22` — base/address used for MMU setup (OPEN)     |
| `+0x10` | consumed into `x23` — base/address used for MMU setup (OPEN)     |
| `+0x18` | consumed into `x24` — base/address used for MMU setup (OPEN)     |
| `+0x480`| pointer to a ≥0x17c0-byte shared handoff/telemetry region        |
| …       | `0x20`…`0x480` body: region descriptors + PT pool (OPEN)         |

Key conclusion: the real struct is **≥ 0x488 bytes** — an order of magnitude larger
than `AppleMonitorBootArgs`. The `0x20…0x480` gap almost certainly carries the
phys/virt/size descriptors for SPTM, TXM, the kernel, the device tree, the kernel
boot-args, and the pre-allocated page-table pool. That body must be reverse-engineered
(see OPEN-1).

The shared region at `+0x480` (tag `"SPTM"`, version `5`) is a runtime SPTM↔kernel
rendezvous page; the emulator only has to supply a valid, zeroed, writable buffer
for it — SPTM fills it in.

### 2.3 SPTM → TXM and SPTM → kernel

- **TXM:** entered by SPTM at `0xfffffff017084000` with a boot-args pointer in `x0`
  (the `__bootcode` `cbz x0` + structured copy). Its argument layout is a distinct,
  smaller structure (OPEN-2).
- **Kernel:** SPTM enters the kernel's `__TEXT_BOOT_EXEC` trampoline
  (`0xfffffff00aa08000`). Decoded prologue:

  ```
  cmp x0, #4 ; b.eq +0x18
  cmp x0, #5 ; b.ne +0x24
  mov x0, x1          ; shuffle: real boot-args ptr was in x1
  mov x1, x2          ; aux handoff arg was in x2
  b   <kernel _start in __TEXT_EXEC>
  ```

  → **SPTM→kernel ABI:** `x0` = handoff selector/version (**4 or 5**, matching the
  `"SPTM"`+`5` stamp and the `version ≥ 3` gate), `x1` = kernel boot-args pointer,
  `x2` = auxiliary handoff pointer. The kernel then runs its normal `_start` with the
  classic `boot_args*` in `x0`.

So the emulator does **not** start the kernel directly any more: it starts **SPTM**,
and SPTM is what eventually jumps to `kern_entry` with the kernel boot-args Inferno
already builds.

### 2.4 Device-tree deltas (27.0.1 vs 26.6.2, n104ap)

Node *topology is identical* between the two builds — no new `sptm`/`txm` nodes.
Differences (all confirmed via the DT parser):

- `/chosen`: **+`txm-secure-channel = 1`**; plus `cryptex1-certificate-format`,
  `cryptex1-key-scheme`, `secureboot-certificate-format`, `secureboot-key-scheme`.
- `/arm-io/dart-sep`: **+`txm-secure-channel-base = 0x4000`**, **+`txm-secure-channel-size
  = 0x4000`** — a 16 KiB DART (IOMMU) window for the TXM↔SEP secure channel at DART VA
  `0x4000`.

Implication: SPTM/TXM **memory-map** entries are *not* in the static DT. iBoot injects
them at runtime, exactly as Inferno already injects `Kernel-<seg>` entries into
`/chosen/memory-map` from `apple_boot_load_macho()`. Inferno must therefore add
`SPTM`/`TXM` (and likely `Sptm`/`Txm` region-id) memory-map entries itself, and must
honor `txm-secure-channel*` once the SEP↔TXM channel is modeled (later milestone).
`crypto-hash-method` is `sha2-384` (unchanged).

### 2.5 Guarded-mode usage (GENTER/GEXIT) — execution level is probably already modeled

Measured call-gate counts:

| Image        | GENTER | GEXIT | reading                                            |
|--------------|--------|-------|----------------------------------------------------|
| 26.6.2 kernel| 8      | —     | PPL-era: a handful of monitor entries              |
| 27.0.1 kernel| 176    | —     | kernel now traps into the monitor ~176 call sites  |
| SPTM         | 1      | 7     | SPTM mostly *returns* (GEXIT) to its caller        |
| TXM          | 0      | 9     | TXM is only ever *entered*; it only GEXITs out     |

Interpretation (confirmed by the GXF analysis, ios-on-pixel#13 `docs/ios27/GXF-GAP.md`):
the EL1 kernel issues `GENTER` to cross into **guarded EL1** where SPTM dispatches;
SPTM routes page-table / code-signing requests (some to TXM) and `GEXIT`s back. TXM
never `GENTER`s and programs **no** GXF registers — SPTM manages GXF for it. **GL1 is
enough: there is no GL2 and no new banked register file.** Inferno already models this
level in `a13_gxf.c` (`GXF_*_EL1`, `*_GL11`, the `GENTER → GXF_ENTER_EL1 → GEXIT` path).

Consequences for the hand-off (this is the whole point):

- **Enter SPTM at non-guarded EL1 with GXF *not* yet enabled.** SPTM's single `GENTER`
  is in its own boot path: it enables GXF from non-guarded EL1 and installs *itself* as
  the `GXF_ENTER_EL1` handler. So the emulator hands control to SPTM exactly the way it
  already hands control to a kernel (EL1, MMU off at reset) — **do not** pre-program any
  GXF register for SPTM.
- The boot hand-off (this document) is therefore the entire critical path; no new CPU
  execution state is required to reach and run SPTM.
- Trace aid only (no emulator work): on 27 the kernel's monitor calls put a selector in
  `x16[63:48]` (175 sites); 26's PPL used `x15`.
- Early SPTM bring-up may `UNDEF` on an `S3_4_C15_*` / `S3_6_C15_*` IMPDEF register
  (SPTM's exception vectors read `S3_6_C15_C8_0`) until the GXF branch (`ios27/gxf`)
  adds them — expected; note and continue.

---

## 3. Physical / virtual memory reservation

### 3.1 The three VA windows

| Image  | VA base           | VA span    | Notes                               |
|--------|-------------------|------------|-------------------------------------|
| Kernel | `…07004000`       | fileset    | mapped at `DRAM_BASE + phys_slide`  |
| TXM    | `…17004000`       | `0x8c000`  | own window                          |
| SPTM   | `…27004000`       | `0x114000` | own window                          |

The windows are deliberately disjoint (`…07/…17/…27`), so all three can be resident
simultaneously. Physical placement is the emulator's choice, as long as it is
reflected in each image's boot-args/descriptors and in the memory-map.

### 3.2 Interaction with `g_phys_base` / `g_virt_base` / `allocate_ram`

- `mem.c` globals `g_phys_base/g_virt_base/g_phys_slide/g_virt_slide` describe **one**
  VA↔PA pair — the kernel's. `vtop_static/ptov_static/vtop_slid` are all kernel-relative.
  SPTM and TXM have *different* VA bases, so they must **not** go through the global
  translators.
- Good news: `apple_boot_load_macho(header, as, memory_map, phys_base, virt_slide)`
  is already image-relative — it derives `kc_base` from *that image's own* segments and
  maps to `phys_base + (vmaddr - kc_base)`. It can load SPTM and TXM as-is by passing
  their own `phys_base` and `virt_slide = 0` (or a shared monitor slide). It even
  returns the image's `LC_UNIXTHREAD` pc (converted through that image's bases), giving
  us `sptm_entry` / `txm_entry` for free.
- Physical reservation: the cleanest fit is to extend the `phys_ptr` bump-allocator in
  `t8030_load_kernelcache()` (the same mechanism used for ramdisk/TZ0/SEPFW/boot-args/DT),
  reserving:
  - `SPTM image`  : `ROUND_UP_16K(0x114000)` + its runtime working set. `__LATE_CONST`
    (0x74000) and `__BOOTDATA` (0x18000) are already inside the image span, but SPTM also
    needs an external **page-table pool** (size OPEN-3; one of the `+0x08/+0x10/+0x18`
    fields or a dedicated carveout likely points at it).
  - `TXM image`   : `ROUND_UP_16K(0x8c000)`.
  - `sptm_bootinfo`: one page (`0x4000`), holding the ≥0x488-byte struct.
  - `sptm↔kernel shared region`: `ROUND_UP_16K(0x17c0)` (the `+0x480` target).
- `allocate_ram()` is only for fixed MMIO-adjacent backing (SROM/SRAM/SEPROM); SPTM/TXM
  live in DRAM, so they use the DRAM AddressSpace writes that `apple_boot_load_macho()`
  already performs, not new `allocate_ram()` regions.
- `AppleBootInfo` (`include/hw/arm/boot.h`) should gain: `sptm_phys_base`,
  `sptm_entry`, `sptm_boot_args_addr`, `sptm_size`, `txm_phys_base`, `txm_entry`,
  `txm_size`, `sptm_handoff_addr`.

---

## 4. Staged implementation plan

Conventions: new machine props `-M t8030,sptm=<sptm.bin>,txm=<txm.bin>` (accept the
decompressed Mach-O directly, and/or the IM4P — IM4P type 4CCs are **`sptm`** and
**`trxm`**, so `apple_boot_load_kernel`'s type check must be generalized or a sibling
added). Firmware stays read-only research input.

### Milestone 0 — "SPTM starts executing" (minimum)

Goal: CPU0 enters SPTM `_start`, passes the `version ≥ 3` gate, and self-relocates /
begins MMU setup. It is acceptable for it to later fault once it needs the real region
descriptors — getting it *executing* is the milestone.

1. `include/hw/arm/t8030.h`: add `char *sptm_filename, *txm_filename;`
   `MachoHeader64 *sptm, *txm;` and the `AppleBootInfo` fields from §3.2.
2. `hw/arm/boot.c`: add `apple_boot_load_monitor(const char *filename, const char
   *expect_4cc)` — thin wrapper over `extract_im4p_payload()` + `apple_boot_parse_macho()`
   accepting `sptm`/`trxm`/`raw`. (Or relax `apple_boot_load_kernel` to take an expected
   type.)
3. `hw/arm/boot.c`: add `apple_boot_setup_sptm_boot_args(as, addr, handoff_addr, ...)`
   that writes a ≥0x488-byte struct: `version` (3 or 4), the three `+0x08/+0x10/+0x18`
   fields (best-effort = SPTM phys base / virt base / kernel phys base), and
   `[+0x480] = handoff_addr`; zero the rest. Keep the legacy
   `apple_boot_setup_monitor_boot_args` for s8000.
4. `hw/arm/t8030.c` `t8030_init`: **replace** the `t8030_kernel_uses_sptm` `error_fatal`
   with: if the kernel uses SPTM, require `sptm`/`txm` props (else a clear error), and
   `t8030->sptm = apple_boot_load_monitor(sptm_filename, "sptm")`, likewise TXM.
5. `hw/arm/t8030.c` `t8030_load_kernelcache`: after the kernel map + boot-args, reserve
   phys via the `phys_ptr` bump (§3.2) and
   `info->sptm_entry = apple_boot_load_macho(t8030->sptm, as, /*memory-map*/ NULL,
   info->sptm_phys_base, 0)`; same for TXM → `info->txm_entry`. Allocate
   `sptm_boot_args_addr` + `sptm_handoff_addr`, then call the setup from step 3.
   Add `SPTM`/`TXM` entries to `/chosen/memory-map` (mirror the `Kernel-<seg>` loop).
6. `hw/arm/t8030.c` `t8030_cpu_reset`: when `t8030->sptm != NULL`, start CPU0 at
   `info->sptm_entry` with `context_id = info->sptm_boot_args_addr` (PC=SPTM,
   x0=bootinfo PA) and set `rvbar` to `sptm_entry & ~0xFFF`, instead of `kern_entry`.
   Leave `kern_entry`/`kern_boot_args_addr` populated — SPTM consumes them via the
   bootinfo body (see Milestone 1).

### Milestone 1 — SPTM reaches the kernel

1. Reverse the `sptm_bootinfo` body `0x20…0x480` (OPEN-1) and populate real region
   descriptors (SPTM/TXM/kernel phys+virt+size, DT, kernel boot-args, PT pool).
2. Fill the TXM arg struct (OPEN-2) so SPTM's TXM launch succeeds.
3. Verify SPTM enters `kern_entry` with `x0 ∈ {4,5}`, `x1 = kern_boot_args_addr`,
   `x2 = aux`. Since the kernel trampoline does `x0=x1; x1=x2`, the existing rev3
   boot-args Inferno builds should satisfy the subsequent `_start` unchanged.

### Milestone 2 — correctness / enforcement

1. **Guarded-level emulation** — *only if GL1 is insufficient* (§2.5). `a13_gxf.c`
   already models GL1 (`GXF_*_EL1`, `*_GL11`), which PPL uses and which SPTM/TXM most
   likely share (SPTM: 1 GENTER/7 GEXIT; TXM: 0/9). If a distinct SPTM guarded level is
   needed, add it + its IMPDEF sysregs there; SPTM's exception vectors read
   `S3_6_C15_C8_0` (an Apple IMPDEF guarded-state register). This is additive and does
   not affect Milestones 0–1.
2. **TXM↔SEP secure channel**: honor `txm-secure-channel-base/size = 0x4000` on
   `/arm-io/dart-sep` and `/chosen/txm-secure-channel`.
3. **Enforcement patches**: trust-cache / code-signing moved kernel→TXM on 27 (per
   `docs/IOS27.md`); equivalent patches must target TXM, not the kernel.

Draft C skeletons for Milestone 0 live in `docs/ios27/drafts/` (see below).

---

## 5. OPEN questions (each with how to resolve)

- **OPEN-1 — `sptm_bootinfo` body `0x20…0x480`.** Need the full field map (region
  descriptors, PT pool base/size, kernel entry/args, DT). *Resolve:* continue the
  `_start` disassembly past `0x…9b0c0` and cross-reference the kernel's `__DATA_SPTM`
  consumer glue; compare against the public SPTM layout once Gaia lands. Highest-value
  unknown — it gates Milestone 1.
- **OPEN-2 — TXM boot-arg struct.** Layout TXM reads from `x0` at `0x…84060`.
  *Resolve:* trace the `__bootcode` `ldp/ldr/str` copy sequence and map each source
  offset; correlate with what SPTM writes before launching TXM.
- **OPEN-3 — SPTM page-table / frame-table pool size.** How much external DRAM SPTM
  needs beyond its image (and whether `+0x08/+0x10/+0x18` point at it or at a DT
  carveout). *Resolve:* once OPEN-1 is mapped, read the pool descriptor; cross-check
  against a `region-id` carveout in `/chosen/carveout-memory-map`.
- **OPEN-4 — Does SPTM/TXM run at the existing GL1?** GENTER/GEXIT counts (§2.5) point
  to yes; if so the CPU model already suffices and the boot flow is the only critical
  path. *Resolve:* bring SPTM up (Milestone 0) and watch whether it executes at GL1
  without taking an unexpected trap; disassemble SPTM's single GENTER and its GXF
  transition. If a distinct level is required, scope the `a13_gxf.c` addition
  (incl. `S3_6_C15_C8_0`).
- **OPEN-5 — IM4P vs raw input.** Whether to feed `.im4p` (needs the `sptm`/`trxm`
  type path + the PAYP ASN.1 fix already applied for kernels) or pre-decompressed
  `.bin`. *Resolve:* generalize `apple_boot_load_kernel`'s 4CC check; decide the
  machine-property contract.
- **OPEN-6 — SEP dependency.** Whether SPTM/TXM bring-up needs a valid SEP/`txm-secure-channel`
  before the kernel mounts root (per `docs/IOS27.md` §4, no 27 SEP key is sourced).
  *Resolve:* bring SPTM to the kernel trampoline first; observe where it blocks.

---

*This document is analysis only; no Inferno source was modified. Draft C is in
`docs/ios27/drafts/`.*
