package com.pilot.bot

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
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
 * Delta Key Baseplate Tester + Coordinate Receive-Key Tapper + Multi-Task PlatoRelay Auto-Completer
 * + Always-On-Top Floating Key Watcher Overlay (visible only when outside Pilot app)
 */
object KeyAutomator {
    const val BASEPLATE_PLACE_ID = "123974602339071"

    private val mainHandler = Handler(Looper.getMainLooper())
    private val running = AtomicBoolean(false)

    @Volatile var isAppInForeground: Boolean = false

    @Volatile private var inChromePhase = false
    @Volatile private var activeTestPkg = ""
    @Volatile private var startedAtMs = 0L
    @Volatile private var currentStep = "Idle"
    @Volatile private var lastLoggedStep = ""
    @Volatile private var previousKeySnapshot = ""
    @Volatile private var lastWebTapMs = 0L
    @Volatile private var lastWebTapSig = ""
    @Volatile private var tasksCompletedCount = 0
    @Volatile private var onSponsorTabSinceMs = 0L
    @Volatile private var appCtx: Context? = null
    @Volatile private var completionCb: ((Boolean, String) -> Unit)? = null

    // Floating Always-On-Top Watcher Overlay views
    private var overlayRoot: LinearLayout? = null
    private var overlayTitleTv: TextView? = null
    private var overlayStepTv: TextView? = null

    private val DELTA_KEY_RX = Regex("""^KEY_[A-Za-z0-9_-]{12,64}$""")
    private val DELTA_KEY_SEARCH_RX = Regex("""\bKEY_[A-Za-z0-9_-]{12,64}\b""")
    private val dateFmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)

    fun isRunning(): Boolean = running.get()

    /** Called by MainActivity.onResume / onPause so the floating overlay only shows when outside Pilot. */
    fun setAppForeground(inForeground: Boolean) {
        isAppInForeground = inForeground
        syncOverlayVisibility()
    }

    /** Immediately aborts any active Baseplate test or Chrome key watcher. */
    fun cancel() {
        if (!running.compareAndSet(true, false)) {
            removeFloatingOverlay()
            return
        }
        inChromePhase = false
        activeTestPkg = ""
        onSponsorTabSinceMs = 0L
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

    /** Deletes any .bak files left behind in Delta folders. */
    fun cleanBakFiles(): Int {
        val countStr = SuShell.exec(
            "cnt=\$(find /storage/emulated/0/Delta -type f -iname '*.bak' 2>/dev/null | wc -l); " +
            "find /storage/emulated/0/Delta -type f -iname '*.bak' -delete 2>/dev/null; " +
            "echo \$cnt"
        ).trim()
        return countStr.toIntOrNull() ?: 0
    }

    /** Inspects Delta/Autoexecute and Delta/Workspace/Nousigi Hub for the active Nousigi key. */
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

    /**
     * Updates the Nousigi key inside Delta/Autoexecute scripts AND Delta/Workspace/Nousigi Hub key files,
     * then deletes any .bak files that pop up during the update.
     */
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

    /** Inspects Delta's saved key in Delta/Internals and calculates its 24h elapsed/remaining time. */
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
                    maxWidth = dp(270)
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
            titleTv.text = "🔑 KEY WATCHER • ${sec}s (Tasks: $tasksCompletedCount)"
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

    /**
     * Taps Delta's 'Receive Key' button rendered inside Roblox's SurfaceView by computing its exact
     * position within the clone's outer window bounds (right-side 'Welcome Back!' panel, below 'Continue').
     */
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

    /**
     * Automates the full PlatoRelay / Platoboost flow in Chrome:
     * 1) If Roblox is still on screen, re-taps 'Receive Key'.
     * 2) If a task opened a new sponsor/ad tab (no longer on platorelay/platoboost), waits 11 seconds
     *    and presses BACK (KEYCODE_BACK) to close the sponsor tab and return to PlatoRelay.
     * 3) On PlatoRelay, scrapes KEY_... if visible, taps uncompleted task buttons (2-4 random tasks),
     *    taps Continue/Unlock once tasks finish, taps 'Verify you are human', and taps Copy Key.
     */
    private fun processChromePlatoRelayStep(ctx: Context): String? {
        SuShell.exec("uiautomator dump /sdcard/pilot_ui.xml >/dev/null 2>&1")
        val xml = SuShell.exec("cat /sdcard/pilot_ui.xml 2>/dev/null")
        if (xml.isBlank()) return null

        val now = System.currentTimeMillis()
        val isRobloxInFront = xml.contains("package=\"com.roblox.clien")
        val isChromeInFront = xml.contains("package=\"com.android.chrome\"") || xml.contains("url_bar")
        val isOnPlatoHost = xml.contains("platorelay.com", ignoreCase = true) ||
            xml.contains("platoboost.com", ignoreCase = true) ||
            xml.contains("auth.platorelay", ignoreCase = true)

        // 1. If Roblox is still in front and Chrome hasn't opened yet, re-tap Receive Key
        if (isRobloxInFront && !isChromeInFront && activeTestPkg.isNotEmpty()) {
            if (now - lastWebTapMs >= 3500L) {
                lastWebTapMs = now
                updateStep("Re-tapping 'Receive Key' in Delta...")
                tapDeltaReceiveKeyInWindow(activeTestPkg)
            }
            return null
        }

        // 2. Check if KEY_... is already displayed anywhere on screen
        val directKey = DELTA_KEY_SEARCH_RX.find(xml)?.value
        if (directKey != null && directKey != previousKeySnapshot) {
            onSponsorTabSinceMs = 0L
            updateStep("Scraped $directKey directly from page!")
            return directKey
        }

        // 3. Detect if we are currently on an opened sponsor/ad task tab (Chrome open, NOT on PlatoRelay)
        if (isChromeInFront && !isOnPlatoHost) {
            if (onSponsorTabSinceMs == 0L) {
                onSponsorTabSinceMs = now
            }
            val waitedSec = (now - onSponsorTabSinceMs) / 1000L
            if (waitedSec < 11L) {
                val rem = 11L - waitedSec
                updateStep("Task #${tasksCompletedCount + 1}: Waiting ${rem}s on sponsor tab...")
                return null
            }

            // 11 seconds elapsed -> close the sponsor tab via KEYCODE_BACK and return to PlatoRelay
            tasksCompletedCount += 1
            onSponsorTabSinceMs = 0L
            lastWebTapSig = ""
            updateStep("Task #$tasksCompletedCount done! Closing tab -> returning to PlatoRelay...")
            SuShell.exec("input keyevent 4")
            Thread.sleep(1400L)

            // Verify we returned to PlatoRelay; if an ad hijacked back-history, send BACK once more
            SuShell.exec("uiautomator dump /sdcard/pilot_ui.xml >/dev/null 2>&1")
            val checkXml = SuShell.exec("cat /sdcard/pilot_ui.xml 2>/dev/null")
            val backOnPlato = checkXml.contains("platorelay", ignoreCase = true) || checkXml.contains("platoboost", ignoreCase = true)
            if (!backOnPlato) {
                SuShell.exec("input keyevent 4")
                Thread.sleep(1000L)
                val savedUrl = Rejoiner.loadConfig(ctx).deltaPlatoUrl.trim()
                if (savedUrl.isNotBlank()) {
                    SuShell.exec("uiautomator dump /sdcard/pilot_ui.xml >/dev/null 2>&1")
                    val check2 = SuShell.exec("cat /sdcard/pilot_ui.xml 2>/dev/null")
                    if (!check2.contains("platorelay", ignoreCase = true) && !check2.contains("platoboost", ignoreCase = true)) {
                        openInFullChrome(savedUrl)
                    }
                }
            }
            return null
        }

        // Reset sponsor timer when back on PlatoRelay
        onSponsorTabSinceMs = 0L

        // Save PlatoRelay URL from Chrome's address bar if visible
        val urlMatch = Regex("""https?://(?:auth\.platorelay\.com|gateway\.platoboost\.com)/[^\s"<']+""").find(xml)
        if (urlMatch != null) {
            val foundUrl = urlMatch.value
            if (Rejoiner.loadConfig(ctx).deltaPlatoUrl.trim() != foundUrl) {
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

        // Priority 3: Uncompleted Task buttons (2-4 random tasks: Complete [not Completed], Visit, Read, Watch, Discover, Open, Start)
        val uncompletedTask = nodes.firstOrNull { n ->
            val low = n.label.lowercase()
            n.enabled && n.cy > 110 &&
                !low.contains("completed") &&
                !low.contains("done") &&
                !low.contains("verified") &&
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
                tapNodeIfReady(hit.label, hit.cx, hit.cy, "Chrome: Tapping '${hit.label.take(24)}'...")
                return null
            }
        }

        updateStep("Chrome: Scanning PlatoRelay page (Tasks done: $tasksCompletedCount)...")
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

            // 150s timeout to accommodate 2-4 random sponsor tasks (11s each) + Turnstile verification
            if (System.currentTimeMillis() - startedAtMs > 150_000L) {
                finishFlow(false, "Timed out after 150s (last step: $currentStep) — returning control to Rejoiner.")
                return
            }

            val ctx = appCtx
            if (ctx != null) {
                // Always check clipboard for a newly copied KEY_...
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

    /**
     * Tests Delta key on Baseplate (123974602339071) using ONLY a logged-in clone.
     * Taps 'Receive Key' inside Roblox (which opens Chrome automatically), completes 2-4 random tasks
     * (11s wait + close tab), taps Verify Human, and applies KEY_...
     */
    fun startDeltaRenewal(
        ctx: Context,
        explicitUrl: String = "",
        onDone: ((Boolean, String) -> Unit)? = null
    ): Boolean {
        if (!running.compareAndSet(false, true)) {
            PilotCore.log("[KEYAUTO] Already running ($currentStep)...")
            return false
        }
        appCtx = ctx.applicationContext
        inChromePhase = false
        activeTestPkg = ""
        lastLoggedStep = ""
        lastWebTapMs = 0L
        lastWebTapSig = ""
        tasksCompletedCount = 0
        onSponsorTabSinceMs = 0L
        completionCb = onDone
        startedAtMs = System.currentTimeMillis()
        showFloatingOverlay(ctx.applicationContext)

        Thread {
            val currentClip = readClipboard(ctx).trim()
            previousKeySnapshot = currentClip

            // If an explicit PlatoRelay URL was passed in (e.g. !getkey <url> or PASTE KEY), open Chrome directly
            val directUrl = explicitUrl.trim()
                .ifBlank { if (isPlatoUrl(currentClip)) currentClip else "" }
            if (directUrl.isNotBlank()) {
                Rejoiner.saveConfig(ctx, Rejoiner.loadConfig(ctx).copy(deltaPlatoUrl = directUrl))
                inChromePhase = true
                updateStep("Opening PlatoRelay in Chrome...")
                openInFullChrome(directUrl)
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

            // Tap 'Receive Key' inside Roblox; Delta opens Chrome automatically -> go straight to Chrome task loop
            updateStep("Tapping 'Receive Key' on ${testSlot.username}...")
            tapDeltaReceiveKeyInWindow(testSlot.pkg)
            Thread.sleep(1500L)
            inChromePhase = true
            updateStep("Chrome: Auto-completing PlatoRelay tasks...")
            mainHandler.postDelayed(keyWatchRunnable, 1500L)
        }.start()
        return true
    }

    /** Applies a pasted Delta key, PlatoRelay URL, or Nousigi key. */
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
        onSponsorTabSinceMs = 0L
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