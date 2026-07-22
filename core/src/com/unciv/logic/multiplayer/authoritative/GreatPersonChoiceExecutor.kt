package com.unciv.logic.multiplayer.authoritative

import com.unciv.logic.civilization.Civilization

/** Resolves one pending free-great-person choice from canonical civilization state. */
internal object GreatPersonChoiceExecutor {
    fun availableChoices(civilization: Civilization): List<String> {
        if (civilization.greatPeople.freeGreatPeople <= 0 || civilization.cities.isEmpty()) {
            return emptyList()
        }
        val mayaRestricted = civilization.greatPeople.mayaLimitedFreeGP > 0
        return civilization.greatPeople.getGreatPeople().asSequence()
            .filter { !mayaRestricted || it.name in civilization.greatPeople.longCountGPPool }
            .map { it.name }
            .sorted()
            .toList()
    }

    fun execute(civilization: Civilization, unitName: String) {
        require(unitName in availableChoices(civilization)) {
            "Great person is unavailable in the canonical state"
        }
        val unit = civilization.gameInfo.ruleset.units[unitName]
            ?: error("Projected great person is missing from the pinned ruleset")
        check(civilization.units.addUnit(unit, civilization.getCapital()) != null) {
            "Canonical great person could not be placed"
        }
        civilization.greatPeople.freeGreatPeople--
        if (civilization.greatPeople.mayaLimitedFreeGP > 0) {
            civilization.greatPeople.mayaLimitedFreeGP--
            civilization.greatPeople.longCountGPPool.remove(unitName)
        }
    }
}
