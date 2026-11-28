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
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.*
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.random.Random

data class AccInfo(val pkg: String, val user: String, val status: String)

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

    fun execLines(cmd: String): List<String> =
        exec(cmd).lines().map { it.trim() }.filter { it.isNotEmpty() }

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
    const val APP_VERSION = "v1.4.3"
    private const val SHARED_SYNC_PATH = "/sdcard/pilot_sync.txt"

    var logs = "Engine $APP_VERSION initialized.\n"
    var onLog: (() -> Unit)? = null
    @Volatile var onAlert: ((title: String, body: String) -> Unit)? = null
    @Volatile var cachedScan: List<AccInfo> = emptyList()

    private val logLines = CopyOnWriteArrayList<String>()
    private val timeFmt = SimpleDateFormat("HH:mm:ss", Locale.US)

    val isRunning: Boolean
        get() = PilotService.isRunning

    fun log(msg: String) {
        val stamped = "[${timeFmt.format(Date())}] $msg"
        logLines.add(stamped)
        while (logLines.size > 150) {
            runCatching { logLines.removeAt(0) }
        }
        logs = (logs + stamped + "\n").takeLast(2800)
        onLog?.invoke()
    }

    fun getLogs(): List<String> = logLines.toList()

    fun prefs(c: Context): SharedPreferences =
        c.applicationContext.getSharedPreferences("pilot_cfg", Context.MODE_PRIVATE)

    private fun histPrefs(c: Context): SharedPreferences =
        c.applicationContext.getSharedPreferences("pilot_hist", Context.MODE_PRIVATE)

    fun startService(c: Context) {
        val app = c.applicationContext
        val svc = Intent(app, PilotService::class.java)
        runCatching {
            if (Build.VERSION.SDK_INT >= 26) app.startForegroundService(svc) else app.startService(svc)
        }
    }

    fun stopService(c: Context) {
        val app = c.applicationContext
        runCatching { app.stopService(Intent(app, PilotService::class.java)) }
        PilotService.isRunning = false
        log("[CORE] Service stop requested")
    }

    fun cleanBotToken(raw: String): String =
        raw.replace("@", "").replace(Regex("""\s+"""), "").trim()

    fun parseAllowedIds(raw: String): List<String> =
        raw.split(",", ";", " ", "\n", "\t")
            .map { it.replace(Regex("""[^0-9]"""), "") }
            .filter { it.length >= 15 }
            .distinct()

    fun getCloud(c: Context): String {
        val f = File("/sdcard/cloud_name.txt")
        if (f.exists()) {
            val t = runCatching { f.readText().trim() }.getOrDefault("")
            if (t.isNotEmpty() && t != "Not Set") return t
        }
        val saved = prefs(c).getString("cloud", "")?.ifEmpty { prefs(c).getString("cloud_name", "") } ?: ""
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
        prefs(c).edit().putString("cloud", clean).putString("cloud_name", clean).apply()
        runCatching { File("/sdcard/cloud_name.txt").writeText(clean) }
    }

    fun getToken(c: Context): String {
        val raw = prefs(c).getString("token", "")?.ifEmpty { prefs(c).getString("bot_token", "") } ?: ""
        return cleanBotToken(raw)
    }

    fun getAllowedIdsRaw(c: Context): String {
        val saved = prefs(c).getString("allowed_ids", "") ?: ""
        if (saved.isNotEmpty()) return saved
        val legacyUid = prefs(c).getLong("user_id", 0L)
        return if (legacyUid != 0L) legacyUid.toString() else ""
    }

    fun getAllowedIds(c: Context): Set<String> = parseAllowedIds(getAllowedIdsRaw(c)).toSet()

    fun getPrimaryUserId(c: Context): Long {
        val ids = parseAllowedIds(getAllowedIdsRaw(c))
        return ids.firstOrNull()?.toLongOrNull() ?: prefs(c).getLong("user_id", 0L)
    }

    fun isAuthorized(c: Context, userId: String, roles: JSONArray?): Boolean {
        val allowed = getAllowedIds(c)
        if (allowed.isEmpty()) return true
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
            .putString("cloud_name", cl)
            .putString("token", cleanTok)
            .putString("bot_token", cleanTok)
            .putString("allowed_ids", joinedIds)
            .putLong("user_id", primaryUid)
            .putString("upd_url", updUrl.trim())
        if (ssChan.trim().isNotEmpty()) edit.putString("ss_channel", ssChan.trim())
        edit.apply()
        runCatching { File("/sdcard/cloud_name.txt").writeText(cl) }
        writeSharedBackup(c)
    }

    private fun writeSharedBackup(c: Context) {
        if (getToken(c).isEmpty()) return
        val code = makeCode(c, autoRoot = true)
        runCatching { File(SHARED_SYNC_PATH).writeText(code) }
        SuShell.exec("printf '%s' '$code' > $SHARED_SYNC_PATH && chmod 666 $SHARED_SYNC_PATH")
    }

    fun tryAutoSeedFromDisk(c: Context): Boolean {
        if (getToken(c).isNotEmpty()) {
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
                log("[SYNC] Auto-loaded full config from $SHARED_SYNC_PATH")
                return true
            }
        }
        return false
    }

    fun makeCode(c: Context, autoRoot: Boolean = true): String {
        val letters = (1..7).map { ('A'..'Z').random() }.joinToString("")
        val rj = Rejoiner.loadConfig(c)
        val rjJson = JSONObject()
            .put("en", rj.enabled)
            .put("bt", rj.autoStartOnBoot)
            .put("pid", rj.targetPlaceId)
            .put("vip", rj.privateServerLinks)
            .put("fps", rj.fpsOptimization)
            .put("r3d", rj.render3dOff)
            .put("ank", rj.autoGetNousigiKey)
            .put("lurl", rj.luarmorUrl)
            .put("lve", rj.lvEmail)
            .put("lvp", rj.lvPassword)
            .put("nkp", rj.nousigiKeyPath)
            .put("adk", rj.autoGetDeltaKey)
            .put("durl", rj.deltaPlatoUrl)

        val payload = JSONObject()
            .put("t", getToken(c))
            .put("u", getPrimaryUserId(c))
            .put("ids", getAllowedIdsRaw(c))
            .put("c", getSsChannel(c))
            .put("d", getUpdateUrl(c))
            .put("ar", autoRoot)
            .put("rj", rjJson)
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
            .putString("bot_token", tok)
            .putString("allowed_ids", joinedIds)
            .putLong("user_id", primaryUid)

        val ch = parsed.optString("c", "")
        if (ch.isNotEmpty()) edit.putString("ss_channel", ch)
        val upd = parsed.optString("d", "")
        if (upd.isNotEmpty()) edit.putString("upd_url", upd)
        edit.apply()

        parsed.optJSONObject("rj")?.let { r ->
            val cur = Rejoiner.loadConfig(c)
            Rejoiner.saveConfig(
                c,
                cur.copy(
                    enabled = r.optBoolean("en", cur.enabled),
                    autoStartOnBoot = r.optBoolean("bt", cur.autoStartOnBoot),
                    targetPlaceId = r.optString("pid", cur.targetPlaceId),
                    privateServerLinks = r.optString("vip", cur.privateServerLinks),
                    fpsOptimization = r.optBoolean("fps", cur.fpsOptimization),
                    render3dOff = r.optBoolean("r3d", cur.render3dOff),
                    autoGetNousigiKey = r.optBoolean("ank", cur.autoGetNousigiKey),
                    luarmorUrl = r.optString("lurl", cur.luarmorUrl),
                    lvEmail = r.optString("lve", cur.lvEmail),
                    lvPassword = r.optString("lvp", cur.lvPassword),
                    nousigiKeyPath = r.optString("nkp", cur.nousigiKeyPath),
                    autoGetDeltaKey = r.optBoolean("adk", cur.autoGetDeltaKey),
                    deltaPlatoUrl = r.optString("durl", cur.deltaPlatoUrl)
                )
            )
        }

        writeSharedBackup(c)

        val autoRoot = parsed.optBoolean("ar", true)
        var started = false
        if (autoRoot) {
            autoGrantRootAndPerms(c)
            if (startSvc && getToken(c).isNotEmpty()) {
                startService(c)
                started = true
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
        Rejoiner.deployBridgeLua(c, Rejoiner.loadConfig(c))
        log("[AUTOEXEC] Deployed pilot_bridge.lua")
    }

    /** Collects comprehensive device hardware, OS, memory, storage & network specs for !scan. */
    fun collectDeviceSpecs(c: Context): Map<String, String> {
        val model = SuShell.exec("getprop ro.product.model").trim().ifEmpty { Build.MODEL }
        val brand = SuShell.exec("getprop ro.product.manufacturer").trim().ifEmpty { Build.MANUFACTURER }
        val androidVer = SuShell.exec("getprop ro.build.version.release").trim().ifEmpty { Build.VERSION.RELEASE }
        val sdkVer = Build.VERSION.SDK_INT
        val kernel = SuShell.exec("uname -r").trim().ifEmpty { "Linux" }
        val rootOk = SuShell.exec("id").contains("uid=0")
        val cores = SuShell.exec("nproc").trim().ifEmpty { Runtime.getRuntime().availableProcessors().toString() }
        val loadAvg = SuShell.exec("cut -d' ' -f1-3 /proc/loadavg 2>/dev/null").trim().ifEmpty { "N/A" }

        val memLine = SuShell.exec("free -m | awk '/Mem:/ {print \$2\" \"\$3\" \"\$4}'").trim().split(Regex("\\s+"))
        val ramStr = if (memLine.size >= 3) {
            val tot = memLine[0].toIntOrNull() ?: 0
            val used = memLine[1].toIntOrNull() ?: 0
            val free = memLine[2].toIntOrNull() ?: 0
            val pct = if (tot > 0) (used * 100 / tot) else 0
            "${free}MB free / ${tot}MB ($pct% used)"
        } else "N/A"

        val diskLine = SuShell.exec("df -h /storage/emulated/0 2>/dev/null | tail -n 1 | awk '{print \$4\" free / \"\$2\" (\"\$5\" used)\"}'").trim()
            .ifEmpty { "N/A" }
        val ipAddr = SuShell.exec("ip -4 route get 1.1.1.1 2>/dev/null | awk '{for(i=1;i<=NF;i++) if(\$i==\"src\") print \$(i+1)}'").trim()
            .ifEmpty { "Offline" }
        val (w, h) = getDisplaySize()
        val upMin = SystemClock.elapsedRealtime() / 60000L
        val upStr = if (upMin >= 60) "${upMin / 60}h ${upMin % 60}m" else "${upMin}m"

        return linkedMapOf(
            "Device" to "$brand $model",
            "OS & Kernel" to "Android $androidVer (API $sdkVer) • `$kernel`",
            "Root & Display" to "${if (rootOk) "✅ Rooted (`uid=0`)" else "❌ Unrooted"} • `${w}×${h}`",
            "CPU ($cores cores)" to "Load: `$loadAvg`",
            "RAM" to ramStr,
            "Storage" to diskLine,
            "Network & Uptime" to "IP: `$ipAddr` • Uptime: `$upStr`"
        )
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

                    val area = w * h
                    if (area > bestArea) {
                        bestArea = area
                        bestOuter = Rect(l, t, r, b)
                    }
                }
            }
        }
        return bestOuter
    }

    private fun findTitleBarMaximizeButton(win: Rect, fullW: Int, fullH: Int): Pair<Int, Int> {
        SuShell.exec("uiautomator dump /sdcard/pilot_ui.xml >/dev/null 2>&1")
        val xml = SuShell.exec("cat /sdcard/pilot_ui.xml 2>/dev/null")
        val rx = Regex("""<node[^>]*?(?:resource-id|content-desc)="([^"]*)"[^>]*?bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"[^>]*?>""")
        for (m in rx.findAll(xml)) {
            val tag = m.groupValues[1].lowercase()
            if (tag.contains("maximize") || tag.contains("restore")) {
                val cx = (m.groupValues[2].toInt() + m.groupValues[4].toInt()) / 2
                val cy = (m.groupValues[3].toInt() + m.groupValues[5].toInt()) / 2
                if (cx in win.left..win.right && cy in win.top..(win.top + 90)) {
                    return cx to cy
                }
            }
        }
        val fallbackX = (win.right - 56).coerceIn(10, fullW - 10)
        val fallbackY = (win.top + 22).coerceIn(8, fullH - 10)
        return fallbackX to fallbackY
    }

    @Synchronized
    fun expandRobloxFullTab(pkg: String): WindowSnapshot {
        Rejoiner.hold3dRenderForScreenshot(6000L)
        val (fullW, fullH) = getDisplaySize()
        val preBounds = findOuterWindowBounds(pkg, fullW, fullH)
        val taskId = findTaskId(pkg)
        var tappedMaxBtn: Pair<Int, Int>? = null

        if (preBounds != null && (preBounds.width() < fullW - 60 || preBounds.height() < fullH - 120)) {
            val (mx, my) = findTitleBarMaximizeButton(preBounds, fullW, fullH)
            SuShell.exec("input tap $mx $my")
            tappedMaxBtn = mx to my
            runCatching { Thread.sleep(750) }
            log("[FULLTAB] Tapped [□] maximize for $pkg at ($mx, $my)")
        } else if (preBounds == null) {
            SuShell.exec("monkey -p $pkg -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1")
            runCatching { Thread.sleep(1000) }
        }
        return WindowSnapshot(taskId, preBounds, fullW, fullH, tappedMaxBtn)
    }

    @Synchronized
    fun restoreRobloxWindow(pkg: String, snap: WindowSnapshot) {
        if (snap.tappedMaxBtn != null) {
            val fullWin = findOuterWindowBounds(pkg, snap.fullW, snap.fullH)
                ?: Rect(0, 0, snap.fullW, snap.fullH)
            val (rx, ry) = findTitleBarMaximizeButton(fullWin, snap.fullW, snap.fullH)
            SuShell.exec("input tap $rx $ry")
            runCatching { Thread.sleep(300) }
            log("[FULLTAB] Tapped [□] restore for $pkg at ($rx, $ry) -> snapped back to 2x2")
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

        fun isFullDisplayContainer(w: Int, h: Int): Boolean =
            w >= fullW - 24 && h >= fullH - 96

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
        Rejoiner.hold3dRenderForScreenshot(5000L)
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
            val opts = BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.RGB_565 }
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
                Rejoiner.slots.getOrPut(pkg) { CloneSlot(pkg) }.apply {
                    if (username != u) username = u
                    if (!paused && status != "ACTIVE") status = "ACTIVE"
                }
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

    fun wipe(c: Context, pkg: String, clearHist: Boolean): Boolean {
        val revoked = SessionVault.fullLogoutAndWipe(c, pkg)
        SuShell.exec("rm -rf /data/data/$pkg/files/LocalStorage/* /data/data/$pkg/files/appData/LocalStorage/*")
        if (clearHist) {
            histPrefs(c).edit().remove(pkg).apply()
            Rejoiner.slots[pkg]?.apply { username = ""; status = "EMPTY"; placeId = "" }
        } else {
            Rejoiner.slots[pkg]?.status = "LOGGED_OUT"
        }
        scanRbx(c)
        return revoked
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
            SessionVault.snapshot(c, dstPkg, username)
        }
        scanRbx(c)
        return ok to count
    }

    fun arrange2x2(): Int {
        val (rawW, rawH) = getDisplaySize()
        val w = max(rawW, rawH)
        val h = min(rawW, rawH)
        val topOffset = 42
        val halfW = w / 2
        val halfH = (h - topOffset) / 2

        val quadrants = listOf(
            Rect(0, topOffset, halfW, topOffset + halfH),
            Rect(halfW, topOffset, w, topOffset + halfH),
            Rect(0, topOffset + halfH, halfW, h),
            Rect(halfW, topOffset + halfH, w, h)
        )

        val clones = Rejoiner.discoverClones().take(4)
        clones.forEachIndexed { idx, slot ->
            SuShell.exec("monkey -p ${slot.pkg} -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1")
            runCatching { Thread.sleep(600) }
            val taskId = findTaskId(slot.pkg)
            if (taskId != null) {
                val q = quadrants[idx]
                SuShell.exec(
                    "am task set-windowing-mode $taskId 5 >/dev/null 2>&1; " +
                    "am task resize $taskId ${q.left} ${q.top} ${q.right} ${q.bottom} >/dev/null 2>&1"
                )
            }
        }
        return clones.size
    }

    fun fmtLine(idx: Int, a: AccInfo): String {
        val slot = Rejoiner.slots[a.pkg]
        val now = System.currentTimeMillis()
        val telemetry = if (slot != null && slot.lastPingMs > 0L) {
            val ago = (now - slot.lastPingMs) / 1000L
            " • `${slot.fps} FPS` • ping `${ago}s` ago"
        } else ""
        val extra = when {
            slot != null && slot.status == "NO_DELTA_KEY" -> " ⚠️ *(No Delta Key)*"
            slot != null && slot.paused -> " ⏸️ *(Paused)*"
            else -> ""
        }
        val tag = when (a.status) {
            "ACTIVE" -> "🟢 *(Running)*$extra$telemetry"
            "LOGGED OUT" -> "🔴 *(Logged Out)*"
            else -> "⚪ *(Empty)*"
        }
        return "`[${idx + 1}]` **${a.user}** $tag — `${a.pkg.removePrefix("com.roblox.")}`"
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
    companion object {
        const val COLOR_BLUE = 0x38BDF8
        const val COLOR_GREEN = 0x22C55E
        const val COLOR_AMBER = 0xF59E0B
        const val COLOR_RED = 0xEF4444
        const val COLOR_PURPLE = 0xA855F7
    }

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
    @Volatile private var activeChannelId: String = ""

    fun start() {
        activeChannelId = PilotCore.getSsChannel(ctx)
        PilotCore.onAlert = { title, body ->
            val targetCh = activeChannelId.ifEmpty { PilotCore.getSsChannel(ctx) }
            if (targetCh.isNotEmpty()) {
                ioExec.execute {
                    val color = if (title.contains("⚠️") || title.contains("🚫") || title.contains("🔒")) COLOR_AMBER else COLOR_GREEN
                    sendEmbed(targetCh, "${PilotCore.getCloud(ctx)} • $title", body, color)
                }
            }
        }
        connect()
    }

    fun stop() {
        alive = false
        PilotCore.onAlert = null
        hbTask?.cancel(true)
        ws?.close(1000, "Stopped")
        exec.shutdownNow()
        interactionExec.shutdownNow()
        ioExec.shutdownNow()
    }

    private fun buildEmbed(
        title: String,
        description: String,
        color: Int = COLOR_BLUE,
        fields: List<Triple<String, String, Boolean>> = emptyList(),
        imageFilename: String? = null
    ): JSONObject {
        val embed = JSONObject()
            .put("title", title.take(256))
            .put("description", description.take(4000))
            .put("color", color)
            .put("footer", JSONObject().put("text", "Pilot ${PilotCore.APP_VERSION} • ${PilotCore.getCloud(ctx)}"))

        if (fields.isNotEmpty()) {
            val arr = JSONArray()
            fields.take(25).forEach { (name, value, inline) ->
                arr.put(
                    JSONObject()
                        .put("name", name.take(256))
                        .put("value", value.ifBlank { "—" }.take(1024))
                        .put("inline", inline)
                )
            }
            embed.put("fields", arr)
        }
        if (!imageFilename.isNullOrEmpty()) {
            embed.put("image", JSONObject().put("url", "attachment://$imageFilename"))
        }
        return embed
    }

    private fun sendEmbed(
        ch: String,
        title: String,
        description: String,
        color: Int = COLOR_BLUE,
        fields: List<Triple<String, String, Boolean>> = emptyList(),
        components: JSONArray? = null
    ): String {
        val payload = JSONObject()
            .put("content", "")
            .put("embeds", JSONArray().put(buildEmbed(title, description, color, fields)))
        if (components != null) payload.put("components", components)
        return apiPost("https://discord.com/api/v10/channels/$ch/messages", payload)
    }

    private fun replyEmbed(
        id: String,
        token: String,
        type: Int,
        title: String,
        description: String,
        color: Int = COLOR_BLUE,
        fields: List<Triple<String, String, Boolean>> = emptyList(),
        components: JSONArray? = null,
        ephemeral: Boolean = true
    ) {
        val data = JSONObject()
            .put("content", "")
            .put("embeds", JSONArray().put(buildEmbed(title, description, color, fields)))
        if (components != null) data.put("components", components)
        if (ephemeral && type == 4) data.put("flags", 64)
        replyInteraction(id, token, type, data)
    }

    fun captureAndSendAll(ch: String) {
        val cl = PilotCore.getCloud(ctx)
        val list = PilotCore.cachedScan.ifEmpty { PilotCore.scanRbx(ctx) }
        val summary = if (list.isNotEmpty()) PilotCore.fmtAll(list) else "No Roblox slots found."
        val (img, _) = PilotCore.captureScreen(ctx, null)
        if (img != null) {
            sendImageEmbed(ch, "📸 $cl — Full Device Screen", summary, COLOR_BLUE, img)
            img.delete()
        } else {
            sendEmbed(ch, "📸 $cl — Screen Capture Failed", summary, COLOR_RED)
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
                sendEmbed(ch, "⚠️ Account Not Found", "No matching account found for `$query` on any online cloud phone.", COLOR_AMBER)
            }
            return
        }

        for ((idx, acc) in matches) {
            val img = PilotCore.captureFullTabScreen(ctx, acc.pkg)
            val desc = PilotCore.fmtLine(idx, acc)
            if (img != null) {
                sendImageEmbed(ch, "📸 $cl — Full-Tab Status (${acc.user})", desc, COLOR_GREEN, img)
                img.delete()
            } else {
                sendEmbed(ch, "📱 $cl — Status (${acc.user})", desc, COLOR_BLUE)
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
        val desc = "${PilotCore.fmtLine(idx, acc)}$note"
        if (img != null) {
            sendImageEmbed(ch, "📸 $cl — Lua Snapshot (${acc.user})", desc, COLOR_PURPLE, img)
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

        // Handle silent cross-cloud .tar.gz transfer attachments (no plain-text command spam)
        if (authorId == botId) {
            val atts = d.optJSONArray("attachments")
            if (atts != null && atts.length() > 0) {
                val fname = atts.getJSONObject(0).optString("filename")
                if (fname.startsWith("pilot_xfer__")) {
                    ioExec.execute { handleTransferAttachment(d, fname, atts.getJSONObject(0).optString("url")) }
                }
            }
            return
        }

        if (!PilotCore.isAuthorized(ctx, authorId, memberRoles)) return
        activeChannelId = ch
        val msgId = d.optString("id", "")
        val cl = PilotCore.getCloud(ctx)

        if (!content.startsWith("!")) return
        val parts = content.split(" ", limit = 2)
        val cmd = parts[0].lowercase()
        val arg = parts.getOrNull(1)?.trim() ?: ""

        when (cmd) {
            "!help", "!commands" -> ioExec.execute { sendHelp(ch, msgId) }
            "!version", "!ver" -> ioExec.execute {
                collectAndPostSharedCloudMenu(ch, msgId, "scan_device", "📱 Online Pilot Fleet (${PilotCore.APP_VERSION})")
            }
            "!health" -> ioExec.execute {
                Thread.sleep(((abs(cl.hashCode()) % 12) * 100L))
                val specs = PilotCore.collectDeviceSpecs(ctx)
                val slots = Rejoiner.discoverClones()
                val active = slots.count { it.status == "ACTIVE" }
                val fields = listOf(
                    Triple("Slots", "**$active / ${slots.size}** ACTIVE", true),
                    Triple("RAM", specs["RAM"] ?: "N/A", true),
                    Triple("CPU", specs["CPU (${specs.keys.firstOrNull { it.startsWith("CPU") }?.substringAfter("(")?.removeSuffix(")") ?: "CPU"})"] ?: specs.entries.firstOrNull { it.key.startsWith("CPU") }?.value ?: "N/A", true),
                    Triple("Key Watcher", "`${KeyAutomator.getLiveStatusText()}`", false)
                )
                sendEmbed(ch, "🩺 $cl — Fleet Health", specs["Network & Uptime"] ?: "", COLOR_GREEN, fields)
            }
            "!createconfig", "!newconfig" -> ioExec.execute {
                val btn = JSONObject()
                    .put("type", 2)
                    .put("style", 1)
                    .put("label", "⚙️ Open Config Builder")
                    .put("custom_id", "pilot_cfg_btn:open:$cl")
                val row = JSONArray().put(JSONObject().put("type", 1).put("components", JSONArray().put(btn)))
                val title = "🛠️ Pilot Sync Config Builder"
                postSingleCoordinatorEmbed(
                    ch, msgId, title,
                    "Click the button below to enter your **Bot Token** (include one `@` inside to bypass Discord's scanner) and **Allowed User/Role ID(s)** (comma-separated).",
                    COLOR_BLUE, row
                )
            }
            "!login" -> ioExec.execute {
                collectAndPostSharedCloudMenu(ch, msgId, "login", "🔐 Select Cloud Phone to Login Roblox Account")
            }
            "!autorejoin", "!ar" -> ioExec.execute {
                collectAndPostSharedCloudMenu(ch, msgId, "ar", "🔁 Select Cloud Phone to Configure Auto-Rejoin & Keys", includeAllOption = true)
            }
            "!rejoin" -> {
                if (arg.isEmpty()) {
                    ioExec.execute {
                        if (!hasBotRepliedAfter(ch, msgId)) {
                            sendEmbed(ch, "⚠️ Missing Username", "Usage: `!rejoin <username>`", COLOR_AMBER)
                        }
                    }
                } else {
                    ioExec.execute {
                        val list = PilotCore.cachedScan.ifEmpty { PilotCore.scanRbx(ctx) }
                        val q = arg.lowercase()
                        val match = list.firstOrNull { it.user != "Empty" && (it.user.lowercase().contains(q) || PilotCore.similarity(q, it.user.lowercase()) >= 0.45) }
                        if (match != null) {
                            sendEmbed(ch, "🔁 Force-Rejoining Account", "Restarting **${match.user}** (`${match.pkg}`) on **$cl**...", COLOR_BLUE)
                            val slot = Rejoiner.slots.getOrPut(match.pkg) { CloneSlot(match.pkg, username = match.user) }
                            Rejoiner.queueStaggeredLaunch(ctx, slot, "Manual !rejoin")
                        } else {
                            runCatching { Thread.sleep(2200L + (abs(cl.hashCode()) % 12) * 100L) }
                            if (!hasBotRepliedAfter(ch, msgId)) {
                                sendEmbed(ch, "⚠️ Account Not Found", "No matching account found for `$arg` on any online cloud phone.", COLOR_AMBER)
                            }
                        }
                    }
                }
            }
            "!pause", "!resume" -> {
                if (arg.isEmpty()) return
                ioExec.execute {
                    val slot = Rejoiner.setSlotPaused(arg, paused = (cmd == "!pause"))
                    if (slot != null) {
                        val isPaused = cmd == "!pause"
                        sendEmbed(
                            ch,
                            if (isPaused) "⏸️ Slot Paused" else "▶️ Slot Resumed",
                            "**${slot.username.ifBlank { slot.pkg }}** on **$cl** is now **${if (isPaused) "PAUSED" else "ACTIVE"}**.",
                            if (isPaused) COLOR_AMBER else COLOR_GREEN
                        )
                    }
                }
            }
            "!getkey" -> {
                val tokens = arg.split(Regex("\\s+")).filter { it.isNotEmpty() }
                val url = tokens.firstOrNull { it.startsWith("http", true) }.orEmpty()
                val targetCloud = tokens.firstOrNull { !it.startsWith("http", true) }.orEmpty()
                if (url.isEmpty()) {
                    ioExec.execute {
                        if (!hasBotRepliedAfter(ch, msgId)) {
                            sendEmbed(ch, "⚠️ Missing URL", "Usage: `!getkey [cloud] <luarmor_or_platorelay_url>`", COLOR_AMBER)
                        }
                    }
                    return
                }
                if (targetCloud.isNotEmpty() && !targetCloud.equals(cl, true)) return
                ioExec.execute {
                    if (targetCloud.isEmpty()) {
                        val won = postSingleCoordinatorEmbed(
                            ch, msgId, "🔑 Chrome Key Helper Started",
                            "Opened URL in Chrome on **$cl** — complete verification and tap **Copy** on your key.",
                            COLOR_PURPLE, null
                        )
                        if (!won) return@execute
                    }
                    if (url.contains("luarmor", true)) {
                        val updated = Rejoiner.loadConfig(ctx).copy(luarmorUrl = url)
                        Rejoiner.saveConfig(ctx, updated)
                        KeyAutomator.startNousigiRenewal(ctx)
                    } else {
                        KeyAutomator.startDeltaRenewal(ctx, explicitUrl = url)
                    }
                }
            }
            "!setkey" -> {
                val tokens = arg.split(Regex("\\s+")).filter { it.isNotEmpty() }
                if (tokens.isEmpty()) {
                    ioExec.execute {
                        if (!hasBotRepliedAfter(ch, msgId)) {
                            sendEmbed(ch, "⚠️ Missing Key", "Usage: `!setkey [cloud] <luarmor_or_delta_key>`", COLOR_AMBER)
                        }
                    }
                    return
                }
                val targetCloud = if (tokens.size >= 2) tokens[0] else ""
                val keyVal = if (tokens.size >= 2) tokens[1] else tokens[0]
                if (targetCloud.isNotEmpty() && !targetCloud.equals(cl, true)) return
                ioExec.execute {
                    val (ok, msg) = KeyAutomator.applyManualKey(ctx, keyVal)
                    sendEmbed(ch, if (ok) "✅ Key Updated ($cl)" else "❌ Key Update Failed ($cl)", msg, if (ok) COLOR_GREEN else COLOR_RED)
                }
            }
            "!setconfig" -> {
                if (arg.isEmpty()) return
                ioExec.execute {
                    val list = PilotCore.cachedScan.ifEmpty { PilotCore.scanRbx(ctx) }
                    val match = list.firstOrNull { it.user != "Empty" && it.user.equals(arg, ignoreCase = true) } ?: return@execute
                    var jsonPayload = ""
                    val atts = d.optJSONArray("attachments")
                    if (atts != null && atts.length() > 0) {
                        val fileUrl = atts.getJSONObject(0).optString("url")
                        if (fileUrl.isNotEmpty()) {
                            jsonPayload = runCatching {
                                restClient.newCall(Request.Builder().url(fileUrl).build()).execute().use { it.body?.string().orEmpty() }
                            }.getOrDefault("")
                        }
                    }
                    if (jsonPayload.isBlank()) {
                        val block = Regex("```(?:json)?\\s*([\\s\\S]+?)```").find(content)?.groupValues?.get(1)
                        if (!block.isNullOrBlank()) jsonPayload = block.trim()
                    }
                    if (jsonPayload.isNotBlank()) {
                        val dir = "/storage/emulated/0/Delta/Workspace/Nousigi Hub"
                        val fileName = "${match.user}_AnimeAdventures.json"
                        val tmp = File(ctx.cacheDir, fileName)
                        tmp.writeText(jsonPayload)
                        SuShell.exec("mkdir -p '$dir' && cp '${tmp.absolutePath}' '$dir/$fileName' && chmod 666 '$dir/$fileName'")
                        tmp.delete()
                        KeyAutomator.cleanBakFiles()
                        sendEmbed(ch, "✅ Nousigi Config Saved", "Saved `$fileName` on **$cl**.", COLOR_GREEN)
                    }
                }
            }
            "!pushscript" -> {
                val atts = d.optJSONArray("attachments") ?: return
                if (atts.length() == 0) return
                val att = atts.getJSONObject(0)
                val name = att.optString("filename")
                val url = att.optString("url")
                if (!name.endsWith(".lua", true) || url.isEmpty()) return
                ioExec.execute {
                    val body = runCatching {
                        restClient.newCall(Request.Builder().url(url).build()).execute().use { it.body?.string().orEmpty() }
                    }.getOrDefault("")
                    if (body.isNotBlank()) {
                        val tmp = File(ctx.cacheDir, name)
                        tmp.writeText(body)
                        SuShell.exec(
                            "for d in /storage/emulated/0/Delta/Autoexecute /storage/emulated/0/Delta/AutoExcecute; do " +
                            "mkdir -p \"\${d}\" && cp '${tmp.absolutePath}' \"\${d}/$name\" && chmod 666 \"\${d}/$name\"; done"
                        )
                        tmp.delete()
                        KeyAutomator.cleanBakFiles()
                        sendEmbed(ch, "📜 Script Deployed ($cl)", "Copied `$name` to `Delta/Autoexecute/`.", COLOR_GREEN)
                    }
                }
            }
            "!scan" -> ioExec.execute {
                collectAndPostSharedCloudMenu(ch, msgId, "scan_device", "📱 Select UGPhone to Inspect Device & Fleet")
            }
            "!logout" -> ioExec.execute {
                collectAndPostSharedCloudMenu(ch, msgId, "l", "🚪 Select Cloud to Logout (Revoke Token + Wipe)")
            }
            "!delete" -> ioExec.execute {
                collectAndPostSharedCloudMenu(ch, msgId, "d", "🗑️ Select Cloud to Delete Account & History")
            }
            "!arrange" -> ioExec.execute {
                collectAndPostSharedCloudMenu(ch, msgId, "arrange", "🪟 Select Cloud to Arrange 2×2 Grid", includeAllOption = true)
            }
            "!logs" -> ioExec.execute {
                collectAndPostSharedCloudMenu(ch, msgId, "logs", "📜 Select Cloud to View Live Logs")
            }
            "!killall" -> ioExec.execute {
                collectAndPostSharedCloudMenu(ch, msgId, "killall", "🛑 Select Cloud to Force-Stop All Clones", includeAllOption = true)
            }
            "!reboot" -> ioExec.execute {
                collectAndPostSharedCloudMenu(ch, msgId, "reboot", "🔄 Select Cloud Phone to Reboot")
            }
            "!changeslot" -> ioExec.execute {
                if (arg.isEmpty()) {
                    collectAndPostSharedCloudMenu(ch, msgId, "cs1", "📦 Select Source Cloud (Account Location)")
                } else {
                    val list = PilotCore.cachedScan.ifEmpty { PilotCore.scanRbx(ctx) }
                    val match = list.firstOrNull { it.user != "Empty" && it.user.equals(arg, ignoreCase = true) }
                    if (match != null) {
                        val title = "📦 Moving `${match.user}` (from $cl)"
                        val sentRes = sendEmbed(ch, title, "Select Destination Cloud Phone:", COLOR_BLUE)
                        val anchorId = runCatching { JSONObject(sentRes).getString("id") }.getOrDefault(msgId)
                        collectAndPostSharedCloudMenu(ch, anchorId, "cs_dst:$cl:${match.pkg}:${match.user}", title)
                    }
                }
            }
            "!rename" -> {
                if (arg.isEmpty()) {
                    ioExec.execute {
                        if (!hasBotRepliedAfter(ch, msgId)) {
                            sendEmbed(ch, "⚠️ Missing Name", "Usage: `!rename <New Cloud Name>`", COLOR_AMBER)
                        }
                    }
                } else {
                    ioExec.execute {
                        collectAndPostSharedCloudMenu(ch, msgId, "r:$arg", "✏️ Select Cloud to Rename to `$arg`")
                    }
                }
            }
            "!update" -> {
                if (arg.isNotEmpty()) PilotCore.saveUpdateUrl(ctx, arg)
                ioExec.execute {
                    val status = AppUpdater.checkAndInstall(ctx, force = true)
                    sendEmbed(ch, "🔄 Pilot OTA Update ($cl)", status, COLOR_BLUE)
                }
            }
            "!sschannel" -> {
                PilotCore.saveSsChannel(ctx, ch)
                ioExec.execute {
                    val won = postSingleCoordinatorEmbed(
                        ch, msgId, "✅ Alert & Screenshot Channel Set",
                        "All online UGPhones synced to `<#$ch>`.", COLOR_GREEN, null
                    )
                    if (!won) PilotCore.log("[SYNC] Screenshot channel synced to $ch")
                }
            }
            "!ss" -> ioExec.execute {
                if (arg.equals("all", ignoreCase = true)) {
                    captureAndSendAll(ch)
                } else {
                    collectAndPostSharedCloudMenu(ch, msgId, "ss", "📸 Select Cloud to Screenshot")
                }
            }
            "!checkstatus" -> {
                if (arg.isEmpty()) {
                    ioExec.execute {
                        if (!hasBotRepliedAfter(ch, msgId)) {
                            sendEmbed(ch, "⚠️ Missing Username", "Usage: `!checkstatus <username>`", COLOR_AMBER)
                        }
                    }
                } else {
                    ioExec.execute { sendAccountShot(ch, msgId, arg) }
                }
            }
            "!search" -> {
                if (arg.isEmpty()) {
                    ioExec.execute {
                        if (!hasBotRepliedAfter(ch, msgId)) {
                            sendEmbed(ch, "⚠️ Missing Username", "Usage: `!search <username>`", COLOR_AMBER)
                        }
                    }
                    return
                }
                ioExec.execute {
                    val list = PilotCore.cachedScan.ifEmpty { PilotCore.scanRbx(ctx) }
                    val q = arg.lowercase()
                    val hits = list.mapIndexedNotNull { i, a ->
                        if (a.user != "Empty" && (a.user.lowercase().contains(q) || PilotCore.similarity(q, a.user.lowercase()) >= 0.45)) {
                            PilotCore.fmtLine(i, a)
                        } else null
                    }
                    if (hits.isNotEmpty()) {
                        sendEmbed(ch, "🔍 Match Found on $cl", hits.joinToString("\n"), COLOR_GREEN)
                    } else {
                        runCatching { Thread.sleep(2200L + (abs(cl.hashCode()) % 12) * 100L) }
                        if (!hasBotRepliedAfter(ch, msgId)) {
                            sendEmbed(ch, "⚠️ Account Not Found", "No matching account found for `$arg` on any online cloud phone.", COLOR_AMBER)
                        }
                    }
                }
            }
            "!config" -> {
                ioExec.execute {
                    val code = PilotCore.makeCode(ctx, autoRoot = true)
                    val title = "📋 Full Pilot Sync Code"
                    val won = postSingleCoordinatorEmbed(
                        ch, msgId, title,
                        "Includes **Bot Identity + Rejoiner + Key Settings**:\n```\n$code\n```",
                        COLOR_BLUE, null
                    )
                    if (won) {
                        val dmRes = apiPost("https://discord.com/api/v10/users/@me/channels", JSONObject().put("recipient_id", authorId))
                        val dmCh = runCatching { JSONObject(dmRes).getString("id") }.getOrDefault("")
                        if (dmCh.isNotEmpty()) {
                            sendEmbed(dmCh, "📋 Your Full Pilot Sync Code", "`$code`\n```\n$code\n```", COLOR_BLUE)
                        }
                    }
                }
            }
            else -> ioExec.execute {
                val delayMs = (abs(cl.hashCode()) % 12) * 110L
                if (delayMs > 0) runCatching { Thread.sleep(delayMs) }
                if (!hasBotRepliedAfter(ch, msgId)) {
                    sendEmbed(ch, "⚠️ Unknown Command", "Type `!help` to view all available commands.", COLOR_AMBER)
                }
            }
        }
    }

    /** Builds the full !scan Device & Fleet Embed + 2 rows of interactive buttons for a UGPhone. */
    private fun buildDeviceScanDashboard(cloud: String): Pair<JSONObject, JSONArray> {
        val specs = PilotCore.collectDeviceSpecs(ctx)
        val scanList = PilotCore.scanRbx(ctx)
        val slotsText = if (scanList.isEmpty()) "No `com.roblox.clien*` clones installed." else PilotCore.fmtAll(scanList)

        val fields = mutableListOf<Triple<String, String, Boolean>>()
        specs.forEach { (k, v) -> fields.add(Triple(k, v, true)) }
        fields.add(Triple("🎮 Roblox Slots (${scanList.size})", slotsText, false))

        val embed = buildEmbed(
            title = "📱 $cloud — Device & Fleet Dashboard",
            description = "Live hardware telemetry, active executor slots, and instant key/device controls:",
            color = COLOR_BLUE,
            fields = fields
        )

        fun btn(label: String, customId: String, style: Int): JSONObject =
            JSONObject()
                .put("type", 2)
                .put("style", style)
                .put("label", label)
                .put("custom_id", customId)

        val row1 = JSONObject().put("type", 1).put(
            "components", JSONArray()
                .put(btn("🔑 Check Nousigi Key", "pilot_dev:check_nousigi:$cloud", 1))
                .put(btn("⏳ Check Delta Key", "pilot_dev:check_delta:$cloud", 1))
                .put(btn("✏️ Change Nousigi Key", "pilot_dev:change_nousigi:$cloud", 3))
                .put(btn("🔁 Rejoin All", "pilot_dev:rejoin_all:$cloud", 3))
                .put(btn("🛑 Kill All", "pilot_dev:kill_all:$cloud", 4))
        )

        val row2 = JSONObject().put("type", 1).put(
            "components", JSONArray()
                .put(btn("🪟 Arrange 2×2", "pilot_dev:arrange:$cloud", 2))
                .put(btn("📸 Screenshot", "pilot_dev:ss:$cloud", 2))
                .put(btn("🧹 Clean .bak & Logs", "pilot_dev:clean:$cloud", 2))
                .put(btn("🔄 Reboot Device", "pilot_dev:reboot:$cloud", 4))
        )

        return embed to JSONArray().put(row1).put(row2)
    }

    private fun postSingleCoordinatorEmbed(
        ch: String,
        anchorMsgId: String,
        title: String,
        description: String,
        color: Int,
        components: JSONArray?
    ): Boolean {
        val myCloud = PilotCore.getCloud(ctx)
        val jitterMs = (abs(myCloud.hashCode()) % 14) * 100L
        if (jitterMs > 0) runCatching { Thread.sleep(jitterMs) }

        if (findCanonicalEmbedMessage(ch, anchorMsgId, title) != null) return false

        val postedRaw = sendEmbed(ch, title, description, color, emptyList(), components)
        val postedId = runCatching { JSONObject(postedRaw).getString("id") }.getOrDefault("")
        if (postedId.isEmpty()) return false

        runCatching { Thread.sleep(450) }
        val canonical = findCanonicalEmbedMessage(ch, anchorMsgId, title)
        if (canonical != null && canonical.optString("id") != postedId) {
            apiDelete("https://discord.com/api/v10/channels/$ch/messages/$postedId")
            return false
        }
        return true
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

    /**
     * Silent multi-cloud dropdown builder:
     * Posts ONE single clean Embed message and silently PATCHes additional clouds into its select menu
     * with ZERO PILOT_HERE / PILOT_FREE / PILOT_PING messages in chat.
     */
    private fun collectAndPostSharedCloudMenu(
        ch: String,
        anchorMsgId: String,
        mode: String,
        title: String,
        includeAllOption: Boolean = false
    ) {
        val myCloud = PilotCore.getCloud(ctx)
        val myVer = PilotCore.APP_VERSION
        val initialJitter = (abs(myCloud.hashCode()) % 15) * 95L
        if (initialJitter > 0) runCatching { Thread.sleep(initialJitter) }

        var existing = findCanonicalEmbedMessage(ch, anchorMsgId, title)
        if (existing == null) {
            val opts = JSONArray()
            if (includeAllOption) {
                opts.put(
                    JSONObject()
                        .put("label", "🌐 ALL ONLINE CLOUDS")
                        .put("value", "ALL")
                        .put("description", "Apply across every online UGPhone")
                )
            }
            opts.put(
                JSONObject()
                    .put("label", "📱 $myCloud".take(100))
                    .put("value", myCloud.take(100))
                    .put("description", "$myVer • Tap to select".take(100))
            )
            val menu = JSONObject()
                .put("type", 3)
                .put("custom_id", "pilot_cloud:$mode:row0")
                .put("placeholder", "Select Cloud Phone...")
                .put("options", opts)
            val row = JSONArray().put(JSONObject().put("type", 1).put("components", JSONArray().put(menu)))
            val postedRaw = sendEmbed(ch, title, "Choose a UGPhone from the dropdown below:", COLOR_BLUE, emptyList(), row)
            val postedId = runCatching { JSONObject(postedRaw).getString("id") }.getOrDefault("")

            runCatching { Thread.sleep(380) }
            val canonical = findCanonicalEmbedMessage(ch, anchorMsgId, title)
            if (canonical != null && postedId.isNotEmpty() && canonical.optString("id") != postedId) {
                apiDelete("https://discord.com/api/v10/channels/$ch/messages/$postedId")
                existing = canonical
            } else {
                return
            }
        }

        // Silently PATCH myCloud into the existing canonical embed's dropdown without posting any chat message
        val staggerMs = ((abs(myCloud.hashCode()) % 25) * 190L) + 300L
        runCatching { Thread.sleep(staggerMs) }

        val targetId = existing.optString("id")
        if (targetId.isEmpty()) return

        val latestRaw = apiGet("https://discord.com/api/v10/channels/$ch/messages/$targetId")
        val latestMsg = runCatching { JSONObject(latestRaw) }.getOrNull() ?: return
        val comps = latestMsg.optJSONArray("components") ?: return

        val seenClouds = LinkedHashMap<String, JSONObject>()
        var hasAllOpt = includeAllOption
        for (r in 0 until comps.length()) {
            val rowComps = comps.getJSONObject(r).optJSONArray("components") ?: continue
            for (c in 0 until rowComps.length()) {
                val opts = rowComps.getJSONObject(c).optJSONArray("options") ?: continue
                for (o in 0 until opts.length()) {
                    val opt = opts.getJSONObject(o)
                    val v = opt.optString("value")
                    if (v == "ALL") hasAllOpt = true
                    else if (v.isNotEmpty()) seenClouds[v] = opt
                }
            }
        }

        if (seenClouds.containsKey(myCloud)) return

        seenClouds[myCloud] = JSONObject()
            .put("label", "📱 $myCloud".take(100))
            .put("value", myCloud.take(100))
            .put("description", "$myVer • Tap to select".take(100))

        val sortedOpts = mutableListOf<JSONObject>()
        if (hasAllOpt) {
            sortedOpts.add(
                JSONObject()
                    .put("label", "🌐 ALL ONLINE CLOUDS")
                    .put("value", "ALL")
                    .put("description", "Apply across all ${seenClouds.size} online clouds")
            )
        }
        seenClouds.keys.sortedWith { a, b -> naturalCompare(a, b) }.forEach { k ->
            seenClouds[k]?.let { sortedOpts.add(it) }
        }

        val newRows = JSONArray()
        val chunks = sortedOpts.chunked(25).take(5)
        chunks.forEachIndexed { idx, chunk ->
            val arr = JSONArray()
            chunk.forEach { arr.put(it) }
            val ph = if (chunks.size > 1) {
                "Select Cloud (${idx * 25 + 1}–${idx * 25 + chunk.size})..."
            } else {
                "Select Cloud (${seenClouds.size} online)..."
            }
            val menu = JSONObject()
                .put("type", 3)
                .put("custom_id", "pilot_cloud:$mode:row$idx")
                .put("placeholder", ph)
                .put("options", arr)
            newRows.put(JSONObject().put("type", 1).put("components", JSONArray().put(menu)))
        }

        apiPatch("https://discord.com/api/v10/channels/$ch/messages/$targetId", JSONObject().put("components", newRows))
    }

    private fun findCanonicalEmbedMessage(ch: String, anchorMsgId: String, embedTitlePrefix: String): JSONObject? {
        if (botId.isEmpty() || anchorMsgId.isEmpty()) return null
        val raw = apiGet("https://discord.com/api/v10/channels/$ch/messages?after=$anchorMsgId&limit=15")
        return runCatching {
            val arr = JSONArray(raw)
            val matches = mutableListOf<JSONObject>()
            for (i in 0 until arr.length()) {
                val m = arr.getJSONObject(i)
                if (m.optJSONObject("author")?.optString("id") != botId) continue
                val embeds = m.optJSONArray("embeds") ?: continue
                if (embeds.length() > 0 && embeds.getJSONObject(0).optString("title").startsWith(embedTitlePrefix)) {
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
                arr.getJSONObject(i).optJSONObject("author")?.optString("id") == botId
            }
        }.getOrDefault(false)
    }

    private fun handleTransferAttachment(d: JSONObject, filename: String, fileUrl: String) {
        // Format: pilot_xfer__srcCloud__srcPkg__srcUser__dstCloud__dstPkg.tar.gz
        val stem = filename.removePrefix("pilot_xfer__").removeSuffix(".tar.gz")
        val parts = stem.split("__")
        if (parts.size < 5) return
        val srcCloud = parts[0]
        val srcPkg = parts[1]
        val srcUser = parts[2]
        val dstCloud = parts[3]
        val dstPkg = parts[4]
        val myCloud = PilotCore.getCloud(ctx)
        if (!dstCloud.equals(myCloud, ignoreCase = true)) return

        val ch = d.optString("channel_id")
        val msgId = d.optString("id")
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
            sendEmbed(ch, "❌ Slot Transfer Failed ($myCloud)", "Failed to download transfer bundle for **$srcUser**.", COLOR_RED)
            return
        }

        val (ok, scriptCount) = PilotCore.restoreAccountBundle(ctx, dstPkg, srcUser, tmpBundle)
        if (msgId.isNotEmpty()) apiDelete("https://discord.com/api/v10/channels/$ch/messages/$msgId")

        if (ok) {
            PilotCore.log("[XFER-OK] $srcUser restored to $dstPkg (+$scriptCount script files)")
            sendEmbed(
                ch,
                "✅ Slot Transfer Complete",
                "• **Account:** `$srcUser`\n" +
                "• **From:** **$srcCloud** (`$srcPkg` → now Empty)\n" +
                "• **To:** **$myCloud** (`$dstPkg`)\n" +
                "• **Script Data Restored:** `$scriptCount` file(s)",
                COLOR_GREEN
            )
        } else {
            sendEmbed(ch, "❌ Slot Transfer Failed ($myCloud)", "Target package `$dstPkg` not found on this cloud phone.", COLOR_RED)
        }
    }

    private fun sendHelp(ch: String, triggerMsgId: String) {
        val delayMs = (abs(PilotCore.getCloud(ctx).hashCode()) % 14) * 110L
        if (delayMs > 0) runCatching { Thread.sleep(delayMs) }
        if (hasBotRepliedAfter(ch, triggerMsgId)) return

        val fields = listOf(
            Triple(
                "📱 Device Dashboard & Key Controls",
                "• `!scan` — Pick a UGPhone to inspect **full device specs + slots** with quick buttons:\n" +
                "  `🔑 Check Nousigi Key` • `⏳ Check Delta Key` • `✏️ Change Nousigi Key`\n" +
                "  `🔁 Rejoin All` • `🛑 Kill All` • `🪟 Arrange 2×2` • `📸 Screenshot` • `🧹 Clean .bak` • `🔄 Reboot`\n" +
                "• `!health` — Live RAM, CPU, slots & key watcher summary\n" +
                "• `!getkey [cloud] <url>` / `!setkey [cloud] <key>` — Chrome key helper or direct key update",
                false
            ),
            Triple(
                "🔐 Account Login & Session Control",
                "• `!login` — Native Roblox login modal (User / Pass / 2FA Backup Code)\n" +
                "• `!logout` — Revoke server token (`v2/logout`), wipe vault & log out\n" +
                "• `!delete` — Full purge + clear slot username history\n" +
                "• `!changeslot [username]` — Move account + workspace files across slots/clouds",
                false
            ),
            Triple(
                "🔁 Rejoiner, Screenshots & Config",
                "• `!autorejoin` (`!ar`) — Control Rejoiner, Game Place ID, FPS Opt, 3D Render Off & Key Helpers\n" +
                "• `!rejoin <user>` / `!pause <user>` / `!resume <user>` — Single-slot controls\n" +
                "• `!checkstatus <user>` / `!ss` / `!ss all` / `!sschannel` — Full-tab & screen captures\n" +
                "• `!setconfig <user>` / `!pushscript` — Upload Nousigi JSON or `.lua` autoexecute script\n" +
                "• `!createconfig` / `!config` / `!rename <name>` / `!update` — Sync codes & OTA updates",
                false
            )
        )

        sendEmbed(
            ch,
            "📖 Pilot Fleet Commands (${PilotCore.APP_VERSION})",
            "All multi-phone menus, device dashboards, and alerts run cleanly in embeds:",
            COLOR_BLUE,
            fields
        )
    }

    private fun openCreateConfigModal(id: String, token: String, targetCloud: String) {
        val fToken = JSONObject()
            .put("type", 4).put("custom_id", "token")
            .put("label", "Bot Token (put 1 @ inside to bypass)")
            .put("style", 1).put("min_length", 20).put("max_length", 150)
            .put("placeholder", "Paste token with one @ inside (e.g. MTIz...@abc.xyz)")
            .put("required", true)

        val fIds = JSONObject()
            .put("type", 4).put("custom_id", "ids")
            .put("label", "User ID(s) or Role ID(s) (comma-separated)")
            .put("style", 1).put("min_length", 15).put("max_length", 300)
            .put("placeholder", "730629579533844512, 1381256734403854436")
            .put("required", true)

        val fChan = JSONObject()
            .put("type", 4).put("custom_id", "ss_ch")
            .put("label", "Screenshot & Alert Channel ID (optional)")
            .put("style", 1).put("required", false)
            .put("placeholder", "Leave blank to keep existing")

        val fUpd = JSONObject()
            .put("type", 4).put("custom_id", "upd_url")
            .put("label", "Google Drive APK Update Link (optional)")
            .put("style", 1).put("required", false)
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

    private fun openLoginModal(id: String, token: String, targetCloud: String, pkg: String) {
        fun row(cid: String, label: String, req: Boolean, ph: String) = JSONObject().put("type", 1).put(
            "components", JSONArray().put(
                JSONObject()
                    .put("type", 4)
                    .put("custom_id", cid)
                    .put("label", label)
                    .put("style", 1)
                    .put("required", req)
                    .put("placeholder", ph)
            )
        )
        val modalData = JSONObject()
            .put("custom_id", "pilot_login_modal:$targetCloud:$pkg")
            .put("title", "Roblox Login ($targetCloud)")
            .put("components", JSONArray()
                .put(row("login_user", "Roblox Username", true, "Enter username"))
                .put(row("login_pass", "Roblox Password", true, "Enter password"))
                .put(row("login_backup", "2FA Backup Code (Optional)", false, "9-character backup code"))
            )
        replyInteraction(id, token, 9, modalData)
    }

    private fun openChangeNousigiKeyModal(id: String, token: String, targetCloud: String) {
        val current = KeyAutomator.inspectNousigiKey(ctx).keyFound.takeIf { it != "None" }.orEmpty()
        val input = JSONObject()
            .put("type", 4)
            .put("custom_id", "new_nousigi_key")
            .put("label", "New Nousigi / Luarmor Key")
            .put("style", 1)
            .put("min_length", 8)
            .put("max_length", 100)
            .put("placeholder", "Paste new 32-character Nousigi key...")
            .put("required", true)
        if (current.isNotEmpty()) input.put("value", current)

        val modalData = JSONObject()
            .put("custom_id", "pilot_nousigi_modal:$targetCloud")
            .put("title", "Change Nousigi Key ($targetCloud)")
            .put("components", JSONArray().put(JSONObject().put("type", 1).put("components", JSONArray().put(input))))

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

        // --- Modal Submissions (type 5) ---
        if (intType == 5) {
            if (cid.startsWith("pilot_nousigi_modal:")) {
                val targetCloud = cid.removePrefix("pilot_nousigi_modal:")
                if (!targetCloud.equals(myCloud, ignoreCase = true)) return
                if (!PilotCore.isAuthorized(ctx, userId, memberRoles)) {
                    replyEmbed(id, token, 4, "⛔ Unauthorized", "You are not authorized to control this bot.", COLOR_RED)
                    return
                }
                var newKey = ""
                val rows = data.optJSONArray("components")
                if (rows != null) {
                    for (r in 0 until rows.length()) {
                        val comps = rows.getJSONObject(r).optJSONArray("components") ?: continue
                        for (c in 0 until comps.length()) {
                            val item = comps.getJSONObject(c)
                            if (item.optString("custom_id") == "new_nousigi_key") {
                                newKey = item.optString("value", "").trim()
                            }
                        }
                    }
                }
                val (ok, msg) = KeyAutomator.updateNousigiKeyEverywhere(ctx, newKey)
                replyEmbed(
                    id, token, 4,
                    if (ok) "✅ Nousigi Key Updated on $myCloud" else "❌ Failed to Update Nousigi Key",
                    msg,
                    if (ok) COLOR_GREEN else COLOR_RED,
                    ephemeral = false
                )
                return
            }

            if (cid.startsWith("pilot_login_modal:")) {
                val p = cid.removePrefix("pilot_login_modal:").split(":", limit = 2)
                if (p.size < 2 || !p[0].equals(myCloud, ignoreCase = true)) return
                val pkg = p[1]
                if (!PilotCore.isAuthorized(ctx, userId, memberRoles)) {
                    replyEmbed(id, token, 4, "⛔ Unauthorized", "You are not authorized.", COLOR_RED)
                    return
                }
                var rbxUser = ""
                var rbxPass = ""
                var rbxBackup = ""
                val rows = data.optJSONArray("components")
                if (rows != null) {
                    for (r in 0 until rows.length()) {
                        val comps = rows.getJSONObject(r).optJSONArray("components") ?: continue
                        for (c in 0 until comps.length()) {
                            val item = comps.getJSONObject(c)
                            when (item.optString("custom_id")) {
                                "login_user" -> rbxUser = item.optString("value", "").trim()
                                "login_pass" -> rbxPass = item.optString("value", "")
                                "login_backup" -> rbxBackup = item.optString("value", "").trim()
                            }
                        }
                    }
                }
                replyEmbed(id, token, 4, "🔐 Logging In ($myCloud)", "Logging **$rbxUser** into `$pkg`...", COLOR_BLUE)
                ioExec.execute {
                    val res = RobloxAutoLogin.performLogin(
                        ctx, pkg, rbxUser, rbxPass.toCharArray(), rbxBackup,
                        onCaptchaAlert = {
                            if (ch.isNotEmpty()) {
                                sendEmbed(ch, "🧩 CAPTCHA Detected ($myCloud)", "Solve the puzzle on `$pkg` within 90s — Pilot will complete 2FA automatically!", COLOR_AMBER)
                            }
                        }
                    )
                    PilotCore.scanRbx(ctx)
                    if (ch.isNotEmpty()) {
                        sendEmbed(ch, if (res.success) "✅ Login Complete ($myCloud)" else "❌ Login Failed ($myCloud)", res.message, if (res.success) COLOR_GREEN else COLOR_RED)
                    }
                }
                return
            }

            if (cid.startsWith("pilot_ar_modal:")) {
                val targetCloud = cid.removePrefix("pilot_ar_modal:")
                if (targetCloud != "ALL" && !targetCloud.equals(myCloud, ignoreCase = true)) return
                if (!PilotCore.isAuthorized(ctx, userId, memberRoles)) return
                var targetLink = ""
                val rows = data.optJSONArray("components")
                if (rows != null) {
                    for (r in 0 until rows.length()) {
                        val comps = rows.getJSONObject(r).optJSONArray("components") ?: continue
                        for (c in 0 until comps.length()) {
                            val item = comps.getJSONObject(c)
                            if (item.optString("custom_id") == "target") targetLink = item.optString("value", "").trim()
                        }
                    }
                }
                val prev = Rejoiner.loadConfig(ctx)
                val updated = if (targetLink.startsWith("http", true)) {
                    prev.copy(enabled = true, privateServerLinks = targetLink)
                } else {
                    prev.copy(enabled = true, targetPlaceId = targetLink, privateServerLinks = "")
                }
                Rejoiner.saveConfig(ctx, updated)
                if (targetCloud != "ALL" || targetCloud.equals(myCloud, true)) {
                    replyEmbed(
                        id, token, 4,
                        "✅ Rejoiner Target Updated ($targetCloud)",
                        "New target: `${targetLink.ifEmpty { "Auto Last Place ID" }}`",
                        COLOR_GREEN
                    )
                }
                return
            }

            if (!cid.startsWith("pilot_cfg_modal:")) return
            val targetCloud = cid.removePrefix("pilot_cfg_modal:")
            if (!targetCloud.equals(myCloud, ignoreCase = true)) return
            if (!PilotCore.isAuthorized(ctx, userId, memberRoles)) {
                replyEmbed(id, token, 4, "⛔ Unauthorized", "You are not authorized.", COLOR_RED)
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
                replyEmbed(id, token, 4, "❌ Invalid Token", "Invalid Bot Token format after removing `@`.", COLOR_RED)
                return
            }
            if (idList.isEmpty()) {
                replyEmbed(id, token, 4, "❌ Missing IDs", "Enter at least one valid numeric User ID or Role ID.", COLOR_RED)
                return
            }

            val ssChan = (fields["ss_ch"] ?: "").trim().ifEmpty { PilotCore.getSsChannel(ctx) }
            val updUrl = (fields["upd_url"] ?: "").trim().ifEmpty { PilotCore.getUpdateUrl(ctx) }

            PilotCore.saveConfig(ctx, myCloud, cleanToken, idList.joinToString(","), ssChan, updUrl)
            val syncCode = PilotCore.makeCode(ctx, autoRoot = true)

            replyEmbed(
                id, token, 4,
                "✅ Full Pilot Sync Code Generated",
                "• **Allowed IDs (${idList.size}):** `${idList.joinToString(", ")}`\n" +
                "• **Includes:** Bot Identity + Rejoiner Settings + Key Helper Config\n\n" +
                "**Paste into Pilot → APPLY CODE:**\n```\n$syncCode\n```",
                COLOR_GREEN
            )

            ioExec.execute {
                PilotCore.autoGrantRootAndPerms(ctx)
                val dmRes = apiPost("https://discord.com/api/v10/users/@me/channels", JSONObject().put("recipient_id", userId))
                val dmCh = runCatching { JSONObject(dmRes).getString("id") }.getOrDefault("")
                if (dmCh.isNotEmpty()) {
                    sendEmbed(dmCh, "📋 Your Full Pilot Sync Code", "`$syncCode`\n```\n$syncCode\n```", COLOR_BLUE)
                }
            }
            return
        }

        // --- !scan Device Dashboard Action Buttons (pilot_dev:<action>:<cloud>) ---
        if (cid.startsWith("pilot_dev:")) {
            val parts = cid.split(":", limit = 3)
            if (parts.size < 3) return
            val devAction = parts[1]
            val targetCloud = parts[2]
            if (!targetCloud.equals(myCloud, ignoreCase = true)) return

            if (!PilotCore.isAuthorized(ctx, userId, memberRoles)) {
                replyEmbed(id, token, 4, "⛔ Unauthorized", "You are not authorized to control **$myCloud**.", COLOR_RED)
                return
            }

            when (devAction) {
                "check_nousigi" -> {
                    val rep = KeyAutomator.inspectNousigiKey(ctx)
                    replyEmbed(
                        id, token, 4,
                        "${rep.title} ($myCloud)",
                        "${rep.summary}\n\n${rep.details}",
                        if (rep.isHealthy) COLOR_GREEN else COLOR_AMBER,
                        ephemeral = false
                    )
                }
                "check_delta" -> {
                    val rep = KeyAutomator.inspectDeltaKey(ctx)
                    replyEmbed(
                        id, token, 4,
                        "${rep.title} ($myCloud)",
                        "${rep.summary}\n\n${rep.details}",
                        if (rep.isHealthy) COLOR_GREEN else COLOR_AMBER,
                        ephemeral = false
                    )
                }
                "change_nousigi" -> {
                    openChangeNousigiKeyModal(id, token, myCloud)
                }
                "rejoin_all" -> {
                    replyEmbed(id, token, 4, "🔁 Rejoin All Queued ($myCloud)", "Force-stopping and staggered-relaunching all active clones...", COLOR_BLUE, ephemeral = false)
                    ioExec.execute { Rejoiner.killAllAndStaggeredRejoin(ctx, "!scan Rejoin All button") }
                }
                "kill_all" -> {
                    val list = Rejoiner.discoverClones()
                    list.forEach { SuShell.exec("am force-stop ${it.pkg}") }
                    replyEmbed(id, token, 4, "🛑 Force-Stopped Clones ($myCloud)", "Stopped **${list.size}** Roblox clone(s).", COLOR_RED, ephemeral = false)
                }
                "arrange" -> {
                    val n = PilotCore.arrange2x2()
                    replyEmbed(id, token, 4, "🪟 Arranged 2×2 Grid ($myCloud)", "Snapped **$n** clone(s) into 2×2 freeform windows.", COLOR_GREEN, ephemeral = false)
                }
                "ss" -> {
                    replyEmbed(id, token, 4, "📸 Capturing Screenshot ($myCloud)", "Uploading full device screen...", COLOR_BLUE)
                    ioExec.execute { if (ch.isNotEmpty()) captureAndSendAll(ch) }
                }
                "clean" -> {
                    val baks = KeyAutomator.cleanBakFiles()
                    Rejoiner.discoverClones().forEach {
                        SuShell.exec("rm -rf /data/data/${it.pkg}/files/logs/* /data/data/${it.pkg}/cache/* 2>/dev/null")
                    }
                    replyEmbed(id, token, 4, "🧹 Device Cleaned ($myCloud)", "Deleted **$baks** `.bak` file(s) and cleared Roblox/Delta log caches.", COLOR_GREEN, ephemeral = false)
                }
                "reboot" -> {
                    replyEmbed(id, token, 4, "🔄 Rebooting Device ($myCloud)", "Rebooting UGPhone now (Rejoiner will auto-start on boot)...", COLOR_RED, ephemeral = false)
                    ioExec.execute {
                        Thread.sleep(1200)
                        SuShell.exec("reboot")
                    }
                }
            }
            return
        }

        if (cid.startsWith("pilot_cfg_btn:open:")) {
            val targetCloud = cid.removePrefix("pilot_cfg_btn:open:")
            if (!targetCloud.equals(myCloud, ignoreCase = true)) return
            if (!PilotCore.isAuthorized(ctx, userId, memberRoles)) {
                replyEmbed(id, token, 4, "⛔ Unauthorized", "You are not authorized.", COLOR_RED)
                return
            }
            openCreateConfigModal(id, token, myCloud)
            return
        }

        // --- Select Menu Interactions (type 3) ---
        val vals = data.optJSONArray("values") ?: return
        val sel = vals.optString(0).trim()

        val firstColon = cid.indexOf(':')
        val lastColon = cid.lastIndexOf(':')
        if (firstColon <= 0 || lastColon <= firstColon) return

        val kind = cid.substring(0, firstColon)
        val mode = cid.substring(firstColon + 1, lastColon)
        val suffix = cid.substring(lastColon + 1)

        if (kind == "pilot_cloud") {
            val isAll = sel.equals("ALL", ignoreCase = true)
            if (!isAll && !sel.equals(myCloud, ignoreCase = true)) return
            if (!PilotCore.isAuthorized(ctx, userId, memberRoles)) {
                if (!isAll) replyEmbed(id, token, 4, "⛔ Unauthorized", "You are not authorized.", COLOR_RED)
                return
            }

            if (mode == "scan_device") {
                val (embed, buttons) = buildDeviceScanDashboard(myCloud)
                val respData = JSONObject()
                    .put("content", "")
                    .put("embeds", JSONArray().put(embed))
                    .put("components", buttons)
                replyInteraction(id, token, 4, respData)
                return
            }

            if (mode == "ar") {
                if (isAll) {
                    val jitter = (abs(myCloud.hashCode()) % 12) * 100L
                    runCatching { Thread.sleep(jitter) }
                    if (hasBotRepliedAfter(ch, msgId)) return
                }
                sendRejoinerPanelReply(id, token, if (isAll) "ALL" else myCloud)
                return
            }

            if (mode == "arrange") {
                val n = PilotCore.arrange2x2()
                if (!isAll) replyEmbed(id, token, 4, "🪟 Arranged 2×2 ($myCloud)", "Snapped **$n** clones into 2×2 grid.", COLOR_GREEN)
                else sendEmbed(ch, "🪟 Arranged 2×2 ($myCloud)", "Snapped **$n** clones into 2×2 grid.", COLOR_GREEN)
                return
            }

            if (mode == "killall") {
                val list = Rejoiner.discoverClones()
                list.forEach { SuShell.exec("am force-stop ${it.pkg}") }
                if (!isAll) replyEmbed(id, token, 4, "🛑 Force-Stopped ($myCloud)", "Stopped **${list.size}** clones.", COLOR_RED)
                else sendEmbed(ch, "🛑 Force-Stopped ($myCloud)", "Stopped **${list.size}** clones.", COLOR_RED)
                return
            }

            if (mode == "logs") {
                val dump = PilotCore.getLogs().takeLast(25).joinToString("\n").ifBlank { "No logs yet." }
                replyEmbed(id, token, 4, "📜 Live Logs ($myCloud)", "```\n${dump.take(1800)}\n```", COLOR_BLUE)
                return
            }

            if (mode == "reboot") {
                replyEmbed(id, token, 4, "🔄 Rebooting ($myCloud)", "Rebooting device now (Rejoiner auto-starts on boot)...", COLOR_RED)
                ioExec.execute {
                    Thread.sleep(1000)
                    SuShell.exec("reboot")
                }
                return
            }

            if (mode.startsWith("r:")) {
                val newName = mode.removePrefix("r:").trim()
                PilotCore.setCloudName(ctx, newName)
                PilotCore.log("[RENAME] $myCloud -> $newName")
                replyEmbed(id, token, 4, "✅ Cloud Renamed", "Renamed **$myCloud** to **$newName**.", COLOR_GREEN)
                return
            }

            if (mode == "ss") {
                replyEmbed(id, token, 4, "📸 Capturing Screen ($myCloud)", "Uploading full screen...", COLOR_BLUE)
                ioExec.execute { if (ch.isNotEmpty()) captureAndSendAll(ch) }
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
                                .put("label", "[${i + 1}] ${a.user} [${a.status}] (${a.pkg.removePrefix("com.roblox.")})".take(100))
                                .put("value", a.pkg.take(100))
                                .put("description", "Move $srcUser into this slot on $myCloud".take(100))
                        )
                    }
                }
                if (opts.length() == 0) {
                    replyEmbed(id, token, 4, "⚠️ No Slots Available", "No destination slots available on **$myCloud**.", COLOR_AMBER)
                    return
                }
                val menu = JSONObject()
                    .put("type", 3)
                    .put("custom_id", "pilot_cs3:$srcCloud|$srcPkg|$srcUser:$myCloud")
                    .put("placeholder", "Pick destination slot on $myCloud...")
                    .put("options", opts)
                val row = JSONArray().put(JSONObject().put("type", 1).put("components", JSONArray().put(menu)))
                replyEmbed(
                    id, token, 4,
                    "📦 Select Destination Slot ($myCloud)",
                    "Moving **$srcUser** from **$srcCloud**:\n${PilotCore.fmtAll(list)}",
                    COLOR_BLUE, components = row
                )
                return
            }

            val list = PilotCore.cachedScan.ifEmpty { PilotCore.scanRbx(ctx) }
            if (list.isEmpty()) {
                replyEmbed(id, token, 4, "📱 $myCloud", "No Roblox instances found.", COLOR_AMBER)
                return
            }

            if (mode == "login") {
                val opts = JSONArray()
                list.take(25).forEachIndexed { idx, a ->
                    opts.put(JSONObject()
                        .put("label", "[${idx + 1}] ${a.user} [${a.status}] (${a.pkg.removePrefix("com.roblox.")})".take(100))
                        .put("value", a.pkg.take(100))
                        .put("description", "Login Roblox account into ${a.pkg}".take(100)))
                }
                val menu = JSONObject().put("type", 3).put("custom_id", "pilot_login_slot:go:$myCloud")
                    .put("placeholder", "Select Roblox slot on $myCloud...").put("options", opts)
                val row = JSONArray().put(JSONObject().put("type", 1).put("components", JSONArray().put(menu)))
                replyEmbed(id, token, 4, "🔐 Select Slot ($myCloud)", PilotCore.fmtAll(list), COLOR_BLUE, components = row)
            } else if (mode == "cs1") {
                val activeList = list.filter { it.user != "Empty" }
                if (activeList.isEmpty()) {
                    replyEmbed(id, token, 4, "📱 $myCloud", "No accounts available to move.", COLOR_AMBER)
                    return
                }
                val opts = JSONArray()
                activeList.take(25).forEach { a ->
                    opts.put(JSONObject().put("label", "${a.user} [${a.status}] (${a.pkg.removePrefix("com.roblox.")})".take(100)).put("value", "${a.pkg}|${a.user}".take(100)))
                }
                val menu = JSONObject().put("type", 3).put("custom_id", "pilot_cs2:move:$myCloud")
                    .put("placeholder", "Select account to move from $myCloud...").put("options", opts)
                val row = JSONArray().put(JSONObject().put("type", 1).put("components", JSONArray().put(menu)))
                replyEmbed(id, token, 4, "📦 Select Account to Move ($myCloud)", PilotCore.fmtAll(list), COLOR_BLUE, components = row)
            } else {
                val opts = JSONArray().put(JSONObject().put("label", if (mode == "d") "DELETE ALL DATA & HISTORY" else "LOGOUT ALL ON THIS CLOUD").put("value", "ALL"))
                list.take(24).forEach { a -> opts.put(JSONObject().put("label", "${a.user} [${a.status}] (${a.pkg.removePrefix("com.roblox.")})".take(100)).put("value", a.pkg.take(100))) }
                val menu = JSONObject().put("type", 3).put("custom_id", "pilot_acc:$mode:$myCloud")
                    .put("placeholder", "Select account on $myCloud...").put("options", opts)
                val row = JSONArray().put(JSONObject().put("type", 1).put("components", JSONArray().put(menu)))
                val label = if (mode == "d") "Select account to **DELETE** (revokes token + wipes vault + history):" else "Select account to **LOGOUT** (revokes token + wipes vault):"
                replyEmbed(id, token, 4, if (mode == "d") "🗑️ Delete Slot ($myCloud)" else "🚪 Logout Slot ($myCloud)", "$label\n${PilotCore.fmtAll(list)}", COLOR_AMBER, components = row)
            }
            return
        }

        // --- Second-step Select Menus (broadcasted to all bots; source & destination both see pilot_cs3!) ---
        if (kind == "pilot_cs3") {
            val srcParts = mode.split("|", limit = 3)
            if (srcParts.size < 3) return
            val srcCloud = srcParts[0]
            val srcPkg = srcParts[1]
            val srcUser = srcParts[2]
            val dstCloud = suffix
            val dstPkg = sel

            if (dstCloud.equals(myCloud, ignoreCase = true)) {
                replyEmbed(
                    id, token, 7,
                    "⏳ Transferring Account",
                    "Packing **$srcUser** on **$srcCloud** (`$srcPkg`) → **$dstCloud** (`$dstPkg`)...",
                    COLOR_BLUE, components = JSONArray()
                )
            }

            if (srcCloud.equals(myCloud, ignoreCase = true)) {
                ioExec.execute {
                    val (bundle, fileCount) = PilotCore.packAccountBundle(ctx, srcPkg, srcUser)
                    PilotCore.wipe(ctx, srcPkg, true)
                    if (bundle == null) {
                        sendEmbed(ch, "❌ Transfer Pack Failed ($myCloud)", "Failed to package **$srcUser**.", COLOR_RED)
                        return@execute
                    }
                    if (dstCloud.equals(myCloud, ignoreCase = true)) {
                        val (ok, restoredFiles) = PilotCore.restoreAccountBundle(ctx, dstPkg, srcUser, bundle)
                        if (ok) sendEmbed(ch, "✅ Local Slot Move Complete ($myCloud)", "Moved **$srcUser**: `$srcPkg` → `$dstPkg` (`$restoredFiles` file(s)).", COLOR_GREEN)
                        else sendEmbed(ch, "❌ Local Move Failed ($myCloud)", "Target slot `$dstPkg` not found.", COLOR_RED)
                    } else {
                        PilotCore.log("[XFER-OUT] Packed $srcUser (+$fileCount files) -> $dstCloud")
                        val safeName = "pilot_xfer__${srcCloud}__${srcPkg}__${srcUser}__${dstCloud}__${dstPkg}.tar.gz"
                        sendFileEmbed(ch, "📦 Encrypted Transfer Bundle", "Transferring **$srcUser** (`$srcCloud` → **$dstCloud**)...", COLOR_BLUE, bundle, safeName)
                        bundle.delete()
                    }
                }
            }
            return
        }

        val isAllTarget = suffix.equals("ALL", ignoreCase = true)
        if (!isAllTarget && !suffix.equals(myCloud, ignoreCase = true)) return

        if (!PilotCore.isAuthorized(ctx, userId, memberRoles)) {
            if (!isAllTarget) replyEmbed(id, token, 4, "⛔ Unauthorized", "You are not authorized.", COLOR_RED)
            return
        }

        if (kind == "pilot_login_slot") {
            openLoginModal(id, token, myCloud, sel)
        } else if (kind == "pilot_ar_act") {
            val cfg = Rejoiner.loadConfig(ctx)
            when (sel) {
                "TOGGLE_RJ" -> Rejoiner.saveConfig(ctx, cfg.copy(enabled = !cfg.enabled))
                "TOGGLE_FPS" -> Rejoiner.saveConfig(ctx, cfg.copy(fpsOptimization = !cfg.fpsOptimization))
                "TOGGLE_3D" -> Rejoiner.saveConfig(ctx, cfg.copy(render3dOff = !cfg.render3dOff))
                "TOGGLE_NOUSIGI" -> Rejoiner.saveConfig(ctx, cfg.copy(autoGetNousigiKey = !cfg.autoGetNousigiKey))
                "TOGGLE_DELTA" -> Rejoiner.saveConfig(ctx, cfg.copy(autoGetDeltaKey = !cfg.autoGetDeltaKey))
                "RUN_NOUSIGI" -> KeyAutomator.startNousigiRenewal(ctx)
                "RUN_DELTA" -> KeyAutomator.startDeltaRenewal(ctx)
                "REJOIN_ALL" -> ioExec.execute { Rejoiner.killAllAndStaggeredRejoin(ctx, "Discord panel") }
                "ON_LINK" -> {
                    if (!isAllTarget || myCloud.equals(PilotCore.getCloud(ctx), true)) {
                        val fTarget = JSONObject()
                            .put("type", 4).put("custom_id", "target")
                            .put("label", "Place ID or Private Server URL")
                            .put("style", 1).put("min_length", 4).put("max_length", 300)
                            .put("placeholder", "e.g. 8304191830 or https://www.roblox.com/games/...")
                            .put("required", false)
                        val modal = JSONObject()
                            .put("custom_id", "pilot_ar_modal:$suffix")
                            .put("title", "Rejoiner Target ($suffix)")
                            .put("components", JSONArray().put(JSONObject().put("type", 1).put("components", JSONArray().put(fTarget))))
                        replyInteraction(id, token, 9, modal)
                    }
                    return
                }
                else -> if (sel.startsWith("GAME:")) {
                    val pid = sel.removePrefix("GAME:")
                    Rejoiner.saveConfig(ctx, cfg.copy(enabled = true, targetPlaceId = pid, privateServerLinks = ""))
                }
            }
            replyEmbed(
                id, token, 7,
                "✅ Rejoiner Setting Updated",
                if (!isAllTarget) "Applied **$sel** on **$myCloud**." else "Applied **$sel** across **ALL ONLINE CLOUDS**.",
                COLOR_GREEN, components = JSONArray()
            )
        } else if (kind == "pilot_cs2") {
            val srcPkg = sel.substringBefore("|")
            val srcUser = sel.substringAfter("|", "")
            val title = "📦 Moving `$srcUser` (from $myCloud)"
            replyEmbed(id, token, 7, title, "Select Destination Cloud Phone below:", COLOR_BLUE, components = JSONArray())
            if (ch.isNotEmpty() && msgId.isNotEmpty()) {
                ioExec.execute {
                    collectAndPostSharedCloudMenu(ch, msgId, "cs_dst:$myCloud:$srcPkg:$srcUser", title)
                }
            }
        } else if (kind == "pilot_acc") {
            val isDel = mode == "d"
            if (sel == "ALL") {
                val msg = if (isDel) "Deleting ALL accounts, revoking tokens & clearing history on **$myCloud**." else "Logging out ALL accounts & revoking tokens on **$myCloud**."
                replyEmbed(id, token, 7, if (isDel) "🗑️ Deleting All Slots" else "🚪 Logging Out All Slots", msg, COLOR_RED, components = JSONArray())
                ioExec.execute {
                    PilotCore.cachedScan.ifEmpty { PilotCore.scanRbx(ctx) }.forEach { PilotCore.wipe(ctx, it.pkg, isDel) }
                }
            } else {
                val msg = if (isDel) "Deleted `$sel` (server token revoked + vault & history cleared)." else "Logged out `$sel` (server token revoked + vault wiped)."
                replyEmbed(id, token, 7, if (isDel) "🗑️ Slot Deleted ($myCloud)" else "🚪 Slot Logged Out ($myCloud)", msg, COLOR_GREEN, components = JSONArray())
                ioExec.execute { PilotCore.wipe(ctx, sel, isDel) }
            }
        }
    }

    private fun sendRejoinerPanelReply(id: String, token: String, targetTag: String) {
        val cfg = Rejoiner.loadConfig(ctx)
        val opts = JSONArray()
            .put(JSONObject().put("label", "Toggle Auto-Rejoin (${if (cfg.enabled) "ON" else "OFF"})").put("value", "TOGGLE_RJ"))
            .put(JSONObject().put("label", "Set Custom Place ID / VIP Link").put("value", "ON_LINK"))
            .put(JSONObject().put("label", "Toggle FPS Optimizer (${if (cfg.fpsOptimization) "ON" else "OFF"})").put("value", "TOGGLE_FPS"))
            .put(JSONObject().put("label", "Toggle 3D Render Off (${if (cfg.render3dOff) "ON" else "OFF"})").put("value", "TOGGLE_3D"))
            .put(JSONObject().put("label", "Toggle Chrome Nousigi Key Helper (${if (cfg.autoGetNousigiKey) "ON" else "OFF"})").put("value", "TOGGLE_NOUSIGI"))
            .put(JSONObject().put("label", "Toggle Chrome Delta Key Helper (${if (cfg.autoGetDeltaKey) "ON" else "OFF"})").put("value", "TOGGLE_DELTA"))
            .put(JSONObject().put("label", "Open Nousigi Key in Chrome Now").put("value", "RUN_NOUSIGI"))
            .put(JSONObject().put("label", "Test Baseplate & Open Delta Key Now").put("value", "RUN_DELTA"))
            .put(JSONObject().put("label", "Kill All & Staggered Rejoin Now").put("value", "REJOIN_ALL"))

        Rejoiner.FAMOUS_GAMES.filter { it.placeId.isNotEmpty() && it.placeId != "CUSTOM" }.forEach { g ->
            opts.put(JSONObject().put("label", "Game: ${g.name} (${g.placeId})").put("value", "GAME:${g.placeId}"))
        }

        val menu = JSONObject()
            .put("type", 3)
            .put("custom_id", "pilot_ar_act:cfg:$targetTag")
            .put("placeholder", "Select setting or game for $targetTag...")
            .put("options", opts)
        val row = JSONArray().put(JSONObject().put("type", 1).put("components", JSONArray().put(menu)))

        replyEmbed(
            id, token, 4,
            "🔁 Rejoiner & Key Control ($targetTag)",
            "• **Rejoiner:** ${if (cfg.enabled) "🟢 ON" else "🔴 OFF"} | **Target:** `${cfg.targetPlaceId.ifEmpty { "Auto" }}`\n" +
            "• **FPS Opt:** `${cfg.fpsOptimization}` | **3D Render Off:** `${cfg.render3dOff}`\n" +
            "• **Chrome Nousigi Key:** `${cfg.autoGetNousigiKey}` | **Chrome Delta Key:** `${cfg.autoGetDeltaKey}`",
            COLOR_BLUE, components = row
        )
    }

    private fun sendFileEmbed(ch: String, title: String, desc: String, color: Int, file: File, fileName: String) {
        runCatching {
            val payload = JSONObject()
                .put("content", "")
                .put("embeds", JSONArray().put(buildEmbed(title, desc, color)))
                .toString()
            val mp = MultipartBody.Builder().setType(MultipartBody.FORM)
                .addFormDataPart("payload_json", payload)
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

    private fun sendImageEmbed(ch: String, title: String, desc: String, color: Int, file: File) {
        runCatching {
            val isJpg = file.name.endsWith(".jpg")
            val mime = if (isJpg) "image/jpeg" else "image/png"
            val fname = if (isJpg) "screen.jpg" else "screen.png"
            val payload = JSONObject()
                .put("content", "")
                .put("embeds", JSONArray().put(buildEmbed(title, desc, color, imageFilename = fname)))
                .toString()
            val mp = MultipartBody.Builder().setType(MultipartBody.FORM)
                .addFormDataPart("payload_json", payload)
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
    private val bridgePool = Executors.newFixedThreadPool(4)
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
            .setContentText("Watchdog, Key Helper & Roblox Bridge Active")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .build()
        startForeground(1, notif)

        wl = (getSystemService(POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Pilot::Lock").apply { acquire() }

        bot = DiscordBot(this).apply { start() }
        Rejoiner.start(this)

        var last: List<AccInfo>? = null
        syncExec.scheduleAtFixedRate({
            val cur = PilotCore.scanRbx(this)
            if (cur != last) {
                last = cur
                cur.forEach {
                    PilotCore.log("[SYNC] ${it.pkg} -> ${it.user} (${it.status})")
                    if (it.status == "ACTIVE" && it.user != "Empty") {
                        SessionVault.snapshot(this, it.pkg, it.user)
                    }
                }
            }
        }, 1, 10, TimeUnit.SECONDS)

        syncExec.scheduleAtFixedRate({
            AppUpdater.checkAndInstall(this, force = false)
        }, 5, 30, TimeUnit.MINUTES)

        Thread {
            runCatching {
                localServer = ServerSocket(8765, 40, InetAddress.getByName("127.0.0.1"))
                PilotCore.log("[BRIDGE] HTTP server listening on 127.0.0.1:8765")
                while (isRunning) {
                    val sock = localServer?.accept() ?: break
                    bridgePool.execute {
                        runCatching {
                            val reqLine = sock.getInputStream().bufferedReader().readLine() ?: ""
                            val pathAndQuery = reqLine.substringAfter(" ", "").substringBefore(" ")
                            val respBody = handleLocalBridgePath(pathAndQuery)
                            val bytes = respBody.toByteArray(Charsets.UTF_8)
                            val header = "HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: ${bytes.size}\r\n\r\n"
                            sock.getOutputStream().apply {
                                write(header.toByteArray(Charsets.UTF_8))
                                write(bytes)
                                flush()
                            }
                            sock.close()
                        }
                    }
                }
            }
        }.start()
    }

    private fun handleLocalBridgePath(pathAndQuery: String): String {
        val path = pathAndQuery.substringBefore("?")
        val rawQuery = pathAndQuery.substringAfter("?", "")
        val params = if (rawQuery.isEmpty()) emptyMap() else rawQuery.split("&").mapNotNull { part ->
            val idx = part.indexOf('=')
            if (idx <= 0) null else URLDecoder.decode(part.substring(0, idx), "UTF-8") to URLDecoder.decode(part.substring(idx + 1), "UTF-8")
        }.toMap()

        return when (path) {
            "/ping" -> Rejoiner.onBridgePing(
                this,
                params["user"].orEmpty(),
                params["placeId"].orEmpty(),
                params["fps"]?.toIntOrNull() ?: 0
            )
            "/keyerr" -> {
                Rejoiner.onBridgeKeyError(this, params["user"].orEmpty(), params["reason"].orEmpty())
                """{"ok":true}"""
            }
            "/kick" -> {
                Rejoiner.onBridgeKick(this, params["user"].orEmpty(), params["code"].orEmpty(), params["msg"].orEmpty())
                """{"ok":true}"""
            }
            "/ss" -> {
                val user = params["user"].orEmpty().trim()
                val ch = PilotCore.getSsChannel(this)
                if (user.isNotEmpty() && ch.isNotEmpty()) {
                    PilotCore.log("[LUA-SS] $user")
                    bot?.sendLuaWindowShot(ch, user)
                }
                """{"ok":true}"""
            }
            else -> """{"ok":true}"""
        }
    }

    override fun onDestroy() {
        isRunning = false
        runCatching { localServer?.close() }
        syncExec.shutdownNow()
        bridgePool.shutdownNow()
        Rejoiner.stop()
        bot?.stop()
        runCatching { if (wl?.isHeld == true) wl?.release() }
        super.onDestroy()
    }
}

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        PilotCore.tryAutoSeedFromDisk(context)
        if (Rejoiner.loadConfig(context).autoStartOnBoot || PilotCore.getToken(context).isNotEmpty()) {
            PilotCore.startService(context)
        }
    }
}