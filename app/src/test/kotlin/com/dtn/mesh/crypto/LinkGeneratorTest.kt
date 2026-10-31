package com.dtn.mesh.crypto

import com.dtn.mesh.model.NodeId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

class LinkGeneratorTest {

    private val sampleXPub = ByteArray(32) { (it + 1).toByte() }
    private val sampleEdPub = ByteArray(32) { (it + 33).toByte() }
    private val sampleGroupKey = ByteArray(32) { (it + 65).toByte() }

    @Test
    fun buildContactLinkProducesValidFormat() {
        val link = LinkGenerator.buildContactLink(
            nodeId = NodeId("!11223344"),
            xPub = sampleXPub,
            edPub = sampleEdPub,
            name = "Charlie",
        )

        // When host is empty in test environment, falls back to raw dtnid:2:...
        if (LinkGenerator.workerHost.isBlank()) {
            assertTrue(link.startsWith("dtnid:2:11223344:"))
            assertFalse(link.startsWith("https://"))
        } else {
            assertTrue(link.startsWith("https://${LinkGenerator.workerHost}/c#dtnid:2:11223344:"))
        }

        // Verify parseable by DeepLinkParser
        val parsed = DeepLinkParser.parse(link)
        assertTrue(parsed is DeepLinkParser.ParsedLink.Contact)
        val contact = (parsed as DeepLinkParser.ParsedLink.Contact).parsed
        assertEquals("Charlie", contact.name)
        assertEquals(0x11223344, contact.nodeNum)
    }

    @Test
    fun buildGroupLinkProducesValidFormat() {
        val link = LinkGenerator.buildGroupLink(
            channel = 7,
            name = "Field Operations",
            key = sampleGroupKey,
        )

        if (LinkGenerator.workerHost.isBlank()) {
            assertTrue(link.startsWith("dtngrp:7:Field Operations:"))
            assertFalse(link.startsWith("https://"))
        } else {
            assertTrue(link.startsWith("https://${LinkGenerator.workerHost}/g#dtngrp:7:Field Operations:"))
        }

        val parsed = DeepLinkParser.parse(link)
        assertTrue(parsed is DeepLinkParser.ParsedLink.Group)
        val group = (parsed as DeepLinkParser.ParsedLink.Group).parsed
        assertEquals(7, group.channel)
        assertEquals("Field Operations", group.name)
    }
}

