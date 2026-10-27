package io.github.dylanneve1.iosonpixel

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Color
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.WindowInsets
import android.widget.Button
import android.widget.CheckBox
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
    /** The selected install (in a developer session, a one-off copy of it; see [Installs.developer]). */
    private lateinit var install: Install
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
    private lateinit var avfDisplay: Switch
    private lateinit var avfNetwork: Switch
    private lateinit var avfCamera: Switch
    private lateinit var backendGroup: RadioGroup
    private lateinit var securityGroup: RadioGroup
    private lateinit var avfInfo: TextView
    private lateinit var subtitle: TextView
    private lateinit var advanced: LinearLayout
    private lateinit var advancedToggle: TextView

    private lateinit var installGroup: RadioGroup
    private val installIds = mutableMapOf<Int, String>()
    private lateinit var addInstall: Button
    private lateinit var renameInstall: Button
    private lateinit var removeInstall: Button
    private lateinit var extraArgs: EditText
    /** Set while [fillForm] writes the fields, so their listeners don't treat it as an edit. */
    private var filling = false

    private lateinit var log: TextView
    private lateinit var logBox: View
    private lateinit var logToggle: TextView

    private val onState: () -> Unit = { render() }

    /**
     * Developer override (debuggable builds): run this backend / VM folder instead of the selected
     * install's, without saving anything. See the hooks at the end of onCreate.
     */
    private var devBackend: Backend? = null
    private var devVmDir: File? = null
    private var devAvfDisplay: Boolean? = null
    private var devAvfNetwork: Boolean? = null
    private val devSession get() = devBackend != null || devVmDir != null || devAvfDisplay != null || devAvfNetwork != null
    /**
     * [VmState.runs] when the developer session pressed Start, or -1: once that run has begun and
     * stopped, the session ends ([endDevSession]).
     */
    private var devRunsAtStart = -1
    private lateinit var devBanner: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ui = Ui(this)
        DebugBundle.install(this)
        val debuggable = (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
        // A developer session that ended stays ended when the activity is recreated from the same intent.
        if (debuggable && savedInstanceState?.getBoolean(KEY_DEV_ENDED) != true) {
            devBackend = intent.getStringExtra(VmService.EXTRA_BACKEND)?.let { Backend.fromId(it) }
            devVmDir = intent.getStringExtra(VmService.EXTRA_VM_DIR)?.let { File(it) }
            if (intent.hasExtra(VmService.EXTRA_AVF_DISPLAY)) devAvfDisplay = intent.getBooleanExtra(VmService.EXTRA_AVF_DISPLAY, false)
            if (intent.hasExtra(VmService.EXTRA_AVF_NETWORK)) devAvfNetwork = intent.getBooleanExtra(VmService.EXTRA_AVF_NETWORK, false)
        }
        devRunsAtStart = savedInstanceState?.getInt(KEY_DEV_RUNS, -1) ?: -1
        // The first call migrates the single-VM app into install 1 (see InstallMigration).
        loadInstall()
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
        subtitle = ui.text(14f, Ui.MUTED).also { root.addView(it, ui.lp(top = 2)) }
        devBanner = ui.text(14f, Color.BLACK, bold = true).apply {
            background = ui.roundRect(Ui.WARN, 12)
            setPadding(ui.dp(12), ui.dp(10), ui.dp(12), ui.dp(10))
            setOnClickListener {
                if (VmState.status == VmState.Status.STOPPED) endDevSession()
                else Toast.makeText(this@MainActivity, "Stop the VM first; the session ends when it stops", Toast.LENGTH_SHORT).show()
            }
        }.also { root.addView(it, ui.lp(top = 8)) }

        buildInstallsCard(root)
        buildStatusCard(root)
        buildStorageCard(root)
        buildSettingsCard(root)
        buildDisplayCard(root)
        buildDiagnosticsCard(root)
        fillForm(install.settings)

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
        // Run a backend / VM folder for this session only, nothing saved; `start` presses Start:
        //   adb shell am start -n <pkg>/.MainActivity --es backend avf --es vm_dir <dir> [--ez avf_display true]
        //     [--ez avf_network true] --ez start true
        if (devSession) VmState.addLine("note: developer session: ${backend.kind.label}, VM folder ${install.dir}" +
            "; settings are not saved")
        if (debuggable && savedInstanceState == null && intent.getBooleanExtra(EXTRA_START, false)) {
            if (VmState.status == VmState.Status.STOPPED) startVm()
        }
    }

    /**
     * Leaves the developer session (an adb `--es backend/vm_dir` or `--ez avf_display` launch):
     * back to the selected install, its saved settings and the install list. Called when the
     * session's VM stops, or when the banner is tapped while nothing runs.
     */
    private fun endDevSession() {
        if (!devSession) return
        devBackend = null
        devVmDir = null
        devAvfDisplay = null
        devAvfNetwork = null
        devRunsAtStart = -1
        devEnded = true
        loadInstall()
        fillForm(install.settings)
        VmState.addLine("note: developer session ended; back to ${install.name}")
        render()
    }

    private var devEnded = false

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean(KEY_DEV_ENDED, devEnded)
        outState.putInt(KEY_DEV_RUNS, devRunsAtStart)
    }

    /** (Re)reads the selected install and makes its backend. */
    private fun loadInstall() {
        val selected = Installs.current(this)
        install = if (devSession) Installs.developer(selected, devBackend, devVmDir, devAvfDisplay, devAvfNetwork) else selected
        backend = VmBackend.create(this, install)
        // The app creates the shared folder itself, so files adb pushes into it stay readable to the app.
        if (!devSession) File(install.sharedDir).mkdirs()
    }

    /** The install list: pick one, add, rename, remove. Only one VM runs at a time. */
    private fun buildInstallsCard(root: LinearLayout) {
        val card = ui.card(root, "Installs", "Each install has its own VM folder, engine and settings.")
        installGroup = RadioGroup(this).also { card.addView(it, ui.lp(top = 8)) }
        installGroup.setOnCheckedChangeListener { _, checked ->
            if (filling) return@setOnCheckedChangeListener
            val id = installIds[checked] ?: return@setOnCheckedChangeListener
            if (id == install.id) return@setOnCheckedChangeListener
            saveSettings()
            Installs.select(this, id)
            loadInstall()
            fillForm(install.settings)
            render()
        }
        val buttons = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        addInstall = ui.button("Add", Ui.Style.TONAL) { addDialog() }
        renameInstall = ui.button("Rename", Ui.Style.OUTLINED) { renameDialog() }
        removeInstall = ui.button("Remove", Ui.Style.OUTLINED) { removeDialog() }
        buttons.addView(addInstall, ui.lp(width = 0, weight = 1f))
        buttons.addView(renameInstall, ui.lp(start = 8, width = 0, weight = 1f))
        buttons.addView(removeInstall, ui.lp(start = 8, width = 0, weight = 1f))
        card.addView(buttons, ui.lp(top = 12))
    }

    private fun renderInstalls(enabled: Boolean) {
        val all = Installs.all(this)
        filling = true
        installGroup.removeAllViews()
        installIds.clear()
        for (i in all) {
            val id = View.generateViewId()
            installIds[id] = i.id
            installGroup.addView(RadioButton(this).apply {
                this.id = id
                text = "${i.name}\n${i.backend.label} · ${if (i.internal) "internal storage" else "shared storage"}"
                setTextColor(Ui.TEXT)
                buttonTintList = ColorStateList.valueOf(ui.accent)
                isEnabled = enabled && !devSession
            })
            if (i.id == (if (devSession) Installs.current(this).id else install.id)) installGroup.check(id)
        }
        filling = false
        addInstall.isEnabled = enabled && !devSession
        renameInstall.isEnabled = !devSession
        removeInstall.isEnabled = enabled && !devSession && all.size > 1
    }

    private fun nameField(value: String) = EditText(this).apply {
        setText(value)
        setSelection(value.length)
        isSingleLine = true
        setTextColor(Ui.TEXT)
    }

    private fun dialogBody(vararg views: View) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(ui.dp(24), ui.dp(8), ui.dp(24), 0)
        views.forEach { addView(it, ui.lp(top = 8)) }
    }

    private fun addDialog() {
        val name = nameField("")
        name.hint = "Name, e.g. iOS 16.5 (AVF)"
        val engines = RadioGroup(this)
        for (b in Backend.entries) engines.addView(RadioButton(this).apply {
            id = b.ordinal + 1
            text = backendLabel(b)
            buttonTintList = ColorStateList.valueOf(ui.accent)
        })
        engines.check(Backend.QEMU.ordinal + 1)
        AlertDialog.Builder(this)
            .setTitle("Add install")
            .setMessage("Starts with an empty VM folder in internal storage. Import a folder into it, or adb push to its shared folder and move it in.")
            .setView(dialogBody(name, engines))
            .setPositiveButton("Add") { _, _ ->
                saveSettings()
                val b = Backend.entries.firstOrNull { it.ordinal + 1 == engines.checkedRadioButtonId } ?: Backend.QEMU
                Installs.add(this, name.text.toString(), b)
                loadInstall()
                fillForm(install.settings)
                render()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun renameDialog() {
        val name = nameField(install.name)
        AlertDialog.Builder(this)
            .setTitle("Rename install")
            .setView(dialogBody(name))
            .setPositiveButton("Rename") { _, _ ->
                val n = name.text.toString().trim()
                if (n.isNotEmpty()) {
                    // Fresh from the store: a move may have changed it since this screen loaded it.
                    Installs.get(this, install.id)?.let { Installs.update(this, it.copy(name = n)) }
                    loadInstall()
                    render()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    /** Removing only forgets the install, unless "delete its files" is ticked (and then only its own folders). */
    private fun removeDialog() {
        val target = install
        val deletable = Installs.deletableDirs(this, target)
        val bytes = deletable.sumOf { d -> d.walkBottomUp().filter { it.isFile }.sumOf { it.length() } }
        val delete = CheckBox(this).apply {
            text = "Also delete its files (${ImportService.gib(bytes)})"
            setTextColor(Ui.TEXT)
            buttonTintList = ColorStateList.valueOf(ui.accent)
        }
        val kept = listOf(target.internalDir, target.sharedDir).distinct().filter { p -> deletable.none { it.path == File(p).canonicalPath } }
        val message = "Remove “${target.name}” from the list?" +
            (if (kept.isNotEmpty()) "\n\nThese folders are kept; the app didn't create them for this install:\n" + kept.joinToString("\n") else "") +
            (if (deletable.isNotEmpty()) "\n\nUnless you tick the box, its files stay too:\n" + deletable.joinToString("\n") { it.path } else "")
        val views = if (deletable.isNotEmpty()) arrayOf<View>(delete) else emptyArray()
        val d = AlertDialog.Builder(this)
            .setTitle("Remove install")
            .setMessage(message)
            .setView(dialogBody(*views))
            .setPositiveButton("Remove") { _, _ ->
                val wipe = delete.isChecked
                Installs.remove(this, target.id)
                loadInstall()
                fillForm(install.settings)
                render()
                if (wipe) Thread({
                    val freed = Installs.deleteFiles(applicationContext, target)
                    runOnUiThread { Toast.makeText(this, "Deleted ${ImportService.gib(freed)}", Toast.LENGTH_SHORT).show() }
                }, "install-delete").start()
            }
            .setNegativeButton("Cancel", null)
            .show()
        delete.setOnCheckedChangeListener { _, on ->
            d.getButton(AlertDialog.BUTTON_POSITIVE).text = if (on) "Remove and delete files" else "Remove"
        }
    }

    private fun backendLabel(b: Backend) = when (b) {
        Backend.QEMU -> "QEMU (software emulation)"
        Backend.AVF -> "AVF (hardware virtualization)"
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
        start = ui.button("Start", Ui.Style.FILLED) { startVm() }
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
            else ImportService.move(this, install, toInternal = moveToInternal())
        }.also { card.addView(it, ui.lp(top = 8)) }
        importProgress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 1000
            progressTintList = ColorStateList.valueOf(ui.accent)
        }.also { card.addView(it, ui.lp(top = 12)) }
        importStatus = ui.text(12.5f, Ui.MUTED).also { card.addView(it, ui.lp(top = 4)) }
    }

    private fun buildSettingsCard(root: LinearLayout) {
        val s = VmSettings()
        val card = ui.card(root, "Settings", "For the selected install. Saved when you start the VM or leave this screen.")
        ram = ui.field(
            card, "Guest RAM", s.ramGb.toString(), suffix = "GiB",
            help = "At most ${VmSettings.MAX_RAM_GB}, the iPhone 11's maximum.",
        )
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
        inProcess.setOnCheckedChangeListener { _, _ -> if (!filling) { saveSettings(); render() } }
        extraArgs = ui.field(
            advanced, "Extra arguments", s.extraArgs,
            help = "Appended to the QEMU command line, or to the AVF guest's kernel command line. " +
                "Separated by spaces, no quoting.",
            numeric = false,
        )

        advanced.addView(ui.text(14f, Ui.TEXT, bold = true).apply { text = "VM backend" }, ui.lp(top = 20))
        backendGroup = RadioGroup(this).also { advanced.addView(it, ui.lp(top = 4)) }
        for (b in Backend.entries) {
            backendGroup.addView(RadioButton(this).apply {
                id = b.ordinal + 1
                text = backendLabel(b)
                setTextColor(Ui.TEXT)
                buttonTintList = ColorStateList.valueOf(ui.accent)
                isEnabled = b.implemented
                if (!b.implemented) alpha = 0.5f
            })
        }
        // The checks and the header depend on it, so apply it right away.
        backendGroup.setOnCheckedChangeListener { _, _ ->
            if (filling) return@setOnCheckedChangeListener
            if (devSession) {
                devBackend = formSettings().backend
                install = Installs.developer(Installs.current(this), devBackend, devVmDir, devAvfDisplay, devAvfNetwork)
            } else {
                saveSettings()
            }
            backend = VmBackend.create(this, install)
            render()
        }
        avfInfo = ui.text(12.5f, Ui.MUTED, selectable = true).also { advanced.addView(it, ui.lp(top = 4)) }
        avfDisplay = switch(
            advanced, "Experimental AVF display",
            "AVF only: the guest's screen and touch in the display, through a framebuffer shared with the VM. " +
                "Needs a wider hidden-API exemption (see the checks). Off: console only.", s.avfDisplay,
        )
        // The checks and the display button depend on it, so apply it right away.
        avfDisplay.setOnCheckedChangeListener { _, checked ->
            if (filling) return@setOnCheckedChangeListener
            if (devSession) {
                devAvfDisplay = checked
                install = Installs.developer(Installs.current(this), devBackend, devVmDir, devAvfDisplay, devAvfNetwork)
            } else {
                saveSettings()
            }
            backend = VmBackend.create(this, install)
            render()
        }
        avfNetwork = switch(
            advanced, "Network",
            "AVF only: the guest gets a network card on the phone's connection (Wi-Fi or mobile data), " +
                "through the network Android sets up for virtual machines. Off: no network.", s.avfNetwork,
        )
        avfNetwork.setOnCheckedChangeListener { _, checked ->
            if (filling) return@setOnCheckedChangeListener
            if (devSession) {
                devAvfNetwork = checked
                install = Installs.developer(Installs.current(this), devBackend, devVmDir, devAvfDisplay, devAvfNetwork)
            } else {
                saveSettings()
            }
            backend = VmBackend.create(this, install)
            render()
        }
        avfCamera = switch(
            advanced, "Camera",
            "AVF only, with the experimental display: the phone's camera in the guest (iOS's Camera app). Android asks " +
                "for the camera permission when you turn this on. The camera is used only while the guest asks for it " +
                "and Scion is on screen.", s.avfCamera,
        )
        avfCamera.setOnCheckedChangeListener { _, checked ->
            if (filling) return@setOnCheckedChangeListener
            if (checked && !cameraGranted()) requestPermissions(arrayOf(Manifest.permission.CAMERA), REQ_CAMERA)
            saveSettings()
            backend = VmBackend.create(this, install)
        }

        advanced.addView(ui.text(14f, Ui.TEXT, bold = true).apply { text = "Guest security" }, ui.lp(top = 20))
        securityGroup = RadioGroup(this).also { advanced.addView(it, ui.lp(top = 4)) }
        for (level in Security.entries) {
            securityGroup.addView(RadioButton(this).apply {
                id = level.ordinal + 1
                text = level.label + if (level == Security.DEFAULT) " (default)" else ""
                setTextColor(Ui.TEXT)
                buttonTintList = ColorStateList.valueOf(ui.accent)
            })
        }
        advanced.addView(ui.text(12.5f, Ui.MUTED).apply {
            text = "AVF only: passed to the guest kernel as ${Security.BOOT_ARG}=<level>. The kernel decides what each " +
                "level means. The default is the most secure level the kernel boots with; until the kernel defines " +
                "its levels, that's ${Security.DEFAULT.label}."
        }, ui.lp(top = 4))

    }

    /** Shows [s] in the settings fields (on start and when another install is picked). */
    private fun fillForm(s: VmSettings) {
        filling = true
        ram.setText(s.ramGb.toString())
        smp.setText(s.smp.toString())
        tbSize.setText(s.tbSizeMb.toString())
        affinity.setText(s.vcpuAffinity)
        splitWx.isChecked = s.splitWx
        inProcess.isChecked = s.inProcess
        avfDisplay.isChecked = s.avfDisplay
        avfNetwork.isChecked = s.avfNetwork
        avfCamera.isChecked = s.avfCamera
        extraArgs.setText(s.extraArgs)
        backendGroup.check(s.backend.ordinal + 1)
        securityGroup.check(s.security.ordinal + 1)
        filling = false
        renderPinning()
        // Open the advanced section if anything in it is set away from its default.
        val d = VmSettings()
        setAdvancedShown(
            s.vcpuAffinity != d.vcpuAffinity || s.splitWx != d.splitWx || s.inProcess != d.inProcess ||
                s.backend != d.backend || s.security != d.security || s.extraArgs != d.extraArgs ||
                s.avfDisplay != d.avfDisplay || s.avfNetwork != d.avfNetwork || s.avfCamera != d.avfCamera || devSession,
        )
    }

    /** Settings → Display: app-wide, saved as soon as they change (read when the display opens). */
    private fun buildDisplayCard(root: LinearLayout) {
        var p = DisplayPrefs.load(this)
        val card = ui.card(root, "Display", "How the guest screen is shown. For every install.")
        fun save(next: DisplayPrefs) { p = next; p.save(this) }
        val border = switch(
            card, "Border around the guest screen",
            "A black frame the app owns, so swipes can start outside Android's own edge gestures.", p.border,
        )
        val width = ui.field(
            card, "Border width", if (p.borderDp == DisplayPrefs.AUTO) "" else p.borderDp.toString(), suffix = "dp",
            help = "0–${DisplayPrefs.MAX_DP}. Empty = automatic: Android's gesture area, at least ${DisplayPrefs.MIN_AUTO_DP} dp.",
        ).apply { hint = "auto" }
        border.setOnCheckedChangeListener { _, on -> save(p.copy(border = on)); width.isEnabled = on }
        width.isEnabled = p.border
        width.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(e: Editable?) {
                val dp = e.toString().trim().toIntOrNull()?.coerceIn(0, DisplayPrefs.MAX_DP) ?: DisplayPrefs.AUTO
                save(p.copy(borderDp = dp))
            }
        })
        val snap = switch(
            card, "Edge swipes go to the guest",
            "A touch starting in the border, or within ${DisplayPrefs.SNAP_DP} dp of the screen's edge, starts " +
                "exactly on the guest's edge: swipe up from the bottom, down from the top-right corner.", p.snap,
        )
        snap.setOnCheckedChangeListener { _, on -> save(p.copy(snap = on)) }
        val pin = switch(
            card, "Pin the guest screen",
            "While the guest screen is open, Android's app pinning keeps you in it: no home, recents or " +
                "notification shade. Android asks to confirm the first time. Leave with Exit on the buttons " +
                "bar, or Android's unpin gesture (swipe up and hold).", p.pin,
        )
        pin.setOnCheckedChangeListener { _, on -> save(p.copy(pin = on)) }

        card.addView(ui.text(14f, Ui.TEXT, bold = true).apply { text = "Hardware buttons bar" }, ui.lp(top = 20))
        val group = RadioGroup(this).also { card.addView(it, ui.lp(top = 4)) }
        // Generated ids: the settings card's radio buttons use 1..n, and duplicate ids mix up saved view state.
        val ids = DisplayPrefs.BarMode.entries.associateWith { View.generateViewId() }
        for ((m, viewId) in ids) {
            group.addView(RadioButton(this).apply {
                id = viewId
                text = m.label
                setTextColor(Ui.TEXT)
                buttonTintList = ColorStateList.valueOf(ui.accent)
                isSaveEnabled = false
            })
        }
        group.check(ids.getValue(p.bar))
        group.setOnCheckedChangeListener { _, id ->
            ids.entries.firstOrNull { it.value == id }?.let { save(p.copy(bar = it.key)) }
        }
        card.addView(ui.text(12.5f, Ui.MUTED).apply {
            text = "Vol −, Vol +, Home, Apps, Power and Exit under the guest screen. Auto-hide: hidden until you tap the " +
                "border or tap with two fingers, and hidden again 3 s after the last press. Hidden, the guest " +
                "screen uses the space. Android's back gesture shows the bar instead of closing the screen."
        }, ui.lp(top = 4))
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
        logToggle.text = (if (shown) "Hide " else "Show ") + "output" + if (shown) "  ▴" else "  ▾"
    }

    private fun copyLog() {
        val cm = getSystemService(ClipboardManager::class.java)
        cm.setPrimaryClip(ClipData.newPlainText("${VmState.install?.name ?: install.name} output", VmState.log()))
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
        // A developer session lasts as long as its VM: once that has stopped, the user gets the app back.
        if (devSession && devRunsAtStart >= 0 && VmState.runs > devRunsAtStart && VmState.status == VmState.Status.STOPPED) {
            endDevSession()
            return
        }
        devBanner.visibility = if (devSession) View.VISIBLE else View.GONE
        devBanner.text = if (VmState.status == VmState.Status.STOPPED) "Developer session (not saved). Tap to exit"
        else "Developer session (not saved). It ends when its VM stops"
        // A move (ImportService) switches the folder in use; pick that up.
        if (!devSession) Installs.get(this, install.id)?.takeIf { it.internal != install.internal }?.let {
            install = install.copy(internal = it.internal)
            backend = VmBackend.create(this, install)
        }
        subtitle.text = install.name + " · " + backendLabel(install.backend) + if (devSession) " · developer session" else ""
        avfInfo.text = "AVF needs a one-time adb setup; with AVF selected, the checks above show what's missing.\n" +
            AvfSupport.checks(this, install.settings.avfDisplay, install.settings.avfNetwork).joinToString("\n") { (if (it.ok) "✓ " else "✗ ") + it.text.lineSequence().first() }

        val st = VmState.status
        // The exit code and notice belong to the install that ran.
        val ranHere = VmState.install?.id == install.id
        val lastExit = VmState.lastExit?.takeIf { st == VmState.Status.STOPPED && ranHere }
        status.text = when (st) {
            VmState.Status.STOPPED -> "Stopped"
            VmState.Status.STARTING -> "Starting…"
            VmState.Status.RUNNING -> "Running"
            VmState.Status.STOPPING -> "Stopping…"
        }
        val dotColor = if (lastExit != null && lastExit != 0) Ui.BAD else ui.stateColor(st)
        statusDot.background = ui.pill(dotColor)
        val notice = VmState.notice?.takeIf { ranHere }
        statusDetail.setTextColor(if (notice != null) Ui.BAD else Ui.MUTED)
        statusDetail.text = when {
            notice != null -> notice + (lastExit?.let { "\nLast exit: $it" } ?: "")
            lastExit != null -> "Last exit: $lastExit"
            st == VmState.Status.RUNNING && backend.hasDisplay -> "Open the display to use the iPhone."
            st == VmState.Status.RUNNING -> "No display yet: the guest console is in the output below."
            else -> ""
        }
        statusDetail.visibility = if (statusDetail.text.isEmpty()) View.GONE else View.VISIBLE

        val list = backend.checks()
        val failing = list.count { !it.ok && it.blocking }
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

        val internal = install.internal
        val sharedHasVm = File(install.sharedDir, install.manifest).isFile
        location.text = if (internal) "In use: internal app storage" else "In use: shared storage"
        locationPath.text = install.dir.absolutePath
        locationHint.text = buildString {
            append("adb push your prepared files to\n").append(install.sharedDir)
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

        renderInstalls(enabled = st == VmState.Status.STOPPED && !importing)
        start.isEnabled = st == VmState.Status.STOPPED && !importing && failing == 0
        stop.isEnabled = st == VmState.Status.RUNNING || st == VmState.Status.STARTING
        display.isEnabled = st != VmState.Status.STOPPED && backend.hasDisplay

        val text = VmState.log()
        log.text = if (text.isEmpty()) "No output yet." else
            (if (!ranHere) "Output of ${VmState.install?.name ?: "another install"}:\n" else "") + text
        log.setTextColor(if (text.isEmpty()) Ui.MUTED else Ui.TEXT)
    }

    /** The move button's direction: into internal storage unless the VM is already there and nothing newer is in shared. */
    private fun moveToInternal() = !install.internal || File(install.sharedDir, install.manifest).isFile

    private fun renderPinning() {
        val p = formSettings().effectiveVcpuAffinity
        pinning.text = "QEMU_VCPU_AFFINITY = " + p.ifBlank { "(off)" }
    }

    /**
     * Saves the form into the selected install, only if something changed (so merely opening
     * the app never rewrites anything). A developer session saves nothing.
     */
    private fun saveSettings() {
        if (devSession) return
        val s = formSettings()
        if (s == install.settings) return
        // Fresh from the store, so a move or rename made meanwhile isn't undone.
        val stored = Installs.get(this, install.id) ?: return
        install = stored.copy(settings = s)
        Installs.update(this, install)
    }

    private fun startVm() {
        saveSettings()
        // (the VM boots either way: until the permission is there the guest's camera frames are black)
        if (install.settings.avfCamera && install.settings.avfDisplay && !cameraGranted()) {
            requestPermissions(arrayOf(Manifest.permission.CAMERA), REQ_CAMERA)
        }
        VmService.start(this, if (devSession) Installs.current(this) else install, devBackend, devVmDir, devAvfDisplay, devAvfNetwork)
        if (devSession) devRunsAtStart = VmState.runs
        if (backend.hasDisplay) openDisplay()
    }

    /** The settings as currently entered on screen. */
    private fun formSettings(): VmSettings {
        val d = VmSettings()
        return VmSettings(
            ramGb = ram.text.toString().toIntOrNull()?.coerceIn(1, VmSettings.MAX_RAM_GB) ?: d.ramGb,
            smp = smp.text.toString().toIntOrNull()?.coerceIn(1, 8) ?: d.smp,
            tbSizeMb = tbSize.text.toString().toIntOrNull()?.coerceIn(64, 4096) ?: d.tbSizeMb,
            splitWx = splitWx.isChecked,
            inProcess = inProcess.isChecked,
            vcpuAffinity = affinity.text.toString().trim(),
            backend = Backend.entries.firstOrNull { it.ordinal + 1 == backendGroup.checkedRadioButtonId } ?: d.backend,
            security = Security.entries.firstOrNull { it.ordinal + 1 == securityGroup.checkedRadioButtonId } ?: d.security,
            extraArgs = extraArgs.text.toString().trim(),
            avfDisplay = avfDisplay.isChecked,
            avfNetwork = avfNetwork.isChecked,
            avfCamera = avfCamera.isChecked,
        )
    }

    private fun cameraGranted() = checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQ_CAMERA || cameraGranted()) return
        // Refused (or Android no longer asks): the setting goes back off; Android's app settings can still allow it.
        Toast.makeText(this, "No camera permission: the guest won't get the camera. Allow it in Android's app settings.", Toast.LENGTH_LONG).show()
        if (avfCamera.isChecked) avfCamera.isChecked = false
    }

    private fun openDisplay() = startActivity(Intent(this, DisplayActivity::class.java))

    @Deprecated("Plain framework Activity; no AndroidX result API here")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        val tree = data?.data
        if (requestCode == REQ_IMPORT && resultCode == RESULT_OK && tree != null) ImportService.start(this, install, tree)
    }

    private companion object {
        const val REQ_IMPORT = 1
        const val REQ_CAMERA = 2
        const val EXTRA_START = "start"
        const val KEY_DEV_ENDED = "dev_session_ended"
        const val KEY_DEV_RUNS = "dev_session_runs"
    }
}
