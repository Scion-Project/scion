#!/usr/bin/env python3
"""Boot restore mode to waiting_host, then measure per-thread host CPU while the guest idles.

Reuses restore_bench.py's command line and kit layout. For each run prints, per QEMU
thread, CPU seconds (utime+stime) during the boot (start -> waiting_host) and during an
idle window of --idle seconds after waiting_host.
"""
import argparse, json, re, shlex, sys, statistics
from pathlib import Path
sys.path.insert(0, str(Path(__file__).parent))
import restore_bench as rb


def script(a, d, uplink):
    cmd = " ".join(shlex.quote(x) for x in rb.qemu_command(a, d, uplink, ["-name", "debug-threads=on", *(a.extra or ())]))
    env = f"QEMU_VCPU_AFFINITY={shlex.quote(a.affinity)} " if a.affinity else ""
    return f"""
cd {d} || exit 1
rm -f serial.stamped qemu.err qemu.pid snap0 snap1 done.flag
snap() {{ p=$(cat {d}/qemu.pid); for t in /proc/$p/task/*; do
  echo "${{t##*/}}:$(cat $t/comm 2>/dev/null)|$(cut -d' ' -f14,15 $t/stat 2>/dev/null)"; done > $1; cat /proc/$p/stat | cut -d' ' -f14,15 >> $1; }}
read t0 _ < /proc/uptime; echo "$t0 @start" > serial.stamped
( {env}timeout {a.timeout} {cmd} 2> qemu.err < /dev/null | while IFS= read -r line; do
  read up _ < /proc/uptime; echo "$up $line" >> serial.stamped
  case "$line" in *"waiting for host to trigger start of restore"*)
    snap snap0; read u0 _ < /proc/uptime; echo "$u0" > t.idle0
    ( sleep {a.idle}; snap snap1; read u1 _ < /proc/uptime; echo "$u1" > t.idle1;
      kill -INT $(cat {d}/qemu.pid 2>/dev/null) 2>/dev/null ) & ;; esac
done )
read t1 _ < /proc/uptime; echo "@wall $t0 $t1"
echo "--snap0"; cat snap0 2>/dev/null; echo "--snap1"; cat snap1 2>/dev/null
echo "--t"; cat t.idle0 t.idle1 2>/dev/null
"""


def parse_snap(txt):
    out = {}
    for l in txt.strip().splitlines():
        if "|" not in l:
            continue
        name, _, v = l.partition("|")
        p = v.split()
        if len(p) != 2:
            continue
        out[name] = (int(p[0]) + int(p[1])) / 100.0
    return out


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--serial", default="emulator-5554")
    for n in ["qemu", "kernel", "dtb", "trustcache", "ramdisk", "sep-rom", "sep-fw"]:
        ap.add_argument(f"--{n}", required=True)
    ap.add_argument("--runs", type=int, default=1)
    ap.add_argument("--smp", type=int, default=4)
    ap.add_argument("--ram", default="2G")
    ap.add_argument("--tb-size", type=int, default=1024)
    ap.add_argument("--timeout", type=int, default=240)
    ap.add_argument("--idle", type=int, default=30)
    ap.add_argument("--uplink", default="auto")
    ap.add_argument("--affinity", default="")
    ap.add_argument("--device-dir", default="/data/local/tmp/idle")
    ap.add_argument("--keep-logs")
    ap.add_argument("--json")
    ap.add_argument("--extra", nargs="*")
    a = ap.parse_args()
    dev = rb.Device(a.serial)
    rb.setup(a, dev)
    results = []
    for i in range(1, a.runs + 1):
        uplink = rb.pick_uplink(a, dev)
        r = dev.sh(script(a, a.device_dir, uplink), check=False, timeout=a.timeout + a.idle + 60)
        out = r.stdout
        stamped = dev.sh(f"cat {a.device_dir}/serial.stamped", check=False).stdout
        res, panic, lines = rb.parse(stamped)
        if a.keep_logs:
            Path(a.keep_logs).mkdir(parents=True, exist_ok=True)
            Path(a.keep_logs, f"run{i}.serial.txt").write_text(stamped)
            Path(a.keep_logs, f"run{i}.out.txt").write_text(out)
        s0 = parse_snap(out.split("--snap0")[1].split("--snap1")[0]) if "--snap0" in out else {}
        s1 = parse_snap(out.split("--snap1")[1].split("--t")[0]) if "--snap1" in out else {}
        tt = out.split("--t")[-1].split()
        win = float(tt[1]) - float(tt[0]) if len(tt) >= 2 else None
        idle = {k: s1[k] - s0.get(k, 0) for k in s1} if s1 else {}
        tot0 = s0.pop("", None)
        tot1 = s1.pop("", None) if s1 else None
        idle.pop("", None)
        row = {"run": i, "milestones": res, "panic": panic, "boot_cpu": s0, "idle_cpu": idle,
               "idle_window": win}
        results.append(row)
        ms = "  ".join(f"{k}={res[k]:.1f}s" for k, _ in rb.MILESTONES if k in res)
        print(f"run {i}: {ms}" + (f"  PANIC {panic}" if panic else ""))
        if win:
            busy = {k: v for k, v in idle.items() if v > 0.005}
            tot = sum(idle.values())
            print(f"  idle window {win:.1f}s: total {tot:.2f} cpu-s = {tot / win:.3f} cores")
            for k in sorted(busy, key=lambda k: -busy[k]):
                print(f"    {k:24s} idle {busy[k]:7.2f}s ({busy[k] / win * 100:5.1f}%)   boot {s0.get(k, 0):7.2f}s")
        else:
            print("  no idle window (did not reach waiting_host?)")
    if a.json:
        Path(a.json).write_text(json.dumps(results, indent=2))


if __name__ == "__main__":
    main()
