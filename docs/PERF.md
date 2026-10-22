# Restore-boot benchmark and profile (iOS 14.8, Android)

Issue #48. Measured on the Android **emulator** (`sdk_gphone64_arm64`, 8 host cores) with
`tools/restore_bench.py`. The boot is the iOS 14.8 restore environment from
`docs/PHONE-SMOKE.md`, run until restore mode waits for a host.

## Summary

- **The restore boot takes 13.1 s** (median of 5) with the app's `-smp 4`, from QEMU start
  to "waiting for host". Average load is under one host core (≈0.95), so the boot is not
  throughput-bound.
- **Fewer vCPUs is faster: 8.5 s with `-smp 2`.** With two or more application vCPUs,
  restored's `enable_usb` step takes **3.05 s** in nearly every run; with one it takes
  **0.06 s**. That wait is 23% of the boot. It turned out to be a guest-side race, not a lost
  wakeup in the emulation (#51, below). **For now, restore boots should use `-smp 2`.**
- In host CPU (AP side), **34% runs guest code**. The biggest QEMU-side cost is
  **indirect-branch TB lookup (≈18%, `helper_lookup_tb_ptr` alone 12%)**, then translation,
  system-register helpers and TLB fills at about 6–7% each.
- **Update 2026-10-22: TCG fast paths (Inferno #22, #23, #25, #26) cut the boot's host CPU by
  5% and `BSD root` by 9%**: `waiting_host` 19.75 → 18.82 s at `-smp 4` on a slower emulator
  than the one above. On the microbenchmarks, an indirect-branch return is 29% cheaper, a
  `TPIDR_EL1` read goes from about 24 ns to under 1 ns, and storage-only system-register
  writes no longer end the TB. See [TCG optimisations](#tcg-optimisations-2026-10-22).
- **Update 2026-10-22 (Inferno 3f2e61d → 2a65516): guest FP on the host FPU and ASID TLB
  contexts.** Native FP (Inferno #30, #32, #33, #38, on by default, `-accel tcg,native-fp=off`
  turns it off) takes the vector-FP microbenchmark from 3.72 s to 0.08 s and the Linux FP
  workloads 2-4x faster. ASID TLB contexts (Inferno #31, #36, `-accel tcg,asid-tlbs=0` turns
  them off) stop QEMU refilling its TLB and jump cache on every address-space switch: a KPTI
  Linux system call is 6.1x cheaper. **The restore boot is unchanged by both** (little FP, 46
  ASID switches/s). See [Native FP and ASID TLB contexts](#native-fp-and-asid-tlb-contexts-2026-10-22).
- **Update 2026-10-22 (Inferno 2a65516 → 52f62f4): the softmmu memory path.** Two fewer
  instructions in every guest load/store's inline TLB lookup (Inferno #39) and a 1024-entry
  TLB floor with a cheaper `tlb_reset_dirty` (#40): **the restore boot is 5.3% faster
  (13.84 → 13.11 s) and uses 7.4% less host CPU** (12+12 emulator runs). See
  [Softmmu memory path](#softmmu-memory-path-2026-10-22).
- **Compiler options (#77, below):** PGO (profile from the restore boot) takes 5.9% off the
  boot's wall time and 8.1% off its host CPU on current Inferno, or 6.3% and 10.0% with
  ThinLTO. ThinLTO alone gives ≤3.6% (within noise) and makes the libraries 11% smaller; `-O3`
  doesn't help. All are opt-in (`QEMU_PGO_PROFILE`, `QEMU_LTO`, `QEMU_OPT`, `QEMU_TUNE`); the
  default build is unchanged.

## Setup

| | |
|---|---|
| QEMU | Inferno master `0871bf895c`, Android cross build, release, unstripped; standard dev options (tracing off), as the app's build |
| Guest | iOS 14.8 (18H17) research kernelcache, n104ap DT, erase restore ramdisk + its trustcache |
| SEP | placeholders (no real SEP inputs on the emulator); see the notes |
| Command | the app's `QemuCommand` (`tcg,thread=multi,tb-size=1024`, same `-append`), with `-m 2G -snapshot`, `-serial stdio` |
| Timing | every serial line is stamped on the device with `/proc/uptime` as it arrives (10 ms resolution, no per-line process spawn); t = 0 is QEMU launch |

## Benchmark

`waiting_host` is when restored prints "waiting for host to trigger start of restore". The
run stops there.

| Milestone | `-smp 4` (app default), n=5 | `-smp 3`, n=3 | `-smp 2`, n=3 |
|---|---|---|---|
| first serial (QEMU start-up) | 0.7 s (0.6–4.3) | 0.6 s | 0.6 s |
| `BSD root: md0` | 2.6 s (2.4–9.8) | 2.5 s | 2.8 s |
| `launchd` hello | 4.2 s (4.1–11.4) | 4.0 s | 4.3 s |
| **`waiting_host`** | **13.1 s (13.0–20.6)** | **12.4 s** | **8.5 s** |
| host CPU, user + sys | ≈9.5 + 3.1 s | | ≈7.6 + 1.5 s |
| average host cores busy | ≈0.95 | | ≈1.08 |

Values are median (min–max). In the `-smp 4` set, run 1 was a cold-start outlier: 4.3 s
before the first serial line, so QEMU start-up on the host was slow. The other four runs
were 13.0–13.5 s.

### Where the time goes (`-smp 4`, medians)

| Phase | Time | Nature |
|---|---|---|
| QEMU start → first serial | 0.7 s | host: image decode (LZFSE), kernel patcher, device tree, setup |
| → `BSD root` | 1.9 s | kernel + IOKit bring-up |
| → `launchd` | 1.6 s | root mount, first userland |
| `launchd` → restored starts | 5.5 s | process launches (dyld, ObjC runtime, page faults); **3.7 s at `-smp 2`** |
| restored → `enable_usb` | 0.4 s | |
| **`enable_usb`** | **3.05 s** | **a fixed wait; 0.06 s at `-smp 2`** |

The 3.05 s `enable_usb` wait is identical in every multi-vCPU run (3.04–3.06 s), uses no
host CPU, and is gone with a single application vCPU.

## Profile

`restore_bench.py --profile <NDK simpleperf>` adds one run under `simpleperf record -e
cpu-clock:u`. That run reached `waiting_host` at 14.4 s, close to unprofiled, so overhead is
low.

Two caveats:
- **User space only.** On user builds SELinux denies the adb shell `perf_event { kernel }`,
  so kernel time (≈25% of QEMU's CPU, the "sys" figure above) isn't sampled.
- **The SEP vCPU thread (`CPU 3/TCG`) is excluded.** With placeholder SEP inputs it spins,
  which isn't the iOS workload; it was 47.5% of all samples in this run (see notes).
  Percentages below are of everything else: the three application vCPUs plus QEMU's main
  and I/O threads.

| Category | Share |
|---|---|
| guest code (TCG-generated) | 34.4% |
| **TB lookup / chaining** | **17.8%** |
| TCG translation (incl. icache flush) | 7.1% |
| system-register helpers | 6.5% |
| softmmu: TLB fill / page walk | 6.5% |
| other (long tail, 52 symbols ≤ 0.4% each) | 5.2% |
| locks / syscalls / libc (memcpy, memset) | 4.1% |
| other TCG helpers (guest ops; SHA-256 ≈1.8%) | 3.9% |
| timers / clock | 2.7% |
| Inferno/Apple checks (`helper_guarded_page_check`) | 2.4% |
| softmmu: slow-path loads/stores | 1.9% |
| start-up: image decode/load (LZFSE, patcher) | 1.7% |
| MMIO / device models | 0.5% |
| below 0.05% per symbol | 5.4% |

Top QEMU symbols: `helper_lookup_tb_ptr` 12.1%, `qht_lookup_custom` 2.7%,
`helper_lookup_cp_reg` 2.1%, `helper_get_cp_reg64` 2.0%, `liveness_pass_1` 2.0%,
`probe_access_internal` 1.8%, `helper_access_check_cp_reg` 1.8%,
`__kernel_clock_gettime` 1.7%, `helper_guarded_page_check` 1.6%, `tcg_gen_code` 1.5%,
`flush_idcache_range` 1.1%. Per thread, CPU 0 does about twice the work of CPUs 1 and 2,
and the main loop takes about 4%.

## TCG optimisations (2026-10-22)

Inferno changes, each merged with CI green. The numbers below come from interleaved
runs on the same emulator, under a lock so runs never overlap.

| PR | Change |
|---|---|
| [Inferno#22](https://github.com/dylanneve1/pyre/pull/22) | Jump-cache hit in `lookup_and_goto_ptr` with the TB state computed inline: no indirect call, no stack frame, one compare branch. About 115 → 69 host instructions per hit |
| [Inferno#23](https://github.com/dylanneve1/pyre/pull/23) | The GXF-banked TPIDR/SPSR/ELR/ESR/FAR_EL1 become plain field loads/stores, with the GL1 copy chosen at translation time from the TB's MMU index. Before, every access called two helpers (`lookup_cp_reg` + `get/set_cp_reg64`). Adds the `gxf_banked_el1` a13-test to CI |
| [Inferno#25](https://github.com/dylanneve1/pyre/pull/25) | A PCREL TB invalidation clears the TB's 64 possible jump-cache slots instead of every vCPU's whole 16k-entry cache |
| [Inferno#26](https://github.com/dylanneve1/pyre/pull/26) | Writes to those storage-only registers, and to their `_GL11` views, no longer end the TB. They caused about ⅔ of the 496k sysreg-write TB exits per boot |
| [Inferno#27](https://github.com/dylanneve1/pyre/pull/27) | `hw/arm/tests/run-a13-bench.py` + `tcg_bench.S`: the microbenchmarks below, bare metal on the `a13-test` machine, no Apple code |
| [Inferno#28](https://github.com/dylanneve1/pyre/pull/28) | t8030 keeps the SEP core powered off when its ROM can only idle (a loop of only WFI/WFE/NOP/YIELD/`B`, like the `wfi` and `b .` placeholders), so placeholder SEP inputs no longer spin a host core. Real SEPROMs are never matched. See [below](#placeholder-sep-roms-inferno28) |

**Reliability.** Inferno#41 (IOMFB simulator-check race) removes a ~2–3% boot-time guest panic (`rt_bandwidth_setup`, data abort); no performance change. See [issue #100](https://github.com/dylanneve1/scion/issues/100).

**Setup.** Android emulator `sdk_gphone64_arm64` on the Mac mini (8 vCPUs, 4 GB; host load
about 3–5 from other work). The QEMU binaries are the CI `qemu-android-arm64` artifacts,
the same build as the app, but stripped. The kit and the command are the same as above,
with the `wfi` SEP placeholder. This emulator is slower than the one in the tables above
(master boots in 19.75 s here vs 13.1 s there), so compare only within this section.

### Restore boot (`restore_bench.py`, medians)

| Milestone | master `0d6b857`, n=6 | + #22 #23 #25, n=6 | + #26 (= master `339be2c`), n=6 |
|---|---|---|---|
| `BSD root: md0` | 4.33 s | 4.12 s | **3.95 s (−9%)** |
| `launchd` hello | 7.04 s | 6.87 s | **6.59 s (−6%)** |
| **`waiting_host`** | 19.75 s | 19.09 s | **18.82 s (−4.7%)** |
| host CPU (user + sys) | 21.15 s | 20.36 s | **20.07 s (−5%)** |

All 18 boots reached `waiting_host` with no panic. Most of the restore boot is guest-side
waiting (the 3 s `enable_usb` stall and the timeouts), so the CPU-bound `BSD root` phase
and the CPU total show the gain best.

### Placeholder SEP ROMs (Inferno#28)

Same emulator, -smp 4, master `339be2c` (before) vs `3f2e61d` (after). Both are release
Android cross builds made the same way (unstripped, log trace backend), run interleaved under
the bench lock.

| SEP ROM | | `launchd` | `waiting_host` | host CPU, user + sys | idle host CPU, 30 s at `waiting_host` |
|---|---|---|---|---|---|
| `wfi` | before (n=10) | 6.5 s | 18.8 s | 20.6 s | 0.71 cores (SEP vCPU 64%) |
| `wfi` | after (n=11) | 5.7 s | **13.8 s (−27%)** | **12.2 s (−41%)** | **0.06–0.09 cores** |
| `b .` | before (n=11) | 6.5 s | 17.4 s | 22.5 s | 1.03 cores (SEP vCPU 96%) |
| `b .` | after (n=11) | 5.7 s | **13.8 s (−21%)** | **12.3 s (−45%)** | **0.07 cores** |

Boot numbers are medians and the idle column is n=2. Before, the SEP vCPU spun. The `wfi`
loop also took the BQL on every wake, so it competed with the application vCPUs: every
milestone now arrives earlier, in the same order. The guest's SEP lines
(`AppleSEPManager::start`, `_setPowerState 0 -> 2`, `AppleSEPKeyStore`) are unchanged, and
a SEP ROM that does real work (`hw/arm/tests/sep_iommu_ro_test.S`) behaves exactly as
before. Idle host CPU is now close to the ~0.05 cores measured with real SEP inputs. **Restore
boot numbers in this file from before 3f2e61d include the spinning SEP placeholder**, so
they aren't comparable with later runs.

One pre-existing flake turned up: a guest `Kernel data abort` panic at pc `…99126d4`,
after IOMFB's `rt_bandwidth_setup(): bandwidth values not found` and `DRIVER DIDN'T LOAD`.
It hit 1 of 21 runs before and 1 of 23 after.

### `-smp` (after the changes)

| | `-smp 2` | `-smp 3` | `-smp 4` |
|---|---|---|---|
| `waiting_host`, master `339be2c` | **12.30 s** (n=4) | 16.84 s (n=3) | 18.82 s (n=6) |
| `waiting_host`, master `0d6b857` | 12.78 s (n=4) | | 19.75 s (n=6) |

The multi-vCPU stall (#51) is unchanged and still dominates. `-smp 2` remains the right
choice **for restore boots**. The app default stays at 4 (3 application cores + SEP): a
normal boot to SpringBoard hasn't been measured, and its UI workload can use the extra
cores.

### Microbenchmarks (`run-a13-bench.py`, a13-test machine, median of 5)

| Workload | master `0d6b857` | + #22 #23 #25 | + #26 |
|---|---|---|---|
| w1: hot `BL`/`RET`, 240M returns | 3.03 s | 2.15 s (−29%) | 2.15 s |
| w2: `BLR`/`RET` over 4096 targets | 4.33 s | 4.03 s (−7%) | 4.03 s |
| w2s: same over 16384 targets | 3.78 s | 3.61 s (−4.5%) | 3.59 s |
| w3: 320M `MRS TPIDR_EL1` | 7.95 s | 0.21 s (−97%) | 0.21 s |
| w4: ALU loop (control) | 2.92 s | 2.90 s | 2.89 s |
| w5: w2 + 62.5k TB invalidations | 3.10 s | 1.95 s (−37%) | 2.00 s |
| w6: 40M `MSR TPIDR_EL1`/`ELR_EL1` | 3.89 s | 2.54 s (−35%) | **0.04 s** |

**What didn't pay off, or doesn't apply here:**
- **#25 has no effect on this boot.** `info jit` at `waiting_host` shows `TB invalidate
  count 0` (163k TBs, `-smp 4`). It helps guests that write over code they have run
  (JITs, page reuse under memory pressure), which needs confirming on the phone's UI
  workload.
- **TB size and cross-page chaining.** t8030 already uses 16 KiB target pages
  (`minimum_page_bits = 14`), so TLB entries and `goto_tb` chaining already cover XNU's
  16 KiB pages. The boot used 96 MB of the 1 GiB translation buffer, so a different
  `tb-size` changes nothing.
- **`helper_guarded_page_check`** (2.4% in the profile above) is gone since Inferno#20
  dropped BTI from the A13 model.
- **The cp-register hash** (`helper_lookup_cp_reg`, 2.1%) mostly served TPIDR_EL1 reads,
  12.5M of the roughly 15M helper-path accesses counted in Inferno#23, which #23 removed. What's left (CNTVCT_EL0,
  about 2.5M) isn't worth a faster table.
- **Host-side microbenchmarks on the Mac are unreliable.** Single runs on Apple silicon
  were bimodal (2.0 s or 7.6 s for the same binary and workload), probably from P/E-core
  placement. The emulator's medians agreed within ±2%.

## Native FP and ASID TLB contexts (2026-10-22)

Inferno 3f2e61d → 2a65516, each change merged with CI green and boot-validated. Both follow
from Inferno's `docs/SAME-ISA-FASTPATH.md` (where TCG's time goes on an AArch64 host): FP
was 62–79% of vCPU time in softfloat on FP code, and a KPTI Linux spent 61% of its system
calls refilling the softmmu TLB and jump cache.

| PR | Change |
|---|---|
| [Inferno#30](https://github.com/dylanneve1/pyre/pull/30), [#32](https://github.com/dylanneve1/pyre/pull/32), [#33](https://github.com/dylanneve1/pyre/pull/33), [#38](https://github.com/dylanneve1/pyre/pull/38) | Guest FP/SIMD runs on the host FPU when the guest FPCR is in the host's mode (RNE, no FZ/DN/AH, no traps): vector and scalar FADD/FSUB/FMUL/FMLA/FDIV/…, emitted inline as host FP instructions; anything else stays on softfloat. A startup self-test compares every native kernel with softfloat and falls back if any differs. **On by default** on AArch64 hosts; `-accel tcg,native-fp=off` turns it off. Docs: Inferno `docs/NATIVE-FP.md` |
| [Inferno#34](https://github.com/dylanneve1/pyre/pull/34) | SPRR configuration/permission writes from EL1 flush the TLB when they change the value (entries kept the previous thread's permissions) |
| [Inferno#37](https://github.com/dylanneve1/pyre/pull/37) | The TLB's `n_used_entries` no longer wraps below zero (it made TLBs double for no reason: 46 of 164 resizes per boot) |
| [Inferno#31](https://github.com/dylanneve1/pyre/pull/31), [#36](https://github.com/dylanneve1/pyre/pull/36) | **ASID TLB contexts**: on a TTBR ASID switch the outgoing TLBs and jump cache are kept aside (8 per vCPU) and swapped back in when the guest returns, instead of being dropped and refilled. Kept-aside copies get every invalidation the live ones get. `-accel tcg,asid-tlbs=0` turns it off. Docs: Inferno `docs/ASID-TLB.md` |

**Microbenchmarks** (`run-a13-bench.py`, a13-test, Android emulator, median of 5):

| Workload | before | after |
|---|---|---|
| w7: 48M vector FMLA/FMUL/FADD | 3.72 s | **0.08 s (46x)** |
| w8: scalar FP mix | 0.71 s | 0.31 s (2.3x) |
| w9: 2M ASID switches, KPTI-style | 7.55 s | **0.98 s (7.7x)** (2.13 s with TLBs only, `x-asid-jc=off`) |
| w1 / w2 / w4 (controls) | 2.19 / 3.96 / 2.88 s | 2.15 / 4.00 / 2.90 s |

**Linux guest** (upstream QEMU 10.2.2 with the changes ported, `-M virt`, macOS host, median
of 3; native times from the same binaries on the emulator): `mem` (double triad) 6.26 → 1.70 s
(3.7x, native 0.22 s), `simd` 7.06 → 3.37 s (2.1x, native 0.32 s); with KASLR (KPTI), `sys`
(1.6M `getppid`) **37.4 → 6.1 s (6.1x)**, 23 → 3.8 µs per system call (`nokaslr` 4.9 s),
`pf` 5.90 → 4.40 s; `int`, `chase`, CoreMark unchanged or slightly faster.

**Restore boot** (`restore_bench.py`, `-smp 4`, `wfi` SEP placeholder, 10 interleaved runs per
variant, CI Android binaries):

| | `waiting_host` | host CPU (user + sys) |
|---|---|---|
| Inferno a7967b7 (native FP on, before ASID contexts and #37) | 13.82 s | 11.96 s |
| + #37, `asid-tlbs=0` | 13.74 s | 12.03 s |
| + ASID TLB contexts, `x-asid-jc=off` | 13.83 s | 12.03 s |
| + jump caches (= 2a65516 default) | 13.68 s | 11.96 s |
| 2a65516 as bumped here (this PR's CI APK), two batches of 10 | 13.81 s / 14.05 s | 11.84 s / 12.29 s |

No change, as expected: the restore boot runs little FP code, and XNU switches ASID only
about 46 times a second there (553 switches in a 10 s host boot) and maps its kernel global,
so its switches were already cheap. Native FP on: 21/21 boots identical milestones (#38).
ASID contexts on: 80 boots, all with identical milestones except one that hit the known
`Kernel data abort` flake at pc `…99126d4` (above); the same flake hit 2 of 65 boots with the
contexts off or on unmodified master, so its rate is unchanged.

## Softmmu memory path (2026-10-22)

Inferno 2a65516 → 52f62f4, both changes merged with CI green and boot-validated. Docs:
Inferno `docs/SOFTMMU.md`.

| PR | Change |
|---|---|
| [Inferno#39](https://github.com/dylanneve1/pyre/pull/39) | The inline TLB lookup before every guest load/store is 8 host instructions (+1 if unaligned) instead of 10: the `sub` that formed the `CPUTLBDescFast` address folds into the `ldp`, and with the addend moved next to the comparators one `ldp` loads comparator and addend. Generated code per TB in the boot: 248 → 233 bytes. Adds microbenchmarks w10 (TLB-hit loads/stores) and w11 (refills) |
| [Inferno#40](https://github.com/dylanneve1/pyre/pull/40) | The softmmu TLB no longer shrinks below 1024 entries (was 64; XNU's frequent flushes kept it at 64–256): page-table walks in a host boot 1.2–1.4M → 0.5M, victim-TLB hits 2–5.7M → 0.4–1M. `tlb_reset_dirty` (run whenever a page gains translated code) now scans only TLBs filled since their last flush and skips empty entries without reading `CPUTLBEntryFull`; without that, a larger TLB made the boot slower. `-accel tcg,x-tlb-bits=N` sets the floor (6 = old) |

**Restore boot** (`restore_bench.py`, `-smp 4`, `wfi` SEP placeholder, CI Android binaries,
interleaved):

| | `waiting_host` | host CPU (user + sys) |
|---|---|---|
| Inferno 2a65516, 12 runs | 13.84 s [13.64–16.84] | 12.04 s |
| **52f62f4 (#39 + #40), 12 runs** | **13.11 s [12.82–16.25]** | **11.15 s** |
| #39 alone vs 2a65516, 10 + 10 runs | 13.67 vs 13.88 s | 11.71 vs 12.16 s |
| #40 alone vs 2a65516, 10 + 10 runs (floor 64 / 1024 / 4096) | 13.81 / 13.39 / 13.47 vs 13.91 s | 11.88 / 11.59 / 11.56 vs 12.15 s |

`BSD root` 3.89 → 3.69 s. Identical milestones in every run of these batches; one
`Kernel data abort … 99126d4` (the IOMFB flake, fixed separately in Inferno#41) in the
4096-entry set only.

**Microbenchmarks** (a13-test, emulator, median of 5): w10 TLB-hit loads/stores 0.42 → 0.38 s;
w11 loads over 4096 pages 0.97 s → 0.08 s with `x-tlb-bits=12` (fits the TLB), unchanged at
the 1024 default; w1/w4/w9 within noise. Linux guest (upstream 10.2.2 port, 4 KiB pages):
no measurable change.

## Optimisation ideas, ranked

Estimated benefit is relative to the 13.1 s `-smp 4` boot.

1. **The multi-vCPU 3 s stall (up to about 4.6 s, 35%).** With two or more application vCPUs,
   `enable_usb` usually waits 3.05 s, and `launchd` → restored is about 1.8 s slower.
   - *What it is (#51):* restored looks up `AppleUSBDeviceMux` before the kernel has
     published it, then waits for the matching notification and resumes only on its own
     ~2.8 s timeout. With one application vCPU the kernel always publishes first. Traces
     rule out a lost emulated event: IPIs, AIC, timer latency, WFE, MTTCG, TLBI batching
     and IOP mailboxes all behave correctly. It's still open why the notification doesn't
     arrive sooner, so it may be ordinary iOS behaviour; a real-device restore log would
     settle it.
   - **Now: use `-smp 2` for restore boots.** Restore runs on the Mac in the user's flow,
     so this doesn't block the phone path. It isn't yet known whether a normal boot to
     SpringBoard is also faster with fewer vCPUs; measure that before changing defaults.
2. **Indirect-branch TB lookup (≈18% of CPU).** *Done in part: Inferno#19 (hit rate 91% →
   97%), #22 (cheaper hit), #25 (no full clears on TB invalidation); see
   [TCG optimisations](#tcg-optimisations-2026-10-22). Open: the dependent `tb->cs_base` load
   on the hit path, and the jump-cache misses on the phone's UI workload.* `helper_lookup_tb_ptr` is the
   `goto_ptr` path taken on returns and indirect jumps; `qht_lookup_custom` shows it often
   misses the per-CPU jump cache and falls through to the global hash table.
   - *Investigate:* the jump-cache hit rate. Check whether the cache is flushed too often
     (TLB flushes, ASID or PAC-key changes), and whether `arm_get_tb_cpu_state` and
     `curr_cflags` can avoid recomputation on every lookup.
3. **System-register helpers (≈6.5%).** *Done for the hottest ones: Inferno#23 (TPIDR/SPSR/
   ELR/ESR/FAR_EL1 without helpers) and #26 (their writes no longer end the TB).* Guest `mrs`/`msr` go through `lookup_cp_reg`,
   `get_cp_reg64` and `access_check_cp_reg` helpers.
   - *Investigate:* count accesses per encoding in a debug build. Make the hot constant or
     plain-field registers `ARM_CP_CONST` or no-`accessfn` ones, so TCG can inline the
     access. Apple IMPDEF registers in `a13.c`/`a13_gxf.c` are the first candidates.
4. **TLB fill and slow paths (≈8.4%).** `probe_access_internal`, `get_phys_addr_nogpc`,
   `tlb_set_page_full`, `atomic_mmu_lookup`, and `tlb_reset_dirty` (code-page dirty
   tracking).
   - *Try:* a larger dynamic TLB. Also measure the guest TLBI rate and whether code-page
     write protection churns. This was already listed in `docs/ROADMAP.md`.
5. **Translation (≈7%).** This is mostly first-execution cost (liveness pass, code
   generation, icache flush per TB), so a bigger `tb-size` won't help a first boot.
   - *Possible:* fewer TB invalidations (see 4). Translations can't be cached across QEMU
     launches today.
6. **Inferno `helper_guarded_page_check` (≈2.4%).** *Gone: Inferno#20 removed BTI from the A13
   model, so the check is no longer emitted.* It's called often enough to show up.
   - *Check:* whether it can be skipped or inlined when the page or exception level can't
     be guarded.
7. **Host clock reads (≈2.7%).** `clock_gettime` via the vDSO comes from guest
   counter/timer accesses (`gt_counter_access`).
   - *Option:* a cheaper path for guest counter reads.
8. **QEMU start-up (0.7 s, about 5% of wall).** LZFSE kernelcache decode, the kernel patcher
   and DT setup run on every launch.
   - *Option:* cache the decoded and patched kernel between launches, since the app
     relaunches QEMU for every boot.
9. **Kernel time (≈25% of CPU, unprofiled).** Profile on a userdebug or rooted device with
   kernel samples (`cpu-clock` without `:u`) to see page-fault and syscall cost.

## Compiler and linker options (#77)

`native/build-android.sh` has opt-in knobs for how **QEMU's own code** is compiled. The
static deps always use the default flags. With none of the knobs set, the generated cross
files are byte-identical to the old ones, so CI and the shipped APK build exactly as before.

| Env | Effect |
|---|---|
| `QEMU_OPT=-O3` | `-O` level for QEMU (default `-O2`; it overrides meson's release `-O3`, which comes first on the command line) |
| `QEMU_TUNE="..."` | CPU flags for QEMU only (default `TUNE_CFLAGS`) |
| `QEMU_LTO=thin` | ThinLTO, linked by the NDK's lld (`-Db_lto=true -Db_lto_mode=thin`) |
| `QEMU_PGO_GENERATE=<device dir>` | instrumented build in `native/build/qemu-pgo-gen` that writes `.profraw` files to that directory on the device (atomic counters, because vCPU threads run the same code at once); not installed into the app |
| `QEMU_PGO_PROFILE=<file.profdata>` | `-fprofile-use` with a merged profile; `native/build-android.sh pgo-merge OUT RAW...` merges with the NDK's `llvm-profdata` |

A build tree reconfigures itself when any of these (or the tuning) change, so switching
between variants never silently reuses old flags.

### Results (2026-10-22)

All measured on the Mac mini's emulator (8 Apple-silicon host cores) under the shared bench
lock. Variants run interleaved, one run per variant per round, and each "base" is the
default build in the same batch. Restore boot: `tools/restore_bench.py --smp 4` with the
`wfi` SEP placeholder. Microbenchmarks: the firmware-free `a13-test` TCG workloads (Inferno's
`hw/arm/tests/tcg_bench.S`), which stand in for the boot's hot paths; none of them is the
PGO training run.

**Current Inferno (`3f2e61d`, with the TCG fast paths), restore boot, 10 rounds, medians:**

| Variant | `bsd_root` | `waiting_host` | host CPU (user+sys) | Range, `waiting_host` | Range, CPU |
|---|---|---|---|---|---|
| base (`-O2`) | 3.91 s | 13.80 s | 12.15 s | 13.42–15.58 s ¹ | 11.68–14.24 s ¹ |
| `QEMU_LTO=thin` | −1.5% | −1.6% | −3.6% | 13.29–15.85 s | 11.31–14.29 s |
| PGO | −7.8% | **−5.9%** (12.99 s) | **−8.1%** (11.16 s) | 12.82–15.44 s | 10.80–13.82 s |
| PGO + ThinLTO | −10.2% | **−6.3%** (12.93 s) | **−10.0%** (10.93 s) | 12.66–15.09 s | 10.64–13.41 s |
| PGO + ThinLTO, stale profile ² | −7.9% | −3.9% | −6.1% | 12.93–15.86 s | 11.03–14.33 s |

¹ 9 of 10 runs; one base run panicked (see below).
² The profile trained on the older Inferno `180389d`, used on `3f2e61d` unchanged.

Microbenchmarks on `3f2e61d`, 3 reps, median QEMU process time, change against base:

| Workload | base | ThinLTO | PGO | PGO+ThinLTO |
|---|---|---|---|---|
| w1 hot BL/RET (jump-cache hits) | 2.13 s | +0.5% | **−18.8%** | **−19.7%** |
| w2 BLR/RET over 4096 targets | 4.07 s | −3.9% | −6.9% | −6.9% |
| w3 `MRS TPIDR_EL1` | 0.21 s | (too short to time) | | |
| w4 ALU loop (control) | 2.87 s | −0.7% | 0.0% | −1.0% |
| w5 w2 + TB invalidations | 1.95 s | +3.1% | −1.0% | −2.6% |

**Earlier Inferno (`180389d`), restore boot, 8 rounds, medians** (two batches; `-O3` was
only in the first, where base was 20.75 s, 23.05 s CPU):

| Variant | `bsd_root` | `waiting_host` | host CPU | Range, `waiting_host` |
|---|---|---|---|---|
| base (`-O2`) | 4.73 s | 20.80 s | 23.09 s | 20.52–21.21 s |
| `QEMU_OPT=-O3` | −3.3% | −0.6% | +0.7% | 20.50–20.91 s |
| `QEMU_LTO=thin` | −4.2% | −1.4% | −1.7% | 20.29–20.91 s |
| `-O3` + ThinLTO | −1.6% | −1.5% | −0.8% | 19.98–20.87 s |
| PGO | −7.3% | −4.5% | −4.5% | 19.51–20.49 s |
| PGO + ThinLTO | −7.3% | −5.0% | −6.4% | 19.53–20.72 s |

Microbenchmarks on `180389d`, 5 reps:

| Workload | base | `-O3` | ThinLTO | `-O3`+ThinLTO | PGO | PGO+ThinLTO |
|---|---|---|---|---|---|---|
| w1 hot BL/RET | 2.79 s | −5.0% | +0.7% | −4.7% | −0.7% | −3.6% |
| w2 BLR/RET over 4096 targets | 7.67 s | −0.4% | +0.1% | −0.7% | −10.2% | −7.7% |
| w3 `MRS TPIDR_EL1` | 7.67 s | +1.4% | 0.0% | −0.8% | +0.1% | +0.3% |
| w4 ALU loop (control) | 2.82 s | −1.1% | +0.4% | −1.1% | −1.1% | −0.7% |
| w5 w2 + TB invalidations | 3.01 s | −1.3% | −0.7% | −2.3% | −9.0% | −7.6% |

**A guest panic that isn't from these options.** Two of about 90 restore boots on `3f2e61d`
panicked the same way: one default build and one ThinLTO build, the latter while the host was
overloaded (load ≈20). The panic is `Kernel data abort` at `pc 0xfffffff0099126d4`,
`far 0x18`, right after `IOMFB::UPPipe_H11P::rt_bandwidth_setup(): bandwidth values not
found` → `start_hardware failed`. None of the 64 boots on `180389d` did this, so it is worth a
look on its own.

Library sizes, stripped, with gzip -6 size in brackets (`180389d`; `3f2e61d` is within
0.1 MB). The APK stores them compressed (`useLegacyPackaging`), and the app extracts them on
install:

| Variant | `libqemu-system-aarch64.so` | `libqemu-system-aarch64-shared.so` |
|---|---|---|
| base | 12.32 MB (3.57) | 12.51 MB (3.58) |
| `-O3` | 12.64 MB (3.68) | |
| ThinLTO | 10.92 MB (3.23), −11% | 11.29 MB (3.27), −10% |
| `-O3` + ThinLTO | 11.22 MB | |
| PGO | 11.40 MB (3.42) | |
| PGO + ThinLTO | 10.18 MB (3.14), −17% | |

ThinLTO on both libraries would take about 0.65 MB off the APK (≈6.0 MB) and 2.6 MB off the
installed size.

Build time (4-core Linux VPS, both QEMU variants): 2:26 base, 2:29 ThinLTO.

**Conclusions.**

- **`-O3` doesn't help the boot.** It is within noise on the restore boot and 2.6% bigger,
  and of the microbenchmarks it only speeds up w1 (5%). It wasn't carried forward.
- **ThinLTO is a small win at most**: 1.4–1.6% wall time and 1.7–3.6% CPU, ranges overlapping
  base. It does save 11% in size.
- **PGO is the clear speed win**: on current Inferno, 5.9% off the restore boot and 8.1% off
  its host CPU, or 6.3% and 10.0% with ThinLTO. It also cuts the indirect-branch microbenchmarks
  by 7–20%. Caveats:
  - The profile was trained on this same restore boot.
  - The profile is tied to an Inferno revision. A profile one bump old still gave 3.9% and
    6.1%, but it decays with each bump.
  - So it stays opt-in. A default would need a profile regenerated on every submodule bump
    (a CI job could do it, given a device runner and kit) and training on a normal boot too.
- Nothing is on by default.

**Tuning for Tensor G5 (untested on target).** The emulator's host is Apple silicon. Its
guest CPU does have the Armv8.2 + crypto/dotprod/rcpc extensions, so the default
`-mtune=cortex-x4` binary runs there, but tuning effects measured on it say nothing about
X4/A725/A520 cores. On a phone, try e.g.
`QEMU_TUNE="-march=armv8.2-a+crypto+dotprod+rcpc -mtune=cortex-a720"` (NDK r28's clang 19
has no `cortex-a725`; the A720 is its predecessor) against the default. Keep the Armv8.2
`-march`: `-mcpu=cortex-x4` would allow Armv9 / SVE2 code. CI's `baseline-build` sets
`TUNE_CFLAGS=-march=armv8-a`, which `QEMU_TUNE` defaults to, so the x86-hosted emulator still
gets a plain Armv8.0 binary.

**Making a PGO build.**

```sh
# instrumented QEMU, a few restore boots on a device, merged profile:
native/pgo-profile.sh restore.profdata --serial <adb serial> --kernel ... --dtb ... \
  --trustcache ... --ramdisk ... --sep-rom ... --sep-fw ...
QEMU_PGO_PROFILE=restore.profdata QEMU_LTO=thin native/build-android.sh qemu
```

The `.profraw` is written when QEMU exits normally (`restore_bench.py` stops it with
SIGINT; SIGKILL loses it). Each profile above came from 3 `-smp 4` restore boots of the
instrumented build on the emulator (27–31 s each on `180389d` versus 21 s uninstrumented, 17 s on `3f2e61d`). The steps were run by hand:
build on Linux, boot on the Mac's emulator, merge back on Linux. The profile holds only
QEMU's branch and call counts, keyed by QEMU function names, so it's no Apple data. Still,
don't commit one: it goes stale with every Inferno bump.

## Idle host CPU

Issue #63: host CPU while the guest idles. Measured with `tools/idle_bench.py`, which
boots restore mode like `restore_bench.py`, then reads each QEMU thread's utime + stime
from `/proc/<pid>/task/*/stat` at `waiting_host` and again 30 s later. Android emulator,
Inferno `master` `a8ab2853c9`, `-smp 4`, release build, `-name debug-threads=on` for the
thread names.

| SEP inputs | runs | host cores while idle (30 s at `waiting_host`) | SEP vCPU |
|---|---|---|---|
| real (`AppleSEPROM-Cebu-B1` + the 14.0 SEP firmware) | 2 | **0.045, 0.053** | ≈0 |
| `b .` placeholder ROM (`restore_bench` kit) | 1 | **≈1.26** | about one core (spinning) |

Per thread with real SEP inputs (run 1): CPU 0 2.1%, main loop 1.4%, CPU 1 1.0%,
CPU 2 0.0%; the SEP vCPU (CPU 3) and the other threads didn't register. The `b .` row
  is from an earlier script version that paired threads by name, so only its total is
  reliable; it matches the 1.28 cores above. A macOS run with real inputs agrees: in a
  10 s `sample` at `waiting_host`, only CPU 0 was in `cpu_exec`, for about 1% of samples.

- **The "spinning SEP" is the placeholder ROM, not the emulated hardware.** With the real
  SEPROM the SEP vCPU sits in WFI and its host thread sleeps. In restore mode at
  `waiting_host` the SEP is still in the SEPROM: restore sends the SEP firmware later.
- **The `wfi` placeholder spins for a guest reason.** At idle the SEP's inbox holds the AP's
  unread messages and `IOP_NONEMPTY` isn't masked (`apple_a7iop_mailbox_update_irq`
  trace, macOS build), so an interrupt really is
  pending. WFI wakes for a pending IRQ whatever `PSTATE.I` says, so it returns at once. A
  real SEP with an unserviced, unmasked mailbox interrupt would do the same. This isn't
  worth an emulator change; benchmarks should use real SEP inputs when they have them.
- **The application vCPUs sleep.** In XNU's idle path (`arm64_retention_wfi`) the PC sits
  after the `wfi`, and the host threads are in `qemu_wait_io_event`. `CYC_OVRD` and
  `ACC_OVRD` are plain storage registers, which is fine: the WFI that follows is what sleeps.
- **The guest's interrupt rate is sane.** With a log-trace build on macOS (same model), at
  `waiting_host` over 20 s: about 62 virtual-timer interrupts/s, 60 IRQs/s from `disp0`
  (AIC 317, the panel's vsync at `ADP_V4_PANEL_HZ`, with `OUTPUT_READY` enabled by the
  guest), and about 500 `CNTV_TVAL` writes/s (XNU reprogramming its deadline on idle
  entry and exit). SMC sensor reads come at 0.5 Hz.
- **On the real Pixel at the home screen the SEP thread used about 4%**, in line with this.

Not done yet:
- A `restore_bench.py` median with real SEP inputs (the emulator lock was busy; no code
  change was made, so there is nothing to regress).
- Idle per-thread numbers after a full boot on the phone (only the ~4% SEP figure so far).
- WFE stays a yield, as upstream. XNU uses WFE only in contended spin-lock loops (713 sites in
  14.0, none in the idle path). Sleeping on WFE would need wake-ups from exclusive-monitor
  clears, which TCG can't see cheaply.
- Possible small win: `adp_v4_vsync_tick` runs at 60 Hz even when the guest hasn't enabled
  `OUTPUT_READY`. Stopping the timer while it's masked saves host wake-ups, but only after
  the display driver masks it, which it didn't do here.

```sh
tools/idle_bench.py --serial <adb serial> --runs 3 --smp 4 --idle 30 \
  --qemu <qemu-system-aarch64> --kernel <kernelcache> --dtb <DeviceTree.im4p> \
  --trustcache <restore .trustcache> --ramdisk <restore ramdisk .dmg> \
  --sep-rom <SEP ROM> --sep-fw <SEP firmware>
```

## Notes and caveats

- **SEP placeholders.** The emulator has no real SEP ROM or firmware.
  - A `b .` ROM spins the SEP vCPU in guest code. A `wfi` loop spins too: WFI completes at
    once while the SEP's mailbox interrupt is pending, even when it's masked, so QEMU keeps
    re-entering `helper_wfi` and taking the BQL.
  - The benchmark uses the `wfi` ROM, which uses less CPU (0.95 vs 1.28 cores) and gives
    similar timings: 13.1 s vs 13.7 s with the `b .` ROM.
  - The profile uses the `b .` ROM with the SEP thread excluded. Under `simpleperf`, the
    `wfi` ROM's lock traffic slowed the profiled run to 38.6 s, so it isn't representative.
  - Since Inferno#28 (`3f2e61d`), t8030 recognises a SEP ROM that can only idle (both
    of these placeholders) and keeps the SEP core powered off, so neither spins any more;
    QEMU prints ``SEP ROM `…` only idles (WFI/B loop); keeping the SEP core powered off``.
  - **With real SEP inputs the SEP vCPU doesn't spin** (#63, [Idle host CPU](#idle-host-cpu)).
    The spin above comes from the placeholder ROMs, not from the emulated SEP hardware.
- `restore_bench.py` takes only your own kit files and never downloads anything. Each run
  is stopped through QEMU's own `-pidfile`, never by name or pattern: `pkill -f` matched
  the adb shell itself during development, and a name-based kill stops other people's runs
  of the same tool. Each run picks a free USB uplink port (18030–18099) unless you pass
  `--uplink`, so several runs can share one device (their timings still affect each other).
- The adb shell can't create FIFOs in `/data/local/tmp` (SELinux), so serial is read
  through a pipe.

## Reproduce (and run on a phone)

```sh
tools/restore_bench.py --serial <adb serial> --runs 5 \
  --qemu <unstripped qemu-system-aarch64> --kernel <kernelcache> --dtb <DeviceTree.im4p> \
  --trustcache <restore ramdisk .trustcache> --ramdisk <restore ramdisk .dmg> \
  --sep-rom <SEP ROM> --sep-fw <SEP firmware> \
  [--smp 2] [--profile <ndk>/simpleperf/bin/android/arm64/simpleperf [--profile-event cpu-cycles:u]] \
  [--keep-logs DIR] [--json out.json]
```

On a phone with a usable PMU, `--profile-event cpu-cycles:u` gives cycle-accurate samples.
The emulator only offers software clocks.
