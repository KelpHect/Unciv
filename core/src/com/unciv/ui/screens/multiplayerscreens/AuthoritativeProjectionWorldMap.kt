package com.unciv.ui.screens.multiplayerscreens

import com.badlogic.gdx.scenes.scene2d.InputEvent
import com.badlogic.gdx.scenes.scene2d.Stage
import com.badlogic.gdx.scenes.scene2d.utils.ActorGestureListener
import com.unciv.UncivGame
import com.unciv.logic.city.City
import com.unciv.logic.civilization.Civilization
import com.unciv.logic.civilization.PlayerType
import com.unciv.logic.map.HexCoord
import com.unciv.logic.map.TileMap
import com.unciv.logic.map.mapunit.MapUnit
import com.unciv.logic.map.tile.RoadStatus
import com.unciv.logic.map.tile.Tile
import com.unciv.logic.multiplayer.authoritative.PlayerProjection
import com.unciv.logic.multiplayer.authoritative.ProjectedUnit
import com.unciv.models.ruleset.Ruleset
import com.unciv.models.ruleset.tile.TerrainType
import com.unciv.ui.components.tilegroups.TileGroupMap
import com.unciv.ui.components.tilegroups.TileSetStrings
import com.unciv.ui.components.tilegroups.WorldTileGroup
import com.unciv.ui.components.widgets.ZoomableScrollPane
import com.unciv.ui.screens.basescreen.BaseScreen
import com.unciv.ui.screens.basescreen.UncivStage
import com.unciv.view.GameView
import com.unciv.utils.Log

/**
 * Materializes a disposable client-side [TileMap] and viewing [Civilization]
 * from an immutable server projection, so the online world can be drawn by the
 * game's real hex renderer instead of a bespoke surface.
 *
 * This is a presentation cache and nothing else: it is rebuilt from scratch on
 * every accepted projection, it is never uploaded, and it never decides
 * anything. Rules, visibility and legality were already decided by the server —
 * the projection only contains what this player is permitted to see, so what is
 * rendered here can never exceed the server's own answer.
 */
internal class AuthoritativeProjectionWorldMap(
    projection: PlayerProjection,
    private val ruleset: Ruleset,
) {
    private val civilizations = HashMap<String, Civilization>()
    val viewer: Civilization = civilization(projection.civilizationId).apply {
        playerType = PlayerType.Human
    }
    val tileMap: TileMap = buildTileMap(projection)
    val gameView: GameView = GameView(tileMap, viewer)

    init {
        // Ruleset objects normally get their back-reference when a GameInfo
        // loads. There is no GameInfo here, so the projection client wires the
        // same references itself before anything renders through them.
        for (baseUnit in ruleset.units.values) baseUnit.setRuleset(ruleset)
        for (building in ruleset.buildings.values) building.ruleset = ruleset

        // Fog is server-decided: every projected tile is explored by definition,
        // and only the ones the server flagged visible are currently in sight.
        val visible = HashSet<Tile>()
        for (projected in projection.exploredTiles) {
            val tile = tileMap.getIfTileExistsOrNull(projected.x, projected.y) ?: continue
            tile.setExplored(viewer, true)
            if (projected.visible) visible.add(tile)
        }
        viewer.viewableTiles = visible
        placeCities(projection)
        placeUnits(projection.ownUnits + projection.visibleForeignUnits)
    }

    /**
     * Without this the player's own cities are simply absent from the online map:
     * no city centre, no borders, no city button. Only the projected fields are
     * used — everything else stays at its default, because the server decides it.
     */
    private fun placeCities(projection: PlayerProjection) {
        viewer.cities = projection.ownCities.mapNotNull { projected ->
            materializeCity(
                id = projected.id,
                name = projected.name,
                x = projected.x,
                y = projected.y,
                owner = viewer,
                ownedTiles = projected.tileStates
                    .filter { it.owningCityId == projected.id }
                    .map { it.x to it.y },
                workedTiles = projected.tileStates
                    .filter { it.workingCityId == projected.id && it.worked }
                    .map { it.x to it.y },
            )?.apply {
                health = projected.health
                population.setProjectedPopulation(projected.population)
                isPuppet = projected.isPuppet
                isBeingRazed = projected.isBeingRazed
            }
        }

        // Rival cities the server said this player can see, through the same
        // materialization. The projection carries no interior facts for them, so
        // they render as a city centre plus whatever border the server disclosed.
        //
        // An entry claiming the viewer's own civilization is dropped rather than
        // resolved: civilization() would hand back the viewer itself, and the
        // assignment below would wipe the player's own cities off their own map.
        // The Rust boundary already rejects that shape; this keeps the client
        // from depending on it, the same way unplaceable tiles are dropped.
        for ((civilizationId, cities) in projection.visibleForeignCities
            .filter { it.civilizationId != projection.civilizationId }
            .groupBy { it.civilizationId }) {
            val owner = civilization(civilizationId)
            owner.cities = cities.mapNotNull { projected ->
                materializeCity(
                    id = projected.id,
                    name = projected.name,
                    x = projected.x,
                    y = projected.y,
                    owner = owner,
                    ownedTiles = projected.ownedTiles.map { it.x to it.y },
                    workedTiles = emptyList(),
                )
            }
        }
    }

    /**
     * One projected city on the disposable map, or null when the projection never
     * sent its centre tile.
     *
     * City.getTiles()/getWorkedTiles() index the map with `tileMap[...]!!`, and
     * this map only holds tiles the projection actually sent. A coordinate the
     * server named but did not send would take the whole world screen down, so
     * unknown tiles are dropped here just like unplaceable units.
     */
    private fun materializeCity(
        id: String,
        name: String,
        x: Int,
        y: Int,
        owner: Civilization,
        ownedTiles: List<Pair<Int, Int>>,
        workedTiles: List<Pair<Int, Int>>,
    ): City? {
        val center = tileMap.getIfTileExistsOrNull(x, y) ?: return null
        val city = City()
        city.id = id
        city.name = name
        city.location = center.position
        city.tiles = ownedTiles
            .mapNotNullTo(HashSet()) { existingPosition(it.first, it.second) }
            .apply { add(center.position) }
        city.workedTiles = workedTiles
            .mapNotNullTo(HashSet()) { existingPosition(it.first, it.second) }
        // This also runs expansion.setTransients(), which is what calls
        // Tile.setOwningCity and so makes the tiles render as owned.
        city.setTransients(owner, tileMap)
        return city
    }

    /** The tile's position, or null when the projection never sent that tile. */
    private fun existingPosition(x: Int, y: Int): HexCoord? =
        tileMap.getIfTileExistsOrNull(x, y)?.position

    /** One lightweight civilization per identity the projection names. */
    private fun civilization(civilizationId: String): Civilization =
        civilizations.getOrPut(civilizationId) {
            val serverRuleset = ruleset
            Civilization(civilizationId).apply {
                nation = serverRuleset.nations[civilizationId]
                    ?: serverRuleset.nations.values.first()
                // There is no GameInfo behind a projection, so the civilization
                // carries the server's pinned ruleset itself.
                attachRuleset(serverRuleset)
            }
        }

    private fun buildTileMap(projection: PlayerProjection): TileMap {
        val fallbackTerrain = ruleset.terrains.values
            .firstOrNull { it.type == TerrainType.Land }
            ?.name
            ?: ruleset.terrains.keys.first()
        val map = TileMap(projection.exploredTiles.size.coerceAtLeast(1))
        map.startingLocations.clear()
        for (projected in projection.exploredTiles) {
            val tile = Tile()
            tile.position = HexCoord(projected.x, projected.y)
            // An unresolvable modded terrain must not silently become a
            // plausible wrong one; it falls back and is logged, not guessed at.
            tile.baseTerrain = projected.baseTerrain.takeIf { it in ruleset.terrains }
                ?: fallbackTerrain.also {
                    Log.debug("V3 projection named unknown terrain %s", projected.baseTerrain)
                }
            tile.naturalWonder = projected.naturalWonderName?.takeIf { it in ruleset.terrains }
            tile.improvement = projected.improvementName?.takeIf { it in ruleset.tileImprovements }
            tile.improvementIsPillaged = projected.improvementPillaged == true
            tile.roadStatus = RoadStatus.entries.firstOrNull { it.name == projected.roadStatus }
                ?: RoadStatus.None
            tile.roadIsPillaged = projected.roadPillaged == true
            map.tileList.add(tile)
        }
        map.setTransients(ruleset, setUnitCivTransients = false)
        // Terrain features and resources resolve ruleset objects through the
        // tile's own ruleset reference, which only exists after the transients
        // above, so they are applied in a second pass rather than at build time.
        for (projected in projection.exploredTiles) {
            val tile = map.getIfTileExistsOrNull(projected.x, projected.y) ?: continue
            val features = projected.terrainFeatures.filter { it in ruleset.terrains }
            if (features.isNotEmpty()) tile.setTerrainFeatures(features)
            val resource = projected.resourceName ?: continue
            if (resource !in ruleset.tileResources) continue
            tile.setTileResource(resource, updateCache = false)
            tile.resourceAmount = projected.resourceAmount ?: 0
        }
        return map
    }

    private fun placeUnits(units: List<ProjectedUnit>) {
        for (projected in units) {
            val baseUnit = ruleset.units[projected.name] ?: continue
            val tile = tileMap.getIfTileExistsOrNull(projected.x, projected.y) ?: continue
            val civilization = civilization(projected.civilizationId)
            val unit = MapUnit()
            unit.id = projected.id
            unit.name = projected.name
            unit.owner = projected.civilizationId
            unit.civ = civilization
            unit.currentTile = tile
            unit.health = projected.health
            unit.currentMovement = projected.currentMovement ?: 0f
            unit.instanceName = projected.instanceName
            unit.automated = projected.automated
            try {
                unit.setTransients(ruleset)
            } catch (exception: Exception) {
                // One unrenderable unit must not cost the player the whole map.
                Log.error("V3 projection unit ${projected.name} could not be rendered", exception)
                continue
            }
            if (baseUnit.isCivilian()) tile.civilianUnit = unit else tile.militaryUnit = unit
            // The same call the load path uses to rebuild a civilization's unit
            // list from tiles. Without it civ.units is empty, so the unit table
            // has nothing to cycle through. updateCivInfo stays false: upkeep and
            // resource recalculation are the server's business, not the renderer's.
            civilization.units.addUnit(unit, updateCivInfo = false)
        }
    }
}

/**
 * Zoomable, pannable hex map over a projected [TileMap].
 *
 * Modelled on `EditorMapHolder`, which already drives the real renderer without
 * a `WorldScreen`. This one keeps a real viewing civilization so fog of war is
 * drawn, and reports tile taps back to the projection-only controller.
 */
internal class AuthoritativeProjectionMapHolder(
    screen: BaseScreen,
    private val world: AuthoritativeProjectionWorldMap,
    private val onTileClicked: (Tile) -> Unit,
) : ZoomableScrollPane(20f, 20f) {
    private val tileGroups = HashMap<Tile, WorldTileGroup>()
    private lateinit var tileGroupMap: TileGroupMap<WorldTileGroup>

    init {
        continuousScrollingX = world.tileMap.mapParameters.worldWrap
        addTiles(screen.stage)
        addClickListener()
        setupZoomPanListeners()
        reloadMaxZoom()
    }

    private fun addTiles(stage: Stage) {
        val tileSetStrings = TileSetStrings(world.tileMap.ruleset!!, UncivGame.Current.settings)
        val groups = world.tileMap.values.map {
            WorldTileGroup(world.gameView.tileMapView.getTile(it), tileSetStrings)
        }
        tileGroupMap = TileGroupMap(this, groups, continuousScrollingX)
        actor = tileGroupMap
        for (group in groups) tileGroups[group.tile] = group
        updateTiles()
        setSize(stage.width, stage.height)
        layout()
    }

    fun updateTiles() {
        for (group in tileGroups.values) group.update(world.gameView.civView)
    }

    private fun addClickListener() {
        tileGroupMap.addListener(object : ActorGestureListener(20f, 0.25f, 1.1f, Float.MAX_VALUE) {
            override fun tap(event: InputEvent?, x: Float, y: Float, count: Int, button: Int) {
                val hit = tileGroupMap.hit(x, y, true) ?: return
                val group = generateSequence(hit) { it.parent }
                    .filterIsInstance<WorldTileGroup>()
                    .firstOrNull() ?: return
                onTileClicked(group.tile)
            }
        })
    }

    /** Suppresses per-tile hit/act work while the user is dragging or zooming. */
    private fun setupZoomPanListeners() {
        fun setActHit() {
            val isEnabled = !isZooming() && !isPanning
            (stage as? UncivStage)?.performPointerEnterExitEvents = isEnabled
            tileGroupMap.shouldAct = isEnabled
            tileGroupMap.shouldHit = isEnabled
        }
        onPanStartListener = { setActHit() }
        onPanStopListener = { setActHit() }
        onZoomStartListener = { setActHit() }
        onZoomStopListener = { setActHit() }
    }

    fun setCenterPosition(position: HexCoord) {
        val group = tileGroups.values.firstOrNull { it.tile.position == position } ?: return
        scrollTo(group.x + group.width / 2, maxY - (group.y + group.width / 2))
        updateVisualScroll()
    }

    /** The tile nearest the current view, so a new revision keeps the player's view. */
    fun centerPosition(): HexCoord? {
        if (!::tileGroupMap.isInitialized) return null
        val targetX = scrollX + width / 2
        val targetY = maxY - (scrollY + height / 2)
        return tileGroups.values.minByOrNull {
            val dx = it.x + it.width / 2 - targetX
            val dy = it.y + it.width / 2 - targetY
            dx * dx + dy * dy
        }?.tile?.position
    }
}
