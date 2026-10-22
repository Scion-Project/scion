package io.github.dylanneve1.iosonpixel

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.view.MotionEvent
import android.view.View

/**
 * Shows the guest framebuffer scaled to fit and forwards touches. With
 * [touch] available every Android pointer becomes its own contact;
 * otherwise the first finger is an absolute pointer with the button held
 * (= one touch in the guest).
 */
class ScreenView(context: Context) : View(context), VmDisplay.Listener {
    var client: VmDisplay? = null
    var touch: VmTouch? = null
    var onDisconnect: ((Throwable?) -> Unit)? = null
    val edge = EdgeInput(this)
    /** Black border (px) the guest image is inset by; touches there go to [edge]. */
    var border = 0
        set(value) { if (field != value) { field = value; invalidate() } }

    private val lock = Any()
    private var bitmap: Bitmap? = null
    private var pixels = IntArray(0)
    private var fbW = 0
    private var fbH = 0
    private val dest = Rect()
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.LTGRAY
        textSize = 14f * resources.displayMetrics.scaledDensity
        textAlign = Paint.Align.CENTER
    }
    var placeholder = "Waiting for the VM display…"
        set(value) { field = value; postInvalidate() }

    init { setBackgroundColor(Color.BLACK) }

    override fun onResize(width: Int, height: Int, pixels: IntArray) {
        synchronized(lock) {
            fbW = width
            fbH = height
            this.pixels = pixels
            bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        }
        post { requestLayout(); invalidate() }
    }

    override fun onUpdate(x: Int, y: Int, w: Int, h: Int) {
        synchronized(lock) {
            bitmap?.setPixels(pixels, y * fbW + x, fbW, x, y, w, h)
        }
        postInvalidate()
    }

    override fun onDisconnected(error: Throwable?) {
        post { onDisconnect?.invoke(error) }
    }

    override fun onDraw(canvas: Canvas) {
        synchronized(lock) {
            val bmp = bitmap
            if (bmp == null) {
                canvas.drawText(placeholder, width / 2f, height / 2f, textPaint)
                return
            }
            computeDest()
            canvas.drawBitmap(bmp, null, dest, paint)
        }
    }

    private fun computeDest() {
        if (fbW == 0 || fbH == 0) return
        val b = EdgeMap.fit(width, height, border, fbW, fbH)
        if (b.left != dest.left || b.top != dest.top || b.right != dest.right || b.bottom != dest.bottom) {
            dest.set(b.left, b.top, b.right, b.bottom)
            edge.excludeSideEdges(dest)
        }
    }

    /** Pointer id held in each multitouch slot, or -1. */
    private val slotPointer = IntArray(VmTouch.SLOTS) { -1 }
    private val slotTracking = IntArray(VmTouch.SLOTS)
    private var nextTrackingId = 0
    /** The current gesture goes to [touch] (decided when its first finger lands). */
    private var multiTouch = false

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        val c = client ?: return false
        if (dest.isEmpty) return false
        if (event.actionMasked == MotionEvent.ACTION_DOWN) multiTouch = touch?.available == true
        // In guest-image coordinates, edge swipes snapped onto the edge.
        val e = edge.map(event, dest)
        try {
            val t = touch
            if (multiTouch && t != null) {
                t.send(contacts(e))
                return true
            }
            val x = (e.x * fbW / dest.width()).toInt().coerceIn(0, fbW - 1)
            val y = (e.y * fbH / dest.height()).toInt().coerceIn(0, fbH - 1)
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> c.pointer(x, y, pressed = true)
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> c.pointer(x, y, pressed = false)
            }
            return true
        } finally {
            e.recycle()
        }
    }

    /** The contacts [event] (in guest-image coordinates) changes, in QEMU's absolute axis range. */
    private fun contacts(event: MotionEvent): List<VmTouch.Event> {
        val out = ArrayList<VmTouch.Event>(event.pointerCount)
        fun emit(type: VmTouch.Type, index: Int) {
            val id = event.getPointerId(index)
            var slot = slotPointer.indexOf(id)
            if (type == VmTouch.Type.BEGIN) {
                if (slot >= 0) return
                slot = slotPointer.indexOf(-1)
                if (slot < 0) return // More fingers than slots: ignore the extra ones.
                slotPointer[slot] = id
                slotTracking[slot] = nextTrackingId++ and 0x7FFFFFFF
            } else if (slot < 0) {
                return
            }
            val x = (event.getX(index) * VmTouch.MAX / dest.width()).toInt()
            val y = (event.getY(index) * VmTouch.MAX / dest.height()).toInt()
            out += VmTouch.Event(type, slot, slotTracking[slot], x.coerceIn(0, VmTouch.MAX), y.coerceIn(0, VmTouch.MAX))
            if (type == VmTouch.Type.END || type == VmTouch.Type.CANCEL) slotPointer[slot] = -1
        }
        /** Cancels contacts no pointer holds any more (an UP or CANCEL we never saw). */
        fun dropStale() {
            for (slot in slotPointer.indices) {
                if (slotPointer[slot] >= 0) {
                    out += VmTouch.Event(VmTouch.Type.CANCEL, slot, slotTracking[slot], 0, 0)
                    slotPointer[slot] = -1
                }
            }
        }
        if (event.actionMasked == MotionEvent.ACTION_DOWN) dropStale()
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                for (i in 0 until event.pointerCount) {
                    if (i != event.actionIndex) emit(VmTouch.Type.UPDATE, i)
                }
                emit(VmTouch.Type.BEGIN, event.actionIndex)
            }
            MotionEvent.ACTION_MOVE -> for (i in 0 until event.pointerCount) emit(VmTouch.Type.UPDATE, i)
            MotionEvent.ACTION_POINTER_UP, MotionEvent.ACTION_UP -> {
                for (i in 0 until event.pointerCount) {
                    emit(if (i == event.actionIndex) VmTouch.Type.END else VmTouch.Type.UPDATE, i)
                }
            }
            MotionEvent.ACTION_CANCEL -> for (i in 0 until event.pointerCount) emit(VmTouch.Type.CANCEL, i)
        }
        if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) dropStale()
        return out
    }
}
