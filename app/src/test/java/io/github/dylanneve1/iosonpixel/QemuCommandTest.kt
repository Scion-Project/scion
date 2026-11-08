package io.github.dylanneve1.iosonpixel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.net.InetAddress

/**
 * The QEMU command line. With the Network setting off it must stay exactly what it was before
 * the setting existed: the inferno uplink on the unix socket and no -netdev (#83).
 */
class QemuCommandTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun paths(): AppPaths {
        val files = tmp.newFolder("files")
        return AppPaths(files, tmp.newFolder("cache"), tmp.newFolder("external"), "/lib/arm64", "1")
    }

    /** A VM folder with every file [VmLayout] wants, optionally with a restore ramdisk. */
    private fun vm(ramdisk: Boolean = false): VmLayout {
        val dir = tmp.newFolder("vm")
        val names = mapOf(
            "kernel" to "kernelcache", "dtb" to "devicetree", "trustcache" to "trustcache",
            "sep_fw" to "sep_fw", "sep_rom" to "sep_rom", "root" to "root.qcow2",
        )
        val props = names.entries.joinToString("\n") { "${it.key}=${it.value}" } +
            "\nroot_format=qcow2\n" + (if (ramdisk) "ramdisk=ramdisk.img\n" else "")
        File(dir, VmLayout.MANIFEST).writeText(props)
        (names.values + listOfNotNull("ramdisk.img".takeIf { ramdisk })).forEach { File(dir, it).writeText("x") }
        return VmLayout(dir)
    }

    /** What QemuCommand.build produced before the Network setting, for [s] (child process, no ramdisk). */
    private fun before(p: AppPaths, vm: VmLayout, s: VmSettings): List<String> {
        fun f(name: String) = File(vm.dir, name).absolutePath
        val args = mutableListOf(
            p.qemu.absolutePath,
            "-L", p.dataDir.absolutePath,
            "-M", "t8030,trustcache=${f("trustcache")},sep-fw=${f("sep_fw")},sep-rom=${f("sep_rom")},kaslr-off=true," +
                "usb-uplink-type=inferno,usb-uplink-addr=unix:${p.usbUplinkSocket.absolutePath}",
            "-accel", "tcg,thread=multi,tb-size=${s.tbSizeMb}",
            "-kernel", f("kernelcache"),
            "-dtb", f("devicetree"),
            "-append", "tlto_us=-1 mtxspin=-1 agm-genuine=1 agm-authentic=1 agm-trusted=1 serial=3 wdt=-1 -vm_compressor_wk_sw",
            "-smp", s.smp.toString(),
            "-m", "${s.ramGb}G",
            "-display", "none", "-vnc", "unix:${p.vncSocket.absolutePath}",
            "-monitor", "unix:${p.monitorSocket.absolutePath},server=on,wait=off",
            "-qmp", "unix:${p.qmpSocket.absolutePath},server=on,wait=off",
            "-serial", "stdio",
            "-drive", "file=sep_nvram,if=pflash,format=raw,file.locking=off",
            "-drive", "file=sep_ssc,if=pflash,format=raw,file.locking=off",
            "-drive", "file=root.qcow2,format=qcow2,if=none,id=root,file.locking=off,discard=unmap,detect-zeroes=unmap",
            "-device", "nvme-ns,drive=root,bus=nvme-bus.0,nsid=1,nstype=1,id=root,logical_block_size=4096,physical_block_size=4096",
        )
        for (ns in VmLayout.SMALL_DISKS) args += listOf(
            "-drive", "file=${ns.name},format=raw,if=none,id=${ns.name},file.locking=off",
            "-device", "${ns.device},drive=${ns.name},bus=nvme-bus.0,nsid=${ns.nsid},nstype=${ns.nstype},id=${ns.name}," +
                "logical_block_size=4096,physical_block_size=4096",
        )
        return args
    }

    @Test fun networkOffIsTheCommandLineFromBefore() {
        val p = paths()
        val vm = vm()
        val s = VmSettings()
        val cmd = QemuCommand.build(p, vm, s)
        assertEquals(before(p, vm, s), cmd)
        assertFalse(cmd.any { it.startsWith("-netdev") || it.startsWith("-nic") || it.startsWith("-net") })
        assertTrue(cmd.any { "usb-uplink-type=inferno" in it })
    }

    @Test fun networkOffWithEverythingElseOnHasNoNetdev() {
        val s = VmSettings(inProcess = true, splitWx = true, extraArgs = "-d guest_errors")
        val cmd = QemuCommand.build(paths(), vm(ramdisk = true), s)
        assertFalse(cmd.any { it.startsWith("-netdev") })
        assertFalse(cmd.any { "usb-uplink-type=net," in it })
    }

    @Test fun networkOnAddsSlirpAndTheNetUplink() {
        val p = paths()
        val vm = vm()
        val off = QemuCommand.build(p, vm, VmSettings())
        val on = QemuCommand.build(p, vm, VmSettings(qemuNetwork = true))
        val machine = on[on.indexOf("-M") + 1]
        assertTrue(machine.endsWith(",kaslr-off=true,usb-uplink-type=net,usb-uplink-addr=n0"))
        assertFalse("usb-uplink-type=inferno" in machine)
        val i = on.indexOf("-netdev")
        assertEquals("user,id=n0", on[i + 1])
        // Nothing else changes.
        assertEquals(off.filter { !it.startsWith("t8030,") }, on.filterIndexed { n, it -> !it.startsWith("t8030,") && n != i && n != i + 1 })
    }

    @Test fun restoreBootsKeepTheInfernoUplink() {
        val p = paths()
        val vm = vm(ramdisk = true)
        val s = VmSettings(qemuNetwork = true)
        assertTrue(QemuCommand.restoreBoot(vm))
        assertFalse(QemuCommand.network(vm, s))
        val cmd = QemuCommand.build(p, vm, s)
        assertEquals(cmd, QemuCommand.build(p, vm, VmSettings()))
        assertTrue(cmd[cmd.indexOf("-M") + 1].endsWith("usb-uplink-type=inferno,usb-uplink-addr=unix:${p.usbUplinkSocket.absolutePath}"))
        assertFalse(cmd.any { it.startsWith("-netdev") })
    }

    @Test fun resolvConfListsTheServers() {
        val v4 = InetAddress.getByName("192.168.1.1")
        val v6 = InetAddress.getByName("2001:4860:4860::8888")
        val ll = InetAddress.getByName("fe80::1")
        assertEquals(
            "nameserver 192.168.1.1\nnameserver 2001:4860:4860:0:0:0:0:8888\nnameserver fe80:0:0:0:0:0:0:1%wlan0\n",
            QemuNet.resolvConf(listOf(v4, v6, ll), "wlan0"),
        )
        // No servers: no file, never an empty one (slirp would fall back to 127.0.0.1).
        assertEquals(null, QemuNet.resolvConf(emptyList(), "wlan0"))
    }

    @Test fun guestAddressSkipsSlirpsOwn() {
        val usernet = """
            Hub -1 (n0):
              Protocol[State]    FD  Source Address  Port   Dest. Address  Port RecvQ SendQ
              UDP[236 sec]       42        10.0.2.3    53       10.0.2.15 62000     0     0
              TCP[ESTABLISHED]   45  192.168.1.20 40000       10.0.2.15 49152     0     0
        """.trimIndent()
        assertEquals("10.0.2.15", QemuNet.guestAddress(usernet))
        assertEquals(null, QemuNet.guestAddress("Hub -1 (n0):\n  Protocol[State] FD Source Address\n"))
    }

    @Test fun statusLine() {
        assertEquals("Network: off", QemuNet.statusText(on = false, restore = false, connected = false, address = null))
        assertEquals("Network: waiting for guest", QemuNet.statusText(on = true, restore = false, connected = false, address = null))
        assertEquals("Network: guest has 10.0.2.15", QemuNet.statusText(on = true, restore = false, connected = true, address = "10.0.2.15"))
        assertEquals("Network: off for restore boots", QemuNet.statusText(on = true, restore = true, connected = false, address = null))
    }

    @Test fun settingIsOffByDefaultAndPersists() {
        assertFalse(VmSettings().qemuNetwork)
        val i = Install(id = "2", name = "x", internalDir = "/a", sharedDir = "/b", internal = true, settings = VmSettings(qemuNetwork = true))
        assertEquals(true, InstallMigration.decode(InstallMigration.encode(listOf(i)))!!.single().settings.qemuNetwork)
        // Lists written before the setting existed read as off.
        val old = InstallMigration.encode(listOf(i.copy(settings = VmSettings()))).replace(",\"qemu_network\":false", "")
        assertFalse("qemu_network" in old)
        assertFalse(InstallMigration.decode(old)!!.single().settings.qemuNetwork)
        // Per install: not in the single-VM app's preferences, so it never triggers a resync.
        assertEquals(InstallMigration.seen(VmSettings(), true), InstallMigration.seen(VmSettings(qemuNetwork = true), true))
    }
}
