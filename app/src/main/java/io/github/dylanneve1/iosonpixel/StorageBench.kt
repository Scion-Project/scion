package io.github.dylanneve1.iosonpixel

import android.content.Context
import android.os.StatFs
import android.util.Log
import java.io.File
import java.io.RandomAccessFile
import kotlin.random.Random

/**
 * Disk throughput of shared storage (FUSE) against internal app storage, for #56.
 * A developer hook (read in onCreate, so `am force-stop <pkg>` first), logged under the tag "StorageBench":
 *   adb shell am start -n <pkg>/.MainActivity --ez storage_bench true [--ei storage_bench_mb 100]
 *
 * Sequential: write [mb] MiB in 1 MiB blocks + fsync, then read it back. Random: 4 KiB
 * writes + fsync, then 4 KiB reads, at aligned offsets in that file (roughly what QEMU
 * does to a disk image). Writes include the fsync, so they reach the disk; an app can't
 * drop the page cache, so reads are mostly cached and show the per-request path cost
 * (FUSE or not) rather than flash speed.
 */
object StorageBench {
    private const val TAG = "StorageBench"
    private const val RANDOM_OPS = 2000

    fun run(ctx: Context, mb: Int) {
        val paths = AppPaths(ctx)
        val targets = listOf("shared", "internal").zip(listOf(paths.sharedVmDir.parentFile!!, ctx.filesDir))
        for (round in 1..3) for ((label, dir) in targets) {
            val line = runCatching { once(dir, mb) }.getOrElse { "failed: $it" }
            Log.i(TAG, "round $round $label (${dir.path}): $line")
        }
        Log.i(TAG, "done")
    }

    private fun once(dir: File, mb: Int): String {
        val size = mb.toLong() shl 20
        val free = StatFs(dir.path).availableBytes
        require(free > size + (64L shl 20)) { "only ${free shr 20} MiB free" }
        val f = File(dir, "storage-bench.tmp")
        val buf = Random(1).nextBytes(1 shl 20) // incompressible
        val page = ByteArray(4096)
        try {
            val wr = timed {
                RandomAccessFile(f, "rw").use { r ->
                    repeat(mb) { r.write(buf) }
                    r.fd.sync()
                }
            }
            val rd = timed {
                RandomAccessFile(f, "r").use { r -> while (r.read(buf) > 0) Unit }
            }
            val rnd = Random(2)
            val pages = size / 4096
            val rw = timed {
                RandomAccessFile(f, "rw").use { r ->
                    repeat(RANDOM_OPS) { r.seek(rnd.nextLong(pages) * 4096); r.write(page) }
                    r.fd.sync()
                }
            }
            val rr = timed {
                RandomAccessFile(f, "r").use { r ->
                    repeat(RANDOM_OPS) { r.seek(rnd.nextLong(pages) * 4096); r.readFully(page) }
                }
            }
            fun mbs(ns: Long) = "%.0f MB/s".format(size / 1e6 / (ns / 1e9))
            fun iops(ns: Long) = "%.0f IOPS".format(RANDOM_OPS / (ns / 1e9))
            return "seq write ${mbs(wr)}, seq read ${mbs(rd)}, 4K rand write ${iops(rw)}, 4K rand read ${iops(rr)}"
        } finally {
            f.delete()
        }
    }

    private inline fun timed(block: () -> Unit): Long {
        val t = System.nanoTime()
        block()
        return System.nanoTime() - t
    }
}
