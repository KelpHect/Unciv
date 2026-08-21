package com.unciv.ui.screens.multiplayerscreens

import com.badlogic.gdx.scenes.scene2d.ui.Table
import com.unciv.GUI
import com.unciv.logic.map.HexCoord
import com.unciv.logic.multiplayer.authoritative.ApiV3GameSummary
import com.unciv.logic.multiplayer.authoritative.ApiV3SpectatorGameProjection
import com.unciv.logic.multiplayer.authoritative.AuthoritativeMultiplayerSession
import com.unciv.logic.multiplayer.authoritative.SpectatorProjection
import com.unciv.logic.multiplayer.authoritative.toPlayerShapedProjection
import com.unciv.models.ruleset.Ruleset
import com.unciv.models.translations.tr
import com.unciv.ui.components.extensions.darken
import com.unciv.ui.components.extensions.toLabel
import com.unciv.ui.components.extensions.toTextButton
import com.unciv.ui.components.input.onClick
import com.unciv.ui.images.ImageGetter
import com.unciv.ui.popups.ToastPopup
import com.unciv.ui.screens.basescreen.BaseScreen
import com.unciv.ui.screens.basescreen.RecreateOnResize
import com.unciv.ui.screens.savescreens.LoadGameScreen
import com.unciv.utils.Concurrency
import com.unciv.utils.launchOnGLThread
import com.unciv.ui.screens.worldscreen.WorldHudHost
import com.unciv.ui.screens.worldscreen.bottombar.TileInfoTable
import com.unciv.ui.screens.worldscreen.minimap.MinimapHolder
import com.unciv.ui.components.widgets.AutoScrollPane as ScrollPane

/**
 * Read-only live view for an invited spectator, on the game's real surface:
 * the fully revealed hex map fills the screen under floating chrome - match
 * identity and turn up top, tile readout and the minimap bottom-right, and a
 * standings panel one tap away.
 *
 * The server discloses the whole map because that is what a single-player
 * spectator sees; every owner-private family (yields, queues, research,
 * policies, diplomacy internals, spies, orders) stays withheld, so this screen
 * has no inputs beyond looking around and leaving.
 */
class AuthoritativeSpectatorScreen(
    private val gameSummary: ApiV3GameSummary,
    initialProjection: ApiV3SpectatorGameProjection,
    private val session: AuthoritativeMultiplayerSession,
    /** The server manifest's ruleset, resolved by the caller off the GL thread. */
    private val ruleset: Ruleset,
) : BaseScreen(), WorldHudHost, RecreateOnResize {
    private var projection = initialProjection
    private var busy = false
    private var secondsSinceRefresh = 0f

    private var world = AuthoritativeProjectionWorldMap(
        projection.projection.toPlayerShapedProjection(),
        ruleset,
    )
    private var mapHolder = AuthoritativeProjectionMapHolder(this, world, ::tileClicked)
    private var renderedRevision = projection.committedRevision
    private var inspectedTile: com.unciv.logic.map.tile.Tile? = null
    private val tileInfoTable = TileInfoTable(this)
    private var minimapWrapper = MinimapHolder(this, mapHolder)

    private val topBar = Table(BaseScreen.skin)
    private val standingsPanel = Table(BaseScreen.skin)
    private var standingsVisible = false

    override val hudScreen get() = this
    override val hudGameView get() = world.gameView
    override val hudViewingCiv get() = world.viewer

    /** A spectator never changes state; every mutating control stays off. */
    override val hudCanChangeState get() = false

    override var hudShouldUpdate = false

    override fun hudCenterOn(position: HexCoord) = mapHolder.setCenterPosition(position)

    override fun getCivilopediaRuleset() = ruleset

    init {
        require(projection.gameId == gameSummary.gameId) {
            "Spectator projection does not match the selected game"
        }
        require(projection.projectionVersion == SpectatorProjection.CURRENT_PROJECTION_VERSION) {
            "Spectator projection uses an incompatible version"
        }

        // HUD widgets consulting GUI.isAllowedChangeState must ask this screen,
        // and must never be answered by a stale host afterwards.
        GUI.hudHost = this

        // Nation portraits and civ colours resolve through the pinned ruleset.
        ImageGetter.setNewRuleset(ruleset, ignoreIfModsAreEqual = true)

        stage.addActor(mapHolder)
        stage.scrollFocus = mapHolder
        stage.addActor(topBar)
        stage.addActor(tileInfoTable)
        stage.addActor(minimapWrapper)
        stage.addActor(standingsPanel)

        rebuild()
    }

    override fun render(delta: Float) {
        super.render(delta)
        if (hudShouldUpdate && !busy) {
            hudShouldUpdate = false
            rebuild()
        }
        secondsSinceRefresh += delta
        if (secondsSinceRefresh >= REFRESH_INTERVAL_SECONDS && !busy) {
            secondsSinceRefresh = 0f
            refreshProjection()
        }
    }

    private fun rebuild() {
        rebuildMapIfRevisionChanged()
        rebuildTopBar()
        rebuildStandings()
        layoutChrome()

        tileInfoTable.civView = world.gameView.civView
        tileInfoTable.updateTileTable(inspectedTile)
    }

    /**
     * The revealed map is rebuilt wholesale per committed revision - same rule
     * as the player world - so pan and zoom survive identical revisions while
     * a fresh revision can never render discarded state.
     */
    private fun rebuildMapIfRevisionChanged() {
        if (projection.committedRevision == renderedRevision) return
        renderedRevision = projection.committedRevision
        val restoredCenter = mapHolder.centerPosition()
        world = AuthoritativeProjectionWorldMap(
            projection.projection.toPlayerShapedProjection(),
            ruleset,
        )
        mapHolder = AuthoritativeProjectionMapHolder(this, world, ::tileClicked)
        restoredCenter?.let(mapHolder::setCenterPosition)
        stage.scrollFocus = mapHolder
        minimapWrapper.remove()
        minimapWrapper = MinimapHolder(this, mapHolder)
        stage.addActor(minimapWrapper)
        inspectedTile = inspectedTile?.position?.let {
            world.tileMap.getIfTileExistsOrNull(it.x, it.y)
        }
    }

    private fun rebuildTopBar() {
        topBar.clear()
        topBar.defaults().pad(6f)
        topBar.setBackground(
            BaseScreen.skinStrings.getUiBackground(
                "MultiplayerScreen/SpectatorTopBar",
                BaseScreen.skinStrings.roundedEdgeRectangleMidShape,
                BaseScreen.skinStrings.skinConfig.baseColor.darken(0.45f),
            ),
        )
        topBar.left()

        val identity = Table(BaseScreen.skin).apply { defaults().left().pad(2f) }
        identity.add(LobbyChrome.title(gameSummary.displayName)).left().row()
        identity.add(
            (
                "Spectating  •  turn [${projection.projection.turn}]  •  " +
                    "current player [${projection.projection.currentPlayerCivilizationId}]  •  " +
                    "revision [${projection.committedRevision}]"
                ).toLabel(LobbyChrome.muted),
        ).left().row()
        projection.projection.victory?.let { victory ->
            identity.add(
                (
                    "${victory.winningCivilizationId} won by ${victory.victoryType} " +
                        "on turn ${victory.victoryTurn}"
                    ).toLabel(LobbyChrome.ready),
            ).left().row()
        }
        topBar.add(identity).growX().left()

        val standings = "Standings".toTextButton()
        standings.onClick {
            standingsVisible = !standingsVisible
            rebuild()
        }
        topBar.add(standings).right().padLeft(10f)

        val leave = "Leave spectator".toTextButton()
        leave.onClick { leaveSpectator() }
        topBar.add(leave).right().padLeft(10f)

        topBar.pack()
    }

    private fun rebuildStandings() {
        standingsPanel.clear()
        standingsPanel.defaults().pad(6f)
        standingsPanel.isVisible = standingsVisible
        if (!standingsVisible) return

        standingsPanel.setBackground(
            BaseScreen.skinStrings.getUiBackground(
                "MultiplayerScreen/SpectatorStandings",
                BaseScreen.skinStrings.roundedEdgeRectangleMidShape,
                BaseScreen.skinStrings.skinConfig.baseColor.darken(0.35f),
            ),
        )
        standingsPanel.add(LobbyChrome.caption("Standings")).growX().left().row()

        val body = Table(BaseScreen.skin).apply { defaults().pad(4f) }
        for (civ in projection.projection.majorCivilizations.sortedBy { it.civilizationId }) {
            val row = Table(BaseScreen.skin).apply { defaults().pad(3f) }
            row.add(LobbyChrome.nationBadge(ruleset, civ.civilizationId, 34f)).left()
            val isCurrent = civ.civilizationId == projection.projection.currentPlayerCivilizationId
            row.add(
                (
                    (if (isCurrent) "▶ " else "") +
                        (nationLeaderName(civ.civilizationId) ?: civ.displayName)
                    ).toLabel(hideIcons = true),
            ).growX().left().padLeft(6f)
            row.add(
                when {
                    civ.defeated -> "Defeated".toLabel(LobbyChrome.danger)
                    civ.humanControlled -> "Human".toLabel(LobbyChrome.accent)
                    else -> "AI".toLabel(LobbyChrome.muted)
                },
            ).right()
            body.add(row).growX().row()
        }
        standingsPanel.add(ScrollPane(body).apply {
            setScrollingDisabled(true, false)
        }).growX().row()
    }

    /** Leader display name for a server civilization ID, or null to fall back. */
    private fun nationLeaderName(civilizationId: String): String? =
        ruleset.nations[civilizationId]?.getLeaderDisplayName()?.tr()

    private fun layoutChrome() {
        mapHolder.setSize(stage.width, stage.height)
        topBar.width = stage.width
        topBar.setPosition(0f, stage.height - topBar.height)
        minimapWrapper.setPosition(stage.width - minimapWrapper.width, 0f)
        tileInfoTable.pack()
        tileInfoTable.setPosition(
            stage.width - tileInfoTable.width - 10f,
            if (minimapWrapper.isVisible) minimapWrapper.height + 5f else 10f,
        )
        if (standingsVisible) {
            standingsPanel.width = (stage.width * 0.42f).coerceAtMost(420f)
            standingsPanel.height = (stage.height * 0.5f)
                .coerceAtMost((topBar.y - 12f).coerceAtLeast(160f))
            standingsPanel.setPosition(
                stage.width - standingsPanel.width - 8f,
                topBar.y - standingsPanel.height - 6f,
            )
        }
    }

    private fun tileClicked(tile: com.unciv.logic.map.tile.Tile) {
        inspectedTile = tile
        rebuild()
    }

    private fun refreshProjection() {
        if (busy) return
        busy = true
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

    /** A resize rebuilds the floating chrome around the newest projection. */
    override fun recreate(): BaseScreen = AuthoritativeSpectatorScreen(
        gameSummary, projection, session, ruleset,
    )

    override fun dispose() {
        if (GUI.hudHost === this) GUI.hudHost = null
        super.dispose()
    }

    companion object {
        private const val REFRESH_INTERVAL_SECONDS = 5f
    }
}
