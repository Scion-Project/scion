package io.github.dylanneve1.iosonpixel

import android.content.Context
import android.content.pm.ApplicationInfo
import io.github.dylanneve1.iosonpixel.rfb.RfbClient
import java.io.File
import java.io.FileWriter
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Software emulation: our Inferno build runs as a child process (or, with
 * [VmSettings.inProcess], as a library in the :vm process, see
 * [QemuInProcess]), its display is QEMU's VNC server on a unix socket (read
 * by [RfbClient]), the buttons go through the human monitor ([Monitor]) and
 * multi-touch goes through QMP ([QmpTouch]).
 */
class QemuBackend(ctx: Context, private val install: Install) : VmBackend {
    private val app = ctx.applicationContext
    private val paths = AppPaths(app, install.id)
    private val dir get() = install.dir

    override val kind = Backend.QEMU

    private val inProcess get() = install.settings.inProcess
    private val libOk get() = paths.qemuLib.isFile && paths.qemuJni.isFile

    override fun checks(): List<VmBackend.Check> {
        val vm = VmLayout(dir)
        val problems = vm.problems()
        val qemuOk = paths.qemu.isFile
        return buildList {
            add(
                if (inProcess) {
                    if (libOk) VmBackend.Check(true, "QEMU library bundled (in-process)")
                    else VmBackend.Check(false, "QEMU library for in-process mode missing (run native/build-android.sh)")
                } else if (qemuOk) VmBackend.Check(true, "QEMU binary bundled")
                else VmBackend.Check(false, "QEMU binary missing (run native/build-android.sh)"),
            )
            if (problems.isEmpty()) add(VmBackend.Check(true, "VM folder looks complete"))
            else problems.forEach { add(VmBackend.Check(false, it)) }
            vm.ramdisk?.takeIf { it.isFile }?.let { add(VmBackend.Check(true, "ramdisk ${it.name} (passed as -initrd)")) }
        }
    }

    override fun problems(): List<String> = VmLayout(dir).problems() + listOfNotNull(
        if (inProcess) "QEMU library for in-process mode not bundled in this build".takeIf { !libOk }
        else "QEMU binary not bundled in this build".takeIf { !paths.qemu.isFile },
    )

    override fun start(settings: VmSettings, log: (String) -> Unit): VmBackend.Instance? {
        val vm = VmLayout(dir)
        paths.prepare()
        installDataFiles()
        paths.vncSocket.delete()
        paths.monitorSocket.delete()
        paths.qmpSocket.delete()

        val extra = QemuCommand.extraArgs(app)
        if (extra.isNotEmpty()) log("debug: ${extra.size} extra QEMU args appended from files/qemu-extra-args.txt")
        val cmd = QemuCommand.build(paths, vm, settings) + settings.extraArgList + extra
        val network = QemuCommand.network(vm, settings)
        when {
            network -> log("network: on (slirp behind the guest's USB link)")
            settings.qemuNetwork -> log("network: restore boot, so the Network setting is not used")
        }
        log("$ " + cmd.joinToString(" "))
        VmState.network(QemuNet.statusText(settings.qemuNetwork, QemuCommand.restoreBoot(vm), connected = false, address = null))

        // With the Network setting on, slirp reads the phone's DNS servers from this file (#82).
        val resolver = if (network) QemuNet.Resolver(app, paths, log).also { it.start() } else null
        val env = buildMap {
            put("TMPDIR", paths.tmpDir.absolutePath)
            if (settings.effectiveVcpuAffinity.isNotBlank()) put("QEMU_VCPU_AFFINITY", settings.effectiveVcpuAffinity)
            if (resolver != null) put(QemuNet.ENV_RESOLV_CONF, paths.resolvConf.absolutePath)
        }
        fun watched(i: VmBackend.Instance): VmBackend.Instance =
            if (resolver != null) QemuNet.Watch(i, paths, resolver) else i
        fun failed(message: String): VmBackend.Instance? {
            log(message)
            resolver?.stop()
            VmState.network(null)
            return null
        }

        if (settings.inProcess) {
            val vmProcess = QemuInProcess(app, paths, cmd, vm.dir.absolutePath, env)
            if (vmProcess.bind()) return watched(vmProcess)
            return failed("error: failed to start QEMU: cannot bind the :vm service")
        }

        val pb = ProcessBuilder(cmd).directory(vm.dir).redirectErrorStream(true)
        pb.environment().putAll(env)

        return try {
            watched(Instance(pb.start()))
        } catch (e: Exception) {
            failed("error: failed to start QEMU: $e")
        }
    }

    override fun openDisplay(listener: VmDisplay.Listener): VmDisplay = RfbClient(paths.vncSocket, listener)

    override fun openTouch(): VmTouch = QmpTouch(paths)

    /** In-process the VM draws into the app's Surface (-display android); the child process uses VNC. */
    override fun surfaceDisplay(): SurfaceDisplay? = if (inProcess) QemuInProcess else null

    override fun press(button: HardwareButton, holdMs: Int) {
        if (!inProcess) return Monitor.press(paths, key(button), holdMs)
        // Through QEMU's input layer, like the monitor's sendkey with a hold time.
        keys.execute {
            QemuInProcess.key(key(button), true)
            Thread.sleep(holdMs.toLong())
            QemuInProcess.key(key(button), false)
        }
    }

    /** Inferno maps the buttons to F-keys (see its "Using the Device's Buttons"). */
    private fun key(button: HardwareButton) = when (button) {
        HardwareButton.POWER -> "f5"
        HardwareButton.HOME -> "f6"
        HardwareButton.VOLUME_DOWN -> "f3"
        HardwareButton.VOLUME_UP -> "f4"
        HardwareButton.RINGER -> "f2"
    }

    private inner class Instance(private val p: Process) : VmBackend.Instance {
        override val isAlive get() = p.isAlive

        override fun pump(onLine: (String) -> Unit): Int {
            FileWriter(paths.logFile, false).use { log ->
                p.inputStream.bufferedReader().forEachLine { line ->
                    log.write(line); log.write("\n"); log.flush()
                    onLine(line)
                }
            }
            return p.waitFor()
        }

        override fun stop() {
            // Ask QEMU to quit cleanly first so disk images are flushed.
            Monitor(paths).send("quit")
            if (!p.waitFor(10, TimeUnit.SECONDS)) {
                p.destroy()
                if (!p.waitFor(5, TimeUnit.SECONDS)) p.destroyForcibly()
            }
        }

        override fun kill() = p.destroy()
    }

    /** Copies keymaps etc. from assets so QEMU's -L data dir is populated. */
    private fun installDataFiles() {
        val assets = app.assets
        fun copy(assetPath: String, dest: File) {
            val children = assets.list(assetPath).orEmpty()
            if (children.isEmpty()) {
                dest.parentFile?.mkdirs()
                assets.open(assetPath).use { input -> dest.outputStream().use { input.copyTo(it) } }
            } else {
                children.forEach { copy("$assetPath/$it", File(dest, it)) }
            }
        }
        if (assets.list("qemu-data").orEmpty().isNotEmpty()) copy("qemu-data", paths.dataDir)
    }

    private companion object {
        /** Presses in order, one at a time (Apps is two Home presses). */
        val keys: ExecutorService = Executors.newSingleThreadExecutor()
    }
}

object QemuCommand {
    /**
     * Debug hook: whitespace-separated tokens from files/qemu-extra-args.txt (no quoting, no
     * comments), appended to the command line. Debuggable builds only; release builds ignore it.
     */
    fun extraArgs(ctx: Context): List<String> {
        if ((ctx.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) == 0) return emptyList()
        val f = File(ctx.filesDir, "qemu-extra-args.txt")
        if (!f.isFile) return emptyList()
        return runCatching { f.readText().split(Regex("\\s+")).filter { it.isNotEmpty() } }.getOrDefault(emptyList())
    }

    /** The slirp netdev's id; the machine's net uplink names it. */
    const val NETDEV_ID = "n0"

    /** A restore boot: the VM folder names a restore ramdisk (passed as -initrd). */
    fun restoreBoot(vm: VmLayout): Boolean = vm.ramdisk?.isFile == true

    /**
     * Whether this boot gets the guest network: the Network setting is on and it is a normal boot.
     * Restore boots always keep the inferno uplink, which restore tools talk to.
     */
    fun network(vm: VmLayout, s: VmSettings): Boolean = s.qemuNetwork && !restoreBoot(vm)

    /** Mirrors the sample command from the Inferno guide, minus host-only display flags. */
    fun build(paths: AppPaths, vm: VmLayout, s: VmSettings): List<String> {
        fun path(key: String) = vm.file(key)!!.absolutePath
        val network = network(vm, s)
        val uplink = if (network) listOf(
            // The guest's USB NCM link, bridged to the slirp netdev below (Inferno's
            // usb-uplink-type=net: QEMU is the USB host and puts the iPhone in mode 3).
            "usb-uplink-type=net",
            "usb-uplink-addr=$NETDEV_ID",
        ) else listOf(
            // Connect out over a unix socket (retried quietly while nothing
            // listens) instead of the default TCP server, which needs INTERNET.
            "usb-uplink-type=inferno",
            "usb-uplink-addr=unix:${paths.usbUplinkSocket.absolutePath}",
        )
        val machine = (
            listOf(
                "t8030",
                "trustcache=${path("trustcache")}",
                "sep-fw=${path("sep_fw")}",
                "sep-rom=${path("sep_rom")}",
                "kaslr-off=true",
            ) + uplink
            ).joinToString(",")

        val accel = buildString {
            append("tcg,thread=multi,tb-size=").append(s.tbSizeMb)
            if (s.splitWx) append(",split-wx=on")
        }

        // In-process the VM draws into the app's Surface; the child process serves VNC.
        val display = if (s.inProcess) listOf("-display", "android")
        else listOf("-display", "none", "-vnc", "unix:${paths.vncSocket.absolutePath}")

        val args = mutableListOf(
            paths.qemu.absolutePath,
            "-L", paths.dataDir.absolutePath,
            "-M", machine,
            "-accel", accel,
            "-kernel", path("kernel"),
            "-dtb", path("dtb"),
        )
        // Optional restore ramdisk; without the key the command is unchanged.
        vm.ramdisk?.takeIf { it.isFile }?.let { args += listOf("-initrd", it.absolutePath) }
        args += listOf(
            "-append", "tlto_us=-1 mtxspin=-1 agm-genuine=1 agm-authentic=1 agm-trusted=1 " +
                "serial=3 wdt=-1 -vm_compressor_wk_sw",
            "-smp", s.smp.toString(),
            "-m", "${s.ramGb}G",
            *display.toTypedArray(),
            "-monitor", "unix:${paths.monitorSocket.absolutePath},server=on,wait=off",
            "-qmp", "unix:${paths.qmpSocket.absolutePath},server=on,wait=off",
            "-serial", "stdio",
            // Shared storage is FUSE-backed; OFD locks there are unreliable.
            "-drive", "file=sep_nvram,if=pflash,format=raw,file.locking=off",
            "-drive", "file=sep_ssc,if=pflash,format=raw,file.locking=off",
        )
        // User-mode networking: DHCP, DNS and NAT in QEMU, through ordinary sockets in the app's
        // UID. Only with the Network setting on; off, there is no netdev at all.
        if (network) args += listOf("-netdev", "user,id=$NETDEV_ID")

        fun nvme(id: String, file: String, format: String, device: String, nsid: Int, nstype: Int, extra: String = "") {
            args += listOf(
                "-drive", "file=$file,format=$format,if=none,id=$id,file.locking=off$extra",
                // Inferno dev looks namespaces up by id (e.g. peripheral/firmware).
                "-device", "$device,drive=$id,bus=nvme-bus.0,nsid=$nsid,nstype=$nstype,id=$id," +
                    "logical_block_size=4096,physical_block_size=4096",
            )
        }
        // Let guest TRIM (NVMe deallocate) and zero writes free qcow2 clusters, so the
        // image can shrink again (#56). If the host file system can't punch holes
        // (possibly shared storage's FUSE layer), QEMU just keeps the space.
        val reclaim = if (vm.rootFormat == "qcow2") ",discard=unmap,detect-zeroes=unmap" else ""
        nvme("root", vm.file("root")!!.name, vm.rootFormat, "nvme-ns", 1, 1, reclaim)
        for (ns in VmLayout.SMALL_DISKS) nvme(ns.name, ns.name, "raw", ns.device, ns.nsid, ns.nstype)
        return args
    }
}
