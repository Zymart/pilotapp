package com.pilot.bot

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.widget.*

class MainActivity : Activity() {
    private lateinit var logView: TextView
    private lateinit var logScroll: ScrollView
    private lateinit var statusPill: TextView
    private lateinit var metaText: TextView
    private lateinit var etCloud: EditText
    private lateinit var etUpdateUrl: EditText

    // Navigation Tabs & Containers
    private lateinit var tabConfigBtn: Button
    private lateinit var tabRejoinBtn: Button
    private lateinit var configSection: LinearLayout
    private lateinit var rejoinSection: LinearLayout

    // Rejoiner UI Controls
    private lateinit var arStatusPill: TextView
    private lateinit var swRejoinEnabled: Switch
    private lateinit var etRejoinTarget: EditText
    private lateinit var tvSlotWatchdogList: TextView

    private var activeTab = 0 // 0 = Config Settings, 1 = Rejoiner Settings

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 33) {
            requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 101)
        }

        Thread { PilotCore.installAutoExec(this) }.start()

        val root = ScrollView(this).apply {
            setBackgroundColor(Color.parseColor("#0B0E14"))
            isFillViewport = true
        }
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 36, 32, 36)
        }

        // Header Card
        val headerCard = makeCard("#131822", "#1E293B", 24f)
        val headerRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val titleCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
        }
        val titleLine = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val title = TextView(this).apply {
            text = "PILOT ENGINE"
            textSize = 20f
            letterSpacing = 0.08f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.parseColor("#F8FAFC"))
        }
        val versionBadge = TextView(this).apply {
            text = PilotCore.APP_VERSION
            textSize = 11f
            typeface = Typeface.MONOSPACE
            setTextColor(Color.parseColor("#38BDF8"))
            setPadding(16, 4, 16, 4)
            background = GradientDrawable().apply {
                cornerRadius = 12f
                setColor(Color.parseColor("#0C2438"))
                setStroke(2, Color.parseColor("#0284C7"))
            }
            layoutParams = LinearLayout.LayoutParams(-2, -2).apply { leftMargin = 14 }
        }
        titleLine.addView(title)
        titleLine.addView(versionBadge)

        metaText = TextView(this).apply {
            text = "${PilotCore.APP_VERSION} • GVIP LITE • RAM ~${getAppRamMb()} MB"
            textSize = 11f
            setTextColor(Color.parseColor("#64748B"))
            setPadding(0, 6, 0, 0)
        }
        titleCol.addView(titleLine)
        titleCol.addView(metaText)

        statusPill = TextView(this).apply {
            textSize = 11f
            typeface = Typeface.DEFAULT_BOLD
            setPadding(24, 10, 24, 10)
        }
        headerRow.addView(titleCol)
        headerRow.addView(statusPill)
        headerCard.addView(headerRow)

        // Navigation Tab Switcher (Config Settings vs Rejoiner Settings)
        val tabBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(8, 8, 8, 8)
            background = GradientDrawable().apply {
                cornerRadius = 20f
                setColor(Color.parseColor("#131822"))
                setStroke(2, Color.parseColor("#1E293B"))
            }
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = 18 }
        }
        tabConfigBtn = Button(this).apply {
            text = "⚙ CONFIG SETTINGS"
            textSize = 11.5f
            letterSpacing = 0.05f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setPadding(12, 18, 12, 18)
            setOnClickListener { switchTab(0) }
        }
        tabRejoinBtn = Button(this).apply {
            text = "🔁 REJOINER SETTINGS"
            textSize = 11.5f
            letterSpacing = 0.05f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setPadding(12, 18, 12, 18)
            setOnClickListener { switchTab(1) }
        }
        tabBar.addView(tabConfigBtn, LinearLayout.LayoutParams(0, -2, 1f).apply { rightMargin = 6 })
        tabBar.addView(tabRejoinBtn, LinearLayout.LayoutParams(0, -2, 1f).apply { leftMargin = 6 })

        // =====================================================================
        // TAB 0: CONFIG SETTINGS SECTION
        // =====================================================================
        configSection = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }

        val cfgCard = makeCard("#131822", "#1E293B", 24f).apply {
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = 18 }
        }
        cfgCard.addView(makeSectionLabel("INSTANCE & DISCORD CONFIG"))

        etCloud = makeInput("Cloud Name (auto-detected)", PilotCore.getCloud(this))
        val etToken = makeInput("Discord Bot Token (@ bypass supported)", PilotCore.getToken(this))
        val etUser = makeInput("Allowed User/Role IDs (comma-separated)", PilotCore.getAllowedIdsRaw(this))
        val etChan = makeInput("Screenshot Channel ID (or use !sschannel)", PilotCore.getSsChannel(this))

        val rowPower = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val btnStart = makePillBtn("START BOT", "#10B981", "#059669") {
            PilotCore.saveConfig(
                this,
                etCloud.text.toString(),
                etToken.text.toString(),
                etUser.text.toString(),
                etChan.text.toString(),
                etUpdateUrl.text.toString()
            )
            etToken.setText(PilotCore.getToken(this))
            etUser.setText(PilotCore.getAllowedIdsRaw(this))

            Thread { PilotCore.autoGrantRootAndPerms(this) }.start()

            val intent = Intent(this, PilotService::class.java)
            if (Build.VERSION.SDK_INT >= 26) startForegroundService(intent) else startService(intent)
            refreshStatus()

            val homeIntent = Intent(Intent.ACTION_MAIN).apply {
                addCategory(Intent.CATEGORY_HOME)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            startActivity(homeIntent)
        }
        val btnStop = makePillBtn("STOP", "#EF4444", "#B91C1C") {
            stopService(Intent(this, PilotService::class.java))
            refreshStatus()
        }
        rowPower.addView(btnStart, LinearLayout.LayoutParams(0, -2, 1.4f).apply { rightMargin = 10 })
        rowPower.addView(btnStop, LinearLayout.LayoutParams(0, -2, 0.8f).apply { leftMargin = 10 })

        cfgCard.addView(etCloud)
        cfgCard.addView(etToken)
        cfgCard.addView(etUser)
        cfgCard.addView(etChan)
        cfgCard.addView(rowPower)

        val syncCard = makeCard("#131822", "#1E293B", 24f).apply {
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = 20 }
        }
        syncCard.addView(makeSectionLabel("CLONE SYNC & OTA AUTO-UPDATER (${PilotCore.APP_VERSION})"))
        val etSync = makeInput("Sync Code (XXXXXXX-...)", "")

        val rowSync = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val btnGen = makePillBtn("COPY CODE", "#0284C7", "#0369A1") {
            PilotCore.saveConfig(
                this,
                etCloud.text.toString(),
                etToken.text.toString(),
                etUser.text.toString(),
                etChan.text.toString(),
                etUpdateUrl.text.toString()
            )
            val code = PilotCore.makeCode(this, autoRoot = true)
            etSync.setText(code)
            val cm = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("PilotSync", code))
            Toast.makeText(this, "Sync Code Copied!", Toast.LENGTH_SHORT).show()
        }
        val btnRestore = makePillBtn("APPLY CODE", "#7C3AED", "#6D28D9") {
            val rawCode = etSync.text.toString()
            Thread {
                val (ok, autoStarted) = PilotCore.applyCode(this, rawCode)
                runOnUiThread {
                    if (ok) {
                        etToken.setText(PilotCore.getToken(this))
                        etUser.setText(PilotCore.getAllowedIdsRaw(this))
                        etChan.setText(PilotCore.getSsChannel(this))
                        etUpdateUrl.setText(PilotCore.getUpdateUrl(this))
                        refreshStatus()
                        Toast.makeText(
                            this,
                            if (autoStarted) "Config Applied, Root Granted & Bot Started!" else "Config Applied!",
                            Toast.LENGTH_SHORT
                        ).show()
                    } else {
                        Toast.makeText(this, "Invalid Sync Code", Toast.LENGTH_SHORT).show()
                    }
                }
            }.start()
        }
        rowSync.addView(btnGen, LinearLayout.LayoutParams(0, -2, 1f).apply { rightMargin = 10 })
        rowSync.addView(btnRestore, LinearLayout.LayoutParams(0, -2, 1f).apply { leftMargin = 10 })

        etUpdateUrl = makeInput("Google Drive APK Link (blank = GitHub Auto)", PilotCore.getUpdateUrl(this)).apply {
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = 14; bottomMargin = 14 }
        }

        val btnUpdate = makePillBtn("CHECK & INSTALL UPDATE NOW", "#D97706", "#B45309") {
            PilotCore.saveUpdateUrl(this, etUpdateUrl.text.toString())
            Toast.makeText(this, "Checking for update (${PilotCore.APP_VERSION})...", Toast.LENGTH_SHORT).show()
            Thread { AppUpdater.checkAndInstall(this, force = true) }.start()
        }

        val rowPerms = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 14, 0, 0)
        }
        val btnRoot = makePillBtn("GRANT ROOT", "#334155", "#1E293B") {
            Thread {
                val rootOk = PilotCore.autoGrantRootAndPerms(this)
                runOnUiThread {
                    Toast.makeText(this, if (rootOk) "Root & Permissions Granted!" else "Root Denied", Toast.LENGTH_SHORT).show()
                }
            }.start()
        }
        val btnBatt = makePillBtn("NO BATTERY LIMIT", "#334155", "#1E293B") {
            requestBatteryExemption()
        }
        rowPerms.addView(btnRoot, LinearLayout.LayoutParams(0, -2, 1f).apply { rightMargin = 10 })
        rowPerms.addView(btnBatt, LinearLayout.LayoutParams(0, -2, 1f).apply { leftMargin = 10 })

        syncCard.addView(etSync)
        syncCard.addView(rowSync)
        syncCard.addView(etUpdateUrl)
        syncCard.addView(btnUpdate)
        syncCard.addView(rowPerms)

        configSection.addView(cfgCard)
        configSection.addView(syncCard)

        // =====================================================================
        // TAB 1: REJOINER SETTINGS SECTION
        // =====================================================================
        rejoinSection = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
        }

        val arControlCard = makeCard("#131822", "#1E293B", 24f).apply {
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = 18 }
        }
        val arHeaderRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, 14)
        }
        val arTitle = makeSectionLabel("ALL-IN-ONE AUTO-REJOINER").apply {
            setPadding(0, 0, 0, 0)
            layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
        }
        arStatusPill = TextView(this).apply {
            textSize = 10.5f
            typeface = Typeface.DEFAULT_BOLD
            setPadding(18, 6, 18, 6)
        }
        arHeaderRow.addView(arTitle)
        arHeaderRow.addView(arStatusPill)

        val toggleRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(22, 18, 22, 18)
            background = GradientDrawable().apply {
                cornerRadius = 16f
                setColor(Color.parseColor("#0B0E14"))
                setStroke(2, Color.parseColor("#1E293B"))
            }
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = 14 }
        }
        val toggleLabelCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
        }
        val toggleTitle = TextView(this).apply {
            text = "Enable Auto-Rejoin Watchdog"
            textSize = 13.5f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.parseColor("#F1F5F9"))
        }
        val toggleSub = TextView(this).apply {
            text = "Monitors Delta heartbeat, freezes, Presence API & teleports"
            textSize = 11f
            setTextColor(Color.parseColor("#64748B"))
            setPadding(0, 4, 0, 0)
        }
        toggleLabelCol.addView(toggleTitle)
        toggleLabelCol.addView(toggleSub)

        swRejoinEnabled = Switch(this).apply {
            isChecked = Rejoiner.isEnabled(this@MainActivity)
            setOnCheckedChangeListener { _, checked ->
                Rejoiner.setConfig(this@MainActivity, checked, etRejoinTarget.text.toString())
                refreshRejoinUi()
            }
        }
        toggleRow.addView(toggleLabelCol)
        toggleRow.addView(swRejoinEnabled)

        etRejoinTarget = makeInput(
            "Target Place ID or Private Server Link (blank = Auto Last Place)",
            Rejoiner.getTarget(this)
        )

        val arBtnRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val btnSaveAr = makePillBtn("SAVE REJOINER", "#10B981", "#059669") {
            Rejoiner.setConfig(this, swRejoinEnabled.isChecked, etRejoinTarget.text.toString())
            refreshRejoinUi()
            Toast.makeText(this, "Rejoiner Settings Saved!", Toast.LENGTH_SHORT).show()
        }
        val btnRejoinAll = makePillBtn("REJOIN ALL NOW", "#0284C7", "#0369A1") {
            Toast.makeText(this, "Force-rejoining all active slots...", Toast.LENGTH_SHORT).show()
            Thread {
                val list = PilotCore.cachedScan.ifEmpty { PilotCore.scanRbx(this) }
                val active = list.filter { it.status == "ACTIVE" && it.user != "Empty" }
                for (acc in active) {
                    Rejoiner.relaunch(this, acc.pkg)
                }
                runOnUiThread {
                    refreshRejoinUi()
                    Toast.makeText(this, "Relaunched ${active.size} active slot(s)", Toast.LENGTH_SHORT).show()
                }
            }.start()
        }
        arBtnRow.addView(btnSaveAr, LinearLayout.LayoutParams(0, -2, 1f).apply { rightMargin = 10 })
        arBtnRow.addView(btnRejoinAll, LinearLayout.LayoutParams(0, -2, 1f).apply { leftMargin = 10 })

        val guardSummary = TextView(this).apply {
            text = "• Delta Lua Heartbeat + 20s Render-Freeze Detector\n" +
                   "• Roblox Presence API (verifies In-Game status)\n" +
                   "• Place-to-Place Teleport Shield (45s pause on OnTeleport)"
            textSize = 11f
            setTextColor(Color.parseColor("#94A3B8"))
            setPadding(0, 16, 0, 0)
        }

        arControlCard.addView(arHeaderRow)
        arControlCard.addView(toggleRow)
        arControlCard.addView(etRejoinTarget)
        arControlCard.addView(arBtnRow)
        arControlCard.addView(guardSummary)

        // Per-Slot Watchdog Status Card
        val arSlotsCard = makeCard("#131822", "#1E293B", 24f).apply {
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = 20 }
        }
        val arSlotsHeader = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, 12)
        }
        val arSlotsTitle = makeSectionLabel("INSTANCE WATCHDOG STATUS").apply {
            setPadding(0, 0, 0, 0)
            layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
        }
        val btnRefreshSlots = TextView(this).apply {
            text = "REFRESH"
            textSize = 11f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.parseColor("#38BDF8"))
            setOnClickListener {
                Thread {
                    PilotCore.scanRbx(this@MainActivity)
                    runOnUiThread { refreshRejoinUi() }
                }.start()
            }
        }
        arSlotsHeader.addView(arSlotsTitle)
        arSlotsHeader.addView(btnRefreshSlots)

        tvSlotWatchdogList = TextView(this).apply {
            textSize = 11.5f
            typeface = Typeface.MONOSPACE
            setTextColor(Color.parseColor("#CBD5E1"))
        }

        arSlotsCard.addView(arSlotsHeader)
        arSlotsCard.addView(tvSlotWatchdogList)

        rejoinSection.addView(arControlCard)
        rejoinSection.addView(arSlotsCard)

        // =====================================================================
        // SHARED LIVE TELEMETRY TERMINAL CARD
        // =====================================================================
        val logCard = makeCard("#0F131A", "#1E293B", 24f).apply {
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = 20 }
        }
        val logHeader = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, 12)
        }
        val logTitle = makeSectionLabel("LIVE SYSTEM TELEMETRY").apply {
            setPadding(0, 0, 0, 0)
            layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
        }
        val btnClear = TextView(this).apply {
            text = "CLEAR"
            textSize = 11f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.parseColor("#38BDF8"))
            setOnClickListener {
                PilotCore.logs = "Cleared (${PilotCore.APP_VERSION}).\n"
                logView.text = PilotCore.logs
            }
        }
        logHeader.addView(logTitle)
        logHeader.addView(btnClear)

        logScroll = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(-1, 320)
        }
        logView = TextView(this).apply {
            text = PilotCore.logs
            textSize = 11.5f
            typeface = Typeface.MONOSPACE
            setTextColor(Color.parseColor("#94A3B8"))
        }
        logScroll.addView(logView)
        logCard.addView(logHeader)
        logCard.addView(logScroll)

        container.addView(headerCard)
        container.addView(tabBar)
        container.addView(configSection)
        container.addView(rejoinSection)
        container.addView(logCard)
        root.addView(container)
        setContentView(root)

        switchTab(0)
        refreshStatus()
        refreshRejoinUi()

        PilotCore.onLog = {
            runOnUiThread {
                logView.text = PilotCore.logs
                etCloud.setText(PilotCore.getCloud(this))
                etUpdateUrl.setText(PilotCore.getUpdateUrl(this))
                refreshStatus()
                refreshRejoinUi()
                logScroll.post { logScroll.fullScroll(View.FOCUS_DOWN) }
            }
        }
    }

    private fun switchTab(tabIndex: Int) {
        activeTab = tabIndex
        val isCfg = tabIndex == 0
        configSection.visibility = if (isCfg) View.VISIBLE else View.GONE
        rejoinSection.visibility = if (isCfg) View.GONE else View.VISIBLE

        tabConfigBtn.setTextColor(Color.parseColor(if (isCfg) "#FFFFFF" else "#64748B"))
        tabConfigBtn.background = GradientDrawable().apply {
            cornerRadius = 14f
            setColor(Color.parseColor(if (isCfg) "#0284C7" else "#0B0E14"))
        }

        tabRejoinBtn.setTextColor(Color.parseColor(if (!isCfg) "#FFFFFF" else "#64748B"))
        tabRejoinBtn.background = GradientDrawable().apply {
            cornerRadius = 14f
            setColor(Color.parseColor(if (!isCfg) "#059669" else "#0B0E14"))
        }

        if (!isCfg) refreshRejoinUi()
    }

    private fun refreshRejoinUi() {
        val enabled = Rejoiner.isEnabled(this)
        if (swRejoinEnabled.isChecked != enabled) {
            swRejoinEnabled.isChecked = enabled
        }
        arStatusPill.text = if (enabled) "● ACTIVE" else "● OFF"
        arStatusPill.setTextColor(Color.parseColor(if (enabled) "#10B981" else "#F87171"))
        arStatusPill.background = GradientDrawable().apply {
            cornerRadius = 50f
            setColor(Color.parseColor(if (enabled) "#064E3B" else "#450A0A"))
            setStroke(2, Color.parseColor(if (enabled) "#10B981" else "#EF4444"))
        }

        val now = System.currentTimeMillis()
        val list = PilotCore.cachedScan
        if (list.isEmpty()) {
            tvSlotWatchdogList.text = "No Roblox slots scanned yet. Start bot or tap REFRESH."
            return
        }

        val sb = StringBuilder()
        list.forEachIndexed { idx, acc ->
            val st = Rejoiner.states[acc.pkg]
            val place = st?.placeId?.takeIf { it > 0L } ?: Rejoiner.getSavedPlaceId(this, acc.pkg)
            val placeTxt = if (place > 0L) "Place:$place" else "Place:Auto"
            val stateTxt = when {
                acc.status != "ACTIVE" -> acc.status
                st != null && now < st.teleportingUntilMs -> "TELEPORTING (${(st.teleportingUntilMs - now) / 1000}s)"
                st != null && st.lastHbMs > 0L -> "IN-GAME (HB ${(now - st.lastHbMs) / 1000}s ago)"
                else -> "RUNNING (Waiting Lua HB)"
            }
            sb.append("[${idx + 1}] ${acc.user} (${acc.pkg})\n")
            sb.append("    $stateTxt • $placeTxt\n")
        }
        tvSlotWatchdogList.text = sb.toString().trim()
    }

    private fun refreshStatus() {
        val running = PilotService.isRunning
        statusPill.text = if (running) "● ONLINE" else "● STOPPED"
        statusPill.setTextColor(Color.parseColor(if (running) "#10B981" else "#F87171"))
        statusPill.background = GradientDrawable().apply {
            cornerRadius = 50f
            setColor(Color.parseColor(if (running) "#064E3B" else "#450A0A"))
            setStroke(2, Color.parseColor(if (running) "#10B981" else "#EF4444"))
        }
        metaText.text = "${PilotCore.APP_VERSION} • GVIP LITE • RAM ~${getAppRamMb()} MB"
    }

    private fun getAppRamMb(): Long {
        val rt = Runtime.getRuntime()
        return ((rt.totalMemory() - rt.freeMemory()) / (1024 * 1024)).coerceAtLeast(8L)
    }

    private fun requestBatteryExemption() {
        if (Build.VERSION.SDK_INT >= 23) {
            val pm = getSystemService(POWER_SERVICE) as PowerManager
            if (!pm.isIgnoringBatteryOptimizations(packageName)) {
                runCatching {
                    val i = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                        data = Uri.parse("package:$packageName")
                    }
                    startActivity(i)
                }
            } else {
                Toast.makeText(this, "Battery Already Unrestricted", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun makeCard(bgHex: String, strokeHex: String, radius: Float) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(28, 26, 28, 26)
        background = GradientDrawable().apply {
            cornerRadius = radius
            setColor(Color.parseColor(bgHex))
            setStroke(2, Color.parseColor(strokeHex))
        }
    }

    private fun makeSectionLabel(label: String) = TextView(this).apply {
        text = label
        textSize = 11f
        letterSpacing = 0.08f
        typeface = Typeface.DEFAULT_BOLD
        setTextColor(Color.parseColor("#38BDF8"))
        setPadding(0, 0, 0, 14)
    }

    private fun makeInput(hintTxt: String, value: String) = EditText(this).apply {
        hint = hintTxt
        setText(value)
        textSize = 13.5f
        setTextColor(Color.parseColor("#F1F5F9"))
        setHintTextColor(Color.parseColor("#475569"))
        setPadding(26, 22, 26, 22)
        background = GradientDrawable().apply {
            cornerRadius = 16f
            setColor(Color.parseColor("#0B0E14"))
            setStroke(2, Color.parseColor("#1E293B"))
        }
        layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = 14 }
    }

    private fun makePillBtn(label: String, topHex: String, botHex: String, onClick: () -> Unit) = Button(this).apply {
        text = label
        textSize = 12f
        letterSpacing = 0.05f
        setTextColor(Color.WHITE)
        typeface = Typeface.DEFAULT_BOLD
        gravity = Gravity.CENTER
        setPadding(16, 20, 16, 20)
        background = GradientDrawable(
            GradientDrawable.Orientation.TOP_BOTTOM,
            intArrayOf(Color.parseColor(topHex), Color.parseColor(botHex))
        ).apply {
            cornerRadius = 16f
        }
        setOnClickListener { onClick() }
    }
}