package com.dtn.mesh.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ReplyCodecTest {

    @Test
    fun plainText_withoutReply_roundTripsCleanly() {
        val original = "Hello, DTN mesh!"
        val encoded = ReplyCodec.encode(original, replyToId = null, replySnippet = null)
        val decoded = ReplyCodec.decode(encoded)

        assertEquals(original, decoded.text)
        assertNull(decoded.replyToId)
        assertNull(decoded.replySnippet)
    }

    @Test
    fun text_withReplyIdAndSnippet_roundTripsCleanly() {
        val text = "Sounds good to me"
        val replyId = "uuid-1234-5678-90ab-cdef"
        val snippet = "Are we meeting tomorrow?"

        val encoded = ReplyCodec.encode(text, replyToId = replyId, replySnippet = snippet)
        val decoded = ReplyCodec.decode(encoded)

        assertEquals(text, decoded.text)
        assertEquals(replyId, decoded.replyToId)
        assertEquals(snippet, decoded.replySnippet)
    }

    @Test
    fun snippetTruncation_respectsMaxSnippetLength() {
        val longSnippet = "This is a very long snippet of text that exceeds sixty characters easily and needs truncation"
        val snippet = ReplyCodec.snippetOf(longSnippet)

        assertEquals(ReplyCodec.MAX_SNIPPET_CHARS, snippet.codePointCount(0, snippet.length))
    }

    @Test
    fun malformedOrShortMarker_fallsBackToPlain() {
        val malformed = byteArrayOf(0x00, 'R'.code.toByte(), 0x10) // missing id bytes
        val decoded = ReplyCodec.decode(malformed)

        // Does not throw; treats payload as raw text
        assertEquals(String(malformed, Charsets.UTF_8), decoded.text)
    }
}

