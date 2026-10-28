package io.github.dylanneve1.iosonpixel.avf

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * The pure part of the AVF camera ([SccamCamera]): where the camera region (SCCAM) goes in guest
 * RAM, what the guest is told on its command line, the header the app writes before boot, the
 * guest's requests, and the choice of capture size. Unit-tested (SccamTest).
 *
 * SCCAM is specified in hackios-xnu-kernel's avf/CAMERA.md: a second slice of guest RAM, like the
 * shared framebuffer ([Scfb]), backed by an ashmem region of the app's. The app writes the phone's
 * camera frames (NV12) into a ring of slots there; the guest's kernel (patch 1400) reads them and
 * gives them to iOS's camera stack. The control path is the header: the guest asks for frames, a
 * camera and a size; the app polls.
 */
object Sccam {
    /** The whole region: a 64 KiB header and three slots of up to 1920x1440 NV12. */
    const val SIZE = 16L shl 20
    const val HEADER_BYTES = 0x10000
    const val NSLOTS = 3
    const val SLOT_BYTES = ((SIZE - HEADER_BYTES) / NSLOTS).toInt() and 0xffff.inv()

    /** The pictures' size the guest is told (4:3, as the iPhone's sensors); the app captures the nearest it can. */
    const val DEFAULT_WIDTH = 1280
    const val DEFAULT_HEIGHT = 960

    const val MAGIC = 0x4d434353 // "SCCM"
    const val VERSION = 1

    // ---- header offsets (CAMERA.md, The region) -------------------------------------------
    const val OFF_MAGIC = 0x00
    const val OFF_VERSION = 0x04
    const val OFF_HEADER_BYTES = 0x08
    const val OFF_NSLOTS = 0x0c
    const val OFF_SLOT_BYTES = 0x10
    const val OFF_HOST_FLAGS = 0x14
    const val OFF_HOST_STATE = 0x18
    const val OFF_HOST_ERROR = 0x1c
    const val OFF_HOST_NS = 0x20
    const val OFF_REQ_ACK = 0x28
    const val OFF_CAMERAS = 0x2c
    const val OFF_FRAME_SEQ = 0x30
    const val OFF_LATEST = 0x38
    const val OFF_REQ_SEQ = 0x40
    const val OFF_REQ_ON = 0x44
    const val OFF_REQ_FACING = 0x48
    const val OFF_REQ_WIDTH = 0x4c
    const val OFF_REQ_HEIGHT = 0x50
    const val OFF_REQ_FPS = 0x54
    const val OFF_GUEST_READING = 0x58
    const val OFF_GUEST_NS = 0x60
    /** The guest's: iOS's flashlight, 0 (off) to 1000 (brightest) (patch 1402, /dev/hakios-torch). */
    const val OFF_TORCH = 0x68
    const val OFF_SLOTS = 0x100
    const val SLOT_DESC = 64

    // host_flags
    const val FLAG_READY = 1
    const val FLAG_DENIED = 2
    const val FLAG_NO_CAMERA = 4

    // host_state
    const val STATE_IDLE = 0
    const val STATE_OPENING = 1
    const val STATE_STREAMING = 2
    const val STATE_ERROR = 3

    const val FACING_BACK = 0
    const val FACING_FRONT = 1

    /** The guest's heartbeat older than this while it wants frames: it is gone, the camera closes. */
    const val GUEST_TIMEOUT_NS = 3_000_000_000L

    /** Where the region sits: directly below the framebuffer's [below] (SCCAM sits under SCFB). */
    data class Region(val addr: Long, val size: Long, val width: Int, val height: Int) {
        val params: List<String>
            get() = listOf(
                "sccam.addr=0x" + addr.toString(16),
                "sccam.size=0x" + size.toString(16),
                "sccam.w=$width",
                "sccam.h=$height",
            )
    }

    fun region(below: Scfb.Region, width: Int = DEFAULT_WIDTH, height: Int = DEFAULT_HEIGHT): Result<Region> {
        val addr = below.addr - SIZE
        if (addr - Scfb.RAM_BASE < Scfb.MIN_GUEST_RAM) {
            return Result.failure(IllegalArgumentException("guest RAM is too small for the camera region too"))
        }
        if (width !in 64..2048 || height !in 64..2048 || width * height * 3L / 2 > SLOT_BYTES) {
            return Result.failure(IllegalArgumentException("camera picture size ${width}x$height is out of range"))
        }
        return Result.success(Region(addr, SIZE, width and 3.inv(), height and 1.inv()))
    }

    /** The header as the app writes it before boot: everything else zero, so nothing stale reads as a frame. */
    fun prime(region: ByteBuffer, cameras: Int, nowNs: Long) {
        val b = region.duplicate().order(ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until OFF_SLOTS + NSLOTS * SLOT_DESC) b.put(i, 0)
        b.putInt(OFF_MAGIC, MAGIC)
        b.putInt(OFF_VERSION, VERSION)
        b.putInt(OFF_HEADER_BYTES, HEADER_BYTES)
        b.putInt(OFF_NSLOTS, NSLOTS)
        b.putInt(OFF_SLOT_BYTES, SLOT_BYTES)
        b.putInt(OFF_CAMERAS, cameras)
        b.putLong(OFF_HOST_NS, nowNs)
    }

    /** What the guest asks for, from its part of the header. */
    data class Request(val seq: Int, val on: Boolean, val facing: Int, val width: Int, val height: Int, val guestNs: Long)

    fun request(region: ByteBuffer): Request {
        val b = region.duplicate().order(ByteOrder.LITTLE_ENDIAN)
        return Request(
            seq = b.getInt(OFF_REQ_SEQ),
            on = b.getInt(OFF_REQ_ON) == 1,
            facing = if (b.getInt(OFF_REQ_FACING) == FACING_FRONT) FACING_FRONT else FACING_BACK,
            width = b.getInt(OFF_REQ_WIDTH),
            height = b.getInt(OFF_REQ_HEIGHT),
            guestNs = b.getLong(OFF_GUEST_NS),
        )
    }

    /**
     * The capture size for a guest asking for [wantW] x [wantH], of the camera's YUV sizes: the
     * same shape if there is one (else the nearest shape), then the smallest at least as big, else
     * the biggest; never more than a slot holds.
     */
    fun chooseSize(sizes: List<Pair<Int, Int>>, wantW: Int, wantH: Int): Pair<Int, Int>? {
        val fits = sizes.filter { (w, h) -> w > 0 && h > 0 && w % 2 == 0 && h % 2 == 0 && w.toLong() * h * 3 / 2 <= SLOT_BYTES }
        if (fits.isEmpty()) return null
        val w = if (wantW > 0) wantW else DEFAULT_WIDTH
        val h = if (wantH > 0) wantH else DEFAULT_HEIGHT
        val want = w.toDouble() / h
        fun shapeOff(s: Pair<Int, Int>) = Math.abs(Math.log(s.first.toDouble() / s.second / want))
        val best = fits.minOf { shapeOff(it) }
        val shaped = fits.filter { shapeOff(it) <= best + 0.01 }
        val area = w.toLong() * h
        return shaped.filter { it.first.toLong() * it.second >= area }.minByOrNull { it.first.toLong() * it.second }
            ?: shaped.maxByOrNull { it.first.toLong() * it.second }
    }

    /** The slot to write next: neither the latest frame's nor the one the guest is reading ([reading] is 1 + its slot). */
    fun nextSlot(latest: Int, reading: Int): Int = (0 until NSLOTS).first { it != latest && it != reading - 1 }
}
