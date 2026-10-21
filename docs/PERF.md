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
2. **Indirect-branch TB lookup (≈18% of CPU).** `helper_lookup_tb_ptr` is the
   `goto_ptr` path taken on returns and indirect jumps; `qht_lookup_custom` shows it often
   misses the per-CPU jump cache and falls through to the global hash table.
   - *Investigate:* the jump-cache hit rate. Check whether the cache is flushed too often
     (TLB flushes, ASID or PAC-key changes), and whether `arm_get_tb_cpu_state` and
     `curr_cflags` can avoid recomputation on every lookup.
3. **System-register helpers (≈6.5%).** Guest `mrs`/`msr` go through `lookup_cp_reg`,
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
6. **Inferno `helper_guarded_page_check` (≈2.4%).** It's called often enough to show up.
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

## Notes and caveats

- **SEP placeholders.** The emulator has no real SEP ROM or firmware.
  - A `b .` ROM spins the SEP vCPU in guest code. A `wfi` loop spins too: WFI completes at
    once while the SEP's mailbox interrupt is pending, even when it's masked, so QEMU keeps
    re-entering `helper_wfi` and taking the BQL.
  - The benchmark uses the `wfi` ROM, which uses less CPU (0.95 vs 1.28 cores) and gives
    similar timings: 13.1 s vs 13.7 s with the `b .` ROM.
  - The profile uses the `b .` ROM with the SEP thread excluded. Under `simpleperf`, the
    `wfi` ROM's lock traffic slowed the profiled run to 38.6 s, so it isn't representative.
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
