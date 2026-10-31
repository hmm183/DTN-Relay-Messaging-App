package com.dtn.mesh.receiver.webrtc

import org.json.JSONArray
import org.json.JSONObject

/**
 * Representation of an ICE Server configuration (STUN / TURN) matching standard WebRTC RTCIceServer.
 *
 * Each user configures their own ICE servers. Credentials remain strictly local to this device
 * and are NEVER shared in WebRTC signaling messages or exchanged with peers.
 */
data class IceServerConfig(
    val urls: List<String>,
    val username: String? = null,
    val credential: String? = null,
) {
    val isTurn: Boolean get() = urls.any { it.startsWith("turn:") || it.startsWith("turns:") }
    val isStun: Boolean get() = urls.any { it.startsWith("stun:") || it.startsWith("stuns:") }
    val isTls: Boolean get() = urls.any { it.startsWith("turns:") || it.contains("transport=tls") }
    val isTcp: Boolean get() = urls.any { it.contains("transport=tcp") }
    /** Every URL is stun:/stuns:/turn:/turns:, and TURN entries carry a credential (WebRTC rejects them otherwise). */
    val hasValidUrls: Boolean get() = urls.isNotEmpty() &&
        urls.all { u -> SCHEMES.any { u.startsWith(it) } && u.length <= 512 } &&
        (!isTurn || (!username.isNullOrBlank() && !credential.isNullOrBlank()))

    /** Never let a credential reach logs via toString(). */
    override fun toString(): String = "IceServerConfig(urls=$urls, username=${username?.let { "set" }}, credential=${credential?.let { "set" }})"

    fun toJson(): JSONObject {
        val obj = JSONObject()
        val arr = JSONArray()
        urls.forEach { arr.put(it) }
        obj.put("urls", if (urls.size == 1) urls[0] else arr)
        username?.let { obj.put("username", it) }
        credential?.let { obj.put("credential", it) }
        return obj
    }

    companion object {
        private val SCHEMES = listOf("stun:", "stuns:", "turn:", "turns:")

        fun fromJson(obj: JSONObject): IceServerConfig? {
            val urls = mutableListOf<String>()
            val rawUrls = obj.opt("urls")
            when (rawUrls) {
                is String -> urls.add(rawUrls)
                is JSONArray -> {
                    for (i in 0 until rawUrls.length()) {
                        urls.add(rawUrls.getString(i))
                    }
                }
                else -> return null
            }
            if (urls.isEmpty()) return null
            val username = obj.optString("username").takeIf { it.isNotBlank() }
            val credential = obj.optString("credential").takeIf { it.isNotBlank() }
            return IceServerConfig(urls, username, credential)
        }

        fun parseJsonArray(jsonStr: String): List<IceServerConfig> = runCatching {
            val arr = JSONArray(jsonStr.trim())
            val list = mutableListOf<IceServerConfig>()
            for (i in 0 until arr.length()) {
                val obj = arr.optJSONObject(i) ?: continue
                fromJson(obj)?.let { list.add(it) }
            }
            list
        }.getOrDefault(emptyList())
    }
}

/** Supported TURN Provider types for Bring Your Own TURN (BYOT). */
enum class TurnProviderType(val label: String) {
    DEFAULT("DTN Mesh default"),        // shared fallback from the build (see DefaultTurnConfig)
    METERED("Your Metered account"),    // user's own Metered.ca account (API key or static credential)
    COTURN("Your TURN server"),         // self-hosted coturn (UDP, TCP, TLS; static or shared secret)
    CUSTOM_JSON("Custom ICE servers"),  // raw iceServers JSON array
}

/** Outcome of a TURN server test allocation or API credential verification. */
data class TurnTestResult(
    val ok: Boolean,
    val message: String,
    val serverCount: Int = 0,
    val tookMs: Long = 0L,
    val details: String = "",
)

