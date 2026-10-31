package com.dtn.mesh.receiver.webrtc

import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** Builds RTCIceServer lists for each provider. Pure JVM code (unit-tested). */
object IceServerFactory {
    const val METERED_RELAY_HOST = "global.relay.metered.ca"
    const val DEFAULT_TURN_PORT = 3478
    const val DEFAULT_TURNS_PORT = 5349

    /** A Metered static credential: STUN + TURN over UDP 80, TCP 80, UDP 443 and TLS 443. */
    fun meteredStatic(username: String, credential: String): List<IceServerConfig> = listOf(
        IceServerConfig(urls = listOf("stun:stun.relay.metered.ca:80")),
        IceServerConfig(listOf("turn:$METERED_RELAY_HOST:80"), username, credential),
        IceServerConfig(listOf("turn:$METERED_RELAY_HOST:80?transport=tcp"), username, credential),
        IceServerConfig(listOf("turn:$METERED_RELAY_HOST:443"), username, credential),
        IceServerConfig(listOf("turns:$METERED_RELAY_HOST:443?transport=tcp"), username, credential),
    )

    /**
     * A coturn server: STUN + TURN UDP/TCP on [port], TURNS (TLS) on [tlsPort] (0 = no TLS).
     * Without a credential only STUN is returned (coturn won't relay for anonymous users).
     */
    fun coturn(host: String, port: Int, tlsPort: Int, username: String?, credential: String?): List<IceServerConfig> {
        val h = hostForUrl(host)
        val list = mutableListOf(IceServerConfig(urls = listOf("stun:$h:$port")))
        if (!username.isNullOrBlank() && !credential.isNullOrBlank()) {
            list += IceServerConfig(listOf("turn:$h:$port"), username, credential)
            list += IceServerConfig(listOf("turn:$h:$port?transport=tcp"), username, credential)
            if (tlsPort > 0) list += IceServerConfig(listOf("turns:$h:$tlsPort?transport=tcp"), username, credential)
        }
        return list
    }

    /**
     * coturn's REST-API / `use-auth-secret` mode (static-auth-secret): a time-limited credential
     * derived on the device — username `"<expiry epoch s>:<label>"`, password
     * `Base64(HMAC-SHA1(secret, username))`. The secret itself never goes on the wire.
     */
    fun coturnSharedSecretCredential(secret: String, label: String, nowSec: Long, ttlSec: Long): Pair<String, String> {
        val username = "${nowSec + ttlSec}:${label.ifBlank { "dtn" }}"
        val mac = Mac.getInstance("HmacSHA1").apply { init(SecretKeySpec(secret.toByteArray(Charsets.UTF_8), "HmacSHA1")) }
        val password = Base64.getEncoder().encodeToString(mac.doFinal(username.toByteArray(Charsets.UTF_8)))
        return username to password
    }

    /** IPv6 literals must be bracketed inside an ICE URL. */
    private fun hostForUrl(host: String): String {
        val h = host.trim()
        return if (h.contains(':') && !h.startsWith("[")) "[$h]" else h
    }
}
