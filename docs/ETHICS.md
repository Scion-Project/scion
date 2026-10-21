# Project aim, ethics and legal position

## Aim

**iOS on Pixel** is a research and debugging project. Its questions are
technical:

* Can an existing open-source Apple-device emulator (ChefKiss Inferno, a QEMU
  fork) be ported to run on an Android phone?
* How far can it be optimised for one specific SoC (Google Tensor G5: CPU
  topology, compiler tuning, and eventually its Imagination GPU)?

Emulator porting, JIT performance on big.LITTLE mobile CPUs, and Android's
process and sandbox model are the interesting parts. It isn't meant to be a
way to get iOS, iOS apps or Apple services onto non-Apple hardware, and it
doesn't provide any of those things.

## What this project does *not* do

These are deliberate design constraints. Contributors (human or agent) should
keep them.

1. **It distributes no Apple software or secrets.** The repository, releases
   and APKs contain no IPSWs, kernels, device trees, SEP firmware, SecureROM
   or SEP ROM dumps, decryption keys/IVs, tickets, or pre-made disk images.
   `.gitignore` excludes VM folders and build output.
2. **It doesn't tell you how to obtain Apple files.** No download links, key
   lookups, or "where to find" pointers. `tools/ipsw_probe.py --list` reports
   iOS versions and compatibility verdicts only. It deliberately strips
   download URLs. The probe only examines a file or URL the user already has.
3. **It doesn't automate firmware preparation.** Upstream Inferno explicitly
   asks that nobody automate "the download, setup, patching, or anything
   related" of firmware and images. We follow that. Decryption, restore and
   filesystem patching stay manual steps the user does by following upstream's
   own documentation. `tools/package_vm.py` only rearranges files the user has
   already prepared: it picks names from the manifest, converts the disk image
   format and copies files to the phone.
4. **It doesn't bypass anything on Apple's side.** No activation, iCloud, App
   Store, FairPlay or DRM circumvention. None of those work in Inferno, and
   adding them is out of scope.
5. **It doesn't impersonate Apple or upstream.** The app's name is
   descriptive; its icon is a generic phone, with no Apple logos.

## Why this is a reasonable thing to build

* **Emulation itself is lawful and well established.** Building software that
  emulates hardware has repeatedly been found lawful. *Sony v. Connectix*
  (9th Cir. 2000) covers a console emulator. More specifically, in
  *Apple v. Corellium* (S.D. Fla. 2020) Corellium's virtualised iPhones were
  held to be fair use for security research. Apple appealed and the case
  settled in 2023. This project is a much smaller research tool in the same
  spirit.
* **The emulator is open source and we comply with its licenses.** Inferno is
  GPL-3.0 (QEMU-derived parts) and AGPL-3.0 (its own code). Our fork is
  public, carries the full source and the upstream license files and copyright
  notices, and this repository is AGPL-3.0.
* **We follow upstream's branding notice.** Inferno's
  `ui/icons/CKBrandingNotice.md` requires derivative builds to remove their
  boot-splash artwork and the "ChefKiss Inferno" name, and not to imply
  endorsement. The `pixel` branch deletes the artwork, renames the version
  string and thread names, and makes the splash optional. The README credits
  upstream and says plainly that we're not affiliated. The copyright line in
  `--version` is kept because the GPL requires keeping copyright notices.
* **We respect upstream's wishes beyond the license.** The no-automation
  request is honoured (above). Upstream also labels its repository "written by
  humans, not AI". This project is AI-assisted and says so in its commit
  trailers, so nothing from here should be submitted upstream as a
  contribution without a human rewriting it and upstream agreeing.
* **Device-specific research uses public sources.** The Tensor G5 details come
  from Google's GPL kernel source as published for the Pixel 10 (CPU topology,
  GPU driver version). No vendor NDAs or leaked material are involved.

## Where responsibility sits

This needs saying honestly. **Apple's iOS Software License Agreement only
permits running iOS on Apple-branded hardware.** This project can't change
that for anyone, however the files were obtained. Running iOS in an emulator
on a Pixel is a choice each user makes about their own copy of the software.
Inferno's own disclaimer puts it the same way: users must comply with
applicable laws and EULAs, and may face consequences in their jurisdiction.

So the project:

* ships only original code plus GPL/AGPL-licensed emulator code,
* leaves obtaining and preparing any Apple files entirely to the user, under
  their own responsibility,
* and is aimed at experimentation, debugging and performance research, not at
  replacing an iPhone.

If you're not comfortable with the EULA question for your own situation, the
parts of this project that don't involve iOS at all are still useful and
self-contained: the Android QEMU port, the vCPU-pinning patch, and the VNC and
touch front end. They can be exercised with QEMU's generic `virt` machine and
a Linux guest (see `docs/HANDOFF.md`).
