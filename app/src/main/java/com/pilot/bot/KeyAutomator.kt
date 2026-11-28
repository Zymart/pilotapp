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
 * Full-Chrome Key Helper + Automatic Clipboard Watcher:
 * 1) Launches Luarmor (Nousigi) or PlatoRelay (Delta) in real Google Chrome so browser checks pass cleanly.
 * 2) Watches the device clipboard for the newly copied key (ignoring the old/blacklisted key).
 * 3) Overwrites the existing key file in place (zero duplicates) and restarts all Roblox clones.
 */
object KeyAutomator {
    enum class Mode { NOUSIGI_LUARMOR, DELTA_PLATO }

    private val mainHandler = Handler(Looper.getMainLooper())
    private val running = AtomicBoolean(false)

    @Volatile private var currentMode: Mode? = null
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
        return "WAITING FOR KEY (${sec}s) — $currentStep"
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

    private val clipboardWatchRunnable = object : Runnable {
        override fun run() {
            if (!running.get()) return
            if (System.currentTimeMillis() - startedAtMs > 300_000L) {
                finishFlow(false, "Timed out after 5m waiting for copied key from Chrome.")
                return
            }

            val ctx = appCtx
            if (ctx != null) {
                val clip = readClipboard(ctx).trim()
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

            mainHandler.postDelayed(this, 2000L)
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
            openInFullChrome(cfg.luarmorUrl)
            return false
        }

        appCtx = ctx.applicationContext
        currentMode = Mode.NOUSIGI_LUARMOR
        lastLoggedStep = ""
        completionCb = onDone
        startedAtMs = System.currentTimeMillis()

        val existingPath = detectExistingKeyFile(ctx)
        previousKeySnapshot = readCurrentFileContent(existingPath)

        // Clear old key from clipboard so we only catch the newly copied key
        if (previousKeySnapshot.isNotEmpty() && readClipboard(ctx).trim() == previousKeySnapshot) {
            writeClipboard(ctx, "")
        }

        updateStep("Opened Luarmor in Chrome — complete check & tap Copy on your key...")
        Thread { openInFullChrome(cfg.luarmorUrl) }.start()
        mainHandler.postDelayed(clipboardWatchRunnable, 2500L)
        return true
    }

    /** Opens PlatoRelay in Full Chrome and auto-captures KEY_... as soon as you tap Copy. */
    fun startDeltaRenewal(
        ctx: Context,
        explicitUrl: String = "",
        onDone: ((Boolean, String) -> Unit)? = null
    ): Boolean {
        if (!running.compareAndSet(false, true)) {
            PilotCore.log("[KEYAUTO] Already waiting for Delta key...")
            return false
        }
        appCtx = ctx.applicationContext
        currentMode = Mode.DELTA_PLATO
        lastLoggedStep = ""
        completionCb = onDone
        startedAtMs = System.currentTimeMillis()
        previousKeySnapshot = readClipboard(ctx).trim()

        Thread {
            var url = explicitUrl.trim().ifBlank { Rejoiner.loadConfig(ctx).deltaPlatoUrl.trim() }
            if (url.isBlank()) {
                updateStep("Grabbing Receive Key link from Delta...")
                url = autoGrabDeltaReceiveKeyLink(ctx)
                if (url.isNotBlank()) {
                    val updated = Rejoiner.loadConfig(ctx).copy(deltaPlatoUrl = url)
                    Rejoiner.saveConfig(ctx, updated)
                    PilotCore.log("[KEYAUTO] Saved grabbed PlatoRelay URL for this UGPhone")
                }
            }
            if (url.isBlank()) {
                finishFlow(false, "No PlatoRelay URL saved and could not grab Receive Key from Delta.")
                return@Thread
            }
            updateStep("Opened PlatoRelay in Chrome — complete steps & copy KEY_...")
            openInFullChrome(url)
            mainHandler.postDelayed(clipboardWatchRunnable, 2500L)
        }.start()
        return true
    }

    /** Immediately applies a key pasted via HUD button, clipboard, or Discord !setkey command. */
    fun applyManualKey(ctx: Context, rawInput: String): Pair<Boolean, String> {
        val trimmed = rawInput.trim()
        if (trimmed.isEmpty()) return false to "Key text is empty."

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
            ?: return false to "Could not recognize a valid Luarmor or Delta key in `$trimmed`."

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

    /** Launches URL in real Google Chrome via root `am start` so background service launches are never blocked. */
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

    private fun autoGrabDeltaReceiveKeyLink(ctx: Context): String {
        val clones = Rejoiner.discoverClones()
        val target = clones.firstOrNull()?.pkg ?: return ""
        SuShell.exec("monkey -p $target -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1")
        Thread.sleep(3000)

        SuShell.exec("uiautomator dump /sdcard/pilot_ui.xml >/dev/null 2>&1")
        val xml = SuShell.exec("cat /sdcard/pilot_ui.xml 2>/dev/null")
        val rx = Regex("""<node[^>]*?(?:text|content-desc)="([^"]+)"[^>]*?bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"[^>]*?>""")
        for (m in rx.findAll(xml)) {
            val label = m.groupValues[1].lowercase()
            if (label.contains("receive key") || label.contains("copy link") || label.contains("get key")) {
                val x = (m.groupValues[2].toInt() + m.groupValues[4].toInt()) / 2
                val y = (m.groupValues[3].toInt() + m.groupValues[5].toInt()) / 2
                SuShell.exec("input tap $x $y")
                Thread.sleep(1500)
                val clip = readClipboard(ctx)
                if (clip.contains("platorelay.com") || clip.contains("platoboost")) {
                    return clip.trim()
                }
            }
        }
        return ""
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

    /** Overwrites the saved/detected existing key file in place — never creates duplicate files. */
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
        currentStep = if (ok) "Completed" else "Failed"
        mainHandler.removeCallbacks(clipboardWatchRunnable)
        PilotCore.log("[KEYAUTO] ${if (ok) "SUCCESS" else "FAIL"}: $msg")
        PilotCore.onAlert?.invoke(if (ok) "🔑 Key Updated" else "⚠️ Key Automator Alert", msg)
        val cb = completionCb
        completionCb = null
        cb?.invoke(ok, msg)
    }
}