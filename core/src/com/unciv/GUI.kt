package com.unciv

import com.badlogic.gdx.Gdx
import com.badlogic.gdx.Input
import com.unciv.logic.civilization.Civilization
import com.unciv.models.metadata.GameSettings
import com.unciv.ui.screens.basescreen.BaseScreen
import com.unciv.ui.screens.worldscreen.UndoHandler.Companion.clearUndoCheckpoints
import com.unciv.ui.screens.worldscreen.worldmap.WorldMapHolder
import com.unciv.ui.screens.worldscreen.WorldHudHost
import com.unciv.ui.screens.worldscreen.WorldScreen
import com.unciv.ui.screens.worldscreen.unit.UnitTable
import yairm210.purity.annotations.Readonly

object GUI {

    /**
     * The HUD host driving the screen when there is no WorldScreen.
     *
     * Set by an API-v3 world screen while it is active and cleared when it is
     * not. Single-player leaves it null and keeps answering from its
     * WorldScreen, so nothing about that path changes.
     */
    @Volatile var hudHost: WorldHudHost? = null

    fun setUpdateWorldOnNextRender() {
        UncivGame.Current.worldScreen?.shouldUpdate = true
    }

    fun pushScreen(screen: BaseScreen) {
        UncivGame.Current.pushScreen(screen)
    }

    fun resetToWorldScreen() {
        UncivGame.Current.resetToWorldScreen()
    }

    @Readonly fun getSettings(): GameSettings = UncivGame.Current.settings

    @Readonly fun isWorldLoaded(): Boolean = UncivGame.Current.worldScreen != null

    @Readonly
    fun isMyTurn(): Boolean {
        if (!UncivGame.isCurrentInitialized() || !isWorldLoaded()) return false
        return UncivGame.Current.worldScreen!!.isPlayersTurn
    }

    /**
     * Whether the active screen currently accepts state-changing input.
     *
     * A single-player game answers from its WorldScreen exactly as before. An
     * API-v3 client has no WorldScreen at all, so the question goes to whichever
     * screen is hosting the HUD, and defaults to `false` - a client that cannot
     * say "yes" must never be assumed to be allowed to change anything.
     */
    @Readonly fun isAllowedChangeState(): Boolean =
        UncivGame.Current.worldScreen?.canChangeState ?: hudHost?.hudCanChangeState ?: false

    @Readonly fun getWorldScreen(): WorldScreen = UncivGame.Current.worldScreen!!

    @Readonly fun getWorldScreenIfActive(): WorldScreen? = UncivGame.Current.getWorldScreenIfActive()

    @Readonly fun getMap(): WorldMapHolder = UncivGame.Current.worldScreen!!.mapHolder

    @Readonly fun getUnitTable(): UnitTable = UncivGame.Current.worldScreen!!.bottomUnitTable

    @Readonly fun getViewingPlayer(): Civilization = UncivGame.Current.worldScreen!!.viewingCiv

    @Readonly fun getSelectedPlayer(): Civilization = UncivGame.Current.worldScreen!!.selectedCiv

    /** Disable Undo (as in: forget the way back, but allow future undo checkpoints) */
    fun clearUndoCheckpoints() {
        UncivGame.Current.worldScreen?.clearUndoCheckpoints()
    }

    /** Fallback in case you have no easy access to a BaseScreen that knows which Ruleset Civilopedia should display.
     *  If at all possible, use [BaseScreen.openCivilopedia] instead. */
    fun openCivilopedia(link: String = "") {
        UncivGame.Current.screen?.openCivilopedia(link)
    }

    private var keyboardAvailableCache: Boolean? = null
    /** Tests availability of a physical keyboard - cached (connecting a keyboard while the game is running won't be recognized until relaunch) */
    val keyboardAvailable: Boolean
        get() {
            // defer decision if Gdx.input not yet initialized
            if (keyboardAvailableCache == null && Gdx.input != null)
                keyboardAvailableCache = Gdx.input.isPeripheralAvailable(Input.Peripheral.HardwareKeyboard)
            return keyboardAvailableCache ?: false
        }

}
