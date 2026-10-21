package io.github.dylanneve1.iosonpixel

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.os.Bundle
import android.text.InputType
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.WindowInsets
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView

class MainActivity : Activity() {
    private lateinit var paths: AppPaths
    private lateinit var backend: VmBackend
    private lateinit var status: TextView
    private lateinit var checks: TextView
    private lateinit var log: TextView
    private lateinit var start: Button
    private lateinit var stop: Button
    private lateinit var importButton: Button
    private lateinit var importStatus: TextView
    private lateinit var importProgress: ProgressBar

    private lateinit var ram: EditText
    private lateinit var smp: EditText
    private lateinit var tbSize: EditText
    private lateinit var affinity: EditText
    private lateinit var splitWx: Switch
    private lateinit var backendGroup: RadioGroup

    private val onState: () -> Unit = { render() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        paths = AppPaths(this)
        paths.vmDir.mkdirs()
        val s = VmSettings.load(this)
        backend = VmBackend.create(this, s.backend)
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
        text(12f).text = "VM folder (adb push your prepared files here, or import them):\n${paths.vmDir.absolutePath}"
        checks = text(12f, mono = true)

        importButton = Button(this).apply {
            setOnClickListener {
                if (ImportState.running) ImportService.cancel(this@MainActivity)
                else startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT_TREE), REQ_IMPORT)
            }
        }.also { root.addView(it, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT)) }
        importProgress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply { max = 1000 }
            .also { root.addView(it) }
        importStatus = text(12f)

        text().text = "VM backend"
        backendGroup = RadioGroup(this).also { root.addView(it) }
        for (b in Backend.entries) {
            backendGroup.addView(RadioButton(this).apply {
                id = b.ordinal + 1
                text = when (b) {
                    Backend.QEMU -> "QEMU (software emulation)"
                    Backend.AVF -> "AVF (hardware virtualization)"
                }
                isEnabled = b.implemented
            })
        }
        backendGroup.check(s.backend.ordinal + 1)
        val avf = AvfSupport.check(this)
        text(12f).text = "AVF: experimental, see ios-on-pixel-avf. Not selectable in this build.\n" +
            "This device:\n" +
            (if (avf.hasFeature) "✓" else "✗") + " advertises ${AvfSupport.FEATURE}\n" +
            (if (avf.hasPermission) "✓" else "✗") + " MANAGE_VIRTUAL_MACHINE granted"

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

        text().text = "${backend.kind.label} output"
        log = text(10f, mono = true)

        setContentView(ScrollView(this).apply {
            addView(root, MATCH_PARENT, WRAP_CONTENT)
            // targetSdk 35+ is always edge-to-edge: the content starts under the
            // status/action bar, which hid the first lines. Pad by the insets.
            clipToPadding = false
            setOnApplyWindowInsetsListener { v, insets ->
                val i = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
                v.setPadding(i.left, i.top, i.right, i.bottom)
                insets
            }
        })

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
        ImportState.observe(onState)
        render()
    }

    override fun onPause() {
        VmState.unobserve(onState)
        ImportState.unobserve(onState)
        super.onPause()
    }

    private fun render() {
        val st = VmState.status
        status.text = "Status: ${st.name.lowercase()}" +
            (VmState.lastExit?.takeIf { st == VmState.Status.STOPPED }?.let { " (last exit $it)" } ?: "")

        val list = backend.checks()
        checks.text = list.joinToString("\n") { (if (it.ok) "✓ " else "✗ ") + it.text }

        val importing = ImportState.running
        importButton.text = if (importing) "Cancel import" else "Import VM folder…"
        importButton.isEnabled = importing || st == VmState.Status.STOPPED
        importProgress.visibility = if (importing) View.VISIBLE else View.GONE
        importProgress.isIndeterminate = importing && ImportState.total == 0L
        if (ImportState.total > 0) importProgress.progress = (ImportState.copied * 1000 / ImportState.total).toInt()
        importStatus.text = ImportState.message
        importStatus.visibility = if (ImportState.message.isEmpty()) View.GONE else View.VISIBLE

        start.isEnabled = st == VmState.Status.STOPPED && !importing && list.all { it.ok }
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
            backend = Backend.entries.firstOrNull { it.ordinal + 1 == backendGroup.checkedRadioButtonId } ?: d.backend,
        ).save(this)
    }

    private fun openDisplay() = startActivity(Intent(this, DisplayActivity::class.java))

    @Deprecated("Plain framework Activity; no AndroidX result API here")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        val tree = data?.data
        if (requestCode == REQ_IMPORT && resultCode == RESULT_OK && tree != null) ImportService.start(this, tree)
    }

    private companion object {
        const val REQ_IMPORT = 1
    }
}
