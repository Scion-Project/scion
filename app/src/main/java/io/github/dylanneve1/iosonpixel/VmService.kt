package io.github.dylanneve1.iosonpixel

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.ServiceInfo
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import java.io.File

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
 * Foreground service owning the running VM (for QEMU, its child process).
 *
 * Android 12+ may kill "phantom" child processes of apps that are not in the
 * foreground; holding a foreground service keeps us out of that bucket.
 */
class VmService : Service() {
    private var instance: VmBackend.Instance? = null
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> stopVm()
            else -> startVm(intent)
        }
        return START_NOT_STICKY
    }

    private fun startVm(intent: Intent?) {
        if (instance != null) return
        startForeground(NOTIF_ID, notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)

        val settings = VmSettings.load(this)
        // Developer override for this run only (see MainActivity); never saved.
        val debuggable = (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
        val kind = intent?.getStringExtra(EXTRA_BACKEND)?.takeIf { debuggable }?.let { Backend.fromId(it) } ?: settings.backend
        val dir = intent?.getStringExtra(EXTRA_VM_DIR)?.takeIf { debuggable }?.let { File(it) }
        if (kind != settings.backend || dir != null) {
            VmState.addLine("note: developer override: backend ${kind.label}" + (dir?.let { ", VM folder $it" } ?: ""))
        }
        val backend = VmBackend.create(this, kind, dir)
        val problems = backend.problems() + listOfNotNull(
            "a VM folder import is still running".takeIf { ImportState.running },
        )
        if (problems.isNotEmpty()) {
            problems.forEach { VmState.addLine("error: $it") }
            VmState.set(VmState.Status.STOPPED)
            stopSelf()
            return
        }

        VmState.set(VmState.Status.STARTING, exit = null)
        val vm = backend.start(settings, VmState::addLine) ?: run {
            VmState.set(VmState.Status.STOPPED)
            stopSelf()
            return
        }
        instance = vm
        wakeLock = (getSystemService(Context.POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "iosonpixel:vm").apply { acquire() }
        VmState.set(VmState.Status.RUNNING)

        Thread({ pump(vm, backend.kind) }, "vm-output").start()
    }

    private fun pump(vm: VmBackend.Instance, kind: Backend) {
        val code = vm.pump(VmState::addLine)
        VmState.addLine("${kind.label} exited with code $code")
        Handler(Looper.getMainLooper()).post {
            if (instance === vm) {
                instance = null
                cleanup()
                VmState.set(VmState.Status.STOPPED, exit = code)
                stopSelf()
            }
        }
    }

    private fun stopVm() {
        val vm = instance ?: run { stopSelf(); return }
        VmState.set(VmState.Status.STOPPING)
        Thread({ vm.stop() }, "vm-stop").start()
    }

    private fun cleanup() {
        wakeLock?.takeIf { it.isHeld }?.release()
        wakeLock = null
    }

    override fun onDestroy() {
        instance?.kill()
        cleanup()
        super.onDestroy()
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
        /** Debuggable builds only: run this backend / VM folder instead of the saved ones. */
        const val EXTRA_BACKEND = "backend"
        const val EXTRA_VM_DIR = "vm_dir"

        fun start(ctx: Context, backend: Backend? = null, vmDir: File? = null) {
            ctx.startForegroundService(Intent(ctx, VmService::class.java).apply {
                backend?.let { putExtra(EXTRA_BACKEND, it.id) }
                vmDir?.let { putExtra(EXTRA_VM_DIR, it.absolutePath) }
            })
        }

        fun stop(ctx: Context) {
            ctx.startService(Intent(ctx, VmService::class.java).setAction(ACTION_STOP))
        }
    }
}
