package com.dtn.mesh.crypto

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.widget.Toast
import com.dtn.mesh.BuildConfig
import com.dtn.mesh.model.NodeId
import java.util.Base64

/**
 * Universal link and direct code generator for DTN Mesh identities and groups.
 *
 * If a valid Cloudflare Worker host is configured in the build (`BuildConfig.WORKER_HOST`),
 * this creates standard HTTPS App Links (`https://<host>/c#...` or `https://<host>/g#...`).
 * If no worker host is configured, it falls back to sharing the raw `dtnid:` / `dtngrp:` URI
 * directly so contacts can still import it via QR or "Paste link / code".
 */
object LinkGenerator {

    val workerHost: String get() = BuildConfig.WORKER_HOST.trim()

    /**
     * Builds an invite link or raw identity code for a peer.
     * Always includes that peer's own keys and name, never the caller's.
     */
    fun buildContactLink(nodeId: NodeId, xPub: ByteArray, edPub: ByteArray, name: String): String {
        // The QR builders sanitise the name (newlines, 48 chars / invite byte cap): one format everywhere.
        val code = IdentityQr.build(nodeId.toNodeNum32(), xPub, edPub, name)
        return if (workerHost.isNotBlank()) "https://$workerHost/c#$code" else code
    }

    /**
     * Builds a group join link or raw group invite code.
     */
    fun buildGroupLink(channel: Int, name: String, key: ByteArray): String {
        val code = GroupInvite.build(channel, name, key)
        return if (workerHost.isNotBlank()) "https://$workerHost/g#$code" else code
    }

    /**
     * Launches the Android system share sheet (ACTION_SEND).
     */
    fun shareText(context: Context, title: String, text: String) {
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, title)
            putExtra(Intent.EXTRA_TEXT, text)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        val chooser = Intent.createChooser(intent, title).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        context.startActivity(chooser)
    }

    /**
     * Copies [text] to the Android system clipboard with a toast notification.
     */
    fun copyToClipboard(context: Context, label: String, text: String) {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        clipboard?.setPrimaryClip(ClipData.newPlainText(label, text))
        Toast.makeText(context, "$label copied to clipboard", Toast.LENGTH_SHORT).show()
    }
}

