package com.dtn.mesh.ui.chats

import com.dtn.mesh.crypto.IdentityQr

/**
 * Shared contact cards sent as ordinary encrypted DMs or group messages.
 * The DM's whole text is the identity QR string (`dtnid:2:...`), so the receiver's chat shows
 * an interactive Contact Card instead of raw text, and notification/queue previews show
 * "Contact · <name>" (never keys or raw node IDs).
 */
object ContactCardDm {

    /** The contact identity a message carries, or null when its text isn't an identity QR string. */
    fun detect(text: String): IdentityQr.Parsed? {
        val t = text.trim()
        if (!t.startsWith(IdentityQr.PREFIX)) return null
        return IdentityQr.parse(t)
    }

    /** "Contact · Alice" for previews, or null when [text] isn't a contact card. */
    fun preview(text: String): String? = detect(text)?.let {
        "Contact · ${it.name.ifBlank { "Peer" }}"
    }
}

