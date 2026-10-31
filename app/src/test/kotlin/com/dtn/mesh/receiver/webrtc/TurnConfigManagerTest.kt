package com.dtn.mesh.receiver.webrtc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

class TurnConfigManagerTest {

    @Test
    fun publicStun_hasNoCredentials() {
        assertTrue(DefaultTurnConfig.PUBLIC_STUN.isNotEmpty())
        DefaultTurnConfig.PUBLIC_STUN.forEach {
            assertTrue(it.isStun)
            assertFalse(it.isTurn)
            assertEquals(null, it.username)
            assertEquals(null, it.credential)
        }
    }

    @Test
    fun meteredStatic_coversUdpTcpAndTls() {
        val s = IceServerFactory.meteredStatic("user", "pass")
        assertTrue(s.first().isStun)
        val turn = s.filter { it.isTurn }
        assertEquals(4, turn.size)
        assertTrue(turn.all { it.username == "user" && it.credential == "pass" })
        assertTrue(turn.any { it.isTcp && !it.isTls })
        assertTrue(turn.any { it.isTls })
        assertTrue(s.all { it.hasValidUrls })
    }

    @Test
    fun coturn_usesTheConfiguredTlsPort_orNone() {
        val withTls = IceServerFactory.coturn("turn.example.org", 3479, 443, "alice", "pw")
        assertEquals(listOf("stun:turn.example.org:3479"), withTls[0].urls)
        assertTrue(withTls.any { it.urls == listOf("turn:turn.example.org:3479") })
        assertTrue(withTls.any { it.urls == listOf("turn:turn.example.org:3479?transport=tcp") })
        assertTrue(withTls.any { it.urls == listOf("turns:turn.example.org:443?transport=tcp") })

        val noTls = IceServerFactory.coturn("turn.example.org", 3478, 0, "alice", "pw")
        assertFalse(noTls.any { it.isTls })
    }

    @Test
    fun coturn_withoutCredential_isStunOnly() {
        val s = IceServerFactory.coturn("turn.example.org", 3478, 5349, null, null)
        assertEquals(1, s.size)
        assertTrue(s[0].isStun)
    }

    @Test
    fun coturn_ipv6LiteralIsBracketed() {
        val s = IceServerFactory.coturn("2001:db8::1", 3478, 0, "u", "p")
        assertEquals("stun:[2001:db8::1]:3478", s[0].urls[0])
    }

    @Test
    fun coturnSharedSecret_matchesTheCoturnRestApiFormula() {
        val (user, pass) = IceServerFactory.coturnSharedSecretCredential("s3cret", "dtn", nowSec = 1_000, ttlSec = 3_600)
        assertEquals("4600:dtn", user)
        val mac = Mac.getInstance("HmacSHA1").apply { init(SecretKeySpec("s3cret".toByteArray(), "HmacSHA1")) }
        assertEquals(Base64.getEncoder().encodeToString(mac.doFinal("4600:dtn".toByteArray())), pass)
        assertFalse(pass.contains("s3cret"))
    }

    @Test
    fun customJson_dropsInvalidEntries() {
        val parsed = IceServerConfig.parseJsonArray(
            """
            [
              {"urls": "stun:stun.example.org:3478"},
              {"urls": ["turn:a.example.org:3478", "turns:a.example.org:5349?transport=tcp"], "username": "u", "credential": "p"},
              {"urls": "turn:no-credential.example.org:3478"},
              {"urls": "http://not-ice.example.org"}
            ]
            """.trimIndent(),
        ).filter { it.hasValidUrls }
        assertEquals(2, parsed.size)
        assertTrue(parsed[1].isTls)
    }

    @Test
    fun customJson_garbageIsEmpty() {
        assertTrue(IceServerConfig.parseJsonArray("not json").isEmpty())
        assertTrue(IceServerConfig.parseJsonArray("{\"urls\":\"stun:x\"}").isEmpty())
    }

    @Test
    fun toString_neverPrintsCredentials() {
        val s = IceServerConfig(listOf("turn:x:3478"), "user-secret", "cred-secret").toString()
        assertFalse(s.contains("user-secret"))
        assertFalse(s.contains("cred-secret"))
        val t = TurnSettings(TurnProviderType.METERED, meteredApiKey = "api-secret", coturnPassword = "pw-secret").toString()
        assertFalse(t.contains("api-secret"))
        assertFalse(t.contains("pw-secret"))
    }
}
