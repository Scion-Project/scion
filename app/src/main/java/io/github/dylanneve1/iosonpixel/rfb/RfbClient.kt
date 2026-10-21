package io.github.dylanneve1.iosonpixel.rfb

import android.net.LocalSocket
import android.net.LocalSocketAddress
import java.io.BufferedInputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.IOException
import java.util.concurrent.Executors

/**
 * Minimal RFB 3.8 client for QEMU's built-in VNC server over a unix socket.
 *
 * Only what QEMU needs: no auth, raw encoding, DesktopSize. Pixels are
 * requested as little-endian 0x00RRGGBB so they convert to Android ARGB with
 * a single OR. Touch is sent as an absolute pointer; Inferno's multitouch
 * device treats the left button as a finger on the glass.
 */
class RfbClient(private val socketPath: File, private val listener: Listener) {

    interface Listener {
        /** The framebuffer was (re)allocated; [pixels] is width*height ARGB. */
        fun onResize(width: Int, height: Int, pixels: IntArray)
        /** Pixels inside the given rectangle changed. Called on the reader thread. */
        fun onUpdate(x: Int, y: Int, w: Int, h: Int)
        fun onDisconnected(error: Throwable?)
    }

    @Volatile private var socket: LocalSocket? = null
    @Volatile private var closed = false
    private var out: DataOutputStream? = null
    private val writer = Executors.newSingleThreadExecutor()

    var width = 0; private set
    var height = 0; private set
    private var pixels = IntArray(0)
    private var raw = ByteArray(0)

    fun start() {
        Thread(::run, "rfb-reader").start()
    }

    fun close() {
        closed = true
        try { socket?.close() } catch (_: IOException) {}
        writer.shutdownNow()
    }

    /** Absolute pointer event in framebuffer coordinates. */
    fun pointer(x: Int, y: Int, pressed: Boolean) {
        val o = out ?: return
        writer.execute {
            try {
                synchronized(o) {
                    o.writeByte(5)
                    o.writeByte(if (pressed) 1 else 0)
                    o.writeShort(x.coerceIn(0, width - 1))
                    o.writeShort(y.coerceIn(0, height - 1))
                    o.flush()
                }
            } catch (_: IOException) {}
        }
    }

    private fun run() {
        var error: Throwable? = null
        try {
            val s = connectWithRetry()
            val input = DataInputStream(BufferedInputStream(s.inputStream, 1 shl 16))
            val output = DataOutputStream(s.outputStream)
            handshake(input, output)
            out = output
            requestUpdate(output, incremental = false)
            while (!closed) readMessage(input, output)
        } catch (t: Throwable) {
            if (!closed) error = t
        } finally {
            try { socket?.close() } catch (_: IOException) {}
            listener.onDisconnected(error)
        }
    }

    /** QEMU creates the socket a moment after the process starts. */
    private fun connectWithRetry(): LocalSocket {
        val deadline = System.currentTimeMillis() + CONNECT_TIMEOUT_MS
        while (true) {
            val s = LocalSocket()
            socket = s
            try {
                s.connect(LocalSocketAddress(socketPath.absolutePath, LocalSocketAddress.Namespace.FILESYSTEM))
                return s
            } catch (e: IOException) {
                s.close()
                if (closed || System.currentTimeMillis() > deadline) throw e
                Thread.sleep(500)
            }
        }
    }

    private fun handshake(input: DataInputStream, output: DataOutputStream) {
        val version = ByteArray(12).also { input.readFully(it) }
        if (!String(version).startsWith("RFB ")) throw IOException("not an RFB server")
        output.write("RFB 003.008\n".toByteArray())

        val count = input.readUnsignedByte()
        if (count == 0) throw IOException("server refused: " + readString(input))
        val types = ByteArray(count).also { input.readFully(it) }
        if (SEC_NONE !in types) throw IOException("server requires authentication")
        output.writeByte(SEC_NONE.toInt())
        if (input.readInt() != 0) throw IOException("security handshake failed: " + readString(input))

        output.writeByte(1) // shared session
        resize(input.readUnsignedShort(), input.readUnsignedShort())
        input.skipBytes(16) // server pixel format; we set our own below
        input.skipBytes(input.readInt()) // desktop name

        // SetPixelFormat: 32bpp, depth 24, little-endian, true colour, 0x00RRGGBB.
        output.writeByte(0); output.write(ByteArray(3))
        output.writeByte(32); output.writeByte(24); output.writeByte(0); output.writeByte(1)
        output.writeShort(255); output.writeShort(255); output.writeShort(255)
        output.writeByte(16); output.writeByte(8); output.writeByte(0)
        output.write(ByteArray(3))

        val encodings = intArrayOf(ENC_RAW, ENC_DESKTOP_SIZE, ENC_POINTER_TYPE_CHANGE)
        output.writeByte(2); output.writeByte(0); output.writeShort(encodings.size)
        encodings.forEach(output::writeInt)
        output.flush()
    }

    private fun readMessage(input: DataInputStream, output: DataOutputStream) {
        when (val type = input.readUnsignedByte()) {
            0 -> {
                input.skipBytes(1)
                val rects = input.readUnsignedShort()
                repeat(rects) { readRect(input) }
                requestUpdate(output, incremental = true)
            }
            1 -> { // SetColourMapEntries: unused with true colour, but must be consumed
                input.skipBytes(3)
                input.skipBytes(input.readUnsignedShort() * 6)
            }
            2 -> Unit // Bell
            3 -> { input.skipBytes(3); input.skipBytes(input.readInt()) } // ServerCutText
            else -> throw IOException("unsupported server message $type")
        }
    }

    private fun readRect(input: DataInputStream) {
        val x = input.readUnsignedShort()
        val y = input.readUnsignedShort()
        val w = input.readUnsignedShort()
        val h = input.readUnsignedShort()
        when (val enc = input.readInt()) {
            ENC_RAW -> {
                val n = w * h * 4
                if (raw.size < n) raw = ByteArray(n)
                input.readFully(raw, 0, n)
                var i = 0
                for (row in y until y + h) {
                    var p = row * width + x
                    for (col in 0 until w) {
                        pixels[p++] = OPAQUE or
                            ((raw[i + 2].toInt() and 0xFF) shl 16) or
                            ((raw[i + 1].toInt() and 0xFF) shl 8) or
                            (raw[i].toInt() and 0xFF)
                        i += 4
                    }
                }
                listener.onUpdate(x, y, w, h)
            }
            ENC_DESKTOP_SIZE -> resize(w, h)
            ENC_POINTER_TYPE_CHANGE -> Unit
            else -> throw IOException("unexpected encoding $enc")
        }
    }

    private fun resize(w: Int, h: Int) {
        width = w
        height = h
        pixels = IntArray(w * h) { OPAQUE }
        listener.onResize(w, h, pixels)
    }

    private fun requestUpdate(output: DataOutputStream, incremental: Boolean) {
        synchronized(output) {
            output.writeByte(3)
            output.writeByte(if (incremental) 1 else 0)
            output.writeShort(0); output.writeShort(0)
            output.writeShort(width); output.writeShort(height)
            output.flush()
        }
    }

    private fun readString(input: DataInputStream): String =
        ByteArray(input.readInt()).also { input.readFully(it) }.toString(Charsets.UTF_8)

    private companion object {
        const val CONNECT_TIMEOUT_MS = 120_000L
        const val SEC_NONE: Byte = 1
        const val ENC_RAW = 0
        const val ENC_DESKTOP_SIZE = -223
        const val ENC_POINTER_TYPE_CHANGE = -257
        const val OPAQUE = 0xFF shl 24
    }
}
