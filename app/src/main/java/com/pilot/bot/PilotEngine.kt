package com.pilot.bot

import android.app.*
import android.content.*
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapRegionDecoder
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
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.URLDecoder
import java.util.concurrent.*
import kotlin.math.max
import kotlin.math.min
import kotlin.random.Random

data class AccInfo(val pkg: String, val user: String, val status: String)

object SuShell {
    private var proc: Process? = null
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

    private fun isAlive(p: Process): Boolean = try {
        p.exitValue()
        false
    } catch (_: IllegalThreadStateException) {
        true
    }
}

object PilotCore {
    var logs = "Engine initialized.\n"
    var onLog: (() -> Unit)? = null

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

    fun saveSsChannel(c: Context, ch: String) {
        prefs(c).edit().putString("ss_channel", ch.trim()).apply()
    }

    fun saveConfig(c: Context, cloud: String, token: String, uid: Long, ssChan: String = "") {
        val cl = cloud.trim().ifEmpty { detectDeviceName(c) }
        val edit = prefs(c).edit().putString("cloud", cl).putString("token", token.trim()).putLong("user_id", uid)
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
            .toString()
        val b64 = Base64.encodeToString(payload.toByteArray(), Base64.URL_SAFE or Base64.NO_WRAP)
        return "$letters-$b64"
    }

    fun applyCode(c: Context, code: String): Boolean = runCatching {
        val raw = code.trim().substringAfter("-")
        val json = JSONObject(String(Base64.decode(raw, Base64.URL_SAFE)))
        val edit = prefs(c).edit().putString("token", json.getString("t")).putLong("user_id", json.getLong("u"))
        val ch = json.optString("c", "")
        if (ch.isNotEmpty()) edit.putString("ss_channel", ch)
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
            val cropped = FileInputStream(rawFile).use { fis ->
                val decoder = BitmapRegionDecoder.newInstance(fis, false)
                val safeRect = Rect(
                    bounds.left.coerceIn(0, decoder.width - 1),
                    bounds.top.coerceIn(0, decoder.height - 1),
                    bounds.right.coerceIn(1, decoder.width),
                    bounds.bottom.coerceIn(1, decoder.height)
                )
                val bmp = decoder.decodeRegion(safeRect, opts)
                decoder.recycle()
                bmp
            }
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
        return res.sortedBy { it.pkg }
    }

    fun wipe(c: Context, pkg: String, clearHist: Boolean) {
        SuShell.exec("am force-stop $pkg && rm -rf /data/data/$pkg/app_webview/Default/Cookies* /data/data/$pkg/shared_prefs/* /data/data/$pkg/files/LocalStorage/* /data/data/$pkg/files/appData/LocalStorage/*")
        if (clearHist) histPrefs(c).edit().remove(pkg).apply()
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
    private var ws: WebSocket? = null
    private var hbTask: ScheduledFuture<*>? = null
    private var seq: Int? = null
    @Volatile private var alive = true

    fun start() { connect() }

    fun stop() {
        alive = false
        hbTask?.cancel(true)
        ws?.close(1000, "Stopped")
        exec.shutdownNow()
    }

    fun captureAndSendAll(ch: String) {
        val cl = PilotCore.getCloud(ctx)
        val list = PilotCore.scanRbx(ctx)
        val summary = if (list.isNotEmpty()) "\n${PilotCore.fmtAll(list)}" else ""
        val (img, _) = PilotCore.captureScreen(ctx, null)
        if (img != null) {
            sendImage(ch, "**📱 $cl** — Full Screen:$summary", img)
            img.delete()
        }
    }

    fun sendAccountShot(ch: String, query: String) {
        val cl = PilotCore.getCloud(ctx)
        val list = PilotCore.scanRbx(ctx)
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
            sendMsg(ch, "⚠️ **📱 $cl** — No matching account found for `$query`.")
            return
        }
        for ((idx, acc) in matches) {
            val (img, croppedOk) = PilotCore.captureScreen(ctx, acc.pkg)
            val note = if (!croppedOk) "\n*(Window hidden — showing full screen)*" else ""
            val caption = "**📱 $cl** — Status for `${query}`:\n${PilotCore.fmtLine(idx, acc)}$note"
            if (img != null) {
                sendImage(ch, caption, img)
                img.delete()
            } else {
                sendMsg(ch, caption)
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
                    val u = d.getJSONObject("user").getString("username")
                    PilotCore.log("[ONLINE] $u | ${PilotCore.getCloud(ctx)}")
                } else if (type == "MESSAGE_CREATE") onMessageCreate(d)
                else if (type == "INTERACTION_CREATE") onInteraction(d)
            }
        }
    }

    private fun onMessageCreate(d: JSONObject) {
        val ownerId = PilotCore.getUserId(ctx).toString()
        val author = d.optJSONObject("author") ?: return
        if (author.optString("id") != ownerId) return
        val content = d.optString("content").trim()
        if (!content.startsWith("!")) return
        val ch = d.getString("channel_id")
        val cl = PilotCore.getCloud(ctx)
        val parts = content.split(" ", limit = 2)
        val cmd = parts[0].lowercase()
        val arg = parts.getOrNull(1)?.trim() ?: ""

        when (cmd) {
            "!scan" -> sendCloudMenu(ch, cl, "s", "**Select Cloud to Scan:**")
            "!logout" -> sendCloudMenu(ch, cl, "l", "**Select Cloud to Logout:**")
            "!delete" -> sendCloudMenu(ch, cl, "d", "**Select Cloud to Delete Account & History:**")
            "!rename" -> {
                if (arg.isEmpty()) sendMsg(ch, "⚠️ **Missing name!** Usage: `!rename <New Cloud Name>`")
                else sendCloudMenu(ch, cl, "r:$arg", "**Select Cloud to Rename to `$arg`:**")
            }
            "!sschannel" -> {
                PilotCore.saveSsChannel(ctx, ch)
                sendMsg(ch, "✅ **📱 $cl** — Screenshots will now post to this channel.")
            }
            "!ss" -> captureAndSendAll(ch)
            "!checkstatus" -> {
                if (arg.isEmpty()) sendMsg(ch, "⚠️ **Missing username!** Usage: `!checkstatus <username>`")
                else sendAccountShot(ch, arg)
            }
            "!search" -> {
                if (arg.isEmpty()) {
                    sendMsg(ch, "⚠️ **Missing username!** Usage: `!search <username>`")
                    return
                }
                val list = PilotCore.scanRbx(ctx)
                val q = arg.lowercase()
                val exact = list.mapIndexedNotNull { i, a -> if (a.user != "Empty" && a.user.lowercase().contains(q)) PilotCore.fmtLine(i, a) else null }
                if (exact.isNotEmpty()) {
                    sendMsg(ch, "**📱 $cl** — Match for `$arg`:\n${exact.joinToString("\n")}")
                } else {
                    val near = list.mapIndexedNotNull { i, a ->
                        if (a.user != "Empty" && PilotCore.similarity(q, a.user.lowercase()) >= 0.45) PilotCore.fmtLine(i, a) else null
                    }
                    if (near.isNotEmpty()) sendMsg(ch, "**📱 $cl** — No exact match for `$arg`, near match:\n${near.joinToString("\n")}")
                    else sendMsg(ch, "⚠️ **📱 $cl** — No matching account found for `$arg`.")
                }
            }
            "!config" -> {
                val code = PilotCore.makeCode(ctx)
                val dmRes = apiPost("https://discord.com/api/v10/users/@me/channels", JSONObject().put("recipient_id", ownerId))
                val dmCh = runCatching { JSONObject(dmRes).getString("id") }.getOrDefault("")
                if (dmCh.isNotEmpty()) {
                    sendMsg(dmCh, "**Your Pilot Sync Code:**\n`$code`\n```\n$code\n```")
                    sendMsg(ch, "Sent your new config code to your DMs!")
                }
            }
            else -> sendMsg(ch, "⚠️ **Invalid command!** Use: `!scan` | `!search <user>` | `!checkstatus <user>` | `!rename <name>` | `!ss` | `!sschannel` | `!logout` | `!delete` | `!config`")
        }
    }

    private fun onInteraction(d: JSONObject) {
        if (d.optInt("type") != 3) return
        val id = d.getString("id")
        val token = d.getString("token")
        val user = d.optJSONObject("member")?.optJSONObject("user") ?: d.optJSONObject("user") ?: return
        val ownerId = PilotCore.getUserId(ctx).toString()
        val data = d.getJSONObject("data")
        val cid = data.optString("custom_id")
        val vals = data.optJSONArray("values") ?: return
        val sel = vals.optString(0)

        val firstColon = cid.indexOf(':')
        val lastColon = cid.lastIndexOf(':')
        if (firstColon <= 0 || lastColon <= firstColon) return

        val kind = cid.substring(0, firstColon)
        val mode = cid.substring(firstColon + 1, lastColon)
        val targetCloud = cid.substring(lastColon + 1)
        val myCloud = PilotCore.getCloud(ctx)
        if (targetCloud != myCloud) return

        if (user.optString("id") != ownerId) {
            replyInteraction(id, token, 4, JSONObject().put("content", "Unauthorized").put("flags", 64))
            return
        }

        if (kind == "pilot_cloud") {
            if (mode.startsWith("r:")) {
                val newName = mode.removePrefix("r:").trim()
                PilotCore.setCloudName(ctx, newName)
                PilotCore.log("[RENAME] $myCloud -> $newName")
                replyInteraction(id, token, 4, JSONObject().put("content", "✅ Renamed **$myCloud** to **$newName**.").put("flags", 64))
                return
            }

            val list = PilotCore.scanRbx(ctx)
            if (list.isEmpty()) {
                replyInteraction(id, token, 4, JSONObject().put("content", "**📱 $myCloud** — No instances found.").put("flags", 64))
                return
            }
            if (mode == "s") {
                replyInteraction(id, token, 4, JSONObject().put("content", "**📱 $myCloud:**\n${PilotCore.fmtAll(list)}").put("flags", 64))
            } else {
                val opts = JSONArray().put(JSONObject().put("label", if (mode == "d") "DELETE ALL DATA & HISTORY" else "LOGOUT ALL ON THIS CLOUD").put("value", "ALL"))
                list.take(24).forEach { a -> opts.put(JSONObject().put("label", "${a.user} [${a.status}] (${a.pkg})").put("value", a.pkg)) }
                val menu = JSONObject().put("type", 3).put("custom_id", "pilot_acc:$mode:$myCloud")
                    .put("placeholder", "Select account on $myCloud...").put("options", opts)
                val row = JSONArray().put(JSONObject().put("type", 1).put("components", JSONArray().put(menu)))
                val label = if (mode == "d") "Select account to **DELETE** (wipes session + history):" else "Select account to **LOGOUT**:"
                replyInteraction(id, token, 4, JSONObject().put("content", "**📱 $myCloud** — $label\n${PilotCore.fmtAll(list)}").put("components", row).put("flags", 64))
            }
        } else if (kind == "pilot_acc") {
            val isDel = mode == "d"
            if (sel == "ALL") {
                PilotCore.scanRbx(ctx).forEach { PilotCore.wipe(ctx, it.pkg, isDel) }
                val msg = if (isDel) "**📱 $myCloud** — Deleted ALL accounts & history (now Empty)." else "**📱 $myCloud** — Logged out ALL."
                replyInteraction(id, token, 7, JSONObject().put("content", msg).put("components", JSONArray()))
            } else {
                PilotCore.wipe(ctx, sel, isDel)
                val msg = if (isDel) "**📱 $myCloud** — Deleted all data & history for `$sel` (now Empty)." else "**📱 $myCloud** — Logged out `$sel`."
                replyInteraction(id, token, 7, JSONObject().put("content", msg).put("components", JSONArray()))
            }
        }
    }

    private fun sendCloudMenu(ch: String, cl: String, mode: String, title: String) {
        val opt = JSONArray().put(JSONObject().put("label", "📱 $cl").put("value", cl).put("description", "Tap to select"))
        val menu = JSONObject().put("type", 3).put("custom_id", "pilot_cloud:$mode:$cl")
            .put("placeholder", "Select Cloud ($cl)...").put("options", opt)
        val row = JSONArray().put(JSONObject().put("type", 1).put("components", JSONArray().put(menu)))
        sendMsg(ch, title, row)
    }

    private fun sendMsg(ch: String, content: String, components: JSONArray? = null) {
        val body = JSONObject().put("content", content)
        if (components != null) body.put("components", components)
        apiPost("https://discord.com/api/v10/channels/$ch/messages", body)
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
            nm.createNotificationChannel(NotificationChannel(chId, "Pilot Bot", NotificationManager.IMPORTANCE_LOW))
        }
        val notif = (if (Build.VERSION.SDK_INT >= 26) Notification.Builder(this, chId) else Notification.Builder(this))
            .setContentTitle("Pilot Active (${PilotCore.getCloud(this)})")
            .setContentText("GVIP Optimized • Auto-syncing Roblox")
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
        }, 2, 10, TimeUnit.SECONDS)

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
                                bot?.sendAccountShot(ch, user)
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
        if (PilotCore.getToken(context).isNotEmpty() && PilotCore.getUserId(context) != 0L) {
            val svc = Intent(context, PilotService::class.java)
            if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(svc) else context.startService(svc)
        }
    }
}