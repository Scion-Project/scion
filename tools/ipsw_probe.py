#!/usr/bin/env python3
"""Check whether an IPSW can run on the Inferno t8030 (iPhone 11) machine.

Reads only BuildManifest.plist and the zip index. For a URL it uses HTTP range
requests, so a few hundred KB are fetched instead of the multi-GB firmware.
Nothing is extracted, decrypted or patched.

  ipsw_probe.py path/to/firmware.ipsw
  ipsw_probe.py https://updates.cdn-apple.com/.../iPhone11,8,iPhone12,1_..._Restore.ipsw
  ipsw_probe.py --list          # iPhone12,1 versions with verdicts (ipsw.me metadata, no URLs)
  ipsw_probe.py --json ...      # machine-readable output
"""
import argparse
import io
import json
import plistlib
import sys
import urllib.request
import zipfile

BOARD = "n104ap"          # iPhone 11
PRODUCT = "iPhone12,1"

# From ChefKiss Inferno hw/arm/apple-silicon/t8030.c (t8030_init): any other
# kernel major can't boot (upstream hits assert_not_reached(), which is undefined
# behaviour in release builds; the pixel branch turns it into an error).
KNOWN_MAJORS = {13, 14, 15, 16, 17, 18, 26}
# Only iOS 14.x is documented as booting to SpringBoard.
UI_MAJORS = {14}
REFERENCE_BUILD = "18A5351d"  # iOS 14.0 beta 5, used throughout the Inferno guide

UA = {"User-Agent": "ios-on-pixel-ipsw-probe/0.1"}


class HttpRangeFile(io.RawIOBase):
    """Seekable read-only view of a remote file via HTTP Range requests."""

    BLOCK = 256 * 1024

    def __init__(self, url):
        self.url = url
        self.pos = 0
        self.cache = {}
        req = urllib.request.Request(url, method="HEAD", headers=UA)
        with urllib.request.urlopen(req, timeout=30) as r:
            self.size = int(r.headers["Content-Length"])
            if r.headers.get("Accept-Ranges", "").lower() != "bytes":
                raise OSError("server does not support range requests")
        self.fetched = 0

    def seekable(self):
        return True

    def readable(self):
        return True

    def tell(self):
        return self.pos

    def seek(self, off, whence=io.SEEK_SET):
        base = {io.SEEK_SET: 0, io.SEEK_CUR: self.pos, io.SEEK_END: self.size}[whence]
        self.pos = max(0, base + off)
        return self.pos

    def _block(self, i):
        if i not in self.cache:
            start = i * self.BLOCK
            end = min(self.size, start + self.BLOCK) - 1
            req = urllib.request.Request(self.url, headers={**UA, "Range": f"bytes={start}-{end}"})
            with urllib.request.urlopen(req, timeout=60) as r:
                self.cache[i] = r.read()
            self.fetched += len(self.cache[i])
        return self.cache[i]

    def readinto(self, b):
        n = min(len(b), self.size - self.pos)
        if n <= 0:
            return 0
        out = bytearray()
        while len(out) < n:
            i, off = divmod(self.pos + len(out), self.BLOCK)
            out += self._block(i)[off:off + n - len(out)]
        b[:n] = out
        self.pos += n
        return n


def open_ipsw(src):
    if src.startswith(("http://", "https://")):
        raw = HttpRangeFile(src)
        return zipfile.ZipFile(io.BufferedReader(raw, buffer_size=HttpRangeFile.BLOCK)), raw
    return zipfile.ZipFile(src), None


def path_of(identity, component):
    return identity.get("Manifest", {}).get(component, {}).get("Info", {}).get("Path")


def probe(src):
    zf, remote = open_ipsw(src)
    with zf:
        names = set(zf.namelist())
        manifest = plistlib.loads(zf.read("BuildManifest.plist"))

    version = manifest.get("ProductVersion", "?")
    build = manifest.get("ProductBuildVersion", "?")
    products = manifest.get("SupportedProductTypes", [])
    major = int(version.split(".")[0]) if version[:1].isdigit() else None

    ids = [i for i in manifest.get("BuildIdentities", [])
           if i.get("Info", {}).get("DeviceClass", "").lower() == BOARD]
    by_variant = {i["Info"].get("Variant", ""): i for i in ids}
    erase = next((i for v, i in by_variant.items() if "Erase" in v), ids[0] if ids else None)
    upgrade = next((i for v, i in by_variant.items() if "Upgrade" in v), None)

    research = sorted(n for n in names if n.startswith("kernelcache.research."))
    files = {}
    if erase:
        files = {
            "kernel_release": path_of(erase, "KernelCache"),
            "kernel_research": research[0] if research else None,
            "device_tree": path_of(erase, "DeviceTree"),
            "sep_firmware": path_of(erase, "SEP"),
            "restore_ramdisk_erase": path_of(erase, "RestoreRamDisk"),
            "restore_ramdisk_upgrade": path_of(upgrade, "RestoreRamDisk") if upgrade else None,
            "os_image": path_of(erase, "OS"),
        }
        rd = files["restore_ramdisk_erase"]
        files["restore_trustcache"] = f"Firmware/{rd}.trustcache" if rd else None
    present = {k: (v in names if v else False) for k, v in files.items()}

    issues, notes = [], []
    if PRODUCT not in products or not ids:
        issues.append(f"not an {PRODUCT} ({BOARD}) firmware; Inferno's UI-capable machine is the iPhone 11 only")
    if major is None:
        issues.append(f"unrecognised ProductVersion {version!r}")
    elif major not in KNOWN_MAJORS:
        issues.append(f"iOS {major} kernel is unknown to the t8030 machine; QEMU cannot boot it")
    elif major not in UI_MAJORS:
        notes.append(f"iOS {major} is recognised but not documented to reach SpringBoard (experimental)")
    for key in ("device_tree", "sep_firmware", "restore_ramdisk_erase", "restore_trustcache"):
        if ids and not present.get(key):
            issues.append(f"missing {key.replace('_', ' ')} ({files.get(key)})")
    if ids and not research:
        notes.append("no research kernelcache; the guide boots kernelcache.research.*, release is untested")
    if build == REFERENCE_BUILD:
        notes.append("this is the guide's reference build")
    notes.append(f"preparing it needs the SEP firmware decryption key for build {build}; "
                 "this tool does not provide or look up keys")

    if issues:
        verdict = "unsupported"
    elif major in UI_MAJORS:
        verdict = "supported"
    else:
        verdict = "experimental"

    result = {
        "source": src, "version": version, "build": build, "products": products,
        "verdict": verdict, "issues": issues, "notes": notes,
        "files": {k: {"path": v, "present": present[k]} for k, v in files.items()},
    }
    if remote:
        result["bytes_fetched"] = remote.fetched
    return result


def print_report(r):
    mark = {"supported": "✅", "experimental": "🧪", "unsupported": "❌"}[r["verdict"]]
    print(f"{mark} {r['verdict'].upper()}: iOS {r['version']} ({r['build']})  [{', '.join(r['products'])}]")
    for i in r["issues"]:
        print(f"   ✗ {i}")
    for n in r["notes"]:
        print(f"   • {n}")
    if r["files"]:
        print("   files for the Inferno setup steps:")
        for k, f in r["files"].items():
            print(f"     {'✓' if f['present'] else '✗'} {k:24} {f['path'] or '-'}")
    if "bytes_fetched" in r:
        print(f"   ({r['bytes_fetched'] / 1024:.0f} KiB fetched via range requests)")


def list_firmwares(as_json):
    req = urllib.request.Request(f"https://api.ipsw.me/v4/device/{PRODUCT}?type=ipsw", headers=UA)
    with urllib.request.urlopen(req, timeout=30) as r:
        fws = json.load(r)["firmwares"]
    rows = []
    for fw in fws:
        major = int(fw["version"].split(".")[0])
        if major not in KNOWN_MAJORS:
            verdict = "unsupported"
        elif major in UI_MAJORS:
            verdict = "supported"
        else:
            verdict = "experimental"
        # Deliberately no download URLs: this tool checks compatibility, it doesn't fetch firmware.
        rows.append({"version": fw["version"], "build": fw["buildid"], "signed": fw["signed"],
                     "verdict": verdict})
    if as_json:
        json.dump(rows, sys.stdout, indent=2)
        print()
        return
    print(f"{PRODUCT} IPSWs (verdict by iOS version only; probe one for a full check):")
    for r in rows:
        print(f"  {r['verdict']:12} iOS {r['version']:8} {r['build']:10} {'signed' if r['signed'] else ''}")


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("ipsw", nargs="*", help="local .ipsw path or URL")
    ap.add_argument("--list", action="store_true", help=f"list known {PRODUCT} IPSWs")
    ap.add_argument("--json", action="store_true")
    a = ap.parse_args()
    if a.list:
        list_firmwares(a.json)
        return 0
    if not a.ipsw:
        ap.error("give an IPSW path/URL, or --list")
    results = []
    for src in a.ipsw:
        try:
            results.append(probe(src))
        except Exception as e:  # report and keep going with the next one
            results.append({"source": src, "verdict": "error", "error": str(e)})
    if a.json:
        json.dump(results, sys.stdout, indent=2)
        print()
    else:
        for r in results:
            if r["verdict"] == "error":
                print(f"⚠️  {r['source']}: {r['error']}")
            else:
                print_report(r)
    return 0 if all(r["verdict"] in ("supported", "experimental") for r in results) else 1


if __name__ == "__main__":
    sys.exit(main())
