package com.unciv.ui.screens.multiplayerscreens

import com.badlogic.gdx.scenes.scene2d.ui.Table
import com.badlogic.gdx.scenes.scene2d.ui.TextButton
import com.unciv.logic.multiplayer.authoritative.AuthoritativeWorldController
import com.unciv.logic.multiplayer.authoritative.ProjectedConstructionKind
import com.unciv.logic.multiplayer.authoritative.ProjectedConstructionOption
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
internal class AuthoritativeWorldDecisions(
    private val controller: AuthoritativeWorldController,
    private val busy: Boolean,
    private val submit: (taskName: String, operation: suspend () -> Unit) -> Unit,
) {
    fun build(): Table = Table().apply {
        defaults().pad(3f)
        addConstructionChoices()
        add(researchSummary()).left().row()
        addChoices(
            "Research",
            controller.projection.research.selectableTargets,
        ) { technology -> controller.selectResearch(technology, append = false) }
        addChoices(
            "Queue research",
            controller.projection.research.appendableTargets,
        ) { technology -> controller.selectResearch(technology, append = true) }
        addResearchQueue()
        addChoices(
            "Free technology",
            controller.projection.research.freeTechnologyChoices,
            controller::chooseProjectedFreeTechnology,
        )
        for (prompt in controller.projection.research.completionPrompts) {
            add(actionButton("Acknowledge ${prompt.technologyName}") {
                controller.acknowledgeProjectedResearchCompletion(prompt.promptId)
            }).left().row()
        }
        add(
            "Culture: ${controller.projection.policies.storedCulture}/" +
                "${controller.projection.policies.cultureNeededForNextPolicy} - " +
                "free policies: ${controller.projection.policies.freePolicies}".toLabel(),
        ).left().row()
        addChoices(
            "Adopt policy",
            controller.projection.policies.selectablePolicies,
            controller::adoptProjectedPolicy,
        )
    }

    private fun Table.addConstructionChoices() {
        for (city in controller.projection.ownCities) {
            add("${city.name} production:".toLabel()).left().row()
            if (city.constructionQueueEntries.isEmpty()) {
                add("No construction selected".toLabel()).left().row()
            } else {
                add(
                    city.constructionQueueEntries.mapIndexed { index, entry ->
                        "${index + 1}. ${entry.name}"
                    }.joinToString(" - ").toLabel(),
                ).left().row()
            }
            val buttons = Table()
            for (option in city.constructionOptions.filter { it.queueable }) {
                addConstructionButtons(buttons, city.id, option)
            }
            if (buttons.children.size > 0) add(buttons).left().row()
        }
    }

    private fun addConstructionButtons(
        buttons: Table,
        cityId: String,
        option: ProjectedConstructionOption,
    ) {
        if (option.placementTargets.isEmpty()) {
            val suffix = if (option.kind == ProjectedConstructionKind.Perpetual)
                " (perpetual)" else ""
            buttons.add(actionButton("${option.name}$suffix") {
                controller.selectConstruction(cityId, option.name)
            }).pad(2f)
            return
        }
        for (target in option.placementTargets) {
            buttons.add(actionButton("${option.name} @ ${target.x},${target.y}") {
                controller.selectConstruction(cityId, option.name, target)
            }).pad(2f)
        }
    }

    private fun Table.addResearchQueue() {
        for ((index, entry) in controller.projection.research.queueEntries.withIndex()) {
            add(
                "${index + 1}. ${entry.technologyName}: ${entry.storedScience}/" +
                    "${entry.cost}${entry.estimatedTurns?.let { " ($it turns)" }.orEmpty()}".toLabel(),
            ).left().row()
            if (entry.availableActions.isNotEmpty()) {
                val actions = Table()
                for (action in entry.availableActions) {
                    actions.add(actionButton(action.title()) {
                        controller.manageResearchQueue(entry.technologyName, index, action)
                    })
                }
                add(actions).left().row()
            }
        }
    }

    private fun Table.addChoices(
        title: String,
        choices: List<String>,
        operation: suspend (String) -> Unit,
    ) {
        if (choices.isEmpty()) return
        add("$title:".toLabel()).left().row()
        val buttons = Table()
        for (choice in choices) {
            buttons.add(actionButton(choice) { operation(choice) }).pad(2f)
        }
        add(buttons).left().row()
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

    private fun ResearchQueueAction.title(): String = when (this) {
        ResearchQueueAction.MoveToTop -> "Move to top"
        ResearchQueueAction.MoveUp -> "Move up"
        ResearchQueueAction.MoveDown -> "Move down"
        ResearchQueueAction.MoveToEnd -> "Move to end"
        ResearchQueueAction.Remove -> "Remove"
    }
}
