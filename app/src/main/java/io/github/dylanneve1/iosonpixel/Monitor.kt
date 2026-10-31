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
    fun send(command: String): Boolean = query(command) != null

    /** Sends one command synchronously and returns what QEMU printed (up to the last 4 KiB), or null if it isn't listening. */
    fun query(command: String): String? = try {
        LocalSocket().use { s ->
            s.connect(LocalSocketAddress(paths.monitorSocket.absolutePath, LocalSocketAddress.Namespace.FILESYSTEM))
            s.soTimeout = 2000
            val input = s.inputStream
            readUntilPrompt(input)
            s.outputStream.write("$command\n".toByteArray())
            s.outputStream.flush()
            readUntilPrompt(input).removeSuffix(PROMPT)
        }
    } catch (e: Exception) {
        null
    }

    private fun readUntilPrompt(input: java.io.InputStream): String {
        val tail = StringBuilder()
        while (true) {
            val b = input.read()
            if (b < 0) return tail.toString()
            tail.append(b.toChar())
            if (tail.endsWith(PROMPT)) return tail.toString()
            if (tail.length > 4096) tail.delete(0, 2048)
        }
    }

    companion object {
        private const val PROMPT = "(qemu) "
        private val io = Executors.newSingleThreadExecutor()

        /** Presses QEMU key [key] for [holdMs] without blocking the caller. */
        fun press(paths: AppPaths, key: String, holdMs: Int) {
            io.execute { Monitor(paths).send("sendkey $key $holdMs") }
        }
    }
}
