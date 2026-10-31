package com.dtn.mesh.receiver.webrtc

import com.dtn.mesh.crypto.DtnCrypto
import com.dtn.mesh.crypto.DtnIdentity
import com.dtn.mesh.database.entity.ContactEntity
import org.json.JSONObject
import java.nio.ByteBuffer
import java.util.Base64
import java.util.UUID

/**
 * WebRTC Signaling messages for P2P direct data channels.
 *
 * Exchanged over MQTT topic `dtn/v1/rtc/<hex8>` and Cloudflare Worker relay (kind 5).
 * Uses consolidated full SDP offers and answers (no trickle ICE), eliminating hundreds of
 * individual candidate messages and protecting Worker free-tier quotas.
 *
 * Security:
 * - Each signal is signed with the sender's Ed25519 identity key over (type, sender, recipient, sessionId, timestamp, full SDP).
 * - Encrypted end-to-end to the recipient's pinned X25519 public key using AES-256-GCM.
 * - Replays are rejected by tracking recent session IDs and enforcing a 2-minute timestamp freshness window.
 * - Connections are strictly limited to mutually added contacts with saved keys.
 */
sealed class WebRtcSignal {
    abstract val sdp: String
    abstract val sessionId: String
    abstract val timestampSec: Long

    data class Offer(
        override val sdp: String,
        override val sessionId: String = UUID.randomUUID().toString().take(16),
        override val timestampSec: Long = System.currentTimeMillis() / 1000L,
    ) : WebRtcSignal()

    data class Answer(
        override val sdp: String,
        override val sessionId: String,
        override val timestampSec: Long = System.currentTimeMillis() / 1000L,
    ) : WebRtcSignal()

    val type: String get() = when (this) {
        is Offer -> "offer"
        is Answer -> "answer"
    }

    companion object {
        const val MAX_SKEW_SEC = 120L // 2 minutes window

        /**
         * Builds the exact canonical byte array to sign / verify.
         */
        fun dataToSign(type: String, senderHex: String, recipientHex: String, sessionId: String, timestampSec: Long, sdp: String): ByteArray {
            val tsBytes = ByteBuffer.allocate(8).putLong(timestampSec).array()
            return type.toByteArray(Charsets.UTF_8) +
                senderHex.lowercase().toByteArray(Charsets.US_ASCII) +
                recipientHex.lowercase().toByteArray(Charsets.US_ASCII) +
                sessionId.toByteArray(Charsets.UTF_8) +
                tsBytes +
                sdp.toByteArray(Charsets.UTF_8)
        }

        /**
         * Signs the full SDP with sender's Ed25519 key, seals it with recipient's X25519 public key,
         * and produces the encrypted outer wire JSON envelope.
         */
        fun signAndSeal(
            signal: WebRtcSignal,
            identity: DtnIdentity,
            recipientHex: String,
            recipientXPub: ByteArray,
        ): String {
            val senderHex = "%08x".format(identity.nodeNum32)
            val cleanRecipient = recipientHex.removePrefix("!").padStart(8, '0').takeLast(8).lowercase()

            val toSign = dataToSign(signal.type, senderHex, cleanRecipient, signal.sessionId, signal.timestampSec, signal.sdp)
            val signature = DtnCrypto.ed25519Sign(identity.edSeed, toSign)

            val b64 = Base64.getEncoder().withoutPadding()
            val inner = JSONObject().apply {
                put("type", signal.type)
                put("sdp", signal.sdp)
                put("sessionId", signal.sessionId)
                put("timestamp", signal.timestampSec)
                put("sender", senderHex)
                put("recipient", cleanRecipient)
                put("sig", b64.encodeToString(signature))
            }

            val plaintext = inner.toString().toByteArray(Charsets.UTF_8)
            val rootKey = DtnCrypto.x25519(identity.xPriv, recipientXPub)

            // Derive salt from sessionId
            val salt = ByteBuffer.allocate(16).apply {
                val bytes = signal.sessionId.toByteArray(Charsets.UTF_8)
                put(bytes, 0, minOf(bytes.size, 16))
            }.array()

            val originNum = identity.nodeNum32
            val destNum = cleanRecipient.toLong(16).toInt()
            val aad = "RTC-1".toByteArray(Charsets.US_ASCII)

            val ciphertext = DtnCrypto.seal(
                rootKey = rootKey,
                messageId = salt,
                origin = originNum,
                dest = destNum,
                suite = DtnCrypto.SUITE_DM,
                fragment = 0,
                aad = aad,
                plaintext = plaintext,
            )

            val outer = JSONObject().apply {
                put("sender", senderHex)
                put("recipient", cleanRecipient)
                put("sessionId", signal.sessionId)
                put("timestamp", signal.timestampSec)
                put("ciphertext", b64.encodeToString(ciphertext))
            }
            return outer.toString()
        }

        /**
         * Parses and decrypts an incoming wire envelope, verifying timestamp freshness, replay cache,
         * recipient match, contact keys, and the Ed25519 signature over the full SDP.
         *
         * @return Decrypted and authenticated [WebRtcSignal], or null if rejected.
         */
        fun openAndVerify(
            envelopeJson: String,
            identity: DtnIdentity,
            contact: ContactEntity,
            nowSec: Long = System.currentTimeMillis() / 1000L,
            isSessionSeen: (String) -> Boolean,
        ): WebRtcSignal? = runCatching {
            val outer = JSONObject(envelopeJson)
            val sender = outer.getString("sender").lowercase()
            val recipient = outer.getString("recipient").lowercase()
            val sessionId = outer.getString("sessionId")
            val timestamp = outer.getLong("timestamp")
            val b64Dec = Base64.getDecoder()
            val ciphertext = b64Dec.decode(outer.getString("ciphertext"))

            // 1. Freshness check: reject messages older than 2 minutes
            if (kotlin.math.abs(nowSec - timestamp) > MAX_SKEW_SEC) return null

            // 2. Replay check
            if (isSessionSeen(sessionId)) return null

            // 3. Recipient match
            val myHex = "%08x".format(identity.nodeNum32)
            if (recipient != myHex) return null

            // 4. Contact must have saved keys
            val peerXPub = contact.xPub ?: return null
            val peerEdPub = contact.edPub ?: return null

            // 5. Decrypt
            val rootKey = DtnCrypto.x25519(identity.xPriv, peerXPub)
            val salt = ByteBuffer.allocate(16).apply {
                val bytes = sessionId.toByteArray(Charsets.UTF_8)
                put(bytes, 0, minOf(bytes.size, 16))
            }.array()

            val originNum = sender.toLong(16).toInt()
            val destNum = identity.nodeNum32
            val aad = "RTC-1".toByteArray(Charsets.US_ASCII)

            val decrypted = DtnCrypto.open(
                rootKey = rootKey,
                messageId = salt,
                origin = originNum,
                dest = destNum,
                suite = DtnCrypto.SUITE_DM,
                fragment = 0,
                aad = aad,
                ciphertext = ciphertext,
            ) ?: return null

            val inner = JSONObject(String(decrypted, Charsets.UTF_8))
            val type = inner.getString("type")
            val sdp = inner.getString("sdp")
            val innerSessionId = inner.getString("sessionId")
            val innerTimestamp = inner.getLong("timestamp")
            val innerSender = inner.getString("sender").lowercase()
            val innerRecipient = inner.getString("recipient").lowercase()
            val sig = b64Dec.decode(inner.getString("sig"))

            if (innerSessionId != sessionId || innerTimestamp != timestamp || innerSender != sender || innerRecipient != recipient) {
                return null
            }

            // 6. Verify Ed25519 signature over full SDP
            val expectedData = dataToSign(type, sender, recipient, sessionId, timestamp, sdp)
            if (!DtnCrypto.ed25519Verify(peerEdPub, expectedData, sig)) {
                return null
            }

            when (type) {
                "offer" -> Offer(sdp = sdp, sessionId = sessionId, timestampSec = timestamp)
                "answer" -> Answer(sdp = sdp, sessionId = sessionId, timestampSec = timestamp)
                else -> null
            }
        }.getOrNull()
    }
}
