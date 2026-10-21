package io.github.dylanneve1.iosonpixel

import android.content.Context
import java.io.File
import java.util.Properties

/**
 * A prepared VM folder, as produced on a Mac by tools/package-vm.sh.
 *
 * The folder holds the user's own files (kernelcache, device tree,
 * SEP firmware/ROM, disk images) plus a `vm.properties` naming them. Nothing
 * here downloads, decrypts or patches firmware; that stays a manual step.
 */
class VmLayout(val dir: File) {
    private val props = Properties().apply {
        File(dir, MANIFEST).takeIf { it.isFile }?.inputStream()?.use { load(it) }
    }

    fun file(key: String): File? = props.getProperty(key)?.let { File(dir, it) }

    val rootFormat: String get() = props.getProperty("root_format", "raw")

    /** Human-readable problems that would stop the VM from booting. */
    fun problems(): List<String> {
        if (!File(dir, MANIFEST).isFile) return listOf("missing $MANIFEST in ${dir.path}")
        val out = mutableListOf<String>()
        for (key in REQUIRED_KEYS) {
            val f = file(key)
            when {
                f == null -> out += "$MANIFEST has no '$key'"
                !f.isFile -> out += "missing file for '$key': ${f.name}"
            }
        }
        for (name in SMALL_DISKS.map { it.name } + PFLASH) {
            if (!File(dir, name).isFile) out += "missing disk image: $name"
        }
        // Inferno now expects a 64K sep_nvram; older setups made a smaller one.
        File(dir, "sep_nvram").takeIf { it.isFile && it.length() != 64L * 1024 }?.let {
            out += "sep_nvram is ${it.length()} bytes, expected 65536 (truncate -s 64K sep_nvram)"
        }
        return out + Img4.check(this)
    }

    companion object {
        const val MANIFEST = "vm.properties"
        val PFLASH = listOf("sep_nvram", "sep_ssc")
        val REQUIRED_KEYS = listOf("kernel", "dtb", "trustcache", "sep_fw", "sep_rom", "root")

        /** The fixed-name NVMe namespaces from the Inferno setup guide. */
        val SMALL_DISKS = listOf(
            Ns("firmware", 2, 2), Ns("syscfg", 3, 3), Ns("ctrl_bits", 4, 4),
            Ns("nvram", 5, 5, device = "apple-nvram"), Ns("effaceable", 6, 6), Ns("panic_log", 7, 8),
        )
    }

    data class Ns(val name: String, val nsid: Int, val nstype: Int, val device: String = "nvme-ns")
}

/** User-tunable knobs, persisted in SharedPreferences. */
data class VmSettings(
    val ramGb: Int = 4,
    /**
     * Application cores + 1 SEP core. The iPhone 11 has 6 + 1, but -smp 7
     * currently exhausts Android's per-process mapping limit (#33), so default to 3 + 1.
     */
    val smp: Int = 4,
    /** TCG translation cache size; bigger means less retranslation. */
    val tbSizeMb: Int = 1024,
    /** Map the JIT buffer twice (RW + RX) instead of RWX. */
    val splitWx: Boolean = false,
    /**
     * vCPU pinning: [AUTO] derives it from [smp] ([TensorG5.vcpuAffinity]),
     * empty disables it, anything else is used as given. See [effectiveVcpuAffinity].
     */
    val vcpuAffinity: String = AUTO,
    /** VM engine; only implemented backends are ever loaded. */
    val backend: Backend = Backend.QEMU,
    /** Run QEMU as a library in the :vm process instead of as a child process. */
    val inProcess: Boolean = false,
) {
    /** What QEMU_VCPU_AFFINITY is set to for this run; empty means no pinning. */
    val effectiveVcpuAffinity: String
        get() = if (vcpuAffinity.equals(AUTO, ignoreCase = true)) TensorG5.vcpuAffinity(smp) else vcpuAffinity

    fun save(ctx: Context) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putInt("ram_gb", ramGb).putInt("smp", smp).putInt("tb_size_mb", tbSizeMb)
            .putBoolean("split_wx", splitWx).putString("vcpu_affinity", vcpuAffinity)
            .putString("backend", backend.id).putBoolean("in_process", inProcess)
            .apply()
    }

    companion object {
        private const val PREFS = "vm_settings"
        const val AUTO = "auto"

        fun load(ctx: Context): VmSettings {
            val p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val d = VmSettings()
            return VmSettings(
                ramGb = p.getInt("ram_gb", d.ramGb),
                smp = p.getInt("smp", d.smp),
                tbSizeMb = p.getInt("tb_size_mb", d.tbSizeMb),
                splitWx = p.getBoolean("split_wx", d.splitWx),
                // Earlier builds saved the fixed 7-vCPU string as the default; treat it as auto.
                vcpuAffinity = p.getString("vcpu_affinity", d.vcpuAffinity)
                    ?.takeIf { it != TensorG5.LEGACY_VCPU_AFFINITY } ?: d.vcpuAffinity,
                backend = Backend.fromId(p.getString("backend", null))?.takeIf { it.implemented } ?: d.backend,
                inProcess = p.getBoolean("in_process", d.inProcess),
            )
        }
    }
}

/**
 * Tensor G5 ("lga") topology, from the Pixel 10 kernel device tree:
 * cpu0-1 Cortex-A520, cpu2-6 Cortex-A725, cpu7 Cortex-X4.
 */
object TensorG5 {
    const val BIG_CORES = "2-7"
    const val LITTLE_CORES = "0-1"

    /** The fixed default before it depended on the vCPU count (right only for 7 vCPUs). */
    const val LEGACY_VCPU_AFFINITY = "2-7;2-7;2-7;2-7;2-7;2-7;0-1"

    /**
     * Default QEMU_VCPU_AFFINITY for [smp] vCPUs: one cpu-set per vCPU,
     * ';'-separated (QEMU repeats the last entry). The emulated application
     * cores float over the six big cores and the SEP core, always the last
     * vCPU, stays on the little cores. A single vCPU has no SEP and goes big.
     */
    fun vcpuAffinity(smp: Int): String =
        if (smp <= 1) BIG_CORES else (List(smp - 1) { BIG_CORES } + LITTLE_CORES).joinToString(";")
}

/** Paths the app uses at runtime. */
class AppPaths(ctx: Context) {
    private val app = ctx.applicationContext

    /** adb-pushable: /sdcard/Android/data/<pkg>/files/vm (FUSE-backed shared storage). */
    val sharedVmDir = File(ctx.getExternalFilesDir(null), "vm")
    /** /data/user/0/<pkg>/files/vm: app-internal storage, no FUSE in the I/O path. */
    val internalVmDir = File(ctx.filesDir, "vm")

    /**
     * Where the VM in use lives. Only a completed move ([ImportService.move]) changes it, so it
     * never points at a half-copied folder; imports and Start use whatever it says.
     */
    val vmInternal: Boolean get() = prefs(app).getBoolean(KEY_INTERNAL, false)
    val vmDir: File get() = if (vmInternal) internalVmDir else sharedVmDir

    val qemu = File(ctx.applicationInfo.nativeLibraryDir, "libqemu-system-aarch64.so")
    /** The in-process variant: QEMU as a library, and the JNI shim that calls it. */
    val qemuLib = File(ctx.applicationInfo.nativeLibraryDir, "libqemu-system-aarch64-shared.so")
    val qemuJni = File(ctx.applicationInfo.nativeLibraryDir, "libqemu_jni.so")
    val dataDir = File(ctx.filesDir, "qemu-data")
    val vncSocket = File(ctx.filesDir, "vnc.sock")
    val monitorSocket = File(ctx.filesDir, "monitor.sock")
    val logFile = File(ctx.getExternalFilesDir(null), "qemu.log")
    val tmpDir: File = ctx.cacheDir

    companion object {
        private const val KEY_INTERNAL = "vm_internal"
        private fun prefs(ctx: Context) = ctx.getSharedPreferences("vm_paths", Context.MODE_PRIVATE)

        /** Synchronous: this is the commit point of a move, so it must be on disk before the source goes. */
        fun setVmInternal(ctx: Context, internal: Boolean) {
            check(prefs(ctx.applicationContext).edit().putBoolean(KEY_INTERNAL, internal).commit()) {
                "cannot save the VM location"
            }
        }
    }
}
