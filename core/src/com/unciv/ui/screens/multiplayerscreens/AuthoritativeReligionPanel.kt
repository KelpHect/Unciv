package com.unciv.ui.screens.multiplayerscreens

import com.badlogic.gdx.scenes.scene2d.ui.Label
import com.badlogic.gdx.scenes.scene2d.ui.Table
import com.badlogic.gdx.scenes.scene2d.ui.TextButton
import com.unciv.logic.multiplayer.authoritative.AuthoritativeReligionController
import com.unciv.logic.multiplayer.authoritative.PlayerProjection
import com.unciv.logic.multiplayer.authoritative.ReligiousBeliefType
import com.unciv.logic.multiplayer.authoritative.ReligionChoiceValidation
import com.unciv.ui.components.extensions.disable
import com.unciv.ui.components.extensions.toLabel
import com.unciv.ui.components.extensions.toTextButton
import com.unciv.ui.components.input.onClick
import com.unciv.ui.components.widgets.UncivTextField

/** Interactive presentation state for one exact projected religion choice. */
internal class AuthoritativeReligionPanel(
    private val projection: PlayerProjection,
    private val controller: AuthoritativeReligionController,
    private val busy: Boolean,
    private val submit: (taskName: String, operation: suspend () -> Unit) -> Unit,
) {
    fun build(): Table = Table().apply {
        defaults().pad(3f)
        val choice = projection.religionChoice ?: return@apply
        val selectedBeliefs = MutableList<String?>(choice.requiredBeliefTypes.size) { null }
        val selectedLabels = mutableListOf<Label>()
        for ((index, type) in choice.requiredBeliefTypes.withIndex()) {
            val selected = "Select ${type.name} belief".toLabel()
            selectedLabels += selected
            add(selected).left().row()
            val choices = Table()
            for (belief in choice.availableBeliefs.filter {
                type == ReligiousBeliefType.Any || it.type == type
            }) {
                choices.add(localButton(belief.name) {
                    val previousSlot = selectedBeliefs.indexOf(belief.name)
                    if (previousSlot >= 0) {
                        selectedBeliefs[previousSlot] = null
                        selectedLabels[previousSlot].setText(
                            "Select ${choice.requiredBeliefTypes[previousSlot].name} belief",
                        )
                    }
                    selectedBeliefs[index] = belief.name
                    selected.setText("${type.name}: ${belief.name}")
                })
            }
            add(choices).left().row()
        }

        var selectedIcon: String? = null
        val iconLabel = "Religion icon: none".toLabel()
        val nameField = if (choice.requiresReligionIdentity) {
            add(iconLabel).left().row()
            val icons = Table()
            for (icon in choice.availableReligionIcons) {
                icons.add(localButton(icon) {
                    selectedIcon = icon
                    iconLabel.setText("Religion icon: $icon")
                })
            }
            add(icons).left().row()
            UncivTextField("Religion name").also {
                it.maxLength = ReligionChoiceValidation.MAX_DISPLAY_NAME_LENGTH
                add(it).growX().row()
            }
        } else null

        add(actionButton("Confirm religious choices") {
            controller.choose(
                selectedBeliefs.map { requireNotNull(it) {
                    "Select every projected belief slot"
                } },
                selectedIcon,
                nameField?.text,
            )
        }).left().row()
    }

    private fun actionButton(
        title: String,
        operation: suspend () -> Unit,
    ): TextButton = title.toTextButton().apply {
        if (busy) disable()
        onClick { submit("Submit authoritative religious choice", operation) }
    }

    private fun localButton(title: String, operation: () -> Unit): TextButton =
        title.toTextButton().apply {
            if (busy) disable()
            onClick { operation() }
        }
}
