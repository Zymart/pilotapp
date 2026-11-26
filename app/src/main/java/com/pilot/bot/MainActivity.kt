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
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.*

class MainActivity : Activity() {
    private lateinit var logView: TextView
    private lateinit var logScroll: ScrollView
    private lateinit var statusPill: TextView
    private lateinit var metaText: TextView
    private lateinit var etCloud: EditText

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 33) {
            requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 101)
        }

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
        val title = TextView(this).apply {
            text = "PILOT ENGINE"
            textSize = 20f
            letterSpacing = 0.08f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.parseColor("#F8FAFC"))
        }
        metaText = TextView(this).apply {
            text = "GVIP LITE • RAM ~${getAppRamMb()} MB • BOOT READY"
            textSize = 11f
            setTextColor(Color.parseColor("#64748B"))
            setPadding(0, 4, 0, 0)
        }
        titleCol.addView(title)
        titleCol.addView(metaText)

        statusPill = TextView(this).apply {
            textSize = 11f
            typeface = Typeface.DEFAULT_BOLD
            setPadding(24, 10, 24, 10)
        }
        headerRow.addView(titleCol)
        headerRow.addView(statusPill)
        headerCard.addView(headerRow)

        // Configuration Card
        val cfgCard = makeCard("#131822", "#1E293B", 24f).apply {
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = 20 }
        }
        cfgCard.addView(makeSectionLabel("INSTANCE & DISCORD CONFIG"))

        etCloud = makeInput("Cloud Name (auto-detected)", PilotCore.getCloud(this))
        val etToken = makeInput("Discord Bot Token", PilotCore.getToken(this))
        val etUser = makeInput("Owner Discord User ID", PilotCore.getUserId(this).let { if (it == 0L) "" else it.toString() }, true)
        val etChan = makeInput("Screenshot Channel ID (or use !sschannel)", PilotCore.getSsChannel(this), true)

        val rowPower = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val btnStart = makePillBtn("START BOT", "#10B981", "#059669") {
            PilotCore.saveConfig(
                this,
                etCloud.text.toString(),
                etToken.text.toString(),
                etUser.text.toString().toLongOrNull() ?: 0L,
                etChan.text.toString()
            )
            val intent = Intent(this, PilotService::class.java)
            if (Build.VERSION.SDK_INT >= 26) startForegroundService(intent) else startService(intent)
            refreshStatus()
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

        // Sync & System Card
        val syncCard = makeCard("#131822", "#1E293B", 24f).apply {
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = 20 }
        }
        syncCard.addView(makeSectionLabel("QUICK CLONE SYNC & PERMISSIONS"))
        val etSync = makeInput("Sync Code (XXXXXXX-...)", "")

        val rowSync = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val btnGen = makePillBtn("COPY CODE", "#0284C7", "#0369A1") {
            PilotCore.saveConfig(
                this,
                etCloud.text.toString(),
                etToken.text.toString(),
                etUser.text.toString().toLongOrNull() ?: 0L,
                etChan.text.toString()
            )
            val code = PilotCore.makeCode(this)
            etSync.setText(code)
            val cm = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("PilotSync", code))
            Toast.makeText(this, "Sync Code Copied!", Toast.LENGTH_SHORT).show()
        }
        val btnRestore = makePillBtn("APPLY CODE", "#7C3AED", "#6D28D9") {
            if (PilotCore.applyCode(this, etSync.text.toString())) {
                etToken.setText(PilotCore.getToken(this))
                etUser.setText(PilotCore.getUserId(this).toString())
                etChan.setText(PilotCore.getSsChannel(this))
                Toast.makeText(this, "Config Applied!", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, "Invalid Sync Code", Toast.LENGTH_SHORT).show()
            }
        }
        rowSync.addView(btnGen, LinearLayout.LayoutParams(0, -2, 1f).apply { rightMargin = 10 })
        rowSync.addView(btnRestore, LinearLayout.LayoutParams(0, -2, 1f).apply { leftMargin = 10 })

        val rowPerms = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 14, 0, 0)
        }
        val btnRoot = makePillBtn("GRANT ROOT", "#334155", "#1E293B") {
            Thread {
                val out = SuShell.exec("id")
                runOnUiThread {
                    Toast.makeText(this, if (out.contains("uid=0")) "Root Granted!" else "Root Denied", Toast.LENGTH_SHORT).show()
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
        syncCard.addView(rowPerms)

        // Live Terminal Card
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
                PilotCore.logs = "Cleared.\n"
                logView.text = PilotCore.logs
            }
        }
        logHeader.addView(logTitle)
        logHeader.addView(btnClear)

        logScroll = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(-1, 340)
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
        container.addView(cfgCard)
        container.addView(syncCard)
        container.addView(logCard)
        root.addView(container)
        setContentView(root)

        refreshStatus()
        PilotCore.onLog = {
            runOnUiThread {
                logView.text = PilotCore.logs
                etCloud.setText(PilotCore.getCloud(this))
                refreshStatus()
                logScroll.post { logScroll.fullScroll(View.FOCUS_DOWN) }
            }
        }
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
        metaText.text = "GVIP LITE • RAM ~${getAppRamMb()} MB • BOOT READY"
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

    private fun makeInput(hintTxt: String, value: String, num: Boolean = false) = EditText(this).apply {
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
        if (num) inputType = InputType.TYPE_CLASS_NUMBER
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