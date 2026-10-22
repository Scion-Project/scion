package io.github.dylanneve1.iosonpixel

import io.github.dylanneve1.iosonpixel.EdgeMap.Box
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EdgeMapTest {
    @Test fun fitKeepsAspectInsideTheBorder() {
        // 1080x2400 view, 60 px border: 960x2280 available; a 1125x2436 guest is width-bound.
        val b = EdgeMap.fit(1080, 2400, 60, 1125, 2436)
        assertEquals(960, b.width)
        assertEquals((2436 * (960f / 1125)).toInt(), b.height)
        assertEquals(60, b.left)
        assertEquals((2400 - b.height) / 2, b.top)
    }

    @Test fun fitWithoutBorderFillsTheBoundAxis() {
        val b = EdgeMap.fit(1000, 500, 0, 200, 100)
        assertEquals(Box(0, 0, 1000, 500), b)
    }

    @Test fun fitIsEmptyWithoutAFramebufferOrRoom() {
        assertTrue(EdgeMap.fit(100, 100, 0, 0, 0).isEmpty)
        assertTrue(EdgeMap.fit(100, 100, 50, 10, 10).isEmpty)
    }

    @Test fun touchInTheBorderStartsOnTheEdge() {
        // 40 px left of the image: snapped to x = 0.
        val o = EdgeMap.startOffset(-40f, 1000f, 60f)
        assertEquals(0f, EdgeMap.map(-40f, o, 1000f), 0f)
        // Below the image: snapped to y = max.
        val ob = EdgeMap.startOffset(1030f, 1000f, 60f)
        assertEquals(1000f, EdgeMap.map(1030f, ob, 1000f), 0f)
    }

    @Test fun touchJustInsideTheImageSnapsToo() {
        assertEquals(1000f, EdgeMap.map(980f, EdgeMap.startOffset(980f, 1000f, 60f), 1000f), 0f)
        assertEquals(0f, EdgeMap.map(59f, EdgeMap.startOffset(59f, 1000f, 60f), 1000f), 0f)
    }

    @Test fun touchInTheMiddleIsUnchanged() {
        assertEquals(0f, EdgeMap.startOffset(500f, 1000f, 60f), 0f)
        assertEquals(500f, EdgeMap.map(500f, 0f, 1000f), 0f)
    }

    @Test fun movesKeepTheOffsetAndStayInTheGuest() {
        // Swipe up from 30 px below the image.
        val o = EdgeMap.startOffset(1030f, 1000f, 60f)
        assertEquals(800f, EdgeMap.map(830f, o, 1000f), 0f)
        assertEquals(0f, EdgeMap.map(-500f, o, 1000f), 0f)
        assertEquals(1000f, EdgeMap.map(1060f, o, 1000f), 0f)
    }

    @Test fun snappingOffOnlyClamps() {
        val o = EdgeMap.startOffset(-40f, 1000f, 60f, enabled = false)
        assertEquals(0f, o, 0f)
        assertEquals(0f, EdgeMap.map(-40f, o, 1000f), 0f)
        assertEquals(20f, EdgeMap.map(20f, o, 1000f), 0f)
    }
}
