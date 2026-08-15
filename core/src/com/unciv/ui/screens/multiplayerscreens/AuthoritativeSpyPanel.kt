package com.unciv.ui.screens.multiplayerscreens

import com.badlogic.gdx.scenes.scene2d.ui.Table
import com.badlogic.gdx.scenes.scene2d.ui.TextButton
import com.unciv.logic.multiplayer.authoritative.AuthoritativeSpyController
import com.unciv.logic.multiplayer.authoritative.PendingEndTurnAction
import com.unciv.logic.multiplayer.authoritative.PlayerProjection
import com.unciv.ui.components.extensions.disable
import com.unciv.ui.components.extensions.toLabel
import com.unciv.ui.components.extensions.toTextButton
import com.unciv.ui.components.input.onClick

/** Renders only destinations and coup actions advertised for owned spies. */
internal class AuthoritativeSpyPanel(
    private val projection: PlayerProjection,
    private val controller: AuthoritativeSpyController,
    private val busy: Boolean,
    private val submit: (taskName: String, operation: suspend () -> Unit) -> Unit,
) {
    fun build(): Table = Table().apply {
        defaults().pad(3f)
        if (!projection.isCurrentTurn || projection.spies.isEmpty()) return@apply
        if (PendingEndTurnAction.MoveSpies in projection.pendingTurnActions) {
            add("Assign every required spy before ending the turn".toLabel()).left().row()
        }
        for (spy in projection.spies) {                add(
                    (
                        "${spy.name} (rank ${spy.rank}) - ${spy.action.name} - " +
                            "${spy.turnsRemaining} turns"
                        ).toLabel(),
                ).left().row()
            if (spy.canMoveToHideout) {
                add(actionButton("Move ${spy.name} to hideout") {
                    controller.move(spy.name, null)
                }).left().row()
            }
            for (cityId in spy.availableCityIds) {
                add(actionButton("Move ${spy.name} to city $cityId") {
                    controller.move(spy.name, cityId)
                }).left().row()
            }
            if (spy.canStageCoup) {
                add(actionButton("Stage coup with ${spy.name}") {
                    controller.setCoup(spy.name, true)
                }).left().row()
            }
            if (spy.canCancelCoup) {
                add(actionButton("Cancel ${spy.name}'s coup") {
                    controller.setCoup(spy.name, false)
                }).left().row()
            }
        }
    }

    private fun actionButton(
        title: String,
        operation: suspend () -> Unit,
    ): TextButton = title.toTextButton().apply {
        if (busy) disable()
        onClick { submit("Submit authoritative spy action", operation) }
    }
}
