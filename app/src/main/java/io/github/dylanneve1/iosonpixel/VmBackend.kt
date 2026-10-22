package io.github.dylanneve1.iosonpixel

import android.content.Context
import android.view.MotionEvent
import android.view.Surface

/**
 * The engine that runs the guest. VmService, MainActivity and DisplayActivity
 * only talk to this; which implementation they get is [VmSettings.backend].
 *
 * [QemuBackend] emulates the iPhone in software; [AvfBackend] runs a custom
 * kernel under the Android Virtualization Framework (pKVM), see scion-avf.
 */
interface VmBackend {
    val kind: Backend

    /** Readiness lines for the main screen, in display order. */
    fun checks(): List<Check>

    /** Problems that stop [start]; empty means it may launch. */
    fun problems(): List<String>

    /**
     * Launches the guest. Progress and errors go to [log]; returns null if the
     * launch failed (the reason has been logged).
     */
    fun start(settings: VmSettings, log: (String) -> Unit): Instance?

    /** False if the guest has no display at all, so the UI shouldn't offer one. */
    val hasDisplay: Boolean get() = true

    /** A framebuffer + pointer connection to the running guest, not yet started. */
    fun openDisplay(listener: VmDisplay.Listener): VmDisplay

    /** If the backend draws straight into a Surface, that display; else null and [openDisplay] is used. */
    fun surfaceDisplay(): SurfaceDisplay? = null

    /** Multi-touch input to the running guest, not yet started; null if this backend has none. */
    fun openTouch(): VmTouch? = null

    /** Presses a hardware button for [holdMs] without blocking the caller. */
    fun press(button: HardwareButton, holdMs: Int = 100)

    /** One running guest. */
    interface Instance {
        val isAlive: Boolean

        /** Feeds console output to [onLine] until the guest exits; returns its exit code. Blocks. */
        fun pump(onLine: (String) -> Unit): Int

        /** Asks the guest to shut down cleanly, killing it if it doesn't. Blocks. */
        fun stop()

        /** Kills the guest immediately. */
        fun kill()
    }

    data class Check(val ok: Boolean, val text: String)

    companion object {
        fun create(ctx: Context, install: Install): VmBackend = when (install.backend) {
            Backend.QEMU -> QemuBackend(ctx, install)
            Backend.AVF -> AvfBackend(ctx, install)
        }

        /** The running VM's backend if there is one, else the selected install's. */
        fun current(ctx: Context): VmBackend = VmService.runningBackend ?: create(ctx, Installs.current(ctx))
    }
}

enum class Backend(val id: String, val label: String, val implemented: Boolean) {
    /** Inferno build bundled as libqemu-system-aarch64.so; software CPU emulation. */
    QEMU("qemu", "QEMU", true),
    /** Android Virtualization Framework (pKVM/crosvm), custom kernel; see scion-avf. */
    AVF("avf", "AVF", true);

    companion object {
        fun fromId(id: String?): Backend? = entries.firstOrNull { it.id == id }
    }
}

/** The guest's display: framebuffer updates in, absolute pointer events out. */
interface VmDisplay {
    interface Listener {
        /** The framebuffer was (re)allocated; [pixels] is width*height ARGB. */
        fun onResize(width: Int, height: Int, pixels: IntArray)
        /** Pixels inside the given rectangle changed. Called on the reader thread. */
        fun onUpdate(x: Int, y: Int, w: Int, h: Int)
        fun onDisconnected(error: Throwable?)
    }

    fun start()
    fun close()

    /** Absolute pointer event in framebuffer coordinates; [pressed] = finger on the glass. */
    fun pointer(x: Int, y: Int, pressed: Boolean)
}

/**
 * A guest display drawn by the VM itself into a Surface the app hands over
 * (QEMU in-process with -display android), with input going straight in.
 */
interface SurfaceDisplay {
    /**
     * Draw into [surface] from now on, or stop if null; when this returns the
     * previous Surface is no longer drawn into. [onSize] gets the guest
     * framebuffer size now (if known) and on every change, on any thread.
     */
    fun setSurface(surface: Surface?, onSize: ((Int, Int) -> Unit)?)

    /** A finger at ([x], [y]) in a [w] x [h] area covering the framebuffer. */
    fun touch(x: Int, y: Int, w: Int, h: Int, down: Boolean)

    /** Press or release a key, by QEMU key name (as for the monitor's sendkey). */
    fun key(name: String, down: Boolean)

    /**
     * The whole [event] (every pointer, for multi-touch) in a [w] x [h] area covering the
     * framebuffer. Return false to get [touch] for the first pointer instead.
     */
    fun motion(event: MotionEvent, w: Int, h: Int): Boolean = false
}

/**
 * Multi-touch input: each finger is its own contact. While [available] is
 * false, touch falls back to the single [VmDisplay.pointer].
 */
interface VmTouch {
    /** Contacts are accepted right now. May start a (re)connect attempt. */
    val available: Boolean

    fun start()
    fun close()

    /** One input frame: every contact that changed, in order. */
    fun send(events: List<Event>)

    enum class Type(val qmp: String) { BEGIN("begin"), UPDATE("update"), END("end"), CANCEL("cancel") }

    /**
     * A contact in [slot] (0 until [SLOTS]); [trackingId] is unique per touch.
     * [x], [y] span the screen as 0..[MAX] (QEMU's absolute axis range).
     */
    data class Event(val type: Type, val slot: Int, val trackingId: Int, val x: Int, val y: Int)

    companion object {
        /** QEMU's INPUT_EVENT_SLOTS_MAX. */
        const val SLOTS = 10
        const val MAX = 0x7FFF
    }
}

/** The iPhone's physical buttons. */
enum class HardwareButton { POWER, HOME, VOLUME_DOWN, VOLUME_UP, RINGER }
