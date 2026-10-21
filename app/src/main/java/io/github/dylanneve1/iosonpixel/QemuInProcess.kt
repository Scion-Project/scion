package io.github.dylanneve1.iosonpixel

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.app.Service
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Binder
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Parcel
import android.os.ParcelFileDescriptor
import android.os.Process
import android.system.Os
import android.util.Log
import android.view.Surface
import android.system.OsConstants
import java.io.FileDescriptor
import java.io.FileOutputStream
import java.io.FileWriter
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.system.exitProcess

/** The JNI shim in native/qemu_jni.c; used in the :vm process only. */
object QemuNative {
    init { System.loadLibrary("qemu_jni") }

    /** Loads QEMU from [lib]; returns null, or the dlopen/dlsym error. */
    @JvmStatic external fun load(lib: String): String?
    /** Runs qemu_android_main(); ends the process with QEMU's exit status. */
    @JvmStatic external fun run(argv: Array<String>, cwd: String)

    // -display android (ui/android.c); no-ops if the library lacks it.
    @JvmStatic external fun setSurface(surface: Surface?)
    @JvmStatic external fun touch(x: Int, y: Int, w: Int, h: Int, down: Boolean)
    @JvmStatic external fun key(name: String, down: Boolean)

    /** Called by the shim on QEMU's main loop when the guest framebuffer size changes. */
    @JvmStatic fun onResize(w: Int, h: Int) = QemuVmService.onResize(w, h)
}

/**
 * Hosts QEMU as a library in its own process (android:process=":vm"), the
 * in-process alternative to exec'ing it. One process runs one VM: QEMU calls
 * exit() when it is done, which ends this process; the app learns about it
 * from the console pipe closing and reads the status from the exit info.
 *
 * Bound (not started) by [QemuInProcess] from VmService, so it gets the
 * foreground service's priority, and given its arguments over the binder.
 * The display (-display android) also goes over the binder: the app's
 * Surface, touches and keys in, framebuffer size changes out.
 */
class QemuVmService : Service() {
    private val binder = object : Binder() {
        override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
            when (code) {
                TX_START -> {
                    val lib = data.readString()!!
                    val argv = data.createStringArray()!!
                    val cwd = data.readString()!!
                    val env = data.createStringArray()!!
                    val console = data.readFileDescriptor()
                    reply?.writeInt(Process.myPid())
                    start(lib, argv, cwd, env, console)
                }
                TX_SURFACE -> {
                    val surface = if (data.readInt() != 0) Surface.CREATOR.createFromParcel(data) else null
                    val size = setSurface(surface, data.readStrongBinder())
                    reply?.writeInt(size.first)
                    reply?.writeInt(size.second)
                }
                TX_TOUCH -> if (loaded) {
                    QemuNative.touch(data.readInt(), data.readInt(), data.readInt(), data.readInt(), data.readInt() != 0)
                }
                TX_KEY -> if (loaded) QemuNative.key(data.readString()!!, data.readInt() != 0)
                else -> return super.onTransact(code, data, reply, flags)
            }
            return true
        }
    }

    override fun onBind(intent: Intent?): IBinder = binder

    private fun start(lib: String, argv: Array<String>, cwd: String, env: Array<String>, console: ParcelFileDescriptor) {
        if (started) return
        started = true
        // QEMU's stdout/stderr (the guest serial console and error messages)
        // go to the pipe the app reads, as with the child process. stdin is a
        // pipe nobody writes to, which is also what the child process had.
        Os.dup2(console.fileDescriptor, 1)
        Os.dup2(console.fileDescriptor, 2)
        console.close()
        val stdin = Os.pipe()
        Os.dup2(stdin[0], 0)
        stdinWriter = stdin[1]
        for (i in env.indices step 2) Os.setenv(env[i], env[i + 1], true)

        // The main loop runs on this thread; give it a native-sized stack.
        Thread(null, {
            try {
                QemuNative.load(lib)?.let { err ->
                    FileOutputStream(FileDescriptor.err).use { it.write("error: cannot load QEMU from $lib: $err\n".toByteArray()) }
                    exitProcess(127)
                }
                synchronized(displayLock) {
                    loaded = true
                    surface?.let { QemuNative.setSurface(it) }
                }
                QemuNative.run(argv, cwd)
            } catch (t: Throwable) {
                Log.e(TAG, "in-process QEMU failed", t)
                FileOutputStream(FileDescriptor.err).use { it.write("error: in-process QEMU: $t\n".toByteArray()) }
            }
            exitProcess(127)
        }, "qemu-main", 8L shl 20).start()
    }

    companion object {
        private const val TAG = "QemuVmService"
        const val TX_START = IBinder.FIRST_CALL_TRANSACTION
        const val TX_SURFACE = IBinder.FIRST_CALL_TRANSACTION + 1
        const val TX_TOUCH = IBinder.FIRST_CALL_TRANSACTION + 2
        const val TX_KEY = IBinder.FIRST_CALL_TRANSACTION + 3
        /** Sent to the app's size listener. */
        const val TX_RESIZE = IBinder.FIRST_CALL_TRANSACTION

        private val displayLock = Any()
        @Volatile private var loaded = false
        private var surface: Surface? = null
        private var sizeListener: IBinder? = null
        private var width = 0
        private var height = 0

        /** Draw into [s] (null stops); returns the current framebuffer size. */
        private fun setSurface(s: Surface?, listener: IBinder?): Pair<Int, Int> = synchronized(displayLock) {
            val old = surface
            surface = s
            sizeListener = listener
            // Once this returns QEMU no longer touches the old window.
            if (loaded) QemuNative.setSurface(s)
            old?.release()
            width to height
        }

        fun onResize(w: Int, h: Int) {
            val listener = synchronized(displayLock) {
                width = w
                height = h
                sizeListener
            } ?: return
            val data = Parcel.obtain()
            try {
                data.writeInt(w)
                data.writeInt(h)
                listener.transact(TX_RESIZE, data, null, IBinder.FLAG_ONEWAY)
            } catch (_: Exception) {
            } finally {
                data.recycle()
            }
        }

        @Volatile private var started = false
        /** Write end of QEMU's stdin; kept open so stdin never reads EOF. */
        private var stdinWriter: FileDescriptor? = null
    }
}

/**
 * Main-process side: binds [QemuVmService] and turns its process into a
 * [VmBackend.Instance]. Its companion is the [SurfaceDisplay] for whichever
 * in-process VM is running, so the display can attach before or after start.
 */
class QemuInProcess(
    ctx: Context,
    private val paths: AppPaths,
    private val argv: List<String>,
    private val cwd: String,
    private val env: Map<String, String>,
) : VmBackend.Instance {
    private val app = ctx.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private val connected = CountDownLatch(1)
    private val exited = CountDownLatch(1)
    @Volatile private var binder: IBinder? = null
    @Volatile private var pid = 0
    @Volatile private var bound = false

    private val conn = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder) {
            // After QEMU exits the system restarts the bound service in a fresh
            // :vm process; that one is never sent START and goes away on unbind.
            if (binder == null) binder = service
            connected.countDown()
        }

        override fun onServiceDisconnected(name: ComponentName?) = Unit
    }

    /** Binds the :vm service; must be called on the main thread. */
    fun bind(): Boolean {
        bound = app.bindService(
            Intent(app, QemuVmService::class.java), conn, Context.BIND_AUTO_CREATE or Context.BIND_IMPORTANT,
        )
        if (bound) current = this
        return bound
    }

    @Volatile private var started = false

    /** Receives framebuffer size changes from the :vm process. */
    private val sizeListener = object : Binder() {
        override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
            if (code != QemuVmService.TX_RESIZE) return super.onTransact(code, data, reply, flags)
            val w = data.readInt()
            val h = data.readInt()
            synchronized(Companion) { onSize }?.invoke(w, h)
            return true
        }
    }

    /** Sends the companion's current Surface to the :vm process (synchronously). */
    private fun sendSurface() {
        val b = binder ?: return
        if (!started) return
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        try {
            val (s, cb) = synchronized(Companion) { surface to onSize }
            if (s != null) {
                data.writeInt(1)
                s.writeToParcel(data, 0)
            } else {
                data.writeInt(0)
            }
            data.writeStrongBinder(if (s != null) sizeListener else null)
            b.transact(QemuVmService.TX_SURFACE, data, reply, 0)
            val w = reply.readInt()
            val h = reply.readInt()
            if (w > 0 && h > 0) cb?.invoke(w, h)
        } catch (_: Exception) {
            // The :vm process is gone; pump() reports that.
        } finally {
            data.recycle()
            reply.recycle()
        }
    }

    private fun send(code: Int, fill: (Parcel) -> Unit) {
        val b = binder ?: return
        if (!started) return
        val data = Parcel.obtain()
        try {
            fill(data)
            b.transact(code, data, null, IBinder.FLAG_ONEWAY)
        } catch (_: Exception) {
        } finally {
            data.recycle()
        }
    }

    override val isAlive get() = exited.count > 0

    override fun pump(onLine: (String) -> Unit): Int {
        try {
            val b = binder ?: run {
                if (!connected.await(10, TimeUnit.SECONDS)) {
                    onLine("error: the :vm service did not start")
                    return -1
                }
                binder!!
            }
            val (read, write) = ParcelFileDescriptor.createPipe()
            val data = Parcel.obtain()
            val reply = Parcel.obtain()
            try {
                data.writeString(paths.qemuLib.absolutePath)
                data.writeStringArray(argv.toTypedArray())
                data.writeString(cwd)
                data.writeStringArray(env.flatMap { listOf(it.key, it.value) }.toTypedArray())
                data.writeFileDescriptor(write.fileDescriptor)
                b.transact(QemuVmService.TX_START, data, reply, 0)
                pid = reply.readInt()
            } finally {
                data.recycle()
                reply.recycle()
                // Only the :vm process may hold the write end, so EOF means it exited.
                write.close()
            }
            started = true
            sendSurface()

            FileWriter(paths.logFile, false).use { log ->
                ParcelFileDescriptor.AutoCloseInputStream(read).bufferedReader().forEachLine { line ->
                    log.write(line); log.write("\n"); log.flush()
                    onLine(line)
                }
            }
            return exitStatus(onLine)
        } catch (e: Exception) {
            onLine("error: in-process QEMU: $e")
            return -1
        } finally {
            if (current === this) current = null
            exited.countDown()
            unbind()
        }
    }

    /** QEMU's exit status, from the system's record of how the :vm process ended. */
    private fun exitStatus(onLine: (String) -> Unit): Int {
        val am = app.getSystemService(ActivityManager::class.java)
        repeat(20) {
            val info = am.getHistoricalProcessExitReasons(app.packageName, pid, 1).firstOrNull()
            if (info != null) {
                if (info.reason == ApplicationExitInfo.REASON_EXIT_SELF) return info.status
                val why = info.description?.takeIf { it.isNotBlank() } ?: when (info.reason) {
                    ApplicationExitInfo.REASON_SIGNALED -> "signal ${info.status}"
                    ApplicationExitInfo.REASON_LOW_MEMORY -> "killed for low memory"
                    ApplicationExitInfo.REASON_CRASH_NATIVE -> "native crash"
                    else -> "exit reason ${info.reason}"
                }
                onLine(":vm process ended: $why")
                // Like Process.waitFor() for a child killed by a signal.
                return if (info.reason == ApplicationExitInfo.REASON_SIGNALED) 128 + info.status else -1
            }
            Thread.sleep(100)
        }
        onLine(":vm process ended; no exit record found")
        return -1
    }

    override fun stop() {
        // Ask QEMU to quit cleanly first so disk images are flushed.
        Monitor(paths).send("quit")
        if (!exited.await(10, TimeUnit.SECONDS)) {
            kill()
            if (!exited.await(5, TimeUnit.SECONDS) && pid != 0) Process.killProcess(pid)
        }
    }

    override fun kill() {
        if (pid != 0) Process.sendSignal(pid, OsConstants.SIGTERM)
        unbind()
    }

    private fun unbind() {
        main.post {
            if (bound) {
                bound = false
                app.unbindService(conn)
            }
        }
    }

    /** The display of the running in-process VM; keeps the Surface across a VM (re)start. */
    companion object : SurfaceDisplay {
        @Volatile private var current: QemuInProcess? = null
        private var surface: Surface? = null
        private var onSize: ((Int, Int) -> Unit)? = null

        override fun setSurface(surface: Surface?, onSize: ((Int, Int) -> Unit)?) {
            synchronized(this) {
                this.surface = surface
                this.onSize = onSize
            }
            current?.sendSurface()
        }

        override fun touch(x: Int, y: Int, w: Int, h: Int, down: Boolean) {
            current?.send(QemuVmService.TX_TOUCH) {
                it.writeInt(x); it.writeInt(y); it.writeInt(w); it.writeInt(h); it.writeInt(if (down) 1 else 0)
            }
        }

        override fun key(name: String, down: Boolean) {
            current?.send(QemuVmService.TX_KEY) { it.writeString(name); it.writeInt(if (down) 1 else 0) }
        }
    }
}
