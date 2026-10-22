package io.github.dylanneve1.iosonpixel.avf

/**
 * The touch reports waiting for the guest. crosvm reads the touch socket only while the guest has
 * free event buffers, so a slow guest leaves reports waiting here rather than in a half-written
 * socket: a report is written whole or not at all, because one cut short would shift every event
 * after it (8-byte evdev events in a byte stream) and the guest would see coordinates and codes
 * that nobody sent.
 *
 * While reports wait, a MOVE replaces a MOVE waiting at the end (a MOVE carries every finger, so
 * the newest says it all); DOWN and UP are never dropped or reordered. Past [max] waiting reports
 * the oldest goes (a guest that reads nothing, e.g. one that boots console-only).
 */
class TouchQueue(private val max: Int = 256) {
    private class Item(val bytes: ByteArray, val move: Boolean)

    private val items = ArrayDeque<Item>()
    private val lock = Object()
    @Volatile private var closed = false

    /** Coalesced MOVEs and reports dropped for room, for the log. */
    var coalesced = 0L; private set
    var dropped = 0L; private set

    fun offer(report: ByteArray, move: Boolean) = synchronized(lock) {
        if (closed) return@synchronized
        val last = items.lastOrNull()
        if (move && last != null && last.move) {
            items[items.size - 1] = Item(report, true)
            coalesced++
        } else {
            if (items.size >= max) {
                items.removeFirst()
                dropped++
            }
            items.addLast(Item(report, move))
        }
        lock.notifyAll()
    }

    /** The next report, waiting for one; null once [close]d. */
    fun take(): ByteArray? = synchronized(lock) {
        while (items.isEmpty() && !closed) lock.wait()
        if (closed) null else items.removeFirst().bytes
    }

    fun size(): Int = synchronized(lock) { items.size }

    fun close() = synchronized(lock) {
        closed = true
        items.clear()
        lock.notifyAll()
    }
}
