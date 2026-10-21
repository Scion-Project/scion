# iOS 27 (t8030) — GXF guarded-level gap analysis for the CPU model

Status: analysis + proposed CPU-model changes. Author: GXF/GXF-execution analyst.
Scope: what additional guarded-level / banked-register / exception-state support
Inferno's ARM CPU model needs so that SPTM (and TXM) on iOS 27.0.1 can execute,
beyond what `hw/arm/a13_gxf.c` provides today.

All firmware inputs were **local research copies** read read-only in a scratch dir.
This document contains only structural facts (segment names, register encodings,
instruction opcodes, call-site counts, control-flow descriptions) — no Apple
payload bytes, keys, or long hex dumps.

---

## 0. Bottom line — "Is GL1 enough?": **YES (partial)**

**A single guarded level (GL1 at EL1) is architecturally sufficient for SPTM and
TXM on t8030.** SPTM and TXM are *not* a new, higher guarded level above PPL on
this SoC. They are guarded-EL1 monitors reached through the *same* GENTER →
`GXF_ENTER_EL1` → GEXIT machinery that PPL used, which Inferno already models.
Evidence below (§2).

"Partial" because, while no new guarded **level** is needed, SPTM/TXM/kernel touch
a handful of Apple system registers in the GXF (`op1=6, CRn=15`) and EL2-impl-defined
(`op1=4, CRn=15`) encoding spaces that `a13_gxf.c` does **not** define yet. An
undefined Apple sysreg access traps as UNDEF, so these are real boot blockers and
must be added (§3, §5). No change to GENTER/GEXIT semantics or to the guarded-level
model itself is required for a first boot.

The heavy lifting for 27 is therefore in **boot flow + monitor dispatch** (the
SPTM/TXM loader agents' area), not in the guarded-level CPU model.

---

## 1. What Inferno's GXF emulates today (precise inventory)

Source: `hw/arm/a13_gxf.c`, `a13_gxf.h`, `target/arm/cpu.h` (`env->gxf`, `env->sprr`),
`target/arm/internals.h` (`arm_is_guarded`, `aarch64_save_sp/restore_sp`),
`target/arm/tcg/{a64.decode,translate-a64.c,helper-a64.c}`, `target/arm/helper.c`.

### 1.1 Guarded-level model
- "Guarded" is a **single boolean per EL**: `arm_is_guarded(env)` ==
  `ARM_FEATURE_GXF && arm_current_el(env) > 0 && (gxf_status_el[cur_el] & 1)`
  (`internals.h:422`). There is no separate GL index/counter; the only guarded
  level modelled is "GL1 at the current EL".
- Storage arrays in `env->gxf` are sized `[4]` (room for GL0..GL3) but **only
  index `[1]` is wired** through the register table. Fields:
  `gxf_config_el[4]`, `gxf_enter_el[4]`, `gxf_status_el[4]`, `gxf_abort_el[4]`,
  `sp_gl[4]`, `tpidr_gl[4]`, `vbar_gl[4]`, `spsr_gl[4]`, `aspsr_gl[4]`,
  `esr_gl[4]`, `elr_gl[4]`, `far_gl[4]` (`cpu.h:633-647`).
- MMU: `ARM_MMU_IDX_A_GXF = 0x10` tags guarded MMU indexes
  (`GE10_1`, `GE20_2`, `GE2`, `GE3`, …) so guarded vs non-guarded translations are
  distinct (`mmuidx.h:114-158`). `arm_mmu_idx_is_guarded()` gates this.

### 1.2 Registers defined (all at GL1 / EL1)
`apple_a13_gxf_cp_reginfo[]` (defined only if `ARM_FEATURE_GXF`):

| Name | op0 op1 CRn CRm op2 (`S3_x_Cy_Cz_w`) | backing |
|---|---|---|
| GXF_CONFIG_EL1 | 3 6 15 1 2 | `gxf.gxf_config_el[1]` |
| GXF_STATUS_EL1 | 3 6 15 8 0 (PL1_R) | `gxf.gxf_status_el[1]` |
| GXF_ENTER_EL1  | 3 6 15 8 1 | `gxf.gxf_enter_el[1]` |
| GXF_ABORT_EL1  | 3 6 15 8 2 | `gxf.gxf_abort_el[1]` |
| ASPSR_GL11     | 3 6 15 8 3 | `gxf.aspsr_gl[1]` |
| SP_GL11        | 3 6 15 9 0 | `gxf.sp_gl[1]` / `sp_el[1]` |
| TPIDR_GL11     | 3 6 15 9 1 | `gxf.tpidr_gl[1]` / `cp15.tpidr_el[1]` |
| VBAR_GL11      | 3 6 15 9 2 | `gxf.vbar_gl[1]` / `cp15.vbar_el[1]` |
| SPSR_GL11      | 3 6 15 9 3 | `gxf.spsr_gl[1]` / `banked_spsr[BANK_SVC]` |
| ESR_GL11       | 3 6 15 9 5 | `gxf.esr_gl[1]` / `cp15.esr_el[1]` |
| ELR_GL11       | 3 6 15 9 6 | `gxf.elr_gl[1]` / `elr_el[1]` |
| FAR_GL11       | 3 6 15 9 7 | `gxf.far_gl[1]` / `cp15.far_el[1]` |
| SPRR_CONFIG_EL1 | 3 6 15 1 0 | `sprr.sprr_config_el[1]` |
| SPRR_CONFIG_EL0 | 3 6 15 1 1 | `sprr.sprr_config_el[0]` |
| SPRR_EL0BR0_EL1 | 3 6 15 1 5 | `sprr.sprr_el_br_el1[0][0]` (perm-mask logic) |
| SPRR_EL0BR1_EL1 | 3 6 15 1 6 | `sprr.sprr_el_br_el1[0][1]` |
| SPRR_EL1BR0_EL1 | 3 6 15 1 7 | `sprr.sprr_el_br_el1[1][0]` |
| SPRR_EL1BR1_EL1 | 3 6 15 3 0 | `sprr.sprr_el_br_el1[1][1]` |
| MPRR_EL0BR0_EL1 | 3 6 15 3 1 | `sprr.mprr_el_br_el1[0][0]` |
| MPRR_EL0BR1_EL1 | 3 6 15 3 2 | `sprr.mprr_el_br_el1[0][1]` |
| MPRR_EL1BR0_EL1 | 3 6 15 3 3 | `sprr.mprr_el_br_el1[1][0]` |
| MPRR_EL1BR1_EL1 | 3 6 15 3 4 | `sprr.mprr_el_br_el1[1][1]` |

`apple_a13_gxf_cp_override_reginfo[]` additionally overrides the standard
`TPIDR_EL1 / VBAR / SPSR_EL1 / ELR_EL1 / ESR_EL1 / FAR_EL1` so that, **while
guarded**, reads/writes are redirected to the `*_gl[1]` banks (the `*_read/_write`
helpers test `arm_is_guarded`).

### 1.3 Instructions and exception flow
- Decodetree (`a64.decode:1912-1913`): `GENTER`/`GEXIT` in Apple space
  `@apple_rd` (`0 -- 0000 --------- 000101 {00001|00000} rd`). Canonical opcodes:
  **GENTER = `0x00201420`**, **GEXIT = `0x00201400`** (rd=0).
- `trans_GENTER` (`translate-a64.c:8865`): requires `EL>0` and `gxf_active`;
  **rejects GENTER while already guarded** (`if (s->guarded) return false` → UNDEF).
  Raises `EXCP_GENTER` (20).
- `trans_GEXIT` (`:8878`): requires guarded; calls `helper_gexit`.
- `EXCP_GENTER` handler (`helper.c:9821`): `addr = gxf_enter_el[new_el]`,
  banks ELR/SPSR into `elr_gl/spsr_gl`, sets `gxf_status_el[new_el] |= 1`.
- `HELPER(gexit)` (`helper-a64.c:691`): restores pstate from `spsr_gl`,
  clears `gxf_status_el&1`, `pc = elr_gl[cur_el]`.
- `EXCP_GXF_ABORT` (21): vectors to `gxf_abort_el[new_el]`; guarded aborts bank
  FAR/ESR into the `*_gl` arrays (`helper.c:9743`, `tlb_helper.c:208`).

**Summary:** Inferno fully models GL1 at EL1 — enter/exit, banked
SP/TPIDR/VBAR/SPSR/ELR/ESR/FAR/ASPSR, GXF control regs, SPRR/MPRR, guarded MMU
indexes, and guarded abort vectoring. This is exactly PPL's environment.

---

## 2. Evidence that SPTM/TXM are GL1, not a new level

Tooling: `otool -l/-h`, NDK `llvm-objdump --macho -d`, and a Python opcode/sysreg
scanner (scans for the canonical GENTER/GEXIT words and decodes MRS/MSR encodings).

### 2.1 SPTM (`27.0.1_sptm.t8030.release.bin`, Mach-O arm64, MH_DYLINKER-style)
- Segments: `__TEXT_EXEC.{__text,__exc}`, a 12-byte `__LAST.__pinst`, `__DATA`,
  `__BOOTDATA`. Entry (LC_UNIXTHREAD) → `__text`.
- GENTER/GEXIT: **1 GENTER, 4 GEXIT** in `__text` (coordinator's whole-image count:
  1 GENTER / 7 GEXIT). The single GENTER is SPTM's **own boot bootstrap**: it sets
  `SPRR_CONFIG_EL0` and `GXF_CONFIG_EL1` to 1, `ISB`, `mov x1, sp`, then GENTER —
  i.e. it enables GXF and enters GL1 for the first time, then programs
  `GXF_ENTER_EL1`, `GXF_ABORT_EL1`, `SPRR_EL1BR0_EL1`, etc. from inside GL1.
- A GEXIT site is immediately followed by a `BTI` landing pad + `MSR PAN,#0` — this
  is SPTM's guarded **entry/exit gate**: GEXIT returns to the kernel; the kernel's
  next GENTER lands back on that gate.
- GXF system registers SPTM touches are **all GL1/EL1**: `GXF_STATUS_EL1`
  (151 reads — the dominant guarded-state check), `GXF_ENTER_EL1`, `GXF_ABORT_EL1`,
  `ASPSR_GL11`, `VBAR_GL11`, plus SPRR/MPRR. **No `*_GL12`, no `GXF_*_EL2`, no
  `CRm`-group indicating a second guarded level appears.**
- SPTM runs guarded at **EL1**, not EL2: it programs `VBAR_EL1/SPSR_EL1/ELR_EL1/
  SCTLR_EL1/TTBR0_1_EL1/TCR_EL1/MAIR_EL1`; the only EL2 named reg is a single
  `VTTBR_EL2` write. Its vectors are the `GXF_ENTER_EL1`/guarded-`VBAR` set.

### 2.2 TXM (`27.0.1_txm.iphoneos.release.bin`)
- Segments: `__TEXT_EXEC.{__text,__exc}`, `__TEXT_BOOT_EXEC.{__text,__bootcode}`.
- GENTER/GEXIT: effectively **enter-from-outside, exit-only** (0–1 GENTER, several
  GEXIT; coordinator: 0 GENTER / 9 GEXIT). TXM is a *leaf* guarded monitor.
- **TXM programs zero GXF/SPRR system registers** (a full-file scan for Apple
  `CRn=15` sysregs finds none in the GXF space). TXM does **not** set up its own
  guarded entry vector — SPTM owns the guarded environment and dispatches to TXM
  within guarded EL1. This is only possible if TXM shares SPTM's guarded level.

### 2.3 Kernel GENTER call convention — PPL (26) vs SPTM (27)
Disassembling each kernel's main `__TEXT_EXEC` and decoding the register setup
immediately before every GENTER:

| | iOS 26.6.2 (PPL) | iOS 27.0.1 (SPTM) |
|---|---|---|
| GENTER sites in kernel | **9** | **175** (≈176) |
| selector register | **x15** = routine index (`MOVZ x15,#n`, low bits) | **x16** = selector in **bits[63:48]** (`MOVK/MOVZ x16,#sel,lsl #48`) |
| lands at | `GXF_ENTER_EL1` (GL1) | `GXF_ENTER_EL1` (GL1) |
| guarded-state check | reads `GXF_STATUS_EL1` | reads `GXF_STATUS_EL1` |

Both eras GENTER from **non-guarded EL1** into **GL1**; neither nests GENTER.
The change from 26→27 is a **software dispatch ABI change** (x15 routine index →
x16 high-halfword selector) and far more call sites (SPTM mediates page-table and
trust operations the kernel used to do directly), **not** a new guarded level.
Selector-in-x16[63:48] is consistent with SPTM demultiplexing to itself vs TXM
and to an operation number — i.e. monitor selection by register, exactly the
"GL1 is enough, dispatch moved to software" picture.

### 2.4 Why GENTER-while-guarded never fires
SPTM's only GENTER is its boot bootstrap, executed from non-guarded EL1. The kernel
GENTERs from non-guarded EL1. TXM does not GENTER. So Inferno's current
"reject GENTER while guarded" is **not** hit on the real boot path, and no nested
GL1→GL2 transition is required. (If a future image nests GENTER, that becomes a
real gap; see OPEN-1.)

---

## 3. The gap (certain vs inferred)

### 3.1 CERTAIN — missing system registers (UNDEF/boot blockers)
Encodings observed in SPTM `__text` and/or the iOS 27 kernel `__TEXT_EXEC` that
`a13_gxf.c` does **not** define. An EL1 access to an undefined Apple sysreg raises
UNDEF, which will crash SPTM/TXM/kernel the moment they touch it.

GXF/SPRR space (`op0=3, op1=6, CRn=15`):
| Encoding `S3_6_C15_Cz_w` | seen in | note |
|---|---|---|
| `S3_6_C15_C0_0` | kernel (x2) | new GXF-space reg, CRm=0 |
| `S3_6_C15_C2_0` | kernel (x2) | new GXF-space reg, CRm=2 |
| `S3_6_C15_C8_7` | SPTM (x1, dedicated setter) | new GXF control reg in the GXF_* (CRm=8) group |

Apple EL2 impl-defined space (`op0=3, op1=4, CRn=15`) — reached from EL1 on Apple
cores (both the kernel and guarded SPTM use them):
| Encoding | seen in |
|---|---|
| `S3_4_C15_C0_0..C0_3` | kernel |
| `S3_4_C15_C0_4`, `C0_5` | SPTM (RMW at bootstrap) |
| `S3_4_C15_C1_0`, `C1_1`, `C1_2` | SPTM |
| `S3_4_C15_C1_3`, `C1_4` | kernel |
| `S3_4_C15_C2_2`, `C2_3`, `C2_4`, `C2_5` | SPTM (+ kernel C2_3/C2_4) |

### 3.2 INFERRED — register semantics
- `S3_6_C15_C8_7` sits in the GXF control group (CRm=8: status/enter/abort/aspsr =
  op2 0/1/2/3). op2=7 is **inferred** to be an additional GXF control/lockdown or
  secondary-entry register. *Resolution:* trace the value SPTM writes and whether it
  gates later GXF behaviour; cross-check against XNU `pexpert`/`osfmk/arm64`
  `GXF_*`/`SPRR_*` register headers if/when available.
- `S3_4_C15_C{0,1,2}_*` are **inferred** to be Apple EL2-side GXF/SPRR/HID config
  (SPRR/APRR-at-EL2, GXF-enable-at-EL2, or core HID). SPTM does RMW on C0_4 at the
  very start of `__text` (reset path), consistent with a core-config/HID register.
  *Resolution:* same XNU header cross-check; diff against the known A13/A14 HID
  register map.

### 3.3 INFERRED — SPRR/MPRR isolation is not enforced
Inferno stores SPRR/MPRR and runs the EL0 perm-remap arithmetic
(`sprr_perm_el0_write`) but does **not** enforce SPRR/MPRR page-permission domains
as actual access control. SPTM↔TXM↔kernel isolation at the *same* GL1 relies on
these domains. For a first **boot** this does not block execution (QEMU won't fault
on it); for faithful security behaviour it is a separate, larger task. Marked OPEN-2.

### 3.4 NOT a gap
- No new guarded **level** (GL2) and no `*_GL12` / `GXF_*_EL2` banked registers are
  needed for t8030 (§2). The `gxf.*_gl[4]` arrays already have headroom if a future
  SoC needs GL2.
- GENTER/GEXIT semantics and the guarded abort vectoring are correct as-is.

---

## 4. The kernel/SPTM boot picture (for cross-reference with the loader agents)
- iOS 27 kernel is `MH_FILESET`; `com.apple.kernel` entry + a sectionless
  `__TEXT_BOOT_EXEC` segment (32 KiB). That boot-exec contains **one GENTER** (at
  offset `+0xb4`) and otherwise almost no Apple sysreg use — it is the early
  trampoline that hands control into the already-running guarded monitor
  environment. `__DATA_SPTM` carries the SPTM hand-off data.
- SPTM/TXM are separate signed Mach-O images (`sptm.t8030.release.im4p`,
  `txm.iphoneos.release.im4p`), loaded before the kernel; SPTM bootstraps GXF (§2.1)
  and then the kernel's 175 GENTER sites call in with the x16 selector.

---

## 5. Concrete CPU-model proposal

Principle: **close the register gaps so firmware does not UNDEF**; do **not** add a
guarded level or change GENTER/GEXIT. Keep all edits in CPU-model files
(`target/arm/cpu.h`, `hw/arm/a13_gxf.c`) — no `hw/arm/t8030.c` changes (owned by the
loader agents).

### 5.1 `target/arm/cpu.h` — backing storage (in the existing `env->gxf` struct)
```c
    struct
    {
        uint64_t gxf_config_el[4];
        uint64_t gxf_enter_el[4];
        uint64_t gxf_status_el[4];
        uint64_t gxf_abort_el[4];
        uint64_t sp_gl[4];
        uint64_t tpidr_gl[4];
        uint64_t vbar_gl[4];
        uint64_t spsr_gl[4];
        uint64_t aspsr_gl[4];
        uint64_t esr_gl[4];
        uint64_t elr_gl[4];
        uint64_t far_gl[4];
        /* iOS 27 SPTM/TXM/kernel: Apple GXF-space control regs not yet
         * individually modelled. Backed as RW storage (semantics OPEN). */
        uint64_t gxf_ctl_misc[4];     /* [0]=S3_6_C15_C0_0 [1]=S3_6_C15_C2_0
                                         [2]=S3_6_C15_C8_7 */
        /* Apple EL2 impl-defined block S3_4_C15_C{0,1,2}_* (OPEN). */
        uint64_t apple_el2_impdef[16];
    } gxf;
```

### 5.2 `hw/arm/a13_gxf.c` — new reginfo entries (appended to `apple_a13_gxf_cp_reginfo[]`)
Draft C in `docs/ios27/drafts/a13_gxf_sptm_regs.inc.c`. Shape of each entry:
```c
    { .name = "APPLE_GXF_C8_7_EL1", .cp = CP_REG_ARM64_SYSREG_CP,
      .state = ARM_CP_STATE_AA64, .opc0 = 3, .opc1 = 6, .crn = 15, .crm = 8,
      .opc2 = 7, .access = PL1_RW, .resetvalue = 0,
      .fieldoffset = offsetof(CPUARMState, gxf.gxf_ctl_misc[2]) },
```
- GXF-space (op1=6): `C0_0 → gxf_ctl_misc[0]`, `C2_0 → gxf_ctl_misc[1]`,
  `C8_7 → gxf_ctl_misc[2]`, all `PL1_RW` storage.
- EL2 impl-defined (op1=4): sixteen `PL1_RW` storage entries mapping
  `C0_0..C0_5 → apple_el2_impdef[0..5]`, `C1_0..C1_4 → [6..10]`,
  `C2_2..C2_5 → [11..14]`. **`PL1_RW` (not `PL2_RW`)** because on Apple cores these
  are reached from EL1 (observed in both the non-guarded kernel and guarded SPTM);
  QEMU's access check uses `.access`, and the `op1=4` field is only part of the
  encoding key.

No `accessfn` is needed: the kernel (non-guarded EL1) also uses several of these,
so an `access_gxf` (guarded-only) gate would wrongly UNDEF the kernel.

### 5.3 Decodetree
**No change.** GENTER/GEXIT already decode (`a64.decode:1912-1913`) and the
canonical SPTM/kernel opcodes `0x00201420`/`0x00201400` match `@apple_rd`.

---

## 6. Minimal first milestone
**M0 — "guarded firmware stops UNDEF-ing on its own config registers."**
With §5 applied, a guest can, at EL1:
1. `MSR GXF_CONFIG_EL1, #1` / `MSR SPRR_CONFIG_EL0, #1`, `ISB`;
2. write `GXF_ENTER_EL1` with a vector, `GENTER` → land guarded at that vector
   (already works today);
3. from guarded EL1, read/write every register in §5.2 **without UNDEF**, and
   `GEXIT` back.
This reproduces SPTM's observed bootstrap prologue (§2.1) in miniature and is the
precondition for the SPTM loader agent to get SPTM past its early init.

**M1 (follow-on, loader agents):** kernel GENTER with x16[63:48] selector →
SPTM dispatcher → GEXIT return; TXM reached from within guarded EL1.

See §8 for the self-contained test that exercises M0.

---

## 7. OPEN questions
- **OPEN-1 (nested GENTER / GL2).** If any 27.x image ever executes GENTER while
  already guarded (true GL1→GL2 nesting), the model needs a GL counter in
  `arm_is_guarded`, a second wired bank (`*_gl[2]`), and removal of the
  "reject GENTER while guarded" check. *Not observed on t8030 27.0.1* (SPTM's only
  GENTER is from non-guarded EL1). *Resolution:* re-scan for GENTER reachable from a
  guarded PC in SPTM/TXM; re-check on each new build.
- **OPEN-2 (SPRR/MPRR enforcement).** Faithful SPTM↔TXM↔kernel memory isolation
  needs SPRR/MPRR domains enforced as access control, not just stored. *Resolution:*
  separate task; map SPRR indices to QEMU page prot and gate loads/stores by the
  active BR domain.
- **OPEN-3 (exact register semantics).** Names/semantics of `S3_6_C15_C8_7` and the
  `S3_4_C15_C{0,1,2}_*` block are inferred (§3.2). Storage-backed RW is enough for
  boot but may need side effects. *Resolution:* XNU `osfmk/arm64` register headers
  cross-check; observe written values and any readback-dependent control flow.
- **OPEN-4 (x16 selector layout).** The exact field split of the x16 selector
  (monitor id vs operation number vs the low-bit operands) is only partially read.
  *Resolution:* SPTM-dispatch agent to decode SPTM's demux on x16[63:48].

## 8. Self-contained test
`docs/ios27/drafts/gxf_sptm_regs_test.S` — a tiny bare-metal AArch64 sequence for
`-cpu apple-gxf` that MSR/MRS-round-trips each new register (§5.2) at EL1 and
performs a GENTER/GEXIT round trip, writing a pass token to a known address.
A UNDEF on any access (i.e. a missing register) diverts to the exception vector and
writes a fail token, so the test fails closed. Build/run notes are in the draft
header. This proves M0 on the modified CPU model without needing Apple firmware.
