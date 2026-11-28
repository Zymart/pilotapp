package com.pilot.bot

import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.Message
import android.provider.Settings
import android.view.Gravity
import android.view.WindowManager
import android.webkit.*
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Background WebView Key Engine for:
 * 1) Nousigi Hub (Luarmor ads.luarmor.net + Linkvertise Premium auto-login, maximize duration, overwrite existing file)
 * 2) Delta Executor (PlatoRelay + LootLabs, auto-grab Receive Key link, 4-layer apply)
 */
object KeyAutomator {
    enum class Mode { NOUSIGI_LUARMOR, DELTA_PLATO }

    private val mainHandler = Handler(Looper.getMainLooper())
    private val running = AtomicBoolean(false)

    @Volatile private var currentMode: Mode? = null
    @Volatile private var forceFreshKey = false
    @Volatile private var activeWebView: WebView? = null
    @Volatile private var overlayParams: WindowManager.LayoutParams? = null
    @Volatile private var wm: WindowManager? = null
    @Volatile private var startedAtMs = 0L
    @Volatile private var lastTapMs = 0L
    @Volatile private var lastTaskClickMs = 0L
    @Volatile private var lastRenewalClicks = 0
    @Volatile private var capturedKey = ""
    @Volatile private var appCtx: Context? = null
    @Volatile private var completionCb: ((Boolean, String) -> Unit)? = null

    fun isRunning(): Boolean = running.get()

    private val tickRunnable = object : Runnable {
        override fun run() {
            if (!running.get()) return
            if (System.currentTimeMillis() - startedAtMs > 300_000L) {
                finishFlow(false, "Key automation timed out after 5m.")
                return
            }
            when (currentMode) {
                Mode.NOUSIGI_LUARMOR -> tickLuarmorLinkvertise()
                Mode.DELTA_PLATO -> tickDeltaPlatoLootLabs()
                null -> Unit
            }
            mainHandler.postDelayed(this, 2600L)
        }
    }

    /** Starts silent background Nousigi (Luarmor + Linkvertise Premium) key fetch/maximize. */
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
            PilotCore.log("[KEYAUTO] Already running — skipping duplicate Nousigi trigger")
            return false
        }
        appCtx = ctx.applicationContext
        currentMode = Mode.NOUSIGI_LUARMOR
        forceFreshKey = blacklistedReset
        capturedKey = ""
        lastRenewalClicks = 0
        completionCb = onDone
        startedAtMs = System.currentTimeMillis()

        PilotCore.log("[KEYAUTO] Starting background Nousigi key flow (reset=$blacklistedReset)")
        mainHandler.post {
            mountBackgroundWebView(ctx.applicationContext, cfg.luarmorUrl)
            mainHandler.postDelayed(tickRunnable, 3000L)
        }
        return true
    }

    /** Starts background Delta (PlatoRelay + LootLabs) key renewal with auto-grab + 4-layer apply. */
    fun startDeltaRenewal(
        ctx: Context,
        explicitUrl: String = "",
        onDone: ((Boolean, String) -> Unit)? = null
    ): Boolean {
        if (!running.compareAndSet(false, true)) {
            PilotCore.log("[KEYAUTO] Already running — skipping duplicate Delta trigger")
            return false
        }
        appCtx = ctx.applicationContext
        currentMode = Mode.DELTA_PLATO
        forceFreshKey = false
        capturedKey = ""
        completionCb = onDone
        startedAtMs = System.currentTimeMillis()

        Thread {
            var url = explicitUrl.trim().ifBlank { Rejoiner.loadConfig(ctx).deltaPlatoUrl.trim() }
            if (url.isBlank()) {
                PilotCore.log("[KEYAUTO] No saved PlatoRelay URL — attempting auto-grab from Delta Receive Key...")
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
            PilotCore.log("[KEYAUTO] Starting background Delta key flow")
            mainHandler.post {
                mountBackgroundWebView(ctx.applicationContext, url)
                mainHandler.postDelayed(tickRunnable, 3000L)
            }
        }.start()
        return true
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

    private fun readClipboard(ctx: Context): String {
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

    @SuppressLint("SetJavaScriptEnabled")
    private fun mountBackgroundWebView(ctx: Context, startUrl: String) {
        teardownWebView()
        val wv = WebView(ctx).apply {
            configureWebView(this)
            addJavascriptInterface(Bridge(), "PilotKeyBridge")
            webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView?, url: String?) {
                    PilotCore.log("[KEYAUTO] Loaded: ${url?.take(64)}")
                }
            }
            webChromeClient = object : WebChromeClient() {
                override fun onCreateWindow(
                    view: WebView?, isDialog: Boolean, isUserGesture: Boolean, resultMsg: Message?
                ): Boolean {
                    val popup = WebView(ctx)
                    configureWebView(popup)
                    popup.webViewClient = WebViewClient()
                    val transport = resultMsg?.obj as? WebView.WebViewTransport ?: return false
                    transport.webView = popup
                    resultMsg.sendToTarget()
                    mainHandler.postDelayed({ runCatching { popup.stopLoading(); popup.destroy() } }, 15_000L)
                    return true
                }
            }
        }
        activeWebView = wv

        if (Settings.canDrawOverlays(ctx)) {
            val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else
                @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE

            val params = WindowManager.LayoutParams(
                2, 2, type,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.START
                x = 0; y = 0
            }
            wm = ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager
            overlayParams = params
            runCatching { wm?.addView(wv, params) }
        }

        if (forceFreshKey) {
            CookieManager.getInstance().removeSessionCookies(null)
        }
        wv.loadUrl(startUrl)
    }

    /** Temporarily expands overlay to 360x220 in top-left corner for ~2s so root input tap hits Turnstile cleanly. */
    private fun flashForTurnstileTap(cssX: Float, cssY: Float) {
        val now = System.currentTimeMillis()
        if (now - lastTapMs < 6500L) return
        lastTapMs = now
        val ctx = appCtx ?: return
        val wv = activeWebView ?: return
        val params = overlayParams ?: return
        val manager = wm ?: return

        mainHandler.post {
            val d = ctx.resources.displayMetrics.density
            params.width = (360 * d).toInt()
            params.height = (220 * d).toInt()
            params.flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
            runCatching { manager.updateViewLayout(wv, params) }

            mainHandler.postDelayed({
                val loc = IntArray(2)
                wv.getLocationOnScreen(loc)
                val tx = (loc[0] + cssX * d).toInt().coerceAtLeast(16)
                val ty = (loc[1] + cssY * d).toInt().coerceAtLeast(16)
                Thread {
                    PilotCore.log("[KEYAUTO] Root Turnstile tap at ($tx, $ty)")
                    SuShell.exec("input tap $tx $ty")
                    Thread.sleep(1500)
                    mainHandler.post {
                        params.width = 2
                        params.height = 2
                        params.flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                        runCatching { manager.updateViewLayout(wv, params) }
                    }
                }.start()
            }, 450L)
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun configureWebView(wv: WebView) {
        val cm = CookieManager.getInstance()
        cm.setAcceptCookie(true)
        cm.setAcceptThirdPartyCookies(wv, true)
        wv.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            javaScriptCanOpenWindowsAutomatically = true
            setSupportMultipleWindows(true)
            userAgentString =
                "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Mobile Safari/537.36"
        }
    }

    private fun tickLuarmorLinkvertise() {
        val ctx = appCtx ?: return
        val cfg = Rejoiner.loadConfig(ctx)
        val emailEsc = cfg.lvEmail.replace("'", "\\'")
        val passEsc = cfg.lvPassword.replace("'", "\\'")
        val js = """
            (function(){
                var host = (location.hostname || '').toLowerCase();
                var text = (document.body && document.body.innerText) ? document.body.innerText : '';

                // Cloudflare Turnstile detection
                var iframes = document.querySelectorAll('iframe');
                for (var i = 0; i < iframes.length; i++) {
                    var src = (iframes[i].src || '').toLowerCase();
                    if (src.indexOf('challenges.cloudflare.com') !== -1 || src.indexOf('turnstile') !== -1) {
                        var r = iframes[i].getBoundingClientRect();
                        if (r.width > 40 && r.height > 30) {
                            PilotKeyBridge.tapTurnstile(r.left + 32, r.top + r.height / 2);
                            return;
                        }
                    }
                }

                // 1. Linkvertise Premium login / direct bypass
                if (host.indexOf('linkvertise') !== -1 || host.indexOf('link-target') !== -1 || host.indexOf('direct-link') !== -1) {
                    var emailIn = document.querySelector('input[type="email"], input[name="email"]');
                    var passIn = document.querySelector('input[type="password"]');
                    if (emailIn && passIn && '$emailEsc'.length > 3) {
                        emailIn.value = '$emailEsc';
                        emailIn.dispatchEvent(new Event('input', {bubbles:true}));
                        passIn.value = '$passEsc';
                        passIn.dispatchEvent(new Event('input', {bubbles:true}));
                        var sub = document.querySelector('button[type="submit"], button');
                        if (sub) sub.click();
                        return;
                    }
                    var lvBtns = document.querySelectorAll('button, a, div[role="button"]');
                    for (var b = 0; b < lvBtns.length; b++) {
                        var el = lvBtns[b];
                        if (el.offsetParent === null) continue;
                        var t = (el.innerText || '').trim().toLowerCase();
                        if (t.indexOf('get ') === 0 || t.indexOf('continue') !== -1 || t.indexOf('free access') !== -1 || t.indexOf('skip') !== -1 || t.indexOf('i accept') !== -1) {
                            el.click();
                            return;
                        }
                    }
                    return;
                }

                // 2. Luarmor page (ads.luarmor.net)
                if (host.indexOf('luarmor') !== -1) {
                    var keyM = text.match(/\b[A-Za-z0-9]{32}\b/);
                    var foundKey = keyM ? keyM[0] : '';
                    if (foundKey) PilotKeyBridge.reportCandidateKey(foundKey);

                    var btns = document.querySelectorAll('button, a');
                    for (var k = 0; k < btns.length; k++) {
                        var btn = btns[k];
                        if (btn.disabled || btn.offsetParent === null) continue;
                        var bt = (btn.innerText || '').trim().toLowerCase();
                        if (bt.indexOf('i accept') !== -1 || bt === 'accept') { btn.click(); return; }
                        if (bt.indexOf('get a new key') !== -1 || bt === 'start' || bt === 'next') { btn.click(); return; }
                        if (bt.indexOf('renew') !== -1 || bt.indexOf('extend') !== -1) {
                            PilotKeyBridge.noteRenewClick();
                            btn.click();
                            return;
                        }
                    }
                    if (foundKey && (text.toLowerCase().indexOf('max') !== -1 || text.toLowerCase().indexOf('active') !== -1 || text.toLowerCase().indexOf('expires') !== -1)) {
                        PilotKeyBridge.onLuarmorComplete(foundKey);
                    }
                }
            })();
        """.trimIndent()
        runCatching { activeWebView?.evaluateJavascript(js, null) }
    }

    private fun tickDeltaPlatoLootLabs() {
        val js = """
            (function(){
                var host = (location.hostname || '').toLowerCase();
                var text = (document.body && document.body.innerText) ? document.body.innerText : '';

                if (host.indexOf('platorelay') !== -1 || host.indexOf('platoboost') !== -1 || host.indexOf('delta') !== -1) {
                    var m = text.match(/\bKEY_[A-Za-z0-9_-]{12,64}\b/);
                    if (m && m[0]) { PilotKeyBridge.onDeltaKeyFound(m[0]); return; }
                    var inputs = document.querySelectorAll('input, textarea, code');
                    for (var i = 0; i < inputs.length; i++) {
                        var v = (inputs[i].value || inputs[i].innerText || '').trim();
                        if (/^KEY_[A-Za-z0-9_-]{12,64}$/.test(v)) { PilotKeyBridge.onDeltaKeyFound(v); return; }
                    }
                }

                var iframes = document.querySelectorAll('iframe');
                for (var f = 0; f < iframes.length; f++) {
                    var src = (iframes[f].src || '').toLowerCase();
                    if (src.indexOf('challenges.cloudflare.com') !== -1 || src.indexOf('turnstile') !== -1) {
                        var r = iframes[f].getBoundingClientRect();
                        if (r.width > 40 && r.height > 30) {
                            PilotKeyBridge.tapTurnstile(r.left + 32, r.top + r.height / 2);
                            return;
                        }
                    }
                }

                var labels = ['unlock content', 'done', 'continue', 'create key', 'proceed', 'get key', 'verify'];
                var els = document.querySelectorAll('button, a, div[role="button"], input[type="submit"]');
                for (var b = 0; b < els.length; b++) {
                    var el = els[b];
                    if (el.disabled || el.offsetParent === null) continue;
                    var t = (el.innerText || el.value || '').trim().toLowerCase();
                    if (!t || t.length > 40) continue;
                    for (var k = 0; k < labels.length; k++) {
                        if (t === labels[k] || t.indexOf(labels[k]) !== -1) { el.click(); return; }
                    }
                }

                if (host.indexOf('loot') !== -1) {
                    var tasks = document.querySelectorAll('a, button, div[role="button"]');
                    for (var j = 0; j < tasks.length; j++) {
                        var tel = tasks[j];
                        if (tel.offsetParent === null) continue;
                        var tt = (tel.innerText || '').trim().toLowerCase();
                        if (tt.indexOf('discover') !== -1 || tt.indexOf('read') !== -1 || tt.indexOf('visit') !== -1 || tt.indexOf('interesting') !== -1) {
                            PilotKeyBridge.clickLootTask(j);
                            return;
                        }
                    }
                }
            })();
        """.trimIndent()
        runCatching { activeWebView?.evaluateJavascript(js, null) }
    }

    class Bridge {
        @JavascriptInterface
        fun tapTurnstile(x: Float, y: Float) = flashForTurnstileTap(x, y)

        @JavascriptInterface
        fun reportCandidateKey(k: String) {
            if (k.length >= 24) capturedKey = k.trim()
        }

        @JavascriptInterface
        fun noteRenewClick() {
            lastRenewalClicks++
            PilotCore.log("[KEYAUTO] Extending Luarmor key time (step #$lastRenewalClicks)...")
        }

        @JavascriptInterface
        fun onLuarmorComplete(rawKey: String) {
            val key = rawKey.trim().ifBlank { capturedKey }
            if (key.isBlank()) return
            Thread {
                val ctx = appCtx ?: return@Thread
                val path = overwriteExistingKeyFile("/storage/emulated/0/Delta/Workspace", key, preferSubdir = "Nousigi Hub")
                if (path != null) {
                    PilotCore.log("[KEYAUTO] Nousigi key saved to existing file: $path")
                    Rejoiner.killAllAndStaggeredRejoin(ctx, reason = "Nousigi key updated")
                    finishFlow(true, "Nousigi key secured & written to `$path`. All clones restarting.")
                } else {
                    finishFlow(false, "Secured Nousigi key `$key`, but no existing key file was found in Delta/Workspace (zero duplicates created).")
                }
            }.start()
        }

        @JavascriptInterface
        fun clickLootTask(index: Int) {
            val now = System.currentTimeMillis()
            if (now - lastTaskClickMs < 16_000L) return
            lastTaskClickMs = now
            mainHandler.post {
                activeWebView?.evaluateJavascript(
                    "(function(){var t=document.querySelectorAll('a,button,div[role=\"button\"]')[$index];if(t)t.click();})();",
                    null
                )
            }
        }

        @JavascriptInterface
        fun onDeltaKeyFound(rawKey: String) {
            val key = rawKey.trim()
            if (key.isBlank()) return
            Thread {
                val ctx = appCtx ?: return@Thread
                mainHandler.post {
                    runCatching {
                        val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        cm.setPrimaryClip(ClipData.newPlainText("DeltaKey", key))
                    }
                }
                val cached = overwriteExistingKeyFile("/storage/emulated/0/Delta/Internals", key, preferSubdir = "")
                Rejoiner.killAllAndStaggeredRejoin(ctx, reason = "Delta key renewed")
                val extra = if (cached != null) " + updated `$cached`" else ""
                finishFlow(true, "Delta key `$key` applied (HWID + Clipboard$extra). All clones restarting.")
            }.start()
        }
    }

    /** Finds an existing key file and overwrites it in place — never creates a duplicate file. */
    private fun overwriteExistingKeyFile(baseDir: String, key: String, preferSubdir: String): String? {
        val searchFirst = if (preferSubdir.isNotBlank()) "'$baseDir/$preferSubdir' '$baseDir'" else "'$baseDir'"
        val existing = SuShell.exec("find $searchFirst -maxdepth 3 -type f -iname '*key*' 2>/dev/null | head -n 1").trim()
        if (existing.isBlank()) return null

        val ctx = appCtx ?: return null
        val tmp = File(ctx.cacheDir, "key_write_tmp")
        FileOutputStream(tmp).use { it.write(key.toByteArray(Charsets.UTF_8)) }
        SuShell.exec("cp '${tmp.absolutePath}' '$existing' && chmod 666 '$existing'")
        tmp.delete()
        return existing
    }

    private fun finishFlow(ok: Boolean, msg: String) {
        if (!running.compareAndSet(true, false)) return
        mainHandler.removeCallbacks(tickRunnable)
        mainHandler.post { teardownWebView() }
        PilotCore.log("[KEYAUTO] ${if (ok) "SUCCESS" else "FAIL"}: $msg")
        PilotCore.onAlert?.invoke(if (ok) "🔑 Key Automator Success" else "⚠️ Key Automator Alert", msg)
        val cb = completionCb
        completionCb = null
        cb?.invoke(ok, msg)
    }

    private fun teardownWebView() {
        runCatching {
            activeWebView?.let { wv ->
                wv.stopLoading()
                runCatching { wm?.removeView(wv) }
                wv.destroy()
            }
        }
        activeWebView = null
        overlayParams = null
    }
}