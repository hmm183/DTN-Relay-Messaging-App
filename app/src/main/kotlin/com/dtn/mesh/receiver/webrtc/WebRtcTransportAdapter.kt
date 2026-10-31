package com.dtn.mesh.receiver.webrtc

import android.content.Context
import android.os.PowerManager
import android.util.Log
import com.dtn.mesh.crypto.DtnIdentity
import com.dtn.mesh.database.dao.ContactDao
import com.dtn.mesh.database.entity.ContactEntity
import com.dtn.mesh.model.DtnMessage
import com.dtn.mesh.model.NodeId
import com.dtn.mesh.receiver.DeliveryOutcome
import com.dtn.mesh.receiver.DeliveryStatusEvent
import com.dtn.mesh.receiver.DtnTransport
import com.dtn.mesh.receiver.DtnWireCodec
import com.dtn.mesh.receiver.InboundPacket
import com.dtn.mesh.receiver.InternetTransportAdapter
import com.dtn.mesh.receiver.MeshConnectionState
import com.dtn.mesh.receiver.NodeEncounterEvent
import com.dtn.mesh.receiver.TransportKind
import com.dtn.mesh.service.DiagSource
import com.dtn.mesh.service.DiagnosticsLog
import com.dtn.mesh.settings.SettingsRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import org.webrtc.DataChannel
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.MediaStream
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.RtpReceiver
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import java.nio.ByteBuffer
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume

/**
 * WebRTC direct phone-to-phone data channels (experimental, Settings › Internet direct).
 *
 * - Only added (not blocked) contacts with pinned keys. Opened opportunistically when a DM to the
 *   contact goes over the relay ([maybeConnect]), at most once a minute per contact, never with
 *   battery saver on. ≤ [MAX_SESSIONS] sessions (least recently used closed), 10 min idle close.
 * - Signaling: one signed + encrypted offer and answer ([WebRtcSignal]) over both relays, no
 *   trickle ICE (gathering is awaited, ≤ [ICE_GATHERING_TIMEOUT_MS]). The DTLS fingerprint is in
 *   the signed SDP, so WebRTC's own DTLS check authenticates the channel: OPEN = verified.
 * - Glare: W3C perfect negotiation, polite = lower node id as UNSIGNED 32-bit.
 * - A verified session is a direct link (SendPath.WEBRTC, reachesTargetDirectly): Sent on write,
 *   Delivered only on the receipt, custody / summaries like BLE. Route queries are never answered
 *   for WebRTC peers ([com.dtn.mesh.receiver.MeshTransport.isPhysicallyReachable]).
 */
@Singleton
class WebRtcTransportAdapter @Inject constructor(
    @ApplicationContext private val context: Context,
    private val identity: DtnIdentity,
    private val settings: SettingsRepository,
    private val contactDao: ContactDao,
    private val turnConfig: TurnConfigManager,
    private val diagnostics: DiagnosticsLog,
    private val internetTransport: InternetTransportAdapter,
) : DtnTransport {

    companion object {
        private const val TAG = "WebRtcTransport"
        const val MAX_SESSIONS = 4
        const val IDLE_TIMEOUT_MS = 10 * 60_000L
        /** A negotiation that hasn't opened its channel by then is abandoned. */
        private const val NEGOTIATION_TIMEOUT_MS = 30_000L
        private const val ICE_GATHERING_TIMEOUT_MS = 5_000L
        /** At most one connection attempt per contact this often. */
        private const val RETRY_INTERVAL_MS = 60_000L
        private const val LABEL = "dtn-mesh-data"
        /** Session ids are remembered this long for replay rejection (> 2 × the freshness window). */
        private const val SEEN_RETENTION_MS = 5 * 60_000L
    }

    override val transportName: String = "WebRTC Direct"

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val packetIds = AtomicInteger(7_000_000)

    private val _connectionState = MutableStateFlow(MeshConnectionState.DISCONNECTED)
    override val connectionState: StateFlow<MeshConnectionState> = _connectionState.asStateFlow()
    override val isConnected: Boolean get() = _connectionState.value == MeshConnectionState.CONNECTED
    override val localNodeId: NodeId get() = identity.nodeId

    private val _inboundMessages = MutableSharedFlow<InboundPacket>(extraBufferCapacity = 64)
    override val inboundMessages: Flow<InboundPacket> = _inboundMessages.asSharedFlow()
    private val _nodeEvents = MutableSharedFlow<NodeEncounterEvent>(extraBufferCapacity = 32)
    override val nodeEvents: Flow<NodeEncounterEvent> = _nodeEvents.asSharedFlow()
    private val _deliveryStatus = MutableSharedFlow<DeliveryStatusEvent>(extraBufferCapacity = 64)
    override val deliveryStatus: Flow<DeliveryStatusEvent> = _deliveryStatus.asSharedFlow()

    private val _directPeers = MutableStateFlow<Set<String>>(emptySet())
    /** Contacts with an open, verified data channel right now. */
    val directPeers: StateFlow<Set<String>> = _directPeers.asStateFlow()

    @Volatile private var factory: PeerConnectionFactory? = null
    private val sessions = ConcurrentHashMap<String, Session>()
    private val lastAttemptMs = ConcurrentHashMap<String, Long>()
    private val seenSessionIds = ConcurrentHashMap<String, Long>()
    private var reaperJob: Job? = null

    private class Session(val peer: NodeId) {
        lateinit var pc: PeerConnection
        @Volatile var dc: DataChannel? = null
        @Volatile var sessionId: String = ""
        @Volatile var makingOffer = false
        @Volatile var open = false
        val createdAtMs = System.currentTimeMillis()
        @Volatile var lastActiveMs = createdAtMs
        val gathered = CompletableDeferred<Unit>()
    }

    init {
        internetTransport.onWebRtcSignal = { json -> handleIncomingSignal(json) }
        scope.launch {
            settings.internetDirectEnabled.collectLatest { on -> if (on) connect() else disconnect() }
        }
    }

    // ── Lifecycle ────────────────────────────────────────────────────────

    private fun initFactory(): Boolean {
        if (factory != null) return true
        return try {
            PeerConnectionFactory.initialize(
                PeerConnectionFactory.InitializationOptions.builder(context)
                    .setEnableInternalTracer(false).createInitializationOptions(),
            )
            factory = PeerConnectionFactory.builder().setOptions(PeerConnectionFactory.Options()).createPeerConnectionFactory()
            true
        } catch (t: Throwable) {
            // Missing / broken native library: switch the feature off instead of crashing.
            Log.e(TAG, "WebRTC native library failed to load", t)
            diagnostics.w(DiagSource.RELAY, "Internet direct turned off: the WebRTC library could not be loaded on this phone")
            scope.launch { settings.setInternetDirectEnabled(false) }
            false
        }
    }

    override suspend fun connect(): Boolean {
        if (!initFactory()) { _connectionState.value = MeshConnectionState.DISCONNECTED; return false }
        _connectionState.value = MeshConnectionState.CONNECTED
        startReaper()
        return true
    }

    override fun disconnect() {
        _connectionState.value = MeshConnectionState.DISCONNECTED
        reaperJob?.cancel(); reaperJob = null
        sessions.keys.toList().forEach { key -> sessions[key]?.let { close(key, it) } }
    }

    // ── Reachability / send ──────────────────────────────────────────────

    fun isPeerReachable(peer: NodeId): Boolean {
        val s = sessions[peer.value] ?: return false
        return s.open && s.dc?.state() == DataChannel.State.OPEN
    }

    fun isDirectConnected(peer: NodeId): Boolean = isPeerReachable(peer)

    suspend fun getLocalIceServers(): List<IceServerConfig> = turnConfig.resolveActiveIceServers()

    override suspend fun sendMessage(message: DtnMessage, targetPeer: NodeId): Int? {
        val s = sessions[targetPeer.value]
        val dc = s?.dc
        if (s == null || !s.open || dc == null || dc.state() != DataChannel.State.OPEN) {
            maybeConnect(targetPeer)
            return null
        }
        return try {
            val wire = DtnWireCodec.encode(message)
            if (!dc.send(DataChannel.Buffer(ByteBuffer.wrap(wire), true))) return null
            s.lastActiveMs = System.currentTimeMillis()
            packetIds.incrementAndGet().also {
                _deliveryStatus.tryEmit(DeliveryStatusEvent(meshPacketId = it, status = DeliveryOutcome.DELIVERED))
            }
        } catch (e: Exception) {
            Log.w(TAG, "WebRTC send to ${targetPeer.value.takeLast(8)} failed", e)
            null
        }
    }

    /** Open a session to [peer] if allowed and none exists (non-blocking, rate-limited). */
    fun maybeConnect(peer: NodeId) {
        if (!isConnected || peer == identity.nodeId || sessions.containsKey(peer.value)) return
        if (context.getSystemService(PowerManager::class.java)?.isPowerSaveMode == true) return
        val now = System.currentTimeMillis()
        val last = lastAttemptMs[peer.value] ?: 0L
        if (now - last < RETRY_INTERVAL_MS) return
        lastAttemptMs[peer.value] = now
        scope.launch { runCatching { initiateOffer(peer) }.onFailure { Log.w(TAG, "offer to ${peer.value.takeLast(8)} failed", it) } }
    }

    // ── Negotiation ──────────────────────────────────────────────────────

    /** Added, not blocked, keys pinned. */
    private suspend fun eligibleContact(peer: NodeId): ContactEntity? {
        val c = contactDao.getByNodeId(peer.value) ?: return null
        if (!c.isAdded || c.xPub == null || c.edPub == null) return null
        if (peer.value in runCatching { settings.blockedNodesOnce() }.getOrDefault(emptySet())) return null
        return c
    }

    private fun isPolite(peer: NodeId): Boolean =
        Integer.toUnsignedLong(identity.nodeNum32) < Integer.toUnsignedLong(peer.toNodeNum32())

    private suspend fun initiateOffer(peer: NodeId) {
        val contact = eligibleContact(peer) ?: return
        if (sessions.containsKey(peer.value)) return
        evictIfFull()
        val s = newSession(peer) ?: return
        s.sessionId = UUID.randomUUID().toString().replace("-", "").take(16)
        s.makingOffer = true
        sessions[peer.value] = s
        attach(s, s.pc.createDataChannel(LABEL, DataChannel.Init().apply { ordered = true }))
        val offer = s.pc.create(offer = true) ?: return fail(s, "createOffer")
        if (!s.pc.setDesc(local = true, offer)) return fail(s, "setLocal(offer)")
        withTimeoutOrNull(ICE_GATHERING_TIMEOUT_MS) { s.gathered.await() }
        val sdp = s.pc.localDescription?.description ?: return fail(s, "no local SDP")
        val sealed = WebRtcSignal.signAndSeal(WebRtcSignal.Offer(sdp, s.sessionId), identity, peer.value, contact.xPub!!)
        internetTransport.sendWebRtcSignal(peer, sealed)
        s.makingOffer = false
    }

    private fun handleIncomingSignal(json: String) {
        if (!isConnected) return
        scope.launch {
            runCatching {
                val sender = org.json.JSONObject(json).getString("sender").lowercase()
                if (sender.length != 8 || !sender.all { it in '0'..'9' || it in 'a'..'f' }) return@launch
                val peer = NodeId("!$sender")
                val contact = eligibleContact(peer) ?: return@launch
                val signal = WebRtcSignal.openAndVerify(json, identity, contact, isSessionSeen = ::isSeen) ?: return@launch
                remember(signal.sessionId)
                when (signal) {
                    is WebRtcSignal.Offer -> onOffer(peer, contact, signal)
                    is WebRtcSignal.Answer -> onAnswer(peer, signal)
                }
            }.onFailure { Log.w(TAG, "bad WebRTC signal", it) }
        }
    }

    private suspend fun onOffer(peer: NodeId, contact: ContactEntity, offer: WebRtcSignal.Offer) {
        sessions[peer.value]?.let { existing ->
            val collision = !existing.open &&
                (existing.makingOffer || existing.pc.signalingState() != PeerConnection.SignalingState.STABLE)
            if (collision && !isPolite(peer)) {
                Log.i(TAG, "glare with ${peer.value.takeLast(8)}: keeping our offer (impolite)")
                return
            }
            close(peer.value, existing) // polite side yields, or the peer started over
        }
        evictIfFull()
        val s = newSession(peer) ?: return
        s.sessionId = offer.sessionId
        sessions[peer.value] = s
        if (!s.pc.setDesc(local = false, SessionDescription(SessionDescription.Type.OFFER, offer.sdp))) return fail(s, "setRemote(offer)")
        val answer = s.pc.create(offer = false) ?: return fail(s, "createAnswer")
        if (!s.pc.setDesc(local = true, answer)) return fail(s, "setLocal(answer)")
        withTimeoutOrNull(ICE_GATHERING_TIMEOUT_MS) { s.gathered.await() }
        val sdp = s.pc.localDescription?.description ?: return fail(s, "no local SDP")
        val sealed = WebRtcSignal.signAndSeal(WebRtcSignal.Answer(sdp, offer.sessionId), identity, peer.value, contact.xPub!!)
        internetTransport.sendWebRtcSignal(peer, sealed)
    }

    private suspend fun onAnswer(peer: NodeId, answer: WebRtcSignal.Answer) {
        val s = sessions[peer.value] ?: return
        if (s.sessionId != answer.sessionId) return
        if (s.pc.signalingState() != PeerConnection.SignalingState.HAVE_LOCAL_OFFER) return
        if (!s.pc.setDesc(local = false, SessionDescription(SessionDescription.Type.ANSWER, answer.sdp))) fail(s, "setRemote(answer)")
    }

    private fun fail(s: Session, step: String) {
        Log.w(TAG, "WebRTC negotiation with ${s.peer.value.takeLast(8)} failed at $step")
        close(s.peer.value, s)
    }

    private suspend fun newSession(peer: NodeId): Session? {
        val f = factory ?: return null
        val ice = getLocalIceServers().map { c ->
            PeerConnection.IceServer.builder(c.urls).apply {
                c.username?.takeIf { it.isNotBlank() }?.let { setUsername(it) }
                c.credential?.takeIf { it.isNotBlank() }?.let { setPassword(it) }
            }.createIceServer()
        }
        val config = PeerConnection.RTCConfiguration(ice).apply { sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN }
        val s = Session(peer)
        val pc = f.createPeerConnection(config, object : PeerConnection.Observer {
            override fun onSignalingChange(state: PeerConnection.SignalingState) {}
            override fun onIceConnectionChange(state: PeerConnection.IceConnectionState) {
                // DISCONNECTED is often transient (network switch); FAILED / CLOSED are final.
                if (state == PeerConnection.IceConnectionState.FAILED || state == PeerConnection.IceConnectionState.CLOSED) {
                    close(peer.value, s)
                }
            }
            override fun onIceConnectionReceivingChange(receiving: Boolean) {}
            override fun onIceGatheringChange(state: PeerConnection.IceGatheringState) {
                if (state == PeerConnection.IceGatheringState.COMPLETE) s.gathered.complete(Unit)
            }
            override fun onIceCandidate(candidate: IceCandidate) {}
            override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>) {}
            override fun onAddStream(stream: MediaStream) {}
            override fun onRemoveStream(stream: MediaStream) {}
            override fun onDataChannel(dc: DataChannel) = attach(s, dc)
            override fun onRenegotiationNeeded() {}
            override fun onAddTrack(receiver: RtpReceiver, mediaStreams: Array<out MediaStream>) {}
        }) ?: return null
        s.pc = pc
        return s
    }

    private fun attach(s: Session, dc: DataChannel) {
        s.dc = dc
        dc.registerObserver(object : DataChannel.Observer {
            override fun onBufferedAmountChange(previousAmount: Long) {}
            override fun onStateChange() {
                when (dc.state()) {
                    DataChannel.State.OPEN -> {
                        if (sessions[s.peer.value] !== s) return
                        s.open = true
                        s.lastActiveMs = System.currentTimeMillis()
                        updateDirectPeers()
                        diagnostics.i(DiagSource.RELAY, "Internet direct (WebRTC) link to ${s.peer.value.takeLast(8)} is up")
                        _nodeEvents.tryEmit(NodeEncounterEvent(nodeId = s.peer, rssi = 0, snr = 0f,
                            timestampMs = System.currentTimeMillis(), isOnline = true, transport = TransportKind.WEBRTC))
                    }
                    DataChannel.State.CLOSED -> close(s.peer.value, s)
                    else -> Unit
                }
            }
            override fun onMessage(buffer: DataChannel.Buffer) {
                s.lastActiveMs = System.currentTimeMillis()
                val data = ByteArray(buffer.data.remaining()).also { buffer.data.get(it) }
                val msg = DtnWireCodec.decode(data, rssi = 0, snr = 0f) ?: return
                _inboundMessages.tryEmit(InboundPacket(message = msg, channel = msg.channel, viaPeer = s.peer, transport = TransportKind.WEBRTC))
            }
        })
    }

    /** Close [s] if it is still the session for [key] (never a newer replacement). */
    private fun close(key: String, s: Session) {
        if (!sessions.remove(key, s)) return
        val wasOpen = s.open
        s.open = false
        runCatching { s.dc?.close() }
        runCatching { s.pc.close() }
        updateDirectPeers()
        if (wasOpen) {
            _nodeEvents.tryEmit(NodeEncounterEvent(nodeId = s.peer, rssi = 0, snr = 0f,
                timestampMs = System.currentTimeMillis(), isOnline = false, transport = TransportKind.WEBRTC))
        }
    }

    private fun evictIfFull() {
        while (sessions.size >= MAX_SESSIONS) {
            val lru = sessions.entries.minByOrNull { it.value.lastActiveMs } ?: return
            close(lru.key, lru.value)
        }
    }

    private fun updateDirectPeers() {
        _directPeers.value = sessions.filterValues { it.open && it.dc?.state() == DataChannel.State.OPEN }.keys.toSet()
    }

    private fun isSeen(id: String): Boolean = seenSessionIds.containsKey(id)

    private fun remember(id: String) {
        val now = System.currentTimeMillis()
        seenSessionIds.entries.removeIf { now - it.value > SEEN_RETENTION_MS }
        seenSessionIds[id] = now
    }

    private fun startReaper() {
        reaperJob?.cancel()
        reaperJob = scope.launch {
            while (isActive) {
                delay(30_000L)
                val now = System.currentTimeMillis()
                for ((key, s) in sessions.entries.toList()) {
                    val idle = s.open && now - s.lastActiveMs > IDLE_TIMEOUT_MS
                    val stuck = !s.open && now - s.createdAtMs > NEGOTIATION_TIMEOUT_MS
                    if (idle || stuck) close(key, s)
                }
            }
        }
    }

    // ── SDP callbacks as suspend functions ───────────────────────────────

    private suspend fun PeerConnection.create(offer: Boolean): SessionDescription? = suspendCancellableCoroutine { cont ->
        val obs = object : SdpObserver {
            override fun onCreateSuccess(d: SessionDescription?) { if (cont.isActive) cont.resume(d) }
            override fun onCreateFailure(e: String?) { if (cont.isActive) cont.resume(null) }
            override fun onSetSuccess() {}
            override fun onSetFailure(e: String?) {}
        }
        if (offer) createOffer(obs, MediaConstraints()) else createAnswer(obs, MediaConstraints())
    }

    private suspend fun PeerConnection.setDesc(local: Boolean, d: SessionDescription): Boolean = suspendCancellableCoroutine { cont ->
        val obs = object : SdpObserver {
            override fun onSetSuccess() { if (cont.isActive) cont.resume(true) }
            override fun onSetFailure(e: String?) { if (cont.isActive) cont.resume(false) }
            override fun onCreateSuccess(d: SessionDescription?) {}
            override fun onCreateFailure(e: String?) {}
        }
        if (local) setLocalDescription(obs, d) else setRemoteDescription(obs, d)
    }
}
