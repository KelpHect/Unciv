package com.unciv.ui.screens.multiplayerscreens

import com.badlogic.gdx.scenes.scene2d.ui.Table
import com.badlogic.gdx.scenes.scene2d.ui.TextButton
import com.unciv.logic.multiplayer.authoritative.AuthoritativeUnitOrderController
import com.unciv.logic.multiplayer.authoritative.PlayerProjection
import com.unciv.ui.components.extensions.disable
import com.unciv.ui.components.extensions.toTextButton
import com.unciv.ui.components.input.onClick

/** Renders exact projected order-state transitions for the selected unit. */
internal class AuthoritativeUnitOrderPanel(
    private val projection: PlayerProjection,
    private val selectedUnitId: Int?,
    private val controller: AuthoritativeUnitOrderController,
    private val busy: Boolean,
    private val submit: (taskName: String, operation: suspend () -> Unit) -> Unit,
) {
    fun build(): Table = Table().apply {
        defaults().pad(3f)
        if (!projection.isCurrentTurn) return@apply
        val unit = projection.ownUnits.singleOrNull { it.id == selectedUnitId }
            ?: return@apply
        if (unit.movementDestinationX != null && unit.movementDestinationY != null) {
            add(actionButton("Cancel movement to " +
                "${unit.movementDestinationX},${unit.movementDestinationY}") {
                controller.cancelMovement(unit.id)
            }).left().row()
        }
        add(actionButton(if (unit.exploring) "Stop exploring" else "Explore") {
            controller.setExploration(unit.id, !unit.exploring)
        }).left().row()
        add(actionButton(if (unit.automated) "Stop automation" else "Automate") {
            controller.setAutomation(unit.id, !unit.automated)
        }).left().row()
        for (promotion in unit.availablePromotions) {
            add(actionButton("Promote: $promotion") {
                controller.promote(unit.id, promotion)
            }).left().row()
        }
        for (destination in unit.swapDestinations) {
            add(actionButton("Swap at ${destination.x},${destination.y}") {
                controller.swap(unit.id, destination.x, destination.y)
            }).left().row()
        }
    }

    private fun actionButton(
        title: String,
        operation: suspend () -> Unit,
    ): TextButton = title.toTextButton().apply {
        if (busy) disable()
        onClick { submit("Submit authoritative unit order", operation) }
    }
}
