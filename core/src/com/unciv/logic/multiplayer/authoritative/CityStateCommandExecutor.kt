package com.unciv.logic.multiplayer.authoritative

import com.unciv.logic.GameInfo
import com.unciv.logic.civilization.Civilization
import com.unciv.models.ruleset.unique.UniqueType

/** City-state intents executed against canonical state by the private worker. */
object CityStateCommandExecutor {
    private val goldGiftAmounts = listOf(250, 500, 1000)

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
}
