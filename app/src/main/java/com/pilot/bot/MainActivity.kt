package com.pilot.bot

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.*

class MainActivity : Activity() {
    private val uiHandler = Handler(Looper.getMainLooper())
    private lateinit var powerBtn: Button
    private lateinit var previewToggleBtn: Button
    private lateinit var statusPill: TextView
    private lateinit var keyStatusText: TextView
    private lateinit var slotMonitorText: TextView
    private lateinit var telemetryText: TextView

    // Identity & Sync Code inputs
    private lateinit var syncCodeInput: EditText
    private lateinit var tokenInput: EditText
    private lateinit var allowedIdsInput: EditText
    private lateinit var cloudInput: EditText
    private lateinit var updateUrlInput: EditText

    // Rejoiner & Key Automator inputs
    private lateinit var gameSpinner: Spinner
    private lateinit var placeIdInput: EditText
    private lateinit var vipInput: EditText
    private lateinit var fpsSwitch: Switch
    private lateinit var render3dSwitch: Switch
    private lateinit var bootSwitch: Switch
    private lateinit var nousigiSwitch: Switch
    private lateinit var luarmorInput: EditText
    private lateinit var lvEmailInput: EditText
    private lateinit var lvPassInput: EditText
    private lateinit var keyPathInput: EditText
    private lateinit var deltaSwitch: Switch
    private lateinit var deltaUrlInput: EditText

    private val refreshTick = object : Runnable {
        override fun run() {
            syncPowerButton()
            syncPreviewButton()
            refreshTelemetry()
            uiHandler.postDelayed(this, 1500L)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        PilotCore.tryAutoSeedFromDisk(this)
        buildHud()
        loadFieldsFromPrefs()
        uiHandler.post(refreshTick)
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        saveFieldsSilent()
        buildHud()
        loadFieldsFromPrefs()
    }

    override fun onDestroy() {
        uiHandler.removeCallbacks(refreshTick)
        super.onDestroy()
    }

    private fun buildHud() {
        val isLandscape = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#0A0F1A"))
            setPadding(dp(12), dp(10), dp(12), dp(10))
        }

        // Top Bar
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(8), dp(12), dp(8))
            background = cardBg("#111827", "#1F2937")
        }
        val title = TextView(this).apply {
            text = "PILOT HUD  ${PilotCore.APP_VERSION}"
            textSize = 15f
            setTypeface(Typeface.MONOSPACE, Typeface.BOLD)
            setTextColor(Color.parseColor("#F8FAFC"))
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        statusPill = TextView(this).apply {
            textSize = 11f
            setTypeface(Typeface.MONOSPACE, Typeface.BOLD)
            setPadding(dp(10), dp(4), dp(10), dp(4))
        }
        header.addView(title)
        header.addView(statusPill)
        root.addView(header)

        // Main Body: 2 Columns in Landscape, Stacked in Portrait
        val bodyContainer = LinearLayout(this).apply {
            orientation = if (isLandscape) LinearLayout.HORIZONTAL else LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f
            ).apply { topMargin = dp(10) }
        }

        val leftScroll = ScrollView(this).apply {
            layoutParams = if (isLandscape)
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1.15f).apply { rightMargin = dp(8) }
            else
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1.2f).apply { bottomMargin = dp(8) }
        }
        leftScroll.addView(buildControlsColumn())

        val rightScroll = ScrollView(this).apply {
            layoutParams = if (isLandscape)
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 0.85f)
            else
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 0.8f)
        }
        rightScroll.addView(buildMonitorColumn())

        bodyContainer.addView(leftScroll)
        bodyContainer.addView(rightScroll)
        root.addView(bodyContainer)
        setContentView(root)
        syncPowerButton()
        syncPreviewButton()
    }

    private fun buildControlsColumn(): LinearLayout {
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        // 1. Power Card
        val powerCard = makeCard("BOT CONTROL")
        powerBtn = Button(this).apply {
            textSize = 13f
            setTypeface(Typeface.DEFAULT_BOLD)
            setTextColor(Color.WHITE)
            setOnClickListener {
                if (PilotCore.isRunning) {
                    PilotCore.stopService(this@MainActivity)
                } else {
                    saveFieldsSilent()
                    if (!Settings.canDrawOverlays(this@MainActivity)) {
                        runCatching {
                            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
                        }
                    }
                    Thread { PilotCore.autoGrantRootAndPerms(this@MainActivity) }.start()
                    PilotCore.startService(this@MainActivity)
                }
                syncPowerButton()
            }
        }
        powerCard.addView(powerBtn)

        val quickRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(8)
            }
        }
        quickRow.addView(makeSmallBtn("REJOIN ALL", "#1D4ED8") {
            saveFieldsSilent()
            Thread { Rejoiner.killAllAndStaggeredRejoin(this, "HUD button") }.start()
            toast("Staggered rejoin queued")
        })
        quickRow.addView(makeSmallBtn("NOUSIGI KEY", "#7C3AED") {
            saveFieldsSilent()
            KeyAutomator.startNousigiRenewal(this)
            toast("Nousigi key renewal started")
        })
        quickRow.addView(makeSmallBtn("DELTA KEY", "#0F766E") {
            saveFieldsSilent()
            KeyAutomator.startDeltaRenewal(this)
            toast("Delta key renewal started")
        })
        previewToggleBtn = makeSmallBtn("PREVIEW: OFF", "#334155") {
            val next = !KeyAutomator.isPreviewEnabled()
            KeyAutomator.setPreviewEnabled(next)
            syncPreviewButton()
            toast(if (next) "Live WebView preview ON" else "Live WebView preview OFF")
        }
        quickRow.addView(previewToggleBtn)
        powerCard.addView(quickRow)
        col.addView(powerCard)

        // 2. Sync Code & Identity Card (Saves & restores ALL settings including Key Automator + Rejoiner)
        val idCard = makeCard("ALL-IN-ONE SYNC CODE & IDENTITY")
        syncCodeInput = makeInput("Paste XXXXXXX-... Sync Code (restores ALL settings)")
        val codeRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(6)
            }
        }
        codeRow.addView(makeSmallBtn("APPLY CODE", "#15803D") {
            val code = syncCodeInput.text.toString().trim()
            if (code.isEmpty()) {
                toast("Paste a sync code first")
            } else {
                Thread {
                    val (ok, _) = PilotCore.applyCode(this, code, startSvc = true)
                    uiHandler.post {
                        if (ok) {
                            loadFieldsFromPrefs()
                            syncPowerButton()
                            toast("All bot, key & rejoiner settings applied!")
                        } else {
                            toast("Invalid sync code")
                        }
                    }
                }.start()
            }
        })
        codeRow.addView(makeSmallBtn("COPY FULL CONFIG CODE", "#334155") {
            saveFieldsSilent()
            val code = PilotCore.makeCode(this, autoRoot = true)
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("PilotSync", code))
            toast("Full config sync code copied!")
        })

        tokenInput = makeInput("Discord Bot Token", password = true)
        allowedIdsInput = makeInput("Allowed User/Role IDs (comma-separated)")
        cloudInput = makeInput("Cloud Phone Name (e.g. UG-DE-01)")
        updateUrlInput = makeInput("Google Drive APK Update URL (optional)")

        idCard.addView(syncCodeInput)
        idCard.addView(codeRow)
        idCard.addView(tokenInput)
        idCard.addView(allowedIdsInput)
        idCard.addView(cloudInput)
        idCard.addView(updateUrlInput)
        col.addView(idCard)

        // 3. Rejoiner Settings Card
        val rjCard = makeCard("REJOINER SETTINGS")
        rjCard.addView(makeLabel("Famous Game Preset"))
        gameSpinner = Spinner(this).apply {
            adapter = ArrayAdapter(
                this@MainActivity,
                android.R.layout.simple_spinner_dropdown_item,
                Rejoiner.FAMOUS_GAMES.map { "${it.name}${if (it.placeId.isNotEmpty() && it.placeId != "CUSTOM") " (${it.placeId})" else ""}" }
            )
            setBackgroundColor(Color.parseColor("#1E293B"))
            onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                    val g = Rejoiner.FAMOUS_GAMES[pos]
                    if (g.placeId != "CUSTOM") placeIdInput.setText(g.placeId)
                }
                override fun onNothingSelected(p: AdapterView<*>?) {}
            }
        }
        rjCard.addView(gameSpinner)
        placeIdInput = makeInput("Place ID (blank = Auto last game)")
        vipInput = makeInput("Private Server Link(s), comma-separated")
        fpsSwitch = makeSwitch("Auto FPS Optimization (ON by default)")
        render3dSwitch = makeSwitch("Ultra-Low GPU: 3D Render Off (auto-ON for screenshots)")
        bootSwitch = makeSwitch("Auto-Start Rejoiner on App / UGPhone Reboot")
        rjCard.addView(placeIdInput)
        rjCard.addView(vipInput)
        rjCard.addView(fpsSwitch)
        rjCard.addView(render3dSwitch)
        rjCard.addView(bootSwitch)
        col.addView(rjCard)

        // 4. Key Automator Card
        val keyCard = makeCard("BACKGROUND KEY AUTOMATOR")
        nousigiSwitch = makeSwitch("Auto Get & Maximize Nousigi Key (Luarmor)")
        luarmorInput = makeInput("Luarmor URL")
        lvEmailInput = makeInput("Linkvertise Premium Email (saved in config & sync code)")
        lvPassInput = makeInput("Linkvertise Premium Password", password = true)
        keyPathInput = makeInput("Existing Key File Path (auto-detected if blank)")
        deltaSwitch = makeSwitch("Auto Get Delta Key (PlatoRelay + LootLabs)")
        deltaUrlInput = makeInput("Saved PlatoRelay URL (auto-grabbed if empty)")

        keyCard.addView(nousigiSwitch)
        keyCard.addView(luarmorInput)
        keyCard.addView(lvEmailInput)
        keyCard.addView(lvPassInput)
        keyCard.addView(keyPathInput)
        keyCard.addView(deltaSwitch)
        keyCard.addView(deltaUrlInput)

        val saveBtn = Button(this).apply {
            text = "SAVE ALL SETTINGS & SYNC"
            textSize = 12f
            setTextColor(Color.WHITE)
            background = cardBg("#0284C7", "#38BDF8")
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(8) }
            setOnClickListener {
                saveFieldsSilent()
                toast("All settings saved & synced!")
            }
        }
        keyCard.addView(saveBtn)
        col.addView(keyCard)

        return col
    }

    private fun buildMonitorColumn(): LinearLayout {
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        val keyMonCard = makeCard("KEY AUTOMATOR LIVE STATUS")
        keyStatusText = TextView(this).apply {
            textSize = 11.5f
            setTypeface(Typeface.MONOSPACE, Typeface.BOLD)
            setTextColor(Color.parseColor("#38BDF8"))
            text = "IDLE"
        }
        keyMonCard.addView(keyStatusText)
        col.addView(keyMonCard)

        val slotCard = makeCard("LIVE ROBLOX SLOTS (PING WATCHDOG)")
        slotMonitorText = TextView(this).apply {
            textSize = 11.5f
            setTypeface(Typeface.MONOSPACE)
            setTextColor(Color.parseColor("#E2E8F0"))
            text = "Scanning com.roblox.clien*..."
        }
        slotCard.addView(slotMonitorText)
        col.addView(slotCard)

        val logCard = makeCard("LIVE SYSTEM TELEMETRY")
        telemetryText = TextView(this).apply {
            textSize = 10.5f
            setTypeface(Typeface.MONOSPACE)
            setTextColor(Color.parseColor("#94A3B8"))
            text = "Waiting for events..."
        }
        logCard.addView(telemetryText)
        col.addView(logCard)

        return col
    }

    private fun syncPowerButton() {
        val online = PilotCore.isRunning
        if (online) {
            powerBtn.text = "■  STOP BOT"
            powerBtn.background = cardBg("#DC2626", "#F87171")
            statusPill.text = "● ONLINE"
            statusPill.setTextColor(Color.parseColor("#4ADE80"))
            statusPill.background = cardBg("#052E16", "#166534")
        } else {
            powerBtn.text = "▶  START BOT"
            powerBtn.background = cardBg("#16A34A", "#4ADE80")
            statusPill.text = "○ OFFLINE"
            statusPill.setTextColor(Color.parseColor("#94A3B8"))
            statusPill.background = cardBg("#1E293B", "#334155")
        }
    }

    private fun syncPreviewButton() {
        val on = KeyAutomator.isPreviewEnabled()
        previewToggleBtn.text = if (on) "PREVIEW: ON" else "PREVIEW: OFF"
        previewToggleBtn.background = cardBg(
            if (on) "#B45309" else "#334155",
            if (on) "#FBBF24" else "#475569"
        )
    }

    private fun refreshTelemetry() {
        val active = KeyAutomator.isRunning()
        keyStatusText.text = KeyAutomator.getLiveStatusText()
        keyStatusText.setTextColor(Color.parseColor(if (active) "#FBBF24" else "#38BDF8"))

        val now = System.currentTimeMillis()
        val slots = Rejoiner.slots.values.sortedBy { it.pkg }
        slotMonitorText.text = if (slots.isEmpty()) {
            "No com.roblox.clien* slots detected yet."
        } else {
            slots.joinToString("\n") { s ->
                val pingAgo = if (s.lastPingMs > 0) "${(now - s.lastPingMs) / 1000}s ago" else "no ping"
                val user = s.username.ifBlank { "(unknown)" }
                "• ${s.pkg.removePrefix("com.roblox.")} | $user | ${s.status} | ${s.fps} FPS ($pingAgo)"
            }
        }
        telemetryText.text = PilotCore.getLogs().takeLast(24).joinToString("\n").ifBlank { "No logs yet." }
    }

    private fun loadFieldsFromPrefs() {
        val cfg = Rejoiner.loadConfig(this)
        tokenInput.setText(PilotCore.getToken(this))
        allowedIdsInput.setText(PilotCore.getAllowedIdsRaw(this))
        cloudInput.setText(PilotCore.getCloud(this))
        updateUrlInput.setText(PilotCore.getUpdateUrl(this))

        placeIdInput.setText(cfg.targetPlaceId)
        vipInput.setText(cfg.privateServerLinks)
        fpsSwitch.isChecked = cfg.fpsOptimization
        render3dSwitch.isChecked = cfg.render3dOff
        bootSwitch.isChecked = cfg.autoStartOnBoot
        nousigiSwitch.isChecked = cfg.autoGetNousigiKey
        luarmorInput.setText(cfg.luarmorUrl)
        lvEmailInput.setText(cfg.lvEmail)
        lvPassInput.setText(cfg.lvPassword)
        deltaSwitch.isChecked = cfg.autoGetDeltaKey
        deltaUrlInput.setText(cfg.deltaPlatoUrl)

        if (cfg.nousigiKeyPath.isNotBlank()) {
            keyPathInput.setText(cfg.nousigiKeyPath)
        } else {
            Thread {
                val found = KeyAutomator.detectExistingKeyFile(this)
                if (found.isNotEmpty()) {
                    uiHandler.post { if (keyPathInput.text.isNullOrBlank()) keyPathInput.setText(found) }
                }
            }.start()
        }

        val presetIdx = Rejoiner.FAMOUS_GAMES.indexOfFirst { it.placeId == cfg.targetPlaceId }
            .takeIf { it >= 0 } ?: (Rejoiner.FAMOUS_GAMES.size - 1)
        gameSpinner.setSelection(presetIdx)
    }

    private fun saveFieldsSilent() {
        val cloud = cloudInput.text.toString().trim().ifBlank { PilotCore.getCloud(this) }
        val tok = tokenInput.text.toString().trim()
        val ids = allowedIdsInput.text.toString().trim()
        val upd = updateUrlInput.text.toString().trim()

        val prev = Rejoiner.loadConfig(this)
        Rejoiner.saveConfig(
            this,
            prev.copy(
                targetPlaceId = placeIdInput.text.toString().trim(),
                privateServerLinks = vipInput.text.toString().trim(),
                fpsOptimization = fpsSwitch.isChecked,
                render3dOff = render3dSwitch.isChecked,
                autoStartOnBoot = bootSwitch.isChecked,
                autoGetNousigiKey = nousigiSwitch.isChecked,
                luarmorUrl = luarmorInput.text.toString().trim(),
                lvEmail = lvEmailInput.text.toString().trim(),
                lvPassword = lvPassInput.text.toString(),
                nousigiKeyPath = keyPathInput.text.toString().trim(),
                autoGetDeltaKey = deltaSwitch.isChecked,
                deltaPlatoUrl = deltaUrlInput.text.toString().trim()
            )
        )
        PilotCore.saveConfig(this, cloud, tok, ids, PilotCore.getSsChannel(this), upd)
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun cardBg(fillHex: String, strokeHex: String) = GradientDrawable().apply {
        setColor(Color.parseColor(fillHex))
        setStroke(dp(1), Color.parseColor(strokeHex))
        cornerRadius = dp(10).toFloat()
    }

    private fun makeCard(header: String): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = cardBg("#111827", "#1F2937")
        setPadding(dp(12), dp(10), dp(12), dp(12))
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = dp(10) }
        addView(TextView(this@MainActivity).apply {
            text = header
            textSize = 11f
            setTypeface(Typeface.MONOSPACE, Typeface.BOLD)
            setTextColor(Color.parseColor("#38BDF8"))
            setPadding(0, 0, 0, dp(6))
        })
    }

    private fun makeLabel(t: String) = TextView(this).apply {
        text = t; textSize = 11f; setTextColor(Color.parseColor("#94A3B8"))
        setPadding(0, dp(4), 0, dp(2))
    }

    private fun makeInput(hintText: String, password: Boolean = false) = EditText(this).apply {
        hint = hintText
        setHintTextColor(Color.parseColor("#64748B"))
        setTextColor(Color.parseColor("#F8FAFC"))
        textSize = 12f
        background = cardBg("#0F172A", "#334155")
        setPadding(dp(10), dp(8), dp(10), dp(8))
        inputType = if (password) InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        else InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(6) }
    }

    private fun makeSwitch(label: String) = Switch(this).apply {
        text = label
        textSize = 12f
        setTextColor(Color.parseColor("#E2E8F0"))
        setPadding(0, dp(6), 0, dp(4))
    }

    private fun makeSmallBtn(label: String, hex: String, onClick: () -> Unit) = Button(this).apply {
        text = label
        textSize = 9.5f
        setTextColor(Color.WHITE)
        background = cardBg(hex, hex)
        layoutParams = LinearLayout.LayoutParams(0, dp(36), 1f).apply { rightMargin = dp(4) }
        setOnClickListener { onClick() }
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
}