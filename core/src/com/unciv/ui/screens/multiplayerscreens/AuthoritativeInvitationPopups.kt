package com.unciv.ui.screens.multiplayerscreens

import com.badlogic.gdx.scenes.scene2d.ui.TextButton
import com.unciv.Constants
import com.unciv.logic.multiplayer.authoritative.ApiV3PlayerInvitation
import com.unciv.logic.multiplayer.authoritative.AuthoritativeInvitationFlow
import com.unciv.logic.multiplayer.authoritative.AuthoritativeInvitationCoordinator
import com.unciv.logic.multiplayer.authoritative.OpenedAuthoritativePlayerGame
import com.unciv.models.translations.tr
import com.unciv.ui.components.extensions.disable
import com.unciv.ui.components.extensions.enable
import com.unciv.ui.components.extensions.toTextButton
import com.unciv.ui.components.input.onClick
import com.unciv.ui.components.widgets.UncivTextField
import com.unciv.ui.popups.Popup
import com.unciv.ui.popups.ToastPopup
import com.unciv.ui.screens.basescreen.BaseScreen
import com.unciv.ui.screens.savescreens.LoadGameScreen
import com.unciv.utils.Concurrency
import com.unciv.utils.launchOnGLThread

class AuthoritativeInvitationInboxPopup(
    private val screen: BaseScreen,
    private val flow: AuthoritativeInvitationFlow,
    private val onAccepted: (OpenedAuthoritativePlayerGame) -> Unit,
) : Popup(screen) {
    init {
        addGoodSizedLabel(Constants.working)
    }

    fun openAndRefresh() {
        open()
        refresh()
    }

    private fun refresh() {
        Concurrency.runOnNonDaemonThreadPool("Refresh authoritative invitations") {
            try {
                val invitations = flow.refresh()
                launchOnGLThread { showInvitations(invitations) }
            } catch (ex: Exception) {
                val (message) = LoadGameScreen.getLoadExceptionMessage(ex)
                launchOnGLThread { reuseWith(message, true) }
            }
        }
    }

    private fun showInvitations(invitations: List<ApiV3PlayerInvitation>) {
        clear()
        addGoodSizedLabel("Server invitations").row()
        if (invitations.isEmpty()) {
            addGoodSizedLabel("No pending invitations").row()
        } else {
            for (invitation in invitations) {
                val button =
                    "Accept [${invitation.invitedBy}] - [${invitation.gameId}]".toTextButton()
                button.onClick { accept(invitation, button) }
                add(button).growX().row()
            }
        }
        val refreshButton = "Refresh".toTextButton()
        refreshButton.onClick {
            clear()
            addGoodSizedLabel(Constants.working)
            refresh()
        }
        add(refreshButton).row()
        addCloseButton()
    }

    private fun accept(invitation: ApiV3PlayerInvitation, button: TextButton) {
        button.disable()
        Concurrency.runOnNonDaemonThreadPool("Accept authoritative invitation") {
            try {
                val accepted = flow.acceptAndOpen(invitation)
                launchOnGLThread {
                    close()
                    onAccepted(accepted)
                }
            } catch (ex: Exception) {
                val (message) = LoadGameScreen.getLoadExceptionMessage(ex)
                launchOnGLThread {
                    button.enable()
                    ToastPopup(message, screen)
                    // A stale invitation is intentionally refreshed before the
                    // user retries, rotating the command ID with its meaning.
                    refresh()
                }
            }
        }
    }
}

class AuthoritativeInvitePlayerPopup(
    private val screen: BaseScreen,
    private val coordinator: AuthoritativeInvitationCoordinator,
    private val gameId: String,
) : Popup(screen) {
    private val username = UncivTextField("Account username")
    private val inviteButton = "Invite player".toTextButton()

    init {
        addGoodSizedLabel("Invite an account to this server game").row()
        add(username).width(screen.stage.width / 2).row()
        inviteButton.onClick { invite() }
        add(inviteButton)
        addCloseButton("Cancel".tr())
    }

    private fun invite() {
        inviteButton.disable()
        Concurrency.runOnNonDaemonThreadPool("Invite authoritative player") {
            try {
                coordinator.invite(gameId, username.text)
                launchOnGLThread {
                    ToastPopup("Player invited", screen)
                    close()
                }
            } catch (ex: Exception) {
                val (message) = LoadGameScreen.getLoadExceptionMessage(ex)
                launchOnGLThread {
                    inviteButton.enable()
                    ToastPopup(message, screen)
                }
            }
        }
    }
}
