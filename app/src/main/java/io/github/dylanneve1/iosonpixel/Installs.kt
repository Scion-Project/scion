package io.github.dylanneve1.iosonpixel

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * One VM the app knows about: a folder, the engine that runs it and that engine's settings.
 *
 * The folder exists in two places, internal app storage ([internalDir]) and shared storage
 * ([sharedDir], adb-pushable); [internal] says which one is in use, and a move switches it. Both
 * paths are stored, so a folder is always used where it is: the install migrated from the
 * single-VM app keeps `files/vm`, new installs get `files/installs/<id>`.
 */
data class Install(
    val id: String,
    val name: String,
    val internalDir: String,
    val sharedDir: String,
    val internal: Boolean,
    val settings: VmSettings,
    /**
     * Migrated from the single-VM layout. Its settings and location are mirrored to the old
     * preferences (vm_settings, vm_paths), so an older build still finds the same VM.
     */
    val legacy: Boolean = false,
) {
    val backend: Backend get() = settings.backend
    val dir: File get() = File(if (internal) internalDir else sharedDir)

    /** The file that makes the folder complete for this backend; import and move key on it. */
    val manifest: String get() = manifestFor(backend)

    companion object {
        fun manifestFor(b: Backend) = when (b) {
            Backend.QEMU -> VmLayout.MANIFEST
            Backend.AVF -> AvfLayout.MANIFEST
        }
    }
}

/**
 * The install list ("VM library"), in the `installs` preferences as JSON, plus which install is
 * selected. Main process only. The first read migrates the single-VM app ([InstallMigration]).
 */
object Installs {
    private const val PREFS = "installs"
    private const val KEY_LIST = "list"
    private const val KEY_CURRENT = "current"
    private const val KEY_NEXT_ID = "next_id"
    private const val KEY_BAD = "list_unreadable"
    /** The old preferences as this app last wrote or read them (see [InstallMigration.resync]). */
    private const val KEY_LEGACY_SEEN = "legacy_seen"

    private val lock = Any()

    private fun prefs(ctx: Context) = ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun all(ctx: Context): List<Install> = synchronized(lock) { load(ctx) }

    fun get(ctx: Context, id: String): Install? = all(ctx).firstOrNull { it.id == id }

    /** The selected install; there is always one. */
    fun current(ctx: Context): Install = synchronized(lock) {
        val list = load(ctx)
        val id = prefs(ctx).getString(KEY_CURRENT, null)
        list.firstOrNull { it.id == id } ?: list.first()
    }

    fun select(ctx: Context, id: String) = synchronized(lock) {
        if (load(ctx).any { it.id == id }) prefs(ctx).edit().putString(KEY_CURRENT, id).apply()
    }

    /** A new install with an empty folder in internal storage (and its shared-storage twin, for adb push). */
    fun add(ctx: Context, name: String, backend: Backend): Install = synchronized(lock) {
        val list = load(ctx)
        val p = prefs(ctx)
        val id = (p.getInt(KEY_NEXT_ID, 0).coerceAtLeast((list.mapNotNull { it.id.toIntOrNull() }.maxOrNull() ?: 0) + 1)).toString()
        val dirs = InstallMigration.dirsFor(ctx.filesDir, sharedRoot(ctx), id)
        val install = Install(
            id = id, name = name.trim().ifEmpty { "Install $id" },
            internalDir = dirs.first.absolutePath, sharedDir = dirs.second.absolutePath, internal = true,
            settings = VmSettings(backend = backend),
        )
        // The app creates both, so files adb pushes into the shared one stay readable to it.
        File(install.internalDir).mkdirs()
        File(install.sharedDir).mkdirs()
        save(ctx, list + install)
        p.edit().putInt(KEY_NEXT_ID, id.toInt() + 1).putString(KEY_CURRENT, id).apply()
        install
    }

    /** Saves [install] (matched by id); the migrated install is mirrored to the old preferences. */
    fun update(ctx: Context, install: Install) = synchronized(lock) {
        save(ctx, load(ctx).map { if (it.id == install.id) install else it })
        if (install.legacy) {
            install.settings.save(ctx)
            AppPaths.setLegacyVmInternal(ctx, install.internal)
            prefs(ctx).edit().putString(KEY_LEGACY_SEEN, InstallMigration.seen(install.settings, install.internal)).apply()
        }
    }

    /**
     * The commit point of a move: synchronous, so it is on disk before the source is deleted.
     * Throws if it can't be saved.
     */
    fun setInternal(ctx: Context, id: String, internal: Boolean) = synchronized(lock) {
        val list = load(ctx).map { if (it.id == id) it.copy(internal = internal) else it }
        check(prefs(ctx).edit().putString(KEY_LIST, InstallMigration.encode(list)).commit()) { "cannot save the VM location" }
        list.firstOrNull { it.id == id && it.legacy }?.let {
            AppPaths.setLegacyVmInternal(ctx, internal)
            prefs(ctx).edit().putString(KEY_LEGACY_SEEN, InstallMigration.seen(it.settings, internal)).commit()
        }
    }

    /** Removes [id] from the list (never the last one). Files are only deleted by [deleteFiles]. */
    fun remove(ctx: Context, id: String) = synchronized(lock) {
        val list = load(ctx)
        if (list.size <= 1) return@synchronized
        val rest = list.filter { it.id != id }
        save(ctx, rest)
        if (prefs(ctx).getString(KEY_CURRENT, null) == id) prefs(ctx).edit().putString(KEY_CURRENT, rest.first().id).apply()
    }

    /**
     * The folders [deleteFiles] may delete: only the ones the app made for this install
     * (`installs/<id>` in internal or shared storage), never a folder that was adopted, such as
     * the migrated `files/vm`, and never one another install also uses.
     */
    fun deletableDirs(ctx: Context, install: Install): List<File> {
        val own = InstallMigration.dirsFor(ctx.filesDir, sharedRoot(ctx), install.id).toList().map { it.canonicalFile }
        val others = all(ctx).filter { it.id != install.id }.flatMap { listOf(it.internalDir, it.sharedDir) }
            .map { File(it).canonicalFile }
        return listOf(install.internalDir, install.sharedDir).map { File(it).canonicalFile }
            .filter { it in own && it !in others && it.exists() }
    }

    /** Deletes [deletableDirs] recursively. Blocking. Returns the bytes freed. */
    fun deleteFiles(ctx: Context, install: Install): Long =
        deletableDirs(ctx, install).sumOf { d -> d.walkBottomUp().sumOf { f -> (if (f.isFile) f.length() else 0L).also { f.delete() } } }

    /**
     * A one-off install for the debug-build developer hook: [base]'s settings, with [backend],
     * the folder [dir] and/or the Experimental AVF display setting [avfDisplay] instead of its own. Never saved; its sockets and logs use id "dev".
     */
    fun developer(base: Install, backend: Backend?, dir: File?, avfDisplay: Boolean? = null, avfNetwork: Boolean? = null): Install = base.copy(
        id = DEVELOPER_ID,
        name = "Developer session",
        internalDir = dir?.absolutePath ?: base.internalDir,
        sharedDir = dir?.absolutePath ?: base.sharedDir,
        settings = base.settings.copy(
            backend = backend ?: base.backend,
            avfDisplay = avfDisplay ?: base.settings.avfDisplay,
            avfNetwork = avfNetwork ?: base.settings.avfNetwork,
        ),
        legacy = false,
    )

    const val DEVELOPER_ID = "dev"

    private fun sharedRoot(ctx: Context): File = ctx.getExternalFilesDir(null) ?: File(ctx.filesDir, "shared")

    private fun save(ctx: Context, list: List<Install>) {
        prefs(ctx).edit().putString(KEY_LIST, InstallMigration.encode(list)).apply()
    }

    /** The list, migrating the single-VM app on first use. */
    private fun load(ctx: Context): List<Install> {
        val p = prefs(ctx)
        val raw = p.getString(KEY_LIST, null)
        if (raw != null) {
            InstallMigration.decode(raw)?.takeIf { it.isNotEmpty() }?.let { list ->
                // An older (single-VM) build may have run in between and changed the old prefs.
                val legacy = legacy(ctx)
                val now = InstallMigration.seen(legacy.settings, legacy.vmInternal)
                val synced = InstallMigration.resync(list, legacy, p.getString(KEY_LEGACY_SEEN, null)) ?: return list
                p.edit().putString(KEY_LIST, InstallMigration.encode(synced)).putString(KEY_LEGACY_SEEN, now).commit()
                return synced
            }
            // Unreadable: keep it for a bug report, then rebuild from the old preferences. The
            // rebuild only records paths; no VM folder is read or written either way.
            p.edit().putString(KEY_BAD, raw).remove(KEY_LIST).commit()
        }
        val legacy = legacy(ctx)
        val list = listOf(InstallMigration.fromLegacy(legacy))
        // commit(): the migration happens once, and must be on disk before anything else runs.
        p.edit().putString(KEY_LIST, InstallMigration.encode(list)).putString(KEY_CURRENT, list.first().id)
            .putInt(KEY_NEXT_ID, 2).putString(KEY_LEGACY_SEEN, InstallMigration.seen(legacy.settings, legacy.vmInternal))
            .commit()
        return list
    }

    private fun legacy(ctx: Context) = InstallMigration.Legacy(
        settings = VmSettings.load(ctx),
        vmInternal = AppPaths.legacyVmInternal(ctx),
        internalVmDir = AppPaths.legacyInternalVmDir(ctx),
        sharedVmDir = AppPaths.legacySharedVmDir(ctx),
    )
}

/**
 * The pure part of [Installs]: migration from the single-VM app and the JSON encoding.
 * Unit-tested (InstallMigrationTest).
 *
 * The migration only *records* where the old VM folder is. It never lists, opens, moves, copies,
 * renames or writes anything in it, and it leaves the old preferences in place.
 */
object InstallMigration {
    const val LEGACY_ID = "1"
    const val LEGACY_NAME = "iOS 14 (iPhone 11)"

    /** What the single-VM app had: its settings (vm_settings), vm_paths' vm_internal and the two folders. */
    data class Legacy(val settings: VmSettings, val vmInternal: Boolean, val internalVmDir: File, val sharedVmDir: File)

    fun fromLegacy(l: Legacy) = Install(
        id = LEGACY_ID,
        name = LEGACY_NAME,
        internalDir = l.internalVmDir.absolutePath,
        sharedDir = l.sharedVmDir.absolutePath,
        internal = l.vmInternal,
        settings = l.settings,
        legacy = true,
    )

    /** A fingerprint of the old preferences: settings and location. */
    fun seen(settings: VmSettings, vmInternal: Boolean): String =
        encode(settings).apply { remove(KEY_AVF_DISPLAY); remove(KEY_AVF_NETWORK) }.put("vm_internal", vmInternal).toString()

    /** Per-install only (not in the old preferences), so it's left out of [seen] and kept by [resync]. */
    private const val KEY_AVF_DISPLAY = "avf_display"
    private const val KEY_AVF_NETWORK = "avf_network"

    /**
     * After a downgrade and re-upgrade: if the old preferences no longer match [seen] (what this
     * app last wrote or read), an older build changed them, so the migrated install adopts them.
     * Returns the updated list, or null if nothing changed. Still touches no file.
     */
    fun resync(list: List<Install>, legacy: Legacy, seen: String?): List<Install>? {
        val now = seen(legacy.settings, legacy.vmInternal)
        if (seen == null || seen == now) return null
        if (list.none { it.legacy }) return null
        return list.map {
            if (it.legacy) it.copy(settings = legacy.settings.copy(avfDisplay = it.settings.avfDisplay, avfNetwork = it.settings.avfNetwork), internal = legacy.vmInternal)
            else it
        }
    }

    /** Internal and shared folders for a new install [id]. */
    fun dirsFor(filesDir: File, sharedRoot: File, id: String) =
        File(File(filesDir, "installs"), id) to File(File(sharedRoot, "installs"), id)

    fun encode(list: List<Install>): String = JSONArray().apply {
        for (i in list) put(JSONObject().apply {
            put("id", i.id)
            put("name", i.name)
            put("internal_dir", i.internalDir)
            put("shared_dir", i.sharedDir)
            put("internal", i.internal)
            put("legacy", i.legacy)
            put("settings", encode(i.settings))
        })
    }.toString()

    /** Null if [raw] isn't a list this app wrote. Unknown settings fall back to their defaults. */
    fun decode(raw: String): List<Install>? = runCatching {
        val a = JSONArray(raw)
        (0 until a.length()).map { n ->
            val o = a.getJSONObject(n)
            Install(
                id = o.getString("id"),
                name = o.getString("name"),
                internalDir = o.getString("internal_dir"),
                sharedDir = o.getString("shared_dir"),
                internal = o.getBoolean("internal"),
                settings = decodeSettings(o.optJSONObject("settings") ?: JSONObject()),
                legacy = o.optBoolean("legacy", false),
            )
        }.takeIf { list -> list.map { it.id }.toSet().size == list.size }
    }.getOrNull()

    private fun encode(s: VmSettings) = JSONObject().apply {
        put("ram_gb", s.ramGb)
        put("smp", s.smp)
        put("tb_size_mb", s.tbSizeMb)
        put("split_wx", s.splitWx)
        put("vcpu_affinity", s.vcpuAffinity)
        put("backend", s.backend.id)
        put("in_process", s.inProcess)
        put("security", s.security.id)
        put("extra_args", s.extraArgs)
        put(KEY_AVF_DISPLAY, s.avfDisplay)
        put(KEY_AVF_NETWORK, s.avfNetwork)
    }

    private fun decodeSettings(o: JSONObject): VmSettings {
        val d = VmSettings()
        return VmSettings(
            ramGb = o.optInt("ram_gb", d.ramGb).coerceIn(1, VmSettings.MAX_RAM_GB),
            smp = o.optInt("smp", d.smp),
            tbSizeMb = o.optInt("tb_size_mb", d.tbSizeMb),
            splitWx = o.optBoolean("split_wx", d.splitWx),
            vcpuAffinity = o.optString("vcpu_affinity", d.vcpuAffinity),
            backend = Backend.fromId(o.optString("backend"))?.takeIf { it.implemented } ?: d.backend,
            inProcess = o.optBoolean("in_process", d.inProcess),
            security = Security.fromId(o.optString("security")) ?: d.security,
            extraArgs = o.optString("extra_args", d.extraArgs),
            avfDisplay = o.optBoolean(KEY_AVF_DISPLAY, d.avfDisplay),
            avfNetwork = o.optBoolean(KEY_AVF_NETWORK, d.avfNetwork),
        )
    }
}
