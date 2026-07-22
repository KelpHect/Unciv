package com.unciv.logic.multiplayer.authoritative

import com.unciv.logic.map.mapunit.MapUnit
import com.unciv.models.UnitAction
import com.unciv.models.UnitActionType

/** Projects and executes generic mod-defined trigger-unique actions through shared Kotlin callbacks. */
internal object UnitTriggerCommandExecutor {
    fun projectedActions(unit: MapUnit): List<ProjectedUnitTriggerAction> = enabled(unit).map {
        ProjectedUnitTriggerAction(OpaqueUnitActionIdentity.id(DOMAIN, unit, it), it.value.title)
    }

    fun actionIdFor(unit: MapUnit, selected: UnitAction): String? =
        OpaqueUnitActionIdentity.idFor(DOMAIN, unit, UnitActionType.TriggerUnique, selected)

    fun execute(unit: MapUnit, actionId: String) {
        require(actionId.length == 64) { "Invalid unit trigger identity" }
        val action = enabled(unit).singleOrNull {
            OpaqueUnitActionIdentity.id(DOMAIN, unit, it) == actionId
        }?.value ?: error("Unit trigger is unavailable in canonical state")
        action.action!!.invoke()
    }

    private fun enabled(unit: MapUnit) = OpaqueUnitActionIdentity.enabled(unit, UnitActionType.TriggerUnique)
    private const val DOMAIN = "trigger_unique"
}
