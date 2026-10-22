package io.github.dylanneve1.iosonpixel

import android.app.Activity
import android.app.ActivityManager
import android.content.ClipData
import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.Build
import android.os.ParcelFileDescriptor
import android.os.StatFs
import android.provider.OpenableColumns
import android.view.View
import android.widget.Toast
import java.io.File
import java.io.FileNotFoundException
import java.io.RandomAccessFile
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * "Share debug bundle": one zip with everything we need to diagnose a boot on
 * the user's phone without adb.
 *
 * What goes in is an explicit list of text: logs, the QEMU command line,
 * settings, device/CPU/memory/storage facts, and the VM folder's *file names
 * and sizes*. Nothing inside the VM folder is ever opened except
 * vm.properties and avf.properties (which only name files), so disk images, kernels and
 * firmware can't end up in the bundle.
 */
object DebugBundle {
    internal const val BUNDLE_DIR = "debug-bundles"
    private const val LAST_RUN = "last_run.txt"
    private const val LAST_RUN_LOG = "last_run_output.txt"
    /** Logs can be huge (the guest serial console goes to stdio); keep the tail. */
    private const val MAX_LOG_BYTES = 8L * 1024 * 1024

    @Volatile private var installed = false
    private var lastStatus: VmState.Status? = null
    /** The output view keeps 400 lines, so the "$ qemu ..." line can scroll out; remember it. */
    private var lastCommand: String? = null

    /**
     * Records each run's outcome to app storage as it happens, so the exit
     * code and command line survive the app being killed before the user
     * shares. VmState only keeps them in memory.
     */
    fun install(ctx: Context) {
        if (installed) return
        installed = true
        val app = ctx.applicationContext
        lastStatus = VmState.status
        // Listeners run on the main thread, for every output line too; only act on status changes.
        VmState.observe {
            val st = VmState.status
            if (st == lastStatus) return@observe
            lastStatus = st
            runCatching {
                val log = VmState.log()
                if (st == VmState.Status.STARTING) lastCommand = null
                commandFromLog(log)?.let { lastCommand = it }
                File(app.filesDir, LAST_RUN).writeText(
                    "time: ${now()}\nstatus: ${st.name.lowercase()}\n" +
                        "exit code: ${VmState.lastExit ?: "n/a"}\n" +
                        "command: ${lastCommand ?: "n/a"}\n" +
                        "QEMU_VCPU_AFFINITY: ${pinning(VmSettings.load(app))}\n",
                )
                if (st == VmState.Status.STOPPED) File(app.filesDir, LAST_RUN_LOG).writeText(log)
            }
        }
    }

    /** Builds the zip (blocking; call off the main thread) and returns it. */
    fun build(ctx: Context): File {
        val app = ctx.applicationContext
        val paths = AppPaths(app)
        val dir = File(app.cacheDir, BUNDLE_DIR).apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() } // one bundle at a time
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        val out = File(dir, "iosonpixel-debug-$stamp.zip")
        val names = mutableListOf<String>()

        ZipOutputStream(out.outputStream().buffered()).use { zip ->
            fun text(name: String, body: String) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(body.toByteArray())
                zip.closeEntry()
                names += name
            }
            fun tail(name: String, f: File) {
                if (!f.isFile) return
                zip.putNextEntry(ZipEntry(name))
                RandomAccessFile(f, "r").use { r ->
                    val skip = (r.length() - MAX_LOG_BYTES).coerceAtLeast(0)
                    if (skip > 0) zip.write("[... first $skip bytes omitted ...]\n".toByteArray())
                    r.seek(skip)
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        val n = r.read(buf)
                        if (n < 0) break
                        zip.write(buf, 0, n)
                    }
                }
                zip.closeEntry()
                names += name
            }

            text("summary.txt", summary(app, paths))
            text("qemu-command.txt", command(app, paths))
            text("settings.txt", settings(app))
            text("vm-folder.txt", vmFolder(paths))
            text("cpu.txt", cpu())
            text("memory-storage.txt", memoryAndStorage(app, paths))
            // QEMU runs with -serial stdio, so qemu.log is also the guest serial log.
            tail("qemu.log", paths.logFile)
            // Any other logs written next to it (e.g. a separate serial log), never the VM folder.
            paths.logFile.parentFile?.listFiles { f -> f.isFile && f.name.endsWith(".log") && f != paths.logFile }
                ?.sortedBy { it.name }?.forEach { tail("logs/${it.name}", it) }
            val view = VmState.log()
            text("app-output.txt", "Output view of this app process (up to 400 lines):\n" + view.ifEmpty { "(empty)" } + "\n")
            tail("last-run-output.txt", File(app.filesDir, LAST_RUN_LOG))
            text("README.txt", readme(names + "README.txt"))
        }
        return out
    }

    /** Builds the bundle off the main thread, then opens the share sheet. [button] is disabled meanwhile. */
    fun buildAndShare(activity: Activity, button: View) {
        button.isEnabled = false
        Thread({
            val result = runCatching { build(activity) }
            activity.runOnUiThread {
                button.isEnabled = true
                result.onSuccess { share(activity, it) }.onFailure {
                    Toast.makeText(activity, "Debug bundle failed: $it", Toast.LENGTH_LONG).show()
                }
            }
        }, "debug-bundle").start()
    }

    /** Opens the share sheet for [file], readable only through [DebugBundleProvider]. */
    fun share(activity: Activity, file: File) {
        val uri = DebugBundleProvider.uriFor(activity, file)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "application/zip"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, "Scion debug bundle (${file.name})")
            clipData = ClipData.newRawUri(file.name, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        activity.startActivity(Intent.createChooser(send, "Share debug bundle"))
    }

    // ---- sections ------------------------------------------------------------

    private fun summary(app: Context, paths: AppPaths): String = buildString {
        val pi = app.packageManager.getPackageInfo(app.packageName, 0)
        appendLine("generated: ${now()}")
        appendLine("app: ${app.packageName} ${pi.versionName} (${pi.longVersionCode})")
        appendLine("qemu binary bundled: ${paths.qemu.isFile}" +
            (if (paths.qemu.isFile) " (${paths.qemu.length()} bytes)" else ""))
        appendLine()
        appendLine("device: ${Build.MANUFACTURER} ${Build.MODEL} (${Build.DEVICE}, ${Build.PRODUCT})")
        appendLine("soc: ${Build.SOC_MANUFACTURER} ${Build.SOC_MODEL}; hardware ${Build.HARDWARE}")
        appendLine("android: ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT}), " +
            "security patch ${Build.VERSION.SECURITY_PATCH}")
        appendLine("build: ${Build.FINGERPRINT} (${Build.TYPE})")
        appendLine("kernel: ${System.getProperty("os.version")}")
        appendLine("abis: ${Build.SUPPORTED_ABIS.joinToString()}")
        appendLine("avf:")
        runCatching { AvfSupport.checks(app) }
            .onSuccess { list -> list.forEach { appendLine("  ${if (it.ok) "ok  " else "FAIL"} ${it.text.lineSequence().first()}") } }
            .onFailure { appendLine("  (failed: $it)") }
        appendLine()
        appendLine("vm status: ${VmState.status.name.lowercase()}")
        appendLine("last exit code (this process): ${VmState.lastExit ?: "n/a"}")
        val last = File(app.filesDir, LAST_RUN)
        appendLine("last recorded run:")
        appendLine(if (last.isFile) last.readText().trimEnd().prependIndent("  ") else "  (none recorded)")
        appendLine()
        appendLine("readiness checks:")
        runCatching { VmBackend.current(app).checks() }
            .onSuccess { list -> list.forEach { appendLine("  ${if (it.ok) "ok  " else "FAIL"} ${it.text}") } }
            .onFailure { appendLine("  (failed: $it)") }
    }

    private fun command(app: Context, paths: AppPaths): String {
        commandFromLog(VmState.log())?.let { return "# from this process's output view (exact)\n$it\n" }
        File(app.filesDir, LAST_RUN).takeIf { it.isFile }?.readLines()
            ?.firstOrNull { it.startsWith("command: ") && it != "command: n/a" }
            ?.let { return "# from the last recorded run (exact)\n${it.removePrefix("command: ")}\n" }
        val vm = VmLayout(paths.vmDir)
        if (vm.problems().isNotEmpty()) return "# no run recorded, and the VM folder isn't complete enough to build one\n"
        return runCatching {
            "# no run recorded; what Start would run now with the current settings\n" +
                QemuCommand.build(paths, vm, VmSettings.load(app)).joinToString(" ") + "\n"
        }.getOrElse { "# no run recorded; reconstructing failed: $it\n" }
    }

    private fun settings(app: Context): String = buildString {
        val s = VmSettings.load(app)
        appendLine(s.toString())
        appendLine()
        appendLine("effective QEMU_VCPU_AFFINITY: ${pinning(s)}" +
            if (s.vcpuAffinity.equals(VmSettings.AUTO, ignoreCase = true)) " (auto, from ${s.smp} vCPUs)" else " (as set)")
        appendLine()
        appendLine("raw preferences (vm_settings):")
        app.getSharedPreferences("vm_settings", Context.MODE_PRIVATE).all.toSortedMap()
            .forEach { (k, v) -> appendLine("  $k = $v") }
    }

    /** Names, sizes and dates only; never file contents (except vm.properties' names). */
    private fun vmFolder(paths: AppPaths): String = buildString {
        val dir = paths.vmDir
        appendLine("folder: ${dir.absolutePath} (${if (paths.vmInternal) "internal app storage" else "shared storage"})")
        // Both only name files (and AVF's kernel command line).
        for (name in listOf(VmLayout.MANIFEST, AvfLayout.MANIFEST)) {
            val manifest = File(dir, name)
            appendLine()
            appendLine("$name:")
            if (manifest.isFile) {
                java.util.Properties().apply { manifest.inputStream().use { load(it) } }
                    .entries.map { it.key.toString() to it.value.toString() }.sortedBy { it.first }
                    .forEach { (k, v) -> appendLine("  $k = $v") }
            } else {
                appendLine("  (missing)")
            }
        }
        appendLine()
        val vm = VmLayout(dir)
        appendLine("ramdisk (-initrd): " + (vm.ramdisk?.let { if (it.isFile) "${it.name}, ${it.length()} bytes" else "${it.name} (missing)" } ?: "none"))
        appendLine()
        appendLine("files (name, bytes, modified):")
        val files = dir.listFiles()?.sortedBy { it.name }.orEmpty()
        if (files.isEmpty()) appendLine("  (empty or missing)")
        for (f in files) {
            appendLine("  ${f.name}${if (f.isDirectory) "/" else ""}  ${if (f.isFile) f.length() else "-"}  " +
                Date(f.lastModified()).let { SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(it) })
        }
    }

    private fun cpu(): String = buildString {
        val base = File("/sys/devices/system/cpu")
        for (n in listOf("online", "possible", "present", "offline", "isolated")) {
            appendLine("$n: ${read(File(base, n))}")
        }
        appendLine("this process Cpus_allowed_list: " +
            read(File("/proc/self/status")).lines().firstOrNull { it.startsWith("Cpus_allowed_list") }
                ?.substringAfter(':')?.trim())
        appendLine()
        appendLine("cpu  midr_el1            capacity  cluster  core  min_khz  max_khz  cur_khz  core_cpus")
        val cpus = base.listFiles { f -> f.name.matches(Regex("cpu\\d+")) }.orEmpty()
            .sortedBy { it.name.removePrefix("cpu").toInt() }
        for (c in cpus) {
            fun r(p: String) = read(File(c, p)).ifEmpty { "-" }
            appendLine(listOf(
                c.name.padEnd(4), r("regs/identification/midr_el1").padEnd(19), r("cpu_capacity").padEnd(9),
                r("topology/cluster_id").padEnd(8), r("topology/core_id").padEnd(5),
                r("cpufreq/cpuinfo_min_freq").padEnd(8), r("cpufreq/cpuinfo_max_freq").padEnd(8),
                r("cpufreq/scaling_cur_freq").padEnd(8), r("topology/core_cpus_list"),
            ).joinToString(" "))
        }
        appendLine()
        appendLine("/proc/cpuinfo:")
        appendLine(read(File("/proc/cpuinfo")))
    }

    private fun memoryAndStorage(app: Context, paths: AppPaths): String = buildString {
        val mi = ActivityManager.MemoryInfo()
        (app.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager).getMemoryInfo(mi)
        appendLine("ram: total ${mib(mi.totalMem)}, available ${mib(mi.availMem)}, " +
            "low-memory threshold ${mib(mi.threshold)}, lowMemory=${mi.lowMemory}")
        appendLine("vm.max_map_count: ${read(File("/proc/sys/vm/max_map_count")).ifEmpty { "(unreadable)" }}")
        appendLine()
        appendLine("storage (free / total):")
        for ((label, f) in listOf(
            "vm folder" to paths.vmDir, "app external files" to paths.logFile.parentFile,
            "app internal files" to app.filesDir, "cache" to app.cacheDir,
        )) {
            val st = f?.let { d -> generateSequence(d) { it.parentFile }.firstOrNull { it.exists() } }
                ?.let { runCatching { StatFs(it.path) }.getOrNull() }
            appendLine("  $label: " + (st?.let { "${mib(it.availableBytes)} / ${mib(it.totalBytes)}" } ?: "n/a") +
                "  (${f?.path})")
        }
        appendLine()
        appendLine("/proc/meminfo:")
        appendLine(read(File("/proc/meminfo")))
    }

    private fun readme(names: List<String>) = """
        Scion debug bundle, generated ${now()}.

        Contents:
        ${names.joinToString("\n        ") { "- $it" }}

        Deliberately NOT included: disk images, kernels, device trees, firmware,
        SEP ROM/NVRAM or any other file from the VM folder. vm-folder.txt lists
        their names and sizes only. QEMU's -serial stdio output (the guest
        serial console) is part of qemu.log; an AVF guest's console is
        logs/avf-console.log. Logs longer than ${MAX_LOG_BYTES / 1024 / 1024} MiB keep only
        their tail.
    """.trimIndent() + "\n"

    // ---- helpers ---------------------------------------------------------------

    private fun pinning(s: VmSettings) = s.effectiveVcpuAffinity.ifBlank { "(off)" }

    internal fun commandFromLog(log: String): String? =
        log.lineSequence().lastOrNull { it.startsWith("$ ") }?.removePrefix("$ ")

    private fun read(f: File): String = runCatching { f.readText().trim() }.getOrDefault("")
    private fun mib(b: Long) = "${b / (1024 * 1024)} MiB"
    private fun now() = SimpleDateFormat("yyyy-MM-dd HH:mm:ss Z", Locale.US).format(Date())
}

/**
 * Serves the debug bundle to whichever app the user shares it with. Framework
 * only (the app has no AndroidX), so this stands in for FileProvider: not
 * exported, readable only through per-URI grants, and it only ever serves
 * plain files directly inside cache/debug-bundles.
 */
class DebugBundleProvider : ContentProvider() {
    companion object {
        fun authority(ctx: Context) = "${ctx.packageName}.debugbundle"
        fun uriFor(ctx: Context, f: File): Uri =
            Uri.Builder().scheme("content").authority(authority(ctx)).appendPath(f.name).build()
    }

    private fun fileFor(uri: Uri): File {
        val ctx = context ?: throw FileNotFoundException("no context")
        val name = uri.lastPathSegment
        if (uri.pathSegments.size != 1 || name == null || name.contains('/') || name.startsWith(".")) {
            throw FileNotFoundException("bad path: $uri")
        }
        val f = File(File(ctx.cacheDir, DebugBundle.BUNDLE_DIR), name)
        if (!f.isFile) throw FileNotFoundException(uri.toString())
        return f
    }

    override fun onCreate() = true

    override fun getType(uri: Uri) = "application/zip"

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        if (mode != "r") throw SecurityException("read-only")
        return ParcelFileDescriptor.open(fileFor(uri), ParcelFileDescriptor.MODE_READ_ONLY)
    }

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?,
                       selectionArgs: Array<out String>?, sortOrder: String?): Cursor {
        val f = fileFor(uri)
        val cols = projection ?: arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)
        return MatrixCursor(cols, 1).apply {
            addRow(cols.map {
                when (it) {
                    OpenableColumns.DISPLAY_NAME -> f.name
                    OpenableColumns.SIZE -> f.length()
                    else -> null
                }
            })
        }
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri? = throw UnsupportedOperationException()
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?) =
        throw UnsupportedOperationException()
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) =
        throw UnsupportedOperationException()
}
