package com.unciv.ui.screens.multiplayerscreens

import com.badlogic.gdx.graphics.Color
import com.unciv.logic.map.HexCoord
import com.unciv.logic.map.HexMath
import com.unciv.logic.map.TileMap
import com.unciv.logic.map.tile.Tile
import com.unciv.logic.multiplayer.authoritative.LobbyTerrainProjection
import com.unciv.models.ruleset.Ruleset
import com.unciv.models.ruleset.tile.TerrainType
import com.unciv.ui.images.ImageGetter
import com.unciv.ui.screens.victoryscreen.IndependentMiniMap
import kotlin.math.min

/**
 * Renders the terrain a lobby revision already committed, straight from the
 * server projection. It builds a bare [TileMap] purely to reuse the existing
 * WorldScreen-free minimap renderer and never touches `GameInfo`,
 * `GameStarter`, or `MapGenerator`: the client generates nothing.
 */
class AuthoritativeLobbyMapPreview(
    terrain: LobbyTerrainProjection,
    ruleset: Ruleset,
    maxWidth: Float,
    maxHeight: Float,
) : IndependentMiniMap(terrain.toPreviewTileMap(ruleset)) {
    /**
     * Half-extents of the map in minimap world coordinates. Measuring the real
     * hex layout keeps the fit exact for every shape, including the custom
     * radii and rectangles the setup contract admits.
     */
    private val worldSpan = tileMap.values
        .map { HexMath.hex2WorldCoords(it.position) }
        .let { vectors ->
            val xs = vectors.map { it.x }
            val ys = vectors.map { it.y }
            (xs.max() - xs.min()) to (ys.max() - ys.min())
        }

    private val unknownTerrainPositions =
        terrain.unknownTerrainPositions(ruleset)

    init {
        deferredInit(maxWidth, maxHeight)
        greyOutTerrainThisClientCannotName()
        markStartPositions(terrain.startPositionCoordinates().toHashSet())
    }

    override fun calcTileSize(maxWidth: Float, maxHeight: Float): Float = min(
        maxWidth / (worldSpan.first * 0.5f + 1f),
        maxHeight / (worldSpan.second * 0.5f + 1f),
    )

    /**
     * A mod the server pinned but this client cannot resolve must read as
     * unknown rather than as a plausible wrong terrain.
     */
    private fun greyOutTerrainThisClientCannotName() {
        if (unknownTerrainPositions.isEmpty()) return
        for (minimapTile in minimapTiles)
            if (minimapTile.tile.position in unknownTerrainPositions)
                minimapTile.image.color = Color.DARK_GRAY
    }

    private fun markStartPositions(startPositions: Set<HexCoord>) {
        if (startPositions.isEmpty()) return
        for (minimapTile in minimapTiles) {
            if (minimapTile.tile.position !in startPositions) continue
            val tileSize = minimapTile.image.width
            val marker = ImageGetter.getImage("OtherIcons/Star").apply {
                color = Color.WHITE
                setSize(tileSize * 1.6f, tileSize * 1.6f)
                setPosition(
                    minimapTile.image.x - tileSize * 0.3f,
                    minimapTile.image.y - tileSize * 0.3f,
                )
            }
            addActor(marker)
        }
    }
}

/**
 * Materializes the projected coordinates verbatim, so the rendered grid can
 * never disagree with what the server committed.
 */
private fun LobbyTerrainProjection.toPreviewTileMap(ruleset: Ruleset): TileMap {
    val fallbackTerrain = ruleset.terrains.values
        .firstOrNull { it.type == TerrainType.Land }
        ?.name
        ?: ruleset.terrains.keys.first()
    val tileMap = TileMap(width * height)
    tileMap.startingLocations.clear()
    for (row in 0 until height) for (column in 0 until width) {
        val name = terrainAt(minX + column, minY + row) ?: continue
        tileMap.tileList.add(
            Tile().apply {
                position = HexCoord(minX + column, minY + row)
                baseTerrain = name.takeIf(ruleset.terrains::containsKey) ?: fallbackTerrain
            },
        )
    }
    tileMap.mapParameters.worldWrap = worldWrap
    tileMap.setTransients(ruleset, setUnitCivTransients = false)
    return tileMap
}

private fun LobbyTerrainProjection.unknownTerrainPositions(ruleset: Ruleset): Set<HexCoord> {
    if (terrainNames.all(ruleset.terrains::containsKey)) return emptySet()
    val unknown = hashSetOf<HexCoord>()
    for (row in 0 until height) for (column in 0 until width) {
        val name = terrainAt(minX + column, minY + row) ?: continue
        if (name !in ruleset.terrains) unknown.add(HexCoord(minX + column, minY + row))
    }
    return unknown
}
