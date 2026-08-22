package com.unciv.ui.screens.multiplayerscreens

import com.badlogic.gdx.scenes.scene2d.ui.Table
import com.badlogic.gdx.scenes.scene2d.ui.TextButton
import com.unciv.logic.multiplayer.authoritative.AuthoritativeWorldController
import com.unciv.logic.multiplayer.authoritative.AuthoritativeUnitTargetMode
import com.unciv.logic.multiplayer.authoritative.ResearchQueueAction
import com.unciv.ui.components.extensions.disable
import com.unciv.ui.components.extensions.toLabel
import com.unciv.ui.components.extensions.toTextButton
import com.unciv.ui.components.input.onClick

/**
 * Renders only choices advertised by the current player projection.
 *
 * This is presentation and typed-input routing, not a client rules engine.
 */
/**
 * The city panels are absent too: they belong to the city a player tapped, not
 * to a list of every city at once.
 *
 * The unit action and order panels are deliberately absent here: they now sit
 * with the real unit table, next to the unit they act on.
 */
internal class AuthoritativeWorldDecisions(
    private val controller: AuthoritativeWorldController,
    private val busy: Boolean,
    private val selectUnitTarget: (AuthoritativeUnitTargetMode) -> Unit,
    private val submit: (taskName: String, operation: suspend () -> Unit) -> Unit,
    /** Presentation-only ruleset for partner identity; never decides anything. */
    private val ruleset: com.unciv.models.ruleset.Ruleset? = null,
    /** Per-partner trade drafts owned by the hosting screen across refreshes. */
    private val tradeDrafts: MutableMap<String, AuthoritativeTradePanel.Draft> =
        mutableMapOf(),
) {
    fun build(): Table = Table().apply {
        defaults().pad(3f)
        add(
            AuthoritativePlayerStatusPanel(controller.projection).build(),
        ).left().row()
        add(
            AuthoritativeCityControlPanel(
                controller.projection,
                controller.cityControls,
                busy,
                submit,
                includeCities = false,
            ).build(),
        ).left().row()
        add(
            AuthoritativeCombatPanel(
                controller.projection,
                controller.selectedUnitId,
                controller.combat,
                busy,
                submit,
            ).build(),
        ).left().row()
        add(
            AuthoritativePromptPanel(
                controller.projection,
                controller.prompts,
                busy,
                submit,
            ).build(),
        ).left().row()
        add(
            AuthoritativeSpyPanel(
                controller.projection,
                controller.spies,
                busy,
                submit,
            ).build(),
        ).left().row()
        add(
            AuthoritativeReligionPanel(
                controller.projection,
                controller.religion,
                busy,
                submit,
            ).build(),
        ).left().row()
        add(
            AuthoritativeDiplomacyPanel(
                controller.projection,
                controller.diplomacy,
                busy,
                submit,
                ruleset,
            ).build(),
        ).left().row()
        add(
            AuthoritativeTradePanel(
                controller.projection,
                controller.trade,
                busy,
                tradeDrafts,
                submit,
            ).build(),
        ).left().row()
        add(
            AuthoritativeHistoryPanel(controller.projection).build(),
        ).left().row()
        add(
            AuthoritativeEmpirePanel(controller.projection).build(),
        ).left().row()
        add(researchSummary().toLabel()).left().row()
        // Research and policies are chosen on the real tech-tree and policy
        // screens, which the world screen opens fed by this projection; only
        // the completion acknowledgements remain here because they are prompts.
        for (prompt in controller.projection.research.completionPrompts) {
            add(actionButton("Acknowledge ${prompt.technologyName}") {
                controller.acknowledgeProjectedResearchCompletion(prompt.promptId)
            }).left().row()
        }
    }

    private fun actionButton(
        title: String,
        operation: suspend () -> Unit,
    ): TextButton = title.toTextButton().apply {
        if (busy) disable()
        onClick { submit("Submit authoritative decision", operation) }
    }

    private fun researchSummary(): String {
        val research = controller.projection.research
        return "Research: ${research.currentTechnology ?: "none"} - " +
            "overflow science: ${research.overflowScience}"
    }
}
