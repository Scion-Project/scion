package io.github.dylanneve1.iosonpixel

import android.app.Activity
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.widget.LinearLayout
import io.github.dylanneve1.iosonpixel.HardwareButton as Key

/**
 * Full-screen guest display plus the iPhone's hardware buttons. The display
 * is the VM's own Surface output when the backend has one (QEMU in-process),
 * else an RFB client ([ScreenView]).
 */
class DisplayActivity : Activity() {
    private lateinit var screen: ScreenView
    private var surfaceScreen: SurfaceScreen? = null
    private var client: VmDisplay? = null
    private var touch: VmTouch? = null
    private lateinit var backend: VmBackend
    private lateinit var ui: Ui
    private val onState: () -> Unit = {
        if (VmState.status == VmState.Status.STOPPED && !attachOnly) {
            client?.close()
            client = null
            placeholder("VM stopped" + (VmState.lastExit?.let { " (exit $it)" } ?: "") + "; see the log")
        }
    }

    private fun placeholder(text: String) {
        surfaceScreen?.let { it.placeholder = text } ?: run { screen.placeholder = text }
    }

    /** Developer hook: `--ez attach true` connects even if the app didn't start the VM. */
    private val attachOnly get() = intent.getBooleanExtra(EXTRA_ATTACH, false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        backend = VmBackend.current(this)
        ui = Ui(this)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        screen = ScreenView(this)
        surfaceScreen = backend.surfaceDisplay()?.let { SurfaceScreen(this, it) }
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setBackgroundColor(Color.BLACK)
            val pad = ui.dp(8)
            setPadding(pad, pad, pad, pad)
            addButton("Vol −") { backend.press(Key.VOLUME_DOWN) }
            addButton("Vol +") { backend.press(Key.VOLUME_UP) }
            addButton("Home") { backend.press(Key.HOME) }
            addButton("Apps") {
                // Double-press Home opens the app switcher.
                backend.press(Key.HOME)
                backend.press(Key.HOME)
            }
            addButton("Power", onLong = { backend.press(Key.POWER, holdMs = 3000) }) {
                backend.press(Key.POWER)
            }
        }
        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(surfaceScreen ?: screen, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))
            addView(bar, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        })

        window.insetsController?.apply {
            hide(WindowInsets.Type.systemBars())
            systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    override fun onStart() {
        super.onStart()
        VmState.observe(onState)
        connect()
    }

    override fun onStop() {
        VmState.unobserve(onState)
        client?.close()
        client = null
        touch?.close()
        touch = null
        screen.touch = null
        super.onStop()
    }

    private fun connect() {
        if (VmState.status == VmState.Status.STOPPED && !attachOnly) {
            placeholder("VM is not running")
            return
        }
        // The Surface display attaches itself when its Surface exists.
        if (surfaceScreen != null) return
        val c = backend.openDisplay(screen)
        screen.client = c
        screen.onDisconnect = { err ->
            screen.placeholder = if (err != null) "Display disconnected: ${err.message}" else "Display closed"
        }
        client = c
        c.start()
        touch = backend.openTouch()?.also { it.start() }
        screen.touch = touch
    }

    private fun LinearLayout.addButton(label: String, onLong: (() -> Unit)? = null, onClick: () -> Unit) {
        // Haptic tick on every press: the guest reacts slowly, so confirm the tap locally.
        addView(ui.button(label, Ui.Style.TONAL) {
            it.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
            onClick()
        }.apply {
            textSize = 14f
            setPadding(0, 0, 0, 0)
            if (onLong != null) setOnLongClickListener {
                it.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                onLong()
                true
            }
        }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f).apply {
            marginStart = ui.dp(3)
            marginEnd = ui.dp(3)
        })
    }

    companion object {
        const val EXTRA_ATTACH = "attach"
    }
}
