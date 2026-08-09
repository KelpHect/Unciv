package com.unciv.ui.screens.multiplayerscreens

import com.badlogic.gdx.scenes.scene2d.ui.Table
import com.unciv.Constants
import com.unciv.logic.multiplayer.authoritative.ApiV3GameSummary
import com.unciv.logic.multiplayer.authoritative.ApiV3SpectatorGameProjection
import com.unciv.logic.multiplayer.authoritative.AuthoritativeMultiplayerSession
import com.unciv.logic.multiplayer.authoritative.SpectatorProjection
import com.unciv.ui.components.extensions.disable
import com.unciv.ui.components.extensions.enable
import com.unciv.ui.components.extensions.toLabel
import com.unciv.ui.components.extensions.toTextButton
import com.unciv.ui.components.input.onClick
import com.unciv.ui.components.widgets.AutoScrollPane
import com.unciv.ui.popups.ToastPopup
import com.unciv.ui.screens.pickerscreens.PickerScreen
import com.unciv.ui.screens.savescreens.LoadGameScreen
import com.unciv.utils.Concurrency
import com.unciv.utils.launchOnGLThread

/**
 * Read-only live view for an invited spectator. The server intentionally sends
 * only public match status, so this screen never attempts to construct a map or
 * reuse a player's fog-of-war projection.
 */
class AuthoritativeSpectatorScreen(
    private val gameSummary: ApiV3GameSummary,
    initialProjection: ApiV3SpectatorGameProjection,
    private val session: AuthoritativeMultiplayerSession,
) : PickerScreen() {
    private var projection = initialProjection
    private var busy = false
    private var secondsSinceRefresh = 0f

    private val content = Table().apply {
        top()
        defaults().pad(6f)
    }

    init {
        require(initialProjection.gameId == gameSummary.gameId) {
            "Spectator projection does not match the selected game"
        }
        require(initialProjection.projectionVersion == SpectatorProjection.CURRENT_PROJECTION_VERSION) {
            "Spectator projection uses an incompatible version"
        }
        setDefaultCloseAction()
        rightSideButton.setText("Leave spectator")
        rightSideButton.onClick { leaveSpectator() }
        topTable.add(AutoScrollPane(content)).grow().row()
        rebuild()
    }

    override fun render(delta: Float) {
        super.render(delta)
        secondsSinceRefresh += delta
        if (secondsSinceRefresh >= REFRESH_INTERVAL_SECONDS && !busy) {
            secondsSinceRefresh = 0f
            refreshProjection()
        }
    }

    private fun rebuild() {
        content.clearChildren()
        content.add(LobbyChrome.caption("Live spectator view")).growX().left().row()
        content.add(LobbyChrome.title(gameSummary.displayName)).growX().left().row()
        content.add(
            LobbyChrome.hint(
                "Read-only public summary  •  revision [${projection.committedRevision}]",
            ),
        ).growX().left().row()
        content.add(
            "Turn ${projection.projection.turn}  •  Current player: " +
                projection.projection.currentPlayerCivilizationId,
        ).growX().left().row()

        projection.projection.victory?.let { victory ->
            content.add(
                "${victory.winningCivilizationId} won by ${victory.victoryType} " +
                    "on turn ${victory.victoryTurn}".toLabel(LobbyChrome.ready),
            ).growX().left().row()
        }

        val civTable = Table().apply {
            defaults().pad(5f)
            add("Civilization".toLabel()).left()
            add("Control".toLabel()).left()
            add("Status".toLabel()).left().row()
            for (civ in projection.projection.majorCivilizations) {
                add(civ.displayName.toLabel(hideIcons = true)).left()
                add(if (civ.humanControlled) "Human" else "AI").left()
                add(if (civ.defeated) "Defeated" else "Alive").left().row()
            }
        }
        content.add(civTable).growX().left().row()
        content.add(
            LobbyChrome.hint("The server refreshes this public summary automatically."),
        ).growX().left().row()
        if (busy) {
            content.add(Constants.working.toLabel()).growX().left().row()
            rightSideButton.disable()
        } else {
            rightSideButton.enable()
        }
    }

    private fun refreshProjection() {
        if (busy) return
        busy = true
        rebuild()
        Concurrency.runOnNonDaemonThreadPool("Refresh authoritative spectator projection") {
            try {
                val refreshed = session.spectatorProjection(gameSummary.gameId)
                require(refreshed.gameId == gameSummary.gameId) {
                    "Server returned a spectator projection for another game"
                }
                require(refreshed.projectionVersion == SpectatorProjection.CURRENT_PROJECTION_VERSION) {
                    "Server returned an incompatible spectator projection"
                }
                launchOnGLThread {
                    projection = refreshed
                    busy = false
                    secondsSinceRefresh = 0f
                    rebuild()
                }
            } catch (ex: Exception) {
                val (message) = LoadGameScreen.getLoadExceptionMessage(ex)
                launchOnGLThread {
                    busy = false
                    rebuild()
                    ToastPopup(message, this@AuthoritativeSpectatorScreen)
                }
            }
        }
    }

    private fun leaveSpectator() {
        if (busy) return
        busy = true
        rebuild()
        Concurrency.runOnNonDaemonThreadPool("Leave authoritative spectator view") {
            try {
                session.leaveSpectator(gameSummary.gameId)
                launchOnGLThread {
                    busy = false
                    game.popScreen()
                }
            } catch (ex: Exception) {
                val (message) = LoadGameScreen.getLoadExceptionMessage(ex)
                launchOnGLThread {
                    busy = false
                    rebuild()
                    ToastPopup(message, this@AuthoritativeSpectatorScreen)
                }
            }
        }
    }

    companion object {
        private const val REFRESH_INTERVAL_SECONDS = 5f
    }
}
