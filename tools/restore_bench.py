#!/usr/bin/env python3
"""Benchmark the iOS restore boot on an Android device, and optionally profile it.

Boots the t8030 machine into the iOS 14 restore environment N times and reports
the wall time to each milestone, from serial-log lines time-stamped on the
device as they arrive:

  first_serial   first serial output (QEMU start-up and iBoot hand-off)
  bsd_root       "BSD root: md0" (kernel and IOKit up, root mount starts)
  launchd        launchd says hello (userland)
  enable_usb     restored_external finishes enable_usb
  waiting_host   "waiting for host to trigger start of restore" (end of the run)

Each run uses the app's QEMU command line (QemuBackend.kt) with -snapshot, so no
disk image is modified. The run stops at waiting_host. All inputs are your own
files; this script never downloads anything.

  restore_bench.py --serial emulator-5554 --runs 5 \\
      --qemu build/qemu-system-aarch64 --kernel kernelcache.research.iphone12b \\
      --dtb DeviceTree.n104ap.im4p --trustcache 018-xxxxx.dmg.trustcache \\
      --ramdisk 018-xxxxx.dmg --sep-rom AppleSEPROM-Cebu-B1 --sep-fw sep-firmware.img4

  ... --profile ~/Library/Android/sdk/ndk/<ver>/simpleperf/bin/android/arm64/simpleperf
      adds one run under simpleperf and prints where the host CPU time went.

The QEMU binary needs symbols (not stripped) for a useful profile.

Serial goes through a pipe on the device, and a shell loop stamps each line with
/proc/uptime as it arrives (10 ms resolution, no process spawned per line), so the
times are wall clock from QEMU launch. Results and method: docs/PERF.md. Note
that -smp 2 boots restore mode much faster than -smp 3+ (a fixed 3 s stall
appears with more than one application vCPU).
"""
import argparse
import hashlib
import json
import os
import re
import shlex
import statistics
import subprocess
import sys
import time
from pathlib import Path

MILESTONES = [
    ("first_serial", None),
    ("bsd_root", re.compile(r"BSD root: md0")),
    ("launchd", re.compile(r"launchd\[1\].*<Notice>: hello")),
    ("enable_usb", re.compile(r"CHECKPOINT END: MAIN:\[0x040E\] enable_usb")),
    ("waiting_host", re.compile(r"waiting for host to trigger start of restore")),
]
PANIC = re.compile(r"panic\(cpu \d+ caller [^)]*\): (.*)")

# name -> (size, nvme nsid, nvme nstype, device), matching QemuBackend.kt / package_vm.py
NVME = [("root", 1 << 30, 1, 1, "nvme-ns"), ("firmware", 8 << 20, 2, 2, "nvme-ns"),
        ("syscfg", 128 << 10, 3, 3, "nvme-ns"), ("ctrl_bits", 8 << 10, 4, 4, "nvme-ns"),
        ("nvram", 8 << 10, 5, 5, "apple-nvram"), ("effaceable", 4 << 10, 6, 6, "nvme-ns"),
        ("panic_log", 1 << 20, 7, 8, "nvme-ns")]
PFLASH = [("sep_nvram", 64 << 10), ("sep_ssc", 128 << 10)]
# The run is stopped through QEMU's own -pidfile, never by name or pattern: pkill -f can
# match the adb shell itself, and other people may run this tool on the same device.
QEMU_NAME = "qemu-restore-bench"
UPLINK_PORTS = range(18030, 18100)
APPEND = ("tlto_us=-1 mtxspin=-1 agm-genuine=1 agm-authentic=1 agm-trusted=1 "
          "serial=3 wdt=-1 -vm_compressor_wk_sw")

# Profile buckets for host symbols, checked in order. Guest code (TCG's code
# buffer, reported as dso "unknown") is counted separately from the dso report.
BUCKETS = [
    ("guest code (TCG-generated)", None),
    ("start-up: image decode/load", re.compile(r"(lzfse|lzss|decompress|extract_im4p|asn1_|apple_boot_|"
                                               r"apple_dt_|macho|ck_patcher|init_arguments)")),
    ("TB lookup / chaining", re.compile(r"(lookup_tb_ptr|tb_htable_lookup|\btb_lookup|\bcpu_exec|\bqht_|"
                                        r"tb_add_jump|cpu_tb_exec|\btb_link|tb_jmp_cache|get_tb_cpu_state|"
                                        r"curr_cflags|rcu_reader)")),
    ("TCG translation (incl. icache flush)", re.compile(r"(\btcg_|\bdisas_|\bgen_|\btranslator_|aarch64_tr_|"
                                                       r"\barm_tr_|tb_gen_code|liveness_pass|reachable_code_pass|"
                                                       r"\bla_|\btemp_|flush_idcache|tb_invalidate|page_collection)")),
    ("system-register helpers", re.compile(r"(cp_reg|\barm_cp_|_cpreg|raw_read|raw_write|_el[0-3]_(read|write)|"
                                           r"gt_counter_access|gt_cnt|gt_.*_read)")),
    ("softmmu: TLB fill / page walk", re.compile(r"(tlb_fill|get_phys_addr|tlb_set_page|probe_access|"
                                                 r"_ptw|\bS1_|\bregime_|tlb_flush|tlb_reset_dirty|cputlb|victim_tlb|get_S1prot|"
                                                 r"aa64_va_parameters|tlb_vaddr_to_host)")),
    ("softmmu: slow-path loads/stores", re.compile(r"(helper_(le|be)_(ld|st)|helper_ld|helper_st|\bdo_ld|"
                                                   r"\bdo_st|mmu_lookup|\bcpu_ld|\bcpu_st|load_helper|"
                                                   r"store_helper|full_(le|be)_)")),
    ("Inferno/Apple checks (GXF, guarded pages)", re.compile(r"(guarded|gxf|\ba13_|apple_a13)")),
    ("MMIO / device models", re.compile(r"(memory_region_dispatch|io_readx|io_writex|memory_region_read|"
                                        r"memory_region_write|\bflatview_|address_space_|\bapple_|\baic_|"
                                        r"\bnvme_|\bdart_|a7iop|rtkit|\bsep_|_mmio_|_reg_read|_reg_write)")),
    ("timers / clock", re.compile(r"(clock_gettime|qemu_clock|\btimer_|cpu_get_clock|icount|get_clock)")),
    ("other TCG helpers (guest ops)", re.compile(r"\bhelper_")),
    ("locks / syscalls / libc", re.compile(r"(futex|pthread_|qemu_mutex|qemu_cond|qemu_event|ppoll|epoll|"
                                           r"syscall|libc\.so|linker64)")),
]


class Device:
    def __init__(self, serial):
        self.serial = serial

    def adb(self, *args, check=True, capture=True, timeout=None):
        cmd = ["adb", "-s", self.serial, *args]
        r = subprocess.run(cmd, capture_output=capture, text=True, timeout=timeout)
        if check and r.returncode != 0:
            sys.exit(f"adb {' '.join(args[:2])} failed: {(r.stderr or r.stdout).strip()}")
        return r

    def sh(self, script, check=True, timeout=None):
        return self.adb("shell", script, check=check, timeout=timeout)

    def push_if_changed(self, local, remote):
        """Push unless the device already has an identical file (MD5, not just size)."""
        size = Path(local).stat().st_size
        local_md5 = hashlib.md5(Path(local).read_bytes()).hexdigest()
        r = self.sh(f"md5sum {shlex.quote(remote)} 2>/dev/null", check=False)
        if r.stdout.split()[:1] != [local_md5]:
            print(f"  push {Path(local).name} ({size / 2**20:.1f} MiB)")
            self.adb("push", "-q", str(local), remote, timeout=1800)


def qemu_command(a, d, uplink, extra=()):
    machine = ",".join([
        "t8030", "trustcache=trustcache", "sep-fw=sep_fw", "sep-rom=sep_rom", "kaslr-off=true",
        f"usb-uplink-addr={uplink}"])
    args = [f"{d}/{QEMU_NAME}", "-M", machine, "-accel", f"tcg,thread=multi,tb-size={a.tb_size}",
            "-kernel", "kernel", "-dtb", "dtb", "-initrd", "ramdisk", "-append", APPEND,
            "-smp", str(a.smp), "-m", a.ram, "-display", "none", "-monitor", "none",
            "-serial", "stdio", "-snapshot", "-pidfile", f"{d}/qemu.pid",
            "-drive", "file=sep_nvram,if=pflash,format=raw,file.locking=off",
            "-drive", "file=sep_ssc,if=pflash,format=raw,file.locking=off", *extra]
    for name, _, nsid, nstype, dev in NVME:
        args += ["-drive", f"file={name},format=raw,if=none,id={name},file.locking=off",
                 "-device", f"{dev},drive={name},bus=nvme-bus.0,nsid={nsid},nstype={nstype},id={name},"
                            "logical_block_size=4096,physical_block_size=4096"]
    return args


def run_script(a, d, uplink, wrap="", extra=()):
    """Device-side script: start QEMU, stamp each serial line with /proc/uptime on
    arrival, stop QEMU once restore mode is waiting for a host (or on timeout).
    Uses a pipe: the adb shell user may not create FIFOs under /data/local/tmp."""
    cmd = " ".join(shlex.quote(x) for x in qemu_command(a, d, uplink, extra))
    return f"""
cd {d} || exit 1
rm -f serial.stamped qemu.err qemu.pid
read t0 _ < /proc/uptime; echo "$t0 @start" > serial.stamped
timeout {a.timeout} {wrap} {cmd} 2> qemu.err < /dev/null | while IFS= read -r line; do
  read up _ < /proc/uptime; echo "$up $line" >> serial.stamped
  case "$line" in *"waiting for host to trigger start of restore"*)
    kill -INT $(cat {d}/qemu.pid 2>/dev/null) 2>/dev/null ;; esac
done
read t1 _ < /proc/uptime; echo "@wall $t0 $t1"; times
"""


def parse_cpu(out):
    """CPU seconds (user, sys) of the run's child processes, and wall seconds."""
    wall = None
    m = re.search(r"@wall ([\d.]+) ([\d.]+)", out)
    if m:
        wall = float(m.group(2)) - float(m.group(1))
    tl = re.findall(r"(\d+)m([\d.]+)s\s+(\d+)m([\d.]+)s", out)
    if len(tl) < 2:
        return None
    u = int(tl[1][0]) * 60 + float(tl[1][1])
    sy = int(tl[1][2]) * 60 + float(tl[1][3])
    return {"user": u, "sys": sy, "wall": wall}


def parse(stamped):
    t0, res, panic, lines = None, {}, None, 0
    for raw in stamped.splitlines():
        ts, _, line = raw.partition(" ")
        try:
            t = float(ts)
        except ValueError:
            continue
        if line == "@start":
            t0 = t
            continue
        lines += 1
        if "first_serial" not in res:
            res["first_serial"] = t - t0
        for name, pat in MILESTONES[1:]:
            if name not in res and pat.search(line):
                res[name] = t - t0
        m = PANIC.search(line)
        if m and panic is None:
            panic = m.group(1).strip()
    return res, panic, lines


def other_qemus(dev, d):
    """Other t8030 instances on the device (they compete for CPU and skew timings).
    Called between runs, when none of ours is running, so every match is someone else's."""
    out = dev.sh("ps -A -o PID,ARGS 2>/dev/null | grep -E 'qemu.*t8030' | grep -v grep", check=False).stdout
    return [l.strip() for l in out.splitlines() if l.strip()]


def pick_uplink(a, dev):
    """The USB uplink address for the next run: --uplink, or the first free port in UPLINK_PORTS."""
    if a.uplink != "auto":
        return a.uplink
    out = dev.sh("ss -ltn 2>/dev/null || netstat -ltn 2>/dev/null", check=False).stdout
    used = {int(p) for p in re.findall(r"127\.0\.0\.1:(\d+)\s", out)}
    port = next((p for p in UPLINK_PORTS if p not in used), None)
    if port is None:
        sys.exit(f"no free USB uplink port in {UPLINK_PORTS.start}-{UPLINK_PORTS.stop - 1}; pass --uplink")
    return f"127.0.0.1:{port}"


def setup(a, dev):
    d = a.device_dir
    dev.sh(f"mkdir -p {d}")
    print(f"kit -> {a.serial}:{d}")
    for local, remote in [(a.qemu, QEMU_NAME), (a.kernel, "kernel"), (a.dtb, "dtb"),
                          (a.trustcache, "trustcache"), (a.ramdisk, "ramdisk"),
                          (a.sep_rom, "sep_rom"), (a.sep_fw, "sep_fw")]:
        if not Path(local).is_file():
            sys.exit(f"missing input: {local}")
        dev.push_if_changed(local, f"{d}/{remote}")
    dev.sh(f"chmod 755 {d}/{QEMU_NAME}")
    blanks = [(n, s) for n, s, *_ in NVME] + PFLASH
    dev.sh("; ".join(f"[ -f {d}/{n} ] || truncate -s {s} {d}/{n}" for n, s in blanks))


def bench(a, dev):
    results = []
    for i in range(1, a.runs + 1):
        others = other_qemus(dev, a.device_dir)
        uplink = pick_uplink(a, dev)
        r = dev.sh(run_script(a, a.device_dir, uplink), check=False, timeout=a.timeout + 60)
        cpu = parse_cpu(r.stdout)
        stamped = dev.sh(f"cat {a.device_dir}/serial.stamped", check=False).stdout
        res, panic, lines = parse(stamped)
        if a.keep_logs:
            Path(a.keep_logs).mkdir(parents=True, exist_ok=True)
            Path(a.keep_logs, f"run{i}.serial.txt").write_text(stamped)
        results.append({"run": i, "milestones": res, "panic": panic, "serial_lines": lines,
                         "concurrent_qemu": len(others), "cpu": cpu, "uplink": uplink})
        ms = "  ".join(f"{k}={res[k]:.1f}s" for k, _ in MILESTONES if k in res)
        note = f"  PANIC: {panic}" if panic else ("" if "waiting_host" in res else "  (did not reach waiting_host)")
        busy = f"  [{len(others)} other QEMU running]" if others else ""
        load = (f"  cpu user {cpu['user']:.1f}s sys {cpu['sys']:.1f}s"
                f" = {(cpu['user'] + cpu['sys']) / cpu['wall']:.2f} cores avg") if cpu and cpu["wall"] else ""
        print(f"run {i}/{a.runs} [uplink {uplink.rsplit(':', 1)[-1]}]: {ms}{note}{busy}{load}")
    return results


def summarize(results):
    print("\nmilestone        runs   min     median  max     (seconds since QEMU start)")
    summary = {}
    for name, _ in MILESTONES:
        v = [r["milestones"][name] for r in results if name in r["milestones"]]
        if not v:
            continue
        summary[name] = {"n": len(v), "min": min(v), "median": statistics.median(v), "max": max(v)}
        s = summary[name]
        print(f"{name:16} {s['n']:4}   {s['min']:6.1f}  {s['median']:6.1f}  {s['max']:6.1f}")
    return summary


def profile(a, dev):
    d = a.device_dir
    dev.push_if_changed(a.profile, f"{d}/simpleperf")
    dev.sh(f"chmod 755 {d}/simpleperf; rm -f {d}/perf.data")
    # cpu-clock needs no hardware PMU (emulators). ":u" = user space only: on user
    # builds SELinux denies the shell perf_event { kernel }, so kernel time is not
    # sampled (see the per-run "cpu ... sys" figure for it).
    wrap = f"{d}/simpleperf record -e {a.profile_event} -f {a.profile_freq} -o {d}/perf.data"
    print(f"\nprofile run (simpleperf, {a.profile_event} @ {a.profile_freq} Hz) ...")
    # debug-threads names the vCPU and I/O threads ("CPU 0/TCG", ...) for the per-thread report.
    dev.sh(run_script(a, d, pick_uplink(a, dev), wrap=wrap, extra=("-name", "restore-bench,debug-threads=on")),
           check=False, timeout=a.timeout + 120)
    stamped = dev.sh(f"cat {d}/serial.stamped", check=False).stdout
    res, panic, _ = parse(stamped)
    print("  milestones: " + "  ".join(f"{k}={v:.1f}s" for k, v in res.items()))
    def report(name, sort, extra=""):
        out = dev.sh(f"cd {d} && ./simpleperf report -i perf.data --sort {sort} {extra} 2>/dev/null",
                     check=False, timeout=600).stdout
        if a.keep_logs:
            Path(a.keep_logs, f"simpleperf-{name}.txt").write_text(out)
        return out

    # The SEP is the last vCPU when SEP firmware is given. With placeholder SEP
    # inputs it spins (WFI wakes at once on its pending, masked mailbox IRQ), which
    # is not the iOS workload, so the hot-spot ranking covers every other thread.
    reports = {"thread": report("thread", "comm")}
    sep = f"CPU {a.smp - 1}/TCG"
    keep = [t for _, t in rows(reports["thread"]) if t != sep]
    only = f"--comms {shlex.quote(','.join(keep))}" if keep else ""
    reports["dso"] = report("dso", "dso", only)
    reports["symbol"] = report("symbol", "dso,symbol", f"{only} --percent-limit 0.05")
    sep_pct = sum(p for p, t in rows(reports["thread"]) if t == sep)
    return res, summarize_profile(reports, excluded=(sep, sep_pct))


ROW = re.compile(r"\s*([\d.]+)%\s+(?:\d+\s+)?(.+?)\s*$")   # event-count column is optional


def rows(report):
    """(percent, text) rows of a simpleperf report table."""
    out, table = [], False
    for line in report.splitlines():
        if line.startswith("Overhead"):
            table = True
            continue
        m = ROW.match(line) if table else None
        if m:
            out.append((float(m.group(1)), m.group(2)))
    return out


def summarize_profile(reports, excluded=None):
    # Guest code = samples outside any mapped file: TCG's code buffer shows up as dso "unknown".
    by_dso = rows(reports["dso"])
    jit = sum(p for p, t in by_dso if t.split()[0] == "unknown")
    totals = {"guest code (TCG-generated)": jit}
    top = []
    for pct, text in rows(reports["symbol"]):
        dso, _, sym = text.partition(" ")
        sym = sym.strip()
        if dso == "unknown":
            continue                      # counted exactly via the dso report
        key = f"{os.path.basename(dso)} {sym}"
        cat = next((name for name, pat in BUCKETS[1:] if pat.search(sym) or pat.search(key)), "other")
        totals[cat] = totals.get(cat, 0.0) + pct
        top.append((pct, cat, os.path.basename(dso), sym))
    totals["below 0.05% per symbol (not attributed)"] = max(0.0, 100.0 - sum(totals.values()))
    if excluded:
        print(f"\nexcluded SEP vCPU thread '{excluded[0]}': {excluded[1]:.1f}% of all samples")
    print("\nhost CPU time by category (user space, % of the remaining threads):")
    for cat, pct in sorted(totals.items(), key=lambda x: -x[1]):
        print(f"  {pct:6.2f}%  {cat}")
    print("\nby thread:")
    for pct, text in rows(reports["thread"])[:8]:
        print(f"  {pct:6.2f}%  {text}")
    print("\ntop symbols (excluding guest code):")
    for pct, cat, dso, sym in sorted(top, reverse=True)[:25]:
        print(f"  {pct:6.2f}%  {sym[:60]:60}  [{dso}] ({cat})")
    return {"categories": totals, "threads": rows(reports["thread"])[:16],
            "top": [{"pct": p, "category": c, "dso": d, "symbol": s} for p, c, d, s in sorted(top, reverse=True)[:60]]}


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--serial", required=True, help="adb serial of the device to run on")
    for name, help_ in [("qemu", "Android qemu-system-aarch64 (unstripped for --profile)"),
                        ("kernel", "kernelcache (IM4P or raw)"), ("dtb", "DeviceTree IM4P"),
                        ("trustcache", "restore ramdisk trustcache"), ("ramdisk", "restore ramdisk"),
                        ("sep-rom", "SEP ROM"), ("sep-fw", "SEP firmware")]:
        ap.add_argument(f"--{name}", required=True, help=help_)
    ap.add_argument("--runs", type=int, default=5)
    ap.add_argument("--smp", type=int, default=4, help="vCPUs incl. SEP (default 4; 7 hits #33 on Android)")
    ap.add_argument("--ram", default="2G")
    ap.add_argument("--tb-size", type=int, default=1024, help="TCG translation cache MiB (app default)")
    ap.add_argument("--timeout", type=int, default=180, help="seconds per run")
    ap.add_argument("--uplink", default="auto",
                    help="USB uplink addr; 'auto' (default) picks a free 127.0.0.1 port in 18030-18099 per run")
    ap.add_argument("--device-dir", default="/data/local/tmp/restore-bench")
    ap.add_argument("--keep-logs", help="local dir for stamped serial logs and the profile report")
    ap.add_argument("--profile", metavar="SIMPLEPERF", help="device simpleperf binary: add a profiled run")
    ap.add_argument("--profile-freq", type=int, default=1000)
    ap.add_argument("--profile-event", default="cpu-clock:u",
                    help="simpleperf event (default cpu-clock:u; on phones with a usable PMU try cpu-cycles:u)")
    ap.add_argument("--json", help="write results to this file")
    a = ap.parse_args()

    if a.keep_logs:
        Path(a.keep_logs).mkdir(parents=True, exist_ok=True)
    dev = Device(a.serial)
    setup(a, dev)
    others = other_qemus(dev, a.device_dir)
    if others:
        print(f"warning: {len(others)} other QEMU process(es) running on the device; timings will be pessimistic")
    t = time.time()
    results = bench(a, dev) if a.runs > 0 else []
    summary = summarize(results) if results else {}
    prof = profile(a, dev) if a.profile else None
    out = {"serial": a.serial, "runs": results, "summary": summary,
           "config": {"smp": a.smp, "ram": a.ram, "tb_size": a.tb_size},
           "profile": {"milestones": prof[0], **prof[1]} if prof else None,
           "wall_seconds": round(time.time() - t, 1)}
    if a.json:
        Path(a.json).write_text(json.dumps(out, indent=2))
    failed = [r for r in results if r["panic"] or "waiting_host" not in r["milestones"]]
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main())
