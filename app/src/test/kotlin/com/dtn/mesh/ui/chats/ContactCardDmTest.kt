package com.dtn.mesh.ui.chats

import com.dtn.mesh.crypto.IdentityQr
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class ContactCardDmTest {

    @Test
    fun detect_parsesValidContactCardString() {
        val xPub = ByteArray(32) { 1 }
        val edPub = ByteArray(32) { 2 }
        val cardString = IdentityQr.build(0x12345678, xPub, edPub, "Bob")

        val parsed = ContactCardDm.detect(cardString)
        assertNotNull(parsed)
        assertEquals("Bob", parsed?.name)
        assertEquals(0x12345678, parsed?.nodeNum)
    }

    @Test
    fun detect_ignoresNormalTextAndGroupInvites() {
        assertNull(ContactCardDm.detect("Hello world"))
        assertNull(ContactCardDm.detect("dtngrp:1:Family:dGVzdGtleQ=="))
        assertNull(ContactCardDm.detect(""))
    }

    @Test
    fun preview_formatsCleanly() {
        val xPub = ByteArray(32) { 1 }
        val edPub = ByteArray(32) { 2 }
        val cardString = IdentityQr.build(0x12345678, xPub, edPub, "Alice")

        assertEquals("Contact · Alice", ContactCardDm.preview(cardString))
    }
}

