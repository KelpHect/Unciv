package com.unciv.logic.multiplayer.authoritative

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap

/**
 * Owns one authenticated API-v3 client lifecycle. UI code opens a game here
 * instead of constructing transports or command buses ad hoc. The initial HTTP
 * projection is always authoritative; WebSocket messages are only hints that
 * cause another authenticated HTTP reconciliation.
 */
class AuthoritativeMultiplayerSession(
    private val transport: ApiV3Transport,
    parentScope: CoroutineScope,
    private val closeTransport: Boolean = false,
) : AutoCloseable {
    private val sessionJob = SupervisorJob(parentScope.coroutineContext[Job])
    private val scope = CoroutineScope(parentScope.coroutineContext + sessionJob)
    private val mutex = Mutex()
    private val lifecycleMutex = Mutex()
    private val games = mutableMapOf<String, AuthoritativeGameCommandBus>()
    private val openedGameIds = ConcurrentHashMap.newKeySet<String>()
    private var notificationJob: Job? = null
    @Volatile
    private var negotiated = false
    @Volatile
    private var authenticated = false

    suspend fun restore(): Boolean {
        negotiate()
        authenticated = transport.restoreSession()
        if (authenticated) {
            startNotifications()
        } else {
            notificationJob?.cancel()
            notificationJob = null
            mutex.withLock { games.clear() }
            openedGameIds.clear()
        }
        return authenticated
    }

    suspend fun login(username: String, password: String): ApiV3Account {
        negotiate()
        val account = transport.login(username, password)
        authenticated = true
        startNotifications()
        return account
    }

    suspend fun register(username: String, password: String): ApiV3Account {
        negotiate()
        return transport.register(username, password)
    }

    suspend fun refreshSession() {
        requireAuthenticated()
        transport.refreshSession()
    }

    suspend fun changePassword(currentPassword: String, newPassword: String) {
        requireAuthenticated()
        transport.changePassword(currentPassword, newPassword)
    }

    suspend fun disableAccount(password: String) {
        requireAuthenticated()
        try {
            transport.disableAccount(password)
        } finally {
            clearAuthenticatedState()
        }
    }

    suspend fun deleteAccount(password: String) {
        requireAuthenticated()
        try {
            transport.deleteAccount(password)
        } finally {
            clearAuthenticatedState()
        }
    }

    suspend fun listGames(after: String? = null, limit: Int = 50): ApiV3GamePage {
        requireAuthenticated()
        return transport.listGames(after, limit)
    }

    suspend fun openGame(gameId: String): AuthoritativeGameCommandBus {
        requireAuthenticated()
        require(gameId.isNotBlank()) { "gameId must not be blank" }
        val bus = mutex.withLock {
            games.getOrPut(gameId) { AuthoritativeGameCommandBus(gameId, transport) }
        }
        bus.refresh()
        openedGameIds += gameId
        return bus
    }

    fun isGameOpen(gameId: String): Boolean = gameId in openedGameIds

    suspend fun moveUnitIfOpen(
        gameId: String,
        unitId: Int,
        destinationX: Int,
        destinationY: Int,
    ): AuthoritativeCommandOutcome? {
        val bus = mutex.withLock { games[gameId] } ?: return null
        return when (val current = bus.state) {
            is AuthoritativeSyncState.Retryable -> {
                check(current.pending is PendingAuthoritativeCommand.MoveUnit &&
                    current.pending.unitId == unitId &&
                    current.pending.destinationX == destinationX &&
                    current.pending.destinationY == destinationY) {
                    "Resolve the pending authoritative command before moving another unit"
                }
                bus.retryPending()
            }
            is AuthoritativeSyncState.Synchronized ->
                bus.moveUnit(unitId, destinationX, destinationY)
            else -> {
                bus.refresh()
                bus.moveUnit(unitId, destinationX, destinationY)
            }
        }
    }

    suspend fun moveUnitTowardIfOpen(
        gameId: String,
        unitId: Int,
        destinationX: Int,
        destinationY: Int,
    ): AuthoritativeCommandOutcome? {
        val bus = mutex.withLock { games[gameId] } ?: return null
        return when (val current = bus.state) {
            is AuthoritativeSyncState.Retryable -> {
                check(current.pending is PendingAuthoritativeCommand.MoveUnitToward &&
                    current.pending.unitId == unitId &&
                    current.pending.destinationX == destinationX &&
                    current.pending.destinationY == destinationY) {
                    "Resolve the pending authoritative command before changing a movement order"
                }
                bus.retryPending()
            }
            is AuthoritativeSyncState.Synchronized ->
                bus.moveUnitToward(unitId, destinationX, destinationY)
            else -> {
                bus.refresh()
                bus.moveUnitToward(unitId, destinationX, destinationY)
            }
        }
    }

    suspend fun cancelUnitMovementOrderIfOpen(
        gameId: String,
        unitId: Int,
    ): AuthoritativeCommandOutcome? {
        val bus = mutex.withLock { games[gameId] } ?: return null
        return when (val current = bus.state) {
            is AuthoritativeSyncState.Retryable -> {
                check(current.pending is PendingAuthoritativeCommand.CancelUnitMovementOrder &&
                    current.pending.unitId == unitId) {
                    "Resolve the pending authoritative command before cancelling another order"
                }
                bus.retryPending()
            }
            is AuthoritativeSyncState.Synchronized -> bus.cancelUnitMovementOrder(unitId)
            else -> {
                bus.refresh()
                bus.cancelUnitMovementOrder(unitId)
            }
        }
    }

    suspend fun setUnitExplorationIfOpen(
        gameId: String,
        unitId: Int,
        enabled: Boolean,
    ): AuthoritativeCommandOutcome? {
        val bus = mutex.withLock { games[gameId] } ?: return null
        return when (val current = bus.state) {
            is AuthoritativeSyncState.Retryable -> {
                check(current.pending is PendingAuthoritativeCommand.SetUnitExploration &&
                    current.pending.unitId == unitId && current.pending.enabled == enabled) {
                    "Resolve the pending authoritative command before changing exploration"
                }
                bus.retryPending()
            }
            is AuthoritativeSyncState.Synchronized -> bus.setUnitExploration(unitId, enabled)
            else -> {
                bus.refresh()
                bus.setUnitExploration(unitId, enabled)
            }
        }
    }

    suspend fun setUnitAutomationIfOpen(
        gameId: String,
        unitId: Int,
        enabled: Boolean,
    ): AuthoritativeCommandOutcome? {
        val bus = mutex.withLock { games[gameId] } ?: return null
        return when (val current = bus.state) {
            is AuthoritativeSyncState.Retryable -> {
                check(current.pending is PendingAuthoritativeCommand.SetUnitAutomation &&
                    current.pending.unitId == unitId && current.pending.enabled == enabled) {
                    "Resolve the pending authoritative command before changing automation"
                }
                bus.retryPending()
            }
            is AuthoritativeSyncState.Synchronized -> bus.setUnitAutomation(unitId, enabled)
            else -> {
                bus.refresh()
                bus.setUnitAutomation(unitId, enabled)
            }
        }
    }

    suspend fun setUnitPostureIfOpen(
        gameId: String,
        unitId: Int,
        posture: UnitPosture,
    ): AuthoritativeCommandOutcome? {
        val bus = mutex.withLock { games[gameId] } ?: return null
        return when (val current = bus.state) {
            is AuthoritativeSyncState.Retryable -> {
                check(current.pending is PendingAuthoritativeCommand.SetUnitPosture &&
                    current.pending.unitId == unitId && current.pending.posture == posture) {
                    "Resolve the pending authoritative command before changing posture"
                }
                bus.retryPending()
            }
            is AuthoritativeSyncState.Synchronized -> bus.setUnitPosture(unitId, posture)
            else -> {
                bus.refresh()
                bus.setUnitPosture(unitId, posture)
            }
        }
    }

    suspend fun disbandUnitIfOpen(
        gameId: String,
        unitId: Int,
    ): AuthoritativeCommandOutcome? {
        val bus = mutex.withLock { games[gameId] } ?: return null
        return when (val current = bus.state) {
            is AuthoritativeSyncState.Retryable -> {
                check(current.pending is PendingAuthoritativeCommand.DisbandUnit &&
                    current.pending.unitId == unitId) {
                    "Resolve the pending authoritative command before disbanding another unit"
                }
                bus.retryPending()
            }
            is AuthoritativeSyncState.Synchronized -> bus.disbandUnit(unitId)
            else -> {
                bus.refresh()
                bus.disbandUnit(unitId)
            }
        }
    }

    suspend fun pillageTileIfOpen(
        gameId: String,
        unitId: Int,
    ): AuthoritativeCommandOutcome? {
        val bus = mutex.withLock { games[gameId] } ?: return null
        return when (val current = bus.state) {
            is AuthoritativeSyncState.Retryable -> {
                check(current.pending is PendingAuthoritativeCommand.PillageTile &&
                    current.pending.unitId == unitId) {
                    "Resolve the pending authoritative command before pillaging with another unit"
                }
                bus.retryPending()
            }
            is AuthoritativeSyncState.Synchronized -> bus.pillageTile(unitId)
            else -> {
                bus.refresh()
                bus.pillageTile(unitId)
            }
        }
    }

    suspend fun foundCityIfOpen(
        gameId: String,
        unitId: Int,
    ): AuthoritativeCommandOutcome? {
        val bus = mutex.withLock { games[gameId] } ?: return null
        return when (val current = bus.state) {
            is AuthoritativeSyncState.Retryable -> {
                check(current.pending is PendingAuthoritativeCommand.FoundCity &&
                    current.pending.unitId == unitId) {
                    "Resolve the pending authoritative command before founding with another unit"
                }
                bus.retryPending()
            }
            is AuthoritativeSyncState.Synchronized -> bus.foundCity(unitId)
            else -> {
                bus.refresh()
                bus.foundCity(unitId)
            }
        }
    }

    suspend fun paradropUnitIfOpen(
        gameId: String,
        unitId: Int,
        destinationX: Int,
        destinationY: Int,
    ): AuthoritativeCommandOutcome? {
        val bus = mutex.withLock { games[gameId] } ?: return null
        return when (val current = bus.state) {
            is AuthoritativeSyncState.Retryable -> {
                check(current.pending is PendingAuthoritativeCommand.ParadropUnit &&
                    current.pending.unitId == unitId &&
                    current.pending.destinationX == destinationX &&
                    current.pending.destinationY == destinationY) {
                    "Resolve the pending authoritative command before another paradrop"
                }
                bus.retryPending()
            }
            is AuthoritativeSyncState.Synchronized ->
                bus.paradropUnit(unitId, destinationX, destinationY)
            else -> {
                bus.refresh()
                bus.paradropUnit(unitId, destinationX, destinationY)
            }
        }
    }

    suspend fun attackWithUnitIfOpen(
        gameId: String,
        unitId: Int,
        targetX: Int,
        targetY: Int,
    ): AuthoritativeCommandOutcome? {
        val bus = mutex.withLock { games[gameId] } ?: return null
        return when (val current = bus.state) {
            is AuthoritativeSyncState.Retryable -> {
                check(current.pending is PendingAuthoritativeCommand.AttackWithUnit &&
                    current.pending.unitId == unitId &&
                    current.pending.targetX == targetX &&
                    current.pending.targetY == targetY) {
                    "Resolve the pending authoritative command before another attack"
                }
                bus.retryPending()
            }
            is AuthoritativeSyncState.Synchronized ->
                bus.attackWithUnit(unitId, targetX, targetY)
            else -> {
                bus.refresh()
                bus.attackWithUnit(unitId, targetX, targetY)
            }
        }
    }

    suspend fun bombardWithCityIfOpen(
        gameId: String,
        cityId: String,
        targetX: Int,
        targetY: Int,
    ): AuthoritativeCommandOutcome? {
        val bus = mutex.withLock { games[gameId] } ?: return null
        return when (val current = bus.state) {
            is AuthoritativeSyncState.Retryable -> {
                check(current.pending is PendingAuthoritativeCommand.BombardWithCity &&
                    current.pending.cityId == cityId &&
                    current.pending.targetX == targetX &&
                    current.pending.targetY == targetY) {
                    "Resolve the pending authoritative command before another bombardment"
                }
                bus.retryPending()
            }
            is AuthoritativeSyncState.Synchronized ->
                bus.bombardWithCity(cityId, targetX, targetY)
            else -> {
                bus.refresh()
                bus.bombardWithCity(cityId, targetX, targetY)
            }
        }
    }

    suspend fun launchNuclearStrikeIfOpen(
        gameId: String,
        unitId: Int,
        targetX: Int,
        targetY: Int,
    ): AuthoritativeCommandOutcome? {
        val bus = mutex.withLock { games[gameId] } ?: return null
        return when (val current = bus.state) {
            is AuthoritativeSyncState.Retryable -> {
                check(current.pending is PendingAuthoritativeCommand.LaunchNuclearStrike &&
                    current.pending.unitId == unitId &&
                    current.pending.targetX == targetX &&
                    current.pending.targetY == targetY) {
                    "Resolve the pending authoritative command before another nuclear strike"
                }
                bus.retryPending()
            }
            is AuthoritativeSyncState.Synchronized ->
                bus.launchNuclearStrike(unitId, targetX, targetY)
            else -> {
                bus.refresh()
                bus.launchNuclearStrike(unitId, targetX, targetY)
            }
        }
    }

    suspend fun airSweepIfOpen(
        gameId: String,
        unitId: Int,
        targetX: Int,
        targetY: Int,
    ): AuthoritativeCommandOutcome? {
        val bus = mutex.withLock { games[gameId] } ?: return null
        return when (val current = bus.state) {
            is AuthoritativeSyncState.Retryable -> {
                check(current.pending is PendingAuthoritativeCommand.AirSweep &&
                    current.pending.unitId == unitId &&
                    current.pending.targetX == targetX &&
                    current.pending.targetY == targetY) {
                    "Resolve the pending authoritative command before another air sweep"
                }
                bus.retryPending()
            }
            is AuthoritativeSyncState.Synchronized -> bus.airSweep(unitId, targetX, targetY)
            else -> {
                bus.refresh()
                bus.airSweep(unitId, targetX, targetY)
            }
        }
    }

    suspend fun upgradeUnitsIfOpen(
        gameId: String,
        unitIds: List<Int>,
        targetUnitName: String,
    ): AuthoritativeCommandOutcome? {
        val bus = mutex.withLock { games[gameId] } ?: return null
        return when (val current = bus.state) {
            is AuthoritativeSyncState.Retryable -> {
                check(current.pending is PendingAuthoritativeCommand.UpgradeUnits &&
                    current.pending.unitIds == unitIds &&
                    current.pending.targetUnitName == targetUnitName) {
                    "Resolve the pending authoritative command before changing the upgrade batch"
                }
                bus.retryPending()
            }
            is AuthoritativeSyncState.Synchronized -> bus.upgradeUnits(unitIds, targetUnitName)
            else -> {
                bus.refresh()
                bus.upgradeUnits(unitIds, targetUnitName)
            }
        }
    }

    suspend fun promoteUnitIfOpen(
        gameId: String,
        unitId: Int,
        promotionNames: List<String>,
        saveAsCityDefault: Boolean = false,
    ): AuthoritativeCommandOutcome? {
        val bus = mutex.withLock { games[gameId] } ?: return null
        return when (val current = bus.state) {
            is AuthoritativeSyncState.Retryable -> {
                check(current.pending is PendingAuthoritativeCommand.PromoteUnit &&
                    current.pending.unitId == unitId &&
                    current.pending.promotionNames == promotionNames &&
                    current.pending.saveAsCityDefault == saveAsCityDefault) {
                    "Resolve the pending authoritative command before changing the promotion"
                }
                bus.retryPending()
            }
            is AuthoritativeSyncState.Synchronized -> bus.promoteUnit(unitId, promotionNames, saveAsCityDefault)
            else -> {
                bus.refresh()
                bus.promoteUnit(unitId, promotionNames, saveAsCityDefault)
            }
        }
    }

    suspend fun setCityUnitPromotionPreferenceIfOpen(
        gameId: String,
        cityId: String,
        baseUnitName: String,
        enabled: Boolean,
    ): AuthoritativeCommandOutcome? {
        val bus = mutex.withLock { games[gameId] } ?: return null
        return when (val current = bus.state) {
            is AuthoritativeSyncState.Retryable -> {
                check(current.pending is PendingAuthoritativeCommand.SetCityUnitPromotionPreference &&
                    current.pending.cityId == cityId &&
                    current.pending.baseUnitName == baseUnitName &&
                    current.pending.enabled == enabled) {
                    "Resolve the pending command before changing promotion preferences"
                }
                bus.retryPending()
            }
            is AuthoritativeSyncState.Synchronized ->
                bus.setCityUnitPromotionPreference(cityId, baseUnitName, enabled)
            else -> {
                bus.refresh()
                bus.setCityUnitPromotionPreference(cityId, baseUnitName, enabled)
            }
        }
    }

    suspend fun renameUnitIfOpen(
        gameId: String,
        unitId: Int,
        instanceName: String?,
    ): AuthoritativeCommandOutcome? {
        val bus = mutex.withLock { games[gameId] } ?: return null
        return when (val current = bus.state) {
            is AuthoritativeSyncState.Retryable -> {
                check(current.pending is PendingAuthoritativeCommand.RenameUnit &&
                    current.pending.unitId == unitId &&
                    current.pending.instanceName == instanceName) {
                    "Resolve the pending authoritative command before changing the unit name"
                }
                bus.retryPending()
            }
            is AuthoritativeSyncState.Synchronized -> bus.renameUnit(unitId, instanceName)
            else -> {
                bus.refresh()
                bus.renameUnit(unitId, instanceName)
            }
        }
    }

    suspend fun setTileImprovementOrderIfOpen(
        gameId: String,
        unitId: Int,
        improvementName: String?,
        queuedImprovementName: String?,
    ): AuthoritativeCommandOutcome? {
        val bus = mutex.withLock { games[gameId] } ?: return null
        return when (val current = bus.state) {
            is AuthoritativeSyncState.Retryable -> {
                check(current.pending is PendingAuthoritativeCommand.SetTileImprovementOrder &&
                    current.pending.unitId == unitId &&
                    current.pending.improvementName == improvementName &&
                    current.pending.queuedImprovementName == queuedImprovementName) {
                    "Resolve the pending authoritative command before changing the improvement order"
                }
                bus.retryPending()
            }
            is AuthoritativeSyncState.Synchronized ->
                bus.setTileImprovementOrder(unitId, improvementName, queuedImprovementName)
            else -> {
                bus.refresh()
                bus.setTileImprovementOrder(unitId, improvementName, queuedImprovementName)
            }
        }
    }

    suspend fun setRoadConnectionOrderIfOpen(
        gameId: String,
        unitId: Int,
        destinationX: Int?,
        destinationY: Int?,
    ): AuthoritativeCommandOutcome? {
        val bus = mutex.withLock { games[gameId] } ?: return null
        return when (val current = bus.state) {
            is AuthoritativeSyncState.Retryable -> {
                check(current.pending is PendingAuthoritativeCommand.SetRoadConnectionOrder &&
                    current.pending.unitId == unitId &&
                    current.pending.destinationX == destinationX &&
                    current.pending.destinationY == destinationY) {
                    "Resolve the pending authoritative command before changing the road order"
                }
                bus.retryPending()
            }
            is AuthoritativeSyncState.Synchronized ->
                bus.setRoadConnectionOrder(unitId, destinationX, destinationY)
            else -> {
                bus.refresh()
                bus.setRoadConnectionOrder(unitId, destinationX, destinationY)
            }
        }
    }

    suspend fun swapUnitsIfOpen(
        gameId: String,
        unitId: Int,
        destinationX: Int,
        destinationY: Int,
    ): AuthoritativeCommandOutcome? {
        val bus = mutex.withLock { games[gameId] } ?: return null
        return when (val current = bus.state) {
            is AuthoritativeSyncState.Retryable -> {
                check(current.pending is PendingAuthoritativeCommand.SwapUnits &&
                    current.pending.unitId == unitId &&
                    current.pending.destinationX == destinationX &&
                    current.pending.destinationY == destinationY) {
                    "Resolve the pending authoritative command before swapping other units"
                }
                bus.retryPending()
            }
            is AuthoritativeSyncState.Synchronized ->
                bus.swapUnits(unitId, destinationX, destinationY)
            else -> {
                bus.refresh()
                bus.swapUnits(unitId, destinationX, destinationY)
            }
        }
    }

    /** Routes a production action only when this exact game was explicitly
     * opened through API v3. A merely installed session must never capture a
     * legacy online game's turn. Ambiguous retries retain the original command
     * ID through the command bus. */
    suspend fun endTurnIfOpen(gameId: String): AuthoritativeCommandOutcome? {
        val bus = mutex.withLock { games[gameId] } ?: return null
        return when (val current = bus.state) {
            is AuthoritativeSyncState.Retryable -> {
                check(current.pending is PendingAuthoritativeCommand.EndTurn) {
                    "Resolve the pending authoritative command before ending the turn"
                }
                bus.retryPending()
            }
            is AuthoritativeSyncState.Synchronized -> bus.endTurn()
            else -> {
                bus.refresh()
                bus.endTurn()
            }
        }
    }

    /** Selects research only for a game explicitly opened through API v3.
     * Legacy and local callers receive null and retain their existing path. */
    suspend fun setResearchPathIfOpen(
        gameId: String,
        technologyName: String,
    ): AuthoritativeCommandOutcome? {
        val bus = mutex.withLock { games[gameId] } ?: return null
        return when (val current = bus.state) {
            is AuthoritativeSyncState.Retryable -> {
                check(current.pending is PendingAuthoritativeCommand.SetResearchPath &&
                    current.pending.technologyName == technologyName) {
                    "Resolve the pending authoritative command before changing research"
                }
                bus.retryPending()
            }
            is AuthoritativeSyncState.Synchronized -> bus.setResearchPath(technologyName)
            else -> {
                bus.refresh()
                bus.setResearchPath(technologyName)
            }
        }
    }

    suspend fun adoptPolicyIfOpen(
        gameId: String,
        policyName: String,
    ): AuthoritativeCommandOutcome? {
        val bus = mutex.withLock { games[gameId] } ?: return null
        return when (val current = bus.state) {
            is AuthoritativeSyncState.Retryable -> {
                check(current.pending is PendingAuthoritativeCommand.AdoptPolicy &&
                    current.pending.policyName == policyName) {
                    "Resolve the pending authoritative command before adopting a policy"
                }
                bus.retryPending()
            }
            is AuthoritativeSyncState.Synchronized -> bus.adoptPolicy(policyName)
            else -> {
                bus.refresh()
                bus.adoptPolicy(policyName)
            }
        }
    }

    suspend fun chooseFreeTechnologyIfOpen(
        gameId: String,
        technologyName: String,
    ): AuthoritativeCommandOutcome? {
        val bus = mutex.withLock { games[gameId] } ?: return null
        return when (val current = bus.state) {
            is AuthoritativeSyncState.Retryable -> {
                check(current.pending is PendingAuthoritativeCommand.ChooseFreeTechnology &&
                    current.pending.technologyName == technologyName) {
                    "Resolve the pending authoritative command before choosing a free technology"
                }
                bus.retryPending()
            }
            is AuthoritativeSyncState.Synchronized -> bus.chooseFreeTechnology(technologyName)
            else -> {
                bus.refresh()
                bus.chooseFreeTechnology(technologyName)
            }
        }
    }

    suspend fun queueConstructionIfOpen(
        gameId: String,
        cityId: String,
        constructionName: String,
    ): AuthoritativeCommandOutcome? {
        val bus = mutex.withLock { games[gameId] } ?: return null
        return when (val current = bus.state) {
            is AuthoritativeSyncState.Retryable -> {
                check(current.pending is PendingAuthoritativeCommand.QueueConstruction &&
                    current.pending.cityId == cityId &&
                    current.pending.constructionName == constructionName) {
                    "Resolve the pending authoritative command before changing production"
                }
                bus.retryPending()
            }
            is AuthoritativeSyncState.Synchronized -> bus.queueConstruction(cityId, constructionName)
            else -> {
                bus.refresh()
                bus.queueConstruction(cityId, constructionName)
            }
        }
    }

    suspend fun queueConstructionAtTileIfOpen(
        gameId: String,
        cityId: String,
        constructionName: String,
        x: Int,
        y: Int,
    ): AuthoritativeCommandOutcome? {
        val bus = mutex.withLock { games[gameId] } ?: return null
        return when (val current = bus.state) {
            is AuthoritativeSyncState.Retryable -> {
                check(current.pending is PendingAuthoritativeCommand.QueueConstructionAtTile &&
                    current.pending.cityId == cityId &&
                    current.pending.constructionName == constructionName &&
                    current.pending.x == x && current.pending.y == y) {
                    "Resolve the pending authoritative command before changing production"
                }
                bus.retryPending()
            }
            is AuthoritativeSyncState.Synchronized ->
                bus.queueConstructionAtTile(cityId, constructionName, x, y)
            else -> {
                bus.refresh()
                bus.queueConstructionAtTile(cityId, constructionName, x, y)
            }
        }
    }

    suspend fun setPerpetualConstructionIfOpen(
        gameId: String,
        cityId: String,
        constructionName: String,
    ): AuthoritativeCommandOutcome? {
        val bus = mutex.withLock { games[gameId] } ?: return null
        return when (val current = bus.state) {
            is AuthoritativeSyncState.Retryable -> {
                check(current.pending is PendingAuthoritativeCommand.SetPerpetualConstruction &&
                    current.pending.cityId == cityId &&
                    current.pending.constructionName == constructionName) {
                    "Resolve the pending authoritative command before changing production"
                }
                bus.retryPending()
            }
            is AuthoritativeSyncState.Synchronized ->
                bus.setPerpetualConstruction(cityId, constructionName)
            else -> {
                bus.refresh()
                bus.setPerpetualConstruction(cityId, constructionName)
            }
        }
    }

    suspend fun removeConstructionIfOpen(
        gameId: String,
        cityId: String,
        queueIndex: Int,
        expectedConstructionName: String,
    ): AuthoritativeCommandOutcome? {
        val bus = mutex.withLock { games[gameId] } ?: return null
        return when (val current = bus.state) {
            is AuthoritativeSyncState.Retryable -> {
                check(current.pending is PendingAuthoritativeCommand.RemoveConstruction &&
                    current.pending.cityId == cityId &&
                    current.pending.queueIndex == queueIndex &&
                    current.pending.expectedConstructionName == expectedConstructionName) {
                    "Resolve the pending authoritative command before changing production"
                }
                bus.retryPending()
            }
            is AuthoritativeSyncState.Synchronized ->
                bus.removeConstruction(cityId, queueIndex, expectedConstructionName)
            else -> {
                bus.refresh()
                bus.removeConstruction(cityId, queueIndex, expectedConstructionName)
            }
        }
    }

    suspend fun moveConstructionIfOpen(
        gameId: String,
        cityId: String,
        fromIndex: Int,
        toIndex: Int,
        expectedConstructionName: String,
    ): AuthoritativeCommandOutcome? {
        val bus = mutex.withLock { games[gameId] } ?: return null
        return when (val current = bus.state) {
            is AuthoritativeSyncState.Retryable -> {
                check(current.pending is PendingAuthoritativeCommand.MoveConstruction &&
                    current.pending.cityId == cityId &&
                    current.pending.fromIndex == fromIndex &&
                    current.pending.toIndex == toIndex &&
                    current.pending.expectedConstructionName == expectedConstructionName) {
                    "Resolve the pending authoritative command before changing production"
                }
                bus.retryPending()
            }
            is AuthoritativeSyncState.Synchronized ->
                bus.moveConstruction(cityId, fromIndex, toIndex, expectedConstructionName)
            else -> {
                bus.refresh()
                bus.moveConstruction(cityId, fromIndex, toIndex, expectedConstructionName)
            }
        }
    }

    suspend fun purchaseConstructionIfOpen(
        gameId: String,
        cityId: String,
        constructionName: String,
        currencyName: String,
        queueIndex: Int?,
    ): AuthoritativeCommandOutcome? {
        val bus = mutex.withLock { games[gameId] } ?: return null
        return when (val current = bus.state) {
            is AuthoritativeSyncState.Retryable -> {
                check(current.pending is PendingAuthoritativeCommand.PurchaseConstruction &&
                    current.pending.cityId == cityId &&
                    current.pending.constructionName == constructionName &&
                    current.pending.currencyName == currencyName &&
                    current.pending.queueIndex == queueIndex) {
                    "Resolve the pending authoritative command before purchasing production"
                }
                bus.retryPending()
            }
            is AuthoritativeSyncState.Synchronized ->
                bus.purchaseConstruction(cityId, constructionName, currencyName, queueIndex)
            else -> {
                bus.refresh()
                bus.purchaseConstruction(cityId, constructionName, currencyName, queueIndex)
            }
        }
    }

    suspend fun purchaseConstructionAtTileIfOpen(
        gameId: String,
        cityId: String,
        constructionName: String,
        currencyName: String,
        x: Int,
        y: Int,
        queueIndex: Int?,
    ): AuthoritativeCommandOutcome? {
        val bus = mutex.withLock { games[gameId] } ?: return null
        return when (val current = bus.state) {
            is AuthoritativeSyncState.Retryable -> {
                check(current.pending is PendingAuthoritativeCommand.PurchaseConstructionAtTile &&
                    current.pending.cityId == cityId &&
                    current.pending.constructionName == constructionName &&
                    current.pending.currencyName == currencyName &&
                    current.pending.x == x && current.pending.y == y &&
                    current.pending.queueIndex == queueIndex) {
                    "Resolve the pending authoritative command before purchasing another construction"
                }
                bus.retryPending()
            }
            is AuthoritativeSyncState.Synchronized ->
                bus.purchaseConstructionAtTile(cityId, constructionName, currencyName, x, y, queueIndex)
            else -> {
                bus.refresh()
                bus.purchaseConstructionAtTile(cityId, constructionName, currencyName, x, y, queueIndex)
            }
        }
    }

    suspend fun buyCityTileIfOpen(
        gameId: String,
        cityId: String,
        x: Int,
        y: Int,
    ): AuthoritativeCommandOutcome? {
        val bus = mutex.withLock { games[gameId] } ?: return null
        return when (val current = bus.state) {
            is AuthoritativeSyncState.Retryable -> {
                check(current.pending is PendingAuthoritativeCommand.BuyCityTile &&
                    current.pending.cityId == cityId &&
                    current.pending.x == x && current.pending.y == y) {
                    "Resolve the pending authoritative command before buying another tile"
                }
                bus.retryPending()
            }
            is AuthoritativeSyncState.Synchronized -> bus.buyCityTile(cityId, x, y)
            else -> {
                bus.refresh()
                bus.buyCityTile(cityId, x, y)
            }
        }
    }

    suspend fun sellBuildingIfOpen(
        gameId: String,
        cityId: String,
        buildingName: String,
    ): AuthoritativeCommandOutcome? {
        val bus = mutex.withLock { games[gameId] } ?: return null
        return when (val current = bus.state) {
            is AuthoritativeSyncState.Retryable -> {
                check(current.pending is PendingAuthoritativeCommand.SellBuilding &&
                    current.pending.cityId == cityId &&
                    current.pending.buildingName == buildingName) {
                    "Resolve the pending authoritative command before selling another building"
                }
                bus.retryPending()
            }
            is AuthoritativeSyncState.Synchronized -> bus.sellBuilding(cityId, buildingName)
            else -> {
                bus.refresh()
                bus.sellBuilding(cityId, buildingName)
            }
        }
    }

    suspend fun setCityTileAssignmentIfOpen(
        gameId: String,
        cityId: String,
        x: Int,
        y: Int,
        assignment: CityTileAssignment,
    ): AuthoritativeCommandOutcome? {
        val bus = mutex.withLock { games[gameId] } ?: return null
        return when (val current = bus.state) {
            is AuthoritativeSyncState.Retryable -> {
                check(current.pending is PendingAuthoritativeCommand.SetCityTileAssignment &&
                    current.pending.cityId == cityId && current.pending.x == x &&
                    current.pending.y == y && current.pending.assignment == assignment) {
                    "Resolve the pending authoritative command before changing another tile assignment"
                }
                bus.retryPending()
            }
            is AuthoritativeSyncState.Synchronized ->
                bus.setCityTileAssignment(cityId, x, y, assignment)
            else -> {
                bus.refresh()
                bus.setCityTileAssignment(cityId, x, y, assignment)
            }
        }
    }

    suspend fun setSpecialistCountIfOpen(
        gameId: String,
        cityId: String,
        specialistName: String,
        count: Int,
    ): AuthoritativeCommandOutcome? {
        val bus = mutex.withLock { games[gameId] } ?: return null
        return when (val current = bus.state) {
            is AuthoritativeSyncState.Retryable -> {
                check(current.pending is PendingAuthoritativeCommand.SetSpecialistCount &&
                    current.pending.cityId == cityId &&
                    current.pending.specialistName == specialistName &&
                    current.pending.count == count) {
                    "Resolve the pending authoritative command before changing another specialist"
                }
                bus.retryPending()
            }
            is AuthoritativeSyncState.Synchronized ->
                bus.setSpecialistCount(cityId, specialistName, count)
            else -> {
                bus.refresh()
                bus.setSpecialistCount(cityId, specialistName, count)
            }
        }
    }

    suspend fun setManualSpecialistsIfOpen(
        gameId: String,
        cityId: String,
        enabled: Boolean,
    ): AuthoritativeCommandOutcome? {
        val bus = mutex.withLock { games[gameId] } ?: return null
        return when (val current = bus.state) {
            is AuthoritativeSyncState.Retryable -> {
                check(current.pending is PendingAuthoritativeCommand.SetManualSpecialists &&
                    current.pending.cityId == cityId && current.pending.enabled == enabled) {
                    "Resolve the pending authoritative command before changing specialist mode"
                }
                bus.retryPending()
            }
            is AuthoritativeSyncState.Synchronized -> bus.setManualSpecialists(cityId, enabled)
            else -> {
                bus.refresh()
                bus.setManualSpecialists(cityId, enabled)
            }
        }
    }

    suspend fun resetCitizensIfOpen(
        gameId: String,
        cityId: String,
    ): AuthoritativeCommandOutcome? {
        val bus = mutex.withLock { games[gameId] } ?: return null
        return when (val current = bus.state) {
            is AuthoritativeSyncState.Retryable -> {
                check(current.pending is PendingAuthoritativeCommand.ResetCitizens &&
                    current.pending.cityId == cityId) {
                    "Resolve the pending authoritative command before resetting citizens"
                }
                bus.retryPending()
            }
            is AuthoritativeSyncState.Synchronized -> bus.resetCitizens(cityId)
            else -> {
                bus.refresh()
                bus.resetCitizens(cityId)
            }
        }
    }

    suspend fun setAvoidGrowthIfOpen(
        gameId: String,
        cityId: String,
        enabled: Boolean,
    ): AuthoritativeCommandOutcome? {
        val bus = mutex.withLock { games[gameId] } ?: return null
        return when (val current = bus.state) {
            is AuthoritativeSyncState.Retryable -> {
                check(current.pending is PendingAuthoritativeCommand.SetAvoidGrowth &&
                    current.pending.cityId == cityId && current.pending.enabled == enabled) {
                    "Resolve the pending authoritative command before changing growth policy"
                }
                bus.retryPending()
            }
            is AuthoritativeSyncState.Synchronized -> bus.setAvoidGrowth(cityId, enabled)
            else -> {
                bus.refresh()
                bus.setAvoidGrowth(cityId, enabled)
            }
        }
    }

    suspend fun setCitizenFocusIfOpen(
        gameId: String,
        cityId: String,
        focus: CitizenFocus,
    ): AuthoritativeCommandOutcome? {
        val bus = mutex.withLock { games[gameId] } ?: return null
        return when (val current = bus.state) {
            is AuthoritativeSyncState.Retryable -> {
                check(current.pending is PendingAuthoritativeCommand.SetCitizenFocus &&
                    current.pending.cityId == cityId && current.pending.focus == focus) {
                    "Resolve the pending authoritative command before changing citizen focus"
                }
                bus.retryPending()
            }
            is AuthoritativeSyncState.Synchronized -> bus.setCitizenFocus(cityId, focus)
            else -> {
                bus.refresh()
                bus.setCitizenFocus(cityId, focus)
            }
        }
    }

    suspend fun closeGame(gameId: String) {
        mutex.withLock { games.remove(gameId) }
        openedGameIds -= gameId
    }

    suspend fun logout() {
        if (authenticated) transport.logout()
        clearAuthenticatedState()
    }

    private suspend fun clearAuthenticatedState() {
        authenticated = false
        notificationJob?.cancel()
        notificationJob = null
        mutex.withLock { games.clear() }
        openedGameIds.clear()
    }

    private suspend fun negotiate() = lifecycleMutex.withLock {
        if (!negotiated) {
            val capabilities = transport.capabilities()
            check(capabilities.protocolVersion == CommandEnvelope.CURRENT_PROTOCOL_VERSION) {
                "Server uses an incompatible authoritative protocol"
            }
            check(capabilities.projectionVersion == PlayerProjection.CURRENT_PROJECTION_VERSION) {
                "Server uses an incompatible player projection"
            }
            check(!capabilities.wholeStateUpload) {
                "Authoritative API unexpectedly permits whole-state uploads"
            }
            negotiated = true
        }
    }

    private fun requireAuthenticated() {
        check(authenticated) { "Authenticate before opening an authoritative game" }
    }

    private fun startNotifications() {
        if (notificationJob?.isActive == true) return
        notificationJob = scope.launch {
            transport.notifications().collect { notification ->
                val targets = mutex.withLock {
                    if (notification.type == "resync_required") games.values.toList()
                    else listOfNotNull(notification.gameId?.let(games::get))
                }
                targets.forEach { bus ->
                    // A failed HTTP refresh must not permanently stop later
                    // notification hints. The bus retains its retry state.
                    runCatching { bus.reconcile(notification) }
                }
            }
        }
    }

    override fun close() {
        notificationJob?.cancel()
        sessionJob.cancel()
        if (closeTransport) (transport as? AutoCloseable)?.close()
    }

    companion object {
        fun create(
            transport: ApiV3Transport,
            scope: CoroutineScope = CoroutineScope(SupervisorJob()),
            closeTransport: Boolean = false,
        ) = AuthoritativeMultiplayerSession(transport, scope, closeTransport)
    }
}
