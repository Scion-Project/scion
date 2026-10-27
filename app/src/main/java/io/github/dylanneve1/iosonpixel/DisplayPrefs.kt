package io.github.dylanneve1.iosonpixel

import android.content.Context

/**
 * Display screen settings (Settings → Display). App-wide, not per install:
 * they are about this phone's screen, not the VM.
 */
data class DisplayPrefs(
    /** Draw the guest inset by a black border that belongs to the app (not Android's edge gestures). */
    val border: Boolean = true,
    /** Border width in dp, or [AUTO]: the system gesture inset, at least [MIN_AUTO_DP]. */
    val borderDp: Int = AUTO,
    /** Touches starting in the border or near an edge start on the guest's edge. */
    val snap: Boolean = true,
    val bar: BarMode = BarMode.AUTO_HIDE,
    /** Pin the display (Android app pinning) while it is open: no home, recents or notification shade. */
    val pin: Boolean = true,
) {
    enum class BarMode(val label: String) {
        ALWAYS("Always"),
        AUTO_HIDE("Auto-hide (tap the border or tap with two fingers to show)"),
        NEVER("Never"),
    }

    fun save(ctx: Context) {
        prefs(ctx).edit()
            .putBoolean("border", border)
            .putInt("border_dp", borderDp)
            .putBoolean("snap", snap)
            .putString("bar", bar.name)
            .putBoolean("pin", pin)
            .apply()
    }

    companion object {
        const val AUTO = -1
        const val MAX_DP = 64
        const val MIN_AUTO_DP = 24
        /** How far inside the guest image a touch still snaps to the edge. */
        const val SNAP_DP = 24

        private fun prefs(ctx: Context) = ctx.applicationContext.getSharedPreferences("display_settings", Context.MODE_PRIVATE)

        fun load(ctx: Context): DisplayPrefs {
            val p = prefs(ctx)
            val d = DisplayPrefs()
            return DisplayPrefs(
                border = p.getBoolean("border", d.border),
                borderDp = p.getInt("border_dp", d.borderDp).let { if (it == AUTO) AUTO else it.coerceIn(0, MAX_DP) },
                snap = p.getBoolean("snap", d.snap),
                bar = p.getString("bar", null)?.let { n -> BarMode.entries.firstOrNull { it.name == n } } ?: d.bar,
                pin = p.getBoolean("pin", d.pin),
            )
        }
    }
}
