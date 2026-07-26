package com.unciv.ui.screens.multiplayerscreens

import com.badlogic.gdx.scenes.scene2d.ui.Table
import com.badlogic.gdx.scenes.scene2d.ui.TextButton
import com.badlogic.gdx.scenes.scene2d.ui.VerticalGroup
import com.unciv.logic.multiplayer.authoritative.ApiV3GameSummary
import com.unciv.models.translations.tr
import com.unciv.ui.components.input.onClick
import com.unciv.ui.screens.basescreen.BaseScreen

/**
 * Projection-backed API-v3 directory display.
 *
 * Unlike [GameList], this never reads a local multiplayer preview or save.
 */
class AuthoritativeGameList(
    private val onSelected: (ApiV3GameSummary) -> Unit,
) : VerticalGroup() {
    init {
        padTop(10f)
        padBottom(10f)
    }

    fun update(games: List<ApiV3GameSummary>) {
        clearChildren()
        for (game in games.sortedWith(GAME_ORDER)) {
            addActor(AuthoritativeGameDisplay(game, onSelected))
        }
    }

    private companion object {
        val GAME_ORDER = compareBy<ApiV3GameSummary>(
            { it.lifecycleStatus != "active" },
            { !it.available },
            { it.gameId },
        )
    }
}

private class AuthoritativeGameDisplay(
    game: ApiV3GameSummary,
    onSelected: (ApiV3GameSummary) -> Unit,
) : Table() {
    init {
        padBottom(5f)
        val availability = if (game.available) "" else " - unavailable".tr()
        val label = "${game.gameId} [${game.role.tr()}]$availability"
        val gameButton = TextButton(label, BaseScreen.skin)
        add(gameButton)
        onClick { onSelected(game) }
    }
}
