package io.github.dylanneve1.iosonpixel

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import io.github.dylanneve1.iosonpixel.avf.AvfApi
import io.github.dylanneve1.iosonpixel.avf.AvfException
import io.github.dylanneve1.iosonpixel.avf.AvfIo
import java.io.File
import java.io.FileWriter
import java.io.InputStream
import java.util.Properties
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Hardware virtualization: a non-protected custom-kernel VM under the Android Virtualization
 * Framework (pKVM, with crosvm run by virtmgr), started through [AvfApi]. The guest is described
 * by avf.properties in the VM folder ([AvfLayout]). Its console goes to the app's output view and
 * to avf-console.log; its display and input go through [io] (headless for now, see [AvfIo]).
 *
 * Needs a one-time adb setup, which [AvfSupport.checks] spells out when something is missing.
 *
 * [vmDir] overrides the VM folder (the debug-build developer hook, see MainActivity).
 */
class AvfBackend(
    ctx: Context,
    private val vmDir: File? = null,
    private val io: AvfIo = AvfIo.None,
) : VmBackend {
    private val app = ctx.applicationContext
    private val paths = AppPaths(app)
    /** Read on each use: a move to internal storage changes [AppPaths.vmDir]. */
    private val dir get() = vmDir ?: paths.vmDir

    override val kind = Backend.AVF

    override val hasDisplay get() = io.hasDisplay

    override fun checks(): List<VmBackend.Check> = AvfSupport.checks(app) + AvfLayout(dir).checks()

    override fun problems(): List<String> = checks().filter { !it.ok }.map { it.text }

    override fun start(settings: VmSettings, log: (String) -> Unit): VmBackend.Instance? {
        val spec = AvfLayout(dir).spec(settings)
        log("$ avf " + describe(spec))
        return AvfVm.start(app, spec, io, paths.avfLogFile, log)
    }

    /** Only reached when [hasDisplay] is false, i.e. headless: say so instead of connecting. */
    override fun openDisplay(listener: VmDisplay.Listener): VmDisplay = object : VmDisplay {
        override fun start() = listener.onDisconnected(
            IllegalStateException("this AVF guest has no display yet; its console is in the app's output view"),
        )
        override fun close() = Unit
        override fun pointer(x: Int, y: Int, pressed: Boolean) = Unit
    }

    override fun surfaceDisplay(): SurfaceDisplay? = AvfVm.current?.screen

    override fun press(button: HardwareButton, holdMs: Int) {
        val screen = AvfVm.current?.screen ?: return
        buttons.execute {
            screen.button(button, true)
            Thread.sleep(holdMs.toLong())
            screen.button(button, false)
        }
    }

    private fun describe(s: AvfApi.Spec) = buildString {
        append("kernel=").append(s.kernel)
        s.initrd?.let { append(" initrd=").append(it) }
        for (d in s.disks) append(" disk=").append(d.path).append(if (d.writable) ":rw" else ":ro")
        append(" mem=").append(s.memoryBytes / (1024 * 1024)).append("M")
        append(" cpus=").append(if (s.matchHostCpus) "host" else "1")
        append(" params=\"").append(s.params.joinToString(" ")).append('"')
    }

    private companion object {
        /** Presses in order, one at a time. */
        val buttons: ExecutorService = Executors.newSingleThreadExecutor()
    }
}

/**
 * Whether this device and app can run an AVF custom VM at all, as main-screen checks. Each
 * failing line says how to fix it, with the exact adb command where one exists.
 */
object AvfSupport {
    fun checks(ctx: Context): List<VmBackend.Check> {
        val pkg = ctx.packageName
        if (Build.VERSION.SDK_INT < 34) {
            return listOf(VmBackend.Check(false, "AVF needs Android 14 or later; this is Android ${Build.VERSION.RELEASE}"))
        }
        if (!ctx.packageManager.hasSystemFeature(AvfApi.FEATURE)) {
            return listOf(VmBackend.Check(false, "This device has no Android Virtualization Framework (${AvfApi.FEATURE} missing)"))
        }
        val api = AvfApi.instance
        if (!api.present) return listOf(VmBackend.Check(false, "AVF framework classes missing from this Android build"))

        val out = mutableListOf(VmBackend.Check(true, "Android Virtualization Framework present"))
        val granted = { p: String -> ctx.checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED }
        for (p in listOf(AvfApi.PERM_MANAGE, AvfApi.PERM_CUSTOM)) {
            val name = p.substringAfterLast('.')
            out += if (granted(p)) VmBackend.Check(true, "$name granted")
            else VmBackend.Check(false, "$name not granted. Grant it over adb:\n  adb shell pm grant $pkg $p")
        }
        out += if (api.blocked.isEmpty()) {
            VmBackend.Check(true, "Custom-VM API reachable (hidden-API exemption set)" +
                if (api.disksSupported) "" else "; disk images are still blocked, set the exemption below")
        } else {
            VmBackend.Check(false, "Custom-VM API blocked by Android's hidden-API policy (" +
                api.blocked.joinToString(limit = 3) { it.key } + "). Allow it over adb, then restart the app. " +
                "This is a global setting:\n  ${exemptionCommand()}\n  adb shell am force-stop $pkg")
        }
        if (granted(AvfApi.PERM_MANAGE)) {
            val caps = runCatching { api.manager(ctx)?.let { api.capabilities(it) } }.getOrNull()
            out += when {
                caps == null -> VmBackend.Check(false, "VirtualMachineManager unavailable on this device")
                caps and AvfApi.CAPABILITY_NON_PROTECTED_VM != 0 -> VmBackend.Check(true, "Hypervisor runs non-protected VMs")
                else -> VmBackend.Check(false, "Hypervisor offers no non-protected VMs (capabilities 0x${caps.toString(16)})")
            }
        }
        return out
    }

    /** Host-side command that sets the exemption ([AvfApi.EXEMPTIONS]), quoted for a POSIX shell. */
    fun exemptionCommand(): String =
        "adb shell settings put global hidden_api_blacklist_exemptions \"'" +
            AvfApi.EXEMPTIONS.joinToString(",").replace("$", "\\$") + "'\""
}

/**
 * An AVF guest, described by `avf.properties` in the VM folder. It sits next to QEMU's
 * vm.properties, so one folder can hold both and share disk images. Paths are relative to the
 * folder:
 *
 * ```
 * kernel=Image              # required: the kernel Image (for iOS, the open-source shim)
 * initrd=initrd.img         # optional: initrd / payload
 * params=console=hvc0 ...   # optional kernel command line (default DEFAULT_PARAMS);
 *                           #   security=<level> is always added from the Security setting
 * disk.0=root.img           # optional virtio-blk disks, in index order (vda, vdb, ...)
 * disk.0.writable=true      #   read-only unless writable=true
 * memory_mib=2048           # optional guest RAM; default: the Guest RAM setting
 * cpus=host                 # optional, 1 or host; default: 1 if the vCPUs setting is 1, else host
 * ```
 */
class AvfLayout(val dir: File) {
    private val manifest = File(dir, MANIFEST)
    private val props = Properties().apply {
        if (manifest.isFile) runCatching { manifest.inputStream().use { load(it) } }
    }

    private fun prop(key: String): String? = props.getProperty(key)?.trim()?.takeIf { it.isNotEmpty() }
    private fun file(key: String): File? = prop(key)?.let { File(dir, it) }

    val kernel: File? get() = file("kernel")
    val initrd: File? get() = file("initrd")
    val params: List<String> get() = (prop("params") ?: DEFAULT_PARAMS).split(Regex("\\s+")).filter { it.isNotEmpty() }
    val memoryMib: Long? get() = prop("memory_mib")?.toLongOrNull()
    val cpus: String? get() = prop("cpus")

    data class Disk(val file: File, val writable: Boolean)

    val disks: List<Disk>
        get() = props.stringPropertyNames().mapNotNull { Regex("""disk\.(\d+)""").matchEntire(it)?.groupValues?.get(1)?.toInt() }
            .sorted().mapNotNull { i ->
                file("disk.$i")?.let { Disk(it, prop("disk.$i.writable").toBoolean()) }
            }

    /** Readiness lines: the manifest and every file it names. */
    fun checks(): List<VmBackend.Check> {
        if (!manifest.isFile) {
            return listOf(VmBackend.Check(false,
                "missing $MANIFEST in ${dir.path}. It names the guest's files, at least:\n  kernel=Image\n  initrd=initrd.img"))
        }
        val out = mutableListOf<VmBackend.Check>()
        val k = kernel
        if (k == null) out += VmBackend.Check(false, "$MANIFEST has no 'kernel'")
        else out += fileCheck("kernel", k, false)
        initrd?.let { out += fileCheck("initrd", it, false) }
        val d = disks
        d.forEachIndexed { i, disk -> out += fileCheck("disk $i (${if (disk.writable) "rw" else "ro"})", disk.file, disk.writable) }
        if (d.isNotEmpty() && AvfApi.instance.present && !AvfApi.instance.disksSupported) {
            out += VmBackend.Check(false, "Disk images need the wider hidden-API exemption:\n  ${AvfSupport.exemptionCommand()}")
        }
        prop("cpus")?.takeIf { it != "1" && it != "host" }?.let { out += VmBackend.Check(false, "$MANIFEST: cpus must be 1 or host, not '$it'") }
        prop("memory_mib")?.takeIf { (it.toLongOrNull() ?: 0) <= 0 }?.let { out += VmBackend.Check(false, "$MANIFEST: memory_mib must be a positive number, not '$it'") }
        return out
    }

    private fun fileCheck(what: String, f: File, writable: Boolean): VmBackend.Check = when {
        !f.isFile -> VmBackend.Check(false, "missing file for $what: ${f.name}")
        // A file adb created in a folder adb also created belongs to shell, and the app can't open it.
        !f.canRead() || (writable && !f.canWrite()) -> VmBackend.Check(false,
            "the app can't ${if (writable) "write" else "read"} ${f.name} ($what). It is probably owned by adb's shell user: " +
                "push into a folder the app created (open the app once first), or copy it in with run-as")
        else -> VmBackend.Check(true, "$what: ${f.name} (${f.length() / 1024} KiB)")
    }

    /** What to boot with [s]; call only when [checks] pass. */
    fun spec(s: VmSettings): AvfApi.Spec = AvfApi.Spec(
        name = AvfVm.NAME,
        kernel = kernel!!.absolutePath,
        initrd = initrd?.absolutePath,
        // The setting wins over any security= in the folder's params.
        params = params.filter { !it.startsWith("${Security.BOOT_ARG}=") } + s.security.bootArg,
        disks = disks.map { AvfApi.Disk(it.file.absolutePath, it.writable) },
        memoryBytes = (memoryMib ?: (s.ramGb * 1024L)) * 1024 * 1024,
        matchHostCpus = cpus?.let { it == "host" } ?: (s.smp > 1),
    )

    companion object {
        const val MANIFEST = "avf.properties"

        /**
         * hvc0 is a virtio-console fed to the same output as ttyS0 (crosvm.rs); earlycon catches
         * what comes before virtio is up. From the AVF probe.
         */
        const val DEFAULT_PARAMS = "console=hvc0 earlycon=uart8250,mmio,0x3f8 panic=-1"
    }
}

/**
 * One AVF VM run, as a [VmBackend.Instance]. Its state, logged as it changes:
 *
 * ```
 * CREATING -> STARTING -> RUNNING -> STOPPING -> STOPPED
 * ```
 * Any state can go straight to STOPPED: a failed build/create/run, or the guest powering off
 * or crashing (onStopped). VmService's STARTING covers CREATING..STARTING, since [start] returns
 * only once `run()` has; its RUNNING and STOPPING are ours.
 *
 * [stop] is crosvm's hard stop (AVF has no ACPI-style request for custom VMs); a clean guest
 * shutdown request belongs in [AvfIo] (e.g. the power button) once input exists.
 */
class AvfVm private constructor(
    private val api: AvfApi,
    private val manager: Any,
    private val io: AvfIo,
    private val logFile: File,
    private val log: (String) -> Unit,
) : VmBackend.Instance {
    enum class State { CREATING, STARTING, RUNNING, STOPPING, STOPPED }

    @Volatile var state = State.CREATING
        private set

    /** The guest's screen while running, if [io] provides one. */
    @Volatile var screen: AvfIo.Screen? = null
        private set

    private var vm: AvfApi.Vm? = null
    private var console: InputStream? = null
    private var vmLog: InputStream? = null
    private val callbacks: ExecutorService = Executors.newSingleThreadExecutor { r -> Thread(r, "avf-callback").apply { isDaemon = true } }
    private val stopped = CountDownLatch(1)
    private val cleaned = AtomicBoolean(false)
    @Volatile private var stopReason: Int? = null
    @Volatile private var stopRequested = false
    private val t0 = System.nanoTime()

    private fun ms() = (System.nanoTime() - t0) / 1_000_000

    private fun state(s: State) {
        if (state == s) return
        state = s
        log("avf: $s (+${ms()} ms)")
    }

    override val isAlive: Boolean get() = stopped.count > 0

    private fun boot(ctx: Context, spec: AvfApi.Spec): Boolean {
        log("avf: $state")
        try {
            val config = api.buildConfig(ctx, spec) { io.configure(it, log) }
            val v = api.create(manager, spec.name, config).also { vm = it }
            v.setCallback(callbacks, object : AvfApi.Listener {
                override fun onStopped(reason: Int) {
                    stopReason = reason
                    log("avf: VM stopped: ${api.stopReasonName(reason)}")
                    state(State.STOPPED)
                    stopped.countDown()
                }

                override fun onError(code: Int, message: String) = log("avf: error ${api.errorName(code)}: $message")

                override fun onEvent(name: String) = log("avf: $name")
            })
            state(State.STARTING)
            v.run()
            console = runCatching { v.consoleOutput() }.onFailure { log("avf: no console output: ${it.message}") }.getOrNull()
            vmLog = runCatching { v.logOutput() }.getOrNull()
            screen = io.attach(v, log)
            // onStopped may already have arrived (a guest that exits at once); keep STOPPED then.
            if (stopped.count > 0) state(State.RUNNING)
            return true
        } catch (e: AvfException) {
            log("error: AVF: ${e.message}")
            // virtmgr checks the grants itself, so a missing one surfaces as a SecurityException here.
            if (e.cause is SecurityException) log("error: check the MANAGE_ and USE_CUSTOM_VIRTUAL_MACHINE grants (see the checks on the main screen)")
        } catch (e: RuntimeException) {
            log("error: AVF: $e")
        }
        state(State.STOPPED)
        stopped.countDown()
        cleanup()
        return false
    }

    override fun pump(onLine: (String) -> Unit): Int {
        FileWriter(logFile, false).use { file ->
            val sink = { line: String ->
                synchronized(file) { runCatching { file.write(line); file.write("\n"); file.flush() } }
                onLine(line)
            }
            val readers = listOfNotNull(
                console?.let { reader("console", it, "", sink) },
                vmLog?.let { reader("log", it, "[vm log] ", sink) },
            )
            stopped.await()
            // Let the readers drain what the guest wrote just before it stopped.
            readers.forEach { it.join(2000) }
        }
        cleanup()
        val r = stopReason
        return when {
            r == api.stopReasonShutdown -> 0
            stopRequested -> 0
            r == null || r == 0 -> 1
            else -> r
        }
    }

    private fun reader(name: String, input: InputStream, prefix: String, sink: (String) -> Unit) = Thread({
        runCatching {
            input.bufferedReader(Charsets.UTF_8).forEachLine { sink(prefix + it.trimEnd('\r')) }
        }
    }, "avf-$name").apply { isDaemon = true; start() }

    override fun stop() {
        stopRequested = true
        if (stopped.count == 0L) return
        state(State.STOPPING)
        try {
            vm?.stop()
        } catch (e: AvfException) {
            log("avf: stop: ${e.message}")
        }
        if (!stopped.await(STOP_TIMEOUT_S, TimeUnit.SECONDS)) {
            log("avf: no onStopped after $STOP_TIMEOUT_S s; closing the VM")
            state(State.STOPPED)
            stopped.countDown()
        }
    }

    override fun kill() {
        stopRequested = true
        runCatching { vm?.stop() }
        stopped.countDown()
        cleanup()
    }

    /** Once: detach I/O, close the VM and delete its instance directory ([NAME] under the framework's vm/). */
    private fun cleanup() {
        if (!cleaned.compareAndSet(false, true)) return
        runCatching { io.detach() }
        screen = null
        vm?.let { v ->
            runCatching { v.close() }
            runCatching { api.delete(manager, NAME) }.onFailure { log("avf: couldn't delete the VM instance: ${it.message}") }
        }
        callbacks.shutdown()
        if (current === this) current = null
    }

    companion object {
        /**
         * The VM instance name. The framework keeps the instance in <app data>/vm/<name>, which is
         * not the VM folder (<app data>/files/vm), and [cleanup] deletes it after each run.
         */
        const val NAME = "iosonpixel"
        private const val STOP_TIMEOUT_S = 10L

        /** The run in progress, for the display and buttons; one at a time (VmService). */
        @Volatile var current: AvfVm? = null
            private set

        /** Builds, creates and runs [spec]; null (reason logged) if any step fails. */
        fun start(ctx: Context, spec: AvfApi.Spec, io: AvfIo, logFile: File, log: (String) -> Unit): AvfVm? {
            val api = AvfApi.instance
            val manager = api.manager(ctx) ?: run {
                log("error: AVF: no VirtualMachineManager on this device")
                return null
            }
            val run = AvfVm(api, manager, io, logFile, log)
            current = run
            return run.takeIf { it.boot(ctx, spec) }
        }
    }
}
