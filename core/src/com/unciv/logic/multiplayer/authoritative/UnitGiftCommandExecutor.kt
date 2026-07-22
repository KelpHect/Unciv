package com.unciv.logic.multiplayer.authoritative

import com.unciv.logic.GameInfo
import com.unciv.logic.civilization.Civilization
import com.unciv.logic.civilization.diplomacy.DiplomaticModifiers
import com.unciv.logic.map.mapunit.MapUnit
import com.unciv.models.ruleset.unique.UniqueType

/** Derives the recipient and executes a unit gift from canonical tile and diplomacy state. */
object UnitGiftCommandExecutor {
    fun canGift(unit: MapUnit): Boolean {
        val recipient = unit.currentTile.getOwner() ?: return false
        if (recipient == unit.civ || unit.isTransported || !unit.hasMovement()) return false
        if (recipient.isCityState) {
            if (recipient.isAtWarWith(unit.civ)) return false
            if (!unit.isMilitary() && unit.getMatchingUniques(
                    UniqueType.GainInfluenceWithUnitGiftToCityState,
                    checkCivInfoUniques = true,
                ).none { unit.matchesFilter(it.params[1]) }) return false
        } else if (!unit.currentTile.isFriendlyTerritory(unit.civ)) return false
        return true
    }

    fun gift(game: GameInfo, actor: Civilization, unitId: Int) {
        require(game.currentPlayer == actor.civID) { "Authenticated actor cannot gift units outside their turn" }
        val unit = actor.units.getUnitById(unitId) ?: error("Unit is not controlled by the authenticated actor")
        require(canGift(unit)) { "Unit gift is unavailable in canonical state" }
        val recipient = unit.currentTile.getOwner()!!
        if (recipient.isCityState) {
            for (unique in unit.getMatchingUniques(
                UniqueType.GainInfluenceWithUnitGiftToCityState,
                checkCivInfoUniques = true,
            )) {
                if (unit.matchesFilter(unique.params[1])) {
                    recipient.getDiplomacyManager(actor)!!.addInfluence(unique.params[0].toFloat() - 5f)
                    break
                }
            }
            recipient.getDiplomacyManager(actor)!!.addInfluence(5f)
        } else recipient.getDiplomacyManager(actor)!!.addModifier(DiplomaticModifiers.GaveUsUnits, 5f)

        if (recipient.isCityState && unit.isGreatPerson()) unit.destroy()
        else unit.gift(recipient)
    }
}
