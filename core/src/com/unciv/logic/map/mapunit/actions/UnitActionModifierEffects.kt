package com.unciv.logic.map.mapunit.actions

import com.unciv.logic.map.mapunit.MapUnit
import com.unciv.models.ruleset.unique.Unique
import com.unciv.models.ruleset.unique.UniqueType
import com.unciv.models.stats.Stat
import com.unciv.models.translations.removeConditionals
import yairm210.purity.annotations.Readonly
import kotlin.math.ceil

/** Headless-safe validation and application of unit-action modifiers. */
object UnitActionModifierEffects {
    @Readonly
    fun canUse(unit: MapUnit, actionUnique: Unique): Boolean =
        usagesLeft(unit, actionUnique)?.let { it > 0 } ?: true

    @Readonly
    fun usableUniques(unit: MapUnit, actionUniqueType: UniqueType) =
        unit.matchingUniquesSequence(actionUniqueType)
            .filter { !it.hasModifier(UniqueType.UnitActionExtraLimitedTimes) }
            .filter { canUse(unit, it) }

    @Readonly
    fun movementPointsToUse(
        unit: MapUnit,
        actionUnique: Unique,
        defaultAllMovement: Boolean = false,
    ): Int {
        if (actionUnique.hasModifier(UniqueType.UnitActionMovementCostAll)) return unit.getMaxMovement()
        val movementCost = actionUnique.modifiers
            .filter {
                it.type == UniqueType.UnitActionMovementCost ||
                    it.type == UniqueType.UnitActionMovementCostRequired
            }
            .maxOfOrNull { it.params[0].toInt() }
        return movementCost ?: if (defaultAllMovement) unit.getMaxMovement() else 1
    }

    @Readonly
    fun canActivate(unit: MapUnit, actionUnique: Unique): Boolean {
        if (!canUse(unit, actionUnique)) return false
        val requiredMovement = if (actionUnique.hasModifier(UniqueType.UnitActionMovementCostAll)) 1
        else actionUnique.getModifiers(UniqueType.UnitActionMovementCostRequired)
            .minOfOrNull { it.params[0].toInt() } ?: 1
        if (requiredMovement > ceil(unit.currentMovement).toInt()) return false
        for (modifier in actionUnique.getModifiers(UniqueType.UnitActionStatsCost)) {
            for ((stat, value) in modifier.stats) {
                val city = unit.getClosestCity()
                if (city != null) {
                    if (!city.hasStatToBuy(stat, value.toInt())) return false
                } else if (stat in Stat.statsWithCivWideField) {
                    if (!unit.civ.hasStatToBuy(stat, value.toInt())) return false
                } else return false
            }
        }
        for (modifier in actionUnique.getModifiers(UniqueType.UnitActionStockpileCost)) {
            if (unit.civ.getResourceAmount(modifier.params[1]) < modifier.params[0].toInt()) return false
        }
        return true
    }

    fun activate(unit: MapUnit, actionUnique: Unique, defaultAllMovement: Boolean = false) {
        unit.useMovementPoints(movementPointsToUse(unit, actionUnique, defaultAllMovement).toFloat())
        for (modifier in actionUnique.modifiers) {
            when (modifier.type) {
                UniqueType.UnitActionConsumeUnit -> unit.consume()
                UniqueType.UnitActionLimitedTimes, UniqueType.UnitActionOnce -> {
                    if (usagesLeft(unit, actionUnique) == 1 &&
                        actionUnique.hasModifier(UniqueType.UnitActionAfterWhichConsumed)) {
                        unit.consume()
                        continue
                    }
                    val key = actionUnique.text.removeConditionals()
                    unit.abilityToTimesUsed[key] = (unit.abilityToTimesUsed[key] ?: 0) + 1
                }
                UniqueType.UnitActionStatsCost -> for ((stat, value) in modifier.stats) {
                    if (stat in Stat.statsWithCivWideField) unit.civ.addStat(stat, -value.toInt())
                    else unit.getClosestCity()?.addStat(stat, -value.toInt())
                }
                UniqueType.UnitActionStockpileCost -> {
                    val resource = unit.civ.gameInfo.ruleset.tileResources[modifier.params[1]]
                    if (resource?.isStockpiled == true)
                        unit.civ.gainStockpiledResource(resource, -modifier.params[0].toInt())
                }
                UniqueType.UnitActionRemovingPromotion -> {
                    val promotionName = modifier.params[0]
                    if (unit.hasStatus(promotionName)) unit.removeStatus(promotionName)
                    else unit.promotions.removePromotion(promotionName)
                }
                else -> continue
            }
        }
    }

    @Readonly
    fun usagesLeft(unit: MapUnit, actionUnique: Unique): Int? {
        val total = maxUsages(unit, actionUnique) ?: return null
        return total - (unit.abilityToTimesUsed[actionUnique.text.removeConditionals()] ?: 0)
    }

    @Readonly
    fun maxUsages(unit: MapUnit, actionUnique: Unique): Int? {
        val extraTimes = unit.matchingUniquesSequence(actionUnique.type!!)
            .filter { it.text.removeConditionals() == actionUnique.text.removeConditionals() }
            .flatMap { it.getModifiers(UniqueType.UnitActionExtraLimitedTimes) }
            .sumOf { it.params[0].toInt() }
        val times = actionUnique.getModifiers(UniqueType.UnitActionLimitedTimes)
            .maxOfOrNull { it.params[0].toInt() }
        if (times != null) return times + extraTimes
        if (actionUnique.hasModifier(UniqueType.UnitActionOnce)) return 1 + extraTimes
        return null
    }
}
