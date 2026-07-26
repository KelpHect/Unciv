package com.unciv.logic.multiplayer.authoritative

import com.unciv.logic.GameExecutionContext
import com.unciv.logic.ContentAddressedRuleset
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
class ResearchQueueOperationsTests {
    @Test
    fun independentTechnologiesExposeAndApplyOnlyBoundedActions() {
        val fixture = fixture()
        val independent = fixture.game.ruleset.technologies.values.asSequence()
            .filter { technology ->
                technology.name !in fixture.actor.tech.techsResearched &&
                    technology.prerequisites.all { it in fixture.actor.tech.techsResearched }
            }
            .map { it.name }
            .take(3)
            .toList()
        assertEquals(3, independent.size)
        fixture.actor.tech.techsToResearch.addAll(independent)

        val before = fixture.engine.playerProjection(fixture.game, fixture.actor.civID).research
        assertFalse(ResearchQueueAction.MoveToTop in before.queueEntries[0].availableActions)
        assertTrue(ResearchQueueAction.MoveToEnd in before.queueEntries[0].availableActions)
        assertTrue(ResearchQueueAction.Remove in before.queueEntries[1].availableActions)

        val firstResult = fixture.engine.manageResearchQueue(
            fixture.game, fixture.actor.civID, independent[0], 0, ResearchQueueAction.MoveToEnd,
        )
        assertEquals(listOf(independent[1], independent[2], independent[0]),
            fixture.actor.tech.techsToResearch)
        fixture.actor.tech.techsToResearch.clear()
        fixture.actor.tech.techsToResearch.addAll(independent)
        val secondResult = fixture.engine.manageResearchQueue(
            fixture.game, fixture.actor.civID, independent[0], 0, ResearchQueueAction.MoveToEnd,
        )
        assertEquals(listOf(independent[1], independent[2], independent[0]),
            fixture.actor.tech.techsToResearch)
        assertEquals(firstResult.canonicalStateHash, secondResult.canonicalStateHash)
    }

    @Test
    fun prerequisiteOrderAndExactQueueIdentityFailClosed() {
        val fixture = fixture()
        val path = fixture.game.ruleset.technologies.values.asSequence()
            .map { fixture.actor.tech.getRequiredTechsToDestination(it).map { technology -> technology.name } }
            .first { it.size >= 3 }
        fixture.actor.tech.techsToResearch.addAll(path)

        val projection = fixture.engine.playerProjection(fixture.game, fixture.actor.civID).research
        assertFalse(ResearchQueueAction.MoveToEnd in projection.queueEntries.first().availableActions)
        assertFalse(ResearchQueueAction.Remove in projection.queueEntries.first().availableActions)
        assertTrue(ResearchQueueAction.Remove in projection.queueEntries.last().availableActions)
        val hashBefore = fixture.engine.stateHash(fixture.game)

        assertThrows(IllegalStateException::class.java) {
            fixture.engine.manageResearchQueue(
                fixture.game, fixture.actor.civID, path.first(), 0, ResearchQueueAction.MoveToEnd,
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            fixture.engine.manageResearchQueue(
                fixture.game, fixture.actor.civID, path.last(), 0, ResearchQueueAction.Remove,
            )
        }
        assertThrows(IllegalStateException::class.java) {
            fixture.foreignEngine.manageResearchQueue(
                fixture.game, fixture.actor.civID, path.last(), path.lastIndex,
                ResearchQueueAction.Remove,
            )
        }
        assertEquals(hashBefore, fixture.engine.stateHash(fixture.game))
    }

    private fun fixture(): Fixture {
        val testGame = TestGame()
        testGame.makeHexagonalMap(4)
        val actor = testGame.addCiv(isPlayer = true)
        actor.playerId = "account-1"
        testGame.addCity(actor, testGame.getTile(HexCoord.Zero))
        testGame.gameInfo.currentPlayer = actor.civID
        testGame.gameInfo.currentPlayerCiv = actor
        return Fixture(
            testGame.gameInfo,
            actor,
            HeadlessGameEngine(context("account-1")),
            HeadlessGameEngine(context("account-2")),
        )
    }

    private fun context(actorId: String) = GameExecutionContext.authoritative(
        actorId = actorId,
        rulesetManifest = RulesetManifest(
            engineBuild = "research-queue-test",
            baseRuleset = ContentAddressedRuleset("Civ V - Vanilla", "0".repeat(64)),
        ),
        clockMillis = { 1_700_000_000_000L },
    )

    private data class Fixture(
        val game: com.unciv.logic.GameInfo,
        val actor: com.unciv.logic.civilization.Civilization,
        val engine: HeadlessGameEngine,
        val foreignEngine: HeadlessGameEngine,
    )
}
