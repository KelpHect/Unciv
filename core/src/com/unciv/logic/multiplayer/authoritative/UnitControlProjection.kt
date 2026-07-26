package com.unciv.logic.multiplayer.authoritative

import com.unciv.logic.map.mapunit.MapUnit
import com.unciv.logic.map.mapunit.actions.UnitCityFounding
import com.unciv.logic.map.mapunit.actions.UnitParadrop
import com.unciv.logic.map.mapunit.actions.UnitPillage
import com.unciv.models.ruleset.unique.UniqueType

/** Exact worker-derived direct controls for one owned current-turn unit. */
object UnitControlProjection {
    fun availablePostures(unit: MapUnit): List<UnitPosture> {
        if (!unit.hasMovement()) return emptyList()
        val tile = unit.currentTile
        val canSleep = !unit.isFortified() && !unit.canFortify() && !unit.isGuarding() &&
            !(tile.hasImprovementInProgress() &&
                unit.canBuildImprovement(tile.getTileImprovementInProgress()!!))
        val canHeal = unit.health < 100 && unit.canHealInCurrentTile()
        return buildList {
            if (canSleep && !unit.isSleeping()) add(UnitPosture.Sleep)
            if (canSleep && canHeal && !unit.isSleepingUntilHealed())
                add(UnitPosture.SleepUntilHealed)
            if (unit.canFortify() && !unit.isFortified()) add(UnitPosture.Fortify)
            if (unit.canFortify() && canHeal && !unit.isFortifyingUntilHealed())
                add(UnitPosture.FortifyUntilHealed)
            if (unit.hasUnique(UniqueType.WithdrawsBeforeMeleeCombat) &&
                !unit.isGuarding()
            ) add(UnitPosture.Guard)
            if (unit.hasUnique(UniqueType.MustSetUp) && !unit.isEmbarked() &&
                !unit.isSetUpForSiege()
            ) add(UnitPosture.Setup)
        }
    }

    fun canDisband(unit: MapUnit): Boolean = unit.hasMovement()

    fun canPillage(unit: MapUnit): Boolean = UnitPillage.canPillage(unit, unit.currentTile)

    fun canFoundCity(unit: MapUnit): Boolean = UnitCityFounding.canFoundCity(unit)

    fun paradropDestinations(unit: MapUnit): List<ProjectedMovementDestination> =
        unit.civ.viewableTiles.asSequence()
            .filter { UnitParadrop.canParadrop(unit, it) }
            .map { ProjectedMovementDestination(it.position.x, it.position.y) }
            .sortedWith(compareBy<ProjectedMovementDestination> { it.x }.thenBy { it.y })
            .toList()

    fun upgradeTargets(unit: MapUnit): List<ProjectedUnitUpgradeTarget> {
        if (!unit.hasMovement() || unit.currentTile.getOwner() != unit.civ || unit.isEmbarked())
            return emptyList()
        return unit.baseUnit.getUpgradeUnits(unit.cache.state)
            .map { unit.civ.getEquivalentUnit(it) }
            .distinctBy { it.name }
            .filter { unit.upgrade.canUpgrade(it) }
            .map { ProjectedUnitUpgradeTarget(it.name, unit.upgrade.getCostOfUpgrade(it)) }
            .filter { it.goldCost <= unit.civ.gold }
            .sortedBy { it.targetUnitName }
            .toList()
    }
}
