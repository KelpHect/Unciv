package com.unciv.logic.multiplayer.authoritative

import com.unciv.logic.map.mapunit.MapUnit
import com.unciv.models.UnitAction
import com.unciv.models.UnitActionType
import com.unciv.ui.screens.worldscreen.unit.actions.UnitActions
import java.security.MessageDigest

/** Selects canonical mod-defined transformations through opaque projected action identities. */
internal object UnitTransformCommandExecutor {
    fun projectedActions(unit: MapUnit): List<ProjectedUnitTransformAction> = enabledActions(unit)
        .map { (index, action) ->
            ProjectedUnitTransformAction(actionId(unit, index, action), action.associatedUnique!!.params[0])
        }

    fun actionIdFor(unit: MapUnit, selected: UnitAction): String? = enabledActions(unit)
        .firstOrNull { (_, action) -> action.associatedUnique === selected.associatedUnique }
        ?.let { (index, action) -> actionId(unit, index, action) }

    fun execute(unit: MapUnit, actionId: String) {
        require(actionId.length == 64) { "Invalid unit transformation identity" }
        val action = enabledActions(unit)
            .singleOrNull { (index, action) -> actionId(unit, index, action) == actionId }
            ?.value ?: error("Unit transformation is unavailable in canonical state")
        action.action!!.invoke()
    }

    private fun enabledActions(unit: MapUnit): List<IndexedValue<UnitAction>> = UnitActions
        .getUnitActions(unit, UnitActionType.Transform)
        .filter { it.action != null && it.associatedUnique != null }
        .withIndex()
        .toList()

    private fun actionId(unit: MapUnit, index: Int, action: UnitAction): String {
        val canonical = "${unit.id}\u0000$index\u0000${action.associatedUnique!!.text}"
        return MessageDigest.getInstance("SHA-256").digest(canonical.toByteArray())
            .joinToString("") { "%02x".format(it) }
    }
}
