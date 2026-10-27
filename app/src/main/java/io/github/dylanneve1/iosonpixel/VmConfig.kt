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

    /**
     * Optional restore ramdisk (`ramdisk=<file>`), passed to QEMU as `-initrd`.
     * Null when vm.properties has no (or an empty) ramdisk key.
     */
    val ramdisk: File? get() = props.getProperty(RAMDISK)?.trim()?.takeIf { it.isNotEmpty() }?.let { File(dir, it) }

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
        ramdisk?.takeIf { !it.isFile }?.let { out += "missing file for '$RAMDISK': ${it.name}" }
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
        const val RAMDISK = "ramdisk"

        /** The fixed-name NVMe namespaces from the Inferno setup guide. */
        val SMALL_DISKS = listOf(
            Ns("firmware", 2, 2), Ns("syscfg", 3, 3), Ns("ctrl_bits", 4, 4),
            Ns("nvram", 5, 5, device = "apple-nvram"), Ns("effaceable", 6, 6), Ns("panic_log", 7, 8),
        )
    }

    data class Ns(val name: String, val nsid: Int, val nstype: Int, val device: String = "nvme-ns")
}

/**
 * User-tunable knobs, per install ([Install.settings], stored by [Installs]). [load] and [save]
 * are the single-VM app's preferences (vm_settings): read once by the migration, and kept in
 * step with the migrated install so a downgrade still works.
 */
data class VmSettings(
    /** At most [MAX_RAM_GB]: t8030 refuses to start with more. */
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
    /** Guest kernel security level, passed as a boot-arg (AVF backend only). */
    val security: Security = Security.DEFAULT,
    /**
     * Extra arguments, whitespace-separated, no quoting: appended to the QEMU command line, or
     * to the AVF guest's kernel command line.
     */
    val extraArgs: String = "",
    /**
     * AVF only, experimental: the guest's screen (a shared framebuffer) and touch in the display
     * ([io.github.dylanneve1.iosonpixel.avf.ScfbIo]). Off: console only, as before. Per install
     * only; the single-VM app's preferences never had it, so [save] and [load] leave it out.
     */
    val avfDisplay: Boolean = false,
    /**
     * AVF only: give the guest a network device (virtio-net) on the phone's connection, through
     * the TAP and tethering AVF sets up for VMs ([io.github.dylanneve1.iosonpixel.avf.AvfApi.Spec.network]).
     * Off by default; per install only, like [avfDisplay].
     */
    val avfNetwork: Boolean = false,
    /**
     * AVF only, with [avfDisplay]: the phone's camera for the guest (iOS's Camera app), through a
     * second shared region ([io.github.dylanneve1.iosonpixel.avf.SccamCamera]). On by default. Needs the
     * CAMERA permission, which Android's prompt asks for at the first start (or when it is turned on);
     * refused, the setting goes back off. Per install only, as [avfDisplay].
     */
    val avfCamera: Boolean = true,
) {
    /** [extraArgs] as tokens. */
    val extraArgList: List<String> get() = extraArgs.split(Regex("\\s+")).filter { it.isNotEmpty() }

    /** What QEMU_VCPU_AFFINITY is set to for this run; empty means no pinning. */
    val effectiveVcpuAffinity: String
        get() = if (vcpuAffinity.equals(AUTO, ignoreCase = true)) TensorG5.vcpuAffinity(smp) else vcpuAffinity

    fun save(ctx: Context) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putInt("ram_gb", ramGb).putInt("smp", smp).putInt("tb_size_mb", tbSizeMb)
            .putBoolean("split_wx", splitWx).putString("vcpu_affinity", vcpuAffinity)
            .putString("backend", backend.id).putBoolean("in_process", inProcess)
            .putString("security", security.id).putString("extra_args", extraArgs)
            .apply()
    }

    companion object {
        private const val PREFS = "vm_settings"
        const val AUTO = "auto"
        /** t8030's SoC maximum (the iPhone 11 has 4 GB); QEMU exits at once above it. */
        const val MAX_RAM_GB = 4

        fun load(ctx: Context): VmSettings {
            val p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val d = VmSettings()
            return VmSettings(
                // Earlier builds allowed up to 12, which t8030 rejects.
                ramGb = p.getInt("ram_gb", d.ramGb).coerceIn(1, MAX_RAM_GB),
                smp = p.getInt("smp", d.smp),
                tbSizeMb = p.getInt("tb_size_mb", d.tbSizeMb),
                splitWx = p.getBoolean("split_wx", d.splitWx),
                // Earlier builds saved the fixed 7-vCPU string as the default; treat it as auto.
                vcpuAffinity = p.getString("vcpu_affinity", d.vcpuAffinity)
                    ?.takeIf { it != TensorG5.LEGACY_VCPU_AFFINITY } ?: d.vcpuAffinity,
                backend = Backend.fromId(p.getString("backend", null))?.takeIf { it.implemented } ?: d.backend,
                inProcess = p.getBoolean("in_process", d.inProcess),
                security = Security.fromId(p.getString("security", null)) ?: d.security,
                extraArgs = p.getString("extra_args", d.extraArgs) ?: d.extraArgs,
            )
        }
    }
}

/**
 * How much of its security the guest kernel enforces, passed on its command line as
 * `security=<id>` ([bootArg]). Only the plumbing lives here: what each level turns on or off is
 * up to the kernel (the XNU port for AVF), and the AVF backend is the only one that passes it.
 */
enum class Security(val id: String, val label: String) {
    /** Everything the kernel can enforce. */
    FULL("full", "Full"),
    /** Whatever the kernel needs relaxed to boot while it's being brought up. */
    BRINGUP("bringup", "Bring-up"),
    /** No enforcement; for debugging the kernel itself. */
    OFF("off", "Off");

    val bootArg: String get() = "$BOOT_ARG=$id"

    companion object {
        const val BOOT_ARG = "security"

        /**
         * The most secure level the kernel boots with. The kernel doesn't define its levels yet,
         * so this is [BRINGUP] for now; raise it to [FULL] once a kernel boots with that.
         */
        val DEFAULT = BRINGUP

        fun fromId(id: String?): Security? = entries.firstOrNull { it.id == id }
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

/**
 * Paths the app uses at runtime for install [installId]: its sockets, logs and run records, so
 * two installs never clobber each other. The install's VM folder itself is [Install.dir].
 */
class AppPaths(ctx: Context, val installId: String) {
    private val external: File = ctx.getExternalFilesDir(null) ?: File(ctx.filesDir, "shared")

    val qemu = File(ctx.applicationInfo.nativeLibraryDir, "libqemu-system-aarch64.so")
    /** The in-process variant: QEMU as a library, and the JNI shim that calls it. */
    val qemuLib = File(ctx.applicationInfo.nativeLibraryDir, "libqemu-system-aarch64-shared.so")
    val qemuJni = File(ctx.applicationInfo.nativeLibraryDir, "libqemu_jni.so")
    /** Keymaps etc.: read-only, shared by all installs. */
    val dataDir = File(ctx.filesDir, "qemu-data")
    val tmpDir: File = ctx.cacheDir

    /** files/run/<id>: this install's sockets and run records (short, for the 108-byte socket path limit). */
    val runDir = File(File(ctx.filesDir, "run"), installId)
    val vncSocket = File(runDir, "vnc.sock")
    val monitorSocket = File(runDir, "monitor.sock")
    /** QMP, for multi-touch input ([QmpTouch]). */
    val qmpSocket = File(runDir, "qmp.sock")
    /**
     * Where the emulated iPhone's USB uplink connects out to. t8030 otherwise
     * defaults to a TCP listener, which needs the INTERNET permission (#67).
     */
    val usbUplinkSocket = File(runDir, "usb-uplink.sock")
    /** The last run's status and output view, for the debug bundle. */
    val lastRun = File(runDir, "last_run.txt")
    val lastRunOutput = File(runDir, "last_run_output.txt")

    /** <external files>/logs/<id>: adb-pullable logs; the debug bundle takes every *.log here. */
    val logDir = File(File(external, "logs"), installId)
    val logFile = File(logDir, "qemu.log")
    /** The AVF guest's console (and hvc2 log). */
    val avfLogFile = File(logDir, "avf-console.log")

    /** Creates [runDir] and [logDir]; call before a run. */
    fun prepare() {
        runDir.mkdirs()
        logDir.mkdirs()
    }

    companion object {
        /*
         * The single-VM app's VM folder and its location flag (vm_paths: vm_internal). Only the
         * install migrated from it ([Install.legacy]) uses them; the flag is kept in step with
         * that install so an older build still finds its VM.
         */
        private const val KEY_INTERNAL = "vm_internal"
        private fun prefs(ctx: Context) = ctx.applicationContext.getSharedPreferences("vm_paths", Context.MODE_PRIVATE)

        /** adb-pushable: /sdcard/Android/data/<pkg>/files/vm (FUSE-backed shared storage). */
        fun legacySharedVmDir(ctx: Context) = File(ctx.getExternalFilesDir(null) ?: File(ctx.filesDir, "shared"), "vm")
        /** /data/user/0/<pkg>/files/vm: app-internal storage, no FUSE in the I/O path. */
        fun legacyInternalVmDir(ctx: Context) = File(ctx.filesDir, "vm")
        fun legacyVmInternal(ctx: Context): Boolean = prefs(ctx).getBoolean(KEY_INTERNAL, false)

        /** Synchronous: part of a move's commit point. */
        fun setLegacyVmInternal(ctx: Context, internal: Boolean) {
            check(prefs(ctx).edit().putBoolean(KEY_INTERNAL, internal).commit()) { "cannot save the VM location" }
        }
    }
}
