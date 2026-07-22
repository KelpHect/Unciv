package com.unciv.logic.multiplayer.authoritative

import com.unciv.logic.city.City
import com.unciv.models.ruleset.unique.UniqueType

/** Executes ordinary owner governance after authenticated ownership and the
 * current turn have been resolved from canonical state. */
internal object CityGovernanceExecutor {
    fun execute(city: City, action: CityGovernanceAction) {
        val mayAnnex = !city.civ.hasUnique(UniqueType.MayNotAnnexCities)
        when (action) {
            CityGovernanceAction.Annex -> {
                require(city.isPuppet) { "Only a puppeted city can be annexed" }
                require(mayAnnex) { "This civilization cannot annex cities" }
                city.annexCity()
                check(!city.isPuppet) { "City was not annexed" }
            }
            CityGovernanceAction.StartRazing -> {
                require(!city.isPuppet) { "A puppeted city cannot start razing" }
                require(!city.isBeingRazed) { "City is already being razed" }
                require(mayAnnex) { "This civilization cannot raze cities" }
                require(city.canBeDestroyed()) { "City cannot be razed" }
                city.isBeingRazed = true
                check(city.isBeingRazed) { "City did not start razing" }
            }
            CityGovernanceAction.StopRazing -> {
                require(city.isBeingRazed) { "City is not being razed" }
                city.isBeingRazed = false
                check(!city.isBeingRazed) { "City did not stop razing" }
            }
        }
    }

    fun availableActions(city: City): List<CityGovernanceAction> {
        val mayAnnex = !city.civ.hasUnique(UniqueType.MayNotAnnexCities)
        return buildList {
            if (city.isPuppet && mayAnnex) add(CityGovernanceAction.Annex)
            if (!city.isPuppet && !city.isBeingRazed && mayAnnex && city.canBeDestroyed())
                add(CityGovernanceAction.StartRazing)
            if (city.isBeingRazed) add(CityGovernanceAction.StopRazing)
        }
    }
}
