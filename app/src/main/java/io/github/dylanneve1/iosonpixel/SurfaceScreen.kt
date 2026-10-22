package io.github.dylanneve1.iosonpixel

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.view.Gravity
import android.view.MotionEvent
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.widget.FrameLayout
import android.widget.TextView

/**
 * The guest display for backends that draw into a Surface themselves
 * ([SurfaceDisplay], QEMU in-process): a SurfaceView kept at the guest
 * framebuffer's aspect ratio, centred on black, with touches passed straight
 * to the VM. Scaling is left to the compositor.
 */
@SuppressLint("ViewConstructor")
class SurfaceScreen(context: Context, private val display: SurfaceDisplay) :
    FrameLayout(context), SurfaceHolder.Callback {
    private val surfaceView = SurfaceView(context)
    private val label = TextView(context).apply {
        setTextColor(Color.LTGRAY)
        textSize = 14f
        gravity = Gravity.CENTER
    }
    private var fbW = 0
    private var fbH = 0

    /** Shown over the display until the first frame size arrives. */
    var placeholder: String
        get() = label.text.toString()
        set(value) {
            label.text = value
            label.visibility = View.VISIBLE
        }

    init {
        setBackgroundColor(Color.BLACK)
        addView(surfaceView)
        addView(label)
        placeholder = "Waiting for the VM display…"
        surfaceView.holder.addCallback(this)
        surfaceView.setOnTouchListener { v, e -> touch(v, e) }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        val h = MeasureSpec.getSize(heightMeasureSpec)
        setMeasuredDimension(w, h)
        var cw = w
        var ch = h
        if (fbW > 0 && fbH > 0) {
            val scale = minOf(w.toFloat() / fbW, h.toFloat() / fbH)
            cw = (fbW * scale).toInt()
            ch = (fbH * scale).toInt()
        }
        surfaceView.measure(MeasureSpec.makeMeasureSpec(cw, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(ch, MeasureSpec.EXACTLY))
        label.measure(MeasureSpec.makeMeasureSpec(w, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(h, MeasureSpec.EXACTLY))
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val left = (r - l - surfaceView.measuredWidth) / 2
        val top = (b - t - surfaceView.measuredHeight) / 2
        surfaceView.layout(left, top, left + surfaceView.measuredWidth, top + surfaceView.measuredHeight)
        label.layout(0, 0, r - l, b - t)
    }

    override fun surfaceCreated(holder: SurfaceHolder) = display.setSurface(holder.surface, ::onFbSize)

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) = Unit

    override fun surfaceDestroyed(holder: SurfaceHolder) = display.setSurface(null, null)

    /** Called on any thread with the guest framebuffer size. */
    private fun onFbSize(w: Int, h: Int) = post {
        label.visibility = View.GONE
        if (w != fbW || h != fbH) {
            fbW = w
            fbH = h
            requestLayout()
        }
    }

    private fun touch(v: View, e: MotionEvent): Boolean {
        if (display.motion(e, v.width, v.height)) return true
        val down = when (e.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> true
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> false
            else -> return true
        }
        val x = e.x.toInt().coerceIn(0, v.width - 1)
        val y = e.y.toInt().coerceIn(0, v.height - 1)
        display.touch(x, y, v.width, v.height, down)
        return true
    }
}
