package com.unciv.ui.screens.multiplayerscreens

import com.badlogic.gdx.scenes.scene2d.ui.Table
import com.badlogic.gdx.scenes.scene2d.ui.TextButton
import com.unciv.logic.multiplayer.authoritative.AuthoritativeCityEconomyController
import com.unciv.logic.multiplayer.authoritative.ConstructionQueueAction
import com.unciv.logic.multiplayer.authoritative.ProjectedConstructionKind
import com.unciv.logic.multiplayer.authoritative.ProjectedConstructionOption
import com.unciv.logic.multiplayer.authoritative.ProjectedConstructionPurchase
import com.unciv.logic.multiplayer.authoritative.ProjectedTargetCoordinate
import com.unciv.logic.multiplayer.authoritative.ProjectedCity
import com.unciv.logic.multiplayer.authoritative.PlayerProjection
import com.unciv.ui.components.extensions.disable
import com.unciv.ui.components.extensions.toLabel
import com.unciv.ui.components.extensions.toTextButton
import com.unciv.ui.components.input.onClick

/** Projection-only city queue and purchase presentation. */
internal class AuthoritativeCityEconomyPanel(
    private val projection: PlayerProjection,
    private val controller: AuthoritativeCityEconomyController,
    private val busy: Boolean,
    private val submit: (taskName: String, operation: suspend () -> Unit) -> Unit,
) {
    fun build(): Table = Table().apply {
        defaults().pad(3f)
        for (city in projection.ownCities) addCity(city)
    }

    private fun Table.addCity(city: ProjectedCity) {
        add("${city.name} production:".toLabel()).left().row()
        if (city.constructionQueueEntries.isEmpty()) {
            add("No construction selected".toLabel()).left().row()
        }
        for ((index, entry) in city.constructionQueueEntries.withIndex()) {
            add(
                ("${index + 1}. ${entry.name}: ${entry.storedProduction}/" +
                    "${entry.productionCost ?: "∞"}" +
                    entry.estimatedTurns?.let { " ($it turns)" }.orEmpty()).toLabel(),
            ).left().row()
            val buttons = Table()
            if (index > 0) buttons.add(actionButton("Move up") {
                controller.moveConstruction(city.id, index, index - 1, entry.name)
            })
            if (index + 1 < city.constructionQueueEntries.size) {
                buttons.add(actionButton("Move down") {
                    controller.moveConstruction(city.id, index, index + 1, entry.name)
                })
            }
            buttons.add(actionButton("Remove") {
                controller.removeConstruction(city.id, index, entry.name)
            })
            addManageButtons(buttons, city.id, entry.name, index, entry.availableActions)
            addPurchaseButtons(buttons, city.id, entry.name, index, entry.purchases)
            add(buttons).left().row()
        }

        val options = Table()
        for (option in city.constructionOptions) {
            if (option.queueable) addConstructionButtons(options, city.id, option)
            addManageButtons(options, city.id, option.name, null, option.availableActions)
            addPurchaseButtons(options, city.id, option.name, null, option.purchases)
        }
        if (options.children.size > 0) add(options).left().row()
    }

    private fun addConstructionButtons(
        buttons: Table,
        cityId: String,
        option: ProjectedConstructionOption,
    ) {
        if (option.placementTargets.isEmpty()) {
            val suffix = if (option.kind == ProjectedConstructionKind.Perpetual)
                " (perpetual)" else ""
            buttons.add(actionButton("Queue ${option.name}$suffix") {
                controller.selectConstruction(cityId, option.name)
            }).pad(2f)
            return
        }
        for (target in option.placementTargets) {
            buttons.add(actionButton("Queue ${option.name} @ ${target.title()}") {
                controller.selectConstruction(cityId, option.name, target)
            }).pad(2f)
        }
    }

    private fun addManageButtons(
        buttons: Table,
        cityId: String,
        constructionName: String,
        queueIndex: Int?,
        actions: List<ConstructionQueueAction>,
    ) {
        for (action in actions) {
            buttons.add(actionButton(action.title()) {
                controller.manageQueues(cityId, constructionName, queueIndex, action)
            }).pad(2f)
        }
    }

    private fun addPurchaseButtons(
        buttons: Table,
        cityId: String,
        constructionName: String,
        queueIndex: Int?,
        purchases: List<ProjectedConstructionPurchase>,
    ) {
        for (purchase in purchases.filter { it.allowed }) {
            if (!purchase.requiresTile) {
                buttons.add(actionButton(purchase.title()) {
                    controller.purchase(
                        cityId, constructionName, purchase.currency, queueIndex,
                    )
                }).pad(2f)
            } else {
                for (target in purchase.legalTargets) {
                    buttons.add(actionButton("${purchase.title()} @ ${target.title()}") {
                        controller.purchase(
                            cityId,
                            constructionName,
                            purchase.currency,
                            queueIndex,
                            target,
                        )
                    }).pad(2f)
                }
            }
        }
    }

    private fun actionButton(
        title: String,
        operation: suspend () -> Unit,
    ): TextButton = title.toTextButton().apply {
        if (busy) disable()
        onClick { submit("Submit authoritative city decision", operation) }
    }

    private fun ProjectedConstructionPurchase.title(): String =
        "Buy ($cost $currency, $availableAmount available)"

    private fun ProjectedTargetCoordinate.title(): String = "$x,$y"

    private fun ConstructionQueueAction.title(): String = when (this) {
        ConstructionQueueAction.MoveToTop -> "Move to top"
        ConstructionQueueAction.MoveToEnd -> "Move to end"
        ConstructionQueueAction.AddToTop -> "Add to top"
        ConstructionQueueAction.AddToAllCities -> "Add to all cities"
        ConstructionQueueAction.AddOrMoveToTopAllCities -> "Top in all cities"
        ConstructionQueueAction.RemoveFromAllCities -> "Remove from all cities"
    }
}
