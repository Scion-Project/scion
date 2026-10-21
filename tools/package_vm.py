#!/usr/bin/env python3
"""Package a finished Inferno setup (the guide's "InfernoData" folder) for the phone.

Run this on the Mac *after* the restore, filesystem patches and first boot
have completed there. It only rearranges files you already prepared:

  * picks the kernel, device tree and trustcache from Restore/BuildManifest.plist
  * converts the 32G sparse `root` disk to qcow2 (only used blocks get copied)
  * writes vm.properties, which the app reads
  * optionally `adb push`es the result into the app's folder

  package_vm.py ~/InfernoData                 # -> ~/InfernoData/phone-vm
  package_vm.py ~/InfernoData --push          # ...and push to a connected Pixel
"""
import argparse
import importlib.util
import os
import plistlib
import shutil
import subprocess
import sys
from pathlib import Path

PKG = "io.github.dylanneve1.iosonpixel"
PHONE_DIR = f"/sdcard/Android/data/{PKG}/files/vm"
SMALL_DISKS = ["firmware", "syscfg", "ctrl_bits", "nvram", "effaceable", "panic_log", "sep_nvram", "sep_ssc"]
AEA_MAGIC = b"AEA1"  # Apple Encrypted Archive container magic (first 4 bytes)


def load_probe():
    spec = importlib.util.spec_from_file_location("ipsw_probe", Path(__file__).with_name("ipsw_probe.py"))
    mod = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(mod)
    return mod


def pick(data: Path, pattern: str, what: str) -> Path:
    hits = sorted(data.glob(pattern))
    if len(hits) != 1:
        sys.exit(f"expected exactly one {what} matching {pattern} in {data}, found {[h.name for h in hits]}; "
                 f"pass it explicitly")
    return hits[0]


def find_qemu_img(data: Path, explicit):
    for c in [explicit, data / "Inferno/build/qemu-img", shutil.which("qemu-img")]:
        if c and Path(c).is_file():
            return str(c)
    sys.exit("qemu-img not found; build Inferno on the Mac (guide: Host Setup) or `brew install qemu`")


def reject_if_aea(path: Path, what: str):
    """Stop with guidance if `path` is still an Apple Encrypted Archive.

    iOS 26/27 IPSWs ship the OS root filesystem (and maybe the restore
    ramdisks) as .aea. This project ships no keys and never decrypts Apple
    firmware for you: the upstream guide's restore step has you decrypt it
    yourself on the Mac with Apple's own `aea` tool. We only sniff the magic
    and refuse — we neither decrypt nor fetch anything.
    """
    is_aea = path.suffix.lower() == ".aea"
    if not is_aea:
        try:
            with open(path, "rb") as f:
                is_aea = f.read(len(AEA_MAGIC)) == AEA_MAGIC
        except OSError:
            return  # existence/readability is reported by the caller's checks
    if not is_aea:
        return
    sys.exit(
        f"{what} ({path.name}) is an Apple Encrypted Archive (.aea): still encrypted.\n"
        f"Decrypt it yourself on this Mac first, then re-run — see `man aea` and the\n"
        f"restore step of the upstream Inferno guide. This tool ships no keys and\n"
        f"does not decrypt or download firmware."
    )


def scan_for_aea(data: Path):
    """Fail early if the prepared tree still contains any encrypted .aea image."""
    for p in sorted(data.rglob("*.aea")):
        reject_if_aea(p, "input image")


def place(src: Path, dst: Path):
    """Hard-link when possible so packaging doesn't double disk usage."""
    if dst.exists():
        dst.unlink()
    try:
        os.link(src, dst)
    except OSError:
        shutil.copy2(src, dst)


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("data", type=Path, help="InfernoData folder from the guide")
    ap.add_argument("--out", type=Path, help="output folder (default: <data>/phone-vm)")
    ap.add_argument("--kernel", type=Path)
    ap.add_argument("--trustcache", type=Path)
    ap.add_argument("--sep-rom", type=Path)
    ap.add_argument("--sep-fw", type=Path, help="default: sep-firmware.*.new.img4")
    ap.add_argument("--raw-root", action="store_true", help="keep root as raw instead of qcow2")
    ap.add_argument("--qemu-img")
    ap.add_argument("--push", action="store_true", help="adb push to the phone afterwards")
    a = ap.parse_args()

    data = a.data.expanduser().resolve()
    scan_for_aea(data)  # refuse undecrypted .aea images (iOS 26/27 OS DMG); user decrypts on the Mac
    out = (a.out or data / "phone-vm").resolve()
    restore = data / "Restore"
    manifest = plistlib.loads((restore / "BuildManifest.plist").read_bytes())
    probe = load_probe()

    ids = [i for i in manifest["BuildIdentities"] if i["Info"].get("DeviceClass", "").lower() == probe.BOARD]
    if not ids:
        sys.exit(f"Restore/BuildManifest.plist has no {probe.BOARD} identity; is this an iPhone 11 IPSW?")
    erase = next((i for i in ids if "Erase" in i["Info"].get("Variant", "")), ids[0])

    research = sorted(restore.glob("kernelcache.research.*"))
    kernel = a.kernel or (research[0] if research else restore / probe.path_of(erase, "KernelCache"))
    dtb = restore / probe.path_of(erase, "DeviceTree")
    trustcache = a.trustcache or restore / "Firmware" / f"{probe.path_of(erase, 'RestoreRamDisk')}.trustcache"
    files = {
        "kernel": kernel,
        "dtb": dtb,
        "trustcache": trustcache,
        "sep_fw": a.sep_fw or pick(data, "sep-firmware.*.new.img4", "re-packed SEP firmware"),
        "sep_rom": a.sep_rom or pick(data, "AppleSEPROM-Cebu-B1*", "SEP ROM"),
    }
    for what, src in files.items():
        reject_if_aea(Path(src), what)  # e.g. an undecrypted OS/kernel image passed explicitly
    missing = [f"{k}: {v}" for k, v in files.items() if not Path(v).is_file()]
    missing += [n for n in SMALL_DISKS + ["root"] if not (data / n).is_file()]
    if missing:
        sys.exit("missing:\n  " + "\n  ".join(missing))

    out.mkdir(parents=True, exist_ok=True)
    props = {}
    for key, src in files.items():
        place(Path(src), out / Path(src).name)
        props[key] = Path(src).name
    for n in SMALL_DISKS:
        place(data / n, out / n)

    if a.raw_root:
        place(data / "root", out / "root")
        props["root"], props["root_format"] = "root", "raw"
    else:
        qemu_img = find_qemu_img(data, a.qemu_img)
        print("converting root -> root.qcow2 (only allocated blocks are copied)…")
        subprocess.run([qemu_img, "convert", "-p", "-O", "qcow2", str(data / "root"), str(out / "root.qcow2")],
                       check=True)
        props["root"], props["root_format"] = "root.qcow2", "qcow2"

    with open(out / "vm.properties", "w") as f:
        f.write(f"# written by package_vm.py; iOS {manifest.get('ProductVersion')} "
                f"({manifest.get('ProductBuildVersion')})\n")
        for k, v in props.items():
            f.write(f"{k}={v}\n")

    total = sum(p.stat().st_size for p in out.iterdir() if p.is_file())
    print(f"packaged {out} ({total / 2**30:.1f} GiB)")

    if a.push:
        subprocess.run(["adb", "shell", "mkdir", "-p", PHONE_DIR], check=True)
        subprocess.run(["adb", "push", "--sync", f"{out}/.", PHONE_DIR], check=True)
        print(f"pushed to {PHONE_DIR}")
    else:
        print(f"push with:  adb push --sync '{out}/.' {PHONE_DIR}")


if __name__ == "__main__":
    main()
