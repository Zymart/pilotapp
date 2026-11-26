package com.pilot.bot

import android.app.*
import android.content.*
import android.os.*
import android.util.Base64
import okhttp3.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.*
import kotlin.math.max
import kotlin.math.min
import kotlin.random.Random

data class AccInfo(val pkg: String, val user: String, val status: String)

object PilotCore {
    var logs = "Ready.\n"
    var onLog: (() -> Unit)? = null

    fun log(msg: String) {
        logs = (logs + msg + "\n").takeLast(3000)
        onLog?.invoke()
    }

    private fun prefs(c: Context) = c.getSharedPreferences("pilot_cfg", Context.MODE_PRIVATE)
    private fun histPrefs(c: Context) = c.getSharedPreferences("pilot_hist", Context.MODE_PRIVATE)

    fun getCloud(c: Context): String {
        val f = File("/sdcard/cloud_name.txt")
        if (f.exists()) {
            val t = runCatching { f.readText().trim() }.getOrDefault("")
            if (t.isNotEmpty()) return t
        }
        return prefs(c).getString("cloud", "Not Set") ?: "Not Set"
    }

    fun getToken(c: Context) = prefs(c).getString("token", "") ?: ""
    fun getUserId(c: Context) = prefs(c).getLong("user_id", 0L)

    fun saveConfig(c: Context, cloud: String, token: String, uid: Long) {
        val cl = cloud.trim().ifEmpty { "Not Set" }
        prefs(c).edit().putString("cloud", cl).putString("token", token.trim()).putLong("user_id", uid).apply()
        runCatching { File("/sdcard/cloud_name.txt").writeText(cl) }
    }

    fun makeCode(c: Context): String {
        val letters = (1..7).map { ('A'..'Z').random() }.joinToString("")
        val payload = JSONObject().put("t", getToken(c)).put("u", getUserId(c)).toString()
        val b64 = Base64.encodeToString(payload.toByteArray(), Base64.URL_SAFE or Base64.NO_WRAP)
        return "$letters-$b64"
    }

    fun applyCode(c: Context, code: String): Boolean = runCatching {
        val raw = code.trim().substringAfter("-")
        val json = JSONObject(String(Base64.decode(raw, Base64.URL_SAFE)))
        prefs(c).edit().putString("token", json.getString("t")).putLong("user_id", json.getLong("u")).apply()
        true
    }.getOrDefault(false)

    fun su(cmd: String): String = runCatching {
        val p = ProcessBuilder("su", "-c", cmd).redirectErrorStream(true).start()
        val out = p.inputStream.bufferedReader().readText().trim()
        p.waitFor(8, TimeUnit.SECONDS)
        out
    }.getOrDefault("")

    fun scanRbx(c: Context): List<AccInfo> {
        val pkgs = (su("ls /data/data 2>/dev/null|grep -E '^com\\.roblox\\.clien'").lines() +
                su("ls /sdcard/Android/data 2>/dev/null|grep -E '^com\\.roblox\\.clien'").lines())
            .map { it.trim() }.filter { it.isNotEmpty() }.toSortedSet()

        val hp = histPrefs(c)
        val edit = hp.edit()
        var changed = false
        val rx = Regex("""(?:UserName|Username)["'>:\s]+([a-zA-Z0-9_]{3,20})""")
        val res = mutableListOf<AccInfo>()

        for (p in pkgs) {
            val raw = su("grep -rnEI 'UserName|Username' /data/data/$p/files/appData/LocalStorage/appStorage.json /data/data/$p/shared_prefs/ 2>/dev/null|head -n 3")
            val m = rx.find(raw)
            if (m != null) {
                val u = m.groupValues[1]
                if (hp.getString(p, null) != u) { edit.putString(p, u); changed = true }
                res.add(AccInfo(p, u, "ACTIVE"))
            } else {
                val old = hp.getString(p, null)
                if (!old.isNullOrEmpty()) res.add(AccInfo(p, old, "LOGGED OUT"))
                else res.add(AccInfo(p, "Empty", "EMPTY"))
            }
        }
        if (changed) edit.apply()
        return res
    }

    fun wipe(c: Context, pkg: String, clearHist: Boolean) {
        su("am force-stop $pkg&&rm -rf /data/data/$pkg/app_webview/Default/Cookies* /data/data/$pkg/shared_prefs/* /data/data/$pkg/files/LocalStorage/* /data/data/$pkg/files/appData/LocalStorage/*")
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
                val interval = pkt.getJSONObject("d").getLong("heartbeat_interval")
                hbTask?.cancel(true)
                hbTask = exec.scheduleAtFixedRate({
                    w.send(JSONObject().put("op", 1).put("d", seq ?: JSONObject.NULL).toString())
                }, Random.nextLong(500, interval), interval, TimeUnit.MILLISECONDS)

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
            else -> sendMsg(ch, "⚠️ **Invalid command!** Use: `!scan` | `!search <user>` | `!logout` | `!delete` | `!config`")
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
        val segs = cid.split(":", limit = 3)
        if (segs.size < 3) return

        val kind = segs[0]
        val mode = segs[1]
        val targetCloud = segs[2]
        val myCloud = PilotCore.getCloud(ctx)
        if (targetCloud != myCloud) return

        if (user.optString("id") != ownerId) {
            replyInteraction(id, token, 4, JSONObject().put("content", "Unauthorized").put("flags", 64))
            return
        }

        if (kind == "pilot_cloud") {
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
        val opt = JSONArray().put(JSONObject().put("label", "📱 $cl").put("value", cl).put("description", "Tap to load accounts"))
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

    private fun replyInteraction(id: String, token: String, type: Int, data: JSONObject) {
        apiPost("https://discord.com/api/v10/interactions/$id/$token/callback", JSONObject().put("type", type).put("data", data), auth = false)
    }

    private fun apiPost(url: String, json: JSONObject, auth: Boolean = true): String = runCatching {
        val b = Request.Builder().url(url).post(RequestBody.create(MediaType.parse("application/json"), json.toString()))
        if (auth) b.header("Authorization", "Bot ${PilotCore.getToken(ctx)}")
        client.newCall(b.build()).execute().use { it.body()?.string() ?: "" }
    }.getOrDefault("")
}

class PilotService : Service() {
    companion object { var isRunning = false }
    private var bot: DiscordBot? = null
    private var wl: PowerManager.WakeLock? = null
    private val syncExec = Executors.newSingleThreadScheduledExecutor()

    override fun onBind(intent: Intent?): IBinder? = null

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
            .setContentText("Auto-syncing Roblox accounts every 4s")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .build()
        startForeground(1, notif)

        wl = (getSystemService(POWER_SERVICE) as PowerManager).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Pilot::Lock").apply { acquire() }
        bot = DiscordBot(this).apply { start() }

        var last: List<AccInfo>? = null
        syncExec.scheduleAtFixedRate({
            val cur = PilotCore.scanRbx(this)
            if (cur != last) {
                last = cur
                cur.forEach { PilotCore.log("[SYNC] ${it.pkg} -> ${it.user} (${it.status})") }
            }
        }, 1, 4, TimeUnit.SECONDS)
    }

    override fun onDestroy() {
        isRunning = false
        syncExec.shutdownNow()
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
