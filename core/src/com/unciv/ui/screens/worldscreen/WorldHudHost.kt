package com.unciv.ui.screens.worldscreen

import com.unciv.logic.map.tile.Tile
import com.unciv.ui.screens.basescreen.BaseScreen
import com.unciv.view.GameView

/**
 * What a world-screen HUD widget actually needs from the screen hosting it.
 *
 * The singleplayer HUD widgets were written against [WorldScreen] itself, which
 * carries a canonical `GameInfo`. An API-v3 client has no `GameInfo` at all - it
 * holds a server projection - so it could never reuse them, and grew a parallel
 * list of text buttons instead.
 *
 * This is the seam between the two. It is deliberately the *narrow* set of
 * things presentation widgets read, not a [WorldScreen] lookalike: a screen to
 * put popups on, the view the widget renders, and a debug-only image lookup.
 * Anything a widget needs beyond this - turn state, empire stats, unit orders -
 * is authority, and belongs behind a typed command, not behind this interface.
 */
interface WorldHudHost {
    /** The screen these widgets live on, for popups and screen pushes. */
    val hudScreen: BaseScreen

    /** The view the HUD renders. Never a `GameInfo`. */
    val hudGameView: GameView

    /**
     * Base image names composing [tile], for [com.unciv.utils.DebugUtils.SHOW_TILE_IMAGE_LOCATIONS].
     * Empty when the host cannot answer, which must never be fatal - this is a
     * developer overlay, not gameplay.
     */
    fun tileImageNames(tile: Tile): List<String> = emptyList()
}
