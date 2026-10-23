package io.github.dylanneve1.iosonpixel.avf

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.ImageFormat
import android.hardware.camera2.CameraAccessException
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.SessionConfiguration
import android.media.Image
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.util.Range
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * The AVF camera (per-install "Camera" setting, with the experimental display): the phone's camera
 * for the guest, through the camera region ([Sccam], hackios-xnu-kernel avf/CAMERA.md).
 *
 * Polls the guest's requests in the region's header every [POLL_MS]; while the guest wants frames
 * (and still reads them, by its heartbeat) the camera it asks for (back or front) runs with Camera2
 * into an [ImageReader], and each frame is published into a slot ([SccamNative.publish]). The
 * camera is open only then: Android shows its camera indicator meanwhile, and only while Scion is
 * in the foreground (the camera is a while-in-use permission; in the background Android disconnects
 * it and this tries again every [RETRY_MS]). Without the CAMERA permission (the user grants it with
 * Android's own prompt, from the setting) the header says so and the guest gets black frames.
 *
 * Everything runs on one handler thread.
 */
class SccamCamera(
    private val ctx: Context,
    private val region: ByteBuffer,
    private val log: (String) -> Unit,
) : AutoCloseable {
    private val b = region.duplicate().order(ByteOrder.LITTLE_ENDIAN)
    private val thread = HandlerThread("sccam").apply { start() }
    private val handler = Handler(thread.looper)
    private val manager = ctx.getSystemService(CameraManager::class.java)
    private val cameras = cameras(ctx)

    @Volatile private var running = true
    private var device: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var reader: ImageReader? = null
    private var openFacing = -1
    private var openSize: Pair<Int, Int>? = null
    private var orientation = 0
    private var opening = false
    private var retryAt = 0L
    private var frame = 0L
    private var published = 0L
    private var failed = 0L
    private var lastGuestNs = 0L
    private var guestChangedAt = 0L
    private var lastAck = -1
    private var saidDenied = false
    private var lastFail: String? = null

    private val poll = object : Runnable {
        override fun run() {
            if (!running) return
            runCatching { tick() }.onFailure { log("avf: camera: ${it.javaClass.simpleName}: ${it.message}") }
            handler.postDelayed(this, POLL_MS)
        }
    }

    fun start() {
        handler.post(poll)
    }

    private fun granted() = ctx.checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED

    private fun tick() {
        val now = SystemClock.elapsedRealtime()
        b.putLong(Sccam.OFF_HOST_NS, System.nanoTime())
        val permitted = granted()
        b.putInt(Sccam.OFF_CAMERAS, cameras)
        b.putInt(Sccam.OFF_HOST_FLAGS, when {
            !permitted -> Sccam.FLAG_DENIED
            cameras == 0 -> Sccam.FLAG_NO_CAMERA
            else -> Sccam.FLAG_READY
        })
        val req = Sccam.request(region)
        if (req.seq != lastAck) {
            lastAck = req.seq
            b.putInt(Sccam.OFF_REQ_ACK, req.seq)
            log("avf: camera: the guest asks: ${if (req.on) "frames of the ${facingName(req.facing)} camera, ${req.width}x${req.height}" else "no frames"}")
        }
        // The guest's heartbeat is in its own clock: it is alive while the value changes.
        if (req.guestNs != lastGuestNs) {
            lastGuestNs = req.guestNs
            guestChangedAt = now
        }
        val alive = now - guestChangedAt < Sccam.GUEST_TIMEOUT_NS / 1_000_000
        if (req.on && !permitted && !saidDenied) {
            saidDenied = true
            log("avf: camera: the guest wants frames, but Scion has no camera permission (turn the Camera setting off and on to be asked)")
        }
        val want = req.on && alive && permitted && cameras != 0
        if (!want) {
            if (device != null || opening) close("the guest ${if (!req.on) "asked for no frames" else if (!alive) "stopped reading" else "may not have them"}")
            b.putInt(Sccam.OFF_HOST_STATE, Sccam.STATE_IDLE)
            return
        }
        val size = openSize
        val same = openFacing == req.facing && size != null && device != null
        if (device != null && !same) close("the guest asks for another camera")
        if (device == null && !opening && now >= retryAt) open(req)
    }

    private fun open(req: Sccam.Request) {
        val id = cameraId(req.facing) ?: run {
            fail("no ${facingName(req.facing)} camera", 0)
            return
        }
        val ch = manager.getCameraCharacteristics(id)
        val map = ch.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP) ?: return fail("camera $id has no stream configurations", 0)
        val sizes = map.getOutputSizes(ImageFormat.YUV_420_888)?.map { it.width to it.height }.orEmpty()
        val size = Sccam.chooseSize(sizes, req.width, req.height) ?: return fail("camera $id has no YUV size that fits", 0)
        orientation = ch.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 0
        val r = ImageReader.newInstance(size.first, size.second, ImageFormat.YUV_420_888, 3)
        r.setOnImageAvailableListener({ ir ->
            val image = runCatching { ir.acquireLatestImage() }.getOrNull() ?: return@setOnImageAvailableListener
            try {
                publish(image, req.facing)
            } finally {
                image.close()
            }
        }, handler)
        reader = r
        openFacing = req.facing
        openSize = size
        opening = true
        b.putInt(Sccam.OFF_HOST_STATE, Sccam.STATE_OPENING)
        log("avf: camera: opening the ${facingName(req.facing)} camera ($id) at ${size.first}x${size.second}, sensor at $orientation°")
        try {
            manager.openCamera(id, object : CameraDevice.StateCallback() {
                override fun onOpened(d: CameraDevice) {
                    if (!running || reader !== r) {
                        d.close()
                        return
                    }
                    device = d
                    startSession(d, r)
                }

                override fun onDisconnected(d: CameraDevice) {
                    if (device === d || reader === r) fail("the camera was disconnected (another app, or Scion went to the background)", 0)
                    d.close()
                }

                override fun onError(d: CameraDevice, error: Int) {
                    if (device === d || reader === r) fail("camera error $error", error)
                    d.close()
                }
            }, handler)
        } catch (e: SecurityException) {
            fail("no camera permission", 0)
        } catch (e: CameraAccessException) {
            fail("camera unavailable: ${e.message}", e.reason)
        } catch (e: IllegalArgumentException) {
            fail("camera $id: ${e.message}", 0)
        }
    }

    private fun startSession(d: CameraDevice, r: ImageReader) {
        val surface = r.surface
        val cb = object : CameraCaptureSession.StateCallback() {
            override fun onConfigured(s: CameraCaptureSession) {
                if (device !== d) {
                    s.close()
                    return
                }
                session = s
                try {
                    val rq = d.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                        addTarget(surface)
                        set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)
                        set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
                        set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, Range(15, 30))
                    }
                    s.setRepeatingRequest(rq.build(), null, handler)
                    opening = false
                    b.putInt(Sccam.OFF_HOST_STATE, Sccam.STATE_STREAMING)
                    b.putInt(Sccam.OFF_HOST_ERROR, 0)
                    lastFail = null
                    log("avf: camera: streaming")
                } catch (e: Exception) {
                    fail("the capture request failed: ${e.message}", 0)
                }
            }

            override fun onConfigureFailed(s: CameraCaptureSession) = fail("the capture session could not be configured", 0)
        }
        try {
            d.createCaptureSession(SessionConfiguration(
                SessionConfiguration.SESSION_REGULAR, listOf(OutputConfiguration(surface)), { handler.post(it) }, cb,
            ))
        } catch (e: Exception) {
            fail("the capture session failed: ${e.message}", 0)
        }
    }

    private fun publish(image: Image, facing: Int) {
        val p = image.planes
        if (p.size < 3) return
        val rc = SccamNative.publish(
            region, Sccam.HEADER_BYTES, Sccam.SLOT_BYTES, Sccam.NSLOTS, ++frame,
            p[0].buffer, p[0].rowStride, p[1].buffer, p[2].buffer, p[1].rowStride, p[1].pixelStride,
            image.width, image.height, facing, orientation, image.timestamp,
        )
        if (rc < 0) {
            frame--
            if (failed++ == 0L) log("avf: camera: a frame could not be published (error $rc, ${image.width}x${image.height})")
            return
        }
        published++
        if (published == 1L || published % 900 == 0L) log("avf: camera: frame $published published")
    }

    private fun fail(why: String, code: Int) {
        // (in the background it fails every RETRY_MS the same way: said once)
        if (why != lastFail) log("avf: camera: $why")
        lastFail = why
        b.putInt(Sccam.OFF_HOST_ERROR, code)
        closeDevice()
        b.putInt(Sccam.OFF_HOST_STATE, Sccam.STATE_ERROR)
        retryAt = SystemClock.elapsedRealtime() + RETRY_MS
    }

    private fun close(why: String) {
        log("avf: camera: closed ($why); $published frames published")
        closeDevice()
    }

    private fun closeDevice() {
        runCatching { session?.close() }
        runCatching { device?.close() }
        runCatching { reader?.close() }
        session = null
        device = null
        reader = null
        opening = false
        openFacing = -1
        openSize = null
    }

    /** Closes the camera and stops polling; waits (briefly) for the camera to be closed. */
    override fun close() {
        if (!running) return
        running = false
        val done = CountDownLatch(1)
        handler.post {
            if (device != null || opening) close("the VM stopped")
            b.putInt(Sccam.OFF_HOST_FLAGS, 0)
            b.putInt(Sccam.OFF_HOST_STATE, Sccam.STATE_IDLE)
            done.countDown()
        }
        done.await(STOP_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        thread.quitSafely()
    }

    private fun cameraId(facing: Int): String? {
        val lens = if (facing == Sccam.FACING_FRONT) CameraCharacteristics.LENS_FACING_FRONT else CameraCharacteristics.LENS_FACING_BACK
        return runCatching {
            manager.cameraIdList.firstOrNull { manager.getCameraCharacteristics(it).get(CameraCharacteristics.LENS_FACING) == lens }
        }.getOrNull()
    }

    private fun facingName(f: Int) = if (f == Sccam.FACING_FRONT) "front" else "back"

    companion object {
        const val POLL_MS = 50L
        const val RETRY_MS = 2000L
        private const val STOP_TIMEOUT_MS = 2000L

        /** Which cameras the phone has: bit 0 a back one, bit 1 a front one ([Sccam.OFF_CAMERAS]). */
        fun cameras(ctx: Context): Int = runCatching {
            val m = ctx.getSystemService(CameraManager::class.java)
            m.cameraIdList.fold(0) { acc, id ->
                acc or when (m.getCameraCharacteristics(id).get(CameraCharacteristics.LENS_FACING)) {
                    CameraCharacteristics.LENS_FACING_BACK -> 1
                    CameraCharacteristics.LENS_FACING_FRONT -> 2
                    else -> 0
                }
            }
        }.getOrDefault(0)
    }
}

/** The frame copy into a slot of the camera region (sccam_put.c), with the seqlock's ordering. */
internal object SccamNative {
    /** The slot written, or a negative reason (sccam_put.c). */
    @JvmStatic external fun publish(
        region: ByteBuffer, headerBytes: Int, slotBytes: Int, nslots: Int, frame: Long,
        y: ByteBuffer, yStride: Int, u: ByteBuffer, v: ByteBuffer, uvStride: Int, uvPixelStride: Int,
        width: Int, height: Int, facing: Int, orientation: Int, timeNs: Long,
    ): Int
}
