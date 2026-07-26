package com.unciv.logic.multiplayer.authoritative

import com.unciv.logic.ContentAddressedRuleset
import com.unciv.logic.GameExecutionContext
import com.unciv.logic.RulesetManifest
import com.unciv.logic.map.HexCoord
import com.unciv.testing.GdxTestRunner
import com.unciv.testing.TestGame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(GdxTestRunner::class)
class CapitalProjectUnitTests {
    @Test
    fun projectionAndWorkerDeriveProjectAndConsumeTheCanonicalUnit() {
        val fixture = fixture()
        val projected = fixture.engine.playerProjection(fixture.game, fixture.actor.civID)
            .ownUnits.single { it.id == fixture.unit.id }
        assertEquals("The Spaceship", projected.capitalProjectName)
        val hashBefore = fixture.engine.stateHash(fixture.game)

        fixture.engine.addUnitToCapitalProject(
            fixture.game,
            fixture.actor.civID,
            fixture.unit.id,
        )

        assertNull(fixture.actor.units.getUnitById(fixture.unit.id))
        assertEquals(1, fixture.actor.victoryManager.currentsSpaceshipParts[fixture.unit.name])
        assertEquals(
            0,
            fixture.engine.playerProjection(fixture.game, fixture.actor.civID)
                .ownUnits.count { it.capitalProjectName != null },
        )
        org.junit.Assert.assertNotEquals(hashBefore, fixture.engine.stateHash(fixture.game))
    }

    @Test
    fun wrongAccountAndNonCapitalLocationRejectWithoutMutation() {
        val wrongAccount = fixture()
        val hashBeforeWrongAccount = wrongAccount.engine.stateHash(wrongAccount.game)
        assertThrows(IllegalStateException::class.java) {
            wrongAccount.foreignEngine.addUnitToCapitalProject(
                wrongAccount.game,
                wrongAccount.actor.civID,
                wrongAccount.unit.id,
            )
        }
        assertEquals(hashBeforeWrongAccount, wrongAccount.engine.stateHash(wrongAccount.game))
        wrongAccount.game.currentPlayer = "Waiting civilization"
        assertNull(
            wrongAccount.engine.playerProjection(wrongAccount.game, wrongAccount.actor.civID)
                .ownUnits.single { it.id == wrongAccount.unit.id }
                .capitalProjectName,
        )

        val misplaced = fixture()
        misplaced.unit.removeFromTile()
        misplaced.unit.putInTile(misplaced.testGame.getTile(HexCoord(1, 0)))
        val hashBeforeMisplaced = misplaced.engine.stateHash(misplaced.game)
        assertNull(CapitalProjectUnitExecutor.projectName(misplaced.unit))
        assertThrows(IllegalArgumentException::class.java) {
            misplaced.engine.addUnitToCapitalProject(
                misplaced.game,
                misplaced.actor.civID,
                misplaced.unit.id,
            )
        }
        assertEquals(hashBeforeMisplaced, misplaced.engine.stateHash(misplaced.game))
    }

    private fun fixture(): Fixture {
        val testGame = TestGame()
        testGame.makeHexagonalMap(3)
        val actor = testGame.addCiv(isPlayer = true)
        actor.playerId = "account-1"
        val city = testGame.addCity(actor, testGame.getTile(HexCoord.Zero))
        val unit = testGame.addUnit("SS Booster", actor, city.getCenterTile())
        testGame.gameInfo.currentPlayer = actor.civID
        testGame.gameInfo.currentPlayerCiv = actor
        return Fixture(
            testGame,
            actor,
            unit,
            HeadlessGameEngine(context("account-1")),
            HeadlessGameEngine(context("account-2")),
        )
    }

    private fun context(actorId: String) = GameExecutionContext.authoritative(
        actorId = actorId,
        rulesetManifest = RulesetManifest(
            engineBuild = "capital-project-test",
            baseRuleset = ContentAddressedRuleset("Civ V - Vanilla", "0".repeat(64)),
        ),
        clockMillis = { 1_700_000_000_000L },
    )

    private data class Fixture(
        val testGame: TestGame,
        val actor: com.unciv.logic.civilization.Civilization,
        val unit: com.unciv.logic.map.mapunit.MapUnit,
        val engine: HeadlessGameEngine,
        val foreignEngine: HeadlessGameEngine,
    ) {
        val game get() = testGame.gameInfo
    }
}
