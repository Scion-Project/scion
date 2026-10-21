package io.github.dylanneve1.iosonpixel

import java.io.File

/**
 * Just enough DER to identify Apple IMG4 containers by their header:
 *   IM4P = SEQUENCE { "IM4P", type, description, payload, ... }
 *   IM4M = SEQUENCE { "IM4M", version, SET { ... } }          (AP/SEP tickets)
 *   IMG4 = SEQUENCE { "IMG4", IM4P, [0] IM4M }                (re-packed SEP firmware)
 */
object Img4 {
    data class Info(val magic: String, val type: String?)

    fun sniff(f: File): Info? {
        val head = ByteArray(256)
        val n = f.inputStream().use { it.read(head) }
        if (n <= 0) return null
        var p = 0
        fun header(expectTag: Int): Int? { // returns content length, advances p past tag+len
            if (p >= n || head[p].toInt() and 0xFF != expectTag) return null
            p++
            val first = head[p++].toInt() and 0xFF
            if (first < 0x80) return first
            val bytes = first and 0x7F
            var len = 0
            repeat(bytes) { len = (len shl 8) or (head[p++].toInt() and 0xFF) }
            return len
        }
        fun ia5(): String? {
            val len = header(0x16) ?: return null
            if (p + len > n) return null
            return String(head, p, len, Charsets.US_ASCII).also { p += len }
        }

        header(0x30) ?: return null
        val magic = ia5() ?: return null
        val type = when (magic) {
            "IM4P" -> ia5()
            "IMG4" -> { header(0x30) ?: return Info(magic, null); ia5(); ia5() }
            else -> null
        }
        return Info(magic, type)
    }

    /** What the Inferno guide's steps should have produced for each manifest key. */
    private val EXPECTED = mapOf(
        "kernel" to Info("IM4P", "krnl"),
        "dtb" to Info("IM4P", "dtre"),
        "ticket" to Info("IM4M", null),
        "sep_fw" to Info("IMG4", "rsep"),
    )

    fun check(vm: VmLayout): List<String> = EXPECTED.mapNotNull { (key, want) ->
        val f = vm.file(key)?.takeIf { it.isFile } ?: return@mapNotNull null
        val got = sniff(f)
        when {
            got == null -> "$key (${f.name}) is not an IMG4/IM4P/IM4M file"
            got.magic != want.magic -> "$key (${f.name}) is ${got.magic}, expected ${want.magic}" +
                if (key == "sep_fw") " (did the decrypt + repack step run?)" else ""
            want.type != null && got.type != want.type ->
                "$key (${f.name}) has payload type '${got.type}', expected '${want.type}'"
            else -> null
        }
    }
}
