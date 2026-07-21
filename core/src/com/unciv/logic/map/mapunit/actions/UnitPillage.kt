package com.unciv.logic.map.mapunit.actions

import com.unciv.logic.civilization.NotificationCategory
import com.unciv.logic.civilization.NotificationIcon
import com.unciv.logic.map.mapunit.MapUnit
import com.unciv.logic.map.tile.Tile
import com.unciv.models.ruleset.unique.UniqueType
import com.unciv.models.stats.Stat
import com.unciv.models.stats.Stats
import com.unciv.ui.components.extensions.toPercent
import yairm210.purity.annotations.Readonly
import kotlin.random.Random

/** Shared pillage rules used by local play, automation, and the authoritative worker. */
object UnitPillage {
    @Readonly
    fun canPillage(unit: MapUnit, tile: Tile): Boolean {
        if (unit.isTransported || !tile.canPillageTile()) return false
        if (unit.hasUnique(UniqueType.CannotPillage, checkCivInfoUniques = true)) return false
        val tileOwner = tile.getOwner()
        return tileOwner == null || unit.civ.isAtWarWith(tileOwner)
    }

    fun pillage(unit: MapUnit): Boolean {
        val tile = unit.currentTile
        if (unit.isCivilian() || !unit.hasMovement() || tile.getOwner() == unit.civ) return false
        if (!canPillage(unit, tile)) return false
        val pillagedImprovement = tile.getImprovementToPillageName() ?: return false
        val pillagingImprovement = tile.canPillageTileImprovement()
        val destroyedWhenPillaged = tile.getImprovementToPillage()
            ?.hasUnique(UniqueType.DestroyedWhenPillaged) == true

        tile.getOwner()?.addNotification(
            "An enemy [${unit.baseUnit.name}] has pillaged our [$pillagedImprovement]",
            tile.position,
            NotificationCategory.War,
            "ImprovementIcons/$pillagedImprovement",
            NotificationIcon.War,
            unit.baseUnit.name,
        )
        applyLoot(tile, unit)
        tile.setPillaged()
        if (tile.resource != null) tile.getOwner()?.cache?.updateCivResources()
        if (!unit.hasUnique(UniqueType.NoMovementToPillage, checkCivInfoUniques = true)) {
            unit.useMovementPoints(1f)
        }
        if (pillagingImprovement) {
            var healAmount = 25f
            for (unique in unit.getMatchingUniques(
                UniqueType.PercentHealthFromPillaging,
                checkCivInfoUniques = true,
            )) healAmount *= unique.params[0].toPercent()
            unit.healBy(healAmount.toInt())
        }
        if (destroyedWhenPillaged) tile.removeImprovement()
        return true
    }

    private fun applyLoot(tile: Tile, unit: MapUnit) {
        val closestCity = unit.civ.cities.minByOrNull { it.getCenterTile().aerialDistanceTo(tile) }
        val improvement = tile.getImprovementToPillage() ?: return
        var pillageYield = Stats()
        val stateForConditionals = unit.cache.state
        val random = Random(unit.civ.gameInfo.turns * tile.position.hashCode().toLong())
        for (unique in improvement.getMatchingUniques(UniqueType.PillageYieldRandom, stateForConditionals)) {
            for ((stat, value) in unique.stats) {
                var yield = Stats()
                yield.add(stat, random.nextInt((value + 1).toInt()) + random.nextInt((value + 1).toInt()).toFloat())
                if (unique.isModifiedByGameSpeed()) yield *= unit.civ.gameInfo.speed.modifier
                if (unique.isModifiedByGameProgress()) yield *= unique.getGameProgressModifier(unit.civ)
                pillageYield.add(yield)
            }
        }
        for (unique in improvement.getMatchingUniques(UniqueType.PillageYieldFixed, stateForConditionals)) {
            var yield = unique.stats
            if (unique.isModifiedByGameSpeed()) yield *= unit.civ.gameInfo.speed.modifier
            if (unique.isModifiedByGameProgress()) yield *= unique.getGameProgressModifier(unit.civ)
            pillageYield.add(yield)
        }
        for (unique in unit.getMatchingUniques(
            UniqueType.PercentYieldFromPillaging,
            checkCivInfoUniques = true,
        )) pillageYield *= unique.params[0].toPercent()
        if (pillageYield.isEmpty()) return

        val globalYield = Stats()
        val cityYield = Stats()
        for ((stat, value) in pillageYield) {
            if (stat in Stat.statsWithCivWideField) {
                unit.civ.addStat(stat, value.toInt())
                globalYield[stat] += value
            } else if (closestCity != null) {
                closestCity.addStat(stat, value.toInt())
                cityYield[stat] += value
            }
        }
        fun Stats.notify(suffix: String) {
            if (isEmpty()) return
            unit.civ.addNotification(
                "We have looted [${toStringWithoutIcons()}] from a [${improvement.name}]$suffix",
                tile.position,
                NotificationCategory.War,
                "ImprovementIcons/${improvement.name}",
                NotificationIcon.War,
            )
        }
        cityYield.notify(" which has been sent to [${closestCity?.name}]")
        globalYield.notify("")
    }
}
