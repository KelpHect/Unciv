package com.unciv.ui.screens.multiplayerscreens

import com.badlogic.gdx.scenes.scene2d.ui.SelectBox
import com.badlogic.gdx.utils.Array
import com.unciv.logic.multiplayer.authoritative.ApiV3Lobby
import com.unciv.logic.multiplayer.authoritative.ApiV3LobbyPasswordUpdate
import com.unciv.ui.components.extensions.toTextButton
import com.unciv.ui.components.input.onClick
import com.unciv.ui.components.widgets.UncivTextField
import com.unciv.ui.popups.Popup
import com.unciv.ui.popups.ToastPopup
import com.unciv.ui.screens.basescreen.BaseScreen

/**
 * Labeled access and capacity form for one canonical lobby revision.
 * Map/rules editing remains in the full game-setup screen.
 */
class AuthoritativeLobbyAccessPopup(
    private val screen: BaseScreen,
    lobby: ApiV3Lobby,
    private val onSave: (String, Int, ApiV3LobbyPasswordUpdate) -> Unit,
) : Popup(screen) {
    private val occupiedSlots = lobby.occupiedSlots
    private val majorCivilizations = lobby.setup.majorCivilizations
    private val name = UncivTextField("Match name", lobby.displayName)
    private val slots = UncivTextField("Human player slots", lobby.humanSlots.toString())
    private val password = UncivTextField("New password (12+ characters)").apply {
        isPasswordMode = true
    }
    private val passwordAction = SelectBox<String>(BaseScreen.skin).apply {
        items = Array(
            if (lobby.passwordRequired) {
                arrayOf("Keep current password", "Make match public", "Replace password")
            } else {
                arrayOf("Keep match public", "Set password")
            },
        )
    }

    init {
        addGoodSizedLabel("LOBBY ACCESS & CAPACITY").colspan(2).growX().row()
        addGoodSizedLabel(
            "Changing any lobby setting resets every player's ready state.",
        ).colspan(2).growX().padBottom(10f).row()
        addGoodSizedLabel("Match name").left()
        add(name).growX().row()
        addGoodSizedLabel("Human player slots").left()
        add(slots).growX().row()
        addGoodSizedLabel("Password policy").left()
        add(passwordAction).growX().row()
        addGoodSizedLabel("Replacement password").left()
        add(password).growX().row()
        addCloseButton().growX()
        add("Save access settings".toTextButton().onClick { save() }).growX()
    }

    private fun save() {
        try {
            val displayName = name.text.trim()
            val humanSlots = slots.text.toInt()
            require(displayName.isNotBlank() && displayName.length <= 80)
            require(humanSlots in occupiedSlots..minOf(16, majorCivilizations))
            val passwordUpdate = when (passwordAction.selected) {
                "Keep current password", "Keep match public" ->
                    ApiV3LobbyPasswordUpdate.keep()
                "Make match public" -> ApiV3LobbyPasswordUpdate.clear()
                "Replace password", "Set password" -> {
                    require(password.text.length in 12..256)
                    ApiV3LobbyPasswordUpdate.replace(password.text)
                }
                else -> error("Unknown password policy")
            }
            close()
            onSave(displayName, humanSlots, passwordUpdate)
        } catch (_: Exception) {
            ToastPopup(
                "Use a name, $occupiedSlots-$majorCivilizations human slots, and 12+ characters when setting a password.",
                screen,
            )
        }
    }
}
