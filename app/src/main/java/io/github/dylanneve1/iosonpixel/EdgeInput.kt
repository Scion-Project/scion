package io.github.dylanneve1.iosonpixel

import android.graphics.Rect
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration

/**
 * Touch for the display screen's border ([EdgeMap]): rewrites each event from
 * view coordinates to guest-image coordinates (0..width, 0..height), snapping
 * edge swipes onto the guest's edge, and spots the taps that show the button bar.
 */
class EdgeInput(private val view: View) {
    var snap = true
    var snapPx = 0f
    /** A quick one-finger tap in the border. */
    var onBorderTap: (() -> Unit)? = null
    /** A quick two-finger tap anywhere. */
    var onTwoFingerTap: (() -> Unit)? = null

    private val offX = FloatArray(MAX_IDS)
    private val offY = FloatArray(MAX_IDS)
    private val startX = FloatArray(MAX_IDS)
    private val startY = FloatArray(MAX_IDS)
    private val slop = ViewConfiguration.get(view.context).scaledTouchSlop.toFloat()
    private var inBorder = false
    private var maxPointers = 0
    private var moved = false

    /** [e] relative to [guest] (in [view]'s coordinates). The caller recycles the result. */
    fun map(e: MotionEvent, guest: Rect): MotionEvent {
        val w = guest.width().toFloat()
        val h = guest.height().toFloat()
        val action = e.actionMasked
        if (action == MotionEvent.ACTION_DOWN || action == MotionEvent.ACTION_POINTER_DOWN) {
            val i = e.actionIndex
            val id = e.getPointerId(i) and (MAX_IDS - 1)
            val lx = e.getX(i) - guest.left
            val ly = e.getY(i) - guest.top
            offX[id] = EdgeMap.startOffset(lx, w, snapPx, snap)
            offY[id] = EdgeMap.startOffset(ly, h, snapPx, snap)
            startX[id] = e.getX(i)
            startY[id] = e.getY(i)
            if (action == MotionEvent.ACTION_DOWN) {
                inBorder = !guest.contains(e.getX(i).toInt(), e.getY(i).toInt())
                maxPointers = 0
                moved = false
            }
        }
        maxPointers = maxOf(maxPointers, e.pointerCount)
        val n = e.pointerCount
        val props = Array(n) { MotionEvent.PointerProperties().also { p -> e.getPointerProperties(it, p) } }
        val coords = Array(n) { i ->
            MotionEvent.PointerCoords().also { c ->
                e.getPointerCoords(i, c)
                val id = e.getPointerId(i) and (MAX_IDS - 1)
                if (Math.abs(c.x - startX[id]) > slop || Math.abs(c.y - startY[id]) > slop) moved = true
                c.x = EdgeMap.map(c.x - guest.left, offX[id], w)
                c.y = EdgeMap.map(c.y - guest.top, offY[id], h)
            }
        }
        if (action == MotionEvent.ACTION_UP && !moved && e.eventTime - e.downTime < TAP_MS) {
            when {
                maxPointers == 2 -> onTwoFingerTap?.invoke()
                maxPointers == 1 && inBorder -> onBorderTap?.invoke()
            }
        }
        return MotionEvent.obtain(
            e.downTime, e.eventTime, e.action, n, props, coords, e.metaState, e.buttonState,
            e.xPrecision, e.yPrecision, e.deviceId, e.edgeFlags, e.source, e.flags,
        )
    }

    /**
     * Keeps Android's back gesture off the left and right edges next to [guest] (the
     * system honours at most 200 dp per edge, so the middle of each edge).
     */
    fun excludeSideEdges(guest: Rect) {
        val limit = (200 * view.resources.displayMetrics.density).toInt()
        val h = minOf(view.height, limit)
        val top = ((guest.top + guest.bottom) / 2 - h / 2).coerceIn(0, maxOf(0, view.height - h))
        val reach = snapPx.toInt()
        view.systemGestureExclusionRects = if (guest.isEmpty || !snap) emptyList() else listOf(
            Rect(0, top, guest.left + reach, top + h),
            Rect(guest.right - reach, top, view.width, top + h),
        )
    }

    private companion object {
        const val MAX_IDS = 32
        const val TAP_MS = 300L
    }
}
