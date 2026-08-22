package com.unciv.ui.screens.multiplayerscreens

import com.badlogic.gdx.scenes.scene2d.Touchable
import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.scenes.scene2d.ui.Image
import com.badlogic.gdx.scenes.scene2d.ui.Label
import com.badlogic.gdx.scenes.scene2d.ui.Table
import com.badlogic.gdx.scenes.scene2d.ui.TextButton
import com.unciv.logic.multiplayer.authoritative.AuthoritativeReligionController
import com.unciv.logic.multiplayer.authoritative.PlayerProjection
import com.unciv.logic.multiplayer.authoritative.ReligiousBeliefType
import com.unciv.logic.multiplayer.authoritative.ReligionChoiceValidation
import com.unciv.ui.components.extensions.darken
import com.unciv.ui.components.extensions.disable
import com.unciv.ui.components.extensions.toLabel
import com.unciv.ui.components.extensions.toTextButton
import com.unciv.ui.components.input.onClick
import com.unciv.ui.components.widgets.UncivTextField
import com.unciv.ui.images.ImageGetter
import com.unciv.ui.screens.basescreen.BaseScreen

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
        add(LobbyChrome.caption("Found your religion")).left().row()
        for ((index, type) in choice.requiredBeliefTypes.withIndex()) {
            val selected = "Select ${type.name} belief".toLabel()
            selectedLabels += selected
            add(selected).left().row()
            // Classic picker hierarchy: one icon-bearing belief button per
            // follower/founder slot, wrapping into rows.
            val choices = Table().apply { defaults().pad(2f) }
            for (belief in choice.availableBeliefs.filter {
                type == ReligiousBeliefType.Any || it.type == type
            }) {
                val beliefButton = Table(BaseScreen.skin).apply {
                    defaults().pad(3f)
                    background = BaseScreen.skinStrings.getUiBackground(
                        "MultiplayerScreen/BeliefButton",
                        BaseScreen.skinStrings.roundedEdgeRectangleSmallShape,
                        BaseScreen.skinStrings.skinConfig.baseColor.darken(0.35f),
                    )
                    add(
                        ImageGetter.getReligionPortrait(belief.name, 26f),
                    ).size(26f).center().row()
                    add(belief.name.toLabel(fontSize = 12, hideIcons = true)).center()
                }
                beliefButton.onClick {
                    val previousSlot = selectedBeliefs.indexOf(belief.name)
                    if (previousSlot >= 0) {
                        selectedBeliefs[previousSlot] = null
                        selectedLabels[previousSlot].setText(
                            "Select ${choice.requiredBeliefTypes[previousSlot].name} belief",
                        )
                    }
                    selectedBeliefs[index] = belief.name
                    selected.setText("${type.name}: ${belief.name}")
                }
                if (busy) beliefButton.touchable = Touchable.disabled
                choices.add(beliefButton)
            }
            add(choices).left().row()
        }

        var selectedIcon: String? = null
        var selectedIconImage: Image? = null
        val iconRow = Table(BaseScreen.skin).apply { defaults().pad(3f) }
        val iconLabel = "Religion icon: none".toLabel()
        val nameField = if (choice.requiresReligionIdentity) {
            add(iconLabel).left().row()
            for (icon in choice.availableReligionIcons) {
                val iconButton = Table(BaseScreen.skin).apply { defaults().pad(4f) }
                background = BaseScreen.skinStrings.getUiBackground(
                    "MultiplayerScreen/ReligionIconButton",
                    BaseScreen.skinStrings.roundedEdgeRectangleSmallShape,
                    BaseScreen.skinStrings.skinConfig.baseColor.darken(0.35f),
                )
                val image = ImageGetter.getReligionIcon(icon)
                iconButton.add(image).size(30f)
                iconButton.onClick {
                    selectedIcon = icon
                    selectedIconImage?.color = Color.WHITE
                    selectedIconImage = image
                    image.color = Color.GOLD
                    iconLabel.setText("Religion icon: $icon")
                }
                if (busy) iconButton.touchable = Touchable.disabled
                iconRow.add(iconButton)
            }
            add(iconRow).left().row()
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
}
