package com.dtn.mesh.service

import com.dtn.mesh.receiver.InboundPacket

/**
 * DMs addressed to us that could not be decrypted because the sender's keys aren't pinned yet
 * (its announce hasn't arrived, e.g. Discover off and the DM overtook the direct announce).
 *
 * They are kept in memory only, per sender, until the sender's announce pins its keys (then the
 * orchestrator replays them) or the bundle's TTL runs out. Bounded so a stranger can't fill
 * memory: [MAX_PER_SENDER] per sender, [MAX_SENDERS] senders, oldest dropped first.
 */
class PendingUndecryptable(
    private val maxPerSender: Int = MAX_PER_SENDER,
    private val maxSenders: Int = MAX_SENDERS,
) {
    private val bySender = LinkedHashMap<String, ArrayDeque<InboundPacket>>()

    @Synchronized
    fun add(sender: String, packet: InboundPacket, nowMs: Long) {
        prune(nowMs)
        val q = bySender.remove(sender) ?: ArrayDeque()
        if (q.none { it.message.id == packet.message.id }) q.addLast(packet)
        while (q.size > maxPerSender) q.removeFirst()
        bySender[sender] = q // re-insert: most recently active sender last
        while (bySender.size > maxSenders) bySender.remove(bySender.keys.first())
    }

    /** Remove and return [sender]'s still-live packets, oldest first. */
    @Synchronized
    fun take(sender: String, nowMs: Long): List<InboundPacket> =
        bySender.remove(sender)?.filter { !expired(it, nowMs) }.orEmpty()

    @get:Synchronized
    val size: Int get() = bySender.values.sumOf { it.size }

    private fun prune(nowMs: Long) {
        val it = bySender.entries.iterator()
        while (it.hasNext()) {
            val e = it.next()
            e.value.removeAll { p -> expired(p, nowMs) }
            if (e.value.isEmpty()) it.remove()
        }
    }

    private fun expired(p: InboundPacket, nowMs: Long) = p.message.createdAtMs + p.message.ttlMs <= nowMs

    companion object {
        const val MAX_PER_SENDER = 20
        const val MAX_SENDERS = 20
    }
}
