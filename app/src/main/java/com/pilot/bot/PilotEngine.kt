package com.pilot.bot

import android.app.*
import android.content.*
import android.database.sqlite.SQLiteDatabase
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

data class WindowSnapshot(
    val taskId: Int?,
    val preBounds: Rect?,
    val fullW: Int,
    val fullH: Int,
    val tappedMaxBtn: Pair<Int, Int>?
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

            val tmpPath = "/data/local/tmp/pilot_upd.apk"
            val copyRes = SuShell.exec("cp '${apkFile.absolutePath}' '$tmpPath' && chmod 644 '$tmpPath' && [ -s '$tmpPath' ] && echo READY")
            apkFile.delete()
            if (!copyRes.contains("READY")) {
                PilotCore.log("[UPD] Root copy failed: $copyRes")
                return "Root stage failed: $copyRes"
            }

            prefs.edit().putString("last_upd_tag", newTag).commit()

            PilotCore.log("[UPD] Installing APK via pm install...")
            val res = SuShell.exec("pm install -r -d -g '$tmpPath' 2>&1")

            return if (res.contains("Success", ignoreCase = true)) {
                SuShell.exec("rm -f '$tmpPath'")
                PilotCore.log("[UPD] Installed! Restarting service...")
                "Update installed! Restarting bot (was ${PilotCore.APP_VERSION})..."
            } else if (res.contains("INSTALL_FAILED_UPDATE_INCOMPATIBLE", ignoreCase = true)) {
                PilotCore.log("[UPD] Signature changed — launching detached reinstall...")
                val scriptPath = "/data/local/tmp/pilot_do_install.sh"
                val logPath = "/data/local/tmp/pilot_upd.log"
                val swapScript = """
                    #!/system/bin/sh
                    trap '' HUP PIPE INT TERM
                    exec >"$logPath" 2>&1
                    echo ${'$'}$ > /sys/fs/cgroup/cgroup.procs 2>/dev/null
                    echo ${'$'}$ > /dev/cpuset/background/tasks 2>/dev/null
                    sleep 2
                    rm -rf /data/local/tmp/pilot_prefs_bak
                    cp -rf /data/data/com.pilot.bot/shared_prefs /data/local/tmp/pilot_prefs_bak 2>/dev/null
                    pm uninstall com.pilot.bot
                    sleep 1
                    pm install -g "$tmpPath"
                    rm -f "$tmpPath" "$scriptPath"
                    if [ -d /data/local/tmp/pilot_prefs_bak ]; then
                      mkdir -p /data/data/com.pilot.bot/shared_prefs
                      cp -rf /data/local/tmp/pilot_prefs_bak/. /data/data/com.pilot.bot/shared_prefs/
                      uid=${'$'}(stat -c "%u:%g" /data/data/com.pilot.bot 2>/dev/null)
                      [ -n "${'$'}uid" ] && chown -R ${'$'}uid /data/data/com.pilot.bot/shared_prefs
                      restorecon -RF /data/data/com.pilot.bot 2>/dev/null
                      rm -rf /data/local/tmp/pilot_prefs_bak
                    fi
                    sleep 1
                    am start-foreground-service -n com.pilot.bot/.PilotService || am startservice -n com.pilot.bot/.PilotService
                """.trimIndent()
                val localSh = File(c.cacheDir, "pilot_do_install.sh")
                localSh.writeText(swapScript)
                SuShell.exec("cp '${localSh.absolutePath}' '$scriptPath' && chmod 755 '$scriptPath'")
                localSh.delete()
                runCatching {
                    ProcessBuilder("su", "-c", "nohup sh '$scriptPath' </dev/null >/dev/null 2>&1 &").start()
                }
                "Signature migrated! Reinstalling & restarting bot in ~5s..."
            } else {
                SuShell.exec("rm -f '$tmpPath'")
                PilotCore.log("[UPD] Install failed: $res")
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
    const val APP_VERSION = "v1.3.8"
    private const val SHARED_SYNC_PATH = "/sdcard/pilot_sync.txt"

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
        writeSharedBackup(c)
    }

    fun saveUpdateUrl(c: Context, url: String) {
        prefs(c).edit().putString("upd_url", url.trim()).apply()
        writeSharedBackup(c)
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
        writeSharedBackup(c)
    }

    private fun writeSharedBackup(c: Context) {
        if (getToken(c).isEmpty() || getAllowedIds(c).isEmpty()) return
        val code = makeCode(c, autoRoot = true)
        runCatching { File(SHARED_SYNC_PATH).writeText(code) }
        SuShell.exec("printf '%s' '$code' > $SHARED_SYNC_PATH && chmod 666 $SHARED_SYNC_PATH")
    }

    fun tryAutoSeedFromDisk(c: Context): Boolean {
        if (getToken(c).isNotEmpty() && getAllowedIds(c).isNotEmpty()) {
            writeSharedBackup(c)
            return true
        }
        val diskCode = runCatching {
            val f = File(SHARED_SYNC_PATH)
            if (f.exists()) f.readText().trim() else SuShell.exec("cat $SHARED_SYNC_PATH 2>/dev/null").trim()
        }.getOrDefault("")
        if (diskCode.contains("-")) {
            val (ok, _) = applyCode(c, diskCode, startSvc = false)
            if (ok) {
                log("[SYNC] Auto-loaded config from $SHARED_SYNC_PATH")
                return true
            }
        }
        return false
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

    fun applyCode(c: Context, code: String, startSvc: Boolean = true): Pair<Boolean, Boolean> {
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
        writeSharedBackup(c)

        val autoRoot = parsed.optBoolean("ar", true)
        var started = false
        if (autoRoot) {
            autoGrantRootAndPerms(c)
            if (startSvc && getToken(c).isNotEmpty() && getAllowedIds(c).isNotEmpty()) {
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
        val lua = Rejoiner.buildBridgeLua()
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
            log("[AUTOEXEC] Ready in ${dirs.size} folder(s)")
        }
    }

    fun getDisplaySize(): Pair<Int, Int> {
        val wm = SuShell.exec("wm size 2>/dev/null")
        val m = Regex("""(\d+)x(\d+)""").findAll(wm).lastOrNull()
        val w = m?.groupValues?.get(1)?.toIntOrNull() ?: 1280
        val h = m?.groupValues?.get(2)?.toIntOrNull() ?: 720
        return w to h
    }

    fun findTaskId(pkg: String): Int? {
        val dump = SuShell.exec("dumpsys activity activities 2>/dev/null")
        if (dump.isEmpty()) return null
        val pkgRx = Regex("""\b${Regex.escape(pkg)}\b""")
        val taskRx = Regex("""Task\{[a-f0-9]+\s+#(\d+)""")
        for (line in dump.lines()) {
            if (pkgRx.containsMatchIn(line) && line.contains("Task{")) {
                val tm = taskRx.find(line)
                if (tm != null) return tm.groupValues[1].toIntOrNull()
            }
        }
        for (block in dump.split("Task{")) {
            if (!pkgRx.containsMatchIn(block)) continue
            val idMatch = Regex("""^[a-f0-9]+\s+#(\d+)""").find(block)
            if (idMatch != null) return idMatch.groupValues[1].toIntOrNull()
        }
        return null
    }

    fun findOuterWindowBounds(pkg: String, fullW: Int, fullH: Int): Rect? {
        val pkgRx = Regex("""\b${Regex.escape(pkg)}\b""")
        val bracketRx = Regex("""\[(\d+),(\d+)\]\[(\d+),(\d+)\]""")
        var bestOuter: Rect? = null
        var bestArea = 0

        val winDump = SuShell.exec("dumpsys window windows 2>/dev/null")
        if (winDump.isNotEmpty()) {
            for (block in winDump.split("Window #")) {
                if (!pkgRx.containsMatchIn(block)) continue
                if (block.contains("SurfaceView")) continue
                if (block.contains("mHasSurface=false") || block.contains("mViewVisibility=0x8")) continue

                for (line in block.lines()) {
                    val trimmed = line.trim()
                    if (trimmed.contains("mParentFrame=") || trimmed.contains("mDisplayFrame=") ||
                        trimmed.contains("parent=[") || trimmed.contains("display=[") ||
                        trimmed.contains("mStable=") || trimmed.contains("mOverscan=")) {
                        continue
                    }
                    if (!trimmed.contains("mFrame=") && !trimmed.contains("mBounds=") &&
                        !trimmed.contains("frame=") && !trimmed.contains("bounds=")) continue

                    val bm = bracketRx.find(trimmed) ?: continue
                    val l = bm.groupValues[1].toIntOrNull() ?: 0
                    val t = bm.groupValues[2].toIntOrNull() ?: 0
                    val r = bm.groupValues[3].toIntOrNull() ?: 0
                    val b = bm.groupValues[4].toIntOrNull() ?: 0
                    val w = r - l
                    val h = b - t
                    if (w < 140 || h < 140) continue
                    if (w >= fullW - 24 && h >= fullH - 96) continue

                    val area = w * h
                    if (area > bestArea) {
                        bestArea = area
                        bestOuter = Rect(l, t, r, b)
                    }
                }
            }
        }

        if (bestOuter == null) {
            val actDump = SuShell.exec("dumpsys activity activities 2>/dev/null")
            if (actDump.isNotEmpty()) {
                for (block in actDump.split("Task{")) {
                    if (!pkgRx.containsMatchIn(block)) continue
                    val bm = Regex("""\b(?:mBounds|bounds)=\[(\d+),(\d+)\]\[(\d+),(\d+)\]""").find(block) ?: continue
                    val l = bm.groupValues[1].toIntOrNull() ?: 0
                    val t = bm.groupValues[2].toIntOrNull() ?: 0
                    val r = bm.groupValues[3].toIntOrNull() ?: 0
                    val b = bm.groupValues[4].toIntOrNull() ?: 0
                    val w = r - l
                    val h = b - t
                    if (w >= 140 && h >= 140 && !(w >= fullW - 24 && h >= fullH - 96)) {
                        val area = w * h
                        if (area > bestArea) {
                            bestArea = area
                            bestOuter = Rect(l, t, r, b)
                        }
                    }
                }
            }
        }
        return bestOuter
    }

    @Synchronized
    fun expandRobloxFullTab(pkg: String): WindowSnapshot {
        val (fullW, fullH) = getDisplaySize()
        val preBounds = findOuterWindowBounds(pkg, fullW, fullH)
        val taskId = findTaskId(pkg)

        if (taskId != null) {
            SuShell.exec(
                "am task resizeable $taskId 2 >/dev/null 2>&1; " +
                "am task set-windowing-mode $taskId 1 >/dev/null 2>&1; " +
                "am task resize $taskId 0 0 $fullW $fullH >/dev/null 2>&1; " +
                "cmd activity task resize $taskId 0 0 $fullW $fullH >/dev/null 2>&1; " +
                "am stack move-task $taskId 1 true >/dev/null 2>&1"
            )
        }

        val resolvedAct = SuShell.exec("cmd package resolve-activity --brief $pkg 2>/dev/null | tail -n 1").trim()
        if (resolvedAct.contains("/")) {
            SuShell.exec("am start -n '$resolvedAct' --activity-reorder-to-front --windowingMode 1 >/dev/null 2>&1")
        } else {
            SuShell.exec("monkey -p $pkg -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1")
        }

        runCatching { Thread.sleep(450) }

        var tappedMaxBtn: Pair<Int, Int>? = null
        val afterBounds = findOuterWindowBounds(pkg, fullW, fullH) ?: preBounds
        if (afterBounds != null && (afterBounds.width() < fullW - 60 || afterBounds.height() < fullH - 120)) {
            val maxBtnX = (afterBounds.right - 46).coerceIn(10, fullW - 10)
            val maxBtnY = (afterBounds.top + 17).coerceIn(8, fullH - 10)
            SuShell.exec("input tap $maxBtnX $maxBtnY")
            tappedMaxBtn = maxBtnX to maxBtnY
            runCatching { Thread.sleep(650) }
        }

        log("[FULLTAB] Expanded $pkg (task=${taskId ?: -1}, ${fullW}x${fullH})")
        return WindowSnapshot(taskId, preBounds, fullW, fullH, tappedMaxBtn)
    }

    @Synchronized
    fun restoreRobloxWindow(pkg: String, snap: WindowSnapshot) {
        val b = snap.preBounds
        if (b != null) {
            if (snap.tappedMaxBtn != null) {
                val restoreX = (snap.fullW - 46).coerceIn(10, snap.fullW - 10)
                val restoreY = 17
                SuShell.exec("input tap $restoreX $restoreY")
                runCatching { Thread.sleep(250) }
            }
            if (snap.taskId != null) {
                SuShell.exec(
                    "am task set-windowing-mode ${snap.taskId} 5 >/dev/null 2>&1; " +
                    "am task resize ${snap.taskId} ${b.left} ${b.top} ${b.right} ${b.bottom} >/dev/null 2>&1; " +
                    "cmd activity task resize ${snap.taskId} ${b.left} ${b.top} ${b.right} ${b.bottom} >/dev/null 2>&1"
                )
            }
            log("[FULLTAB] Restored $pkg back to floating window")
        } else {
            if (snap.tappedMaxBtn != null) {
                val restoreX = (snap.fullW - 46).coerceIn(10, snap.fullW - 10)
                val restoreY = 17
                SuShell.exec("input tap $restoreX $restoreY")
                runCatching { Thread.sleep(200) }
            }
            if (snap.taskId != null) {
                SuShell.exec("am task set-windowing-mode ${snap.taskId} 5 >/dev/null 2>&1")
            }
            SuShell.exec("am start -a android.intent.action.MAIN -c android.intent.category.HOME >/dev/null 2>&1")
            log("[FULLTAB] Minimized $pkg back to background")
        }
    }

    @Synchronized
    fun captureFullTabScreen(c: Context, pkg: String): File? {
        val snap = expandRobloxFullTab(pkg)
        val rawPath = File(c.cacheDir, "fulltab_${System.nanoTime()}.png").absolutePath
        SuShell.exec("screencap -p '$rawPath' && chmod 666 '$rawPath'")
        restoreRobloxWindow(pkg, snap)
        val rawFile = File(rawPath)
        return if (rawFile.exists() && rawFile.length() > 0L) rawFile else null
    }

    fun findWindowBounds(pkg: String, fullW: Int, fullH: Int): Rect? {
        val pkgRx = Regex("""\b${Regex.escape(pkg)}\b""")
        val bracketRx = Regex("""\[(\d+),(\d+)\]\[(\d+),(\d+)\]""")
        val surfaceRectRx = Regex("""rect=\((-?\d+(?:\.\d+)?),\s*(-?\d+(?:\.\d+)?)\)\s+(\d+)\s*x\s*(\d+)""")

        fun isFullDisplayContainer(w: Int, h: Int): Boolean {
            return w >= fullW - 24 && h >= fullH - 96
        }

        var bestSurfaceRect: Rect? = null
        var bestSurfaceArea = 0
        var bestWindowRect: Rect? = null
        var bestWindowArea = 0

        val winDump = SuShell.exec("dumpsys window windows 2>/dev/null")
        if (winDump.isNotEmpty()) {
            for (block in winDump.split("Window #")) {
                if (!pkgRx.containsMatchIn(block)) continue
                if (block.contains("mHasSurface=false") || block.contains("mViewVisibility=0x8")) continue

                val isSurface = block.contains("SurfaceView")
                for (line in block.lines()) {
                    val trimmed = line.trim()
                    if (trimmed.contains("mParentFrame=") || trimmed.contains("mDisplayFrame=") ||
                        trimmed.contains("parent=[") || trimmed.contains("display=[") ||
                        trimmed.contains("mStable=") || trimmed.contains("mOverscan=")) {
                        continue
                    }

                    val sm = surfaceRectRx.find(trimmed)
                    if (sm != null) {
                        val l = sm.groupValues[1].toFloatOrNull()?.toInt() ?: 0
                        val t = sm.groupValues[2].toFloatOrNull()?.toInt() ?: 0
                        val w = sm.groupValues[3].toIntOrNull() ?: 0
                        val h = sm.groupValues[4].toIntOrNull() ?: 0
                        if (w >= 140 && h >= 140 && !isFullDisplayContainer(w, h)) {
                            val area = w * h
                            val r = Rect(l, t, l + w, t + h)
                            if (isSurface && area > bestSurfaceArea) {
                                bestSurfaceArea = area
                                bestSurfaceRect = r
                            } else if (area > bestWindowArea) {
                                bestWindowArea = area
                                bestWindowRect = r
                            }
                        }
                    }

                    if (!trimmed.contains("mFrame=") && !trimmed.contains("mBounds=") &&
                        !trimmed.contains("content=") && !trimmed.contains("frame=") &&
                        !trimmed.contains("bounds=")) continue

                    val bm = bracketRx.find(trimmed) ?: continue
                    val l = bm.groupValues[1].toIntOrNull() ?: 0
                    val t = bm.groupValues[2].toIntOrNull() ?: 0
                    val r = bm.groupValues[3].toIntOrNull() ?: 0
                    val b = bm.groupValues[4].toIntOrNull() ?: 0
                    val w = r - l
                    val h = b - t
                    if (w < 140 || h < 140 || isFullDisplayContainer(w, h)) continue

                    val area = w * h
                    val rect = Rect(l, t, r, b)
                    if (isSurface && area > bestSurfaceArea) {
                        bestSurfaceArea = area
                        bestSurfaceRect = rect
                    } else if (area > bestWindowArea) {
                        bestWindowArea = area
                        bestWindowRect = rect
                    }
                }
            }
        }

        if (bestSurfaceRect != null) return bestSurfaceRect
        if (bestWindowRect != null) {
            val topTrim = if (bestWindowRect.height() > 220) 38 else 0
            val sideTrim = if (bestWindowRect.width() > 220) 2 else 0
            return Rect(
                bestWindowRect.left + sideTrim,
                bestWindowRect.top + topTrim,
                bestWindowRect.right - sideTrim,
                bestWindowRect.bottom - sideTrim
            )
        }
        return null
    }

    @Synchronized
    fun captureScreen(c: Context, pkg: String? = null, tapInsideFirst: Boolean = false): Pair<File?, Boolean> {
        val rawPath = File(c.cacheDir, "raw.png").absolutePath
        val outPath = File(c.cacheDir, "crop.jpg").absolutePath

        if (!pkg.isNullOrEmpty() && tapInsideFirst) {
            val (fw, fh) = getDisplaySize()
            val preBounds = findWindowBounds(pkg, fw, fh)
            if (preBounds != null) {
                val tapX = preBounds.centerX().coerceAtLeast(10)
                val tapY = (preBounds.top + 28).coerceAtMost(preBounds.bottom - 10)
                SuShell.exec("input tap $tapX $tapY")
                runCatching { Thread.sleep(250) }
            }
        }

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
            FileOutputStream(outFile).use { cropped.compress(Bitmap.CompressFormat.JPEG, 92, it) }
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

    @Synchronized
    fun injectCookie(
        c: Context,
        pkg: String,
        rawCookie: String,
        knownUser: String = "",
        knownUserId: Long = 0L
    ): Pair<Boolean, String> {
        val clean = AccountLaunchManager.sanitizeCookie(rawCookie)
        if (clean.length < 50) {
            return false to "Cookie is too short or invalid."
        }

        val pkgCheck = SuShell.exec("[ -d '/data/data/$pkg' ] && echo OK").trim()
        if (pkgCheck != "OK") {
            return false to "Package `$pkg` is not installed on this cloud phone."
        }

        SuShell.exec("am force-stop $pkg && rm -rf /data/data/$pkg/shared_prefs/* /data/data/$pkg/files/LocalStorage/* /data/data/$pkg/files/appData/LocalStorage/*")

        val cookieDir = "/data/data/$pkg/app_webview/Default"
        val cookiePath = "$cookieDir/Cookies"
        val hasDb = SuShell.exec("[ -f '$cookiePath' ] && echo YES").trim() == "YES"
        if (!hasDb) {
            SuShell.exec("monkey -p $pkg -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1 && sleep 4 && am force-stop $pkg")
        }

        val tmpDb = File(c.cacheDir, "ck_${System.nanoTime()}.db")
        SuShell.exec("cp '$cookiePath' '${tmpDb.absolutePath}' 2>/dev/null && cp '${cookiePath}-wal' '${tmpDb.absolutePath}-wal' 2>/dev/null && cp '${cookiePath}-shm' '${tmpDb.absolutePath}-shm' 2>/dev/null && chmod 666 '${tmpDb.absolutePath}'*")

        if (!tmpDb.exists() || tmpDb.length() == 0L) {
            return false to "Could not read WebView Cookies database for `$pkg`."
        }

        val writeOk = runCatching {
            val db = SQLiteDatabase.openDatabase(tmpDb.absolutePath, null, SQLiteDatabase.OPEN_READWRITE)
            val cols = HashSet<String>()
            db.rawQuery("PRAGMA table_info(cookies)", null).use { cur ->
                while (cur.moveToNext()) {
                    cols.add(cur.getString(1).lowercase())
                }
            }
            if (cols.isEmpty()) {
                db.close()
                return@runCatching false
            }

            db.delete("cookies", "name = ? AND host_key LIKE ?", arrayOf(".ROBLOSECURITY", "%roblox.com"))

            val nowMicros = (System.currentTimeMillis() / 1000L + 11644473600L) * 1_000_000L
            val expMicros = nowMicros + (365L * 86400L * 1_000_000L)

            val cv = ContentValues().apply {
                if ("creation_utc" in cols) put("creation_utc", nowMicros)
                if ("host_key" in cols) put("host_key", ".roblox.com")
                if ("top_frame_site_key" in cols) put("top_frame_site_key", "")
                if ("name" in cols) put("name", ".ROBLOSECURITY")
                if ("value" in cols) put("value", clean)
                if ("encrypted_value" in cols) put("encrypted_value", ByteArray(0))
                if ("path" in cols) put("path", "/")
                if ("expires_utc" in cols) put("expires_utc", expMicros)
                if ("is_secure" in cols) put("is_secure", 1)
                if ("is_httponly" in cols) put("is_httponly", 1)
                if ("last_access_utc" in cols) put("last_access_utc", nowMicros)
                if ("has_expires" in cols) put("has_expires", 1)
                if ("is_persistent" in cols) put("is_persistent", 1)
                if ("priority" in cols) put("priority", 1)
                if ("samesite" in cols) put("samesite", -1)
                if ("source_scheme" in cols) put("source_scheme", 2)
                if ("source_port" in cols) put("source_port", 443)
                if ("last_update_utc" in cols) put("last_update_utc", nowMicros)
                if ("source_type" in cols) put("source_type", 0)
                if ("has_cross_site_ancestor" in cols) put("has_cross_site_ancestor", 0)
            }

            db.insertOrThrow("cookies", null, cv)
            db.close()
            true
        }.getOrDefault(false)

        if (!writeOk) {
            c.cacheDir.listFiles { f -> f.name.startsWith(tmpDb.name) }?.forEach { it.delete() }
            return false to "Failed to write `.ROBLOSECURITY` into SQLite database."
        }

        val launchUri = if (Rejoiner.isEnabled(c)) Rejoiner.buildLaunchUri(c, pkg, null) else ""
        val launchCmd = if (launchUri.isNotEmpty()) {
            "am start -a android.intent.action.VIEW -d '$launchUri' -p $pkg >/dev/null 2>&1"
        } else {
            "monkey -p $pkg -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1"
        }

        val restoreSh = """
            mkdir -p '$cookieDir'
            rm -f '${cookiePath}-wal' '${cookiePath}-shm'
            cp '${tmpDb.absolutePath}' '$cookiePath'
            uid=${'$'}(stat -c '%u:%g' /data/data/$pkg 2>/dev/null)
            if [ -n "${'$'}uid" ]; then
              chown -R ${'$'}uid '$cookieDir' 2>/dev/null
            fi
            restorecon -RF /data/data/$pkg 2>/dev/null
            $launchCmd
        """.trimIndent()
        SuShell.exec(restoreSh)
        c.cacheDir.listFiles { f -> f.name.startsWith(tmpDb.name) }?.forEach { it.delete() }

        if (knownUser.isNotEmpty()) {
            histPrefs(c).edit().putString(pkg, knownUser).apply()
            if (knownUserId > 0L) Rejoiner.saveUserId(c, knownUser, knownUserId)
        }

        runCatching { Thread.sleep(3500) }
        val updated = scanRbx(c).firstOrNull { it.pkg == pkg }
        val loggedUser = knownUser.ifEmpty { updated?.user?.takeIf { it != "Empty" } ?: "Session Loaded" }
        log("[LAUNCH] Cookie injected into $pkg -> $loggedUser")
        return true to loggedUser
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
    private val wsClient = OkHttpClient.Builder()
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .build()
    private val restClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .build()
    private val exec = Executors.newSingleThreadScheduledExecutor()
    private val interactionExec = Executors.newCachedThreadPool()
    private val ioExec = Executors.newFixedThreadPool(3)
    private var ws: WebSocket? = null
    private var hbTask: ScheduledFuture<*>? = null
    private var seq: Int? = null
    @Volatile private var botId: String = ""
    @Volatile private var alive = true

    private val checkIns = ConcurrentHashMap<String, ConcurrentHashMap<String, String>>()
    private val checkInMsgIds = ConcurrentHashMap<String, ConcurrentLinkedQueue<String>>()

    private val freeCheckIns = ConcurrentHashMap<String, ConcurrentHashMap<String, Pair<String, Int>>>()
    private val freeCheckInMsgIds = ConcurrentHashMap<String, ConcurrentLinkedQueue<String>>()

    fun start() { connect() }

    fun stop() {
        alive = false
        hbTask?.cancel(true)
        ws?.close(1000, "Stopped")
        exec.shutdownNow()
        interactionExec.shutdownNow()
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

    fun sendAccountShot(ch: String, triggerMsgId: String, rawQuery: String) {
        val cl = PilotCore.getCloud(ctx)
        val list = PilotCore.cachedScan.ifEmpty { PilotCore.scanRbx(ctx) }
        val query = rawQuery.trim()
        val q = query.lowercase()

        var matches = list.mapIndexedNotNull { i, a ->
            if (a.user != "Empty" && a.user.lowercase().contains(q)) i to a else null
        }
        if (matches.isEmpty()) {
            matches = list.mapIndexedNotNull { i, a ->
                if (a.user != "Empty" && PilotCore.similarity(q, a.user.lowercase()) >= 0.45) i to a else null
            }
        }

        if (matches.isEmpty()) {
            runCatching { Thread.sleep(2200L + (abs(cl.hashCode()) % 12) * 100L) }
            if (!hasBotRepliedAfter(ch, triggerMsgId)) {
                sendMsg(ch, "⚠️ No matching account found for `$query` on any online cloud phone.")
            }
            return
        }

        for ((idx, acc) in matches) {
            val img = PilotCore.captureFullTabScreen(ctx, acc.pkg)
            val caption = "**📱 $cl** — Full-Tab Status for `${acc.user}`:\n${PilotCore.fmtLine(idx, acc)}"
            if (img != null) {
                sendImage(ch, caption, img)
                img.delete()
            } else {
                sendMsg(ch, caption)
            }
        }
    }

    fun sendLuaWindowShot(ch: String, exactUser: String) {
        val cl = PilotCore.getCloud(ctx)
        val list = PilotCore.cachedScan.ifEmpty { PilotCore.scanRbx(ctx) }
        val q = exactUser.trim().lowercase()
        val match = list.mapIndexedNotNull { i, a ->
            if (a.user != "Empty" && a.user.lowercase() == q) i to a else null
        }.firstOrNull() ?: return

        val (idx, acc) = match
        val (img, croppedOk) = PilotCore.captureScreen(ctx, acc.pkg, tapInsideFirst = false)
        val note = if (!croppedOk) "\n*(Window minimized/hidden — showing full screen)*" else ""
        val caption = "**📱 $cl** — Status for `${acc.user}`:\n${PilotCore.fmtLine(idx, acc)}$note"
        if (img != null) {
            sendImage(ch, caption, img)
            img.delete()
        }
    }

    private fun connect() {
        if (!alive) return
        val token = PilotCore.getToken(ctx)
        if (token.isEmpty()) { PilotCore.log("[ERR] Missing Token"); return }
        val req = Request.Builder().url("wss://gateway.discord.gg/?v=10&encoding=json").build()
        ws = wsClient.newWebSocket(req, object : WebSocketListener() {
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
                else if (type == "INTERACTION_CREATE") interactionExec.execute { onInteraction(d) }
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
            when {
                content.startsWith("PILOT_HERE:") -> {
                    val p = content.split(":", limit = 4)
                    if (p.size >= 4) {
                        val anchor = p[1]
                        val cloud = p[2]
                        val ver = p[3]
                        checkIns.computeIfAbsent(anchor) { ConcurrentHashMap() }[cloud] = ver
                        val mid = d.optString("id")
                        if (mid.isNotEmpty()) {
                            checkInMsgIds.computeIfAbsent(anchor) { ConcurrentLinkedQueue() }.add(mid)
                        }
                    }
                }
                content.startsWith("PILOT_FREE:") -> {
                    val p = content.split(":", limit = 5)
                    if (p.size >= 5) {
                        val anchor = p[1]
                        val cloud = p[2]
                        val ver = p[3]
                        val freeCnt = p[4].toIntOrNull() ?: 0
                        if (freeCnt > 0) {
                            freeCheckIns.computeIfAbsent(anchor) { ConcurrentHashMap() }[cloud] = ver to freeCnt
                        }
                        val mid = d.optString("id")
                        if (mid.isNotEmpty()) {
                            freeCheckInMsgIds.computeIfAbsent(anchor) { ConcurrentLinkedQueue() }.add(mid)
                        }
                    }
                }
                content.startsWith("✅ **Cookie Verified!**") -> {
                    val sid = Regex("""\*\*Session:\*\*\s*`([^`]+)`""").find(content)?.groupValues?.get(1)
                    val accMatch = Regex("""\*\*Account:\*\*\s*`([^`]+)`\s*\(`(\d+)`\)""").find(content)
                    if (!sid.isNullOrEmpty() && accMatch != null) {
                        AccountLaunchManager.getSession(ctx, sid)?.let { s ->
                            s.username = accMatch.groupValues[1]
                            s.userId = accMatch.groupValues[2].toLongOrNull() ?: 0L
                            s.stage = LaunchStage.AWAITING_JSON
                            AccountLaunchManager.putSession(ctx, s)
                        }
                    }
                }
                content.startsWith("❌ **Account Launch Failed**") -> {
                    val sid = Regex("""\(`([^`]+)`\)""").find(content)?.groupValues?.get(1)
                    if (!sid.isNullOrEmpty()) AccountLaunchManager.removeSession(ctx, sid)
                }
                content.startsWith("PILOT_PACK_REQ:") -> {
                    ioExec.execute { handlePackRequest(d) }
                }
                content.startsWith("PILOT_XFER:") -> {
                    ioExec.execute { handleTransferPayload(d) }
                }
            }
            return
        }

        if (!PilotCore.isAuthorized(ctx, authorId, memberRoles)) return
        val msgId = d.optString("id", "")
        val cl = PilotCore.getCloud(ctx)

        val awaiting = AccountLaunchManager.findAwaitingSession(ctx, authorId, ch)
        if (awaiting != null) {
            if (content.equals("!cancel", ignoreCase = true)) {
                AccountLaunchManager.removeSession(ctx, awaiting.sessionId)
                if (awaiting.coordinatorCloud.equals(cl, ignoreCase = true)) {
                    ioExec.execute { sendMsg(ch, "🛑 Cancelled **!accountlaunch** session (`${awaiting.sessionId}`).") }
                }
                return
            }

            val atts = d.optJSONArray("attachments")
            val hasFile = atts != null && atts.length() > 0
            val isSkip = content.equals("skip", ignoreCase = true)
            val isInlineJson = content.startsWith("{") && content.endsWith("}")

            if (hasFile || isSkip || isInlineJson) {
                if (isSkip) {
                    awaiting.skipJson = true
                } else if (hasFile) {
                    awaiting.jsonUrl = atts!!.getJSONObject(0).optString("url", "")
                } else {
                    awaiting.inlineJson = content
                }
                awaiting.stage = LaunchStage.PICKING_CLOUD
                AccountLaunchManager.putSession(ctx, awaiting)
                ioExec.execute { collectAndPostAvailableCloudMenu(ch, msgId, awaiting) }
                return
            }
        }

        if (!content.startsWith("!")) return
        val parts = content.split(" ", limit = 2)
        val cmd = parts[0].lowercase()
        val arg = parts.getOrNull(1)?.trim() ?: ""

        when (cmd) {
            "!help", "!commands" -> ioExec.execute { sendHelp(ch, msgId) }
            "!version", "!ver" -> ioExec.execute {
                collectAndPostSharedText(ch, msgId, "**📱 Online Pilot Instances:**") { cloud, ver ->
                    "• **$cloud** — `$ver`"
                }
            }
            "!createconfig", "!newconfig" -> ioExec.execute {
                val btn = JSONObject()
                    .put("type", 2)
                    .put("style", 1)
                    .put("label", "⚙️ Open Config Builder")
                    .put("custom_id", "pilot_cfg_btn:open:$cl")
                val row = JSONArray().put(JSONObject().put("type", 1).put("components", JSONArray().put(btn)))
                val header = "**🛠️ Pilot Sync Config Builder**"
                postSingleCoordinatorMessage(
                    ch,
                    msgId,
                    header,
                    JSONObject()
                        .put("content", "$header\nClick the button below to enter your **Bot Token** (include one `@` inside to bypass Discord's scanner) and **Allowed User/Role ID(s)** (comma-separated):")
                        .put("components", row)
                )
            }
            "!accountlaunch", "!al" -> ioExec.execute {
                val sid = msgId.takeLast(8).ifEmpty { (100000..999999).random().toString() }
                val btn = JSONObject()
                    .put("type", 2)
                    .put("style", 3)
                    .put("label", "🚀 Enter Roblox Cookie")
                    .put("custom_id", "pilot_al_btn:open:$cl:$sid")
                val row = JSONArray().put(JSONObject().put("type", 1).put("components", JSONArray().put(btn)))
                val header = "**🚀 Pilot Account Launch**"
                postSingleCoordinatorMessage(
                    ch,
                    msgId,
                    header,
                    JSONObject()
                        .put("content", "$header\nClick the button below to submit the `.ROBLOSECURITY` cookie. Pilot will resolve the username, prompt for your Nousigi Hub JSON (`message.txt`), and let you pick an available UGPhone slot:")
                        .put("components", row)
                )
            }
            "!autorejoin", "!ar" -> ioExec.execute {
                collectAndPostSharedCloudMenu(ch, msgId, "ar", "**🔁 Select Cloud Phone to Configure Auto-Rejoin:**")
            }
            "!rejoin" -> {
                if (arg.isEmpty()) {
                    ioExec.execute {
                        if (!hasBotRepliedAfter(ch, msgId)) sendMsg(ch, "⚠️ **Missing username!** Usage: `!rejoin <username>`")
                    }
                } else {
                    ioExec.execute {
                        val list = PilotCore.cachedScan.ifEmpty { PilotCore.scanRbx(ctx) }
                        val q = arg.lowercase()
                        val match = list.firstOrNull { it.user != "Empty" && (it.user.lowercase().contains(q) || PilotCore.similarity(q, it.user.lowercase()) >= 0.45) }
                        if (match != null) {
                            sendMsg(ch, "🔁 **📱 $cl** — Force-rejoining **${match.user}** (`${match.pkg}`)...")
                            Rejoiner.relaunch(ctx, match.pkg)
                        } else {
                            runCatching { Thread.sleep(2200L + (abs(cl.hashCode()) % 12) * 100L) }
                            if (!hasBotRepliedAfter(ch, msgId)) {
                                sendMsg(ch, "⚠️ No matching account found for `$arg` on any online cloud phone.")
                            }
                        }
                    }
                }
            }
            "!scan" -> ioExec.execute {
                collectAndPostSharedCloudMenu(ch, msgId, "s", "**Select Cloud to Scan:**")
            }
            "!logout" -> ioExec.execute {
                collectAndPostSharedCloudMenu(ch, msgId, "l", "**Select Cloud to Logout:**")
            }
            "!delete" -> ioExec.execute {
                collectAndPostSharedCloudMenu(ch, msgId, "d", "**Select Cloud to Delete Account & History:**")
            }
            "!changeslot" -> ioExec.execute {
                if (arg.isEmpty()) {
                    collectAndPostSharedCloudMenu(ch, msgId, "cs1", "**Select Source Cloud (where the account is now):**")
                } else {
                    val list = PilotCore.cachedScan.ifEmpty { PilotCore.scanRbx(ctx) }
                    val match = list.firstOrNull { it.user != "Empty" && it.user.equals(arg, ignoreCase = true) }
                    if (match != null) {
                        val header = "**📦 Moving `${match.user}`** (from **$cl** `${match.pkg}`)\nSelect Destination Cloud Phone:"
                        val sentRes = apiPost("https://discord.com/api/v10/channels/$ch/messages", JSONObject().put("content", header))
                        val anchorId = runCatching { JSONObject(sentRes).getString("id") }.getOrDefault(msgId)
                        collectAndPostSharedCloudMenu(ch, anchorId, "cs_dst:$cl:${match.pkg}:${match.user}", header)
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
                        collectAndPostSharedCloudMenu(ch, msgId, "r:$arg", "**Select Cloud to Rename to `$arg`:**")
                    }
                }
            }
            "!update" -> {
                if (arg.isNotEmpty()) PilotCore.saveUpdateUrl(ctx, arg)
                ioExec.execute {
                    val status = AppUpdater.checkAndInstall(ctx, force = true)
                    collectAndPostSharedText(ch, msgId, "**🔄 Pilot OTA Update:**") { cloud, _ ->
                        if (cloud == cl) "• **$cloud** — $status" else "• **$cloud** — Update triggered"
                    }
                }
            }
            "!sschannel" -> {
                PilotCore.saveSsChannel(ctx, ch)
                ioExec.execute {
                    collectAndPostSharedText(ch, msgId, "✅ **Screenshot Channel Updated:**") { cloud, ver ->
                        "• **$cloud** (`$ver`) — synced"
                    }
                }
            }
            "!ss" -> ioExec.execute {
                if (arg.equals("all", ignoreCase = true)) {
                    captureAndSendAll(ch)
                } else {
                    collectAndPostSharedCloudMenu(ch, msgId, "ss", "**Select Cloud to Screenshot:**")
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
                        sendMsg(ch, "🔍 **Match for `$arg`:**\n${hits.joinToString("\n")}")
                    } else {
                        runCatching { Thread.sleep(2200L + (abs(cl.hashCode()) % 12) * 100L) }
                        if (!hasBotRepliedAfter(ch, msgId)) {
                            sendMsg(ch, "⚠️ No matching account found for `$arg` on any online cloud phone.")
                        }
                    }
                }
            }
            "!config" -> {
                ioExec.execute {
                    val code = PilotCore.makeCode(ctx, autoRoot = true)
                    val header = "**📋 Your Pilot Sync Code (Auto-Root Enabled):**"
                    val won = postSingleCoordinatorMessage(
                        ch,
                        msgId,
                        header,
                        JSONObject().put("content", "$header\n```\n$code\n```")
                    )
                    if (won) {
                        val dmRes = apiPost("https://discord.com/api/v10/users/@me/channels", JSONObject().put("recipient_id", authorId))
                        val dmCh = runCatching { JSONObject(dmRes).getString("id") }.getOrDefault("")
                        if (dmCh.isNotEmpty()) {
                            sendMsg(dmCh, "**Your Pilot Sync Code:**\n`$code`\n```\n$code\n```")
                        }
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

    private fun postSingleCoordinatorMessage(ch: String, anchorMsgId: String, prefix: String, payload: JSONObject): Boolean {
        val myCloud = PilotCore.getCloud(ctx)
        val jitterMs = (abs(myCloud.hashCode()) % 14) * 100L
        if (jitterMs > 0) runCatching { Thread.sleep(jitterMs) }

        if (findCanonicalBotMessage(ch, anchorMsgId, prefix) != null) return false

        val postedRaw = apiPost("https://discord.com/api/v10/channels/$ch/messages", payload)
        val postedId = runCatching { JSONObject(postedRaw).getString("id") }.getOrDefault("")
        if (postedId.isEmpty()) return false

        runCatching { Thread.sleep(450) }
        val canonical = findCanonicalBotMessage(ch, anchorMsgId, prefix)
        if (canonical != null && canonical.optString("id") != postedId) {
            apiDelete("https://discord.com/api/v10/channels/$ch/messages/$postedId")
            return false
        }
        return true
    }

    private fun postSelfCleaningCheckIn(ch: String, text: String) {
        val raw = apiPost("https://discord.com/api/v10/channels/$ch/messages", JSONObject().put("content", text))
        val mid = runCatching { JSONObject(raw).getString("id") }.getOrDefault("")
        if (mid.isNotEmpty()) {
            exec.schedule({ apiDelete("https://discord.com/api/v10/channels/$ch/messages/$mid") }, 3500, TimeUnit.MILLISECONDS)
        }
    }

    private fun naturalCompare(a: String, b: String): Int {
        val rx = Regex("""(\d+|\D+)""")
        val pa = rx.findAll(a).map { it.value }.toList()
        val pb = rx.findAll(b).map { it.value }.toList()
        for (i in 0 until min(pa.size, pb.size)) {
            val na = pa[i].toLongOrNull()
            val nb = pb[i].toLongOrNull()
            val cmp = if (na != null && nb != null) na.compareTo(nb) else pa[i].compareTo(pb[i], ignoreCase = true)
            if (cmp != 0) return cmp
        }
        return pa.size.compareTo(pb.size)
    }

    private fun collectAndPostAvailableCloudMenu(ch: String, anchorMsgId: String, session: LaunchSession) {
        val myCloud = PilotCore.getCloud(ctx)
        val myVer = PilotCore.APP_VERSION
        val slots = PilotCore.cachedScan.ifEmpty { PilotCore.scanRbx(ctx) }
        val freeCount = slots.count { it.status == "EMPTY" || it.status == "LOGGED OUT" }
        val title = "**🚀 Account Launch (`${session.username}`)** — Select a Cloud Phone with an available slot:"

        if (freeCount == 0) {
            if (session.coordinatorCloud.equals(myCloud, ignoreCase = true)) {
                runCatching { Thread.sleep(3200) }
                if (!hasBotRepliedAfter(ch, anchorMsgId)) {
                    AccountLaunchManager.removeSession(ctx, session.sessionId)
                    sendMsg(ch, "⚠️ No online cloud phones currently have an **Empty** or **Logged Out** slot available for `${session.username}`.")
                }
            }
            return
        }

        val map = freeCheckIns.computeIfAbsent(anchorMsgId) { ConcurrentHashMap() }
        map[myCloud] = myVer to freeCount

        val jitterMs = (abs(myCloud.hashCode()) % 14) * 95L
        if (jitterMs > 0) runCatching { Thread.sleep(jitterMs) }

        val existing = findCanonicalBotMessage(ch, anchorMsgId, title)
        val targetId: String
        if (existing == null) {
            val opt = JSONObject()
                .put("label", "📱 $myCloud".take(100))
                .put("value", myCloud.take(100))
                .put("description", "$freeCount free slot(s) • $myVer".take(100))
            val menu = JSONObject()
                .put("type", 3)
                .put("custom_id", "pilot_al_cloud:${session.sessionId}:row0")
                .put("placeholder", "Scanning clouds with free slots...")
                .put("options", JSONArray().put(opt))
            val row = JSONArray().put(JSONObject().put("type", 1).put("components", JSONArray().put(menu)))
            val postedRaw = apiPost(
                "https://discord.com/api/v10/channels/$ch/messages",
                JSONObject().put("content", title).put("components", row)
            )
            val postedId = runCatching { JSONObject(postedRaw).getString("id") }.getOrDefault("")

            runCatching { Thread.sleep(350) }
            val canonical = findCanonicalBotMessage(ch, anchorMsgId, title)
            if (canonical != null && postedId.isNotEmpty() && canonical.optString("id") != postedId) {
                apiDelete("https://discord.com/api/v10/channels/$ch/messages/$postedId")
                postSelfCleaningCheckIn(ch, "PILOT_FREE:$anchorMsgId:$myCloud:$myVer:$freeCount")
                return
            }
            targetId = canonical?.optString("id")?.takeIf { it.isNotEmpty() } ?: postedId
        } else {
            postSelfCleaningCheckIn(ch, "PILOT_FREE:$anchorMsgId:$myCloud:$myVer:$freeCount")
            return
        }

        if (targetId.isEmpty()) return

        runCatching { Thread.sleep(2200) }

        val recentRaw = apiGet("https://discord.com/api/v10/channels/$ch/messages?after=$anchorMsgId&limit=50")
        runCatching {
            val arr = JSONArray(recentRaw)
            for (i in 0 until arr.length()) {
                val m = arr.getJSONObject(i)
                val c = m.optString("content")
                if (c.startsWith("PILOT_FREE:$anchorMsgId:")) {
                    val p = c.split(":", limit = 5)
                    if (p.size >= 5) {
                        val cnt = p[4].toIntOrNull() ?: 0
                        if (cnt > 0) map[p[2]] = p[3] to cnt
                        val mid = m.optString("id")
                        if (mid.isNotEmpty()) apiDelete("https://discord.com/api/v10/channels/$ch/messages/$mid")
                    }
                }
            }
        }

        val q = freeCheckInMsgIds.remove(anchorMsgId)
        if (q != null) {
            while (true) {
                val mid = q.poll() ?: break
                apiDelete("https://discord.com/api/v10/channels/$ch/messages/$mid")
            }
        }

        val sortedClouds = map.keys.sortedWith { a, b -> naturalCompare(a, b) }
        val allOpts = sortedClouds.map { cloud ->
            val (ver, cnt) = map[cloud] ?: (myVer to 1)
            JSONObject()
                .put("label", "📱 $cloud".take(100))
                .put("value", cloud.take(100))
                .put("description", "$cnt free slot(s) • $ver".take(100))
        }
        freeCheckIns.remove(anchorMsgId)

        val rows = JSONArray()
        val chunks = allOpts.chunked(25).take(5)
        chunks.forEachIndexed { idx, chunk ->
            val arr = JSONArray()
            chunk.forEach { arr.put(it) }
            val ph = if (chunks.size > 1) {
                "Select Available Cloud (${idx * 25 + 1}–${idx * 25 + chunk.size})..."
            } else {
                "Select Cloud (${allOpts.size} with free slots)..."
            }
            val menu = JSONObject()
                .put("type", 3)
                .put("custom_id", "pilot_al_cloud:${session.sessionId}:row$idx")
                .put("placeholder", ph)
                .put("options", arr)
            rows.put(JSONObject().put("type", 1).put("components", JSONArray().put(menu)))
        }

        apiPatch(
            "https://discord.com/api/v10/channels/$ch/messages/$targetId",
            JSONObject().put("content", title).put("components", rows)
        )
    }

    private fun electAndCollectClouds(
        ch: String,
        anchorMsgId: String,
        title: String,
        initialBuilder: (String, String) -> JSONObject
    ): Pair<String, Map<String, String>>? {
        val myCloud = PilotCore.getCloud(ctx)
        val myVer = PilotCore.APP_VERSION
        val map = checkIns.computeIfAbsent(anchorMsgId) { ConcurrentHashMap() }
        map[myCloud] = myVer

        val jitterMs = (abs(myCloud.hashCode()) % 14) * 95L
        if (jitterMs > 0) runCatching { Thread.sleep(jitterMs) }

        val existing = findCanonicalBotMessage(ch, anchorMsgId, title)
        val targetId: String
        if (existing == null) {
            val initBody = initialBuilder(myCloud, myVer)
            val postedRaw = apiPost("https://discord.com/api/v10/channels/$ch/messages", initBody)
            val postedId = runCatching { JSONObject(postedRaw).getString("id") }.getOrDefault("")

            runCatching { Thread.sleep(350) }
            val canonical = findCanonicalBotMessage(ch, anchorMsgId, title)
            if (canonical != null && postedId.isNotEmpty() && canonical.optString("id") != postedId) {
                apiDelete("https://discord.com/api/v10/channels/$ch/messages/$postedId")
                postSelfCleaningCheckIn(ch, "PILOT_HERE:$anchorMsgId:$myCloud:$myVer")
                return null
            }
            targetId = canonical?.optString("id")?.takeIf { it.isNotEmpty() } ?: postedId
        } else {
            postSelfCleaningCheckIn(ch, "PILOT_HERE:$anchorMsgId:$myCloud:$myVer")
            return null
        }

        if (targetId.isEmpty()) return null

        runCatching { Thread.sleep(2200) }

        val recentRaw = apiGet("https://discord.com/api/v10/channels/$ch/messages?after=$anchorMsgId&limit=50")
        runCatching {
            val arr = JSONArray(recentRaw)
            for (i in 0 until arr.length()) {
                val m = arr.getJSONObject(i)
                val c = m.optString("content")
                if (c.startsWith("PILOT_HERE:$anchorMsgId:")) {
                    val p = c.split(":", limit = 4)
                    if (p.size >= 4) {
                        map[p[2]] = p[3]
                        val mid = m.optString("id")
                        if (mid.isNotEmpty()) apiDelete("https://discord.com/api/v10/channels/$ch/messages/$mid")
                    }
                }
            }
        }

        val q = checkInMsgIds.remove(anchorMsgId)
        if (q != null) {
            while (true) {
                val mid = q.poll() ?: break
                apiDelete("https://discord.com/api/v10/channels/$ch/messages/$mid")
            }
        }

        val snapshot = LinkedHashMap<String, String>()
        map.keys.sortedWith { a, b -> naturalCompare(a, b) }.forEach { k ->
            snapshot[k] = map[k] ?: myVer
        }
        checkIns.remove(anchorMsgId)
        return targetId to snapshot
    }

    private fun collectAndPostSharedCloudMenu(ch: String, anchorMsgId: String, mode: String, title: String) {
        val result = electAndCollectClouds(ch, anchorMsgId, title) { myCloud, myVer ->
            val opt = JSONObject()
                .put("label", "📱 $myCloud".take(100))
                .put("value", myCloud.take(100))
                .put("description", "$myVer • Tap to select".take(100))
            val menu = JSONObject()
                .put("type", 3)
                .put("custom_id", "pilot_cloud:$mode:row0")
                .put("placeholder", "Scanning online clouds...")
                .put("options", JSONArray().put(opt))
            val row = JSONArray().put(JSONObject().put("type", 1).put("components", JSONArray().put(menu)))
            JSONObject().put("content", title).put("components", row)
        } ?: return

        val (targetId, cloudsMap) = result
        val allOpts = cloudsMap.map { (cloud, ver) ->
            JSONObject()
                .put("label", "📱 $cloud".take(100))
                .put("value", cloud.take(100))
                .put("description", "$ver • Tap to select".take(100))
        }

        val rows = JSONArray()
        val chunks = allOpts.chunked(25).take(5)
        chunks.forEachIndexed { idx, chunk ->
            val arr = JSONArray()
            chunk.forEach { arr.put(it) }
            val ph = if (chunks.size > 1) {
                "Select Cloud (${idx * 25 + 1}–${idx * 25 + chunk.size})..."
            } else {
                "Select Cloud (${allOpts.size} online)..."
            }
            val menu = JSONObject()
                .put("type", 3)
                .put("custom_id", "pilot_cloud:$mode:row$idx")
                .put("placeholder", ph)
                .put("options", arr)
            rows.put(JSONObject().put("type", 1).put("components", JSONArray().put(menu)))
        }

        apiPatch("https://discord.com/api/v10/channels/$ch/messages/$targetId", JSONObject().put("content", title).put("components", rows))
    }

    private fun collectAndPostSharedText(ch: String, anchorMsgId: String, header: String, lineFormatter: (String, String) -> String) {
        val result = electAndCollectClouds(ch, anchorMsgId, header) { myCloud, myVer ->
            JSONObject().put("content", "$header\n${lineFormatter(myCloud, myVer)}")
        } ?: return

        val (targetId, cloudsMap) = result
        val lines = cloudsMap.map { (cloud, ver) -> lineFormatter(cloud, ver) }
        val newContent = "$header\n${lines.joinToString("\n")}".take(1950)
        apiPatch("https://discord.com/api/v10/channels/$ch/messages/$targetId", JSONObject().put("content", newContent))
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
        val afterRaw = apiGet("https://discord.com/api/v10/channels/$ch/messages?after=$triggerMsgId&limit=10")
        return runCatching {
            val arr = JSONArray(afterRaw)
            (0 until arr.length()).any { i ->
                val m = arr.getJSONObject(i)
                val c = m.optString("content")
                m.optJSONObject("author")?.optString("id") == botId &&
                    !c.startsWith("PILOT_HERE:") &&
                    !c.startsWith("PILOT_FREE:")
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
            restClient.newCall(req).execute().use { resp ->
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

            **🚀 Account Launch & Setup**
            • `!accountlaunch` (or `!al`) — Submit `.ROBLOSECURITY` cookie via modal, resolve username, upload Nousigi Hub JSON (`message.txt`), pick an available UGPhone slot & launch

            **🔍 Monitoring & Search**
            • `!scan` — Select a cloud phone to list all Roblox instances & statuses
            • `!search <username>` — Find which cloud & slot has an account (exact or close match)
            • `!checkstatus <username>` — Expand that player's Roblox to **full-tab**, capture screenshot, and restore/minimize it back
            • `!version` — Show the installed Pilot version on every online cloud phone

            **🔁 All-in-One Auto-Rejoiner**
            • `!autorejoin` — Toggle Auto-Rejoin ON/OFF or set a Place ID / Private Server link
            • `!rejoin <username>` — Immediately force-restart & rejoin a specific account

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
            • `!config` — Post & DM your current `XXXXXXX-...` sync code
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

    private fun openAccountLaunchModal(id: String, token: String, targetCloud: String, sid: String) {
        val fCookie = JSONObject()
            .put("type", 4)
            .put("custom_id", "cookie")
            .put("label", ".ROBLOSECURITY Cookie")
            .put("style", 2)
            .put("min_length", 50)
            .put("max_length", 2500)
            .put("placeholder", "_|WARNING:-DO-NOT-SHARE-THIS.--Sharing-this-will-allow-someone-to-log-in...")
            .put("required", true)

        val fGame = JSONObject()
            .put("type", 4)
            .put("custom_id", "game")
            .put("label", "Nousigi Hub Game Suffix (optional)")
            .put("style", 1)
            .put("max_length", 60)
            .put("value", "AnimeAdventures")
            .put("placeholder", "AnimeAdventures")
            .put("required", false)

        val rows = JSONArray()
            .put(JSONObject().put("type", 1).put("components", JSONArray().put(fCookie)))
            .put(JSONObject().put("type", 1).put("components", JSONArray().put(fGame)))

        val modalData = JSONObject()
            .put("custom_id", "pilot_al_modal:$targetCloud:$sid")
            .put("title", "Account Launch — Cookie Setup")
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
            if (cid.startsWith("pilot_al_modal:")) {
                val p = cid.removePrefix("pilot_al_modal:").split(":", limit = 2)
                if (p.size < 2) return
                val coordinatorCloud = p[0]
                val sid = p[1]

                if (!PilotCore.isAuthorized(ctx, userId, memberRoles)) {
                    if (coordinatorCloud.equals(myCloud, ignoreCase = true)) {
                        replyInteraction(id, token, 4, JSONObject().put("content", "Unauthorized").put("flags", 64))
                    }
                    return
                }

                var rawCookie = ""
                var rawGame = "AnimeAdventures"
                val rows = data.optJSONArray("components")
                if (rows != null) {
                    for (r in 0 until rows.length()) {
                        val comps = rows.getJSONObject(r).optJSONArray("components") ?: continue
                        for (c in 0 until comps.length()) {
                            val item = comps.getJSONObject(c)
                            when (item.optString("custom_id")) {
                                "cookie" -> rawCookie = item.optString("value", "")
                                "game" -> rawGame = item.optString("value", "AnimeAdventures")
                            }
                        }
                    }
                }

                val gameSuffix = AccountLaunchManager.sanitizeGameSuffix(rawGame)
                AccountLaunchManager.cleanupExpired(ctx)

                val session = LaunchSession(
                    sessionId = sid,
                    coordinatorCloud = coordinatorCloud,
                    authorId = userId,
                    channelId = ch,
                    rawCookie = rawCookie,
                    gameSuffix = gameSuffix
                )
                AccountLaunchManager.putSession(ctx, session)

                if (!coordinatorCloud.equals(myCloud, ignoreCase = true)) return

                replyInteraction(
                    id, token, 4,
                    JSONObject().put("content", "⏳ Verifying `.ROBLOSECURITY` cookie with Roblox...").put("flags", 64)
                )

                ioExec.execute {
                    val (ok, rbxUid, nameOrErr) = AccountLaunchManager.resolveCookieUser(rawCookie)
                    if (!ok) {
                        AccountLaunchManager.removeSession(ctx, sid)
                        if (ch.isNotEmpty()) {
                            sendMsg(ch, "❌ **Account Launch Failed** (`$sid`): $nameOrErr")
                        }
                        return@execute
                    }
                    session.username = nameOrErr
                    session.userId = rbxUid
                    session.stage = LaunchStage.AWAITING_JSON
                    AccountLaunchManager.putSession(ctx, session)
                    if (ch.isNotEmpty()) {
                        sendMsg(
                            ch,
                            "✅ **Cookie Verified!**\n" +
                            "• **Account:** `$nameOrErr` (`$rbxUid`)\n" +
                            "• **Target Config:** `${AccountLaunchManager.NOUSIGI_DIR}/${nameOrErr}_${gameSuffix}.json`\n" +
                            "• **Session:** `$sid`\n\n" +
                            "📄 **Next Step:** Send your Nousigi Hub JSON file (`message.txt` or `.json`) in this channel now — or type `skip` to launch without a config file *(type `!cancel` to abort)*."
                        )
                    }
                }
                return
            }

            if (cid.startsWith("pilot_ar_modal:")) {
                val targetCloud = cid.removePrefix("pilot_ar_modal:")
                if (!targetCloud.equals(myCloud, ignoreCase = true)) return
                if (!PilotCore.isAuthorized(ctx, userId, memberRoles)) {
                    replyInteraction(id, token, 4, JSONObject().put("content", "Unauthorized").put("flags", 64))
                    return
                }
                var targetLink = ""
                val rows = data.optJSONArray("components")
                if (rows != null) {
                    for (r in 0 until rows.length()) {
                        val comps = rows.getJSONObject(r).optJSONArray("components") ?: continue
                        for (c in 0 until comps.length()) {
                            val item = comps.getJSONObject(c)
                            if (item.optString("custom_id") == "target") {
                                targetLink = item.optString("value", "")
                            }
                        }
                    }
                }
                Rejoiner.setConfig(ctx, enabled = true, target = targetLink)
                val displayTarget = targetLink.trim().ifEmpty { "Auto-detect each account's last Place ID" }
                replyInteraction(
                    id, token, 4,
                    JSONObject()
                        .put(
                            "content",
                            "✅ **Auto-Rejoiner Enabled on $myCloud!**\n" +
                            "• **Target:** `$displayTarget`\n" +
                            "• **Checks:** Delta Heartbeat + Freeze Detector + Roblox Presence API + Process Watchdog\n" +
                            "• **Teleport Protection:** Active (pauses during place-to-place teleports)"
                        )
                        .put("flags", 64)
                )
                return
            }

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

        if (cid.startsWith("pilot_al_btn:open:")) {
            val p = cid.removePrefix("pilot_al_btn:open:").split(":", limit = 2)
            if (p.size < 2) return
            val targetCloud = p[0]
            val sid = p[1]
            if (!targetCloud.equals(myCloud, ignoreCase = true)) return
            if (!PilotCore.isAuthorized(ctx, userId, memberRoles)) {
                replyInteraction(id, token, 4, JSONObject().put("content", "Unauthorized").put("flags", 64))
                return
            }
            openAccountLaunchModal(id, token, myCloud, sid)
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
        val sel = vals.optString(0).trim()

        val firstColon = cid.indexOf(':')
        val lastColon = cid.lastIndexOf(':')
        if (firstColon <= 0 || lastColon <= firstColon) return

        val kind = cid.substring(0, firstColon)
        val mode = cid.substring(firstColon + 1, lastColon)
        val suffix = cid.substring(lastColon + 1)

        if (kind == "pilot_al_cloud") {
            if (!sel.equals(myCloud, ignoreCase = true)) return
            if (!PilotCore.isAuthorized(ctx, userId, memberRoles)) {
                replyInteraction(id, token, 4, JSONObject().put("content", "Unauthorized").put("flags", 64))
                return
            }
            val session = AccountLaunchManager.getSession(ctx, mode)
            if (session == null) {
                replyInteraction(id, token, 4, JSONObject().put("content", "⚠️ Account Launch session expired. Please run `!accountlaunch` again.").put("flags", 64))
                return
            }

            val allSlots = PilotCore.cachedScan.ifEmpty { PilotCore.scanRbx(ctx) }
            val freeIndexed = allSlots.mapIndexedNotNull { idx, acc ->
                if (acc.status == "EMPTY" || acc.status == "LOGGED OUT") idx to acc else null
            }
            if (freeIndexed.isEmpty()) {
                replyInteraction(id, token, 4, JSONObject().put("content", "⚠️ **$myCloud** no longer has any Empty or Logged Out slots.").put("flags", 64))
                return
            }

            val opts = JSONArray()
            freeIndexed.take(25).forEach { (idx, acc) ->
                opts.put(
                    JSONObject()
                        .put("label", "[${idx + 1}] ${acc.user} [${acc.status}] (${acc.pkg})".take(100))
                        .put("value", acc.pkg.take(100))
                        .put("description", "Launch ${session.username.ifEmpty { "account" }} in ${acc.pkg}".take(100))
                )
            }
            val menu = JSONObject()
                .put("type", 3)
                .put("custom_id", "pilot_al_slot:${session.sessionId}:$myCloud")
                .put("placeholder", "Pick available slot on $myCloud...".take(150))
                .put("options", opts)
            val row = JSONArray().put(JSONObject().put("type", 1).put("components", JSONArray().put(menu)))
            replyInteraction(
                id, token, 4,
                JSONObject()
                    .put(
                        "content",
                        "**🚀 $myCloud** — Select an available slot for **`${session.username.ifEmpty { "Account" }}`**:\n${PilotCore.fmtAll(allSlots)}".take(1950)
                    )
                    .put("components", row)
                    .put("flags", 64)
            )
            return
        }

        if (kind == "pilot_cloud") {
            if (!sel.equals(myCloud, ignoreCase = true)) return

            if (!PilotCore.isAuthorized(ctx, userId, memberRoles)) {
                replyInteraction(id, token, 4, JSONObject().put("content", "Unauthorized").put("flags", 64))
                return
            }

            if (mode == "ar") {
                val enabled = Rejoiner.isEnabled(ctx)
                val curTarget = Rejoiner.getTarget(ctx).ifEmpty { "Auto (Last Place ID)" }
                val opts = JSONArray()
                    .put(JSONObject().put("label", "✅ Enable Auto-Rejoin (Auto Last Place)").put("value", "ON_AUTO").put("description", "Rejoin each account's last Place ID automatically"))
                    .put(JSONObject().put("label", "🔗 Enable Auto-Rejoin (Custom Place / VIP Link)").put("value", "ON_LINK").put("description", "Enter a specific Place ID or Private Server URL"))
                    .put(JSONObject().put("label", "🛑 Disable Auto-Rejoin").put("value", "OFF").put("description", "Turn off automatic rejoin monitoring on $myCloud".take(100)))
                val menu = JSONObject()
                    .put("type", 3)
                    .put("custom_id", "pilot_ar_act:cfg:$myCloud")
                    .put("placeholder", "Choose Auto-Rejoin action for $myCloud...")
                    .put("options", opts)
                val row = JSONArray().put(JSONObject().put("type", 1).put("components", JSONArray().put(menu)))
                replyInteraction(
                    id, token, 4,
                    JSONObject()
                        .put("content", "**🔁 $myCloud — Auto-Rejoiner Settings**\n• **Status:** ${if (enabled) "🟢 ON" else "🔴 OFF"}\n• **Target:** `$curTarget`")
                        .put("components", row)
                        .put("flags", 64)
                )
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
                                .put("label", "[${i + 1}] ${a.user} [${a.status}] (${a.pkg})".take(100))
                                .put("value", a.pkg.take(100))
                                .put("description", "Move $srcUser into this slot on $myCloud".take(100))
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
                        .put("content", "**📱 $myCloud** — Select destination slot to receive **$srcUser** (from **$srcCloud**):\n${PilotCore.fmtAll(list)}".take(1950))
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
                replyInteraction(id, token, 4, JSONObject().put("content", "**📱 $myCloud** (${PilotCore.APP_VERSION}):\n${PilotCore.fmtAll(list)}".take(1950)).put("flags", 64))
            } else if (mode == "cs1") {
                val activeList = list.filter { it.user != "Empty" }
                if (activeList.isEmpty()) {
                    replyInteraction(id, token, 4, JSONObject().put("content", "**📱 $myCloud** — No accounts available to move.").put("flags", 64))
                    return
                }
                val opts = JSONArray()
                activeList.take(25).forEach { a ->
                    opts.put(JSONObject().put("label", "${a.user} [${a.status}] (${a.pkg})".take(100)).put("value", "${a.pkg}|${a.user}".take(100)))
                }
                val menu = JSONObject().put("type", 3).put("custom_id", "pilot_cs2:move:$myCloud")
                    .put("placeholder", "Select account to move from $myCloud...").put("options", opts)
                val row = JSONArray().put(JSONObject().put("type", 1).put("components", JSONArray().put(menu)))
                replyInteraction(id, token, 4, JSONObject().put("content", "**📱 $myCloud** — Select which account to move:\n${PilotCore.fmtAll(list)}".take(1950)).put("components", row).put("flags", 64))
            } else {
                val opts = JSONArray().put(JSONObject().put("label", if (mode == "d") "DELETE ALL DATA & HISTORY" else "LOGOUT ALL ON THIS CLOUD").put("value", "ALL"))
                list.take(24).forEach { a -> opts.put(JSONObject().put("label", "${a.user} [${a.status}] (${a.pkg})".take(100)).put("value", a.pkg.take(100))) }
                val menu = JSONObject().put("type", 3).put("custom_id", "pilot_acc:$mode:$myCloud")
                    .put("placeholder", "Select account on $myCloud...").put("options", opts)
                val row = JSONArray().put(JSONObject().put("type", 1).put("components", JSONArray().put(menu)))
                val label = if (mode == "d") "Select account to **DELETE** (wipes session + history):" else "Select account to **LOGOUT**:"
                replyInteraction(id, token, 4, JSONObject().put("content", "**📱 $myCloud** — $label\n${PilotCore.fmtAll(list)}".take(1950)).put("components", row).put("flags", 64))
            }
            return
        }

        if (!suffix.equals(myCloud, ignoreCase = true)) return

        if (!PilotCore.isAuthorized(ctx, userId, memberRoles)) {
            replyInteraction(id, token, 4, JSONObject().put("content", "Unauthorized").put("flags", 64))
            return
        }

        if (kind == "pilot_al_slot") {
            val targetPkg = sel
            val session = AccountLaunchManager.getSession(ctx, mode)?.also {
                AccountLaunchManager.removeSession(ctx, mode)
            }
            if (session == null) {
                replyInteraction(id, token, 7, JSONObject().put("content", "⚠️ Session expired. Please run `!accountlaunch` again.").put("components", JSONArray()))
                return
            }
            replyInteraction(
                id, token, 7,
                JSONObject()
                    .put("content", "⏳ Injecting cookie & saving Nousigi Hub config for **`${session.username}`** on **$myCloud** (`$targetPkg`)...")
                    .put("components", JSONArray())
            )
            ioExec.execute {
                val (ok, resultMsg) = AccountLaunchManager.executeLaunch(ctx, targetPkg, session)
                if (ch.isNotEmpty()) {
                    if (ok) sendMsg(ch, resultMsg)
                    else sendMsg(ch, "❌ **Account Launch Failed** on **$myCloud** (`$targetPkg`): $resultMsg")
                }
            }
        } else if (kind == "pilot_ar_act") {
            when (sel) {
                "ON_AUTO" -> {
                    Rejoiner.setConfig(ctx, enabled = true, target = "")
                    replyInteraction(id, token, 7, JSONObject().put("content", "✅ **Auto-Rejoiner Enabled** on **$myCloud** (Auto Last Place ID + Freeze & Teleport Guard).").put("components", JSONArray()))
                }
                "OFF" -> {
                    Rejoiner.setConfig(ctx, enabled = false, target = Rejoiner.getTarget(ctx))
                    replyInteraction(id, token, 7, JSONObject().put("content", "🛑 **Auto-Rejoiner Disabled** on **$myCloud**.").put("components", JSONArray()))
                }
                "ON_LINK" -> {
                    val fTarget = JSONObject()
                        .put("type", 4)
                        .put("custom_id", "target")
                        .put("label", "Place ID or Private Server URL")
                        .put("style", 1)
                        .put("min_length", 4)
                        .put("max_length", 300)
                        .put("placeholder", "e.g. 2753915549 or https://www.roblox.com/games/...")
                        .put("required", false)
                    val modal = JSONObject()
                        .put("custom_id", "pilot_ar_modal:$myCloud")
                        .put("title", "Auto-Rejoin Target ($myCloud)")
                        .put("components", JSONArray().put(JSONObject().put("type", 1).put("components", JSONArray().put(fTarget))))
                    replyInteraction(id, token, 9, modal)
                }
            }
        } else if (kind == "pilot_cs2") {
            val srcPkg = sel.substringBefore("|")
            val srcUser = sel.substringAfter("|", "")
            val header = "**📦 Moving `$srcUser`** (from **$myCloud** `$srcPkg`)\nSelect Destination Cloud Phone:"
            replyInteraction(id, token, 7, JSONObject().put("content", header).put("components", JSONArray()))
            if (ch.isNotEmpty() && msgId.isNotEmpty()) {
                ioExec.execute {
                    collectAndPostSharedCloudMenu(ch, msgId, "cs_dst:$myCloud:$srcPkg:$srcUser", header)
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
                val msg = if (isDel) "**📱 $myCloud** — Deleting ALL accounts & history (now Empty)." else "**📱 $myCloud** — Logging out ALL."
                replyInteraction(id, token, 7, JSONObject().put("content", msg).put("components", JSONArray()))
                ioExec.execute {
                    PilotCore.cachedScan.ifEmpty { PilotCore.scanRbx(ctx) }.forEach { PilotCore.wipe(ctx, it.pkg, isDel) }
                }
            } else {
                val msg = if (isDel) "**📱 $myCloud** — Deleted all data & history for `$sel` (now Empty)." else "**📱 $myCloud** — Logged out `$sel`."
                replyInteraction(id, token, 7, JSONObject().put("content", msg).put("components", JSONArray()))
                ioExec.execute { PilotCore.wipe(ctx, sel, isDel) }
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
            restClient.newCall(req).execute().close()
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
            restClient.newCall(req).execute().close()
        }
    }

    private fun replyInteraction(id: String, token: String, type: Int, data: JSONObject) {
        runCatching {
            val body = JSONObject().put("type", type).put("data", data).toString()
                .toRequestBody("application/json".toMediaType())
            val req = Request.Builder()
                .url("https://discord.com/api/v10/interactions/$id/$token/callback")
                .post(body)
                .build()
            restClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) {
                    val err = resp.body?.string()?.take(180) ?: ""
                    PilotCore.log("[INT-ERR] HTTP ${resp.code}: $err")
                }
            }
        }.onFailure {
            PilotCore.log("[INT-ERR] ${it.message ?: "callback failed"}")
        }
    }

    private fun apiDelete(url: String) {
        runCatching {
            val req = Request.Builder().url(url)
                .header("Authorization", "Bot ${PilotCore.getToken(ctx)}")
                .delete().build()
            restClient.newCall(req).execute().close()
        }
    }

    private fun apiGet(url: String): String = runCatching {
        val req = Request.Builder().url(url)
            .header("Authorization", "Bot ${PilotCore.getToken(ctx)}")
            .get().build()
        restClient.newCall(req).execute().use { it.body?.string() ?: "" }
    }.getOrDefault("")

    private fun apiPatch(url: String, json: JSONObject): String = runCatching {
        val body = json.toString().toRequestBody("application/json".toMediaType())
        val req = Request.Builder().url(url)
            .header("Authorization", "Bot ${PilotCore.getToken(ctx)}")
            .patch(body).build()
        restClient.newCall(req).execute().use { it.body?.string() ?: "" }
    }.getOrDefault("")

    private fun apiPost(url: String, json: JSONObject, auth: Boolean = true): String = runCatching {
        val body = json.toString().toRequestBody("application/json".toMediaType())
        val b = Request.Builder().url(url).post(body)
        if (auth) b.header("Authorization", "Bot ${PilotCore.getToken(ctx)}")
        restClient.newCall(b.build()).execute().use { it.body?.string() ?: "" }
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
        PilotCore.tryAutoSeedFromDisk(this)

        val chId = "pilot_svc"
        if (Build.VERSION.SDK_INT >= 26) {
            val nm = getSystemService(NotificationManager::class.java)
            nm?.createNotificationChannel(NotificationChannel(chId, "Pilot Bot", NotificationManager.IMPORTANCE_LOW))
        }
        val notif = (if (Build.VERSION.SDK_INT >= 26) Notification.Builder(this, chId) else Notification.Builder(this))
            .setContentTitle("Pilot Active (${PilotCore.getCloud(this)}) • ${PilotCore.APP_VERSION}")
            .setContentText("GVIP Optimized • Watchdog & Roblox Bridge Active")
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
            runCatching { Rejoiner.tick(this) }
        }, 15, 15, TimeUnit.SECONDS)

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
                            sock.getOutputStream().write("HTTP/1.1 200 OK\r\nContent-Length: 2\r\n\r\nOK".toByteArray())
                            sock.close()

                            val pathAndQuery = reqLine.substringAfter(" ", "").substringBefore(" ")
                            if (Rejoiner.handleBridgeRequest(this, pathAndQuery)) return@runCatching

                            if (pathAndQuery.startsWith("/ss")) {
                                val userParam = Regex("""[?&]user=([^&\s]+)""").find(pathAndQuery)?.groupValues?.get(1)
                                val user = if (!userParam.isNullOrEmpty()) URLDecoder.decode(userParam, "UTF-8").trim() else ""
                                val ch = PilotCore.getSsChannel(this)
                                if (user.isNotEmpty() && ch.isNotEmpty()) {
                                    PilotCore.log("[LUA-SS] $user")
                                    bot?.sendLuaWindowShot(ch, user)
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
        PilotCore.tryAutoSeedFromDisk(context)
        if (PilotCore.getToken(context).isNotEmpty() && PilotCore.getAllowedIds(context).isNotEmpty()) {
            val svc = Intent(context, PilotService::class.java)
            if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(svc) else context.startService(svc)
        }
    }
}