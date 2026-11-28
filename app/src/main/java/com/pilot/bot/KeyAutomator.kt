package com.pilot.bot

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

data class KeyCheckReport(
    val title: String,
    val summary: String,
    val keyFound: String,
    val details: String,
    val isHealthy: Boolean
)

/**
 * Delta Key Baseplate Tester + Portrait-Locked PlatoRelay & Nousigi Host Key Generator
 * + Ctrl+W Safe Ad-Tab Closer (never backs out to Home) + Floating Key Watcher Overlay
 */
object KeyAutomator {
    const val BASEPLATE_PLACE_ID = "123974602339071"

    private val mainHandler = Handler(Looper.getMainLooper())
    private val running = AtomicBoolean(false)

    @Volatile var isAppInForeground: Boolean = false

    @Volatile private var mode = "DELTA" // "DELTA" or "NOUSIGI_HOST"
    @Volatile private var inChromePhase = false
    @Volatile private var activeTestPkg = ""
    @Volatile private var startedAtMs = 0L
    @Volatile private var currentStep = "Idle"
    @Volatile private var lastLoggedStep = ""
    @Volatile private var previousKeySnapshot = ""
    @Volatile private var lastWebTapMs = 0L
    @Volatile private var lastWebTapSig = ""
    @Volatile private var tasksCompletedCount = 0
    @Volatile private var waitingForTaskTimer = false
    @Volatile private var onSponsorTabSinceMs = 0L
    @Volatile private var nousigiPortalUrl = "https://getkey.nousigi.com/"
    @Volatile private var collectedNousigiKeys = mutableListOf<String>()
    @Volatile private var appCtx: Context? = null
    @Volatile private var completionCb: ((Boolean, String) -> Unit)? = null

    @Volatile private var orientationForced = false
    @Volatile private var savedAccelRotation = "1"
    @Volatile private var savedUserRotation = "0"

    private var overlayRoot: LinearLayout? = null
    private var overlayTitleTv: TextView? = null
    private var overlayStepTv: TextView? = null

    private val DELTA_KEY_RX = Regex("""^KEY_[A-Za-z0-9_-]{12,64}$""")
    private val DELTA_KEY_SEARCH_RX = Regex("""\bKEY_[A-Za-z0-9_-]{12,64}\b""")
    private val NOUSIGI_KEY_SEARCH_RX = Regex("""\b[A-Za-z0-9]{24,64}\b""")
    private val dateFmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)

    private val AD_TRAP_KEYWORDS = listOf(
        "vpnapptoggle",
        "?pbt=",
        "use vpn",
        "stream movies",
        "7-day trial",
        "3-day trial",
        "free trial",
        "€0.00",
        "$0.00",
        "taxes may apply",
        "/ 1 month",
        "no payment today",
        "cancel anytime",
        "phone is infected",
        "virus detected",
        "spin to win",
        "claim your prize",
        "allow notifications"
    )

    fun isRunning(): Boolean = running.get()

    fun setAppForeground(inForeground: Boolean) {
        isAppInForeground = inForeground
        syncOverlayVisibility()
    }

    fun openChromeUrl(url: String) {
        Thread { openInFullChrome(url, lockPortrait = false) }.start()
    }

    private fun forcePortraitOrientation() {
        if (!orientationForced) {
            val accel = SuShell.exec("settings get system accelerometer_rotation 2>/dev/null").trim()
            val userRot = SuShell.exec("settings get system user_rotation 2>/dev/null").trim()
            savedAccelRotation = if (accel == "0" || accel == "1") accel else "1"
            savedUserRotation = if (userRot in setOf("0", "1", "2", "3")) userRot else "0"
            orientationForced = true
        }

        SuShell.exec("settings put system accelerometer_rotation 0 && settings put system user_rotation 0")
        runCatching { Thread.sleep(450L) }

        val curDims = SuShell.exec("dumpsys window displays 2>/dev/null | grep -oE 'cur=[0-9]+x[0-9]+' | head -n 1")
        val m = Regex("""cur=(\d+)x(\d+)""").find(curDims)
        val curW = m?.groupValues?.get(1)?.toIntOrNull() ?: 0
        val curH = m?.groupValues?.get(2)?.toIntOrNull() ?: 0
        if (curW > 0 && curH > 0 && curW > curH) {
            SuShell.exec("settings put system user_rotation 1")
            runCatching { Thread.sleep(450L) }
        }
        PilotCore.log("[KEYAUTO] Forced screen orientation to Portrait")
    }

    private fun restoreOrientation() {
        if (!orientationForced) return
        orientationForced = false
        val rot = savedUserRotation.ifBlank { "0" }
        val accel = savedAccelRotation.ifBlank { "1" }
        SuShell.exec("settings put system user_rotation $rot && settings put system accelerometer_rotation $accel")
        PilotCore.log("[KEYAUTO] Restored screen orientation (user_rotation=$rot, accel=$accel)")
    }

    fun cancel() {
        if (!running.compareAndSet(true, false)) {
            removeFloatingOverlay()
            restoreOrientation()
            return
        }
        inChromePhase = false
        activeTestPkg = ""
        waitingForTaskTimer = false
        onSponsorTabSinceMs = 0L
        currentStep = "Cancelled"
        mainHandler.removeCallbacks(keyWatchRunnable)
        completionCb = null
        removeFloatingOverlay()
        restoreOrientation()
        PilotCore.log("[KEYAUTO] Cancelled active key watcher")
    }

    fun getLiveStatusText(): String {
        if (!running.get()) return "IDLE ($currentStep)"
        val sec = ((System.currentTimeMillis() - startedAtMs) / 1000L).coerceAtLeast(0L)
        return "ACTIVE (${sec}s) — $currentStep"
    }

    fun cleanBakFiles(): Int {
        val countStr = SuShell.exec(
            "cnt=\$(find /storage/emulated/0/Delta -type f -iname '*.bak' 2>/dev/null | wc -l); " +
            "find /storage/emulated/0/Delta -type f -iname '*.bak' -delete 2>/dev/null; " +
            "echo \$cnt"
        ).trim()
        return countStr.toIntOrNull() ?: 0
    }

    fun inspectNousigiKey(ctx: Context): KeyCheckReport {
        val autoKeys = mutableListOf<Triple<String, String, Long>>()
        val autoDirs = listOf("/storage/emulated/0/Delta/Autoexecute", "/storage/emulated/0/Delta/AutoExcecute")
        val keyAssignRx = Regex("""(?:getgenv\(\)\s*\.\s*Key|script_key|Key)\s*=\s*["']([^"'\r\n]{8,80})["']""")

        for (dir in autoDirs) {
            val files = SuShell.execLines("find '$dir' -maxdepth 2 -type f 2>/dev/null")
            for (f in files) {
                val content = SuShell.exec("cat '$f' 2>/dev/null")
                val m = keyAssignRx.find(content)
                if (m != null) {
                    val mtime = SuShell.exec("stat -c '%Y' '$f' 2>/dev/null").trim().toLongOrNull() ?: 0L
                    autoKeys.add(Triple(f, m.groupValues[1].trim(), mtime))
                }
            }
        }

        val wsKeys = mutableListOf<Triple<String, String, Long>>()
        val wsFiles = SuShell.execLines(
            "find '/storage/emulated/0/Delta/Workspace/Nousigi Hub' '/storage/emulated/0/Delta/Workspace' -maxdepth 3 -type f -iname '*key*' 2>/dev/null"
        )
        for (wf in wsFiles) {
            val raw = SuShell.exec("cat '$wf' 2>/dev/null").trim()
            if (raw.isNotEmpty() && raw.length <= 128) {
                val mtime = SuShell.exec("stat -c '%Y' '$wf' 2>/dev/null").trim().toLongOrNull() ?: 0L
                wsKeys.add(Triple(wf, raw, mtime))
            }
        }

        val distinctAuto = autoKeys.distinctBy { it.first }
        val distinctWs = wsKeys.distinctBy { it.first }
        val primaryKey = distinctAuto.firstOrNull()?.second
            ?: distinctWs.firstOrNull()?.second
            ?: ""

        if (primaryKey.isEmpty()) {
            return KeyCheckReport(
                title = "🔑 Nousigi Key Check — Not Found",
                summary = "No Nousigi key was found in `Delta/Autoexecute` or `Delta/Workspace/Nousigi Hub`.",
                keyFound = "None",
                details = "Use **✏️ Change Nousigi Key** below to set your key in Autoexecute & Nousigi Hub.",
                isHealthy = false
            )
        }

        val sb = StringBuilder()
        if (distinctAuto.isNotEmpty()) {
            sb.append("**Autoexecute Scripts:**\n")
            distinctAuto.forEach { (path, k, ts) ->
                val whenStr = if (ts > 0L) dateFmt.format(Date(ts * 1000L)) else "Unknown"
                sb.append("• `${path.removePrefix("/storage/emulated/0/")}`\n  Key: `$k` • Updated: `$whenStr`\n")
            }
        }
        if (distinctWs.isNotEmpty()) {
            sb.append("\n**Nousigi Hub Workspace Key File(s):**\n")
            distinctWs.forEach { (path, k, ts) ->
                val whenStr = if (ts > 0L) dateFmt.format(Date(ts * 1000L)) else "Unknown"
                sb.append("• `${path.removePrefix("/storage/emulated/0/")}`\n  Key: `$k` • Updated: `$whenStr`\n")
            }
        }

        val allKeys = (distinctAuto.map { it.second } + distinctWs.map { it.second }).distinct()
        val syncNote = if (allKeys.size > 1) {
            "\n⚠️ **Mismatch detected** between Autoexecute and Workspace key files! Tap **✏️ Change Nousigi Key** to sync both."
        } else {
            "\n✅ All detected Nousigi key locations match."
        }

        return KeyCheckReport(
            title = "🔑 Active Nousigi Key",
            summary = "Key: ```\n$primaryKey\n```",
            keyFound = primaryKey,
            details = sb.toString().trim() + syncNote,
            isHealthy = allKeys.size == 1
        )
    }

    fun updateNousigiKeyEverywhere(ctx: Context, rawNewKey: String): Pair<Boolean, String> {
        val newKey = rawNewKey.trim().replace("\"", "").replace("'", "")
        if (newKey.length < 8) return false to "Invalid key: `$newKey` is too short."

        val updatedFiles = mutableListOf<String>()
        val autoDirs = listOf("/storage/emulated/0/Delta/Autoexecute", "/storage/emulated/0/Delta/AutoExcecute")
        val keyAssignRx = Regex("""((?:getgenv\(\)\s*\.\s*Key|script_key|Key)\s*=\s*)(["'])[^"'\r\n]*\2""")

        for (dir in autoDirs) {
            val files = SuShell.execLines("find '$dir' -maxdepth 2 -type f \\( -iname '*.lua' -o -iname '*.txt' \\) 2>/dev/null")
            for (f in files) {
                if (f.endsWith("pilot_bridge.lua", ignoreCase = true)) continue
                val content = SuShell.exec("cat '$f' 2>/dev/null")
                if (content.isBlank()) continue

                val newContent = when {
                    keyAssignRx.containsMatchIn(content) -> {
                        keyAssignRx.replace(content) { mr -> "${mr.groupValues[1]}\"$newKey\"" }
                    }
                    content.contains("nousigi", ignoreCase = true) || content.contains("luarmor", ignoreCase = true) -> {
                        "getgenv().Key = \"$newKey\"\n$content"
                    }
                    else -> null
                }

                if (newContent != null) {
                    val tmp = File(ctx.cacheDir, "nousigi_auto_tmp_${System.nanoTime()}")
                    tmp.writeText(newContent)
                    SuShell.exec("cp '${tmp.absolutePath}' '$f' && chmod 666 '$f'")
                    tmp.delete()
                    updatedFiles.add(f.removePrefix("/storage/emulated/0/"))
                }
            }
        }

        if (updatedFiles.isEmpty()) {
            val defaultDir = "/storage/emulated/0/Delta/Autoexecute"
            val targetFile = "$defaultDir/nousigi_key.lua"
            val tmp = File(ctx.cacheDir, "nousigi_key_tmp")
            tmp.writeText("getgenv().Key = \"$newKey\"\n")
            SuShell.exec("mkdir -p '$defaultDir' && cp '${tmp.absolutePath}' '$targetFile' && chmod 666 '$targetFile'")
            tmp.delete()
            updatedFiles.add(targetFile.removePrefix("/storage/emulated/0/"))
        }

        val wsFiles = SuShell.execLines(
            "find '/storage/emulated/0/Delta/Workspace/Nousigi Hub' '/storage/emulated/0/Delta/Workspace' -maxdepth 3 -type f -iname '*key*' 2>/dev/null"
        ).distinct()
        for (wf in wsFiles) {
            val tmp = File(ctx.cacheDir, "nousigi_ws_tmp_${System.nanoTime()}")
            tmp.writeText(newKey)
            SuShell.exec("cp '${tmp.absolutePath}' '$wf' && chmod 666 '$wf'")
            tmp.delete()
            updatedFiles.add(wf.removePrefix("/storage/emulated/0/"))
        }

        val deletedBaks = cleanBakFiles()

        PilotCore.log("[KEYAUTO] Updated Nousigi key in ${updatedFiles.size} file(s), removed $deletedBaks .bak file(s)")
        val fileList = updatedFiles.joinToString("\n") { "• `$it`" }
        return true to "**New Key:** `$newKey`\n\n**Updated Files:**\n$fileList\n\n🧹 **Cleaned `.bak` files:** `$deletedBaks` removed"
    }

    fun inspectDeltaKey(ctx: Context): KeyCheckReport {
        val candidates = SuShell.execLines(
            "find '/storage/emulated/0/Delta/Internals' '/storage/emulated/0/Delta' -maxdepth 3 -type f \\( -iname '*key*' -o -iname '*auth*' -o -iname '*license*' \\) 2>/dev/null"
        ).distinct()

        var foundKey = ""
        var foundPath = ""
        var mtimeSec = 0L

        for (path in candidates) {
            val content = SuShell.exec("cat '$path' 2>/dev/null").trim()
            val m = DELTA_KEY_SEARCH_RX.find(content)
            if (m != null) {
                foundKey = m.value
                foundPath = path
                mtimeSec = SuShell.exec("stat -c '%Y' '$path' 2>/dev/null").trim().toLongOrNull() ?: 0L
                break
            }
        }

        val slots = Rejoiner.discoverClones()
        val activePinging = slots.count { it.status == "ACTIVE" && it.lastPingMs > 0L }
        val noKeySlots = slots.count { it.status == "NO_DELTA_KEY" }

        if (foundKey.isEmpty()) {
            return KeyCheckReport(
                title = "⏳ Delta Key Check — No Local Key File",
                summary = if (activePinging > 0) "Delta is currently **ACTIVE** on `$activePinging` slot(s) (server HWID session), but no `KEY_...` cache file was found in `Delta/Internals`."
                else "No `KEY_...` file found in `Delta/Internals` and no slots are currently pinging.",
                keyFound = "None",
                details = "• **Active Pinging Slots:** `$activePinging/${slots.size}`\n• **Slots Needing Key:** `$noKeySlots`",
                isHealthy = activePinging > 0 && noKeySlots == 0
            )
        }

        val nowSec = System.currentTimeMillis() / 1000L
        val ageSec = (nowSec - mtimeSec).coerceAtLeast(0L)
        val totalWindowSec = 24L * 3600L
        val remSec = totalWindowSec - ageSec

        val savedAtStr = if (mtimeSec > 0L) dateFmt.format(Date(mtimeSec * 1000L)) else "Unknown"
        val ageStr = formatDuration(ageSec)
        val remStr = if (remSec > 0L && noKeySlots == 0) {
            "🟢 **${formatDuration(remSec)} remaining** (out of 24h)"
        } else {
            "🔴 **EXPIRED** (${formatDuration(kotlin.math.abs(remSec))} overdue)"
        }

        return KeyCheckReport(
            title = "⏳ Delta Key & Time Remaining",
            summary = "Key: `$foundKey`\nStatus: $remStr",
            keyFound = foundKey,
            details = "• **Saved / Activated:** `$savedAtStr` (`$ageStr` ago)\n" +
                "• **File:** `${foundPath.removePrefix("/storage/emulated/0/")}`\n" +
                "• **Live Executor Pings:** `$activePinging/${slots.size}` active (`$noKeySlots` missing key)",
            isHealthy = remSec > 0L && noKeySlots == 0
        )
    }

    private fun formatDuration(totalSec: Long): String {
        val h = totalSec / 3600L
        val m = (totalSec % 3600L) / 60L
        return "${h}h ${m}m"
    }

    // --- Always-On-Top Floating Key Watcher Pill (Only visible when NOT inside Pilot app) ---

    private fun showFloatingOverlay(ctx: Context) {
        mainHandler.post {
            if (!running.get()) return@post
            if (overlayRoot != null) {
                syncOverlayVisibility()
                refreshOverlayText()
                return@post
            }
            if (!Settings.canDrawOverlays(ctx)) {
                SuShell.exec("cmd appops set ${ctx.packageName} SYSTEM_ALERT_WINDOW allow >/dev/null 2>&1")
                if (!Settings.canDrawOverlays(ctx)) return@post
            }

            runCatching {
                val wm = ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager
                val density = ctx.resources.displayMetrics.density
                fun dp(v: Int): Int = (v * density).toInt()

                val pill = LinearLayout(ctx).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(dp(12), dp(7), dp(8), dp(7))
                    background = GradientDrawable().apply {
                        setColor(Color.parseColor("#E60B1220"))
                        setStroke(dp(1), Color.parseColor("#38BDF8"))
                        cornerRadius = dp(18).toFloat()
                    }
                    visibility = if (isAppInForeground) View.GONE else View.VISIBLE
                }

                val textCol = LinearLayout(ctx).apply {
                    orientation = LinearLayout.VERTICAL
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply { rightMargin = dp(10) }
                }

                val titleTv = TextView(ctx).apply {
                    text = "🔑 KEY WATCHER • 0s"
                    textSize = 10f
                    setTypeface(Typeface.MONOSPACE, Typeface.BOLD)
                    setTextColor(Color.parseColor("#38BDF8"))
                }

                val stepTv = TextView(ctx).apply {
                    text = currentStep
                    textSize = 11f
                    maxWidth = dp(280)
                    setTypeface(Typeface.MONOSPACE)
                    setTextColor(Color.parseColor("#F8FAFC"))
                }

                textCol.addView(titleTv)
                textCol.addView(stepTv)

                val stopBtn = Button(ctx).apply {
                    text = "✕ STOP"
                    textSize = 9.5f
                    setTypeface(Typeface.MONOSPACE, Typeface.BOLD)
                    setTextColor(Color.WHITE)
                    setPadding(dp(8), dp(2), dp(8), dp(2))
                    minHeight = 0
                    minWidth = 0
                    background = GradientDrawable().apply {
                        setColor(Color.parseColor("#B91C1C"))
                        cornerRadius = dp(10).toFloat()
                    }
                    setOnClickListener { cancel() }
                }

                pill.addView(textCol)
                pill.addView(stopBtn)

                val lpType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                } else {
                    @Suppress("DEPRECATION")
                    WindowManager.LayoutParams.TYPE_PHONE
                }

                val params = WindowManager.LayoutParams(
                    WindowManager.LayoutParams.WRAP_CONTENT,
                    WindowManager.LayoutParams.WRAP_CONTENT,
                    lpType,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                    PixelFormat.TRANSLUCENT
                ).apply {
                    gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
                    y = dp(14)
                }

                wm.addView(pill, params)
                overlayRoot = pill
                overlayTitleTv = titleTv
                overlayStepTv = stepTv
                refreshOverlayText()
            }
        }
    }

    private fun syncOverlayVisibility() {
        mainHandler.post {
            val pill = overlayRoot ?: return@post
            pill.visibility = if (running.get() && !isAppInForeground) View.VISIBLE else View.GONE
        }
    }

    private fun refreshOverlayText() {
        mainHandler.post {
            val titleTv = overlayTitleTv ?: return@post
            val stepTv = overlayStepTv ?: return@post
            val sec = ((System.currentTimeMillis() - startedAtMs) / 1000L).coerceAtLeast(0L)
            titleTv.text = if (mode == "NOUSIGI_HOST") {
                "👑 HOST KEY GEN • ${sec}s (${collectedNousigiKeys.size}/5)"
            } else {
                "🔑 KEY WATCHER • ${sec}s (Tasks: $tasksCompletedCount)"
            }
            stepTv.text = currentStep
            syncOverlayVisibility()
        }
    }

    private fun removeFloatingOverlay() {
        mainHandler.post {
            val pill = overlayRoot ?: return@post
            val ctx = appCtx
            if (ctx != null) {
                runCatching {
                    val wm = ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager
                    wm.removeView(pill)
                }
            }
            overlayRoot = null
            overlayTitleTv = null
            overlayStepTv = null
        }
    }

    private fun updateStep(step: String) {
        currentStep = step
        refreshOverlayText()
        if (step != lastLoggedStep) {
            lastLoggedStep = step
            PilotCore.log("[KEYAUTO] $step")
        }
    }

    private fun isPlatoUrl(text: String): Boolean {
        val t = text.trim().lowercase()
        return t.startsWith("http") && (t.contains("platorelay.com") || t.contains("platoboost"))
    }

    private fun tapDeltaReceiveKeyInWindow(pkg: String): Boolean {
        if (pkg.isBlank()) return false
        var win = PilotCore.findOuterWindowBounds(pkg)
        if (win == null) {
            SuShell.exec("monkey -p $pkg -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1")
            runCatching { Thread.sleep(650) }
            win = PilotCore.findOuterWindowBounds(pkg)
        }
        if (win == null) return false

        val titleBarH = 38
        val contentTop = win.top + titleBarH
        val contentW = (win.right - win.left).coerceAtLeast(220)
        val contentH = (win.bottom - contentTop).coerceAtLeast(160)

        val tapX = win.left + (contentW * 0.82f).toInt()
        val tapY1 = contentTop + (contentH * 0.56f).toInt()
        val tapY2 = contentTop + (contentH * 0.60f).toInt()

        SuShell.exec("input tap $tapX $tapY1")
        PilotCore.log("[KEYAUTO] Tapped Delta 'Receive Key' at ($tapX, $tapY1) inside $pkg")
        runCatching { Thread.sleep(450) }
        SuShell.exec("input tap $tapX $tapY2")
        return true
    }

    private fun dumpUiXmlCompressed(): String {
        val out = SuShell.exec("rm -f /sdcard/pilot_ui.xml && uiautomator dump --compressed /sdcard/pilot_ui.xml 2>&1")
        if (!out.contains("UI hierchary dumped", ignoreCase = true) &&
            !out.contains("UI hierarchy dumped", ignoreCase = true)
        ) {
            SuShell.exec("rm -f /sdcard/pilot_ui.xml && uiautomator dump /sdcard/pilot_ui.xml >/dev/null 2>&1")
        }
        return SuShell.exec("cat /sdcard/pilot_ui.xml 2>/dev/null")
    }

    private fun readChromeNewestTabStrings(): String {
        return SuShell.exec(
            "newest=\$(ls -t /data/data/com.android.chrome/app_tabs/0/tab* 2>/dev/null | head -n 1); " +
            "[ -n \"\$newest\" ] && strings \"\$newest\" 2>/dev/null | tail -n 30"
        )
    }

    private fun isFakeAdTrap(rawText: String): Boolean {
        if (rawText.isBlank()) return false
        val low = rawText.lowercase()
        return AD_TRAP_KEYWORDS.any { low.contains(it) }
    }

    private fun findWhiteContinueButtonByPixels(ctx: Context): Pair<Int, Int>? {
        val snapPath = File(ctx.cacheDir, "key_scan_${System.nanoTime()}.png").absolutePath
        SuShell.exec("screencap -p '$snapPath' && chmod 666 '$snapPath'")
        val file = File(snapPath)
        if (!file.exists() || file.length() == 0L) return null

        return try {
            val bmp = BitmapFactory.decodeFile(snapPath) ?: return null
            val w = bmp.width
            val h = bmp.height
            val cx = w / 2

            val leftMarginPx = bmp.getPixel((w * 0.08f).toInt(), (h * 0.50f).toInt())
            val rightMarginPx = bmp.getPixel((w * 0.92f).toInt(), (h * 0.50f).toInt())
            val isDarkBg = Color.red(leftMarginPx) < 35 && Color.green(leftMarginPx) < 35 &&
                Color.red(rightMarginPx) < 35 && Color.green(rightMarginPx) < 35
            if (!isDarkBg) {
                bmp.recycle()
                return null
            }

            val yStart = (h * 0.40f).toInt()
            val yEnd = (h * 0.88f).toInt()
            var runStart = -1
            var foundCenterY = -1

            for (y in yStart..yEnd) {
                val p = bmp.getPixel(cx, y)
                val isWhite = Color.red(p) > 220 && Color.green(p) > 220 && Color.blue(p) > 220
                if (isWhite) {
                    if (runStart < 0) runStart = y
                } else {
                    if (runStart >= 0) {
                        val bandH = y - runStart
                        if (bandH in 24..115) {
                            val leftSide = bmp.getPixel((cx - 70).coerceAtLeast(10), (runStart + y) / 2)
                            val rightSide = bmp.getPixel((cx + 70).coerceAtMost(w - 10), (runStart + y) / 2)
                            if (Color.red(leftSide) > 210 && Color.red(rightSide) > 210) {
                                foundCenterY = (runStart + y) / 2
                            }
                        }
                        runStart = -1
                    }
                }
            }
            bmp.recycle()
            if (foundCenterY > 0) cx to foundCenterY else null
        } finally {
            file.delete()
        }
    }

    /** Closes only the active pop-up/task tab (Ctrl+W) and re-focuses PlatoRelay in Chrome without ever backing out to Home. */
    private fun closeChromeTabAndReturnToMain(ctx: Context, stepLabel: String, mainUrl: String) {
        onSponsorTabSinceMs = 0L
        waitingForTaskTimer = false
        lastWebTapSig = ""
        updateStep(stepLabel)

        // Ctrl+W (KEYCODE_W = 51 with --ctrl) closes the current tab in Chrome without exiting Chrome to Home
        SuShell.exec("input keyevent --ctrl 51")
        Thread.sleep(700L)

        // Bring the PlatoRelay / Nousigi URL back to front in Chrome
        val targetUrl = mainUrl.trim().ifBlank {
            Rejoiner.loadConfig(ctx).deltaPlatoUrl.trim()
        }
        if (targetUrl.isNotBlank()) {
            openInFullChrome(targetUrl, lockPortrait = false)
            Thread.sleep(900L)
        } else {
            SuShell.exec("monkey -p com.android.chrome -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1")
            Thread.sleep(700L)
        }
    }

    /** Automates Nousigi Host Key Generation (up to 5 keys) in Chrome after Discord & Linkvertise login. */
    private fun processNousigiHostStep(ctx: Context) {
        val now = System.currentTimeMillis()
        val xml = dumpUiXmlCompressed()
        val tabStrings = readChromeNewestTabStrings()
        val combined = "$xml\n$tabStrings"

        val clip = readClipboard(ctx).trim()
        val clipCandidate = if (clip != previousKeySnapshot && NOUSIGI_KEY_SEARCH_RX.matches(clip) && !clip.startsWith("KEY_")) clip else null
        val xmlKeyMatch = Regex("""(?:Key|key|Token)["'\s:>]+([A-Za-z0-9]{24,64})""").find(xml)?.groupValues?.get(1)
        val foundKey = clipCandidate ?: xmlKeyMatch

        if (foundKey != null && foundKey !in collectedNousigiKeys && !foundKey.contains("nousigi", true)) {
            collectedNousigiKeys.add(foundKey)
            previousKeySnapshot = foundKey
            val cl = PilotCore.getCloud(ctx)
            val entries = PilotCore.setKeyHostPool(ctx, cl, collectedNousigiKeys)
            if (entries.size == 1) {
                updateNousigiKeyEverywhere(ctx, entries[0].key)
            }
            updateStep("Host Key ${collectedNousigiKeys.size}/5 captured!")
            if (collectedNousigiKeys.size >= 5) {
                finishFlow(
                    true,
                    "👑 Collected **5/5** Nousigi keys on **$cl** (Slot `1/5` applied to Host). Run `!distributekeys` to send Slots `2–5` to your 4 worker phones!"
                )
                return
            }
            Thread.sleep(1200L)
            openInFullChrome(nousigiPortalUrl, lockPortrait = true)
            return
        }

        if (isFakeAdTrap(combined)) {
            closeChromeTabAndReturnToMain(ctx, "Blocked ad redirect -> returning to Nousigi...", nousigiPortalUrl)
            return
        }

        val isLinkvertise = combined.contains("linkvertise.com", ignoreCase = true)
        if (isLinkvertise) {
            if (onSponsorTabSinceMs == 0L) onSponsorTabSinceMs = now
            val waited = (now - onSponsorTabSinceMs) / 1000L
            if (waited < 10L) {
                updateStep("Linkvertise (${collectedNousigiKeys.size}/5): Waiting ${10L - waited}s...")
                return
            }
        } else {
            onSponsorTabSinceMs = 0L
        }

        if (xml.isBlank()) {
            updateStep("Chrome (Host ${collectedNousigiKeys.size}/5): Scanning Nousigi portal...")
            return
        }

        data class UiNode(val label: String, val cx: Int, val cy: Int, val enabled: Boolean)
        val nodes = mutableListOf<UiNode>()
        val nodeRx = Regex("""<node\b([^>]+)>""")
        val textRx = Regex("""\btext="([^"]*)"""")
        val descRx = Regex("""\bcontent-desc="([^"]*)"""")
        val enRx = Regex("""\benabled="(true|false)"""")
        val boundsRx = Regex("""\bbounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"""")

        for (m in nodeRx.findAll(xml)) {
            val attrs = m.groupValues[1]
            val bm = boundsRx.find(attrs) ?: continue
            val l = bm.groupValues[1].toInt()
            val t = bm.groupValues[2].toInt()
            val r = bm.groupValues[3].toInt()
            val b = bm.groupValues[4].toInt()
            if (r - l < 18 || b - t < 14) continue
            val txt = textRx.find(attrs)?.groupValues?.get(1).orEmpty().trim()
            val desc = descRx.find(attrs)?.groupValues?.get(1).orEmpty().trim()
            val label = txt.ifEmpty { desc }
            if (label.isNotEmpty()) {
                val enabled = enRx.find(attrs)?.groupValues?.get(1) != "false"
                nodes.add(UiNode(label, (l + r) / 2, (t + b) / 2, enabled))
            }
        }

        val patterns = listOf(
            "copy key",
            "copy",
            "get key",
            "generate key",
            "create key",
            "free access",
            "continue",
            "authorize",
            "verify you are human",
            "i am human",
            "next",
            "get started"
        )
        for (pat in patterns) {
            val hit = nodes.firstOrNull { n ->
                val low = n.label.lowercase()
                n.enabled && n.cy > 90 && (low == pat || (pat.length >= 6 && low.contains(pat)))
            }
            if (hit != null) {
                tapNodeIfReady(hit.label, hit.cx, hit.cy, "Host (${collectedNousigiKeys.size}/5): Tapping '${hit.label.take(22)}'...")
                return
            }
        }

        updateStep("Host (${collectedNousigiKeys.size}/5): Waiting on Nousigi / Linkvertise...")
    }

    private fun processChromePlatoRelayStep(ctx: Context): String? {
        val now = System.currentTimeMillis()
        val focusInfo = SuShell.exec(
            "dumpsys window windows 2>/dev/null | grep -E 'mCurrentFocus|mFocusedApp' | head -n 3"
        )
        val xml = dumpUiXmlCompressed()
        val tabStrings = readChromeNewestTabStrings()
        val combinedContext = "$xml\n$tabStrings"

        val isRobloxInFront = focusInfo.contains("com.roblox.clien") || xml.contains("package=\"com.roblox.clien")
        val isChromeInFront = focusInfo.contains("com.android.chrome") || xml.contains("package=\"com.android.chrome\"") || xml.contains("url_bar")

        if (isRobloxInFront && !isChromeInFront && activeTestPkg.isNotEmpty()) {
            if (now - lastWebTapMs >= 3500L) {
                lastWebTapMs = now
                updateStep("Re-tapping 'Receive Key' in Delta...")
                tapDeltaReceiveKeyInWindow(activeTestPkg)
            }
            return null
        }

        val directKey = DELTA_KEY_SEARCH_RX.find(combinedContext)?.value
        if (directKey != null && directKey != previousKeySnapshot) {
            onSponsorTabSinceMs = 0L
            waitingForTaskTimer = false
            updateStep("Scraped $directKey directly from page!")
            return directKey
        }

        val savedPlato = Rejoiner.loadConfig(ctx).deltaPlatoUrl.trim()

        if (isFakeAdTrap(combinedContext)) {
            closeChromeTabAndReturnToMain(ctx, "Blocked fake ad redirect -> closing tab...", savedPlato)
            return null
        }

        val isOnPlatoHost = combinedContext.contains("platorelay.com", ignoreCase = true) ||
            combinedContext.contains("platoboost.com", ignoreCase = true) ||
            combinedContext.contains("auth.platorelay", ignoreCase = true) ||
            combinedContext.contains("Delta Android Keysystem", ignoreCase = true)

        if (xml.isBlank()) {
            if (isChromeInFront && !isOnPlatoHost && tabStrings.isNotBlank()) {
                if (!waitingForTaskTimer) {
                    closeChromeTabAndReturnToMain(ctx, "Pop-up ad tab opened -> closing immediately...", savedPlato)
                    return null
                }
                if (onSponsorTabSinceMs == 0L) onSponsorTabSinceMs = now
                val waitedSec = (now - onSponsorTabSinceMs) / 1000L
                if (waitedSec < 11L) {
                    updateStep("Task #${tasksCompletedCount + 1}: Waiting ${11L - waitedSec}s on task tab...")
                    return null
                }
                tasksCompletedCount += 1
                closeChromeTabAndReturnToMain(ctx, "Task #$tasksCompletedCount done! Closing tab...", savedPlato)
                return null
            }

            val whiteBtn = findWhiteContinueButtonByPixels(ctx)
            if (whiteBtn != null) {
                tapNodeIfReady("white_continue_px", whiteBtn.first, whiteBtn.second, "Chrome: Tapping white Continue (${whiteBtn.first}, ${whiteBtn.second})...")
            } else {
                updateStep("Chrome: Scanning portrait screen for Continue / key...")
            }
            return null
        }

        if (isChromeInFront && !isOnPlatoHost) {
            if (!waitingForTaskTimer) {
                closeChromeTabAndReturnToMain(ctx, "Pop-up ad tab opened -> closing immediately...", savedPlato)
                return null
            }

            if (onSponsorTabSinceMs == 0L) {
                onSponsorTabSinceMs = now
            }
            val waitedSec = (now - onSponsorTabSinceMs) / 1000L
            if (waitedSec < 11L) {
                val rem = 11L - waitedSec
                updateStep("Task #${tasksCompletedCount + 1}: Waiting ${rem}s on task tab...")
                return null
            }

            tasksCompletedCount += 1
            closeChromeTabAndReturnToMain(ctx, "Task #$tasksCompletedCount done! Closing tab -> PlatoRelay...", savedPlato)
            return null
        }

        onSponsorTabSinceMs = 0L
        waitingForTaskTimer = false

        val urlMatch = Regex("""https?://(?:auth\.platorelay\.com|gateway\.platoboost\.com)/[^\s"<']+""").find(combinedContext)
        if (urlMatch != null) {
            val foundUrl = urlMatch.value
            if (savedPlato != foundUrl) {
                Rejoiner.saveConfig(ctx, Rejoiner.loadConfig(ctx).copy(deltaPlatoUrl = foundUrl))
            }
        }

        data class UiNode(val label: String, val cx: Int, val cy: Int, val enabled: Boolean)
        val nodes = mutableListOf<UiNode>()
        val nodeRx = Regex("""<node\b([^>]+)>""")
        val textRx = Regex("""\btext="([^"]*)"""")
        val descRx = Regex("""\bcontent-desc="([^"]*)"""")
        val enRx = Regex("""\benabled="(true|false)"""")
        val boundsRx = Regex("""\bbounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"""")

        for (m in nodeRx.findAll(xml)) {
            val attrs = m.groupValues[1]
            val bm = boundsRx.find(attrs) ?: continue
            val l = bm.groupValues[1].toInt()
            val t = bm.groupValues[2].toInt()
            val r = bm.groupValues[3].toInt()
            val b = bm.groupValues[4].toInt()
            if (r - l < 18 || b - t < 14) continue

            val txt = textRx.find(attrs)?.groupValues?.get(1).orEmpty().trim()
            val desc = descRx.find(attrs)?.groupValues?.get(1).orEmpty().trim()
            val label = txt.ifEmpty { desc }
            if (label.isEmpty()) continue

            val enabled = enRx.find(attrs)?.groupValues?.get(1) != "false"
            nodes.add(UiNode(label, (l + r) / 2, (t + b) / 2, enabled))
        }

        val finishPatterns = listOf("copy key", "copy", "create key", "get key")
        for (pat in finishPatterns) {
            val hit = nodes.firstOrNull { n ->
                val low = n.label.lowercase()
                n.enabled && n.cy > 90 && (low == pat || (pat.length >= 6 && low.contains(pat)))
            }
            if (hit != null) {
                tapNodeIfReady(hit.label, hit.cx, hit.cy, "Chrome: Tapping '${hit.label.take(24)}'...")
                return null
            }
        }

        val chromePrompts = listOf("accept & continue", "use without an account", "no thanks", "got it")
        for (pat in chromePrompts) {
            val hit = nodes.firstOrNull { n -> n.label.lowercase().contains(pat) && n.cy > 90 }
            if (hit != null) {
                tapNodeIfReady(hit.label, hit.cx, hit.cy, "Chrome: Dismissing '${hit.label.take(24)}'...")
                return null
            }
        }

        val uncompletedTask = nodes.firstOrNull { n ->
            val low = n.label.lowercase()
            n.enabled && n.cy > 110 &&
                !low.contains("completed") &&
                !low.contains("done") &&
                !low.contains("verified") &&
                !low.contains("tutorial") &&
                !low.contains("shop") &&
                (low == "complete" ||
                    low == "visit" ||
                    low == "read" ||
                    low == "watch" ||
                    low == "discover" ||
                    low == "open" ||
                    low == "start" ||
                    low == "go" ||
                    low.startsWith("complete ") ||
                    low.startsWith("visit ") ||
                    low.startsWith("task "))
        }
        if (uncompletedTask != null) {
            waitingForTaskTimer = true
            tapNodeIfReady(
                uncompletedTask.label,
                uncompletedTask.cx,
                uncompletedTask.cy,
                "Starting Task #${tasksCompletedCount + 1} ('${uncompletedTask.label.take(20)}')..."
            )
            return null
        }

        val verifyAndContinuePatterns = listOf(
            "verify you are human",
            "i am human",
            "confirm you are human",
            "continue",
            "proceed",
            "unlock",
            "next"
        )
        for (pat in verifyAndContinuePatterns) {
            val hit = nodes.firstOrNull { n ->
                val low = n.label.lowercase()
                n.enabled && n.cy > 90 && (low == pat || (pat.length >= 6 && low.contains(pat)))
            }
            if (hit != null) {
                waitingForTaskTimer = false
                tapNodeIfReady(hit.label, hit.cx, hit.cy, "Chrome: Tapping '${hit.label.take(24)}' (${hit.cx}, ${hit.cy})...")
                return null
            }
        }

        val whiteBtn = findWhiteContinueButtonByPixels(ctx)
        if (whiteBtn != null) {
            waitingForTaskTimer = false
            tapNodeIfReady("white_continue_px", whiteBtn.first, whiteBtn.second, "Chrome: Tapping white Continue (${whiteBtn.first}, ${whiteBtn.second})...")
            return null
        }

        updateStep("Chrome: Scanning PlatoRelay page (Tasks: $tasksCompletedCount)...")
        return null
    }

    private fun tapNodeIfReady(label: String, cx: Int, cy: Int, stepMsg: String) {
        val now = System.currentTimeMillis()
        val sig = "${label.lowercase()}@$cx,$cy"
        if (sig != lastWebTapSig || (now - lastWebTapMs) >= 3500L) {
            lastWebTapSig = sig
            lastWebTapMs = now
            updateStep(stepMsg)
            SuShell.exec("input tap $cx $cy")
        }
    }

    private val keyWatchRunnable = object : Runnable {
        override fun run() {
            if (!running.get()) return
            refreshOverlayText()

            val timeoutMs = if (mode == "NOUSIGI_HOST") 300_000L else 150_000L
            if (System.currentTimeMillis() - startedAtMs > timeoutMs) {
                if (mode == "NOUSIGI_HOST" && collectedNousigiKeys.isNotEmpty()) {
                    finishFlow(true, "Saved **${collectedNousigiKeys.size}/5** Nousigi key(s) into Host Pool.")
                } else {
                    finishFlow(false, "Timed out (last step: $currentStep) — returning control.")
                }
                return
            }

            val ctx = appCtx
            if (ctx != null) {
                if (mode == "NOUSIGI_HOST") {
                    Thread {
                        if (running.get()) processNousigiHostStep(ctx)
                    }.start()
                    mainHandler.postDelayed(this, 2000L)
                    return
                }

                val clip = readClipboard(ctx).trim()
                if (clip.isNotEmpty() && clip != previousKeySnapshot) {
                    val token = extractDeltaKey(clip)
                    if (token != null && token != previousKeySnapshot) {
                        applyDeltaKeyInternal(ctx, token)
                        return
                    }
                }

                if (inChromePhase) {
                    Thread {
                        if (!running.get()) return@Thread
                        val scrapedKey = processChromePlatoRelayStep(ctx)
                        if (scrapedKey != null && running.get()) {
                            applyDeltaKeyInternal(ctx, scrapedKey)
                        }
                    }.start()
                    mainHandler.postDelayed(this, 1800L)
                    return
                }
            }

            mainHandler.postDelayed(this, 1200L)
        }
    }

    fun startNousigiKeyGeneration(ctx: Context, portalUrl: String = "https://getkey.nousigi.com/"): Boolean {
        cancel()
        if (!running.compareAndSet(false, true)) return false

        mode = "NOUSIGI_HOST"
        appCtx = ctx.applicationContext
        nousigiPortalUrl = portalUrl.trim().ifBlank { "https://getkey.nousigi.com/" }
        collectedNousigiKeys = PilotCore.getKeyPool(ctx).map { it.key }.toMutableList()
        inChromePhase = true
        lastLoggedStep = ""
        lastWebTapMs = 0L
        lastWebTapSig = ""
        onSponsorTabSinceMs = 0L
        startedAtMs = System.currentTimeMillis()
        showFloatingOverlay(ctx.applicationContext)

        Thread {
            previousKeySnapshot = readClipboard(ctx).trim()
            updateStep("Opening Nousigi Key Portal (${collectedNousigiKeys.size}/5)...")
            openInFullChrome(nousigiPortalUrl, lockPortrait = true)
            mainHandler.postDelayed(keyWatchRunnable, 2200L)
        }.start()
        return true
    }

    fun startDeltaRenewal(
        ctx: Context,
        explicitUrl: String = "",
        onDone: ((Boolean, String) -> Unit)? = null
    ): Boolean {
        if (!running.compareAndSet(false, true)) {
            PilotCore.log("[KEYAUTO] Already running ($currentStep)...")
            return false
        }
        mode = "DELTA"
        appCtx = ctx.applicationContext
        inChromePhase = false
        activeTestPkg = ""
        lastLoggedStep = ""
        lastWebTapMs = 0L
        lastWebTapSig = ""
        tasksCompletedCount = 0
        waitingForTaskTimer = false
        onSponsorTabSinceMs = 0L
        completionCb = onDone
        startedAtMs = System.currentTimeMillis()
        showFloatingOverlay(ctx.applicationContext)

        Thread {
            forcePortraitOrientation()

            val currentClip = readClipboard(ctx).trim()
            previousKeySnapshot = currentClip

            val directUrl = explicitUrl.trim()
                .ifBlank { if (isPlatoUrl(currentClip)) currentClip else "" }
            if (directUrl.isNotBlank()) {
                Rejoiner.saveConfig(ctx, Rejoiner.loadConfig(ctx).copy(deltaPlatoUrl = directUrl))
                inChromePhase = true
                updateStep("Chrome (Portrait): Scanning PlatoRelay...")
                openInFullChrome(directUrl, lockPortrait = true)
                mainHandler.postDelayed(keyWatchRunnable, 1800L)
                return@Thread
            }

            val testSlot = Rejoiner.pickBestTestClone(ctx)
            if (testSlot == null) {
                finishFlow(false, "Skipped Delta Baseplate test — no logged-in Roblox accounts found on this device.")
                return@Thread
            }

            activeTestPkg = testSlot.pkg
            val testStartMs = System.currentTimeMillis()
            updateStep("Launching ${testSlot.username} into Baseplate ($BASEPLATE_PLACE_ID)...")
            val launched = Rejoiner.launchSingleCloneToPlace(ctx, testSlot.pkg, BASEPLATE_PLACE_ID)
            if (!launched) {
                finishFlow(false, "Aborted Baseplate test — `${testSlot.pkg}` has no logged-in account.")
                return@Thread
            }

            val bootDeadline = testStartMs + 11_000L
            while (running.get() && System.currentTimeMillis() < bootDeadline) {
                Thread.sleep(1500L)
                refreshOverlayText()
                if (Rejoiner.lastAnyBridgePingMs > testStartMs) {
                    val pingUser = Rejoiner.lastPingUser.ifBlank { testSlot.username }
                    updateStep("Baseplate script executed ($pingUser) — Delta key is STILL VALID!")
                    if (Rejoiner.isBotActive()) {
                        Rejoiner.killAllAndStaggeredRejoin(ctx, reason = "Delta key verified on Baseplate")
                    }
                    finishFlow(true, "Baseplate test passed ($pingUser executed script) — Delta key is still valid!")
                    return@Thread
                }
            }
            if (!running.get()) return@Thread

            updateStep("Tapping 'Receive Key' on ${testSlot.username}...")
            tapDeltaReceiveKeyInWindow(testSlot.pkg)
            Thread.sleep(1500L)
            inChromePhase = true
            updateStep("Chrome (Portrait): Scanning PlatoRelay...")
            mainHandler.postDelayed(keyWatchRunnable, 1500L)
        }.start()
        return true
    }

    fun applyManualKey(ctx: Context, rawInput: String): Pair<Boolean, String> {
        val trimmed = rawInput.trim()
        if (trimmed.isEmpty()) return false to "Clipboard/input is empty."

        if (isPlatoUrl(trimmed)) {
            cancel()
            val updated = Rejoiner.loadConfig(ctx).copy(deltaPlatoUrl = trimmed)
            Rejoiner.saveConfig(ctx, updated)
            startDeltaRenewal(ctx, explicitUrl = trimmed)
            return true to "Saved PlatoRelay URL and started Chrome task runner!"
        }

        val deltaKey = extractDeltaKey(trimmed)
        if (deltaKey != null) {
            cancel()
            appCtx = ctx.applicationContext
            if (running.compareAndSet(false, true)) {
                applyDeltaKeyInternal(ctx.applicationContext, deltaKey)
            }
            return true to "Applied Delta key `$deltaKey`."
        }

        val nousigiKey = trimmed.takeIf { it.length >= 8 && !it.contains(" ") }
            ?: return false to "Could not recognize a valid key or PlatoRelay link."

        val (ok, msg) = updateNousigiKeyEverywhere(ctx, nousigiKey)
        if (ok && Rejoiner.isBotActive()) {
            Thread { Rejoiner.killAllAndStaggeredRejoin(ctx, reason = "Nousigi key updated") }.start()
        }
        return ok to msg
    }

    private fun applyDeltaKeyInternal(ctx: Context, key: String) {
        Thread {
            updateStep("Detected Delta key! Applying to clipboard, cache & active clones...")
            writeClipboard(ctx, key)
            val cached = overwriteDeltaKeyFile(ctx, key)
            if (Rejoiner.isBotActive()) {
                Rejoiner.killAllAndStaggeredRejoin(ctx, reason = "Delta key renewed")
            }
            val extra = if (cached != null) " + updated `$cached`" else ""
            finishFlow(true, "Delta key `$key` applied (Clipboard$extra).")
        }.start()
    }

    private fun openInFullChrome(url: String, lockPortrait: Boolean = true) {
        if (lockPortrait) forcePortraitOrientation()
        val safeUrl = url.replace("'", "%27")
        val res = SuShell.exec(
            "am start -a android.intent.action.VIEW -d '$safeUrl' -p com.android.chrome >/dev/null 2>&1 && echo CHROME_OK"
        ).trim()
        if (res != "CHROME_OK") {
            SuShell.exec("am start -a android.intent.action.VIEW -d '$safeUrl' >/dev/null 2>&1")
        }
    }

    private fun extractDeltaKey(text: String): String? {
        val clean = text.trim()
        if (DELTA_KEY_RX.matches(clean)) return clean
        return DELTA_KEY_SEARCH_RX.find(clean)?.value
    }

    fun readClipboard(ctx: Context): String {
        var result = ""
        val latch = CountDownLatch(1)
        mainHandler.post {
            runCatching {
                val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                result = cm.primaryClip?.getItemAt(0)?.coerceToText(ctx)?.toString().orEmpty()
            }
            latch.countDown()
        }
        latch.await(2, TimeUnit.SECONDS)
        return result
    }

    private fun writeClipboard(ctx: Context, value: String) {
        mainHandler.post {
            runCatching {
                val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText("PilotKey", value))
            }
        }
    }

    private fun overwriteDeltaKeyFile(ctx: Context, key: String): String? {
        val existing = SuShell.exec("find '/storage/emulated/0/Delta/Internals' -maxdepth 3 -type f -iname '*key*' 2>/dev/null | head -n 1").trim()
        if (existing.isBlank()) return null
        val tmp = File(ctx.cacheDir, "delta_key_write_tmp")
        FileOutputStream(tmp).use { it.write(key.toByteArray(Charsets.UTF_8)) }
        SuShell.exec("cp '${tmp.absolutePath}' '$existing' && chmod 666 '$existing'")
        tmp.delete()
        return existing
    }

    private fun finishFlow(ok: Boolean, msg: String) {
        if (!running.compareAndSet(true, false)) {
            removeFloatingOverlay()
            restoreOrientation()
            return
        }
        inChromePhase = false
        activeTestPkg = ""
        waitingForTaskTimer = false
        onSponsorTabSinceMs = 0L
        currentStep = if (ok) "Completed" else "Failed"
        mainHandler.removeCallbacks(keyWatchRunnable)
        removeFloatingOverlay()
        restoreOrientation()
        PilotCore.log("[KEYAUTO] ${if (ok) "SUCCESS" else "FAIL"}: $msg")
        PilotCore.onAlert?.invoke(if (ok) "🔑 Key Status" else "⚠️ Key Watcher Alert", msg)
        val cb = completionCb
        completionCb = null
        cb?.invoke(ok, msg)
    }
}