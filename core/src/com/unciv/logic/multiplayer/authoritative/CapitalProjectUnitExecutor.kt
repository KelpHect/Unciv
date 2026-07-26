package com.unciv.logic.multiplayer.authoritative

import com.unciv.logic.GameInfo
import com.unciv.logic.civilization.Civilization
import com.unciv.logic.map.mapunit.MapUnit
import com.unciv.models.ruleset.unique.UniqueType

/** Derives and executes the unit action that adds a part to its capital project. */
object CapitalProjectUnitExecutor {
    @Suppress("DEPRECATION")
    fun projectName(unit: MapUnit): String? {
        val unique = unit.getMatchingUniques(UniqueType.AddInCapital).firstOrNull() ?: return null
        val city = unit.currentTile.getCity() ?: return null
        if (!unit.currentTile.isCityCenter() || city.civ != unit.civ || !city.isCapital()) return null
        return unique.params.firstOrNull()?.takeIf { it.isNotBlank() }
    }

    fun execute(game: GameInfo, actor: Civilization, unitId: Int) {
        require(game.currentPlayer == actor.civID) {
            "Authenticated actor cannot add capital-project units outside their turn"
        }
        val unit = actor.units.getUnitById(unitId)
            ?: error("Unit is not controlled by the authenticated actor")
        execute(unit)
    }

    fun execute(unit: MapUnit) {
        require(projectName(unit) != null) {
            "Capital-project unit action is unavailable in canonical state"
        }
        val actor = unit.civ
        val stableUnitId = unit.id
        val unitName = unit.name
        val previousCount = actor.victoryManager.currentsSpaceshipParts[unitName]
        actor.victoryManager.currentsSpaceshipParts.add(unitName, 1)
        unit.destroy()
        check(actor.units.getUnitById(stableUnitId) == null) {
            "Capital-project unit was not consumed"
        }
        check(actor.victoryManager.currentsSpaceshipParts[unitName] == previousCount + 1) {
            "Capital-project contribution was not committed"
        }
    }
}
