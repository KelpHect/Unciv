package com.unciv.ui.screens.pickerscreens

import com.badlogic.gdx.scenes.scene2d.Actor
import com.unciv.UncivGame
import com.unciv.GUI
import com.unciv.logic.civilization.Civilization
import com.unciv.logic.multiplayer.authoritative.AuthoritativeCommandOutcome
import com.unciv.models.UncivSound
import com.unciv.models.translations.tr
import com.unciv.ui.components.input.onClick
import com.unciv.ui.components.input.onDoubleClick
import com.unciv.ui.components.extensions.disable
import com.unciv.ui.components.extensions.enable
import com.unciv.ui.images.ImageGetter
import com.unciv.ui.popups.ToastPopup
import com.unciv.utils.Concurrency
import kotlinx.coroutines.CancellationException

class DiplomaticVotePickerScreen(private val votingCiv: Civilization) : PickerScreen() {
    private var chosenCiv: String? = null
    private var authoritativeSubmissionInProgress = false

    init {
        setDefaultCloseAction()
        rightSideButton.setText("Choose a civ to vote for".tr())

        descriptionLabel.setText("Choose who should become the world leader and win a Diplomatic Victory!".tr())

        val choosableCivs = votingCiv.diplomacyFunctions.getKnownCivsSorted(false)
        for (civ in choosableCivs) {
            addButton(civ.civName, "Vote for [${civ.civName}]", civ.civID,
                ImageGetter.getNationPortrait(
                    civ.nation,
                    PickerPane.pickerOptionIconSize
                )
            )
        }
        addButton("Abstain", "Abstain", null,
            ImageGetter.getImage("OtherIcons/Stop").apply {
                setSize(PickerPane.pickerOptionIconSize, PickerPane.pickerOptionIconSize)
            }
        )

        rightSideButton.onClick(UncivSound.Chimes, ::voteAndClose)
    }

    private fun voteAndClose() {
        if (isAuthoritativeGame()) {
            submitAuthoritativeVote()
            return
        }
        votingCiv.diplomaticVoteForCiv(chosenCiv)
        UncivGame.Current.popScreen()
    }

    private fun isAuthoritativeGame() = votingCiv.gameInfo.gameParameters.isOnlineMultiplayer &&
        game.onlineMultiplayer.authoritativeSession?.isGameOpen(votingCiv.gameInfo.gameId) == true

    private fun submitAuthoritativeVote() {
        if (authoritativeSubmissionInProgress) return
        authoritativeSubmissionInProgress = true
        rightSideButton.disable()
        Concurrency.runOnNonDaemonThreadPool("Cast authoritative diplomatic vote") {
            val outcome = try {
                game.onlineMultiplayer.authoritativeSession?.castDiplomaticVoteIfOpen(
                    votingCiv.gameInfo.gameId,
                    chosenCiv,
                )
            } catch (ex: Exception) {
                if (ex is CancellationException) throw ex
                Concurrency.runOnGLThread {
                    authoritativeSubmissionInProgress = false
                    rightSideButton.enable()
                    ToastPopup(
                        "Could not submit diplomatic vote: [${ex.message ?: "Unknown"}]",
                        this@DiplomaticVotePickerScreen,
                    )
                }
                return@runOnNonDaemonThreadPool
            }
            Concurrency.runOnGLThread {
                when (outcome) {
                    is AuthoritativeCommandOutcome.Accepted -> {
                        votingCiv.gameInfo.isUpToDate = false
                        game.popScreen()
                        ToastPopup("Diplomatic vote committed by the authoritative server", GUI.getWorldScreen())
                    }
                    is AuthoritativeCommandOutcome.StaleRefreshed -> {
                        votingCiv.gameInfo.isUpToDate = false
                        game.popScreen()
                        ToastPopup("Game changed on the server - diplomatic vote was not cast", GUI.getWorldScreen())
                    }
                    is AuthoritativeCommandOutcome.Rejected -> {
                        authoritativeSubmissionInProgress = false
                        rightSideButton.enable()
                        ToastPopup("Server rejected diplomatic vote: [${outcome.code}]", this@DiplomaticVotePickerScreen)
                    }
                    AuthoritativeCommandOutcome.RetryRequired -> {
                        authoritativeSubmissionInProgress = false
                        rightSideButton.enable()
                        ToastPopup("Server response was lost - retry will use the same vote", this@DiplomaticVotePickerScreen)
                    }
                    null -> {
                        authoritativeSubmissionInProgress = false
                        rightSideButton.enable()
                        ToastPopup("Authoritative game was closed before the vote", this@DiplomaticVotePickerScreen)
                    }
                }
            }
        }
    }

    private fun addButton(caption: String, pickText: String, choice: String?, icon: Actor) {
        val button = PickerPane.getPickerOptionButton(icon, caption)
        button.onClick {
            chosenCiv = choice
            pick(pickText.tr())
        }
        button.onDoubleClick(UncivSound.Chimes) {
            chosenCiv = choice
            voteAndClose()
        }
        topTable.add(button).fillX().pad(10f).row()
    }
}
