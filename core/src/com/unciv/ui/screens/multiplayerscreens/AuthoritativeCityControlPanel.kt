package com.unciv.ui.screens.multiplayerscreens

import com.badlogic.gdx.scenes.scene2d.ui.Table
import com.badlogic.gdx.scenes.scene2d.ui.TextButton
import com.unciv.logic.multiplayer.authoritative.AuthoritativeCityControlController
import com.unciv.logic.multiplayer.authoritative.CityDispositionAction
import com.unciv.logic.multiplayer.authoritative.CityGovernanceAction
import com.unciv.logic.multiplayer.authoritative.CityTileAssignment
import com.unciv.logic.multiplayer.authoritative.CitizenFocus
import com.unciv.logic.multiplayer.authoritative.PlayerProjection
import com.unciv.logic.multiplayer.authoritative.ProjectedCity
import com.unciv.ui.components.extensions.disable
import com.unciv.ui.components.extensions.toLabel
import com.unciv.ui.components.extensions.toTextButton
import com.unciv.ui.components.input.onClick

/** Projection-only city tiles, citizens, governance, and disposition UI. */
internal class AuthoritativeCityControlPanel(
    private val projection: PlayerProjection,
    private val controller: AuthoritativeCityControlController,
    private val busy: Boolean,
    private val submit: (taskName: String, operation: suspend () -> Unit) -> Unit,
) {
    fun build(): Table = Table().apply {
        defaults().pad(3f)
        addDispositions()
        for (city in projection.ownCities) addCity(city)
    }

    private fun Table.addDispositions() {
        for (disposition in projection.pendingCityDispositions) {
            add("Choose ${disposition.cityName} disposition:".toLabel()).left().row()
            val buttons = Table()
            for (action in disposition.availableActions) {
                buttons.add(actionButton(action.title()) {
                    controller.resolveDisposition(disposition.cityId, action)
                })
            }
            add(buttons).left().row()
        }
    }

    private fun Table.addCity(city: ProjectedCity) {
        add("${city.name} city controls:".toLabel()).left().row()
        val economy = Table()
        for (purchase in city.tilePurchases.filter { it.affordable }) {
            economy.add(actionButton(
                "Buy tile ${purchase.x},${purchase.y} (${purchase.goldCost} Gold)",
            ) {
                controller.buyTile(city.id, purchase.x, purchase.y)
            }).pad(2f)
        }
        for (purchase in city.tileBatchPurchases.filter { it.affordable }) {
            economy.add(actionButton(
                "Buy ring ${purchase.ring}: ${purchase.tileCount} tiles " +
                    "(${purchase.goldCost} Gold)",
            ) {
                controller.buyTileBatch(city.id, purchase.ring)
            }).pad(2f)
        }
        for (building in city.sellableBuildings) {
            economy.add(actionButton("Sell $building") {
                controller.sellBuilding(city.id, building)
            }).pad(2f)
        }
        for (action in city.availableGovernanceActions) {
            economy.add(actionButton(action.title()) {
                controller.setGovernance(city.id, action)
            }).pad(2f)
        }
        if (economy.children.size > 0) add(economy).left().row()

        for (tile in city.assignableTiles) {
            val buttons = Table()
            buttons.add("${tile.x},${tile.y}:".toLabel())
            for (assignment in CityTileAssignment.entries) {
                buttons.add(actionButton(assignment.title()) {
                    controller.setTileAssignment(
                        city.id, tile.x, tile.y, assignment,
                    )
                })
            }
            add(buttons).left().row()
        }

        if (city.specialists.isNotEmpty()) {
            add(actionButton(
                if (city.manualSpecialists) "Use automatic specialists"
                else "Use manual specialists",
            ) {
                controller.setManualSpecialists(city.id, !city.manualSpecialists)
            }).left().row()
        }
        for (specialist in city.specialists) {
            val buttons = Table()
            buttons.add(
                "${specialist.name}: ${specialist.assigned}/${specialist.capacity}".toLabel(),
            )
            if (specialist.assigned > 0) {
                buttons.add(actionButton("-") {
                    controller.setSpecialistCount(
                        city.id, specialist.name, specialist.assigned - 1,
                    )
                })
            }
            if (specialist.assigned < specialist.capacity) {
                buttons.add(actionButton("+") {
                    controller.setSpecialistCount(
                        city.id, specialist.name, specialist.assigned + 1,
                    )
                })
            }
            add(buttons).left().row()
        }

        val citizens = Table()
        citizens.add(actionButton("Reset citizens") {
            controller.resetCitizens(city.id)
        })
        citizens.add(actionButton(
            if (city.avoidGrowth) "Allow growth" else "Avoid growth",
        ) {
            controller.setAvoidGrowth(city.id, !city.avoidGrowth)
        })
        for (focus in city.selectableCitizenFocuses) {
            citizens.add(actionButton(
                if (focus == city.citizenFocus) "✓ ${focus.title()}" else focus.title(),
            ) {
                controller.setCitizenFocus(city.id, focus)
            })
        }
        add(citizens).left().row()

        for (preference in city.unitPromotionPreferences) {
            val saved = preference.savedPromotions.joinToString().ifEmpty { "none" }
            add(actionButton(
                if (preference.enabled) {
                    "Disable ${preference.baseUnitName} promotion preference ($saved)"
                } else {
                    "Enable ${preference.baseUnitName} promotion preference ($saved)"
                },
            ) {
                controller.setUnitPromotionPreference(
                    city.id, preference.baseUnitName, !preference.enabled,
                )
            }).left().row()
        }
    }

    private fun actionButton(
        title: String,
        operation: suspend () -> Unit,
    ): TextButton = title.toTextButton().apply {
        if (busy) disable()
        onClick { submit("Submit authoritative city control", operation) }
    }

    private fun CityDispositionAction.title(): String = name.replaceCamelCase()
    private fun CityGovernanceAction.title(): String = name.replaceCamelCase()
    private fun CityTileAssignment.title(): String = name.replaceCamelCase()
    private fun CitizenFocus.title(): String = name.replaceCamelCase()

    private fun String.replaceCamelCase(): String =
        replace(Regex("(?<=[a-z])(?=[A-Z])"), " ")
}
