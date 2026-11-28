package com.pilot.bot

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Rect
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
import kotlin.math.max
import kotlin.math.min

data class KeyCheckReport(
    val title: String,
    val summary: String,
    val keyFound: String,
    val details: String,
    val isHealthy: Boolean
)

/**
 * Delta Key Baseplate Tester + Fixed-Slot Clone Snapper & Verified Receive-Key -> Chrome Launcher
 * + 10s Countdown-Safe, Ad-Bypassing PlatoRelay & Nousigi Solver
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
    @Volatile private var platoReadyAtMs = 0L
    @Volatile private var nousigiPortalUrl = "https://getkey.nousigi.com/"
    @Volatile private var collectedNousigiKeys = mutableListOf<String>()
    @Volatile private var appCtx: Context? = null
    @Volatile private var completionCb: ((Boolean, String) -> Unit)? = null

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
        Thread { openInFullChrome(url) }.start()
    }

    fun cancel() {
        if (!running.compareAndSet(true, false)) {
            removeFloatingOverlay()
            return
        }
        inChromePhase = false
        activeTestPkg = ""
        waitingForTaskTimer = false
        onSponsorTabSinceMs = 0L
        platoReadyAtMs = 0L
        currentStep = "Cancelled"
        mainHandler.removeCallbacks(keyWatchRunnable)
        completionCb = null
        removeFloatingOverlay()
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

    private fun isChromeForeground(): Boolean {
        val focus = SuShell.exec("dumpsys window windows 2>/dev/null | grep -E 'mCurrentFocus|mFocusedApp' | head -n 3")
        return focus.contains("com.android.chrome")
    }

    private fun snapCloneToConsistentSpot(pkg: String): Rect? {
        val (rawW, rawH) = PilotCore.getDisplaySize()
        val w = max(rawW, rawH)
        val h = min(rawW, rawH)

        val left = (w * 0.11f).toInt()
        val top = (h * 0.08f).toInt()
        val right = (w * 0.89f).toInt()
        val bottom = (h * 0.92f).toInt()

        val taskId = PilotCore.findTaskId(pkg)
        if (taskId != null) {
            SuShell.exec(
                "am task set-windowing-mode $taskId 5 >/dev/null 2>&1; " +
                "am task resize $taskId $left $top $right $bottom >/dev/null 2>&1"
            )
            runCatching { Thread.sleep(550L) }
            val actual = PilotCore.findOuterWindowBounds(pkg)
            if (actual != null && actual.width() > 300) {
                PilotCore.log("[KEYAUTO] Snapped $pkg (task #$taskId) to fixed spot $actual")
                return actual
            }
        }
        return PilotCore.findOuterWindowBounds(pkg) ?: Rect(left, top, right, bottom)
    }

    private fun ensureReceiveKeyOpensChrome(pkg: String, username: String): Boolean {
        if (pkg.isBlank()) return false

        for (attempt in 1..4) {
            if (!running.get()) return false
            updateStep("Snapping $username & tapping 'Receive Key' ($attempt/4)...")

            SuShell.exec("monkey -p $pkg -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1")
            runCatching { Thread.sleep(900L) }

            val box = snapCloneToConsistentSpot(pkg) ?: continue
            val titleBarH = 38
            val contentTop = box.top + titleBarH
            val contentW = (box.right - box.left).coerceAtLeast(300)
            val contentH = (box.bottom - contentTop).coerceAtLeast(200)

            val tapX = box.left + (contentW * 0.835f).toInt()
            val tapY1 = contentTop + (contentH * 0.545f).toInt()
            val tapY2 = contentTop + (contentH * 0.585f).toInt()

            SuShell.exec("input tap $tapX $tapY1")
            PilotCore.log("[KEYAUTO] Tapped 'Receive Key' at ($tapX, $tapY1) inside locked box $box (attempt $attempt)")
            runCatching { Thread.sleep(450L) }
            SuShell.exec("input tap $tapX $tapY2")

            val checkDeadline = System.currentTimeMillis() + 3500L
            while (running.get() && System.currentTimeMillis() < checkDeadline) {
                runCatching { Thread.sleep(500L) }
                if (isChromeForeground()) {
                    // Give PlatoRelay's 5s countdown 10s to finish before tapping Continue
                    platoReadyAtMs = System.currentTimeMillis() + 10_000L
                    PilotCore.log("[KEYAUTO] Confirmed Chrome opened — waiting 10s for PlatoRelay countdown!")
                    return true
                }
            }
            PilotCore.log("[KEYAUTO] Chrome did not open yet — re-snapping $pkg & retrying...")
        }
        return isChromeForeground()
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

    /**
     * Orientation-agnostic pixel detector (works in BOTH Landscape 1280x720 and Portrait 720x1280):
     * Scans 3 center columns (42%, 50%, 58% width) of a screencap PNG for PlatoRelay's bright white
     * 'Continue' button (R,G,B > 195) inside PlatoRelay's dark background. Ignores green VPN ads, red Watch tutorial, and blue Visit shop.
     */
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

            val leftMarginPx = bmp.getPixel((w * 0.06f).toInt(), (h * 0.55f).toInt())
            val rightMarginPx = bmp.getPixel((w * 0.94f).toInt(), (h * 0.55f).toInt())
            val isDarkBg = Color.red(leftMarginPx) < 40 && Color.green(leftMarginPx) < 40 &&
                Color.red(rightMarginPx) < 40 && Color.green(rightMarginPx) < 40
            if (!isDarkBg) {
                bmp.recycle()
                return null
            }

            val yStart = (h * 0.30f).toInt()
            val yEnd = (h * 0.95f).toInt()
            var runStart = -1
            var foundCenterY = -1

            for (y in yStart..yEnd) {
                val p = bmp.getPixel(cx, y)
                // Accept bright white or slightly dimmed countdown-complete white (R,G,B > 195)
                val isBright = Color.red(p) > 195 && Color.green(p) > 195 && Color.blue(p) > 195
                if (isBright) {
                    if (runStart < 0) runStart = y
                } else {
                    if (runStart >= 0) {
                        val bandH = y - runStart
                        if (bandH in 20..120) {
                            val midY = (runStart + y) / 2
                            val leftSide = bmp.getPixel((w * 0.42f).toInt(), midY)
                            val rightSide = bmp.getPixel((w * 0.58f).toInt(), midY)
                            if (Color.red(leftSide) > 190 && Color.red(rightSide) > 190) {
                                foundCenterY = midY
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

    /**
     * Closes only the active pop-up/ad tab (Ctrl+W) WITHOUT reloading the PlatoRelay URL
     * (reloading resets PlatoRelay's 5s countdown!), then waits 10 seconds before tapping Continue again.
     */
    private fun closeChromeTabAndWaitCountdown(ctx: Context, stepLabel: String) {
        onSponsorTabSinceMs = 0L
        waitingForTaskTimer = false
        lastWebTapSig = ""
        updateStep(stepLabel)

        // Ctrl+W closes only the pop-up/ad tab and reveals the existing PlatoRelay tab underneath intact
        SuShell.exec("input keyevent --ctrl 51")
        Thread.sleep(750L)

        if (!isChromeForeground()) {
            SuShell.exec("monkey -p com.android.chrome -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1")
            Thread.sleep(700L)
        }

        // Wait 10s after closing ad/task tab so PlatoRelay's 5s countdown finishes completely before clicking Continue
        platoReadyAtMs = System.currentTimeMillis() + 10_000L
    }

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
            openInFullChrome(nousigiPortalUrl)
            return
        }

        if (isFakeAdTrap(combined)) {
            closeChromeTabAndWaitCountdown(ctx, "Blocked ad redirect -> closing tab...")
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

        val isChromeInFront = focusInfo.contains("com.android.chrome") || xml.contains("package=\"com.android.chrome\"") || xml.contains("url_bar")

        // If Chrome is NOT in front, re-snap Roblox and tap Receive Key
        if (!isChromeInFront && activeTestPkg.isNotEmpty()) {
            val slotUser = Rejoiner.slots[activeTestPkg]?.username?.ifBlank { "slot" } ?: "slot"
            ensureReceiveKeyOpensChrome(activeTestPkg, slotUser)
            return null
        }

        // 1. Always check if KEY_... is already visible on screen
        val directKey = DELTA_KEY_SEARCH_RX.find(combinedContext)?.value
        if (directKey != null && directKey != previousKeySnapshot) {
            onSponsorTabSinceMs = 0L
            waitingForTaskTimer = false
            updateStep("Scraped $directKey directly from page!")
            return directKey
        }

        // 2. Immediately detect and close fake pop-up ad traps (e.g. vpnapptoggle.com/?pbt=Continue)
        if (isFakeAdTrap(combinedContext)) {
            closeChromeTabAndWaitCountdown(ctx, "Blocked fake ad -> closing & waiting 10s...")
            return null
        }

        val isOnPlatoHost = combinedContext.contains("platorelay.com", ignoreCase = true) ||
            combinedContext.contains("platoboost.com", ignoreCase = true) ||
            combinedContext.contains("auth.platorelay", ignoreCase = true) ||
            combinedContext.contains("Delta Android Keysystem", ignoreCase = true)

        // 3. Handle non-PlatoRelay tabs (pop-up ads vs real tasks)
        if (isChromeInFront && !isOnPlatoHost && tabStrings.isNotBlank()) {
            if (!waitingForTaskTimer) {
                closeChromeTabAndWaitCountdown(ctx, "Pop-up ad tab closed -> waiting 10s...")
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
            closeChromeTabAndWaitCountdown(ctx, "Task #$tasksCompletedCount done -> waiting 10s...")
            return null
        }

        // Back on PlatoRelay
        onSponsorTabSinceMs = 0L
        waitingForTaskTimer = false

        // Initialize 10s countdown wait on first arrival if not yet set
        if (platoReadyAtMs == 0L) {
            platoReadyAtMs = now + 10_000L
        }

        // Enforce 10-second countdown wait before clicking Continue so we never click during the 5s button timer
        if (now < platoReadyAtMs) {
            val remSec = ((platoReadyAtMs - now) / 1000L) + 1L
            updateStep("PlatoRelay: Waiting ${remSec}s for 5s countdown...")
            return null
        }

        // Save PlatoRelay URL from Chrome's address bar if visible
        val urlMatch = Regex("""https?://(?:auth\.platorelay\.com|gateway\.platoboost\.com)/[^\s"<']+""").find(combinedContext)
        if (urlMatch != null) {
            val foundUrl = urlMatch.value
            val savedPlato = Rejoiner.loadConfig(ctx).deltaPlatoUrl.trim()
            if (savedPlato != foundUrl) {
                Rejoiner.saveConfig(ctx, Rejoiner.loadConfig(ctx).copy(deltaPlatoUrl = foundUrl))
            }
        }

        // Parse XML nodes if dump succeeded
        data class UiNode(val label: String, val cx: Int, val cy: Int, val enabled: Boolean)
        val nodes = mutableListOf<UiNode>()
        if (xml.isNotBlank()) {
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
        }

        // Priority 1: Copy Key / Create Key / Get Key
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

        // Priority 2: Chrome first-run dialogs
        val chromePrompts = listOf("accept & continue", "use without an account", "no thanks", "got it")
        for (pat in chromePrompts) {
            val hit = nodes.firstOrNull { n -> n.label.lowercase().contains(pat) && n.cy > 90 }
            if (hit != null) {
                tapNodeIfReady(hit.label, hit.cx, hit.cy, "Chrome: Dismissing '${hit.label.take(24)}'...")
                return null
            }
        }

        // Priority 3: Uncompleted Task buttons (Complete [not Completed], Visit, Read, Watch, Discover, Open, Start)
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

        // Priority 4: Cloudflare Turnstile / Human Verification + Continue / Proceed / Unlock
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

        // Priority 5: Pixel-detected white Continue button on PlatoRelay dark page
        val whiteBtn = findWhiteContinueButtonByPixels(ctx)
        if (whiteBtn != null) {
            waitingForTaskTimer = false
            tapNodeIfReady("white_continue_px", whiteBtn.first, whiteBtn.second, "Chrome: Tapping white Continue (${whiteBtn.first}, ${whiteBtn.second})...")
            return null
        }

        // Priority 6: Guaranteed center-card Continue tap once 10s wait has elapsed on PlatoRelay (never gets stuck scanning)
        val (w, h) = PilotCore.getDisplaySize()
        val tapX = w / 2
        val tapY = if (w > h) (h * 0.785f).toInt() else (h * 0.640f).toInt()
        waitingForTaskTimer = false
        tapNodeIfReady("plato_center_continue", tapX, tapY, "Chrome: Tapping Continue ($tapX, $tapY)...")
        return null
    }

    private fun tapNodeIfReady(label: String, cx: Int, cy: Int, stepMsg: String) {
        val now = System.currentTimeMillis()
        val sig = "${label.lowercase()}@$cx,$cy"
        if (sig != lastWebTapSig || (now - lastWebTapMs) >= 4000L) {
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

            val timeoutMs = if (mode == "NOUSIGI_HOST") 300_000L else 180_000L
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
                    mainHandler.postDelayed(this, 1600L)
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
        platoReadyAtMs = 0L
        startedAtMs = System.currentTimeMillis()
        showFloatingOverlay(ctx.applicationContext)

        Thread {
            previousKeySnapshot = readClipboard(ctx).trim()
            updateStep("Opening Nousigi Key Portal (${collectedNousigiKeys.size}/5)...")
            openInFullChrome(nousigiPortalUrl)
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
        platoReadyAtMs = 0L
        completionCb = onDone
        startedAtMs = System.currentTimeMillis()
        showFloatingOverlay(ctx.applicationContext)

        Thread {
            val currentClip = readClipboard(ctx).trim()
            previousKeySnapshot = currentClip

            val directUrl = explicitUrl.trim()
                .ifBlank { if (isPlatoUrl(currentClip)) currentClip else "" }
            if (directUrl.isNotBlank()) {
                Rejoiner.saveConfig(ctx, Rejoiner.loadConfig(ctx).copy(deltaPlatoUrl = directUrl))
                inChromePhase = true
                platoReadyAtMs = System.currentTimeMillis() + 10_000L
                updateStep("PlatoRelay: Waiting 10s for 5s countdown...")
                openInFullChrome(directUrl)
                mainHandler.postDelayed(keyWatchRunnable, 1600L)
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

            ensureReceiveKeyOpensChrome(testSlot.pkg, testSlot.username)
            inChromePhase = true
            if (platoReadyAtMs == 0L) {
                platoReadyAtMs = System.currentTimeMillis() + 10_000L
            }
            updateStep("PlatoRelay: Waiting 10s for 5s countdown...")
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

    private fun openInFullChrome(url: String) {
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
            return
        }
        inChromePhase = false
        activeTestPkg = ""
        waitingForTaskTimer = false
        onSponsorTabSinceMs = 0L
        platoReadyAtMs = 0L
        currentStep = if (ok) "Completed" else "Failed"
        mainHandler.removeCallbacks(keyWatchRunnable)
        removeFloatingOverlay()
        PilotCore.log("[KEYAUTO] ${if (ok) "SUCCESS" else "FAIL"}: $msg")
        PilotCore.onAlert?.invoke(if (ok) "🔑 Key Status" else "⚠️ Key Watcher Alert", msg)
        val cb = completionCb
        completionCb = null
        cb?.invoke(ok, msg)
    }
}