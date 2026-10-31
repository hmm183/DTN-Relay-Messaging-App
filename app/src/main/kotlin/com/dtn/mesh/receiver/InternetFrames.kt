package com.dtn.mesh.receiver

import java.util.Base64


/**
 * Framing for bundles carried over the MQTT internet relay.
 *
 * `[0xD7][version=1][4B publisher node num, big-endian][DTN wire bytes...]`
 *
 * The 6-byte prefix tells the receiver which node published the bundle (its immediate previous
 * hop on the relay), so echo suppression works on the internet path the same way it does over
 * BLE. It is NOT authenticated - receivers must never use it for trust decisions (routing
 * summaries arriving over the relay are dropped for exactly this reason). Payloads without the
 * prefix are accepted as raw wire bytes for compatibility with the first relay build.
 */
object InternetFrames {
    private const val MAGIC: Byte = 0xD7.toByte()
    private const val VERSION: Byte = 1
    const val HEADER_LEN = 6

    class Unwrapped(val publisherNum: Int?, val wire: ByteArray)

    fun wrap(publisherNum: Int, wire: ByteArray): ByteArray {
        val out = ByteArray(HEADER_LEN + wire.size)
        out[0] = MAGIC
        out[1] = VERSION
        out[2] = (publisherNum ushr 24).toByte()
        out[3] = (publisherNum ushr 16).toByte()
        out[4] = (publisherNum ushr 8).toByte()
        out[5] = publisherNum.toByte()
        System.arraycopy(wire, 0, out, HEADER_LEN, wire.size)
        return out
    }

    fun unwrap(payload: ByteArray): Unwrapped {
        if (payload.size <= HEADER_LEN || payload[0] != MAGIC || payload[1] != VERSION) {
            return Unwrapped(publisherNum = null, wire = payload)
        }
        val num = ((payload[2].toInt() and 0xFF) shl 24) or ((payload[3].toInt() and 0xFF) shl 16) or
            ((payload[4].toInt() and 0xFF) shl 8) or (payload[5].toInt() and 0xFF)
        return Unwrapped(publisherNum = num, wire = payload.copyOfRange(HEADER_LEN, payload.size))
    }
}


/**
 * Retained presence records on `dtn/v1/presence/<node hex>`: ASCII `on:<epochSec>` or
 * `on:<epochSec>:<base64Name>` or `off:<epochSec>`. The broker keeps the last one per node; the
 * Last Will publishes `off` when a client drops without saying goodbye, and live clients refresh
 * `on` on a heartbeat.
 */
object Presence {
    data class Status(val online: Boolean, val epochSec: Long, val name: String? = null)

    fun encode(online: Boolean, epochSec: Long, name: String? = null): ByteArray {
        val b64Name = name?.takeIf { it.isNotBlank() }?.let {
            Base64.getEncoder().withoutPadding().encodeToString(it.toByteArray(Charsets.UTF_8))
        }
        return if (b64Name != null) {
            "${if (online) "on" else "off"}:$epochSec:$b64Name".toByteArray(Charsets.US_ASCII)
        } else {
            "${if (online) "on" else "off"}:$epochSec".toByteArray(Charsets.US_ASCII)
        }
    }

    /** Parse a presence payload. Empty (a cleared retained message) means offline. */
    fun decode(payload: ByteArray): Status? {
        if (payload.isEmpty()) return Status(online = false, epochSec = 0L)
        val text = runCatching { String(payload, Charsets.US_ASCII) }.getOrNull() ?: return null
        val parts = text.split(":", limit = 3)
        if (parts.isEmpty()) return null
        val state = parts[0]
        val ts = parts.getOrNull(1)?.toLongOrNull() ?: return null
        val name = if (parts.size >= 3 && parts[2].isNotBlank()) {
            runCatching {
                String(Base64.getDecoder().decode(parts[2]), Charsets.UTF_8)
            }.getOrNull()
        } else null

        return when (state) {
            "on" -> Status(true, ts, name)
            "off" -> Status(false, ts, name)
            else -> null
        }
    }

    /**
     * When a presence record proves the node was last online (epoch ms), or null if it doesn't.
     */
    fun heardAtMs(status: Status, retained: Boolean, nowMs: Long, staleMs: Long, maxSkewMs: Long): Long? {
        if (!status.online) return null
        if (!retained) return nowMs
        val sentMs = status.epochSec * 1000L
        val ageMs = nowMs - sentMs
        return when {
            ageMs < -maxSkewMs -> null
            ageMs < 0 -> nowMs
            ageMs < staleMs -> sentMs
            else -> null
        }
    }

    /** Extract the 8-hex-digit node from a `<root>/presence/<hex>` topic, or null. */
    fun nodeHexFromTopic(topic: String, presencePrefix: String): String? {
        if (!topic.startsWith(presencePrefix)) return null
        val hex = topic.removePrefix(presencePrefix)
        return hex.takeIf { it.length == 8 && it.all { c -> c in '0'..'9' || c in 'a'..'f' } }
    }
}
