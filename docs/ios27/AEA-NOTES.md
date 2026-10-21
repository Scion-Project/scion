# AEA (Apple Encrypted Archive) notes

Why `package_vm.py` now refuses `.aea` input, and what the user must do about
it themselves. Read [../ETHICS.md](../ETHICS.md) first: this project ships no
keys and does not automate firmware decryption. These notes keep that line.

## 1. What `.aea` is

Starting with iOS 16, Apple wraps parts of the firmware in the **Apple
Encrypted Archive** container. IPSW components that used to be a plain `.dmg`
now arrive as `<name>.dmg.aea`: an encrypted, authenticated stream wrapping the
original payload. The file begins with the four ASCII bytes **`AEA1`** (its
format magic); that is all this project ever looks at.

AEA is a first-class part of macOS. Apple ships:

* the **`aea`** command-line tool (`man aea`: subcommands `encrypt`, `decrypt`,
  `id`, `sign`, `verify`), and
* the **Apple Archive** framework (`AppleArchive` / `libAppleArchive`, the
  `AEAContext` / `AEA*` C API) behind it.

Decrypting an `.aea` needs the decryption material for that specific archive
(an auth/signing key or a profile). The user obtains and supplies that
themselves, exactly as they already do for the SEP firmware key — this project
neither provides nor looks up any key.

## 2. Which 27.0.1 components are `.aea`

From `tools/ipsw_probe.py` run on a local 27.0.1 (`24A446`) IPSW, `n104ap`
erase/upgrade identities (manifest metadata only), plus [../IOS27.md](../IOS27.md)
§1 for 26.6.2 (`23G90`):

| Build-manifest component | `ipsw_probe` key | 27.0.1 path | `.aea`? |
|---|---|---|---|
| `OS` (the root filesystem DMG) | `os_image` | `043-70213-665.dmg.aea` | **yes** (also `.aea` on 26.6.2) |
| `RestoreRamDisk` (erase) | `restore_ramdisk_erase` | `043-69915-784.dmg` | no, plain `.dmg` |
| `RestoreRamDisk` (upgrade) | `restore_ramdisk_upgrade` | `043-69093-778.dmg` | no, plain `.dmg` |

So on 27.0.1 the **OS image is the only AEA component**.

`tools/ipsw_probe.py` already flags this: `MONITOR_COMPONENTS` aside, its
`encrypted` check inspects `os_image`, `restore_ramdisk_erase` and
`restore_ramdisk_upgrade` for an `.aea` suffix and emits
*"AEA-encrypted images (...) must be decrypted manually before use"*.

The one that matters for `package_vm.py` is the **`OS` / root filesystem DMG**:
the upstream Inferno restore step produces the `root` disk from it, and that
step is where the user decrypts the `.aea` on their Mac. SPTM/TXM (`sptm.*`,
`txm.*`) are separate IM4P firmware images and are **not** `.aea`.

The detection code doesn't depend on these names: it keys on the `.aea`
suffix and the `AEA1` magic, so it also covers other builds where a different
component turns out to be AEA.

## 3. What `package_vm.py` does about it

`package_vm.py` only rearranges files the user has **already prepared** (see
README / ETHICS). If the user points it at a tree where the OS image (or any
other input) is still an encrypted `.aea`, the right behaviour is to **stop and
explain**, not to attempt decryption.

So the tool now:

1. defines `AEA_MAGIC = b"AEA1"` and a small `reject_if_aea(path, what)` that
   checks the suffix and the 4-byte magic and, on a match, exits with a clear
   message telling the user to decrypt it themselves with Apple's `aea` tool
   (per the upstream guide), and
2. before packaging, scans the input tree for any `*.aea` and runs the same
   check over each picked input file.

It does **not**:

* run `aea`, the Apple Archive framework, or any decryption;
* contain, derive, fetch, or prompt for a key, IV, or profile;
* download anything or tell the user where to get keys.

The message points at `man aea` and the upstream Inferno guide, which is where
the user already does restore/patching by hand.

## 4. The detection function (as added to `tools/package_vm.py`)

```python
AEA_MAGIC = b"AEA1"  # Apple Encrypted Archive container magic (first 4 bytes)


def reject_if_aea(path: Path, what: str):
    """Stop with guidance if `path` is still an Apple Encrypted Archive.

    iOS 26/27 IPSWs ship the OS root filesystem DMG as .aea (on 27.0.1 the
    restore ramdisks are plain .dmg). This project ships no keys and never decrypts Apple
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
        f"Decrypt it yourself with macOS tools (Apple's `aea`; see `man aea`) by\n"
        f"following the upstream Inferno guide's restore step, then rerun this tool.\n"
        f"This tool ships no keys and does not decrypt or download firmware."
    )


def scan_for_aea(data: Path):
    """Fail early if the prepared tree still contains any encrypted .aea image."""
    for p in sorted(data.rglob("*.aea")):
        reject_if_aea(p, "input image")
```

It is wired into `main()` right after the input tree is resolved and after the
`files` dict is built (so both a stray `*.aea` anywhere in the tree and an
explicitly-passed `.aea` input are caught before any work):

```python
    data = a.data.expanduser().resolve()
    ...
    scan_for_aea(data)
    ...
    # after `files = {...}` is assembled, before the existence check:
    for what, src in files.items():
        reject_if_aea(Path(src), what)
```

Style matches the rest of the file: `sys.exit(...)` for user-facing errors,
`pathlib.Path`, no new imports, no third-party dependencies.

## 5. OPEN / to resolve

* ~~Exact `.aea` component names for 27.0.1~~ — **resolved** (§2): only the
  `OS` image (`043-70213-665.dmg.aea`) is AEA; both restore ramdisks are plain
  `.dmg`.
* **Other builds.** 26.6.2's ramdisk paths weren't re-checked here (IOS27.md
  only records its OS image as `.aea`). The tree-wide `scan_for_aea` covers any
  stray `.aea` regardless; re-run `ipsw_probe.py` on a build before relying on
  the table above for it.
* **Precise `aea decrypt` invocation** — intentionally not spelled out with a
  key argument, to avoid implying decryption is keyless. `man aea` plus the
  upstream guide is the correct pointer; refine the message only if upstream
  documents a canonical command.
