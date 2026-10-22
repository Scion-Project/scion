package io.github.dylanneve1.iosonpixel

/**
 * Pure geometry for the display screen's border and edge snapping (no Android
 * types, so it runs in JVM unit tests).
 *
 * Android owns the physical screen edges for its own gestures, so the guest
 * image is drawn inset by a border that belongs to the app. A touch that
 * starts in the border, or within the snap distance inside the image, is
 * moved to start exactly on the nearest guest edge, so the guest sees an edge
 * swipe. Each axis snaps on its own; the offset found at the first contact is
 * kept for the rest of that finger's moves, clamped to the guest area.
 */
object EdgeMap {
    /** A rectangle in view pixels, [left, right) x [top, bottom). */
    data class Box(val left: Int, val top: Int, val right: Int, val bottom: Int) {
        val width get() = right - left
        val height get() = bottom - top
        val isEmpty get() = width <= 0 || height <= 0
        fun contains(x: Float, y: Float) = x >= left && x < right && y >= top && y < bottom
    }

    /**
     * Where an [fbW] x [fbH] framebuffer is drawn in a [viewW] x [viewH] view
     * with a [border] (px) on every side: scaled to fit, aspect kept, centred.
     * Empty if the framebuffer size is unknown or there is no room.
     */
    fun fit(viewW: Int, viewH: Int, border: Int, fbW: Int, fbH: Int): Box {
        val aw = viewW - 2 * border
        val ah = viewH - 2 * border
        if (fbW <= 0 || fbH <= 0 || aw <= 0 || ah <= 0) return Box(0, 0, 0, 0)
        val scale = minOf(aw.toFloat() / fbW, ah.toFloat() / fbH)
        val w = (fbW * scale).toInt()
        val h = (fbH * scale).toInt()
        val left = (viewW - w) / 2
        val top = (viewH - h) / 2
        return Box(left, top, left + w, top + h)
    }

    /**
     * The offset to keep for a finger whose first contact is at [local] (relative to
     * the guest image's start on one axis, may be negative or past [size] in the
     * border). Within [snap] of an edge (or outside the image) it moves the contact
     * onto that edge (0 or [size]); otherwise 0. With snapping off, always 0.
     */
    fun startOffset(local: Float, size: Float, snap: Float, enabled: Boolean = true): Float = when {
        !enabled || size <= 0f -> 0f
        local < snap -> -local
        local > size - snap -> size - local
        else -> 0f
    }

    /** A finger at [local] with the [offset] from its first contact, clamped to 0..[size]. */
    fun map(local: Float, offset: Float, size: Float): Float = (local + offset).coerceIn(0f, maxOf(size, 0f))
}
