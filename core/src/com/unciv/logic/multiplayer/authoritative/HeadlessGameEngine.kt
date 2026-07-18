package com.unciv.logic.multiplayer.authoritative

import com.unciv.logic.GameExecutionContext
import com.unciv.logic.GameInfo
import com.unciv.logic.GameStarter
import com.unciv.logic.civilization.PlayerType
import com.unciv.logic.files.UncivFiles
import com.unciv.logic.map.HexCoord
import com.unciv.models.metadata.GameSetupInfo
import java.security.MessageDigest

/**
 * Kotlin-side execution boundary for API v3. This is intentionally private to
 * the future worker protocol: it reuses Unciv's existing game engine and does
 * not listen on a network port or commit persistence.
 */
class HeadlessGameEngine(
    private val executionContext: GameExecutionContext,
) {
    init {
        require(executionContext.actorId != null) { "Authoritative execution requires an authenticated actor" }
        require(!executionContext.persistLocalSettings) { "Authoritative execution must not persist client settings" }
        require(!executionContext.allowUiSideEffects) { "Authoritative execution must not trigger UI effects" }
        require(executionContext.rulesetManifest != null) { "Authoritative execution requires a pinned ruleset manifest" }
    }

    fun createGame(setup: GameSetupInfo): EngineResult {
        val game = GameStarter.startNewGame(setup, executionContext)
        return result(game)
    }

    /** Assigns the authenticated actor to the first canonical unclaimed major
     * civilization. Selection is server deterministic and accepts no client
     * civilization input. The control plane restricts joining to revision 0. */
    fun assignPlayer(game: GameInfo): PlayerAssignmentResult {
        require(game.civilizations.none { it.playerId == executionContext.actorId }) {
            "Authenticated actor is already assigned to this game"
        }
        val civilization = game.civilizations.firstOrNull {
            it.isMajorCiv() && it.isAI() && it.playerId.isEmpty()
        } ?: error("No unassigned civilization is available")
        civilization.playerType = PlayerType.Human
        civilization.playerId = executionContext.actorId!!
        return PlayerAssignmentResult(result(game), civilization.civID)
    }

    /** Runs shared turn processing only for the authenticated civilization.
     * The civilization ID comes from server membership, never the client. */
    fun endTurn(game: GameInfo, actorCivilizationId: String): EngineResult {
        val actorCivilization = game.civilizations.singleOrNull {
            it.civID == actorCivilizationId && it.playerId == executionContext.actorId
        } ?: error("Authenticated actor is not assigned to this civilization")
        require(game.currentPlayer == actorCivilization.civID) {
            "Authenticated actor cannot end another civilization's turn"
        }
        val pendingActions = AuthoritativeTurnReadiness.pendingActions(actorCivilization)
        require(pendingActions.isEmpty()) {
            "Resolve mandatory turn actions: ${pendingActions.joinToString { it.wireName }}"
        }
        game.nextTurn(executionContext = executionContext)
        return result(game)
    }

    /** Applies one exact movement intent through Unciv's canonical movement
     * implementation. Actor, unit ownership, turn, bounds, and legality are
     * all derived from the loaded server state. */
    fun moveUnit(
        game: GameInfo,
        actorCivilizationId: String,
        unitId: Int,
        destination: HexCoord,
    ): EngineResult {
        val actorCivilization = game.civilizations.singleOrNull {
            it.civID == actorCivilizationId && it.playerId == executionContext.actorId
        } ?: error("Authenticated actor is not assigned to this civilization")
        require(game.currentPlayer == actorCivilization.civID) {
            "Authenticated actor cannot move a unit outside their turn"
        }
        val unit = actorCivilization.units.getUnitById(unitId)
            ?: error("Unit is not controlled by the authenticated actor")
        require(destination in game.tileMap) { "Destination is outside the canonical map" }
        val destinationTile = game.tileMap[destination]
        require(destinationTile != unit.getTile()) { "Unit is already at the destination" }
        require(unit.movement.canReachInCurrentTurn(destinationTile)) {
            "Destination is not reachable this turn"
        }
        require(unit.movement.canMoveTo(destinationTile)) {
            "Unit cannot enter the destination"
        }
        unit.movement.moveToTile(destinationTile)
        check(unit.getTile() == destinationTile) { "Movement did not reach the requested destination" }
        return result(game)
    }

    /** Adds one explicitly named construction to a city owned by the
     * authenticated civilization. The shared construction model remains the
     * source of truth for prerequisites, queue capacity, and uniqueness. */
    fun queueConstruction(
        game: GameInfo,
        actorCivilizationId: String,
        cityId: String,
        constructionName: String,
    ): EngineResult {
        val actorCivilization = game.civilizations.singleOrNull {
            it.civID == actorCivilizationId && it.playerId == executionContext.actorId
        } ?: error("Authenticated actor is not assigned to this civilization")
        require(game.currentPlayer == actorCivilization.civID) {
            "Authenticated actor cannot change production outside their turn"
        }
        val city = actorCivilization.cities.firstOrNull { it.id == cityId }
            ?: error("City is not controlled by the authenticated actor")
        require(constructionName.isNotBlank() && constructionName.length <= 128) {
            "Construction name is invalid"
        }
        val construction = city.cityConstructions.getConstruction(constructionName)
        require(city.cityConstructions.canAddToQueue(construction)) {
            "Construction cannot be added to this city"
        }
        val previousSize = city.cityConstructions.constructionQueue.size
        city.cityConstructions.addToQueue(construction)
        check(city.cityConstructions.constructionQueue.size == previousSize + 1) {
            "Construction queue was not updated"
        }
        return result(game)
    }

    /** Selects a destination technology. The client cannot author a research
     * queue: the shared rules engine derives and orders every prerequisite. */
    fun setResearchPath(
        game: GameInfo,
        actorCivilizationId: String,
        technologyName: String,
    ): EngineResult {
        val actorCivilization = game.civilizations.singleOrNull {
            it.civID == actorCivilizationId && it.playerId == executionContext.actorId
        } ?: error("Authenticated actor is not assigned to this civilization")
        require(game.currentPlayer == actorCivilization.civID) {
            "Authenticated actor cannot select research outside their turn"
        }
        require(technologyName.isNotBlank() && technologyName.length <= 128) {
            "Technology name is invalid"
        }
        require(actorCivilization.tech.freeTechs == 0) {
            "A free technology requires the dedicated free-technology command"
        }
        val technology = game.ruleset.technologies[technologyName]
            ?: error("Technology is unavailable in the pinned ruleset")
        val path = actorCivilization.tech.getRequiredTechsToDestination(technology)
        require(path.isNotEmpty()) { "Technology cannot be selected for research" }
        actorCivilization.tech.techsToResearch = ArrayList(path.map { it.name })
        actorCivilization.tech.updateResearchProgress()
        return result(game)
    }

    fun playerProjection(game: GameInfo, actorCivilizationId: String): PlayerProjection {
        val actorCivilization = game.civilizations.singleOrNull {
            it.civID == actorCivilizationId && it.playerId == executionContext.actorId
        } ?: error("Authenticated actor is not assigned to this civilization")
        return PlayerProjectionBuilder.build(game, actorCivilization)
    }

    /**
     * Rebuilds the ruleset-dependent transient graph from a canonical snapshot.
     * Snapshot validation, size limits, and version selection belong to the
     * Rust control plane before a worker receives this payload.
     */
    fun loadSnapshot(snapshot: String): GameInfo = UncivFiles.gameInfoFromString(snapshot)

    fun serializeSnapshot(game: GameInfo): String =
        UncivFiles.gameInfoToString(game, forceZip = false, updateChecksum = false)

    fun stateHash(game: GameInfo): String {
        val bytes = serializeSnapshot(game).toByteArray(Charsets.UTF_8)
        return MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    }

    private fun result(game: GameInfo) = EngineResult(game, stateHash(game))
}

data class EngineResult(
    val game: GameInfo,
    val canonicalStateHash: String,
)

data class PlayerAssignmentResult(
    val result: EngineResult,
    val civilizationId: String,
)
