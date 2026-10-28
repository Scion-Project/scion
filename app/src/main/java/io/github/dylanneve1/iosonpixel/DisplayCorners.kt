package io.github.dylanneve1.iosonpixel

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.view.View

/**
 * The iPhone X's rounded screen corners, over the guest's picture (Settings → Display). iOS lays
 * itself out for a screen whose corners the hardware rounds -- 39 pt of a 375 pt wide screen, 117 of
 * its 1125 pixels -- so drawn on a square one some of what it puts near a corner (the status bar's
 * ends, the home indicator's surroundings, sheets' and the dock's edges) sits oddly against it.
 */
object DisplayCorners {
    /** The corners' radius, as a part of the screen's width (the iPhone X's: 39 / 375 pt). */
    const val RADIUS_OF_WIDTH = 39f / 375f

    /** What is outside the rounded rectangle [w] x [h] at [left], [top]: the four corners, to be painted over. */
    fun mask(left: Float, top: Float, w: Float, h: Float, into: Path = Path()): Path {
        val r = w * RADIUS_OF_WIDTH
        into.reset()
        into.fillType = Path.FillType.EVEN_ODD
        into.addRect(left, top, left + w, top + h, Path.Direction.CW)
        into.addRoundRect(RectF(left, top, left + w, top + h), r, r, Path.Direction.CW)
        return into
    }

    /** Black corners over a SurfaceView (a view of the window's draws over the hole it leaves), the guest's size. */
    class Overlay(context: Context) : View(context) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK }
        private val path = Path()

        init {
            setWillNotDraw(false)
        }

        override fun onDraw(canvas: Canvas) {
            if (width == 0 || height == 0) return
            canvas.drawPath(mask(0f, 0f, width.toFloat(), height.toFloat(), path), paint)
        }
    }
}
