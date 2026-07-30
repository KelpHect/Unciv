package com.unciv.logic.multiplayer.authoritative

import com.unciv.Constants
import com.unciv.logic.map.HexCoord
import com.unciv.testing.GdxTestRunner
import com.unciv.testing.TestGame
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The pregame terrain projection is the only map disclosure a lobby member
 * receives. These tests pin both halves of that boundary: the terrain it must
 * carry, and the gameplay state it must never carry.
 */
@RunWith(GdxTestRunner::class)
class LobbyTerrainProjectionTests {
    private val json = Json { encodeDefaults = true }

    @Test
    fun projectsEveryTileOfARectangularMapIntoASortedPalette() {
        val testGame = TestGame()
        testGame.makeRectangularMap(6, 8)
        val tundra = testGame.getTile(0, 0)
        tundra.baseTerrain = Constants.tundra
        tundra.setTerrainTransients()

        val projection = LobbyTerrainProjection.build(testGame.gameInfo)

        assertTrue(projection.isConsistent())
        assertEquals(
            projection.terrainNames.sorted().distinct(),
            projection.terrainNames,
        )
        assertTrue(Constants.tundra in projection.terrainNames)
        assertEquals(Constants.tundra, projection.terrainAt(0, 0))
        // Every tile the engine holds is addressable, and nothing else is.
        for (tile in testGame.gameInfo.tileMap.values)
            assertEquals(tile.baseTerrain, projection.terrainAt(tile.position.x, tile.position.y))
        assertEquals(projection.width * projection.height, projection.tiles.size)
    }

    @Test
    fun projectsAHexagonalMapAndReportsOffMapCoordinatesAsAbsent() {
        val testGame = TestGame()
        testGame.makeHexagonalMap(3)

        val projection = LobbyTerrainProjection.build(testGame.gameInfo)

        assertTrue(projection.isConsistent())
        assertEquals(
            testGame.getTile(0, 0).baseTerrain,
            projection.terrainAt(0, 0),
        )
        // A hexagonal map does not fill its bounding box. In this coordinate basis
        // the low corner is on the map but the opposite one is not.
        assertNull(
            projection.terrainAt(projection.minX, projection.minY + projection.height - 1),
        )
        assertNull(projection.terrainAt(9999, 9999))
        assertTrue(projection.tiles.any { it == -1 })
        // Absence is exactly the engine's own view of the map.
        assertNull(testGame.gameInfo.tileMap.getIfTileExistsOrNull(9999, 9999))
    }

    @Test
    fun carriesWorldWrapSoTheClientRendersTheSameTopology() {
        val testGame = TestGame()
        testGame.makeRectangularMap(6, 8)
        testGame.gameInfo.tileMap.mapParameters.worldWrap = true

        assertTrue(LobbyTerrainProjection.build(testGame.gameInfo).worldWrap)
    }

    @Test
    fun startPositionsAreOneUnlabeledCoordinatePerMajorCivilization() {
        val testGame = TestGame()
        testGame.makeHexagonalMap(4)
        val first = testGame.addCiv(isPlayer = true)
        val second = testGame.addCiv()
        val firstTile = testGame.getTile(HexCoord(1, 0))
        val secondTile = testGame.getTile(HexCoord(-2, 1))
        testGame.addUnit("Warrior", first, firstTile)
        testGame.addUnit("Warrior", second, secondTile)

        val projection = LobbyTerrainProjection.build(testGame.gameInfo)
        val positions = projection.startPositionCoordinates()

        assertTrue(projection.isConsistent())
        assertEquals(2, positions.size)
        assertTrue(firstTile.position in positions)
        assertTrue(secondTile.position in positions)
        // Coordinates only: nothing ties a start to the civilization holding it.
        val encoded = json.encodeToString(LobbyTerrainProjection.serializer(), projection)
        assertFalse(encoded.contains(first.civName))
        assertFalse(encoded.contains(second.civName))
    }

    @Test
    fun disclosesNoGameplayStateBeyondTerrainAndStartPositions() {
        val testGame = TestGame()
        testGame.makeHexagonalMap(4)
        val civ = testGame.addCiv(isPlayer = true)
        val tile = testGame.getTile(HexCoord(1, 0))
        val resource = testGame.createResource()
        val improvement = testGame.createTileImprovement()
        tile.setTileResource(resource)
        tile.setImprovementBasic(improvement)
        val unit = testGame.addUnit("Warrior", civ, tile)

        val encoded = json.encodeToString(
            LobbyTerrainProjection.serializer(),
            LobbyTerrainProjection.build(testGame.gameInfo),
        )

        for (secret in listOf(
            resource.name,
            improvement.name,
            unit.name,
            civ.civName,
            civ.nation.name,
        )) assertFalse("Pregame terrain leaked [$secret]", encoded.contains(secret))
        // Terrain names are the only ruleset content the payload may name.
        for (field in listOf(
            "resource", "improvement", "unit", "city", "civilization", "owner",
            "visible", "naturalWonder", "turn", "victory",
        )) assertFalse(
            "Pregame terrain must not carry a [$field] field",
            encoded.contains("\"$field"),
        )
    }

    @Test
    fun rejectsMalformedProjectionsFailClosed() {
        val valid = LobbyTerrainProjection(
            worldWrap = false,
            minX = -1,
            minY = -1,
            width = 2,
            height = 2,
            terrainNames = listOf("Grassland", "Ocean"),
            tiles = listOf(0, 1, 1, 0),
            startPositions = listOf(-1, -1),
        )
        assertTrue(valid.isConsistent())

        assertFalse(valid.copy(terrainNames = listOf("Ocean", "Grassland")).isConsistent())
        assertFalse(valid.copy(terrainNames = listOf("Ocean", "Ocean")).isConsistent())
        assertFalse(valid.copy(terrainNames = emptyList()).isConsistent())
        assertFalse(valid.copy(tiles = listOf(0, 1, 1)).isConsistent())
        assertFalse(valid.copy(tiles = listOf(0, 1, 2, 0)).isConsistent())
        assertFalse(valid.copy(tiles = listOf(-1, -1, -1, -1)).isConsistent())
        assertFalse(valid.copy(startPositions = listOf(0)).isConsistent())
        assertFalse(valid.copy(startPositions = listOf(99, 99)).isConsistent())
        assertFalse(valid.copy(width = 0).isConsistent())
        assertFalse(
            valid.copy(width = LobbyTerrainProjection.maxBoundingBoxSide + 1).isConsistent(),
        )
        assertFalse(
            valid.copy(
                startPositions = List(2 * (LobbyTerrainProjection.maxStartPositions + 1)) { -1 },
            ).isConsistent(),
        )
    }

    @Test
    fun survivesAWireRoundTripUnderTheStrictClientDecoder() {
        val testGame = TestGame()
        testGame.makeRectangularMap(5, 5)
        val projection = LobbyTerrainProjection.build(testGame.gameInfo)
        val strict = Json { ignoreUnknownKeys = false; encodeDefaults = true }

        val decoded = strict.decodeFromString(
            LobbyTerrainProjection.serializer(),
            strict.encodeToString(LobbyTerrainProjection.serializer(), projection),
        )

        assertEquals(projection, decoded)
    }
}
