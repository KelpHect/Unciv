package com.unciv.logic.multiplayer.authoritative

import com.unciv.logic.GameInfo
import com.unciv.logic.map.HexCoord
import kotlinx.serialization.Serializable

/**
 * Pregame terrain projection for a server-owned lobby.
 *
 * It carries the committed map's base terrain plus unlabeled start positions
 * and nothing else: no units, cities, resources, improvements, natural
 * wonders, tile ownership, civilization identities, or turn state. Gameplay
 * projections stay gated on lobby start; this is deliberately the only
 * pregame map disclosure and every lobby member sees the identical payload.
 */
@Serializable
data class LobbyTerrainProjection(
    val worldWrap: Boolean,
    val minX: Int,
    val minY: Int,
    val width: Int,
    val height: Int,
    /** Sorted, deduplicated base terrain names that [tiles] indexes into. */
    val terrainNames: List<String>,
    /** Row-major over the bounding box; `-1` marks a coordinate off the map. */
    val tiles: List<Int>,
    /** Flat `x, y` pairs. Unlabeled on purpose: which civilization starts where stays private. */
    val startPositions: List<Int>,
) {
    /** Fail-closed shape check mirrored by the Rust control plane. */
    fun isConsistent(): Boolean =
        width in 1..maxBoundingBoxSide &&
            height in 1..maxBoundingBoxSide &&
            terrainNames.isNotEmpty() &&
            terrainNames.size <= maxTerrainNames &&
            terrainNames.none(String::isBlank) &&
            terrainNames == terrainNames.distinct().sorted() &&
            tiles.size == width * height &&
            tiles.all { it in -1 until terrainNames.size } &&
            tiles.any { it >= 0 } &&
            startPositions.size % 2 == 0 &&
            startPositions.size <= 2 * maxStartPositions &&
            startPositionsAreOnTheMap()

    /** Base terrain name at a map coordinate, or `null` when the tile is off the map. */
    fun terrainAt(x: Int, y: Int): String? {
        val column = x - minX
        val row = y - minY
        if (column !in 0 until width || row !in 0 until height) return null
        return terrainNames.getOrNull(tiles[row * width + column])
    }

    fun startPositionCoordinates(): List<HexCoord> =
        startPositions.chunked(2) { HexCoord(it[0], it[1]) }

    private fun startPositionsAreOnTheMap() =
        startPositionCoordinates().all { terrainAt(it.x, it.y) != null }

    companion object {
        const val CURRENT_PROJECTION_VERSION = 1

        /** The widest custom world the public setup contract admits is 220 x 220. */
        const val maxBoundingBoxSide = 256
        const val maxTerrainNames = 512
        const val maxStartPositions = 16

        fun build(game: GameInfo): LobbyTerrainProjection {
            val tileMap = game.tileMap
            val positions = tileMap.values.map { it.position }
            val minX = positions.minOf { it.x }
            val minY = positions.minOf { it.y }
            val width = positions.maxOf { it.x } - minX + 1
            val height = positions.maxOf { it.y } - minY + 1
            val terrainNames = tileMap.values.mapTo(sortedSetOf()) { it.baseTerrain }.toList()
            val terrainIndices = terrainNames.withIndex().associate { it.value to it.index }
            val tiles = MutableList(width * height) { -1 }
            for (tile in tileMap.values) {
                val column = tile.position.x - minX
                val row = tile.position.y - minY
                tiles[row * width + column] = terrainIndices.getValue(tile.baseTerrain)
            }
            return LobbyTerrainProjection(
                worldWrap = tileMap.mapParameters.worldWrap,
                minX = minX,
                minY = minY,
                width = width,
                height = height,
                terrainNames = terrainNames,
                tiles = tiles,
                startPositions = startPositions(game),
            )
        }

        /**
         * One coordinate per major civilization, deduplicated and ordered so the
         * payload never implies which civilization owns which start.
         */
        private fun startPositions(game: GameInfo): List<Int> = game.civilizations
            .asSequence()
            .filter { it.isMajorCiv() }
            .mapNotNull { it.units.getCivUnits().firstOrNull()?.getTile()?.position }
            .distinct()
            .sortedWith(compareBy(HexCoord::x, HexCoord::y))
            .flatMap { sequenceOf(it.x, it.y) }
            .toList()
    }
}
