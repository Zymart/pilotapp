package com.pilot.bot

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Handler
import android.os.Looper
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
 * Full-Chrome Key Helper + Baseplate Executor Tester + Nousigi/Delta Key Inspector & Autoexecute Updater
 */
object KeyAutomator {
    enum class Mode { NOUSIGI_LUARMOR, DELTA_PLATO }

    // Baseplate place ID used to verify whether Delta autoexecutes scripts
    const val BASEPLATE_PLACE_ID = "123974602339071"

    private val mainHandler = Handler(Looper.getMainLooper())
    private val running = AtomicBoolean(false)

    @Volatile private var currentMode: Mode? = null
    @Volatile private var waitingForPlatoLink = false
    @Volatile private var startedAtMs = 0L
    @Volatile private var currentStep = "Idle"
    @Volatile private var lastLoggedStep = ""
    @Volatile private var previousKeySnapshot = ""
    @Volatile private var appCtx: Context? = null
    @Volatile private var completionCb: ((Boolean, String) -> Unit)? = null

    private val LUARMOR_KEY_RX = Regex("""^[A-Za-z0-9]{32}$""")
    private val DELTA_KEY_RX = Regex("""^KEY_[A-Za-z0-9_-]{12,64}$""")
    private val dateFmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)

    fun isRunning(): Boolean = running.get()

    fun getLiveStatusText(): String {
        if (!running.get()) return "IDLE ($currentStep)"
        val sec = ((System.currentTimeMillis() - startedAtMs) / 1000L).coerceAtLeast(0L)
        return "ACTIVE (${sec}s) — $currentStep"
    }

    /** Removes any .bak files left behind in Delta folders. */
    fun cleanBakFiles(): Int {
        val countStr = SuShell.exec(
            "cnt=\$(find /storage/emulated/0/Delta -type f -iname '*.bak' 2>/dev/null | wc -l); " +
            "find /storage/emulated/0/Delta -type f -iname '*.bak' -delete 2>/dev/null; " +
            "echo \$cnt"
        ).trim()
        return countStr.toIntOrNull() ?: 0
    }

    /** Finds the existing key file in Delta/Workspace/Nousigi Hub (or custom saved path). */
    fun detectExistingKeyFile(ctx: Context? = null): String {
        val saved = ctx?.let { Rejoiner.loadConfig(it).nousigiKeyPath.trim() }.orEmpty()
        if (saved.isNotEmpty() && SuShell.exec("[ -f '$saved' ] && echo OK").trim() == "OK") {
            return saved
        }
        return SuShell.exec(
            "find '/storage/emulated/0/Delta/Workspace/Nousigi Hub' '/storage/emulated/0/Delta/Workspace' -maxdepth 3 -type f -iname '*key*' 2>/dev/null | head -n 1"
        ).trim()
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
            val m = Regex("""\bKEY_[A-Za-z0-9_-]{12,64}\b""").find(content)
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

    private fun readCurrentFileContent(path: String): String {
        if (path.isBlank()) return ""
        return SuShell.exec("cat '$path' 2>/dev/null").trim()
    }

    private fun updateStep(step: String) {
        currentStep = step
        if (step != lastLoggedStep) {
            lastLoggedStep = step
            PilotCore.log("[KEYAUTO] $step")
        }
    }

    private fun isPlatoUrl(text: String): Boolean {
        val t = text.trim().lowercase()
        return t.startsWith("http") && (t.contains("platorelay.com") || t.contains("platoboost"))
    }

    private val clipboardWatchRunnable = object : Runnable {
        override fun run() {
            if (!running.get()) return
            if (System.currentTimeMillis() - startedAtMs > 300_000L) {
                finishFlow(false, "Timed out after 5m (last step: $currentStep).")
                return
            }

            val ctx = appCtx
            if (ctx != null) {
                val clip = readClipboard(ctx).trim()

                if (waitingForPlatoLink) {
                    if (isPlatoUrl(clip)) {
                        waitingForPlatoLink = false
                        previousKeySnapshot = clip
                        val updated = Rejoiner.loadConfig(ctx).copy(deltaPlatoUrl = clip)
                        Rejoiner.saveConfig(ctx, updated)
                        updateStep("Caught & saved PlatoRelay URL! Opening Chrome — copy KEY_ when done...")
                        Thread { openInFullChrome(clip) }.start()
                    }
                    mainHandler.postDelayed(this, 1500L)
                    return
                }

                if (clip.isNotEmpty() && clip != previousKeySnapshot) {
                    when (currentMode) {
                        Mode.NOUSIGI_LUARMOR -> {
                            val token = extractLuarmorKey(clip)
                            if (token != null && token != previousKeySnapshot) {
                                applyNousigiKeyInternal(ctx, token)
                                return
                            }
                        }
                        Mode.DELTA_PLATO -> {
                            val token = extractDeltaKey(clip)
                            if (token != null && token != previousKeySnapshot) {
                                applyDeltaKeyInternal(ctx, token)
                                return
                            }
                        }
                        null -> Unit
                    }
                }
            }

            mainHandler.postDelayed(this, 1800L)
        }
    }

    fun startNousigiRenewal(
        ctx: Context,
        blacklistedReset: Boolean = false,
        onDone: ((Boolean, String) -> Unit)? = null
    ): Boolean {
        val cfg = Rejoiner.loadConfig(ctx)
        if (cfg.luarmorUrl.isBlank()) {
            onDone?.invoke(false, "Luarmor URL is empty in Rejoiner Settings.")
            return false
        }
        if (!running.compareAndSet(false, true)) {
            PilotCore.log("[KEYAUTO] Already waiting for key — opening Chrome URL again...")
            Thread { openInFullChrome(cfg.luarmorUrl) }.start()
            return false
        }

        appCtx = ctx.applicationContext
        currentMode = Mode.NOUSIGI_LUARMOR
        waitingForPlatoLink = false
        lastLoggedStep = ""
        completionCb = onDone
        startedAtMs = System.currentTimeMillis()

        val existingPath = detectExistingKeyFile(ctx)
        previousKeySnapshot = readCurrentFileContent(existingPath)

        if (previousKeySnapshot.isNotEmpty() && readClipboard(ctx).trim() == previousKeySnapshot) {
            writeClipboard(ctx, "")
        }

        updateStep("Opened Luarmor in Chrome — complete check & tap Copy on your key...")
        Thread { openInFullChrome(cfg.luarmorUrl) }.start()
        mainHandler.postDelayed(clipboardWatchRunnable, 2000L)
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
        appCtx = ctx.applicationContext
        currentMode = Mode.DELTA_PLATO
        waitingForPlatoLink = false
        lastLoggedStep = ""
        completionCb = onDone
        startedAtMs = System.currentTimeMillis()

        Thread {
            val testSlot = Rejoiner.pickBestTestClone()
            if (testSlot != null) {
                val testStartMs = System.currentTimeMillis()
                updateStep("Launching ${testSlot.pkg.removePrefix("com.roblox.")} into Baseplate ($BASEPLATE_PLACE_ID) to test Delta key...")
                Rejoiner.launchSingleCloneToPlace(ctx, testSlot.pkg, BASEPLATE_PLACE_ID)

                val testDeadline = testStartMs + 28_000L
                while (running.get() && System.currentTimeMillis() < testDeadline) {
                    Thread.sleep(1500L)
                    if (Rejoiner.lastAnyBridgePingMs > testStartMs) {
                        val pingUser = Rejoiner.lastPingUser.ifBlank { testSlot.pkg }
                        updateStep("Baseplate script executed ($pingUser) — Delta key is STILL VALID!")
                        Rejoiner.killAllAndStaggeredRejoin(ctx, reason = "Delta key verified on Baseplate")
                        finishFlow(true, "Baseplate test passed ($pingUser executed script) — Delta key is still valid! Returning to main game.")
                        return@Thread
                    }
                }
                if (!running.get()) return@Thread
                updateStep("No script ping on Baseplate after 28s — Delta key expired/missing.")
            }

            val currentClip = readClipboard(ctx).trim()
            previousKeySnapshot = currentClip

            var url = explicitUrl.trim()
                .ifBlank { Rejoiner.loadConfig(ctx).deltaPlatoUrl.trim() }
                .ifBlank { if (isPlatoUrl(currentClip)) currentClip else "" }
                .ifBlank { scanDeltaFilesForPlatoUrl() }

            if (url.isNotBlank()) {
                if (Rejoiner.loadConfig(ctx).deltaPlatoUrl.trim() != url) {
                    val updated = Rejoiner.loadConfig(ctx).copy(deltaPlatoUrl = url)
                    Rejoiner.saveConfig(ctx, updated)
                    PilotCore.log("[KEYAUTO] Saved PlatoRelay URL for this UGPhone")
                }
                previousKeySnapshot = url
                updateStep("Opened PlatoRelay in Chrome — complete steps & copy KEY_...")
                openInFullChrome(url)
                mainHandler.postDelayed(clipboardWatchRunnable, 2000L)
                return@Thread
            }

            waitingForPlatoLink = true
            updateStep("Baseplate ($BASEPLATE_PLACE_ID) is open — tap 'Receive Key' in Delta now (auto-catching link)...")
            mainHandler.postDelayed(clipboardWatchRunnable, 1500L)
        }.start()
        return true
    }

    private fun scanDeltaFilesForPlatoUrl(): String {
        val found = SuShell.exec(
            "grep -rhoE 'https?://(auth\\.platorelay\\.com|gateway\\.platoboost\\.com)/[^[:space:]\"'\\''<>]+' /storage/emulated/0/Delta /data/data/com.roblox.clien*/files 2>/dev/null | head -n 1"
        ).trim()
        return if (isPlatoUrl(found)) found else ""
    }

    fun applyManualKey(ctx: Context, rawInput: String): Pair<Boolean, String> {
        val trimmed = rawInput.trim()
        if (trimmed.isEmpty()) return false to "Clipboard/input is empty."

        if (isPlatoUrl(trimmed)) {
            val updated = Rejoiner.loadConfig(ctx).copy(deltaPlatoUrl = trimmed)
            Rejoiner.saveConfig(ctx, updated)
            startDeltaRenewal(ctx, explicitUrl = trimmed)
            return true to "Saved PlatoRelay URL and running Baseplate/Chrome flow!"
        }

        val deltaKey = extractDeltaKey(trimmed)
        if (deltaKey != null) {
            appCtx = ctx.applicationContext
            if (running.compareAndSet(false, true)) {
                applyDeltaKeyInternal(ctx.applicationContext, deltaKey)
            } else {
                Thread {
                    writeClipboard(ctx, deltaKey)
                    val cached = overwriteExistingKeyFile(null, "/storage/emulated/0/Delta/Internals", deltaKey, "")
                    Rejoiner.killAllAndStaggeredRejoin(ctx, reason = "Delta key applied")
                    PilotCore.log("[KEYAUTO] Manual Delta key applied ($deltaKey, cached=$cached)")
                }.start()
            }
            return true to "Applied Delta key `$deltaKey` and restarting clones."
        }

        val luarmorKey = extractLuarmorKey(trimmed) ?: trimmed.takeIf { it.length >= 8 && !it.contains(" ") }
            ?: return false to "Could not recognize a valid key or PlatoRelay link."

        val (ok, msg) = updateNousigiKeyEverywhere(ctx, luarmorKey)
        if (ok) {
            Thread { Rejoiner.killAllAndStaggeredRejoin(ctx, reason = "Nousigi key updated") }.start()
        }
        return ok to msg
    }

    private fun applyNousigiKeyInternal(ctx: Context, key: String) {
        Thread {
            updateStep("Detected key! Updating Autoexecute & Nousigi Hub...")
            val (_, msg) = updateNousigiKeyEverywhere(ctx, key)
            Rejoiner.killAllAndStaggeredRejoin(ctx, reason = "Nousigi key updated")
            finishFlow(true, msg)
        }.start()
    }

    private fun applyDeltaKeyInternal(ctx: Context, key: String) {
        Thread {
            updateStep("Detected Delta key! Applying to clipboard, cache & clones...")
            writeClipboard(ctx, key)
            val cached = overwriteExistingKeyFile(null, "/storage/emulated/0/Delta/Internals", key, preferSubdir = "")
            Rejoiner.killAllAndStaggeredRejoin(ctx, reason = "Delta key renewed")
            val extra = if (cached != null) " + updated `$cached`" else ""
            finishFlow(true, "Delta key `$key` applied (Clipboard$extra). All clones restarting.")
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

    private fun extractLuarmorKey(text: String): String? {
        val clean = text.trim()
        if (LUARMOR_KEY_RX.matches(clean)) return clean
        return Regex("""\b[A-Za-z0-9]{32}\b""").find(clean)?.value
    }

    private fun extractDeltaKey(text: String): String? {
        val clean = text.trim()
        if (DELTA_KEY_RX.matches(clean)) return clean
        return Regex("""\bKEY_[A-Za-z0-9_-]{12,64}\b""").find(clean)?.value
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

    private fun overwriteExistingKeyFile(ctx: Context?, baseDir: String, key: String, preferSubdir: String): String? {
        val explicitPath = ctx?.let { Rejoiner.loadConfig(it).nousigiKeyPath.trim() }.orEmpty()
        val existing = if (explicitPath.isNotEmpty() && SuShell.exec("[ -f '$explicitPath' ] && echo OK").trim() == "OK") {
            explicitPath
        } else {
            val searchFirst = if (preferSubdir.isNotBlank()) "'$baseDir/$preferSubdir' '$baseDir'" else "'$baseDir'"
            SuShell.exec("find $searchFirst -maxdepth 3 -type f -iname '*key*' 2>/dev/null | head -n 1").trim()
        }
        if (existing.isBlank()) return null

        val c = appCtx ?: ctx ?: return null
        val tmp = File(c.cacheDir, "key_write_tmp")
        FileOutputStream(tmp).use { it.write(key.toByteArray(Charsets.UTF_8)) }
        SuShell.exec("cp '${tmp.absolutePath}' '$existing' && chmod 666 '$existing'")
        tmp.delete()
        return existing
    }

    private fun finishFlow(ok: Boolean, msg: String) {
        if (!running.compareAndSet(true, false)) return
        waitingForPlatoLink = false
        currentStep = if (ok) "Completed" else "Failed"
        mainHandler.removeCallbacks(clipboardWatchRunnable)
        PilotCore.log("[KEYAUTO] ${if (ok) "SUCCESS" else "FAIL"}: $msg")
        PilotCore.onAlert?.invoke(if (ok) "🔑 Key Status" else "⚠️ Key Watcher Alert", msg)
        val cb = completionCb
        completionCb = null
        cb?.invoke(ok, msg)
    }
}