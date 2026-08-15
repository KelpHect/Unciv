package com.unciv.logic.multiplayer.authoritative

import com.unciv.logic.map.HexCoord
import com.unciv.testing.GdxTestRunner
import com.unciv.testing.TestGame
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * A rival city is disclosed only where the server already decided the player can
 * see the tile it stands on, and only as the few fields needed to draw it.
 */
@RunWith(GdxTestRunner::class)
class ForeignCityProjectionTests {
    @Test
    fun rivalCityOnAVisibleTileIsProjectedWithOnlyItsVisibleBorder() {
        val testGame = TestGame()
        testGame.makeHexagonalMap(3)
        val observer = testGame.addCiv()
        val rival = testGame.addCiv()
        val centre = testGame.getTile(HexCoord(1, 0))
        val nearBorder = testGame.getTile(HexCoord(2, 0))
        val farBorder = testGame.getTile(HexCoord(0, 2))
        val city = testGame.addCity(rival, centre)
        city.tiles = hashSetOf(centre.position, nearBorder.position, farBorder.position)
        city.expansion.setTransients()

        // The observer sees the city centre and one of its border tiles, not the other.
        observer.viewableTiles = setOf(centre, nearBorder)

        val projection = PlayerProjectionBuilder.build(testGame.gameInfo, observer)
        val projected = projection.visibleForeignCities.single()

        assertEquals(city.id, projected.id)
        assertEquals(rival.civID, projected.civilizationId)
        assertEquals(1, projected.x)
        assertEquals(0, projected.y)
        assertEquals(
            "Only the border tiles the observer can see may be disclosed",
            listOf(1 to 0, 2 to 0),
            projected.ownedTiles.map { it.x to it.y },
        )
        assertTrue("A rival city is never an own city", projection.ownCities.isEmpty())
    }

    @Test
    fun rivalCityOnANonVisibleTileIsAbsentFromTheProjection() {
        val testGame = TestGame()
        testGame.makeHexagonalMap(3)
        val observer = testGame.addCiv()
        val rival = testGame.addCiv()
        val centre = testGame.getTile(HexCoord(1, 0))
        val city = testGame.addCity(rival, centre)
        city.name = "Hidden Capital"

        // Explored is deliberately not enough: the tile must be currently visible.
        centre.setExplored(observer, true)
        observer.viewableTiles = emptySet()

        val projection = PlayerProjectionBuilder.build(testGame.gameInfo, observer)

        assertTrue(
            "A city the server did not make visible must not be disclosed",
            projection.visibleForeignCities.isEmpty(),
        )
        val encoded = Json { encodeDefaults = true }
            .encodeToString(PlayerProjection.serializer(), projection)
        assertFalse(encoded.contains("Hidden Capital"))
        assertFalse(encoded.contains(city.id))
    }

    @Test
    fun theViewersOwnCityIsNeverProjectedAsForeign() {
        val testGame = TestGame()
        testGame.makeHexagonalMap(3)
        val observer = testGame.addCiv()
        val centre = testGame.getTile(HexCoord(1, 0))
        testGame.addCity(observer, centre)
        observer.viewableTiles = setOf(centre)

        val projection = PlayerProjectionBuilder.build(testGame.gameInfo, observer)

        assertEquals(1, projection.ownCities.size)
        assertTrue(projection.visibleForeignCities.isEmpty())
    }

    @Test
    fun projectedForeignCityCarriesNoInteriorCityState() {
        // The disclosure boundary is the type itself: if someone adds population,
        // health or construction to it, this fails and forces a policy review.
        val leaves = ProjectedForeignCity.serializer().descriptor
        val names = (0 until leaves.elementsCount).map { leaves.getElementName(it) }.toSet()
        assertEquals(
            setOf("id", "name", "civilizationId", "x", "y", "ownedTiles"),
            names,
        )
    }
}
