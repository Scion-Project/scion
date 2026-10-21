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
    /** 6 application cores + 1 SEP core, matching the iPhone 11. */
    val smp: Int = 7,
    /** TCG translation cache size; bigger means less retranslation. */
    val tbSizeMb: Int = 1024,
    /** Map the JIT buffer twice (RW + RX) instead of RWX. */
    val splitWx: Boolean = false,
    /** Pin vCPU threads per [TensorG5.DEFAULT_VCPU_AFFINITY]; empty disables. */
    val vcpuAffinity: String = TensorG5.DEFAULT_VCPU_AFFINITY,
) {
    fun save(ctx: Context) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putInt("ram_gb", ramGb).putInt("smp", smp).putInt("tb_size_mb", tbSizeMb)
            .putBoolean("split_wx", splitWx).putString("vcpu_affinity", vcpuAffinity)
            .apply()
    }

    companion object {
        private const val PREFS = "vm_settings"

        fun load(ctx: Context): VmSettings {
            val p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val d = VmSettings()
            return VmSettings(
                ramGb = p.getInt("ram_gb", d.ramGb),
                smp = p.getInt("smp", d.smp),
                tbSizeMb = p.getInt("tb_size_mb", d.tbSizeMb),
                splitWx = p.getBoolean("split_wx", d.splitWx),
                vcpuAffinity = p.getString("vcpu_affinity", d.vcpuAffinity) ?: d.vcpuAffinity,
            )
        }
    }
}

/**
 * Tensor G5 ("lga") topology, from the Pixel 10 kernel device tree:
 * cpu0-1 Cortex-A520, cpu2-6 Cortex-A725, cpu7 Cortex-X4.
 */
object TensorG5 {
    /**
     * One cpu-set per vCPU, ';'-separated; the last entry repeats.
     * The six emulated A13 application cores float over the six big cores,
     * and the emulated SEP core (vCPU 6) stays on the little cores.
     */
    const val DEFAULT_VCPU_AFFINITY = "2-7;2-7;2-7;2-7;2-7;2-7;0-1"
}

/** Paths the app uses at runtime. */
class AppPaths(ctx: Context) {
    /** adb-pushable: /sdcard/Android/data/<pkg>/files/vm */
    val vmDir = File(ctx.getExternalFilesDir(null), "vm")
    val qemu = File(ctx.applicationInfo.nativeLibraryDir, "libqemu-system-aarch64.so")
    val dataDir = File(ctx.filesDir, "qemu-data")
    val vncSocket = File(ctx.filesDir, "vnc.sock")
    val monitorSocket = File(ctx.filesDir, "monitor.sock")
    val logFile = File(ctx.getExternalFilesDir(null), "qemu.log")
    val tmpDir: File = ctx.cacheDir
}

object QemuCommand {
    /** Mirrors the sample command from the Inferno guide, minus host-only display flags. */
    fun build(paths: AppPaths, vm: VmLayout, s: VmSettings): List<String> {
        fun path(key: String) = vm.file(key)!!.absolutePath
        val machine = listOf(
            "t8030",
            "trustcache=${path("trustcache")}",
            "sep-fw=${path("sep_fw")}",
            "sep-rom=${path("sep_rom")}",
            "kaslr-off=true",
        ).joinToString(",")

        val accel = buildString {
            append("tcg,thread=multi,tb-size=").append(s.tbSizeMb)
            if (s.splitWx) append(",split-wx=on")
        }

        val args = mutableListOf(
            paths.qemu.absolutePath,
            "-L", paths.dataDir.absolutePath,
            "-M", machine,
            "-accel", accel,
            "-kernel", path("kernel"),
            "-dtb", path("dtb"),
            "-append", "tlto_us=-1 mtxspin=-1 agm-genuine=1 agm-authentic=1 agm-trusted=1 " +
                "serial=3 wdt=-1 -vm_compressor_wk_sw",
            "-smp", s.smp.toString(),
            "-m", "${s.ramGb}G",
            "-display", "none",
            "-vnc", "unix:${paths.vncSocket.absolutePath}",
            "-monitor", "unix:${paths.monitorSocket.absolutePath},server=on,wait=off",
            "-serial", "stdio",
            // Shared storage is FUSE-backed; OFD locks there are unreliable.
            "-drive", "file=sep_nvram,if=pflash,format=raw,file.locking=off",
            "-drive", "file=sep_ssc,if=pflash,format=raw,file.locking=off",
        )

        fun nvme(id: String, file: String, format: String, device: String, nsid: Int, nstype: Int) {
            args += listOf(
                "-drive", "file=$file,format=$format,if=none,id=$id,file.locking=off",
                "-device", "$device,drive=$id,bus=nvme-bus.0,nsid=$nsid,nstype=$nstype," +
                    (if (device == "apple-nvram") "id=$id," else "") +
                    "logical_block_size=4096,physical_block_size=4096",
            )
        }
        nvme("root", vm.file("root")!!.name, vm.rootFormat, "nvme-ns", 1, 1)
        for (ns in VmLayout.SMALL_DISKS) nvme(ns.name, ns.name, "raw", ns.device, ns.nsid, ns.nstype)
        return args
    }
}
