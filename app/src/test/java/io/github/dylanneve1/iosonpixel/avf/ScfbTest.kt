package io.github.dylanneve1.iosonpixel.avf

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class ScfbTest {
    private fun events(b: ByteArray): List<Triple<Int, Int, Int>> {
        val bb = ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN)
        return List(b.size / 8) { Triple(bb.short.toInt(), bb.short.toInt(), bb.int) }
    }

    @Test fun regionSitsBelowTheTopOfRamAndFitsTwoIphoneBuffers() {
        val r = Scfb.region(2048L shl 20, 1125, 2436).getOrThrow()
        assertEquals(32L shl 20, r.size)
        // 4 MiB clear of the top (crosvm's FDT), inside guest RAM.
        assertEquals(Scfb.RAM_BASE + (2048L shl 20) - (4L shl 20) - r.size, r.addr)
        assertTrue(Scfb.BUFFER_ALIGN + 2L * 1125 * 2436 * 4 <= r.size)
        assertEquals(0L, r.addr % (2L shl 20))
        assertEquals(
            listOf("scfb.addr=0x1fdc00000".replace("1fdc00000", (r.addr).toString(16)), "scfb.size=0x2000000", "scfb.w=1125", "scfb.h=2436"),
            r.params,
        )
    }

    @Test fun matchesTheDisplayTestAppsPlacement() {
        // display/app (512 MiB guest): 0x9dc00000, 32 MiB.
        val r = Scfb.region(512L shl 20, 1080, 2424).getOrThrow()
        assertEquals(0x9dc00000L, r.addr)
        assertEquals(0x2000000L, r.size)
    }

    @Test fun bigScreensGetABiggerRegionAndTinyGuestsNone() {
        val r = Scfb.region(4096L shl 20, 4000, 4000).getOrThrow()
        assertTrue(r.size >= Scfb.BUFFER_ALIGN + 2L * 4000 * 4000 * 4)
        assertEquals(0L, r.size % (2L shl 20))
        assertTrue(Scfb.region(128L shl 20, 1125, 2436).isFailure)
        assertTrue(Scfb.region(2048L shl 20, 0, 10).isFailure)
        assertTrue(Scfb.region(2048L shl 20, 10, 9000).isFailure)
    }

    @Test fun primeClearsTheHeaderAndStampsHostTime() {
        val b = ByteBuffer.allocate(4096)
        for (i in 0 until 4096) b.put(i, 0x5a)
        Scfb.prime(b, 0x1122334455667788L)
        for (i in 0 until Scfb.OFF_HOST_NS) assertEquals("byte $i", 0, b.get(i).toInt())
        assertEquals(0x1122334455667788L, b.order(ByteOrder.LITTLE_ENDIAN).getLong(Scfb.OFF_HOST_NS))
        assertEquals(0x5a, b.get(Scfb.HEADER_BYTES).toInt())
    }

    @Test fun displaySizeParses() {
        assertEquals(1125 to 2436, Scfb.parseSize("1125x2436"))
        assertEquals(540 to 1212, Scfb.parseSize(" 540 X 1212 "))
        assertNull(Scfb.parseSize("1125"))
        assertNull(Scfb.parseSize("0x100"))
        assertNull(Scfb.parseSize("abcxdef"))
    }

    @Test fun touchDownMoveUpIsTheFrameworksEvdevStream() {
        val p = listOf(Scfb.Pointer(0, 100, 200))
        assertEquals(
            listOf(Triple(1, 0x14a, 1), Triple(3, 0x2f, 0), Triple(3, 0x39, 0), Triple(3, 0x35, 100), Triple(3, 0x36, 200),
                Triple(3, 0, 100), Triple(3, 1, 200), Triple(0, 0, 0)),
            events(Scfb.touchReport(Scfb.Action.DOWN, p, 0)),
        )
        val two = listOf(Scfb.Pointer(0, 1, 2), Scfb.Pointer(1, 3, 4))
        val move = events(Scfb.touchReport(Scfb.Action.MOVE, two, 0))
        assertEquals(listOf(Triple(3, 0x2f, 0), Triple(3, 0x39, 0), Triple(3, 0x35, 1), Triple(3, 0x36, 2),
            Triple(3, 0x2f, 1), Triple(3, 0x39, 1), Triple(3, 0x35, 3), Triple(3, 0x36, 4),
            Triple(3, 0, 1), Triple(3, 1, 2), Triple(0, 0, 0)), move)
        // The second finger lifts: its slot ends, BTN_TOUCH stays down.
        val pu = events(Scfb.touchReport(Scfb.Action.POINTER_UP, two, 1))
        assertEquals(Triple(3, 0x2f, 1), pu[0])
        assertEquals(Triple(3, 0x39, -1), pu[1])
        assertTrue(pu.none { it.second == 0x14a })
        val up = events(Scfb.touchReport(Scfb.Action.UP, p, 0))
        assertEquals(Triple(1, 0x14a, 0), up[0])
        assertEquals(Triple(3, 0x39, -1), up[2])
        assertEquals(Triple(0, 0, 0), up.last())
        // Cancel lifts every finger.
        val cancel = events(Scfb.touchReport(Scfb.Action.CANCEL, two, 0))
        assertEquals(2, cancel.count { it == Triple(3, 0x39, -1) })
        assertArrayEquals(Scfb.touchReport(Scfb.Action.UP, p, 0), Scfb.touchReport(Scfb.Action.UP, p, 0))
    }

    @Test fun viewCoordinatesScaleToFramebufferPixels() {
        assertEquals(0, Scfb.scale(-5f, 540, 1125))
        assertEquals(562, Scfb.scale(270f, 540, 1125))
        assertEquals(1124, Scfb.scale(540f, 540, 1125))
        assertEquals(0, Scfb.scale(10f, 0, 1125))
    }
}
