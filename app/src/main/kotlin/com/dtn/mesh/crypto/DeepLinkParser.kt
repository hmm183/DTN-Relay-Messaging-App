package com.dtn.mesh.crypto

import java.net.URLDecoder

/**
 * Contact / group links. ONE payload format everywhere: the existing identity and invite strings
 * (`dtnid:2:…`, `dtngrp:…`, [IdentityQr] / [GroupInvite]). Accepted carriers:
 *  - `https://<worker host>/c#dtnid:2:…` and `/g#dtngrp:…` (payload in the fragment, which never
 *    reaches the server or link-preview bots);
 *  - the bare strings, e.g. pasted, or from a QR scanner app (`dtnid:` / `dtngrp:` schemes);
 *  - `dtnid://…` / `dtngrp://…` (some apps and Chrome intent URLs add the slashes);
 *  - any of these percent-encoded (browsers and the fallback page's intent URL encode them).
 *
 * The input is untrusted (MainActivity is exported): parsing only, length-capped, and the caller
 * must show a confirm screen before acting.
 */
object DeepLinkParser {

    /** Longer than any valid contact (≈ 160 B) or invite (≤ 206 B) string, with room for encoding. */
    private const val MAX_LEN = 2048

    sealed class ParsedLink {
        data class Contact(val parsed: IdentityQr.Parsed) : ParsedLink()
        data class Group(val parsed: GroupInvite.Parsed) : ParsedLink()
    }

    fun parse(raw: String): ParsedLink? {
        val trimmed = raw.trim()
        if (trimmed.isEmpty() || trimmed.length > MAX_LEN) return null
        // https://host/c#payload → payload. A bare payload has no '#' (names can't contain one
        // after encoding, and the identity / invite formats never use it).
        val afterHash = if (trimmed.startsWith("https://", ignoreCase = true) || trimmed.startsWith("http://", ignoreCase = true)) {
            if (!trimmed.contains('#')) return null
            trimmed.substringAfter('#')
        } else trimmed
        for (candidate in listOf(afterHash, decode(afterHash)).distinct()) {
            val payload = normalise(candidate) ?: continue
            IdentityQr.parse(payload)?.let { return ParsedLink.Contact(it) }
            GroupInvite.parse(payload)?.let { return ParsedLink.Group(it) }
        }
        return null
    }

    /** `dtnid://x` → `dtnid:x` (same for dtngrp); anything else is not a link we know. */
    private fun normalise(s: String): String? {
        val t = s.trim()
        for (prefix in listOf(IdentityQr.PREFIX, GroupInvite.PREFIX)) {
            val scheme = prefix.removeSuffix(":")
            if (t.startsWith("$scheme://", ignoreCase = true)) return prefix + t.substring(scheme.length + 3)
            if (t.startsWith(prefix, ignoreCase = true)) return prefix + t.substring(prefix.length)
        }
        return null
    }

    /** Percent-decoding that keeps '+' (Base64 uses it; URLDecoder would turn it into a space). */
    private fun decode(s: String): String =
        runCatching { URLDecoder.decode(s.replace("+", "%2B"), "UTF-8") }.getOrDefault(s)

    fun buildWebContactLink(workerHost: String, nodeNum: Int, xPub: ByteArray, edPub: ByteArray, name: String): String =
        "https://$workerHost/c#" + IdentityQr.build(nodeNum, xPub, edPub, name)

    fun buildWebGroupLink(workerHost: String, channel: Int, name: String, key: ByteArray): String =
        "https://$workerHost/g#" + GroupInvite.build(channel, name, key)
}
