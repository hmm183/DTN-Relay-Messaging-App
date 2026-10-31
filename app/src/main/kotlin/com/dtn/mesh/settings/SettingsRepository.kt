package com.dtn.mesh.settings

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.util.Base64
import javax.inject.Inject
import javax.inject.Singleton

private val Context.dataStore by preferencesDataStore(name = "dtn_settings")

/**
 * User settings backed by Jetpack DataStore: display name (shown in announces), the optional
 * internet-relay transport (off by default), the MQTT broker URL, persisted routing algorithm,
 * chat theme, and per-contact state.
 */
@Singleton
class SettingsRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val keyVault: com.dtn.mesh.crypto.KeyVault,
) {
    private val store = context.dataStore

    val displayName: Flow<String> = store.data.map { it[KEY_NAME] ?: "" }
    val internetEnabled: Flow<Boolean> = store.data.map { it[KEY_INET] ?: false }
    val internetDirectEnabled: Flow<Boolean> = store.data.map { it[KEY_INTERNET_DIRECT] ?: false }
    val brokerUrl: Flow<String> = store.data.map { it[KEY_BROKER] ?: DEFAULT_BROKER }

    /** Discover mode: broadcast our name + keys to everyone (off: only to added contacts). */
    val discoverable: Flow<Boolean> = store.data.map { it[KEY_DISCOVER] ?: false }

    /** Selected routing algorithm name (defaults to EPIDEMIC). */
    val routingAlgorithm: Flow<String> = store.data.map { it[KEY_ALGO] ?: DEFAULT_ALGORITHM }

    /** Selected chat wallpaper/theme. */
    val chatTheme: Flow<String> = store.data.map { it[KEY_THEME] ?: DEFAULT_THEME }

    /** DiMaxProp λ (distance weight) and Q-learning η (progress-reward scale), 0..1. */
    val diMaxPropLambda: Flow<Double> = store.data.map { (it[KEY_LAMBDA] ?: DEFAULT_LAMBDA.toFloat()).toDouble() }
    val qShapingEta: Flow<Double> = store.data.map { (it[KEY_ETA] ?: DEFAULT_ETA.toFloat()).toDouble() }

    suspend fun displayNameOnce(): String = displayName.first()
    suspend fun internetEnabledOnce(): Boolean = internetEnabled.first()
    suspend fun internetDirectEnabledOnce(): Boolean = internetDirectEnabled.first()
    suspend fun brokerUrlOnce(): String = brokerUrl.first()
    suspend fun discoverableOnce(): Boolean = discoverable.first()
    suspend fun routingAlgorithmOnce(): String = routingAlgorithm.first()
    suspend fun chatThemeOnce(): String = chatTheme.first()

    suspend fun setDisplayName(name: String) { store.edit { it[KEY_NAME] = name } }
    suspend fun setInternetEnabled(enabled: Boolean) { store.edit { it[KEY_INET] = enabled } }
    suspend fun setInternetDirectEnabled(enabled: Boolean) { store.edit { it[KEY_INTERNET_DIRECT] = enabled } }
    suspend fun setBrokerUrl(url: String) { store.edit { it[KEY_BROKER] = url } }
    suspend fun setDiscoverable(on: Boolean) { store.edit { it[KEY_DISCOVER] = on } }
    suspend fun setRoutingAlgorithm(name: String) { store.edit { it[KEY_ALGO] = name } }
    suspend fun setChatTheme(theme: String) { store.edit { it[KEY_THEME] = theme } }
    /** Absolute path of the app-private copy of the gallery photo used as chat wallpaper. */
    val chatWallpaperPath: Flow<String?> = store.data.map { it[KEY_WALLPAPER_PATH] }
    suspend fun chatWallpaperPathOnce(): String? = chatWallpaperPath.first()
    /** Set the photo wallpaper (and select it) in one edit. */
    suspend fun setChatWallpaperPhoto(path: String) {
        store.edit { it[KEY_WALLPAPER_PATH] = path; it[KEY_THEME] = THEME_PHOTO }
    }

    val brokerUsername: Flow<String> = store.data.map { it[KEY_BROKER_USER] ?: "" }
    suspend fun brokerUsernameOnce(): String = brokerUsername.first()
    suspend fun setBrokerUsername(username: String) { store.edit { it[KEY_BROKER_USER] = username.trim() } }

    val brokerPasswordEnc: Flow<String> = store.data.map { it[KEY_BROKER_PASS_ENC] ?: "" }
    suspend fun brokerPasswordOnce(): String {
        val enc = brokerPasswordEnc.first()
        if (enc.isBlank()) return ""
        return runCatching {
            val bytes = java.util.Base64.getDecoder().decode(enc)
            String(keyVault.unwrap(bytes), Charsets.UTF_8)
        }.getOrDefault("")
    }
    suspend fun setBrokerPassword(password: String) {
        if (password.isBlank()) {
            store.edit { it.remove(KEY_BROKER_PASS_ENC) }
        } else {
            val wrapped = keyVault.wrap(password.toByteArray(Charsets.UTF_8))
            val enc = java.util.Base64.getEncoder().withoutPadding().encodeToString(wrapped)
            store.edit { it[KEY_BROKER_PASS_ENC] = enc }
        }
    }

    // ── BYOT (Bring Your Own TURN) Settings ──────────────────────────────────
    // Each provider has its OWN credential slots, so switching provider can never send one
    // provider's credential to another provider's server. Secrets (API key, passwords, shared
    // secret, the custom JSON which usually embeds credentials) are KeyVault-encrypted
    // (AndroidKeyStore AES-GCM); only ciphertext is in DataStore. Nothing here is ever sent to peers.

    private fun sealString(plain: String): String? = plain.takeIf { it.isNotBlank() }?.let {
        java.util.Base64.getEncoder().withoutPadding().encodeToString(keyVault.wrap(it.toByteArray(Charsets.UTF_8)))
    }
    private fun openString(enc: String?): String = if (enc.isNullOrBlank()) "" else runCatching {
        String(keyVault.unwrap(java.util.Base64.getDecoder().decode(enc)), Charsets.UTF_8)
    }.getOrDefault("")
    private suspend fun putSecret(key: androidx.datastore.preferences.core.Preferences.Key<String>, value: String) {
        val enc = sealString(value.trim())
        store.edit { if (enc == null) it.remove(key) else it[key] = enc }
    }

    val turnProviderType: Flow<String> = store.data.map { it[KEY_TURN_PROVIDER] ?: "DEFAULT" }
    suspend fun turnProviderTypeOnce(): String = turnProviderType.first()
    suspend fun setTurnProviderType(type: String) { store.edit { it[KEY_TURN_PROVIDER] = type } }

    // Metered (user's own account)
    val turnMeteredDomain: Flow<String> = store.data.map { it[KEY_TURN_METERED_DOMAIN] ?: "" }
    suspend fun turnMeteredDomainOnce(): String = turnMeteredDomain.first()
    suspend fun setTurnMeteredDomain(domain: String) { store.edit { it[KEY_TURN_METERED_DOMAIN] = domain.trim() } }
    suspend fun turnMeteredApiKeyOnce(): String = openString(store.data.first()[KEY_TURN_METERED_KEY_ENC])
    suspend fun setTurnMeteredApiKey(apiKey: String) = putSecret(KEY_TURN_METERED_KEY_ENC, apiKey)
    val turnMeteredUsername: Flow<String> = store.data.map { it[KEY_TURN_METERED_USER] ?: "" }
    suspend fun turnMeteredUsernameOnce(): String = turnMeteredUsername.first()
    suspend fun setTurnMeteredUsername(user: String) { store.edit { it[KEY_TURN_METERED_USER] = user.trim() } }
    suspend fun turnMeteredPasswordOnce(): String = openString(store.data.first()[KEY_TURN_METERED_PASS_ENC])
    suspend fun setTurnMeteredPassword(pass: String) = putSecret(KEY_TURN_METERED_PASS_ENC, pass)

    // coturn (self-hosted)
    val turnCustomHost: Flow<String> = store.data.map { it[KEY_TURN_CUSTOM_HOST] ?: "" }
    suspend fun turnCustomHostOnce(): String = turnCustomHost.first()
    suspend fun setTurnCustomHost(host: String) { store.edit { it[KEY_TURN_CUSTOM_HOST] = host.trim() } }
    val turnCustomPort: Flow<Int> = store.data.map { it[KEY_TURN_CUSTOM_PORT] ?: 3478 }
    suspend fun turnCustomPortOnce(): Int = turnCustomPort.first()
    suspend fun setTurnCustomPort(port: Int) { store.edit { it[KEY_TURN_CUSTOM_PORT] = port.coerceIn(1, 65535) } }
    /** TURNS (TLS) port; 0 = the server has no TLS listener. */
    val turnCustomTlsPort: Flow<Int> = store.data.map { it[KEY_TURN_CUSTOM_TLS_PORT] ?: 5349 }
    suspend fun turnCustomTlsPortOnce(): Int = turnCustomTlsPort.first()
    suspend fun setTurnCustomTlsPort(port: Int) { store.edit { it[KEY_TURN_CUSTOM_TLS_PORT] = port.coerceIn(0, 65535) } }
    /** true = the password field is coturn's static-auth-secret (REST API mode, time-limited creds). */
    val turnCustomUseSharedSecret: Flow<Boolean> = store.data.map { it[KEY_TURN_CUSTOM_SECRET_MODE] ?: false }
    suspend fun turnCustomUseSharedSecretOnce(): Boolean = turnCustomUseSharedSecret.first()
    suspend fun setTurnCustomUseSharedSecret(on: Boolean) { store.edit { it[KEY_TURN_CUSTOM_SECRET_MODE] = on } }
    val turnCustomUsername: Flow<String> = store.data.map { it[KEY_TURN_CUSTOM_USER] ?: "" }
    suspend fun turnCustomUsernameOnce(): String = turnCustomUsername.first()
    suspend fun setTurnCustomUsername(user: String) { store.edit { it[KEY_TURN_CUSTOM_USER] = user.trim() } }
    suspend fun turnCustomPasswordOnce(): String = openString(store.data.first()[KEY_TURN_CUSTOM_PASS_ENC])
    suspend fun setTurnCustomPassword(pass: String) = putSecret(KEY_TURN_CUSTOM_PASS_ENC, pass)

    // Custom iceServers JSON (encrypted: it normally carries credentials). A value that still
    // starts with '[' is a plaintext copy from the first BYOT build: read it, re-encrypt on save.
    val turnCustomJson: Flow<String> = store.data.map { p ->
        val raw = p[KEY_TURN_CUSTOM_JSON].orEmpty()
        if (raw.trimStart().startsWith("[")) raw else openString(raw)
    }
    suspend fun turnCustomJsonOnce(): String = turnCustomJson.first()
    suspend fun setTurnCustomJson(json: String) = putSecret(KEY_TURN_CUSTOM_JSON, json)
    suspend fun setDiMaxPropLambda(v: Double) { store.edit { it[KEY_LAMBDA] = v.coerceIn(0.0, 1.0).toFloat() } }
    suspend fun setQShapingEta(v: Double) { store.edit { it[KEY_ETA] = v.coerceIn(0.0, 1.0).toFloat() } }

    // ── Blocked contacts (decision 13) ───────────────────────────────────────

    /**
     * Blocked node ids: their DMs are dropped once the receipt is queued, their announces are
     * ignored and their group posts hidden. Relaying their bundles for others is unchanged.
     */
    val blockedNodes: Flow<Set<String>> = store.data.map { it[KEY_BLOCKED] ?: emptySet() }.distinctUntilChanged()

    suspend fun blockedNodesOnce(): Set<String> = blockedNodes.first()

    suspend fun setBlocked(nodeId: String, blocked: Boolean) {
        store.edit {
            val current = it[KEY_BLOCKED] ?: emptySet()
            it[KEY_BLOCKED] = if (blocked) current + nodeId else current - nodeId
        }
    }

    // ── Deleted contacts ─────────────────────────────────────────────────────

    /** Deleted contact → when (epoch ms). Hidden from Discovered until they announce after that. */
    val deletedContacts: Flow<Map<String, Long>> = store.data.map { longsWithPrefix(it, PREFIX_DELETED) }.distinctUntilChanged()

    suspend fun deletedAtOnce(nodeId: String): Long? = store.data.first()[longPreferencesKey(PREFIX_DELETED + nodeId)]

    suspend fun setDeleted(nodeId: String, atMs: Long) { store.edit { it[longPreferencesKey(PREFIX_DELETED + nodeId)] = atMs } }

    suspend fun clearDeleted(nodeId: String) { store.edit { it.remove(longPreferencesKey(PREFIX_DELETED + nodeId)) } }

    // ── Unread (per-chat last-read time) ─────────────────────────────────────

    /** Chat key (node id, "grp:<ch>", or the broadcast chat id) → last-read time (epoch ms). */
    val lastRead: Flow<Map<String, Long>> = store.data.map { longsWithPrefix(it, PREFIX_LAST_READ) }.distinctUntilChanged()

    /**
     * Messages up to this time count as read in a chat that has no last-read time yet (set once,
     * the first time this version runs), so an upgrade doesn't light up every old chat.
     */
    val unreadBaseline: Flow<Long?> = store.data.map { it[KEY_UNREAD_BASELINE] }.distinctUntilChanged()

    suspend fun ensureUnreadBaseline(nowMs: Long) {
        store.edit { if (it[KEY_UNREAD_BASELINE] == null) it[KEY_UNREAD_BASELINE] = nowMs }
    }

    /** Everything in [chatKey] up to [atMs] has been seen (never moves backwards). */
    suspend fun markRead(chatKey: String, atMs: Long) {
        store.edit {
            val key = longPreferencesKey(PREFIX_LAST_READ + chatKey)
            if ((it[key] ?: 0L) < atMs) it[key] = atMs
        }
    }

    suspend fun clearLastRead(chatKey: String) { store.edit { it.remove(longPreferencesKey(PREFIX_LAST_READ + chatKey)) } }

    // ── Key changes (decision 14) ────────────────────────────────────────────

    /** Issue time (epoch s) of the newest accepted announce for a contact's current keys. */
    suspend fun keyIssuedAtSec(nodeId: String): Long? = store.data.first()[longPreferencesKey(PREFIX_KEY_ISSUED + nodeId)]

    suspend fun setKeyIssuedAtSec(nodeId: String, sec: Long) {
        store.edit { it[longPreferencesKey(PREFIX_KEY_ISSUED + nodeId)] = sec }
    }

    /** New keys from a newer announce of a QR-verified contact, waiting for Accept or a QR re-scan. */
    suspend fun pendingKeys(nodeId: String): PendingKeys? =
        store.data.first()[stringPreferencesKey(PREFIX_PENDING + nodeId)]?.let(PendingKeys::decode)

    suspend fun setPendingKeys(nodeId: String, keys: PendingKeys) {
        store.edit { it[stringPreferencesKey(PREFIX_PENDING + nodeId)] = keys.encode() }
    }

    suspend fun clearPendingKeys(nodeId: String) { store.edit { it.remove(stringPreferencesKey(PREFIX_PENDING + nodeId)) } }

    /** Contacts whose keys changed while they were verified (the chat shows "Keys changed"). */
    val pendingKeyNodes: Flow<Set<String>> = store.data.map { prefs ->
        prefs.asMap().keys.map { it.name }.filter { it.startsWith(PREFIX_PENDING) }.map { it.removePrefix(PREFIX_PENDING) }.toSet()
    }.distinctUntilChanged()

    suspend fun hasPendingKeys(nodeId: String): Boolean = store.data.first()[stringPreferencesKey(PREFIX_PENDING + nodeId)] != null

    /** Contact → when its safety number last changed (epoch ms), for the notice in its chat. */
    val keyChangedAt: Flow<Map<String, Long>> = store.data.map { longsWithPrefix(it, PREFIX_KEY_CHANGED) }.distinctUntilChanged()

    suspend fun setKeyChangedAt(nodeId: String, atMs: Long) {
        store.edit { it[longPreferencesKey(PREFIX_KEY_CHANGED + nodeId)] = atMs }
    }

    // ── Group members invited with Create group ──────────────────────────────

    /**
     * Channel → node ids this phone sent the group's invite DM to (Create group › Add members).
     * Part of the group's known members for its receipts ("Received by 1 of 2").
     */
    val groupInvited: Flow<Map<Int, Set<String>>> = store.data.map { prefs ->
        prefs.asMap().entries.mapNotNull { (k, v) ->
            val ch = k.name.takeIf { it.startsWith(PREFIX_GROUP_INVITED) }?.removePrefix(PREFIX_GROUP_INVITED)?.toIntOrNull()
            val ids = (v as? Set<*>)?.filterIsInstance<String>()?.toSet()
            if (ch != null && ids != null) ch to ids else null
        }.toMap()
    }.distinctUntilChanged()

    suspend fun addGroupInvited(channel: Int, nodeIds: Collection<String>) {
        if (nodeIds.isEmpty()) return
        store.edit {
            val key = stringSetPreferencesKey(PREFIX_GROUP_INVITED + channel)
            it[key] = (it[key] ?: emptySet()) + nodeIds
        }
    }

    /** Leaving a group forgets who was invited to it (the slot may later hold another group). */
    suspend fun clearGroupInvited(channel: Int) { store.edit { it.remove(stringSetPreferencesKey(PREFIX_GROUP_INVITED + channel)) } }

    private fun longsWithPrefix(prefs: Preferences, prefix: String): Map<String, Long> =
        prefs.asMap().entries.mapNotNull { (k, v) ->
            if (k.name.startsWith(prefix) && v is Long) k.name.removePrefix(prefix) to v else null
        }.toMap()

    companion object {
        /** TLS by default: hides topics/presence from the network path (payloads are E2E anyway). */
        const val DEFAULT_BROKER = com.dtn.mesh.receiver.RelayEndpoints.DEFAULT_URL
        const val DEFAULT_LAMBDA = 0.5
        const val DEFAULT_ETA = 0.2
        const val DEFAULT_ALGORITHM = "EPIDEMIC"
        const val DEFAULT_THEME = "DEFAULT"
        private val KEY_NAME = stringPreferencesKey("display_name")
        private val KEY_INET = booleanPreferencesKey("internet_enabled")
        private val KEY_BROKER = stringPreferencesKey("broker_url")
        private val KEY_BROKER_USER = stringPreferencesKey("broker_username")
        private val KEY_BROKER_PASS_ENC = stringPreferencesKey("broker_password_enc")
        private val KEY_TURN_PROVIDER = stringPreferencesKey("turn_provider")
        private val KEY_TURN_METERED_DOMAIN = stringPreferencesKey("turn_metered_domain")
        private val KEY_TURN_METERED_KEY_ENC = stringPreferencesKey("turn_metered_key_enc")
        private val KEY_TURN_CUSTOM_HOST = stringPreferencesKey("turn_custom_host")
        private val KEY_TURN_CUSTOM_PORT = intPreferencesKey("turn_custom_port")
        private val KEY_TURN_CUSTOM_USER = stringPreferencesKey("turn_custom_user")
        private val KEY_TURN_CUSTOM_PASS_ENC = stringPreferencesKey("turn_custom_pass_enc")
        private val KEY_TURN_CUSTOM_JSON = stringPreferencesKey("turn_custom_json")
        private val KEY_TURN_METERED_USER = stringPreferencesKey("turn_metered_user")
        private val KEY_TURN_METERED_PASS_ENC = stringPreferencesKey("turn_metered_pass_enc")
        private val KEY_TURN_CUSTOM_TLS_PORT = intPreferencesKey("turn_custom_tls_port")
        private val KEY_TURN_CUSTOM_SECRET_MODE = booleanPreferencesKey("turn_custom_secret_mode")
        private val KEY_INTERNET_DIRECT = booleanPreferencesKey("internet_direct_experimental")
        private val KEY_DISCOVER = booleanPreferencesKey("discoverable")
        private val KEY_ALGO = stringPreferencesKey("routing_algorithm")
        private val KEY_THEME = stringPreferencesKey("chat_theme")
        private val KEY_WALLPAPER_PATH = stringPreferencesKey("chat_wallpaper_path")
        /** [chatTheme] value for the gallery-photo wallpaper. */
        const val THEME_PHOTO = "PHOTO"
        private val KEY_LAMBDA = floatPreferencesKey("dimaxprop_lambda")
        private val KEY_ETA = floatPreferencesKey("q_shaping_eta")
        private val KEY_BLOCKED = stringSetPreferencesKey("blocked_nodes")
        private val KEY_UNREAD_BASELINE = longPreferencesKey("unread_baseline_ms")
        private const val PREFIX_DELETED = "deleted_at:"
        private const val PREFIX_LAST_READ = "last_read:"
        private const val PREFIX_KEY_ISSUED = "key_issued_s:"
        private const val PREFIX_PENDING = "key_pending:"
        private const val PREFIX_KEY_CHANGED = "key_changed_ms:"
        private const val PREFIX_GROUP_INVITED = "group_invited:"
    }
}

/** A verified contact's new keys, held until the user accepts them or re-scans the QR. */
class PendingKeys(val xPub: ByteArray, val edPub: ByteArray, val issuedAtSec: Long, val name: String) {
    /** `<issuedAt>:<b64 xPub>:<b64 edPub>:<name>` (the name is last, so it may contain ':'). */
    fun encode(): String {
        val enc = Base64.getEncoder().withoutPadding()
        return "$issuedAtSec:${enc.encodeToString(xPub)}:${enc.encodeToString(edPub)}:$name"
    }

    companion object {
        fun decode(text: String): PendingKeys? {
            val parts = text.split(":", limit = 4)
            if (parts.size < 3) return null
            val issued = parts[0].toLongOrNull() ?: return null
            val dec = Base64.getDecoder()
            val x = runCatching { dec.decode(parts[1]) }.getOrNull()?.takeIf { it.size == 32 } ?: return null
            val ed = runCatching { dec.decode(parts[2]) }.getOrNull()?.takeIf { it.size == 32 } ?: return null
            return PendingKeys(x, ed, issued, parts.getOrNull(3).orEmpty())
        }
    }
}
