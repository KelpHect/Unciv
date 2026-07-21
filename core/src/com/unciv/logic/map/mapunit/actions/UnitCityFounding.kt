package com.unciv.logic.map.mapunit.actions

import com.unciv.logic.city.City
import com.unciv.logic.map.mapunit.MapUnit
import com.unciv.models.ruleset.unique.UniqueTarget
import com.unciv.models.ruleset.unique.UniqueType
import yairm210.purity.annotations.Readonly

/** Shared validation and execution for human, AI, and authoritative city founding. */
object UnitCityFounding {
    @Readonly
    fun foundingUnique(unit: MapUnit) =
        UnitActionModifierEffects.usableUniques(unit, UniqueType.FoundCity).firstOrNull()
            ?: UnitActionModifierEffects.usableUniques(unit, UniqueType.FoundPuppetCity).firstOrNull()

    @Readonly
    fun canFoundCity(unit: MapUnit): Boolean {
        val tile = unit.currentTile
        val unique = foundingUnique(unit) ?: return false
        if (tile.isWater || tile.isImpassible()) return false
        if (unit.civ.isOneCityChallenger() && unit.civ.hasEverOwnedOriginalCapital) return false
        if (!unit.hasMovement() || !tile.canBeSettled(unit.civ)) return false
        return UnitActionModifierEffects.canActivate(unit, unique)
    }

    fun foundCity(unit: MapUnit): City? {
        if (!canFoundCity(unit)) return null
        val unique = foundingUnique(unit) ?: return null
        val hasActionModifiers = unique.modifiers.any {
            it.type?.targetTypes?.contains(UniqueTarget.UnitActionModifier) == true
        }
        val city = unit.civ.addCity(unit.currentTile.position, unit)
        if (hasActionModifiers) UnitActionModifierEffects.activate(unit, unique)
        else unit.destroy()
        if (unique.type == UniqueType.FoundPuppetCity) city.isPuppet = true
        return city
    }
}
