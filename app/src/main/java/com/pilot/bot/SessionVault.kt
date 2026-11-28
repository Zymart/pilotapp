package com.pilot.bot

import android.content.Context
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

object SessionVault {
    private fun vaultDir(ctx: Context, pkg: String): File =
        File(ctx.filesDir, "session_vault/${pkg.replace(Regex("[^a-zA-Z0-9._-]"), "_")}")

    /** Extracts the raw .ROBLOSECURITY cookie from a clone's WebView SQLite/Prefs. */
    fun extractCookie(pkg: String): String {
        val cmd = """
            for f in /data/data/$pkg/app_webview/Default/Cookies /data/data/$pkg/app_webview/Cookies; do
              if [ -f "${'$'}f" ]; then
                strings "${'$'}f" 2>/dev/null | grep -oE '_\|WARNING:-DO-NOT-SHARE-THIS\.\--[^[:space:]"'"'"']+' | head -n 1
              fi
            done
        """.trimIndent()
        return SuShell.exec(cmd).trim()
    }

    /** Validates a cookie against Roblox auth API. Returns username if valid, null if revoked/expired. */
    fun validateCookie(cookie: String): String? {
        if (cookie.length < 40) return null
        return runCatching {
            val conn = (URL("https://users.roblox.com/v1/users/authenticated").openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 8000
                readTimeout = 8000
                setRequestProperty("Cookie", ".ROBLOSECURITY=$cookie")
                setRequestProperty("Accept", "application/json")
            }
            val code = conn.responseCode
            if (code == 200) {
                val body = conn.inputStream.bufferedReader().readText()
                JSONObject(body).optString("name").ifEmpty { null }
            } else {
                null
            }
        }.getOrNull()
    }

    /** Calls POST auth.roblox.com/v2/logout with X-CSRF-Token handshake to kill the session server-side. */
    fun revokeServerToken(cookie: String): Boolean {
        if (cookie.length < 40) return false
        return runCatching {
            fun postLogout(csrf: String?): Pair<Int, String?> {
                val conn = (URL("https://auth.roblox.com/v2/logout").openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    doOutput = true
                    connectTimeout = 8000
                    readTimeout = 8000
                    setRequestProperty("Cookie", ".ROBLOSECURITY=$cookie")
                    setRequestProperty("Content-Length", "0")
                    if (!csrf.isNullOrEmpty()) setRequestProperty("x-csrf-token", csrf)
                }
                conn.outputStream.use { }
                val code = conn.responseCode
                val nextCsrf = conn.getHeaderField("x-csrf-token")
                conn.disconnect()
                return code to nextCsrf
            }
            val (code1, csrf) = postLogout(null)
            if (code1 == 200) return@runCatching true
            if (code1 == 403 && !csrf.isNullOrEmpty()) {
                val (code2, _) = postLogout(csrf)
                return@runCatching code2 == 200
            }
            false
        }.getOrDefault(false)
    }

    /** Saves a local snapshot of an ACTIVE clone's session files so UGPhone glitches can auto-recover. */
    fun snapshot(ctx: Context, pkg: String, username: String): Boolean {
        if (username.isBlank() || username == "Empty") return false
        val dir = vaultDir(ctx, pkg).apply { mkdirs() }
        val cookie = extractCookie(pkg)
        if (cookie.isBlank()) return false

        File(dir, "meta.json").writeText(
            JSONObject().put("pkg", pkg).put("user", username).put("ts", System.currentTimeMillis()).toString()
        )
        File(dir, "cookie.dat").writeText(cookie)

        val tarPath = File(dir, "session.tar").absolutePath
        SuShell.exec(
            "cd /data/data/$pkg && tar -cf '$tarPath' app_webview shared_prefs 2>/dev/null; chmod 600 '$tarPath'"
        )
        PilotCore.log("[VAULT] Snapshot saved for $username ($pkg)")
        return true
    }

    enum class RestoreResult { RESTORED, REVOKED, NO_BACKUP }

    /** Checks if a logged-out clone's vaulted session is still valid; restores on glitch or purges if password changed. */
    fun tryRestore(ctx: Context, pkg: String): Pair<RestoreResult, String> {
        val dir = vaultDir(ctx, pkg)
        val cookieFile = File(dir, "cookie.dat")
        val tarFile = File(dir, "session.tar")
        val metaFile = File(dir, "meta.json")
        if (!cookieFile.exists() || !tarFile.exists()) return RestoreResult.NO_BACKUP to ""

        val savedUser = runCatching { JSONObject(metaFile.readText()).optString("user") }.getOrDefault("")
        val cookie = cookieFile.readText().trim()
        val liveUser = validateCookie(cookie)

        if (liveUser == null) {
            deleteVault(ctx, pkg)
            PilotCore.log("[VAULT] Session revoked for $savedUser ($pkg) — snapshot purged")
            return RestoreResult.REVOKED to savedUser
        }

        SuShell.exec("am force-stop $pkg")
        val uid = SuShell.exec("stat -c '%u:%g' /data/data/$pkg 2>/dev/null").trim()
        SuShell.exec(
            "rm -rf /data/data/$pkg/app_webview /data/data/$pkg/shared_prefs && " +
            "tar -xf '${tarFile.absolutePath}' -C /data/data/$pkg 2>/dev/null && " +
            (if (uid.contains(":")) "chown -R $uid /data/data/$pkg/app_webview /data/data/$pkg/shared_prefs && " else "") +
            "restorecon -R /data/data/$pkg 2>/dev/null"
        )
        PilotCore.log("[VAULT] Restored local session for $liveUser ($pkg)")
        return RestoreResult.RESTORED to liveUser
    }

    fun deleteVault(ctx: Context, pkg: String) {
        vaultDir(ctx, pkg).deleteRecursively()
    }

    /** Full 3-step purge for !logout and !delete: server revoke -> vault wipe -> local clone wipe. */
    fun fullLogoutAndWipe(ctx: Context, pkg: String): Boolean {
        val liveCookie = extractCookie(pkg)
        val vaultCookie = runCatching { File(vaultDir(ctx, pkg), "cookie.dat").readText().trim() }.getOrDefault("")
        val tokenToKill = liveCookie.ifBlank { vaultCookie }

        val revoked = if (tokenToKill.isNotBlank()) revokeServerToken(tokenToKill) else false
        deleteVault(ctx, pkg)

        SuShell.exec(
            "am force-stop $pkg && " +
            "rm -rf /data/data/$pkg/app_webview/Default/Cookies* " +
            "/data/data/$pkg/app_webview/Cookies* " +
            "/data/data/$pkg/app_webview/Default/Local\\ Storage " +
            "/data/data/$pkg/shared_prefs/*"
        )
        PilotCore.log("[VAULT] Full logout wipe complete for $pkg (serverRevoked=$revoked)")
        return revoked
    }
}