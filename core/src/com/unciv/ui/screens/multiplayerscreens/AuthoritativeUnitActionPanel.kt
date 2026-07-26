package com.unciv.ui.screens.multiplayerscreens

import com.badlogic.gdx.scenes.scene2d.ui.Table
import com.badlogic.gdx.scenes.scene2d.ui.TextButton
import com.unciv.logic.multiplayer.authoritative.AuthoritativeUnitActionController
import com.unciv.logic.multiplayer.authoritative.PlayerProjection
import com.unciv.ui.components.extensions.disable
import com.unciv.ui.components.extensions.toTextButton
import com.unciv.ui.components.input.onClick

/** Renders only direct special actions advertised for the selected unit. */
internal class AuthoritativeUnitActionPanel(
    private val projection: PlayerProjection,
    private val selectedUnitId: Int?,
    private val controller: AuthoritativeUnitActionController,
    private val busy: Boolean,
    private val submit: (taskName: String, operation: suspend () -> Unit) -> Unit,
) {
    fun build(): Table = Table().apply {
        defaults().pad(3f)
        val unit = projection.ownUnits.singleOrNull { it.id == selectedUnitId }
            ?: return@apply
        for (action in unit.availableReligiousActions) {
            add(actionButton(action.name) {
                controller.useReligiousAction(unit.id, action)
            }).left().row()
        }
        for (action in unit.availableGreatPersonActions) {
            add(actionButton(action.name) {
                controller.useGreatPersonAction(unit.id, action)
            }).left().row()
        }
        if (unit.canGift) {
            add(actionButton("Gift unit") { controller.gift(unit.id) }).left().row()
        }
        unit.capitalProjectName?.let { project ->
            add(actionButton("Add to $project") {
                controller.addToCapitalProject(unit.id)
            }).left().row()
        }
        for (action in unit.availableInstantImprovementActions) {
            add(actionButton(action.title) {
                controller.createInstantImprovement(unit.id, action.actionId)
            }).left().row()
        }
        for (action in unit.availableTransformActions) {
            add(actionButton("Transform to ${action.targetUnitName}") {
                controller.transform(unit.id, action.actionId)
            }).left().row()
        }
        for (action in unit.availableTriggerActions) {
            add(actionButton(action.title) {
                controller.triggerUnique(unit.id, action.actionId)
            }).left().row()
        }
    }

    private fun actionButton(
        title: String,
        operation: suspend () -> Unit,
    ): TextButton = title.toTextButton().apply {
        if (busy) disable()
        onClick { submit("Submit authoritative unit action", operation) }
    }
}
