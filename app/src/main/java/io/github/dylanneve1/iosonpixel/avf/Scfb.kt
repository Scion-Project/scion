package io.github.dylanneve1.iosonpixel.avf

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * The pure part of the AVF display ([ScfbIo]): where the shared framebuffer (SCFB) goes in
 * guest RAM, what the guest is told on its command line, the header the app writes before boot,
 * and the evdev stream for touch. Unit-tested (ScfbTest).
 *
 * SCFB is specified in scion-avf's display/SCFB.md: a linear framebuffer in a slice of guest RAM
 * that crosvm backs with an ashmem region of the app's, so the app sees every pixel the guest
 * draws without a copy on the guest side.
 */
object Scfb {
    /** crosvm's guest RAM starts here on arm64. */
    const val RAM_BASE = 0x8000_0000L

    /** crosvm puts the FDT in the last 2 MiB of RAM; the region stays clear of the top 4 MiB. */
    const val TOP_GAP = 4L shl 20

    /** Room for the header and two buffers of an iPhone-sized screen (2 x 10.5 MiB at 1125x2436). */
    const val MIN_REGION = 32L shl 20

    /** Pixel buffers start 64 KiB aligned, after the 4 KiB header page (SCFB.md). */
    const val BUFFER_ALIGN = 64L shl 10

    /** The guest must keep at least this much RAM for itself. */
    const val MIN_GUEST_RAM = 128L shl 20

    /** The iPhone 11's screen, what the iOS guest draws by default. */
    const val DEFAULT_WIDTH = 1125
    const val DEFAULT_HEIGHT = 2436

    const val MAX_DIM = 8192

    const val MAGIC = 0x42464353 // "SCFB"
    const val OFF_HOST_NS = 0x68
    const val HEADER_BYTES = 0x70

    /** Where the region sits in guest RAM and the screen size the guest is asked for. */
    data class Region(val addr: Long, val size: Long, val width: Int, val height: Int) {
        /** Kernel command-line arguments that tell the guest where to draw (SCFB.md, Plumbing). */
        val params: List<String>
            get() = listOf(
                "scfb.addr=0x" + addr.toString(16),
                "scfb.size=0x" + size.toString(16),
                "scfb.w=$width",
                "scfb.h=$height",
            )
    }

    /**
     * The region for a guest with [memoryBytes] of RAM drawing [width] x [height]: big enough for
     * two buffers, 2 MiB aligned in size, ending [TOP_GAP] below the top of RAM. A failure, with the
     * reason, if it doesn't fit.
     */
    fun region(memoryBytes: Long, width: Int, height: Int): Result<Region> {
        if (width !in 1..MAX_DIM || height !in 1..MAX_DIM) {
            return Result.failure(IllegalArgumentException("screen size ${width}x$height is out of range"))
        }
        val frame = (width.toLong() * height * 4 + BUFFER_ALIGN - 1) / BUFFER_ALIGN * BUFFER_ALIGN
        val needed = BUFFER_ALIGN + 2 * frame
        val size = maxOf(MIN_REGION, (needed + (2L shl 20) - 1) / (2L shl 20) * (2L shl 20))
        val top = memoryBytes / (2L shl 20) * (2L shl 20) - TOP_GAP
        val start = top - size
        if (start < MIN_GUEST_RAM) {
            return Result.failure(IllegalArgumentException(
                "guest RAM (${memoryBytes shr 20} MiB) is too small for a ${size shr 20} MiB framebuffer",
            ))
        }
        return Result.success(Region(RAM_BASE + start, size, width, height))
    }

    /**
     * Clears the header and stamps host_ns, before boot: the guest can tell the memory really is
     * shared, and nothing stale reads as a valid header.
     */
    fun prime(region: ByteBuffer, nowNs: Long) {
        val b = region.duplicate().order(ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until HEADER_BYTES) b.put(i, 0)
        b.putLong(OFF_HOST_NS, nowNs)
    }

    /** Parses avf.properties' `display=WxH`; null if malformed. */
    fun parseSize(s: String): Pair<Int, Int>? {
        val m = Regex("""\s*(\d{1,5})\s*[xX]\s*(\d{1,5})\s*""").matchEntire(s) ?: return null
        val w = m.groupValues[1].toInt()
        val h = m.groupValues[2].toInt()
        return (w to h).takeIf { w in 1..MAX_DIM && h in 1..MAX_DIM }
    }

    // ---- touch ------------------------------------------------------------------

    private const val EV_SYN = 0
    private const val EV_KEY = 1
    private const val EV_ABS = 3
    private const val BTN_TOUCH = 0x14a
    private const val ABS_X = 0x00
    private const val ABS_Y = 0x01
    private const val ABS_MT_SLOT = 0x2f
    private const val ABS_MT_POSITION_X = 0x35
    private const val ABS_MT_POSITION_Y = 0x36
    private const val ABS_MT_TRACKING_ID = 0x39

    /** What a touch report says, independent of MotionEvent (so it can be tested on the JVM). */
    enum class Action { DOWN, POINTER_DOWN, MOVE, POINTER_UP, UP, CANCEL }

    /** One finger: its pointer id (used as slot and tracking id) and position in framebuffer pixels. */
    data class Pointer(val id: Int, val x: Int, val y: Int)

    /**
     * One input report for crosvm's virtio-input multi-touch device, as the 8-byte evdev events
     * its socket takes (u16 type, u16 code, s32 value, little-endian), ending in SYN_REPORT.
     * The same stream the framework writes (VirtualMachine.sendMultiTouchEventInternal).
     * [changed] is the finger that went down or up; MOVE reports every finger in [pointers].
     */
    fun touchReport(action: Action, pointers: List<Pointer>, changed: Int): ByteArray {
        val ev = ArrayList<Triple<Int, Int, Int>>()
        fun e(type: Int, code: Int, value: Int) = ev.add(Triple(type, code, value))
        fun at(p: Pointer, tracking: Int) {
            e(EV_ABS, ABS_MT_SLOT, p.id)
            e(EV_ABS, ABS_MT_TRACKING_ID, tracking)
            e(EV_ABS, ABS_MT_POSITION_X, p.x)
            e(EV_ABS, ABS_MT_POSITION_Y, p.y)
        }
        when (action) {
            Action.MOVE -> pointers.forEach { at(it, it.id) }
            Action.DOWN, Action.POINTER_DOWN -> {
                if (action == Action.DOWN) e(EV_KEY, BTN_TOUCH, 1)
                at(pointers[changed], pointers[changed].id)
            }
            Action.POINTER_UP -> at(pointers[changed], -1)
            Action.UP -> {
                e(EV_KEY, BTN_TOUCH, 0)
                at(pointers[changed], -1)
            }
            Action.CANCEL -> {
                e(EV_KEY, BTN_TOUCH, 0)
                pointers.forEach { at(it, -1) }
            }
        }
        // Single-touch axes follow the first finger, for drivers that only read those.
        pointers.firstOrNull()?.let { e(EV_ABS, ABS_X, it.x); e(EV_ABS, ABS_Y, it.y) }
        e(EV_SYN, 0, 0)
        val b = ByteBuffer.allocate(8 * ev.size).order(ByteOrder.LITTLE_ENDIAN)
        for ((t, c, v) in ev) b.putShort(t.toShort()).putShort(c.toShort()).putInt(v)
        return b.array()
    }

    /** View coordinate [v] in a view [viewSize] long, as a framebuffer pixel in 0 until [fbSize]. */
    fun scale(v: Float, viewSize: Int, fbSize: Int): Int =
        if (viewSize <= 0) 0 else (v * fbSize / viewSize).toInt().coerceIn(0, fbSize - 1)
}
