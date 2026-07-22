package com.unciv.logic.multiplayer.authoritative

import com.unciv.logic.GameInfo
import com.unciv.logic.city.City
import com.unciv.logic.civilization.AlertType
import com.unciv.logic.civilization.Civilization
import com.unciv.models.ruleset.unique.UniqueType

/** Resolves an exact canonical post-capture decision and consumes it only
 * after the shared Kotlin engine completes the selected action. */
internal object CityDispositionExecutor {
    fun pendingCity(game: GameInfo, actor: Civilization, cityId: String): City {
        require(actor.popupAlerts.any { it.type == AlertType.CityConquered && it.value == cityId }) {
            "City does not have a pending conquest disposition"
        }
        return game.getCities().singleOrNull { it.id == cityId }
            ?: error("Pending conquest city does not exist")
    }

    fun availableActions(city: City, actor: Civilization): List<CityDispositionAction> {
        val canLiberate = city.foundingCivObject != null &&
            city.civ != city.foundingCivObject && actor != city.foundingCivObject
        if (actor.isOneCityChallenger()) return listOf(CityDispositionAction.Destroy)
        val mayAnnex = !actor.hasUnique(UniqueType.MayNotAnnexCities)
        return buildList {
            if (canLiberate) add(CityDispositionAction.Liberate)
            if (mayAnnex) add(CityDispositionAction.Annex)
            add(CityDispositionAction.Puppet)
            if (city.canBeDestroyed(justCaptured = true)) add(CityDispositionAction.Raze)
        }
    }

    fun execute(game: GameInfo, actor: Civilization, cityId: String, action: CityDispositionAction) {
        val alert = actor.popupAlerts.singleOrNull {
            it.type == AlertType.CityConquered && it.value == cityId
        } ?: error("City conquest disposition is absent or ambiguous")
        val city = pendingCity(game, actor, cityId)
        require(action in availableActions(city, actor)) {
            "City disposition is not available in the canonical state"
        }
        when (action) {
            CityDispositionAction.Liberate -> city.liberateCity(actor)
            CityDispositionAction.Annex -> {
                city.puppetCity(actor)
                city.annexCity()
            }
            CityDispositionAction.Puppet -> city.puppetCity(actor)
            CityDispositionAction.Raze -> {
                city.puppetCity(actor)
                if (!actor.hasUnique(UniqueType.MayNotAnnexCities)) city.annexCity()
                city.isBeingRazed = true
            }
            CityDispositionAction.Destroy -> {
                city.puppetCity(actor)
                city.destroyCity()
            }
        }
        actor.popupAlerts.remove(alert)
        check(actor.popupAlerts.none { it.type == AlertType.CityConquered && it.value == cityId }) {
            "Resolved city disposition remains pending"
        }
    }
}
