package io.github.dylanneve1.iosonpixel.avf

import android.os.Build
import android.os.Parcel
import android.os.ParcelFileDescriptor
import android.os.SharedMemory
import android.system.ErrnoException
import android.system.Os
import android.view.MotionEvent
import android.view.Surface
import io.github.dylanneve1.iosonpixel.HardwareButton
import java.io.FileOutputStream
import java.lang.reflect.Constructor
import java.lang.reflect.Field
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * The experimental AVF display ("Experimental AVF display" setting): the guest's screen and
 * touch, the way scion-avf's display test app does it (docs/DISPLAY-INPUT.md there).
 *
 *  - Screen: a shared framebuffer ([Scfb]). An ashmem region of the app's is added to the VM's
 *    raw config as a `CustomMemoryBackingFile`, so crosvm backs a slice of guest RAM with it; the
 *    guest is told where on its command line, and the app copies each published frame into the
 *    display's Surface ([ScfbNative]).
 *  - Touch: a virtio-input multi-touch device, added to the same raw config, whose socket the app
 *    writes evdev reports into.
 *  - Camera, with the per-install Camera setting ([camera]): a second shared region ([Sccam]) right
 *    below the framebuffer's, added the same way, that [SccamCamera] writes the phone's camera
 *    frames into. If it can't be set up, the display goes on without it.
 *  - GPU, with `gpu=scgpu` in avf.properties ([gpu]): a third region ([Scgpu]) below those, for the
 *    renderer this app runs itself (gfxstream, scgpu.c); the guest's frames are shown in an overlay
 *    over the framebuffer's Surface once the guest presents through it. If it can't be set up, the
 *    guest finds no GPU and draws in software.
 *  - Sound, unless `sound=off` in avf.properties ([sound]): one more region ([Scsnd]) below those, a
 *    ring the guest's kernel puts iOS's sound into and this app plays through AAudio (scsnd.c). If it
 *    can't be set up, iOS is silent as before.
 *
 * The Java API never fills those two raw-config fields, so for the one `createVm` call of this
 * run the app wraps the `IVirtualizationService` binder the framework uses (in this process, on
 * its own VM's config) and adds them there. virtmgr still checks the app's permissions and
 * SELinux still applies; if anything refuses, the VM boots console-only and the user is told
 * why ([UNAVAILABLE] + reason, through [notice]).
 */
class ScfbIo(
    private val width: Int,
    private val height: Int,
    private val notice: (String) -> Unit,
    private val camera: CameraSpec? = null,
    private val gpu: Boolean = false,
    private val sound: Boolean = false,
) : AvfIo {
    /**
     * The camera, when the install's Camera setting is on: which cameras the phone has (bit 0 back,
     * bit 1 front) and what starts serving the region once the VM runs ([SccamCamera]).
     */
    class CameraSpec(val cameras: Int, val start: (ByteBuffer, (String) -> Unit) -> AutoCloseable)

    override val hasDisplay: Boolean get() = unavailable() == null

    @Volatile override var configured = false
        private set

    private var log: (String) -> Unit = {}
    private var region: Scfb.Region? = null
    private var shm: SharedMemory? = null
    private var shmFd: ParcelFileDescriptor? = null
    private var map: ByteBuffer? = null
    private var touchOurs: ParcelFileDescriptor? = null
    private var touchTheirs: ParcelFileDescriptor? = null
    private var hook: Hook? = null
    private var screen: ScfbScreen? = null
    private var cam: Sccam.Region? = null
    private var camShm: SharedMemory? = null
    private var camFd: ParcelFileDescriptor? = null
    private var camMap: ByteBuffer? = null
    private var camRunner: AutoCloseable? = null
    private var gpuRegion: Scgpu.Region? = null
    private var gpuShm: SharedMemory? = null
    private var gpuFd: ParcelFileDescriptor? = null
    private var gpuMap: ByteBuffer? = null
    private var bellOurs: ParcelFileDescriptor? = null
    private var bellTheirs: ParcelFileDescriptor? = null
    private var sndRegion: Scsnd.Region? = null
    private var sndShm: SharedMemory? = null
    private var sndFd: ParcelFileDescriptor? = null
    private var sndMap: ByteBuffer? = null

    private fun fail(reason: String) {
        val text = UNAVAILABLE + reason
        log("avf: $text; booting console-only")
        notice("$text. The VM booted console-only.")
    }

    override fun configure(image: AvfApi.ImageBuilder, memoryBytes: Long, log: (String) -> Unit) {
        this.log = log
        configured = false
        unavailable()?.let { fail(it); return }
        val r = Scfb.region(memoryBytes, width, height).getOrElse { fail(it.message.orEmpty()); return }
        try {
            createRegion(r)
            cam = camera?.let { setupCamera(r, it) }
            gpuRegion = if (gpu) setupGpu(cam?.addr ?: r.addr, r) else null
            sndRegion = if (sound) setupSound(gpuRegion?.addr ?: cam?.addr ?: r.addr) else null
            val pair = ParcelFileDescriptor.createSocketPair()
            touchOurs = pair[0]
            touchTheirs = pair[1]
            // Blocking: reports go out whole from a writer thread (ScfbScreen), never from the UI
            // thread. A non-blocking write cut short split an evdev event and scrambled the stream.
            hook = Hook(hooks!!, r, cam).also { it.install() }
        } catch (e: Exception) {
            release()
            fail(describe(e))
            return
        }
        // Last, so the guest only hears of the region once everything else is in place.
        if (!(r.params + cam?.params.orEmpty() + gpuRegion?.params.orEmpty() + sndRegion?.params.orEmpty())
                .all { image.call("addParam", it) }) {
            release()
            fail("VirtualMachineCustomImageConfig.Builder.addParam refused the scfb.* arguments")
            return
        }
        region = r
        configured = true
        log("avf: display: ${r.width}x${r.height} shared framebuffer, ${r.size shr 20} MiB at guest 0x${r.addr.toString(16)}; " +
            "virtio-input multi-touch")
        cam?.let { log("avf: camera: ${it.size shr 20} MiB region at guest 0x${it.addr.toString(16)}, pictures ${it.width}x${it.height}") }
        gpuRegion?.let { log("avf: gpu: SCGPU, gfxstream in the app, ${it.size shr 20} MiB region at guest 0x${it.addr.toString(16)}, " + (if (bellOurs != null) "doorbell" else "polling")) }
        sndRegion?.let { log("avf: sound: SCSND, played by the app (AAudio), ${it.size shr 20} MiB region at guest 0x${it.addr.toString(16)}") }
    }

    /** The sound's region below [belowAddr], made, and the player started on it; null (and why, logged) if it can't be. */
    private fun setupSound(belowAddr: Long): Scsnd.Region? {
        val s = Scsnd.region(belowAddr).getOrElse {
            log("avf: sound off for this run: ${it.message}")
            return null
        }
        return try {
            val (sm, fd, b) = ashmem("scsnd", s.size)
            sndShm = sm
            sndFd = fd
            sndMap = b
            val rc = ScsndNative.start(b)
            if (rc != 0) throw IllegalStateException(ScsndNative.startError(rc))
            s
        } catch (e: Exception) {
            log("avf: sound off for this run: ${describe(e)}")
            releaseSound(started = false)
            null
        }
    }

    /** The GPU's region below [belowAddr], made, and the renderer started on it; null (and why, logged) if it can't be. */
    private fun setupGpu(belowAddr: Long, r: Scfb.Region): Scgpu.Region? {
        val g = Scgpu.region(belowAddr).getOrElse {
            log("avf: gpu off for this run: ${it.message}")
            return null
        }
        return try {
            val (sm, fd, b) = ashmem("scgpu", g.size)
            gpuShm = sm
            gpuFd = fd
            gpuMap = b
            // The doorbell, when this build can name an input device (the guest finds it by its name).
            if (hooks?.mtName != null) {
                val pair = ParcelFileDescriptor.createSocketPair()
                bellOurs = pair[0]
                bellTheirs = pair[1]
            }
            val rc = ScgpuNative.start(b, g.addr, r.width, r.height, bellOurs?.fd ?: -1)
            if (rc != 0) throw IllegalStateException(ScgpuNative.startError(rc))
            g
        } catch (e: Exception) {
            log("avf: gpu off for this run: ${describe(e)}")
            releaseGpu(started = false)
            null
        }
    }

    /** The camera's region below the framebuffer's, made and primed; null (and why, logged) if it can't be. */
    private fun setupCamera(r: Scfb.Region, spec: CameraSpec): Sccam.Region? {
        val c = Sccam.region(r).getOrElse {
            log("avf: camera off for this run: ${it.message}")
            return null
        }
        return try {
            val (sm, fd, b) = ashmem("sccam", c.size)
            camShm = sm
            camFd = fd
            camMap = b
            Sccam.prime(b, spec.cameras, System.nanoTime())
            c
        } catch (e: Exception) {
            log("avf: camera off for this run: ${describe(e)}")
            releaseCamera()
            null
        }
    }

    /** The ashmem region, mapped here and primed ([Scfb.prime]); its fd goes to crosvm. */
    private fun createRegion(r: Scfb.Region) {
        val (sm, pfd, b) = ashmem("scfb", r.size)
        shm = sm
        shmFd = pfd
        map = b
        Scfb.prime(b, System.nanoTime())
    }

    /** An ashmem region of [size] bytes: it, its fd (for crosvm) and its mapping here. Closes what it made if it fails. */
    private fun ashmem(name: String, size: Long): Triple<SharedMemory, ParcelFileDescriptor, ByteBuffer> {
        val sm = SharedMemory.create(name, size.toInt())
        var pfd: ParcelFileDescriptor? = null
        try {
            // SharedMemory has no public fd getter; its parcel form is the fd (a dup, ours to close).
            val p = Parcel.obtain()
            pfd = try {
                sm.writeToParcel(p, 0)
                p.setDataPosition(0)
                p.readFileDescriptor()
            } finally {
                p.recycle()
            }
            val kind = runCatching { Os.readlink("/proc/self/fd/${pfd!!.fd}") }.getOrDefault("?")
            // crosvm may map ashmem (every domain may), not an app's memfd (DISPLAY-INPUT.md, section 4).
            if (!kind.startsWith("/dev/ashmem")) throw IllegalStateException("shared memory is $kind, not ashmem, so crosvm can't map it")
            return Triple(sm, pfd!!, sm.mapReadWrite())
        } catch (e: Exception) {
            runCatching { pfd?.close() }
            runCatching { sm.close() }
            throw e
        }
    }

    override fun attach(vm: AvfApi.Vm, log: (String) -> Unit): AvfIo.Screen? {
        hook?.remove()
        hook = null
        // False also when the createVm hook had to drop the devices (virtmgr refused them).
        if (!configured) return null
        val c = camMap
        val spec = camera
        if (cam != null && c != null && spec != null) {
            camRunner = try {
                spec.start.invoke(c, log)
            } catch (e: Exception) {
                log("avf: camera: couldn't start: ${describe(e)}")
                null
            }
        }
        return ScfbScreen().also { screen = it }
    }

    override fun detach() {
        hook?.remove()
        hook = null
        screen?.let { s ->
            s.setSurface(null, null)
            s.closeTouch()
            s.stats()?.let { log("avf: display: $it") }
        }
        if (gpuRegion != null) {
            val (n, failed, busyNs) = ScgpuNative.stats().toList()
            log("avf: gpu: $n commands ($failed failed), %.1f s of them in the renderer".format(busyNs / 1e9))
        }
        if (sndRegion != null) {
            val st = ScsndNative.stats()
            log("avf: sound: %.1f s played in ${st[1]} streams; ${st[4]} underruns, %.2f s skipped, %.2f s dropped%s".format(
                st[0] / 48000.0, st[2] / 48000.0, st[3] / 48000.0, if (st[5] == 0L) " (the guest never took the region)" else ""))
        }
        screen = null
        configured = false
        release()
    }

    private fun releaseGpu(started: Boolean = gpuRegion != null) {
        if (started) runCatching { ScgpuNative.stop() }
        listOf(bellOurs, bellTheirs).forEach { runCatching { it?.close() } }
        bellOurs = null
        bellTheirs = null
        gpuMap?.let { runCatching { SharedMemory.unmap(it) } }
        gpuMap = null
        runCatching { gpuFd?.close() }
        gpuFd = null
        runCatching { gpuShm?.close() }
        gpuShm = null
        gpuRegion = null
    }

    private fun releaseSound(started: Boolean = sndRegion != null) {
        if (started) runCatching { ScsndNative.stop() }
        sndMap?.let { runCatching { SharedMemory.unmap(it) } }
        sndMap = null
        runCatching { sndFd?.close() }
        sndFd = null
        runCatching { sndShm?.close() }
        sndShm = null
        sndRegion = null
    }

    private fun releaseCamera() {
        runCatching { camRunner?.close() }
        camRunner = null
        camMap?.let { runCatching { SharedMemory.unmap(it) } }
        camMap = null
        runCatching { camFd?.close() }
        camFd = null
        runCatching { camShm?.close() }
        camShm = null
        cam = null
    }

    private fun release() {
        releaseCamera()
        releaseGpu()
        releaseSound()
        hook?.remove()
        hook = null
        map?.let { runCatching { SharedMemory.unmap(it) } }
        map = null
        listOf(shmFd, touchOurs, touchTheirs).forEach { runCatching { it?.close() } }
        shmFd = null
        touchOurs = null
        touchTheirs = null
        runCatching { shm?.close() }
        shm = null
        region = null
    }

    // ---- createVm hook ----------------------------------------------------------------

    /**
     * Wraps the binder of the framework's VirtualizationService singleton for one createVm call,
     * adding the region and the touch device to that call's raw config. [remove] puts the
     * original binder back.
     */
    private inner class Hook(private val h: Members, private val r: Scfb.Region, private val c: Sccam.Region?) {
        private var service: Any? = null // strong: the framework only keeps a weak reference
        private var original: Any? = null
        @Volatile private var armed = true

        fun install() {
            val s = h.getInstance.invoke(null) ?: throw IllegalStateException("VirtualizationService.getInstance() returned null")
            val orig = h.mBinder.get(s) ?: throw IllegalStateException("VirtualizationService has no binder")
            val proxy = Proxy.newProxyInstance(h.ivs.classLoader, arrayOf(h.ivs)) { _, m, args ->
                if (m.name == "createVm" && armed && args != null && args.isNotEmpty()) {
                    armed = false
                    createVm(orig, m, args)
                } else forward(orig, m, args)
            }
            h.mBinder.set(s, proxy)
            service = s
            original = orig
        }

        fun remove() {
            val s = service ?: return
            runCatching { h.mBinder.set(s, original) }
            service = null
            original = null
        }

        private fun forward(target: Any, m: Method, args: Array<out Any?>?): Any? = try {
            m.invoke(target, *(args ?: emptyArray()))
        } catch (e: InvocationTargetException) {
            throw e.targetException
        }

        /** createVm with the devices; if virtmgr refuses them, again as the app built it. */
        private fun createVm(target: Any, m: Method, args: Array<out Any?>): Any? {
            val undo = try {
                patch(args[0]!!)
            } catch (e: Exception) {
                configured = false
                fail("couldn't add the devices to the VM config: ${describe(e)}")
                return forward(target, m, args)
            }
            return try {
                forward(target, m, args)
            } catch (e: Exception) {
                undo()
                // The camera's or the GPU's region too much? The display without them, before nothing at all. (The
                // guest then finds no header where sccam.addr/scgpu.addr say: plain RAM, all zero, and boots
                // without a camera, and draws in software.)
                if (c != null || gpuRegion != null || sndRegion != null) {
                    log("avf: virtmgr refused the camera's, the GPU's or the sound's region (${describe(e)}); trying without them")
                    val undo2 = runCatching { patch(args[0]!!, withExtras = false) }.getOrNull()
                    if (undo2 != null) {
                        try {
                            return forward(target, m, args).also {
                                releaseCamera()
                                releaseGpu()
                                releaseSound()
                            }
                        } catch (e2: Exception) {
                            undo2()
                        }
                    }
                }
                configured = false
                fail("virtmgr refused the display devices: ${describe(e)}")
                forward(target, m, args)
            }
        }

        /** Appends the backing file(s) and the touch device; returns what puts the config back. */
        private fun patch(config: Any, withExtras: Boolean = true): () -> Unit {
            val raw = h.getRawConfig.invoke(config)!!
            val file = h.cmbfNew.newInstance()
            h.cmbfFile.set(file, shmFd)
            h.cmbfRangeStart.setLong(file, r.addr)
            h.cmbfSize.setLong(file, r.size)
            val mt = h.mtNew.newInstance()
            h.mtWidth.setInt(mt, r.width)
            h.mtHeight.setInt(mt, r.height)
            h.mtPfd.set(mt, touchTheirs)
            h.mtName?.set(mt, "scion-touch")
            val dev = h.multiTouch.invoke(null, mt)
            val oldFiles = h.rawFiles.get(raw)
            val oldInputs = h.rawInputs.get(raw)
            var files = append(h.rawFiles.type.componentType!!, oldFiles, file)
            if (withExtras && c != null && camFd != null) {
                val cf = h.cmbfNew.newInstance()
                h.cmbfFile.set(cf, camFd)
                h.cmbfRangeStart.setLong(cf, c.addr)
                h.cmbfSize.setLong(cf, c.size)
                files = append(h.rawFiles.type.componentType!!, files, cf)
            }
            val g = gpuRegion
            if (withExtras && g != null && gpuFd != null) {
                val gf = h.cmbfNew.newInstance()
                h.cmbfFile.set(gf, gpuFd)
                h.cmbfRangeStart.setLong(gf, g.addr)
                h.cmbfSize.setLong(gf, g.size)
                files = append(h.rawFiles.type.componentType!!, files, gf)
            }
            val sr = sndRegion
            if (withExtras && sr != null && sndFd != null) {
                val sf = h.cmbfNew.newInstance()
                h.cmbfFile.set(sf, sndFd)
                h.cmbfRangeStart.setLong(sf, sr.addr)
                h.cmbfSize.setLong(sf, sr.size)
                files = append(h.rawFiles.type.componentType!!, files, sf)
            }
            h.rawFiles.set(raw, files)
            var inputs = append(h.rawInputs.type.componentType!!, oldInputs, dev)
            val bell = bellTheirs
            if (withExtras && g != null && bell != null && h.mtName != null) {
                // SCGPU's doorbell: a tiny multi-touch device the guest's driver gives to SCGPU by its name
                val bt = h.mtNew.newInstance()
                h.mtWidth.setInt(bt, 16)
                h.mtHeight.setInt(bt, 16)
                h.mtPfd.set(bt, bell)
                h.mtName.set(bt, Scgpu.BELL_NAME)
                inputs = append(h.rawInputs.type.componentType!!, inputs, h.multiTouch.invoke(null, bt)!!)
            }
            h.rawInputs.set(raw, inputs)
            return {
                runCatching { h.rawFiles.set(raw, oldFiles) }
                runCatching { h.rawInputs.set(raw, oldInputs) }
            }
        }

        private fun append(type: Class<*>, old: Any?, item: Any): Any {
            val n = if (old == null) 0 else java.lang.reflect.Array.getLength(old)
            val a = java.lang.reflect.Array.newInstance(type, n + 1)
            for (i in 0 until n) java.lang.reflect.Array.set(a, i, java.lang.reflect.Array.get(old, i))
            java.lang.reflect.Array.set(a, n, item)
            return a
        }
    }

    // ---- the screen ---------------------------------------------------------------------

    /** Callback target for the native loop: the guest's framebuffer size. */
    class SizeSink(private val f: (Int, Int) -> Unit) {
        @Suppress("unused") // called from scfb_present.c
        fun onSize(w: Int, h: Int) = f(w, h)
    }

    private inner class ScfbScreen : AvfIo.Screen {
        private val r = region!!
        private val buffer = map!!
        private val touch = touchOurs!!
        private val touchQueue = TouchQueue()

        /** Writes queued touch reports whole, blocking while crosvm isn't reading (see [TouchQueue]). */
        private val touchWriter = Thread({
            val out = FileOutputStream(touch.fileDescriptor)
            while (true) {
                val report = touchQueue.take() ?: break
                try {
                    out.write(report)
                } catch (_: java.io.IOException) {
                    break   // the VM is gone
                }
            }
        }, "scfb-touch").apply {
            isDaemon = true
            start()
        }
        private val lock = Any()
        private var thread: Thread? = null
        private var control: ByteBuffer? = null
        /** frames, copy ns, max copy ns, seqlock waits, (unused), torn, partial frames, bytes copied, unchanged frames: scfb_present.c's control block. */
        private val totals = LongArray(9)

        override fun setSurface(surface: Surface?, onSize: ((Int, Int) -> Unit)?) = synchronized(lock) {
            stopPresenting()
            if (surface == null) return@synchronized
            val c = ByteBuffer.allocateDirect(128).order(ByteOrder.LITTLE_ENDIAN)
            control = c
            val sink = SizeSink { w, h -> onSize?.invoke(w, h) }
            thread = Thread({
                val rc = ScfbNative.presentLoop(surface, buffer, c, sink)
                if (rc < 0 && c.getInt(0) == 0) log("avf: display stopped: ${presentError(rc)}")
            }, "scfb-present").apply {
                priority = Thread.MAX_PRIORITY
                isDaemon = true
                start()
            }
        }

        private fun stopPresenting() {
            val c = control ?: return
            c.putInt(0, 1)
            thread?.join(STOP_TIMEOUT_MS)
            for (i in totals.indices) {
                val v = c.getLong(8 + 8 * i)
                totals[i] = if (i == 2) maxOf(totals[i], v) else totals[i] + v
            }
            thread = null
            control = null
        }

        /** SCGPU: the overlay goes over the framebuffer once the guest shows its frames through the host. */
        override fun overlayWanted(): Boolean = gpuRegion != null && ScgpuNative.presenting()

        override fun setOverlaySurface(surface: Surface?, width: Int, height: Int) {
            if (gpuRegion != null) ScgpuNative.setWindow(surface, width, height)
        }

        fun closeTouch() {
            touchQueue.close()
            if (touchQueue.coalesced + touchQueue.dropped > 0) {
                log("avf: touch: ${touchQueue.coalesced} moves merged while the guest was busy, ${touchQueue.dropped} reports dropped")
            }
        }

        fun stats(): String? {
            val frames = totals[0]
            if (frames == 0L) return null
            val (copyNs, maxNs, waits, _, torn) = totals.drop(1)
            val partial = totals[6]
            val bytes = totals[7]
            return ("$frames frames shown ($partial only their damage, ${totals[8]} more unchanged and skipped), copy avg %.2f ms, max %.2f ms, avg %.0f KiB a frame; " +
                "seqlock waits $waits, shown torn $torn").format(copyNs / 1e6 / frames, maxNs / 1e6, bytes / 1024.0 / frames)
        }

        override fun motion(event: MotionEvent, w: Int, h: Int): Boolean {
            val action = when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> Scfb.Action.DOWN
                MotionEvent.ACTION_POINTER_DOWN -> Scfb.Action.POINTER_DOWN
                MotionEvent.ACTION_MOVE -> Scfb.Action.MOVE
                MotionEvent.ACTION_POINTER_UP -> Scfb.Action.POINTER_UP
                MotionEvent.ACTION_UP -> Scfb.Action.UP
                MotionEvent.ACTION_CANCEL -> Scfb.Action.CANCEL
                else -> return true
            }
            val pointers = (0 until event.pointerCount).map { i ->
                Scfb.Pointer(
                    event.getPointerId(i).coerceIn(0, MAX_SLOT),
                    Scfb.scale(event.getX(i), w, r.width),
                    Scfb.scale(event.getY(i), h, r.height),
                )
            }
            touchQueue.offer(Scfb.touchReport(action, pointers, event.actionIndex, if (android.os.Build.VERSION.SDK_INT >= 34) event.eventTimeNanos / 1000 else event.eventTime * 1000),
                action == Scfb.Action.MOVE)
            return true
        }

        override fun touch(x: Int, y: Int, w: Int, h: Int, down: Boolean) = Unit

        override fun key(name: String, down: Boolean) = Unit

        // No keys device yet: the buttons have nothing to press.
        override fun button(button: HardwareButton, down: Boolean) = Unit
    }

    companion object {
        /** The prefix of every message saying the display is off for this run. */
        const val UNAVAILABLE = "AVF display unavailable on this Android build: "

        /**
         * The prefixes the display needs in `hidden_api_blacklist_exemptions`, on top of
         * [AvfApi.EXEMPTIONS] (scion-avf docs/DISPLAY-INPUT.md, section 5): the framework's
         * VirtualizationService (getInstance, mBinder) and the virtualizationservice AIDL types
         * (IVirtualizationService, VirtualMachineConfig.getRawConfig, VirtualMachineRawConfig,
         * CustomMemoryBackingFile, InputDevice).
         */
        val EXEMPTIONS = listOf(
            "Landroid/system/virtualmachine/VirtualizationService;",
            "Landroid/system/virtualizationservice/",
        )

        private const val STOP_TIMEOUT_MS = 1000L
        /** virtio-input multi-touch slots crosvm offers. */
        private const val MAX_SLOT = 9

        /** Why the display can't be used in this process, or null if it can. */
        fun unavailable(): String? = when {
            Build.VERSION.SDK_INT < 34 -> "needs Android 14 or later"
            hooks == null -> "non-SDK members not reachable: " + missing.joinToString(limit = 4) +
                " (hidden-API exemption not set, or this Android build changed them)"
            nativeError != null -> "the frame-copy library didn't load ($nativeError)"
            else -> null
        }

        private var missing: List<String> = emptyList()

        /** The non-SDK members, resolved once per process (an exemption applies to new processes). */
        private val hooks: Members? by lazy {
            val out = mutableListOf<String>()
            val m = Members.resolve(out)
            missing = out
            m
        }

        private val nativeError: String? by lazy {
            runCatching { System.loadLibrary("scfb") }.exceptionOrNull()?.let { it.message ?: it.javaClass.simpleName }
        }

        private fun presentError(rc: Int) = when (rc) {
            -1 -> "no Surface to draw into"
            -2 -> "the guest's framebuffer header points outside the shared region"
            -3 -> "the display's buffer queue refused to lock"
            -4 -> "the display's buffer queue refused the guest's size or pixel format"
            -5 -> "the guest uses a pixel format other than XR24 (BGRX) or XB24 (RGBX)"
            else -> "error $rc"
        }

        private fun describe(t: Throwable): String {
            val c = if (t is InvocationTargetException) t.targetException ?: t else t
            return when (c) {
                is ErrnoException -> c.message.orEmpty()
                else -> "${c.javaClass.simpleName}: ${c.message}"
            }
        }
    }

    /** Every non-SDK member the display uses. [resolve] checks each one exists first. */
    private class Members(
        val getInstance: Method,
        val mBinder: Field,
        val ivs: Class<*>,
        val getRawConfig: Method,
        val rawFiles: Field,
        val rawInputs: Field,
        val cmbfNew: Constructor<*>,
        val cmbfFile: Field,
        val cmbfRangeStart: Field,
        val cmbfSize: Field,
        val multiTouch: Method,
        val mtNew: Constructor<*>,
        val mtWidth: Field,
        val mtHeight: Field,
        val mtPfd: Field,
        /** Optional: older builds have no name. */
        val mtName: Field?,
    ) {
        companion object {
            private const val VM = "android.system.virtualmachine"
            private const val VS = "android.system.virtualizationservice"

            /** The members, or null with what's missing added to [missing]. */
            fun resolve(missing: MutableList<String>): Members? {
                fun <T> get(name: String, f: () -> T): T? = try {
                    f()
                } catch (e: ReflectiveOperationException) {
                    missing += name
                    null
                } catch (e: LinkageError) {
                    missing += name
                    null
                }
                fun cls(n: String) = get(n) { Class.forName(n) }
                val vs = cls("$VM.VirtualizationService")
                val ivs = cls("$VS.IVirtualizationService")
                val cfg = cls("$VS.VirtualMachineConfig")
                val raw = cls("$VS.VirtualMachineRawConfig")
                val cmbf = cls("$VS.CustomMemoryBackingFile")
                val input = cls("$VS.InputDevice")
                val mt = cls("$VS.InputDevice\$MultiTouch")
                val getInstance = vs?.let { c -> get("VirtualizationService.getInstance()") { c.getDeclaredMethod("getInstance").apply { isAccessible = true } } }
                val mBinder = vs?.let { c -> get("VirtualizationService.mBinder") { c.getDeclaredField("mBinder").apply { isAccessible = true } } }
                val createVm = ivs?.let { c -> get("IVirtualizationService.createVm") { c.methods.firstOrNull { it.name == "createVm" } ?: throw NoSuchMethodException("createVm") } }
                val getRawConfig = cfg?.let { c -> get("VirtualMachineConfig.getRawConfig()") { c.getMethod("getRawConfig") } }
                val rawFiles = raw?.let { c -> get("VirtualMachineRawConfig.customMemoryBackingFiles") { c.getField("customMemoryBackingFiles") } }
                val rawInputs = raw?.let { c -> get("VirtualMachineRawConfig.inputDevices") { c.getField("inputDevices") } }
                val cmbfNew = cmbf?.let { c -> get("CustomMemoryBackingFile()") { c.getConstructor() } }
                val cmbfFile = cmbf?.let { c -> get("CustomMemoryBackingFile.file") { c.getField("file") } }
                val cmbfStart = cmbf?.let { c -> get("CustomMemoryBackingFile.rangeStart") { c.getField("rangeStart") } }
                val cmbfSize = cmbf?.let { c -> get("CustomMemoryBackingFile.size") { c.getField("size") } }
                val multiTouch = if (input != null && mt != null) get("InputDevice.multiTouch()") { input.getMethod("multiTouch", mt) } else null
                val mtNew = mt?.let { c -> get("InputDevice.MultiTouch()") { c.getConstructor() } }
                val mtWidth = mt?.let { c -> get("InputDevice.MultiTouch.width") { c.getField("width") } }
                val mtHeight = mt?.let { c -> get("InputDevice.MultiTouch.height") { c.getField("height") } }
                val mtPfd = mt?.let { c -> get("InputDevice.MultiTouch.pfd") { c.getField("pfd") } }
                val mtName = mt?.let { c -> runCatching { c.getField("name") }.getOrNull() }
                if (missing.isNotEmpty() || createVm == null) return null
                return Members(
                    getInstance!!, mBinder!!, ivs!!, getRawConfig!!, rawFiles!!, rawInputs!!, cmbfNew!!, cmbfFile!!,
                    cmbfStart!!, cmbfSize!!, multiTouch!!, mtNew!!, mtWidth!!, mtHeight!!, mtPfd!!, mtName,
                )
            }
        }
    }
}

/** The frame copy, native because the SDK's ImageWriter refuses BGRA_8888 (scfb_present.c). */
internal object ScfbNative {
    /**
     * Copies each frame the guest publishes in [region] into [surface] until [control]'s first
     * word is set; [control] also receives counters. Returns 0, or a negative reason.
     */
    @JvmStatic external fun presentLoop(surface: Surface, region: ByteBuffer, control: ByteBuffer, sink: ScfbIo.SizeSink): Int
}
