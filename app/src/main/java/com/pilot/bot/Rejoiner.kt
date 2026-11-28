package com.pilot.bot

import android.content.Context
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

data class FamousGame(val name: String, val placeId: String)

data class CloneSlot(
    val pkg: String,
    @Volatile var username: String = "",
    @Volatile var status: String = "EMPTY", // ACTIVE, FROZEN, NO_DELTA_KEY, LOGGED_OUT, PAUSED, REVOKED, EMPTY, COOLDOWN
    @Volatile var placeId: String = "",
    @Volatile var jobId: String = "",
    @Volatile var gameState: String = "Idle",
    @Volatile var fps: Int = 0,
    @Volatile var pingMs: Int = 0,
    @Volatile var memMb: Int = 0,
    @Volatile var lastPingMs: Long = 0L,
    @Volatile var lastLaunchMs: Long = 0L,
    @Volatile var teleportGraceUntilMs: Long = 0L,
    @Volatile var lagGraceUntilMs: Long = 0L,
    @Volatile var cooldownUntilMs: Long = 0L,
    @Volatile var consecutiveFails: Int = 0,
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
    val nousigiKeyPath: String = "",
    val autoGetDeltaKey: Boolean = false,
    val deltaPlatoUrl: String = "",
    val startupGraceSec: Int = 210,
    val pingTimeoutSec: Int = 180,
    val staggerDelaySec: Int = 14
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
    private val forceStopped = AtomicBoolean(false)
    private val sessionGeneration = AtomicLong(0L)
    private val launchExec = Executors.newSingleThreadExecutor()
    private var watchdogThread: Thread? = null

    @Volatile private var vipIndex = 0
    @Volatile private var screenshot3dHoldUntilMs = 0L
    @Volatile var pendingLuaCaptureUser: String = ""

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
            nousigiKeyPath = p.getString("rj_nousigi_key_path", "").orEmpty(),
            autoGetDeltaKey = p.getBoolean("rj_auto_delta", false),
            deltaPlatoUrl = p.getString("rj_delta_url", "").orEmpty(),
            startupGraceSec = p.getInt("rj_startup_grace", 210).coerceAtLeast(180),
            pingTimeoutSec = p.getInt("rj_ping_timeout", 180).coerceAtLeast(150),
            staggerDelaySec = p.getInt("rj_stagger_sec", 14).coerceAtLeast(10)
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
            .putString("rj_nousigi_key_path", cfg.nousigiKeyPath.trim())
            .putBoolean("rj_auto_delta", cfg.autoGetDeltaKey)
            .putString("rj_delta_url", cfg.deltaPlatoUrl.trim())
            .putInt("rj_startup_grace", cfg.startupGraceSec)
            .putInt("rj_ping_timeout", cfg.pingTimeoutSec)
            .putInt("rj_stagger_sec", cfg.staggerDelaySec)
            .apply()
        deployBridgeLua(ctx, cfg)
    }

    fun isBotActive(gen: Long? = null): Boolean {
        if (forceStopped.get() || !PilotCore.isRunning) return false
        if (gen != null && gen != sessionGeneration.get()) return false
        return true
    }

    /** Interruptible sleep that aborts within 500ms if STOP BOT is pressed. */
    private fun sleepWhileActive(ms: Long, gen: Long): Boolean {
        val end = System.currentTimeMillis() + ms
        while (System.currentTimeMillis() < end) {
            if (!isBotActive(gen)) return false
            val step = (end - System.currentTimeMillis()).coerceIn(1L, 500L)
            try {
                Thread.sleep(step)
            } catch (_: InterruptedException) {
                return false
            }
        }
        return isBotActive(gen)
    }

    fun discoverClones(): List<CloneSlot> {
        val pkgs = SuShell.execLines("pm list packages | grep 'com.roblox.clien' | sed 's/package://' | sort")
            .filter { it.startsWith("com.roblox.clien") }
        pkgs.forEach { pkg -> slots.putIfAbsent(pkg, CloneSlot(pkg)) }
        return pkgs.mapNotNull { slots[it] }
    }

    /**
     * Strict session gate: returns true ONLY if the clone has an active logged-in Roblox account
     * (or recovers one from SessionVault). Never returns true for EMPTY, LOGGED_OUT, or REVOKED slots.
     */
    fun hasLoggedInAccount(ctx: Context, pkg: String): Boolean {
        val slot = slots.getOrPut(pkg) { CloneSlot(pkg) }
        if (slot.status == "REVOKED") return false

        val cookie = SessionVault.extractCookie(pkg)
        val diskUser = PilotCore.readDiskUsername(pkg)

        if (cookie.length >= 40 && diskUser.isNotEmpty()) {
            if (slot.username != diskUser) slot.username = diskUser
            if (!slot.paused && (slot.status == "EMPTY" || slot.status == "LOGGED_OUT")) {
                slot.status = "ACTIVE"
            }
            return true
        }

        val (res, restoredUser) = SessionVault.tryRestore(ctx, pkg)
        return when (res) {
            SessionVault.RestoreResult.RESTORED -> {
                slot.username = restoredUser
                slot.status = "ACTIVE"
                true
            }
            SessionVault.RestoreResult.REVOKED -> {
                slot.status = "REVOKED"
                false
            }
            SessionVault.RestoreResult.NO_BACKUP -> {
                val histUser = PilotCore.getSlotHistoryUser(ctx, pkg)
                if (histUser.isNotEmpty()) {
                    slot.username = histUser
                    slot.status = "LOGGED_OUT"
                } else {
                    slot.username = ""
                    slot.status = "EMPTY"
                }
                false
            }
        }
    }

    /** Picks ONLY a logged-in clone for Baseplate executor testing. Never falls back to empty/logged-out clones. */
    fun pickBestTestClone(ctx: Context): CloneSlot? {
        PilotCore.scanRbx(ctx)
        val all = discoverClones()
        val eligible = all.filter { !it.paused && hasLoggedInAccount(ctx, it.pkg) }
        return eligible.firstOrNull { it.status == "ACTIVE" } ?: eligible.firstOrNull()
    }

    /** Launches a single logged-in clone into a specific Place ID. Refuses to open empty/logged-out clones. */
    fun launchSingleCloneToPlace(ctx: Context, pkg: String, placeId: String): Boolean {
        if (!hasLoggedInAccount(ctx, pkg)) {
            PilotCore.log("[REJOINER] Blocked launch of $pkg (Logged Out / Empty)")
            return false
        }
        deployBridgeLua(ctx, loadConfig(ctx))
        SuShell.exec("am force-stop $pkg")
        runCatching { Thread.sleep(1000) }
        val uri = "roblox://experiences/start?placeId=$placeId"
        SuShell.exec("am start -p $pkg -a android.intent.action.VIEW -d '$uri' >/dev/null 2>&1")
        slots[pkg]?.apply {
            lastLaunchMs = System.currentTimeMillis()
            lastPingMs = 0L
            lagGraceUntilMs = 0L
        }
        return true
    }

    fun onBridgePing(
        ctx: Context,
        user: String,
        placeId: String,
        fps: Int,
        pingMs: Int = 0,
        memMb: Int = 0,
        jobId: String = "",
        state: String = ""
    ): String {
        val now = System.currentTimeMillis()
        lastAnyBridgePingMs = now
        lastPingUser = user
        lastPingPlaceId = placeId

        val matched = slots.values.firstOrNull { it.username.equals(user, ignoreCase = true) }
            ?: slots.values.firstOrNull { it.username.isBlank() }
        if (matched != null) {
            val prevUser = matched.username
            if (prevUser.isNotBlank() && !prevUser.equals(user, ignoreCase = true)) {
                PilotCore.log("[REJOINER] Account swap detected on ${matched.pkg}: $prevUser -> $user")
            }
            matched.username = user
            matched.placeId = placeId
            if (jobId.isNotBlank()) matched.jobId = jobId
            if (state.isNotBlank()) matched.gameState = state
            matched.fps = fps
            if (pingMs > 0) matched.pingMs = pingMs
            if (memMb > 0) matched.memMb = memMb
            matched.lastPingMs = now
            matched.lagGraceUntilMs = 0L
            matched.consecutiveFails = 0
            if (!matched.paused) matched.status = "ACTIVE"
        }

        val cfg = loadConfig(ctx)
        val want3dOff = cfg.render3dOff && now > screenshot3dHoldUntilMs
        val wantCapture = pendingLuaCaptureUser.isNotEmpty() && pendingLuaCaptureUser.equals(user, ignoreCase = true)
        if (wantCapture) {
            pendingLuaCaptureUser = ""
        }
        return """{"ok":true,"fpsOpt":${cfg.fpsOptimization},"render3dOff":$want3dOff,"capture":$wantCapture}"""
    }

    fun onBridgeTeleport(user: String) {
        val slot = slots.values.firstOrNull { it.username.equals(user, ignoreCase = true) } ?: return
        slot.teleportGraceUntilMs = System.currentTimeMillis() + 65_000L
        slot.gameState = "Teleporting"
        PilotCore.log("[REJOINER] $user teleporting — granted 65s freeze grace window")
    }

    fun hold3dRenderForScreenshot(ms: Long = 6000L) {
        screenshot3dHoldUntilMs = System.currentTimeMillis() + ms
    }

    fun requestLuaNativeCapture(username: String) {
        hold3dRenderForScreenshot(7000L)
        pendingLuaCaptureUser = username.trim()
    }

    fun onBridgeKick(ctx: Context, user: String, code: String, msg: String) {
        val slot = slots.values.firstOrNull { it.username.equals(user, ignoreCase = true) } ?: return
        slot.lastKickReason = "$code: $msg"
        slot.gameState = "Disconnected ($code)"
        PilotCore.log("[REJOINER] $user disconnected ($code): $msg")

        if (!isBotActive()) return

        when {
            code == "273" || msg.contains("same account", true) -> {
                slot.paused = true
                slot.status = "PAUSED"
                savePausedState(ctx)
                PilotCore.onAlert?.invoke(
                    "⏸️ Slot Paused (Error 273)",
                    "**$user** was opened on another device. Use `!resume $user` when done."
                )
            }
            code == "268" -> {
                slot.cooldownUntilMs = System.currentTimeMillis() + 600_000L // 10 min cooldown
                slot.status = "COOLDOWN"
                SuShell.exec("am force-stop ${slot.pkg} && rm -rf /data/data/${slot.pkg}/cache/* 2>/dev/null")
                PilotCore.onAlert?.invoke(
                    "🛡️ Client Kick Cooldown (Error 268)",
                    "**$user** (`${slot.pkg}`) received Error 268. Cleared cache and paused for 10 minutes before rejoining."
                )
            }
            code == "267" -> {
                PilotCore.onAlert?.invoke(
                    "⚠️ Game/Script Kick (Error 267)",
                    "**$user** kicked: `$msg`. Rejoining after 8s..."
                )
                queueStaggeredLaunch(ctx, slot, "Error 267 kick")
            }
            code in setOf("277", "279", "260", "529") -> {
                queueStaggeredLaunch(ctx, slot, "Network/Server drop ($code)")
            }
        }
    }

    private fun savePausedState(ctx: Context) {
        val pausedPkgs = slots.values.filter { it.paused }.joinToString(",") { it.pkg }
        PilotCore.prefs(ctx).edit().putString("rj_paused_pkgs", pausedPkgs).apply()
    }

    private fun restorePausedState(ctx: Context) {
        val saved = PilotCore.prefs(ctx).getString("rj_paused_pkgs", "").orEmpty()
        if (saved.isBlank()) return
        saved.split(",").filter { it.isNotBlank() }.forEach { pkg ->
            slots.getOrPut(pkg) { CloneSlot(pkg) }.apply {
                paused = true
                status = "PAUSED"
            }
        }
    }

    fun setSlotPaused(ctx: Context, userOrPkg: String, paused: Boolean): CloneSlot? {
        val s = slots.values.firstOrNull {
            it.username.equals(userOrPkg, true) || it.pkg.equals(userOrPkg, true)
        } ?: return null
        s.paused = paused
        s.cooldownUntilMs = 0L
        s.status = if (paused) "PAUSED" else "ACTIVE"
        savePausedState(ctx)
        return s
    }

    fun start(ctx: Context) {
        forceStopped.set(false)
        val gen = sessionGeneration.incrementAndGet()
        if (!running.compareAndSet(false, true)) return

        val cfg = loadConfig(ctx)
        deployBridgeLua(ctx, cfg)
        restorePausedState(ctx)
        PilotCore.scanRbx(ctx)
        discoverClones()

        watchdogThread = Thread {
            PilotCore.log("[REJOINER] Watchdog started (gen=$gen, ${slots.size} clones found)")
            var cycle = 0
            while (running.get() && isBotActive(gen)) {
                runCatching { tickWatchdog(ctx, cycle++, gen) }
                if (!sleepWhileActive(10_000L, gen)) break
            }
            PilotCore.log("[REJOINER] Watchdog exited cleanly (gen=$gen)")
        }.apply { isDaemon = true; start() }
    }

    /** Hard stop: immediately invalidates all queued launches and interrupts the watchdog thread. */
    fun stop() {
        forceStopped.set(true)
        sessionGeneration.incrementAndGet()
        running.set(false)
        watchdogThread?.interrupt()
        watchdogThread = null
        PilotCore.log("[REJOINER] Hard-stopped — all queued rejoins cancelled")
    }

    private fun getFreeRamMb(): Int {
        val out = SuShell.exec("free -m | awk '/Mem:/ {print \$4}'").trim()
        return out.toIntOrNull() ?: 1024
    }

    /** Reads kernel CPU jiffies (utime + stime) from /proc/<pid>/stat to distinguish script lag from a true deadlock. */
    private fun readProcessCpuTicks(pid: String): Long {
        val stat = SuShell.exec("cat /proc/$pid/stat 2>/dev/null").trim()
        val afterComm = stat.substringAfterLast(')', "").trim()
        if (afterComm.isEmpty()) return -1L
        val parts = afterComm.split(Regex("\\s+"))
        // After ')': index 0=state, 11=utime (field 14), 12=stime (field 15)
        val utime = parts.getOrNull(11)?.toLongOrNull() ?: return -1L
        val stime = parts.getOrNull(12)?.toLongOrNull() ?: return -1L
        return utime + stime
    }

    /** Returns true if the process is still burning CPU ticks (i.e. executing heavy scripts / lagging, NOT frozen). */
    private fun isProcessActivelyRunningCpu(pid: String, gen: Long): Boolean {
        val t1 = readProcessCpuTicks(pid)
        if (t1 < 0L) return false
        if (!sleepWhileActive(4500L, gen)) return true
        val t2 = readProcessCpuTicks(pid)
        if (t2 < 0L) return false
        return (t2 - t1) > 0L
    }

    private fun tickWatchdog(ctx: Context, cycle: Int, gen: Long) {
        if (!isBotActive(gen) || KeyAutomator.isRunning()) return

        val cfg = loadConfig(ctx)
        if (!cfg.enabled) return
        val list = discoverClones()
        val now = System.currentTimeMillis()

        // Self-heal bridge script & auto-vacuum caches if storage/RAM gets tight
        if (cycle > 0 && cycle % 12 == 0) {
            val bridgeExists = SuShell.exec("[ -f /storage/emulated/0/Delta/Autoexecute/pilot_bridge.lua ] && echo OK").trim()
            if (bridgeExists != "OK") deployBridgeLua(ctx, cfg)
        }
        if (cycle > 0 && (cycle % 180 == 0 || getFreeRamMb() < 320)) {
            list.forEach { SuShell.exec("rm -rf /data/data/${it.pkg}/files/logs/* /data/data/${it.pkg}/cache/* 2>/dev/null") }
            KeyAutomator.cleanBakFiles()
        }

        for (slot in list) {
            if (!isBotActive(gen)) return

            if (slot.paused) {
                slot.status = "PAUSED"
                continue
            }

            if (slot.cooldownUntilMs > now) {
                slot.status = "COOLDOWN"
                continue
            }

            val prevStatus = slot.status
            if (!hasLoggedInAccount(ctx, slot.pkg)) {
                if (slot.status == "REVOKED" && prevStatus != "REVOKED") {
                    PilotCore.onAlert?.invoke("🔒 Session Revoked", "**${slot.username}** (`${slot.pkg}`) password changed or signed out.")
                }
                continue
            }

            if (slot.username.isNotBlank() && cycle % 30 == 0) {
                SessionVault.snapshot(ctx, slot.pkg, slot.username)
            }

            val pid = SuShell.exec("pidof ${slot.pkg}").trim().split(Regex("\\s+")).firstOrNull().orEmpty()
            if (pid.isBlank()) {
                val backoffMs = (30_000L + (slot.consecutiveFails.coerceAtMost(4) * 15_000L))
                if (now - slot.lastLaunchMs > backoffMs) {
                    slot.status = "OFFLINE"
                    slot.gameState = "Offline"
                    queueStaggeredLaunch(ctx, slot, "Process closed", gen)
                }
                continue
            }

            // Respect teleport and active-script-lag grace windows
            if (now < slot.teleportGraceUntilMs || now < slot.lagGraceUntilMs) continue

            val sinceLaunch = now - slot.lastLaunchMs
            val isBootingScript = (slot.lastPingMs == 0L)
            val requiredGraceMs = if (isBootingScript) {
                cfg.startupGraceSec * 1000L
            } else {
                cfg.pingTimeoutSec * 1000L
            }
            val sincePing = if (isBootingScript) sinceLaunch else (now - slot.lastPingMs)

            if (sinceLaunch > requiredGraceMs && sincePing > requiredGraceMs) {
                if (isDeltaKeyMissingOnScreen()) {
                    slot.status = "NO_DELTA_KEY"
                    slot.gameState = "Needs Delta Key"
                    PilotCore.log("[REJOINER] ${slot.pkg} alive with no ping -> NO DELTA KEY")
                    if (cfg.autoGetDeltaKey && !KeyAutomator.isRunning() && isBotActive(gen)) {
                        PilotCore.onAlert?.invoke("🔑 Delta Key Missing", "Testing executor on Baseplate & starting Delta key helper...")
                        KeyAutomator.startDeltaRenewal(ctx)
                    }
                    continue
                }

                // Check kernel CPU ticks before restarting: if CPU is active, it's executing scripts / lagging, NOT frozen
                if (isProcessActivelyRunningCpu(pid, gen)) {
                    slot.lagGraceUntilMs = System.currentTimeMillis() + 60_000L
                    slot.gameState = "Script Lag (Grace +60s)"
                    PilotCore.log("[REJOINER] ${slot.pkg} (${slot.username}) lagging while executing script — extending grace 60s")
                    continue
                }

                slot.consecutiveFails += 1
                slot.status = "FROZEN"
                slot.gameState = "Frozen"
                PilotCore.log("[REJOINER] ${slot.pkg} (${slot.username}) deadlocked (0 CPU ticks + no ping) -> restarting")
                queueStaggeredLaunch(ctx, slot, "Frozen / deadlocked", gen)
            }
        }
    }

    private fun isDeltaKeyMissingOnScreen(): Boolean {
        SuShell.exec("uiautomator dump /sdcard/pilot_ui.xml >/dev/null 2>&1")
        val xml = SuShell.exec("cat /sdcard/pilot_ui.xml 2>/dev/null").lowercase()
        return xml.contains("receive key") || xml.contains("enter key") || xml.contains("delta key")
    }

    fun queueStaggeredLaunch(ctx: Context, slot: CloneSlot, reason: String, expectedGen: Long = sessionGeneration.get()) {
        launchExec.execute {
            if (!isBotActive(expectedGen)) return@execute
            if (slot.paused || !hasLoggedInAccount(ctx, slot.pkg)) {
                PilotCore.log("[REJOINER] Skipped ${slot.pkg} ($reason — Logged Out / Empty / Paused)")
                return@execute
            }

            // RAM-aware launch check: wait briefly if free RAM is critically low (<280MB)
            var ramChecks = 0
            while (getFreeRamMb() < 280 && ramChecks < 4) {
                if (!sleepWhileActive(3000L, expectedGen)) return@execute
                ramChecks++
            }

            if (!isBotActive(expectedGen)) return@execute
            val cfg = loadConfig(ctx)
            val launchStart = System.currentTimeMillis()
            PilotCore.log("[REJOINER] Launching ${slot.pkg} (${slot.username}) ($reason)")
            SuShell.exec("am force-stop ${slot.pkg}")
            if (!sleepWhileActive(1200L, expectedGen)) return@execute

            val deepLink = buildLaunchUri(cfg, slot)
            if (deepLink.isNotBlank()) {
                SuShell.exec("am start -p ${slot.pkg} -a android.intent.action.VIEW -d '$deepLink' >/dev/null 2>&1")
            } else {
                SuShell.exec("monkey -p ${slot.pkg} -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1")
            }
            slot.lastLaunchMs = System.currentTimeMillis()
            slot.lastPingMs = 0L
            slot.lagGraceUntilMs = 0L
            slot.gameState = "Loading Scripts"

            // Ping-aware stagger: wait minimum staggerDelaySec, and up to 32s until this slot sends its initial /ping
            val minDelayMs = (cfg.staggerDelaySec.coerceIn(10, 30)) * 1000L
            if (!sleepWhileActive(minDelayMs, expectedGen)) return@execute
            val extraDeadline = launchStart + 32_000L
            while (System.currentTimeMillis() < extraDeadline && slot.lastPingMs < launchStart) {
                if (!sleepWhileActive(1500L, expectedGen)) return@execute
            }
        }
    }

    fun killAllAndStaggeredRejoin(ctx: Context, reason: String = "Manual rejoin") {
        val gen = sessionGeneration.get()
        PilotCore.scanRbx(ctx)
        val eligible = discoverClones().filter { !it.paused && hasLoggedInAccount(ctx, it.pkg) }
        PilotCore.log("[REJOINER] Rejoining ${eligible.size} active clone(s) ($reason)...")
        eligible.forEach { SuShell.exec("am force-stop ${it.pkg}") }
        eligible.forEach { queueStaggeredLaunch(ctx, it, reason, gen) }
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
            -- Pilot v1.4.6 Bridge (Immediate Handshake + Chunked FPS Opt + Telemetry + Teleport Grace + CaptureService)
            if getgenv().__PILOT_BRIDGE_V146 then return end
            getgenv().__PILOT_BRIDGE_V146 = true

            local Players = game:GetService("Players")
            local RunService = game:GetService("RunService")
            local GuiService = game:GetService("GuiService")
            local VirtualUser = game:GetService("VirtualUser")
            local Lighting = game:GetService("Lighting")
            local HttpService = game:GetService("HttpService")
            local Stats = game:GetService("Stats")

            local req = (syn and syn.request) or http_request or request
            if not req then return end

            repeat task.wait(0.5) until Players.LocalPlayer
            local lp = Players.LocalPlayer

            local function enc(s) return HttpService:UrlEncode(tostring(s or "")) end

            -- Immediate handshake ping BEFORE heavy scripts load so Pilot knows this clone booted cleanly
            pcall(function()
                req({
                    Url = "http://127.0.0.1:8765/ping?user=" .. enc(lp.Name)
                        .. "&placeId=" .. enc(game.PlaceId)
                        .. "&jobId=" .. enc(game.JobId)
                        .. "&fps=30&ping=0&mem=0&state=Loaded",
                    Method = "GET"
                })
            end)

            getgenv().PilotSnap = function()
                pcall(function()
                    pcall(function()
                        local cs = game:GetService("CaptureService")
                        cs:CaptureScreenshot(function() end)
                    end)
                    req({ Url = "http://127.0.0.1:8765/ss?user=" .. enc(lp.Name), Method = "GET" })
                end)
            end

            lp.OnTeleport:Connect(function()
                pcall(function()
                    req({ Url = "http://127.0.0.1:8765/teleport?user=" .. enc(lp.Name), Method = "GET" })
                end)
            end)

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
                task.spawn(function()
                    pcall(function()
                        settings().Rendering.QualityLevel = Enum.QualityLevel.Level01
                        Lighting.GlobalShadows = false
                        Lighting.FogEnd = 9e9
                        Lighting.Brightness = 1
                        for _, v in ipairs(Lighting:GetChildren()) do
                            if v:IsA("PostEffect") or v:IsA("Atmosphere") then v.Enabled = false end
                        end
                        local list = workspace:GetDescendants()
                        for i = 1, #list do
                            local d = list[i]
                            if d:IsA("ParticleEmitter") or d:IsA("Trail") or d:IsA("Beam") then
                                d.Enabled = false
                            elseif d:IsA("BasePart") then
                                d.CastShadow = false
                            end
                            if i % 200 == 0 then task.wait() end
                        end
                    end)
                end)
            end
            if ${cfg.fpsOptimization} then applyFpsBoost() end

            GuiService.ErrorMessageChanged:Connect(function(msg)
                local code = ""
                pcall(function() code = tostring(GuiService:GetErrorCode().Value) end)
                pcall(function()
                    req({ Url = "http://127.0.0.1:8765/kick?user=" .. enc(lp.Name) .. "&code=" .. enc(code) .. "&msg=" .. enc(msg), Method = "GET" })
                end)
            end)

            local frames = 0
            RunService.RenderStepped:Connect(function() frames += 1 end)

            task.spawn(function()
                local cycle = 0
                while true do
                    cycle += 1
                    local fps = math.floor(frames / 8)
                    frames = 0
                    local pingMs = 0
                    local memMb = 0
                    pcall(function() pingMs = math.floor(Stats.Network.ServerStatsItem["Data Ping"]:GetValue()) end)
                    pcall(function() memMb = math.floor(Stats:GetTotalMemoryUsageMb()) end)
                    local state = (game.PlaceId == 8304191830) and "Lobby" or "In-Match"

                    local ok, res = pcall(function()
                        return req({
                            Url = "http://127.0.0.1:8765/ping?user=" .. enc(lp.Name)
                                .. "&placeId=" .. enc(game.PlaceId)
                                .. "&jobId=" .. enc(game.JobId)
                                .. "&fps=" .. fps
                                .. "&ping=" .. pingMs
                                .. "&mem=" .. memMb
                                .. "&state=" .. enc(state),
                            Method = "GET"
                        })
                    end)
                    if ok and res and res.Body then
                        pcall(function()
                            local data = HttpService:JSONDecode(res.Body)
                            if data.fpsOpt then applyFpsBoost() end
                            pcall(function() RunService:Set3dRenderingEnabled(not data.render3dOff) end)
                            if data.capture then
                                pcall(function() RunService:Set3dRenderingEnabled(true) end)
                                task.wait(0.25)
                                pcall(function()
                                    local cs = game:GetService("CaptureService")
                                    cs:CaptureScreenshot(function() end)
                                end)
                                req({ Url = "http://127.0.0.1:8765/ss?user=" .. enc(lp.Name), Method = "GET" })
                            end
                        end)
                    end
                    if cycle % 15 == 0 then
                        pcall(function() collectgarbage("collect") end)
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