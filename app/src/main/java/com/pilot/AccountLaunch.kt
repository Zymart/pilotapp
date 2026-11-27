package com.pilot.bot

import android.content.Context
import okhttp3.OkHttpClient
import okhttp3.Request
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

    fun cleanupExpired() {
        val now = System.currentTimeMillis()
        val it = sessions.entries.iterator()
        while (it.hasNext()) {
            if (now - it.next().value.createdAtMs > SESSION_TTL_MS) {
                it.remove()
            }
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

    fun findAwaitingSession(authorId: String, channelId: String): LaunchSession? {
        cleanupExpired()
        return sessions.values
            .filter { it.authorId == authorId && it.channelId == channelId && it.stage == LaunchStage.AWAITING_JSON }
            .maxByOrNull { it.createdAtMs }
    }

    fun resolveCookieUser(rawCookie: String): Triple<Boolean, Long, String> {
        val clean = sanitizeCookie(rawCookie)
        if (clean.length < 50) {
            return Triple(false, 0L, "Cookie is too short or invalid.")
        }
        return runCatching {
            val req = Request.Builder()
                .url("https://users.roblox.com/v1/users/authenticated")
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

    @Synchronized
    fun saveNousigiConfig(
        c: Context,
        username: String,
        gameSuffix: String,
        jsonUrl: String,
        inlineJson: String
    ): Pair<Boolean, String> {
        val content: String = if (jsonUrl.isNotEmpty()) {
            val downloaded = runCatching {
                val req = Request.Builder()
                    .url(jsonUrl)
                    .header("User-Agent", "Mozilla/5.0")
                    .get()
                    .build()
                http.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) "" else resp.body?.string() ?: ""
                }
            }.getOrDefault("")
            if (downloaded.isBlank()) {
                return false to "Failed to download uploaded config attachment from Discord."
            }
            downloaded.trim().removePrefix("\uFEFF")
        } else {
            inlineJson.trim().removePrefix("\uFEFF")
        }

        if (!content.startsWith("{") && !content.startsWith("[")) {
            return false to "Uploaded file does not appear to be valid JSON."
        }

        val safeUser = username.replace(Regex("""[^a-zA-Z0-9_-]"""), "")
        val safeGame = sanitizeGameSuffix(gameSuffix)
        val fileName = "${safeUser}_${safeGame}.json"
        val destPath = "$NOUSIGI_DIR/$fileName"

        val tmpFile = File(c.cacheDir, "nousigi_${System.nanoTime()}.json")
        return try {
            FileOutputStream(tmpFile).use { it.write(content.toByteArray(Charsets.UTF_8)) }
            val sh = """
                mkdir -p '$NOUSIGI_DIR'
                cp '${tmpFile.absolutePath}' '$destPath'
                chmod 666 '$destPath'
                [ -s '$destPath' ] && echo SAVED
            """.trimIndent()
            val out = SuShell.exec(sh)
            if (out.contains("SAVED")) {
                PilotCore.log("[LAUNCH] Saved Nousigi config -> $fileName (${content.length} bytes)")
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