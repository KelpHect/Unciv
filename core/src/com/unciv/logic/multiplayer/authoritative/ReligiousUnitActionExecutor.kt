package com.unciv.logic.multiplayer.authoritative

import com.unciv.logic.map.mapunit.MapUnit
import com.unciv.models.UnitActionType
import com.unciv.ui.screens.worldscreen.unit.actions.UnitActions

/** Executes the closed religious-unit command family through Unciv's canonical rules. */
internal object ReligiousUnitActionExecutor {
    fun availableActions(unit: MapUnit): List<ReligiousUnitAction> = ReligiousUnitAction.entries
        .filter { action ->
            UnitActions.getUnitActions(unit, action.unitActionType).any { it.action != null }
        }

    fun execute(unit: MapUnit, action: ReligiousUnitAction) {
        require(UnitActions.invokeUnitAction(unit, action.unitActionType)) {
            "Religious unit action is unavailable in the canonical state"
        }
    }

    private val ReligiousUnitAction.unitActionType: UnitActionType
        get() = when (this) {
            ReligiousUnitAction.SpreadReligion -> UnitActionType.SpreadReligion
            ReligiousUnitAction.RemoveHeresy -> UnitActionType.RemoveHeresy
        }
}
