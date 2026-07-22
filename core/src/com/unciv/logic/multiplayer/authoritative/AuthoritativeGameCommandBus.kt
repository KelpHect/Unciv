package com.unciv.logic.multiplayer.authoritative

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.UUID

sealed interface AuthoritativeSyncState {
    data object Uninitialized : AuthoritativeSyncState
    data class Refreshing(val cached: ApiV3GameProjection?) : AuthoritativeSyncState
    data class Synchronized(val current: ApiV3GameProjection) : AuthoritativeSyncState
    data class Submitting(val current: ApiV3GameProjection, val commandId: String) : AuthoritativeSyncState
    data class Retryable(
        val current: ApiV3GameProjection,
        val pending: PendingAuthoritativeCommand,
        val cause: Throwable,
    ) : AuthoritativeSyncState
    data class Rejected(
        val current: ApiV3GameProjection,
        val code: String,
    ) : AuthoritativeSyncState
}

private enum class PartnerDiplomacyAction {
    DeclareWar,
    Denounce,
    OfferFriendship,
}

sealed interface PendingAuthoritativeCommand {
    val commandId: String
    val expectedRevision: Long
    val observedStateHash: String

    data class MoveUnit(
        override val commandId: String,
        override val expectedRevision: Long,
        override val observedStateHash: String,
        val unitId: Int,
        val destinationX: Int,
        val destinationY: Int,
    ) : PendingAuthoritativeCommand

    data class MoveUnitToward(
        override val commandId: String,
        override val expectedRevision: Long,
        override val observedStateHash: String,
        val unitId: Int,
        val destinationX: Int,
        val destinationY: Int,
    ) : PendingAuthoritativeCommand

    data class CancelUnitMovementOrder(
        override val commandId: String,
        override val expectedRevision: Long,
        override val observedStateHash: String,
        val unitId: Int,
    ) : PendingAuthoritativeCommand

    data class SetUnitExploration(
        override val commandId: String,
        override val expectedRevision: Long,
        override val observedStateHash: String,
        val unitId: Int,
        val enabled: Boolean,
    ) : PendingAuthoritativeCommand

    data class SetUnitAutomation(
        override val commandId: String,
        override val expectedRevision: Long,
        override val observedStateHash: String,
        val unitId: Int,
        val enabled: Boolean,
    ) : PendingAuthoritativeCommand

    data class SetUnitPosture(
        override val commandId: String,
        override val expectedRevision: Long,
        override val observedStateHash: String,
        val unitId: Int,
        val posture: UnitPosture,
    ) : PendingAuthoritativeCommand

    data class DisbandUnit(
        override val commandId: String,
        override val expectedRevision: Long,
        override val observedStateHash: String,
        val unitId: Int,
    ) : PendingAuthoritativeCommand

    data class PillageTile(
        override val commandId: String,
        override val expectedRevision: Long,
        override val observedStateHash: String,
        val unitId: Int,
    ) : PendingAuthoritativeCommand

    data class FoundCity(
        override val commandId: String,
        override val expectedRevision: Long,
        override val observedStateHash: String,
        val unitId: Int,
    ) : PendingAuthoritativeCommand

    data class ParadropUnit(
        override val commandId: String,
        override val expectedRevision: Long,
        override val observedStateHash: String,
        val unitId: Int,
        val destinationX: Int,
        val destinationY: Int,
    ) : PendingAuthoritativeCommand

    data class AttackWithUnit(
        override val commandId: String,
        override val expectedRevision: Long,
        override val observedStateHash: String,
        val unitId: Int,
        val targetX: Int,
        val targetY: Int,
    ) : PendingAuthoritativeCommand

    data class BombardWithCity(
        override val commandId: String,
        override val expectedRevision: Long,
        override val observedStateHash: String,
        val cityId: String,
        val targetX: Int,
        val targetY: Int,
    ) : PendingAuthoritativeCommand

    data class LaunchNuclearStrike(
        override val commandId: String,
        override val expectedRevision: Long,
        override val observedStateHash: String,
        val unitId: Int,
        val targetX: Int,
        val targetY: Int,
    ) : PendingAuthoritativeCommand

    data class AirSweep(
        override val commandId: String,
        override val expectedRevision: Long,
        override val observedStateHash: String,
        val unitId: Int,
        val targetX: Int,
        val targetY: Int,
    ) : PendingAuthoritativeCommand

    data class UpgradeUnits(
        override val commandId: String,
        override val expectedRevision: Long,
        override val observedStateHash: String,
        val unitIds: List<Int>,
        val targetUnitName: String,
    ) : PendingAuthoritativeCommand

    data class PromoteUnit(
        override val commandId: String,
        override val expectedRevision: Long,
        override val observedStateHash: String,
        val unitId: Int,
        val promotionNames: List<String>,
        val saveAsCityDefault: Boolean,
    ) : PendingAuthoritativeCommand

    data class SetCityUnitPromotionPreference(
        override val commandId: String,
        override val expectedRevision: Long,
        override val observedStateHash: String,
        val cityId: String,
        val baseUnitName: String,
        val enabled: Boolean,
    ) : PendingAuthoritativeCommand

    data class RenameUnit(
        override val commandId: String,
        override val expectedRevision: Long,
        override val observedStateHash: String,
        val unitId: Int,
        val instanceName: String?,
    ) : PendingAuthoritativeCommand

    data class SetTileImprovementOrder(
        override val commandId: String,
        override val expectedRevision: Long,
        override val observedStateHash: String,
        val unitId: Int,
        val improvementName: String?,
        val queuedImprovementName: String?,
    ) : PendingAuthoritativeCommand

    data class SetRoadConnectionOrder(
        override val commandId: String,
        override val expectedRevision: Long,
        override val observedStateHash: String,
        val unitId: Int,
        val destinationX: Int?,
        val destinationY: Int?,
    ) : PendingAuthoritativeCommand

    data class SwapUnits(
        override val commandId: String,
        override val expectedRevision: Long,
        override val observedStateHash: String,
        val unitId: Int,
        val destinationX: Int,
        val destinationY: Int,
    ) : PendingAuthoritativeCommand

    data class EndTurn(
        override val commandId: String,
        override val expectedRevision: Long,
        override val observedStateHash: String,
    ) : PendingAuthoritativeCommand

    data class Resign(
        override val commandId: String,
        override val expectedRevision: Long,
        override val observedStateHash: String,
    ) : PendingAuthoritativeCommand

    data class QueueConstruction(
        override val commandId: String,
        override val expectedRevision: Long,
        override val observedStateHash: String,
        val cityId: String,
        val constructionName: String,
    ) : PendingAuthoritativeCommand

    data class QueueConstructionAtTile(
        override val commandId: String,
        override val expectedRevision: Long,
        override val observedStateHash: String,
        val cityId: String,
        val constructionName: String,
        val x: Int,
        val y: Int,
    ) : PendingAuthoritativeCommand

    data class SetPerpetualConstruction(
        override val commandId: String,
        override val expectedRevision: Long,
        override val observedStateHash: String,
        val cityId: String,
        val constructionName: String,
    ) : PendingAuthoritativeCommand

    data class RemoveConstruction(
        override val commandId: String,
        override val expectedRevision: Long,
        override val observedStateHash: String,
        val cityId: String,
        val queueIndex: Int,
        val expectedConstructionName: String,
    ) : PendingAuthoritativeCommand

    data class MoveConstruction(
        override val commandId: String,
        override val expectedRevision: Long,
        override val observedStateHash: String,
        val cityId: String,
        val fromIndex: Int,
        val toIndex: Int,
        val expectedConstructionName: String,
    ) : PendingAuthoritativeCommand

    data class PurchaseConstruction(
        override val commandId: String,
        override val expectedRevision: Long,
        override val observedStateHash: String,
        val cityId: String,
        val constructionName: String,
        val currencyName: String,
        val queueIndex: Int?,
    ) : PendingAuthoritativeCommand

    data class PurchaseConstructionAtTile(
        override val commandId: String,
        override val expectedRevision: Long,
        override val observedStateHash: String,
        val cityId: String,
        val constructionName: String,
        val currencyName: String,
        val x: Int,
        val y: Int,
        val queueIndex: Int?,
    ) : PendingAuthoritativeCommand

    data class BuyCityTile(
        override val commandId: String,
        override val expectedRevision: Long,
        override val observedStateHash: String,
        val cityId: String,
        val x: Int,
        val y: Int,
    ) : PendingAuthoritativeCommand

    data class SellBuilding(
        override val commandId: String,
        override val expectedRevision: Long,
        override val observedStateHash: String,
        val cityId: String,
        val buildingName: String,
    ) : PendingAuthoritativeCommand

    data class SetCityGovernance(
        override val commandId: String,
        override val expectedRevision: Long,
        override val observedStateHash: String,
        val cityId: String,
        val action: CityGovernanceAction,
    ) : PendingAuthoritativeCommand

    data class ResolveCityDisposition(
        override val commandId: String,
        override val expectedRevision: Long,
        override val observedStateHash: String,
        val cityId: String,
        val action: CityDispositionAction,
    ) : PendingAuthoritativeCommand

    data class CastDiplomaticVote(
        override val commandId: String,
        override val expectedRevision: Long,
        override val observedStateHash: String,
        val candidateCivilizationId: String?,
    ) : PendingAuthoritativeCommand

    data class ChooseGreatPerson(
        override val commandId: String,
        override val expectedRevision: Long,
        override val observedStateHash: String,
        val unitName: String,
    ) : PendingAuthoritativeCommand

    data class UseReligiousUnit(
        override val commandId: String,
        override val expectedRevision: Long,
        override val observedStateHash: String,
        val unitId: Int,
        val action: ReligiousUnitAction,
    ) : PendingAuthoritativeCommand

    data class UseGreatPersonUnit(
        override val commandId: String,
        override val expectedRevision: Long,
        override val observedStateHash: String,
        val unitId: Int,
        val action: GreatPersonUnitAction,
    ) : PendingAuthoritativeCommand
    data class GiftUnit(override val commandId: String, override val expectedRevision: Long, override val observedStateHash: String, val unitId: Int) : PendingAuthoritativeCommand
    data class TransformUnit(override val commandId: String, override val expectedRevision: Long, override val observedStateHash: String, val unitId: Int, val actionId: String) : PendingAuthoritativeCommand
    data class TriggerUnitUnique(override val commandId: String, override val expectedRevision: Long, override val observedStateHash: String, val unitId: Int, val actionId: String) : PendingAuthoritativeCommand

    data class ChooseReligiousBeliefs(
        override val commandId: String,
        override val expectedRevision: Long,
        override val observedStateHash: String,
        val beliefNames: List<String>,
        val religionIconName: String?,
        val religionDisplayName: String?,
    ) : PendingAuthoritativeCommand

    data class OfferTrade(
        override val commandId: String, override val expectedRevision: Long, override val observedStateHash: String,
        val otherCivilizationId: String, val trade: ProjectedTrade,
    ) : PendingAuthoritativeCommand

    data class RetractTradeOffer(
        override val commandId: String, override val expectedRevision: Long, override val observedStateHash: String,
        val otherCivilizationId: String,
    ) : PendingAuthoritativeCommand

    data class AcceptTrade(
        override val commandId: String, override val expectedRevision: Long, override val observedStateHash: String,
        val requestId: String,
    ) : PendingAuthoritativeCommand

    data class DeclineTrade(
        override val commandId: String, override val expectedRevision: Long, override val observedStateHash: String,
        val requestId: String,
    ) : PendingAuthoritativeCommand

    data class CounterTrade(
        override val commandId: String, override val expectedRevision: Long, override val observedStateHash: String,
        val requestId: String, val trade: ProjectedTrade,
    ) : PendingAuthoritativeCommand

    data class DeclareWar(override val commandId: String, override val expectedRevision: Long, override val observedStateHash: String, val otherCivilizationId: String) : PendingAuthoritativeCommand
    data class DenounceCivilization(override val commandId: String, override val expectedRevision: Long, override val observedStateHash: String, val otherCivilizationId: String) : PendingAuthoritativeCommand
    data class OfferFriendship(override val commandId: String, override val expectedRevision: Long, override val observedStateHash: String, val otherCivilizationId: String) : PendingAuthoritativeCommand
    data class MakeDiplomaticDemand(override val commandId: String, override val expectedRevision: Long, override val observedStateHash: String, val otherCivilizationId: String, val demand: DiplomaticDemand) : PendingAuthoritativeCommand
    data class RespondToDiplomaticPrompt(override val commandId: String, override val expectedRevision: Long, override val observedStateHash: String, val promptId: String, val accept: Boolean) : PendingAuthoritativeCommand
    data class RespondToCityStateProtectionPrompt(override val commandId: String, override val expectedRevision: Long, override val observedStateHash: String, val promptId: String, val response: CityStateProtectionResponse) : PendingAuthoritativeCommand
    data class GiftCityStateGold(override val commandId: String, override val expectedRevision: Long, override val observedStateHash: String, val cityStateId: String, val amount: Int) : PendingAuthoritativeCommand
    data class SetCityStateProtection(override val commandId: String, override val expectedRevision: Long, override val observedStateHash: String, val cityStateId: String, val protect: Boolean) : PendingAuthoritativeCommand
    data class DemandCityStateTribute(override val commandId: String, override val expectedRevision: Long, override val observedStateHash: String, val cityStateId: String, val worker: Boolean) : PendingAuthoritativeCommand
    data class GiftCityStateImprovement(override val commandId: String, override val expectedRevision: Long, override val observedStateHash: String, val cityStateId: String, val x: Int, val y: Int, val improvementName: String) : PendingAuthoritativeCommand
    data class NegotiateCityStatePeace(override val commandId: String, override val expectedRevision: Long, override val observedStateHash: String, val cityStateId: String) : PendingAuthoritativeCommand
    data class MarryCityState(override val commandId: String, override val expectedRevision: Long, override val observedStateHash: String, val cityStateId: String) : PendingAuthoritativeCommand
    data class MoveSpy(override val commandId: String, override val expectedRevision: Long, override val observedStateHash: String, val spyName: String, val cityId: String?) : PendingAuthoritativeCommand
    data class SetSpyCoup(override val commandId: String, override val expectedRevision: Long, override val observedStateHash: String, val spyName: String, val enabled: Boolean) : PendingAuthoritativeCommand
    data class ResolveEventChoice(override val commandId: String, override val expectedRevision: Long, override val observedStateHash: String, val promptId: String, val choiceId: String) : PendingAuthoritativeCommand

    data class SetCityTileAssignment(
        override val commandId: String,
        override val expectedRevision: Long,
        override val observedStateHash: String,
        val cityId: String,
        val x: Int,
        val y: Int,
        val assignment: CityTileAssignment,
    ) : PendingAuthoritativeCommand

    data class SetSpecialistCount(
        override val commandId: String,
        override val expectedRevision: Long,
        override val observedStateHash: String,
        val cityId: String,
        val specialistName: String,
        val count: Int,
    ) : PendingAuthoritativeCommand

    data class SetManualSpecialists(
        override val commandId: String,
        override val expectedRevision: Long,
        override val observedStateHash: String,
        val cityId: String,
        val enabled: Boolean,
    ) : PendingAuthoritativeCommand

    data class ResetCitizens(
        override val commandId: String,
        override val expectedRevision: Long,
        override val observedStateHash: String,
        val cityId: String,
    ) : PendingAuthoritativeCommand

    data class SetAvoidGrowth(
        override val commandId: String,
        override val expectedRevision: Long,
        override val observedStateHash: String,
        val cityId: String,
        val enabled: Boolean,
    ) : PendingAuthoritativeCommand

    data class SetCitizenFocus(
        override val commandId: String,
        override val expectedRevision: Long,
        override val observedStateHash: String,
        val cityId: String,
        val focus: CitizenFocus,
    ) : PendingAuthoritativeCommand

    data class SetResearchPath(
        override val commandId: String,
        override val expectedRevision: Long,
        override val observedStateHash: String,
        val technologyName: String,
    ) : PendingAuthoritativeCommand

    data class AdoptPolicy(
        override val commandId: String,
        override val expectedRevision: Long,
        override val observedStateHash: String,
        val policyName: String,
    ) : PendingAuthoritativeCommand

    data class ChooseFreeTechnology(
        override val commandId: String,
        override val expectedRevision: Long,
        override val observedStateHash: String,
        val technologyName: String,
    ) : PendingAuthoritativeCommand
}

sealed interface AuthoritativeCommandOutcome {
    data class Accepted(
        val result: ApiV3CommandAccepted,
        val current: ApiV3GameProjection,
    ) : AuthoritativeCommandOutcome
    data class StaleRefreshed(val current: ApiV3GameProjection) : AuthoritativeCommandOutcome
    data class Rejected(val code: String) : AuthoritativeCommandOutcome
    data object RetryRequired : AuthoritativeCommandOutcome
}

/**
 * Serializes commands from one client and treats its projection as disposable.
 * There is no optimistic canonical mutation here: a rejected command leaves the
 * last server projection intact, while ambiguous network failures retain the
 * exact command ID for safe idempotent retry.
 */
class AuthoritativeGameCommandBus(
    private val gameId: String,
    private val transport: ApiV3Transport,
    private val commandIdFactory: () -> String = { UUID.randomUUID().toString() },
) {
    private val mutex = Mutex()
    private var terminalAccepted = false
    var state: AuthoritativeSyncState = AuthoritativeSyncState.Uninitialized
        private set

    suspend fun refresh(): ApiV3GameProjection = mutex.withLock {
        refreshLocked(cachedProjection())
    }

    suspend fun resign() = mutex.withLock {
        require(!terminalAccepted) { "This authoritative game session has ended" }
        val current = requireSynchronized()
        submitLocked(PendingAuthoritativeCommand.Resign(
            commandIdFactory(), current.committedRevision, current.canonicalStateHash,
        ), current)
    }

    suspend fun moveUnit(unitId: Int, destinationX: Int, destinationY: Int) = mutex.withLock {
        val current = requireSynchronized()
        require(current.projection.ownUnits.any { it.id == unitId }) {
            "Unit is absent from the current player projection"
        }
        require(current.projection.exploredTiles.any {
            it.x == destinationX && it.y == destinationY
        }) {
            "Destination is absent from the current player projection"
        }
        submitLocked(PendingAuthoritativeCommand.MoveUnit(
            commandId = commandIdFactory(),
            expectedRevision = current.committedRevision,
            observedStateHash = current.canonicalStateHash,
            unitId = unitId,
            destinationX = destinationX,
            destinationY = destinationY,
        ), current)
    }

    suspend fun moveUnitToward(unitId: Int, destinationX: Int, destinationY: Int) = mutex.withLock {
        val current = requireSynchronized()
        require(current.projection.ownUnits.any { it.id == unitId }) {
            "Unit is absent from the current player projection"
        }
        require(current.projection.exploredTiles.any {
            it.x == destinationX && it.y == destinationY
        }) {
            "Destination is absent from the current player projection"
        }
        submitLocked(PendingAuthoritativeCommand.MoveUnitToward(
            commandIdFactory(), current.committedRevision, current.canonicalStateHash,
            unitId, destinationX, destinationY,
        ), current)
    }

    suspend fun cancelUnitMovementOrder(unitId: Int) = mutex.withLock {
        val current = requireSynchronized()
        val unit = current.projection.ownUnits.singleOrNull { it.id == unitId }
            ?: error("Unit is absent from the current player projection")
        require(unit.movementDestinationX != null && unit.movementDestinationY != null) {
            "Unit has no projected movement order"
        }
        submitLocked(PendingAuthoritativeCommand.CancelUnitMovementOrder(
            commandIdFactory(), current.committedRevision, current.canonicalStateHash, unitId,
        ), current)
    }

    suspend fun setUnitExploration(unitId: Int, enabled: Boolean) = mutex.withLock {
        val current = requireSynchronized()
        val unit = current.projection.ownUnits.singleOrNull { it.id == unitId }
            ?: error("Unit is absent from the current player projection")
        require(unit.exploring != enabled) {
            "Unit exploration already matches the requested state"
        }
        submitLocked(PendingAuthoritativeCommand.SetUnitExploration(
            commandIdFactory(), current.committedRevision, current.canonicalStateHash,
            unitId, enabled,
        ), current)
    }

    suspend fun setUnitAutomation(unitId: Int, enabled: Boolean) = mutex.withLock {
        val current = requireSynchronized()
        val unit = current.projection.ownUnits.singleOrNull { it.id == unitId }
            ?: error("Unit is absent from the current player projection")
        require(unit.automated != enabled) {
            "Unit automation already matches the requested state"
        }
        submitLocked(PendingAuthoritativeCommand.SetUnitAutomation(
            commandIdFactory(), current.committedRevision, current.canonicalStateHash,
            unitId, enabled,
        ), current)
    }

    suspend fun setUnitPosture(unitId: Int, posture: UnitPosture) = mutex.withLock {
        val current = requireSynchronized()
        val unit = current.projection.ownUnits.singleOrNull { it.id == unitId }
            ?: error("Unit is absent from the current player projection")
        require(unit.posture != posture) { "Unit already has the requested posture" }
        submitLocked(PendingAuthoritativeCommand.SetUnitPosture(
            commandIdFactory(), current.committedRevision, current.canonicalStateHash,
            unitId, posture,
        ), current)
    }

    suspend fun disbandUnit(unitId: Int) = mutex.withLock {
        val current = requireSynchronized()
        require(current.projection.ownUnits.any { it.id == unitId }) {
            "Unit is absent from the current player projection"
        }
        submitLocked(PendingAuthoritativeCommand.DisbandUnit(
            commandIdFactory(), current.committedRevision, current.canonicalStateHash, unitId,
        ), current)
    }

    suspend fun pillageTile(unitId: Int) = mutex.withLock {
        val current = requireSynchronized()
        require(current.projection.ownUnits.any { it.id == unitId }) {
            "Unit is absent from the current player projection"
        }
        submitLocked(PendingAuthoritativeCommand.PillageTile(
            commandIdFactory(), current.committedRevision, current.canonicalStateHash, unitId,
        ), current)
    }

    suspend fun foundCity(unitId: Int) = mutex.withLock {
        val current = requireSynchronized()
        require(current.projection.ownUnits.any { it.id == unitId }) {
            "Unit is absent from the current player projection"
        }
        submitLocked(PendingAuthoritativeCommand.FoundCity(
            commandIdFactory(), current.committedRevision, current.canonicalStateHash, unitId,
        ), current)
    }

    suspend fun paradropUnit(unitId: Int, destinationX: Int, destinationY: Int) = mutex.withLock {
        val current = requireSynchronized()
        require(current.projection.ownUnits.any { it.id == unitId }) {
            "Unit is absent from the current player projection"
        }
        require(current.projection.exploredTiles.any {
            it.x == destinationX && it.y == destinationY && it.visible
        }) { "Paradrop destination is absent from the current visible projection" }
        submitLocked(PendingAuthoritativeCommand.ParadropUnit(
            commandIdFactory(), current.committedRevision, current.canonicalStateHash,
            unitId, destinationX, destinationY,
        ), current)
    }

    suspend fun attackWithUnit(unitId: Int, targetX: Int, targetY: Int) = mutex.withLock {
        val current = requireSynchronized()
        require(current.projection.ownUnits.any { it.id == unitId }) {
            "Unit is absent from the current player projection"
        }
        require(current.projection.exploredTiles.any {
            it.x == targetX && it.y == targetY && it.visible
        }) { "Attack target is absent from the current visible projection" }
        submitLocked(PendingAuthoritativeCommand.AttackWithUnit(
            commandIdFactory(), current.committedRevision, current.canonicalStateHash,
            unitId, targetX, targetY,
        ), current)
    }

    suspend fun bombardWithCity(cityId: String, targetX: Int, targetY: Int) = mutex.withLock {
        val current = requireSynchronized()
        require(current.projection.ownCities.any { it.id == cityId }) {
            "City is absent from the current player projection"
        }
        require(current.projection.exploredTiles.any {
            it.x == targetX && it.y == targetY && it.visible
        }) { "Bombard target is absent from the current visible projection" }
        submitLocked(PendingAuthoritativeCommand.BombardWithCity(
            commandIdFactory(), current.committedRevision, current.canonicalStateHash,
            cityId, targetX, targetY,
        ), current)
    }

    suspend fun launchNuclearStrike(unitId: Int, targetX: Int, targetY: Int) = mutex.withLock {
        val current = requireSynchronized()
        require(current.projection.ownUnits.any { it.id == unitId }) {
            "Nuclear unit is absent from the current player projection"
        }
        require(current.projection.exploredTiles.any { it.x == targetX && it.y == targetY }) {
            "Nuclear target is absent from the current explored projection"
        }
        submitLocked(PendingAuthoritativeCommand.LaunchNuclearStrike(
            commandIdFactory(), current.committedRevision, current.canonicalStateHash,
            unitId, targetX, targetY,
        ), current)
    }

    suspend fun airSweep(unitId: Int, targetX: Int, targetY: Int) = mutex.withLock {
        val current = requireSynchronized()
        require(current.projection.ownUnits.any { it.id == unitId }) {
            "Air-sweep unit is absent from the current player projection"
        }
        submitLocked(PendingAuthoritativeCommand.AirSweep(
            commandIdFactory(), current.committedRevision, current.canonicalStateHash,
            unitId, targetX, targetY,
        ), current)
    }

    suspend fun upgradeUnits(unitIds: List<Int>, targetUnitName: String) = mutex.withLock {
        val current = requireSynchronized()
        require(unitIds.isNotEmpty() && unitIds.size <= 100 && unitIds.distinct().size == unitIds.size) {
            "Upgrade batch must contain between 1 and 100 distinct units"
        }
        require(targetUnitName.isNotBlank() && targetUnitName.length <= 200) {
            "Upgrade target name is invalid"
        }
        val projectedIds = current.projection.ownUnits.mapTo(HashSet()) { it.id }
        require(unitIds.all { it in projectedIds }) {
            "Upgrade batch contains a unit absent from the current player projection"
        }
        submitLocked(PendingAuthoritativeCommand.UpgradeUnits(
            commandIdFactory(), current.committedRevision, current.canonicalStateHash,
            unitIds.toList(), targetUnitName,
        ), current)
    }

    suspend fun promoteUnit(
        unitId: Int,
        promotionNames: List<String>,
        saveAsCityDefault: Boolean = false,
    ) = mutex.withLock {
        val current = requireSynchronized()
        require(current.projection.ownUnits.any { it.id == unitId }) {
            "Unit is absent from the current player projection"
        }
        require(promotionNames.isNotEmpty() && promotionNames.size <= 10 &&
            promotionNames.distinct().size == promotionNames.size) {
            "Promotion path must contain between 1 and 10 distinct promotions"
        }
        require(promotionNames.all { it.isNotBlank() && it.length <= 200 }) {
            "Promotion name is invalid"
        }
        submitLocked(PendingAuthoritativeCommand.PromoteUnit(
            commandIdFactory(), current.committedRevision, current.canonicalStateHash,
            unitId, promotionNames.toList(), saveAsCityDefault,
        ), current)
    }

    suspend fun setCityUnitPromotionPreference(
        cityId: String,
        baseUnitName: String,
        enabled: Boolean,
    ) = mutex.withLock {
        val current = requireSynchronized()
        require(current.projection.ownCities.any { it.id == cityId }) {
            "City is absent from the current player projection"
        }
        require(baseUnitName.isNotBlank() && baseUnitName.length <= 200) {
            "Base unit name is invalid"
        }
        submitLocked(PendingAuthoritativeCommand.SetCityUnitPromotionPreference(
            commandIdFactory(), current.committedRevision, current.canonicalStateHash,
            cityId, baseUnitName, enabled,
        ), current)
    }

    suspend fun renameUnit(unitId: Int, instanceName: String?) = mutex.withLock {
        val current = requireSynchronized()
        require(current.projection.ownUnits.any { it.id == unitId }) {
            "Unit is absent from the current player projection"
        }
        require(instanceName == null ||
            (instanceName.isNotBlank() && instanceName.length <= 100 && instanceName.none { it.isISOControl() })) {
            "Unit name must be null or 1-100 printable characters"
        }
        submitLocked(PendingAuthoritativeCommand.RenameUnit(
            commandIdFactory(), current.committedRevision, current.canonicalStateHash,
            unitId, instanceName,
        ), current)
    }

    suspend fun setTileImprovementOrder(
        unitId: Int,
        improvementName: String?,
        queuedImprovementName: String?,
    ) = mutex.withLock {
        val current = requireSynchronized()
        require(current.projection.ownUnits.any { it.id == unitId }) {
            "Unit is absent from the current player projection"
        }
        require(improvementName == null ||
            (improvementName.isNotBlank() && improvementName.length <= 200)) {
            "Improvement name is invalid"
        }
        require(queuedImprovementName == null ||
            (queuedImprovementName.isNotBlank() && queuedImprovementName.length <= 200)) {
            "Queued improvement name is invalid"
        }
        require(improvementName != null || queuedImprovementName == null) {
            "A cancelled order cannot include a queued improvement"
        }
        submitLocked(PendingAuthoritativeCommand.SetTileImprovementOrder(
            commandIdFactory(), current.committedRevision, current.canonicalStateHash,
            unitId, improvementName, queuedImprovementName,
        ), current)
    }

    suspend fun setRoadConnectionOrder(
        unitId: Int,
        destinationX: Int?,
        destinationY: Int?,
    ) = mutex.withLock {
        val current = requireSynchronized()
        require(current.projection.ownUnits.any { it.id == unitId }) {
            "Unit is absent from the current player projection"
        }
        require((destinationX == null) == (destinationY == null)) {
            "Road destination coordinates must both be present or absent"
        }
        if (destinationX != null && destinationY != null)
            require(current.projection.exploredTiles.any {
                it.x == destinationX && it.y == destinationY
            }) { "Road destination is absent from the current player projection" }
        submitLocked(PendingAuthoritativeCommand.SetRoadConnectionOrder(
            commandIdFactory(), current.committedRevision, current.canonicalStateHash,
            unitId, destinationX, destinationY,
        ), current)
    }

    suspend fun swapUnits(unitId: Int, destinationX: Int, destinationY: Int) = mutex.withLock {
        val current = requireSynchronized()
        require(current.projection.ownUnits.any { it.id == unitId }) {
            "Unit is absent from the current player projection"
        }
        require(current.projection.exploredTiles.any {
            it.x == destinationX && it.y == destinationY
        }) {
            "Destination is absent from the current player projection"
        }
        submitLocked(PendingAuthoritativeCommand.SwapUnits(
            commandId = commandIdFactory(),
            expectedRevision = current.committedRevision,
            observedStateHash = current.canonicalStateHash,
            unitId = unitId,
            destinationX = destinationX,
            destinationY = destinationY,
        ), current)
    }

    suspend fun endTurn() = mutex.withLock {
        val current = requireSynchronized()
        submitLocked(PendingAuthoritativeCommand.EndTurn(
            commandId = commandIdFactory(),
            expectedRevision = current.committedRevision,
            observedStateHash = current.canonicalStateHash,
        ), current)
    }

    suspend fun queueConstruction(cityId: String, constructionName: String) = mutex.withLock {
        val current = requireSynchronized()
        val city = current.projection.ownCities.singleOrNull { it.id == cityId }
            ?: error("City is absent from the current player projection")
        require(constructionName in city.availableConstructions) {
            "Construction is absent from the current player projection"
        }
        submitLocked(PendingAuthoritativeCommand.QueueConstruction(
            commandId = commandIdFactory(),
            expectedRevision = current.committedRevision,
            observedStateHash = current.canonicalStateHash,
            cityId = cityId,
            constructionName = constructionName,
        ), current)
    }

    suspend fun queueConstructionAtTile(
        cityId: String,
        constructionName: String,
        x: Int,
        y: Int,
    ) = mutex.withLock {
        val current = requireSynchronized()
        val city = current.projection.ownCities.singleOrNull { it.id == cityId }
            ?: error("City is absent from the current player projection")
        require(constructionName in city.availableConstructions) {
            "Construction is absent from the current player projection"
        }
        require(current.projection.exploredTiles.any { it.x == x && it.y == y }) {
            "Tile is absent from the current player projection"
        }
        submitLocked(PendingAuthoritativeCommand.QueueConstructionAtTile(
            commandIdFactory(), current.committedRevision, current.canonicalStateHash,
            cityId, constructionName, x, y,
        ), current)
    }

    suspend fun setPerpetualConstruction(cityId: String, constructionName: String) = mutex.withLock {
        val current = requireSynchronized()
        val city = current.projection.ownCities.singleOrNull { it.id == cityId }
            ?: error("City is absent from the current player projection")
        require(constructionName in city.availableConstructions) {
            "Perpetual construction is absent from the current player projection"
        }
        submitLocked(PendingAuthoritativeCommand.SetPerpetualConstruction(
            commandIdFactory(), current.committedRevision, current.canonicalStateHash,
            cityId, constructionName,
        ), current)
    }

    suspend fun removeConstruction(cityId: String, queueIndex: Int, expectedConstructionName: String) = mutex.withLock {
        val current = requireSynchronized()
        requireProjectedQueueEntry(current, cityId, queueIndex, expectedConstructionName)
        submitLocked(PendingAuthoritativeCommand.RemoveConstruction(
            commandIdFactory(), current.committedRevision, current.canonicalStateHash,
            cityId, queueIndex, expectedConstructionName,
        ), current)
    }

    suspend fun moveConstruction(
        cityId: String,
        fromIndex: Int,
        toIndex: Int,
        expectedConstructionName: String,
    ) = mutex.withLock {
        val current = requireSynchronized()
        val city = requireProjectedQueueEntry(current, cityId, fromIndex, expectedConstructionName)
        require(toIndex in city.constructionQueue.indices && kotlin.math.abs(fromIndex - toIndex) == 1) {
            "Construction destination is absent from the current player projection"
        }
        submitLocked(PendingAuthoritativeCommand.MoveConstruction(
            commandIdFactory(), current.committedRevision, current.canonicalStateHash,
            cityId, fromIndex, toIndex, expectedConstructionName,
        ), current)
    }

    suspend fun purchaseConstruction(
        cityId: String,
        constructionName: String,
        currencyName: String,
        queueIndex: Int?,
    ) = mutex.withLock {
        val current = requireSynchronized()
        val city = current.projection.ownCities.singleOrNull { it.id == cityId }
            ?: error("City is absent from the current player projection")
        if (queueIndex == null) {
            require(constructionName in city.availableConstructions) {
                "Construction is absent from the current player projection"
            }
        } else {
            require(queueIndex in city.constructionQueue.indices &&
                city.constructionQueue[queueIndex] == constructionName) {
                "Construction queue entry is absent from the current player projection"
            }
        }
        require(currencyName.isNotBlank() && currencyName.length <= 32) {
            "Purchase currency is invalid"
        }
        submitLocked(PendingAuthoritativeCommand.PurchaseConstruction(
            commandIdFactory(), current.committedRevision, current.canonicalStateHash,
            cityId, constructionName, currencyName, queueIndex,
        ), current)
    }

    suspend fun purchaseConstructionAtTile(
        cityId: String,
        constructionName: String,
        currencyName: String,
        x: Int,
        y: Int,
        queueIndex: Int?,
    ) = mutex.withLock {
        val current = requireSynchronized()
        val city = current.projection.ownCities.singleOrNull { it.id == cityId }
            ?: error("City is absent from the current player projection")
        if (queueIndex == null) {
            require(constructionName in city.availableConstructions) {
                "Construction is absent from the current player projection"
            }
        } else {
            require(queueIndex in city.constructionQueue.indices &&
                city.constructionQueue[queueIndex] == constructionName) {
                "Construction queue entry is absent from the current player projection"
            }
        }
        require(currencyName.isNotBlank() && currencyName.length <= 32) {
            "Purchase currency is invalid"
        }
        require(current.projection.exploredTiles.any { it.x == x && it.y == y }) {
            "Tile is absent from the current player projection"
        }
        submitLocked(PendingAuthoritativeCommand.PurchaseConstructionAtTile(
            commandIdFactory(), current.committedRevision, current.canonicalStateHash,
            cityId, constructionName, currencyName, x, y, queueIndex,
        ), current)
    }

    suspend fun buyCityTile(cityId: String, x: Int, y: Int) = mutex.withLock {
        val current = requireSynchronized()
        require(current.projection.ownCities.any { it.id == cityId }) {
            "City is absent from the current player projection"
        }
        require(current.projection.exploredTiles.any { it.x == x && it.y == y }) {
            "Tile is absent from the current player projection"
        }
        submitLocked(PendingAuthoritativeCommand.BuyCityTile(
            commandIdFactory(), current.committedRevision, current.canonicalStateHash,
            cityId, x, y,
        ), current)
    }

    suspend fun sellBuilding(cityId: String, buildingName: String) = mutex.withLock {
        val current = requireSynchronized()
        require(current.projection.ownCities.any { it.id == cityId }) {
            "City is absent from the current player projection"
        }
        require(buildingName.isNotBlank() && buildingName.length <= 128) {
            "Building name is invalid"
        }
        submitLocked(PendingAuthoritativeCommand.SellBuilding(
            commandIdFactory(), current.committedRevision, current.canonicalStateHash,
            cityId, buildingName,
        ), current)
    }

    suspend fun setCityGovernance(cityId: String, action: CityGovernanceAction) = mutex.withLock {
        val current = requireSynchronized()
        require(current.projection.ownCities.any { it.id == cityId }) {
            "City is absent from the current player projection"
        }
        submitLocked(PendingAuthoritativeCommand.SetCityGovernance(
            commandIdFactory(), current.committedRevision, current.canonicalStateHash,
            cityId, action,
        ), current)
    }

    suspend fun resolveCityDisposition(cityId: String, action: CityDispositionAction) = mutex.withLock {
        val current = requireSynchronized()
        val decision = current.projection.pendingCityDispositions.singleOrNull { it.cityId == cityId }
            ?: error("City disposition is absent from the current player projection")
        require(action in decision.availableActions) {
            "City disposition is absent from the current player projection"
        }
        submitLocked(PendingAuthoritativeCommand.ResolveCityDisposition(
            commandIdFactory(), current.committedRevision, current.canonicalStateHash,
            cityId, action,
        ), current)
    }

    suspend fun castDiplomaticVote(candidateCivilizationId: String?) = mutex.withLock {
        val current = requireSynchronized()
        require(PendingEndTurnAction.CastDiplomaticVote in current.projection.pendingTurnActions) {
            "Diplomatic vote is absent from the current player projection"
        }
        if (candidateCivilizationId != null) {
            require(candidateCivilizationId in current.projection.diplomaticVoteCandidates) {
                "Diplomatic vote candidate is absent from the current player projection"
            }
        }
        submitLocked(PendingAuthoritativeCommand.CastDiplomaticVote(
            commandIdFactory(), current.committedRevision, current.canonicalStateHash,
            candidateCivilizationId,
        ), current)
    }

    suspend fun chooseGreatPerson(unitName: String) = mutex.withLock {
        val current = requireSynchronized()
        require(PendingEndTurnAction.PickGreatPerson in current.projection.pendingTurnActions &&
            unitName in current.projection.selectableGreatPeople) {
            "Great person is absent from the current player projection"
        }
        submitLocked(PendingAuthoritativeCommand.ChooseGreatPerson(
            commandIdFactory(), current.committedRevision, current.canonicalStateHash,
            unitName,
        ), current)
    }

    suspend fun useReligiousUnit(unitId: Int, action: ReligiousUnitAction) = mutex.withLock {
        val current = requireSynchronized()
        val unit = current.projection.ownUnits.singleOrNull { it.id == unitId }
            ?: error("Unit is absent from the current player projection")
        require(action in unit.availableReligiousActions) {
            "Religious unit action is absent from the current player projection"
        }
        submitLocked(PendingAuthoritativeCommand.UseReligiousUnit(
            commandIdFactory(), current.committedRevision, current.canonicalStateHash,
            unitId, action,
        ), current)
    }

    suspend fun useGreatPersonUnit(unitId: Int, action: GreatPersonUnitAction) = mutex.withLock {
        val current = requireSynchronized()
        val unit = current.projection.ownUnits.singleOrNull { it.id == unitId }
            ?: error("Unit is absent from the current player projection")
        require(action in unit.availableGreatPersonActions) {
            "Great-person action is absent from the current player projection"
        }
        submitLocked(PendingAuthoritativeCommand.UseGreatPersonUnit(
            commandIdFactory(), current.committedRevision, current.canonicalStateHash,
            unitId, action,
        ), current)
    }

    suspend fun giftUnit(unitId: Int) = mutex.withLock {
        val current = requireSynchronized()
        require(current.projection.ownUnits.any { it.id == unitId && it.canGift }) {
            "Unit gift is absent from the current player projection"
        }
        submitLocked(PendingAuthoritativeCommand.GiftUnit(
            commandIdFactory(), current.committedRevision, current.canonicalStateHash, unitId,
        ), current)
    }

    suspend fun transformUnit(unitId: Int, actionId: String) = mutex.withLock {
        val current = requireSynchronized()
        require(current.projection.ownUnits.any {
            it.id == unitId && it.availableTransformActions.any { action -> action.actionId == actionId }
        }) { "Unit transformation is absent from the current player projection" }
        submitLocked(PendingAuthoritativeCommand.TransformUnit(
            commandIdFactory(), current.committedRevision, current.canonicalStateHash,
            unitId, actionId,
        ), current)
    }

    suspend fun triggerUnitUnique(unitId: Int, actionId: String) = mutex.withLock {
        val current = requireSynchronized()
        require(current.projection.ownUnits.any {
            it.id == unitId && it.availableTriggerActions.any { action -> action.actionId == actionId }
        }) { "Unit trigger is absent from the current player projection" }
        submitLocked(PendingAuthoritativeCommand.TriggerUnitUnique(
            commandIdFactory(), current.committedRevision, current.canonicalStateHash,
            unitId, actionId,
        ), current)
    }

    suspend fun chooseReligiousBeliefs(
        beliefNames: List<String>,
        religionIconName: String?,
        religionDisplayName: String?,
    ) = mutex.withLock {
        val current = requireSynchronized()
        val choice = current.projection.religionChoice
            ?: error("Religious choice is absent from the current player projection")
        require(beliefNames.size == choice.requiredBeliefTypes.size &&
            beliefNames.distinct().size == beliefNames.size &&
            beliefNames.all { name -> choice.availableBeliefs.any { it.name == name } }) {
            "Religious beliefs are absent from the current player projection"
        }
        if (choice.requiresReligionIdentity) {
            require(religionIconName in choice.availableReligionIcons && !religionDisplayName.isNullOrBlank()) {
                "Religion identity is absent from the current player projection"
            }
        } else require(religionIconName == null && religionDisplayName == null) {
            "Religion identity is not accepted for this projected choice"
        }
        submitLocked(PendingAuthoritativeCommand.ChooseReligiousBeliefs(
            commandIdFactory(), current.committedRevision, current.canonicalStateHash,
            beliefNames, religionIconName, religionDisplayName,
        ), current)
    }

    suspend fun offerTrade(otherCivilizationId: String, trade: ProjectedTrade) = mutex.withLock {
        val current = requireSynchronized()
        val partner = current.projection.tradePartners.singleOrNull { it.civilizationId == otherCivilizationId }
            ?: error("Trade partner is absent from the current player projection")
        require(!partner.hasPendingOutgoingOffer && (trade.ourOffers.isNotEmpty() || trade.theirOffers.isNotEmpty())) { "Trade offer is not available" }
        submitLocked(PendingAuthoritativeCommand.OfferTrade(commandIdFactory(), current.committedRevision, current.canonicalStateHash, otherCivilizationId, trade), current)
    }

    suspend fun retractTradeOffer(otherCivilizationId: String) = mutex.withLock {
        val current = requireSynchronized()
        require(current.projection.tradePartners.any { it.civilizationId == otherCivilizationId && it.hasPendingOutgoingOffer }) { "Pending trade offer is absent" }
        submitLocked(PendingAuthoritativeCommand.RetractTradeOffer(commandIdFactory(), current.committedRevision, current.canonicalStateHash, otherCivilizationId), current)
    }

    suspend fun acceptTrade(requestId: String) = tradeDecision(requestId, true)
    suspend fun declineTrade(requestId: String) = tradeDecision(requestId, false)

    private suspend fun tradeDecision(requestId: String, accept: Boolean) = mutex.withLock {
        val current = requireSynchronized()
        require(current.projection.pendingTradeRequests.any { it.requestId == requestId }) { "Trade request is absent from the current player projection" }
        val pending = if (accept) PendingAuthoritativeCommand.AcceptTrade(commandIdFactory(), current.committedRevision, current.canonicalStateHash, requestId)
            else PendingAuthoritativeCommand.DeclineTrade(commandIdFactory(), current.committedRevision, current.canonicalStateHash, requestId)
        submitLocked(pending, current)
    }

    suspend fun counterTrade(requestId: String, trade: ProjectedTrade) = mutex.withLock {
        val current = requireSynchronized()
        require(current.projection.pendingTradeRequests.any { it.requestId == requestId }) { "Trade request is absent from the current player projection" }
        submitLocked(PendingAuthoritativeCommand.CounterTrade(
            commandIdFactory(), current.committedRevision, current.canonicalStateHash, requestId, trade,
        ), current)
    }

    suspend fun declareWar(otherId: String) = partnerDiplomacy(otherId, PartnerDiplomacyAction.DeclareWar)
    suspend fun denounceCivilization(otherId: String) = partnerDiplomacy(otherId, PartnerDiplomacyAction.Denounce)
    suspend fun offerFriendship(otherId: String) = partnerDiplomacy(otherId, PartnerDiplomacyAction.OfferFriendship)

    private suspend fun partnerDiplomacy(otherId: String, action: PartnerDiplomacyAction) = mutex.withLock {
        val current = requireSynchronized()
        val partner = current.projection.diplomacyPartners.singleOrNull { it.civilizationId == otherId }
        val cityState = current.projection.cityStatePartners.singleOrNull { it.civilizationId == otherId }
        require(partner != null || cityState != null) { "Diplomatic counterpart is absent from the current player projection" }
        val allowed = when (action) {
            PartnerDiplomacyAction.DeclareWar -> partner?.canDeclareWar == true || cityState?.canDeclareWar == true
            PartnerDiplomacyAction.Denounce -> partner?.canDenounce == true
            PartnerDiplomacyAction.OfferFriendship -> partner?.canOfferFriendship == true
        }
        require(allowed) { "Diplomatic action is absent from the current player projection" }
        val commandId = commandIdFactory()
        val pending = when (action) {
            PartnerDiplomacyAction.DeclareWar -> PendingAuthoritativeCommand.DeclareWar(commandId, current.committedRevision, current.canonicalStateHash, otherId)
            PartnerDiplomacyAction.Denounce -> PendingAuthoritativeCommand.DenounceCivilization(commandId, current.committedRevision, current.canonicalStateHash, otherId)
            PartnerDiplomacyAction.OfferFriendship -> PendingAuthoritativeCommand.OfferFriendship(commandId, current.committedRevision, current.canonicalStateHash, otherId)
        }
        submitLocked(pending, current)
    }

    suspend fun makeDiplomaticDemand(otherId: String, demand: DiplomaticDemand) = mutex.withLock {
        val current = requireSynchronized()
        require(current.projection.diplomacyPartners.any { it.civilizationId == otherId && demand in it.availableDemands }) { "Diplomatic demand is absent from the current player projection" }
        submitLocked(PendingAuthoritativeCommand.MakeDiplomaticDemand(commandIdFactory(), current.committedRevision, current.canonicalStateHash, otherId, demand), current)
    }

    suspend fun respondToDiplomaticPrompt(promptId: String, accept: Boolean) = mutex.withLock {
        val current = requireSynchronized()
        require(current.projection.diplomacyPrompts.any { it.promptId == promptId }) { "Diplomatic prompt is absent from the current player projection" }
        submitLocked(PendingAuthoritativeCommand.RespondToDiplomaticPrompt(commandIdFactory(), current.committedRevision, current.canonicalStateHash, promptId, accept), current)
    }

    suspend fun respondToCityStateProtectionPrompt(promptId: String, response: CityStateProtectionResponse) = mutex.withLock {
        val current = requireSynchronized()
        require(current.projection.diplomacyPrompts.any { it.promptId == promptId && response in it.availableCityStateResponses }) {
            "City-state protection response is absent from the current player projection"
        }
        submitLocked(PendingAuthoritativeCommand.RespondToCityStateProtectionPrompt(commandIdFactory(), current.committedRevision, current.canonicalStateHash, promptId, response), current)
    }

    suspend fun giftCityStateGold(cityStateId: String, amount: Int) = mutex.withLock {
        val current = requireSynchronized()
        val partner = current.projection.cityStatePartners.singleOrNull { it.civilizationId == cityStateId }
            ?: error("City-state is absent from the current player projection")
        require(amount in partner.availableGoldGifts) { "Gold gift is absent from the current player projection" }
        submitLocked(PendingAuthoritativeCommand.GiftCityStateGold(commandIdFactory(), current.committedRevision, current.canonicalStateHash, cityStateId, amount), current)
    }

    suspend fun setCityStateProtection(cityStateId: String, protect: Boolean) = mutex.withLock {
        val current = requireSynchronized()
        val partner = current.projection.cityStatePartners.singleOrNull { it.civilizationId == cityStateId }
            ?: error("City-state is absent from the current player projection")
        require(if (protect) partner.canPledgeProtection else partner.canRevokeProtection) { "Protection action is absent from the current player projection" }
        submitLocked(PendingAuthoritativeCommand.SetCityStateProtection(commandIdFactory(), current.committedRevision, current.canonicalStateHash, cityStateId, protect), current)
    }

    suspend fun demandCityStateTribute(cityStateId: String, worker: Boolean) = mutex.withLock {
        val current = requireSynchronized()
        val partner = current.projection.cityStatePartners.singleOrNull { it.civilizationId == cityStateId }
            ?: error("City-state is absent from the current player projection")
        require(if (worker) partner.canDemandWorker else partner.tributeGoldAmount != null) { "Tribute action is absent from the current player projection" }
        submitLocked(PendingAuthoritativeCommand.DemandCityStateTribute(commandIdFactory(), current.committedRevision, current.canonicalStateHash, cityStateId, worker), current)
    }

    suspend fun giftCityStateImprovement(cityStateId: String, x: Int, y: Int, improvementName: String) = mutex.withLock {
        val current = requireSynchronized()
        val partner = current.projection.cityStatePartners.singleOrNull { it.civilizationId == cityStateId }
            ?: error("City-state is absent from the current player projection")
        require(partner.improvementGifts.any { it.x == x && it.y == y && it.improvementName == improvementName }) {
            "Improvement gift is absent from the current player projection"
        }
        submitLocked(PendingAuthoritativeCommand.GiftCityStateImprovement(commandIdFactory(), current.committedRevision, current.canonicalStateHash, cityStateId, x, y, improvementName), current)
    }

    suspend fun negotiateCityStatePeace(cityStateId: String) = mutex.withLock {
        val current = requireSynchronized()
        require(current.projection.cityStatePartners.any { it.civilizationId == cityStateId && it.canNegotiatePeace }) {
            "City-state peace is absent from the current player projection"
        }
        submitLocked(PendingAuthoritativeCommand.NegotiateCityStatePeace(commandIdFactory(), current.committedRevision, current.canonicalStateHash, cityStateId), current)
    }

    suspend fun marryCityState(cityStateId: String) = mutex.withLock {
        val current = requireSynchronized()
        require(current.projection.cityStatePartners.any { it.civilizationId == cityStateId && it.diplomaticMarriageCost != null }) {
            "Diplomatic marriage is absent from the current player projection"
        }
        submitLocked(PendingAuthoritativeCommand.MarryCityState(commandIdFactory(), current.committedRevision, current.canonicalStateHash, cityStateId), current)
    }

    suspend fun moveSpy(spyName: String, cityId: String?) = mutex.withLock {
        val current = requireSynchronized()
        val spy = current.projection.spies.singleOrNull { it.name == spyName }
            ?: error("Spy is absent from the current player projection")
        require(if (cityId == null) spy.canMoveToHideout else cityId in spy.availableCityIds) {
            "Spy destination is absent from the current player projection"
        }
        submitLocked(PendingAuthoritativeCommand.MoveSpy(commandIdFactory(), current.committedRevision, current.canonicalStateHash, spyName, cityId), current)
    }

    suspend fun setSpyCoup(spyName: String, enabled: Boolean) = mutex.withLock {
        val current = requireSynchronized()
        val spy = current.projection.spies.singleOrNull { it.name == spyName }
            ?: error("Spy is absent from the current player projection")
        require(if (enabled) spy.canStageCoup else spy.canCancelCoup) { "Spy coup action is absent from the current player projection" }
        submitLocked(PendingAuthoritativeCommand.SetSpyCoup(commandIdFactory(), current.committedRevision, current.canonicalStateHash, spyName, enabled), current)
    }

    suspend fun resolveEventChoice(promptId: String, choiceId: String) = mutex.withLock {
        val current = requireSynchronized()
        require(current.projection.eventPrompts.any { prompt ->
            prompt.promptId == promptId && prompt.choices.any { it.choiceId == choiceId }
        }) { "Event choice is absent from the current player projection" }
        submitLocked(PendingAuthoritativeCommand.ResolveEventChoice(commandIdFactory(), current.committedRevision, current.canonicalStateHash, promptId, choiceId), current)
    }

    suspend fun setCityTileAssignment(
        cityId: String,
        x: Int,
        y: Int,
        assignment: CityTileAssignment,
    ) = mutex.withLock {
        val current = requireSynchronized()
        val city = current.projection.ownCities.singleOrNull { it.id == cityId }
            ?: error("City is absent from the current player projection")
        require(city.assignableTiles.any { it.x == x && it.y == y }) {
            "Tile is absent from the city's assignable projection"
        }
        submitLocked(PendingAuthoritativeCommand.SetCityTileAssignment(
            commandIdFactory(), current.committedRevision, current.canonicalStateHash,
            cityId, x, y, assignment,
        ), current)
    }

    suspend fun setSpecialistCount(
        cityId: String,
        specialistName: String,
        count: Int,
    ) = mutex.withLock {
        val current = requireSynchronized()
        val city = current.projection.ownCities.singleOrNull { it.id == cityId }
            ?: error("City is absent from the current player projection")
        val specialist = city.specialists.singleOrNull { it.name == specialistName }
            ?: error("Specialist is absent from the city's projection")
        require(count in 0..specialist.capacity) {
            "Specialist count is outside projected capacity"
        }
        submitLocked(PendingAuthoritativeCommand.SetSpecialistCount(
            commandIdFactory(), current.committedRevision, current.canonicalStateHash,
            cityId, specialistName, count,
        ), current)
    }

    suspend fun setManualSpecialists(cityId: String, enabled: Boolean) = mutex.withLock {
        val current = requireSynchronized()
        val city = current.projection.ownCities.singleOrNull { it.id == cityId }
            ?: error("City is absent from the current player projection")
        require(city.specialists.isNotEmpty()) {
            "City has no projected specialist slots"
        }
        submitLocked(PendingAuthoritativeCommand.SetManualSpecialists(
            commandIdFactory(), current.committedRevision, current.canonicalStateHash,
            cityId, enabled,
        ), current)
    }

    suspend fun resetCitizens(cityId: String) = mutex.withLock {
        val current = requireSynchronized()
        require(current.projection.ownCities.any { it.id == cityId }) {
            "City is absent from the current player projection"
        }
        submitLocked(PendingAuthoritativeCommand.ResetCitizens(
            commandIdFactory(), current.committedRevision, current.canonicalStateHash, cityId,
        ), current)
    }

    suspend fun setAvoidGrowth(cityId: String, enabled: Boolean) = mutex.withLock {
        val current = requireSynchronized()
        require(current.projection.ownCities.any { it.id == cityId }) {
            "City is absent from the current player projection"
        }
        submitLocked(PendingAuthoritativeCommand.SetAvoidGrowth(
            commandIdFactory(), current.committedRevision, current.canonicalStateHash,
            cityId, enabled,
        ), current)
    }

    suspend fun setCitizenFocus(cityId: String, focus: CitizenFocus) = mutex.withLock {
        val current = requireSynchronized()
        val city = current.projection.ownCities.singleOrNull { it.id == cityId }
            ?: error("City is absent from the current player projection")
        require(focus in city.selectableCitizenFocuses) {
            "Citizen focus is absent from the city's projected allowlist"
        }
        submitLocked(PendingAuthoritativeCommand.SetCitizenFocus(
            commandIdFactory(), current.committedRevision, current.canonicalStateHash,
            cityId, focus,
        ), current)
    }

    private fun requireProjectedQueueEntry(
        current: ApiV3GameProjection,
        cityId: String,
        queueIndex: Int,
        expectedConstructionName: String,
    ): ProjectedCity {
        val city = current.projection.ownCities.singleOrNull { it.id == cityId }
            ?: error("City is absent from the current player projection")
        require(queueIndex in city.constructionQueue.indices &&
            city.constructionQueue[queueIndex] == expectedConstructionName) {
            "Construction queue entry is absent from the current player projection"
        }
        return city
    }

    suspend fun setResearchPath(technologyName: String) = mutex.withLock {
        val current = requireSynchronized()
        require(technologyName in current.projection.research.selectableTargets) {
            "Technology is absent from the current player projection"
        }
        submitLocked(PendingAuthoritativeCommand.SetResearchPath(
            commandId = commandIdFactory(),
            expectedRevision = current.committedRevision,
            observedStateHash = current.canonicalStateHash,
            technologyName = technologyName,
        ), current)
    }

    suspend fun adoptPolicy(policyName: String) = mutex.withLock {
        val current = requireSynchronized()
        require(policyName in current.projection.policies.selectablePolicies) {
            "Policy is absent from the current player projection"
        }
        submitLocked(PendingAuthoritativeCommand.AdoptPolicy(
            commandId = commandIdFactory(),
            expectedRevision = current.committedRevision,
            observedStateHash = current.canonicalStateHash,
            policyName = policyName,
        ), current)
    }

    suspend fun chooseFreeTechnology(technologyName: String) = mutex.withLock {
        val current = requireSynchronized()
        require(technologyName in current.projection.research.freeTechnologyChoices) {
            "Technology is absent from the current free-technology projection"
        }
        submitLocked(PendingAuthoritativeCommand.ChooseFreeTechnology(
            commandId = commandIdFactory(),
            expectedRevision = current.committedRevision,
            observedStateHash = current.canonicalStateHash,
            technologyName = technologyName,
        ), current)
    }

    suspend fun retryPending(): AuthoritativeCommandOutcome = mutex.withLock {
        val retryable = state as? AuthoritativeSyncState.Retryable
            ?: error("There is no ambiguous command to retry")
        submitLocked(retryable.pending, retryable.current)
    }

    /** Returns a refreshed projection only when this hint proves the cached
     * view may be stale. Duplicate and older hints are intentionally ignored. */
    suspend fun reconcile(notification: ApiV3RevisionNotification): ApiV3GameProjection? =
        mutex.withLock {
            val current = cachedProjection()
            when (notification.type) {
                "resync_required" -> refreshLocked(current)
                "revision_committed" -> {
                    if (notification.gameId != gameId) return@withLock null
                    val notifiedRevision = notification.committedRevision ?: return@withLock null
                    if (current != null && notifiedRevision < current.committedRevision) {
                        return@withLock null
                    }
                    if (current != null
                        && notifiedRevision == current.committedRevision
                        && notification.canonicalStateHash == current.canonicalStateHash
                    ) return@withLock null
                    refreshLocked(current)
                }
                else -> null
            }
        }

    private suspend fun submitLocked(
        pending: PendingAuthoritativeCommand,
        current: ApiV3GameProjection,
    ): AuthoritativeCommandOutcome {
        state = AuthoritativeSyncState.Submitting(current, pending.commandId)
        val accepted = try {
            when (pending) {
                is PendingAuthoritativeCommand.MoveUnit -> transport.moveUnit(
                    gameId,
                    ApiV3MoveUnitRequest(
                        pending.commandId,
                        pending.expectedRevision,
                        pending.observedStateHash,
                        pending.unitId,
                        pending.destinationX,
                        pending.destinationY,
                    ),
                )
                is PendingAuthoritativeCommand.MoveUnitToward -> transport.moveUnitToward(
                    gameId,
                    ApiV3MoveUnitTowardRequest(
                        pending.commandId, pending.expectedRevision, pending.observedStateHash,
                        pending.unitId, pending.destinationX, pending.destinationY,
                    ),
                )
                is PendingAuthoritativeCommand.CancelUnitMovementOrder ->
                    transport.cancelUnitMovementOrder(
                        gameId,
                        ApiV3CancelUnitMovementOrderRequest(
                            pending.commandId, pending.expectedRevision,
                            pending.observedStateHash, pending.unitId,
                        ),
                    )
                is PendingAuthoritativeCommand.SetUnitExploration ->
                    transport.setUnitExploration(
                        gameId,
                        ApiV3SetUnitExplorationRequest(
                            pending.commandId, pending.expectedRevision,
                            pending.observedStateHash, pending.unitId, pending.enabled,
                        ),
                    )
                is PendingAuthoritativeCommand.SetUnitAutomation ->
                    transport.setUnitAutomation(
                        gameId,
                        ApiV3SetUnitAutomationRequest(
                            pending.commandId, pending.expectedRevision,
                            pending.observedStateHash, pending.unitId, pending.enabled,
                        ),
                    )
                is PendingAuthoritativeCommand.SetUnitPosture ->
                    transport.setUnitPosture(
                        gameId,
                        ApiV3SetUnitPostureRequest(
                            pending.commandId, pending.expectedRevision,
                            pending.observedStateHash, pending.unitId, pending.posture,
                        ),
                    )
                is PendingAuthoritativeCommand.DisbandUnit ->
                    transport.disbandUnit(
                        gameId,
                        ApiV3DisbandUnitRequest(
                            pending.commandId, pending.expectedRevision,
                            pending.observedStateHash, pending.unitId,
                        ),
                    )
                is PendingAuthoritativeCommand.PillageTile ->
                    transport.pillageTile(
                        gameId,
                        ApiV3PillageTileRequest(
                            pending.commandId, pending.expectedRevision,
                            pending.observedStateHash, pending.unitId,
                        ),
                    )
                is PendingAuthoritativeCommand.FoundCity ->
                    transport.foundCity(
                        gameId,
                        ApiV3FoundCityRequest(
                            pending.commandId, pending.expectedRevision,
                            pending.observedStateHash, pending.unitId,
                        ),
                    )
                is PendingAuthoritativeCommand.ParadropUnit ->
                    transport.paradropUnit(
                        gameId,
                        ApiV3ParadropUnitRequest(
                            pending.commandId, pending.expectedRevision,
                            pending.observedStateHash, pending.unitId,
                            pending.destinationX, pending.destinationY,
                        ),
                    )
                is PendingAuthoritativeCommand.AttackWithUnit ->
                    transport.attackWithUnit(
                        gameId,
                        ApiV3AttackWithUnitRequest(
                            pending.commandId, pending.expectedRevision,
                            pending.observedStateHash, pending.unitId,
                            pending.targetX, pending.targetY,
                        ),
                    )
                is PendingAuthoritativeCommand.BombardWithCity ->
                    transport.bombardWithCity(
                        gameId,
                        ApiV3BombardWithCityRequest(
                            pending.commandId, pending.expectedRevision,
                            pending.observedStateHash, pending.cityId,
                            pending.targetX, pending.targetY,
                        ),
                    )
                is PendingAuthoritativeCommand.LaunchNuclearStrike ->
                    transport.launchNuclearStrike(
                        gameId,
                        ApiV3LaunchNuclearStrikeRequest(
                            pending.commandId, pending.expectedRevision,
                            pending.observedStateHash, pending.unitId,
                            pending.targetX, pending.targetY,
                        ),
                    )
                is PendingAuthoritativeCommand.AirSweep ->
                    transport.airSweep(
                        gameId,
                        ApiV3AirSweepRequest(
                            pending.commandId, pending.expectedRevision,
                            pending.observedStateHash, pending.unitId,
                            pending.targetX, pending.targetY,
                        ),
                    )
                is PendingAuthoritativeCommand.UpgradeUnits ->
                    transport.upgradeUnits(
                        gameId,
                        ApiV3UpgradeUnitsRequest(
                            pending.commandId, pending.expectedRevision,
                            pending.observedStateHash, pending.unitIds, pending.targetUnitName,
                        ),
                    )
                is PendingAuthoritativeCommand.PromoteUnit ->
                    transport.promoteUnit(
                        gameId,
                        ApiV3PromoteUnitRequest(
                            pending.commandId, pending.expectedRevision,
                            pending.observedStateHash, pending.unitId, pending.promotionNames,
                            pending.saveAsCityDefault,
                        ),
                    )
                is PendingAuthoritativeCommand.SetCityUnitPromotionPreference ->
                    transport.setCityUnitPromotionPreference(
                        gameId,
                        ApiV3SetCityUnitPromotionPreferenceRequest(
                            pending.commandId, pending.expectedRevision, pending.observedStateHash,
                            pending.cityId, pending.baseUnitName, pending.enabled,
                        ),
                    )
                is PendingAuthoritativeCommand.RenameUnit ->
                    transport.renameUnit(
                        gameId,
                        ApiV3RenameUnitRequest(
                            pending.commandId, pending.expectedRevision,
                            pending.observedStateHash, pending.unitId, pending.instanceName,
                        ),
                    )
                is PendingAuthoritativeCommand.SetTileImprovementOrder ->
                    transport.setTileImprovementOrder(
                        gameId,
                        ApiV3SetTileImprovementOrderRequest(
                            pending.commandId, pending.expectedRevision,
                            pending.observedStateHash, pending.unitId,
                            pending.improvementName, pending.queuedImprovementName,
                        ),
                    )
                is PendingAuthoritativeCommand.SetRoadConnectionOrder ->
                    transport.setRoadConnectionOrder(
                        gameId,
                        ApiV3SetRoadConnectionOrderRequest(
                            pending.commandId, pending.expectedRevision,
                            pending.observedStateHash, pending.unitId,
                            pending.destinationX, pending.destinationY,
                        ),
                    )
                is PendingAuthoritativeCommand.SwapUnits -> transport.swapUnits(
                    gameId,
                    ApiV3SwapUnitsRequest(
                        pending.commandId, pending.expectedRevision, pending.observedStateHash,
                        pending.unitId, pending.destinationX, pending.destinationY,
                    ),
                )
                is PendingAuthoritativeCommand.EndTurn -> transport.endTurn(
                    gameId,
                    ApiV3EndTurnRequest(
                        pending.commandId,
                        pending.expectedRevision,
                        pending.observedStateHash,
                    ),
                )
                is PendingAuthoritativeCommand.Resign -> transport.resign(
                    gameId,
                    ApiV3ResignRequest(
                        pending.commandId,
                        pending.expectedRevision,
                        pending.observedStateHash,
                    ),
                )
                is PendingAuthoritativeCommand.QueueConstruction -> transport.queueConstruction(
                    gameId,
                    ApiV3QueueConstructionRequest(
                        pending.commandId,
                        pending.expectedRevision,
                        pending.observedStateHash,
                        pending.cityId,
                        pending.constructionName,
                    ),
                )
                is PendingAuthoritativeCommand.QueueConstructionAtTile -> transport.queueConstructionAtTile(
                    gameId,
                    ApiV3QueueConstructionAtTileRequest(
                        pending.commandId, pending.expectedRevision, pending.observedStateHash,
                        pending.cityId, pending.constructionName, pending.x, pending.y,
                    ),
                )
                is PendingAuthoritativeCommand.SetPerpetualConstruction -> transport.setPerpetualConstruction(
                    gameId,
                    ApiV3SetPerpetualConstructionRequest(
                        pending.commandId,
                        pending.expectedRevision,
                        pending.observedStateHash,
                        pending.cityId,
                        pending.constructionName,
                    ),
                )
                is PendingAuthoritativeCommand.RemoveConstruction -> transport.removeConstruction(
                    gameId,
                    ApiV3RemoveConstructionRequest(
                        pending.commandId, pending.expectedRevision, pending.observedStateHash,
                        pending.cityId, pending.queueIndex, pending.expectedConstructionName,
                    ),
                )
                is PendingAuthoritativeCommand.MoveConstruction -> transport.moveConstruction(
                    gameId,
                    ApiV3MoveConstructionRequest(
                        pending.commandId, pending.expectedRevision, pending.observedStateHash,
                        pending.cityId, pending.fromIndex, pending.toIndex,
                        pending.expectedConstructionName,
                    ),
                )
                is PendingAuthoritativeCommand.PurchaseConstruction -> transport.purchaseConstruction(
                    gameId,
                    ApiV3PurchaseConstructionRequest(
                        pending.commandId, pending.expectedRevision, pending.observedStateHash,
                        pending.cityId, pending.constructionName, pending.currencyName,
                        pending.queueIndex,
                    ),
                )
                is PendingAuthoritativeCommand.PurchaseConstructionAtTile -> transport.purchaseConstructionAtTile(
                    gameId,
                    ApiV3PurchaseConstructionAtTileRequest(
                        pending.commandId, pending.expectedRevision, pending.observedStateHash,
                        pending.cityId, pending.constructionName, pending.currencyName,
                        pending.x, pending.y, pending.queueIndex,
                    ),
                )
                is PendingAuthoritativeCommand.BuyCityTile -> transport.buyCityTile(
                    gameId,
                    ApiV3BuyCityTileRequest(
                        pending.commandId, pending.expectedRevision, pending.observedStateHash,
                        pending.cityId, pending.x, pending.y,
                    ),
                )
                is PendingAuthoritativeCommand.SellBuilding -> transport.sellBuilding(
                    gameId,
                    ApiV3SellBuildingRequest(
                        pending.commandId, pending.expectedRevision, pending.observedStateHash,
                        pending.cityId, pending.buildingName,
                    ),
                )
                is PendingAuthoritativeCommand.SetCityGovernance -> transport.setCityGovernance(
                    gameId,
                    ApiV3SetCityGovernanceRequest(
                        pending.commandId, pending.expectedRevision, pending.observedStateHash,
                        pending.cityId, pending.action,
                    ),
                )
                is PendingAuthoritativeCommand.ResolveCityDisposition -> transport.resolveCityDisposition(
                    gameId,
                    ApiV3ResolveCityDispositionRequest(
                        pending.commandId, pending.expectedRevision, pending.observedStateHash,
                        pending.cityId, pending.action,
                    ),
                )
                is PendingAuthoritativeCommand.CastDiplomaticVote -> transport.castDiplomaticVote(
                    gameId,
                    ApiV3CastDiplomaticVoteRequest(
                        pending.commandId, pending.expectedRevision, pending.observedStateHash,
                        pending.candidateCivilizationId,
                    ),
                )
                is PendingAuthoritativeCommand.ChooseGreatPerson -> transport.chooseGreatPerson(
                    gameId,
                    ApiV3ChooseGreatPersonRequest(
                        pending.commandId, pending.expectedRevision, pending.observedStateHash,
                        pending.unitName,
                    ),
                )
                is PendingAuthoritativeCommand.UseReligiousUnit -> transport.useReligiousUnit(
                    gameId,
                    ApiV3UseReligiousUnitRequest(
                        pending.commandId, pending.expectedRevision, pending.observedStateHash,
                        pending.unitId, pending.action,
                    ),
                )
                is PendingAuthoritativeCommand.UseGreatPersonUnit -> transport.useGreatPersonUnit(
                    gameId,
                    ApiV3UseGreatPersonUnitRequest(
                        pending.commandId, pending.expectedRevision, pending.observedStateHash,
                        pending.unitId, pending.action,
                    ),
                )
                is PendingAuthoritativeCommand.GiftUnit -> transport.giftUnit(
                    gameId, ApiV3GiftUnitRequest(
                        pending.commandId, pending.expectedRevision, pending.observedStateHash, pending.unitId,
                    ),
                )
                is PendingAuthoritativeCommand.TransformUnit -> transport.transformUnit(
                    gameId, ApiV3TransformUnitRequest(
                        pending.commandId, pending.expectedRevision, pending.observedStateHash,
                        pending.unitId, pending.actionId,
                    ),
                )
                is PendingAuthoritativeCommand.TriggerUnitUnique -> transport.triggerUnitUnique(
                    gameId, ApiV3TriggerUnitUniqueRequest(
                        pending.commandId, pending.expectedRevision, pending.observedStateHash,
                        pending.unitId, pending.actionId,
                    ),
                )
                is PendingAuthoritativeCommand.ChooseReligiousBeliefs -> transport.chooseReligiousBeliefs(
                    gameId,
                    ApiV3ChooseReligiousBeliefsRequest(
                        pending.commandId, pending.expectedRevision, pending.observedStateHash,
                        pending.beliefNames, pending.religionIconName, pending.religionDisplayName,
                    ),
                )
                is PendingAuthoritativeCommand.OfferTrade -> transport.offerTrade(gameId, ApiV3OfferTradeRequest(
                    pending.commandId, pending.expectedRevision, pending.observedStateHash, pending.otherCivilizationId, pending.trade,
                ))
                is PendingAuthoritativeCommand.RetractTradeOffer -> transport.retractTradeOffer(gameId, ApiV3RetractTradeOfferRequest(
                    pending.commandId, pending.expectedRevision, pending.observedStateHash, pending.otherCivilizationId,
                ))
                is PendingAuthoritativeCommand.AcceptTrade -> transport.acceptTrade(gameId, ApiV3TradeRequestDecisionRequest(
                    pending.commandId, pending.expectedRevision, pending.observedStateHash, pending.requestId,
                ))
                is PendingAuthoritativeCommand.DeclineTrade -> transport.declineTrade(gameId, ApiV3TradeRequestDecisionRequest(
                    pending.commandId, pending.expectedRevision, pending.observedStateHash, pending.requestId,
                ))
                is PendingAuthoritativeCommand.CounterTrade -> transport.counterTrade(gameId, ApiV3CounterTradeRequest(
                    pending.commandId, pending.expectedRevision, pending.observedStateHash, pending.requestId, pending.trade,
                ))
                is PendingAuthoritativeCommand.DeclareWar -> transport.declareWar(gameId, ApiV3DiplomacyPartnerRequest(pending.commandId, pending.expectedRevision, pending.observedStateHash, pending.otherCivilizationId))
                is PendingAuthoritativeCommand.DenounceCivilization -> transport.denounceCivilization(gameId, ApiV3DiplomacyPartnerRequest(pending.commandId, pending.expectedRevision, pending.observedStateHash, pending.otherCivilizationId))
                is PendingAuthoritativeCommand.OfferFriendship -> transport.offerFriendship(gameId, ApiV3DiplomacyPartnerRequest(pending.commandId, pending.expectedRevision, pending.observedStateHash, pending.otherCivilizationId))
                is PendingAuthoritativeCommand.MakeDiplomaticDemand -> transport.makeDiplomaticDemand(gameId, ApiV3DiplomaticDemandRequest(pending.commandId, pending.expectedRevision, pending.observedStateHash, pending.otherCivilizationId, pending.demand))
                is PendingAuthoritativeCommand.RespondToDiplomaticPrompt -> transport.respondToDiplomaticPrompt(gameId, ApiV3DiplomaticPromptResponseRequest(pending.commandId, pending.expectedRevision, pending.observedStateHash, pending.promptId, pending.accept))
                is PendingAuthoritativeCommand.RespondToCityStateProtectionPrompt -> transport.respondToCityStateProtectionPrompt(gameId, ApiV3CityStateProtectionPromptResponseRequest(pending.commandId, pending.expectedRevision, pending.observedStateHash, pending.promptId, pending.response))
                is PendingAuthoritativeCommand.GiftCityStateGold -> transport.giftCityStateGold(gameId, ApiV3CityStateGoldGiftRequest(pending.commandId, pending.expectedRevision, pending.observedStateHash, pending.cityStateId, pending.amount))
                is PendingAuthoritativeCommand.SetCityStateProtection -> transport.setCityStateProtection(gameId, ApiV3CityStateProtectionRequest(pending.commandId, pending.expectedRevision, pending.observedStateHash, pending.cityStateId, pending.protect))
                is PendingAuthoritativeCommand.DemandCityStateTribute -> transport.demandCityStateTribute(gameId, ApiV3CityStateTributeRequest(pending.commandId, pending.expectedRevision, pending.observedStateHash, pending.cityStateId, pending.worker))
                is PendingAuthoritativeCommand.GiftCityStateImprovement -> transport.giftCityStateImprovement(gameId, ApiV3CityStateImprovementGiftRequest(pending.commandId, pending.expectedRevision, pending.observedStateHash, pending.cityStateId, pending.x, pending.y, pending.improvementName))
                is PendingAuthoritativeCommand.NegotiateCityStatePeace -> transport.negotiateCityStatePeace(gameId, ApiV3CityStatePeaceRequest(pending.commandId, pending.expectedRevision, pending.observedStateHash, pending.cityStateId))
                is PendingAuthoritativeCommand.MarryCityState -> transport.marryCityState(gameId, ApiV3CityStateMarriageRequest(pending.commandId, pending.expectedRevision, pending.observedStateHash, pending.cityStateId))
                is PendingAuthoritativeCommand.MoveSpy -> transport.moveSpy(gameId, ApiV3MoveSpyRequest(pending.commandId, pending.expectedRevision, pending.observedStateHash, pending.spyName, pending.cityId))
                is PendingAuthoritativeCommand.SetSpyCoup -> transport.setSpyCoup(gameId, ApiV3SetSpyCoupRequest(pending.commandId, pending.expectedRevision, pending.observedStateHash, pending.spyName, pending.enabled))
                is PendingAuthoritativeCommand.ResolveEventChoice -> transport.resolveEventChoice(gameId, ApiV3ResolveEventChoiceRequest(pending.commandId, pending.expectedRevision, pending.observedStateHash, pending.promptId, pending.choiceId))
                is PendingAuthoritativeCommand.SetCityTileAssignment -> transport.setCityTileAssignment(
                    gameId,
                    ApiV3SetCityTileAssignmentRequest(
                        pending.commandId, pending.expectedRevision, pending.observedStateHash,
                        pending.cityId, pending.x, pending.y, pending.assignment,
                    ),
                )
                is PendingAuthoritativeCommand.SetSpecialistCount -> transport.setSpecialistCount(
                    gameId,
                    ApiV3SetSpecialistCountRequest(
                        pending.commandId, pending.expectedRevision, pending.observedStateHash,
                        pending.cityId, pending.specialistName, pending.count,
                    ),
                )
                is PendingAuthoritativeCommand.SetManualSpecialists -> transport.setManualSpecialists(
                    gameId,
                    ApiV3SetManualSpecialistsRequest(
                        pending.commandId, pending.expectedRevision, pending.observedStateHash,
                        pending.cityId, pending.enabled,
                    ),
                )
                is PendingAuthoritativeCommand.ResetCitizens -> transport.resetCitizens(
                    gameId,
                    ApiV3ResetCitizensRequest(
                        pending.commandId, pending.expectedRevision, pending.observedStateHash,
                        pending.cityId,
                    ),
                )
                is PendingAuthoritativeCommand.SetAvoidGrowth -> transport.setAvoidGrowth(
                    gameId,
                    ApiV3SetAvoidGrowthRequest(
                        pending.commandId, pending.expectedRevision, pending.observedStateHash,
                        pending.cityId, pending.enabled,
                    ),
                )
                is PendingAuthoritativeCommand.SetCitizenFocus -> transport.setCitizenFocus(
                    gameId,
                    ApiV3SetCitizenFocusRequest(
                        pending.commandId, pending.expectedRevision, pending.observedStateHash,
                        pending.cityId, pending.focus,
                    ),
                )
                is PendingAuthoritativeCommand.SetResearchPath -> transport.setResearchPath(
                    gameId,
                    ApiV3SetResearchPathRequest(
                        pending.commandId,
                        pending.expectedRevision,
                        pending.observedStateHash,
                        pending.technologyName,
                    ),
                )
                is PendingAuthoritativeCommand.AdoptPolicy -> transport.adoptPolicy(
                    gameId,
                    ApiV3AdoptPolicyRequest(
                        pending.commandId,
                        pending.expectedRevision,
                        pending.observedStateHash,
                        pending.policyName,
                    ),
                )
                is PendingAuthoritativeCommand.ChooseFreeTechnology -> transport.chooseFreeTechnology(
                    gameId,
                    ApiV3ChooseFreeTechnologyRequest(
                        pending.commandId,
                        pending.expectedRevision,
                        pending.observedStateHash,
                        pending.technologyName,
                    ),
                )
            }
        } catch (exception: ApiV3Exception) {
            if (exception.httpStatus == 409 && exception.error.code == "stale_revision") {
                val refreshed = refreshLocked(current)
                return AuthoritativeCommandOutcome.StaleRefreshed(refreshed)
            }
            state = AuthoritativeSyncState.Rejected(current, exception.error.code)
            return AuthoritativeCommandOutcome.Rejected(exception.error.code)
        } catch (exception: Throwable) {
            state = AuthoritativeSyncState.Retryable(current, pending, exception)
            return AuthoritativeCommandOutcome.RetryRequired
        }

        check(accepted.gameId == gameId) { "Server accepted a command for a different game" }
        check(accepted.commandId == pending.commandId) { "Server returned a different command ID" }
        check(accepted.previousRevision == pending.expectedRevision) { "Server returned an invalid parent revision" }
        if (pending is PendingAuthoritativeCommand.Resign) {
            terminalAccepted = true
            return AuthoritativeCommandOutcome.Accepted(accepted, current)
        }
        return try {
            val refreshed = transport.projection(gameId)
            check(refreshed.committedRevision == accepted.committedRevision) {
                "Projection did not reconcile to the accepted revision"
            }
            check(refreshed.canonicalStateHash == accepted.canonicalStateHash) {
                "Projection canonical hash did not match the accepted command"
            }
            state = AuthoritativeSyncState.Synchronized(refreshed)
            AuthoritativeCommandOutcome.Accepted(accepted, refreshed)
        } catch (exception: Throwable) {
            // The command may already be durable. Retain its ID and retry it;
            // the server will return the original result before we refresh.
            state = AuthoritativeSyncState.Retryable(current, pending, exception)
            AuthoritativeCommandOutcome.RetryRequired
        }
    }

    private suspend fun refreshLocked(cached: ApiV3GameProjection?): ApiV3GameProjection {
        state = AuthoritativeSyncState.Refreshing(cached)
        val refreshed = transport.projection(gameId)
        check(refreshed.gameId == gameId) { "Server returned a projection for a different game" }
        check(refreshed.projectionVersion == PlayerProjection.CURRENT_PROJECTION_VERSION) {
            "Server returned an incompatible projection version"
        }
        state = AuthoritativeSyncState.Synchronized(refreshed)
        return refreshed
    }

    private fun requireSynchronized() =
        (state as? AuthoritativeSyncState.Synchronized)?.current
            ?: error("Refresh the authoritative game before submitting a command")

    private fun cachedProjection() = when (val current = state) {
        is AuthoritativeSyncState.Synchronized -> current.current
        is AuthoritativeSyncState.Refreshing -> current.cached
        is AuthoritativeSyncState.Submitting -> current.current
        is AuthoritativeSyncState.Retryable -> current.current
        is AuthoritativeSyncState.Rejected -> current.current
        AuthoritativeSyncState.Uninitialized -> null
    }
}
