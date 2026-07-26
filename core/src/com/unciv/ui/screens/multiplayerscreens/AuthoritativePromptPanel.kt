package com.unciv.ui.screens.multiplayerscreens

import com.badlogic.gdx.scenes.scene2d.ui.Table
import com.badlogic.gdx.scenes.scene2d.ui.TextButton
import com.unciv.logic.multiplayer.authoritative.AuthoritativePromptController
import com.unciv.logic.multiplayer.authoritative.DiplomacyPromptType
import com.unciv.logic.multiplayer.authoritative.PendingEndTurnAction
import com.unciv.logic.multiplayer.authoritative.PlayerProjection
import com.unciv.ui.components.extensions.disable
import com.unciv.ui.components.extensions.toLabel
import com.unciv.ui.components.extensions.toTextButton
import com.unciv.ui.components.input.onClick

/** Renders exact server-projected votes, selections, events, and prompts. */
internal class AuthoritativePromptPanel(
    private val projection: PlayerProjection,
    private val controller: AuthoritativePromptController,
    private val busy: Boolean,
    private val submit: (taskName: String, operation: suspend () -> Unit) -> Unit,
) {
    fun build(): Table = Table().apply {
        defaults().pad(3f)
        if (PendingEndTurnAction.CastDiplomaticVote in projection.pendingTurnActions) {
            add("Diplomatic vote:".toLabel()).left().row()
            add(actionButton("Abstain") {
                controller.castDiplomaticVote(null)
            }).left().row()
            for (candidate in projection.diplomaticVoteCandidates) {
                add(actionButton("Vote for $candidate") {
                    controller.castDiplomaticVote(candidate)
                }).left().row()
            }
        }
        if (PendingEndTurnAction.PickGreatPerson in projection.pendingTurnActions) {
            for (unitName in projection.selectableGreatPeople) {
                add(actionButton("Choose $unitName") {
                    controller.chooseGreatPerson(unitName)
                }).left().row()
            }
        }
        for (prompt in projection.eventPrompts) {
            add("${prompt.eventName}: ${prompt.text}".toLabel()).left().row()
            for (choice in prompt.choices) {
                add(actionButton(choice.text) {
                    controller.resolveEvent(prompt.promptId, choice.choiceId)
                }).left().row()
            }
        }
        for (prompt in projection.diplomacyPrompts) {
            add("${prompt.requestingCivilizationId}: ${prompt.type.name}".toLabel())
                .left().row()
            if (
                prompt.type == DiplomacyPromptType.Friendship ||
                prompt.type == DiplomacyPromptType.Demand
            ) {
                add(actionButton("Accept") {
                    controller.respondToDiplomacy(prompt.promptId, true)
                })
                add(actionButton("Decline") {
                    controller.respondToDiplomacy(prompt.promptId, false)
                }).row()
            }
            for (response in prompt.availableCityStateResponses) {
                add(actionButton(response.name) {
                    controller.respondToCityState(prompt.promptId, response)
                }).left().row()
            }
        }
    }

    private fun actionButton(
        title: String,
        operation: suspend () -> Unit,
    ): TextButton = title.toTextButton().apply {
        if (busy) disable()
        onClick { submit("Submit authoritative choice", operation) }
    }
}
