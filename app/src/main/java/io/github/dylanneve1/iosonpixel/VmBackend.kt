package io.github.dylanneve1.iosonpixel

import android.content.Context
import android.content.pm.PackageManager
import android.view.Surface

/**
 * The engine that runs the guest. VmService, MainActivity and DisplayActivity
 * only talk to this; which implementation they get is [VmSettings.backend].
 *
 * Today only [QemuBackend] exists. AVF (hardware virtualization) is developed
 * in ios-on-pixel-avf and plugs in here once it boots something.
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
        fun create(ctx: Context, kind: Backend): VmBackend = when (kind) {
            Backend.QEMU -> QemuBackend(ctx)
            // Not selectable yet (see Backend.implemented); VmSettings.load never returns it.
            Backend.AVF -> QemuBackend(ctx)
        }

        fun current(ctx: Context): VmBackend = create(ctx, VmSettings.load(ctx).backend)
    }
}

enum class Backend(val id: String, val label: String, val implemented: Boolean) {
    /** Inferno build bundled as libqemu-system-aarch64.so; software CPU emulation. */
    QEMU("qemu", "QEMU", true),
    /** Android Virtualization Framework (pKVM/crosvm); see ios-on-pixel-avf. */
    AVF("avf", "AVF", false);

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

/**
 * What the device offers for the AVF backend. Read-only: nothing here starts
 * a VM. MANAGE_VIRTUAL_MACHINE is a development permission, granted with
 * `adb shell pm grant <pkg> android.permission.MANAGE_VIRTUAL_MACHINE`.
 */
data class AvfSupport(val hasFeature: Boolean, val hasPermission: Boolean) {
    companion object {
        /** PackageManager.FEATURE_VIRTUALIZATION_FRAMEWORK (API 34); spelled out for minSdk 31. */
        const val FEATURE = "android.software.virtualization_framework"
        const val PERMISSION = "android.permission.MANAGE_VIRTUAL_MACHINE"

        fun check(ctx: Context) = AvfSupport(
            hasFeature = ctx.packageManager.hasSystemFeature(FEATURE),
            hasPermission = ctx.checkSelfPermission(PERMISSION) == PackageManager.PERMISSION_GRANTED,
        )
    }
}
