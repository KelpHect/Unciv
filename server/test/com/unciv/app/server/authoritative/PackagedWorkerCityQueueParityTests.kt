package com.unciv.app.server.authoritative

import com.unciv.logic.multiplayer.authoritative.ConstructionQueueAction
import org.junit.Assert.assertEquals
import org.junit.BeforeClass
import org.junit.Test

class PackagedWorkerCityQueueParityTests {
    @Test(timeout = 300_000)
    fun cityFoundingProjectionAndQueueEditsAreStableAcrossFreshWorkers() {
        val fixture = PackagedWorkerScenarioFixture(
            actorId = actorId,
            gameId = "00000000-0000-4000-8000-000000000301",
            serverSeed = 642_975_318L,
        )
        val responses = PackagedWorkerParityHarness.assertStableScenario { send ->
            val results = mutableListOf<WorkerResponse>()
            var response = send(fixture.createGameRequest(1_700_300_000_000L))
            results += response

            val civilizationId = requireNotNull(response.actorCivilizationId)
            val settlerId = fixture.decode(response.snapshot)
                .getCivilization(civilizationId)
                .units.getCivUnits()
                .first { it.isCivilian() }
                .id
            response = send(
                fixture.request(
                    WorkerOperation.FoundCity(
                        snapshot = requireNotNull(response.snapshot),
                        actorCivilizationId = civilizationId,
                        unitId = settlerId,
                    ),
                    1_700_300_010_000L,
                ),
            )
            results += response
            var snapshot = requireNotNull(response.snapshot)

            fun project(serverTime: Long): com.unciv.logic.multiplayer.authoritative.PlayerProjection {
                val projected = send(
                    fixture.request(
                        WorkerOperation.ProjectState(snapshot, civilizationId),
                        serverTime,
                    ),
                )
                results += projected
                return requireNotNull(projected.playerProjection)
            }

            var city = project(1_700_300_020_000L).ownCities.single()
            val constructions = city.constructionOptions
                .filter { it.queueable && it.placementTargets.isEmpty() }
                .take(3)
                .map { it.name }
            assertEquals("Fixture requires three ordinary constructions", 3, constructions.size)

            constructions.forEachIndexed { index, construction ->
                response = send(
                    fixture.request(
                        WorkerOperation.QueueConstruction(
                            snapshot = snapshot,
                            actorCivilizationId = civilizationId,
                            cityId = city.id,
                            constructionName = construction,
                        ),
                        1_700_300_030_000L + index,
                    ),
                )
                results += response
                snapshot = requireNotNull(response.snapshot)
            }

            var canonicalCity = fixture.decode(snapshot)
                .getCivilization(civilizationId)
                .cities
                .single()
            val moveFromIndex = canonicalCity.cityConstructions.constructionQueue.lastIndex
            val moveToIndex = moveFromIndex - 1
            val movedConstruction = canonicalCity.cityConstructions.constructionQueue[moveFromIndex]
            response = send(
                fixture.request(
                    WorkerOperation.MoveConstruction(
                        snapshot = snapshot,
                        actorCivilizationId = civilizationId,
                        cityId = city.id,
                        fromIndex = moveFromIndex,
                        toIndex = moveToIndex,
                        expectedConstructionName = movedConstruction,
                    ),
                    1_700_300_040_000L,
                ),
            )
            results += response
            snapshot = requireNotNull(response.snapshot) { response.error.toString() }

            canonicalCity = fixture.decode(snapshot)
                .getCivilization(civilizationId)
                .cities
                .single()
            val removalIndex = canonicalCity.cityConstructions.constructionQueue.lastIndex
            val removedConstruction = canonicalCity.cityConstructions.constructionQueue[removalIndex]
            response = send(
                fixture.request(
                    WorkerOperation.RemoveConstruction(
                        snapshot = snapshot,
                        actorCivilizationId = civilizationId,
                        cityId = city.id,
                        queueIndex = removalIndex,
                        expectedConstructionName = removedConstruction,
                    ),
                    1_700_300_050_000L,
                ),
            )
            results += response
            snapshot = requireNotNull(response.snapshot)

            city = project(1_700_300_060_000L).ownCities.single()
            val contextOption = city.constructionOptions.first {
                ConstructionQueueAction.AddToTop in it.availableActions
            }
            response = send(
                fixture.request(
                    WorkerOperation.ManageConstructionQueues(
                        snapshot = snapshot,
                        actorCivilizationId = civilizationId,
                        cityId = city.id,
                        constructionName = contextOption.name,
                        action = ConstructionQueueAction.AddToTop,
                    ),
                    1_700_300_070_000L,
                ),
            )
            results += response
            snapshot = requireNotNull(response.snapshot)

            val finalCity = fixture.decode(snapshot)
                .getCivilization(civilizationId)
                .cities
                .single()
            assertEquals(contextOption.name, finalCity.cityConstructions.constructionQueue.first())
            assertEquals(3, finalCity.cityConstructions.constructionQueue.size)

            response = send(
                fixture.request(
                    WorkerOperation.ProjectSpectatorState(snapshot),
                    1_700_300_080_000L,
                ),
            )
            results += response
            requireNotNull(response.spectatorProjection)
            results
        }

        assertEquals(11, responses.size)
    }

    companion object {
        private const val actorId = "account-city-queue-parity"

        @JvmStatic
        @BeforeClass
        fun initializeRulesets() = PackagedWorkerParityHarness.initializeRulesets()
    }
}
