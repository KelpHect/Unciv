package com.unciv.ui.screens.multiplayerscreens

import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.scenes.scene2d.ui.SelectBox
import com.badlogic.gdx.scenes.scene2d.ui.Table
import com.unciv.logic.multiplayer.authoritative.ApiV3GameSetup
import com.unciv.logic.multiplayer.authoritative.ApiV3Lobby
import com.unciv.logic.multiplayer.authoritative.ApiV3LobbyPasswordUpdate
import com.unciv.models.ruleset.RulesetCache
import com.unciv.ui.components.extensions.toLabel
import com.unciv.ui.components.widgets.TabbedPager
import com.unciv.ui.components.widgets.UncivTextField
import com.unciv.ui.screens.basescreen.BaseScreen

internal data class AuthoritativeLobbyConfiguration(
    val displayName: String,
    val humanSlots: Int,
    val password: ApiV3LobbyPasswordUpdate,
    val setup: ApiV3GameSetup,
)

/**
 * Owner-only room/access fields composed with the typed game setup pages.
 * The screen owns saving so all settings remain in the real-time lobby.
 */
internal class AuthoritativeLobbyConfigurationEditor(
    private val lobby: ApiV3Lobby,
) {
    private val name = UncivTextField("Match name", lobby.displayName)
    private val slots = UncivTextField("Human players", lobby.humanSlots.toString())
    private val password = UncivTextField("New password (12+ characters)").apply {
        isPasswordMode = true
    }
    private val passwordAction = SelectBox<String>(BaseScreen.skin).apply {
        items = com.badlogic.gdx.utils.Array(
            if (lobby.passwordRequired) {
                arrayOf("Keep current password", "Make match public", "Replace password")
            } else {
                arrayOf("Keep match public", "Set password")
            },
        )
    }
    private val setupEditor = AuthoritativeGameSetupEditor(
        RulesetCache.getComplexRuleset(
            lobby.modNames.toCollection(linkedSetOf()),
            lobby.baseRulesetName,
        ),
        lobby.setup,
    )

    val gamePage: Table =
        object : Table(BaseScreen.skin), TabbedPager.IPageExtensions {
            init {
                defaults().pad(8f)
                add("ROOM & ACCESS".toLabel(Color.GOLD)).colspan(2).growX().left().row()
                addField("Match name", this@AuthoritativeLobbyConfigurationEditor.name)
                addField("Human player slots", slots)
                addField("Password policy", passwordAction)
                addField("Replacement password", password)
                add(
                    "Saving any setting is atomic and resets every player's ready state."
                        .toLabel(Color.LIGHT_GRAY),
                ).colspan(2).growX().left().row()
                add(setupEditor.gamePage).colspan(2).growX().row()
            }

            override fun activated(index: Int, caption: String, pager: TabbedPager) = Unit

            override fun deactivated(index: Int, caption: String, pager: TabbedPager) {
                passwordAction.hideList()
                setupEditor.closeOpenLists()
            }
    }

    val worldPage get() = setupEditor.worldPage
    val victoryPage get() = setupEditor.victoryPage
    val advancedPage get() = setupEditor.advancedPage

    fun build(): AuthoritativeLobbyConfiguration {
        val displayName = name.text.trim()
        val humanSlots = slots.text.trim().toIntOrNull()
        require(displayName.isNotBlank() && displayName.length <= 80) {
            "Match name must contain 1-80 characters."
        }
        require(humanSlots != null) { "Human player slots must be a number." }
        val setup = setupEditor.build(lobby.setup.ownerCivilizationId)
        require(
            humanSlots in lobby.occupiedSlots..minOf(16, setup.majorCivilizations),
        ) {
            "Human slots must fit the occupied slots and major civilizations."
        }
        val passwordUpdate = when (passwordAction.selected) {
            "Keep current password", "Keep match public" ->
                ApiV3LobbyPasswordUpdate.keep()
            "Make match public" -> ApiV3LobbyPasswordUpdate.clear()
            "Replace password", "Set password" -> {
                require(password.text.length in 12..256) {
                    "Private match passwords must contain 12-256 characters."
                }
                ApiV3LobbyPasswordUpdate.replace(password.text)
            }
            else -> error("Unknown password policy")
        }
        return AuthoritativeLobbyConfiguration(
            displayName,
            humanSlots,
            passwordUpdate,
            setup,
        )
    }

    private fun Table.addField(label: String, actor: com.badlogic.gdx.scenes.scene2d.Actor) {
        add(label.toLabel(Color.LIGHT_GRAY)).left()
        add(actor).minWidth(220f).growX().left().row()
    }
}
