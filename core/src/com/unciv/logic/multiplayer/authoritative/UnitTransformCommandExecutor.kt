package com.unciv.logic.multiplayer.authoritative

import com.unciv.logic.map.mapunit.MapUnit
import com.unciv.models.UnitAction
import com.unciv.models.UnitActionType

/** Selects canonical mod-defined transformations through opaque projected action identities. */
internal object UnitTransformCommandExecutor {
    fun projectedActions(unit: MapUnit): List<ProjectedUnitTransformAction> = enabledActions(unit)
        .map { indexed ->
            ProjectedUnitTransformAction(actionId(unit, indexed), indexed.value.associatedUnique!!.params[0])
        }

    fun actionIdFor(unit: MapUnit, selected: UnitAction): String? =
        OpaqueUnitActionIdentity.idFor(DOMAIN, unit, UnitActionType.Transform, selected)

    fun execute(unit: MapUnit, actionId: String) {
        require(actionId.length == 64) { "Invalid unit transformation identity" }
        val action = enabledActions(unit)
            .singleOrNull { actionId(unit, it) == actionId }
            ?.value ?: error("Unit transformation is unavailable in canonical state")
        action.action!!.invoke()
    }

    private fun enabledActions(unit: MapUnit) =
        OpaqueUnitActionIdentity.enabled(unit, UnitActionType.Transform)

    private fun actionId(unit: MapUnit, action: IndexedValue<UnitAction>) =
        OpaqueUnitActionIdentity.id(DOMAIN, unit, action)

    private const val DOMAIN = "transform"
}
