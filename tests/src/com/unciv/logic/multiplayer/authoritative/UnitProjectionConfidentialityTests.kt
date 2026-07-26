package com.unciv.logic.multiplayer.authoritative

import com.unciv.logic.map.HexCoord
import com.unciv.testing.GdxTestRunner
import com.unciv.testing.TestGame
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(GdxTestRunner::class)
class UnitProjectionConfidentialityTests {
    @Test
    fun remainingMovementIsPresentForOwnUnitsAndRedactedForVisibleForeignUnits() {
        val testGame = TestGame()
        testGame.makeHexagonalMap(3)
        val observer = testGame.addCiv()
        val foreign = testGame.addCiv()
        val ownUnit = testGame.addUnit("Warrior", observer, testGame.getTile(HexCoord.Zero))
        val foreignUnit = testGame.addUnit("Warrior", foreign, testGame.getTile(HexCoord(1, 0)))
        ownUnit.currentMovement = 1.25f
        foreignUnit.currentMovement = 1.75f

        val projection = PlayerProjectionBuilder.build(testGame.gameInfo, observer)

        assertEquals(1.25f, projection.ownUnits.single().currentMovement)
        assertNull(projection.visibleForeignUnits.single().currentMovement)
        val encoded = Json { encodeDefaults = true }
            .encodeToString(PlayerProjection.serializer(), projection)
        assertTrue(encoded.contains("\"currentMovement\":null"))
        assertTrue(!encoded.contains("1.75"))
    }
}
