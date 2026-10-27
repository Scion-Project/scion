package io.github.dylanneve1.iosonpixel.avf

import java.nio.ByteBuffer

/**
 * SCSND: iOS's sound, played by this app (scsnd.c). AVF's own sound device (crosvm's virtio-snd,
 * avf.properties audio=1) failed to start two times in three on the Pixel and took the VM with it.
 * Instead the app lends the guest one more region of RAM, below the GPU's (or the camera's, or the
 * framebuffer's), as it lends those; the guest's kernel puts what iOS plays into a ring there, and
 * the app plays it through AAudio (hackios-xnu-kernel xnu/files/bsd/sys/hakios.scsnd.h).
 */
object Scsnd {
    /** A header and a ring of 1.4 s (the player keeps it near empty). */
    const val SIZE = 1L shl 20

    data class Region(val addr: Long, val size: Long) {
        val params: List<String>
            get() = listOf("scsnd.addr=0x" + addr.toString(16), "scsnd.size=0x" + size.toString(16))
    }

    /** The region right below [belowAddr] (the lowest of the others). */
    fun region(belowAddr: Long): Result<Region> {
        val addr = belowAddr - SIZE
        if (addr - Scfb.RAM_BASE < Scfb.MIN_GUEST_RAM) {
            return Result.failure(IllegalArgumentException("guest RAM is too small for the sound's region too"))
        }
        return Result.success(Region(addr, SIZE))
    }
}

/** The player (scsnd.c, in libscfb next to the frame copy). */
internal object ScsndNative {
    /** The region's header written and the player's thread started: before the VM starts. 0, or a negative reason. */
    @JvmStatic external fun start(region: ByteBuffer): Int

    @JvmStatic external fun stop()

    /** Frames played, streams started, frames skipped, frames the guest dropped, underruns, whether the guest's sink is up. */
    @JvmStatic external fun stats(): LongArray

    fun startError(rc: Int) = when (rc) {
        -3 -> "the region is too small"
        -5 -> "no thread for the player"
        -16 -> "it is already running"
        else -> "error $rc"
    }
}
