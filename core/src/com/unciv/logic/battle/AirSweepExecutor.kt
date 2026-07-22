package com.unciv.logic.battle

import com.unciv.logic.map.mapunit.MapUnit
import com.unciv.logic.map.tile.Tile
import com.unciv.models.UnitActionType
import com.unciv.models.ruleset.unique.UniqueType

/** Headless-safe entry point for one canonical air-sweep intent. */
object AirSweepExecutor {
    fun sweep(unit: MapUnit, target: Tile): Boolean {
        if (!unit.hasUnique(UniqueType.CanAirsweep) || !unit.canAttack()) return false
        if (target == unit.currentTile || unit.currentTile.aerialDistanceTo(target) > unit.getRange()) {
            return false
        }
        unit.action = UnitActionType.AirSweep.value
        AirInterception.airSweep(MapUnitCombatant(unit), target)
        return true
    }
}
