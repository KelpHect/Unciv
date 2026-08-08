package com.unciv.ui.screens.multiplayerscreens

import com.unciv.logic.multiplayer.authoritative.ApiV3PublicMatchSummary
import com.unciv.logic.multiplayer.authoritative.AuthoritativeMultiplayerSession
import com.unciv.ui.components.extensions.toLabel
import com.unciv.ui.components.extensions.toTextButton
import com.unciv.ui.components.input.onActivation
import com.unciv.ui.components.widgets.AutoScrollPane
import com.unciv.ui.popups.Popup
import com.unciv.ui.screens.multiplayerscreens.LobbyChrome.card

/**
 * Popup listing public matches. Clicking a match opens the YouTube-style
 * replay screen for step-by-step playback.
 */
class AuthoritativePublicMatchesPopup(
    screen: com.unciv.ui.screens.basescreen.BaseScreen,
    private val matches: List<ApiV3PublicMatchSummary>,
    private val session: AuthoritativeMultiplayerSession,
) : Popup(screen) {

    init {
        add("Public matches".toLabel()).padBottom(10f).row()
        val list = com.badlogic.gdx.scenes.scene2d.ui.Table()
        for (match in matches) {
            val row = card().apply {
                add(match.displayName.toLabel()).left().growX().padRight(10f)
                add("Rev ${match.headRevision}".toLabel()).right().padRight(10f)
                add(match.lifecycleStatus.toLabel()).right().padRight(10f)
                row()
                add(
                    "Watch replay".toTextButton().onActivation {
                        close()
                        screen.game.pushScreen(
                            AuthoritativeReplayScreen(session, match.gameId),
                        )
                    },
                ).left().padTop(4f)
            }
            list.add(row).growX().padBottom(6f).row()
        }
        add(AutoScrollPane(list)).grow().row()
        addCloseButton()
    }
}
