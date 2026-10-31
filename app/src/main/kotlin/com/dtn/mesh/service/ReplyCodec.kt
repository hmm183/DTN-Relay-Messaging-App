package com.dtn.mesh.service

/**
 * Reply metadata carried inside the encrypted message body (DMs after the algorithm byte, group
 * messages at the start), so the recipient can render the quote and jump to the parent.
 *
 * `[0x00]['R'][idLen][id UTF-8][snippetLen][snippet UTF-8][text UTF-8…]`
 *
 * Composer text never starts with NUL, so a body without the marker is plain text. A body that
 * starts with the marker but is malformed is also treated as plain text (never dropped).
 * Builds without this codec show the header bytes as part of the text.
 */
object ReplyCodec {
    private const val MARK0: Byte = 0x00
    private const val MARK1: Byte = 'R'.code.toByte()
    /** Chat row ids are UUIDs (36 chars) or canonical fragment parents; anything longer is dropped. */
    private const val MAX_ID_BYTES = 64
    const val MAX_SNIPPET_CHARS = 60

    data class Body(val text: String, val replyToId: String? = null, val replySnippet: String? = null)

    fun encode(text: String, replyToId: String?, replySnippet: String?): ByteArray {
        val textBytes = text.toByteArray(Charsets.UTF_8)
        val id = replyToId?.toByteArray(Charsets.UTF_8)
        if (id == null || id.isEmpty() || id.size > MAX_ID_BYTES) return textBytes
        // ≤ 60 chars × 4 bytes = 240 < 255, so the length always fits one byte.
        val snip = snippetOf(replySnippet).toByteArray(Charsets.UTF_8)
        return byteArrayOf(MARK0, MARK1, id.size.toByte()) + id + byteArrayOf(snip.size.toByte()) + snip + textBytes
    }

    fun decode(body: ByteArray): Body {
        val plain = Body(String(body, Charsets.UTF_8))
        if (body.size < 4 || body[0] != MARK0 || body[1] != MARK1) return plain
        val idLen = body[2].toInt() and 0xFF
        val snipLenAt = 3 + idLen
        if (idLen == 0 || idLen > MAX_ID_BYTES || snipLenAt >= body.size) return plain
        val snipLen = body[snipLenAt].toInt() and 0xFF
        val textAt = snipLenAt + 1 + snipLen
        if (textAt > body.size) return plain
        val id = String(body, 3, idLen, Charsets.UTF_8)
        val snippet = String(body, snipLenAt + 1, snipLen, Charsets.UTF_8).ifBlank { null }
        return Body(String(body, textAt, body.size - textAt, Charsets.UTF_8), id, snippet)
    }

    /** One-line preview of the parent, cut on a code-point boundary. */
    fun snippetOf(text: String?): String {
        val oneLine = text.orEmpty().replace('\n', ' ').trim()
        if (oneLine.codePointCount(0, oneLine.length) <= MAX_SNIPPET_CHARS) return oneLine
        return oneLine.substring(0, oneLine.offsetByCodePoints(0, MAX_SNIPPET_CHARS))
    }
}
