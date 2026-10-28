package io.github.dylanneve1.iosonpixel.avf

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import java.lang.invoke.VarHandle
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * The phone's battery for iOS's battery gauge (hackios-xnu-kernel patch 1403,
 * bsd/dev/hakios.scfb_battery.c): Android's battery state, written into a block of the shared
 * framebuffer's header page at [OFF] whenever Android says it changed (ACTION_BATTERY_CHANGED) and
 * every [POLL_MS] besides (the current and the charge counter change without a broadcast). The guest
 * looks every 2 s and hands it to iOS's power source: the status bar's battery, "Charging", Settings
 * > Battery.
 *
 * The block, little-endian 32-bit words: magic "BATT", a sequence count (odd while this writes), then
 * struct hakios_battery_state's numbers -- the charge in hundredths of a percent, voltage (uV),
 * current (uA, more than 0 while it charges), temperature (hundredths of a degree), present, on a
 * charger, status (Linux's: 1 charging, 2 discharging, 3 not charging, 4 full), charge, full
 * charge and design charge (uAh; 0 unknown), cycles, the port's kind (1 a computer, 2 a charger) and
 * what it gives (uV, uA; 0 unknown).
 */
class ScfbBattery(
    private val ctx: Context,
    region: ByteBuffer,
    private val log: (String) -> Unit,
) : AutoCloseable {
    private val b = region.duplicate().order(ByteOrder.LITTLE_ENDIAN)
    private val thread = HandlerThread("scfb-battery").apply { start() }
    private val handler = Handler(thread.looper)
    private val manager = ctx.getSystemService(BatteryManager::class.java)
    private var seq = 0
    private var said = false
    @Volatile private var last: Intent? = null

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            last = intent
            publish()
        }
    }

    private val poll = object : Runnable {
        override fun run() {
            publish()
            handler.postDelayed(this, POLL_MS)
        }
    }

    fun start() {
        val filter = IntentFilter(Intent.ACTION_BATTERY_CHANGED)
        last = if (Build.VERSION.SDK_INT >= 33) {
            ctx.registerReceiver(receiver, filter, null, handler, Context.RECEIVER_NOT_EXPORTED)
        } else {
            ctx.registerReceiver(receiver, filter, null, handler)
        }
        handler.post(poll)
    }

    private fun publish() {
        val i = last ?: return
        val level = i.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = i.getIntExtra(BatteryManager.EXTRA_SCALE, 100).takeIf { it > 0 } ?: 100
        if (level < 0) return
        val plugged = i.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0)
        val status = when (i.getIntExtra(BatteryManager.EXTRA_STATUS, BatteryManager.BATTERY_STATUS_UNKNOWN)) {
            BatteryManager.BATTERY_STATUS_CHARGING -> 1
            BatteryManager.BATTERY_STATUS_NOT_CHARGING -> 3
            BatteryManager.BATTERY_STATUS_FULL -> 4
            else -> 2
        }
        val charge = prop(BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER)      // uAh
        val current = prop(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW)        // uA, more than 0 while it charges
        val percentHundredths = level * 10000 / scale
        val full = if (charge > 0 && level > 0) (charge.toLong() * scale / level).toInt() else 0
        val cycles = i.getIntExtra("android.os.extra.CYCLE_COUNT", 0)       // (BatteryManager.EXTRA_CYCLE_COUNT, Android 14)
        val words = intArrayOf(
            percentHundredths,
            i.getIntExtra(BatteryManager.EXTRA_VOLTAGE, 0) * 1000,          // mV
            current,
            i.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) * 10,       // tenths of a degree
            if (i.getBooleanExtra(BatteryManager.EXTRA_PRESENT, true)) 1 else 0,
            if (plugged != 0) 1 else 0,
            status,
            maxOf(charge, 0), full, 0, cycles,
            when (plugged) { BatteryManager.BATTERY_PLUGGED_USB -> 1; 0 -> 0; else -> 2 },
            0, 0,
        )
        synchronized(this) {
            seq = seq or 1
            b.putInt(OFF + 4, seq)                  // odd: being written
            fence()
            words.forEachIndexed { k, v -> b.putInt(OFF + 8 + 4 * k, v) }
            b.putInt(OFF, MAGIC)
            fence()
            seq += 1
            b.putInt(OFF + 4, seq)                  // even: whole
        }
        if (!said) {
            said = true
            log("avf: battery: iOS sees the phone's: ${level * 100 / scale}%${if (plugged != 0) ", on a charger" else ""}")
        }
    }

    /** The stores before it seen before those after it, by the guest's CPUs too (Android 13; before, a monitor's). */
    private fun fence() {
        if (Build.VERSION.SDK_INT >= 33) VarHandle.fullFence()
    }

    /** A BatteryManager property, 0 where the phone doesn't say. */
    private fun prop(id: Int): Int = runCatching { manager.getIntProperty(id) }.getOrDefault(0).let {
        if (it == Int.MIN_VALUE) 0 else it
    }

    override fun close() {
        runCatching { ctx.unregisterReceiver(receiver) }
        handler.removeCallbacksAndMessages(null)
        thread.quitSafely()
    }

    companion object {
        /** The block's place in the shared framebuffer's header page (its first 0x70 bytes are SCFB's own). */
        const val OFF = 0x100
        const val MAGIC = 0x54544142 // "BATT"
        const val POLL_MS = 10_000L
    }
}
