package com.unciv.ui.screens.multiplayerscreens

import com.unciv.logic.multiplayer.authoritative.ApiV3Exception
import com.unciv.logic.multiplayer.authoritative.ApiV3GameSummary
import com.unciv.logic.multiplayer.authoritative.ApiV3RewindStatus
import com.unciv.logic.multiplayer.authoritative.AuthoritativeMultiplayerSession
import com.unciv.ui.components.extensions.toTextButton
import com.unciv.ui.components.input.onClick
import com.unciv.ui.popups.ConfirmPopup
import com.unciv.ui.popups.Popup
import com.unciv.ui.popups.ToastPopup
import com.unciv.ui.screens.basescreen.BaseScreen
import com.unciv.ui.screens.savescreens.LoadGameScreen
import com.unciv.utils.Concurrency
import com.unciv.utils.launchOnGLThread

/** Projection-only UI for unanimous whole-game start-of-turn rewind. */
class AuthoritativeRewindPopup(
    private val screen: BaseScreen,
    private val game: ApiV3GameSummary,
    private val session: AuthoritativeMultiplayerSession,
    private val onChanged: () -> Unit,
) : Popup(screen) {
    init {
        require(game.role in setOf("owner", "player") && game.lifecycleStatus == "active")
        addGoodSizedLabel("Loading server turn-start checkpoints...").row()
        addCloseButton()
        load()
    }

    private fun load() {
        Concurrency.runOnNonDaemonThreadPool("Load authoritative rewind") {
            try {
                val status = session.currentRewind(game.gameId)
                val checkpoints = session.rewindCheckpoints(game.gameId)
                launchOnGLThread {
                    clear()
                    addGoodSizedLabel(
                        "This restores every human, AI, map and random outcome to the turn start.",
                    ).row()
                    if (status?.status == "pending") addPending(status)
                    else {
                        if (checkpoints.isEmpty())
                            addGoodSizedLabel("No earlier turn-start checkpoint is available.").row()
                        for (checkpoint in checkpoints) {
                            val button = "Propose start of turn [${checkpoint.turn}]".toTextButton()
                            button.onClick {
                                ConfirmPopup(
                                    screen,
                                    "Ask every human player to rewind the whole game to the " +
                                        "start of turn [${checkpoint.turn}]?",
                                    "Propose rewind",
                                    action = {
                                        perform {
                                            session.proposeRewind(
                                                game.gameId,
                                                game.committedRevision,
                                                checkpoint.revision,
                                            )
                                        }
                                    },
                                ).open()
                            }
                            add(button).growX().row()
                        }
                    }
                    addCloseButton()
                    pack()
                }
            } catch (error: Exception) {
                showFailure(error)
            }
        }
    }

    private fun addPending(status: ApiV3RewindStatus) {
        addGoodSizedLabel(
            "Checkpoint revision [${status.targetRevision}]: " +
                "[${status.approvals}]/[${status.requiredApprovals}] approvals.",
        ).row()
        if (status.actorApproved == null) {
            val approve = "Approve whole-game rewind".toTextButton()
            val reject = "Reject rewind".toTextButton()
            approve.onClick { perform { session.voteRewind(game.gameId, status.requestId, true) } }
            reject.onClick { perform { session.voteRewind(game.gameId, status.requestId, false) } }
            add(approve).growX().row()
            add(reject).growX().row()
        } else {
            addGoodSizedLabel("Your vote: [${if (status.actorApproved) "approve" else "reject"}]").row()
        }
    }

    private fun perform(action: suspend () -> ApiV3RewindStatus) {
        Concurrency.runOnNonDaemonThreadPool("Vote authoritative rewind") {
            try {
                val result = action()
                launchOnGLThread {
                    onChanged()
                    ToastPopup(
                        when (result.status) {
                            "applied" -> "Whole game restored to the selected turn start"
                            "rejected" -> "Rewind rejected"
                            "stale" -> "Game changed; rewind request expired"
                            else -> "Rewind vote recorded"
                        },
                        screen,
                    )
                    close()
                }
            } catch (error: Exception) {
                showFailure(error)
            }
        }
    }

    private fun showFailure(error: Exception) {
        val message = if (error is ApiV3Exception) error.error.code
        else LoadGameScreen.getLoadExceptionMessage(error).first
        Concurrency.runOnGLThread {
            ToastPopup(message, screen)
            close()
        }
    }
}
