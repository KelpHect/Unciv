package com.unciv.ui.screens.multiplayerscreens

import com.badlogic.gdx.scenes.scene2d.ui.Table
import com.badlogic.gdx.scenes.scene2d.ui.TextButton
import com.unciv.logic.multiplayer.authoritative.AuthoritativeUnitOrderController
import com.unciv.logic.multiplayer.authoritative.PlayerProjection
import com.unciv.ui.components.extensions.disable
import com.unciv.ui.components.extensions.toTextButton
import com.unciv.ui.components.input.onClick
import com.unciv.ui.components.widgets.UncivTextField

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
        for (posture in unit.availablePostures) {
            add(actionButton("Posture: ${posture.name}") {
                controller.setPosture(unit.id, posture)
            }).left().row()
        }
        if (unit.canPillage)
            add(actionButton("Pillage current tile") {
                controller.pillage(unit.id)
            }).left().row()
        if (unit.canFoundCity)
            add(actionButton("Found city") {
                controller.foundCity(unit.id)
            }).left().row()
        for (destination in unit.paradropDestinations) {
            add(actionButton("Paradrop to ${destination.x},${destination.y}") {
                controller.paradrop(unit.id, destination.x, destination.y)
            }).left().row()
        }
        for (upgrade in unit.availableUpgradeTargets) {
            add(actionButton(
                "Upgrade to ${upgrade.targetUnitName} (${upgrade.goldCost} gold)",
            ) {
                controller.upgrade(unit.id, upgrade.targetUnitName)
            }).left().row()
        }
        if (unit.canRename) add(renameControl(unit.id, unit.instanceName)).left().row()
        if (unit.canDisband)
            add(actionButton("Disband unit") {
                controller.disband(unit.id)
            }).left().row()
    }

    private fun renameControl(unitId: Int, currentName: String?): Table = Table().apply {
        val name = UncivTextField("Unit name", currentName.orEmpty())
        add(name).width(220f)
        add(actionButton("Rename") {
            controller.rename(unitId, name.text.trim().ifEmpty { null })
        })
    }

    private fun actionButton(
        title: String,
        operation: suspend () -> Unit,
    ): TextButton = title.toTextButton().apply {
        if (busy) disable()
        onClick { submit("Submit authoritative unit order", operation) }
    }
}
