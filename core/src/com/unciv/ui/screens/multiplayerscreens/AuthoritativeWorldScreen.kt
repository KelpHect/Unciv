package com.unciv.ui.screens.multiplayerscreens

import com.badlogic.gdx.scenes.scene2d.ui.Label
import com.badlogic.gdx.scenes.scene2d.ui.Table
import com.unciv.GUI
import com.unciv.logic.map.HexCoord
import com.unciv.logic.map.tile.Tile
import com.unciv.logic.multiplayer.authoritative.ApiV3GameProjection
import com.unciv.logic.multiplayer.authoritative.ApiV3GameSummary
import com.unciv.logic.multiplayer.authoritative.AuthoritativeGameDirectory
import com.unciv.logic.multiplayer.authoritative.AuthoritativeMultiplayerSession
import com.unciv.logic.multiplayer.authoritative.AuthoritativeWorldController
import com.unciv.logic.multiplayer.authoritative.AuthoritativeWorldSelection
import com.unciv.logic.multiplayer.authoritative.AuthoritativeWorldStatus
import com.unciv.logic.multiplayer.authoritative.ProjectedCity
import com.unciv.logic.multiplayer.authoritative.ProjectedTap
import com.unciv.logic.multiplayer.authoritative.OpenedAuthoritativeGame
import com.unciv.models.ruleset.Ruleset
import com.unciv.models.stats.Stat
import com.unciv.ui.components.extensions.darken
import com.unciv.ui.components.extensions.disable
import com.unciv.ui.components.extensions.enable
import com.unciv.ui.components.extensions.toLabel
import com.unciv.ui.components.extensions.toTextButton
import com.unciv.ui.components.input.KeyCharAndCode
import com.unciv.ui.components.input.keyShortcuts
import com.unciv.ui.components.input.onClick
import com.unciv.ui.images.ImageGetter
import com.unciv.ui.popups.ToastPopup
import com.unciv.ui.screens.basescreen.BaseScreen
import com.unciv.ui.screens.basescreen.RecreateOnResize
import com.unciv.ui.screens.cityscreen.CityScreen
import com.unciv.ui.screens.worldscreen.WorldHudHost
import com.unciv.ui.screens.worldscreen.bottombar.TileInfoTable
import com.unciv.ui.screens.worldscreen.minimap.MinimapHolder
import com.unciv.ui.screens.worldscreen.unit.UnitTable
import com.unciv.ui.screens.savescreens.LoadGameScreen
import com.unciv.utils.Concurrency
import com.unciv.utils.launchOnGLThread
import com.unciv.ui.components.widgets.AutoScrollPane as ScrollPane

/**
 * API-v3 world surface backed exclusively by a player projection.
 *
 * The online match plays on the same kind of surface a local one does: the
 * game's real hex renderer fills the screen, and the HUD floats above it -
 * nation and treasury up top, the game's own unit table docked bottom-left,
 * the tile readout and End turn bottom-right, and every server-advertised
 * decision reachable from one slide-in panel instead of a permanent wall of
 * buttons. It intentionally shares no WorldScreen/GameInfo objects: unavailable
 * actions remain absent rather than falling through to local mutation.
 */
class AuthoritativeWorldScreen(
    private val gameSummary: ApiV3GameSummary,
    private val directory: AuthoritativeGameDirectory,
    initialProjection: ApiV3GameProjection,
    private val session: AuthoritativeMultiplayerSession,
    /** The server manifest's ruleset, resolved by the caller off the GL thread. */
    private val ruleset: Ruleset,
) : BaseScreen(), WorldHudHost, RecreateOnResize {
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
     * The game's own minimap over the projected map. It is rebuilt together
     * with the map on every committed revision, and shows the whole projected
     * area - the server already bounded it to what this player may see.
     */
    private var minimapWrapper = MinimapHolder(this, mapHolder)

    /**
     * The game's own unit table: selection, unit info, promotions and idle-unit
     * cycling, over units materialized from the projection. Its orders are not
     * from here - the singleplayer action row mutates GameInfo locally, which V3
     * forbids, so the buttons above it come from what the server advertised.
     */
    private val unitTable = UnitTable(this)

    /** Tap and city-selection decisions, kept outside this GL-bound screen. */
    private val selection = AuthoritativeWorldSelection()
    private var renderedRevision = controller.current.committedRevision

    @Volatile private var busy = false
    private var secondsSinceRefresh = 0f

    // region floating chrome

    /** Full-width status strip across the top, like the single-player top bar. */
    private val topBar = Table(BaseScreen.skin)

    /** The game's own unit table docked bottom-left, with the advertised
     *  action and order rows stacked above it exactly like single-player. */
    private val unitDock = Table(BaseScreen.skin)

    /** Big End turn control in the bottom-right corner. */
    private val endTurnButton = "End turn".toTextButton().apply {
        labelCell.pad(14f)
        onClick { submitEndTurn() }
    }

    /** What the slide-in panel currently shows. */
    private enum class PanelMode { Decisions, City }

    /**
     * One slide-in panel on the right holds everything that is not the map or
     * the unit dock: the server-advertised decisions, and the open city's own
     * panels. This replaces the former permanent stack of every choice at once.
     */
    private val sidePanel = Table(BaseScreen.skin)
    private var sidePanelMode = PanelMode.Decisions
    private var sidePanelVisible = false

    /** The revision whose blocker prompt the player explicitly dismissed. */
    private var blockersDismissedAtRevision = Long.MIN_VALUE

    // endregion

    init {
        // Widgets that consult GUI.isAllowedChangeState (the unit table's
        // popups, tile readout links) must ask this screen while it hosts them,
        // and must never be answered by a stale host afterwards.
        GUI.hudHost = this

        stage.addActor(mapHolder)
        // Without this the map never receives scroll events, so it cannot zoom.
        stage.scrollFocus = mapHolder
        stage.addActor(topBar)
        stage.addActor(unitDock)
        stage.addActor(endTurnButton)
        stage.addActor(tileInfoTable)
        stage.addActor(minimapWrapper)
        stage.addActor(sidePanel)

        rebuild()
        initialCenter()?.let(mapHolder::setCenterPosition)
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
        rebuildMapIfProjectionReplaced()
        rebuildTopBar()
        rebuildUnitDock()
        rebuildSidePanel()
        // The minimap tracks the current map and the show/hide setting; a null
        // viewer reveals everything, which is correct - the projection already
        // contains only what this player may see.
        minimapWrapper.update(null)
        layoutChrome()

        if (controller.canEndTurn() && !busy) endTurnButton.enable()
        else endTurnButton.disable()

        tileInfoTable.civView = world.gameView.civView
        tileInfoTable.updateTileTable(inspectedTile)
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
        // The minimap renders the old disposable map, so it is rebuilt with the
        // new one instead of being updated in place.
        minimapWrapper.remove()
        minimapWrapper = MinimapHolder(this, mapHolder)
        stage.addActor(minimapWrapper)
        // The old map is discarded wholesale, so anything holding one of its
        // tiles or views would render state the server has already replaced.
        // A razed or captured city must not keep its panel open over a
        // projection that no longer lists it.
        selection.onProjectionReplaced(controller.projection)
        // Every MapUnit is a new object after a revision, so the table would
        // otherwise hold one belonging to the discarded map.
        unitTable.selectUnit(controller.selectedUnitId?.let(::materializedUnit))
        inspectedTile = inspectedTile?.position?.let {
            world.tileMap.getIfTileExistsOrNull(it.x, it.y)
        }
    }

    // region top bar

    private fun rebuildTopBar() {
        topBar.clear()
        topBar.defaults().pad(6f)
        topBar.setBackground(
            BaseScreen.skinStrings.getUiBackground(
                "MultiplayerScreen/WorldTopBar",
                BaseScreen.skinStrings.roundedEdgeRectangleMidShape,
                BaseScreen.skinStrings.skinConfig.baseColor.darken(0.45f),
            ),
        )
        topBar.left()

        val identity = Table(BaseScreen.skin).apply { defaults().pad(3f) }
        identity.add(LobbyChrome.nationBadge(ruleset, controller.projection.civilizationId, 36f))
            .left()
        val titles = Table(BaseScreen.skin).apply { defaults().left().pad(1f) }
        titles.add(
            (
                "Server game [${controller.current.gameId}] - " +
                    "revision [${controller.current.committedRevision}] - " +
                    "turn [${controller.projection.turn}]"
                ).toLabel(),
        ).left().row()
        titles.add(turnStateLabel()).left().row()
        identity.add(titles).left().padLeft(6f)
        topBar.add(identity).growX().left()

        val treasury = Table(BaseScreen.skin)
        treasury.add(ImageGetter.getStatIcon(Stat.Gold.name)).size(20f)
        treasury.add("${controller.projection.gold}".toLabel()).padLeft(4f)
        topBar.add(treasury).right()

        statusLabel()?.let { topBar.add(it).right().padLeft(10f) }

        val decisions = decisionsButtonText().toTextButton()
        decisions.onClick { toggleSidePanel(PanelMode.Decisions) }
        topBar.add(decisions).right().padLeft(10f)

        val leave = "Leave match".toTextButton()
        leave.keyShortcuts.add(KeyCharAndCode.BACK)
        leave.onClick { game.popScreen() }
        topBar.add(leave).right().padLeft(10f)

        topBar.pack()
    }

    private fun turnStateLabel() =
        if (controller.projection.isCurrentTurn) "Your turn".toLabel(LobbyChrome.ready)
        else (
            "Waiting for [" + currentPlayerName() + "]"
            ).toLabel(LobbyChrome.muted)

    /** The leader display name, falling back to the server's identity string. */
    private fun currentPlayerName(): String {
        val id = controller.projection.currentPlayerCivilizationId
        return ruleset.nations[id]?.leaderName ?: id
    }

    private fun statusLabel(): Label? = when (val status = controller.status) {
        AuthoritativeWorldStatus.Synchronized -> null
        AuthoritativeWorldStatus.Refreshing, AuthoritativeWorldStatus.Submitting ->
            statusText().toLabel(LobbyChrome.muted)
        AuthoritativeWorldStatus.StaleRefreshed -> statusText().toLabel(LobbyChrome.accent)
        is AuthoritativeWorldStatus.Rejected, AuthoritativeWorldStatus.RetryRequired,
        AuthoritativeWorldStatus.OfflineCached,
        -> statusText().toLabel(LobbyChrome.danger)
    }

    private fun decisionsButtonText(): String {
        val blockers = controller.projection.pendingTurnActions.size
        return if (blockers == 0) "Decisions" else "Decisions [$blockers]"
    }

    // endregion
    // region bottom dock

    private fun rebuildUnitDock() {
        unitDock.clear()
        unitDock.defaults().pad(3f)
        // Orders for the selected unit sit directly above the game's own unit
        // table, mirroring where the single-player action row lives.
        unitDock.add(unitOrdersForSelection()).left().row()
        unitDock.add(unitTable).left().row()
        unitDock.pack()
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

    // endregion
    // region side panel

    private fun toggleSidePanel(mode: PanelMode) {
        if (sidePanelVisible && sidePanelMode == mode) {
            closeSidePanel()
            return
        }
        sidePanelMode = mode
        sidePanelVisible = true
        rebuild()
    }

    private fun closeSidePanel() {
        sidePanelVisible = false
        if (sidePanelMode == PanelMode.City) selection.selectCity(null)
        // Only the revision seen at dismissal counts: a newer blocker asks again.
        blockersDismissedAtRevision = controller.current.committedRevision
        rebuild()
    }

    private fun rebuildSidePanel() {
        // A blocker the player has not dismissed yet opens the decisions panel
        // once per revision, so the choice is discoverable without permanently
        // covering the map. Closing records the revision; a newer blocker asks
        // again.
        if (!sidePanelVisible &&
            sidePanelMode == PanelMode.Decisions &&
            controller.projection.pendingTurnActions.isNotEmpty() &&
            blockersDismissedAtRevision != controller.current.committedRevision
        ) {
            sidePanelVisible = true
        }
        sidePanel.clear()
        sidePanel.defaults().pad(6f)
        sidePanel.isVisible = sidePanelVisible
        if (!sidePanelVisible) return

        val city = selection.selectedCity(controller.projection)
        if (sidePanelMode == PanelMode.City && city == null) {
            // The open city was razed or captured out from under the panel.
            sidePanelMode = PanelMode.Decisions
        }

        val title = when (sidePanelMode) {
            PanelMode.City -> city?.name ?: "Decisions"
            PanelMode.Decisions -> "Decisions"
        }
        sidePanel.setBackground(
            BaseScreen.skinStrings.getUiBackground(
                "MultiplayerScreen/WorldSidePanel",
                BaseScreen.skinStrings.roundedEdgeRectangleMidShape,
                BaseScreen.skinStrings.skinConfig.baseColor.darken(0.35f),
            ),
        )

        val header = Table(BaseScreen.skin).apply { defaults().pad(3f) }
        header.add(LobbyChrome.caption(title)).left()
        val close = "Close".toTextButton()
        close.onClick { closeSidePanel() }
        header.add(close).right()
        sidePanel.add(header).growX().row()

        val body = when (sidePanelMode) {
            PanelMode.City -> cityPanelFor(requireNotNull(city))
            PanelMode.Decisions -> decisionsPanel()
        }
        sidePanel.add(ScrollPane(body).apply {
            setScrollingDisabled(false, false)
            setOverscroll(false, false)
        }).grow().row()
    }

    /** Retry/reconnect controls plus every server-advertised decision. */
    private fun decisionsPanel(): Table = Table(BaseScreen.skin).apply {
        defaults().pad(3f)
        if (controller.status == AuthoritativeWorldStatus.RetryRequired) {
            val retry = "Retry uncertain action".toTextButton()
            retry.onClick {
                runOperation("Retry authoritative action") {
                    controller.retryUncertainCommand()
                }
            }
            add(retry).left().row()
        } else {
            val refreshLabel =
                if (controller.status == AuthoritativeWorldStatus.OfflineCached)
                    "Reconnect and replace cached projection"
                else "Refresh server projection"
            val refresh = refreshLabel.toTextButton()
            refresh.onClick { refreshProjection(silent = false) }
            add(refresh).left().row()
        }
        add(
            ScrollPane(
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
            },
        ).grow().row()
    }

    /** The open city's own panel: production, purchases, tiles and governance. */
    private fun cityPanelFor(city: ProjectedCity): Table {
        val disabled = busy || !controller.canAcceptProjectedInput
        val submit: (String, suspend () -> Unit) -> Unit = { taskName, operation ->
            runOperation(taskName, operation = operation)
        }
        return Table(BaseScreen.skin).apply {
            defaults().pad(3f)
            add("${city.name} - population [${city.population}]".toLabel()).left().row()
            add(AuthoritativeCityEconomyPanel(
                controller.projection, controller.cityEconomy, disabled, submit, city.id,
            ).build()).left().row()
            add(AuthoritativeCityControlPanel(
                controller.projection, controller.cityControls, disabled, submit,
                onlyCityId = city.id, includeDispositions = false,
            ).build()).left().row()
            add(openCityScreenButton(city)).left().row()
        }
    }

    /**
     * Opens the real single-player city screen over this projected city:
     * read-only, with the server's own construction figures fed in, because a
     * client without canonical state can neither compute costs nor mutate
     * anything.
     */
    private fun openCityScreenButton(city: ProjectedCity) = "Open city screen".toTextButton().apply {
        onClick {
            val materialized = world.viewer.cities.firstOrNull { it.id == city.id }
            if (materialized == null) {
                ToastPopup("This city is not on the current projection", this@AuthoritativeWorldScreen)
                return@onClick
            }
            game.pushScreen(
                CityScreen(
                    world.gameView.getCityView(materialized),
                    forceReadOnly = true,
                    projectedCity = city,
                ),
            )
        }
    }

    // endregion

    /** Places every floating widget, sized to what it actually drew. */
    private fun layoutChrome() {
        mapHolder.setSize(stage.width, stage.height)
        topBar.width = stage.width
        topBar.setPosition(0f, stage.height - topBar.height)
        unitDock.setPosition(10f, 10f)
        minimapWrapper.setPosition(stage.width - minimapWrapper.width, 0f)
        endTurnButton.pack()
        endTurnButton.setPosition(
            stage.width - endTurnButton.width - minimapWrapper.width - 20f,
            10f,
        )
        tileInfoTable.pack()
        tileInfoTable.setPosition(
            stage.width - tileInfoTable.width - 10f,
            if (minimapWrapper.isVisible) minimapWrapper.height + 5f
            else endTurnButton.height + 18f,
        )
        if (sidePanelVisible) {
            sidePanel.width = (stage.width * 0.42f).coerceAtMost(480f)
            sidePanel.height = (stage.height * 0.66f)
                .coerceAtMost((topBar.y - 12f).coerceAtLeast(200f))
            sidePanel.setPosition(
                stage.width - sidePanel.width - 8f,
                topBar.y - sidePanel.height - 6f,
            )
        }
    }

    private fun tileClicked(tile: Tile) {
        if (busy) return
        val x = tile.position.x
        val y = tile.position.y
        // Inspecting a tile is a read, so it happens on every tap regardless of
        // what the tap also turns out to mean.
        inspectedTile = tile
        when (val tap = selection.decide(
            controller.projection, x, y,
            unitTargetModeActive = controller.unitTargetMode != null,
            canSubmitUnitTarget = controller.canSubmitUnitTarget(x, y),
            canMoveSelectedTo = controller.canMoveSelectedTo(x, y),
        )) {
            is ProjectedTap.SubmitUnitTarget -> runOperation("Submit authoritative unit target") {
                controller.submitUnitTarget(tap.x, tap.y)
            }
            is ProjectedTap.SelectCity -> {
                selection.selectCity(tap.cityId)
                unitTable.selectUnit(null)
                syncControllerSelection()
                sidePanelMode = PanelMode.City
                sidePanelVisible = true
                rebuild()
            }
            ProjectedTap.SelectUnit -> {
                selection.selectCity(null)
                // The game's own selection order - military before civilian,
                // re-tap to cycle - rather than whichever unit is listed first.
                unitTable.tileSelected(tile)
                syncControllerSelection()
                rebuild()
            }
            is ProjectedTap.MoveSelectedUnit -> submitMove(tap.x, tap.y)
            // Nothing was submitted, but the readout still has to redraw.
            ProjectedTap.RejectedUnitTarget, ProjectedTap.InspectOnly -> rebuild()
        }
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

    /** A resize rebuilds the whole floating chrome around the same projection. */
    override fun recreate(): BaseScreen = AuthoritativeWorldScreen(
        gameSummary, directory, controller.current, session, ruleset,
    )

    override fun dispose() {
        if (GUI.hudHost === this) GUI.hudHost = null
        super.dispose()
    }

    companion object {
        private const val REFRESH_INTERVAL_SECONDS = 5f
    }
}
