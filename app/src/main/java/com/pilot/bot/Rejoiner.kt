package com.pilot.bot

import android.content.Context
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLDecoder
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlin.math.max

data class RejoinState(
    val pkg: String,
    @Volatile var user: String = "",
    @Volatile var userId: Long = 0L,
    @Volatile var placeId: Long = 0L,
    @Volatile var lastHbMs: Long = 0L,
    @Volatile var lastFrame: Long = -1L,
    @Volatile var lastFrameAdvanceMs: Long = 0L,
    @Volatile var teleportingUntilMs: Long = 0L,
    @Volatile var lastRelaunchMs: Long = 0L,
    @Volatile var presenceOfflineStrikes: Int = 0
)

object Rejoiner {
    val states = ConcurrentHashMap<String, RejoinState>()

    private val apiHttp = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    private fun prefs(c: Context) = c.getSharedPreferences("pilot_cfg", Context.MODE_PRIVATE)
    private fun histPrefs(c: Context) = c.getSharedPreferences("pilot_hist", Context.MODE_PRIVATE)

    fun isEnabled(c: Context): Boolean = prefs(c).getBoolean("ar_enabled", false)
    fun getTarget(c: Context): String = prefs(c).getString("ar_target", "") ?: ""

    fun setConfig(c: Context, enabled: Boolean, target: String) {
        prefs(c).edit()
            .putBoolean("ar_enabled", enabled)
            .putString("ar_target", target.trim())
            .apply()
        PilotCore.log("[REJOIN] ${if (enabled) "ENABLED" else "DISABLED"} | Target: ${target.trim().ifEmpty { "Auto (Last Place)" }}")
    }

    fun getSavedPlaceId(c: Context, pkg: String): Long = prefs(c).getLong("last_place_$pkg", 0L)
    fun savePlaceId(c: Context, pkg: String, placeId: Long) {
        if (placeId > 0L) prefs(c).edit().putLong("last_place_$pkg", placeId).apply()
    }

    fun getSavedUserId(c: Context, user: String): Long =
        if (user.isEmpty() || user == "Empty") 0L else histPrefs(c).getLong("uid_${user.lowercase()}", 0L)

    fun saveUserId(c: Context, user: String, uid: Long) {
        if (user.isNotEmpty() && user != "Empty" && uid > 0L) {
            histPrefs(c).edit().putLong("uid_${user.lowercase()}", uid).apply()
        }
    }

    /**
     * Lua script deployed into Delta's Autoexecute folder.
     * Reports heartbeats, frame ticks (freeze detection), place teleports, and kick prompts to Pilot.
     */
    fun buildBridgeLua(): String = """
        repeat task.wait(0.5) until game:IsLoaded()
        local Players = game:GetService("Players")
        local HttpService = game:GetService("HttpService")
        local RunService = game:GetService("RunService")
        local GuiService = game:GetService("GuiService")
        local TeleportService = game:GetService("TeleportService")

        local lp = Players.LocalPlayer
        while not lp do
            task.wait(0.5)
            lp = Players.LocalPlayer
        end

        local req = request or http_request or (syn and syn.request)
        if not req then return end

        local frameCount = 0
        local teleporting = false
        local lastTpAt = 0

        RunService.Heartbeat:Connect(function()
            frameCount = frameCount + 1
        end)

        local function ping(path)
            pcall(function()
                req({
                    Url = "http://127.0.0.1:8765" .. path,
                    Method = "GET"
                })
            end)
        end

        local function sendHeartbeat(tpFlag)
            local u = HttpService:UrlEncode(lp.Name)
            local url = string.format(
                "/hb?user=%s&uid=%d&place=%d&frame=%d&tp=%d",
                u, lp.UserId, game.PlaceId, frameCount, tpFlag and 1 or 0
            )
            ping(url)
        end

        -- Teleport shield: prevents false rejoins while moving between places/sub-places
        lp.OnTeleport:Connect(function(state)
            if state == Enum.TeleportState.Started
                or state == Enum.TeleportState.WaitingForServer
                or state == Enum.TeleportState.InProgress then
                teleporting = true
                lastTpAt = os.clock()
                ping("/tp?user=" .. HttpService:UrlEncode(lp.Name) .. "&state=1")
            elseif state == Enum.TeleportState.Failed then
                teleporting = false
                ping("/tp?user=" .. HttpService:UrlEncode(lp.Name) .. "&state=0")
            end
        end)

        TeleportService.TeleportInitFailed:Connect(function()
            teleporting = false
            ping("/tp?user=" .. HttpService:UrlEncode(lp.Name) .. "&state=0")
        end)

        -- Catch Roblox kick/disconnect error prompts immediately
        pcall(function()
            GuiService.ErrorMessageChanged:Connect(function()
                local msg = GuiService:GetErrorMessage()
                if msg and msg ~= "" then
                    ping("/err?user=" .. HttpService:UrlEncode(lp.Name) .. "&msg=" .. HttpService:UrlEncode(msg))
                end
            end)
        end)

        local function snapMe()
            ping("/ss?user=" .. HttpService:UrlEncode(lp.Name))
        end

        getgenv().PilotSnap = snapMe

        -- Heartbeat loop (every 4s) for freeze + place + teleport tracking
        task.spawn(function()
            sendHeartbeat(false)
            while task.wait(4) do
                if teleporting and (os.clock() - lastTpAt) > 45 then
                    teleporting = false
                end
                sendHeartbeat(teleporting)
            end
        end)

        -- Hourly auto-screenshot loop
        task.spawn(function()
            task.wait(6)
            snapMe()
            while task.wait(3600) do
                snapMe()
            end
        end)
    """.trimIndent()

    /**
     * Handles `/hb`, `/tp`, and `/err` routes from `pilot_bridge.lua`.
     * Returns true if the request was handled by Rejoiner.
     */
    fun handleBridgeRequest(c: Context, pathAndQuery: String): Boolean {
        if (!pathAndQuery.startsWith("/hb") && !pathAndQuery.startsWith("/tp") && !pathAndQuery.startsWith("/err")) {
            return false
        }
        val userParam = Regex("""[?&]user=([^&\s]+)""").find(pathAndQuery)?.groupValues?.get(1) ?: return true
        val user = runCatching { URLDecoder.decode(userParam, "UTF-8").trim() }.getOrDefault("")
        if (user.isEmpty()) return true

        val list = PilotCore.cachedScan.ifEmpty { PilotCore.scanRbx(c) }
        val pkg = list.firstOrNull { it.user.equals(user, ignoreCase = true) }?.pkg ?: return true
        val now = System.currentTimeMillis()

        when {
            pathAndQuery.startsWith("/hb") -> {
                val uid = Regex("""[?&]uid=(\d+)""").find(pathAndQuery)?.groupValues?.get(1)?.toLongOrNull() ?: 0L
                val place = Regex("""[?&]place=(\d+)""").find(pathAndQuery)?.groupValues?.get(1)?.toLongOrNull() ?: 0L
                val frame = Regex("""[?&]frame=(\d+)""").find(pathAndQuery)?.groupValues?.get(1)?.toLongOrNull() ?: 0L
                val tp = Regex("""[?&]tp=(\d+)""").find(pathAndQuery)?.groupValues?.get(1) == "1"

                val st = states.computeIfAbsent(pkg) { RejoinState(pkg, user) }
                st.user = user
                if (uid > 0L) {
                    st.userId = uid
                    saveUserId(c, user, uid)
                }
                if (place > 0L) {
                    st.placeId = place
                    savePlaceId(c, pkg, place)
                }
                st.lastHbMs = now
                if (frame != st.lastFrame) {
                    st.lastFrame = frame
                    st.lastFrameAdvanceMs = now
                }
                if (tp) {
                    st.teleportingUntilMs = max(st.teleportingUntilMs, now + 45_000L)
                }
            }

            pathAndQuery.startsWith("/tp") -> {
                val stateFlag = Regex("""[?&]state=(\d+)""").find(pathAndQuery)?.groupValues?.get(1)
                val st = states.computeIfAbsent(pkg) { RejoinState(pkg, user) }
                if (stateFlag == "1") {
                    st.teleportingUntilMs = now + 45_000L
                    PilotCore.log("[TP] $user teleporting (45s rejoin pause)")
                } else {
                    st.teleportingUntilMs = 0L
                }
            }

            pathAndQuery.startsWith("/err") -> {
                if (isEnabled(c)) {
                    val st = states.computeIfAbsent(pkg) { RejoinState(pkg, user) }
                    if (now >= st.teleportingUntilMs && (now - st.lastRelaunchMs) > 25_000L) {
                        PilotCore.log("[REJOIN] $user kicked/disconnected -> rejoining...")
                        relaunch(c, pkg, st)
                    }
                }
            }
        }
        return true
    }

    /**
     * All-in-One Rejoin Watchdog:
     * 1. Delta Lua Heartbeat + Frame Counter (freeze detection)
     * 2. Roblox Presence API (`presence.roblox.com/v1/presence/users`)
     * 3. Android OS Process Check (`pidof <pkg>`)
     * 4. Teleport Guard (`teleportingUntilMs`)
     */
    fun tick(c: Context) {
        if (!isEnabled(c)) return
        val now = System.currentTimeMillis()
        val list = PilotCore.cachedScan.ifEmpty { PilotCore.scanRbx(c) }
        val activeAccs = list.filter { it.status == "ACTIVE" && it.user != "Empty" }
        if (activeAccs.isEmpty()) return

        val uidToPkg = HashMap<Long, String>()
        for (acc in activeAccs) {
            val st = states.computeIfAbsent(acc.pkg) { RejoinState(acc.pkg, acc.user) }
            st.user = acc.user
            val uid = if (st.userId > 0L) st.userId else getSavedUserId(c, acc.user)
            if (uid > 0L) {
                st.userId = uid
                uidToPkg[uid] = acc.pkg
            }
        }

        val presenceMap = if (uidToPkg.isNotEmpty()) fetchRobloxPresences(uidToPkg.keys.toList()) else emptyMap()

        for (acc in activeAccs) {
            val st = states.computeIfAbsent(acc.pkg) { RejoinState(acc.pkg, acc.user) }

            if (now < st.teleportingUntilMs) continue
            if (now - st.lastRelaunchMs < 35_000L) continue

            val pid = SuShell.exec("pidof ${acc.pkg}").trim()
            if (pid.isEmpty()) {
                PilotCore.log("[REJOIN] ${acc.user} (${acc.pkg}) closed/crashed -> relaunching...")
                relaunch(c, acc.pkg, st)
                continue
            }

            if (st.lastHbMs > 0L) {
                val sinceHb = now - st.lastHbMs
                val sinceFrameMove = if (st.lastFrameAdvanceMs > 0L) now - st.lastFrameAdvanceMs else 0L
                if (sinceFrameMove > 20_000L && sinceHb > 18_000L) {
                    PilotCore.log("[REJOIN] ${acc.user} (${acc.pkg}) FROZEN (${sinceFrameMove / 1000}s) -> restarting...")
                    relaunch(c, acc.pkg, st)
                    continue
                }
            }

            val pType = if (st.userId > 0L) presenceMap[st.userId] else null
            if (pType != null) {
                if (pType == 2) {
                    st.presenceOfflineStrikes = 0
                } else {
                    st.presenceOfflineStrikes++
                    if (st.presenceOfflineStrikes >= 2 && (now - st.lastHbMs) > 15_000L) {
                        st.presenceOfflineStrikes = 0
                        PilotCore.log("[REJOIN] ${acc.user} (${acc.pkg}) Roblox API status=$pType (not in-game) -> rejoining...")
                        relaunch(c, acc.pkg, st)
                        continue
                    }
                }
            }
        }
    }

    private fun fetchRobloxPresences(userIds: List<Long>): Map<Long, Int> {
        val out = HashMap<Long, Int>()
        runCatching {
            val arr = JSONArray()
            userIds.take(50).forEach { arr.put(it) }
            val payload = JSONObject().put("userIds", arr).toString()
            val req = Request.Builder()
                .url("https://presence.roblox.com/v1/presence/users")
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .post(payload.toRequestBody("application/json".toMediaType()))
                .build()
            apiHttp.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@use
                val body = resp.body?.string() ?: return@use
                val list = JSONObject(body).optJSONArray("userPresences") ?: return@use
                for (i in 0 until list.length()) {
                    val item = list.getJSONObject(i)
                    val uid = item.optLong("userId", 0L)
                    val pType = item.optInt("userPresenceType", -1)
                    if (uid > 0L && pType >= 0) out[uid] = pType
                }
            }
        }
        return out
    }

    fun buildLaunchUri(c: Context, pkg: String, st: RejoinState?): String {
        val customTarget = getTarget(c).trim()
        if (customTarget.isNotEmpty()) {
            if (customTarget.startsWith("roblox://", ignoreCase = true)) return customTarget
            val codeMatch = Regex("""(?:privateServerLinkCode=|code=)([a-zA-Z0-9_-]+)""").find(customTarget)
            val placeMatch = Regex("""(?:games/|placeId=)(\d+)""").find(customTarget)
            val pid = placeMatch?.groupValues?.get(1)?.toLongOrNull()
                ?: customTarget.toLongOrNull()
                ?: st?.placeId?.takeIf { it > 0L }
                ?: getSavedPlaceId(c, pkg)
            if (pid > 0L && codeMatch != null) {
                return "roblox://placeId=$pid&linkCode=${codeMatch.groupValues[1]}"
            }
            if (pid > 0L) return "roblox://placeId=$pid"
        }

        val lastPid = st?.placeId?.takeIf { it > 0L } ?: getSavedPlaceId(c, pkg)
        return if (lastPid > 0L) "roblox://placeId=$lastPid" else ""
    }

    @Synchronized
    fun relaunch(c: Context, pkg: String, st: RejoinState? = null) {
        val state = st ?: states.computeIfAbsent(pkg) { RejoinState(pkg) }
        state.lastRelaunchMs = System.currentTimeMillis()
        state.lastHbMs = 0L
        state.lastFrame = -1L
        state.lastFrameAdvanceMs = 0L
        state.presenceOfflineStrikes = 0

        val uri = buildLaunchUri(c, pkg, state)
        val cmd = if (uri.isNotEmpty()) {
            "am force-stop $pkg && sleep 1 && am start -a android.intent.action.VIEW -d '$uri' -p $pkg >/dev/null 2>&1"
        } else {
            "am force-stop $pkg && sleep 1 && monkey -p $pkg -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1"
        }
        SuShell.exec(cmd)
    }
}