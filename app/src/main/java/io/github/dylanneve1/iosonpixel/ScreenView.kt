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
import io.github.dylanneve1.iosonpixel.rfb.RfbClient

/**
 * Shows the guest framebuffer scaled to fit, and turns a finger on the view
 * into an absolute pointer with the button held (= one touch in the guest).
 */
class ScreenView(context: Context) : View(context), RfbClient.Listener {
    var client: RfbClient? = null
    var onDisconnect: ((Throwable?) -> Unit)? = null

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
        val scale = minOf(width.toFloat() / fbW, height.toFloat() / fbH)
        val w = (fbW * scale).toInt()
        val h = (fbH * scale).toInt()
        val left = (width - w) / 2
        val top = (height - h) / 2
        dest.set(left, top, left + w, top + h)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        val c = client ?: return false
        if (dest.isEmpty) return false
        val x = ((event.x - dest.left) * fbW / dest.width()).toInt()
        val y = ((event.y - dest.top) * fbH / dest.height()).toInt()
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> c.pointer(x, y, pressed = true)
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> c.pointer(x, y, pressed = false)
        }
        return true
    }
}
