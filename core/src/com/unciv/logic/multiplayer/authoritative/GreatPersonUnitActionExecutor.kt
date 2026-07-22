package com.unciv.logic.multiplayer.authoritative

import com.unciv.logic.map.mapunit.MapUnit
import com.unciv.models.UnitActionType
import com.unciv.ui.screens.worldscreen.unit.actions.UnitActions

/** Executes direct great-person actions through the canonical shared Kotlin action implementations. */
internal object GreatPersonUnitActionExecutor {
    fun availableActions(unit: MapUnit): List<GreatPersonUnitAction> = GreatPersonUnitAction.entries
        .filter { action -> UnitActions.getUnitActions(unit, action.unitActionType).any { it.action != null } }

    fun execute(unit: MapUnit, action: GreatPersonUnitAction) {
        require(UnitActions.invokeUnitAction(unit, action.unitActionType)) {
            "Great-person unit action is unavailable in the canonical state"
        }
    }

    private val GreatPersonUnitAction.unitActionType: UnitActionType
        get() = when (this) {
            GreatPersonUnitAction.HurryResearch -> UnitActionType.HurryResearch
            GreatPersonUnitAction.HurryPolicy -> UnitActionType.HurryPolicy
            GreatPersonUnitAction.HurryWonder -> UnitActionType.HurryWonder
            GreatPersonUnitAction.HurryBuilding -> UnitActionType.HurryBuilding
            GreatPersonUnitAction.ConductTradeMission -> UnitActionType.ConductTradeMission
        }
}
