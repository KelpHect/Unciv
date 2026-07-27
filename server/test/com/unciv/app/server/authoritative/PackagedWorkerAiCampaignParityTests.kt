package com.unciv.app.server.authoritative

import com.unciv.models.ruleset.tech.Technology
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test

class PackagedWorkerAiCampaignParityTests {
    @Test(timeout = 300_000)
    fun eightCompleteThreeAiRoundsAreByteStableAcrossFreshWorkers() {
        val fixture = PackagedWorkerScenarioFixture(
            actorId = actorId,
            gameId = "00000000-0000-4000-8000-000000001301",
            serverSeed = 391_746_285L,
            majorCivilizations = 4,
            cityStates = 2,
            barbarians = BarbarianMode.Normal,
        )

        val responses = PackagedWorkerParityHarness.assertStableScenario { send ->
            val results = mutableListOf<WorkerResponse>()
            var response = send(fixture.createGameRequest(1_701_300_000_000L))
            results += response
            val civilizationId = requireNotNull(response.actorCivilizationId)
            val created = fixture.decode(response.snapshot)
            val actor = created.getCivilization(civilizationId)
            val destination = created.ruleset.technologies.values
                .filter { actor.tech.getRequiredTechsToDestination(it).isNotEmpty() }
                .maxWith(
                    compareBy<Technology> {
                        it.column?.columnNumber ?: -1
                    }.thenBy { it.name },
                )

            response = send(
                fixture.request(
                    WorkerOperation.SetResearchPath(
                        snapshot = requireNotNull(response.snapshot),
                        actorCivilizationId = civilizationId,
                        technologyName = destination.name,
                        append = false,
                    ),
                    1_701_300_010_000L,
                ),
            )
            results += response

            repeat(8) { round ->
                response = send(
                    fixture.request(
                        WorkerOperation.EndTurn(
                            snapshot = requireNotNull(response.snapshot),
                            actorCivilizationId = civilizationId,
                        ),
                        1_701_300_020_000L + round * 60_000L,
                    ),
                )
                results += response
                val advanced = fixture.decode(response.snapshot)
                assertEquals(round + 1, advanced.turns)
                assertEquals(civilizationId, advanced.currentPlayer)
            }

            val finalGame = fixture.decode(response.snapshot)
            val aiCivilizations = finalGame.civilizations.filter {
                it.isMajorCiv() && it.isAI()
            }
            assertEquals(3, aiCivilizations.size)
            assertTrue(aiCivilizations.all { it.cities.isNotEmpty() })
            results
        }

        assertEquals(10, responses.size)
        assertNotEquals(
            responses.first().canonicalStateHash,
            responses.last().canonicalStateHash,
        )
    }

    companion object {
        private const val actorId = "account-ai-campaign-parity"

        @JvmStatic
        @BeforeClass
        fun initializeRulesets() = PackagedWorkerParityHarness.initializeRulesets()
    }
}
