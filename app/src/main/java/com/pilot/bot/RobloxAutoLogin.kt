package com.pilot.bot

import android.content.Context
import java.util.concurrent.atomic.AtomicBoolean

object RobloxAutoLogin {
    private val busy = AtomicBoolean(false)

    data class LoginResult(val success: Boolean, val message: String, val captchaHit: Boolean = false)

    /**
     * Native UI-automated Roblox login (RAM-only credentials; never written to disk or logged).
     * Flow: launch clone -> tap Log In -> type username & password -> submit -> optional 2FA backup code.
     */
    fun performLogin(
        ctx: Context,
        pkg: String,
        username: String,
        passwordChars: CharArray,
        backupCode: String = "",
        onCaptchaAlert: (() -> Unit)? = null
    ): LoginResult {
        if (!busy.compareAndSet(false, true)) {
            return LoginResult(false, "Another auto-login is already running on this phone.")
        }
        try {
            SessionVault.deleteVault(ctx, pkg)
            SuShell.exec("am force-stop $pkg")
            Thread.sleep(1000)

            SuShell.exec("monkey -p $pkg -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1")
            Thread.sleep(6000)

            findAndTapNode(listOf("log in", "login", "sign in"), timeoutMs = 10_000)
            Thread.sleep(2000)

            val fields = findEditTextCenters()
            if (fields.size < 2) {
                return LoginResult(false, "Could not locate Username/Password fields on $pkg.")
            }

            val (ux, uy) = fields[0]
            SuShell.exec("input tap $ux $uy")
            Thread.sleep(450)
            typeTextSafely(username)
            Thread.sleep(500)

            val (px, py) = fields[1]
            SuShell.exec("input tap $px $py")
            Thread.sleep(450)
            typeTextSafely(String(passwordChars))
            Thread.sleep(600)

            SuShell.exec("input keyevent 4")
            Thread.sleep(500)
            val tappedSubmit = findAndTapNode(listOf("log in", "login", "sign in"), timeoutMs = 6000)
            if (!tappedSubmit) SuShell.exec("input keyevent 66")

            val deadline = System.currentTimeMillis() + 30_000L
            var captchaNotified = false
            while (System.currentTimeMillis() < deadline) {
                Thread.sleep(2500)

                val cookie = SessionVault.extractCookie(pkg)
                if (cookie.length > 40) {
                    val verified = SessionVault.validateCookie(cookie)
                    if (!verified.isNullOrBlank()) {
                        SessionVault.snapshot(ctx, pkg, verified)
                        Rejoiner.forceRescanSlot(ctx, pkg)
                        return LoginResult(true, "Logged in as **$verified** on `$pkg` and saved to SessionVault.")
                    }
                }

                val xml = dumpUiXml().lowercase()

                if (xml.contains("incorrect username") || xml.contains("incorrect password")) {
                    return LoginResult(false, "Roblox rejected the username or password.")
                }

                if (xml.contains("2-step verification") || xml.contains("enter code") || xml.contains("authenticator")) {
                    if (backupCode.isBlank()) {
                        return LoginResult(false, "Account requires 2-Step Verification, but no backup code was provided.")
                    }
                    findAndTapNode(listOf("backup code", "use another", "another method"), timeoutMs = 3000)
                    Thread.sleep(1200)
                    val codeBoxes = findEditTextCenters()
                    if (codeBoxes.isNotEmpty()) {
                        SuShell.exec("input tap ${codeBoxes[0].first} ${codeBoxes[0].second}")
                        Thread.sleep(400)
                        typeTextSafely(backupCode.trim())
                        Thread.sleep(500)
                        findAndTapNode(listOf("verify", "submit", "continue"), timeoutMs = 4000)
                        Thread.sleep(4000)
                        continue
                    }
                }

                if (!captchaNotified && ((xml.contains("verification") && xml.contains("puzzle")) || xml.contains("arkose"))) {
                    captchaNotified = true
                    onCaptchaAlert?.invoke()
                    val capEnd = System.currentTimeMillis() + 90_000L
                    while (System.currentTimeMillis() < capEnd) {
                        Thread.sleep(3000)
                        val c = SessionVault.extractCookie(pkg)
                        val u = SessionVault.validateCookie(c)
                        if (!u.isNullOrBlank()) {
                            SessionVault.snapshot(ctx, pkg, u)
                            Rejoiner.forceRescanSlot(ctx, pkg)
                            return LoginResult(true, "Logged in as **$u** after CAPTCHA solve.", captchaHit = true)
                        }
                    }
                    return LoginResult(false, "Timed out waiting for CAPTCHA solve on `$pkg`.", captchaHit = true)
                }
            }

            return LoginResult(false, "Login timed out on `$pkg` — verify credentials or check screenshot.")
        } finally {
            passwordChars.fill('\u0000')
            busy.set(false)
        }
    }

    private fun dumpUiXml(): String {
        SuShell.exec("uiautomator dump /sdcard/pilot_ui.xml >/dev/null 2>&1")
        return SuShell.exec("cat /sdcard/pilot_ui.xml 2>/dev/null")
    }

    private fun findAndTapNode(keywords: List<String>, timeoutMs: Long): Boolean {
        val end = System.currentTimeMillis() + timeoutMs
        val nodeRegex = Regex("""<node[^>]*?(?:text|content-desc)="([^"]+)"[^>]*?bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"[^>]*?>""")
        while (System.currentTimeMillis() < end) {
            val xml = dumpUiXml()
            for (m in nodeRegex.findAll(xml)) {
                val label = m.groupValues[1].trim().lowercase()
                if (keywords.any { label == it || label.contains(it) }) {
                    val x = (m.groupValues[2].toInt() + m.groupValues[4].toInt()) / 2
                    val y = (m.groupValues[3].toInt() + m.groupValues[5].toInt()) / 2
                    SuShell.exec("input tap $x $y")
                    return true
                }
            }
            Thread.sleep(900)
        }
        return false
    }

    private fun findEditTextCenters(): List<Pair<Int, Int>> {
        val xml = dumpUiXml()
        val rx = Regex("""<node[^>]*?class="android\.widget\.EditText"[^>]*?bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"[^>]*?>""")
        return rx.findAll(xml).map { m ->
            val cx = (m.groupValues[1].toInt() + m.groupValues[3].toInt()) / 2
            val cy = (m.groupValues[2].toInt() + m.groupValues[4].toInt()) / 2
            cx to cy
        }.toList()
    }

    private fun typeTextSafely(raw: String) {
        val escaped = raw
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("'", "\\'")
            .replace(" ", "%s")
            .replace("&", "\\&")
            .replace("|", "\\|")
            .replace(";", "\\;")
            .replace("(", "\\(")
            .replace(")", "\\)")
            .replace("$", "\\$")
            .replace("`", "\\`")
        SuShell.exec("input text \"$escaped\"")
    }
}