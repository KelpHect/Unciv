package com.unciv.logic.multiplayer.authoritative

import com.unciv.logic.ContentAddressedRuleset
import com.unciv.logic.GameExecutionContext
import com.unciv.logic.RulesetManifest
import com.unciv.logic.map.HexCoord
import com.unciv.models.ruleset.unique.UniqueType
import com.unciv.testing.GdxTestRunner
import com.unciv.testing.TestGame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(GdxTestRunner::class)
class InstantImprovementCommandTests {
    @Test
    fun projectionAndWorkerExecuteTheExactCanonicalImprovementAction() {
        val fixture = fixture()
        val projected = fixture.projectedActions()
        assertTrue(projected.isNotEmpty())
        val selected = projected.first()
        val hashBefore = fixture.engine.stateHash(fixture.game)
        val improvementBefore = fixture.unit.currentTile.improvement

        fixture.engine.createInstantImprovement(
            fixture.game, fixture.actor.civID, fixture.unit.id, selected.actionId,
        )

        assertNotEquals(improvementBefore, fixture.tile.improvement)
        assertNotEquals(hashBefore, fixture.engine.stateHash(fixture.game))
        assertTrue(
            fixture.engine.playerProjection(fixture.game, fixture.actor.civID)
                .ownUnits.none { unit ->
                    unit.availableInstantImprovementActions.any {
                        it.actionId == selected.actionId
                    }
                },
        )
    }

    @Test
    fun waterImprovementIsAlsoDerivedAndExecutedByTheWorker() {
        val testGame = TestGame()
        testGame.makeHexagonalMap(3)
        val actor = testGame.addCiv(isPlayer = true)
        actor.playerId = "account-1"
        val city = testGame.addCity(actor, testGame.getTile(HexCoord.Zero))
        val tile = testGame.getTile(HexCoord(1, 0))
        tile.baseTerrain = "Coast"
        tile.tileResource = testGame.ruleset.tileResources["Fish"]
        tile.setTransients()
        city.expansion.takeOwnership(tile)
        actor.tech.addTechnology("Sailing")
        val unit = testGame.addUnit("Work Boats", actor, tile)
        testGame.gameInfo.currentPlayer = actor.civID
        testGame.gameInfo.currentPlayerCiv = actor
        val engine = HeadlessGameEngine(context("account-1"))
        assertTrue(tile.isWater)
        assertTrue(unit.hasUnique(UniqueType.CreateWaterImprovements))
        val improvement = tile.tileResource!!.getImprovingImprovement(tile, unit.cache.state)!!
        assertTrue(
            tile.improvementFunctions.canBuildImprovement(improvement, unit.cache.state),
        )
        val action = engine.playerProjection(testGame.gameInfo, actor.civID)
            .ownUnits.single { it.id == unit.id }
            .availableInstantImprovementActions.single()

        engine.createInstantImprovement(
            testGame.gameInfo, actor.civID, unit.id, action.actionId,
        )

        assertEquals("Fishing Boats", tile.improvement)
        assertTrue(actor.units.getUnitById(unit.id) == null)
    }

    @Test
    fun oneFilterProducingSeveralImprovementsMapsEachButtonToItsOwnOpaqueAction() {
        val testGame = TestGame()
        testGame.makeHexagonalMap(3)
        val actor = testGame.addCiv(isPlayer = true)
        val city = testGame.addCity(actor, testGame.getTile(HexCoord.Zero))
        val tile = testGame.getTile(HexCoord(1, 0))
        city.expansion.takeOwnership(tile)
        val unit = testGame.addDefaultMeleeUnitWithUniques(
            actor,
            tile,
            "Can instantly construct a [Great Improvement] improvement",
        )
        actor.playerId = "account-1"
        testGame.gameInfo.currentPlayer = actor.civID
        testGame.gameInfo.currentPlayerCiv = actor
        val engine = HeadlessGameEngine(context("account-1"))
        val actions = engine.playerProjection(testGame.gameInfo, actor.civID)
            .ownUnits.single { it.id == unit.id }
            .availableInstantImprovementActions
        assertTrue(actions.size > 1)
        assertEquals(actions.size, actions.map { it.actionId }.distinct().size)
        val selected = actions.last()

        engine.createInstantImprovement(
            testGame.gameInfo, actor.civID, unit.id, selected.actionId,
        )

        assertEquals(
            selected.title.substringAfter("Create [").substringBefore("]"),
            tile.improvement,
        )
    }

    @Test
    fun forgedWrongAccountAndOutOfTurnActionsRejectWithoutMutationOrDisclosure() {
        val forged = fixture()
        val hashBeforeForged = forged.engine.stateHash(forged.game)
        assertThrows(IllegalStateException::class.java) {
            forged.engine.createInstantImprovement(
                forged.game, forged.actor.civID, forged.unit.id, "f".repeat(64),
            )
        }
        assertEquals(hashBeforeForged, forged.engine.stateHash(forged.game))

        val wrongAccount = fixture()
        val hashBeforeWrongAccount = wrongAccount.engine.stateHash(wrongAccount.game)
        assertThrows(IllegalStateException::class.java) {
            wrongAccount.foreignEngine.createInstantImprovement(
                wrongAccount.game,
                wrongAccount.actor.civID,
                wrongAccount.unit.id,
                wrongAccount.projectedActions().first().actionId,
            )
        }
        assertEquals(hashBeforeWrongAccount, wrongAccount.engine.stateHash(wrongAccount.game))

        val outOfTurn = fixture()
        outOfTurn.game.currentPlayer = "Waiting civilization"
        assertFalse(
            outOfTurn.engine.playerProjection(outOfTurn.game, outOfTurn.actor.civID)
                .ownUnits.single { it.id == outOfTurn.unit.id }
                .availableInstantImprovementActions.isNotEmpty(),
        )
    }

    private fun fixture(): Fixture {
        val testGame = TestGame()
        testGame.makeHexagonalMap(3)
        val actor = testGame.addCiv(isPlayer = true)
        actor.playerId = "account-1"
        testGame.addCity(actor, testGame.getTile(HexCoord.Zero))
        val tile = testGame.getTile(HexCoord(1, 0))
        val unit = testGame.addUnit("Great Scientist", actor, tile)
        testGame.gameInfo.currentPlayer = actor.civID
        testGame.gameInfo.currentPlayerCiv = actor
        return Fixture(
            testGame,
            actor,
            unit,
            tile,
            HeadlessGameEngine(context("account-1")),
            HeadlessGameEngine(context("account-2")),
        )
    }

    private fun context(actorId: String) = GameExecutionContext.authoritative(
        actorId = actorId,
        rulesetManifest = RulesetManifest(
            engineBuild = "instant-improvement-test",
            baseRuleset = ContentAddressedRuleset("Civ V - Vanilla", "0".repeat(64)),
        ),
        clockMillis = { 1_700_000_000_000L },
    )

    private data class Fixture(
        val testGame: TestGame,
        val actor: com.unciv.logic.civilization.Civilization,
        val unit: com.unciv.logic.map.mapunit.MapUnit,
        val tile: com.unciv.logic.map.tile.Tile,
        val engine: HeadlessGameEngine,
        val foreignEngine: HeadlessGameEngine,
    ) {
        val game get() = testGame.gameInfo

        fun projectedActions() = engine.playerProjection(game, actor.civID)
            .ownUnits.single { it.id == unit.id }
            .availableInstantImprovementActions
    }
}
