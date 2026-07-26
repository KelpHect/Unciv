package com.unciv.logic.multiplayer.authoritative

import com.unciv.logic.ContentAddressedRuleset
import com.unciv.logic.GameExecutionContext
import com.unciv.logic.RulesetManifest
import com.unciv.logic.map.HexCoord
import com.unciv.testing.GdxTestRunner
import com.unciv.testing.TestGame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(GdxTestRunner::class)
class CityTileBatchPurchaseTests {
    @Test
    fun projectionAndWorkerDeriveTheCompleteAtomicRingPurchase() {
        val fixture = fixture()
        fixture.actor.addGold(10_000)
        val option = fixture.engine.playerProjection(fixture.game, fixture.actor.civID)
            .ownCities.single().tileBatchPurchases.first()
        assertTrue(option.tileCount >= 2)
        assertTrue(option.affordable)
        val ownedBefore = fixture.ownedTileCount()
        val goldBefore = fixture.actor.gold

        fixture.engine.buyCityTileBatch(
            fixture.game, fixture.actor.civID, fixture.city.id, option.ring,
        )

        assertEquals(ownedBefore + option.tileCount, fixture.ownedTileCount())
        assertEquals(goldBefore - option.goldCost, fixture.actor.gold)
        assertFalse(fixture.engine.playerProjection(fixture.game, fixture.actor.civID)
            .ownCities.single().tileBatchPurchases.any { it.ring == option.ring })
        val hashAfter = fixture.engine.stateHash(fixture.game)
        assertThrows(IllegalStateException::class.java) {
            fixture.engine.buyCityTileBatch(
                fixture.game, fixture.actor.civID, fixture.city.id, option.ring,
            )
        }
        assertEquals(hashAfter, fixture.engine.stateHash(fixture.game))
    }

    @Test
    fun unaffordableForeignStaleAndOutOfRangeBatchesFailWithoutMutation() {
        val fixture = fixture()
        fixture.actor.addGold(-fixture.actor.gold)
        val option = fixture.engine.playerProjection(fixture.game, fixture.actor.civID)
            .ownCities.single().tileBatchPurchases.first()
        assertFalse(option.affordable)
        val hashBefore = fixture.engine.stateHash(fixture.game)
        val ownedBefore = fixture.ownedTileCount()

        assertThrows(IllegalArgumentException::class.java) {
            fixture.engine.buyCityTileBatch(
                fixture.game, fixture.actor.civID, fixture.city.id, option.ring,
            )
        }
        assertThrows(IllegalStateException::class.java) {
            fixture.foreignEngine.buyCityTileBatch(
                fixture.game, fixture.actor.civID, fixture.city.id, option.ring,
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            fixture.engine.buyCityTileBatch(
                fixture.game, fixture.actor.civID, fixture.city.id, 33,
            )
        }
        assertEquals(hashBefore, fixture.engine.stateHash(fixture.game))
        assertEquals(ownedBefore, fixture.ownedTileCount())
    }

    private fun fixture(): Fixture {
        val testGame = TestGame()
        testGame.makeHexagonalMap(5)
        val actor = testGame.addCiv(isPlayer = true)
        actor.playerId = "account-1"
        val city = testGame.addCity(actor, testGame.getTile(HexCoord.Zero))
        testGame.gameInfo.currentPlayer = actor.civID
        testGame.gameInfo.currentPlayerCiv = actor
        return Fixture(
            testGame.gameInfo,
            actor,
            city,
            HeadlessGameEngine(context("account-1")),
            HeadlessGameEngine(context("account-2")),
        )
    }

    private fun context(actorId: String) = GameExecutionContext.authoritative(
        actorId = actorId,
        rulesetManifest = RulesetManifest(
            engineBuild = "city-tile-batch-test",
            baseRuleset = ContentAddressedRuleset("Civ V - Vanilla", "0".repeat(64)),
        ),
        clockMillis = { 1_700_000_000_000L },
    )

    private data class Fixture(
        val game: com.unciv.logic.GameInfo,
        val actor: com.unciv.logic.civilization.Civilization,
        val city: com.unciv.logic.city.City,
        val engine: HeadlessGameEngine,
        val foreignEngine: HeadlessGameEngine,
    ) {
        fun ownedTileCount() = game.tileMap.tileList.count { it.owningCity == city }
    }
}
