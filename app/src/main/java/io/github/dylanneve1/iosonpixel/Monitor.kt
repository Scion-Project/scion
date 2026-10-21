package io.github.dylanneve1.iosonpixel

import android.net.LocalSocket
import android.net.LocalSocketAddress
import java.util.concurrent.Executors

/**
 * Talks to QEMU's human monitor over its unix socket. Used for the hardware
 * buttons, which Inferno maps to F-keys (see its "Using the Device's Buttons").
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

    enum class Button(val key: String) {
        POWER("f5"), HOME("f6"), VOLUME_DOWN("f3"), VOLUME_UP("f4"), RINGER("f2"),
    }

    companion object {
        private val io = Executors.newSingleThreadExecutor()

        /** Presses [button] for [holdMs] without blocking the caller. */
        fun press(paths: AppPaths, button: Button, holdMs: Int = 100) {
            io.execute { Monitor(paths).send("sendkey ${button.key} $holdMs") }
        }
    }
}
