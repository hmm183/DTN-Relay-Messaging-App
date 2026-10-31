package com.dtn.mesh.receiver.webrtc

import com.dtn.mesh.BuildConfig

/**
 * DTN Mesh's shared fallback ICE servers, for devices that don't configure their own provider.
 *
 * No credential is in source: the Metered domain / API key / static credential come from the
 * build (`dtn.defaultTurn*` in the git-ignored local.properties, see app/build.gradle.kts). An
 * empty value means "no shared TURN": the fallback is then STUN only, which still connects most
 * pairs directly; the rest keep using the relays.
 *
 * Anything compiled into an APK can be extracted, so a shared key here is effectively public:
 * anyone holding the APK can spend that quota. Prefer minting short-lived credentials on the
 * Cloudflare Worker (the Metered secret key never leaves the server) once that endpoint exists.
 */
object DefaultTurnConfig {
    val DEFAULT_METERED_DOMAIN: String = BuildConfig.DEFAULT_TURN_DOMAIN
    val DEFAULT_METERED_API_KEY: String = BuildConfig.DEFAULT_TURN_API_KEY
    private val DEFAULT_USERNAME: String = BuildConfig.DEFAULT_TURN_USERNAME
    private val DEFAULT_CREDENTIAL: String = BuildConfig.DEFAULT_TURN_CREDENTIAL

    /** Public STUN servers: no account, no credentials. */
    val PUBLIC_STUN: List<IceServerConfig> = listOf(
        IceServerConfig(urls = listOf("stun:stun.cloudflare.com:3478")),
        IceServerConfig(urls = listOf("stun:stun.relay.metered.ca:80")),
    )

    val hasSharedApiKey: Boolean get() = DEFAULT_METERED_DOMAIN.isNotBlank() && DEFAULT_METERED_API_KEY.isNotBlank()

    /** Used when the shared Metered API can't be reached: STUN, plus the static credential if one was configured. */
    val FALLBACK_ICE_SERVERS: List<IceServerConfig>
        get() = if (DEFAULT_USERNAME.isBlank() || DEFAULT_CREDENTIAL.isBlank()) PUBLIC_STUN
        else PUBLIC_STUN + IceServerFactory.meteredStatic(DEFAULT_USERNAME, DEFAULT_CREDENTIAL).filter { it.isTurn }
}
