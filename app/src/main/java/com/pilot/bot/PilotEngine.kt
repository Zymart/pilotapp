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
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.random.Random

data class AccInfo(val pkg: String, val user: String, val status: String)
data class ShotRequest(
    val ch: String,
    val idx: Int,
    val acc: AccInfo,
    val markerPath: String,
    val latch: CountDownLatch = CountDownLatch(1),
    @Volatile var capturedFile: File? = null
)

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
                return "Already up to date (${PilotCore.APP_VERSION})."
            }

            PilotCore.log("[UPD] Downloading APK (current ${PilotCore.APP_VERSION})...")
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
                "Update installed! Restarting bot (was ${PilotCore.APP_VERSION})..."
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
                "Signature migrated! Reinstalling & restarting bot in 3s..."
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
    const val APP_VERSION = "v1.2.4"

    var logs = "Engine $APP_VERSION initialized.\n"
    var onLog: (() -> Unit)? = null
    @Volatile var cachedScan: List<AccInfo> = emptyList()

    val pendingShots = ConcurrentHashMap<String, ShotRequest>()
    val autoMarkers = ConcurrentHashMap<String, String>()

    fun log(msg: String) {
        logs = (logs + msg + "\n").takeLast(2400)
        onLog?.invoke()
    }

    private fun prefs(c: Context) = c.getSharedPreferences("pilot_cfg", Context.MODE_PRIVATE)
    private fun histPrefs(c: Context) = c.getSharedPreferences("pilot_hist", Context.MODE_PRIVATE)

    fun cleanBotToken(raw: String): String {
        return raw.replace("@", "").replace(Regex("""\s+"""), "").trim()
    }

    fun parseAllowedIds(raw: String): List<String> {
        return raw.split(",", ";", " ", "\n", "\t")
            .map { it.replace(Regex("""[^0-9]"""), "") }
            .filter { it.length >= 15 }
            .distinct()
    }

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

    fun getToken(c: Context) = cleanBotToken(prefs(c).getString("token", "") ?: "")

    fun getAllowedIdsRaw(c: Context): String {
        val saved = prefs(c).getString("allowed_ids", "") ?: ""
        if (saved.isNotEmpty()) return saved
        val legacyUid = prefs(c).getLong("user_id", 0L)
        return if (legacyUid != 0L) legacyUid.toString() else ""
    }

    fun getAllowedIds(c: Context): Set<String> {
        return parseAllowedIds(getAllowedIdsRaw(c)).toSet()
    }

    fun getPrimaryUserId(c: Context): Long {
        val ids = parseAllowedIds(getAllowedIdsRaw(c))
        return ids.firstOrNull()?.toLongOrNull() ?: prefs(c).getLong("user_id", 0L)
    }

    fun isAuthorized(c: Context, userId: String, roles: JSONArray?): Boolean {
        val allowed = getAllowedIds(c)
        if (allowed.isEmpty()) return false
        if (userId.isNotEmpty() && userId in allowed) return true
        if (roles != null) {
            for (i in 0 until roles.length()) {
                val r = roles.optString(i)
                if (r.isNotEmpty() && r in allowed) return true
            }
        }
        return false
    }

    fun getSsChannel(c: Context) = prefs(c).getString("ss_channel", "") ?: ""
    fun getUpdateUrl(c: Context) = prefs(c).getString("upd_url", "") ?: ""

    fun saveSsChannel(c: Context, ch: String) {
        prefs(c).edit().putString("ss_channel", ch.trim()).apply()
    }

    fun saveUpdateUrl(c: Context, url: String) {
        prefs(c).edit().putString("upd_url", url.trim()).apply()
    }

    fun saveConfig(c: Context, cloud: String, rawToken: String, rawIds: String, ssChan: String = "", updUrl: String = "") {
        val cl = cloud.trim().ifEmpty { detectDeviceName(c) }
        val cleanTok = cleanBotToken(rawToken)
        val idList = parseAllowedIds(rawIds)
        val joinedIds = idList.joinToString(",")
        val primaryUid = idList.firstOrNull()?.toLongOrNull() ?: 0L

        val edit = prefs(c).edit()
            .putString("cloud", cl)
            .putString("token", cleanTok)
            .putString("allowed_ids", joinedIds)
            .putLong("user_id", primaryUid)
            .putString("upd_url", updUrl.trim())
        if (ssChan.trim().isNotEmpty()) edit.putString("ss_channel", ssChan.trim())
        edit.apply()
        runCatching { File("/sdcard/cloud_name.txt").writeText(cl) }
    }

    fun makeCode(c: Context, autoRoot: Boolean = true): String {
        val letters = (1..7).map { ('A'..'Z').random() }.joinToString("")
        val payload = JSONObject()
            .put("t", getToken(c))
            .put("u", getPrimaryUserId(c))
            .put("ids", getAllowedIdsRaw(c))
            .put("c", getSsChannel(c))
            .put("d", getUpdateUrl(c))
            .put("ar", autoRoot)
            .toString()
        val b64 = Base64.encodeToString(payload.toByteArray(), Base64.URL_SAFE or Base64.NO_WRAP)
        return "$letters-$b64"
    }

    fun applyCode(c: Context, code: String): Pair<Boolean, Boolean> {
        val parsed = runCatching {
            val raw = code.trim().substringAfter("-")
            JSONObject(String(Base64.decode(raw, Base64.URL_SAFE)))
        }.getOrNull() ?: return false to false

        val tok = cleanBotToken(parsed.optString("t", ""))
        if (tok.isEmpty()) return false to false

        val rawIds = parsed.optString("ids", "").ifEmpty {
            val u = parsed.optLong("u", 0L)
            if (u != 0L) u.toString() else ""
        }
        val idList = parseAllowedIds(rawIds)
        val joinedIds = idList.joinToString(",")
        val primaryUid = idList.firstOrNull()?.toLongOrNull() ?: parsed.optLong("u", 0L)

        val edit = prefs(c).edit()
            .putString("token", tok)
            .putString("allowed_ids", joinedIds)
            .putLong("user_id", primaryUid)

        val ch = parsed.optString("c", "")
        if (ch.isNotEmpty()) edit.putString("ss_channel", ch)
        val upd = parsed.optString("d", "")
        if (upd.isNotEmpty()) edit.putString("upd_url", upd)
        edit.apply()

        val autoRoot = parsed.optBoolean("ar", true)
        var started = false
        if (autoRoot) {
            autoGrantRootAndPerms(c)
            if (getToken(c).isNotEmpty() && getAllowedIds(c).isNotEmpty()) {
                val svc = Intent(c, PilotService::class.java)
                runCatching {
                    if (Build.VERSION.SDK_INT >= 26) c.startForegroundService(svc) else c.startService(svc)
                    started = true
                }
            }
        }
        return true to started
    }

    @Synchronized
    fun autoGrantRootAndPerms(c: Context): Boolean {
        val idOut = SuShell.exec("id")
        if (!idOut.contains("uid=0")) {
            log("[ROOT] Root check failed")
            return false
        }
        val pkg = c.packageName
        val cmds = """
            dumpsys deviceidle whitelist +$pkg >/dev/null 2>&1
            cmd appops set $pkg RUN_IN_BACKGROUND allow >/dev/null 2>&1
            cmd appops set $pkg RUN_ANY_IN_BACKGROUND allow >/dev/null 2>&1
            pm grant $pkg android.permission.POST_NOTIFICATIONS >/dev/null 2>&1
            pm grant $pkg android.permission.READ_EXTERNAL_STORAGE >/dev/null 2>&1
            pm grant $pkg android.permission.WRITE_EXTERNAL_STORAGE >/dev/null 2>&1
        """.trimIndent()
        SuShell.exec(cmds)
        installAutoExec(c)
        log("[ROOT] Auto-granted root, battery whitelist & autoexec")
        return true
    }

    @Synchronized
    fun installAutoExec(c: Context) {
        val lua = """
            repeat task.wait(0.5) until game:IsLoaded()
            local Players = game:GetService("Players")
            local CaptureService = game:GetService("CaptureService")
            local HttpService = game:GetService("HttpService")

            local lp = Players.LocalPlayer
            while not lp do
                task.wait(0.5)
                lp = Players.LocalPlayer
            end

            local req = request or http_request or (syn and syn.request)
            if not req then return end

            local capturing = false

            local function notifyPilot(path)
                pcall(function()
                    req({
                        Url = "http://127.0.0.1:8765" .. path,
                        Method = "GET"
                    })
                end)
            end

            local function onCaptured(contentId, captureObj)
                pcall(function()
                    if contentId and CaptureService.SaveCaptureToExternalStorage then
                        CaptureService:SaveCaptureToExternalStorage(contentId)
                    elseif captureObj and CaptureService.SaveCapturesToExternalStorageAsync then
                        CaptureService:SaveCapturesToExternalStorageAsync({ captureObj })
                    end
                end)
                task.wait(0.35)
                notifyPilot("/done?user=" .. HttpService:UrlEncode(lp.Name) .. "&cid=" .. HttpService:UrlEncode(tostring(contentId or "")))
                capturing = false
            end

            local function takeRobloxScreenshot(isAuto)
                if capturing then return end
                capturing = true
                if isAuto then
                    notifyPilot("/pre_auto?user=" .. HttpService:UrlEncode(lp.Name))
                end

                local ok = pcall(function()
                    if CaptureService.CaptureScreenshot then
                        CaptureService:CaptureScreenshot(function(contentId)
                            if contentId and tostring(contentId) ~= "" then
                                onCaptured(contentId, nil)
                            else
                                capturing = false
                                notifyPilot("/fail?user=" .. HttpService:UrlEncode(lp.Name))
                            end
                        end)
                    elseif CaptureService.TakeScreenshotCaptureAsync then
                        CaptureService:TakeScreenshotCaptureAsync(function(result, screenshotCapture)
                            if result == Enum.ScreenshotCaptureResult.Success and screenshotCapture then
                                onCaptured(screenshotCapture.ContentId, screenshotCapture)
                            else
                                capturing = false
                                notifyPilot("/fail?user=" .. HttpService:UrlEncode(lp.Name))
                            end
                        end, {
                            UICaptureMode = Enum.UICaptureMode.All
                        })
                    else
                        capturing = false
                        notifyPilot("/fail?user=" .. HttpService:UrlEncode(lp.Name))
                    end
                end)

                if not ok then
                    capturing = false
                    notifyPilot("/fail?user=" .. HttpService:UrlEncode(lp.Name))
                end
            end

            getgenv().PilotSnap = function()
                takeRobloxScreenshot(true)
            end

            task.spawn(function()
                while task.wait(1.0) do
                    local ok, res = pcall(function()
                        return req({
                            Url = "http://127.0.0.1:8765/poll?user=" .. HttpService:UrlEncode(lp.Name),
                            Method = "GET"
                        })
                    end)
                    if ok and res and res.Body == "SNAP" then
                        takeRobloxScreenshot(false)
                    end
                end
            end)

            task.spawn(function()
                task.wait(6)
                takeRobloxScreenshot(true)
                while task.wait(3600) do
                    takeRobloxScreenshot(true)
                end
            end)
        """.trimIndent()

        runCatching {
            val tmp = File(c.filesDir, "pilot_bridge.lua")
            tmp.writeText(lua)

            val sh = """
                mkdir -p /storage/emulated/0/Delta/AutoExcecute /storage/emulated/0/Delta/Autoexecute 2>/dev/null
                find -L /storage/emulated/0 /data/data -maxdepth 6 -type d \( -iname '*autoexecute*' -o -iname '*autoexcecute*' \) 2>/dev/null
            """.trimIndent()
            val found = SuShell.exec(sh).lines().map { it.trim() }.filter { it.isNotEmpty() }
            val dirs = (listOf(
                "/storage/emulated/0/Delta/AutoExcecute",
                "/storage/emulated/0/Delta/Autoexecute"
            ) + found).toSet()

            for (d in dirs) {
                SuShell.exec("mkdir -p '$d' && cp '${tmp.absolutePath}' '$d/pilot_bridge.lua' && chmod 666 '$d/pilot_bridge.lua'")
            }
            log("[AUTOEXEC] CaptureService bridge ready in ${dirs.size} folder(s)")
        }
    }

    fun createMarker(): String {
        val path = "/data/local/tmp/pilot_mark_${System.nanoTime()}"
        SuShell.exec("touch '$path'")
        return path
    }

    /**
     * Grabs the in-game image written by Roblox's built-in CaptureService—checking both
     * external gallery folders (/sdcard/Pictures/Roblox) and Roblox's internal rbxtemp://
     * cache (/data/data/<pkg>/cache & files), detecting PNG/JPEG headers even without extensions.
     */
    @Synchronized
    fun grabLatestRobloxCapture(c: Context, pkg: String, markerPath: String): File? {
        val destFile = File(c.cacheDir, "rbx_cap_${System.nanoTime()}.png")
        val sh = """
            found=""
            for dir in /storage/emulated/0/Pictures/Roblox /storage/emulated/0/DCIM/Roblox /storage/emulated/0/Pictures /storage/emulated/0/DCIM; do
              [ -d "${'$'}dir" ] || continue
              for f in ${'$'}(find -L "${'$'}dir" -maxdepth 2 -type f -newer '$markerPath' 2>/dev/null); do
                hdr=${'$'}(od -An -tx1 -N4 "${'$'}f" 2>/dev/null | tr -d ' \n')
                case "${'$'}hdr" in
                  89504e47*|ffd8ff*) found="${'$'}f"; break ;;
                esac
              done
              [ -n "${'$'}found" ] && break
            done

            if [ -z "${'$'}found" ] && [ -n "$pkg" ]; then
              for f in ${'$'}(find "/data/data/$pkg/cache" "/data/data/$pkg/files" -maxdepth 4 -type f -newer '$markerPath' 2>/dev/null); do
                sz=${'$'}(stat -c '%s' "${'$'}f" 2>/dev/null || echo 0)
                [ "${'$'}sz" -gt 4096 ] || continue
                hdr=${'$'}(od -An -tx1 -N4 "${'$'}f" 2>/dev/null | tr -d ' \n')
                case "${'$'}hdr" in
                  89504e47*|ffd8ff*) found="${'$'}f"; break ;;
                esac
              done
            fi

            rm -f '$markerPath'
            if [ -n "${'$'}found" ] && [ -f "${'$'}found" ]; then
              cp "${'$'}found" '${destFile.absolutePath}'
              chmod 666 '${destFile.absolutePath}'
              case "${'$'}found" in
                /storage/emulated/0/*) rm -f "${'$'}found" ;;
              esac
              echo "FOUND"
            fi
        """.trimIndent()

        val out = SuShell.exec(sh)
        return if (out.contains("FOUND") && destFile.exists() && destFile.length() > 4096L) destFile else null
    }

    /**
     * Fallback window-bounds cropper when an account is still on the login screen (before Autoexecute runs).
     * Ignores full-display buffer rects (e.g., 1280x720) so floating multi-window bounds are chosen accurately.
     */
    fun findWindowBounds(pkg: String, fullW: Int, fullH: Int): Rect? {
        val dump = SuShell.exec("dumpsys window windows 2>/dev/null")
        if (dump.isEmpty()) return null

        val pkgRx = Regex("""\b${Regex.escape(pkg)}\b""")
        val bracketRx = Regex("""\[(\d+),(\d+)\]\[(\d+),(\d+)\]""")
        var bestFloating: Rect? = null
        var bestFloatingArea = 0

        for (block in dump.split("Window #")) {
            if (!pkgRx.containsMatchIn(block)) continue
            if (block.contains("mHasSurface=false") || block.contains("mViewVisibility=0x8")) continue

            for (line in block.lines()) {
                val trimmed = line.trim()
                if (!trimmed.contains("mFrame=") && !trimmed.contains("mBounds=") &&
                    !trimmed.contains("content=") && !trimmed.contains("frame=")) continue

                val bm = bracketRx.find(trimmed) ?: continue
                val l = bm.groupValues[1].toIntOrNull() ?: 0
                val t = bm.groupValues[2].toIntOrNull() ?: 0
                val r = bm.groupValues[3].toIntOrNull() ?: 0
                val b = bm.groupValues[4].toIntOrNull() ?: 0
                val w = r - l
                val h = b - t
                // Ignore full-screen display frames; pick actual floating window bounds
                if (w >= 150 && h >= 150 && (w < fullW - 10 || h < fullH - 10)) {
                    val area = w * h
                    if (area > bestFloatingArea) {
                        bestFloatingArea = area
                        bestFloating = Rect(l, t, r, b)
                    }
                }
            }
        }

        if (bestFloating != null && bestFloating.height() > 240) {
            return Rect(bestFloating.left, bestFloating.top + 38, bestFloating.right, bestFloating.bottom)
        }
        return bestFloating
    }

    @Synchronized
    fun captureScreen(c: Context, pkg: String? = null): Pair<File?, Boolean> {
        val rawPath = File(c.cacheDir, "raw.png").absolutePath
        val outPath = File(c.cacheDir, "crop.jpg").absolutePath
        SuShell.exec("screencap -p '$rawPath' && chmod 666 '$rawPath'")
        val rawFile = File(rawPath)
        if (!rawFile.exists() || rawFile.length() == 0L) return null to false

        if (pkg.isNullOrEmpty()) return rawFile to true

        return runCatching {
            val opts = BitmapFactory.Options().apply {
                inPreferredConfig = Bitmap.Config.RGB_565
            }
            val full = BitmapFactory.decodeFile(rawPath, opts) ?: return rawFile to false
            val w = full.width
            val h = full.height

            val bounds = findWindowBounds(pkg, w, h)
            if (bounds == null) {
                full.recycle()
                return rawFile to false
            }

            val l = bounds.left.coerceIn(0, w - 1)
            val t = bounds.top.coerceIn(0, h - 1)
            val r = bounds.right.coerceIn(l + 1, w)
            val b = bounds.bottom.coerceIn(t + 1, h)

            val cropped = Bitmap.createBitmap(full, l, t, r - l, b - t)
            full.recycle()

            val outFile = File(outPath)
            FileOutputStream(outFile).use { cropped.compress(Bitmap.CompressFormat.JPEG, 90, it) }
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

    fun fmtAll(list: List<AccInfo>) = list.mapIndexed { i, a -> fmtLine(i, a) }.joinToString("\n")

    fun similarity(s1: String, s2: String): Double {
        val longer = if (s1.length >= s2.length) s1 else s2
        val shorter = if (s1.length >= s2.length) s2 else s1
        if (longer.isEmpty()) return 1.0
        val costs = IntArray(shorter.length + 1) { it }
        for (i in 1..longer.length) {
            var lastVal = i
            for (j in 1..shorter.length) {
                val newVal = if (longer[i - 1] == shorter[j - 1]) costs[j - 1]
                else min(min(costs[j - 1], lastVal), costs[j]) + 1
                costs[j - 1] = lastVal
                lastVal = newVal
            }
            costs[shorter.length] = lastVal
        }
        return (longer.length - costs[shorter.length]) / longer.length.toDouble()
    }
}

class DiscordBot(private val ctx: Context) {
    private val client = OkHttpClient.Builder().readTimeout(0, TimeUnit.MILLISECONDS).build()
    private val exec = Executors.newSingleThreadScheduledExecutor()
    private val ioExec = Executors.newFixedThreadPool(3)
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

    fun captureAndSendAll(ch: String) {
        val cl = PilotCore.getCloud(ctx)
        val list = PilotCore.cachedScan.ifEmpty { PilotCore.scanRbx(ctx) }
        val summary = if (list.isNotEmpty()) "\n${PilotCore.fmtAll(list)}" else ""
        val (img, _) = PilotCore.captureScreen(ctx, null)
        if (img != null) {
            sendImage(ch, "**📱 $cl** (${PilotCore.APP_VERSION}) — Full Screen:$summary", img)
            img.delete()
        }
    }

    // !checkstatus <username> — triggers Roblox built-in CaptureService inside that player's client
    fun sendAccountShot(ch: String, triggerMsgId: String, query: String) {
        val cl = PilotCore.getCloud(ctx)
        val list = PilotCore.cachedScan.ifEmpty { PilotCore.scanRbx(ctx) }
        val q = query.trim().lowercase()
        var matches = list.mapIndexedNotNull { i, a ->
            if (a.user != "Empty" && a.user.lowercase().contains(q)) i to a else null
        }
        if (matches.isEmpty()) {
            matches = list.mapIndexedNotNull { i, a ->
                if (a.user != "Empty" && PilotCore.similarity(q, a.user.lowercase()) >= 0.45) i to a else null
            }
        }

        if (matches.isEmpty()) {
            runCatching { Thread.sleep(2000L + (abs(cl.hashCode()) % 12) * 100L) }
            if (!hasBotRepliedAfter(ch, triggerMsgId)) {
                sendMsg(ch, "⚠️ No matching account found for `$query` on any online cloud phone.")
            }
            return
        }

        for ((idx, acc) in matches) {
            val key = acc.user.lowercase()
            val marker = PilotCore.createMarker()
            val req = ShotRequest(ch, idx, acc, marker)
            PilotCore.pendingShots[key] = req

            val gotLua = runCatching { req.latch.await(6000, TimeUnit.MILLISECONDS) }.getOrDefault(false)
            PilotCore.pendingShots.remove(key)

            val capFile = req.capturedFile
            if (gotLua && capFile != null && capFile.exists()) {
                val caption = "**📱 $cl** — In-Game Capture for `${acc.user}`:\n${PilotCore.fmtLine(idx, acc)}"
                sendImage(ch, caption, capFile)
                capFile.delete()
            } else {
                SuShell.exec("rm -f '$marker'")
                val (img, croppedOk) = PilotCore.captureScreen(ctx, acc.pkg)
                val note = if (!croppedOk) "\n*(Player not in-game yet — showing full screen)*" else "\n*(Window crop)*"
                val caption = "**📱 $cl** — Status for `${acc.user}`:\n${PilotCore.fmtLine(idx, acc)}$note"
                if (img != null) {
                    sendImage(ch, caption, img)
                    img.delete()
                } else {
                    sendMsg(ch, caption)
                }
            }
        }
    }

    fun sendAutoCaptureToChannel(ch: String, exactUser: String, capFile: File?) {
        val cl = PilotCore.getCloud(ctx)
        val list = PilotCore.cachedScan.ifEmpty { PilotCore.scanRbx(ctx) }
        val q = exactUser.trim().lowercase()
        val match = list.mapIndexedNotNull { i, a ->
            if (a.user != "Empty" && a.user.lowercase() == q) i to a else null
        }.firstOrNull() ?: return

        val (idx, acc) = match
        if (capFile != null && capFile.exists()) {
            val caption = "**📱 $cl** — In-Game Capture for `${acc.user}`:\n${PilotCore.fmtLine(idx, acc)}"
            sendImage(ch, caption, capFile)
            capFile.delete()
        } else {
            val (img, croppedOk) = PilotCore.captureScreen(ctx, acc.pkg)
            val note = if (!croppedOk) "\n*(Window minimized/hidden — showing full screen)*" else ""
            val caption = "**📱 $cl** — Status for `${acc.user}`:\n${PilotCore.fmtLine(idx, acc)}$note"
            if (img != null) {
                sendImage(ch, caption, img)
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
            override fun onClosed(w: WebSocket, code: Int, reason: String) { scheduleReconnect() }
            override fun onFailure(w: WebSocket, t: Throwable, response: Response?) { scheduleReconnect() }
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
                    .put("properties", JSONObject().put("os", "android").put("browser", "pilot").put("device", "pilot")))
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
                    PilotCore.log("[ONLINE] $u | ${PilotCore.getCloud(ctx)} (${PilotCore.APP_VERSION})")
                } else if (type == "MESSAGE_CREATE") onMessageCreate(d)
                else if (type == "INTERACTION_CREATE") onInteraction(d)
            }
        }
    }

    private fun onMessageCreate(d: JSONObject) {
        val author = d.optJSONObject("author") ?: return
        val authorId = author.optString("id")
        val memberRoles = d.optJSONObject("member")?.optJSONArray("roles")
        val content = d.optString("content").trim()
        val ch = d.getString("channel_id")

        if (authorId == botId) {
            if (content.startsWith("PILOT_PACK_REQ:")) {
                ioExec.execute { handlePackRequest(d) }
            } else if (content.startsWith("PILOT_XFER:")) {
                ioExec.execute { handleTransferPayload(d) }
            }
            return
        }

        if (!PilotCore.isAuthorized(ctx, authorId, memberRoles)) return
        if (!content.startsWith("!")) return
        val msgId = d.optString("id", "")
        val cl = PilotCore.getCloud(ctx)
        val parts = content.split(" ", limit = 2)
        val cmd = parts[0].lowercase()
        val arg = parts.getOrNull(1)?.trim() ?: ""

        when (cmd) {
            "!help", "!commands" -> ioExec.execute { sendHelp(ch, msgId) }
            "!version", "!ver" -> ioExec.execute {
                upsertSharedTextLine(ch, msgId, "**📱 Online Pilot Instances:**", "• **$cl** — `${PilotCore.APP_VERSION}`")
            }
            "!createconfig", "!newconfig" -> ioExec.execute {
                val delayMs = (abs(cl.hashCode()) % 14) * 110L
                if (delayMs > 0) runCatching { Thread.sleep(delayMs) }
                if (hasBotRepliedAfter(ch, msgId)) return@execute
                val btn = JSONObject()
                    .put("type", 2)
                    .put("style", 1)
                    .put("label", "⚙️ Open Config Builder")
                    .put("custom_id", "pilot_cfg_btn:open:$cl")
                val row = JSONArray().put(JSONObject().put("type", 1).put("components", JSONArray().put(btn)))
                sendMsg(
                    ch,
                    "**🛠️ Pilot Sync Config Builder**\nClick the button below to enter your **Bot Token** (include one `@` inside to bypass Discord's scanner) and **Allowed User/Role ID(s)** (comma-separated):",
                    row
                )
            }
            "!scan" -> ioExec.execute {
                upsertSharedCloudMenu(ch, msgId, "s", "**Select Cloud to Scan:**")
            }
            "!logout" -> ioExec.execute {
                upsertSharedCloudMenu(ch, msgId, "l", "**Select Cloud to Logout:**")
            }
            "!delete" -> ioExec.execute {
                upsertSharedCloudMenu(ch, msgId, "d", "**Select Cloud to Delete Account & History:**")
            }
            "!changeslot" -> ioExec.execute {
                if (arg.isEmpty()) {
                    upsertSharedCloudMenu(ch, msgId, "cs1", "**Select Source Cloud (where the account is now):**")
                } else {
                    val list = PilotCore.cachedScan.ifEmpty { PilotCore.scanRbx(ctx) }
                    val match = list.firstOrNull { it.user != "Empty" && it.user.equals(arg, ignoreCase = true) }
                    if (match != null) {
                        val header = "**📦 Moving `${match.user}`** (from **$cl** `${match.pkg}`)\nSelect Destination Cloud Phone:"
                        val sentRes = apiPost("https://discord.com/api/v10/channels/$ch/messages", JSONObject().put("content", header))
                        val anchorId = runCatching { JSONObject(sentRes).getString("id") }.getOrDefault(msgId)
                        upsertSharedCloudMenu(ch, anchorId, "cs_dst:$cl:${match.pkg}:${match.user}", header)
                    }
                }
            }
            "!rename" -> {
                if (arg.isEmpty()) {
                    ioExec.execute {
                        if (!hasBotRepliedAfter(ch, msgId)) sendMsg(ch, "⚠️ **Missing name!** Usage: `!rename <New Cloud Name>`")
                    }
                } else {
                    ioExec.execute {
                        upsertSharedCloudMenu(ch, msgId, "r:$arg", "**Select Cloud to Rename to `$arg`:**")
                    }
                }
            }
            "!update" -> {
                if (arg.isNotEmpty()) PilotCore.saveUpdateUrl(ctx, arg)
                ioExec.execute {
                    val status = AppUpdater.checkAndInstall(ctx, force = true)
                    upsertSharedTextLine(ch, msgId, "**🔄 Pilot OTA Update:**", "• **$cl** — $status")
                }
            }
            "!sschannel" -> {
                PilotCore.saveSsChannel(ctx, ch)
                ioExec.execute {
                    upsertSharedTextLine(ch, msgId, "✅ **Screenshot Channel Updated:**", "• **$cl** — synced")
                }
            }
            "!ss" -> ioExec.execute {
                if (arg.equals("all", ignoreCase = true)) {
                    captureAndSendAll(ch)
                } else {
                    upsertSharedCloudMenu(ch, msgId, "ss", "**Select Cloud to Screenshot:**")
                }
            }
            "!checkstatus" -> {
                if (arg.isEmpty()) {
                    ioExec.execute {
                        if (!hasBotRepliedAfter(ch, msgId)) sendMsg(ch, "⚠️ **Missing username!** Usage: `!checkstatus <username>`")
                    }
                } else {
                    ioExec.execute { sendAccountShot(ch, msgId, arg) }
                }
            }
            "!search" -> {
                if (arg.isEmpty()) {
                    ioExec.execute {
                        if (!hasBotRepliedAfter(ch, msgId)) sendMsg(ch, "⚠️ **Missing username!** Usage: `!search <username>`")
                    }
                    return
                }
                ioExec.execute {
                    val list = PilotCore.cachedScan.ifEmpty { PilotCore.scanRbx(ctx) }
                    val q = arg.lowercase()
                    val hits = list.mapIndexedNotNull { i, a ->
                        if (a.user != "Empty" && (a.user.lowercase().contains(q) || PilotCore.similarity(q, a.user.lowercase()) >= 0.45)) {
                            "**📱 $cl** — ${PilotCore.fmtLine(i, a)}"
                        } else null
                    }
                    if (hits.isNotEmpty()) {
                        upsertSharedTextLine(ch, msgId, "🔍 **Search Results for `$arg`:**", hits.joinToString("\n"))
                    } else {
                        runCatching { Thread.sleep(2000L + (abs(cl.hashCode()) % 12) * 100L) }
                        if (!hasBotRepliedAfter(ch, msgId)) {
                            sendMsg(ch, "⚠️ No matching account found for `$arg` on any online cloud phone.")
                        }
                    }
                }
            }
            "!config" -> {
                ioExec.execute {
                    val delayMs = (abs(cl.hashCode()) % 12) * 110L
                    if (delayMs > 0) runCatching { Thread.sleep(delayMs) }
                    if (hasBotRepliedAfter(ch, msgId)) return@execute
                    val code = PilotCore.makeCode(ctx, autoRoot = true)
                    val dmRes = apiPost("https://discord.com/api/v10/users/@me/channels", JSONObject().put("recipient_id", authorId))
                    val dmCh = runCatching { JSONObject(dmRes).getString("id") }.getOrDefault("")
                    if (dmCh.isNotEmpty()) {
                        sendMsg(dmCh, "**Your Pilot Sync Code:**\n`$code`\n```\n$code\n```")
                        sendMsg(ch, "Sent your new config code to your DMs!")
                    }
                }
            }
            else -> ioExec.execute {
                val delayMs = (abs(cl.hashCode()) % 12) * 110L
                if (delayMs > 0) runCatching { Thread.sleep(delayMs) }
                if (!hasBotRepliedAfter(ch, msgId)) {
                    sendMsg(ch, "⚠️ **Invalid command!** Type `!help` to view all available commands.")
                }
            }
        }
    }

    @Synchronized
    private fun upsertSharedCloudMenu(ch: String, anchorMsgId: String, mode: String, title: String) {
        val myCloud = PilotCore.getCloud(ctx)
        val jitterMs = (abs(myCloud.hashCode()) % 14) * 115L
        if (jitterMs > 0) runCatching { Thread.sleep(jitterMs) }

        val myOption = JSONObject()
            .put("label", "📱 $myCloud")
            .put("value", myCloud)
            .put("description", "${PilotCore.APP_VERSION} • Tap to select")

        var existing = findCanonicalBotMessage(ch, anchorMsgId, title)
        if (existing == null) {
            val menu = JSONObject()
                .put("type", 3)
                .put("custom_id", "pilot_cloud:$mode:row0")
                .put("placeholder", "Select Cloud ($myCloud)...")
                .put("options", JSONArray().put(myOption))
            val row = JSONArray().put(JSONObject().put("type", 1).put("components", JSONArray().put(menu)))
            val postedRaw = apiPost("https://discord.com/api/v10/channels/$ch/messages", JSONObject().put("content", title).put("components", row))
            val postedId = runCatching { JSONObject(postedRaw).getString("id") }.getOrDefault("")

            runCatching { Thread.sleep(320) }
            val canonical = findCanonicalBotMessage(ch, anchorMsgId, title)
            if (canonical != null && postedId.isNotEmpty() && canonical.getString("id") != postedId) {
                apiDelete("https://discord.com/api/v10/channels/$ch/messages/$postedId")
                existing = canonical
            } else {
                return
            }
        }

        repeat(2) { attempt ->
            val target = if (attempt == 0) existing!! else (findCanonicalBotMessage(ch, anchorMsgId, title) ?: return)
            val targetId = target.getString("id")
            val optMap = LinkedHashMap<String, JSONObject>()

            val oldRows = target.optJSONArray("components")
            if (oldRows != null) {
                for (r in 0 until oldRows.length()) {
                    val comps = oldRows.getJSONObject(r).optJSONArray("components") ?: continue
                    for (c in 0 until comps.length()) {
                        val opts = comps.getJSONObject(c).optJSONArray("options") ?: continue
                        for (o in 0 until opts.length()) {
                            val obj = opts.getJSONObject(o)
                            val v = obj.optString("value")
                            if (v.isNotEmpty()) optMap[v] = obj
                        }
                    }
                }
            }
            optMap[myCloud] = myOption

            val sortedOpts = optMap.values.sortedWith { a, b ->
                a.optString("value").compareTo(b.optString("value"), ignoreCase = true)
            }

            val rows = JSONArray()
            val chunks = sortedOpts.chunked(25).take(5)
            chunks.forEachIndexed { idx, chunk ->
                val arr = JSONArray()
                chunk.forEach { arr.put(it) }
                val ph = if (chunks.size > 1) {
                    "Select Cloud (${idx * 25 + 1}–${idx * 25 + chunk.size})..."
                } else {
                    "Select Cloud (${sortedOpts.size} online)..."
                }
                val menu = JSONObject()
                    .put("type", 3)
                    .put("custom_id", "pilot_cloud:$mode:row$idx")
                    .put("placeholder", ph)
                    .put("options", arr)
                rows.put(JSONObject().put("type", 1).put("components", JSONArray().put(menu)))
            }

            apiPatch("https://discord.com/api/v10/channels/$ch/messages/$targetId", JSONObject().put("content", title).put("components", rows))
            runCatching { Thread.sleep(280) }
            val verify = findCanonicalBotMessage(ch, anchorMsgId, title)
            if (verify != null && verify.toString().contains("\"value\":\"$myCloud\"")) return
        }
    }

    @Synchronized
    private fun upsertSharedTextLine(ch: String, anchorMsgId: String, header: String, myLine: String) {
        val myCloud = PilotCore.getCloud(ctx)
        val jitterMs = (abs(myCloud.hashCode()) % 14) * 115L
        if (jitterMs > 0) runCatching { Thread.sleep(jitterMs) }

        var existing = findCanonicalBotMessage(ch, anchorMsgId, header)
        if (existing == null) {
            val body = "$header\n$myLine"
            val postedRaw = apiPost("https://discord.com/api/v10/channels/$ch/messages", JSONObject().put("content", body))
            val postedId = runCatching { JSONObject(postedRaw).getString("id") }.getOrDefault("")

            runCatching { Thread.sleep(320) }
            val canonical = findCanonicalBotMessage(ch, anchorMsgId, header)
            if (canonical != null && postedId.isNotEmpty() && canonical.getString("id") != postedId) {
                apiDelete("https://discord.com/api/v10/channels/$ch/messages/$postedId")
                existing = canonical
            } else {
                return
            }
        }

        repeat(2) { attempt ->
            val target = if (attempt == 0) existing!! else (findCanonicalBotMessage(ch, anchorMsgId, header) ?: return)
            val targetId = target.getString("id")
            val curLines = target.optString("content").lines()
                .drop(1)
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .toMutableList()

            curLines.removeAll { it.contains("**$myCloud**") }
            curLines.add(myLine)
            val sorted = curLines.sortedWith { a, b -> a.compareTo(b, ignoreCase = true) }
            val newContent = "$header\n${sorted.joinToString("\n")}".take(1950)

            apiPatch("https://discord.com/api/v10/channels/$ch/messages/$targetId", JSONObject().put("content", newContent))
            runCatching { Thread.sleep(280) }
            val verify = findCanonicalBotMessage(ch, anchorMsgId, header)
            if (verify != null && verify.optString("content").contains("**$myCloud**")) return
        }
    }

    private fun findCanonicalBotMessage(ch: String, anchorMsgId: String, prefix: String): JSONObject? {
        if (botId.isEmpty() || anchorMsgId.isEmpty()) return null
        val raw = apiGet("https://discord.com/api/v10/channels/$ch/messages?after=$anchorMsgId&limit=15")
        return runCatching {
            val arr = JSONArray(raw)
            val matches = mutableListOf<JSONObject>()
            for (i in 0 until arr.length()) {
                val m = arr.getJSONObject(i)
                if (m.optJSONObject("author")?.optString("id") == botId && m.optString("content").startsWith(prefix)) {
                    matches.add(m)
                }
            }
            matches.minByOrNull { it.optString("id").toLongOrNull() ?: Long.MAX_VALUE }
        }.getOrNull()
    }

    private fun hasBotRepliedAfter(ch: String, triggerMsgId: String): Boolean {
        if (botId.isEmpty() || triggerMsgId.isEmpty()) return false
        val afterRaw = apiGet("https://discord.com/api/v10/channels/$ch/messages?after=$triggerMsgId&limit=8")
        return runCatching {
            val arr = JSONArray(afterRaw)
            (0 until arr.length()).any { i ->
                arr.getJSONObject(i).optJSONObject("author")?.optString("id") == botId
            }
        }.getOrDefault(false)
    }

    private fun handlePackRequest(d: JSONObject) {
        val content = d.optString("content").trim()
        val ch = d.optString("channel_id")
        val msgId = d.optString("id")
        val parts = content.split(":", limit = 6)
        if (parts.size < 6) return
        val srcCloud = parts[1]
        val srcPkg = parts[2]
        val srcUser = parts[3]
        val dstCloud = parts[4]
        val dstPkg = parts[5]
        val myCloud = PilotCore.getCloud(ctx)
        if (!srcCloud.equals(myCloud, ignoreCase = true)) return

        if (msgId.isNotEmpty()) apiDelete("https://discord.com/api/v10/channels/$ch/messages/$msgId")

        val (bundle, fileCount) = PilotCore.packAccountBundle(ctx, srcPkg, srcUser)
        PilotCore.wipe(ctx, srcPkg, true)

        if (bundle == null) {
            sendMsg(ch, "❌ Failed to package **$srcUser** from **$myCloud**.")
            return
        }

        if (dstCloud.equals(myCloud, ignoreCase = true)) {
            val (ok, restoredFiles) = PilotCore.restoreAccountBundle(ctx, dstPkg, srcUser, bundle)
            if (ok) sendMsg(ch, "✅ Moved **$srcUser** locally on **$myCloud**: `$srcPkg` → `$dstPkg` (`$restoredFiles` script file(s)).")
            else sendMsg(ch, "❌ Target slot `$dstPkg` not found on **$myCloud**.")
        } else {
            PilotCore.log("[XFER-OUT] Packed $srcUser (+$fileCount files) -> $dstCloud")
            sendFile(ch, "PILOT_XFER:go:$myCloud:$srcPkg:$srcUser:$dstCloud:$dstPkg", bundle, "xfer_${srcUser}.tar.gz")
            bundle.delete()
        }
    }

    private fun handleTransferPayload(d: JSONObject) {
        val content = d.optString("content").trim()
        val ch = d.optString("channel_id")
        val msgId = d.optString("id")
        val parts = content.split(":", limit = 7)
        if (parts.size < 7) return
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
            sendMsg(ch, "❌ **📱 $myCloud** — Failed to download transfer bundle for **$srcUser**.")
            return
        }

        val (ok, scriptCount) = PilotCore.restoreAccountBundle(ctx, dstPkg, srcUser, tmpBundle)
        if (msgId.isNotEmpty()) apiDelete("https://discord.com/api/v10/channels/$ch/messages/$msgId")

        if (ok) {
            PilotCore.log("[XFER-OK] $srcUser restored to $dstPkg (+$scriptCount script files)")
            sendMsg(
                ch,
                "✅ **Slot Transfer Complete!**\n" +
                "• **Account:** `$srcUser`\n" +
                "• **From:** **$srcCloud** (`$srcPkg` → now Empty)\n" +
                "• **To:** **$myCloud** (`$dstPkg`)\n" +
                "• **Script Data Restored:** `$scriptCount` file(s)"
            )
        } else {
            sendMsg(ch, "❌ **📱 $myCloud** — Target package `$dstPkg` not found on this cloud phone.")
        }
    }

    private fun sendHelp(ch: String, triggerMsgId: String) {
        val delayMs = (abs(PilotCore.getCloud(ctx).hashCode()) % 14) * 110L
        if (delayMs > 0) runCatching { Thread.sleep(delayMs) }
        if (hasBotRepliedAfter(ch, triggerMsgId)) return

        val helpText = """
            **📖 PILOT BOT COMMANDS (${PilotCore.APP_VERSION})**

            **🔍 Monitoring & Search**
            • `!scan` — Select a cloud phone to list all Roblox instances & statuses
            • `!search <username>` — Find which cloud & slot has an account (exact or close match)
            • `!checkstatus <username>` — Capture an **in-game** screenshot via Roblox `CaptureService`
            • `!version` — Show the installed Pilot version on every online cloud phone

            **📸 Screenshots & Auto-Capture**
            • `!ss` — Select a cloud phone for a full-screen screenshot (or `!ss all`)
            • `!sschannel` — Set this channel for hourly Delta auto-screenshots & Lua triggers
            • *Lua Trigger:* Call `getgenv().PilotSnap()` inside any Delta script

            **⚙️ Account & Cloud Management**
            • `!createconfig` — Build a new `XXXXXXX-...` Sync Code (supports `@` token bypass & multiple User/Role IDs)
            • `!changeslot [username]` — Move an account + its script workspace files to another slot/cloud
            • `!logout` — Log out one or all accounts on a cloud (keeps last username history)
            • `!delete` — Wipe session data **and** clear username history (resets to Empty)
            • `!rename <new name>` — Remotely rename a cloud phone
            • `!update [drive_url]` — Check & silently install the latest Pilot APK
            • `!config` — DM yourself your current `XXXXXXX-...` sync code
            • `!help` — Show this command guide
        """.trimIndent()

        sendMsg(ch, helpText)
    }

    private fun openCreateConfigModal(id: String, token: String, targetCloud: String) {
        val fToken = JSONObject()
            .put("type", 4)
            .put("custom_id", "token")
            .put("label", "Bot Token (put 1 @ inside to bypass)")
            .put("style", 1)
            .put("min_length", 20)
            .put("max_length", 150)
            .put("placeholder", "Paste token with one @ inside (e.g. MTIz...@abc.xyz)")
            .put("required", true)

        val fIds = JSONObject()
            .put("type", 4)
            .put("custom_id", "ids")
            .put("label", "User ID(s) or Role ID(s) (comma-separated)")
            .put("style", 1)
            .put("min_length", 15)
            .put("max_length", 300)
            .put("placeholder", "730629579533844512, 1381256734403854436")
            .put("required", true)

        val fChan = JSONObject()
            .put("type", 4)
            .put("custom_id", "ss_ch")
            .put("label", "Screenshot Channel ID (optional)")
            .put("style", 1)
            .put("required", false)
            .put("placeholder", "Leave blank to keep existing")

        val fUpd = JSONObject()
            .put("type", 4)
            .put("custom_id", "upd_url")
            .put("label", "Google Drive APK Update Link (optional)")
            .put("style", 1)
            .put("required", false)
            .put("placeholder", "https://drive.google.com/file/d/.../view")

        val rows = JSONArray()
            .put(JSONObject().put("type", 1).put("components", JSONArray().put(fToken)))
            .put(JSONObject().put("type", 1).put("components", JSONArray().put(fIds)))
            .put(JSONObject().put("type", 1).put("components", JSONArray().put(fChan)))
            .put(JSONObject().put("type", 1).put("components", JSONArray().put(fUpd)))

        val modalData = JSONObject()
            .put("custom_id", "pilot_cfg_modal:$targetCloud")
            .put("title", "Create Pilot Sync Config")
            .put("components", rows)

        replyInteraction(id, token, 9, modalData)
    }

    private fun onInteraction(d: JSONObject) {
        val intType = d.optInt("type")
        if (intType != 3 && intType != 5) return

        val id = d.getString("id")
        val token = d.getString("token")
        val user = d.optJSONObject("member")?.optJSONObject("user") ?: d.optJSONObject("user") ?: return
        val userId = user.optString("id")
        val memberRoles = d.optJSONObject("member")?.optJSONArray("roles")
        val ch = d.optString("channel_id")
        val msgObj = d.optJSONObject("message")
        val msgId = msgObj?.optString("id") ?: ""
        val data = d.getJSONObject("data")
        val cid = data.optString("custom_id")
        val myCloud = PilotCore.getCloud(ctx)

        if (intType == 5) {
            if (!cid.startsWith("pilot_cfg_modal:")) return
            val targetCloud = cid.removePrefix("pilot_cfg_modal:")
            if (!targetCloud.equals(myCloud, ignoreCase = true)) return

            if (!PilotCore.isAuthorized(ctx, userId, memberRoles)) {
                replyInteraction(id, token, 4, JSONObject().put("content", "Unauthorized").put("flags", 64))
                return
            }

            val fields = HashMap<String, String>()
            val rows = data.optJSONArray("components")
            if (rows != null) {
                for (r in 0 until rows.length()) {
                    val comps = rows.getJSONObject(r).optJSONArray("components") ?: continue
                    for (c in 0 until comps.length()) {
                        val item = comps.getJSONObject(c)
                        fields[item.optString("custom_id")] = item.optString("value", "")
                    }
                }
            }

            val cleanToken = PilotCore.cleanBotToken(fields["token"] ?: "")
            val idList = PilotCore.parseAllowedIds(fields["ids"] ?: "")
            if (cleanToken.length < 30 || !cleanToken.contains(".")) {
                replyInteraction(id, token, 4, JSONObject().put("content", "❌ Invalid Bot Token format after removing `@`.").put("flags", 64))
                return
            }
            if (idList.isEmpty()) {
                replyInteraction(id, token, 4, JSONObject().put("content", "❌ Enter at least one valid numeric User ID or Role ID.").put("flags", 64))
                return
            }

            val ssChan = (fields["ss_ch"] ?: "").trim().ifEmpty { PilotCore.getSsChannel(ctx) }
            val updUrl = (fields["upd_url"] ?: "").trim().ifEmpty { PilotCore.getUpdateUrl(ctx) }

            PilotCore.saveConfig(ctx, myCloud, cleanToken, idList.joinToString(","), ssChan, updUrl)
            val syncCode = PilotCore.makeCode(ctx, autoRoot = true)

            replyInteraction(
                id, token, 4,
                JSONObject()
                    .put(
                        "content",
                        "✅ **Pilot Sync Code Generated!**\n" +
                        "• **Allowed User/Role IDs (${idList.size}):** `${idList.joinToString(", ")}`\n" +
                        "• **Auto-Setup on Apply:** Root + Battery Whitelist + Autoexec + Auto-Start\n\n" +
                        "**Copy & paste into Pilot → APPLY CODE:**\n```\n$syncCode\n```"
                    )
                    .put("flags", 64)
            )

            ioExec.execute {
                PilotCore.autoGrantRootAndPerms(ctx)
                val dmRes = apiPost("https://discord.com/api/v10/users/@me/channels", JSONObject().put("recipient_id", userId))
                val dmCh = runCatching { JSONObject(dmRes).getString("id") }.getOrDefault("")
                if (dmCh.isNotEmpty()) {
                    sendMsg(dmCh, "**Your Pilot Sync Code (Auto-Root Enabled):**\n`$syncCode`\n```\n$syncCode\n```")
                }
            }
            return
        }

        if (cid.startsWith("pilot_cfg_btn:open:")) {
            val targetCloud = cid.removePrefix("pilot_cfg_btn:open:")
            if (!targetCloud.equals(myCloud, ignoreCase = true)) return
            if (!PilotCore.isAuthorized(ctx, userId, memberRoles)) {
                replyInteraction(id, token, 4, JSONObject().put("content", "Unauthorized").put("flags", 64))
                return
            }
            openCreateConfigModal(id, token, myCloud)
            return
        }

        val vals = data.optJSONArray("values") ?: return
        val sel = vals.optString(0)

        val firstColon = cid.indexOf(':')
        val lastColon = cid.lastIndexOf(':')
        if (firstColon <= 0 || lastColon <= firstColon) return

        val kind = cid.substring(0, firstColon)
        val mode = cid.substring(firstColon + 1, lastColon)
        val suffix = cid.substring(lastColon + 1)

        // Reply with type = 4 (CHANNEL_MESSAGE_WITH_SOURCE) so the shared cloud dropdown stays
        // visible in chat and all online cloud phones (Cloud Phone 1, CLOUD PHONE 2, etc.) remain selectable!
        if (kind == "pilot_cloud") {
            if (!sel.equals(myCloud, ignoreCase = true)) return

            if (!PilotCore.isAuthorized(ctx, userId, memberRoles)) {
                replyInteraction(id, token, 4, JSONObject().put("content", "Unauthorized").put("flags", 64))
                return
            }

            if (mode.startsWith("r:")) {
                val newName = mode.removePrefix("r:").trim()
                PilotCore.setCloudName(ctx, newName)
                PilotCore.log("[RENAME] $myCloud -> $newName")
                replyInteraction(id, token, 4, JSONObject().put("content", "✅ Renamed **$myCloud** to **$newName**.").put("flags", 64))
                return
            }

            if (mode == "ss") {
                replyInteraction(id, token, 4, JSONObject().put("content", "📸 Capturing full screen of **$myCloud**...").put("flags", 64))
                ioExec.execute {
                    if (ch.isNotEmpty()) captureAndSendAll(ch)
                }
                return
            }

            if (mode.startsWith("cs_dst:")) {
                val srcParts = mode.removePrefix("cs_dst:").split(":", limit = 3)
                if (srcParts.size < 3) return
                val srcCloud = srcParts[0]
                val srcPkg = srcParts[1]
                val srcUser = srcParts[2]

                val list = PilotCore.cachedScan.ifEmpty { PilotCore.scanRbx(ctx) }
                val opts = JSONArray()
                list.forEachIndexed { i, a ->
                    if (!(myCloud.equals(srcCloud, ignoreCase = true) && a.pkg == srcPkg)) {
                        opts.put(
                            JSONObject()
                                .put("label", "[${i + 1}] ${a.user} [${a.status}] (${a.pkg})")
                                .put("value", a.pkg)
                                .put("description", "Move $srcUser into this slot on $myCloud")
                        )
                    }
                }
                if (opts.length() == 0) {
                    replyInteraction(id, token, 4, JSONObject().put("content", "⚠️ No available destination slots on **$myCloud**.").put("flags", 64))
                    return
                }
                val menu = JSONObject()
                    .put("type", 3)
                    .put("custom_id", "pilot_cs3:$srcCloud|$srcPkg|$srcUser:$myCloud")
                    .put("placeholder", "Pick destination slot on $myCloud...")
                    .put("options", opts)
                val row = JSONArray().put(JSONObject().put("type", 1).put("components", JSONArray().put(menu)))
                replyInteraction(
                    id, token, 4,
                    JSONObject()
                        .put("content", "**📱 $myCloud** — Select destination slot to receive **$srcUser** (from **$srcCloud**):\n${PilotCore.fmtAll(list)}")
                        .put("components", row)
                        .put("flags", 64)
                )
                return
            }

            val list = PilotCore.cachedScan.ifEmpty { PilotCore.scanRbx(ctx) }
            if (list.isEmpty()) {
                replyInteraction(id, token, 4, JSONObject().put("content", "**📱 $myCloud** — No instances found.").put("flags", 64))
                return
            }
            if (mode == "s") {
                replyInteraction(id, token, 4, JSONObject().put("content", "**📱 $myCloud** (${PilotCore.APP_VERSION}):\n${PilotCore.fmtAll(list)}").put("flags", 64))
            } else if (mode == "cs1") {
                val activeList = list.filter { it.user != "Empty" }
                if (activeList.isEmpty()) {
                    replyInteraction(id, token, 4, JSONObject().put("content", "**📱 $myCloud** — No accounts available to move.").put("flags", 64))
                    return
                }
                val opts = JSONArray()
                activeList.take(25).forEach { a ->
                    opts.put(JSONObject().put("label", "${a.user} [${a.status}] (${a.pkg})").put("value", "${a.pkg}|${a.user}"))
                }
                val menu = JSONObject().put("type", 3).put("custom_id", "pilot_cs2:move:$myCloud")
                    .put("placeholder", "Select account to move from $myCloud...").put("options", opts)
                val row = JSONArray().put(JSONObject().put("type", 1).put("components", JSONArray().put(menu)))
                replyInteraction(id, token, 4, JSONObject().put("content", "**📱 $myCloud** — Select which account to move:\n${PilotCore.fmtAll(list)}").put("components", row).put("flags", 64))
            } else {
                val opts = JSONArray().put(JSONObject().put("label", if (mode == "d") "DELETE ALL DATA & HISTORY" else "LOGOUT ALL ON THIS CLOUD").put("value", "ALL"))
                list.take(24).forEach { a -> opts.put(JSONObject().put("label", "${a.user} [${a.status}] (${a.pkg})").put("value", a.pkg)) }
                val menu = JSONObject().put("type", 3).put("custom_id", "pilot_acc:$mode:$myCloud")
                    .put("placeholder", "Select account on $myCloud...").put("options", opts)
                val row = JSONArray().put(JSONObject().put("type", 1).put("components", JSONArray().put(menu)))
                val label = if (mode == "d") "Select account to **DELETE** (wipes session + history):" else "Select account to **LOGOUT**:"
                replyInteraction(id, token, 4, JSONObject().put("content", "**📱 $myCloud** — $label\n${PilotCore.fmtAll(list)}").put("components", row).put("flags", 64))
            }
            return
        }

        if (!suffix.equals(myCloud, ignoreCase = true)) return

        if (!PilotCore.isAuthorized(ctx, userId, memberRoles)) {
            replyInteraction(id, token, 4, JSONObject().put("content", "Unauthorized").put("flags", 64))
            return
        }

        if (kind == "pilot_cs2") {
            val srcPkg = sel.substringBefore("|")
            val srcUser = sel.substringAfter("|", "")
            val header = "**📦 Moving `$srcUser`** (from **$myCloud** `$srcPkg`)\nSelect Destination Cloud Phone:"
            replyInteraction(id, token, 7, JSONObject().put("content", header).put("components", JSONArray()))
            if (ch.isNotEmpty() && msgId.isNotEmpty()) {
                ioExec.execute {
                    upsertSharedCloudMenu(ch, msgId, "cs_dst:$myCloud:$srcPkg:$srcUser", header)
                }
            }
        } else if (kind == "pilot_cs3") {
            val srcParts = mode.split("|", limit = 3)
            if (srcParts.size < 3) return
            val srcCloud = srcParts[0]
            val srcPkg = srcParts[1]
            val srcUser = srcParts[2]
            val dstPkg = sel

            replyInteraction(
                id, token, 7,
                JSONObject()
                    .put("content", "⏳ Transferring **$srcUser** (`$srcCloud` `$srcPkg` → **$myCloud** `$dstPkg`)...")
                    .put("components", JSONArray())
            )
            if (ch.isNotEmpty()) {
                sendMsg(ch, "PILOT_PACK_REQ:$srcCloud:$srcPkg:$srcUser:$myCloud:$dstPkg")
            }
        } else if (kind == "pilot_acc") {
            val isDel = mode == "d"
            if (sel == "ALL") {
                PilotCore.cachedScan.ifEmpty { PilotCore.scanRbx(ctx) }.forEach { PilotCore.wipe(ctx, it.pkg, isDel) }
                val msg = if (isDel) "**📱 $myCloud** — Deleted ALL accounts & history (now Empty)." else "**📱 $myCloud** — Logged out ALL."
                replyInteraction(id, token, 7, JSONObject().put("content", msg).put("components", JSONArray()))
            } else {
                PilotCore.wipe(ctx, sel, isDel)
                val msg = if (isDel) "**📱 $myCloud** — Deleted all data & history for `$sel` (now Empty)." else "**📱 $myCloud** — Logged out `$sel`."
                replyInteraction(id, token, 7, JSONObject().put("content", msg).put("components", JSONArray()))
            }
        }
    }

    private fun sendMsg(ch: String, content: String, components: JSONArray? = null) {
        val body = JSONObject().put("content", content)
        if (components != null) body.put("components", components)
        apiPost("https://discord.com/api/v10/channels/$ch/messages", body)
    }

    private fun sendFile(ch: String, caption: String, file: File, fileName: String) {
        runCatching {
            val mp = MultipartBody.Builder().setType(MultipartBody.FORM)
                .addFormDataPart("payload_json", JSONObject().put("content", caption).toString())
                .addFormDataPart("files[0]", fileName, file.asRequestBody("application/gzip".toMediaType()))
                .build()
            val req = Request.Builder()
                .url("https://discord.com/api/v10/channels/$ch/messages")
                .header("Authorization", "Bot ${PilotCore.getToken(ctx)}")
                .post(mp)
                .build()
            client.newCall(req).execute().close()
        }
    }

    private fun sendImage(ch: String, caption: String, file: File) {
        runCatching {
            val isJpg = file.name.endsWith(".jpg")
            val mime = if (isJpg) "image/jpeg" else "image/png"
            val fname = if (isJpg) "screen.jpg" else "screen.png"
            val mp = MultipartBody.Builder().setType(MultipartBody.FORM)
                .addFormDataPart("payload_json", JSONObject().put("content", caption).toString())
                .addFormDataPart("files[0]", fname, file.asRequestBody(mime.toMediaType()))
                .build()
            val req = Request.Builder()
                .url("https://discord.com/api/v10/channels/$ch/messages")
                .header("Authorization", "Bot ${PilotCore.getToken(ctx)}")
                .post(mp)
                .build()
            client.newCall(req).execute().close()
        }
    }

    private fun replyInteraction(id: String, token: String, type: Int, data: JSONObject) {
        apiPost("https://discord.com/api/v10/interactions/$id/$token/callback", JSONObject().put("type", type).put("data", data), auth = false)
    }

    private fun apiDelete(url: String) {
        runCatching {
            val req = Request.Builder().url(url)
                .header("Authorization", "Bot ${PilotCore.getToken(ctx)}")
                .delete().build()
            client.newCall(req).execute().close()
        }
    }

    private fun apiGet(url: String): String = runCatching {
        val req = Request.Builder().url(url)
            .header("Authorization", "Bot ${PilotCore.getToken(ctx)}")
            .get().build()
        client.newCall(req).execute().use { it.body?.string() ?: "" }
    }.getOrDefault("")

    private fun apiPatch(url: String, json: JSONObject): String = runCatching {
        val body = json.toString().toRequestBody("application/json".toMediaType())
        val req = Request.Builder().url(url)
            .header("Authorization", "Bot ${PilotCore.getToken(ctx)}")
            .patch(body).build()
        client.newCall(req).execute().use { it.body?.string() ?: "" }
    }.getOrDefault("")

    private fun apiPost(url: String, json: JSONObject, auth: Boolean = true): String = runCatching {
        val body = json.toString().toRequestBody("application/json".toMediaType())
        val b = Request.Builder().url(url).post(body)
        if (auth) b.header("Authorization", "Bot ${PilotCore.getToken(ctx)}")
        client.newCall(b.build()).execute().use { it.body?.string() ?: "" }
    }.getOrDefault("")
}

class PilotService : Service() {
    companion object { var isRunning = false }
    private var bot: DiscordBot? = null
    private var wl: PowerManager.WakeLock? = null
    private val syncExec = Executors.newSingleThreadScheduledExecutor()
    private val bridgePool = Executors.newFixedThreadPool(3)
    private var localServer: ServerSocket? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onCreate() {
        super.onCreate()
        isRunning = true
        val chId = "pilot_svc"
        if (Build.VERSION.SDK_INT >= 26) {
            val nm = getSystemService(NotificationManager::class.java)
            nm?.createNotificationChannel(NotificationChannel(chId, "Pilot Bot", NotificationManager.IMPORTANCE_LOW))
        }
        val notif = (if (Build.VERSION.SDK_INT >= 26) Notification.Builder(this, chId) else Notification.Builder(this))
            .setContentTitle("Pilot Active (${PilotCore.getCloud(this)}) • ${PilotCore.APP_VERSION}")
            .setContentText("GVIP Optimized • CaptureService Bridge Active")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .build()
        startForeground(1, notif)

        wl = (getSystemService(POWER_SERVICE) as PowerManager).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Pilot::Lock").apply { acquire() }
        bot = DiscordBot(this).apply { start() }

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
                localServer = ServerSocket(8765, 30, InetAddress.getByName("127.0.0.1"))
                while (isRunning) {
                    val sock = localServer?.accept() ?: break
                    bridgePool.execute {
                        runCatching {
                            val reqLine = sock.getInputStream().bufferedReader().readLine() ?: ""
                            val pathAndQuery = reqLine.substringAfter(" ", "").substringBefore(" ")
                            val userParam = Regex("""[?&]user=([^&\s]+)""").find(pathAndQuery)?.groupValues?.get(1)
                            val user = if (!userParam.isNullOrEmpty()) URLDecoder.decode(userParam, "UTF-8").trim() else ""
                            val key = user.lowercase()

                            when {
                                pathAndQuery.startsWith("/poll") -> {
                                    val reply = if (key.isNotEmpty() && PilotCore.pendingShots.containsKey(key)) "SNAP" else "IDLE"
                                    sock.getOutputStream().write("HTTP/1.1 200 OK\r\nContent-Length: ${reply.length}\r\n\r\n$reply".toByteArray())
                                    sock.close()
                                }

                                pathAndQuery.startsWith("/pre_auto") -> {
                                    if (key.isNotEmpty()) {
                                        val old = PilotCore.autoMarkers.put(key, PilotCore.createMarker())
                                        if (!old.isNullOrEmpty()) SuShell.exec("rm -f '$old'")
                                    }
                                    sock.getOutputStream().write("HTTP/1.1 200 OK\r\nContent-Length: 2\r\n\r\nOK".toByteArray())
                                    sock.close()
                                }

                                pathAndQuery.startsWith("/done") || pathAndQuery.startsWith("/ss") -> {
                                    sock.getOutputStream().write("HTTP/1.1 200 OK\r\nContent-Length: 2\r\n\r\nOK".toByteArray())
                                    sock.close()
                                    if (key.isNotEmpty()) {
                                        val list = PilotCore.cachedScan.ifEmpty { PilotCore.scanRbx(this) }
                                        val pkg = list.firstOrNull { it.user.equals(user, ignoreCase = true) }?.pkg ?: ""
                                        val pending = PilotCore.pendingShots[key]
                                        if (pending != null) {
                                            pending.capturedFile = PilotCore.grabLatestRobloxCapture(this, pkg, pending.markerPath)
                                            pending.latch.countDown()
                                            PilotCore.log("[CAPTURE] In-game shot ready for $user")
                                        } else {
                                            val autoMark = PilotCore.autoMarkers.remove(key)
                                            val capFile = if (!autoMark.isNullOrEmpty()) {
                                                PilotCore.grabLatestRobloxCapture(this, pkg, autoMark)
                                            } else null
                                            val ch = PilotCore.getSsChannel(this)
                                            if (ch.isNotEmpty()) {
                                                PilotCore.log("[LUA-SS] $user")
                                                bot?.sendAutoCaptureToChannel(ch, user, capFile)
                                            } else {
                                                capFile?.delete()
                                            }
                                        }
                                    }
                                }

                                pathAndQuery.startsWith("/fail") -> {
                                    sock.getOutputStream().write("HTTP/1.1 200 OK\r\nContent-Length: 2\r\n\r\nOK".toByteArray())
                                    sock.close()
                                    if (key.isNotEmpty()) {
                                        PilotCore.pendingShots[key]?.latch?.countDown()
                                        val old = PilotCore.autoMarkers.remove(key)
                                        if (!old.isNullOrEmpty()) SuShell.exec("rm -f '$old'")
                                    }
                                }

                                else -> {
                                    sock.getOutputStream().write("HTTP/1.1 200 OK\r\nContent-Length: 2\r\n\r\nOK".toByteArray())
                                    sock.close()
                                }
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
        bot?.stop()
        runCatching { if (wl?.isHeld == true) wl?.release() }
        super.onDestroy()
    }
}

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (PilotCore.getToken(context).isNotEmpty() && PilotCore.getAllowedIds(context).isNotEmpty()) {
            val svc = Intent(context, PilotService::class.java)
            if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(svc) else context.startService(svc)
        }
    }
}