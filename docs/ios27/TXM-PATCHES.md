# iOS 27 (t8030): where the kernel security checks moved

Status: DRAFT. Documentation deliverable for the SPTM/TXM boot-chain effort.

**Scope note.** The goal is to boot iOS 27 in the emulator faithfully. This
document is **reverse-engineering documentation only**: it records *where* each
check that Inferno's existing `kernel_patches.c` used to match now lives on
26.6.2 / 27.0.1, and *why* the old pattern no longer matches. It deliberately
contains **no new patterns to bypass or disable security checks** (code signing,
trust cache, AMFI, root auth, pmap/page enforcement). It exists so the boot-chain
work knows which enforcement is now kernel-side vs. in TXM.

All firmware inspected was a local research copy, read-only; no Apple bytes are
reproduced — findings are mnemonics / offsets / segment names only. Refines the
patch matrix in `docs/IOS27.md` §3.

## 1. Summary

| Check (kernel_patches.c name) | 26 | 27 | Where it is on 27 |
|---|---|---|---|
| allow SHA1 signatures in AMFI | ✅ | ❌ | AMFI kext, same function — only a compared immediate changed |
| disable AMX | ✅ | ❌ | kernel text, same `msr amx_config_el1` site — surrounding store relocated |
| bypass pmap_cs_enforce | ❌ | ❌ | 26: still in `__PPLTEXT`; 27: **moved to TXM** (PPL gone) |
| allow remounting rootfs / union mounts | ❌ | ❌ | kernel mount path; anchor constant changed (pre-27 drift) |
| force enable kprintf | ❌ | ❌ | kernel; serial gate restructured (pre-27 drift; debug-only) |

Also verified: the matrix's ✅ for **"all binaries in trustcache (iOS 16+)"** in
27 kernel text is a **false positive** — see §6.1.

The structural headline: on 27 the kernel has **no `__PPLTEXT`**, and
code-signing / trust-cache / Image4 trust evaluation / entitlement checks /
page-execute enforcement are all present in the **TXM** image. On 27 the kernel
is a *client* of TXM for these decisions. That is the reason the SPTM/TXM boot
chain has to exist before 27 can boot — not a matter of re-matching kernel
patterns.

## 2. Method & matcher recap

`ck_patcher_find_callback(_ctx)` scans a `CKPatcherRange` (a Mach-O
segment+section mapped to a host pointer) for `pattern` under `mask` (`align`=4
for instructions). On a match it invokes a callback that usually walks
`ck_patcher_find_prev_insn` / `find_next_insn` (masked single-instruction search
bounded by an instruction count) to locate the real site. A patch is reported
*applied* only if the callback returns true — so a pattern can **match** while the
patch still reports **failure** because the callback's secondary search misses.
That distinction explains both 27-only regressions (§3, §4).

Section ranges were parsed from the `MH_FILESET` kernelcaches (each kext —
`com.apple.kernel`, `…AppleMobileFileIntegrity`, `…AppleImage4`,
`…filesystems.apfs` — carries its own segments). Matching was reproduced in
Python against the exact patterns/masks in `hw/arm/kernel_patches.c`; code
windows were decoded with a small local AArch64 decoder (mnemonics only).

Confirmed structure:

- 26.6.2 `com.apple.kernel` has a `__PPLTEXT` segment; 27.0.1 does **not** (it
  has `__DATA_SPTM` instead). Matches `docs/IOS27.md`.
- TXM (`27.0.1_txm.iphoneos.release.bin`) is a standalone arm64 Mach-O
  (filetype 2) with `__TEXT`, `__DATA_CONST` (incl. `__auth_ptr`), `__TEXT_EXEC`
  (`__text`, `__exc`), `__TEXT_BOOT_EXEC` (`__text`, `__bootcode`), `__DATA`,
  `__LINKEDIT`. Load address base `0xfffffff017004000`. Identity strings:
  "Trusted Execution Monitor", `com.apple.security.libimage4.TXMonitor`,
  "Code Signing Monitor Image4 Module".

## 3. AMFI "allow SHA1 signatures" — 27-only failure — logic still kernel-side

**Where:** AMFI kext `__TEXT_EXEC.__text`, same function on 26 and 27 — a
hash-strength gate immediately after `tbz w2, #26` and a `bl` to a hash-eval
helper.

**Why the matrix shows ❌ on 27:** the `tbz w2, #0x1A` anchor matches on *both*
26 and 27. The callback then searches the following few instructions for an
*exact* `cmp w0, #2` and acts on it. On 26 the instruction after the `bl` is
`cmp w0, #2`; on **27 it is `cmp w0, #1`** (the compared hash-strength constant
changed). The exact-immediate search returns NULL, so the patch reports failure.

**Conclusion:** the check did **not** move to TXM; it is the same kernel-side
comparison with a different constant. (No bypass pattern is proposed here, per
scope.) This is useful only as evidence that AMFI hash-strength selection is
still a kernel decision on 27.

## 4. AMX "disable AMX" — 27-only failure — logic still kernel-side

**Where:** kernel `__TEXT_EXEC.__text`. The `msr amx_config_el1, x10` + `isb`
site used by the `disable AMX (newer)` variant is present on both 26 and 27.

**Why the matrix shows ❌ on 27:** the `(newer)` callback, having matched the
`msr`, searches the **6 instructions before it** for a 32-bit store to
`gAMXVersion` and neutralises that store.

- 26: the store (`adrp; movz w9,#1; str w9,[x8,#gAMXVersion]`) sits ~4 insns
  before the `msr`; found → reported applied.
- 27: the surrounding code is restructured into a loop with two
  `msr amx_config_el1` sites and **no** store-to-global within 24 insns before
  the matched `msr`; the 6-insn search fails → reported failure.

The older commpage-capability anchors (`mov w?,#0x3C80; movk w?,#0x900,lsl#16;
orr …`) match on neither 26 nor 27 — the `movk w?,#0x900,lsl#16` half is absent
from `com.apple.kernel` text on both.

**Boot relevance (important):** AMX matters to emulation only if the guest
executes AMX ops or an AMX system-register access that TCG cannot handle. Note
the AMX patch never NOPed the `msr amx_config_el1` itself (only the
`gAMXVersion` store / commpage bits), and 26 booted with the `msr` intact — so
the `msr` is evidently tolerated. Whether 27's AMX init path needs any emulator
handling is **OPEN** (§8), but this is a CPU-feature-advertisement question, not
a security bypass.

## 5. pmap_cs_enforce — fails 26 and 27 — moved to TXM on 27

**26:** `__PPLTEXT.__text`. The `pmap_cs` failure result `movn x8, #0x31c`
(=`#0xffffffffffffce3`) appears exactly once, in PPL text. The pattern expects
`bl; cbnz w0; movn x8,#0x31c`; on 26 the instruction before the `movn` is a
`b.cond` following a `tst`/`ands` — a flag-test shape — so the pattern misses.
The logic is still in PPL on 26, just scheduled differently.

**27:** gone. The `0xce3` sentinel is absent from the **entire** 27 kernelcache
(all fileset entries), and `__PPLTEXT` does not exist. TXM carries the
replacement — strings: `"page enforcement failed (%u | %u): …"`,
`"non-executable privilege level"`, `"disallowed executable debug mapping"`,
`"denying executable mapping as the kernel has panicked"`. Page/CS execute
enforcement is a TXM responsibility on 27.

**Conclusion:** on 27 there is no kernel site here at all; the decision is taken
inside TXM when the kernel requests a mapping/trust operation. This is the
clearest single example of enforcement migrating kernel → TXM.

## 6. union/rootfs remount and kprintf — fail on 26 and 27 — pre-27 drift

Both were already ❌ on 26, so they are pre-27 regressions (likely covered by
unpushed upstream "Gaia" work). Lower priority.

- **union/rootfs remount:** both anchors — `orr w9, wzr, #0x1FFE` (old) and
  `movz w9, #0x1FFE` (new) — are absent from `com.apple.kernel` text on 26 and
  27; there is no `movz`/`movk w?,#0x1FFE` anywhere in kernel text. The flag-mask
  constant used by the mount/remount path changed, so the anchor no longer
  exists. This is a VFS mount policy check, still kernel-side; exact 27 anchor is
  **OPEN**.
- **kprintf:** both anchors (varargs prologue `add x?,fp,#0x10; str x?,[sp,#…];
  orr w8,…; cbz w8`, and the newer `ldrb w8,[x8,#?]; tbz w8,#0; …; b`) are absent
  from 26 and 27 kernel text. The serial-output gate was restructured. Debug-only,
  not required to boot. Exact 27 anchor is **OPEN**.

### 6.1 "all binaries in trustcache (iOS 16+)" ✅ on 27 is a false positive

The `(iOS 16+)` fallback anchors on `movz x?, #0xEE7E` and, on a match, walks
back to the enclosing `pacibsp` and overwrites the function prologue with
`mov w0, #0x101; ret`. On 27 `0xEE7E` occurs three times in kernel text, and the
first two are **PAC call-site discriminators**: each `movz x17, #0xEE7E` is
immediately followed by `blraa x8, x17` (authenticated indirect branch with the
modifier in x17). `0xEE7E` here is a pointer-auth diversifier, **not** the
trust-cache magic the patch assumes. Applying the patch would rewrite the
prologue of whatever unrelated function encloses that call site, so the ✅ is
spurious and, if the patch pipeline ran on 27, actively harmful. The real
trust-cache enforcement is in TXM (§5, §7). This matters for faithful boot:
the patch set should **not** apply this fallback on 27.

## 7. Implication for the boot chain (not patches)

Because trust/CS/page enforcement is now in TXM, booting 27 faithfully requires
**loading and mapping TXM** (and SPTM) as part of the boot flow, in the physical
locations the real bootloader uses, and wiring the device-tree / boot-args the
monitors expect — exactly the emulator work now in progress on branch
`ios27/txm-load` of Inferno (separate PR). TXM is itself an Img4 payload
(`txm.iphoneos.release.im4p`) with the same PAYP-tagged structure already handled
by the fixed IM4P decoder, decompressing (LZFSE) to the Mach-O described in §2.

The IPSW also ships a **research** TXM (`txm.iphoneos.research.im4p`). Both 27.0.1
TXM images are the same size (557088 bytes) and differ in only ~4.4 KB across
~105 byte-runs: ~3984 bytes in `__TEXT.__cstring` (diagnostic/log strings), only
~419 bytes (~100 instructions) in `__TEXT_EXEC.__text`, and ~22 bytes of
`__binname`/header identity. So the research build differs almost entirely by
**diagnostics**, not by relaxed enforcement — loading it gives a more talkative
monitor during bring-up but is **not** a bypass of TXM. The precise
characterisation of the ~419-byte code delta is follow-up and does not change
this conclusion. The emulator should prefer the research TXM for its logging,
exactly as the guide already prefers research kernelcaches.

## 8. OPEN questions (how to resolve)

- **RESOLVED — research vs release TXM delta** (§7): ~4.4 KB total, ~3984 B in
  `__cstring` (diagnostics) and only ~419 B in `__text`; the research build adds
  logging, not relaxed enforcement. A per-function breakdown of the ~419 B code
  delta is optional follow-up.
- **OPEN — AMX on 27 for emulation.** Does 27's AMX init touch any
  system-register / instruction TCG must handle? Resolve by locating
  `_commpage_populate` + the AMX init path on 27 and checking against Inferno's
  current AMX handling. CPU-feature question, not security.
- **OPEN — 27 union/rootfs remount anchor** and **27 kprintf serial gate.** New
  constants/instructions; low priority (pre-27 drift, likely upstream-fixed).
- **RESOLVED — "all binaries in trustcache (iOS 16+)" on 27 is a false
  positive** (§6.1): `0xEE7E` is a PAC call-site discriminator, not the
  trust-cache magic.
```
