package com.unciv.logic.multiplayer.authoritative

import com.unciv.logic.GameExecutionContext
import com.unciv.logic.GameInfo
import com.unciv.logic.GameStarter
import com.unciv.logic.civilization.PlayerType
import com.unciv.logic.files.UncivFiles
import com.unciv.logic.map.HexCoord
import com.unciv.models.metadata.GameSetupInfo
import com.unciv.models.UnitActionType
import com.unciv.models.ruleset.Building
import com.unciv.models.ruleset.INonPerpetualConstruction
import com.unciv.models.ruleset.PerpetualConstruction
import com.unciv.models.ruleset.unique.UniqueType
import com.unciv.models.stats.Stat
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

    /** Disbands one owned unit while the shared engine derives transport,
     * treasury, upkeep, and defeat consequences from canonical state. */
    fun disbandUnit(
        game: GameInfo,
        actorCivilizationId: String,
        unitId: Int,
    ): EngineResult {
        val actorCivilization = game.civilizations.singleOrNull {
            it.civID == actorCivilizationId && it.playerId == executionContext.actorId
        } ?: error("Authenticated actor is not assigned to this civilization")
        require(game.currentPlayer == actorCivilization.civID) {
            "Authenticated actor cannot disband a unit outside their turn"
        }
        val unit = actorCivilization.units.getUnitById(unitId)
            ?: error("Unit is not controlled by the authenticated actor")
        require(unit.hasMovement()) { "Unit has no movement available to disband" }

        unit.disband()
        actorCivilization.updateStatsForNextTurn()
        check(actorCivilization.units.getUnitById(unitId) == null) {
            "Disbanded unit remained in canonical state"
        }
        return result(game)
    }

    /** Upgrades a bounded set of owned units atomically from the control
     * plane's perspective. Target equivalence, costs, resources, and placement
     * are all derived from canonical state for each unit in order. */
    fun upgradeUnits(
        game: GameInfo,
        actorCivilizationId: String,
        unitIds: List<Int>,
        targetUnitName: String,
    ): EngineResult {
        val actorCivilization = game.civilizations.singleOrNull {
            it.civID == actorCivilizationId && it.playerId == executionContext.actorId
        } ?: error("Authenticated actor is not assigned to this civilization")
        require(game.currentPlayer == actorCivilization.civID) {
            "Authenticated actor cannot upgrade units outside their turn"
        }
        require(unitIds.isNotEmpty() && unitIds.size <= 100) {
            "Upgrade batch must contain between 1 and 100 units"
        }
        require(unitIds.distinct().size == unitIds.size) { "Upgrade batch contains duplicate units" }
        require(targetUnitName.isNotBlank() && targetUnitName.length <= 200) {
            "Upgrade target name is invalid"
        }

        for (unitId in unitIds) {
            val unit = actorCivilization.units.getUnitById(unitId)
                ?: error("Unit is not controlled by the authenticated actor")
            require(unit.hasMovement()) { "Unit has no movement available to upgrade" }
            require(unit.currentTile.getOwner() == actorCivilization) {
                "Unit must be in owned territory to upgrade"
            }
            require(!unit.isEmbarked()) { "Embarked unit cannot upgrade" }
            val upgradedUnit = unit.baseUnit.getUpgradeUnits(unit.cache.state)
                .map { actorCivilization.getEquivalentUnit(it) }
                .firstOrNull { it.name == targetUnitName }
                ?: error("Requested unit is not a canonical upgrade target")
            require(unit.upgrade.canUpgrade(upgradedUnit)) { "Unit cannot upgrade to requested target" }
            val cost = unit.upgrade.getCostOfUpgrade(upgradedUnit)
            require(actorCivilization.gold >= cost) { "Civilization cannot afford unit upgrade" }

            unit.upgrade.performUpgrade(upgradedUnit, isFree = false, goldCostOfUpgrade = cost)
            val resultUnit = actorCivilization.units.getUnitById(unitId)
            check(resultUnit?.baseUnit?.name == targetUnitName) {
                "Canonical unit upgrade could not be placed"
            }
        }
        actorCivilization.updateStatsForNextTurn()
        return result(game)
    }

    /** Applies a bounded promotion path to one owned unit. The client may
     * choose the path, but canonical availability, prerequisites, XP, movement,
     * attacks, and triggered effects are revalidated before every step. */
    fun promoteUnit(
        game: GameInfo,
        actorCivilizationId: String,
        unitId: Int,
        promotionNames: List<String>,
    ): EngineResult {
        val actorCivilization = game.civilizations.singleOrNull {
            it.civID == actorCivilizationId && it.playerId == executionContext.actorId
        } ?: error("Authenticated actor is not assigned to this civilization")
        require(game.currentPlayer == actorCivilization.civID) {
            "Authenticated actor cannot promote a unit outside their turn"
        }
        require(promotionNames.isNotEmpty() && promotionNames.size <= 10) {
            "Promotion path must contain between 1 and 10 promotions"
        }
        require(promotionNames.distinct().size == promotionNames.size) {
            "Promotion path contains duplicate promotions"
        }
        require(promotionNames.all { it.isNotBlank() && it.length <= 200 }) {
            "Promotion name is invalid"
        }

        for (promotionName in promotionNames) {
            val unit = actorCivilization.units.getUnitById(unitId)
                ?: error("Unit is not controlled by the authenticated actor")
            require(unit.hasMovement() && unit.attacksThisTurn == 0) {
                "Unit cannot promote after exhausting movement or attacking"
            }
            require(unit.promotions.canBePromoted()) { "Unit cannot currently be promoted" }
            require(unit.promotions.getAvailablePromotions().any { it.name == promotionName }) {
                "Requested promotion is not canonically available"
            }
            unit.promotions.addPromotion(promotionName)
            check(actorCivilization.units.getUnitById(unitId) != null) {
                "Promoted unit lost its stable identity"
            }
        }
        actorCivilization.updateStatsForNextTurn()
        return result(game)
    }

    fun renameUnit(
        game: GameInfo,
        actorCivilizationId: String,
        unitId: Int,
        instanceName: String?,
    ): EngineResult {
        val actorCivilization = game.civilizations.singleOrNull {
            it.civID == actorCivilizationId && it.playerId == executionContext.actorId
        } ?: error("Authenticated actor is not assigned to this civilization")
        require(game.currentPlayer == actorCivilization.civID) {
            "Authenticated actor cannot rename a unit outside their turn"
        }
        require(instanceName == null ||
            (instanceName.isNotBlank() && instanceName.length <= 100 && instanceName.none { it.isISOControl() })) {
            "Unit name must be null or 1-100 printable characters"
        }
        val unit = actorCivilization.units.getUnitById(unitId)
            ?: error("Unit is not controlled by the authenticated actor")
        unit.instanceName = instanceName
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
        unit.action = null
        unit.movement.moveToTile(destinationTile)
        check(unit.getTile() == destinationTile) { "Movement did not reach the requested destination" }
        return result(game)
    }

    /** Advances a unit toward a known canonical destination and persists the
     * remaining route as a server-owned order for later turn processing. */
    fun moveUnitToward(
        game: GameInfo,
        actorCivilizationId: String,
        unitId: Int,
        destination: HexCoord,
    ): EngineResult {
        val actorCivilization = game.civilizations.singleOrNull {
            it.civID == actorCivilizationId && it.playerId == executionContext.actorId
        } ?: error("Authenticated actor is not assigned to this civilization")
        require(game.currentPlayer == actorCivilization.civID) {
            "Authenticated actor cannot order a unit outside their turn"
        }
        val unit = actorCivilization.units.getUnitById(unitId)
            ?: error("Unit is not controlled by the authenticated actor")
        require(destination in game.tileMap) { "Destination is outside the canonical map" }
        val destinationTile = game.tileMap[destination]
        require(actorCivilization.hasExplored(destinationTile)) {
            "Destination is not known to the authenticated actor"
        }
        require(destinationTile != unit.getTile()) { "Unit is already at the destination" }
        require(unit.movement.canReach(destinationTile)) { "Destination is not reachable" }
        val origin = unit.getTile()
        unit.action = null
        unit.movement.headTowards(destinationTile)
        check(unit.getTile() != origin) { "Movement order made no canonical progress" }
        unit.action = if (unit.getTile() == destinationTile) null else
            "moveTo ${destination.x},${destination.y}"
        return result(game)
    }

    fun cancelUnitMovementOrder(
        game: GameInfo,
        actorCivilizationId: String,
        unitId: Int,
    ): EngineResult {
        val actorCivilization = game.civilizations.singleOrNull {
            it.civID == actorCivilizationId && it.playerId == executionContext.actorId
        } ?: error("Authenticated actor is not assigned to this civilization")
        require(game.currentPlayer == actorCivilization.civID) {
            "Authenticated actor cannot cancel a unit order outside their turn"
        }
        val unit = actorCivilization.units.getUnitById(unitId)
            ?: error("Unit is not controlled by the authenticated actor")
        require(unit.isMoving()) { "Unit has no canonical movement order" }
        unit.action = null
        return result(game)
    }

    /** Starts or stops exploration through the shared automation engine. */
    fun setUnitExploration(
        game: GameInfo,
        actorCivilizationId: String,
        unitId: Int,
        enabled: Boolean,
    ): EngineResult {
        val actorCivilization = game.civilizations.singleOrNull {
            it.civID == actorCivilizationId && it.playerId == executionContext.actorId
        } ?: error("Authenticated actor is not assigned to this civilization")
        require(game.currentPlayer == actorCivilization.civID) {
            "Authenticated actor cannot change unit exploration outside their turn"
        }
        val unit = actorCivilization.units.getUnitById(unitId)
            ?: error("Unit is not controlled by the authenticated actor")
        require(!unit.baseUnit.movesLikeAirUnits) { "Air units cannot explore" }
        if (enabled) {
            require(!unit.isExploring()) { "Unit is already exploring" }
            unit.action = com.unciv.models.UnitActionType.Explore.value
            if (unit.hasMovement()) com.unciv.logic.automation.unit.UnitAutomation.automatedExplore(unit)
        } else {
            require(unit.isExploring()) { "Unit is not exploring" }
            unit.action = null
        }
        return result(game)
    }

    /** Enables or disables broad unit automation and executes its immediate
     * canonical action through the shared rules implementation. */
    fun setUnitAutomation(
        game: GameInfo,
        actorCivilizationId: String,
        unitId: Int,
        enabled: Boolean,
    ): EngineResult {
        val actorCivilization = game.civilizations.singleOrNull {
            it.civID == actorCivilizationId && it.playerId == executionContext.actorId
        } ?: error("Authenticated actor is not assigned to this civilization")
        require(game.currentPlayer == actorCivilization.civID) {
            "Authenticated actor cannot change unit automation outside their turn"
        }
        val unit = actorCivilization.units.getUnitById(unitId)
            ?: error("Unit is not controlled by the authenticated actor")
        if (enabled) {
            require(!unit.isAutomated()) { "Unit is already automated" }
            require(unit.hasMovement()) { "Unit has no movement available for automation" }
            unit.automated = true
            com.unciv.logic.automation.unit.UnitAutomation.automateUnitMoves(unit)
        } else {
            require(unit.isAutomated()) { "Unit is not automated" }
            unit.action = null
            unit.automated = false
        }
        return result(game)
    }

    /** Applies one allowlisted passive posture after rerunning its canonical
     * eligibility rules. Raw action strings never cross the API boundary. */
    fun setUnitPosture(
        game: GameInfo,
        actorCivilizationId: String,
        unitId: Int,
        posture: UnitPosture,
    ): EngineResult {
        val actorCivilization = game.civilizations.singleOrNull {
            it.civID == actorCivilizationId && it.playerId == executionContext.actorId
        } ?: error("Authenticated actor is not assigned to this civilization")
        require(game.currentPlayer == actorCivilization.civID) {
            "Authenticated actor cannot change a unit posture outside their turn"
        }
        val unit = actorCivilization.units.getUnitById(unitId)
            ?: error("Unit is not controlled by the authenticated actor")
        require(unit.hasMovement()) { "Unit has no movement available to change posture" }
        when (posture) {
            UnitPosture.Sleep, UnitPosture.SleepUntilHealed -> {
                require(!unit.isFortified() && !unit.canFortify() && !unit.isGuarding()) {
                    "Unit cannot sleep"
                }
                val tile = unit.currentTile
                require(!(tile.hasImprovementInProgress() &&
                    unit.canBuildImprovement(tile.getTileImprovementInProgress()!!))) {
                    "Unit cannot sleep while working on an improvement"
                }
                if (posture == UnitPosture.SleepUntilHealed) {
                    require(unit.health < 100 && unit.canHealInCurrentTile()) {
                        "Unit cannot sleep until healed"
                    }
                }
                unit.action = if (posture == UnitPosture.Sleep)
                    UnitActionType.Sleep.value else UnitActionType.SleepUntilHealed.value
            }
            UnitPosture.Fortify, UnitPosture.FortifyUntilHealed -> {
                require(unit.canFortify()) { "Unit cannot fortify" }
                if (posture == UnitPosture.FortifyUntilHealed) {
                    require(unit.health < 100 && unit.canHealInCurrentTile()) {
                        "Unit cannot fortify until healed"
                    }
                    unit.fortifyUntilHealed()
                } else unit.fortify()
            }
            UnitPosture.Guard -> {
                require(unit.getMatchingUniques(UniqueType.WithdrawsBeforeMeleeCombat).any()) {
                    "Unit cannot guard"
                }
                require(!unit.isGuarding()) { "Unit is already guarding" }
                unit.action = UnitActionType.Guard.value
            }
        }
        return result(game)
    }

    /** Swaps a controlled unit with the compatible friendly unit occupying the
     * destination, using the shared movement implementation and canonical state. */
    fun swapUnits(
        game: GameInfo,
        actorCivilizationId: String,
        unitId: Int,
        destination: HexCoord,
    ): EngineResult {
        val actorCivilization = game.civilizations.singleOrNull {
            it.civID == actorCivilizationId && it.playerId == executionContext.actorId
        } ?: error("Authenticated actor is not assigned to this civilization")
        require(game.currentPlayer == actorCivilization.civID) {
            "Authenticated actor cannot swap units outside their turn"
        }
        val unit = actorCivilization.units.getUnitById(unitId)
            ?: error("Unit is not controlled by the authenticated actor")
        require(destination in game.tileMap) { "Destination is outside the canonical map" }
        val destinationTile = game.tileMap[destination]
        require(unit.movement.canUnitSwapTo(destinationTile)) {
            "Unit cannot swap with the destination occupant"
        }
        val origin = unit.getTile()
        unit.movement.swapMoveToTile(destinationTile, keepEscorting = true)
        check(unit.getTile() == destinationTile) { "Unit swap did not reach the destination" }
        check(destinationTile.getUnits().any { it == unit }) { "Swapped unit is absent from destination" }
        check(origin.getUnits().any { it.civ == actorCivilization }) {
            "Unit swap did not move a friendly occupant to the origin"
        }
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

    fun setPerpetualConstruction(
        game: GameInfo,
        actorCivilizationId: String,
        cityId: String,
        constructionName: String,
    ): EngineResult {
        val city = requireOwnedCurrentTurnCity(game, actorCivilizationId, cityId)
        require(constructionName.isNotBlank() && constructionName.length <= 128) {
            "Perpetual construction name is invalid"
        }
        val construction = requireNotNull(
            PerpetualConstruction.perpetualConstructionsMap[constructionName],
        ) { "Construction is not a perpetual construction" }
        require(city.cityConstructions.canAddToQueue(construction) &&
            !city.cityConstructions.isBeingConstructedOrEnqueued(constructionName)) {
            "Perpetual construction is not available in this city"
        }
        city.cityConstructions.addToQueue(construction)
        check(city.cityConstructions.constructionQueue.lastOrNull() == constructionName) {
            "Perpetual construction was not selected"
        }
        return result(game)
    }

    fun removeConstruction(
        game: GameInfo,
        actorCivilizationId: String,
        cityId: String,
        queueIndex: Int,
        expectedConstructionName: String,
    ): EngineResult {
        val city = requireOwnedCurrentTurnCity(game, actorCivilizationId, cityId)
        require(expectedConstructionName.isNotBlank() && expectedConstructionName.length <= 128) {
            "Expected construction name is invalid"
        }
        val queue = city.cityConstructions.constructionQueue
        require(queueIndex in queue.indices) { "Construction queue position is invalid" }
        require(queue[queueIndex] == expectedConstructionName) {
            "Construction queue entry no longer matches the client projection"
        }
        val previousSize = queue.size
        city.cityConstructions.removeFromQueue(queueIndex, false)
        check(queue.size == previousSize - 1) { "Construction queue entry was not removed" }
        return result(game)
    }

    fun moveConstruction(
        game: GameInfo,
        actorCivilizationId: String,
        cityId: String,
        fromIndex: Int,
        toIndex: Int,
        expectedConstructionName: String,
    ): EngineResult {
        val city = requireOwnedCurrentTurnCity(game, actorCivilizationId, cityId)
        require(expectedConstructionName.isNotBlank() && expectedConstructionName.length <= 128) {
            "Expected construction name is invalid"
        }
        val queue = city.cityConstructions.constructionQueue
        require(fromIndex in queue.indices && toIndex in queue.indices) {
            "Construction queue position is invalid"
        }
        require(kotlin.math.abs(fromIndex - toIndex) == 1) {
            "Construction may move only one queue position per command"
        }
        require(queue[fromIndex] == expectedConstructionName) {
            "Construction queue entry no longer matches the client projection"
        }
        if (toIndex < fromIndex) city.cityConstructions.raisePriority(fromIndex)
        else city.cityConstructions.lowerPriority(fromIndex)
        check(queue[toIndex] == expectedConstructionName) { "Construction queue was not reordered" }
        return result(game)
    }

    fun purchaseConstruction(
        game: GameInfo,
        actorCivilizationId: String,
        cityId: String,
        constructionName: String,
        currencyName: String,
        queueIndex: Int?,
    ): EngineResult {
        val city = requireOwnedCurrentTurnCity(game, actorCivilizationId, cityId)
        require(constructionName.isNotBlank() && constructionName.length <= 128) {
            "Construction name is invalid"
        }
        val currency = Stat.entries.singleOrNull { it.name == currencyName }
            ?: error("Purchase currency is invalid")
        require(currency in Stat.statsUsableToBuy) { "Purchase currency is not supported" }
        val construction = city.cityConstructions.getConstruction(constructionName)
            as? INonPerpetualConstruction
            ?: error("Construction cannot be purchased")
        require(construction !is Building || !construction.hasUnique(UniqueType.CreatesOneImprovement)) {
            "Tile-specific purchases require a dedicated command"
        }
        val cost = construction.getStatBuyCost(city, currency)
            ?: error("Construction cannot be purchased with this currency")
        require(city.cityConstructions.isConstructionPurchaseAllowed(construction, currency, cost)) {
            "Construction purchase is not legal in the canonical game state"
        }
        val canonicalQueueIndex = queueIndex ?: -1
        if (queueIndex != null) {
            require(queueIndex in city.cityConstructions.constructionQueue.indices &&
                city.cityConstructions.constructionQueue[queueIndex] == constructionName) {
                "Construction queue entry no longer matches the client projection"
            }
        }
        require(city.cityConstructions.purchaseConstruction(
            construction, canonicalQueueIndex, false, currency,
        )) { "Construction could not be placed" }
        return result(game)
    }

    fun queueConstructionAtTile(
        game: GameInfo,
        actorCivilizationId: String,
        cityId: String,
        constructionName: String,
        coordinates: HexCoord,
    ): EngineResult {
        val city = requireOwnedCurrentTurnCity(game, actorCivilizationId, cityId)
        require(constructionName.isNotBlank() && constructionName.length <= 128) {
            "Construction name is invalid"
        }
        val tile = game.tileMap.getOrNull(coordinates.x, coordinates.y)
            ?: error("Tile coordinates are outside the canonical map")
        val construction = city.cityConstructions.getConstruction(constructionName) as? Building
            ?: error("Tile-specific construction is invalid")
        val improvement = construction.getImprovementToCreate(city.getRuleset(), city.civ)
            ?: error("Construction does not create a tile improvement")
        require(city.cityConstructions.canAddToQueue(construction)) {
            "Construction cannot be queued in the canonical game state"
        }
        require(city.cityConstructions.canPlaceCreateOneImprovementOn(improvement, tile)) {
            "Construction improvement cannot be placed on this tile"
        }
        val previousQueueSize = city.cityConstructions.constructionQueue.size
        city.cityConstructions.addToQueue(construction, tile = tile)
        check(city.cityConstructions.constructionQueue.size == previousQueueSize + 1 &&
            city.cityConstructions.constructionQueue.last() == constructionName) {
            "Tile-specific construction was not queued"
        }
        check(tile.isMarkedForCreatesOneImprovement(improvement.name)) {
            "Tile-specific construction marker was not committed"
        }
        return result(game)
    }

    fun purchaseConstructionAtTile(
        game: GameInfo,
        actorCivilizationId: String,
        cityId: String,
        constructionName: String,
        currencyName: String,
        coordinates: HexCoord,
        queueIndex: Int?,
    ): EngineResult {
        val city = requireOwnedCurrentTurnCity(game, actorCivilizationId, cityId)
        require(constructionName.isNotBlank() && constructionName.length <= 128) {
            "Construction name is invalid"
        }
        val currency = Stat.entries.singleOrNull { it.name == currencyName }
            ?: error("Purchase currency is invalid")
        require(currency in Stat.statsUsableToBuy) { "Purchase currency is not supported" }
        val tile = game.tileMap.getOrNull(coordinates.x, coordinates.y)
            ?: error("Tile coordinates are outside the canonical map")
        val construction = city.cityConstructions.getConstruction(constructionName) as? Building
            ?: error("Tile-specific construction is invalid")
        val improvement = construction.getImprovementToCreate(city.getRuleset(), city.civ)
            ?: error("Construction does not create a tile improvement")
        if (queueIndex == null) {
            require(city.cityConstructions.canPlaceCreateOneImprovementOn(improvement, tile)) {
                "Construction improvement cannot be placed on this tile"
            }
        } else {
            require(tile.isMarkedForCreatesOneImprovement(improvement.name) && tile.getCity() == city) {
                "Queued construction is not marked on this city's tile"
            }
        }
        val cost = construction.getStatBuyCost(city, currency)
            ?: error("Construction cannot be purchased with this currency")
        require(city.cityConstructions.isConstructionPurchaseAllowed(construction, currency, cost)) {
            "Construction purchase is not legal in the canonical game state"
        }
        val canonicalQueueIndex = queueIndex ?: -1
        if (queueIndex != null) {
            require(queueIndex in city.cityConstructions.constructionQueue.indices &&
                city.cityConstructions.constructionQueue[queueIndex] == constructionName) {
                "Construction queue entry no longer matches the client projection"
            }
            require(city.cityConstructions.getTileForImprovement(improvement.name) == tile) {
                "Construction queue entry is bound to a different canonical tile"
            }
        }
        require(city.cityConstructions.purchaseConstruction(
            construction, canonicalQueueIndex, false, currency, tile,
        )) { "Construction could not be placed" }
        check(city.cityConstructions.isBuilt(constructionName)) {
            "Purchased construction was not completed"
        }
        check(tile.improvement == improvement.name) {
            "Purchased construction improvement was not committed"
        }
        return result(game)
    }

    fun buyCityTile(
        game: GameInfo,
        actorCivilizationId: String,
        cityId: String,
        coordinates: HexCoord,
    ): EngineResult {
        val city = requireOwnedCurrentTurnCity(game, actorCivilizationId, cityId)
        val tile = game.tileMap.getOrNull(coordinates.x, coordinates.y)
            ?: error("Tile coordinates are outside the canonical map")
        require(city.expansion.canBuyTile(tile)) {
            "Tile cannot be purchased by this city"
        }
        val canonicalCost = city.expansion.getGoldCostOfTile(tile)
        val previousGold = city.civ.gold
        city.expansion.buyTile(tile)
        check(tile.owningCity == city) { "Purchased tile ownership was not committed" }
        check(city.civ.gold == previousGold - canonicalCost) {
            "Canonical tile price was not applied"
        }
        return result(game)
    }

    fun setCityTileAssignment(
        game: GameInfo,
        actorCivilizationId: String,
        cityId: String,
        coordinates: HexCoord,
        assignment: CityTileAssignment,
    ): EngineResult {
        val city = requireOwnedCurrentTurnCity(game, actorCivilizationId, cityId)
        require(!city.isPuppet && !city.isInResistance()) {
            "City population cannot be assigned manually"
        }
        val tile = game.tileMap.getOrNull(coordinates.x, coordinates.y)
            ?: error("Tile coordinates are outside the canonical map")
        require(tile.getOwner() == city.civ && tile in city.tilesInRange && !tile.isCityCenter()) {
            "Tile is not assignable by this city"
        }
        require(!tile.isWorked() || tile.getWorkingCity() == city) {
            "Tile is worked by another city"
        }
        require(!tile.stats.getTileStats(city, city.civ).isEmpty() && !tile.isBlockaded()) {
            "Tile cannot currently be worked"
        }
        when (assignment) {
            CityTileAssignment.Unworked -> {
                require(city.isWorked(tile)) { "Tile is not worked by this city" }
                city.population.stopWorkingTile(tile.position)
            }
            CityTileAssignment.Worked, CityTileAssignment.Locked -> {
                if (!city.isWorked(tile)) {
                    require(city.population.getFreePopulation() > 0) {
                        "City has no free population"
                    }
                    city.workedTiles.add(tile.position)
                }
                if (assignment == CityTileAssignment.Locked) city.lockedTiles.add(tile.position)
                else city.lockedTiles.remove(tile.position)
            }
        }
        city.cityStats.update()
        check(city.isWorked(tile) == (assignment != CityTileAssignment.Unworked)) {
            "City tile assignment was not committed"
        }
        check(tile.isLocked() == (assignment == CityTileAssignment.Locked)) {
            "City tile lock state was not committed"
        }
        return result(game)
    }

    fun setSpecialistCount(
        game: GameInfo,
        actorCivilizationId: String,
        cityId: String,
        specialistName: String,
        count: Int,
    ): EngineResult {
        val city = requireOwnedCurrentTurnCity(game, actorCivilizationId, cityId)
        require(!city.isPuppet && !city.isInResistance()) {
            "City specialists cannot be assigned manually"
        }
        require(count >= 0 && city.getRuleset().specialists.containsKey(specialistName)) {
            "Specialist assignment is invalid"
        }
        val capacity = city.population.getMaxSpecialists()[specialistName]
        require(capacity > 0 && count <= capacity) {
            "Specialist count exceeds canonical capacity"
        }
        val previous = city.population.specialistAllocations[specialistName]
        require(count - previous <= city.population.getFreePopulation()) {
            "City has no free population for this specialist"
        }
        city.population.specialistAllocations[specialistName] = count
        city.manualSpecialists = true
        city.cityStats.update()
        check(city.population.specialistAllocations[specialistName] == count) {
            "Specialist assignment was not committed"
        }
        return result(game)
    }

    fun setManualSpecialists(
        game: GameInfo,
        actorCivilizationId: String,
        cityId: String,
        enabled: Boolean,
    ): EngineResult {
        val city = requireOwnedCurrentTurnCity(game, actorCivilizationId, cityId)
        require(!city.isPuppet && !city.isInResistance()) {
            "City specialist mode cannot be changed manually"
        }
        city.manualSpecialists = enabled
        if (!enabled) city.reassignPopulation()
        check(city.manualSpecialists == enabled) {
            "Manual specialist mode was not committed"
        }
        return result(game)
    }

    fun resetCitizens(
        game: GameInfo,
        actorCivilizationId: String,
        cityId: String,
    ): EngineResult {
        val city = requireOwnedCurrentTurnCity(game, actorCivilizationId, cityId)
        require(!city.isPuppet && !city.isInResistance()) {
            "City citizens cannot be reset manually"
        }
        city.reassignPopulation(resetLocked = true)
        check(city.lockedTiles.isEmpty()) { "Citizen reset did not clear tile locks" }
        check(city.population.getFreePopulation() == 0) {
            "Citizen reset left population unassigned"
        }
        return result(game)
    }

    fun setAvoidGrowth(
        game: GameInfo,
        actorCivilizationId: String,
        cityId: String,
        enabled: Boolean,
    ): EngineResult {
        val city = requireOwnedCurrentTurnCity(game, actorCivilizationId, cityId)
        require(!city.isPuppet && !city.isInResistance()) {
            "City growth policy cannot be changed manually"
        }
        city.avoidGrowth = enabled
        city.reassignPopulation()
        check(city.avoidGrowth == enabled) { "Avoid-growth policy was not committed" }
        return result(game)
    }

    fun setCitizenFocus(
        game: GameInfo,
        actorCivilizationId: String,
        cityId: String,
        focus: CitizenFocus,
    ): EngineResult {
        val city = requireOwnedCurrentTurnCity(game, actorCivilizationId, cityId)
        require(!city.isPuppet && !city.isInResistance()) {
            "City citizen focus cannot be changed manually"
        }
        val domainFocus = com.unciv.logic.city.CityFocus.valueOf(focus.name)
        require(domainFocus.tableEnabled) { "Citizen focus is not player-selectable" }
        require(domainFocus != com.unciv.logic.city.CityFocus.FaithFocus || game.isReligionEnabled()) {
            "Faith focus requires religion to be enabled"
        }
        city.setCityFocus(domainFocus)
        city.reassignPopulation()
        check(city.getCityFocus() == domainFocus) { "Citizen focus was not committed" }
        return result(game)
    }

    private fun requireOwnedCurrentTurnCity(
        game: GameInfo,
        actorCivilizationId: String,
        cityId: String,
    ): com.unciv.logic.city.City {
        val actorCivilization = game.civilizations.singleOrNull {
            it.civID == actorCivilizationId && it.playerId == executionContext.actorId
        } ?: error("Authenticated actor is not assigned to this civilization")
        require(game.currentPlayer == actorCivilization.civID) {
            "Authenticated actor cannot change production outside their turn"
        }
        return actorCivilization.cities.firstOrNull { it.id == cityId }
            ?: error("City is not controlled by the authenticated actor")
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

    fun adoptPolicy(
        game: GameInfo,
        actorCivilizationId: String,
        policyName: String,
    ): EngineResult {
        val actorCivilization = game.civilizations.singleOrNull {
            it.civID == actorCivilizationId && it.playerId == executionContext.actorId
        } ?: error("Authenticated actor is not assigned to this civilization")
        require(game.currentPlayer == actorCivilization.civID) {
            "Authenticated actor cannot adopt a policy outside their turn"
        }
        require(policyName.isNotBlank() && policyName.length <= 128) { "Policy name is invalid" }
        val policy = game.ruleset.policies[policyName]
            ?: error("Policy is unavailable in the pinned ruleset")
        require(actorCivilization.policies.canAdoptPolicy()) {
            "Civilization cannot afford or receive another policy"
        }
        require(actorCivilization.policies.isAdoptable(policy)) {
            "Policy cannot be adopted in the canonical game state"
        }
        actorCivilization.policies.adopt(policy)
        return result(game)
    }

    fun chooseFreeTechnology(
        game: GameInfo,
        actorCivilizationId: String,
        technologyName: String,
    ): EngineResult {
        val actorCivilization = game.civilizations.singleOrNull {
            it.civID == actorCivilizationId && it.playerId == executionContext.actorId
        } ?: error("Authenticated actor is not assigned to this civilization")
        require(game.currentPlayer == actorCivilization.civID) {
            "Authenticated actor cannot choose a free technology outside their turn"
        }
        require(technologyName.isNotBlank() && technologyName.length <= 128) {
            "Technology name is invalid"
        }
        require(actorCivilization.tech.freeTechs > 0) {
            "Civilization has no free technology grant"
        }
        require(game.ruleset.technologies.containsKey(technologyName)) {
            "Technology is unavailable in the pinned ruleset"
        }
        require(actorCivilization.tech.canBeResearched(technologyName)) {
            "Technology cannot be chosen from the canonical game state"
        }
        actorCivilization.tech.getFreeTechnology(technologyName)
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
