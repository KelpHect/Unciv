package com.unciv.ui.screens.multiplayerscreens

import com.unciv.logic.map.HexCoord
import com.unciv.logic.map.tile.Tile
import com.unciv.logic.multiplayer.authoritative.ApiV3GameProjection
import com.unciv.logic.multiplayer.authoritative.ApiV3GameSummary
import com.unciv.logic.multiplayer.authoritative.AuthoritativeGameDirectory
import com.unciv.logic.multiplayer.authoritative.AuthoritativeMultiplayerSession
import com.unciv.logic.multiplayer.authoritative.AuthoritativeWorldController
import com.unciv.logic.multiplayer.authoritative.AuthoritativeWorldStatus
import com.unciv.logic.multiplayer.authoritative.OpenedAuthoritativeGame
import com.unciv.models.ruleset.Ruleset
import com.unciv.ui.components.extensions.disable
import com.unciv.ui.components.extensions.enable
import com.unciv.ui.components.extensions.toLabel
import com.unciv.ui.components.extensions.toTextButton
import com.unciv.ui.components.input.onClick
import com.unciv.ui.popups.ToastPopup
import com.unciv.ui.screens.pickerscreens.PickerScreen
import com.unciv.ui.screens.savescreens.LoadGameScreen
import com.unciv.utils.Concurrency
import com.unciv.utils.launchOnGLThread
import com.unciv.ui.components.widgets.AutoScrollPane as ScrollPane

/**
 * API-v3 world surface backed exclusively by a player projection.
 *
 * It intentionally shares no WorldScreen/GameInfo objects. The first supported
 * inputs are projected unit selection/movement and end turn; unavailable
 * actions remain absent rather than falling through to local mutation.
 */
class AuthoritativeWorldScreen(
    private val gameSummary: ApiV3GameSummary,
    private val directory: AuthoritativeGameDirectory,
    initialProjection: ApiV3GameProjection,
    session: AuthoritativeMultiplayerSession,
    /** The server manifest's ruleset, resolved by the caller off the GL thread. */
    private val ruleset: Ruleset,
) : PickerScreen(disableScroll = true) {
    private val controller = AuthoritativeWorldController(
        initialProjection,
        refreshProjection = {
            val opened = directory.open(gameSummary)
            require(opened is OpenedAuthoritativeGame.Player) {
                "Player world cannot refresh through a spectator projection"
            }
            opened.projection
        },
        retryPending = { session.retryPendingIfOpen(gameSummary.gameId) },
        moveUnit = { unitId, x, y -> session.moveUnitIfOpen(gameSummary.gameId, unitId, x, y) },
        endTurn = { session.endTurnIfOpen(gameSummary.gameId) },
        setResearch = { technology, append ->
            session.setResearchPathIfOpen(gameSummary.gameId, technology, append)
        },
        manageResearch = { technology, index, action ->
            session.manageResearchQueueIfOpen(gameSummary.gameId, technology, index, action)
        },
        adoptPolicy = { policy ->
            session.adoptPolicyIfOpen(gameSummary.gameId, policy)
        },
        chooseFreeTechnology = { technology ->
            session.chooseFreeTechnologyIfOpen(gameSummary.gameId, technology)
        },
        acknowledgeResearchCompletion = { promptId ->
            session.acknowledgeResearchCompletionIfOpen(gameSummary.gameId, promptId)
        },
        cityEconomyActions = authoritativeCityEconomyActions(session, gameSummary.gameId),
        cityControlActions = authoritativeCityControlActions(session, gameSummary.gameId),
        combatActions = authoritativeCombatActions(session, gameSummary.gameId),
        unitActionActions = authoritativeUnitActions(session, gameSummary.gameId),
        unitOrderActions = authoritativeUnitOrderActions(session, gameSummary.gameId),
        promptActions = authoritativePromptActions(session, gameSummary.gameId),
        spyActions = authoritativeSpyActions(session, gameSummary.gameId),
        religionAction = { beliefs, icon, name ->
            session.chooseReligiousBeliefsIfOpen(
                gameSummary.gameId, beliefs, icon, name,
            )
        },
        diplomacyActions = authoritativeDiplomacyActions(session, gameSummary.gameId),
        tradeActions = authoritativeTradeActions(session, gameSummary.gameId),
    )

    /**
     * Rebuilt from each accepted projection. The hex map is drawn by the game's
     * real renderer over this disposable cache, so the online world looks and
     * pans exactly like a local one without ever holding canonical state.
     */
    private var world = AuthoritativeProjectionWorldMap(controller.projection, ruleset)
    private var mapHolder = AuthoritativeProjectionMapHolder(this, world, ::tileClicked)
    private var renderedRevision = controller.current.committedRevision

    @Volatile private var busy = false
    private var secondsSinceRefresh = 0f

    init {
        setDefaultCloseAction()
        rightSideButton.setText("End turn")
        rightSideButton.onClick { submitEndTurn() }
        rebuild()
        initialCenter()?.let(mapHolder::setCenterPosition)
        // Without this the map never receives scroll events, so it cannot zoom.
        stage.scrollFocus = mapHolder
    }

    override fun render(delta: Float) {
        super.render(delta)
        secondsSinceRefresh += delta
        if (secondsSinceRefresh >= REFRESH_INTERVAL_SECONDS && !busy) {
            secondsSinceRefresh = 0f
            refreshProjection(silent = true)
        }
    }

    private fun rebuild() {
        topTable.clear()
        topTable.add(
            (
                "Server game [${controller.current.gameId}] - " +
                    "revision [${controller.current.committedRevision}] - " +
                    "turn [${controller.projection.turn}]"
                ).toLabel(),
        ).colspan(3).row()
        rebuildMapIfProjectionReplaced()
        topTable.add(mapHolder).colspan(3).grow().maxHeight(stage.height * 0.62f).row()
        topTable.add(ScrollPane(
            AuthoritativeWorldDecisions(
                controller,
                busy || !controller.canAcceptProjectedInput,
                selectUnitTarget = { mode ->
                    controller.beginUnitTargetSelection(mode)
                    rebuild()
                },
            ) { taskName, operation ->
                runOperation(taskName, operation = operation)
            }.build(),
        ).apply {
            setScrollingDisabled(false, false)
            setOverscroll(false, false)
        }).colspan(3).growX().maxHeight(stage.height * 0.22f).row()
        if (controller.status == AuthoritativeWorldStatus.RetryRequired) {
            val retry = "Retry uncertain action".toTextButton()
            retry.onClick {
                runOperation("Retry authoritative action") {
                    controller.retryUncertainCommand()
                }
            }
            topTable.add(retry).colspan(3).row()
        } else {
            val refreshLabel =
                if (controller.status == AuthoritativeWorldStatus.OfflineCached)
                    "Reconnect and replace cached projection"
                else "Refresh server projection"
            val refresh = refreshLabel.toTextButton()
            refresh.onClick { refreshProjection(silent = false) }
            topTable.add(refresh).colspan(3).row()
        }

        if (controller.canEndTurn() && !busy) rightSideButton.enable()
        else rightSideButton.disable()
        descriptionLabel.setText(description())
    }

    /**
     * The projection is immutable and replaced wholesale, so the rendered map is
     * rebuilt only when the server actually committed a new revision. Rebuilding
     * on every UI refresh would throw away the player's pan and zoom.
     */
    private fun rebuildMapIfProjectionReplaced() {
        val revision = controller.current.committedRevision
        if (revision == renderedRevision) return
        renderedRevision = revision
        val restoredCenter = mapHolder.centerPosition()
        world = AuthoritativeProjectionWorldMap(controller.projection, ruleset)
        mapHolder = AuthoritativeProjectionMapHolder(this, world, ::tileClicked)
        restoredCenter?.let(mapHolder::setCenterPosition)
        stage.scrollFocus = mapHolder
    }

    private fun tileClicked(tile: Tile) {
        if (busy) return
        val x = tile.position.x
        val y = tile.position.y
        if (controller.unitTargetMode != null) {
            if (controller.canSubmitUnitTarget(x, y)) {
                runOperation("Submit authoritative unit target") {
                    controller.submitUnitTarget(x, y)
                }
            }
            return
        }
        val ownUnit = controller.projection.ownUnits.firstOrNull { it.x == x && it.y == y }
        if (ownUnit != null) {
            controller.selectUnit(ownUnit.id)
            rebuild()
            return
        }
        if (controller.canMoveSelectedTo(x, y)) submitMove(x, y)
    }

    private fun submitMove(x: Int, y: Int) = runOperation("Move authoritative unit") {
        controller.moveSelectedTo(x, y)
    }

    private fun submitEndTurn() = runOperation("End authoritative turn") {
        controller.submitEndTurn()
    }

    private fun refreshProjection(silent: Boolean) =
        runOperation("Refresh authoritative world", silent) { controller.refresh() }

    private fun runOperation(
        taskName: String,
        silent: Boolean = false,
        operation: suspend () -> Unit,
    ) {
        if (busy) return
        busy = true
        rebuild()
        Concurrency.runOnNonDaemonThreadPool(taskName) {
            try {
                operation()
                launchOnGLThread {
                    busy = false
                    secondsSinceRefresh = 0f
                    rebuild()
                    if (!silent && controller.status !is AuthoritativeWorldStatus.Synchronized)
                        ToastPopup(statusText(), this@AuthoritativeWorldScreen)
                }
            } catch (ex: Exception) {
                val (message) = LoadGameScreen.getLoadExceptionMessage(ex)
                launchOnGLThread {
                    busy = false
                    rebuild()
                    ToastPopup(message, this@AuthoritativeWorldScreen)
                }
            }
        }
    }

    private fun description(): String {
        val selected = controller.selectedUnit()
        val selection = if (selected == null) "Select one of your projected units"
        else "${selected.name} #${selected.id} - ${selected.health} health - " +
            "${requireNotNull(selected.currentMovement)} movement"
        val blockers = controller.projection.pendingTurnActions
            .joinToString { it.name }
            .ifEmpty { "none" }
        val targetMode = controller.unitTargetMode?.let {
            "\nTarget selection: ${it.name}"
        }.orEmpty()
        return "$selection$targetMode\nEnd-turn requirements: $blockers\nStatus: ${statusText()}"
    }

    private fun statusText(): String = when (val status = controller.status) {
        AuthoritativeWorldStatus.Synchronized -> "Synchronized"
        AuthoritativeWorldStatus.Refreshing -> "Refreshing"
        AuthoritativeWorldStatus.Submitting -> "Submitting"
        AuthoritativeWorldStatus.RetryRequired -> "Response uncertain - retry the same action"
        AuthoritativeWorldStatus.OfflineCached ->
            "Offline - cached projection is read-only until server refresh succeeds"
        AuthoritativeWorldStatus.StaleRefreshed -> "Stale projection refreshed"
        is AuthoritativeWorldStatus.Rejected -> "Rejected: ${status.code}"
    }

    private fun initialCenter(): HexCoord? {
        val projection = controller.projection
        val start = projection.ownCities.firstOrNull()?.let { it.x to it.y }
            ?: projection.ownUnits.firstOrNull()?.let { it.x to it.y }
            ?: projection.exploredTiles.firstOrNull()?.let { it.x to it.y }
            ?: return null
        return HexCoord(start.first, start.second)
    }

    companion object {
        private const val REFRESH_INTERVAL_SECONDS = 5f
    }
}
