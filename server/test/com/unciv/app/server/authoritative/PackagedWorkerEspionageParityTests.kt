package com.unciv.app.server.authoritative

import com.unciv.logic.multiplayer.authoritative.PlayerProjection
import com.unciv.models.SpyAction
import com.unciv.models.ruleset.unique.UniqueType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test

class PackagedWorkerEspionageParityTests {
    @Test(timeout = 300_000)
    fun espionageMovementAndCoupAreStableAcrossFreshWorkers() {
        val fixture = PackagedWorkerScenarioFixture(
            actorId = actorId,
            gameId = "00000000-0000-4000-8000-000000000801",
            serverSeed = 681_204_973L,
            cityStates = 1,
        )
        val responses = PackagedWorkerParityHarness.assertStableScenario { send ->
            val results = mutableListOf<WorkerResponse>()
            var response = send(fixture.createGameRequest(1_700_800_000_000L))
            results += response

            val civilizationId = requireNotNull(response.actorCivilizationId)
            val game = fixture.decode(response.snapshot)
            val actor = game.getCivilization(civilizationId)
            val majorOpponent = game.civilizations.single {
                it.isMajorCiv() && it.civID != civilizationId
            }
            val cityState = game.getAliveCityStates().single()
            fun ensureCapital(civilization: com.unciv.logic.civilization.Civilization) =
                civilization.getCapital() ?: civilization.addCity(
                    civilization.units.getCivUnits()
                        .first { it.hasUnique(UniqueType.FoundCity) }
                        .currentTile.position,
                )

            val opponentCity = ensureCapital(majorOpponent)
            val cityStateCity = ensureCapital(cityState)
            opponentCity.getCenterTile().setExplored(actor, true)
            val spy = actor.espionageManager.addSpy()
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

            var projection = project(1_700_800_010_000L)
            var projectedSpy = projection.spies.single { it.name == spy.name }
            assertTrue(opponentCity.id in projectedSpy.availableCityIds)
            response = send(
                fixture.request(
                    WorkerOperation.MoveSpy(
                        snapshot = snapshot,
                        actorCivilizationId = civilizationId,
                        spyName = projectedSpy.name,
                        cityId = opponentCity.id,
                    ),
                    1_700_800_010_001L,
                ),
            )
            results += response
            snapshot = requireNotNull(response.snapshot)
            var canonicalGame = fixture.decode(snapshot)
            var canonicalSpy = canonicalGame.getCivilization(civilizationId)
                .espionageManager.spyList.single { it.name == spy.name }
            assertEquals(opponentCity.id, canonicalSpy.getCity().id)
            assertEquals(SpyAction.Moving, canonicalSpy.action)

            projection = project(1_700_800_020_000L)
            projectedSpy = projection.spies.single { it.name == spy.name }
            assertTrue(projectedSpy.canMoveToHideout)
            response = send(
                fixture.request(
                    WorkerOperation.MoveSpy(
                        snapshot = snapshot,
                        actorCivilizationId = civilizationId,
                        spyName = projectedSpy.name,
                        cityId = null,
                    ),
                    1_700_800_020_001L,
                ),
            )
            results += response
            snapshot = requireNotNull(response.snapshot)

            canonicalGame = fixture.decode(snapshot)
            val canonicalActor = canonicalGame.getCivilization(civilizationId)
            val canonicalOpponent = canonicalGame.getCivilization(majorOpponent.civID)
            val canonicalCityState = canonicalGame.getCivilization(cityState.civID)
            canonicalActor.diplomacyFunctions.makeCivilizationsMeet(canonicalCityState)
            canonicalActor.diplomacyFunctions.makeCivilizationsMeet(canonicalOpponent)
            canonicalOpponent.diplomacyFunctions.makeCivilizationsMeet(canonicalCityState)
            canonicalCityState.getDiplomacyManager(canonicalOpponent)!!.addInfluence(100f)
            val canonicalCityStateCity = canonicalCityState.getCapital()!!
            canonicalCityStateCity.getCenterTile().setExplored(canonicalActor, true)
            canonicalSpy = canonicalActor.espionageManager.spyList.single { it.name == spy.name }
            canonicalSpy.moveTo(canonicalCityStateCity)
            canonicalSpy.setAction(SpyAction.RiggingElections, 10)
            snapshot = fixture.encode(canonicalGame)

            projection = project(1_700_800_030_000L)
            projectedSpy = projection.spies.single { it.name == spy.name }
            assertTrue(projectedSpy.canStageCoup)
            response = send(
                fixture.request(
                    WorkerOperation.SetSpyCoup(
                        snapshot = snapshot,
                        actorCivilizationId = civilizationId,
                        spyName = projectedSpy.name,
                        enabled = true,
                    ),
                    1_700_800_030_001L,
                ),
            )
            results += response
            snapshot = requireNotNull(response.snapshot)
            canonicalGame = fixture.decode(snapshot)
            canonicalSpy = canonicalGame.getCivilization(civilizationId)
                .espionageManager.spyList.single { it.name == spy.name }
            assertEquals(SpyAction.Coup, canonicalSpy.action)
            assertEquals(1, canonicalSpy.turnsRemainingForAction)

            projection = project(1_700_800_040_000L)
            projectedSpy = projection.spies.single { it.name == spy.name }
            assertTrue(projectedSpy.canCancelCoup)
            response = send(
                fixture.request(
                    WorkerOperation.SetSpyCoup(
                        snapshot = snapshot,
                        actorCivilizationId = civilizationId,
                        spyName = projectedSpy.name,
                        enabled = false,
                    ),
                    1_700_800_040_001L,
                ),
            )
            results += response
            snapshot = requireNotNull(response.snapshot)
            canonicalGame = fixture.decode(snapshot)
            canonicalSpy = canonicalGame.getCivilization(civilizationId)
                .espionageManager.spyList.single { it.name == spy.name }
            assertEquals(SpyAction.CounterIntelligence, canonicalSpy.action)
            assertEquals(10, canonicalSpy.turnsRemainingForAction)
            results
        }

        assertEquals(9, responses.size)
    }

    companion object {
        private const val actorId = "account-espionage-parity"

        @JvmStatic
        @BeforeClass
        fun initializeRulesets() = PackagedWorkerParityHarness.initializeRulesets()
    }
}
