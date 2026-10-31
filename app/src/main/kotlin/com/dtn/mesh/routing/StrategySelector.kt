package com.dtn.mesh.routing

import com.dtn.mesh.model.ContactRecord
import com.dtn.mesh.model.DtnMessage
import com.dtn.mesh.model.NodeId

/**
 * Runtime-switchable wrapper around [RoutingStrategy] implementations.
 *
 * Allows toggling between PRoPHET, MaxProp, Epidemic, and DiMaxProp at runtime.
 */
class StrategySelector(
    private val prophet: ProphetStrategy = ProphetStrategy(),
    private val maxProp: MaxPropStrategy = MaxPropStrategy(),
    private val epidemic: EpidemicStrategy = EpidemicStrategy(),
    private val stableProphet: ProphetStrategy = ProphetStrategy(ProphetConfig(stabilityAware = true)),
    private val qLearning: QLearningStrategy = QLearningStrategy(),
    private val diMaxPropStrategy: DiMaxPropStrategy = DiMaxPropStrategy(),
    initialStrategy: StrategyType = StrategyType.EPIDEMIC,
) : RoutingStrategy {

    enum class StrategyType { PROPHET, MAXPROP, EPIDEMIC, STABLE, QLEARNING, DIMAXPROP }

    var activeType: StrategyType = initialStrategy
        private set

    val active: RoutingStrategy
        get() = when (activeType) {
            StrategyType.PROPHET -> prophet
            StrategyType.MAXPROP -> maxProp
            StrategyType.EPIDEMIC -> epidemic
            StrategyType.STABLE -> stableProphet
            StrategyType.QLEARNING -> qLearning
            StrategyType.DIMAXPROP -> diMaxPropStrategy
        }

    val diMaxProp: DiMaxPropStrategy get() = diMaxPropStrategy

    fun setSelfId(id: String) {
        prophet.selfId = id
        stableProphet.selfId = id
        maxProp.selfId = id
        diMaxPropStrategy.selfId = id
    }

    override val name: String get() = active.name

    fun switchTo(type: StrategyType) {
        activeType = type
    }

    override fun onEncounter(peerId: NodeId, contactRecord: ContactRecord) {
        prophet.onEncounter(peerId, contactRecord)
        maxProp.onEncounter(peerId, contactRecord)
        epidemic.onEncounter(peerId, contactRecord)
        stableProphet.onEncounter(peerId, contactRecord)
        diMaxPropStrategy.onEncounter(peerId, contactRecord)
    }

    override fun onRoutingSummaryReceived(peerId: NodeId, summary: RoutingSummary) {
        when (summary.strategyTag) {
            "PROPHET" -> prophet.onRoutingSummaryReceived(peerId, summary)
            "STABLE-PROPHET" -> stableProphet.onRoutingSummaryReceived(peerId, summary)
            "MAXPROP" -> maxProp.onRoutingSummaryReceived(peerId, summary)
            DiMaxPropStrategy.TAG -> diMaxPropStrategy.onRoutingSummaryReceived(peerId, summary)
        }
    }

    override fun buildRoutingSummary(): RoutingSummary =
        if (activeType == StrategyType.QLEARNING) diMaxPropStrategy.buildRoutingSummary()
        else active.buildRoutingSummary()

    override fun rankForForwarding(candidates: List<DtnMessage>, peerId: NodeId): List<ForwardCandidate> =
        active.rankForForwarding(candidates, peerId)

    override fun rankForDrop(candidates: List<DtnMessage>, bytesToFree: Long): List<DtnMessage> =
        active.rankForDrop(candidates, bytesToFree)

    override fun onPeriodicAge() {
        prophet.onPeriodicAge()
        maxProp.onPeriodicAge()
        epidemic.onPeriodicAge()
        stableProphet.onPeriodicAge()
        diMaxPropStrategy.onPeriodicAge()
    }

    override fun getDeliveryProbability(peerId: NodeId): Double =
        active.getDeliveryProbability(peerId)

    override fun exportState(): ByteArray = active.exportState()

    override fun importState(data: ByteArray) = active.importState(data)

    fun prophetDeliveryProbability(peerId: NodeId): Double = prophet.getDeliveryProbability(peerId)

    fun seedProphetProbabilities(seed: Map<String, Double>) {
        prophet.seedProbabilities(seed)
        stableProphet.seedProbabilities(seed)
    }

    fun exportAllStates(): Pair<ByteArray, ByteArray> =
        prophet.exportState() to maxProp.exportState()

    fun importAllStates(prophetState: ByteArray, maxPropState: ByteArray) {
        prophet.importState(prophetState)
        maxProp.importState(maxPropState)
    }
}
