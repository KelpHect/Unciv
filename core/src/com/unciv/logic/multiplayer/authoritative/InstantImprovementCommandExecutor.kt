package com.unciv.logic.multiplayer.authoritative

import com.unciv.logic.map.mapunit.MapUnit
import com.unciv.models.UnitAction
import com.unciv.models.UnitActionType

/** Projects and executes canonical instant-improvement actions through their shared Kotlin callbacks. */
internal object InstantImprovementCommandExecutor {
    fun projectedActions(unit: MapUnit): List<ProjectedInstantImprovementAction> = enabled(unit).map {
        ProjectedInstantImprovementAction(
            actionId = OpaqueUnitActionIdentity.id(DOMAIN, unit, it),
            title = it.value.title,
        )
    }

    fun actionIdFor(unit: MapUnit, selected: UnitAction): String? =
        enabled(unit).firstOrNull {
            it.value.associatedUnique === selected.associatedUnique &&
                it.value.title == selected.title
        }?.let { OpaqueUnitActionIdentity.id(DOMAIN, unit, it) }

    fun execute(unit: MapUnit, actionId: String) {
        require(actionId.length == 64) { "Invalid instant-improvement action identity" }
        val action = enabled(unit).singleOrNull {
            OpaqueUnitActionIdentity.id(DOMAIN, unit, it) == actionId
        }?.value ?: error("Instant improvement is unavailable in canonical state")
        action.action!!.invoke()
    }

    private fun enabled(unit: MapUnit) =
        OpaqueUnitActionIdentity.enabled(unit, UnitActionType.CreateImprovement)

    private const val DOMAIN = "create_improvement"
}
