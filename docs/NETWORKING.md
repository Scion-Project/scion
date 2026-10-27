# Networking: getting the guest online

## AVF backend (iOS 16.5 on HackiOS's XNU): the "Network" setting

*Added 2026-10-22. Everything below the next heading is about the QEMU backend.*

**What AVF offers on the Pixel 10 (Android 17, DP11.260918.006):** custom VMs can ask for a
network card with `VirtualMachineCustomImageConfig.Builder.useNetwork(true)`. The member is
`@hide`, but the existing hidden-API exemption (`Landroid/system/virtualmachine/VirtualMachineCustomImageConfig`)
already covers it. virtualizationservice then makes a TAP with `vmnic` (`avf_tap_<suffix>`) and
asks the system's `IVmTethering` service (service-virtualization.jar) to start tethering of type 7
(TETHERING_VIRTUAL) on it, so Android's tethering supplies DHCP, DNS and NAT. crosvm puts a
virtio-net-pci card on that TAP. The Terminal app (Linux VM) uses exactly this: its config only
sets `useNetwork`. On this phone `/apex/com.android.virt/bin/vmnic` exists and
`android.system.vmtethering.IVmTethering` is published, so the feature is built in.
`VirtualMachineManager.isFeatureEnabled("com.android.kvm.NETWORK")` reports it.

**Not yet verified on the phone:** whether virtualizationservice accepts `useNetwork` from a
sideloaded app holding MANAGE_VIRTUAL_MACHINE, USE_CUSTOM_VIRTUAL_MACHINE and INTERNET (the Terminal is a
privileged app). Nothing is bypassed: if it refuses, the VM fails to start with the framework's
error in the app's log. The fallback then is a user-mode network stack in the app (libslirp or
gvisor-tap-vsock style) carrying Ethernet frames over virtio-vsock (`connectVsock`), with a vsock
link driver behind HackiOS's `redwood_net` in the guest.

**What Scion does:** Settings › Advanced › **Network** (per install, off by default). When it's
on, the app adds the INTERNET permission's use, calls `useNetwork(true)`, and shows a check line
for AVF's network feature. When it's off, the VM is exactly as before: no card.

**The guest side** (hackios-xnu-kernel, `bsd/dev/redwood_virtio_net.c`): HackiOS's virtio-net
driver runs on the virtio-pci transport. With boot-arg `virtio_net_wifi=1` (in the kernel repo's
`avf/scion/avf.properties`), en0 is presented to iOS as **Wi-Fi**, the way the phone build presents
its ath11k radio: RedwoodWLAN's IOKit entries, Apple80211 ioctls answered in the kernel, and a
virtual station that hears one open network, **"Scion"**, and joins it at boot. configd makes a
Wi-Fi network service and runs DHCP itself; Settings › Wi-Fi shows "Scion". Without the boot-arg
the card is a wired en0 that iOS doesn't configure by itself (HackiOS's `net_test dhcp en0`
does it on QEMU).

**Verified on QEMU (Mac, hvf, 2026-10-23):** `NET=1 avf/qemu-scfb.sh` (virtio-net-pci on
`-netdev user`) with the new kernel. iOS shows the Wi-Fi icon in the status bar, and Settings ›
Wi-Fi shows "Scion" ticked as an Unsecured Network. The DHCP lease is 10.0.2.15/24 with router
10.0.2.2, and Safari loads google.com over HTTPS. One requirement: configd has to be among
the launchd jobs. A root.apfs built without `REST=1` has no configd, so nothing runs DHCP.
`common/build.sh` builds with `REST=1`; also check `mkjobs.py --rest` writes one plist per job
(fixed in hackios-xnu-userland: before the fix, all the on-demand jobs were written to one file
named `RWA_PACKED16`).

**Turning it on** (needs a kernel with the virtual station: hackios-xnu-kernel main after the
`avf/net-wifi` merge):

1. Rebuild install 2's `payload.bin` from that kernel and push it with `avf/scion/install-ios165.sh`
   (it also pushes the new `avf.properties`). Or keep the old `avf.properties` and put
   `virtio_net_wifi=1` in the install's **Extra arguments**.
2. In Scion, pick install 2, open Advanced, turn on **Network**, start the VM.
3. Developer hook for one session: `--ez avf_network true` (with `--es backend avf --es vm_dir ...`).

Check the console for `net: virtio network device on ...`, `net: en0 attached ... (a radio)` and
`net: en0 is a radio (virtio_net_wifi=1): one open network, "Scion"`. On the Android side,
`adb shell ip addr` shows an `avf_tap_*` interface while the VM runs.


The emulated iPhone 11 (t8030, iOS 14.0) boots to SpringBoard on a Pixel 10, but
it has no network. Users report that "Wi-Fi doesn't work". This document covers
why, what each way of fixing it costs, and which one we're doing first.

**Goal:** the guest reaches the internet through the Pixel's connection, and
ideally iOS shows that as Wi-Fi.

**Recommendation:** do it in two phases.

1. **Wired first (about 2 weeks to the Pixel; already working on desktop).** Use the iPhone's own built-in USB reverse tether. QEMU
   acts as the USB host and puts the guest's USB port into Apple's "mode 3",
   which adds a CDC-NCM Ethernet function. It then bridges that link to QEMU's
   user-mode network (slirp), which supplies DHCP, DNS and NAT. iOS already
   uses this link as its route to the internet. Upstream Inferno users run the
   same thing today with a companion VM. On the phone, nothing in the guest
   changes; the work is on the host side. The catch: iOS shows it as a wired
   link, not as Wi-Fi.
2. **Fake Wi-Fi later (months), and only after a go/no-go spike.** Emulate enough
   of the BCM4378 that iOS joins one virtual network bridged to the same
   netdev. Only this makes Settings › Wi-Fi show as connected. It's about ten
   times the work of phase 1, and we have the least evidence about whether it's
   feasible.

**Bluetooth is out of scope.**

| Route | Shows as | Effort | Risk | Issues |
|---|---|---|---|---|
| USB NCM reverse tether, QEMU as host, slirp | wired link (no Wi-Fi icon) | **about 2 weeks** to a Pixel; works on desktop already (Inferno#21) | low | #79 #82 #83 |
| Fake Wi-Fi: emulated BCM4378 PCIe and firmware protocol | **Wi-Fi**, joined to one network | 2–3 week spike, then **2–4 more months** | high | #84 |
| USB host mode plus a QEMU `usb-net` adapter | "Ethernet" in Settings | months | high (Lightning accessory ID) | none: rejected |
| Paravirtual NIC kext | anything | months | very high | none: rejected |

## What's there today

* **Inferno removes the radios from the device tree.** `hw/arm/boot.c` deletes
  the `wlan`, `bluetooth-pcie` and `amfm` nodes (`REM_NAMES`,
  `REM_DEV_TYPES`). The `bluetooth,n88` entry in `KEEP_COMP` is commented out
  because it caused hangs; that's upstream commit 9bf632df5f. Nothing in the
  machine models the Broadcom chip, so iOS sees no Wi-Fi hardware at all.
  `t8030_create_misc()` still sets placeholder `mac-address-wifi0` and
  `mac-address-bluetooth0` values in `/chosen`.
* **The guest's USB port is the only external link.** `apple_typec` wraps a
  DWC3 in **device** mode; the iPhone acts as the USB device, as on a real
  phone. The machine's `usb-uplink-type` exports that device to a host:
  * `virtualhere` listens on TCP;
  * `inferno` connects to a unix socket. The app uses this since #76. Nothing
    listens on the socket during a normal boot.
* **The app has no INTERNET permission** (#67, #76), and the Android QEMU
  build has `-Dslirp=disabled`.

## The real hardware, and what the drivers expect

All of this is read from the iOS 14.0 (18A5351d) n104 device tree and the
research kernelcache.

**Wi-Fi and Bluetooth are one Broadcom BCM4378, on PCIe.**

```
arm-io/apcie/pci-bridge2            apcie-port 2, 8 MSI vectors, PERST/CLKREQ GPIOs
  ├─ wlan            fn 0   compatible "wlan-pcie,bcm4378", iommu-parent → dart-apcie2/mapper-apcie2-wlan
  └─ bluetooth-pcie  fn 1   compatible "wlan-pcie,bcm4378", iommu-parent → dart-apcie2/mapper-apcie2-bt
arm-io/wlan          property bag: module-instance "moana", MAC from syscfg/WMac,
                     wifi-calibration-msf syscfg/WCAL, ~40 wlan.*/bcom.* tunables
arm-io/bluetooth     compatible "bluetooth,n88", transport-encoding 7 (PCIe), MAC syscfg/BMac
```

The relevant kexts:

| Kext | Matches | Role |
|---|---|---|
| `AppleBCMWLANBusInterfacePCIe` | `IONameMatch wlan` on `AppleARMIODevice` | chip bring-up (`AppleBCMWLANChipManagerPCIe`, `AppleBCMWLANChipBackplane`: core enumeration, OTP, TCM), firmware download, msgbuf rings (`AppleBCMWLANPCIeTransmitFlowRing`, `...CompletionRing`), Skywalk data path |
| `BCMWLANFirmware4378_Hashstore` | `IOImageLoader`, consumer `AppleBCMWLANCore` | hashes that the firmware images, supplied through IOImageLoader, are checked against |
| `AppleBCMWLANCore` (+ `IO80211Family`, `IOSkywalkFamily`) | the bus interface | the 802.11 personality: about 850 symbols, plus join, scan, roam, power, AWDL and proximity managers. It drives the firmware with IOCTLs and iovars |
| `AppleBluetoothModule` + `AppleConvergedIPCOLYBTControl` | `arm-io/bluetooth`, then PCIe fn 1 | Bluetooth over Apple's ring-based "converged IPC" (HCI/ACL/SCO pipes, image doorbell for firmware) |

**Emulating Wi-Fi means writing the firmware's side of that bus.** We can't run
Broadcom's ARM firmware, because that needs the D11 MAC and the radio. Instead,
QEMU would model:

1. **A PCIe endpoint** on bridge 2: IDs, BARs, MSI, and a backplane window over
   a believable set of cores (chipcommon with an EROM, ARM CR4, PCIe2). That
   covers chip and revision IDs, OTP reads, and the "module" lookup that picks
   the firmware for `moana`.
2. **The firmware download:** TCM that takes the image, NVRAM and the random
   seed, then a released core that "boots" and publishes the shared structure
   at the end of RAM.
3. **msgbuf:** control submit/complete rings, rx-post, tx/rx completion,
   dynamically created flowrings, ring indices in TCM or host memory, and
   doorbells.
4. **An answer for every IOCTL and iovar** that `AppleBCMWLANCore` and `wifid`
   issue before they treat the interface as usable: version strings, CLM and
   country, power, the scan offload, roam profiles, and Apple-specific ones.
   Then events for a synthetic scan result and a successful join to one open
   network.
5. **The data path:** 802.3 frames in flowrings and rx completions, to and from
   a QEMU netdev.

The drivers are closed source. The only public reference is the Linux host
side, written by Asahi against the same chip family with real firmware:
brcmfmac's BCM4378 support, and `hci_bcm4377` for the Bluetooth ring transport.
We'd be writing the opposite side, without a chip to trace. **Estimate: 3–6
engineer-months to a stable association, high risk**, and it may need redoing
for any iOS version that changes the driver. A 2–3 week spike can turn most of
that risk into a yes or no (#84).

**Bluetooth: out of scope.** Android doesn't give apps raw HCI access to the
Pixel's controller, so pass-through is impossible. A fake controller with no
radio would only let the toggle turn on.

## Cheaper routes

### Chosen: the iPhone's built-in USB reverse tether

iOS's device-side USB stack has an Ethernet function: `AppleUSBDeviceNCM` and
`AppleUSBEthernetDevice`. The `usb-drd` node's `configuration-string` is
`stdMuxPTPEthValIDA`. A host enables it with Apple's vendor requests: GET_MODE
is `0x45` and SET_MODE is `0x52` with `wIndex = 3`. The device then
re-enumerates with a fifth configuration that has the usbmux interface and a
CDC-NCM function. usbmuxd does this when `USBMUXD_DEFAULT_DEVICE_MODE=3` is
set. Its source calls mode 3 "CDC NCM … activated using internet-sharing
feature in macOS": the real-device feature where a Mac shares its connection
*to* an iPhone over USB.

**It works on Inferno today, with a companion VM.** eShard's write-up
("Emulating an iPhone in QEMU, part II") and upstream discussions
[#72](https://github.com/ChefKissInc/Inferno/discussions/72) and
[#192](https://github.com/ChefKissInc/Inferno/discussions/192) describe the
setup: usbmuxd in mode 3, `cdc_ncm` in the companion's Linux, and dnsmasq or
NetworkManager "Shared" for DHCP, DNS and NAT. The guest gets a lease and
Safari connects. Nothing changes in the guest.

**What's different for us:** the phone has no companion VM, no Linux
`cdc_ncm`, and no usbmuxd. So QEMU has to be the host:

```
 iOS guest                 Inferno (QEMU, :vm process)                    Android
 ─────────                 ───────────────────────────                    ───────
 AppleUSBDeviceNCM ─ DWC3 ─ usb-uplink-type=net (#79)                     
   (config 5)               ├ mode 3 + SET_CONFIGURATION 5
                            ├ CDC-NCM: NTB16 ⇄ Ethernet frames ─ -netdev user (slirp, #82)
                            │                                     DHCP · DNS · NAT ─ sockets ─ Pixel's network
 lockdownd/usbmux ─────────┴ usbmux → unix socket (#80, optional: tools, SSH)
                                                                  app: Network setting (#83)
```

Most of the pieces already exist. `usb-uplink.c` already has the host-side
helpers: submit a transfer to the exported device, read and cache its
descriptors. QEMU's net layer and libslirp provide DHCP, DNS, NAT and
`hostfwd`, and slirp builds with meson and only needs glib. So the in-QEMU
host is protocol glue (mode switch, configuration, NTB16 framing) rather than
a new subsystem. It also gives desktop Inferno users networking without a
companion VM.

**Pairing turned out not to be needed.** eShard says the DHCP request comes
"as long as the phone was paired". In our measurement (below), iOS 14.0 took a
lease and went online with no pairing and no lockdown session. So the usbmux
socket (#80) and a pairing client (#81) aren't on the networking path. #80 is
still worth doing for `idevice*` tools and SSH.

**What the user sees:** network works for Safari and apps. Settings › Wi-Fi
still shows nothing, and the status bar has no Wi-Fi icon. Apps that check
specifically for Wi-Fi rather than for reachability will be unhappy. The App
Store, iCloud and activation still don't work; that's an Inferno limitation
and out of scope (`docs/ETHICS.md` §4).

### Rejected: iPhone as USB host plus an emulated USB Ethernet adapter

iOS 14 has the host-mode stack (`AppleUSBHostT8030`, `AppleUSBXHCIARM`) and
class drivers for adapters (`usb.cdc.ecm`, `usb.cdc.ncm`,
`usb.ethernet.asix`, `AppleUSBEthernetHost`). QEMU has `usb-net`
(CDC-ECM/RNDIS). This route would show "Ethernet" in Settings. But:

* Inferno's DWC3 resets into device mode and never switches `PRTCAPDIR` to
  host. The embedded xHCI exists only as registers.
* On a Lightning iPhone, host mode starts when the Tristar (`tristar,cbtl1612`
  on `smc-i2c1`; not modelled) identifies a camera-adapter accessory over the
  Lightning ID bus. Accessory identification involves Apple's
  authentication, which we can't and won't fake.
* While the port is a host it isn't a device, so restore and usbmux on the
  same port would break.

### Rejected: a paravirtual NIC driver in the guest

That means a kext against IONetworkingFamily/Skywalk's private KPIs, linked
into a sealed prelinked kernelcache, and an AMFI/kext-signing bypass to load
it. It also means extending the firmware patching, which this project leaves
to upstream's manual steps (`docs/ETHICS.md` §3). Same for a jailbroken guest
daemon using `IOUserEthernet` over a usbmux tunnel. Not doing either.

### Upstream's plans

ChefKissInc/Inferno has no networking code. Its answer is the companion VM
reverse tether above (discussions #72 and #192). There's an open issue,
[#248 "WiFi and Bluetooth (PCI-e)"](https://github.com/ChefKissInc/Inferno/issues/248),
with no implementation. Its one comment says something was needed "to make
bluetoothd stop going in a crash loop". Nothing upstream to wait for.

## Reaching Android without silently adding network access

slirp turns the guest's TCP and UDP into ordinary sockets in QEMU's process.
That's the app's UID, both in-process (`:vm`) and in the exec path, so it
needs `android.permission.INTERNET`. INTERNET is an install-time permission:
the manifest must declare it, it's granted at install, and stock Android has no
way to revoke it. A runtime setting can't add it.

**The plan (#83):** declare INTERNET (and `ACCESS_NETWORK_STATE`), add a
**Network setting that's off by default**, and keep the QEMU command line
byte-for-byte as it is today while it's off: `inferno` uplink, no `-netdev`. So
the guarantee changes from "can't reach the network" to "doesn't, unless you
turn Network on". README and ETHICS wording should say so.

If a build that *can't* reach the network is ever wanted: a product flavor
without the permission (about 1 day), or a helper APK that holds INTERNET and
gets frames through a socketpair fd passed over Binder (about 1 week).

**Android-specific gotcha (#82):** libslirp finds the upstream resolver by
reading `/etc/resolv.conf`, which Android doesn't have. The app has to pass the
resolver from `LinkProperties`, or DNS has to go through a proxy in the app.
`-netdev user,restrict=on` (DHCP and guest-to-host only) needs no permission,
which is handy for testing.

## Measured (M1, desktop)

Inferno [#21](https://github.com/Scion-Project/pyre/pull/21) adds
`usb-uplink-type=net`. For that type, `usb-uplink-addr` names the netdev:

```
-netdev user,id=n0 -M t8030,...,usb-uplink-type=net,usb-uplink-addr=n0
```

Run on macOS: iOS 14.0 (18A5351d), n104, `-snapshot`, no companion VM, no
pairing.

```
usb-net-uplink: device 05ac:12a8 "iPhone", 4 configurations
usb-net-uplink: switching the device to mode 3 (CDC-NCM)
usb-net-uplink: device 05ac:12a8 "iPhone", 5 configurations
usb-net-uplink: CDC-NCM up (config 5, control 2, data 3/1, in 0x87 out 0x05, NTB in 12144 out 12144)
usb-net-uplink: guest reports network connected
```

* The guest builds "PTP + Apple Mobile Device + NCM" and registers
  `AppleUSBNCMControl`/`AppleUSBNCMData`.
* slirp's `info usernet` shows the guest at 10.0.2.15, so it took the DHCP
  lease. It sends DNS to 10.0.2.3 and opens TCP/443 connections to Apple's
  servers.
* **No pairing and no lockdown session were needed.**

Not measured yet: throughput (the first version sends one frame per NTB and
polls endpoints that return NAK) and Safari. This boot stayed on the
first-boot progress bar, though networking was already up.

## Milestones

**M1: wired networking on desktop (#79): done in Inferno #21**, pending
review. The remaining work there: batch frames per NTB for throughput.

**M2: on the Pixel (#82, #83). About 1–2 weeks.** libslirp in the Android
build with the DNS fix, and the Network setting that passes
`-netdev user,id=n0` and `usb-uplink-type=net,usb-uplink-addr=n0`.

**M3: fake Wi-Fi spike (#84). 2–3 weeks, time-boxed.** A BCM4378 PCIe endpoint
that gets `AppleBCMWLANBusInterfacePCIe` through firmware download. Go or
no-go for the 2–4 further months of full fake Wi-Fi, which would reuse M1's
netdev plumbing.
