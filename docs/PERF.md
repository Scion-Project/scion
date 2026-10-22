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
- **Compiler options (#77, below):** PGO (profile from the restore boot) takes 4.5% off the
  boot's wall time and host CPU, and 6.4% CPU with ThinLTO. `-O3` and ThinLTO alone are
  within noise for speed; ThinLTO makes the libraries 11% smaller. All are opt-in
  (`QEMU_PGO_PROFILE`, `QEMU_LTO`, `QEMU_OPT`, `QEMU_TUNE`); the default build is unchanged.

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
| [Inferno#22](https://github.com/dylanneve1/Inferno/pull/22) | Jump-cache hit in `lookup_and_goto_ptr` with the TB state computed inline: no indirect call, no stack frame, one compare branch. About 115 → 69 host instructions per hit |
| [Inferno#23](https://github.com/dylanneve1/Inferno/pull/23) | The GXF-banked TPIDR/SPSR/ELR/ESR/FAR_EL1 become plain field loads/stores, with the GL1 copy chosen at translation time from the TB's MMU index. Before, every access called two helpers (`lookup_cp_reg` + `get/set_cp_reg64`). Adds the `gxf_banked_el1` a13-test to CI |
| [Inferno#25](https://github.com/dylanneve1/Inferno/pull/25) | A PCREL TB invalidation clears the TB's 64 possible jump-cache slots instead of every vCPU's whole 16k-entry cache |
| [Inferno#26](https://github.com/dylanneve1/Inferno/pull/26) | Writes to those storage-only registers, and to their `_GL11` views, no longer end the TB. They caused about ⅔ of the 496k sysreg-write TB exits per boot |
| [Inferno#27](https://github.com/dylanneve1/Inferno/pull/27) | `hw/arm/tests/run-a13-bench.py` + `tcg_bench.S`: the microbenchmarks below, bare metal on the `a13-test` machine, no Apple code |
| [Inferno#28](https://github.com/dylanneve1/Inferno/pull/28) | t8030 keeps the SEP core powered off when its ROM can only idle (a loop of only WFI/WFE/NOP/YIELD/`B`, like the `wfi` and `b .` placeholders), so placeholder SEP inputs no longer spin a host core. Real SEPROMs are never matched. See [below](#placeholder-sep-roms-inferno28) |

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

### Results (Oct 2026, Inferno `180389d`)

Measured on the emulator (8 Apple-silicon host cores) with `tools/restore_bench.py --smp 4`,
one run per variant per round, interleaved, 8 rounds, under the shared bench lock. Each
"base" is the default build in the same batch. Medians:

| Variant | `bsd_root` | `waiting_host` | host CPU (user+sys) | Range, `waiting_host` |
|---|---|---|---|---|
| base (`-O2`) | 4.73 s | 20.80 s | 23.09 s | 20.52–21.21 s |
| `QEMU_OPT=-O3` ¹ | −3.3% | −0.6% | +0.7% | 20.50–20.91 s |
| `QEMU_LTO=thin` | −4.2% | −1.4% | −1.7% | 20.29–20.91 s |
| `-O3` + ThinLTO ¹ | −1.6% | −1.5% | −0.8% | 19.98–20.87 s |
| PGO | −7.3% | **−4.5%** (19.85 s) | **−4.5%** (22.06 s) | 19.51–20.49 s |
| PGO + ThinLTO | −7.3% | **−5.0%** (19.75 s) | **−6.4%** (21.61 s) | 19.53–20.72 s |

¹ From the first batch (base 20.75 s, 23.05 s CPU); the rest are from the second.

The firmware-free `a13-test` TCG microbenchmarks (Inferno's `hw/arm/tests/tcg_bench.S`:
proxies for the boot's hot paths; none of them was the PGO training run). Same emulator,
5 interleaved reps, median QEMU process wall time, change against base:

| Workload | base | `-O3` | ThinLTO | `-O3`+ThinLTO | PGO | PGO+ThinLTO |
|---|---|---|---|---|---|---|
| w1 hot BL/RET (jump-cache hits) | 2.79 s | −5.0% | +0.7% | −4.7% | −0.7% | −3.6% |
| w2 BLR/RET over 4096 targets | 7.67 s | −0.4% | +0.1% | −0.7% | **−10.2%** | **−7.7%** |
| w3 `MRS TPIDR_EL1` | 7.67 s | +1.4% | 0.0% | −0.8% | +0.1% | +0.3% |
| w4 ALU loop (control) | 2.82 s | −1.1% | +0.4% | −1.1% | −1.1% | −0.7% |
| w5 w2 + TB invalidations | 3.01 s | −1.3% | −0.7% | −2.3% | **−9.0%** | **−7.6%** |

Library sizes, stripped, with gzip -6 size in brackets. The APK stores them compressed
(`useLegacyPackaging`), and the app extracts them on install:

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
  and of the microbenchmarks it only speeds up w1 (5%).
- **ThinLTO is neutral to slightly positive for speed (≤2%, within noise) and saves 11% in
  size.** All 64 restore boots in these batches (16 with ThinLTO alone, 8 more with PGO + ThinLTO) reached `waiting_host` with no panic.
- **PGO is the only clear speed win**: 4.5% off the restore boot in wall time and CPU, and
  6.4% CPU with ThinLTO. The ranges barely overlap with base. It also takes 8–10% off the
  indirect-branch and TB-invalidation microbenchmarks it was not trained on. But the profile was
  trained on this same restore boot, and it is tied to an Inferno revision (functions that
  changed lose their profile), so it stays opt-in. A default would need a profile kept in step
  with the submodule, and training on a normal boot too.
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
SIGINT; SIGKILL loses it). The profile above came from 3 `-smp 4` restore boots of the
instrumented build on the emulator (27–31 s each, versus 21 s). The steps were run by hand:
build on Linux, boot on the Mac's emulator, merge back on Linux. The profile holds only
QEMU's branch and call counts, keyed by QEMU function names, so it's no Apple data. Still,
don't commit one: it goes stale with every Inferno bump.

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
  - **On the phone, with real SEP inputs, check the SEP thread's CPU use.** A vCPU that
    keeps waking from WFI costs a whole host core (battery, thermals).
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
