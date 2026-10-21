package io.github.dylanneve1.iosonpixel

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.os.Bundle
import android.text.InputType
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView

class MainActivity : Activity() {
    private lateinit var paths: AppPaths
    private lateinit var status: TextView
    private lateinit var checks: TextView
    private lateinit var log: TextView
    private lateinit var start: Button
    private lateinit var stop: Button

    private lateinit var ram: EditText
    private lateinit var smp: EditText
    private lateinit var tbSize: EditText
    private lateinit var affinity: EditText
    private lateinit var splitWx: Switch

    private val onState: () -> Unit = { render() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        paths = AppPaths(this)
        paths.vmDir.mkdirs()
        val s = VmSettings.load(this)
        val pad = (16 * resources.displayMetrics.density).toInt()

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }
        fun text(size: Float = 14f, mono: Boolean = false) = TextView(this).apply {
            textSize = size
            if (mono) typeface = Typeface.MONOSPACE
            setTextIsSelectable(true)
        }.also { root.addView(it) }
        fun field(label: String, value: String, numeric: Boolean = true): EditText {
            text().text = label
            return EditText(this).apply {
                setText(value)
                if (numeric) inputType = InputType.TYPE_CLASS_NUMBER
            }.also { root.addView(it) }
        }

        // Keep the soft keyboard from scrolling the page to the first field on launch.
        root.isFocusableInTouchMode = true
        root.requestFocus()
        status = text(16f)
        text(12f).text = "VM folder (adb push your prepared files here):\n${paths.vmDir.absolutePath}"
        checks = text(12f, mono = true)

        ram = field("Guest RAM (GiB)", s.ramGb.toString())
        smp = field("vCPUs (6 application cores + 1 SEP)", s.smp.toString())
        tbSize = field("JIT translation cache (MiB)", s.tbSizeMb.toString())
        affinity = field("vCPU → Tensor G5 core pinning (';' per vCPU, empty = off)", s.vcpuAffinity, numeric = false)
        splitWx = Switch(this).apply {
            text = "Split W^X JIT mapping (try if QEMU fails to allocate its code buffer)"
            isChecked = s.splitWx
        }.also { root.addView(it) }

        val buttons = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        start = Button(this).apply {
            text = "Start"
            setOnClickListener { saveSettings(); VmService.start(this@MainActivity); openDisplay() }
        }
        stop = Button(this).apply { text = "Stop"; setOnClickListener { VmService.stop(this@MainActivity) } }
        val display = Button(this).apply { text = "Display"; setOnClickListener { openDisplay() } }
        listOf(start, stop, display).forEach { buttons.addView(it, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f)) }
        root.addView(buttons)

        text().text = "QEMU output"
        log = text(10f, mono = true)

        setContentView(ScrollView(this).apply { addView(root, MATCH_PARENT, WRAP_CONTENT) })

        if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 0)
        }

        // Developer hook (DisplayActivity isn't exported):
        //   adb shell am start -n <pkg>/.MainActivity --ez attach true
        if (intent.getBooleanExtra(DisplayActivity.EXTRA_ATTACH, false)) {
            startActivity(Intent(this, DisplayActivity::class.java).putExtra(DisplayActivity.EXTRA_ATTACH, true))
        }
    }

    override fun onResume() {
        super.onResume()
        VmState.observe(onState)
        render()
    }

    override fun onPause() {
        VmState.unobserve(onState)
        super.onPause()
    }

    private fun render() {
        val st = VmState.status
        status.text = "Status: ${st.name.lowercase()}" +
            (VmState.lastExit?.takeIf { st == VmState.Status.STOPPED }?.let { " (last exit $it)" } ?: "")

        val problems = VmLayout(paths.vmDir).problems()
        val qemuOk = paths.qemu.isFile
        checks.text = buildString {
            appendLine(if (qemuOk) "✓ QEMU binary bundled" else "✗ QEMU binary missing (run native/build-android.sh)")
            if (problems.isEmpty()) appendLine("✓ VM folder looks complete")
            else problems.forEach { appendLine("✗ $it") }
        }.trimEnd()

        start.isEnabled = st == VmState.Status.STOPPED && qemuOk && problems.isEmpty()
        stop.isEnabled = st == VmState.Status.RUNNING || st == VmState.Status.STARTING
        log.text = VmState.log()
    }

    private fun saveSettings() {
        val d = VmSettings()
        VmSettings(
            ramGb = ram.text.toString().toIntOrNull()?.coerceIn(1, 12) ?: d.ramGb,
            smp = smp.text.toString().toIntOrNull()?.coerceIn(1, 8) ?: d.smp,
            tbSizeMb = tbSize.text.toString().toIntOrNull()?.coerceIn(64, 4096) ?: d.tbSizeMb,
            splitWx = splitWx.isChecked,
            vcpuAffinity = affinity.text.toString().trim(),
        ).save(this)
    }

    private fun openDisplay() = startActivity(Intent(this, DisplayActivity::class.java))
}
