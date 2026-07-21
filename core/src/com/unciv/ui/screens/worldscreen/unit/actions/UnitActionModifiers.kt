package com.unciv.ui.screens.worldscreen.unit.actions

import com.unciv.logic.map.mapunit.MapUnit
import com.unciv.logic.map.mapunit.actions.UnitActionModifierEffects
import com.unciv.models.ruleset.unique.Unique
import com.unciv.models.ruleset.unique.UniqueType
import com.unciv.models.stats.Stats
import com.unciv.models.translations.tr
import com.unciv.ui.components.fonts.FontRulesetIcons
import com.unciv.ui.components.fonts.Fonts
import yairm210.purity.annotations.Readonly

object UnitActionModifiers {
    @Readonly
    fun canUse(unit: MapUnit, actionUnique: Unique): Boolean {
        return UnitActionModifierEffects.canUse(unit, actionUnique)
    }

    @Readonly
    fun getUsableUnitActionUniques(unit: MapUnit, actionUniqueType: UniqueType) =
        UnitActionModifierEffects.usableUniques(unit, actionUniqueType)

    @Readonly
    private fun getMovementPointsToUse(unit: MapUnit, actionUnique: Unique, defaultAllMovement: Boolean = false): Int {
        return UnitActionModifierEffects.movementPointsToUse(unit, actionUnique, defaultAllMovement)
    }

    /** Checks if this Action Unique can be executed, based on action modifiers
     * @param unit: The specific unit executing the Action
     * @param actionUnique: Unique that defines the Action
     * @return Boolean
     */
    @Readonly
    fun canActivateSideEffects(unit: MapUnit, actionUnique: Unique): Boolean {
        return UnitActionModifierEffects.canActivate(unit, actionUnique)
    }

    fun activateSideEffects(unit: MapUnit, actionUnique: Unique, defaultAllMovement: Boolean = false) {
        UnitActionModifierEffects.activate(unit, actionUnique, defaultAllMovement)
    }

    /** Returns 'null' if usages are not limited */
    @Readonly
    private fun usagesLeft(unit: MapUnit, actionUnique: Unique): Int? =
        UnitActionModifierEffects.usagesLeft(unit, actionUnique)

    @Readonly
    private fun getMaxUsages(unit: MapUnit, actionUnique: Unique): Int? =
        UnitActionModifierEffects.maxUsages(unit, actionUnique)

    @Readonly
    fun actionTextWithSideEffects(originalText: String, actionUnique: Unique, unit: MapUnit): String {
        val sideEffectString = getSideEffectString(unit, actionUnique)
        if (sideEffectString == "") return originalText
        else return "{$originalText} $sideEffectString"
    }

    @Readonly
    fun getSideEffectString(unit: MapUnit, actionUnique: Unique, defaultAllMovement: Boolean = false): String {
        val effects = ArrayList<String>()

        val maxUsages = getMaxUsages(unit, actionUnique)
        if (maxUsages!=null) effects += "${usagesLeft(unit, actionUnique)}/$maxUsages"

        if (actionUnique.hasModifier(UniqueType.UnitActionStatsCost)) {
            val statCost = Stats()
            for (conditional in actionUnique.getModifiers(UniqueType.UnitActionStatsCost))
                statCost.add(conditional.stats)
            effects += statCost.toStringOnlyIcons(false)
        }

        if (actionUnique.hasModifier(UniqueType.UnitActionStockpileCost)) {
            var stockpileString = ""
            for (conditionals in actionUnique.getModifiers(UniqueType.UnitActionStockpileCost))
                stockpileString += " ${conditionals.params[0].toInt()} {${conditionals.params[1]}}"
            effects += stockpileString.removePrefix(" ") // drop leading space
        }

        if (actionUnique.hasModifier(UniqueType.UnitActionConsumeUnit)
            || actionUnique.hasModifier(UniqueType.UnitActionAfterWhichConsumed) && usagesLeft(unit, actionUnique) == 1
        ) effects += Fonts.death.toString()
        else effects += getMovementPointsToUse(
            unit,
            actionUnique,
            defaultAllMovement
        ).tr() + Fonts.movement
        
        for (removes in actionUnique.getModifiers(UniqueType.UnitActionRemovingPromotion)) {
            val promotionName = removes.params[0]
            val promotionChar = FontRulesetIcons.rulesetObjectNameToChar[promotionName]
            if (promotionChar != null) effects += "-$promotionChar"
        }

        return if (effects.isEmpty()) ""
        else "(${effects.joinToString { it.tr() }})"
    }
    
    @Readonly
    fun getUseFrequency(unit: MapUnit, actionUnique: Unique?, default: Float): Float {
        val modifier = actionUnique?.modifiersMap?.get(UniqueType.UnitActionPriority)
            ?.firstOrNull { it.conditionalsApply(unit.cache.state) } ?: return default
                
        return modifier.params[0].toFloat()
    }
}
