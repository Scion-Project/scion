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
 *
 * A display that shows the guest's frames through a renderer of its own (AVF
 * SCGPU) gets a second SurfaceView, over the first and the same size, once it
 * says it wants one ([SurfaceDisplay.overlayWanted], asked every
 * [OVERLAY_POLL_MS]); until then the overlay is gone and the first Surface has
 * the picture.
 */
@SuppressLint("ViewConstructor")
class SurfaceScreen(context: Context, private val display: SurfaceDisplay) :
    FrameLayout(context), SurfaceHolder.Callback {
    private val surfaceView = SurfaceView(context)
    private val overlay = SurfaceView(context).apply {
        setZOrderMediaOverlay(true)
        visibility = View.GONE
    }
    private val overlayCallback = object : SurfaceHolder.Callback {
        override fun surfaceCreated(holder: SurfaceHolder) = Unit
        override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) =
            display.setOverlaySurface(holder.surface, width, height)
        override fun surfaceDestroyed(holder: SurfaceHolder) = display.setOverlaySurface(null, 0, 0)
    }
    private val overlayPoll = object : Runnable {
        override fun run() {
            // (not before the guest's frame size is known: until then both views fill the whole area,
            // and the renderer's window would be made at that shape -- the picture squashed once the
            // views take the guest's shape, as after leaving the display and coming back)
            val want = display.overlayWanted() && fbW > 0
            val visible = overlay.visibility == View.VISIBLE
            if (want != visible) overlay.visibility = if (want) View.VISIBLE else View.GONE
            postDelayed(this, OVERLAY_POLL_MS)
        }
    }
    private val label = TextView(context).apply {
        setTextColor(Color.LTGRAY)
        textSize = 14f
        gravity = Gravity.CENTER
    }
    private var fbW = 0
    private var fbH = 0
    private val guest = android.graphics.Rect()
    val edge = EdgeInput(this)
    /** Black border (px) the guest image is inset by; touches there go to [edge]. */
    var border = 0
        set(value) { if (field != value) { field = value; requestLayout() } }

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
        addView(overlay)
        addView(label)
        placeholder = "Waiting for the VM display…"
        surfaceView.holder.addCallback(this)
        overlay.holder.addCallback(overlayCallback)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        post(overlayPoll)
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(overlayPoll)
        super.onDetachedFromWindow()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        val h = MeasureSpec.getSize(heightMeasureSpec)
        setMeasuredDimension(w, h)
        // Until the framebuffer size is known, fill the area inside the border.
        val b = EdgeMap.fit(w, h, border, fbW.takeIf { it > 0 } ?: (w - 2 * border), fbH.takeIf { it > 0 } ?: (h - 2 * border))
        val cw = maxOf(b.width, 1)
        val ch = maxOf(b.height, 1)
        surfaceView.measure(MeasureSpec.makeMeasureSpec(cw, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(ch, MeasureSpec.EXACTLY))
        overlay.measure(MeasureSpec.makeMeasureSpec(cw, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(ch, MeasureSpec.EXACTLY))
        label.measure(MeasureSpec.makeMeasureSpec(w, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(h, MeasureSpec.EXACTLY))
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val left = (r - l - surfaceView.measuredWidth) / 2
        val top = (b - t - surfaceView.measuredHeight) / 2
        surfaceView.layout(left, top, left + surfaceView.measuredWidth, top + surfaceView.measuredHeight)
        overlay.layout(left, top, left + surfaceView.measuredWidth, top + surfaceView.measuredHeight)
        label.layout(0, 0, r - l, b - t)
        guest.set(left, top, left + surfaceView.measuredWidth, top + surfaceView.measuredHeight)
        edge.excludeSideEdges(guest)
    }

    // The whole view, border included, takes touch; the SurfaceView itself never sees it.
    override fun onInterceptTouchEvent(ev: MotionEvent) = true

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (guest.isEmpty) return false
        // In guest-image coordinates, edge swipes snapped onto the edge.
        val e = edge.map(event, guest)
        try {
            return touch(e, guest.width(), guest.height())
        } finally {
            e.recycle()
        }
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

    private fun touch(e: MotionEvent, w: Int, h: Int): Boolean {
        if (display.motion(e, w, h)) return true
        val down = when (e.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> true
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> false
            else -> return true
        }
        val x = e.x.toInt().coerceIn(0, w - 1)
        val y = e.y.toInt().coerceIn(0, h - 1)
        display.touch(x, y, w, h, down)
        return true
    }

    private companion object {
        const val OVERLAY_POLL_MS = 100L
    }
}
