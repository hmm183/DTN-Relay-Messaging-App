package com.dtn.mesh.crypto

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

class DeepLinkParserTest {

    private val xKey = ByteArray(32) { (it + 1).toByte() }
    private val edKey = ByteArray(32) { (it + 33).toByte() }
    private val groupKey = ByteArray(32) { (it + 65).toByte() }

    @Test
    fun parseWebContactLinkWithFragment() {
        val qr = IdentityQr.build(0x12345678, xKey, edKey, "Alice")
        val link = "https://dtnmesh.workers.dev/c#$qr"
        val parsed = DeepLinkParser.parse(link)
        assertNotNull(parsed)
        assertTrue(parsed is DeepLinkParser.ParsedLink.Contact)
        val contact = (parsed as DeepLinkParser.ParsedLink.Contact).parsed
        assertEquals(0x12345678, contact.nodeNum)
        assertEquals("Alice", contact.name)
    }

    @Test
    fun parseWebGroupLinkWithFragment() {
        val qr = GroupInvite.build(42, "Hiking Club", groupKey)
        val link = "https://dtnmesh.workers.dev/g#$qr"
        val parsed = DeepLinkParser.parse(link)
        assertNotNull(parsed)
        assertTrue(parsed is DeepLinkParser.ParsedLink.Group)
        val group = (parsed as DeepLinkParser.ParsedLink.Group).parsed
        assertEquals(42, group.channel)
        assertEquals("Hiking Club", group.name)
    }

    @Test
    fun parseDirectDtnidPayload() {
        val qr = IdentityQr.build(0x12345678, xKey, edKey, "Bob")
        val parsed = DeepLinkParser.parse(qr)
        assertNotNull(parsed)
        assertTrue(parsed is DeepLinkParser.ParsedLink.Contact)
        assertEquals("Bob", (parsed as DeepLinkParser.ParsedLink.Contact).parsed.name)
    }

    /** Keys whose Base64 contains '+' and '/', which naive URL-decoding would corrupt. */
    private val plusKey = ByteArray(32) { 0xFB.toByte() }

    @Test
    fun parsePercentEncodedIntentForm_keepsPlusInBase64() {
        val qr = IdentityQr.build(0x12345678, plusKey, edKey, "Ann Lee")
        assertTrue(qr.contains('+'))
        // What the fallback page's "Open in app" delivers: dtnid:<percent-encoded rest>.
        val rest = java.net.URLEncoder.encode(qr.removePrefix("dtnid:"), "UTF-8").replace("+", "%20")
        val parsed = DeepLinkParser.parse("dtnid:$rest") as DeepLinkParser.ParsedLink.Contact
        assertTrue(parsed.parsed.xPub.contentEquals(plusKey))
        assertEquals("Ann Lee", parsed.parsed.name)
    }

    @Test
    fun parseSlashSlashScheme() {
        val qr = IdentityQr.build(0x12345678, xKey, edKey, "Bob")
        val parsed = DeepLinkParser.parse("dtnid://" + qr.removePrefix("dtnid:"))
        assertTrue(parsed is DeepLinkParser.ParsedLink.Contact)
    }

    @Test
    fun rejectsWebLinkWithoutFragment_unknownSchemes_andOversizeInput() {
        assertEquals(null, DeepLinkParser.parse("https://dtnmesh.example.workers.dev/c"))
        assertEquals(null, DeepLinkParser.parse("https://evil.example/c?id=12345678"))
        assertEquals(null, DeepLinkParser.parse("javascript:alert(1)"))
        assertEquals(null, DeepLinkParser.parse("dtnid:" + "A".repeat(5000)))
        assertEquals(null, DeepLinkParser.parse(""))
    }

    @Test
    fun parseDirectDtngrpPayload() {
        val qr = GroupInvite.build(7, "Mesh Net", groupKey)
        val parsed = DeepLinkParser.parse(qr)
        assertNotNull(parsed)
        assertTrue(parsed is DeepLinkParser.ParsedLink.Group)
        assertEquals(7, (parsed as DeepLinkParser.ParsedLink.Group).parsed.channel)
    }
}

