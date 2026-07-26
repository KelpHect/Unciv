package com.unciv.app.server.authoritative

import com.unciv.logic.civilization.AlertType
import com.unciv.logic.civilization.PopupAlert
import com.unciv.logic.civilization.diplomacy.DiplomacyFlags
import com.unciv.logic.civilization.diplomacy.DiplomaticModifiers
import com.unciv.logic.multiplayer.authoritative.CityStateProtectionResponse
import com.unciv.logic.multiplayer.authoritative.DiplomacyPromptType
import com.unciv.logic.multiplayer.authoritative.PlayerProjection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test

class PackagedWorkerCityStateParityTests {
    @Test(timeout = 300_000)
    fun cityStateDiplomacyActionsAreStableAcrossFreshWorkers() {
        val fixture = PackagedWorkerScenarioFixture(
            actorId = actorId,
            gameId = "00000000-0000-4000-8000-000000001001",
            serverSeed = 538_164_290L,
            cityStates = 1,
        )
        val responses = PackagedWorkerParityHarness.assertStableScenario { send ->
            val results = mutableListOf<WorkerResponse>()
            var response = send(fixture.createGameRequest(1_701_000_000_000L))
            results += response

            val civilizationId = requireNotNull(response.actorCivilizationId)
            val game = fixture.decode(response.snapshot)
            val actor = game.getCivilization(civilizationId)
            val opponent = game.civilizations.single {
                it.isMajorCiv() && it.civID != civilizationId
            }
            val cityState = game.getAliveCityStates().single()
            actor.diplomacyFunctions.makeCivilizationsMeet(cityState)
            actor.addGold(500 - actor.gold)
            var snapshot = fixture.encode(game)

            fun project(serverTime: Long): PlayerProjection {
                val projectionResponse = send(
                    fixture.request(
                        WorkerOperation.ProjectState(snapshot, civilizationId),
                        serverTime,
                    ),
                )
                results += projectionResponse
                return requireNotNull(projectionResponse.playerProjection)
            }

            var projection = project(1_701_000_010_000L)
            var partner = projection.cityStatePartners.single { it.civilizationId == cityState.civID }
            val goldGift = partner.availableGoldGifts.min()
            assertTrue(partner.canPledgeProtection)
            response = send(
                fixture.request(
                    WorkerOperation.GiftCityStateGold(
                        snapshot = snapshot,
                        actorCivilizationId = civilizationId,
                        cityStateCivilizationId = partner.civilizationId,
                        amount = goldGift,
                    ),
                    1_701_000_010_001L,
                ),
            )
            results += response
            snapshot = requireNotNull(response.snapshot)
            var canonicalGame = fixture.decode(snapshot)
            var canonicalActor = canonicalGame.getCivilization(civilizationId)
            var canonicalCityState = canonicalGame.getCivilization(cityState.civID)
            assertEquals(500 - goldGift, canonicalActor.gold)
            assertTrue(canonicalCityState.getDiplomacyManager(canonicalActor)!!.getInfluence() > 0f)

            projection = project(1_701_000_020_000L)
            partner = projection.cityStatePartners.single { it.civilizationId == cityState.civID }
            assertTrue(partner.canPledgeProtection)
            response = send(
                fixture.request(
                    WorkerOperation.SetCityStateProtection(
                        snapshot = snapshot,
                        actorCivilizationId = civilizationId,
                        cityStateCivilizationId = partner.civilizationId,
                        protect = true,
                    ),
                    1_701_000_020_001L,
                ),
            )
            results += response
            snapshot = requireNotNull(response.snapshot)

            canonicalGame = fixture.decode(snapshot)
            canonicalActor = canonicalGame.getCivilization(civilizationId)
            canonicalCityState = canonicalGame.getCivilization(cityState.civID)
            canonicalActor.getDiplomacyManager(canonicalCityState)!!.declareWar()
            canonicalActor.getDiplomacyManager(canonicalCityState)!!.removeFlag(DiplomacyFlags.DeclaredWar)
            canonicalCityState.getDiplomacyManager(canonicalActor)!!.removeFlag(DiplomacyFlags.DeclaredWar)
            snapshot = fixture.encode(canonicalGame)
            projection = project(1_701_000_030_000L)
            partner = projection.cityStatePartners.single { it.civilizationId == cityState.civID }
            assertTrue(partner.canNegotiatePeace)
            response = send(
                fixture.request(
                    WorkerOperation.NegotiateCityStatePeace(
                        snapshot = snapshot,
                        actorCivilizationId = civilizationId,
                        cityStateCivilizationId = partner.civilizationId,
                    ),
                    1_701_000_030_001L,
                ),
            )
            results += response
            snapshot = requireNotNull(response.snapshot)
            canonicalGame = fixture.decode(snapshot)
            canonicalActor = canonicalGame.getCivilization(civilizationId)
            canonicalCityState = canonicalGame.getCivilization(cityState.civID)
            assertFalse(canonicalActor.isAtWarWith(canonicalCityState))

            val canonicalOpponent = canonicalGame.getCivilization(opponent.civID)
            canonicalActor.diplomacyFunctions.makeCivilizationsMeet(canonicalOpponent)
            canonicalActor.popupAlerts.add(
                PopupAlert(
                    AlertType.BulliedProtectedMinor,
                    "${canonicalOpponent.civID}@${canonicalCityState.civID}",
                ),
            )
            snapshot = fixture.encode(canonicalGame)
            projection = project(1_701_000_040_000L)
            val prompt = projection.diplomacyPrompts.single {
                it.type == DiplomacyPromptType.BulliedProtectedMinor
            }
            assertTrue(CityStateProtectionResponse.Condemn in prompt.availableCityStateResponses)
            response = send(
                fixture.request(
                    WorkerOperation.RespondToCityStateProtectionPrompt(
                        snapshot = snapshot,
                        actorCivilizationId = civilizationId,
                        promptId = prompt.promptId,
                        response = CityStateProtectionResponse.Condemn,
                    ),
                    1_701_000_040_001L,
                ),
            )
            results += response
            snapshot = requireNotNull(response.snapshot)
            canonicalGame = fixture.decode(snapshot)
            canonicalActor = canonicalGame.getCivilization(civilizationId)
            val finalOpponent = canonicalGame.getCivilization(opponent.civID)
            assertTrue(
                finalOpponent.getDiplomacyManager(canonicalActor)!!
                    .hasModifier(DiplomaticModifiers.SidedWithProtectedMinor),
            )
            assertTrue(canonicalActor.popupAlerts.none { it.type == AlertType.BulliedProtectedMinor })
            results
        }

        assertEquals(9, responses.size)
    }

    companion object {
        private const val actorId = "account-city-state-parity"

        @JvmStatic
        @BeforeClass
        fun initializeRulesets() = PackagedWorkerParityHarness.initializeRulesets()
    }
}
