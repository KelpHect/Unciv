package com.unciv.app.server.authoritative

import com.unciv.logic.civilization.AlertType
import com.unciv.logic.civilization.PopupAlert
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test

class PackagedWorkerResearchPolicyParityTests {
    @Test(timeout = 300_000)
    fun researchQueueFreeTechnologyPolicyAndCompletionAreStableAcrossFreshWorkers() {
        val fixture = PackagedWorkerScenarioFixture(
            actorId = actorId,
            gameId = "00000000-0000-4000-8000-000000000601",
            serverSeed = 309_642_875L,
        )
        val responses = PackagedWorkerParityHarness.assertStableScenario { send ->
            val results = mutableListOf<WorkerResponse>()
            var response = send(fixture.createGameRequest(1_700_600_000_000L))
            results += response

            val civilizationId = requireNotNull(response.actorCivilizationId)
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

            var projection = project(1_700_600_010_000L)
            val researchTarget = projection.research.selectableTargets.last()
            response = send(
                fixture.request(
                    WorkerOperation.SetResearchPath(
                        snapshot = snapshot,
                        actorCivilizationId = civilizationId,
                        technologyName = researchTarget,
                        append = false,
                    ),
                    1_700_600_020_000L,
                ),
            )
            results += response
            snapshot = requireNotNull(response.snapshot)

            projection = project(1_700_600_030_000L)
            val queueEntry = projection.research.queueEntries.first {
                it.availableActions.isNotEmpty()
            }
            response = send(
                fixture.request(
                    WorkerOperation.ManageResearchQueue(
                        snapshot = snapshot,
                        actorCivilizationId = civilizationId,
                        technologyName = queueEntry.technologyName,
                        queueIndex = projection.research.queueEntries.indexOf(queueEntry),
                        action = queueEntry.availableActions.first(),
                    ),
                    1_700_600_040_000L,
                ),
            )
            results += response

            val preparedGame = fixture.decode(response.snapshot)
            val preparedCivilization = preparedGame.getCivilization(civilizationId)
            preparedCivilization.tech.freeTechs = 1
            preparedCivilization.policies.freePolicies = 1
            preparedCivilization.policies.shouldOpenPolicyPicker = true
            val completedTechnology = preparedGame.ruleset.technologies.keys.first()
            preparedCivilization.popupAlerts +=
                PopupAlert(AlertType.TechResearched, completedTechnology)
            snapshot = fixture.encode(preparedGame)

            projection = project(1_700_600_050_000L)
            val freeTechnology = projection.research.freeTechnologyChoices.first()
            val policy = projection.policies.selectablePolicies.first()
            val completion = projection.research.completionPrompts.single {
                it.technologyName == completedTechnology
            }

            response = send(
                fixture.request(
                    WorkerOperation.ChooseFreeTechnology(
                        snapshot = snapshot,
                        actorCivilizationId = civilizationId,
                        technologyName = freeTechnology,
                    ),
                    1_700_600_060_000L,
                ),
            )
            results += response
            snapshot = requireNotNull(response.snapshot)

            response = send(
                fixture.request(
                    WorkerOperation.AdoptPolicy(
                        snapshot = snapshot,
                        actorCivilizationId = civilizationId,
                        policyName = policy,
                    ),
                    1_700_600_070_000L,
                ),
            )
            results += response
            snapshot = requireNotNull(response.snapshot)

            response = send(
                fixture.request(
                    WorkerOperation.AcknowledgeResearchCompletion(
                        snapshot = snapshot,
                        actorCivilizationId = civilizationId,
                        promptId = completion.promptId,
                    ),
                    1_700_600_080_000L,
                ),
            )
            results += response
            snapshot = requireNotNull(response.snapshot)

            val finalProjection = project(1_700_600_090_000L)
            assertTrue(freeTechnology in finalProjection.research.researchedTechnologies)
            assertTrue(policy in finalProjection.policies.adoptedPolicies)
            assertTrue(finalProjection.research.completionPrompts.none {
                it.promptId == completion.promptId
            })
            results
        }

        assertEquals(10, responses.size)
    }

    companion object {
        private const val actorId = "account-research-policy-parity"

        @JvmStatic
        @BeforeClass
        fun initializeRulesets() = PackagedWorkerParityHarness.initializeRulesets()
    }
}
