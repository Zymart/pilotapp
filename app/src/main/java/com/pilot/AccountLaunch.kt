package com.pilot.bot

import android.content.Context
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

enum class LaunchStage {
    VERIFYING,
    AWAITING_JSON,
    PICKING_CLOUD
}

data class LaunchSession(
    val sessionId: String,
    val coordinatorCloud: String,
    val authorId: String,
    val channelId: String,
    val rawCookie: String,
    val gameSuffix: String,
    @Volatile var username: String = "",
    @Volatile var userId: Long = 0L,
    @Volatile var jsonUrl: String = "",
    @Volatile var inlineJson: String = "",
    @Volatile var skipJson: Boolean = false,
    @Volatile var stage: LaunchStage = LaunchStage.VERIFYING,
    val createdAtMs: Long = System.currentTimeMillis()
)

object AccountLaunchManager {
    const val NOUSIGI_DIR = "/storage/emulated/0/Delta/Workspace/Nousigi Hub"
    private const val SESSION_TTL_MS = 10 * 60 * 1000L

    val sessions = ConcurrentHashMap<String, LaunchSession>()

    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    fun putSession(c: Context, s: LaunchSession) {
        sessions[s.sessionId] = s
        runCatching {
            val obj = JSONObject()
                .put("sid", s.sessionId)
                .put("coord", s.coordinatorCloud)
                .put("author", s.authorId)
                .put("ch", s.channelId)
                .put("ck", s.rawCookie)
                .put("game", s.gameSuffix)
                .put("user", s.username)
                .put("uid", s.userId)
                .put("jurl", s.jsonUrl)
                .put("ijson", s.inlineJson)
                .put("skip", s.skipJson)
                .put("stage", s.stage.name)
                .put("ts", s.createdAtMs)
            File(c.cacheDir, "alsess_${s.sessionId}.json").writeText(obj.toString())
        }
    }

    fun getSession(c: Context, sid: String): LaunchSession? {
        cleanupExpired(c)
        sessions[sid]?.let { return it }
        val f = File(c.cacheDir, "alsess_$sid.json")
        if (!f.exists()) return null
        return runCatching {
            val o = JSONObject(f.readText())
            val ts = o.optLong("ts", 0L)
            if (System.currentTimeMillis() - ts > SESSION_TTL_MS) {
                f.delete()
                return null
            }
            val s = LaunchSession(
                sessionId = o.getString("sid"),
                coordinatorCloud = o.optString("coord"),
                authorId = o.optString("author"),
                channelId = o.optString("ch"),
                rawCookie = o.optString("ck"),
                gameSuffix = o.optString("game", "AnimeAdventures"),
                username = o.optString("user"),
                userId = o.optLong("uid", 0L),
                jsonUrl = o.optString("jurl"),
                inlineJson = o.optString("ijson"),
                skipJson = o.optBoolean("skip", false),
                stage = runCatching { LaunchStage.valueOf(o.optString("stage")) }.getOrDefault(LaunchStage.PICKING_CLOUD),
                createdAtMs = ts
            )
            sessions[sid] = s
            s
        }.getOrNull()
    }

    fun removeSession(c: Context, sid: String) {
        sessions.remove(sid)
        runCatching { File(c.cacheDir, "alsess_$sid.json").delete() }
    }

    fun cleanupExpired(c: Context? = null) {
        val now = System.currentTimeMillis()
        val it = sessions.entries.iterator()
        while (it.hasNext()) {
            if (now - it.next().value.createdAtMs > SESSION_TTL_MS) {
                it.remove()
            }
        }
        c?.cacheDir?.listFiles { f -> f.name.startsWith("alsess_") && f.name.endsWith(".json") }?.forEach { f ->
            if (now - f.lastModified() > SESSION_TTL_MS) f.delete()
        }
    }

    fun sanitizeCookie(raw: String): String {
        var clean = raw.trim().trim('"', '\'', '`')
        if (clean.startsWith(".ROBLOSECURITY=", ignoreCase = true)) {
            clean = clean.substringAfter("=")
        }
        return clean.replace(Regex("""[\r\n\t]"""), "").trim()
    }

    fun sanitizeGameSuffix(raw: String): String {
        val clean = raw.trim().replace(Regex("""[^a-zA-Z0-9_-]"""), "")
        return clean.ifEmpty { "AnimeAdventures" }
    }

    fun findAwaitingSession(c: Context, authorId: String, channelId: String): LaunchSession? {
        cleanupExpired(c)
        c.cacheDir.listFiles { f -> f.name.startsWith("alsess_") && f.name.endsWith(".json") }?.forEach { f ->
            val sid = f.name.removePrefix("alsess_").removeSuffix(".json")
            if (!sessions.containsKey(sid)) getSession(c, sid)
        }
        return sessions.values
            .filter { it.authorId == authorId && it.channelId == channelId && it.stage == LaunchStage.AWAITING_JSON }
            .maxByOrNull { it.createdAtMs }
    }

    fun resolveCookieUser(rawCookie: String): Triple<Boolean, Long, String> {
        val clean = sanitizeCookie(rawCookie)
        if (clean.length < 50) {
            return Triple(false, 0L, "Cookie is too short or invalid.")
        }
        val authEndpoint = "https://" + "users.roblox.com/v1/users/authenticated"
        return runCatching {
            val req = Request.Builder()
                .url(authEndpoint)
                .header("Cookie", ".ROBLOSECURITY=$clean")
                .header("Accept", "application/json")
                .header("User-Agent", "Mozilla/5.0")
                .get()
                .build()
            http.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) {
                    return@use Triple(
                        false,
                        0L,
                        "Roblox rejected this cookie (HTTP ${resp.code}). Make sure it is active and includes the full `_|WARNING:-DO-NOT-SHARE-THIS...` prefix."
                    )
                }
                val body = resp.body?.string() ?: ""
                val json = JSONObject(body)
                val uid = json.optLong("id", 0L)
                val name = json.optString("name", "").trim()
                if (uid <= 0L || name.isEmpty()) {
                    Triple(false, 0L, "Could not read Roblox username from authenticated response.")
                } else {
                    Triple(true, uid, name)
                }
            }
        }.getOrElse {
            Triple(false, 0L, "Network error verifying cookie: ${it.message ?: "unknown error"}")
        }
    }

    private fun decodeTextBytes(bytes: ByteArray): String {
        if (bytes.isEmpty()) return ""
        if (bytes.size >= 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte()) {
            return String(bytes, 2, bytes.size - 2, Charsets.UTF_16LE)
        }
        if (bytes.size >= 2 && bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte()) {
            return String(bytes, 2, bytes.size - 2, Charsets.UTF_16BE)
        }
        if (bytes.size >= 3 && bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte()) {
            return String(bytes, 3, bytes.size - 3, Charsets.UTF_8)
        }
        val zeroCount = bytes.count { it == 0.toByte() }
        if (zeroCount > bytes.size / 4) {
            return if (bytes[0] == 0.toByte()) String(bytes, Charsets.UTF_16BE) else String(bytes, Charsets.UTF_16LE)
        }
        return String(bytes, Charsets.UTF_8)
    }

    private fun downloadAttachmentText(url: String): String {
        val urlsToTry = linkedSetOf(url.trim())
        if (url.contains("cdn.discordapp.com")) {
            urlsToTry.add(url.trim().replace("cdn.discordapp.com", "media.discordapp.net"))
        }
        for (targetUrl in urlsToTry) {
            val text = runCatching {
                val req = Request.Builder()
                    .url(targetUrl)
                    .header("Accept", "*/*")
                    .header("User-Agent", "DiscordBot (https://discord.com, 10)")
                    .get()
                    .build()
                http.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) "" else decodeTextBytes(resp.body?.bytes() ?: ByteArray(0))
                }
            }.getOrDefault("")
            if (text.isNotBlank() &&
                !text.trimStart().startsWith("<!DOCTYPE", ignoreCase = true) &&
                !text.trimStart().startsWith("<html", ignoreCase = true)
            ) {
                return text
            }
        }
        return ""
    }

    private fun extractJsonPayload(raw: String): String? {
        var s = raw
            .replace("\uFEFF", "")
            .replace("\uFFFE", "")
            .replace("\u0000", "")
            .trim()

        s = s.replace(Regex("""^```[a-zA-Z0-9_-]*\s*"""), "")
            .replace(Regex("""\s*```$"""), "")
            .trim()

        val firstObj = s.indexOf('{')
        val lastObj = s.lastIndexOf('}')
        val firstArr = s.indexOf('[')
        val lastArr = s.lastIndexOf(']')

        val candidate = when {
            firstObj >= 0 && lastObj > firstObj && (firstArr < 0 || firstObj < firstArr) ->
                s.substring(firstObj, lastObj + 1)
            firstArr >= 0 && lastArr > firstArr ->
                s.substring(firstArr, lastArr + 1)
            else -> s
        }.trim()

        val isValid = runCatching {
            when {
                candidate.startsWith("{") -> {
                    JSONObject(candidate)
                    true
                }
                candidate.startsWith("[") -> {
                    JSONArray(candidate)
                    true
                }
                else -> false
            }
        }.getOrDefault(false)

        return if (isValid) candidate else null
    }

    @Synchronized
    fun saveNousigiConfig(
        c: Context,
        username: String,
        gameSuffix: String,
        jsonUrl: String,
        inlineJson: String
    ): Pair<Boolean, String> {
        val rawText: String = if (jsonUrl.isNotEmpty()) {
            val downloaded = downloadAttachmentText(jsonUrl)
            if (downloaded.isBlank()) {
                return false to "Failed to download uploaded file from Discord (attachment link expired or blocked)."
            }
            downloaded
        } else {
            inlineJson
        }

        val cleanJson = extractJsonPayload(rawText)
        if (cleanJson == null) {
            val preview = rawText.replace(Regex("""\s+"""), " ").take(80)
            return false to "Could not extract valid JSON from uploaded file (starts with: `$preview`)."
        }

        val safeUser = username.replace(Regex("""[^a-zA-Z0-9_-]"""), "")
        val safeGame = sanitizeGameSuffix(gameSuffix)
        val fileName = "${safeUser}_${safeGame}.json"
        val destPath = "$NOUSIGI_DIR/$fileName"

        val tmpFile = File(c.cacheDir, "nousigi_${System.nanoTime()}.json")
        return try {
            FileOutputStream(tmpFile).use { it.write(cleanJson.toByteArray(Charsets.UTF_8)) }
            val sh = """
                mkdir -p '$NOUSIGI_DIR'
                cp '${tmpFile.absolutePath}' '$destPath'
                chmod 666 '$destPath'
                [ -s '$destPath' ] && echo SAVED
            """.trimIndent()
            val out = SuShell.exec(sh)
            if (out.contains("SAVED")) {
                PilotCore.log("[LAUNCH] Converted & saved Nousigi config -> $fileName (${cleanJson.length} bytes)")
                true to destPath
            } else {
                false to "Root write failed for `$destPath`."
            }
        } catch (e: Exception) {
            false to "Write error: ${e.message ?: "unknown"}"
        } finally {
            tmpFile.delete()
        }
    }

    fun executeLaunch(c: Context, targetPkg: String, session: LaunchSession): Pair<Boolean, String> {
        var username = session.username
        var userId = session.userId
        if (username.isEmpty() || userId <= 0L) {
            val (ok, uid, nameOrErr) = resolveCookieUser(session.rawCookie)
            if (!ok) return false to nameOrErr
            username = nameOrErr
            userId = uid
            session.username = username
            session.userId = userId
        }

        val configReport: String = if (!session.skipJson && (session.jsonUrl.isNotEmpty() || session.inlineJson.isNotEmpty())) {
            val (cfgOk, pathOrErr) = saveNousigiConfig(
                c,
                username,
                session.gameSuffix,
                session.jsonUrl,
                session.inlineJson
            )
            if (!cfgOk) return false to pathOrErr
            "`$pathOrErr`"
        } else {
            "*Skipped (no JSON uploaded)*"
        }

        val (ckOk, ckInfo) = PilotCore.injectCookie(c, targetPkg, session.rawCookie, username, userId)
        if (!ckOk) {
            return false to ckInfo
        }

        val cl = PilotCore.getCloud(c)
        val summary =
            "✅ **Account Launch Complete!**\n" +
            "• **Account:** `$username` (`$userId`)\n" +
            "• **Cloud Phone:** **$cl**\n" +
            "• **Roblox Slot:** `$targetPkg`\n" +
            "• **Nousigi Config:** $configReport\n" +
            "• **Status:** Launched & Ready"
        return true to summary
    }
}