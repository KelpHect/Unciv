package com.unciv.logic.battle

import com.unciv.logic.map.mapunit.MapUnit
import com.unciv.logic.map.tile.Tile

/** Deterministic target selection for authoritative calls into the shared [Battle] engine. */
object UnitAttackExecutor {
    fun findCanonicalAttack(unit: MapUnit, target: Tile): AttackableTile? =
        TargetHelper.getAttackableEnemies(unit, unit.movement.getDistanceToTiles())
            .asSequence()
            .filter { it.tileToAttack == target }
            .sortedWith(
                compareByDescending<AttackableTile> { it.movementLeftAfterMovingToAttackTile }
                    .thenBy { it.tileToAttackFrom.position.x }
                    .thenBy { it.tileToAttackFrom.position.y },
            )
            .firstOrNull()

    fun attack(
        unit: MapUnit,
        target: Tile,
        deferHumanCityDisposition: Boolean = false,
    ): Battle.DamageDealt? {
        if (!unit.canAttack()) return null
        val attack = findCanonicalAttack(unit, target) ?: return null
        val attacker = MapUnitCombatant(unit)
        if (!Battle.movePreparingAttack(attacker, attack)) return null
        return Battle.attackOrNuke(attacker, attack, deferHumanCityDisposition)
    }
}
