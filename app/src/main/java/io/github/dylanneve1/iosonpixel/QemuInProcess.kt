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
import android.system.OsConstants
import java.io.FileDescriptor
import java.io.FileOutputStream
import java.io.FileWriter
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.system.exitProcess

/** The JNI shim in native/qemu_jni.c. */
object QemuNative {
    /** Loads [lib] and runs its qemu_android_main(); ends the process with QEMU's exit status. */
    external fun run(lib: String, argv: Array<String>, cwd: String)
}

/**
 * Hosts QEMU as a library in its own process (android:process=":vm"), the
 * in-process alternative to exec'ing it. One process runs one VM: QEMU calls
 * exit() when it is done, which ends this process; the app learns about it
 * from the console pipe closing and reads the status from the exit info.
 *
 * Bound (not started) by [QemuInProcess] from VmService, so it gets the
 * foreground service's priority, and given its arguments over the binder.
 */
class QemuVmService : Service() {
    private val binder = object : Binder() {
        override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
            if (code != TX_START) return super.onTransact(code, data, reply, flags)
            val lib = data.readString()!!
            val argv = data.createStringArray()!!
            val cwd = data.readString()!!
            val env = data.createStringArray()!!
            val console = data.readFileDescriptor()
            reply?.writeInt(Process.myPid())
            start(lib, argv, cwd, env, console)
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
                System.loadLibrary("qemu_jni")
                QemuNative.run(lib, argv, cwd)
            } catch (t: Throwable) {
                FileOutputStream(FileDescriptor.err).use { it.write("error: in-process QEMU: $t\n".toByteArray()) }
            }
            exitProcess(127)
        }, "qemu-main", 8L shl 20).start()
    }

    companion object {
        const val TX_START = IBinder.FIRST_CALL_TRANSACTION

        @Volatile private var started = false
        /** Write end of QEMU's stdin; kept open so stdin never reads EOF. */
        private var stdinWriter: FileDescriptor? = null
    }
}

/** Main-process side: binds [QemuVmService] and turns its process into a [VmBackend.Instance]. */
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
        return bound
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
}
