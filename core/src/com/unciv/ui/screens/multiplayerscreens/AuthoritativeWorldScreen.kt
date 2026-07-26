package com.unciv.ui.screens.multiplayerscreens

import com.badlogic.gdx.scenes.scene2d.ui.Table
import com.badlogic.gdx.scenes.scene2d.ui.TextButton
import com.unciv.logic.multiplayer.authoritative.ApiV3GameProjection
import com.unciv.logic.multiplayer.authoritative.ApiV3GameSummary
import com.unciv.logic.multiplayer.authoritative.AuthoritativeGameDirectory
import com.unciv.logic.multiplayer.authoritative.AuthoritativeMultiplayerSession
import com.unciv.logic.multiplayer.authoritative.AuthoritativeWorldController
import com.unciv.logic.multiplayer.authoritative.AuthoritativeWorldStatus
import com.unciv.logic.multiplayer.authoritative.OpenedAuthoritativeGame
import com.unciv.logic.multiplayer.authoritative.ProjectedTileVisibility
import com.unciv.logic.multiplayer.authoritative.ProjectedUnit
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
    )

    private var centerX = initialCenter().first
    private var centerY = initialCenter().second
    @Volatile private var busy = false
    private var secondsSinceRefresh = 0f

    init {
        setDefaultCloseAction()
        rightSideButton.setText("End turn")
        rightSideButton.onClick { submitEndTurn() }
        rebuild()
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
            "Server game [${controller.current.gameId}] - " +
                "revision [${controller.current.committedRevision}] - " +
                "turn [${controller.projection.turn}]".toLabel(),
        ).colspan(3).row()
        topTable.add(navigationButton("←", -WINDOW_STEP, 0))
        topTable.add(navigationButton("↑", 0, WINDOW_STEP))
        topTable.add(navigationButton("→", WINDOW_STEP, 0)).row()
        topTable.add(navigationButton("↙", -WINDOW_STEP, -WINDOW_STEP))
        topTable.add(navigationButton("↓", 0, -WINDOW_STEP))
        topTable.add(navigationButton("↗", WINDOW_STEP, WINDOW_STEP)).row()
        val map = buildMap()
        topTable.add(ScrollPane(map).apply {
            setScrollingDisabled(false, false)
            setOverscroll(false, false)
        }).colspan(3).grow().maxHeight(stage.height * 0.55f).row()
        topTable.add(ScrollPane(
            AuthoritativeWorldDecisions(controller, busy) { taskName, operation ->
                runOperation(taskName, operation = operation)
            }.build(),
        ).apply {
            setScrollingDisabled(false, false)
            setOverscroll(false, false)
        }).colspan(3).growX().maxHeight(stage.height * 0.22f).row()
        val refresh = "Refresh server projection".toTextButton()
        refresh.onClick { refreshProjection(silent = false) }
        topTable.add(refresh).colspan(3).row()

        if (controller.canEndTurn() && !busy) rightSideButton.enable()
        else rightSideButton.disable()
        descriptionLabel.setText(description())
    }

    private fun buildMap(): Table {
        val projection = controller.projection
        val tiles = projection.exploredTiles.associateBy { it.x to it.y }
        val ownUnits = projection.ownUnits.groupBy { it.x to it.y }
        val foreignUnits = projection.visibleForeignUnits.groupBy { it.x to it.y }
        val cities = projection.ownCities.associateBy { it.x to it.y }
        return Table().apply {
            defaults().width(TILE_WIDTH).height(TILE_HEIGHT).pad(1f)
            for (y in (centerY + WINDOW_RADIUS) downTo (centerY - WINDOW_RADIUS)) {
                for (x in (centerX - WINDOW_RADIUS)..(centerX + WINDOW_RADIUS)) {
                    val tile = tiles[x to y]
                    if (tile == null) {
                        add()
                    } else {
                        val button = TextButton(
                            tileText(
                                tile,
                                ownUnits[x to y].orEmpty(),
                                foreignUnits[x to y].orEmpty(),
                                cities[x to y]?.name,
                            ),
                            skin,
                        )
                        button.onClick { tileClicked(x, y, ownUnits[x to y].orEmpty()) }
                        add(button)
                    }
                }
                row()
            }
        }
    }

    private fun tileText(
        tile: ProjectedTileVisibility,
        ownUnits: List<ProjectedUnit>,
        foreignUnits: List<ProjectedUnit>,
        cityName: String?,
    ): String {
        val selected = controller.selectedUnitId
        return buildList {
            add(if (tile.visible) tile.baseTerrain else "Fog: ${tile.baseTerrain}")
            tile.terrainFeatures.firstOrNull()?.let(::add)
            tile.resourceName?.let { add("{$it}") }
            cityName?.let { add("[$it]") }
            ownUnits.firstOrNull()?.let {
                add("${if (it.id == selected) "▶" else "U"} ${it.name}")
            }
            foreignUnits.firstOrNull()?.let { add("Enemy ${it.name}") }
        }.joinToString("\n")
    }

    private fun tileClicked(x: Int, y: Int, units: List<ProjectedUnit>) {
        if (busy) return
        val ownUnit = units.firstOrNull()
        if (ownUnit != null) {
            controller.selectUnit(ownUnit.id)
            centerX = x
            centerY = y
            rebuild()
            return
        }
        if (controller.canMoveSelectedTo(x, y)) submitMove(x, y)
    }

    private fun navigationButton(text: String, deltaX: Int, deltaY: Int): TextButton =
        text.toTextButton().apply {
            onClick {
                centerX += deltaX
                centerY += deltaY
                rebuild()
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

    private fun description(): String {
        val selected = controller.selectedUnit()
        val selection = if (selected == null) "Select one of your projected units"
        else "${selected.name} #${selected.id} - ${selected.health} health - " +
            "${selected.currentMovement} movement"
        val blockers = controller.projection.pendingTurnActions
            .joinToString { it.name }
            .ifEmpty { "none" }
        return "$selection\nEnd-turn requirements: $blockers\nStatus: ${statusText()}"
    }

    private fun statusText(): String = when (val status = controller.status) {
        AuthoritativeWorldStatus.Synchronized -> "Synchronized"
        AuthoritativeWorldStatus.Refreshing -> "Refreshing"
        AuthoritativeWorldStatus.Submitting -> "Submitting"
        AuthoritativeWorldStatus.RetryRequired -> "Response uncertain - retry the same action"
        AuthoritativeWorldStatus.StaleRefreshed -> "Stale projection refreshed"
        is AuthoritativeWorldStatus.Rejected -> "Rejected: ${status.code}"
    }

    private fun initialCenter(): Pair<Int, Int> {
        val projection = controller.projection
        return projection.ownCities.firstOrNull()?.let { it.x to it.y }
            ?: projection.ownUnits.firstOrNull()?.let { it.x to it.y }
            ?: projection.exploredTiles.firstOrNull()?.let { it.x to it.y }
            ?: (0 to 0)
    }

    companion object {
        private const val WINDOW_RADIUS = 6
        private const val WINDOW_STEP = 5
        private const val TILE_WIDTH = 105f
        private const val TILE_HEIGHT = 75f
        private const val REFRESH_INTERVAL_SECONDS = 5f
    }
}
