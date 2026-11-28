package com.pilot.bot

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Handler
import android.os.Looper
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Full-Chrome Key Helper + Baseplate Executor Key Tester + Automatic Clipboard Watcher:
 * 1) Launches a clone into a lightweight Baseplate game (4483381587) to verify whether Delta still
 *    has a valid key by checking if autoexecute (pilot_bridge.lua) fires /ping.
 * 2) If Delta has no key, auto-catches the PlatoRelay URL when you tap "Receive Key" on Baseplate
 *    (or opens your saved URL in Google Chrome) and captures KEY_... when copied.
 * 3) Supports Nousigi (Luarmor) in Full Chrome and overwrites existing key files in place.
 */
object KeyAutomator {
    enum class Mode { NOUSIGI_LUARMOR, DELTA_PLATO }

    // Ultra-lightweight Roblox Baseplate place used to quickly verify if Delta autoexecutes scripts
    const val BASEPLATE_PLACE_ID = "4483381587"

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

    fun isRunning(): Boolean = running.get()

    fun getLiveStatusText(): String {
        if (!running.get()) return "IDLE ($currentStep)"
        val sec = ((System.currentTimeMillis() - startedAtMs) / 1000L).coerceAtLeast(0L)
        return "ACTIVE (${sec}s) — $currentStep"
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

                // Stage 1 (Delta only, first time on this UGPhone): catch PlatoRelay URL when user taps "Receive Key"
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

                // Stage 2: watch clipboard for the finished key
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

    /** Opens Luarmor in Full Chrome and auto-captures the 32-char key as soon as you tap Copy. */
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

    /**
     * Launches a clone into Baseplate (4483381587) first to test if Delta still has a valid key
     * (verified by pilot_bridge.lua executing and hitting /ping). If not, opens Chrome or auto-catches
     * the PlatoRelay link when you tap "Receive Key" on Baseplate.
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

                // Wait up to 28s to see if pilot_bridge.lua executes inside Baseplate and sends /ping
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

            // Check explicit URL, saved config URL, current clipboard, or Delta cached files
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

            // Clone is already open on Baseplate right now — wait for user to tap "Receive Key"
            waitingForPlatoLink = true
            updateStep("Baseplate is open — tap 'Receive Key' in Delta now (auto-catching link)...")
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

    /** Applies a pasted key OR saves a pasted PlatoRelay link and opens it in Chrome. */
    fun applyManualKey(ctx: Context, rawInput: String): Pair<Boolean, String> {
        val trimmed = rawInput.trim()
        if (trimmed.isEmpty()) return false to "Clipboard is empty."

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

        val luarmorKey = extractLuarmorKey(trimmed) ?: trimmed.takeIf { it.length >= 16 && !it.contains(" ") }
            ?: return false to "Could not recognize a valid key or PlatoRelay link in clipboard."

        appCtx = ctx.applicationContext
        if (running.compareAndSet(false, true)) {
            applyNousigiKeyInternal(ctx.applicationContext, luarmorKey)
            return true to "Saved Nousigi key `$luarmorKey` and restarting clones."
        } else {
            val path = overwriteExistingKeyFile(ctx, "/storage/emulated/0/Delta/Workspace", luarmorKey, "Nousigi Hub")
            return if (path != null) {
                Thread { Rejoiner.killAllAndStaggeredRejoin(ctx, reason = "Manual Nousigi key") }.start()
                true to "Saved Nousigi key `$luarmorKey` to `$path` and restarting clones."
            } else {
                false to "No existing key file found in `Delta/Workspace` (zero duplicates created)."
            }
        }
    }

    private fun applyNousigiKeyInternal(ctx: Context, key: String) {
        Thread {
            updateStep("Detected key! Saving to existing Nousigi key file...")
            val path = overwriteExistingKeyFile(ctx, "/storage/emulated/0/Delta/Workspace", key, preferSubdir = "Nousigi Hub")
            if (path != null) {
                PilotCore.log("[KEYAUTO] Nousigi key saved to existing file: $path")
                Rejoiner.killAllAndStaggeredRejoin(ctx, reason = "Nousigi key updated")
                finishFlow(true, "Nousigi key `$key` saved to `$path`. All clones restarting.")
            } else {
                finishFlow(false, "Detected key `$key`, but no existing key file was found in `Delta/Workspace` (zero duplicates created).")
            }
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