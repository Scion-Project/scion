# iOS 14.8 smoke boot on Android: how far does XNU get?

Issue #35. Measured on the Android **emulator** (`sdk_gphone64_arm64`, 6 GB RAM, ~650 MB
free storage), not on a phone. Inferno `master` at `7c9a29a789`, Android cross build
(release, TCG `thread=multi`), no new kernel patches: only the patches upstream already
applies.

## Result

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

So the evidence points at the **SEP**, which is only a placeholder in this run. The
likely reading is that `AppleS8000AESAccelerator` won't publish `IOAESAccelerator` until
the SEP is up, but that's a correlation, not proven. **OPEN; to resolve:** repeat with a
real SEP ROM and SEP firmware (the user's own, as the app uses them). If `IOAESAccelerator`
registers, the SEP was the gate. If not, trace `apple_aes_*` again to see where the driver
stalls.

The `md0` mount failures are **not** the stopping point. They come from the placeholder
ramdisk (16 KiB of zeros, see below), and XNU keeps retrying until the AES panic.

## Inputs and command

| Input | Source |
|---|---|
| `kernelcache.research.iphone12b` | iOS 14.8 (18H17), already in the scratch kernels dir |
| `DeviceTree.n104ap.im4p` (`dtre`) | range-fetched from the 14.8 IPSW (n104ap Erase identity) |
| restore trustcache (`rtsc`) | range-fetched: `Firmware/018-61661-017.dmg.trustcache` |
| ramdisk | 16 KiB placeholder (zeros). t8030 needs one without `auto-boot`; the real 14.8 restore ramdisk was not fetched |
| SEP ROM / SEP firmware | placeholders: a ROM of `b .` (SEP core idles) and 16 KiB of zeros. No real SEP inputs here |
| NVMe namespaces, NVRAM, SEP NVRAM/SSC | blank images; root is a sparse 1 GiB file; all opened with `-snapshot` |

The command mirrors the app's `QemuCommand` (Inferno's sample command), with `-smp 4`,
`-m 2G` (the app default is 4G; the emulator has ~4 GB available), `-snapshot`, and a
placeholder `-initrd`:

```
qemu -M t8030,trustcache=restore.trustcache.im4p,sep-fw=placeholder.sepfw,sep-rom=spin.seprom,kaslr-off=true,usb-uplink-addr=127.0.0.1:18030 \
  -accel tcg,thread=multi,tb-size=1024 \
  -kernel kernelcache.research.iphone12b -dtb DeviceTree.n104ap.im4p -initrd placeholder.ramdisk \
  -append "tlto_us=-1 mtxspin=-1 agm-genuine=1 agm-authentic=1 agm-trusted=1 serial=3 wdt=-1 -vm_compressor_wk_sw" \
  -smp 4 -m 2G -display none -snapshot -serial file:serial.log \
  <sep_nvram/sep_ssc pflash, 7 NVMe namespaces each with id=<name>, as in QemuBackend.kt>
```

## Found along the way

1. **A zero-filled SEP ROM makes QEMU abort.** The first attempt used a ROM of zeros. Once
   `AppleSEPManager` powered the SEP on, the SEP core ran garbage and took exceptions.
   Serial showed a `dart-sep` fatal exception at address 0, then QEMU aborted with
   `Bad ram pointer 0x200` in `tb_htable_lookup` → `qemu_ram_addr_from_host_nofail`.
   A guest should fault there, not kill the host process. Only an invalid SEP ROM
   triggers it (a real ROM doesn't execute zeros), and I haven't checked whether it's
   Android-specific, so it isn't fixed here. A `b .` ROM avoids it, and that run has no
   `dart-sep` fault.
2. **Concurrent t8030 instances collide on the USB uplink port.** The VirtualHere host
   device binds `127.0.0.1:8030` by default, so a second instance fails to start.
   `usb-uplink-addr=127.0.0.1:<port>` avoids it. This matters for parallel testing on one
   device.
3. **The Android dev build has tracing compiled out** (`Trace backends: nop`), so `-trace`
   silently does nothing. Add `-Dtrace_backends=log` for a diagnostic build.
4. **`-smp 7` exhausts `vm.max_map_count`** before boot (tracked in #33). `-smp 4` boots.

## What this means for the phone proof of concept

- TCG on Android is fast enough for early boot: kernel and IOKit to root mount in seconds.
- The next blocker is `IOAESAccelerator` never registering, most likely because no SEP
  answers. Until it registers, nothing reaches userland, with or without a real ramdisk.
  The CPU and memory model got this far without complaint.
- **The most useful next data point is this same boot with real SEP inputs on the phone.**
  The emulator has no real SEP ROM or firmware, so it can't test that.
- After that, a real restore ramdisk (or a restored root disk with `auto-boot`) is needed
  to get past root mount.

## Reproduce

Test kit: `smoke14.sh` in the agent's scratch testkit, run from `/data/local/tmp/smoke14/`
on the emulator (`ANDROID_SERIAL=emulator-5554`; never the real phone). Firmware stays
there and in local scratch, and is never committed.
