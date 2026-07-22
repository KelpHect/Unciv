package com.unciv.logic.multiplayer.authoritative

import com.unciv.Constants
import com.unciv.logic.GameInfo
import com.unciv.logic.civilization.Civilization
import com.unciv.logic.civilization.PopupAlert
import com.unciv.logic.civilization.AlertType
import com.unciv.logic.civilization.diplomacy.DiplomacyFlags
import com.unciv.logic.trade.TradeLogic
import com.unciv.logic.trade.TradeOffer
import com.unciv.logic.trade.TradeOfferType
import com.unciv.models.ruleset.tile.ResourceType
import com.unciv.models.ruleset.unique.UniqueType

/** City-state intents executed against canonical state by the private worker. */
object CityStateCommandExecutor {
    private val goldGiftAmounts = listOf(250, 500, 1000)
    private const val improvementGiftCost = 200

    fun partners(actor: Civilization): List<ProjectedCityStatePartner> = actor.getKnownCivs()
        .asSequence()
        .filter { it.isCityState && !it.isDefeated() }
        .map { cityState ->
            val functions = cityState.cityStateFunctions
            val peaceful = !actor.isAtWarWith(cityState)
            ProjectedCityStatePartner(
                civilizationId = cityState.civID,
                availableGoldGifts = if (peaceful) goldGiftAmounts.filter { actor.gold >= it } else emptyList(),
                canPledgeProtection = functions.otherCivCanPledgeProtection(actor),
                canRevokeProtection = functions.otherCivCanWithdrawProtection(actor),
                tributeGoldAmount = functions.goldGainedByTribute().takeIf {
                    peaceful && functions.getTributeWillingness(actor, demandingWorker = false) >= 0
                },
                canDemandWorker = peaceful && functions.getTributeWillingness(actor, demandingWorker = true) >= 0 &&
                    hasBuildableWorker(cityState),
                improvementGifts = improvementGifts(actor, cityState),
                canNegotiatePeace = canNegotiatePeace(actor, cityState),
                canDeclareWar = !actor.gameInfo.ruleset.modOptions.hasUnique(UniqueType.DiplomaticRelationshipsCannotChange) &&
                    actor.getDiplomacyManager(cityState)!!.canDeclareWar(),
                diplomaticMarriageCost = functions.getDiplomaticMarriageCost().takeIf { functions.canBeMarriedBy(actor) },
            )
        }
        .sortedBy { it.civilizationId }
        .toList()

    fun giftGold(game: GameInfo, actor: Civilization, cityStateId: String, amount: Int) {
        requireCurrentActor(game, actor)
        val cityState = cityState(game, actor, cityStateId)
        require(amount in goldGiftAmounts && !actor.isAtWarWith(cityState) && actor.gold >= amount) {
            "Gold gift is not legal in canonical state"
        }
        cityState.cityStateFunctions.receiveGoldGift(actor, amount)
    }

    fun setProtection(game: GameInfo, actor: Civilization, cityStateId: String, protect: Boolean) {
        requireCurrentActor(game, actor)
        val functions = cityState(game, actor, cityStateId).cityStateFunctions
        if (protect) {
            require(functions.otherCivCanPledgeProtection(actor)) { "Protection pledge is not legal in canonical state" }
            functions.addProtectorCiv(actor)
        } else {
            require(functions.otherCivCanWithdrawProtection(actor)) { "Protection withdrawal is not legal in canonical state" }
            functions.removeProtectorCiv(actor)
        }
    }

    fun demandTribute(game: GameInfo, actor: Civilization, cityStateId: String, worker: Boolean) {
        requireCurrentActor(game, actor)
        val cityState = cityState(game, actor, cityStateId)
        require(!actor.isAtWarWith(cityState)) { "Tribute cannot be demanded during war" }
        val functions = cityState.cityStateFunctions
        require(functions.getTributeWillingness(actor, demandingWorker = worker) >= 0) {
            "Tribute demand is not legal in canonical state"
        }
        require(!worker || hasBuildableWorker(cityState)) { "City-state has no canonical worker tribute available" }
        if (worker) functions.tributeWorker(actor) else functions.tributeGold(actor)
    }

    fun giftImprovement(game: GameInfo, actor: Civilization, cityStateId: String, x: Int, y: Int, improvementName: String) {
        requireCurrentActor(game, actor)
        val cityState = cityState(game, actor, cityStateId)
        val gift = improvementGifts(actor, cityState).singleOrNull {
            it.x == x && it.y == y && it.improvementName == improvementName
        } ?: error("Improvement gift is not legal in canonical state")
        require(actor.gold >= gift.goldCost) { "Insufficient canonical gold for improvement gift" }
        val tile = game.tileMap[x, y]
        val improvement = game.ruleset.tileImprovements[improvementName]
            ?: error("Unknown canonical improvement")
        actor.addGold(-gift.goldCost)
        tile.stopWorkingOnImprovement()
        tile.setImprovement(improvement)
        cityState.cache.updateCivResources()
    }

    fun negotiatePeace(game: GameInfo, actor: Civilization, cityStateId: String) {
        requireCurrentActor(game, actor)
        val cityState = cityState(game, actor, cityStateId)
        require(canNegotiatePeace(actor, cityState)) { "City-state peace is not legal in canonical state" }
        val trade = TradeLogic(actor, cityState)
        trade.currentTrade.ourOffers.add(TradeOffer(Constants.peaceTreaty, TradeOfferType.Treaty, speed = game.speed))
        trade.currentTrade.theirOffers.add(TradeOffer(Constants.peaceTreaty, TradeOfferType.Treaty, speed = game.speed))
        trade.acceptTrade()
    }

    fun marry(game: GameInfo, actor: Civilization, cityStateId: String) {
        requireCurrentActor(game, actor)
        val cityState = cityState(game, actor, cityStateId)
        require(cityState.cityStateFunctions.canBeMarriedBy(actor)) { "Diplomatic marriage is not legal in canonical state" }
        val cityIds = cityState.cities.map { it.id }
        cityState.cityStateFunctions.diplomaticMarriage(actor)
        cityIds.forEach { actor.popupAlerts.add(PopupAlert(AlertType.DiplomaticMarriage, it)) }
    }

    private fun cityState(game: GameInfo, actor: Civilization, id: String): Civilization {
        val cityState = game.civilizations.singleOrNull { it.civID == id }
            ?: error("Unknown city-state")
        require(cityState.isCityState && !cityState.isDefeated() && actor.knows(cityState)) {
            "Civilization is not an available city-state counterpart"
        }
        return cityState
    }

    private fun requireCurrentActor(game: GameInfo, actor: Civilization) =
        require(game.currentPlayer == actor.civID) { "Authenticated actor cannot act outside their turn" }

    private fun hasBuildableWorker(cityState: Civilization): Boolean = cityState.gameInfo.ruleset.units.values.any {
        it.hasUnique(UniqueType.BuildImprovements) && it.isCivilian() && it.isBuildable(cityState)
    }

    private fun improvementGifts(actor: Civilization, cityState: Civilization): List<ProjectedCityStateImprovementGift> {
        if (actor.gold < improvementGiftCost || actor.isAtWarWith(cityState) ||
            cityState.getDiplomacyManager(actor)!!.getInfluence() < 60) return emptyList()
        return cityState.cities.asSequence().flatMap { it.getTiles().asSequence() }
            .filter { tile ->
                val resource = tile.tileResource
                resource != null && cityState.canSeeResource(resource) && resource.resourceType != ResourceType.Bonus &&
                    (tile.improvement == null || !resource.isImprovedBy(tile.improvement!!))
            }
            .flatMap { tile -> cityState.gameInfo.ruleset.tileImprovements.values.asSequence()
                .filter { improvement -> improvement.turnsToBuild != -1 && tile.tileResource!!.isImprovedBy(improvement.name) &&
                    tile.improvementFunctions.canBuildImprovement(improvement, cityState.state) }
                .map { ProjectedCityStateImprovementGift(tile.position.x, tile.position.y, it.name, improvementGiftCost) }
            }
            .sortedWith(compareBy<ProjectedCityStateImprovementGift> { it.x }.thenBy { it.y }.thenBy { it.improvementName })
            .toList()
    }

    private fun canNegotiatePeace(actor: Civilization, cityState: Civilization): Boolean {
        if (!actor.isAtWarWith(cityState)) return false
        if (cityState.allyCiv?.let { actor.isAtWarWith(it) } == true) return false
        val diplomacy = actor.getDiplomacyManager(cityState) ?: return false
        if (diplomacy.hasFlag(DiplomacyFlags.DeclaredWar)) return false
        return !actor.gameInfo.ruleset.modOptions.hasUnique(UniqueType.DiplomaticRelationshipsCannotChange)
    }
}
