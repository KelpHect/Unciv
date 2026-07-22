package com.unciv.logic.multiplayer.authoritative

import com.unciv.Constants
import com.unciv.logic.GameExecutionContext
import com.unciv.logic.GameInfo
import com.unciv.logic.GameStarter
import com.unciv.logic.battle.AirSweepExecutor
import com.unciv.logic.battle.Battle
import com.unciv.logic.battle.CityCombatant
import com.unciv.logic.battle.NuclearStrikeExecutor
import com.unciv.logic.battle.TargetHelper
import com.unciv.logic.battle.UnitAttackExecutor
import com.unciv.logic.civilization.PlayerType
import com.unciv.logic.civilization.Civilization
import com.unciv.logic.civilization.managers.ImprovementFunctions
import com.unciv.logic.files.UncivFiles
import com.unciv.logic.map.HexCoord
import com.unciv.logic.map.MapPathing
import com.unciv.logic.map.mapunit.actions.UnitPillage
import com.unciv.logic.map.mapunit.actions.UnitCityFounding
import com.unciv.logic.map.mapunit.actions.UnitParadrop
import com.unciv.logic.map.tile.ImprovementBuildingProblem
import com.unciv.models.metadata.GameSetupInfo
import com.unciv.models.UnitActionType
import com.unciv.models.ruleset.Building
import com.unciv.models.ruleset.INonPerpetualConstruction
import com.unciv.models.ruleset.PerpetualConstruction
import com.unciv.models.ruleset.unique.GameContext
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

    fun offerTrade(game: GameInfo, actorCivilizationId: String, otherCivilizationId: String, trade: ProjectedTrade): EngineResult {
        val actor = authenticatedCivilization(game, actorCivilizationId)
        TradeCommandExecutor.offer(game, actor, otherCivilizationId, trade)
        return result(game)
    }

    fun retractTradeOffer(game: GameInfo, actorCivilizationId: String, otherCivilizationId: String): EngineResult {
        val actor = authenticatedCivilization(game, actorCivilizationId)
        TradeCommandExecutor.retract(game, actor, otherCivilizationId)
        return result(game)
    }

    fun acceptTrade(game: GameInfo, actorCivilizationId: String, requestId: String): EngineResult {
        val actor = authenticatedCivilization(game, actorCivilizationId)
        TradeCommandExecutor.accept(game, actor, requestId)
        return result(game)
    }

    fun declineTrade(game: GameInfo, actorCivilizationId: String, requestId: String): EngineResult {
        val actor = authenticatedCivilization(game, actorCivilizationId)
        TradeCommandExecutor.decline(game, actor, requestId)
        return result(game)
    }

    fun counterTrade(game: GameInfo, actorCivilizationId: String, requestId: String, trade: ProjectedTrade): EngineResult {
        val actor = authenticatedCivilization(game, actorCivilizationId)
        TradeCommandExecutor.counter(game, actor, requestId, trade)
        return result(game)
    }

    fun declareWar(game: GameInfo, actorCivilizationId: String, otherCivilizationId: String): EngineResult {
        DiplomacyCommandExecutor.declareWar(game, authenticatedCivilization(game, actorCivilizationId), otherCivilizationId)
        return result(game)
    }

    fun denounceCivilization(game: GameInfo, actorCivilizationId: String, otherCivilizationId: String): EngineResult {
        DiplomacyCommandExecutor.denounce(game, authenticatedCivilization(game, actorCivilizationId), otherCivilizationId)
        return result(game)
    }

    fun offerFriendship(game: GameInfo, actorCivilizationId: String, otherCivilizationId: String): EngineResult {
        DiplomacyCommandExecutor.offerFriendship(game, authenticatedCivilization(game, actorCivilizationId), otherCivilizationId)
        return result(game)
    }

    fun makeDiplomaticDemand(game: GameInfo, actorCivilizationId: String, otherCivilizationId: String, demand: DiplomaticDemand): EngineResult {
        DiplomacyCommandExecutor.makeDemand(game, authenticatedCivilization(game, actorCivilizationId), otherCivilizationId, demand)
        return result(game)
    }

    fun respondToDiplomaticPrompt(game: GameInfo, actorCivilizationId: String, promptId: String, accept: Boolean): EngineResult {
        DiplomacyCommandExecutor.respond(game, authenticatedCivilization(game, actorCivilizationId), promptId, accept)
        return result(game)
    }

    fun respondToCityStateProtectionPrompt(game: GameInfo, actorCivilizationId: String, promptId: String, response: CityStateProtectionResponse): EngineResult {
        DiplomacyCommandExecutor.respondToCityStateProtectionPrompt(game, authenticatedCivilization(game, actorCivilizationId), promptId, response)
        return result(game)
    }

    fun giftCityStateGold(game: GameInfo, actorCivilizationId: String, cityStateId: String, amount: Int): EngineResult {
        CityStateCommandExecutor.giftGold(game, authenticatedCivilization(game, actorCivilizationId), cityStateId, amount)
        return result(game)
    }

    fun setCityStateProtection(game: GameInfo, actorCivilizationId: String, cityStateId: String, protect: Boolean): EngineResult {
        CityStateCommandExecutor.setProtection(game, authenticatedCivilization(game, actorCivilizationId), cityStateId, protect)
        return result(game)
    }

    fun demandCityStateTribute(game: GameInfo, actorCivilizationId: String, cityStateId: String, worker: Boolean): EngineResult {
        CityStateCommandExecutor.demandTribute(game, authenticatedCivilization(game, actorCivilizationId), cityStateId, worker)
        return result(game)
    }

    fun giftCityStateImprovement(game: GameInfo, actorCivilizationId: String, cityStateId: String, x: Int, y: Int, improvementName: String): EngineResult {
        CityStateCommandExecutor.giftImprovement(game, authenticatedCivilization(game, actorCivilizationId), cityStateId, x, y, improvementName)
        return result(game)
    }

    fun negotiateCityStatePeace(game: GameInfo, actorCivilizationId: String, cityStateId: String): EngineResult {
        CityStateCommandExecutor.negotiatePeace(game, authenticatedCivilization(game, actorCivilizationId), cityStateId)
        return result(game)
    }

    fun marryCityState(game: GameInfo, actorCivilizationId: String, cityStateId: String): EngineResult {
        CityStateCommandExecutor.marry(game, authenticatedCivilization(game, actorCivilizationId), cityStateId)
        return result(game)
    }

    fun moveSpy(game: GameInfo, actorCivilizationId: String, spyName: String, cityId: String?): EngineResult {
        EspionageCommandExecutor.moveSpy(game, authenticatedCivilization(game, actorCivilizationId), spyName, cityId)
        return result(game)
    }

    fun setSpyCoup(game: GameInfo, actorCivilizationId: String, spyName: String, enabled: Boolean): EngineResult {
        EspionageCommandExecutor.setCoup(game, authenticatedCivilization(game, actorCivilizationId), spyName, enabled)
        return result(game)
    }

    fun resolveEventChoice(game: GameInfo, actorCivilizationId: String, promptId: String, choiceId: String): EngineResult {
        EventChoiceCommandExecutor.resolve(game, authenticatedCivilization(game, actorCivilizationId), promptId, choiceId)
        return result(game)
    }

    private fun authenticatedCivilization(game: GameInfo, civilizationId: String): Civilization =
        game.civilizations.singleOrNull { it.civID == civilizationId && it.playerId == executionContext.actorId }
            ?: error("Authenticated actor is not assigned to this civilization")

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

    /** Pillages the owned unit's canonical current tile. Target selection,
     * loot, healing, movement cost, and destruction are all server-derived. */
    fun pillageTile(
        game: GameInfo,
        actorCivilizationId: String,
        unitId: Int,
    ): EngineResult {
        val actorCivilization = game.civilizations.singleOrNull {
            it.civID == actorCivilizationId && it.playerId == executionContext.actorId
        } ?: error("Authenticated actor is not assigned to this civilization")
        require(game.currentPlayer == actorCivilization.civID) {
            "Authenticated actor cannot pillage outside their turn"
        }
        val unit = actorCivilization.units.getUnitById(unitId)
            ?: error("Unit is not controlled by the authenticated actor")
        require(UnitPillage.pillage(unit)) {
            "Unit cannot pillage its canonical current tile"
        }
        return result(game)
    }

    /** Founds a city at the owned unit's canonical current tile. The worker
     * derives legality, city identity/name, ownership, modifiers, and unit consumption. */
    fun foundCity(
        game: GameInfo,
        actorCivilizationId: String,
        unitId: Int,
    ): EngineResult {
        val actorCivilization = game.civilizations.singleOrNull {
            it.civID == actorCivilizationId && it.playerId == executionContext.actorId
        } ?: error("Authenticated actor is not assigned to this civilization")
        require(game.currentPlayer == actorCivilization.civID) {
            "Authenticated actor cannot found a city outside their turn"
        }
        val unit = actorCivilization.units.getUnitById(unitId)
            ?: error("Unit is not controlled by the authenticated actor")
        val foundingLocation = unit.currentTile.position
        val city = UnitCityFounding.foundCity(unit)
            ?: error("Unit cannot found a city on its canonical current tile")
        check(city.civ == actorCivilization && city.getCenterTile().position == foundingLocation) {
            "Founded city did not match canonical actor and location"
        }
        return result(game)
    }

    /** Paradrops one owned unit while the worker derives capability, range,
     * visibility, terrain, occupancy, movement cost, and attack state. */
    fun paradropUnit(
        game: GameInfo,
        actorCivilizationId: String,
        unitId: Int,
        destinationX: Int,
        destinationY: Int,
    ): EngineResult {
        val actorCivilization = game.civilizations.singleOrNull {
            it.civID == actorCivilizationId && it.playerId == executionContext.actorId
        } ?: error("Authenticated actor is not assigned to this civilization")
        require(game.currentPlayer == actorCivilization.civID) {
            "Authenticated actor cannot paradrop outside their turn"
        }
        val unit = actorCivilization.units.getUnitById(unitId)
            ?: error("Unit is not controlled by the authenticated actor")
        val destination = game.tileMap.getIfTileExistsOrNull(destinationX, destinationY)
            ?: error("Paradrop destination is outside the canonical map")
        require(UnitParadrop.paradrop(unit, destination)) {
            "Unit cannot paradrop to the requested canonical destination"
        }
        return result(game)
    }

    /** Executes a normal unit attack while the worker derives the defender,
     * attack-from tile, movement, setup, combat randomness, and every outcome. */
    fun attackWithUnit(
        game: GameInfo,
        actorCivilizationId: String,
        unitId: Int,
        targetX: Int,
        targetY: Int,
    ): EngineResult {
        val actorCivilization = game.civilizations.singleOrNull {
            it.civID == actorCivilizationId && it.playerId == executionContext.actorId
        } ?: error("Authenticated actor is not assigned to this civilization")
        require(game.currentPlayer == actorCivilization.civID) {
            "Authenticated actor cannot attack outside their turn"
        }
        val unit = actorCivilization.units.getUnitById(unitId)
            ?: error("Unit is not controlled by the authenticated actor")
        val target = game.tileMap.getIfTileExistsOrNull(targetX, targetY)
            ?: error("Attack target is outside the canonical map")
        require(UnitAttackExecutor.attack(unit, target, deferHumanCityDisposition = true) != null) {
            "Unit cannot attack the requested canonical target"
        }
        return result(game)
    }

    /** Bombards one canonical visible enemy from an owned city. Range,
     * line-of-sight, attack availability, defender, RNG, and damage are server-derived. */
    fun bombardWithCity(
        game: GameInfo,
        actorCivilizationId: String,
        cityId: String,
        targetX: Int,
        targetY: Int,
    ): EngineResult {
        val actorCivilization = game.civilizations.singleOrNull {
            it.civID == actorCivilizationId && it.playerId == executionContext.actorId
        } ?: error("Authenticated actor is not assigned to this civilization")
        require(game.currentPlayer == actorCivilization.civID) {
            "Authenticated actor cannot bombard outside their turn"
        }
        val city = actorCivilization.cities.singleOrNull { it.id == cityId }
            ?: error("City is not controlled by the authenticated actor")
        require(city.canBombard()) { "City cannot currently bombard" }
        val target = game.tileMap.getIfTileExistsOrNull(targetX, targetY)
            ?: error("Bombard target is outside the canonical map")
        require(TargetHelper.getBombardableTiles(city).any { it == target }) {
            "City cannot bombard the requested canonical target"
        }
        val defender = Battle.getMapCombatantOfTile(target)
            ?: error("Canonical bombard target has no defender")
        Battle.attack(CityCombatant(city), defender)
        return result(game)
    }

    /** Launches an owned nuclear weapon at one canonical explored coordinate.
     * The shared engine derives range, blast victims, interception, RNG, and all outcomes. */
    fun launchNuclearStrike(
        game: GameInfo,
        actorCivilizationId: String,
        unitId: Int,
        targetX: Int,
        targetY: Int,
    ): EngineResult {
        val actorCivilization = game.civilizations.singleOrNull {
            it.civID == actorCivilizationId && it.playerId == executionContext.actorId
        } ?: error("Authenticated actor is not assigned to this civilization")
        require(game.currentPlayer == actorCivilization.civID) {
            "Authenticated actor cannot launch a nuclear strike outside their turn"
        }
        val unit = actorCivilization.units.getUnitById(unitId)
            ?: error("Unit is not controlled by the authenticated actor")
        val target = game.tileMap.getIfTileExistsOrNull(targetX, targetY)
            ?: error("Nuclear target is outside the canonical map")
        require(NuclearStrikeExecutor.launch(unit, target)) {
            "Unit cannot launch a nuclear strike at the requested canonical target"
        }
        return result(game)
    }

    /** Executes an air sweep while the server derives eligibility, range,
     * interceptor selection, deterministic combat, consumption, and notifications. */
    fun airSweep(
        game: GameInfo,
        actorCivilizationId: String,
        unitId: Int,
        targetX: Int,
        targetY: Int,
    ): EngineResult {
        val actorCivilization = game.civilizations.singleOrNull {
            it.civID == actorCivilizationId && it.playerId == executionContext.actorId
        } ?: error("Authenticated actor is not assigned to this civilization")
        require(game.currentPlayer == actorCivilization.civID) {
            "Authenticated actor cannot air sweep outside their turn"
        }
        val unit = actorCivilization.units.getUnitById(unitId)
            ?: error("Unit is not controlled by the authenticated actor")
        val target = game.tileMap.getIfTileExistsOrNull(targetX, targetY)
            ?: error("Air-sweep target is outside the canonical map")
        require(AirSweepExecutor.sweep(unit, target)) {
            "Unit cannot air sweep the requested canonical target"
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
        saveAsCityDefault: Boolean = false,
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
        if (saveAsCityDefault) {
            val unit = actorCivilization.units.getUnitById(unitId)!!
            val city = unit.currentTile.getCity()
                ?: error("Unit must be in a city to save default promotions")
            require(city.civ == actorCivilization && !city.isPuppet) {
                "Default promotions require an owned non-puppet city"
            }
            city.unitShouldUseSavedPromotion[unit.baseUnit.name] = true
            city.unitToPromotions[unit.baseUnit.name] = unit.promotions.clone()
        }
        actorCivilization.updateStatsForNextTurn()
        return result(game)
    }

    fun setCityUnitPromotionPreference(
        game: GameInfo,
        actorCivilizationId: String,
        cityId: String,
        baseUnitName: String,
        enabled: Boolean,
    ): EngineResult {
        val actorCivilization = game.civilizations.singleOrNull {
            it.civID == actorCivilizationId && it.playerId == executionContext.actorId
        } ?: error("Authenticated actor is not assigned to this civilization")
        require(game.currentPlayer == actorCivilization.civID) {
            "Authenticated actor cannot change city preferences outside their turn"
        }
        require(baseUnitName.isNotBlank() && baseUnitName.length <= 200) {
            "Base unit name is invalid"
        }
        require(game.ruleset.units.containsKey(baseUnitName)) { "Base unit is unavailable" }
        val city = actorCivilization.cities.firstOrNull { it.id == cityId }
            ?: error("City is not controlled by the authenticated actor")
        require(!city.isPuppet) { "Puppet city preferences cannot be changed" }
        require(city.unitToPromotions.containsKey(baseUnitName)) {
            "No canonical default promotions are saved for this unit"
        }
        city.unitShouldUseSavedPromotion[baseUnitName] = enabled
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

    /** Starts, replaces, or cancels the worker order on the owned unit's
     * canonical tile. Build duration and stockpile costs are server-derived. */
    fun setTileImprovementOrder(
        game: GameInfo,
        actorCivilizationId: String,
        unitId: Int,
        improvementName: String?,
        queuedImprovementName: String?,
    ): EngineResult {
        val actorCivilization = game.civilizations.singleOrNull {
            it.civID == actorCivilizationId && it.playerId == executionContext.actorId
        } ?: error("Authenticated actor is not assigned to this civilization")
        require(game.currentPlayer == actorCivilization.civID) {
            "Authenticated actor cannot change improvement orders outside their turn"
        }
        val unit = actorCivilization.units.getUnitById(unitId)
            ?: error("Unit is not controlled by the authenticated actor")
        val tile = unit.currentTile
        require(!tile.isMarkedForCreatesOneImprovement()) {
            "This tile's improvement is controlled by a one-time creation action"
        }

        if (improvementName == null) {
            require(queuedImprovementName == null) { "A cancelled order cannot include a queued improvement" }
            require(tile.improvementInProgress != null) { "Tile has no improvement order to cancel" }
            tile.stopWorkingOnImprovement()
            return result(game)
        }

        require(unit.hasMovement()) { "Unit has no movement available to build" }
        require(!tile.isCityCenter()) { "City-center tiles cannot receive improvement orders" }
        require(improvementName.isNotBlank() && improvementName.length <= 200) {
            "Improvement name is invalid"
        }
        require(queuedImprovementName == null ||
            (queuedImprovementName.isNotBlank() && queuedImprovementName.length <= 200)) {
            "Queued improvement name is invalid"
        }
        val improvement = game.ruleset.tileImprovements[improvementName]
            ?: error("Improvement is unavailable in the pinned ruleset")
        require(improvement.name != Constants.cancelImprovementOrder) {
            "Cancellation must use an empty improvement name"
        }
        val context = GameContext(actorCivilization, unit = unit, tile = tile)
        val isRepair = improvement.name == Constants.repair
        if (isRepair) {
            require(unit.cache.hasUniqueToBuildImprovements && !unit.isEmbarked() && tile.isPillaged()) {
                "Unit cannot repair the canonical tile"
            }
            require(!tile.isEnemyTerritory(actorCivilization)) {
                "Enemy territory cannot be repaired"
            }
            val improvementToRepair = tile.getImprovementToRepair()
                ?: error("Canonical tile has no repairable improvement")
            require(ImprovementFunctions.getImprovementBuildingProblems(improvementToRepair, context)
                .none { it == ImprovementBuildingProblem.OutsideBorders }) {
                "Canonical tile is outside the repair boundary"
            }
        } else {
            require(improvement.turnsToBuild != -1 && unit.canBuildImprovement(improvement)) {
                "Unit cannot build the requested improvement"
            }
            require(tile.improvementFunctions.canBuildImprovement(improvement, context)) {
                "Requested improvement is not legal on the canonical tile"
            }
        }

        val queuedImprovement = queuedImprovementName?.let { name ->
            val queued = game.ruleset.tileImprovements[name]
                ?: error("Queued improvement is unavailable in the pinned ruleset")
            require(queued.name != Constants.cancelImprovementOrder &&
                queued.turnsToBuild != -1 && unit.canBuildImprovement(queued)) {
                "Unit cannot build the queued improvement"
            }
            require(improvement.name.startsWith(Constants.remove)) {
                "A queued follow-up requires a canonical removal improvement first"
            }
            val removedFeature = improvement.name.removePrefix(Constants.remove)
            require(tile.terrainFeatures.contains(removedFeature)) {
                "The first improvement does not remove a canonical terrain feature"
            }
            val tileAfterRemoval = tile.clone(addUnits = false)
            tileAfterRemoval.setTerrainFeatures(tile.terrainFeatures - removedFeature)
            val futureContext = GameContext(actorCivilization, unit = unit, tile = tileAfterRemoval)
            require(tileAfterRemoval.improvementFunctions.canBuildImprovement(queued, futureContext)) {
                "Queued improvement is not legal after the requested removal"
            }
            queued
        }

        if (tile.improvementInProgress != improvement.name) {
            if (isRepair) {
                val originalTurns = tile.getImprovementToRepair()!!.getTurnsToBuild(actorCivilization, unit)
                val repairTurns = improvement.getTurnsToBuild(actorCivilization, unit).coerceAtMost(originalTurns)
                tile.stopWorkingOnImprovement()
                tile.queueImprovement(Constants.repair, repairTurns)
            } else tile.startWorkingOnImprovement(improvement, actorCivilization, unit)
            if (queuedImprovement != null)
                tile.queueImprovement(queuedImprovement, actorCivilization, unit)
        } else require(queuedImprovement == null) {
            "An unchanged active order cannot add a follow-up"
        }
        unit.action = null
        return result(game)
    }

    /** Starts or cancels a canonical multi-turn road order. The client names
     * only a destination; path, road tier, movement, and work are server-owned. */
    fun setRoadConnectionOrder(
        game: GameInfo,
        actorCivilizationId: String,
        unitId: Int,
        destination: HexCoord?,
    ): EngineResult {
        val actorCivilization = game.civilizations.singleOrNull {
            it.civID == actorCivilizationId && it.playerId == executionContext.actorId
        } ?: error("Authenticated actor is not assigned to this civilization")
        require(game.currentPlayer == actorCivilization.civID) {
            "Authenticated actor cannot change road orders outside their turn"
        }
        val unit = actorCivilization.units.getUnitById(unitId)
            ?: error("Unit is not controlled by the authenticated actor")

        if (destination == null) {
            require(unit.isAutomatingRoadConnection()) { "Unit has no canonical road order" }
            actorCivilization.getWorkerAutomation().roadToAutomation.stopAndCleanAutomation(unit)
            return result(game)
        }

        val bestRoad = actorCivilization.tech.getBestRoadAvailable()
        require(bestRoad != com.unciv.logic.map.tile.RoadStatus.None) {
            "Civilization has no available road technology"
        }
        val canBuildRoad = unit.getMatchingUniques(UniqueType.BuildImprovements).any {
            it.params[0] == "Land" || it.params[0] in Constants.all ||
                (it.params[0] == "Road" && bestRoad in setOf(
                    com.unciv.logic.map.tile.RoadStatus.Road,
                    com.unciv.logic.map.tile.RoadStatus.Railroad,
                )) ||
                (it.params[0] == "Railroad" &&
                    bestRoad == com.unciv.logic.map.tile.RoadStatus.Railroad)
        }
        require(canBuildRoad && !unit.isEmbarked()) { "Unit cannot build the available road" }
        require(destination in game.tileMap) { "Road destination is outside the canonical map" }
        val destinationTile = game.tileMap[destination]
        require(destinationTile != unit.currentTile) { "Road destination equals the unit's current tile" }
        require(MapPathing.isValidRoadPathTile(actorCivilization, destinationTile)) {
            "Road destination is not canonically reachable or buildable"
        }
        val path = MapPathing.getRoadPath(actorCivilization, unit.currentTile, destinationTile)
            ?: error("No canonical road path reaches the destination")
        require(path.size > 1 && path.first() == unit.currentTile && path.last() == destinationTile) {
            "Canonical road path is invalid"
        }

        unit.automatedRoadConnectionDestination = destination
        unit.automatedRoadConnectionPath = path.map { it.position }
        unit.action = UnitActionType.ConnectRoad.value
        unit.automated = true
        if (unit.hasMovement())
            com.unciv.logic.automation.unit.UnitAutomation.automateUnitMoves(unit)
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

    /** Sells one canonical, non-free building after deriving every rule and
     * economic effect from the server-owned snapshot. */
    fun sellBuilding(
        game: GameInfo,
        actorCivilizationId: String,
        cityId: String,
        buildingName: String,
    ): EngineResult {
        val city = requireOwnedCurrentTurnCity(game, actorCivilizationId, cityId)
        require(buildingName.isNotBlank() && buildingName.length <= 128) {
            "Building name is invalid"
        }
        val building = city.getRuleset().buildings[buildingName]
            ?: error("Building does not exist in the canonical ruleset")
        require(building.isSellable()) { "Building cannot be sold" }
        require(city.cityConstructions.isBuilt(buildingName)) { "Building is not built in this city" }
        require(!city.civ.civConstructions.hasFreeBuilding(city, building)) {
            "Free buildings cannot be sold"
        }
        require(!city.isPuppet) { "Buildings in puppeted cities cannot be sold" }
        require(!city.hasSoldBuildingThisTurn || game.gameParameters.godMode) {
            "This city has already sold a building this turn"
        }
        val previousGold = city.civ.gold
        val canonicalRefund = city.getGoldForSellingBuilding(buildingName)
        city.sellBuilding(building)
        check(!city.cityConstructions.isBuilt(buildingName)) { "Sold building remains in the city" }
        check(city.civ.gold == previousGold + canonicalRefund) { "Canonical building refund was not applied" }
        check(city.hasSoldBuildingThisTurn) { "Building sale limit was not recorded" }
        return result(game)
    }

    fun setCityGovernance(
        game: GameInfo,
        actorCivilizationId: String,
        cityId: String,
        action: CityGovernanceAction,
    ): EngineResult {
        val city = requireOwnedCurrentTurnCity(game, actorCivilizationId, cityId)
        CityGovernanceExecutor.execute(city, action)
        return result(game)
    }

    fun resolveCityDisposition(
        game: GameInfo,
        actorCivilizationId: String,
        cityId: String,
        action: CityDispositionAction,
    ): EngineResult {
        val actor = game.civilizations.singleOrNull {
            it.civID == actorCivilizationId && it.playerId == executionContext.actorId
        } ?: error("Authenticated actor is not assigned to this civilization")
        require(game.currentPlayer == actor.civID) {
            "Authenticated actor cannot resolve city conquest outside their turn"
        }
        CityDispositionExecutor.execute(game, actor, cityId, action)
        return result(game)
    }

    fun castDiplomaticVote(
        game: GameInfo,
        actorCivilizationId: String,
        candidateCivilizationId: String?,
    ): EngineResult {
        val actor = game.civilizations.singleOrNull {
            it.civID == actorCivilizationId && it.playerId == executionContext.actorId
        } ?: error("Authenticated actor is not assigned to this civilization")
        require(game.currentPlayer == actor.civID) {
            "Authenticated actor cannot vote outside their turn"
        }
        require(actor.mayVoteForDiplomaticVictory()) {
            "Civilization does not have a pending diplomatic vote"
        }
        if (candidateCivilizationId != null) {
            require(candidateCivilizationId in PlayerProjectionBuilder.diplomaticVoteCandidates(actor)) {
                "Diplomatic vote candidate is unavailable in the canonical state"
            }
        }
        actor.diplomaticVoteForCiv(candidateCivilizationId)
        return result(game)
    }

    fun chooseGreatPerson(
        game: GameInfo,
        actorCivilizationId: String,
        unitName: String,
    ): EngineResult {
        val actor = game.civilizations.singleOrNull {
            it.civID == actorCivilizationId && it.playerId == executionContext.actorId
        } ?: error("Authenticated actor is not assigned to this civilization")
        require(game.currentPlayer == actor.civID) {
            "Authenticated actor cannot choose a great person outside their turn"
        }
        GreatPersonChoiceExecutor.execute(actor, unitName)
        return result(game)
    }

    fun useReligiousUnit(
        game: GameInfo,
        actorCivilizationId: String,
        unitId: Int,
        action: ReligiousUnitAction,
    ): EngineResult {
        val actor = game.civilizations.singleOrNull {
            it.civID == actorCivilizationId && it.playerId == executionContext.actorId
        } ?: error("Authenticated actor is not assigned to this civilization")
        require(game.currentPlayer == actor.civID) {
            "Authenticated actor cannot use a religious unit outside their turn"
        }
        val unit = actor.units.getUnitById(unitId)
            ?: error("Unit is not controlled by the authenticated actor")
        ReligiousUnitActionExecutor.execute(unit, action)
        return result(game)
    }

    fun useGreatPersonUnit(
        game: GameInfo,
        actorCivilizationId: String,
        unitId: Int,
        action: GreatPersonUnitAction,
    ): EngineResult {
        val actor = authenticatedCivilization(game, actorCivilizationId)
        require(game.currentPlayer == actor.civID) {
            "Authenticated actor cannot use a great person outside their turn"
        }
        val unit = actor.units.getUnitById(unitId)
            ?: error("Unit is not controlled by the authenticated actor")
        GreatPersonUnitActionExecutor.execute(unit, action)
        return result(game)
    }

    fun giftUnit(game: GameInfo, actorCivilizationId: String, unitId: Int): EngineResult {
        UnitGiftCommandExecutor.gift(game, authenticatedCivilization(game, actorCivilizationId), unitId)
        return result(game)
    }

    fun transformUnit(
        game: GameInfo,
        actorCivilizationId: String,
        unitId: Int,
        actionId: String,
    ): EngineResult {
        val actor = authenticatedCivilization(game, actorCivilizationId)
        require(game.currentPlayer == actor.civID) {
            "Authenticated actor cannot transform a unit outside their turn"
        }
        val unit = actor.units.getUnitById(unitId)
            ?: error("Unit is not controlled by the authenticated actor")
        UnitTransformCommandExecutor.execute(unit, actionId)
        return result(game)
    }

    fun triggerUnitUnique(
        game: GameInfo,
        actorCivilizationId: String,
        unitId: Int,
        actionId: String,
    ): EngineResult {
        val actor = authenticatedCivilization(game, actorCivilizationId)
        require(game.currentPlayer == actor.civID) {
            "Authenticated actor cannot trigger a unit unique outside their turn"
        }
        val unit = actor.units.getUnitById(unitId)
            ?: error("Unit is not controlled by the authenticated actor")
        UnitTriggerCommandExecutor.execute(unit, actionId)
        return result(game)
    }

    fun chooseReligiousBeliefs(
        game: GameInfo,
        actorCivilizationId: String,
        beliefNames: List<String>,
        religionIconName: String?,
        religionDisplayName: String?,
    ): EngineResult {
        val actor = game.civilizations.singleOrNull {
            it.civID == actorCivilizationId && it.playerId == executionContext.actorId
        } ?: error("Authenticated actor is not assigned to this civilization")
        require(game.currentPlayer == actor.civID) {
            "Authenticated actor cannot choose religious beliefs outside their turn"
        }
        ReligionChoiceExecutor.execute(
            actor, beliefNames, religionIconName, religionDisplayName,
        )
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
