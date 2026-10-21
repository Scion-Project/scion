package io.github.dylanneve1.iosonpixel

import android.net.LocalSocket
import android.net.LocalSocketAddress
import android.os.SystemClock
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader
import java.io.OutputStream
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException

/**
 * Multi-touch over QEMU's QMP socket: every [send] becomes one
 * `input-send-event` command with `mtt` events, which Inferno's touch
 * controller (mt-spi) turns into one touch frame.
 *
 * [available] stays false until a probe event has been accepted, so a QEMU
 * without a multitouch input handler (older Inferno) keeps using the VNC
 * pointer. Connecting is lazy and retried, since QEMU may still be starting.
 */
class QmpTouch(private val paths: AppPaths) : VmTouch {
    private val writer = Executors.newSingleThreadExecutor()
    @Volatile private var socket: LocalSocket? = null
    @Volatile private var out: OutputStream? = null
    @Volatile private var supported = false
    @Volatile private var unsupported = false
    @Volatile private var closed = false
    @Volatile private var lastAttempt = 0L
    @Volatile private var connecting = false

    override val available: Boolean
        get() {
            if (!supported && !unsupported) retry()
            return supported && out != null
        }

    override fun start() = retry(force = true)

    override fun close() {
        closed = true
        try { socket?.close() } catch (_: IOException) {}
        writer.shutdownNow()
    }

    override fun send(events: List<VmTouch.Event>) {
        if (events.isEmpty() || !supported) return
        val cmd = command(events)
        execute { write(cmd) }
    }

    private fun retry(force: Boolean = false) {
        val now = SystemClock.uptimeMillis()
        if (closed || connecting || (!force && now - lastAttempt < RETRY_MS)) return
        connecting = true
        lastAttempt = now
        execute {
            try { connect() } finally { connecting = false }
        }
    }

    private fun execute(task: () -> Unit) {
        try { writer.execute(task) } catch (_: RejectedExecutionException) {}
    }

    /** Runs on [writer]. Connects, negotiates and probes; a reader thread then drains replies. */
    private fun connect() {
        if (closed || out != null) return
        val s = LocalSocket()
        try {
            s.connect(LocalSocketAddress(paths.qmpSocket.absolutePath, LocalSocketAddress.Namespace.FILESYSTEM))
            s.soTimeout = 2000
            val input = BufferedReader(InputStreamReader(s.inputStream, Charsets.UTF_8))
            val o = s.outputStream
            input.readLine() ?: throw IOException("no QMP greeting")
            o.write("{\"execute\":\"qmp_capabilities\"}\n".toByteArray()); o.flush()
            reply(input)
            // A cancel on a slot nothing uses changes nothing in the guest, but
            // fails if no device consumes multitouch events.
            o.write(command(listOf(VmTouch.Event(VmTouch.Type.CANCEL, VmTouch.SLOTS - 1, -1, 0, 0)))
                .toByteArray()); o.flush()
            val probe = reply(input)
            val err = probe.optJSONObject("error")
            if (err != null) {
                val desc = err.optString("desc")
                Log.i(TAG, "multitouch probe failed: $desc")
                if (desc.contains("handler not found")) unsupported = true
                s.close()
                return
            }
            s.soTimeout = 0
            socket = s
            out = o
            supported = true
            Log.i(TAG, "multitouch via QMP")
            Thread({ drain(s, input) }, "qmp-touch-reader").start()
        } catch (e: IOException) {
            try { s.close() } catch (_: IOException) {}
        }
    }

    /** Next command reply, skipping asynchronous events. */
    private fun reply(input: BufferedReader): JSONObject {
        while (true) {
            val line = input.readLine() ?: throw IOException("QMP closed")
            val obj = JSONObject(line)
            if (!obj.has("event")) return obj
        }
    }

    private fun drain(s: LocalSocket, input: BufferedReader) {
        try {
            while (true) {
                val line = input.readLine() ?: break
                val err = JSONObject(line).optJSONObject("error") ?: continue
                Log.w(TAG, "input-send-event: ${err.optString("desc")}")
            }
        } catch (_: Exception) {
        }
        // QEMU went away (or we closed): fall back to VNC until a reconnect succeeds.
        if (socket === s) {
            supported = false
            out = null
            socket = null
        }
    }

    private fun write(cmd: String) {
        val o = out ?: return
        try {
            o.write(cmd.toByteArray()); o.flush()
        } catch (_: IOException) {
            try { socket?.close() } catch (_: IOException) {}
        }
    }

    private fun command(events: List<VmTouch.Event>): String {
        val list = JSONArray()
        for (e in events) {
            // The position goes after begin/update but before end, while the slot is still live.
            if (e.type != VmTouch.Type.END) list.put(mtt(e.type.qmp, e, "x", 0))
            if (e.type != VmTouch.Type.CANCEL) {
                list.put(mtt("data", e, "x", e.x))
                list.put(mtt("data", e, "y", e.y))
            }
            if (e.type == VmTouch.Type.END) list.put(mtt(e.type.qmp, e, "x", 0))
        }
        val args = JSONObject().put("events", list)
        return JSONObject().put("execute", "input-send-event").put("arguments", args).toString() + "\n"
    }

    private fun mtt(type: String, e: VmTouch.Event, axis: String, value: Int) = JSONObject()
        .put("type", "mtt")
        .put("data", JSONObject()
            .put("type", type)
            .put("slot", e.slot)
            .put("tracking-id", e.trackingId)
            .put("axis", axis)
            .put("value", value))

    private companion object {
        const val TAG = "QmpTouch"
        const val RETRY_MS = 2000L
    }
}
