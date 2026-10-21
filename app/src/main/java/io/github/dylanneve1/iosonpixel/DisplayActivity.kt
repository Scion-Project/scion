package io.github.dylanneve1.iosonpixel

import android.app.Activity
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import io.github.dylanneve1.iosonpixel.Monitor.Button as Key
import io.github.dylanneve1.iosonpixel.rfb.RfbClient

/** Full-screen guest display plus the iPhone's hardware buttons. */
class DisplayActivity : Activity() {
    private lateinit var screen: ScreenView
    private var client: RfbClient? = null
    private lateinit var paths: AppPaths
    private val onState: () -> Unit = {
        if (VmState.status == VmState.Status.STOPPED && !attachOnly) {
            client?.close()
            client = null
            screen.placeholder = "VM stopped" + (VmState.lastExit?.let { " (exit $it)" } ?: "") + "; see the log"
        }
    }

    /** Developer hook: `--ez attach true` connects even if the app didn't start the VM. */
    private val attachOnly get() = intent.getBooleanExtra(EXTRA_ATTACH, false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        paths = AppPaths(this)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        screen = ScreenView(this)
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setBackgroundColor(Color.rgb(20, 20, 20))
            addButton("Vol−") { Monitor.press(paths, Key.VOLUME_DOWN) }
            addButton("Vol+") { Monitor.press(paths, Key.VOLUME_UP) }
            addButton("Home") { Monitor.press(paths, Key.HOME) }
            addButton("Apps") {
                // Double-press Home opens the app switcher.
                Monitor.press(paths, Key.HOME)
                Monitor.press(paths, Key.HOME)
            }
            addButton("Power", onLong = { Monitor.press(paths, Key.POWER, holdMs = 3000) }) {
                Monitor.press(paths, Key.POWER)
            }
        }
        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(screen, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))
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
        super.onStop()
    }

    private fun connect() {
        if (VmState.status == VmState.Status.STOPPED && !attachOnly) {
            screen.placeholder = "VM is not running"
            return
        }
        val c = RfbClient(paths.vncSocket, screen)
        screen.client = c
        screen.onDisconnect = { err ->
            screen.placeholder = if (err != null) "Display disconnected: ${err.message}" else "Display closed"
        }
        client = c
        c.start()
    }

    private fun LinearLayout.addButton(label: String, onLong: (() -> Unit)? = null, onClick: () -> Unit) {
        addView(Button(context).apply {
            text = label
            isAllCaps = false
            setOnClickListener { onClick() }
            if (onLong != null) setOnLongClickListener { onLong(); true }
        }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
    }

    companion object {
        const val EXTRA_ATTACH = "attach"
    }
}
