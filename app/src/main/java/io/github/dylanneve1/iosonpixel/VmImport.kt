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
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

/** Process-wide state of a VM folder import, observed like [VmState]. */
object ImportState {
    @Volatile var running = false
        private set
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
 */
class ImportService : Service() {
    private var worker: Thread? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_CANCEL -> if (worker != null) ImportState.cancelled = true else stopSelf()
            else -> intent?.data?.let { start(it) }
        }
        return START_NOT_STICKY
    }

    private fun start(tree: Uri) {
        if (worker != null) return
        startForeground(NOTIF_ID, notification("Preparing…", 0, 0), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        if (VmState.status != VmState.Status.STOPPED) {
            ImportState.update(false, "Import refused: stop the VM first")
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return
        }
        ImportState.cancelled = false
        ImportState.update(true, "Import: reading folder…", 0, 0)
        worker = Thread({
            val result = try {
                copyTree(tree)
            } catch (e: Exception) {
                "Import failed: ${e.message ?: e}"
            }
            ImportState.update(false, result)
            Handler(Looper.getMainLooper()).post {
                worker = null
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }, "vm-import").apply { start() }
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
        var copied = 0L
        var lastReport = 0L
        val buf = ByteArray(1 shl 20)
        // vm.properties last: it is what makes the folder look complete.
        for (e in entries.sortedBy { it.name == VmLayout.MANIFEST }) {
            val out = File(dest, e.name)
            out.delete()
            try {
                val input = contentResolver.openInputStream(e.uri) ?: throw IOException("cannot open ${e.name}")
                input.use { inp ->
                    FileOutputStream(out).use { o ->
                        while (true) {
                            if (ImportState.cancelled) throw CancelledException()
                            val n = inp.read(buf)
                            if (n < 0) break
                            o.write(buf, 0, n)
                            copied += n
                            val now = System.currentTimeMillis()
                            if (now - lastReport > REPORT_MS) {
                                lastReport = now
                                progress(e.name, copied, total)
                            }
                        }
                        o.fd.sync()
                    }
                }
            } catch (x: Throwable) {
                out.delete()
                if (x is CancelledException) return "Import cancelled; the VM folder stays incomplete until a full import"
                throw IOException("${e.name}: ${x.message ?: x}", x)
            }
        }
        return "Imported ${entries.size} files (${gib(copied)})"
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

    private fun progress(name: String, copied: Long, total: Long) {
        val pct = if (total > 0) (copied * 100 / total).toInt() else 0
        ImportState.update(true, "Import: $name, ${gib(copied)} of ${gib(total)} ($pct%)", copied, total)
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
            .setContentTitle(getString(R.string.import_running))
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
        private const val REPORT_MS = 250L
        /** Headroom left on the device after the import. */
        private const val SPARE_BYTES = 256L shl 20

        fun gib(bytes: Long) = "%.2f GiB".format(bytes / (1L shl 30).toDouble())

        fun start(ctx: Context, tree: Uri) {
            ctx.startForegroundService(Intent(ctx, ImportService::class.java).setData(tree))
        }

        fun cancel(ctx: Context) {
            ctx.startService(Intent(ctx, ImportService::class.java).setAction(ACTION_CANCEL))
        }
    }
}
