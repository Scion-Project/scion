package io.github.dylanneve1.iosonpixel.avf

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TouchQueueTest {
    private fun r(v: Int) = byteArrayOf(v.toByte())

    @Test fun movesWaitingAtTheEndMergeIntoTheNewest() {
        val q = TouchQueue()
        q.offer(r(1), move = false)      // DOWN
        q.offer(r(2), move = true)
        q.offer(r(3), move = true)
        q.offer(r(4), move = true)
        q.offer(r(5), move = false)      // UP
        assertEquals(3, q.size())
        assertArrayEquals(r(1), q.take())
        assertArrayEquals(r(4), q.take())
        assertArrayEquals(r(5), q.take())
        assertEquals(2L, q.coalesced)
    }

    @Test fun downAndUpAreNeverMergedOrReordered() {
        val q = TouchQueue()
        q.offer(r(1), move = false)
        q.offer(r(2), move = false)
        q.offer(r(3), move = true)
        q.offer(r(4), move = false)
        q.offer(r(5), move = true)
        listOf(1, 2, 3, 4, 5).forEach { assertArrayEquals(r(it), q.take()) }
        assertEquals(0L, q.coalesced)
    }

    @Test fun aFullQueueDropsTheOldestWholeReport() {
        val q = TouchQueue(max = 2)
        q.offer(r(1), move = false)
        q.offer(r(2), move = false)
        q.offer(r(3), move = false)
        assertArrayEquals(r(2), q.take())
        assertArrayEquals(r(3), q.take())
        assertEquals(1L, q.dropped)
    }

    @Test fun closeEndsTheWriter() {
        val q = TouchQueue()
        q.offer(r(1), move = false)
        q.close()
        assertNull(q.take())
    }
}
