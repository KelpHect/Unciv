package com.unciv.logic.multiplayer.authoritative

import com.unciv.logic.map.HexCoord
import com.unciv.testing.GdxTestRunner
import com.unciv.testing.TestGame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(GdxTestRunner::class)
class WonderEventProjectionTests {
    @Test
    fun completedWonderCreatesDurableCanonicalEvent() {
        val testGame = TestGame()
        testGame.makeHexagonalMap(4)
        val builder = testGame.addCiv()
        val city = testGame.addCity(builder, testGame.getTile(HexCoord.Zero))
        val wonder = testGame.createWonder("[+1 Culture]")
        testGame.gameInfo.turns = 7

        assertTrue(city.cityConstructions.completeConstruction(wonder))

        val event = testGame.gameInfo.wonderCompletionEvents.single()
        assertEquals(7, event.turn)
        assertEquals(wonder.name, event.wonderName)
        assertEquals(builder.civID, event.builderCivilizationId)
        assertEquals(city.id, event.cityId)
        assertEquals(event, testGame.gameInfo.clone().wonderCompletionEvents.single())
    }

    @Test
    fun projectionDisclosesOnlyLegallyKnownBuilderAndLocation() {
        val testGame = TestGame()
        testGame.makeHexagonalMap(5)
        val builder = testGame.addCiv()
        val city = testGame.addCity(builder, testGame.getTile(HexCoord.Zero))
        val knownObserver = testGame.addCiv()
        val unknownObserver = testGame.addCiv()
        builder.diplomacyFunctions.makeCivilizationsMeet(knownObserver)
        val wonder = testGame.createWonder("[+1 Culture]")
        testGame.gameInfo.turns = 3
        city.cityConstructions.completeConstruction(wonder)

        val hiddenLocation = PlayerProjectionBuilder.build(testGame.gameInfo, knownObserver)
            .wonderEvents.single()
        assertEquals(builder.civID, hiddenLocation.builderCivilizationId)
        assertNull(hiddenLocation.cityId)
        assertNull(hiddenLocation.x)

        val fullyHidden = PlayerProjectionBuilder.build(testGame.gameInfo, unknownObserver)
            .wonderEvents.single()
        assertNull(fullyHidden.builderCivilizationId)
        assertNull(fullyHidden.cityName)
        assertNotNull(fullyHidden.effectSummary)

        city.getCenterTile().setExplored(knownObserver, true)
        val disclosed = PlayerProjectionBuilder.build(testGame.gameInfo, knownObserver)
            .wonderEvents.single()
        assertEquals(city.id, disclosed.cityId)
        assertEquals(city.name, disclosed.cityName)
        assertEquals(city.location.x, disclosed.x)
        assertEquals(city.location.y, disclosed.y)
    }
}
