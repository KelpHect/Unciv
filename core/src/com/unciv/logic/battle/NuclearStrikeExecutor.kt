package com.unciv.logic.battle

import com.unciv.logic.map.mapunit.MapUnit
import com.unciv.logic.map.tile.Tile

/** Headless-safe authorization boundary for launching the shared nuclear rules engine. */
object NuclearStrikeExecutor {
    fun launch(unit: MapUnit, target: Tile): Boolean {
        if (!unit.isNuclearWeapon() || !unit.canAttack()) return false
        val attacker = MapUnitCombatant(unit)
        if (!Nuke.mayUseNuke(attacker, target)) return false
        Nuke.NUKE(attacker, target)
        return true
    }
}
