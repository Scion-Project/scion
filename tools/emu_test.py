#!/usr/bin/env python3
"""Regression checks for the Android build of QEMU, run on an emulator or phone.

Pushes the cross-built qemu-system-aarch64 to a device over adb and runs:

  * patch-check: `-M t8030,patch-check=on` against each kernelcache you give it,
    reporting which kernel patches matched and whether the kernel uses SPTM/TXM
  * boot smoke test (optional): boots a VM folder for a fixed time and checks the
    serial log for a marker such as "Darwin Kernel Version"
  * app smoke test: installs the APK, launches it, checks the main screen, and
    runs the bundled QEMU inside the app sandbox (needs no Apple files; CI runs it)

Kernels and VM folders are your own files; this tool never downloads anything.

  emu_test.py patch-check ~/kernels/*kernelcache*
  emu_test.py patch-check --expect expected.json ~/kernels/*
  emu_test.py boot ~/InfernoData/phone-vm --seconds 300
  emu_test.py app-smoke app/build/outputs/apk/debug/app-debug.apk --out smoke

Exit status is non-zero on any failure or on a regression against --expect.
"""
import argparse
import json
import re
import subprocess
import sys
import time
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
DEFAULT_QEMU = ROOT / "app/src/main/jniLibs/arm64-v8a/libqemu-system-aarch64.so"
DEVICE_DIR = "/data/local/tmp/ios-on-pixel-test"
PKG = "io.github.dylanneve1.iosonpixel"
# primaryCpuAbi -> the lib/<isa> directory the package manager extracts to.
ABI_LIB_DIR = {"arm64-v8a": "arm64", "armeabi-v7a": "arm", "x86_64": "x86_64", "x86": "x86"}

APPLIED = re.compile(r"`(.+?)` patch applied in `(.+?)`")
MISSED = re.compile(r"`(.+?)` patch did not apply in `(.+?)`")
HEADER = re.compile(r"patch-check: (\S+) ([\d.]+), SPTM/TXM: (yes|no)")
# Fallback variants of one patch share a row: "disable AMX (newer)" -> "disable AMX".
VARIANT = re.compile(r" \((new|newer|old|alt|iOS 16\+)\)$")


def adb(serial, *args, check=True, capture=True):
    cmd = ["adb"] + (["-s", serial] if serial else []) + list(args)
    r = subprocess.run(cmd, capture_output=capture, text=True)
    if check and r.returncode != 0:
        sys.exit(f"adb {' '.join(args[:2])} failed: {r.stderr.strip()}")
    return r


def push_qemu(serial, qemu):
    if not qemu.is_file():
        sys.exit(f"no QEMU binary at {qemu}; run native/build-android.sh or pass --qemu")
    adb(serial, "shell", "mkdir", "-p", f"{DEVICE_DIR}/k")
    adb(serial, "push", "-q", str(qemu), f"{DEVICE_DIR}/qemu")
    adb(serial, "shell", "chmod", "755", f"{DEVICE_DIR}/qemu")


def parse_patch_check(output):
    """Turn patch-check output into {version, sptm, patches: {name: [variants applied]}}."""
    result = {"version": None, "sptm": None, "patches": {}, "error": None}
    for line in output.splitlines():
        if m := HEADER.search(line):
            result["version"], result["sptm"] = m.group(2), m.group(3) == "yes"
        elif m := APPLIED.search(line):
            base = VARIANT.sub("", m.group(1))
            variant = m.group(1)[len(base):].strip() or "-"
            result["patches"].setdefault(base, []).append(variant)
        elif m := MISSED.search(line):
            result["patches"].setdefault(VARIANT.sub("", m.group(1)), [])
    if result["version"] is None:
        result["error"] = output.strip().splitlines()[-1] if output.strip() else "no output"
    return result


def cmd_patch_check(a):
    push_qemu(a.serial, a.qemu)
    results = {}
    for k in a.kernels:
        k = Path(k)
        adb(a.serial, "push", "-q", str(k), f"{DEVICE_DIR}/k/{k.name}")
        r = adb(a.serial, "shell",
                f"cd {DEVICE_DIR} && timeout {a.timeout} ./qemu -M t8030,patch-check=on "
                f"-kernel k/{k.name} -display none -m 1G 2>&1", check=False)
        results[k.name] = parse_patch_check(r.stdout)

    names = sorted({p for r in results.values() for p in r["patches"]})
    cols = list(results)
    print("| Patch | " + " | ".join(cols) + " |")
    print("|---|" + "---|" * len(cols))
    print("| *iOS / SPTM* | " + " | ".join(
        f"{r['version']} / {'yes' if r['sptm'] else 'no'}" if not r["error"] else f"ERROR: {r['error']}"
        for r in results.values()) + " |")
    for n in names:
        cells = []
        for r in results.values():
            v = r["patches"].get(n)
            cells.append("—" if v is None else ("✅ " + ",".join(v)) if v else "❌")
        print(f"| {n} | " + " | ".join(cells) + " |")

    failed = [k for k, r in results.items() if r["error"]]
    if a.json:
        Path(a.json).write_text(json.dumps(results, indent=2) + "\n")
    if a.expect:
        failed += regressions(json.loads(Path(a.expect).read_text()), results)
    for f in failed:
        print(f"FAIL: {f}", file=sys.stderr)
    return 1 if failed else 0


def regressions(expected, results):
    """A regression is a patch that applied in the baseline but no longer does."""
    out = []
    for kernel, exp in expected.items():
        got = results.get(kernel)
        if got is None:
            continue
        if exp.get("sptm") is not None and got["sptm"] != exp["sptm"]:
            out.append(f"{kernel}: SPTM/TXM detection changed ({exp['sptm']} -> {got['sptm']})")
        for name, variants in exp.get("patches", {}).items():
            if variants and not got["patches"].get(name):
                out.append(f"{kernel}: '{name}' no longer applies")
    return out


def cmd_boot(a):
    """Boot a packaged VM folder for a fixed time and look for a marker in the serial log."""
    vm = Path(a.vm)
    if not (vm / "vm.properties").is_file():
        sys.exit(f"{vm} has no vm.properties; package it with tools/package_vm.py first")
    push_qemu(a.serial, a.qemu)
    adb(a.serial, "push", "-q", "--sync", f"{vm}/.", f"{DEVICE_DIR}/vm", capture=False)
    props = dict(line.split("=", 1) for line in (vm / "vm.properties").read_text().splitlines()
                 if "=" in line and not line.startswith("#"))
    machine = ",".join(["t8030", f"trustcache={props['trustcache']}", f"sep-fw={props['sep_fw']}",
                        f"sep-rom={props['sep_rom']}", "kaslr-off=true"]
                       + [f"{k}={props[k]}" for k in ("sptm", "txm") if k in props])
    drives = [f"-drive file=sep_nvram,if=pflash,format=raw,file.locking=off",
              f"-drive file=sep_ssc,if=pflash,format=raw,file.locking=off",
              f"-drive file={props['root']},format={props.get('root_format', 'raw')},if=none,id=root,file.locking=off "
              f"-device nvme-ns,drive=root,bus=nvme-bus.0,nsid=1,nstype=1,id=root,"
              f"logical_block_size=4096,physical_block_size=4096"]
    for name, nsid, nstype, dev in [("firmware", 2, 2, "nvme-ns"), ("syscfg", 3, 3, "nvme-ns"),
                                    ("ctrl_bits", 4, 4, "nvme-ns"), ("nvram", 5, 5, "apple-nvram"),
                                    ("effaceable", 6, 6, "nvme-ns"), ("panic_log", 7, 8, "nvme-ns")]:
        # Inferno dev looks namespaces up by id (e.g. peripheral/firmware).
        drives.append(f"-drive file={name},format=raw,if=none,id={name},file.locking=off "
                      f"-device {dev},drive={name},bus=nvme-bus.0,nsid={nsid},nstype={nstype},id={name},"
                      f"logical_block_size=4096,physical_block_size=4096")
    cmd = (f"cd {DEVICE_DIR}/vm && timeout {a.seconds} ../qemu -M {machine} -kernel {props['kernel']} "
           f"-dtb {props['dtb']} -smp {a.smp} -m 4G -display none -serial file:{DEVICE_DIR}/serial.log "
           + " ".join(drives) + " > ../qemu.log 2>&1; echo exit=$?")
    print(adb(a.serial, "shell", cmd, check=False).stdout.strip())
    log = adb(a.serial, "shell", f"cat {DEVICE_DIR}/serial.log", check=False).stdout
    Path(a.log).write_text(log)
    found = re.search(a.marker, log)
    print(f"serial log: {len(log.splitlines())} lines, saved to {a.log}")
    print(f"marker {a.marker!r}: {'FOUND' if found else 'not found'}")
    return 0 if found else 1


def native_lib_dir(serial):
    """The app's nativeLibraryDir, where the package manager extracted libqemu-system-aarch64.so."""
    dump = adb(serial, "shell", "dumpsys", "package", PKG).stdout
    legacy = re.search(r"legacyNativeLibraryDir=(\S+)", dump)
    abi = re.search(r"primaryCpuAbi=(\S+)", dump)
    if not (legacy and abi and abi.group(1) in ABI_LIB_DIR):
        sys.exit(f"can't find {PKG}'s native library dir in `dumpsys package`")
    return f"{legacy.group(1)}/{ABI_LIB_DIR[abi.group(1)]}"


def cmd_app_smoke(a):
    """Install the APK, launch MainActivity, check the main screen and the bundled QEMU."""
    out = Path(a.out)
    out.mkdir(parents=True, exist_ok=True)
    failures = []

    def check(ok, what):
        print(f"{'PASS' if ok else 'FAIL'}: {what}")
        if not ok:
            failures.append(what)

    print("device ABIs:", adb(a.serial, "shell", "getprop", "ro.product.cpu.abilist").stdout.strip())
    adb(a.serial, "logcat", "-c", check=False)
    # -g grants runtime permissions, so the notification prompt doesn't cover the screen.
    adb(a.serial, "install", "-r", "-g", str(a.apk))
    adb(a.serial, "shell", "am", "start", "-W", "-n", f"{PKG}/.MainActivity")

    xml, deadline = "", time.monotonic() + a.timeout
    while time.monotonic() < deadline:
        adb(a.serial, "shell", "uiautomator", "dump", "/sdcard/window_dump.xml", check=False)
        xml = adb(a.serial, "shell", "cat", "/sdcard/window_dump.xml", check=False).stdout
        if "Status: " in xml:
            break
        time.sleep(2)
    (out / "window_dump.xml").write_text(xml)
    check("Status: stopped" in xml, "main screen renders 'Status: stopped'")
    check("✓ QEMU binary bundled" in xml, "checks list shows '✓ QEMU binary bundled'")
    check(bool(adb(a.serial, "shell", "pidof", PKG, check=False).stdout.strip()), "app process is alive")

    # Exec QEMU the way the app does: as the app's uid, from nativeLibraryDir (run-as needs a debug APK).
    qemu = f"{native_lib_dir(a.serial)}/libqemu-system-aarch64.so"
    print("bundled QEMU:", qemu)
    ver = adb(a.serial, "shell", "run-as", PKG, qemu, "--version", check=False)
    (out / "qemu-version.txt").write_text(ver.stdout + ver.stderr)
    check(ver.returncode == 0 and "version" in ver.stdout.lower(), "bundled QEMU runs `--version` in the app sandbox")
    machines = adb(a.serial, "shell", "run-as", PKG, qemu, "-M", "help", check=False)
    (out / "qemu-machines.txt").write_text(machines.stdout + machines.stderr)
    check(re.search(r"^t8030\b", machines.stdout, re.M) is not None, "bundled QEMU lists the t8030 machine")

    shot = subprocess.run(["adb"] + (["-s", a.serial] if a.serial else []) + ["exec-out", "screencap", "-p"],
                          capture_output=True)
    (out / "screen.png").write_bytes(shot.stdout)
    logcat = adb(a.serial, "logcat", "-d", check=False).stdout
    (out / "logcat.txt").write_text(logcat)
    check(not re.search(rf"FATAL EXCEPTION.*\n.*Process: {re.escape(PKG)}", logcat), "no crash in logcat")
    print(f"artifacts in {out}/")
    return 1 if failures else 0


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--serial", help="adb device serial (default: the only device)")
    ap.add_argument("--qemu", type=Path, default=DEFAULT_QEMU, help="Android QEMU binary to test")
    sub = ap.add_subparsers(dest="cmd", required=True)

    pc = sub.add_parser("patch-check", help="kernel patch matrix for one or more kernelcaches")
    pc.add_argument("kernels", nargs="+")
    pc.add_argument("--timeout", type=int, default=300)
    pc.add_argument("--json", help="write results as JSON (usable as a later --expect baseline)")
    pc.add_argument("--expect", help="baseline JSON; fail if a patch that applied there no longer does")

    bt = sub.add_parser("boot", help="boot smoke test of a packaged VM folder")
    bt.add_argument("vm", help="folder from tools/package_vm.py")
    bt.add_argument("--seconds", type=int, default=300)
    bt.add_argument("--smp", type=int, default=4, help="vCPUs; 7 currently exhausts Android's mapping limit (#33)")
    bt.add_argument("--marker", default=r"Darwin Kernel Version", help="regex expected in the serial log")
    bt.add_argument("--log", default="serial.log", help="where to save the serial log locally")

    sm = sub.add_parser("app-smoke", help="install + launch the APK, check the UI and the bundled QEMU")
    sm.add_argument("apk", type=Path)
    sm.add_argument("--out", default="smoke", help="folder for the UI dump, screenshot, logcat, QEMU output")
    sm.add_argument("--timeout", type=int, default=60, help="seconds to wait for the main screen")

    a = ap.parse_args()
    sys.exit({"patch-check": cmd_patch_check, "boot": cmd_boot, "app-smoke": cmd_app_smoke}[a.cmd](a))


if __name__ == "__main__":
    main()
