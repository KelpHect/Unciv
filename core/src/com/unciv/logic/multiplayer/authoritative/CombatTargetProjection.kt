package com.unciv.logic.multiplayer.authoritative

import com.unciv.logic.battle.TargetHelper
import com.unciv.logic.battle.Battle
import com.unciv.logic.battle.CityCombatant
import com.unciv.logic.battle.MapUnitCombatant
import com.unciv.logic.city.City
import com.unciv.logic.map.mapunit.MapUnit
import com.unciv.models.ruleset.unique.UniqueType

/** Derives bounded combat input choices from the canonical worker state. */
internal object CombatTargetProjection {
    private const val MAX_TARGETS_PER_ENTITY = 10_000

    fun attackTargets(unit: MapUnit, enabled: Boolean): List<ProjectedAttackTarget> {
        if (!enabled || !unit.canAttack()) return emptyList()
        return TargetHelper.getAttackableEnemies(unit, unit.movement.getDistanceToTiles())
            .asSequence()
            .filter {
                it.tileToAttack.isVisible(unit.civ) && it.tileToAttackFrom.isVisible(unit.civ)
            }
            .distinctBy { it.tileToAttack.position }
            .map {
                ProjectedAttackTarget(
                    it.tileToAttack.position.x,
                    it.tileToAttack.position.y,
                    it.tileToAttackFrom.position.x,
                    it.tileToAttackFrom.position.y,
                    CombatPreviewProjection.build(
                        MapUnitCombatant(unit),
                        Battle.getMapCombatantOfTile(it.tileToAttack)
                            ?: error("Canonical attack target has no defender"),
                        it.tileToAttackFrom,
                    ),
                )
            }
            .sortedWith(compareBy<ProjectedAttackTarget> { it.x }.thenBy { it.y })
            .take(MAX_TARGETS_PER_ENTITY)
            .toList()
    }

    fun bombardTargets(city: City, enabled: Boolean): List<ProjectedBombardTarget> {
        if (!enabled || !city.canBombard()) return emptyList()
        return TargetHelper.getBombardableTiles(city)
            .map {
                ProjectedBombardTarget(
                    it.position.x,
                    it.position.y,
                    CombatPreviewProjection.build(
                        CityCombatant(city),
                        Battle.getMapCombatantOfTile(it)
                            ?: error("Canonical bombard target has no defender"),
                        city.getCenterTile(),
                    ),
                )
            }
            .sortedWith(compareBy<ProjectedBombardTarget> { it.x }.thenBy { it.y })
            .take(MAX_TARGETS_PER_ENTITY)
            .toList()
    }

    /** These are candidates, not a hidden-state-derived legality oracle. The
     * worker rechecks every blast victim and diplomacy constraint on submit. */
    fun nuclearTargetCandidates(unit: MapUnit, enabled: Boolean): List<ProjectedTargetCoordinate> {
        if (!enabled || !unit.isNuclearWeapon() || !unit.canAttack()) return emptyList()
        val targets = ArrayList<ProjectedTargetCoordinate>()
        unit.getTile().forEachTileInDistance(unit.getRange()) {
            if (it != unit.getTile() && it.isExplored(unit.civ))
                targets += ProjectedTargetCoordinate(it.position.x, it.position.y)
        }
        return targets.sortedWith(compareBy<ProjectedTargetCoordinate> { it.x }.thenBy { it.y })
            .take(MAX_TARGETS_PER_ENTITY)
    }

    fun airSweepTargets(unit: MapUnit, enabled: Boolean): List<ProjectedTargetCoordinate> {
        if (!enabled || !unit.hasUnique(UniqueType.CanAirsweep) || !unit.canAttack()) return emptyList()
        val targets = ArrayList<ProjectedTargetCoordinate>()
        unit.getTile().forEachTileInDistance(unit.getRange()) {
            if (it != unit.getTile())
                targets += ProjectedTargetCoordinate(it.position.x, it.position.y)
        }
        return targets.sortedWith(compareBy<ProjectedTargetCoordinate> { it.x }.thenBy { it.y })
            .take(MAX_TARGETS_PER_ENTITY)
    }
}
