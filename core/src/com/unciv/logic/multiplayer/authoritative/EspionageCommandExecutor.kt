package com.unciv.logic.multiplayer.authoritative

import com.unciv.logic.GameInfo
import com.unciv.logic.civilization.Civilization
import com.unciv.models.Spy
import com.unciv.models.SpyAction

/** Player espionage intents; all progress and random outcomes remain in server-owned turn execution. */
object EspionageCommandExecutor {
    fun spies(actor: Civilization): List<ProjectedSpy> = actor.espionageManager.spyList.map { spy ->
        val currentCity = spy.getCityOrNull()
        ProjectedSpy(
            name = spy.name,
            rank = spy.rank,
            cityId = currentCity?.id,
            civilizationId = currentCity?.civ?.civID,
            action = ProjectedSpyAction.valueOf(spy.action.name),
            turnsRemaining = spy.turnsRemainingForAction,
            availableCityIds = if (spy.isAlive()) {
                actor.gameInfo.getCities().asSequence()
                    .filter { it != currentCity && actor.hasExplored(it.getCenterTile()) }
                    .filter { actor.espionageManager.getSpyAssignedToCity(it) == null }
                    .map { it.id }
                    .sorted()
                    .toList()
            } else emptyList(),
            canMoveToHideout = currentCity != null && spy.isAlive(),
            canStageCoup = spy.canDoCoup(),
            canCancelCoup = spy.action == SpyAction.Coup,
        )
    }.sortedBy { it.name }

    fun moveSpy(game: GameInfo, actor: Civilization, spyName: String, cityId: String?) {
        requireCurrentActor(game, actor)
        val spy = spy(actor, spyName)
        val projected = spies(actor).single { it.name == spyName }
        if (cityId == null) {
            require(projected.canMoveToHideout) { "Spy cannot move to the hideout in canonical state" }
            spy.moveTo(null)
        } else {
            require(cityId in projected.availableCityIds) { "Spy destination is not legal in canonical state" }
            spy.moveTo(game.getCities().single { it.id == cityId })
        }
    }

    fun setCoup(game: GameInfo, actor: Civilization, spyName: String, enabled: Boolean) {
        requireCurrentActor(game, actor)
        val spy = spy(actor, spyName)
        val projected = spies(actor).single { it.name == spyName }
        if (enabled) {
            require(projected.canStageCoup) { "Coup is not legal in canonical state" }
            spy.setAction(SpyAction.Coup, 1)
        } else {
            require(projected.canCancelCoup) { "Spy has no canonical coup to cancel" }
            spy.setAction(SpyAction.CounterIntelligence, 10)
        }
    }

    private fun spy(actor: Civilization, name: String): Spy {
        require(name.isNotBlank() && name.length <= 128) { "Invalid spy name" }
        return actor.espionageManager.spyList.singleOrNull { it.name == name }
            ?: error("Unknown owned spy")
    }

    private fun requireCurrentActor(game: GameInfo, actor: Civilization) =
        require(game.currentPlayer == actor.civID) { "Authenticated actor cannot manage spies outside their turn" }
}
