package com.pilot.bot

import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
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

/** Custom Tactical Arc Telemetry Gauge (RAM % + Active Slots) */
class TelemetryArcView(context: Context) : View(context) {
    var ramPct: Float = 0f
    var activeSlots: Int = 0
    var totalSlots: Int = 4

    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 10f
        color = Color.parseColor("#1E293B")
        strokeCap = Paint.Cap.ROUND
    }
    private val ramPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 10f
        color = Color.parseColor("#06B6D4")
        strokeCap = Paint.Cap.ROUND
    }
    private val slotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 10f
        color = Color.parseColor("#10B981")
        strokeCap = Paint.Cap.ROUND
    }
    private val numPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#F8FAFC")
        textSize = 24f
        typeface = Typeface.MONOSPACE
        textAlign = Paint.Align.CENTER
    }
    private val subPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#94A3B8")
        textSize = 15f
        typeface = Typeface.MONOSPACE
        textAlign = Paint.Align.CENTER
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val h = height.toFloat()
        val r = (h * 0.36f).coerceAtMost(48f)
        val cy = h * 0.52f

        val cx1 = width * 0.25f
        val rect1 = RectF(cx1 - r, cy - r, cx1 + r, cy + r)
        canvas.drawArc(rect1, 135f, 270f, false, trackPaint)
        ramPaint.color = when {
            ramPct > 85f -> Color.parseColor("#EF4444")
            ramPct > 70f -> Color.parseColor("#F59E0B")
            else -> Color.parseColor("#06B6D4")
        }
        canvas.drawArc(rect1, 135f, (ramPct.coerceIn(0f, 100f) / 100f) * 270f, false, ramPaint)
        canvas.drawText("${ramPct.toInt()}%", cx1, cy + 7f, numPaint)
        canvas.drawText("RAM", cx1, cy + r + 14f, subPaint)

        val cx2 = width * 0.75f
        val rect2 = RectF(cx2 - r, cy - r, cx2 + r, cy + r)
        canvas.drawArc(rect2, 135f, 270f, false, trackPaint)
        val ratio = if (totalSlots > 0) (activeSlots.toFloat() / totalSlots.toFloat()).coerceIn(0f, 1f) else 0f
        canvas.drawArc(rect2, 135f, ratio * 270f, false, slotPaint)
        canvas.drawText("$activeSlots/$totalSlots", cx2, cy + 7f, numPaint)
        canvas.drawText("SLOTS", cx2, cy + r + 14f, subPaint)
    }
}

class MainActivity : Activity() {
    private val uiHandler = Handler(Looper.getMainLooper())
    private var currentTab = 0 // 0 = FLEET, 1 = CONFIG, 2 = CONSOLE

    private lateinit var powerBtn: Button
    private lateinit var rejoinPowerBtn: Button
    private lateinit var statusPill: TextView
    private lateinit var cloudBadge: TextView
    private lateinit var arcGauge: TelemetryArcView
    private lateinit var keyStatusText: TextView
    private lateinit var slotMatrixContainer: LinearLayout
    private lateinit var telemetryText: TextView

    private lateinit var tabFleetBtn: Button
    private lateinit var tabConfigBtn: Button
    private lateinit var tabConsoleBtn: Button
    private lateinit var fleetPane: ScrollView
    private lateinit var configPane: ScrollView
    private lateinit var consolePane: ScrollView

    // Identity & Sync Code inputs
    private lateinit var syncCodeInput: EditText
    private lateinit var tokenInput: EditText
    private lateinit var allowedIdsInput: EditText
    private lateinit var cloudInput: EditText
    private lateinit var updateUrlInput: EditText
    private lateinit var autoStartBotSwitch: Switch

    // 5-Key Host Mode inputs
    private lateinit var keyHostSwitch: Switch
    private lateinit var hostActionsContainer: LinearLayout
    private lateinit var nousigiGetUrlInput: EditText
    private lateinit var hostPoolSummaryTv: TextView

    // Rejoiner & Delta Key inputs
    private lateinit var autoRejoinSwitch: Switch
    private lateinit var gameSpinner: Spinner
    private lateinit var placeIdInput: EditText
    private lateinit var vipInput: EditText
    private lateinit var fpsSwitch: Switch
    private lateinit var render3dSwitch: Switch
    private lateinit var keyPathInput: EditText
    private lateinit var deltaSwitch: Switch
    private lateinit var deltaUrlInput: EditText

    private val refreshTick = object : Runnable {
        override fun run() {
            syncPowerButton()
            refreshTelemetry()
            uiHandler.postDelayed(this, 1600L)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        PilotCore.tryAutoSeedFromDisk(this)
        buildHud()
        loadFieldsFromPrefs()

        if (PilotCore.shouldAutoStart(this) && PilotCore.getToken(this).isNotEmpty() && !PilotCore.isRunning) {
            PilotCore.startService(this)
        }
        uiHandler.post(refreshTick)
    }

    override fun onResume() {
        super.onResume()
        KeyAutomator.setAppForeground(true)
        refreshHostPoolSummary()
    }

    override fun onPause() {
        KeyAutomator.setAppForeground(false)
        super.onPause()
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
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#060B14"))
            setPadding(dp(10), dp(8), dp(10), dp(8))
        }

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(8), dp(12), dp(8))
            background = cardBg("#0D1526", "#1E293B")
        }
        val titleCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        val title = TextView(this).apply {
            text = "PILOT // FLEET COMMAND"
            textSize = 14f
            setTypeface(Typeface.MONOSPACE, Typeface.BOLD)
            setTextColor(Color.parseColor("#F8FAFC"))
        }
        cloudBadge = TextView(this).apply {
            text = "${PilotCore.getCloud(this@MainActivity)} • ${PilotCore.APP_VERSION}"
            textSize = 10.5f
            setTypeface(Typeface.MONOSPACE)
            setTextColor(Color.parseColor("#38BDF8"))
        }
        titleCol.addView(title)
        titleCol.addView(cloudBadge)

        statusPill = TextView(this).apply {
            textSize = 11f
            setTypeface(Typeface.MONOSPACE, Typeface.BOLD)
            setPadding(dp(10), dp(5), dp(10), dp(5))
        }
        header.addView(titleCol)
        header.addView(statusPill)
        root.addView(header)

        val dock = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(8); bottomMargin = dp(8) }
        }
        tabFleetBtn = makeTabBtn("⚡ FLEET") { switchTab(0) }
        tabConfigBtn = makeTabBtn("⚙️ CONFIG") { switchTab(1) }
        tabConsoleBtn = makeTabBtn("📟 CONSOLE") { switchTab(2) }
        dock.addView(tabFleetBtn)
        dock.addView(tabConfigBtn)
        dock.addView(tabConsoleBtn)
        root.addView(dock)

        val frame = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
        }

        fleetPane = ScrollView(this).apply { addView(buildFleetTab()) }
        configPane = ScrollView(this).apply { addView(buildConfigTab()) }
        consolePane = ScrollView(this).apply { addView(buildConsoleTab()) }

        frame.addView(fleetPane)
        frame.addView(configPane)
        frame.addView(consolePane)
        root.addView(frame)

        setContentView(root)
        switchTab(currentTab)
        syncPowerButton()
    }

    private fun switchTab(idx: Int) {
        currentTab = idx
        fleetPane.visibility = if (idx == 0) View.VISIBLE else View.GONE
        configPane.visibility = if (idx == 1) View.VISIBLE else View.GONE
        consolePane.visibility = if (idx == 2) View.VISIBLE else View.GONE

        fun styleTab(btn: Button, active: Boolean) {
            btn.background = if (active) cardBg("#0284C7", "#38BDF8") else cardBg("#0F172A", "#1E293B")
            btn.setTextColor(if (active) Color.WHITE else Color.parseColor("#94A3B8"))
        }
        styleTab(tabFleetBtn, idx == 0)
        styleTab(tabConfigBtn, idx == 1)
        styleTab(tabConsoleBtn, idx == 2)
    }

    private fun buildFleetTab(): LinearLayout {
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        val reactorCard = makeCard("REACTOR POWER & SYSTEM TELEMETRY")
        val powerRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }

        powerBtn = Button(this).apply {
            textSize = 11.5f
            setTypeface(Typeface.MONOSPACE, Typeface.BOLD)
            setTextColor(Color.WHITE)
            layoutParams = LinearLayout.LayoutParams(0, dp(44), 1f).apply { rightMargin = dp(6) }
            setOnClickListener {
                if (PilotCore.isRunning) {
                    PilotCore.setShouldAutoStart(this@MainActivity, false)
                    autoStartBotSwitch.isChecked = false
                    PilotCore.stopService(this@MainActivity)
                    toast("Discord bot stopped")
                } else {
                    saveFieldsSilent()
                    if (!Settings.canDrawOverlays(this@MainActivity)) {
                        runCatching {
                            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
                        }
                    }
                    Thread { PilotCore.autoGrantRootAndPerms(this@MainActivity) }.start()
                    PilotCore.startService(this@MainActivity)
                    toast("Connecting UGPhone to Discord bot...")
                }
                syncPowerButton()
            }
        }

        rejoinPowerBtn = Button(this).apply {
            textSize = 11.5f
            setTypeface(Typeface.MONOSPACE, Typeface.BOLD)
            setTextColor(Color.WHITE)
            layoutParams = LinearLayout.LayoutParams(0, dp(44), 1f)
            setOnClickListener {
                val curCfg = Rejoiner.loadConfig(this@MainActivity)
                if (Rejoiner.isRunning() || curCfg.enabled) {
                    Rejoiner.saveConfig(this@MainActivity, curCfg.copy(enabled = false))
                    autoRejoinSwitch.isChecked = false
                    Rejoiner.stop()
                    toast("Auto-Rejoiner stopped")
                } else {
                    saveFieldsSilent()
                    Rejoiner.saveConfig(this@MainActivity, Rejoiner.loadConfig(this@MainActivity).copy(enabled = true))
                    autoRejoinSwitch.isChecked = true
                    if (!PilotCore.isRunning) {
                        PilotCore.startService(this@MainActivity)
                    } else {
                        Rejoiner.start(this@MainActivity)
                    }
                    toast("Auto-Rejoiner started!")
                }
                syncPowerButton()
            }
        }

        powerRow.addView(powerBtn)
        powerRow.addView(rejoinPowerBtn)
        reactorCard.addView(powerRow)

        arcGauge = TelemetryArcView(this).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(105)).apply {
                topMargin = dp(8)
            }
        }
        reactorCard.addView(arcGauge)

        val row1 = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(6)
            }
        }
        row1.addView(makeSmallBtn("🔁 REJOIN ACTIVE", "#1D4ED8") {
            saveFieldsSilent()
            Thread { Rejoiner.killAllAndStaggeredRejoin(this, "HUD button") }.start()
            toast("Rejoining logged-in clones only")
        })
        row1.addView(makeSmallBtn("🪟 ARRANGE 2×2", "#0369A1") {
            Thread {
                val n = PilotCore.arrange2x2(this)
                uiHandler.post { toast("Arranged $n logged-in clone(s)") }
            }.start()
        })
        row1.addView(makeSmallBtn("🧹 CLEAN .BAK", "#334155") {
            Thread {
                val n = KeyAutomator.cleanBakFiles()
                uiHandler.post { toast("Removed $n .bak file(s)") }
            }.start()
        })
        reactorCard.addView(row1)

        val row2 = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(6)
            }
        }
        row2.addView(makeSmallBtn("🔑 SET NOUSIGI", "#6D28D9") { showNousigiKeyDialog() })
        row2.addView(makeSmallBtn("⏳ DELTA KEY", "#0F766E") {
            saveFieldsSilent()
            KeyAutomator.startDeltaRenewal(this)
            toast("Testing Baseplate (${KeyAutomator.BASEPLATE_PLACE_ID})...")
        })
        row2.addView(makeSmallBtn("📋 PASTE KEY", "#B45309") {
            saveFieldsSilent()
            Thread {
                val clip = KeyAutomator.readClipboard(this)
                val (_, msg) = KeyAutomator.applyManualKey(this, clip)
                uiHandler.post { toast(msg.replace("`", "").replace("*", "")) }
            }.start()
        })
        reactorCard.addView(row2)
        col.addView(reactorCard)

        val keyCard = makeCard("KEY WATCHER & EXECUTOR STATUS")
        keyStatusText = TextView(this).apply {
            textSize = 11f
            setTypeface(Typeface.MONOSPACE, Typeface.BOLD)
            setTextColor(Color.parseColor("#38BDF8"))
            text = "IDLE"
        }
        keyCard.addView(keyStatusText)
        col.addView(keyCard)

        val matrixCard = makeCard("ROBLOX CLONE SLOT MATRIX")
        slotMatrixContainer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        matrixCard.addView(slotMatrixContainer)
        col.addView(matrixCard)

        return col
    }

    private fun buildConfigTab(): LinearLayout {
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        val idCard = makeCard("ALL-IN-ONE SYNC CODE & BOT IDENTITY")
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
                    val (ok, _) = PilotCore.applyCode(this, code, startSvc = false)
                    uiHandler.post {
                        if (ok) {
                            loadFieldsFromPrefs()
                            syncPowerButton()
                            toast("Config applied! Tap RUN BOT when ready.")
                        } else {
                            toast("Invalid sync code")
                        }
                    }
                }.start()
            }
        })
        codeRow.addView(makeSmallBtn("COPY FULL CONFIG", "#334155") {
            saveFieldsSilent()
            val code = PilotCore.makeCode(this, autoRoot = true)
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("PilotSync", code))
            toast("Full config sync code copied!")
        })

        tokenInput = makeInput("Discord Bot Token", password = true)
        allowedIdsInput = makeInput("Allowed User/Role IDs (comma-separated)")
        cloudInput = makeInput("Cloud Phone Name (e.g. Cloud Phone 30)")
        updateUrlInput = makeInput("Google Drive APK Update URL (optional)")
        autoStartBotSwitch = makeSwitch("Auto-Start Discord Bot (On App Open & Device Reboot)")

        idCard.addView(syncCodeInput)
        idCard.addView(codeRow)
        idCard.addView(tokenInput)
        idCard.addView(allowedIdsInput)
        idCard.addView(cloudInput)
        idCard.addView(updateUrlInput)
        idCard.addView(autoStartBotSwitch)
        col.addView(idCard)

        // 5-Key Host Mode Card
        val hostCard = makeCard("👑 5-KEY HOST MODE (1 HOST → 5 UGPHONES)")
        keyHostSwitch = makeSwitch("Turn On as Key Host (Manages 5 Nousigi Keys)")
        keyHostSwitch.setOnCheckedChangeListener { _, isChecked ->
            PilotCore.setKeyHostFlag(this, isChecked)
            hostActionsContainer.visibility = if (isChecked) View.VISIBLE else View.GONE
            refreshHostPoolSummary()
        }
        hostCard.addView(keyHostSwitch)

        hostActionsContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = if (PilotCore.isKeyHost(this@MainActivity)) View.VISIBLE else View.GONE
        }

        hostActionsContainer.addView(TextView(this).apply {
            text = "1) Login to Discord & Linkvertise in Chrome first, then tap Start Get Nousigi Key:"
            textSize = 11f
            setTextColor(Color.parseColor("#94A3B8"))
            setPadding(0, dp(4), 0, dp(6))
        })

        val loginRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        loginRow.addView(makeSmallBtn("💬 1. LOGIN DISCORD", "#4F46E5") {
            KeyAutomator.openChromeUrl("https://discord.com/login")
            toast("Opened Discord login in Chrome")
        })
        loginRow.addView(makeSmallBtn("🔗 2. LOGIN LINKVERTISE", "#D97706") {
            KeyAutomator.openChromeUrl("https://publisher.linkvertise.com/login")
            toast("Opened Linkvertise login in Chrome")
        })
        hostActionsContainer.addView(loginRow)

        nousigiGetUrlInput = makeInput("Nousigi Key Portal / Discord Link (default: getkey.nousigi.com)")

        val genRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(6)
            }
        }
        genRow.addView(makeSmallBtn("🔑 3. START GET NOUSIGI KEY", "#15803D") {
            saveFieldsSilent()
            val portalUrl = nousigiGetUrlInput.text.toString().trim().ifBlank { "https://getkey.nousigi.com/" }
            KeyAutomator.startNousigiKeyGeneration(this, portalUrl)
            toast("Starting Nousigi Key Generator (5-Key Host Pool)...")
        })
        genRow.addView(makeSmallBtn("📡 SEND TO 4 PHONES", "#0284C7") {
            val ch = PilotCore.getSsChannel(this)
            if (ch.isEmpty()) {
                toast("Run !sschannel in Discord first so Host knows the channel")
            } else {
                Thread {
                    PilotCore.triggerDistributeFromHud(this)
                    uiHandler.post { toast("Triggered !distributekeys in Discord!") }
                }.start()
            }
        })

        hostPoolSummaryTv = TextView(this).apply {
            textSize = 10.5f
            setTypeface(Typeface.MONOSPACE)
            setTextColor(Color.parseColor("#CBD5E1"))
            setPadding(0, dp(8), 0, 0)
        }

        hostActionsContainer.addView(nousigiGetUrlInput)
        hostActionsContainer.addView(genRow)
        hostActionsContainer.addView(hostPoolSummaryTv)
        hostCard.addView(hostActionsContainer)
        col.addView(hostCard)

        val rjCard = makeCard("REJOINER & PERFORMANCE")
        autoRejoinSwitch = makeSwitch("Enable Auto-Rejoin Watchdog (Restart Frozen/Offline Clones)")
        rjCard.addView(autoRejoinSwitch)
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
        rjCard.addView(placeIdInput)
        rjCard.addView(vipInput)
        rjCard.addView(fpsSwitch)
        rjCard.addView(render3dSwitch)
        col.addView(rjCard)

        val keyCard = makeCard("KEY PATHS & DELTA PLATORELAY")
        keyPathInput = makeInput("Nousigi Key File Path (auto-detected if blank)")
        deltaSwitch = makeSwitch("Test Baseplate & Open Chrome on Missing Delta Key")
        deltaUrlInput = makeInput("Saved PlatoRelay URL (auto-caught on Receive Key)")
        keyCard.addView(keyPathInput)
        keyCard.addView(deltaSwitch)
        keyCard.addView(deltaUrlInput)

        val saveBtn = Button(this).apply {
            text = "SAVE ALL SETTINGS & SYNC"
            textSize = 12f
            setTypeface(Typeface.MONOSPACE, Typeface.BOLD)
            setTextColor(Color.WHITE)
            background = cardBg("#0284C7", "#38BDF8")
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(10) }
            setOnClickListener {
                saveFieldsSilent()
                cloudBadge.text = "${PilotCore.getCloud(this@MainActivity)} • ${PilotCore.APP_VERSION}"
                toast("All settings saved & synced!")
            }
        }
        keyCard.addView(saveBtn)
        col.addView(keyCard)

        return col
    }

    private fun buildConsoleTab(): LinearLayout {
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val logCard = makeCard("LIVE SYSTEM TELEMETRY STREAM")
        telemetryText = TextView(this).apply {
            textSize = 10.5f
            setTypeface(Typeface.MONOSPACE)
            setTextColor(Color.parseColor("#CBD5E1"))
            text = "Waiting for events..."
        }
        logCard.addView(telemetryText)
        col.addView(logCard)
        return col
    }

    private fun showNousigiKeyDialog() {
        val rep = KeyAutomator.inspectNousigiKey(this)
        val input = EditText(this).apply {
            hint = "Enter new Nousigi / Luarmor key"
            if (rep.keyFound != "None") setText(rep.keyFound)
        }
        AlertDialog.Builder(this)
            .setTitle("Update Nousigi Key (Autoexecute + Hub)")
            .setMessage("Current: ${rep.keyFound}\nUpdates Autoexecute & Nousigi Hub and removes .bak files.")
            .setView(input)
            .setPositiveButton("Update") { _, _ ->
                val newKey = input.text.toString().trim()
                Thread {
                    val (ok, msg) = KeyAutomator.updateNousigiKeyEverywhere(this, newKey)
                    uiHandler.post { toast(if (ok) "Nousigi key updated & .bak cleaned!" else msg) }
                }.start()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun syncPowerButton() {
        val botOnline = PilotCore.isRunning
        if (botOnline) {
            powerBtn.text = "■ STOP BOT"
            powerBtn.background = cardBg("#B91C1C", "#F87171")
            statusPill.text = "● BOT ONLINE"
            statusPill.setTextColor(Color.parseColor("#4ADE80"))
            statusPill.background = cardBg("#052E16", "#166534")
        } else {
            powerBtn.text = "▶ RUN BOT"
            powerBtn.background = cardBg("#15803D", "#4ADE80")
            statusPill.text = "○ OFFLINE"
            statusPill.setTextColor(Color.parseColor("#94A3B8"))
            statusPill.background = cardBg("#0F172A", "#334155")
        }

        val rjActive = Rejoiner.isRunning() || Rejoiner.loadConfig(this).enabled
        if (rjActive && botOnline) {
            rejoinPowerBtn.text = "■ STOP REJOINER"
            rejoinPowerBtn.background = cardBg("#B45309", "#FBBF24")
        } else if (rjActive) {
            rejoinPowerBtn.text = "⏳ REJOIN ARMED"
            rejoinPowerBtn.background = cardBg("#0369A1", "#38BDF8")
        } else {
            rejoinPowerBtn.text = "▶ RUN AUTO-REJOINER"
            rejoinPowerBtn.background = cardBg("#1D4ED8", "#60A5FA")
        }
    }

    private fun refreshHostPoolSummary() {
        if (!::hostPoolSummaryTv.isInitialized) return
        val pool = PilotCore.getKeyPool(this)
        if (pool.isEmpty()) {
            hostPoolSummaryTv.text = "Host Pool: 0/5 keys stored."
        } else {
            val lines = pool.joinToString("\n") { e ->
                val owner = e.assignedCloud.ifBlank { "Free" }
                val masked = if (e.key.length > 10) "${e.key.take(5)}...${e.key.takeLast(4)}" else e.key
                "[${e.slot}/5] $masked -> $owner"
            }
            hostPoolSummaryTv.text = "Host Pool (${pool.size}/5):\n$lines"
        }
    }

    private fun refreshTelemetry() {
        val active = KeyAutomator.isRunning()
        keyStatusText.text = KeyAutomator.getLiveStatusText()
        keyStatusText.setTextColor(Color.parseColor(if (active) "#FBBF24" else "#38BDF8"))

        val now = System.currentTimeMillis()
        val slots = Rejoiner.slots.values.sortedBy { it.pkg }
        val activeCount = slots.count { it.status == "ACTIVE" }

        val memParts = SuShell.exec("free -m | awk '/Mem:/ {print \$2\" \"\$3}'").trim().split(Regex("\\s+"))
        if (memParts.size >= 2) {
            val tot = memParts[0].toFloatOrNull() ?: 1f
            val used = memParts[1].toFloatOrNull() ?: 0f
            arcGauge.ramPct = if (tot > 0f) (used * 100f / tot) else 0f
        }
        arcGauge.activeSlots = activeCount
        arcGauge.totalSlots = slots.size.coerceAtLeast(1)
        arcGauge.invalidate()

        slotMatrixContainer.removeAllViews()
        if (slots.isEmpty()) {
            slotMatrixContainer.addView(TextView(this).apply {
                text = "No com.roblox.clien* clones detected."
                textSize = 11f
                setTextColor(Color.parseColor("#64748B"))
            })
        } else {
            for (s in slots) {
                val card = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    background = cardBg("#0B1220", "#1E293B")
                    setPadding(dp(10), dp(7), dp(10), dp(7))
                    layoutParams = LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
                    ).apply { bottomMargin = dp(6) }
                }
                val dotColor = when (s.status) {
                    "ACTIVE" -> "#22C55E"
                    "PAUSED", "COOLDOWN", "NO_DELTA_KEY" -> "#F59E0B"
                    "LOGGED_OUT", "REVOKED", "FROZEN" -> "#EF4444"
                    else -> "#475569"
                }
                val dot = TextView(this).apply {
                    text = "● "
                    textSize = 13f
                    setTextColor(Color.parseColor(dotColor))
                }
                val infoCol = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                }
                val nameTv = TextView(this).apply {
                    val u = s.username.ifBlank { "(Empty Slot)" }
                    text = "$u  [${s.pkg.removePrefix("com.roblox.")}]"
                    textSize = 11.5f
                    setTypeface(Typeface.MONOSPACE, Typeface.BOLD)
                    setTextColor(Color.parseColor("#F1F5F9"))
                }
                val subTv = TextView(this).apply {
                    val pingAgo = if (s.lastPingMs > 0) "${(now - s.lastPingMs) / 1000}s ago" else "no ping"
                    text = "${s.status} • ${s.gameState} • ${s.fps} FPS • ${s.pingMs}ms ($pingAgo)"
                    textSize = 10f
                    setTypeface(Typeface.MONOSPACE)
                    setTextColor(Color.parseColor("#94A3B8"))
                }
                infoCol.addView(nameTv)
                infoCol.addView(subTv)
                card.addView(dot)
                card.addView(infoCol)
                slotMatrixContainer.addView(card)
            }
        }

        telemetryText.text = PilotCore.getLogs().takeLast(35).joinToString("\n").ifBlank { "No logs yet." }
    }

    private fun loadFieldsFromPrefs() {
        val cfg = Rejoiner.loadConfig(this)
        tokenInput.setText(PilotCore.getToken(this))
        allowedIdsInput.setText(PilotCore.getAllowedIdsRaw(this))
        cloudInput.setText(PilotCore.getCloud(this))
        updateUrlInput.setText(PilotCore.getUpdateUrl(this))
        autoStartBotSwitch.isChecked = PilotCore.shouldAutoStart(this)

        val isHost = PilotCore.isKeyHost(this)
        keyHostSwitch.isChecked = isHost
        hostActionsContainer.visibility = if (isHost) View.VISIBLE else View.GONE
        nousigiGetUrlInput.setText(PilotCore.prefs(this).getString("nousigi_get_url", "https://getkey.nousigi.com/"))
        refreshHostPoolSummary()

        autoRejoinSwitch.isChecked = cfg.enabled
        placeIdInput.setText(cfg.targetPlaceId)
        vipInput.setText(cfg.privateServerLinks)
        fpsSwitch.isChecked = cfg.fpsOptimization
        render3dSwitch.isChecked = cfg.render3dOff
        keyPathInput.setText(cfg.nousigiKeyPath)
        deltaSwitch.isChecked = cfg.autoGetDeltaKey
        deltaUrlInput.setText(cfg.deltaPlatoUrl)

        val presetIdx = Rejoiner.FAMOUS_GAMES.indexOfFirst { it.placeId == cfg.targetPlaceId }
            .takeIf { it >= 0 } ?: (Rejoiner.FAMOUS_GAMES.size - 1)
        gameSpinner.setSelection(presetIdx)
    }

    private fun saveFieldsSilent() {
        val cloud = cloudInput.text.toString().trim().ifBlank { PilotCore.getCloud(this) }
        val tok = tokenInput.text.toString().trim()
        val ids = allowedIdsInput.text.toString().trim()
        val upd = updateUrlInput.text.toString().trim()
        val wantAutoBot = autoStartBotSwitch.isChecked

        PilotCore.setShouldAutoStart(this, wantAutoBot)
        PilotCore.setKeyHostFlag(this, keyHostSwitch.isChecked)
        PilotCore.prefs(this).edit()
            .putString("nousigi_get_url", nousigiGetUrlInput.text.toString().trim())
            .apply()

        val prev = Rejoiner.loadConfig(this)
        Rejoiner.saveConfig(
            this,
            prev.copy(
                enabled = autoRejoinSwitch.isChecked,
                autoStartOnBoot = wantAutoBot,
                targetPlaceId = placeIdInput.text.toString().trim(),
                privateServerLinks = vipInput.text.toString().trim(),
                fpsOptimization = fpsSwitch.isChecked,
                render3dOff = render3dSwitch.isChecked,
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
        background = cardBg("#0D1526", "#1E293B")
        setPadding(dp(12), dp(10), dp(12), dp(12))
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = dp(10) }
        addView(TextView(this@MainActivity).apply {
            text = header
            textSize = 10.5f
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
        setHintTextColor(Color.parseColor("#475569"))
        setTextColor(Color.parseColor("#F8FAFC"))
        textSize = 12f
        background = cardBg("#090E1A", "#334155")
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

    private fun makeTabBtn(label: String, onClick: () -> Unit) = Button(this).apply {
        text = label
        textSize = 11f
        setTypeface(Typeface.MONOSPACE, Typeface.BOLD)
        layoutParams = LinearLayout.LayoutParams(0, dp(36), 1f).apply { rightMargin = dp(4) }
        setOnClickListener { onClick() }
    }

    private fun makeSmallBtn(label: String, hex: String, onClick: () -> Unit) = Button(this).apply {
        text = label
        textSize = 9.5f
        setTypeface(Typeface.MONOSPACE, Typeface.BOLD)
        setTextColor(Color.WHITE)
        background = cardBg(hex, hex)
        layoutParams = LinearLayout.LayoutParams(0, dp(36), 1f).apply { rightMargin = dp(4) }
        setOnClickListener { onClick() }
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
}