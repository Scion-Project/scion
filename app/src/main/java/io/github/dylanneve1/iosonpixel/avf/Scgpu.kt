package io.github.dylanneve1.iosonpixel.avf

import android.view.Surface
import java.nio.ByteBuffer

/**
 * SCGPU: the guest's GPU in this app (scgpu.c). With `gpu=scgpu` in avf.properties, crosvm gets no
 * virtio-gpu; instead the app lends the guest a third region of RAM, below the camera's (or the
 * framebuffer's), as it lends those two, and runs gfxstream's host renderer itself on the commands
 * the guest leaves there. The guest's display frames then go from the GPU straight into a window of
 * the app's ([ScfbIo]'s overlay): nothing is read back into the guest and copied out again, which is
 * what made the GPU path laggy (hackios-xnu-kernel xnu/files/bsd/sys/hakios.scgpu.h).
 */
object Scgpu {
    /** A mailbox and a pool for the pipe's buffers and the transfers' staging (11 MiB for a whole screen). */
    const val SIZE = 64L shl 20

    data class Region(val addr: Long, val size: Long) {
        val params: List<String>
            get() = listOf("scgpu.addr=0x" + addr.toString(16), "scgpu.size=0x" + size.toString(16))
    }

    /** The region right below [belowAddr] (the camera's region, or the framebuffer's). */
    fun region(belowAddr: Long): Result<Region> {
        val addr = belowAddr - SIZE
        if (addr - Scfb.RAM_BASE < Scfb.MIN_GUEST_RAM) {
            return Result.failure(IllegalArgumentException("guest RAM is too small for the GPU's region too"))
        }
        return Result.success(Region(addr, SIZE))
    }
}

/** The renderer and its device thread (scgpu.c, in libscfb next to the frame copy). */
internal object ScgpuNative {
    /** gfxstream up and the region's header written: before the VM starts. 0, or a negative reason. */
    @JvmStatic external fun start(region: ByteBuffer, guestAddr: Long, fbWidth: Int, fbHeight: Int): Int

    /** Whether the guest has begun to show its frames through the host. */
    @JvmStatic external fun presenting(): Boolean

    /** The window the guest's frames go to, [width] x [height] pixels; null takes it away. */
    @JvmStatic external fun setWindow(surface: Surface?, width: Int, height: Int)

    @JvmStatic external fun stop()

    /** Commands, failed commands, ns spent on them. */
    @JvmStatic external fun stats(): LongArray

    fun startError(rc: Int) = when (rc) {
        -1 -> "libgfxstream_backend.so didn't load (is it in the APK? native/fetch-gfxstream.sh)"
        -2 -> "libgfxstream_backend.so lacks a stream_renderer function"
        -3 -> "the region is too small"
        -4 -> "gfxstream's renderer didn't start (EGL/GLES)"
        -5 -> "no thread for the device"
        -16 -> "it is already running"
        else -> "error $rc"
    }
}
