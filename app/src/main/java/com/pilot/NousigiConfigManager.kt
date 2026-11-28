package com.pilot.bot

import android.content.Context
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

data class NousigiSettingEntry(
    val key: String,
    val type: String, // "bool", "number", "string"
    val valueStr: String,
    val category: String // "Auto Joiners", "Match Flow", "Webhooks", "Other"
)

data class NousigiUserFile(
    val fullPath: String,
    val relPath: String,
    val displayName: String
)

/**
 * Recursive All-JSON User Detector + Discord Nousigi UI Config Editor:
 * - Resolves both Roblox Username and numeric UserId so files in Nousigi Hub/, cache/,
 *   or nested subfolders named by either Username or UserId are detected and migrated.
 * - Parses any Nousigi JSON config into interactive Discord categories & paginated toggles/modals.
 * - Cleans up .bak files automatically after every write.
 */
object NousigiConfigManager {
    private const val WORKSPACE_ROOT = "/storage/emulated/0/Delta/Workspace"
    private const val NOUSIGI_DIR = "/storage/emulated/0/Delta/Workspace/Nousigi Hub"

    private val http = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

    /** Resolves numeric Roblox UserId from local clone files or Roblox Users API. */
    fun resolveUserId(username: String, pkgHint: String = ""): String {
        val cleanUser = username.trim()
        if (cleanUser.isEmpty() || cleanUser.equals("Empty", true)) return ""

        // 1. Check local appStorage.json / shared_prefs first
        val targetDirs = if (pkgHint.isNotBlank()) "/data/data/$pkgHint" else "/data/data/com.roblox.clien*"
        val localDump = SuShell.exec(
            "grep -rhoE '\"UserId\"\\s*:\\s*\"?[0-9]{4,15}\"?|name=\"UserId\"[^>]*>[0-9]{4,15}<' $targetDirs/files/appData/LocalStorage/appStorage.json $targetDirs/shared_prefs/ 2>/dev/null | head -n 5"
        )
        val idRx = Regex("""([0-9]{5,15})""")
        val localId = idRx.find(localDump)?.groupValues?.get(1).orEmpty()
        if (localId.isNotEmpty()) return localId

        // 2. Fallback to Roblox public username -> UserId lookup
        return runCatching {
            val payload = JSONObject()
                .put("usernames", JSONArray().put(cleanUser))
                .put("excludeBannedUsers", false)
                .toString()
            val req = Request.Builder()
                .url("https://users.roblox.com/v1/usernames/users")
                .post(payload.toRequestBody("application/json".toMediaType()))
                .build()
            http.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@use ""
                val data = JSONObject(resp.body?.string().orEmpty()).optJSONArray("data") ?: return@use ""
                if (data.length() > 0) data.getJSONObject(0).optLong("id", 0L).takeIf { it > 0L }?.toString().orEmpty() else ""
            }
        }.getOrDefault("")
    }

    /**
     * Recursively scans EVERY .json file inside /storage/emulated/0/Delta/Workspace/
     * (including Nousigi Hub/, cache/, and any subfolders) matching username or numeric userId
     * in either filename or file contents.
     */
    fun findAllUserJsonFiles(username: String, userId: String = ""): List<NousigiUserFile> {
        val u = username.trim()
        if (u.isEmpty() || u.equals("Empty", true)) return emptyList()
        val uid = userId.ifBlank { resolveUserId(u) }

        val allJsons = SuShell.execLines("find '$WORKSPACE_ROOT' -type f -iname '*.json' 2>/dev/null | sort")
        val matched = mutableListOf<NousigiUserFile>()

        for (fullPath in allJsons) {
            val rel = fullPath.removePrefix("$WORKSPACE_ROOT/")
            val fname = fullPath.substringAfterLast('/')

            // Skip obvious global/system files that don't belong to a specific player
            if (fname.equals("global.json", true) || fname.equals("settings.json", true)) continue

            val nameMatches = fname.contains(u, ignoreCase = true) || (uid.isNotEmpty() && fname.contains(uid))
            var contentMatches = false
            if (!nameMatches) {
                val body = SuShell.exec("cat '$fullPath' 2>/dev/null")
                if (body.isNotEmpty()) {
                    contentMatches = body.contains(u, ignoreCase = true) || (uid.isNotEmpty() && body.contains(uid))
                }
            }

            if (nameMatches || contentMatches) {
                matched.add(
                    NousigiUserFile(
                        fullPath = fullPath,
                        relPath = "Delta/Workspace/$rel",
                        displayName = rel.removePrefix("Nousigi Hub/")
                    )
                )
            }
        }
        return matched
    }

    /**
     * Packs and removes all user-associated .json files across Nousigi Hub & cache into stageExtDir
     * for cross-slot / cross-cloud !changeslot transfers.
     */
    fun migrateUserJsonFilesToStage(stageExtDir: String, username: String, pkgHint: String = ""): Int {
        val uid = resolveUserId(username, pkgHint)
        val files = findAllUserJsonFiles(username, uid)
        var count = 0
        for (f in files) {
            val relFromSdcard = f.relPath // e.g. Delta/Workspace/Nousigi Hub/cache/12345.json
            val dstFile = "$stageExtDir/$relFromSdcard"
            val dstParent = dstFile.substringBeforeLast('/')
            SuShell.exec("mkdir -p '$dstParent' && cp -f '${f.fullPath}' '$dstFile' 2>/dev/null && rm -f '${f.fullPath}' 2>/dev/null")
            count++
        }
        KeyAutomator.cleanBakFiles()
        return count
    }

    /** Deletes all user-associated .json files across Nousigi Hub & cache when !delete is run. */
    fun deleteAllUserJsonFiles(username: String, pkgHint: String = ""): Int {
        val uid = resolveUserId(username, pkgHint)
        val files = findAllUserJsonFiles(username, uid)
        for (f in files) {
            SuShell.exec("rm -f '${f.fullPath}' 2>/dev/null")
        }
        KeyAutomator.cleanBakFiles()
        return files.size
    }

    /** Ensures a default <Username>_AnimeAdventures.json exists if the user opens !nousigi before playing. */
    fun ensureDefaultConfig(ctx: Context, username: String): String {
        val existing = findAllUserJsonFiles(username)
        val mainCfg = existing.firstOrNull { it.fullPath.endsWith("${username}_AnimeAdventures.json", true) }
            ?: existing.firstOrNull { it.fullPath.contains("AnimeAdventures", true) }
        if (mainCfg != null) return mainCfg.fullPath

        val targetPath = "$NOUSIGI_DIR/${username}_AnimeAdventures.json"
        val defaultJson = JSONObject()
            .put("Auto Join", false)
            .put("Auto Join Story", false)
            .put("Auto Join Legend", false)
            .put("Auto Join Raid", false)
            .put("Auto Join Challenge", false)
            .put("Auto Join Portal", false)
            .put("Auto Replay", true)
            .put("Auto Next", false)
            .put("Auto Return Lobby", false)
            .put("Auto Play", true)
            .put("Webhook URL", "")
            .put("Mention ID", "")
            .put("Webhook Stage Finished", true)
            .put("Webhook Unit Summoned", false)
            .toString(2)

        writeJsonSafe(ctx, targetPath, defaultJson)
        return targetPath
    }

    fun readSettings(filePath: String): List<NousigiSettingEntry> {
        val raw = SuShell.exec("cat '$filePath' 2>/dev/null").trim()
        if (raw.isEmpty()) return emptyList()
        val obj = runCatching { JSONObject(raw) }.getOrNull() ?: return emptyList()
        val out = mutableListOf<NousigiSettingEntry>()
        val keys = obj.keys()
        while (keys.hasNext()) {
            val k = keys.next()
            val v = obj.opt(k) ?: continue
            val type = when (v) {
                is Boolean -> "bool"
                is Number -> "number"
                is String -> "string"
                else -> continue
            }
            out.add(
                NousigiSettingEntry(
                    key = k,
                    type = type,
                    valueStr = v.toString(),
                    category = categorizeKey(k)
                )
            )
        }
        return out.sortedWith(compareBy({ it.category }, { it.key.lowercase() }))
    }

    private fun categorizeKey(k: String): String {
        val low = k.lowercase()
        return when {
            low.contains("join") || low.contains("story") || low.contains("raid") ||
                low.contains("portal") || low.contains("challenge") || low.contains("legend") ||
                low.contains("event") || low.contains("priority") -> "Auto Joiners"
            low.contains("webhook") || low.contains("mention") || low.contains("discord") ||
                low.contains("ping") || low.contains("notify") -> "Webhooks"
            low.contains("replay") || low.contains("next") || low.contains("lobby") ||
                low.contains("rejoin") || low.contains("play") || low.contains("macro") ||
                low.contains("equip") -> "Match Flow"
            else -> "Other"
        }
    }

    fun toggleBooleanSetting(ctx: Context, filePath: String, key: String): Pair<Boolean, String> {
        val raw = SuShell.exec("cat '$filePath' 2>/dev/null").trim()
        val obj = runCatching { JSONObject(raw) }.getOrNull() ?: return false to "Could not parse JSON file."
        val cur = obj.optBoolean(key, false)
        val next = !cur
        obj.put(key, next)
        writeJsonSafe(ctx, filePath, obj.toString(2))
        return true to "Set **$key** → `${if (next) "ON (true)" else "OFF (false)"}`"
    }

    fun updateSettingValue(ctx: Context, filePath: String, key: String, rawValue: String): Pair<Boolean, String> {
        val raw = SuShell.exec("cat '$filePath' 2>/dev/null").trim()
        val obj = runCatching { JSONObject(raw) }.getOrNull() ?: return false to "Could not parse JSON file."
        val existing = obj.opt(key)
        val trimmed = rawValue.trim()

        when {
            existing is Boolean || trimmed.equals("true", true) || trimmed.equals("false", true) -> {
                obj.put(key, trimmed.equals("true", true))
            }
            existing is Int || existing is Long -> {
                val n = trimmed.toLongOrNull() ?: return false to "Expected a whole number for **$key**."
                obj.put(key, n)
            }
            existing is Double || existing is Float -> {
                val d = trimmed.toDoubleOrNull() ?: return false to "Expected a decimal number for **$key**."
                obj.put(key, d)
            }
            else -> {
                obj.put(key, trimmed)
            }
        }
        writeJsonSafe(ctx, filePath, obj.toString(2))
        return true to "Updated **$key** → `${trimmed.ifEmpty { "(blank)" }}`"
    }

    private fun writeJsonSafe(ctx: Context, targetPath: String, formattedJson: String) {
        val dir = targetPath.substringBeforeLast('/')
        val tmp = File(ctx.cacheDir, "nousigi_cfg_${System.nanoTime()}.json")
        tmp.writeText(formattedJson)
        SuShell.exec("mkdir -p '$dir' && cp '${tmp.absolutePath}' '$targetPath' && chmod 666 '$targetPath'")
        tmp.delete()
        KeyAutomator.cleanBakFiles()
    }
}