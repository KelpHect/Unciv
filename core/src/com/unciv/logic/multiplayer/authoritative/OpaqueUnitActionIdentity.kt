package com.unciv.logic.multiplayer.authoritative

import com.unciv.logic.map.mapunit.MapUnit
import com.unciv.models.UnitAction
import com.unciv.models.UnitActionType
import com.unciv.ui.screens.worldscreen.unit.actions.UnitActions
import java.security.MessageDigest

/** Stable, player-scoped identities for currently executable shared Kotlin unit actions. */
internal object OpaqueUnitActionIdentity {
    fun enabled(unit: MapUnit, type: UnitActionType): List<IndexedValue<UnitAction>> = UnitActions
        .getUnitActions(unit, type)
        .filter { it.action != null && it.associatedUnique != null }
        .withIndex()
        .toList()

    fun id(domain: String, unit: MapUnit, indexed: IndexedValue<UnitAction>): String {
        val canonical = "$domain\u0000${unit.id}\u0000${indexed.index}\u0000${indexed.value.associatedUnique!!.text}"
        return MessageDigest.getInstance("SHA-256").digest(canonical.toByteArray())
            .joinToString("") { "%02x".format(it) }
    }

    fun idFor(domain: String, unit: MapUnit, type: UnitActionType, selected: UnitAction): String? =
        enabled(unit, type)
            .firstOrNull { it.value.associatedUnique === selected.associatedUnique }
            ?.let { id(domain, unit, it) }
}
