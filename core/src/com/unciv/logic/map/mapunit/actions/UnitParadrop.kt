package com.unciv.logic.map.mapunit.actions

import com.unciv.logic.map.mapunit.MapUnit
import com.unciv.logic.map.tile.Tile
import com.unciv.models.UnitActionType
import com.unciv.models.ruleset.unique.UniqueType

/** Shared validation and execution for human, AI, and authoritative paradrops. */
object UnitParadrop {
    /** Rebuilds the derived destination filters from the unit's canonical uniques. */
    fun rebuildDestinationFilters(unit: MapUnit): Boolean {
        unit.cache.paradropDestinationTileFilters.clear()
        for (unique in unit.getMatchingUniques(UniqueType.MayParadrop, unit.cache.state)) {
            val tileFilter = unique.params[0]
            val distance = unique.params[1].toInt()
            val previousDistance = unit.cache.paradropDestinationTileFilters[tileFilter]
            if (previousDistance == null || distance > previousDistance)
                unit.cache.paradropDestinationTileFilters[tileFilter] = distance
        }
        return unit.cache.paradropDestinationTileFilters.isNotEmpty()
    }

    fun canParadrop(unit: MapUnit, destination: Tile): Boolean {
        if (unit.hasUnitMovedThisTurn() || unit.cache.cannotMove) return false
        if (!rebuildDestinationFilters(unit)) return false

        val previousAction = unit.action
        unit.action = UnitActionType.Paradrop.value
        val canReach = try {
            unit.movement.canReach(destination) && unit.movement.canMoveTo(destination)
        } finally {
            unit.action = previousAction
        }
        return canReach
    }

    /** Executes a paradrop while deriving all legality and costs from canonical state. */
    fun paradrop(unit: MapUnit, destination: Tile): Boolean {
        if (!canParadrop(unit, destination)) return false
        val origin = unit.currentTile
        unit.action = UnitActionType.Paradrop.value
        unit.movement.moveToTile(destination)
        return unit.currentTile == destination && unit.currentTile != origin
    }
}
