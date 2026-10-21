package io.github.dylanneve1.iosonpixel

import android.Manifest
import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.WindowInsets
import android.widget.Button
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import java.io.File

class MainActivity : Activity() {
    private lateinit var ui: Ui
    private lateinit var paths: AppPaths
    private lateinit var backend: VmBackend
    private lateinit var scroll: ScrollView

    private lateinit var statusDot: View
    private lateinit var status: TextView
    private lateinit var statusDetail: TextView
    private lateinit var checksSummary: TextView
    private lateinit var checks: LinearLayout
    private lateinit var start: Button
    private lateinit var stop: Button
    private lateinit var display: Button

    private lateinit var importButton: Button
    private lateinit var importStatus: TextView
    private lateinit var importProgress: ProgressBar
    private lateinit var location: TextView
    private lateinit var locationPath: TextView
    private lateinit var locationHint: TextView
    private lateinit var moveButton: Button

    private lateinit var ram: EditText
    private lateinit var smp: EditText
    private lateinit var tbSize: EditText
    private lateinit var affinity: EditText
    private lateinit var pinning: TextView
    private lateinit var splitWx: Switch
    private lateinit var inProcess: Switch
    private lateinit var backendGroup: RadioGroup
    private lateinit var advanced: LinearLayout
    private lateinit var advancedToggle: TextView

    private lateinit var log: TextView
    private lateinit var logBox: View
    private lateinit var logToggle: TextView

    private val onState: () -> Unit = { render() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ui = Ui(this)
        paths = AppPaths(this)
        paths.sharedVmDir.mkdirs()
        DebugBundle.install(this)
        val s = VmSettings.load(this)
        backend = VmBackend.create(this, s.backend)
        window.decorView.setBackgroundColor(Ui.BG)
        // The header below carries the app name; the theme's action bar would repeat it.
        actionBar?.hide()

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(ui.dp(16), ui.dp(8), ui.dp(16), ui.dp(24))
            // Keep the soft keyboard from scrolling the page to the first field on launch.
            isFocusableInTouchMode = true
            requestFocus()
        }

        // Header
        root.addView(ui.text(30f, Ui.TEXT).apply { text = getString(R.string.app_name) }, ui.lp(top = 16))
        root.addView(ui.text(14f, Ui.MUTED).apply {
            text = "Emulated iPhone 11 (t8030) · ${backend.kind.label}"
        }, ui.lp(top = 2))

        buildStatusCard(root)
        buildStorageCard(root)
        buildSettingsCard(root, s)
        buildDiagnosticsCard(root)

        scroll = ScrollView(this).apply {
            isFillViewport = true
            addView(root, MATCH_PARENT, WRAP_CONTENT)
            // targetSdk 35+ is always edge-to-edge: the content starts under the
            // status/action bar, which hid the first lines. Pad by the insets.
            clipToPadding = false
            setOnApplyWindowInsetsListener { v, insets ->
                val i = insets.getInsets(
                    WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout() or WindowInsets.Type.ime(),
                )
                v.setPadding(i.left, i.top, i.right, i.bottom)
                insets
            }
        }
        setContentView(scroll)

        if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 0)
        }

        // Developer hooks. This activity is exported, so only honour them in debuggable builds.
        val debuggable = (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
        // DisplayActivity isn't exported:
        //   adb shell am start -n <pkg>/.MainActivity --ez attach true
        if (debuggable && intent.getBooleanExtra(DisplayActivity.EXTRA_ATTACH, false)) {
            startActivity(Intent(this, DisplayActivity::class.java).putExtra(DisplayActivity.EXTRA_ATTACH, true))
        }
        // Shared vs internal storage throughput, logged (see StorageBench).
        if (debuggable && intent.getBooleanExtra("storage_bench", false)) {
            val mb = intent.getIntExtra("storage_bench_mb", 100).coerceIn(1, 1024)
            Thread({ StorageBench.run(applicationContext, mb) }, "storage-bench").start()
        }
    }

    /** Status, readiness checks and the Start / Stop / Display actions. */
    private fun buildStatusCard(root: LinearLayout) {
        val card = ui.card(root, "Virtual machine")
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        statusDot = ui.dot(Ui.MUTED, 12).also { row.addView(it) }
        status = ui.text(22f, Ui.TEXT).also { row.addView(it, ui.lp(start = 12, width = WRAP_CONTENT)) }
        card.addView(row, ui.lp(top = 12))
        statusDetail = ui.text(13f, Ui.MUTED).also { card.addView(it, ui.lp(top = 4)) }

        val buttons = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        start = ui.button("Start", Ui.Style.FILLED) { saveSettings(); VmService.start(this); openDisplay() }
        stop = ui.button("Stop", Ui.Style.DANGER) { VmService.stop(this) }
        display = ui.button("Display", Ui.Style.TONAL) { openDisplay() }
        buttons.addView(start, ui.lp(width = 0, weight = 1.3f))
        buttons.addView(display, ui.lp(start = 8, width = 0, weight = 1f))
        buttons.addView(stop, ui.lp(start = 8, width = 0, weight = 1f))
        card.addView(buttons, ui.lp(top = 16))

        ui.divider(card)
        checksSummary = ui.text(14f, Ui.TEXT, bold = true).also { card.addView(it, ui.lp(top = 14)) }
        checks = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }.also { card.addView(it, ui.lp(top = 4)) }
    }

    /** Where the VM folder lives, and getting files into it. */
    private fun buildStorageCard(root: LinearLayout) {
        val card = ui.card(root, "VM files")
        location = ui.text(15f, Ui.TEXT).also { card.addView(it, ui.lp(top = 10)) }
        locationPath = ui.text(12f, Ui.MUTED, mono = true, selectable = true).also { card.addView(it, ui.lp(top = 2)) }
        locationHint = ui.text(13f, Ui.MUTED, selectable = true).also { card.addView(it, ui.lp(top = 12)) }

        importButton = ui.button("Import VM folder…", Ui.Style.TONAL) {
            if (ImportState.running) ImportService.cancel(this)
            else startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT_TREE), REQ_IMPORT)
        }.also { card.addView(it, ui.lp(top = 16)) }
        // "Keep VM in internal storage": moves the folder out of FUSE-backed shared storage.
        moveButton = ui.button("", Ui.Style.OUTLINED) {
            if (ImportState.running) ImportService.cancel(this)
            else ImportService.move(this, toInternal = moveToInternal())
        }.also { card.addView(it, ui.lp(top = 8)) }
        importProgress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 1000
            progressTintList = ColorStateList.valueOf(ui.accent)
        }.also { card.addView(it, ui.lp(top = 12)) }
        importStatus = ui.text(12.5f, Ui.MUTED).also { card.addView(it, ui.lp(top = 4)) }
    }

    private fun buildSettingsCard(root: LinearLayout, s: VmSettings) {
        val card = ui.card(root, "Settings", "Saved when you start the VM or leave this screen.")
        ram = ui.field(card, "Guest RAM", s.ramGb.toString(), suffix = "GiB")
        smp = ui.field(card, "vCPUs", s.smp.toString(), help = "Application cores + 1 SEP. 7 matches the iPhone 11.")
        tbSize = ui.field(card, "JIT translation cache", s.tbSizeMb.toString(), suffix = "MiB")

        advancedToggle = ui.text(14f, ui.accent, bold = true).apply {
            setPadding(0, ui.dp(12), 0, ui.dp(4))
            setOnClickListener { setAdvancedShown(advanced.visibility != View.VISIBLE) }
        }.also { card.addView(it, ui.lp(top = 8)) }
        advanced = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }.also { card.addView(it) }

        affinity = ui.field(
            advanced, "vCPU → Tensor G5 core pinning", s.vcpuAffinity,
            help = "auto = derived from the vCPU count. Or one CPU list per vCPU, separated by ';'. Empty = off.",
            numeric = false,
        )
        pinning = ui.text(12f, Ui.MUTED, mono = true).also { advanced.addView(it, ui.lp(top = 6)) }
        // Show what auto (or the override) resolves to as the fields are edited.
        val showPinning = object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(e: Editable?) = renderPinning()
        }
        smp.addTextChangedListener(showPinning)
        affinity.addTextChangedListener(showPinning)
        splitWx = switch(advanced, "Split W^X JIT mapping", "Try this if QEMU fails to allocate its code buffer.", s.splitWx)
        inProcess = switch(
            advanced, "Run QEMU in-process",
            "Library in a separate :vm process instead of a child process. Experimental.", s.inProcess,
        )
        // The checks depend on it, so apply it right away.
        inProcess.setOnCheckedChangeListener { _, _ -> saveSettings(); render() }

        advanced.addView(ui.text(14f, Ui.TEXT, bold = true).apply { text = "VM backend" }, ui.lp(top = 20))
        backendGroup = RadioGroup(this).also { advanced.addView(it, ui.lp(top = 4)) }
        for (b in Backend.entries) {
            backendGroup.addView(RadioButton(this).apply {
                id = b.ordinal + 1
                text = when (b) {
                    Backend.QEMU -> "QEMU (software emulation)"
                    Backend.AVF -> "AVF (hardware virtualization)"
                }
                setTextColor(Ui.TEXT)
                buttonTintList = ColorStateList.valueOf(ui.accent)
                isEnabled = b.implemented
                if (!b.implemented) alpha = 0.5f
            })
        }
        backendGroup.check(s.backend.ordinal + 1)
        val avf = AvfSupport.check(this)
        advanced.addView(ui.text(12.5f, Ui.MUTED).apply {
            text = "AVF is experimental (see ios-on-pixel-avf) and not selectable in this build.\n" +
                (if (avf.hasFeature) "✓" else "✗") + " this device advertises ${AvfSupport.FEATURE}\n" +
                (if (avf.hasPermission) "✓" else "✗") + " MANAGE_VIRTUAL_MACHINE granted"
        }, ui.lp(top = 4))

        renderPinning()

        // Open the advanced section if anything in it is set away from its default.
        val d = VmSettings()
        setAdvancedShown(
            s.vcpuAffinity != d.vcpuAffinity || s.splitWx != d.splitWx || s.inProcess != d.inProcess || s.backend != d.backend,
        )
    }

    private fun buildDiagnosticsCard(root: LinearLayout) {
        val card = ui.card(root, "Diagnostics")
        val actions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        actions.addView(ui.button("Share debug bundle", Ui.Style.TONAL) { DebugBundle.buildAndShare(this, it) },
            ui.lp(width = 0, weight = 1f))
        actions.addView(ui.button("Copy log", Ui.Style.OUTLINED) { copyLog() }, ui.lp(start = 8, width = WRAP_CONTENT))
        card.addView(actions, ui.lp(top = 12))

        logToggle = ui.text(14f, ui.accent, bold = true).apply {
            setPadding(0, ui.dp(12), 0, ui.dp(4))
            setOnClickListener { setLogShown(logBox.visibility != View.VISIBLE) }
        }.also { card.addView(it, ui.lp(top = 8)) }
        log = ui.text(11f, Ui.TEXT, mono = true, selectable = true).apply {
            setLineSpacing(0f, 1.05f)
            setHorizontallyScrolling(true)
        }
        // Long QEMU lines scroll sideways instead of wrapping into an unreadable column.
        logBox = HorizontalScrollView(this).apply {
            background = ui.roundRect(Ui.FIELD, 12, Ui.OUTLINE)
            setPadding(ui.dp(12), ui.dp(10), ui.dp(12), ui.dp(10))
            addView(log, WRAP_CONTENT, WRAP_CONTENT)
        }.also { card.addView(it, ui.lp(top = 6)) }
        setLogShown(true)
    }

    private fun switch(parent: LinearLayout, title: String, help: String, checked: Boolean): Switch {
        val sw = Switch(this).apply {
            text = title
            textSize = 14f
            setTextColor(Ui.TEXT)
            isChecked = checked
            thumbTintList = ColorStateList(
                arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
                intArrayOf(ui.accent, Ui.MUTED),
            )
            trackTintList = ColorStateList(
                arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
                intArrayOf(ui.tonal, Ui.OUTLINE),
            )
        }
        parent.addView(sw, ui.lp(top = 16))
        parent.addView(ui.text(12.5f, Ui.MUTED).apply { text = help }, ui.lp(top = 0))
        return sw
    }

    private fun setAdvancedShown(shown: Boolean) {
        advanced.visibility = if (shown) View.VISIBLE else View.GONE
        advancedToggle.text = if (shown) "Hide advanced  ▴" else "Advanced  ▾"
    }

    private fun setLogShown(shown: Boolean) {
        logBox.visibility = if (shown) View.VISIBLE else View.GONE
        logToggle.text = (if (shown) "Hide " else "Show ") + "${backend.kind.label} output" + if (shown) "  ▴" else "  ▾"
    }

    private fun copyLog() {
        val cm = getSystemService(ClipboardManager::class.java)
        cm.setPrimaryClip(ClipData.newPlainText("${backend.kind.label} output", VmState.log()))
        // Android 13+ shows its own clipboard confirmation.
        if (android.os.Build.VERSION.SDK_INT < 33) Toast.makeText(this, "Log copied", Toast.LENGTH_SHORT).show()
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
        saveSettings()
        super.onPause()
    }

    private fun render() {
        val st = VmState.status
        val lastExit = VmState.lastExit?.takeIf { st == VmState.Status.STOPPED }
        status.text = when (st) {
            VmState.Status.STOPPED -> "Stopped"
            VmState.Status.STARTING -> "Starting…"
            VmState.Status.RUNNING -> "Running"
            VmState.Status.STOPPING -> "Stopping…"
        }
        val dotColor = if (lastExit != null && lastExit != 0) Ui.BAD else ui.stateColor(st)
        statusDot.background = ui.pill(dotColor)
        statusDetail.text = when {
            lastExit != null -> "Last exit: $lastExit"
            st == VmState.Status.RUNNING -> "Open the display to use the iPhone."
            else -> ""
        }
        statusDetail.visibility = if (statusDetail.text.isEmpty()) View.GONE else View.VISIBLE

        val list = backend.checks()
        val failing = list.count { !it.ok }
        checksSummary.text = if (failing == 0) "Ready to start" else
            "$failing ${if (failing == 1) "thing needs" else "things need"} fixing before you can start"
        checksSummary.setTextColor(if (failing == 0) Ui.GOOD else Ui.TEXT)
        checks.removeAllViews()
        for (c in list.sortedBy { it.ok }) {
            checks.addView(LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                addView(ui.text(13f, if (c.ok) Ui.GOOD else Ui.BAD, bold = true).apply {
                    text = if (c.ok) "✓" else "✗"
                }, ui.lp(width = ui.dp(20)))
                addView(ui.text(13f, if (c.ok) Ui.MUTED else Ui.TEXT, selectable = true).apply { text = c.text },
                    ui.lp(width = 0, weight = 1f))
            }, ui.lp(top = 6))
        }

        val internal = paths.vmInternal
        val sharedHasVm = File(paths.sharedVmDir, VmLayout.MANIFEST).isFile
        location.text = if (internal) "In use: internal app storage" else "In use: shared storage"
        locationPath.text = paths.vmDir.absolutePath
        locationHint.text = buildString {
            append("adb push your prepared files to\n").append(paths.sharedVmDir.absolutePath)
            if (internal) append("\nthen move them to internal storage")
            append(", or import them into the folder in use.")
            if (internal && sharedHasVm) append("\n\n⚠ Shared storage holds a VM that is not in use.")
        }

        val importing = ImportState.running
        val moving = importing && ImportState.moving
        importButton.text = if (importing && !moving) "Cancel import" else "Import VM folder…"
        importButton.isEnabled = (importing && !moving) || (!importing && st == VmState.Status.STOPPED)
        val toInternal = moveToInternal()
        moveButton.text = when {
            moving -> "Cancel move"
            !toInternal -> "Move VM back to shared storage"
            internal -> "Replace in-use VM with the shared-storage one"
            else -> "Move VM to internal storage"
        }
        moveButton.isEnabled = moving || (!importing && st == VmState.Status.STOPPED && (!toInternal || sharedHasVm))
        importProgress.visibility = if (importing) View.VISIBLE else View.GONE
        importProgress.isIndeterminate = importing && ImportState.total == 0L
        if (ImportState.total > 0) importProgress.progress = (ImportState.copied * 1000 / ImportState.total).toInt()
        importStatus.text = ImportState.message
        importStatus.visibility = if (ImportState.message.isEmpty()) View.GONE else View.VISIBLE

        start.isEnabled = st == VmState.Status.STOPPED && !importing && failing == 0
        stop.isEnabled = st == VmState.Status.RUNNING || st == VmState.Status.STARTING
        display.isEnabled = st != VmState.Status.STOPPED

        val text = VmState.log()
        log.text = text.ifEmpty { "No output yet." }
        log.setTextColor(if (text.isEmpty()) Ui.MUTED else Ui.TEXT)
    }

    /** The move button's direction: into internal storage unless the VM is already there and nothing newer is in shared. */
    private fun moveToInternal() = !paths.vmInternal || File(paths.sharedVmDir, VmLayout.MANIFEST).isFile

    private fun renderPinning() {
        val p = formSettings().effectiveVcpuAffinity
        pinning.text = "QEMU_VCPU_AFFINITY = " + p.ifBlank { "(off)" }
    }

    private fun saveSettings() = formSettings().save(this)

    /** The settings as currently entered on screen. */
    private fun formSettings(): VmSettings {
        val d = VmSettings()
        return VmSettings(
            ramGb = ram.text.toString().toIntOrNull()?.coerceIn(1, 12) ?: d.ramGb,
            smp = smp.text.toString().toIntOrNull()?.coerceIn(1, 8) ?: d.smp,
            tbSizeMb = tbSize.text.toString().toIntOrNull()?.coerceIn(64, 4096) ?: d.tbSizeMb,
            splitWx = splitWx.isChecked,
            inProcess = inProcess.isChecked,
            vcpuAffinity = affinity.text.toString().trim(),
            backend = Backend.entries.firstOrNull { it.ordinal + 1 == backendGroup.checkedRadioButtonId } ?: d.backend,
        )
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
