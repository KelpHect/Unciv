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
    var state: AuthoritativeSyncState = AuthoritativeSyncState.Uninitialized
        private set

    suspend fun refresh(): ApiV3GameProjection = mutex.withLock {
        refreshLocked(cachedProjection())
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
