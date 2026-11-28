package com.pilot.bot

import android.content.Context
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

data class FamousGame(val name: String, val placeId: String)

data class CloneSlot(
    val pkg: String,
    @Volatile var username: String = "",
    @Volatile var status: String = "IDLE",
    @Volatile var placeId: String = "",
    @Volatile var fps: Int = 0,
    @Volatile var lastPingMs: Long = 0L,
    @Volatile var lastLaunchMs: Long = 0L,
    @Volatile var lastKickReason: String = "",
    @Volatile var paused: Boolean = false
)

data class RejoinConfig(
    val enabled: Boolean = true,
    val autoStartOnBoot: Boolean = true,
    val targetPlaceId: String = "8304191830",
    val privateServerLinks: String = "",
    val fpsOptimization: Boolean = true,
    val render3dOff: Boolean = false,
    val autoGetNousigiKey: Boolean = false,
    val luarmorUrl: String = "https://ads.luarmor.net/get_key?for=-dkAHbyOeBCOT",
    val lvEmail: String = "",
    val lvPassword: String = "",
    val nousigiKeyPath: String = "",
    val autoGetDeltaKey: Boolean = false,
    val deltaPlatoUrl: String = "",
    val pingTimeoutSec: Int = 95,
    val staggerDelaySec: Int = 13
)

object Rejoiner {
    val FAMOUS_GAMES = listOf(
        FamousGame("Anime Adventures", "8304191830"),
        FamousGame("Blox Fruits", "2753915549"),
        FamousGame("Anime Vanguards", "16146832113"),
        FamousGame("Fisch", "16732694052"),
        FamousGame("Pet Simulator 99", "8737899170"),
        FamousGame("Steal a Brainrot", "109983668079237"),
        FamousGame("Grow a Garden", "126884695634066"),
        FamousGame("Auto (Last Place ID)", ""),
        FamousGame("Custom Place ID / VIP", "CUSTOM")
    )

    val slots = ConcurrentHashMap<String, CloneSlot>()
    private val running = AtomicBoolean(false)
    private val launchExec = Executors.newSingleThreadExecutor()
    private var watchdogThread: Thread? = null
    @Volatile private var vipIndex = 0
    @Volatile private var screenshot3dHoldUntilMs = 0L

    // Global bridge ping markers used by KeyAutomator's Baseplate executor test
    @Volatile var lastAnyBridgePingMs: Long = 0L
    @Volatile var lastPingUser: String = ""
    @Volatile var lastPingPlaceId: String = ""

    fun loadConfig(ctx: Context): RejoinConfig {
        val p = PilotCore.prefs(ctx)
        return RejoinConfig(
            enabled = p.getBoolean("rj_enabled", true),
            autoStartOnBoot = p.getBoolean("rj_boot", true),
            targetPlaceId = p.getString("rj_place", "8304191830").orEmpty(),
            privateServerLinks = p.getString("rj_vip", "").orEmpty(),
            fpsOptimization = p.getBoolean("rj_fps", true),
            render3dOff = p.getBoolean("rj_3doff", false),
            autoGetNousigiKey = p.getBoolean("rj_auto_nousigi", false),
            luarmorUrl = p.getString("rj_luarmor_url", "https://ads.luarmor.net/get_key?for=-dkAHbyOeBCOT").orEmpty(),
            lvEmail = p.getString("rj_lv_email", "").orEmpty(),
            lvPassword = p.getString("rj_lv_pass", "").orEmpty(),
            nousigiKeyPath = p.getString("rj_nousigi_key_path", "").orEmpty(),
            autoGetDeltaKey = p.getBoolean("rj_auto_delta", false),
            deltaPlatoUrl = p.getString("rj_delta_url", "").orEmpty(),
            pingTimeoutSec = p.getInt("rj_ping_timeout", 95),
            staggerDelaySec = p.getInt("rj_stagger_sec", 13)
        )
    }

    fun saveConfig(ctx: Context, cfg: RejoinConfig) {
        PilotCore.prefs(ctx).edit()
            .putBoolean("rj_enabled", cfg.enabled)
            .putBoolean("rj_boot", cfg.autoStartOnBoot)
            .putString("rj_place", cfg.targetPlaceId.trim())
            .putString("rj_vip", cfg.privateServerLinks.trim())
            .putBoolean("rj_fps", cfg.fpsOptimization)
            .putBoolean("rj_3doff", cfg.render3dOff)
            .putBoolean("rj_auto_nousigi", cfg.autoGetNousigiKey)
            .putString("rj_luarmor_url", cfg.luarmorUrl.trim())
            .putString("rj_lv_email", cfg.lvEmail.trim())
            .putString("rj_lv_pass", cfg.lvPassword)
            .putString("rj_nousigi_key_path", cfg.nousigiKeyPath.trim())
            .putBoolean("rj_auto_delta", cfg.autoGetDeltaKey)
            .putString("rj_delta_url", cfg.deltaPlatoUrl.trim())
            .putInt("rj_ping_timeout", cfg.pingTimeoutSec)
            .putInt("rj_stagger_sec", cfg.staggerDelaySec)
            .apply()
        deployBridgeLua(ctx, cfg)
    }

    fun discoverClones(): List<CloneSlot> {
        val pkgs = SuShell.execLines("pm list packages | grep 'com.roblox.clien' | sed 's/package://' | sort")
            .filter { it.startsWith("com.roblox.clien") }
        pkgs.forEach { pkg -> slots.putIfAbsent(pkg, CloneSlot(pkg)) }
        return pkgs.mapNotNull { slots[it] }
    }

    /** Picks a logged-in clone for Baseplate executor testing, falling back to the first available clone. */
    fun pickBestTestClone(): CloneSlot? {
        val all = discoverClones()
        return all.firstOrNull {
            it.status != "EMPTY" && it.status != "LOGGED_OUT" && it.status != "REVOKED"
        } ?: all.firstOrNull()
    }

    /** Force-stops a single clone and launches it directly into a specific Place ID (e.g., Baseplate). */
    fun launchSingleCloneToPlace(ctx: Context, pkg: String, placeId: String) {
        deployBridgeLua(ctx, loadConfig(ctx))
        SuShell.exec("am force-stop $pkg")
        runCatching { Thread.sleep(1200) }
        val uri = "roblox://experiences/start?placeId=$placeId"
        SuShell.exec("am start -p $pkg -a android.intent.action.VIEW -d '$uri' >/dev/null 2>&1")
        slots[pkg]?.apply {
            lastLaunchMs = System.currentTimeMillis()
            lastPingMs = 0L
        }
    }

    fun onBridgePing(ctx: Context, user: String, placeId: String, fps: Int): String {
        val now = System.currentTimeMillis()
        lastAnyBridgePingMs = now
        lastPingUser = user
        lastPingPlaceId = placeId

        val matched = slots.values.firstOrNull { it.username.equals(user, ignoreCase = true) }
            ?: slots.values.firstOrNull { it.username.isBlank() }
        if (matched != null) {
            val prevUser = matched.username
            if (prevUser.isNotBlank() && !prevUser.equals(user, ignoreCase = true)) {
                PilotCore.log("[REJOINER] Instant account swap detected on ${matched.pkg}: $prevUser -> $user")
            }
            matched.username = user
            matched.placeId = placeId
            matched.fps = fps
            matched.lastPingMs = now
            if (!matched.paused) matched.status = "ACTIVE"
        }
        val cfg = loadConfig(ctx)
        val want3dOff = cfg.render3dOff && now > screenshot3dHoldUntilMs
        return """{"ok":true,"fpsOpt":${cfg.fpsOptimization},"render3dOff":$want3dOff}"""
    }

    fun hold3dRenderForScreenshot(ms: Long = 5000L) {
        screenshot3dHoldUntilMs = System.currentTimeMillis() + ms
    }

    fun onBridgeKeyError(ctx: Context, user: String, reason: String) {
        PilotCore.log("[REJOINER] Key error reported by $user: $reason")
        val cfg = loadConfig(ctx)
        if (cfg.autoGetNousigiKey && !KeyAutomator.isRunning()) {
            val isBanned = reason.contains("ban", true) || reason.contains("blacklist", true)
            PilotCore.onAlert?.invoke("🚫 Key Error Detected ($user)", "Reason: `$reason` — opening Luarmor in Chrome...")
            KeyAutomator.startNousigiRenewal(ctx, blacklistedReset = isBanned)
        }
    }

    fun onBridgeKick(ctx: Context, user: String, code: String, msg: String) {
        val slot = slots.values.firstOrNull { it.username.equals(user, ignoreCase = true) } ?: return
        slot.lastKickReason = "$code: $msg"
        PilotCore.log("[REJOINER] $user disconnected ($code): $msg")

        if (code == "273" || msg.contains("same account", true)) {
            slot.paused = true
            slot.status = "PAUSED"
            PilotCore.onAlert?.invoke("⏸️ Slot Paused (Error 273)", "**$user** was opened on another device. Use `!resume $user` when done.")
        }
    }

    fun setSlotPaused(userOrPkg: String, paused: Boolean): CloneSlot? {
        val s = slots.values.firstOrNull {
            it.username.equals(userOrPkg, true) || it.pkg.equals(userOrPkg, true)
        } ?: return null
        s.paused = paused
        s.status = if (paused) "PAUSED" else "IDLE"
        return s
    }

    fun forceRescanSlot(ctx: Context, pkg: String) {
        val slot = slots.getOrPut(pkg) { CloneSlot(pkg) }
        val cookie = SessionVault.extractCookie(pkg)
        val live = SessionVault.validateCookie(cookie)
        if (!live.isNullOrBlank()) {
            slot.username = live
            slot.status = "ACTIVE"
            SessionVault.snapshot(ctx, pkg, live)
        } else {
            slot.status = "LOGGED_OUT"
        }
    }

    fun start(ctx: Context) {
        if (!running.compareAndSet(false, true)) return
        val cfg = loadConfig(ctx)
        deployBridgeLua(ctx, cfg)
        discoverClones()

        watchdogThread = Thread {
            PilotCore.log("[REJOINER] Watchdog started (${slots.size} clones found)")
            var cycle = 0
            while (running.get()) {
                runCatching { tickWatchdog(ctx, cycle++) }
                try { Thread.sleep(10_000L) } catch (_: InterruptedException) { break }
            }
        }.apply { isDaemon = true; start() }
    }

    fun stop() {
        running.set(false)
        watchdogThread?.interrupt()
        watchdogThread = null
    }

    private fun tickWatchdog(ctx: Context, cycle: Int) {
        // Pause normal watchdog restarts while KeyAutomator is running a Baseplate key test
        if (KeyAutomator.isRunning()) return

        val cfg = loadConfig(ctx)
        if (!cfg.enabled) return
        val list = discoverClones()
        val now = System.currentTimeMillis()

        if (cycle > 0 && cycle % 180 == 0) {
            list.forEach { SuShell.exec("rm -rf /data/data/${it.pkg}/files/logs/* /data/data/${it.pkg}/cache/* 2>/dev/null") }
        }

        for (slot in list) {
            if (slot.paused) {
                slot.status = "PAUSED"
                continue
            }

            if (cycle % 3 == 0) {
                val cookie = SessionVault.extractCookie(slot.pkg)
                if (cookie.isBlank()) {
                    val (res, oldUser) = SessionVault.tryRestore(ctx, slot.pkg)
                    when (res) {
                        SessionVault.RestoreResult.RESTORED -> {
                            slot.username = oldUser
                            slot.status = "ACTIVE"
                            queueStaggeredLaunch(ctx, slot, "Vault restored")
                        }
                        SessionVault.RestoreResult.REVOKED -> {
                            slot.status = "REVOKED"
                            PilotCore.onAlert?.invoke("🔒 Session Revoked", "**$oldUser** (`${slot.pkg}`) password changed or signed out.")
                        }
                        SessionVault.RestoreResult.NO_BACKUP -> slot.status = "LOGGED_OUT"
                    }
                    continue
                } else if (slot.username.isNotBlank() && cycle % 30 == 0) {
                    SessionVault.snapshot(ctx, slot.pkg, slot.username)
                }
            }

            val pid = SuShell.exec("pidof ${slot.pkg}").trim()
            if (pid.isBlank()) {
                if (now - slot.lastLaunchMs > 30_000L && slot.status != "LOGGED_OUT" && slot.status != "EMPTY" && slot.status != "REVOKED") {
                    slot.status = "OFFLINE"
                    queueStaggeredLaunch(ctx, slot, "Process closed")
                }
                continue
            }

            val sinceLaunch = now - slot.lastLaunchMs
            val sincePing = if (slot.lastPingMs == 0L) sinceLaunch else (now - slot.lastPingMs)
            if (sinceLaunch > cfg.pingTimeoutSec * 1000L && sincePing > cfg.pingTimeoutSec * 1000L) {
                if (isDeltaKeyMissingOnScreen()) {
                    slot.status = "NO_DELTA_KEY"
                    PilotCore.log("[REJOINER] ${slot.pkg} alive with no ping -> NO DELTA KEY")
                    if (cfg.autoGetDeltaKey && !KeyAutomator.isRunning()) {
                        PilotCore.onAlert?.invoke("🔑 Delta Key Missing", "Testing executor on Baseplate & starting Delta key helper...")
                        KeyAutomator.startDeltaRenewal(ctx)
                    }
                } else {
                    slot.status = "FROZEN"
                    PilotCore.log("[REJOINER] ${slot.pkg} stopped pinging (frozen) -> restarting")
                    queueStaggeredLaunch(ctx, slot, "Frozen / no executor ping")
                }
            }
        }
    }

    private fun isDeltaKeyMissingOnScreen(): Boolean {
        SuShell.exec("uiautomator dump /sdcard/pilot_ui.xml >/dev/null 2>&1")
        val xml = SuShell.exec("cat /sdcard/pilot_ui.xml 2>/dev/null").lowercase()
        return xml.contains("receive key") || xml.contains("enter key") || xml.contains("delta key")
    }

    fun queueStaggeredLaunch(ctx: Context, slot: CloneSlot, reason: String) {
        launchExec.execute {
            val cfg = loadConfig(ctx)
            PilotCore.log("[REJOINER] Launching ${slot.pkg} ($reason)")
            SuShell.exec("am force-stop ${slot.pkg}")
            Thread.sleep(1200)

            val deepLink = buildLaunchUri(cfg, slot)
            if (deepLink.isNotBlank()) {
                SuShell.exec("am start -p ${slot.pkg} -a android.intent.action.VIEW -d '$deepLink' >/dev/null 2>&1")
            } else {
                SuShell.exec("monkey -p ${slot.pkg} -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1")
            }
            slot.lastLaunchMs = System.currentTimeMillis()
            slot.lastPingMs = 0L
            Thread.sleep((cfg.staggerDelaySec.coerceIn(5, 30)) * 1000L)
        }
    }

    fun killAllAndStaggeredRejoin(ctx: Context, reason: String = "Manual rejoin") {
        val list = discoverClones()
        PilotCore.log("[REJOINER] Killing all ${list.size} clones ($reason)...")
        list.forEach { SuShell.exec("am force-stop ${it.pkg}") }
        list.filter { !it.paused && it.status != "LOGGED_OUT" && it.status != "REVOKED" && it.status != "EMPTY" }
            .forEach { queueStaggeredLaunch(ctx, it, reason) }
    }

    private fun buildLaunchUri(cfg: RejoinConfig, slot: CloneSlot): String {
        val vips = cfg.privateServerLinks.split(",", "\n").map { it.trim() }.filter { it.isNotBlank() }
        if (vips.isNotEmpty()) {
            val pick = vips[vipIndex++ % vips.size]
            if (pick.startsWith("http", true)) return pick
        }
        val pid = cfg.targetPlaceId.ifBlank { slot.placeId }
        return if (pid.all { it.isDigit() } && pid.isNotBlank()) {
            "roblox://experiences/start?placeId=$pid"
        } else ""
    }

    fun deployBridgeLua(ctx: Context, cfg: RejoinConfig) {
        val lua = """
            -- Pilot v1.4.2 Bridge (Auto-Ping + Blacklist Detection + FPS Optimizer + Anti-AFK + PilotSnap)
            if getgenv().__PILOT_BRIDGE_V14 then return end
            getgenv().__PILOT_BRIDGE_V14 = true

            local Players = game:GetService("Players")
            local RunService = game:GetService("RunService")
            local GuiService = game:GetService("GuiService")
            local VirtualUser = game:GetService("VirtualUser")
            local Lighting = game:GetService("Lighting")
            local HttpService = game:GetService("HttpService")
            local CoreGui = game:GetService("CoreGui")

            local req = (syn and syn.request) or http_request or request
            if not req then return end

            repeat task.wait(0.5) until Players.LocalPlayer
            local lp = Players.LocalPlayer

            local function enc(s) return HttpService:UrlEncode(tostring(s or "")) end

            getgenv().PilotSnap = function()
                pcall(function()
                    req({ Url = "http://127.0.0.1:8765/ss?user=" .. enc(lp.Name), Method = "GET" })
                end)
            end

            lp.Idled:Connect(function()
                pcall(function()
                    VirtualUser:CaptureController()
                    VirtualUser:ClickButton2(Vector2.new())
                end)
            end)

            local fpsApplied = false
            local function applyFpsBoost()
                if fpsApplied then return end
                fpsApplied = true
                pcall(function()
                    settings().Rendering.QualityLevel = Enum.QualityLevel.Level01
                    Lighting.GlobalShadows = false
                    Lighting.FogEnd = 9e9
                    Lighting.Brightness = 1
                    for _, v in ipairs(Lighting:GetChildren()) do
                        if v:IsA("PostEffect") or v:IsA("Atmosphere") then v.Enabled = false end
                    end
                    for _, d in ipairs(workspace:GetDescendants()) do
                        if d:IsA("ParticleEmitter") or d:IsA("Trail") or d:IsA("Beam") then
                            d.Enabled = false
                        elseif d:IsA("BasePart") then
                            d.CastShadow = false
                        end
                    end
                end)
            end
            if ${cfg.fpsOptimization} then applyFpsBoost() end

            local function checkAuthMessage(msg)
                local low = string.lower(tostring(msg or ""))
                if string.find(low, "blacklisted") or string.find(low, "key has been banned") or string.find(low, "invalid key") or string.find(low, "key expired") then
                    pcall(function()
                        req({ Url = "http://127.0.0.1:8765/keyerr?user=" .. enc(lp.Name) .. "&reason=" .. enc(msg), Method = "GET" })
                    end)
                end
            end

            GuiService.ErrorMessageChanged:Connect(function(msg)
                local code = ""
                pcall(function() code = tostring(GuiService:GetErrorCode().Value) end)
                checkAuthMessage(msg)
                pcall(function()
                    req({ Url = "http://127.0.0.1:8765/kick?user=" .. enc(lp.Name) .. "&code=" .. enc(code) .. "&msg=" .. enc(msg), Method = "GET" })
                end)
            end)

            task.spawn(function()
                while true do
                    pcall(function()
                        local pg = CoreGui:FindFirstChild("RobloxPromptGui")
                        if pg then
                            for _, obj in ipairs(pg:GetDescendants()) do
                                if obj:IsA("TextLabel") and obj.Text ~= "" then
                                    checkAuthMessage(obj.Text)
                                end
                            end
                        end
                    end)
                    task.wait(6)
                end
            end)

            local frames = 0
            RunService.RenderStepped:Connect(function() frames += 1 end)

            task.spawn(function()
                while true do
                    local fps = math.floor(frames / 8)
                    frames = 0
                    local ok, res = pcall(function()
                        return req({
                            Url = "http://127.0.0.1:8765/ping?user=" .. enc(lp.Name) .. "&placeId=" .. enc(game.PlaceId) .. "&fps=" .. fps,
                            Method = "GET"
                        })
                    end)
                    if ok and res and res.Body then
                        pcall(function()
                            local data = HttpService:JSONDecode(res.Body)
                            if data.fpsOpt then applyFpsBoost() end
                            pcall(function() RunService:Set3dRenderingEnabled(not data.render3dOff) end)
                        end)
                    end
                    task.wait(8)
                end
            end)
        """.trimIndent()

        val tmp = File(ctx.cacheDir, "pilot_bridge.lua")
        FileOutputStream(tmp).use { it.write(lua.toByteArray(Charsets.UTF_8)) }
        SuShell.exec(
            "for d in /storage/emulated/0/Delta/Autoexecute /storage/emulated/0/Delta/AutoExcecute; do " +
            "mkdir -p \"${'$'}d\" && cp '${tmp.absolutePath}' \"${'$'}d/pilot_bridge.lua\" && chmod 666 \"${'$'}d/pilot_bridge.lua\"; " +
            "done"
        )
        tmp.delete()
    }
}