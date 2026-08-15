package com.unciv.ui.screens.multiplayerscreens

import com.badlogic.gdx.scenes.scene2d.ui.Table
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
import com.unciv.ui.screens.worldscreen.WorldHudHost
import com.unciv.ui.screens.worldscreen.bottombar.TileInfoTable
import com.unciv.ui.screens.worldscreen.unit.UnitTable
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
) : PickerScreen(disableScroll = true), WorldHudHost {
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

    override val hudScreen get() = this
    override val hudGameView get() = world.gameView
    override val hudViewingCiv get() = world.viewer

    /** A widget asking to redraw becomes a rebuild on the next render. */
    override var hudShouldUpdate = false

    /**
     * The server decides this, not the client: input is accepted only while the
     * projection says this player may act, and never mid-submission.
     */
    override val hudCanChangeState get() = controller.canAcceptProjectedInput && !busy

    override fun hudCenterOn(position: HexCoord) = mapHolder.setCenterPosition(position)

    /** Civilopedia must describe the server's pinned ruleset, not a guessed one. */
    override fun getCivilopediaRuleset() = ruleset

    /** The tile whose readout is shown, independent of unit selection. */
    private var inspectedTile: Tile? = null
    private val tileInfoTable = TileInfoTable(this)

    /**
     * The game's own unit table: selection, unit info, promotions and idle-unit
     * cycling, over units materialized from the projection. Its orders are not
     * from here - the singleplayer action row mutates GameInfo locally, which V3
     * forbids, so the buttons below it come from what the server advertised.
     */
    private val unitTable = UnitTable(this)
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
        // A widget (idle-unit cycling, the table's close button) asked to redraw.
        if (hudShouldUpdate && !busy) {
            hudShouldUpdate = false
            rebuild()
        }
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
        // The game's own tile readout, over the projection - same stats, same
        // description, same Civilopedia links a single-player game shows.
        tileInfoTable.updateTileTable(inspectedTile)
        topTable.add(tileInfoTable).colspan(3).row()
        unitTable.update()
        topTable.add(unitTable).colspan(3).row()
        // Orders for the selected unit: advertised by the server on the
        // projection, dispatched through the same typed command bus as before.
        topTable.add(unitOrdersForSelection()).colspan(3).row()
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
        // The old map is discarded wholesale, so anything holding one of its
        // tiles or views would render state the server has already replaced.
        tileInfoTable.civView = world.gameView.civView
        // Every MapUnit is a new object after a revision, so the table would
        // otherwise hold one belonging to the discarded map.
        unitTable.selectUnit(controller.selectedUnitId?.let(::materializedUnit))
        inspectedTile = inspectedTile?.position?.let {
            world.tileMap.getIfTileExistsOrNull(it.x, it.y)
        }
    }

    private fun tileClicked(tile: Tile) {
        if (busy) return
        val x = tile.position.x
        val y = tile.position.y
        // Inspecting a tile is a read, so it happens on every tap regardless of
        // whether the tap also turns out to be a unit selection or an order.
        inspectedTile = tile
        if (controller.unitTargetMode != null) {
            if (controller.canSubmitUnitTarget(x, y)) {
                runOperation("Submit authoritative unit target") {
                    controller.submitUnitTarget(x, y)
                }
            // Tapping somewhere that is not a legal target submits nothing, but
            // it is still an inspection, so the readout has to redraw.
            } else rebuild()
            return
        }
        val ownUnit = controller.projection.ownUnits.any { it.x == x && it.y == y }
        if (ownUnit) {
            // The game's own selection order - military before civilian, re-tap
            // to deselect - instead of whichever unit the projection lists first.
            // A unit that failed to materialize is simply not on the tile, so it
            // cannot be selected into a state the controller then has to undo.
            unitTable.tileSelected(tile)
            syncControllerSelection()
            rebuild()
            return
        }
        if (controller.canMoveSelectedTo(x, y)) submitMove(x, y)
        // A tap that is only an inspection still has to redraw the readout.
        else rebuild()
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

    /** The materialized unit for a projected id, on the current map. */
    private fun materializedUnit(id: Int) = world.viewer.units.getUnitById(id)

    /**
     * Makes the controller agree with whatever the unit table now has selected.
     *
     * The table changes selection on its own - idle-unit cycling, its close
     * button, the game's tile-selection order - so the controller follows it
     * rather than the other way round.
     */
    private fun syncControllerSelection() {
        val tableSelection = unitTable.selectedUnit?.id
        if (tableSelection == controller.selectedUnitId) return
        // selectUnit refuses a unit the projection does not advertise, so a
        // table selection the server does not know about clears instead.
        if (tableSelection != null &&
            controller.projection.ownUnits.any { it.id == tableSelection }
        ) controller.selectUnit(tableSelection)
        else controller.deselectUnit()
    }

    /**
     * The unit table can change the selection on its own (idle-unit cycling and
     * its close button), so the controller is resynchronized from it before the
     * order buttons are built for whatever is now selected.
     */
    private fun unitOrdersForSelection(): Table {
        // A no-op when the two already agree, so starting a target selection and
        // redrawing does not cancel it. When they genuinely differ the table has
        // moved to another unit, and dropping the stale target mode is correct.
        syncControllerSelection()
        val disabled = busy || !controller.canAcceptProjectedInput
        val submit: (String, suspend () -> Unit) -> Unit = { taskName, operation ->
            runOperation(taskName, operation = operation)
        }
        return Table().apply {
            defaults().pad(3f)
            add(AuthoritativeUnitActionPanel(
                controller.projection, controller.selectedUnitId,
                controller.unitActions, disabled, submit,
            ).build()).left().row()
            add(AuthoritativeUnitOrderPanel(
                controller.projection, controller.selectedUnitId,
                controller.unitOrders, disabled,
                { mode -> controller.beginUnitTargetSelection(mode); rebuild() },
                submit,
            ).build()).left().row()
        }
    }

    companion object {
        private const val REFRESH_INTERVAL_SECONDS = 5f
    }
}
