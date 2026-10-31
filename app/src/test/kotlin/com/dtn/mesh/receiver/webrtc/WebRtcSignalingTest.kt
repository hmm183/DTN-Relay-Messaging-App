package com.dtn.mesh.receiver.webrtc

import com.dtn.mesh.crypto.DtnCrypto
import com.dtn.mesh.crypto.DtnIdentity
import com.dtn.mesh.database.entity.ContactEntity
import com.dtn.mesh.model.NodeId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.Field

class WebRtcSignalingTest {

    // Helper to construct a lightweight DtnIdentity for tests without Android Context
    private fun createTestIdentity(nodeNum: Int): DtnIdentity {
        val (xPriv, xPub) = DtnCrypto.generateX25519()
        val (edSeed, edPub) = DtnCrypto.generateEd25519()

        val instance = allocateUninitialized(DtnIdentity::class.java)
        setField(instance, "xPriv", xPriv)
        setField(instance, "xPub", xPub)
        setField(instance, "edSeed", edSeed)
        setField(instance, "edPub", edPub)
        setField(instance, "nodeId", "!%08x".format(nodeNum))
        setField(instance, "nodeNum32", nodeNum)
        setField(instance, "idIsStable", true)
        setField(instance, "createdAtMs", System.currentTimeMillis())
        return instance
    }

    private fun <T> allocateUninitialized(clazz: Class<T>): T {
        val unsafeClass = Class.forName("sun.misc.Unsafe")
        val unsafeField = unsafeClass.getDeclaredField("theUnsafe")
        unsafeField.isAccessible = true
        val unsafe = unsafeField.get(null)
        val allocateInstance = unsafeClass.getMethod("allocateInstance", Class::class.java)
        @Suppress("UNCHECKED_CAST")
        return allocateInstance.invoke(unsafe, clazz) as T
    }

    private fun setField(target: Any, name: String, value: Any) {
        var c: Class<*>? = target.javaClass
        while (c != null) {
            try {
                val f: Field = c.getDeclaredField(name)
                f.isAccessible = true
                f.set(target, value)
                return
            } catch (_: NoSuchFieldException) {
                c = c.superclass
            }
        }
        throw NoSuchFieldException(name)
    }

    @Test
    fun roundTripOfferSealingAndOpening() {
        val alice = createTestIdentity(0x11223344)
        val bob = createTestIdentity(0x55667788)

        val bobContactAtAlice = ContactEntity(
            nodeId = "!55667788",
            xPub = bob.xPub,
            edPub = bob.edPub,
            keyVerified = true,
        )
        val aliceContactAtBob = ContactEntity(
            nodeId = "!11223344",
            xPub = alice.xPub,
            edPub = alice.edPub,
            keyVerified = true,
        )

        val originalOffer = WebRtcSignal.Offer(
            sdp = "v=0\r\no=- 12345 2 IN IP4 127.0.0.1\r\ns=-\r\nt=0 0\r\na=fingerprint:sha-256 AA:BB:CC\r\n",
            sessionId = "sess-12345678",
            timestampSec = 1000L,
        )

        val sealedJson = WebRtcSignal.signAndSeal(
            signal = originalOffer,
            identity = alice,
            recipientHex = "55667788",
            recipientXPub = bobContactAtAlice.xPub!!,
        )

        // Bob opens and verifies Alice's offer
        val opened = WebRtcSignal.openAndVerify(
            envelopeJson = sealedJson,
            identity = bob,
            contact = aliceContactAtBob,
            nowSec = 1050L, // 50 seconds later (< 120s max skew)
            isSessionSeen = { false },
        )

        assertNotNull(opened)
        assertTrue(opened is WebRtcSignal.Offer)
        val offer = opened as WebRtcSignal.Offer
        assertEquals(originalOffer.sdp, offer.sdp)
        assertEquals("sess-12345678", offer.sessionId)
        assertEquals(1000L, offer.timestampSec)
    }

    @Test
    fun roundTripAnswerSealingAndOpening() {
        val alice = createTestIdentity(0x11223344)
        val bob = createTestIdentity(0x55667788)

        val aliceContactAtBob = ContactEntity(
            nodeId = "!11223344",
            xPub = alice.xPub,
            edPub = alice.edPub,
            keyVerified = true,
        )

        val originalAnswer = WebRtcSignal.Answer(
            sdp = "v=0\r\na=fingerprint:sha-256 DD:EE:FF\r\n",
            sessionId = "sess-87654321",
            timestampSec = 2000L,
        )

        val sealed = WebRtcSignal.signAndSeal(
            signal = originalAnswer,
            identity = bob,
            recipientHex = "11223344",
            recipientXPub = alice.xPub,
        )

        val opened = WebRtcSignal.openAndVerify(
            envelopeJson = sealed,
            identity = alice,
            contact = ContactEntity(nodeId = "!55667788", xPub = bob.xPub, edPub = bob.edPub),
            nowSec = 2020L,
            isSessionSeen = { false },
        )

        assertNotNull(opened)
        assertTrue(opened is WebRtcSignal.Answer)
        assertEquals(originalAnswer.sdp, opened!!.sdp)
    }

    @Test
    fun rejectsStaleTimestamp() {
        val alice = createTestIdentity(0x11223344)
        val bob = createTestIdentity(0x55667788)

        val sealed = WebRtcSignal.signAndSeal(
            signal = WebRtcSignal.Offer(sdp = "test-sdp", sessionId = "s1", timestampSec = 1000L),
            identity = alice,
            recipientHex = "55667788",
            recipientXPub = bob.xPub,
        )

        // 130s later (> 120s window)
        val opened = WebRtcSignal.openAndVerify(
            envelopeJson = sealed,
            identity = bob,
            contact = ContactEntity(nodeId = "!11223344", xPub = alice.xPub, edPub = alice.edPub),
            nowSec = 1130L,
            isSessionSeen = { false },
        )
        assertNull("Stale signal (>120s) must be rejected", opened)
    }

    @Test
    fun rejectsReplayedSessionId() {
        val alice = createTestIdentity(0x11223344)
        val bob = createTestIdentity(0x55667788)

        val sealed = WebRtcSignal.signAndSeal(
            signal = WebRtcSignal.Offer(sdp = "test-sdp", sessionId = "already-seen-id", timestampSec = 1000L),
            identity = alice,
            recipientHex = "55667788",
            recipientXPub = bob.xPub,
        )

        val opened = WebRtcSignal.openAndVerify(
            envelopeJson = sealed,
            identity = bob,
            contact = ContactEntity(nodeId = "!11223344", xPub = alice.xPub, edPub = alice.edPub),
            nowSec = 1010L,
            isSessionSeen = { id -> id == "already-seen-id" },
        )
        assertNull("Replayed session ID must be rejected", opened)
    }

    @Test
    fun rejectsWrongContactKey() {
        val alice = createTestIdentity(0x11223344)
        val bob = createTestIdentity(0x55667788)
        val eve = createTestIdentity(0x99999999.toInt())

        val sealed = WebRtcSignal.signAndSeal(
            signal = WebRtcSignal.Offer(sdp = "test-sdp", sessionId = "s1", timestampSec = 1000L),
            identity = alice,
            recipientHex = "55667788",
            recipientXPub = bob.xPub,
        )

        // Bob tries opening it with Eve's keys instead of Alice's
        val opened = WebRtcSignal.openAndVerify(
            envelopeJson = sealed,
            identity = bob,
            contact = ContactEntity(nodeId = "!11223344", xPub = eve.xPub, edPub = eve.edPub),
            nowSec = 1010L,
            isSessionSeen = { false },
        )
        assertNull("Signal encrypted with different key must fail decryption", opened)
    }
}

