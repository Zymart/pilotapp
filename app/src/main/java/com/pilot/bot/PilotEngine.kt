package com.pilot.bot

import android.app.*
import android.content.*
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Rect
import android.os.*
import android.provider.Settings
import android.util.Base64
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.File
import java.io.FileOutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.URLDecoder
import java.util.concurrent.*
import kotlin.math.max
import kotlin.random.Random

data class AccInfo(val pkg: String, val user: String, val status: String)

object SuShell {
    private var proc: java.lang.Process? = null
    private var writer: BufferedWriter? = null
    private var reader: BufferedReader? = null

    @Synchronized
    fun exec(cmd: String): String {
        return runCatching {
            if (proc == null || !isAlive(proc!!)) {
                runCatching { proc?.destroy() }
                val p = ProcessBuilder("su").redirectErrorStream(true).start()
                proc = p
                writer = p.outputStream.bufferedWriter()
                reader = p.inputStream.bufferedReader()
            }
            val marker = "__PILOT_END_${System.nanoTime()}__"
            writer!!.write("$cmd\necho $marker\n")
            writer!!.flush()
            val sb = StringBuilder()
            while (true) {
                val line = reader!!.readLine() ?: break
                if (line.contains(marker)) break
                sb.append(line).append('\n')
            }
            sb.toString().trim()
        }.getOrElse {
            proc = null
            ""
        }
    }

    private fun isAlive(p: java.lang.Process): Boolean = try {
        p.exitValue()
        false
    } catch (_: IllegalThreadStateException) {
        true
    }
}

object AppUpdater {
    private val http = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    @Volatile private var updating = false

    fun normalizeUrl(raw: String): String {
        val u = raw.trim()
        if (u.isEmpty()) return ""
        val driveId = Regex("""drive\.google\.com/(?:file/d/|open\?id=|uc\?.*?\bid=)([a-zA-Z0-9_-]{15,})""").find(u)?.groupValues?.get(1)
        return if (!driveId.isNullOrEmpty()) "https://drive.google.com/uc?export=download&id=$driveId" else u
    }

    @Synchronized
    fun checkAndInstall(c: Context, force: Boolean = false): String {
        if (updating) return "Update already in progress."
        updating = true
        try {
            val prefs = c.getSharedPreferences("pilot_cfg", Context.MODE_PRIVATE)
            val customRaw = PilotCore.getUpdateUrl(c)
            val lastTag = prefs.getString("last_upd_tag", "") ?: ""

            var downloadUrl = ""
            var newTag = ""

            if (customRaw.isNotEmpty()) {
                downloadUrl = normalizeUrl(customRaw)
                val headReq = Request.Builder().url(downloadUrl).head()
                    .header("User-Agent", "Mozilla/5.0").build()
                val sig = runCatching {
                    http.newCall(headReq).execute().use { r ->
                        val etag = r.header("ETag") ?: ""
                        val len = r.header("Content-Length") ?: ""
                        val mod = r.header("Last-Modified") ?: ""
                        "$downloadUrl|$etag|$len|$mod"
                    }
                }.getOrDefault("$downloadUrl|${System.currentTimeMillis() / 3600000}")
                newTag = sig
            } else {
                val apiUrl = "https://api.github.com/repos/Zymart/pilotapp/releases/tags/latest"
                val req = Request.Builder().url(apiUrl)
                    .header("Accept", "application/vnd.github+json")
                    .header("User-Agent", "PilotUpdater").build()
                val body = runCatching {
                    http.newCall(req).execute().use { if (it.isSuccessful) it.body?.string() ?: "" else "" }
                }.getOrDefault("")
                if (body.isEmpty()) {
                    PilotCore.log("[UPD] No GitHub release found (set Drive URL or make repo public).")
                    return "No release found (paste a Google Drive APK link or make repo public)."
                }
                val json = JSONObject(body)
                val assets = json.optJSONArray("assets")
                if (assets != null && assets.length() > 0) {
                    val a = assets.getJSONObject(0)
                    downloadUrl = a.getString("browser_download_url")
                    newTag = "gh:${a.optLong("id")}:${a.optString("updated_at")}"
                }
            }

            if (downloadUrl.isEmpty()) return "Invalid update URL."
            if (!force && newTag.isNotEmpty() && newTag == lastTag) {
                return "Already up to date."
            }

            PilotCore.log("[UPD] Downloading APK...")
            val apkFile = File(c.cacheDir, "pilot_update.apk")
            if (!downloadBinary(downloadUrl, apkFile)) {
                PilotCore.log("[UPD] Download failed or invalid APK.")
                return "Download failed or link is not a public APK."
            }

            PilotCore.log("[UPD] Installing silently via root...")
            val tmpPath = "/data/local/tmp/pilot_upd.apk"
            val res = SuShell.exec("cp '${apkFile.absolutePath}' '$tmpPath' && chmod 644 '$tmpPath' && pm install -r -d '$tmpPath'")
            apkFile.delete()

            return if (res.contains("Success", ignoreCase = true)) {
                SuShell.exec("rm -f '$tmpPath'")
                prefs.edit().putString("last_upd_tag", newTag).apply()
                PilotCore.log("[UPD] Installed! Restarting service...")
                "Update installed! Restarting agent..."
            } else if (res.contains("INSTALL_FAILED_UPDATE_INCOMPATIBLE", ignoreCase = true)) {
                prefs.edit().putString("last_upd_tag", newTag).apply()
                PilotCore.log("[UPD] Signature changed — migrating data & reinstalling...")
                val swapScript = """
                    nohup sh -c '
                      sleep 2
                      rm -rf /data/local/tmp/pilot_prefs_bak
                      cp -rf /data/data/com.pilot.bot/shared_prefs /data/local/tmp/pilot_prefs_bak 2>/dev/null
                      pm uninstall com.pilot.bot >/dev/null 2>&1
                      pm install -g "$tmpPath" >/dev/null 2>&1
                      rm -f "$tmpPath"
                      if [ -d /data/local/tmp/pilot_prefs_bak ]; then
                        mkdir -p /data/data/com.pilot.bot/shared_prefs
                        cp -rf /data/local/tmp/pilot_prefs_bak/. /data/data/com.pilot.bot/shared_prefs/
                        uid=${'$'}(stat -c "%u:%g" /data/data/com.pilot.bot 2>/dev/null)
                        [ -n "${'$'}uid" ] && chown -R ${'$'}uid /data/data/com.pilot.bot/shared_prefs
                        restorecon -RF /data/data/com.pilot.bot 2>/dev/null
                        rm -rf /data/local/tmp/pilot_prefs_bak
                      fi
                      am start-foreground-service -n com.pilot.bot/.PilotService >/dev/null 2>&1 || am startservice -n com.pilot.bot/.PilotService >/dev/null 2>&1
                    ' >/dev/null 2>&1 &
                """.trimIndent()
                SuShell.exec(swapScript)
                "Signature migrated! Reinstalling & restarting agent in 3s..."
            } else {
                SuShell.exec("rm -f '$tmpPath'")
                PilotCore.log("[UPD] Install error: $res")
                "Install failed: $res"
            }
        } finally {
            updating = false
        }
    }

    private fun downloadBinary(url: String, dest: File): Boolean {
        return runCatching {
            val req = Request.Builder().url(url).header("User-Agent", "Mozilla/5.0").build()
            http.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return false
                val ctype = resp.header("Content-Type") ?: ""
                if (ctype.contains("text/html", ignoreCase = true)) {
                    val html = resp.body?.string() ?: return false
                    val confirm = Regex("""name="confirm"\s+value="([^"]+)"""").find(html)?.groupValues?.get(1)
                    val uuid = Regex("""name="uuid"\s+value="([^"]+)"""").find(html)?.groupValues?.get(1)
                    val id = Regex("""[?&]id=([a-zA-Z0-9_-]+)""").find(url)?.groupValues?.get(1)
                    if (!id.isNullOrEmpty() && !confirm.isNullOrEmpty()) {
                        val bypass = "https://drive.usercontent.google.com/download?id=$id&export=download&confirm=$confirm" +
                                (if (!uuid.isNullOrEmpty()) "&uuid=$uuid" else "")
                        return downloadBinary(bypass, dest)
                    }
                    return false
                }
                val bytes = resp.body?.bytes() ?: return false
                if (bytes.size < 100_000) return false
                if (bytes[0] != 0x50.toByte() || bytes[1] != 0x4B.toByte()) return false
                FileOutputStream(dest).use { it.write(bytes) }
                true
            }
        }.getOrDefault(false)
    }
}

object PilotCore {
    var logs = "Agent initialized.\n"
    var onLog: (() -> Unit)? = null
    @Volatile var cachedScan: List<AccInfo> = emptyList()

    fun log(msg: String) {
        logs = (logs + msg + "\n").takeLast(2400)
        onLog?.invoke()
    }

    private fun prefs(c: Context) = c.getSharedPreferences("pilot_cfg", Context.MODE_PRIVATE)
    private fun histPrefs(c: Context) = c.getSharedPreferences("pilot_hist", Context.MODE_PRIVATE)

    fun getCloud(c: Context): String {
        val f = File("/sdcard/cloud_name.txt")
        if (f.exists()) {
            val t = runCatching { f.readText().trim() }.getOrDefault("")
            if (t.isNotEmpty() && t != "Not Set") return t
        }
        val saved = prefs(c).getString("cloud", "") ?: ""
        if (saved.isNotEmpty() && saved != "Not Set") return saved

        val detected = detectDeviceName(c)
        setCloudName(c, detected)
        return detected
    }

    private fun detectDeviceName(c: Context): String {
        val bad = setOf("", "android", "localhost", "generic", "sdk", "unknown", "not set")
        val sysName = runCatching {
            Settings.Global.getString(c.contentResolver, "device_name")?.trim() ?: ""
        }.getOrDefault("")
        if (sysName.lowercase() !in bad && !sysName.startsWith("umu", true)) return sysName

        val propName = SuShell.exec("getprop persist.sys.device_name; getprop ro.product.marketname").lines()
            .map { it.trim() }.firstOrNull { it.lowercase() !in bad }
        if (!propName.isNullOrEmpty()) return propName

        val aid = runCatching {
            Settings.Secure.getString(c.contentResolver, Settings.Secure.ANDROID_ID) ?: ""
        }.getOrDefault("")
        val suffix = if (aid.length >= 4) aid.takeLast(4).uppercase() else (1000..9999).random().toString()
        return "UG-DE-$suffix"
    }

    fun setCloudName(c: Context, name: String) {
        val clean = name.trim().ifEmpty { detectDeviceName(c) }
        prefs(c).edit().putString("cloud", clean).apply()
        runCatching { File("/sdcard/cloud_name.txt").writeText(clean) }
    }

    fun getToken(c: Context) = prefs(c).getString("token", "") ?: ""
    fun getUserId(c: Context) = prefs(c).getLong("user_id", 0L)
    fun getSsChannel(c: Context) = prefs(c).getString("ss_channel", "") ?: ""
    fun getUpdateUrl(c: Context) = prefs(c).getString("upd_url", "") ?: ""

    fun saveSsChannel(c: Context, ch: String) {
        prefs(c).edit().putString("ss_channel", ch.trim()).apply()
    }

    fun saveUpdateUrl(c: Context, url: String) {
        prefs(c).edit().putString("upd_url", url.trim()).apply()
    }

    fun saveConfig(c: Context, cloud: String, token: String, uid: Long, ssChan: String = "", updUrl: String = "") {
        val cl = cloud.trim().ifEmpty { detectDeviceName(c) }
        val edit = prefs(c).edit()
            .putString("cloud", cl)
            .putString("token", token.trim())
            .putLong("user_id", uid)
            .putString("upd_url", updUrl.trim())
        if (ssChan.trim().isNotEmpty()) edit.putString("ss_channel", ssChan.trim())
        edit.apply()
        runCatching { File("/sdcard/cloud_name.txt").writeText(cl) }
    }

    fun makeCode(c: Context): String {
        val letters = (1..7).map { ('A'..'Z').random() }.joinToString("")
        val payload = JSONObject()
            .put("t", getToken(c))
            .put("u", getUserId(c))
            .put("c", getSsChannel(c))
            .put("d", getUpdateUrl(c))
            .toString()
        val b64 = Base64.encodeToString(payload.toByteArray(), Base64.URL_SAFE or Base64.NO_WRAP)
        return "$letters-$b64"
    }

    fun applyCode(c: Context, code: String): Boolean = runCatching {
        val raw = code.trim().substringAfter("-")
        val json = JSONObject(String(Base64.decode(raw, Base64.URL_SAFE)))
        val edit = prefs(c).edit()
            .putString("token", json.getString("t"))
            .putLong("user_id", json.getLong("u"))
        val ch = json.optString("c", "")
        if (ch.isNotEmpty()) edit.putString("ss_channel", ch)
        val upd = json.optString("d", "")
        if (upd.isNotEmpty()) edit.putString("upd_url", upd)
        edit.apply()
        true
    }.getOrDefault(false)

    fun installAutoExec(c: Context) {
        val lua = """
            repeat task.wait(0.5) until game:IsLoaded()
            local Players = game:GetService("Players")
            local lp = Players.LocalPlayer
            while not lp do
                task.wait(0.5)
                lp = Players.LocalPlayer
            end

            local req = request or http_request or (syn and syn.request)
            if not req then return end

            local function snapMe()
                pcall(function()
                    req({
                        Url = "http://127.0.0.1:8765/ss?user=" .. lp.Name,
                        Method = "GET"
                    })
                end)
            end

            getgenv().PilotSnap = snapMe

            task.spawn(function()
                while task.wait(3600) do
                    snapMe()
                end
            end)
        """.trimIndent()

        runCatching {
            val tmp = File(c.filesDir, "pilot_bridge.lua")
            tmp.writeText(lua)
            val found = SuShell.exec("mkdir -p /storage/emulated/0/Delta/Autoexecute && find -L /storage/emulated/0 /data/data -maxdepth 6 -type d -iname 'autoexecute' 2>/dev/null")
                .lines().map { it.trim() }.filter { it.isNotEmpty() }
            val dirs = (listOf("/storage/emulated/0/Delta/Autoexecute") + found).toSet()
            for (d in dirs) {
                SuShell.exec("cp '${tmp.absolutePath}' '$d/pilot_bridge.lua' && chmod 666 '$d/pilot_bridge.lua'")
            }
            log("[AUTOEXEC] Ready in ${dirs.size} folder(s)")
        }
    }

    fun findWindowBounds(pkg: String): Rect? {
        val dump = SuShell.exec("dumpsys window windows 2>/dev/null")
        if (dump.isEmpty()) return null
        val frameRx = Regex("""\b(?:mFrame|mBounds)=\[(\d+),(\d+)\]\[(\d+),(\d+)\]""")
        var bestRect: Rect? = null
        var bestArea = 0

        for (block in dump.split("Window #")) {
            if (!block.contains("package=$pkg") && !block.contains("$pkg/")) continue
            if (block.contains("mHasSurface=false") || block.contains("mViewVisibility=0x8")) continue
            for (line in block.lines()) {
                val m = frameRx.find(line)
                if (m != null) {
                    val l = m.groupValues[1].toIntOrNull() ?: 0
                    val t = m.groupValues[2].toIntOrNull() ?: 0
                    val r = m.groupValues[3].toIntOrNull() ?: 0
                    val b = m.groupValues[4].toIntOrNull() ?: 0
                    val w = r - l
                    val h = b - t
                    val area = w * h
                    if (w >= 150 && h >= 150 && area > bestArea) {
                        bestArea = area
                        bestRect = Rect(l, t, r, b)
                    }
                }
            }
        }
        return bestRect
    }

    @Synchronized
    fun captureScreen(c: Context, pkg: String? = null): Pair<File?, Boolean> {
        val rawPath = File(c.cacheDir, "raw.png").absolutePath
        val outPath = File(c.cacheDir, "crop.jpg").absolutePath
        SuShell.exec("screencap -p '$rawPath' && chmod 666 '$rawPath'")
        val rawFile = File(rawPath)
        if (!rawFile.exists() || rawFile.length() == 0L) return null to false

        if (pkg.isNullOrEmpty()) return rawFile to true

        val bounds = findWindowBounds(pkg) ?: return rawFile to false

        return runCatching {
            val opts = BitmapFactory.Options().apply {
                inPreferredConfig = Bitmap.Config.RGB_565
            }
            val full = BitmapFactory.decodeFile(rawPath, opts) ?: return rawFile to false
            val w = full.width
            val h = full.height
            val l = bounds.left.coerceIn(0, w - 1)
            val t = bounds.top.coerceIn(0, h - 1)
            val r = bounds.right.coerceIn(l + 1, w)
            val b = bounds.bottom.coerceIn(t + 1, h)

            val cropped = Bitmap.createBitmap(full, l, t, r - l, b - t)
            full.recycle()

            val outFile = File(outPath)
            FileOutputStream(outFile).use { cropped.compress(Bitmap.CompressFormat.JPEG, 88, it) }
            cropped.recycle()
            rawFile.delete()
            outFile to true
        }.getOrElse {
            rawFile to false
        }
    }

    fun scanRbx(c: Context): List<AccInfo> {
        val script = """
            for d in /data/data/com.roblox.clien*; do
              [ -d "${'$'}d" ] || continue
              p=${'$'}{d##*/}
              line=${'$'}(grep -rnEI 'UserName|Username' "${'$'}d/files/appData/LocalStorage/appStorage.json" "${'$'}d/shared_prefs/" 2>/dev/null | head -n 1)
              echo "PKG:${'$'}p|${'$'}line"
            done
        """.trimIndent()

        val out = SuShell.exec(script)
        val hp = histPrefs(c)
        val edit = hp.edit()
        var changed = false
        val rx = Regex("""(?:UserName|Username)["'>:\s]+([a-zA-Z0-9_]{3,20})""")
        val res = mutableListOf<AccInfo>()

        for (row in out.lines()) {
            if (!row.startsWith("PKG:")) continue
            val payload = row.removePrefix("PKG:")
            val pkg = payload.substringBefore("|").trim()
            val rawLine = payload.substringAfter("|", "")
            if (pkg.isEmpty()) continue

            val m = rx.find(rawLine)
            if (m != null) {
                val u = m.groupValues[1]
                if (hp.getString(pkg, null) != u) { edit.putString(pkg, u); changed = true }
                res.add(AccInfo(pkg, u, "ACTIVE"))
            } else {
                val old = hp.getString(pkg, null)
                if (!old.isNullOrEmpty()) res.add(AccInfo(pkg, old, "LOGGED OUT"))
                else res.add(AccInfo(pkg, "Empty", "EMPTY"))
            }
        }
        if (changed) edit.apply()
        val sorted = res.sortedBy { it.pkg }
        cachedScan = sorted
        return sorted
    }

    fun wipe(c: Context, pkg: String, clearHist: Boolean) {
        SuShell.exec("am force-stop $pkg && rm -rf /data/data/$pkg/app_webview/Default/Cookies* /data/data/$pkg/shared_prefs/* /data/data/$pkg/files/LocalStorage/* /data/data/$pkg/files/appData/LocalStorage/*")
        if (clearHist) histPrefs(c).edit().remove(pkg).apply()
        scanRbx(c)
    }

    @Synchronized
    fun packAccountBundle(c: Context, pkg: String, username: String): Pair<File?, Int> {
        val safeUser = username.replace(Regex("[^a-zA-Z0-9_-]"), "")
        val bundleFile = File(c.cacheDir, "xfer_${System.nanoTime()}.tar.gz")
        val stageDir = "/data/local/tmp/pilot_stage_${System.nanoTime()}"

        val sh = """
            am force-stop $pkg
            rm -rf '$stageDir' && mkdir -p '$stageDir/session' '$stageDir/ext'
            for sub in app_webview/Default shared_prefs files/LocalStorage files/appData/LocalStorage; do
              if [ -d "/data/data/$pkg/${'$'}sub" ]; then
                mkdir -p "$stageDir/session/${'$'}sub"
                cp -rf "/data/data/$pkg/${'$'}sub/." "$stageDir/session/${'$'}sub/" 2>/dev/null
              fi
            done
            cnt=0
            if [ -n "$safeUser" ]; then
              cd /storage/emulated/0 2>/dev/null && find -L . -maxdepth 8 -iname "*${safeUser}*" ! -path "./Android/data/*" ! -path "./Android/obb/*" ! -path "./DCIM/*" ! -path "./Pictures/*" ! -path "./Movies/*" ! -path "./Music/*" 2>/dev/null | while IFS= read -r rel; do
                [ -z "${'$'}rel" ] && continue
                clean=${'$'}{rel#./}
                mkdir -p "$stageDir/ext/${'$'}(dirname "${'$'}clean")"
                cp -rf "/storage/emulated/0/${'$'}clean" "$stageDir/ext/${'$'}clean" 2>/dev/null
                rm -rf "/storage/emulated/0/${'$'}clean" 2>/dev/null
              done
              cnt=${'$'}(find "$stageDir/ext" -type f 2>/dev/null | wc -l)
            fi
            tar -czf '${bundleFile.absolutePath}' -C '$stageDir' . 2>/dev/null
            chmod 666 '${bundleFile.absolutePath}' 2>/dev/null
            rm -rf '$stageDir'
            echo "FILES:${'$'}cnt"
        """.trimIndent()

        val out = SuShell.exec(sh)
        val fileCount = Regex("""FILES:(\d+)""").find(out)?.groupValues?.get(1)?.toIntOrNull() ?: 0
        return if (bundleFile.exists() && bundleFile.length() > 0L) bundleFile to fileCount else null to 0
    }

    @Synchronized
    fun restoreAccountBundle(c: Context, dstPkg: String, username: String, bundleFile: File): Pair<Boolean, Int> {
        val stageDir = "/data/local/tmp/pilot_restore_${System.nanoTime()}"
        val sh = """
            if [ ! -d "/data/data/$dstPkg" ]; then
              echo "ERR:NO_PKG"
              exit 1
            fi
            am force-stop $dstPkg
            rm -rf '$stageDir' && mkdir -p '$stageDir'
            tar -xzf '${bundleFile.absolutePath}' -C '$stageDir' 2>/dev/null
            rm -rf /data/data/$dstPkg/app_webview/Default/Cookies* /data/data/$dstPkg/shared_prefs/* /data/data/$dstPkg/files/LocalStorage/* /data/data/$dstPkg/files/appData/LocalStorage/*
            mkdir -p /data/data/$dstPkg/app_webview/Default /data/data/$dstPkg/shared_prefs /data/data/$dstPkg/files/LocalStorage /data/data/$dstPkg/files/appData/LocalStorage
            if [ -d "$stageDir/session" ]; then
              cp -rf "$stageDir/session/." "/data/data/$dstPkg/" 2>/dev/null
            fi
            uid=${'$'}(stat -c '%u:%g' /data/data/$dstPkg 2>/dev/null)
            if [ -n "${'$'}uid" ]; then
              chown -R ${'$'}uid /data/data/$dstPkg/app_webview /data/data/$dstPkg/shared_prefs /data/data/$dstPkg/files 2>/dev/null
            fi
            restorecon -RF /data/data/$dstPkg 2>/dev/null
            cnt=0
            if [ -d "$stageDir/ext" ]; then
              cnt=${'$'}(find "$stageDir/ext" -type f 2>/dev/null | wc -l)
              cp -rf "$stageDir/ext/." "/storage/emulated/0/" 2>/dev/null
              chmod -R 777 /storage/emulated/0/Delta 2>/dev/null
            fi
            rm -rf '$stageDir'
            echo "RESTORED:${'$'}cnt"
        """.trimIndent()

        val out = SuShell.exec(sh)
        bundleFile.delete()
        val ok = out.contains("RESTORED:")
        val count = Regex("""RESTORED:(\d+)""").find(out)?.groupValues?.get(1)?.toIntOrNull() ?: 0
        if (ok && username.isNotEmpty() && username != "Empty") {
            histPrefs(c).edit().putString(dstPkg, username).apply()
        }
        scanRbx(c)
        return ok to count
    }

    fun fmtLine(idx: Int, a: AccInfo): String {
        val tag = when (a.status) {
            "ACTIVE" -> "🟢 *(Running)*"
            "LOGGED OUT" -> "🔴 *(Logged Out)*"
            else -> "⚪ *(Empty)*"
        }
        return "`[${idx + 1}]` **${a.user}** $tag — `${a.pkg}`"
    }
}

class PilotBridgeAgent(private val ctx: Context) {
    private val client = OkHttpClient.Builder().readTimeout(0, TimeUnit.MILLISECONDS).build()
    private val exec = Executors.newSingleThreadScheduledExecutor()
    private val ioExec = Executors.newFixedThreadPool(2)
    private var ws: WebSocket? = null
    private var hbTask: ScheduledFuture<*>? = null
    private var seq: Int? = null
    @Volatile private var botId: String = ""
    @Volatile private var alive = true

    fun start() { connect() }

    fun stop() {
        alive = false
        hbTask?.cancel(true)
        ws?.close(1000, "Stopped")
        exec.shutdownNow()
        ioExec.shutdownNow()
    }

    fun sendAccountShot(ch: String, query: String) {
        val cl = PilotCore.getCloud(ctx)
        val list = PilotCore.cachedScan.ifEmpty { PilotCore.scanRbx(ctx) }
        val q = query.trim().lowercase()
        val matches = list.mapIndexedNotNull { i, a ->
            if (a.user != "Empty" && a.user.lowercase() == q) i to a else null
        }
        if (matches.isEmpty()) return

        for ((idx, acc) in matches) {
            val (img, croppedOk) = PilotCore.captureScreen(ctx, acc.pkg)
            val note = if (!croppedOk) "\n*(Window hidden — showing full screen)*" else ""
            val caption = "**📱 $cl** — Auto Screenshot:\n${PilotCore.fmtLine(idx, acc)}$note"
            if (img != null) {
                sendFile(ch, caption, img, if (img.name.endsWith(".jpg")) "screen.jpg" else "screen.png")
                img.delete()
            }
        }
    }

    private fun connect() {
        if (!alive) return
        val token = PilotCore.getToken(ctx)
        if (token.isEmpty()) { PilotCore.log("[ERR] Missing Token"); return }
        val req = Request.Builder().url("wss://gateway.discord.gg/?v=10&encoding=json").build()
        ws = client.newWebSocket(req, object : WebSocketListener() {
            override fun onMessage(w: WebSocket, text: String) {
                exec.execute { handlePacket(w, JSONObject(text)) }
            }
            override fun onClosed(w: WebSocket, code: Int, reason: String) {
                PilotCore.log("[WS] Closed ($code), reconnecting...")
                scheduleReconnect()
            }
            override fun onFailure(w: WebSocket, t: Throwable, response: Response?) {
                PilotCore.log("[WS] Error: ${t.message ?: "disconnected"}")
                scheduleReconnect()
            }
        })
    }

    private fun scheduleReconnect() {
        hbTask?.cancel(true)
        if (alive) exec.schedule({ connect() }, 4, TimeUnit.SECONDS)
    }

    private fun handlePacket(w: WebSocket, pkt: JSONObject) {
        if (!pkt.isNull("s")) seq = pkt.getInt("s")
        when (pkt.optInt("op")) {
            10 -> {
                val interval = max(1000L, pkt.getJSONObject("d").getLong("heartbeat_interval"))
                hbTask?.cancel(true)
                hbTask = exec.scheduleAtFixedRate({
                    w.send(JSONObject().put("op", 1).put("d", seq ?: JSONObject.NULL).toString())
                }, Random.nextLong(500L, interval), interval, TimeUnit.MILLISECONDS)

                val idPayload = JSONObject().put("op", 2).put("d", JSONObject()
                    .put("token", PilotCore.getToken(ctx))
                    .put("intents", 37377)
                    .put("properties", JSONObject().put("os", "android").put("browser", "pilot-agent").put("device", "pilot-agent")))
                w.send(idPayload.toString())
            }
            1 -> w.send(JSONObject().put("op", 1).put("d", seq ?: JSONObject.NULL).toString())
            7, 9 -> { w.close(4000, "Reconnect"); scheduleReconnect() }
            0 -> {
                val type = pkt.optString("t")
                val d = pkt.optJSONObject("d") ?: return
                if (type == "READY") {
                    val userObj = d.getJSONObject("user")
                    botId = userObj.optString("id", "")
                    val u = userObj.getString("username")
                    PilotCore.log("[ONLINE] Agent ready ($u) | ${PilotCore.getCloud(ctx)}")
                } else if (type == "MESSAGE_CREATE") {
                    onBridgeMessage(d)
                }
            }
        }
    }

    private fun onBridgeMessage(d: JSONObject) {
        val ownerId = PilotCore.getUserId(ctx).toString()
        val author = d.optJSONObject("author") ?: return
        val authorId = author.optString("id")
        val isTrusted = author.optBoolean("bot", false) || authorId == ownerId || (botId.isNotEmpty() && authorId == botId)
        if (!isTrusted) return

        val content = d.optString("content").trim()
        val ch = d.optString("channel_id")
        if (ch.isEmpty() || content.isEmpty()) return

        when {
            content.startsWith("PILOT_PING:") -> {
                val reqId = content.removePrefix("PILOT_PING:").trim()
                if (reqId.isEmpty()) return
                ioExec.execute {
                    val myCloud = PilotCore.getCloud(ctx)
                    val list = PilotCore.cachedScan.ifEmpty { PilotCore.scanRbx(ctx) }
                    val arr = JSONArray()
                    for (a in list) {
                        arr.put(JSONObject().put("pkg", a.pkg).put("user", a.user).put("status", a.status))
                    }
                    val payload = JSONObject().put("cloud", myCloud).put("accs", arr).toString()
                    sendMsg(ch, "PILOT_PONG:$reqId:$payload")
                }
            }

            content.startsWith("PILOT_CMD:") -> {
                val parts = content.split(":", limit = 5)
                if (parts.size < 4) return
                val reqId = parts[1]
                val targetCloud = parts[2]
                val action = parts[3].uppercase()
                val arg = parts.getOrNull(4) ?: ""
                val myCloud = PilotCore.getCloud(ctx)
                if (targetCloud != "*" && !targetCloud.equals(myCloud, ignoreCase = true)) return

                ioExec.execute { handleBridgeCommand(ch, reqId, myCloud, action, arg) }
            }

            content.startsWith("PILOT_XFER:") -> {
                ioExec.execute { handleTransferPayload(d) }
            }
        }
    }

    private fun handleBridgeCommand(ch: String, reqId: String, myCloud: String, action: String, arg: String) {
        when (action) {
            "SHOT" -> {
                val pkg = if (arg.isEmpty() || arg.equals("ALL", ignoreCase = true)) null else arg
                val (img, _) = PilotCore.captureScreen(ctx, pkg)
                if (img != null) {
                    val fname = if (img.name.endsWith(".jpg")) "crop.jpg" else "screen.png"
                    sendFile(ch, "PILOT_RES:$reqId:$myCloud", img, fname)
                    img.delete()
                }
            }
            "LOGOUT" -> {
                if (arg.equals("ALL", ignoreCase = true)) {
                    PilotCore.scanRbx(ctx).forEach { PilotCore.wipe(ctx, it.pkg, false) }
                    sendMsg(ch, "PILOT_ACK:$reqId:$myCloud:Logged out ALL slots.")
                } else if (arg.isNotEmpty()) {
                    PilotCore.wipe(ctx, arg, false)
                    sendMsg(ch, "PILOT_ACK:$reqId:$myCloud:Logged out `$arg`.")
                }
            }
            "DELETE" -> {
                if (arg.equals("ALL", ignoreCase = true)) {
                    PilotCore.scanRbx(ctx).forEach { PilotCore.wipe(ctx, it.pkg, true) }
                    sendMsg(ch, "PILOT_ACK:$reqId:$myCloud:Deleted ALL accounts & history (now Empty).")
                } else if (arg.isNotEmpty()) {
                    PilotCore.wipe(ctx, arg, true)
                    sendMsg(ch, "PILOT_ACK:$reqId:$myCloud:Deleted `$arg` & history (now Empty).")
                }
            }
            "RENAME" -> {
                val newName = arg.trim()
                if (newName.isNotEmpty()) {
                    PilotCore.setCloudName(ctx, newName)
                    PilotCore.log("[RENAME] $myCloud -> $newName")
                    sendMsg(ch, "PILOT_ACK:$reqId:$myCloud:Renamed to **$newName**.")
                }
            }
            "SSCHAN" -> {
                val targetCh = arg.trim()
                if (targetCh.isNotEmpty()) {
                    PilotCore.saveSsChannel(ctx, targetCh)
                    sendMsg(ch, "PILOT_ACK:$reqId:$myCloud:Screenshot channel set.")
                }
            }
            "UPDATE" -> {
                if (arg.trim().isNotEmpty()) PilotCore.saveUpdateUrl(ctx, arg.trim())
                val status = AppUpdater.checkAndInstall(ctx, force = true)
                sendMsg(ch, "PILOT_ACK:$reqId:$myCloud:$status")
            }
            "PACK" -> {
                val p = arg.split("|", limit = 4)
                if (p.size < 4) return
                val srcPkg = p[0]
                val srcUser = p[1]
                val dstCloud = p[2]
                val dstPkg = p[3]

                val (bundle, fileCount) = PilotCore.packAccountBundle(ctx, srcPkg, srcUser)
                PilotCore.wipe(ctx, srcPkg, true)

                if (bundle == null) {
                    sendMsg(ch, "PILOT_ACK:$reqId:$myCloud:❌ Failed to package **$srcUser**.")
                    return
                }

                if (dstCloud.equals(myCloud, ignoreCase = true)) {
                    val (ok, restoredFiles) = PilotCore.restoreAccountBundle(ctx, dstPkg, srcUser, bundle)
                    if (ok) {
                        sendMsg(ch, "PILOT_ACK:$reqId:$myCloud:✅ Moved **$srcUser** locally (`$srcPkg` → `$dstPkg`, `$restoredFiles` script file(s)).")
                    } else {
                        sendMsg(ch, "PILOT_ACK:$reqId:$myCloud:❌ Target slot `$dstPkg` not found.")
                    }
                } else {
                    PilotCore.log("[XFER-OUT] Packed $srcUser (+$fileCount files) -> $dstCloud")
                    sendFile(ch, "PILOT_XFER:$reqId:$myCloud:$srcPkg:$srcUser:$dstCloud:$dstPkg", bundle, "xfer_${srcUser}.tar.gz")
                    bundle.delete()
                }
            }
        }
    }

    private fun handleTransferPayload(d: JSONObject) {
        val content = d.optString("content").trim()
        val ch = d.optString("channel_id")
        val msgId = d.optString("id")
        val parts = content.split(":", limit = 7)
        if (parts.size < 7) return
        val reqId = parts[1]
        val srcCloud = parts[2]
        val srcPkg = parts[3]
        val srcUser = parts[4]
        val dstCloud = parts[5]
        val dstPkg = parts[6]
        val myCloud = PilotCore.getCloud(ctx)
        if (!dstCloud.equals(myCloud, ignoreCase = true)) return

        val atts = d.optJSONArray("attachments") ?: return
        if (atts.length() == 0) return
        val fileUrl = atts.getJSONObject(0).optString("url")
        if (fileUrl.isEmpty()) return

        PilotCore.log("[XFER-IN] Receiving $srcUser from $srcCloud -> $dstPkg")
        val tmpBundle = File(ctx.cacheDir, "recv_${System.nanoTime()}.tar.gz")
        val downloaded = runCatching {
            val req = Request.Builder().url(fileUrl).build()
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@use false
                val bytes = resp.body?.bytes() ?: return@use false
                FileOutputStream(tmpBundle).use { it.write(bytes) }
                true
            }
        }.getOrDefault(false)

        if (!downloaded) {
            sendMsg(ch, "PILOT_ACK:$reqId:$myCloud:❌ Failed to download transfer bundle for **$srcUser**.")
            return
        }

        val (ok, scriptCount) = PilotCore.restoreAccountBundle(ctx, dstPkg, srcUser, tmpBundle)
        if (msgId.isNotEmpty()) apiDelete("https://discord.com/api/v10/channels/$ch/messages/$msgId")

        if (ok) {
            PilotCore.log("[XFER-OK] $srcUser restored to $dstPkg (+$scriptCount script files)")
            sendMsg(
                ch,
                "PILOT_ACK:$reqId:$myCloud:✅ Moved **$srcUser** from **$srcCloud** (`$srcPkg`) → **$myCloud** (`$dstPkg`) • `$scriptCount` script file(s) restored."
            )
        } else {
            sendMsg(ch, "PILOT_ACK:$reqId:$myCloud:❌ Target package `$dstPkg` not found.")
        }
    }

    private fun sendMsg(ch: String, content: String) {
        apiPost("https://discord.com/api/v10/channels/$ch/messages", JSONObject().put("content", content))
    }

    private fun sendFile(ch: String, caption: String, file: File, fileName: String) {
        runCatching {
            val mime = when {
                fileName.endsWith(".jpg", true) || fileName.endsWith(".jpeg", true) -> "image/jpeg"
                fileName.endsWith(".png", true) -> "image/png"
                else -> "application/gzip"
            }
            val mp = MultipartBody.Builder().setType(MultipartBody.FORM)
                .addFormDataPart("payload_json", JSONObject().put("content", caption).toString())
                .addFormDataPart("files[0]", fileName, file.asRequestBody(mime.toMediaType()))
                .build()
            val req = Request.Builder()
                .url("https://discord.com/api/v10/channels/$ch/messages")
                .header("Authorization", "Bot ${PilotCore.getToken(ctx)}")
                .post(mp)
                .build()
            client.newCall(req).execute().close()
        }
    }

    private fun apiDelete(url: String) {
        runCatching {
            val req = Request.Builder().url(url)
                .header("Authorization", "Bot ${PilotCore.getToken(ctx)}")
                .delete().build()
            client.newCall(req).execute().close()
        }
    }

    private fun apiPost(url: String, json: JSONObject): String = runCatching {
        val body = json.toString().toRequestBody("application/json".toMediaType())
        val req = Request.Builder().url(url)
            .header("Authorization", "Bot ${PilotCore.getToken(ctx)}")
            .post(body).build()
        client.newCall(req).execute().use { it.body?.string() ?: "" }
    }.getOrDefault("")
}

class PilotService : Service() {
    companion object { var isRunning = false }
    private var agent: PilotBridgeAgent? = null
    private var wl: PowerManager.WakeLock? = null
    private val syncExec = Executors.newSingleThreadScheduledExecutor()
    private val bridgePool = Executors.newFixedThreadPool(2)
    private var localServer: ServerSocket? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onCreate() {
        super.onCreate()
        isRunning = true
        val chId = "pilot_svc"
        if (Build.VERSION.SDK_INT >= 26) {
            val nm = getSystemService(NotificationManager::class.java)
            nm?.createNotificationChannel(NotificationChannel(chId, "Pilot Agent", NotificationManager.IMPORTANCE_LOW))
        }
        val notif = (if (Build.VERSION.SDK_INT >= 26) Notification.Builder(this, chId) else Notification.Builder(this))
            .setContentTitle("Pilot Agent (${PilotCore.getCloud(this)})")
            .setContentText("GVIP Optimized • Silent Bridge Active")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .build()
        startForeground(1, notif)

        wl = (getSystemService(POWER_SERVICE) as PowerManager).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Pilot::Lock").apply { acquire() }
        agent = PilotBridgeAgent(this).apply { start() }

        bridgePool.execute { PilotCore.installAutoExec(this) }

        var last: List<AccInfo>? = null
        syncExec.scheduleAtFixedRate({
            val cur = PilotCore.scanRbx(this)
            if (cur != last) {
                last = cur
                cur.forEach { PilotCore.log("[SYNC] ${it.pkg} -> ${it.user} (${it.status})") }
            }
        }, 1, 10, TimeUnit.SECONDS)

        syncExec.scheduleAtFixedRate({
            AppUpdater.checkAndInstall(this, force = false)
        }, 5, 30, TimeUnit.MINUTES)

        Thread {
            runCatching {
                localServer = ServerSocket(8765, 20, InetAddress.getByName("127.0.0.1"))
                while (isRunning) {
                    val sock = localServer?.accept() ?: break
                    bridgePool.execute {
                        runCatching {
                            val reqLine = sock.getInputStream().bufferedReader().readLine() ?: ""
                            sock.getOutputStream().write("HTTP/1.1 200 OK\r\nContent-Length: 2\r\n\r\nOK".toByteArray())
                            sock.close()
                            val userParam = Regex("""[?&]user=([^&\s]+)""").find(reqLine)?.groupValues?.get(1)
                            val ch = PilotCore.getSsChannel(this)
                            if (!userParam.isNullOrEmpty() && ch.isNotEmpty()) {
                                val user = URLDecoder.decode(userParam, "UTF-8")
                                PilotCore.log("[LUA-SS] $user")
                                agent?.sendAccountShot(ch, user)
                            }
                        }
                    }
                }
            }
        }.start()
    }

    override fun onDestroy() {
        isRunning = false
        runCatching { localServer?.close() }
        syncExec.shutdownNow()
        bridgePool.shutdownNow()
        agent?.stop()
        runCatching { if (wl?.isHeld == true) wl?.release() }
        super.onDestroy()
    }
}

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (PilotCore.getToken(context).isNotEmpty() && PilotCore.getUserId(context) != 0L) {
            val svc = Intent(context, PilotService::class.java)
            if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(svc) else context.startService(svc)
        }
    }
}