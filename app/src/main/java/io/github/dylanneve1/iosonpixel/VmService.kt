package io.github.dylanneve1.iosonpixel

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import java.io.File
import java.io.FileWriter
import java.util.concurrent.TimeUnit

/** Process-wide VM state the activities observe. */
object VmState {
    enum class Status { STOPPED, STARTING, RUNNING, STOPPING }

    @Volatile var status = Status.STOPPED
        private set
    @Volatile var lastExit: Int? = null
        private set

    private const val MAX_LINES = 400
    private val lines = ArrayDeque<String>()
    private val listeners = mutableSetOf<() -> Unit>()
    private val main = Handler(Looper.getMainLooper())

    fun log(): String = synchronized(lines) { lines.joinToString("\n") }

    fun addLine(line: String) {
        synchronized(lines) {
            lines.addLast(line)
            while (lines.size > MAX_LINES) lines.removeFirst()
        }
        notifyListeners()
    }

    internal fun set(status: Status, exit: Int? = lastExit) {
        this.status = status
        lastExit = exit
        notifyListeners()
    }

    /** Listeners are always called on the main thread. */
    fun observe(l: () -> Unit) { synchronized(listeners) { listeners += l } }
    fun unobserve(l: () -> Unit) { synchronized(listeners) { listeners -= l } }

    private fun notifyListeners() {
        main.post { synchronized(listeners) { listeners.toList() }.forEach { it() } }
    }
}

/**
 * Foreground service owning the QEMU child process.
 *
 * Android 12+ may kill "phantom" child processes of apps that are not in the
 * foreground; holding a foreground service keeps us out of that bucket.
 */
class VmService : Service() {
    private var process: Process? = null
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> stopVm()
            else -> startVm()
        }
        return START_NOT_STICKY
    }

    private fun startVm() {
        if (process != null) return
        startForeground(NOTIF_ID, notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)

        val paths = AppPaths(this)
        val vm = VmLayout(paths.vmDir)
        val problems = vm.problems() + listOfNotNull(
            "QEMU binary not bundled in this build".takeIf { !paths.qemu.isFile },
        )
        if (problems.isNotEmpty()) {
            problems.forEach { VmState.addLine("error: $it") }
            VmState.set(VmState.Status.STOPPED)
            stopSelf()
            return
        }

        VmState.set(VmState.Status.STARTING, exit = null)
        installDataFiles(paths)
        paths.vncSocket.delete()
        paths.monitorSocket.delete()

        val settings = VmSettings.load(this)
        val cmd = QemuCommand.build(paths, vm, settings)
        VmState.addLine("$ " + cmd.joinToString(" "))

        val pb = ProcessBuilder(cmd).directory(vm.dir).redirectErrorStream(true)
        pb.environment()["TMPDIR"] = paths.tmpDir.absolutePath
        if (settings.vcpuAffinity.isNotBlank()) {
            pb.environment()["QEMU_VCPU_AFFINITY"] = settings.vcpuAffinity
        }

        val p = try {
            pb.start()
        } catch (e: Exception) {
            VmState.addLine("error: failed to start QEMU: $e")
            VmState.set(VmState.Status.STOPPED)
            stopSelf()
            return
        }
        process = p
        wakeLock = (getSystemService(Context.POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "iosonpixel:vm").apply { acquire() }
        VmState.set(VmState.Status.RUNNING)

        Thread({ pump(p, paths.logFile) }, "qemu-output").start()
    }

    private fun pump(p: Process, logFile: File) {
        FileWriter(logFile, false).use { log ->
            p.inputStream.bufferedReader().forEachLine { line ->
                log.write(line); log.write("\n"); log.flush()
                VmState.addLine(line)
            }
        }
        val code = p.waitFor()
        VmState.addLine("QEMU exited with code $code")
        Handler(Looper.getMainLooper()).post {
            if (process === p) {
                process = null
                cleanup()
                VmState.set(VmState.Status.STOPPED, exit = code)
                stopSelf()
            }
        }
    }

    private fun stopVm() {
        val p = process ?: run { stopSelf(); return }
        VmState.set(VmState.Status.STOPPING)
        Thread({
            // Ask QEMU to quit cleanly first so disk images are flushed.
            Monitor(AppPaths(this)).send("quit")
            if (!p.waitFor(10, TimeUnit.SECONDS)) {
                p.destroy()
                if (!p.waitFor(5, TimeUnit.SECONDS)) p.destroyForcibly()
            }
        }, "qemu-stop").start()
    }

    private fun cleanup() {
        wakeLock?.takeIf { it.isHeld }?.release()
        wakeLock = null
    }

    override fun onDestroy() {
        process?.destroy()
        cleanup()
        super.onDestroy()
    }

    /** Copies keymaps etc. from assets so QEMU's -L data dir is populated. */
    private fun installDataFiles(paths: AppPaths) {
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

    private fun notification(): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, getString(R.string.notif_channel), NotificationManager.IMPORTANCE_LOW),
        )
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, DisplayActivity::class.java), PendingIntent.FLAG_IMMUTABLE,
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, VmService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE,
        )
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_launcher)
            .setContentTitle(getString(R.string.notif_running))
            .setContentIntent(open)
            .addAction(Notification.Action.Builder(null, getString(R.string.notif_stop), stop).build())
            .setOngoing(true)
            .build()
    }

    companion object {
        private const val CHANNEL = "vm"
        private const val NOTIF_ID = 1
        const val ACTION_STOP = "stop"

        fun start(ctx: Context) {
            ctx.startForegroundService(Intent(ctx, VmService::class.java))
        }

        fun stop(ctx: Context) {
            ctx.startService(Intent(ctx, VmService::class.java).setAction(ACTION_STOP))
        }
    }
}
