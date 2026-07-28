package com.unciv.logic.multiplayer.authoritative

import com.unciv.logic.map.HexCoord
import com.unciv.testing.GdxTestRunner
import com.unciv.testing.TestGame
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(GdxTestRunner::class)
class TileProjectionTests {
    @Test
    fun exploredTerrainIsProjectedWhileUnrevealedResourceIsAbsent() {
        val testGame = TestGame()
        testGame.makeHexagonalMap(3)
        val observer = testGame.addCiv()
        val tile = testGame.getTile(HexCoord.Zero)
        tile.setExplored(observer, true)
        observer.viewableTiles = setOf(tile)
        tile.setTileResource("Coal")

        val hidden = PlayerProjectionBuilder.build(testGame.gameInfo, observer)
            .exploredTiles.single { it.x == 0 && it.y == 0 }

        assertEquals(tile.baseTerrain, hidden.baseTerrain)
        assertEquals(tile.terrainFeatures.sorted(), hidden.terrainFeatures)
        assertNull(hidden.improvementName)
        assertNull(hidden.improvementPillaged)
        assertNull(hidden.resourceName)
        assertNull(hidden.resourceAmount)
        assertTrue(!Json.encodeToString(ProjectedTileVisibility.serializer(), hidden)
            .contains("Coal"))

        observer.tech.addTechnology(testGame.ruleset.tileResources["Coal"]!!.revealedBy!!)
        val revealed = PlayerProjectionBuilder.build(testGame.gameInfo, observer)
            .exploredTiles.single { it.x == 0 && it.y == 0 }

        assertEquals("Coal", revealed.resourceName)
        assertNotNull(revealed.resourceAmount)
    }
}
