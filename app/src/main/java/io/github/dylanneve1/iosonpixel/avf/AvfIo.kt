package io.github.dylanneve1.iosonpixel.avf

import android.view.KeyEvent
import io.github.dylanneve1.iosonpixel.HardwareButton
import io.github.dylanneve1.iosonpixel.SurfaceDisplay

/**
 * Display and input for an AVF guest: the seam between [io.github.dylanneve1.iosonpixel.AvfBackend]
 * and whatever drives the guest's screen, touch and keys. Only [None] (headless) exists so far.
 *
 * What AVF offers a custom VM is still being mapped (all `@hide`, so behind [AvfApi.EXEMPTIONS]):
 * on the image builder, `setDisplayConfig`/`setGpuConfig` (virtio-gpu + crosvm's Android display)
 * and `useTouch`/`useKeyboard`/`useSwitches` (virtio-input); at run time the crosvm display
 * service's `setSurface`, and `VirtualMachine.sendKeyEvent`/`sendMultiTouchEvent`/`sendSwitchEvent`.
 * An implementation of this interface is where that goes; the backend and UI won't need to change.
 *
 * Per run, AvfBackend calls, in order:
 *  1. [configure], before the VM config is built: ask for devices on the image builder;
 *  2. [attach], once `run()` has returned: connect, and return the guest's [Screen] (null = headless);
 *  3. [detach], after the VM has stopped or failed to start (also if [attach] was never reached).
 */
interface AvfIo {
    /** True if [attach] will return a [Screen]; the UI only offers the display when it does. */
    val hasDisplay: Boolean

    fun configure(image: AvfApi.ImageBuilder, log: (String) -> Unit)

    fun attach(vm: AvfApi.Vm, log: (String) -> Unit): Screen?

    fun detach()

    /**
     * A running guest's screen. As a [SurfaceDisplay], DisplayActivity shows it with SurfaceScreen
     * (the guest draws into the app's Surface). [SurfaceDisplay.motion] carries multi-touch.
     */
    interface Screen : SurfaceDisplay {
        /** An Android key event for the guest keyboard; false if not taken. */
        fun key(event: KeyEvent): Boolean = false

        /** One of the iPhone's buttons, pressed or released (e.g. as virtio-input keys or switches). */
        fun button(button: HardwareButton, down: Boolean)
    }

    /** No display or input devices: console only. */
    object None : AvfIo {
        override val hasDisplay = false
        override fun configure(image: AvfApi.ImageBuilder, log: (String) -> Unit) = Unit
        override fun attach(vm: AvfApi.Vm, log: (String) -> Unit): Screen? = null
        override fun detach() = Unit
    }
}
