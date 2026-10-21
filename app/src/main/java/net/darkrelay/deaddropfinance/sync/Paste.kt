package net.darkrelay.deaddropfinance.sync

import java.util.Base64

/**
 * Cleanup for text pasted into the setup boxes: labels, spaces, quotes and line breaks are stripped. Each function
 * returns the cleaned value or a plain message saying what's wrong; nothing is ever silently refused.
 */
object Paste {
    sealed class Result {
        data class Ok(val value: String) : Result()
        data class Bad(val message: String) : Result()
    }

    fun appId(raw: String): Result {
        val m = Regex("""app_[A-Za-z0-9]+""").find(raw)
        return if (m != null) Result.Ok(m.value)
        else Result.Bad("That doesn't look like a Teller application ID. It starts with app_ and is on your Teller dashboard.")
    }

    /** A SimpleFIN setup token is base64 text that decodes to a web address (the claim URL). */
    fun setupToken(raw: String): Result {
        var t = raw.trim()
        if (t.isEmpty()) return Result.Bad("Paste the setup token first.")
        if (t.startsWith("http://", true) || t.startsWith("https://", true)) {
            return Result.Bad("That is a web address, not a setup token. Paste the setup token, the long string of letters and numbers the Bridge shows once.")
        }
        t = t.replace(Regex("""^(?i)\s*(simplefin\s*)?(setup\s*)?token\s*[:=]\s*"""), "")
        t = t.filter { !it.isWhitespace() && it != '"' && it != '\'' && it != '`' }
        if (t.isEmpty()) return Result.Bad("Paste the setup token first.")
        if (!Regex("""[A-Za-z0-9+/_\-]+={0,2}""").matches(t)) {
            return Result.Bad("That has characters a setup token never has. Copy the token again, only the token.")
        }
        val url = decodeClaimUrl(t) ?: return Result.Bad("That doesn't decode to a SimpleFIN token. Copy the whole token, with nothing missing at the end.")
        if (!url.startsWith("https://")) return Result.Bad("That token points somewhere that isn't a secure address, so it isn't used.")
        return Result.Ok(t)
    }

    fun decodeClaimUrl(setupToken: String): String? = try {
        val bytes = if (setupToken.contains('-') || setupToken.contains('_')) Base64.getUrlDecoder().decode(setupToken) else Base64.getMimeDecoder().decode(setupToken)
        String(bytes, Charsets.UTF_8).trim().takeIf { it.startsWith("http", ignoreCase = true) }
    } catch (e: IllegalArgumentException) {
        null
    }
}
