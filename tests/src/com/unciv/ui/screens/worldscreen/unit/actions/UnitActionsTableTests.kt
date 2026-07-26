package com.unciv.ui.screens.worldscreen.unit.actions

import com.unciv.models.UnitActionType
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UnitActionsTableTests {
    @Test
    fun `opened v3 opaque actions fail closed when projection mapping is unavailable`() {
        val submitted = mutableListOf<UnitActionType>()

        for (type in listOf(
            UnitActionType.CreateImprovement,
            UnitActionType.Transform,
            UnitActionType.TriggerUnique,
        )) {
            val handled = UnitActionsTable.routeAuthoritativeOpaqueUnitAction(
                type,
                usesAuthoritativeCommands = true,
                actionIdFor = { null },
                submit = { submittedType, _ -> submitted += submittedType },
            )

            assertTrue(handled)
        }

        assertTrue(submitted.isEmpty())
    }

    @Test
    fun `opened v3 opaque actions submit only their mapped identity`() {
        val submitted = mutableListOf<Pair<UnitActionType, String>>()

        val handled = UnitActionsTable.routeAuthoritativeOpaqueUnitAction(
            UnitActionType.Transform,
            usesAuthoritativeCommands = true,
            actionIdFor = { "opaque-action-id" },
            submit = { type, actionId -> submitted += type to actionId },
        )

        assertTrue(handled)
        assertTrue(submitted == listOf(UnitActionType.Transform to "opaque-action-id"))
    }

    @Test
    fun `legacy and unrelated actions retain their local route`() {
        val resolve: (UnitActionType) -> String? = { "must-not-submit" }
        val submit: (UnitActionType, String) -> Unit = { _, _ -> error("unexpected submission") }

        assertFalse(UnitActionsTable.routeAuthoritativeOpaqueUnitAction(
            UnitActionType.Transform,
            usesAuthoritativeCommands = false,
            resolve,
            submit,
        ))
        assertFalse(UnitActionsTable.routeAuthoritativeOpaqueUnitAction(
            UnitActionType.Sleep,
            usesAuthoritativeCommands = true,
            resolve,
            submit,
        ))
    }
}
