package com.dtn.mesh.receiver.webrtc

import android.util.Log
import com.dtn.mesh.service.DiagSource
import com.dtn.mesh.service.DiagnosticsLog
import com.dtn.mesh.settings.SettingsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.SocketTimeoutException
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/** Everything a provider may read, snapshotted from [SettingsRepository] once per resolution. */
data class TurnSettings(
    val type: TurnProviderType,
    val meteredDomain: String = "",
    val meteredApiKey: String = "",
    val meteredUsername: String = "",
    val meteredPassword: String = "",
    val coturnHost: String = "",
    val coturnPort: Int = IceServerFactory.DEFAULT_TURN_PORT,
    val coturnTlsPort: Int = IceServerFactory.DEFAULT_TURNS_PORT,
    val coturnUsername: String = "",
    val coturnPassword: String = "",
    val coturnSharedSecret: Boolean = false,
    val customJson: String = "",
) {
    /** Never let credentials reach logs via toString(). */
    override fun toString(): String = "TurnSettings(type=$type, meteredDomain=$meteredDomain, coturnHost=$coturnHost:$coturnPort)"
}

/** Which infrastructure the ICE list actually points at (TURN usage is billed to that account). */
data class IceResolution(
    val servers: List<IceServerConfig>,
    /** Human-readable owner of the TURN allocation, e.g. "Your Metered account (my.metered.live)". */
    val source: String,
    /** The configured provider failed or is incomplete, so the shared default (or STUN only) is used. */
    val fellBack: Boolean = false,
    val reason: String = "",
)

/**
 * One TURN / ICE provider. Adding one (Cloudflare TURN, a Worker-minted credential, …) = a new
 * [TurnProviderType], a class here, and its settings: the WebRTC transport only ever asks
 * [TurnConfigManager.resolve] for a list and never knows which provider produced it.
 * Returns null when the provider isn't configured or can't produce servers right now.
 */
interface IceServerProvider {
    val type: TurnProviderType
    suspend fun resolve(s: TurnSettings): IceResolution?
}

/**
 * Bring Your Own TURN: each device builds its RTCPeerConnection from ITS OWN locally configured
 * ICE servers. Nothing here is ever put in signaling or sent to a peer. In a WebRTC session each
 * side gathers relay candidates from its own TURN account; whichever candidate pair ICE selects
 * decides whose allocation carries the traffic (A's, B's, or one each in a relay-relay pair), so
 * usage is billed to the account actually used and quotas are never pooled.
 */
@Singleton
class TurnConfigManager @Inject constructor(
    private val settings: SettingsRepository,
    private val diagnostics: DiagnosticsLog,
    private val identity: com.dtn.mesh.crypto.DtnIdentity,
) {
    @Volatile private var workerTurnCache: Pair<List<IceServerConfig>, Long>? = null
    companion object {
        private const val TAG = "TurnConfigManager"
        private const val CACHE_TTL_MS = 30 * 60_000L
        private const val COTURN_SECRET_TTL_SEC = 6 * 3600L
    }

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .followRedirects(false) // an API key must never follow a redirect to another host
        .build()

    /** "domain|key hash" → (servers, fetched at). API keys are hashed so the map holds no secret. */
    private val meteredCache = ConcurrentHashMap<String, Pair<List<IceServerConfig>, Long>>()

    private val _lastResolution = MutableStateFlow<IceResolution?>(null)
    /** What the last [resolve] actually used (shown on the TURN page). */
    val lastResolution: StateFlow<IceResolution?> = _lastResolution.asStateFlow()

    // ── Providers ────────────────────────────────────────────────────────

    private val defaultProvider = object : IceServerProvider {
        override val type = TurnProviderType.DEFAULT
        override suspend fun resolve(s: TurnSettings): IceResolution {
            if (com.dtn.mesh.BuildConfig.WORKER_HOST.isNotBlank()) {
                fetchWorkerTurn(com.dtn.mesh.BuildConfig.WORKER_HOST).getOrNull()?.let {
                    // Only a list WITH TURN counts: an unconfigured Worker answers STUN only, and the
                    // build's stop-gap TURN below is better than that.
                    if (it.any { s -> s.isTurn }) return IceResolution(it, "Cloudflare Worker TURN (${com.dtn.mesh.BuildConfig.WORKER_HOST})")
                }
            }
            if (DefaultTurnConfig.hasSharedApiKey) {
                fetchMeteredCached(DefaultTurnConfig.DEFAULT_METERED_DOMAIN, DefaultTurnConfig.DEFAULT_METERED_API_KEY)
                    .getOrNull()?.let { return IceResolution(it, "DTN Mesh shared TURN (${DefaultTurnConfig.DEFAULT_METERED_DOMAIN})") }
            }
            val fallback = DefaultTurnConfig.FALLBACK_ICE_SERVERS
            val turn = fallback.any { it.isTurn }
            return IceResolution(
                fallback,
                if (turn) "DTN Mesh shared TURN (static credential)" else "Public STUN only (no TURN configured in this build)",
            )
        }
    }

    private val meteredProvider = object : IceServerProvider {
        override val type = TurnProviderType.METERED
        override suspend fun resolve(s: TurnSettings): IceResolution? {
            val domain = cleanDomain(s.meteredDomain)
            if (s.meteredApiKey.isNotBlank() && domain.isNotBlank()) {
                val r = fetchMeteredCached(domain, s.meteredApiKey)
                r.getOrNull()?.let { return IceResolution(it, "Your Metered account ($domain)") }
                // An API key was configured but failed: try the static credential before giving up.
                if (s.meteredUsername.isBlank() || s.meteredPassword.isBlank()) return null
            }
            if (s.meteredUsername.isNotBlank() && s.meteredPassword.isNotBlank()) {
                return IceResolution(IceServerFactory.meteredStatic(s.meteredUsername, s.meteredPassword),
                    "Your Metered account (static credential)")
            }
            return null
        }
    }

    private val coturnProvider = object : IceServerProvider {
        override val type = TurnProviderType.COTURN
        override suspend fun resolve(s: TurnSettings): IceResolution? {
            if (s.coturnHost.isBlank()) return null
            val (user, pass) = coturnCredential(s) ?: return null
            return IceResolution(IceServerFactory.coturn(s.coturnHost, s.coturnPort, s.coturnTlsPort, user, pass),
                "Your TURN server (${s.coturnHost})")
        }
    }

    private val customJsonProvider = object : IceServerProvider {
        override val type = TurnProviderType.CUSTOM_JSON
        override suspend fun resolve(s: TurnSettings): IceResolution? {
            val parsed = IceServerConfig.parseJsonArray(s.customJson).filter { it.hasValidUrls }
            if (parsed.isEmpty()) return null
            return IceResolution(parsed, "Your custom ICE servers")
        }
    }

    private val providers: Map<TurnProviderType, IceServerProvider> =
        listOf(defaultProvider, meteredProvider, coturnProvider, customJsonProvider).associateBy { it.type }

    // ── Resolution ───────────────────────────────────────────────────────

    /** ICE servers for this device's next RTCPeerConnection. Never throws; never empty. */
    suspend fun resolve(): IceResolution = withContext(Dispatchers.IO) {
        val s = snapshot()
        val chosen = runCatching { providers.getValue(s.type).resolve(s) }
            .onFailure { Log.w(TAG, "provider ${s.type} failed: ${it.javaClass.simpleName}") }
            .getOrNull()
        val result = if (chosen != null && chosen.servers.isNotEmpty()) chosen else {
            val d = defaultProvider.resolve(s)
            val why = "${s.type.label} is not set up or unreachable"
            if (s.type != TurnProviderType.DEFAULT) {
                diagnostics.w(DiagSource.RELAY, "TURN: $why; using ${d.source} instead (its quota, not yours)")
            }
            d.copy(fellBack = s.type != TurnProviderType.DEFAULT, reason = why)
        }
        _lastResolution.value = result
        result
    }

    suspend fun resolveActiveIceServers(): List<IceServerConfig> = resolve().servers

    private suspend fun snapshot(): TurnSettings = TurnSettings(
        type = runCatching { TurnProviderType.valueOf(settings.turnProviderTypeOnce()) }.getOrDefault(TurnProviderType.DEFAULT),
        meteredDomain = settings.turnMeteredDomainOnce(),
        meteredApiKey = settings.turnMeteredApiKeyOnce(),
        meteredUsername = settings.turnMeteredUsernameOnce(),
        meteredPassword = settings.turnMeteredPasswordOnce(),
        coturnHost = settings.turnCustomHostOnce(),
        coturnPort = settings.turnCustomPortOnce(),
        coturnTlsPort = settings.turnCustomTlsPortOnce(),
        coturnUsername = settings.turnCustomUsernameOnce(),
        coturnPassword = settings.turnCustomPasswordOnce(),
        coturnSharedSecret = settings.turnCustomUseSharedSecretOnce(),
        customJson = settings.turnCustomJsonOnce(),
    )

    /** Static user/password, or a fresh time-limited credential derived from coturn's shared secret. */
    private fun coturnCredential(s: TurnSettings): Pair<String, String>? {
        if (s.coturnPassword.isBlank()) return null
        if (s.coturnSharedSecret) {
            return IceServerFactory.coturnSharedSecretCredential(
                s.coturnPassword, s.coturnUsername, System.currentTimeMillis() / 1000L, COTURN_SECRET_TTL_SEC,
            )
        }
        if (s.coturnUsername.isBlank()) return null
        return s.coturnUsername to s.coturnPassword
    }

    // ── Metered REST ─────────────────────────────────────────────────────

    private fun cleanDomain(d: String) = d.trim().removePrefix("https://").removePrefix("http://").substringBefore('/').trimEnd('.')

    private fun fetchWorkerTurn(host: String): Result<List<IceServerConfig>> = runCatching {
        if (host.isBlank()) return@runCatching emptyList()
        workerTurnCache?.let { (servers, at) -> if (System.currentTimeMillis() - at < CACHE_TTL_MS) return@runCatching servers }
        // node = our id: the Worker caches one credential per node and rate-limits per node and IP.
        val url = "https://$host/turn?node=" + "%08x".format(identity.nodeNum32)
        val req = Request.Builder().url(url).get().build()
        httpClient.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw java.io.IOException("HTTP ${resp.code}")
            val body = resp.body?.string().orEmpty()
            val json = org.json.JSONObject(body)
            val arr = json.optJSONArray("iceServers") ?: return@use emptyList()
            IceServerConfig.parseJsonArray(arr.toString()).filter { it.hasValidUrls }
                .also { if (it.any { s -> s.isTurn }) workerTurnCache = it to System.currentTimeMillis() }
        }
    }

    private fun fetchMeteredCached(domain: String, apiKey: String): Result<List<IceServerConfig>> {
        val key = "$domain|${apiKey.hashCode()}"
        val now = System.currentTimeMillis()
        meteredCache[key]?.let { (servers, at) -> if (now - at < CACHE_TTL_MS) return Result.success(servers) }
        return fetchMetered(domain, apiKey).onSuccess { meteredCache[key] = it to now }
    }

    /**
     * Metered's credential endpoint: `GET https://<app>.metered.live/api/v1/turn/credentials?apiKey=…`,
     * returning an iceServers array. Over HTTPS only, no redirects; the key is never logged.
     */
    private fun fetchMetered(domain: String, apiKey: String): Result<List<IceServerConfig>> = runCatching {
        val url = "https://$domain/api/v1/turn/credentials".toHttpUrlOrNull()?.newBuilder()
            ?.addQueryParameter("apiKey", apiKey)?.build() ?: error("invalid domain")
        httpClient.newCall(Request.Builder().url(url).build()).execute().use { response ->
            if (!response.isSuccessful) error("HTTP ${response.code}")
            val body = response.body?.string().orEmpty()
            val servers = IceServerConfig.parseJsonArray(body).filter { it.hasValidUrls }
            if (servers.isEmpty()) error("no ICE servers in the response")
            servers
        }
    }.onFailure { Log.w(TAG, "Metered credential fetch from $domain failed: ${it.message}") }

    // ── Test (never saves) ───────────────────────────────────────────────

    /**
     * Checks a proposed configuration. Only claims what it verified: a Metered API key is
     * verified by fetching; a coturn host by a STUN Binding round trip. TURN credentials
     * themselves (static or derived) can only be proven by an allocation during a real session,
     * so the result says "not verified" for them.
     */
    suspend fun testConfiguration(s: TurnSettings): TurnTestResult = withContext(Dispatchers.IO) {
        val t0 = System.currentTimeMillis()
        fun took() = System.currentTimeMillis() - t0
        try {
            when (s.type) {
                TurnProviderType.DEFAULT -> {
                    val r = defaultProvider.resolve(s)
                    TurnTestResult(r.servers.isNotEmpty(), r.source, r.servers.size, took())
                }
                TurnProviderType.METERED -> {
                    val domain = cleanDomain(s.meteredDomain)
                    when {
                        s.meteredApiKey.isNotBlank() && domain.isBlank() ->
                            TurnTestResult(false, "Enter your Metered app domain (e.g. myapp.metered.live)")
                        s.meteredApiKey.isNotBlank() -> fetchMetered(domain, s.meteredApiKey).fold(
                            onSuccess = { TurnTestResult(true, "Metered API key works: ${it.size} ICE servers", it.size, took(), "From $domain") },
                            onFailure = { TurnTestResult(false, "Metered rejected the request (${it.message}). Check the domain and API key.", tookMs = took()) },
                        )
                        s.meteredUsername.isNotBlank() && s.meteredPassword.isNotBlank() -> TurnTestResult(
                            true, "Static credential saved, not verified", 5, took(),
                            "TURN credentials are only checked when a session allocates a relay.",
                        )
                        else -> TurnTestResult(false, "Enter a Metered API key, or a static username and password")
                    }
                }
                TurnProviderType.COTURN -> {
                    if (s.coturnHost.isBlank()) return@withContext TurnTestResult(false, "Enter the TURN server host")
                    val rtt = stunBindingRoundTrip(s.coturnHost, s.coturnPort)
                    val cred = coturnCredential(s)
                    val count = IceServerFactory.coturn(s.coturnHost, s.coturnPort, s.coturnTlsPort, cred?.first, cred?.second).size
                    if (rtt == null) TurnTestResult(false, "No STUN reply from ${s.coturnHost}:${s.coturnPort} (UDP)", tookMs = took(),
                        details = "The host may block UDP; TURN over TCP/TLS might still work.")
                    else TurnTestResult(true, "STUN reply from ${s.coturnHost}:${s.coturnPort} in $rtt ms", count, took(),
                        if (cred == null) "No credential: STUN only, no relay." else "Credentials not verified until a session allocates a relay.")
                }
                TurnProviderType.CUSTOM_JSON -> {
                    val parsed = IceServerConfig.parseJsonArray(s.customJson).filter { it.hasValidUrls }
                    if (parsed.isNotEmpty()) TurnTestResult(true, "Parsed ${parsed.size} ICE servers (not contacted)", parsed.size, took())
                    else TurnTestResult(false, "Not a JSON array of ICE servers with stun:/turn:/turns: URLs")
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "TURN test failed: ${e.javaClass.simpleName}")
            TurnTestResult(false, "Test failed: ${e.javaClass.simpleName}", tookMs = took())
        }
    }

    /** RFC 5389 Binding Request; the round trip in ms when a matching Binding Success arrives, else null. */
    private fun stunBindingRoundTrip(host: String, port: Int): Long? {
        val address = InetAddress.getAllByName(host.trim().removeSurrounding("[", "]")).firstOrNull() ?: return null
        val txId = ByteArray(12).also { SecureRandom().nextBytes(it) }
        val req = ByteArray(20)
        req[1] = 0x01 // Binding Request, length 0
        req[4] = 0x21; req[5] = 0x12; req[6] = 0xA4.toByte(); req[7] = 0x42 // magic cookie
        System.arraycopy(txId, 0, req, 8, 12)
        DatagramSocket().use { socket ->
            socket.soTimeout = 3000
            val t0 = System.currentTimeMillis()
            socket.send(DatagramPacket(req, req.size, address, port))
            val buf = ByteArray(512)
            val resp = DatagramPacket(buf, buf.size)
            repeat(3) {
                try { socket.receive(resp) } catch (_: SocketTimeoutException) { return null }
                val ok = resp.length >= 20 && buf[0] == 0x01.toByte() && buf[1] == 0x01.toByte() &&
                    (8 until 20).all { buf[it] == txId[it - 8] }
                if (ok) return System.currentTimeMillis() - t0
            }
        }
        return null
    }
}
