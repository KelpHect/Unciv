package com.unciv.logic.multiplayer.authoritative

import com.unciv.logic.ContentAddressedRuleset
import com.unciv.logic.GameExecutionContext
import com.unciv.logic.RulesetManifest
import com.unciv.logic.civilization.diplomacy.DiplomaticStatus
import com.unciv.logic.civilization.diplomacy.DiplomacyManager
import com.unciv.logic.map.HexCoord
import com.unciv.models.ruleset.Policy
import com.unciv.models.ruleset.PolicyBranch
import com.unciv.testing.GdxTestRunner
import com.unciv.testing.TestGame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(GdxTestRunner::class)
class PolicyAuthorityTests {
    @Test
    fun ideologyBranchAndTenetUseTheProjectedCanonicalPolicyPath() {
        val fixture = fixture()
        fixture.actor.tech.era = fixture.game.ruleset.eras.getValue("Industrial era")
        fixture.actor.policies.freePolicies = 2
        fixture.actor.policies.shouldOpenPolicyPicker = true

        val before = fixture.engine.playerProjection(fixture.game, fixture.actor.civID)
        assertTrue("Freedom" in before.policies.selectablePolicies)
        assertTrue("Autocracy" in before.policies.selectablePolicies)
        assertTrue("Order" in before.policies.selectablePolicies)
        assertTrue(PendingEndTurnAction.PickPolicy in before.pendingTurnActions)

        fixture.engine.adoptPolicy(fixture.game, fixture.actor.civID, "Freedom")
        val afterIdeology = fixture.engine.playerProjection(fixture.game, fixture.actor.civID)
        assertTrue("Freedom" in afterIdeology.policies.adoptedPolicies)
        assertFalse("Autocracy" in afterIdeology.policies.selectablePolicies)
        assertFalse("Order" in afterIdeology.policies.selectablePolicies)
        assertTrue("Constitution" in afterIdeology.policies.selectablePolicies)

        fixture.engine.adoptPolicy(fixture.game, fixture.actor.civID, "Constitution")
        val afterTenet = fixture.engine.playerProjection(fixture.game, fixture.actor.civID)
        assertTrue("Constitution" in afterTenet.policies.adoptedPolicies)
        assertEquals(0, afterTenet.policies.freePolicies)
        assertFalse(PendingEndTurnAction.PickPolicy in afterTenet.pendingTurnActions)

        fixture.foreign.tech.era = fixture.game.ruleset.eras.getValue("Industrial era")
        fixture.foreign.policies.freePolicies = 1
        fixture.foreign.policies.adopt(fixture.game.ruleset.policies.getValue("Order"))
        fixture.actor.diplomacy[fixture.foreign.civID] =
            DiplomacyManager(fixture.actor, fixture.foreign).apply {
                diplomaticStatus = DiplomaticStatus.Peace
            }
        fixture.foreign.diplomacy[fixture.actor.civID] =
            DiplomacyManager(fixture.foreign, fixture.actor).apply {
                diplomaticStatus = DiplomaticStatus.Peace
            }
        val publicBranches = fixture.engine.playerProjection(fixture.game, fixture.actor.civID)
            .diplomacyPartners.single().adoptedPolicyBranches
        assertEquals(listOf("Order"), publicBranches)
    }

    @Test
    fun modDefinedPolicyChoicesRemainDataDrivenAndFailClosed() {
        val fixture = fixture()
        val branch = PolicyBranch().apply {
            name = "Modded Governance"
            era = fixture.actor.getEra().name
            requires = arrayListOf()
            this.branch = this
        }
        val first = policy("Community Mandate", branch)
        val second = policy("Open Assemblies", branch)
        val completion = policy("Modded Governance Complete", branch)
        branch.policies = arrayListOf(first, second, completion)
        fixture.game.ruleset.policyBranches[branch.name] = branch
        for (policy in listOf(branch, first, second, completion))
            fixture.game.ruleset.policies[policy.name] = policy
        fixture.actor.policies.freePolicies = 2

        val before = fixture.engine.playerProjection(fixture.game, fixture.actor.civID)
        assertTrue(branch.name in before.policies.selectablePolicies)

        fixture.engine.adoptPolicy(fixture.game, fixture.actor.civID, branch.name)
        val choices = fixture.engine.playerProjection(fixture.game, fixture.actor.civID)
            .policies.selectablePolicies
        assertTrue(first.name in choices)
        assertTrue(second.name in choices)

        val hashBeforeRejection = fixture.engine.stateHash(fixture.game)
        assertThrows(IllegalStateException::class.java) {
            fixture.foreignEngine.adoptPolicy(fixture.game, fixture.actor.civID, first.name)
        }
        assertEquals(hashBeforeRejection, fixture.engine.stateHash(fixture.game))

        fixture.engine.adoptPolicy(fixture.game, fixture.actor.civID, second.name)
        assertTrue(second.name in fixture.actor.policies.getAdoptedPolicies())
    }

    private fun policy(name: String, branch: PolicyBranch) = Policy().apply {
        this.name = name
        this.branch = branch
        requires = arrayListOf(branch.name)
    }

    private fun fixture(): Fixture {
        val testGame = TestGame()
        testGame.makeHexagonalMap(4)
        val actor = testGame.addCiv(isPlayer = true)
        actor.playerId = "account-1"
        testGame.addCity(actor, testGame.getTile(HexCoord.Zero))
        val foreign = testGame.addCiv(isPlayer = true)
        foreign.playerId = "account-2"
        testGame.addCity(foreign, testGame.getTile(HexCoord(2, 0)))
        testGame.gameInfo.currentPlayer = actor.civID
        testGame.gameInfo.currentPlayerCiv = actor
        return Fixture(
            testGame.gameInfo,
            actor,
            foreign,
            HeadlessGameEngine(context("account-1")),
            HeadlessGameEngine(context("account-2")),
        )
    }

    private fun context(actorId: String) = GameExecutionContext.authoritative(
        actorId = actorId,
        rulesetManifest = RulesetManifest(
            engineBuild = "policy-authority-test",
            baseRuleset = ContentAddressedRuleset("Civ V - Vanilla", "0".repeat(64)),
        ),
        clockMillis = { 1_700_000_000_000L },
    )

    private data class Fixture(
        val game: com.unciv.logic.GameInfo,
        val actor: com.unciv.logic.civilization.Civilization,
        val foreign: com.unciv.logic.civilization.Civilization,
        val engine: HeadlessGameEngine,
        val foreignEngine: HeadlessGameEngine,
    )
}
