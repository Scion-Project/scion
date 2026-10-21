# iOS 14.8 smoke boot on Android: how far does XNU get?

Issue #35. Measured on the Android **emulator** (`sdk_gphone64_arm64`, 6 GB RAM, ~650 MB
free storage), not on a phone. Inferno `master` at `7c9a29a789`, Android cross build
(release, TCG `thread=multi`), no new kernel patches: only the patches upstream already
applies.

> **Update: with the real restore ramdisk, there's no panic.** XNU boots to `launchd`
> and the restore daemon, brings up USB, and waits for a restore host. See
> [Run 2](#run-2-real-restore-ramdisk). The `IOAESAccelerator` panic below came from the
> **placeholder ramdisk**: Inferno's troubleshooting page lists it as the symptom of
> booting restore without the restore ramdisk. It was not the SEP, as first suspected.

## Result (run 1: placeholder ramdisk)

**XNU 14.8 boots through the whole kernel and IOKit bring-up and reaches root mount in
about 5 seconds of wall time.** It then panics about 60 guest seconds later:

```
panic(cpu 0 caller 0xfffffff0083cf560): cannot find IOAESAccelerator
```

| Wall time | Serial line | Stage |
|---|---|---|
| ≤ 5 s | 1 | `iBoot version: ios-on-pixel`, kernel starts; corecrypto FIPS self-tests pass |
| ≤ 5 s | 25–30 | AMFI (research mode), `AppleT803xIO` (chip-revision C0) |
| ≤ 5 s | 121–175 | `AppleS8000AES::start` begins; `AppleSEPManager` registers and powers the SEP on |
| ≤ 5 s | 210–215 | PCI configuration; NVMe (ANS2) finds the 7 namespaces |
| ≤ 5 s | 269 | **`BSD root: md0`**; IOKit enumeration done, root mount attempted |
| 5–60 s | 270–461 | APFS/HFS mount of `md0` fails 14 times (expected, see inputs) |
| ≈ 60 s | 474 | **`panic: cannot find IOAESAccelerator`** |
| 66 s | 556 | watchdog reset; on reset t8030 sees the panic header and QEMU exits (status 0) |

The panic string and full panic log are on the serial console, right after the mount
retries. Measured with 5-second sampling. Another agent's t8030 instance was running on
the emulator for part of the run, so these times are an upper bound.

## What the panic means

`IOAESAccelerator` is published by **`AppleS8000AESAccelerator`**, the driver for the
application processor's own AES block, not the SEP. On serial it gets partway: registers
mapped, `aes-version: 3`, `using-acg: no`, `using DART Mapper`. It never registers the
service, and about 60 s later the consumer gives up and panics. Inferno does model this
block (`hw/misc/aes.c`, `t8030_create_aes`). With `-d guest_errors` the model logs **no**
unsupported-key, unknown-opcode or bad-offset accesses.

A trace-enabled build (`-Dtrace_backends=log`) narrows it further:

- **The driver never touches the AES block.** Not one `apple_aes_*` event (register read
  or write, command, IRQ) fires during the whole boot. In the same run the positive
  controls do fire: watchdog reads, writes and the panic reset, plus mailbox traffic. The
  `aes-version` it prints comes from the device tree, so it stops *before* using the
  hardware.
- **The SEP never answers.** The AP posts 4 messages to the SEP's inbox. None are consumed
  and nothing comes back, and the SEP IOP never starts (`apple_sep_iop_start` never
  fires). Every other IOP has matched two-way traffic: SMC (434/434), SIO, ANS2, AOP.

**Corrected by run 2:** I read this as the SEP gating the AES driver. That was wrong.
With the real restore ramdisk, the same placeholder SEP (it still never answers) produces
no `IOAESAccelerator` panic in four full boots. The trace facts above still hold, but
they didn't identify the cause: the missing restore ramdisk did. That matches Inferno's
troubleshooting entry for this panic.

The `md0` mount failures come from the placeholder ramdisk (16 KiB of zeros, see below).

## Inputs and command

| Input | Source |
|---|---|
| `kernelcache.research.iphone12b` | iOS 14.8 (18H17), already in the scratch kernels dir |
| `DeviceTree.n104ap.im4p` (`dtre`) | range-fetched from the 14.8 IPSW (n104ap Erase identity) |
| restore trustcache (`rtsc`) | range-fetched: `Firmware/018-61661-017.dmg.trustcache` |
| ramdisk | 16 KiB placeholder (zeros). t8030 needs one without `auto-boot`; the real 14.8 restore ramdisk was not fetched |
| SEP ROM / SEP firmware | placeholders, both filled with `b .` words: a 4 KiB ROM (the SEP core spins in place) and a 16 KiB firmware file (never run). No real SEP inputs here; see below |
| NVMe namespaces, NVRAM, SEP NVRAM/SSC | blank images; root is a sparse 1 GiB file; all opened with `-snapshot` |

The command mirrors the app's `QemuCommand` (Inferno's sample command), with `-smp 4`,
`-m 2G` (the app default is 4G; the emulator has ~4 GB available), `-snapshot`, a
placeholder `-initrd`, and a USB uplink port of its own (any free port; see finding 2):

```
qemu -M t8030,trustcache=restore.trustcache.im4p,sep-fw=placeholder.sepfw,sep-rom=spin.seprom,kaslr-off=true,usb-uplink-addr=127.0.0.1:18030 \
  -accel tcg,thread=multi,tb-size=1024 \
  -kernel kernelcache.research.iphone12b -dtb DeviceTree.n104ap.im4p -initrd placeholder.ramdisk \
  -append "tlto_us=-1 mtxspin=-1 agm-genuine=1 agm-authentic=1 agm-trusted=1 serial=3 wdt=-1 -vm_compressor_wk_sw" \
  -smp 4 -m 2G -display none -snapshot -serial file:serial.log \
  <sep_nvram/sep_ssc pflash, 7 NVMe namespaces each with id=<name>, as in QemuBackend.kt>
```

**SEP placeholders.** Since Inferno #11, t8030 refuses SEP images that can't be real: empty
files, files larger than their region (ROM 8 MiB, firmware 16 MiB), and files that are a
single repeated byte. So zero-filled placeholders stop at load with a clear error. Fill the
placeholders with any non-uniform pattern instead, for example the AArch64 `b .` word:

```sh
python3 -c "import struct; w = struct.pack('<I', 0x14000000)
open('spin.seprom', 'wb').write(w * 1024)          # 4 KiB SEP ROM: the SEP core spins in place
open('placeholder.sepfw', 'wb').write(w * 4096)    # 16 KiB SEP firmware: never run"
```

The ramdisk placeholder (`placeholder.ramdisk`, 16 KiB of zeros) isn't checked.

## Found along the way

1. **A zero-filled SEP ROM makes QEMU abort.** The first attempt used a ROM of zeros. Once
   `AppleSEPManager` powered the SEP on, the SEP core ran garbage and took exceptions.
   Serial showed a `dart-sep` fatal exception at address 0, then QEMU aborted with
   `Bad ram pointer 0x200` in `tb_htable_lookup` → `qemu_ram_addr_from_host_nofail`.
   A guest should fault there, not kill the host process. Only an invalid SEP ROM
   triggers it (a real ROM doesn't execute zeros). A `b .` ROM avoids it, and that run has
   no `dart-sep` fault. **Addressed in Inferno #11:** an IOMMU-denied CPU access is now a
   guest bus error instead of a host abort, and a uniform SEP image is rejected at load.
2. **Concurrent t8030 instances collide on the USB uplink port.** The VirtualHere host
   device binds `127.0.0.1:8030` by default, so a second instance fails to start.
   `usb-uplink-addr=127.0.0.1:<port>` avoids it. This matters for parallel testing on one
   device.
3. **The Android dev build has tracing compiled out** (`Trace backends: nop`), so `-trace`
   silently does nothing. Add `-Dtrace_backends=log` for a diagnostic build.
4. **`-smp 7` exhausts `vm.max_map_count`** before boot (tracked in #33). `-smp 4` boots.
5. **Fetching:** `ipsw_probe.HttpRangeFile` has no retry, and a 71 MiB member timed out
   mid-read. Fetching the member's exact byte range with `curl --retry` (offset from the
   zip directory), then inflating and checking the CRC, worked first time (8 s).

## Run 2: real restore ramdisk

Same binary, command and inputs as run 1, but `-initrd` is the real 14.8 erase restore
ramdisk (`018-61661-017.dmg`, IM4P `rdsk`, 105.7 MiB). It was range-fetched from the 14.8
IPSW, CRC-checked against the zip directory, and kept as a local research copy. The
binary is Inferno master `7c9a29a789` built with `-Dtrace_backends=log` (no `-trace`
given). USB uplink on `127.0.0.1:18030`. Capped at 9 minutes.

**Result: XNU boots to userland and the restore daemon waits for a host. No panic.**

| Wall time | Stage |
|---|---|
| ≤ 5 s | `BSD root: md0`; the ramdisk mounts |
| ≤ 10 s | `launchd[1]: hello`, then "Restore environment starting." Boot tasks run |
| ≈ 19 s | `restored_external` checkpoints, through `enable_usb` |
| ≈ 19 s | USB device stack up as *Apple Mobile Device* with the `AppleUSBMux` interface |
| ≈ 19 s | **`waiting for host to trigger start of restore [timeout of 120 seconds]`** |
| +120 s | no host connects: `restore-outcome=recovery_mode` written to NVRAM, `/sbin/reboot` |
| ≈ +5 s | **warm reboot works**: the next boot reaches `launchd` about 5 s after the reboot |

In 9 minutes the cycle ran **four times**, each identical and none panicking. Waiting for
a host over USB is the expected end of restore mode. Times come from 5-second sampling
and the guest wall clock in the log.

Nothing in userland blocked on the SEP, even though it still never answers (restore mode
hasn't needed it by this point). Non-fatal noise each boot:
- `gasgauge_start_update_thread failed` (no battery gauge), reported as a restore warning
- `kSMCKeyNotFound` for SMC notifications
- missing display tunables

## What this means for the phone proof of concept

- **The Android build boots iOS 14.8 XNU into userland.** `launchd` and the restore
  daemon run within about 10–20 s of wall time on the emulator, and warm reboots work.
  CPU, memory, interrupts, NVMe, NVRAM and USB-device emulation all held up.
- The next steps are on the host side: a restore host on the uplink port to start an
  actual restore, or a restored root disk with `auto-boot` for a normal boot.
- The SEP hasn't been exercised yet. Real SEP inputs will matter once a restore or normal
  boot reaches the data-protection and keybag stages.

## Reproduce

Test kit: `smoke14.sh` in the agent's scratch testkit, run from `/data/local/tmp/smoke14/`
on the emulator (`ANDROID_SERIAL=emulator-5554`; never the real phone). Firmware stays
there and in local scratch, and is never committed.
