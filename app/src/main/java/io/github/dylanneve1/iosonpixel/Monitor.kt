package io.github.dylanneve1.iosonpixel

import android.net.LocalSocket
import android.net.LocalSocketAddress
import java.util.concurrent.Executors

/**
 * Talks to QEMU's human monitor over its unix socket. Used by [QemuBackend]
 * for the hardware buttons and for a clean quit.
 */
class Monitor(private val paths: AppPaths) {

    /** Sends one command synchronously; returns false if QEMU isn't listening. */
    fun send(command: String): Boolean = try {
        LocalSocket().use { s ->
            s.connect(LocalSocketAddress(paths.monitorSocket.absolutePath, LocalSocketAddress.Namespace.FILESYSTEM))
            s.soTimeout = 2000
            val input = s.inputStream
            readUntilPrompt(input)
            s.outputStream.write("$command\n".toByteArray())
            s.outputStream.flush()
            readUntilPrompt(input)
        }
        true
    } catch (e: Exception) {
        false
    }

    private fun readUntilPrompt(input: java.io.InputStream) {
        val tail = StringBuilder()
        while (true) {
            val b = input.read()
            if (b < 0) return
            tail.append(b.toChar())
            if (tail.endsWith("(qemu) ")) return
            if (tail.length > 4096) tail.delete(0, 2048)
        }
    }

    companion object {
        private val io = Executors.newSingleThreadExecutor()

        /** Presses QEMU key [key] for [holdMs] without blocking the caller. */
        fun press(paths: AppPaths, key: String, holdMs: Int) {
            io.execute { Monitor(paths).send("sendkey $key $holdMs") }
        }
    }
}
