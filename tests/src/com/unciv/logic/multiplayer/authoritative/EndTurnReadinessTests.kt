package com.unciv.logic.multiplayer.authoritative

import com.unciv.logic.ContentAddressedRuleset
import com.unciv.logic.GameExecutionContext
import com.unciv.logic.RulesetManifest
import com.unciv.logic.civilization.CivFlags
import com.unciv.logic.map.HexCoord
import com.unciv.models.ruleset.BeliefType
import com.unciv.testing.GdxTestRunner
import com.unciv.testing.TestGame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(GdxTestRunner::class)
class EndTurnReadinessTests {
    @Test
    fun constructionAndTechnologyBlockersClearOnlyThroughCanonicalCommands() {
        val fixture = fixture()
        assertBlockedAndUnchanged(fixture, PendingEndTurnAction.PickConstruction)
        assertBlockedAndUnchanged(fixture, PendingEndTurnAction.PickTechnology)

        val city = fixture.actor.cities.single()
        val construction = fixture.engine.playerProjection(fixture.game, fixture.actor.civID)
            .ownCities.single().constructionOptions.first { it.queueable }.name
        fixture.engine.queueConstruction(
            fixture.game, fixture.actor.civID, city.id, construction,
        )
        assertFalse(PendingEndTurnAction.PickConstruction in fixture.pending())

        val technology = fixture.engine.playerProjection(fixture.game, fixture.actor.civID)
            .research.selectableTargets.first()
        fixture.engine.setResearchPath(fixture.game, fixture.actor.civID, technology)
        assertFalse(PendingEndTurnAction.PickTechnology in fixture.pending())
    }

    @Test
    fun sellableBuildingProjectionIsCanonicalAndTurnScoped() {
        val fixture = fixture()
        val city = fixture.actor.cities.single()
        city.cityConstructions.addBuilding("Monument")

        assertEquals(
            listOf("Monument"),
            fixture.engine.playerProjection(
                fixture.game, fixture.actor.civID,
            ).ownCities.single().sellableBuildings,
        )

        city.hasSoldBuildingThisTurn = true
        assertTrue(
            fixture.engine.playerProjection(
                fixture.game, fixture.actor.civID,
            ).ownCities.single().sellableBuildings.isEmpty(),
        )
        city.hasSoldBuildingThisTurn = false
        city.isPuppet = true
        assertTrue(
            fixture.engine.playerProjection(
                fixture.game, fixture.actor.civID,
            ).ownCities.single().sellableBuildings.isEmpty(),
        )
    }

    @Test
    fun policyAndPantheonBlockersClearOnlyThroughCanonicalCommands() {
        val policyFixture = fixture()
        policyFixture.actor.policies.freePolicies = 1
        policyFixture.actor.policies.shouldOpenPolicyPicker = true
        assertBlockedAndUnchanged(policyFixture, PendingEndTurnAction.PickPolicy)
        val policy = policyFixture.engine.playerProjection(
            policyFixture.game, policyFixture.actor.civID,
        ).policies.selectablePolicies.first()
        policyFixture.engine.adoptPolicy(
            policyFixture.game, policyFixture.actor.civID, policy,
        )
        assertFalse(PendingEndTurnAction.PickPolicy in policyFixture.pending())

        val religionFixture = fixture()
        religionFixture.actor.religionManager.storedFaith = 10_000
        assertBlockedAndUnchanged(
            religionFixture, PendingEndTurnAction.FoundOrExpandPantheon,
        )
        val choice = religionFixture.engine.playerProjection(
            religionFixture.game, religionFixture.actor.civID,
        ).religionChoice!!
        val beliefs = choice.requiredBeliefTypes.map { required ->
            choice.availableBeliefs.first { it.type == required }.name
        }
        religionFixture.engine.chooseReligiousBeliefs(
            religionFixture.game, religionFixture.actor.civID, beliefs, null, null,
        )
        assertFalse(
            PendingEndTurnAction.FoundOrExpandPantheon in religionFixture.pending(),
        )
    }

    @Test
    fun foundingEnhancementAndReformBlockersShareTheCanonicalBeliefCommand() {
        val fixture = fixture()
        fixture.actor.religionManager.storedFaith = 10_000
        resolveReligionChoice(fixture)

        val city = fixture.actor.cities.single()
        val foundingProphet = fixture.testGame.addUnit(
            "Great Prophet", fixture.actor, city.getCenterTile(),
        )
        foundingProphet.religion = fixture.actor.religionManager.religion!!.name
        fixture.engine.useReligiousUnit(
            fixture.game, fixture.actor.civID, foundingProphet.id,
            ReligiousUnitAction.FoundReligion,
        )
        assertBlockedAndUnchanged(fixture, PendingEndTurnAction.FoundReligion)
        resolveReligionChoice(fixture, withIdentity = true)
        assertFalse(PendingEndTurnAction.FoundReligion in fixture.pending())

        val enhancingProphet = fixture.testGame.addUnit(
            "Great Prophet", fixture.actor, city.getCenterTile(),
        )
        enhancingProphet.religion = fixture.actor.religionManager.religion!!.name
        fixture.engine.useReligiousUnit(
            fixture.game, fixture.actor.civID, enhancingProphet.id,
            ReligiousUnitAction.EnhanceReligion,
        )
        assertBlockedAndUnchanged(fixture, PendingEndTurnAction.EnhanceReligion)
        resolveReligionChoice(fixture)
        assertFalse(PendingEndTurnAction.EnhanceReligion in fixture.pending())

        fixture.actor.religionManager.freeBeliefs[BeliefType.Any.name] = 1
        assertBlockedAndUnchanged(fixture, PendingEndTurnAction.ReformReligion)
        resolveReligionChoice(fixture)
        assertFalse(PendingEndTurnAction.ReformReligion in fixture.pending())
    }

    @Test
    fun diplomaticVoteAndGreatPersonBlockersClearOnlyThroughCanonicalCommands() {
        val voteFixture = fixture()
        val other = voteFixture.testGame.addCiv(isPlayer = true)
        other.playerId = "account-2"
        voteFixture.testGame.addUnit(
            "Warrior", other, voteFixture.testGame.getTile(HexCoord(4, 0)),
        )
        voteFixture.actor.addFlag(CivFlags.TurnsTillNextDiplomaticVote.name, 0)
        assertBlockedAndUnchanged(voteFixture, PendingEndTurnAction.CastDiplomaticVote)
        voteFixture.engine.castDiplomaticVote(
            voteFixture.game, voteFixture.actor.civID, null,
        )
        assertFalse(PendingEndTurnAction.CastDiplomaticVote in voteFixture.pending())

        val greatPersonFixture = fixture()
        greatPersonFixture.actor.greatPeople.freeGreatPeople = 1
        assertBlockedAndUnchanged(greatPersonFixture, PendingEndTurnAction.PickGreatPerson)
        val unitName = greatPersonFixture.engine.playerProjection(
            greatPersonFixture.game, greatPersonFixture.actor.civID,
        ).selectableGreatPeople.first {
            !greatPersonFixture.game.ruleset.units.getValue(it).isWaterUnit
        }
        greatPersonFixture.engine.chooseGreatPerson(
            greatPersonFixture.game, greatPersonFixture.actor.civID, unitName,
        )
        assertFalse(PendingEndTurnAction.PickGreatPerson in greatPersonFixture.pending())
    }

    private fun assertBlockedAndUnchanged(
        fixture: Fixture,
        action: PendingEndTurnAction,
    ) {
        assertTrue(action in fixture.pending())
        val hashBefore = fixture.engine.stateHash(fixture.game)
        val currentPlayerBefore = fixture.game.currentPlayer
        val rejection = assertThrows(IllegalArgumentException::class.java) {
            fixture.engine.endTurn(fixture.game, fixture.actor.civID)
        }
        assertTrue(rejection.message.orEmpty().contains(action.wireName))
        assertEquals(hashBefore, fixture.engine.stateHash(fixture.game))
        assertEquals(currentPlayerBefore, fixture.game.currentPlayer)
    }

    private fun resolveReligionChoice(fixture: Fixture, withIdentity: Boolean = false) {
        val choice = fixture.engine.playerProjection(
            fixture.game, fixture.actor.civID,
        ).religionChoice!!
        val selected = mutableSetOf<String>()
        val beliefs = choice.requiredBeliefTypes.map { required ->
            choice.availableBeliefs.first {
                it.name !in selected &&
                    (required == ReligiousBeliefType.Any || it.type == required)
            }.also { selected += it.name }.name
        }
        val icon = if (withIdentity) choice.availableReligionIcons.first() else null
        fixture.engine.chooseReligiousBeliefs(
            fixture.game, fixture.actor.civID, beliefs, icon, icon,
        )
    }

    private fun fixture(): Fixture {
        val testGame = TestGame()
        testGame.makeHexagonalMap(5)
        val actor = testGame.addCiv(isPlayer = true)
        actor.playerId = "account-1"
        testGame.addCity(actor, testGame.getTile(HexCoord.Zero))
        testGame.gameInfo.currentPlayer = actor.civID
        testGame.gameInfo.currentPlayerCiv = actor
        return Fixture(
            testGame,
            testGame.gameInfo,
            actor,
            HeadlessGameEngine(GameExecutionContext.authoritative(
                actorId = "account-1",
                rulesetManifest = RulesetManifest(
                    engineBuild = "end-turn-readiness-test",
                    baseRuleset = ContentAddressedRuleset(
                        "Civ V - Vanilla", "0".repeat(64),
                    ),
                ),
                clockMillis = { 1_700_000_000_000L },
            )),
        )
    }

    private data class Fixture(
        val testGame: TestGame,
        val game: com.unciv.logic.GameInfo,
        val actor: com.unciv.logic.civilization.Civilization,
        val engine: HeadlessGameEngine,
    ) {
        fun pending() = engine.playerProjection(game, actor.civID).pendingTurnActions
    }
}
