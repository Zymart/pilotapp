package com.pilot.bot

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.widget.*

class MainActivity : Activity() {
    private lateinit var logView: TextView
    private lateinit var statusView: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 33) {
            requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 101)
        }

        val root = ScrollView(this).apply { setBackgroundColor(Color.parseColor("#121212")) }
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(40, 48, 40, 48)
        }

        val title = TextView(this).apply {
            text = "PILOT CONTROL PANEL"
            textSize = 20f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.parseColor("#00E676"))
        }
        statusView = TextView(this).apply {
            text = if (PilotService.isRunning) "Status: RUNNING" else "Status: STOPPED"
            textSize = 14f
            setTextColor(Color.parseColor("#80CBC4"))
            setPadding(0, 8, 0, 24)
        }

        val etCloud = makeInput("Cloud Name (e.g. Cloud Phone 43)", PilotCore.getCloud(this))
        val etToken = makeInput("Bot Token", PilotCore.getToken(this))
        val etUser = makeInput("Owner Discord User ID", PilotCore.getUserId(this).let { if (it == 0L) "" else it.toString() }, true)
        val etSync = makeInput("Sync Code (XXXXXXX-...)", "")

        val rowBtns = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val btnStart = makeBtn("START BOT", "#00C853") {
            PilotCore.saveConfig(this, etCloud.text.toString(), etToken.text.toString(), etUser.text.toString().toLongOrNull() ?: 0L)
            val intent = Intent(this, PilotService::class.java)
            if (Build.VERSION.SDK_INT >= 26) startForegroundService(intent) else startService(intent)
            statusView.text = "Status: RUNNING"
        }
        val btnStop = makeBtn("STOP BOT", "#D50000") {
            stopService(Intent(this, PilotService::class.java))
            statusView.text = "Status: STOPPED"
        }
        rowBtns.addView(btnStart, LinearLayout.LayoutParams(0, -2, 1f).apply { rightMargin = 12 })
        rowBtns.addView(btnStop, LinearLayout.LayoutParams(0, -2, 1f).apply { leftMargin = 12 })

        val rowSync = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 16, 0, 16)
        }
        val btnGen = makeBtn("CREATE CODE", "#0277BD") {
            PilotCore.saveConfig(this, etCloud.text.toString(), etToken.text.toString(), etUser.text.toString().toLongOrNull() ?: 0L)
            etSync.setText(PilotCore.makeCode(this))
        }
        val btnRestore = makeBtn("USE OLD CODE", "#6A1B9A") {
            if (PilotCore.applyCode(this, etSync.text.toString())) {
                etToken.setText(PilotCore.getToken(this))
                etUser.setText(PilotCore.getUserId(this).toString())
                Toast.makeText(this, "Config Restored!", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, "Invalid Sync Code", Toast.LENGTH_SHORT).show()
            }
        }
        rowSync.addView(btnGen, LinearLayout.LayoutParams(0, -2, 1f).apply { rightMargin = 12 })
        rowSync.addView(btnRestore, LinearLayout.LayoutParams(0, -2, 1f).apply { leftMargin = 12 })

        val btnRoot = makeBtn("GRANT ROOT ACCESS (SU)", "#37474F") {
            Thread {
                val out = PilotCore.su("id")
                runOnUiThread { Toast.makeText(this, if (out.contains("uid=0")) "Root Granted!" else "Root Denied", Toast.LENGTH_SHORT).show() }
            }.start()
        }

        logView = TextView(this).apply {
            text = PilotCore.logs
            textSize = 12f
            typeface = Typeface.MONOSPACE
            setTextColor(Color.parseColor("#E0E0E0"))
            setBackgroundColor(Color.parseColor("#1E1E1E"))
            setPadding(24, 24, 24, 24)
        }

        box.addView(title)
        box.addView(statusView)
        box.addView(etCloud)
        box.addView(etToken)
        box.addView(etUser)
        box.addView(rowBtns)
        box.addView(etSync)
        box.addView(rowSync)
        box.addView(btnRoot)
        box.addView(TextView(this).apply { text = "\nLIVE LOGS:"; setTextColor(Color.GRAY); textSize = 12f })
        box.addView(logView)
        root.addView(box)
        setContentView(root)

        PilotCore.onLog = { runOnUiThread { logView.text = PilotCore.logs; statusView.text = if (PilotService.isRunning) "Status: RUNNING" else "Status: STOPPED" } }
    }

    private fun makeInput(hintTxt: String, value: String, num: Boolean = false) = EditText(this).apply {
        hint = hintTxt
        setText(value)
        setTextColor(Color.WHITE)
        setHintTextColor(Color.GRAY)
        setBackgroundColor(Color.parseColor("#242424"))
        setPadding(28, 24, 28, 24)
        if (num) inputType = InputType.TYPE_CLASS_NUMBER
        layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = 18 }
    }

    private fun makeBtn(label: String, hex: String, onClick: () -> Unit) = Button(this).apply {
        text = label
        setTextColor(Color.WHITE)
        typeface = Typeface.DEFAULT_BOLD
        gravity = Gravity.CENTER
        setBackgroundColor(Color.parseColor(hex))
        setOnClickListener { onClick() }
    }
}
