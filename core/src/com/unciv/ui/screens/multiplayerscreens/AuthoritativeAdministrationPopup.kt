package com.unciv.ui.screens.multiplayerscreens

import com.badlogic.gdx.scenes.scene2d.ui.TextButton
import com.unciv.logic.multiplayer.authoritative.ApiV3GameSummary
import com.unciv.logic.multiplayer.authoritative.AuthoritativeAdministrationCoordinator
import com.unciv.logic.multiplayer.authoritative.AuthoritativeCommandOutcome
import com.unciv.logic.multiplayer.authoritative.ApiV3Exception
import com.unciv.ui.components.extensions.disable
import com.unciv.ui.components.extensions.enable
import com.unciv.ui.components.extensions.toTextButton
import com.unciv.ui.components.input.onClick
import com.unciv.ui.components.widgets.UncivTextField
import com.unciv.ui.popups.ConfirmPopup
import com.unciv.ui.popups.Popup
import com.unciv.ui.popups.ToastPopup
import com.unciv.ui.screens.basescreen.BaseScreen
import com.unciv.ui.screens.savescreens.LoadGameScreen
import com.unciv.utils.Concurrency
import com.unciv.utils.launchOnGLThread

class AuthoritativeAdministrationPopup(
    private val screen: BaseScreen,
    private val gameSummary: ApiV3GameSummary,
    private val coordinator: AuthoritativeAdministrationCoordinator,
    private val onChanged: () -> Unit,
) : Popup(screen) {
    private val memberUsername = UncivTextField("Member account username")
    private val kickButton = "Kick player".toTextButton()
    private val inviteSpectatorButton = "Invite spectator".toTextButton()
    private val revokeSpectatorButton = "Revoke spectator".toTextButton()
    private val forceResignButton = "Force current player to resign".toTextButton()
    private val transferButton = "Transfer ownership".toTextButton()
    private val closeGameButton = "Close game".toTextButton()
    private val archiveGameButton = "Archive game".toTextButton()
    private val actionButtons =
        listOf(
            kickButton,
            inviteSpectatorButton,
            revokeSpectatorButton,
            forceResignButton,
            transferButton,
            closeGameButton,
            archiveGameButton,
        )

    init {
        require(
            gameSummary.role == "owner" &&
                gameSummary.lifecycleStatus in setOf("active", "closed"),
        ) {
            "Only an active or closed authoritative game owner can administer this game"
        }
        addGoodSizedLabel("Server game administration").row()
        addGoodSizedLabel(
            "Game [${gameSummary.gameId}] - revision [${gameSummary.committedRevision}]",
        ).row()
        add(memberUsername).width(screen.stage.width / 2).row()

        kickButton.onClick {
            confirm(
                "Kick [${memberUsername.text.trim()}] from this game?",
                "Kick",
            ) { kick() }
        }
        inviteSpectatorButton.onClick {
            confirm(
                "Invite [${memberUsername.text.trim()}] as a public-summary spectator?",
                "Invite spectator",
            ) { inviteSpectator() }
        }
        revokeSpectatorButton.onClick {
            confirm(
                "Revoke spectator access for [${memberUsername.text.trim()}]?",
                "Revoke spectator",
            ) { revokeSpectator() }
        }
        forceResignButton.onClick {
            confirm(
                "Force the canonical current player to resign? " +
                    "The server will reject this until its timeout has elapsed.",
                "Force resign",
            ) { forceResign() }
        }
        transferButton.onClick {
            confirm(
                "Transfer ownership to [${memberUsername.text.trim()}]?",
                "Transfer",
            ) { transfer() }
        }
        closeGameButton.onClick {
            confirm(
                "Close this game? No further gameplay commands will be accepted.",
                "Close game",
            ) { closeGame() }
        }
        archiveGameButton.onClick {
            confirm(
                "Archive this game and close its local server session?",
                "Archive game",
            ) { archiveGame() }
        }

        val active = gameSummary.lifecycleStatus == "active"
        kickButton.isVisible = active
        inviteSpectatorButton.isVisible = active
        revokeSpectatorButton.isVisible = true
        forceResignButton.isVisible = active
        transferButton.isVisible = active
        closeGameButton.isVisible = active
        archiveGameButton.isVisible = !active
        for (button in actionButtons) {
            if (button.isVisible) add(button).growX().row()
        }
        addCloseButton()
    }

    private fun confirm(message: String, confirmText: String, action: () -> Unit) {
        ConfirmPopup(screen, message, confirmText, action = action).open()
    }

    private fun kick() = runAction("Kick authoritative member") {
        when (val outcome = coordinator.kick(gameSummary.gameId, memberUsername.text)) {
            is AuthoritativeCommandOutcome.Accepted -> ActionResult()
            AuthoritativeCommandOutcome.RetryRequired ->
                ActionResult(
                    "Kick status is uncertain - retry the same action to confirm",
                    closePopup = false,
                )
            is AuthoritativeCommandOutcome.StaleRefreshed ->
                ActionResult("Game ownership or membership changed - refreshed")
            is AuthoritativeCommandOutcome.Rejected -> ActionResult(outcome.code)
        }
    }

    private fun transfer() = runAction("Transfer authoritative ownership") {
        coordinator.transfer(gameSummary.gameId, memberUsername.text)
        ActionResult()
    }

    private fun inviteSpectator() = runAction("Invite authoritative spectator") {
        coordinator.inviteSpectator(gameSummary.gameId, memberUsername.text)
        ActionResult()
    }

    private fun revokeSpectator() = runAction("Revoke authoritative spectator") {
        coordinator.revokeSpectator(gameSummary.gameId, memberUsername.text)
        ActionResult()
    }

    private fun forceResign() = runAction("Force authoritative resignation") {
        when (val outcome = coordinator.forceResign(gameSummary.gameId)) {
            is AuthoritativeCommandOutcome.Accepted -> ActionResult()
            AuthoritativeCommandOutcome.RetryRequired ->
                ActionResult(
                    "Force-resignation status is uncertain - retry to confirm",
                    closePopup = false,
                )
            is AuthoritativeCommandOutcome.StaleRefreshed ->
                ActionResult("Game changed on the server - refreshed", closePopup = false)
            is AuthoritativeCommandOutcome.Rejected ->
                ActionResult(outcome.code, closePopup = false)
        }
    }

    private fun closeGame() = runAction("Close authoritative game") {
        coordinator.close(gameSummary.gameId)
        ActionResult()
    }

    private fun archiveGame() = runAction("Archive authoritative game") {
        coordinator.archive(gameSummary.gameId)
        ActionResult()
    }

    private fun runAction(
        taskName: String,
        action: suspend () -> ActionResult,
    ) {
        setButtonsEnabled(false)
        Concurrency.runOnNonDaemonThreadPool(taskName) {
            try {
                val result = action()
                launchOnGLThread {
                    setButtonsEnabled(true)
                    onChanged()
                    if (result.message == null) {
                        ToastPopup("Server game administration updated", screen)
                    } else {
                        ToastPopup(result.message, screen)
                    }
                    if (result.closePopup) close()
                }
            } catch (ex: Exception) {
                val (message) = LoadGameScreen.getLoadExceptionMessage(ex)
                launchOnGLThread {
                    setButtonsEnabled(true)
                    onChanged()
                    ToastPopup(message, screen)
                    if (ex is ApiV3Exception) close()
                }
            }
        }
    }

    private fun setButtonsEnabled(enabled: Boolean) {
        for (button: TextButton in actionButtons) {
            if (enabled) button.enable() else button.disable()
        }
    }

    private data class ActionResult(
        val message: String? = null,
        val closePopup: Boolean = true,
    )
}
