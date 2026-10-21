package io.github.dylanneve1.iosonpixel

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.StatFs
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import android.system.Os
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer

/** Process-wide state of a VM folder import or move, observed like [VmState]. */
object ImportState {
    @Volatile var running = false
        private set
    /** What is (or last was) running: an import or a move between storage locations. */
    @Volatile var moving = false
        internal set
    /** One-line progress or result for the main screen; empty when nothing happened yet. */
    @Volatile var message = ""
        private set
    @Volatile var copied = 0L
        private set
    @Volatile var total = 0L
        private set

    @Volatile internal var cancelled = false

    private val listeners = mutableSetOf<() -> Unit>()
    private val main = Handler(Looper.getMainLooper())

    internal fun update(running: Boolean, message: String, copied: Long = this.copied, total: Long = this.total) {
        this.running = running
        this.message = message
        this.copied = copied
        this.total = total
        main.post { synchronized(listeners) { listeners.toList() }.forEach { it() } }
    }

    /** Listeners are always called on the main thread. */
    fun observe(l: () -> Unit) { synchronized(listeners) { listeners += l } }
    fun unobserve(l: () -> Unit) { synchronized(listeners) { listeners -= l } }
}

/**
 * Copies a VM folder picked with the Storage Access Framework (the output
 * of tools/package_vm.py) into [AppPaths.vmDir], as an alternative to
 * `adb push`. A foreground service so a multi-GB copy survives the user
 * leaving the app.
 *
 * Files are streamed, never held in memory. The old vm.properties is removed
 * first and the new one is written last, so a cancelled or failed import
 * shows up as an incomplete folder in the checks instead of a mixed VM.
 *
 * The same service moves the VM between shared storage and internal app
 * storage ([ACTION_MOVE], see [moveVm]).
 */
class ImportService : Service() {
    private var worker: Thread? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_CANCEL -> if (worker != null) ImportState.cancelled = true else stopSelf()
            ACTION_MOVE -> start(move = true) { moveVm(intent.getBooleanExtra(EXTRA_TO_INTERNAL, true)) }
            else -> intent?.data?.let { tree -> start(move = false) { copyTree(tree) } }
        }
        return START_NOT_STICKY
    }

    /** Runs [job] (which returns the final status line) on a worker thread, in the foreground. */
    private fun start(move: Boolean, job: () -> String) {
        if (worker != null) return
        val what = if (move) "Move" else "Import"
        ImportState.moving = move
        startForeground(NOTIF_ID, notification("Preparing…", 0, 0), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        if (VmState.status != VmState.Status.STOPPED) {
            ImportState.update(false, "$what refused: stop the VM first")
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return
        }
        ImportState.cancelled = false
        ImportState.update(true, "$what: reading folder…", 0, 0)
        worker = Thread({
            val result = try {
                job()
            } catch (e: Exception) {
                "$what failed: ${e.message ?: e}"
            }
            ImportState.update(false, result)
            Handler(Looper.getMainLooper()).post {
                worker = null
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }, if (move) "vm-move" else "vm-import").apply { start() }
    }

    private data class Entry(val name: String, val uri: Uri, val size: Long)

    /** Returns the final status line. */
    private fun copyTree(tree: Uri): String {
        val dest = AppPaths(this).vmDir.apply { mkdirs() }
        val entries = list(tree)
        if (entries.none { it.name == VmLayout.MANIFEST }) {
            return "Import failed: no ${VmLayout.MANIFEST} in that folder (pick the one package_vm.py wrote)"
        }

        // Each file replaces one of the same name, so only growth needs free space.
        val total = entries.sumOf { it.size }
        val needed = entries.sumOf { e -> maxOf(0L, e.size - File(dest, e.name).let { if (it.isFile) it.length() else 0L }) }
        val free = StatFs(dest.path).availableBytes
        if (needed + SPARE_BYTES > free) {
            return "Import failed: needs ${gib(needed)} free, ${gib(free)} available"
        }

        File(dest, VmLayout.MANIFEST).delete()
        val copy = Copier("Import", total)
        // vm.properties last: it is what makes the folder look complete.
        for (e in entries.sortedBy { it.name == VmLayout.MANIFEST }) {
            try {
                copy.file(e.name, File(dest, e.name)) {
                    contentResolver.openInputStream(e.uri) ?: throw IOException("cannot open ${e.name}")
                }
            } catch (x: CancelledException) {
                return "Import cancelled; the VM folder stays incomplete until a full import"
            }
        }
        return "Imported ${entries.size} files (${gib(copy.copied)})"
    }

    /**
     * Moves the VM folder between shared storage and internal app storage
     * (no FUSE; see #56). Shared storage sits on the same /data partition, so
     * this is a copy and then a delete, and needs the VM's size free.
     *
     * Ordering, so an interrupted move never leaves two half folders:
     * 1. the destination is cleared and filled, vm.properties last;
     * 2. commit: [AppPaths.setVmInternal] switches the active location;
     * 3. only then is the source deleted, vm.properties first.
     * Before 2, the source is untouched and still in use, and a cancelled or
     * failed copy deletes what it wrote. After 2, the destination is complete.
     */
    private fun moveVm(toInternal: Boolean): String {
        val paths = AppPaths(this)
        val src = if (toInternal) paths.sharedVmDir else paths.internalVmDir
        val dest = (if (toInternal) paths.internalVmDir else paths.sharedVmDir).apply { mkdirs() }
        val where = if (toInternal) "internal storage" else "shared storage"
        if (!File(src, VmLayout.MANIFEST).isFile) return "Move failed: no ${VmLayout.MANIFEST} in ${src.path}"
        val files = src.listFiles { f -> f.isFile && !f.name.startsWith(".") }.orEmpty().toList()

        // The destination's old files go first, so they count as free space.
        val old = dest.listFiles { f -> f.isFile }.orEmpty().toList()
        val needed = files.sumOf { allocated(it) }
        val free = StatFs(dest.path).availableBytes + old.sumOf { allocated(it) }
        if (needed + SPARE_BYTES > free) {
            return "Move failed: needs ${gib(needed)} free in $where (plus ${gib(SPARE_BYTES)} headroom), ${gib(free)} available"
        }

        // Replacing the VM in use (a fresh adb push to shared storage while the VM is
        // internal): the source is complete, so make it the active one first.
        if (paths.vmInternal == toInternal) AppPaths.setVmInternal(this, !toInternal)
        fun clearDest() {
            File(dest, VmLayout.MANIFEST).delete()
            dest.listFiles { f -> f.isFile }?.forEach { it.delete() }
        }
        clearDest()
        val copy = Copier("Move to $where", files.sumOf { it.length() })
        try {
            for (f in files.sortedBy { it.name == VmLayout.MANIFEST }) {
                copy.file(f.name, File(dest, f.name)) { f.inputStream() }
            }
            AppPaths.setVmInternal(this, toInternal)
        } catch (x: Throwable) {
            clearDest()
            if (x is CancelledException) return "Move cancelled; the VM stays in ${src.path}"
            throw x
        }

        File(src, VmLayout.MANIFEST).delete()
        files.forEach { it.delete() }
        return "Moved ${files.size} files (${gib(copy.copied)}) to $where"
    }

    /** Bytes a file actually occupies; sparse raw disks are much smaller than their length. */
    private fun allocated(f: File): Long =
        runCatching { minOf(f.length(), Os.stat(f.path).st_blocks * 512) }.getOrDefault(f.length())

    /**
     * Streams files with cancel checks and progress. All-zero blocks are
     * skipped rather than written, so sparse disk images stay sparse.
     */
    private inner class Copier(private val what: String, private val total: Long) {
        var copied = 0L
            private set
        private var lastReport = 0L
        private val buf = ByteArray(1 shl 20)
        private val longs = ByteBuffer.wrap(buf).asLongBuffer()

        fun file(name: String, out: File, open: () -> InputStream) {
            out.delete()
            try {
                open().use { inp ->
                    RandomAccessFile(out, "rw").use { o ->
                        var pos = 0L
                        while (true) {
                            if (ImportState.cancelled) throw CancelledException()
                            val n = fill(inp)
                            if (n <= 0) break
                            if (!zero(n)) {
                                o.seek(pos)
                                o.write(buf, 0, n)
                            }
                            pos += n
                            copied += n
                            val now = System.currentTimeMillis()
                            if (now - lastReport > REPORT_MS) {
                                lastReport = now
                                progress(what, name, copied, total)
                            }
                        }
                        o.setLength(pos)
                        o.fd.sync()
                    }
                }
            } catch (x: Throwable) {
                out.delete()
                if (x is CancelledException) throw x
                throw IOException("$name: ${x.message ?: x}", x)
            }
        }

        /** Reads until [buf] is full or the stream ends, so zero checks see whole blocks. */
        private fun fill(inp: InputStream): Int {
            var n = 0
            while (n < buf.size) {
                val r = inp.read(buf, n, buf.size - n)
                if (r < 0) break
                n += r
            }
            return n
        }

        private fun zero(n: Int): Boolean {
            val whole = n / 8
            for (i in 0 until whole) if (longs.get(i) != 0L) return false
            for (i in whole * 8 until n) if (buf[i] != 0.toByte()) return false
            return true
        }
    }

    /** The folder's top-level files; package_vm.py writes no subfolders. */
    private fun list(tree: Uri): List<Entry> {
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
        val cols = arrayOf(Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME, Document.COLUMN_MIME_TYPE, Document.COLUMN_SIZE)
        val out = mutableListOf<Entry>()
        contentResolver.query(children, cols, null, null, null)?.use { c ->
            while (c.moveToNext()) {
                val name = c.getString(1) ?: continue
                if (c.getString(2) == Document.MIME_TYPE_DIR) continue
                // Never let a provider-supplied name escape the VM folder.
                if (name.contains('/') || name == "." || name == ".." || name.startsWith(".")) continue
                val size = if (c.isNull(3)) 0L else c.getLong(3)
                out += Entry(name, DocumentsContract.buildDocumentUriUsingTree(tree, c.getString(0)), size)
            }
        } ?: throw IOException("cannot list the folder")
        return out
    }

    private fun progress(what: String, name: String, copied: Long, total: Long) {
        val pct = if (total > 0) (copied * 100 / total).toInt() else 0
        ImportState.update(true, "$what: $name, ${gib(copied)} of ${gib(total)} ($pct%)", copied, total)
        getSystemService(NotificationManager::class.java)
            .notify(NOTIF_ID, notification(name, copied, total))
    }

    private fun notification(text: String, copied: Long, total: Long): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, getString(R.string.import_channel), NotificationManager.IMPORTANCE_LOW),
        )
        val cancel = PendingIntent.getService(
            this, 2, Intent(this, ImportService::class.java).setAction(ACTION_CANCEL), PendingIntent.FLAG_IMMUTABLE,
        )
        // Progress is in MiB so it fits an Int.
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_launcher)
            .setContentTitle(getString(if (ImportState.moving) R.string.move_running else R.string.import_running))
            .setContentText(text)
            .setProgress((total shr 20).toInt(), (copied shr 20).toInt(), total == 0L)
            .setContentIntent(PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE))
            .addAction(Notification.Action.Builder(null, getString(R.string.import_cancel), cancel).build())
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .build()
    }

    private class CancelledException : Exception()

    companion object {
        private const val CHANNEL = "import"
        private const val NOTIF_ID = 2
        private const val ACTION_CANCEL = "cancel"
        private const val ACTION_MOVE = "move"
        private const val EXTRA_TO_INTERNAL = "to_internal"
        private const val REPORT_MS = 250L
        /** Headroom left on the device after the import. */
        private const val SPARE_BYTES = 256L shl 20

        fun gib(bytes: Long) = "%.2f GiB".format(bytes / (1L shl 30).toDouble())

        fun start(ctx: Context, tree: Uri) {
            ctx.startForegroundService(Intent(ctx, ImportService::class.java).setData(tree))
        }

        /** Moves the VM to internal app storage, or back to shared storage. */
        fun move(ctx: Context, toInternal: Boolean) {
            ctx.startForegroundService(
                Intent(ctx, ImportService::class.java).setAction(ACTION_MOVE).putExtra(EXTRA_TO_INTERNAL, toInternal),
            )
        }

        fun cancel(ctx: Context) {
            ctx.startService(Intent(ctx, ImportService::class.java).setAction(ACTION_CANCEL))
        }
    }
}
