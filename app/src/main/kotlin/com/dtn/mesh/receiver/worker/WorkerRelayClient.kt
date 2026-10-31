package com.dtn.mesh.receiver.worker

import android.util.Log
import com.dtn.mesh.crypto.DtnIdentity
import com.dtn.mesh.model.DtnMessage
import com.dtn.mesh.model.NodeId
import com.dtn.mesh.receiver.InternetFrames
import com.dtn.mesh.service.DiagLevel
import com.dtn.mesh.service.DiagSource
import com.dtn.mesh.service.DiagnosticsLog
import com.dtn.mesh.settings.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import okio.ByteString.Companion.toByteString
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Layer A Dual-Homed Relay Client: connects to the Cloudflare Worker + Durable Object
 * relay WebSocket endpoint (wss://<host>/relay?node=<hex8>).
 *
 * Runs in parallel with HiveMQ MQTT. Every bundle sent over the internet is dispatched
 * over both transports. Since incoming bundles are deduplicated by bundle ID at the
 * MessageQueueManager level, having dual active relays ensures zero message loss even
 * if HiveMQ is overloaded or offline for hours.
 */
@Singleton
class WorkerRelayClient @Inject constructor(
    private val identity: DtnIdentity,
    private val settings: SettingsRepository,
    private val diagnostics: DiagnosticsLog,
) {
    companion object {
        private const val TAG = "WorkerRelayClient"
        /**
         * The Worker host from the build (`dtn.workerHost` in local.properties). A workers.dev
         * host is `<worker>.<account-subdomain>.workers.dev`; empty = this relay stays off.
         */
        val WORKER_HOST: String = com.dtn.mesh.BuildConfig.WORKER_HOST
        private const val MIN_BACKOFF_MS = 2_000L
        private const val MAX_BACKOFF_MS = 60_000L
        /** A full SDP offer (signed + encrypted, Base64) can reach ~6 KB with TURN candidates. */
        private const val MAX_FRAME_BYTES = 16 * 1024

        // Framing byte 0:
        const val KIND_DIRECT: Byte = 0
        const val KIND_BROADCAST: Byte = 1
        const val KIND_PRESENCE: Byte = 2
        const val KIND_ROUTE_QUERY: Byte = 3
        const val KIND_ROUTE_REPLY: Byte = 4
        const val KIND_WEBRTC_SIGNAL: Byte = 5
    }

    private val scope = CoroutineScope(Dispatchers.IO + Job())
    private val client = OkHttpClient.Builder()
        .readTimeout(0, TimeUnit.MILLISECONDS) // Keep-alive WebSocket
        .pingInterval(30, TimeUnit.SECONDS)
        .build()

    private var webSocket: WebSocket? = null
    private var reconnectJob: Job? = null
    @Volatile private var isWanted = false
    @Volatile private var backoffMs = MIN_BACKOFF_MS

    /** Route query from another phone: (asker hex, dest hex). */
    var onRouteQuery: ((askerNum: Int, destHex: String) -> Unit)? = null
    /** Route answer: (carrier num, framed body bytes). */
    var onRouteReply: ((carrierNum: Int, body: ByteArray) -> Unit)? = null

    val isConfigured: Boolean get() = WORKER_HOST.isNotBlank()

    private val _isConnected = MutableStateFlow(false)
    val isConnected: StateFlow<Boolean> = _isConnected.asStateFlow()

    private val _onlinePeers = MutableStateFlow<Set<String>>(emptySet())
    val onlinePeers: StateFlow<Set<String>> = _onlinePeers.asStateFlow()

    /** Callback invoked when an incoming bundle payload is received. */
    var onFrameReceived: ((wireBytes: ByteArray, publisherNum: Int?) -> Unit)? = null

    /** WebRTC signaling callback for Phase 6 direct connection negotiation. */
    var onWebRtcSignal: ((fromHex: String, signalJson: String) -> Unit)? = null

    fun start() {
        if (!isConfigured) return // no Worker deployed for this build
        if (isWanted) return
        isWanted = true
        connect()
    }

    fun stop() {
        isWanted = false
        reconnectJob?.cancel()
        reconnectJob = null
        webSocket?.close(1000, "Normal closure")
        webSocket = null
        _isConnected.value = false
        _onlinePeers.value = emptySet()
    }

    private fun connect() {
        if (!isWanted) return
        val nodeHex = "%08x".format(identity.nodeNum32)
        val host = WORKER_HOST
        val url = "wss://$host/relay?node=$nodeHex"

        val request = Request.Builder()
            .url(url)
            .build()

        webSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(ws: WebSocket, response: Response) {
                Log.i(TAG, "Cloudflare Worker relay connected")
                _isConnected.value = true
                backoffMs = MIN_BACKOFF_MS
                diagnostics.i(DiagSource.RELAY, "Connected to Cloudflare Worker dual-relay ($host)")
            }

            override fun onMessage(ws: WebSocket, text: String) {
                handleTextMessage(text)
            }

            override fun onMessage(ws: WebSocket, bytes: ByteString) {
                handleBinaryMessage(bytes.toByteArray())
            }

            override fun onClosing(ws: WebSocket, code: Int, reason: String) {
                ws.close(1000, null)
            }

            override fun onClosed(ws: WebSocket, code: Int, reason: String) {
                _isConnected.value = false
                _onlinePeers.value = emptySet()
                scheduleReconnect()
            }

            override fun onFailure(ws: WebSocket, t: Throwable, response: Response?) {
                Log.w(TAG, "Cloudflare Worker relay failed: ${t.message}")
                _isConnected.value = false
                _onlinePeers.value = emptySet()
                scheduleReconnect()
            }
        })
    }

    private fun scheduleReconnect() {
        if (!isWanted || reconnectJob?.isActive == true) return
        val wait = backoffMs
        backoffMs = (backoffMs * 2).coerceAtMost(MAX_BACKOFF_MS) // 2, 4, 8 … 60 s (a quota refusal must not hammer the Worker)
        reconnectJob = scope.launch {
            delay(wait)
            if (isWanted) {
                connect()
            }
        }
    }

    private fun handleTextMessage(text: String) {
        runCatching {
            val json = JSONObject(text)
            when (json.optString("type")) {
                "roster" -> {
                    val arr = json.optJSONArray("nodes") ?: return
                    val set = HashSet<String>()
                    for (i in 0 until arr.length()) {
                        set.add("!" + arr.getString(i).lowercase())
                    }
                    _onlinePeers.value = set
                }
                "presence" -> {
                    val node = json.optString("node").lowercase()
                    val online = json.optBoolean("online", false)
                    val id = "!$node"
                    val current = _onlinePeers.value.toMutableSet()
                    if (online) current.add(id) else current.remove(id)
                    _onlinePeers.value = current
                }
            }
        }
    }

    private fun handleBinaryMessage(bytes: ByteArray) {
        if (bytes.isEmpty() || bytes.size > MAX_FRAME_BYTES) return
        val kind = bytes[0]
        when (kind) {
            KIND_DIRECT -> {
                // [Kind=0][8B targetHex][Payload...]
                if (bytes.size <= 9) return
                val payload = bytes.copyOfRange(9, bytes.size)
                val unwrapped = InternetFrames.unwrap(payload)
                onFrameReceived?.invoke(unwrapped.wire, unwrapped.publisherNum)
            }
            KIND_BROADCAST -> {
                // [Kind=1][Payload...]
                if (bytes.size <= 1) return
                val payload = bytes.copyOfRange(1, bytes.size)
                val unwrapped = InternetFrames.unwrap(payload)
                onFrameReceived?.invoke(unwrapped.wire, unwrapped.publisherNum)
            }
            KIND_ROUTE_QUERY -> {
                // [Kind=3][8B destHex][framed "Q"]
                if (bytes.size <= 9) return
                val destHex = String(bytes, 1, 8, Charsets.US_ASCII).lowercase()
                val asker = InternetFrames.unwrap(bytes.copyOfRange(9, bytes.size)).publisherNum ?: return
                if (asker == identity.nodeNum32) return
                onRouteQuery?.invoke(asker, destHex)
            }
            KIND_ROUTE_REPLY -> {
                // [Kind=4][8B askerHex][framed "I_SEE:<hex>"]
                if (bytes.size <= 9) return
                val framed = InternetFrames.unwrap(bytes.copyOfRange(9, bytes.size))
                val carrier = framed.publisherNum ?: return
                onRouteReply?.invoke(carrier, framed.wire)
            }
            KIND_WEBRTC_SIGNAL -> {
                // [Kind=5][8B targetHex][8B senderHex][SignalJson...]
                if (bytes.size <= 17) return
                val senderHex = String(bytes, 9, 8, Charsets.US_ASCII)
                val signalJson = String(bytes, 17, bytes.size - 17, Charsets.UTF_8)
                onWebRtcSignal?.invoke(senderHex, signalJson)
            }
        }
    }

    fun sendDirect(dest: NodeId, wireBytes: ByteArray): Boolean {
        val ws = webSocket ?: return false
        val destHex = dest.value.removePrefix("!").padStart(8, '0').takeLast(8).lowercase()
        val wrapped = InternetFrames.wrap(identity.nodeNum32, wireBytes)
        val frame = ByteArray(1 + 8 + wrapped.size)
        frame[0] = KIND_DIRECT
        System.arraycopy(destHex.toByteArray(Charsets.US_ASCII), 0, frame, 1, 8)
        System.arraycopy(wrapped, 0, frame, 9, wrapped.size)
        return ws.send(frame.toByteString())
    }

    /** [kind] + 8-hex [targetHex] + our framed [body]: route queries / answers. */
    fun sendTargeted(kind: Byte, targetHex: String, body: ByteArray): Boolean {
        val ws = webSocket ?: return false
        if (!_isConnected.value) return false
        val wrapped = InternetFrames.wrap(identity.nodeNum32, body)
        val frame = ByteArray(1 + 8 + wrapped.size)
        frame[0] = kind
        System.arraycopy(targetHex.lowercase().padStart(8, '0').takeLast(8).toByteArray(Charsets.US_ASCII), 0, frame, 1, 8)
        System.arraycopy(wrapped, 0, frame, 9, wrapped.size)
        return ws.send(frame.toByteString())
    }

    fun sendBroadcast(wireBytes: ByteArray): Boolean {
        val ws = webSocket ?: return false
        val wrapped = InternetFrames.wrap(identity.nodeNum32, wireBytes)
        val frame = ByteArray(1 + wrapped.size)
        frame[0] = KIND_BROADCAST
        System.arraycopy(wrapped, 0, frame, 1, wrapped.size)
        return ws.send(frame.toByteString())
    }

    fun sendWebRtcSignal(dest: NodeId, signalJson: String): Boolean {
        val ws = webSocket ?: return false
        val destHex = dest.value.removePrefix("!").padStart(8, '0').takeLast(8).lowercase()
        val myHex = "%08x".format(identity.nodeNum32)
        val jsonBytes = signalJson.toByteArray(Charsets.UTF_8)
        val frame = ByteArray(1 + 8 + 8 + jsonBytes.size)
        frame[0] = KIND_WEBRTC_SIGNAL
        System.arraycopy(destHex.toByteArray(Charsets.US_ASCII), 0, frame, 1, 8)
        System.arraycopy(myHex.toByteArray(Charsets.US_ASCII), 0, frame, 9, 8)
        System.arraycopy(jsonBytes, 0, frame, 17, jsonBytes.size)
        return ws.send(frame.toByteString())
    }
}

