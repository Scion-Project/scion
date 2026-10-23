package io.github.dylanneve1.iosonpixel.avf

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class SccamTest {
    @Test fun regionSitsRightBelowTheFramebuffersAndSaysSoOnTheCommandLine() {
        val fb = Scfb.region(4096L shl 20, 1125, 2436).getOrThrow()
        val r = Sccam.region(fb).getOrThrow()
        assertEquals(fb.addr - Sccam.SIZE, r.addr)
        assertEquals(0L, r.addr % (2L shl 20))
        assertEquals(
            listOf("sccam.addr=0x" + r.addr.toString(16), "sccam.size=0x1000000", "sccam.w=1280", "sccam.h=960"),
            r.params,
        )
        assertTrue(Sccam.region(Scfb.region(176L shl 20, 100, 100).getOrThrow()).isFailure)
        assertTrue(Sccam.region(fb, 4000, 3000).isFailure)
    }

    @Test fun slotsFitInTheRegionAndHoldTheLargestSize() {
        assertTrue(Sccam.HEADER_BYTES + Sccam.NSLOTS.toLong() * Sccam.SLOT_BYTES <= Sccam.SIZE)
        assertEquals(0, Sccam.SLOT_BYTES % 0x10000)
        assertTrue(1920L * 1440 * 3 / 2 <= Sccam.SLOT_BYTES)
        // the header's slot descriptors are inside the header
        assertTrue(Sccam.OFF_SLOTS + Sccam.NSLOTS * Sccam.SLOT_DESC <= Sccam.HEADER_BYTES)
    }

    @Test fun primeWritesTheLayoutAndClearsTheRest() {
        val b = ByteBuffer.allocate(Sccam.HEADER_BYTES).order(ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until Sccam.HEADER_BYTES) b.put(i, 0x5a)
        Sccam.prime(b, cameras = 3, nowNs = 42L)
        assertEquals(Sccam.MAGIC, b.getInt(Sccam.OFF_MAGIC))
        assertEquals(1, b.getInt(Sccam.OFF_VERSION))
        assertEquals(Sccam.HEADER_BYTES, b.getInt(Sccam.OFF_HEADER_BYTES))
        assertEquals(Sccam.NSLOTS, b.getInt(Sccam.OFF_NSLOTS))
        assertEquals(Sccam.SLOT_BYTES, b.getInt(Sccam.OFF_SLOT_BYTES))
        assertEquals(3, b.getInt(Sccam.OFF_CAMERAS))
        assertEquals(42L, b.getLong(Sccam.OFF_HOST_NS))
        assertEquals(0L, b.getLong(Sccam.OFF_FRAME_SEQ))
        assertEquals(0, b.getInt(Sccam.OFF_REQ_ON))
        assertEquals(0L, b.getLong(Sccam.OFF_SLOTS))
    }

    @Test fun requestReadsTheGuestsFields() {
        val b = ByteBuffer.allocate(Sccam.HEADER_BYTES).order(ByteOrder.LITTLE_ENDIAN)
        b.putInt(Sccam.OFF_REQ_SEQ, 7).putInt(Sccam.OFF_REQ_ON, 1).putInt(Sccam.OFF_REQ_FACING, 1)
            .putInt(Sccam.OFF_REQ_WIDTH, 1280).putInt(Sccam.OFF_REQ_HEIGHT, 960).putLong(Sccam.OFF_GUEST_NS, 99L)
        assertEquals(Sccam.Request(7, true, Sccam.FACING_FRONT, 1280, 960, 99L), Sccam.request(b))
        b.putInt(Sccam.OFF_REQ_ON, 2).putInt(Sccam.OFF_REQ_FACING, 5) // anything else: off, the back camera
        val r = Sccam.request(b)
        assertFalse(r.on)
        assertEquals(Sccam.FACING_BACK, r.facing)
    }

    @Test fun captureSizeKeepsTheShapeAndIsAtLeastAsBig() {
        val pixel = listOf(4080 to 3072, 1920 to 1440, 1600 to 1200, 1280 to 960, 1920 to 1080, 1280 to 720, 640 to 480, 320 to 240)
        assertEquals(1280 to 960, Sccam.chooseSize(pixel, 1280, 960))
        assertEquals(1600 to 1200, Sccam.chooseSize(pixel, 1440, 1080))
        assertEquals(1280 to 720, Sccam.chooseSize(pixel, 1280, 720))
        // bigger than any 4:3 that fits a slot: the biggest that does (4080x3072 doesn't fit)
        assertEquals(1920 to 1440, Sccam.chooseSize(pixel, 2048, 1536))
        // no 4:3 at all: the nearest shape
        assertEquals(1440 to 960, Sccam.chooseSize(listOf(1920 to 1080, 1440 to 960), 1280, 960))
        assertNull(Sccam.chooseSize(listOf(4080 to 3072), 1280, 960))
        assertNull(Sccam.chooseSize(emptyList(), 1280, 960))
    }

    @Test fun nextSlotAvoidsTheLatestAndTheOneBeingRead() {
        assertEquals(1, Sccam.nextSlot(latest = 0, reading = 0))
        assertEquals(2, Sccam.nextSlot(latest = 0, reading = 2))
        assertEquals(0, Sccam.nextSlot(latest = 1, reading = 3))
        assertEquals(0, Sccam.nextSlot(latest = 2, reading = 0))
    }
}
