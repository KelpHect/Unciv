package com.unciv.app.server.authoritative

import com.unciv.logic.civilization.CivFlags
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test

class PackagedWorkerTurnDecisionParityTests {
    @Test(timeout = 300_000)
    fun voteGreatPersonAndPantheonChoicesAreStableAcrossFreshWorkers() {
        val fixture = PackagedWorkerScenarioFixture(
            actorId = actorId,
            gameId = "00000000-0000-4000-8000-000000001101",
            serverSeed = 684_137_295L,
            baseName = "Civ V - Gods & Kings",
        )
        val responses = PackagedWorkerParityHarness.assertStableScenario { send ->
            val results = mutableListOf<WorkerResponse>()
            var response = send(fixture.createGameRequest(1_701_100_000_000L))
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
                        requireNotNull(response.snapshot), civilizationId, settlerId,
                    ),
                    1_701_100_010_000L,
                ),
            )
            results += response

            val game = fixture.decode(response.snapshot)
            val civilization = game.getCivilization(civilizationId)
            val other = game.civilizations.single {
                it.isMajorCiv() && it.civID != civilizationId
            }
            civilization.diplomacyFunctions.makeCivilizationsMeet(other)
            civilization.addFlag(CivFlags.TurnsTillNextDiplomaticVote.name, 0)
            civilization.greatPeople.freeGreatPeople = 1
            civilization.religionManager.storedFaith = 10_000
            var snapshot = fixture.encode(game)

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

            val projection = project(1_701_100_020_000L)
            val voteCandidate = projection.diplomaticVoteCandidates.single()
            val greatPerson = projection.selectableGreatPeople.first {
                !game.ruleset.units.getValue(it).isWaterUnit
            }
            val religionChoice = requireNotNull(projection.religionChoice)
            val beliefs = religionChoice.requiredBeliefTypes.map { required ->
                religionChoice.availableBeliefs.first { it.type == required }.name
            }

            response = send(
                fixture.request(
                    WorkerOperation.CastDiplomaticVote(
                        snapshot, civilizationId, voteCandidate,
                    ),
                    1_701_100_030_000L,
                ),
            )
            results += response
            snapshot = requireNotNull(response.snapshot)

            response = send(
                fixture.request(
                    WorkerOperation.ChooseGreatPerson(
                        snapshot, civilizationId, greatPerson,
                    ),
                    1_701_100_040_000L,
                ),
            )
            results += response
            snapshot = requireNotNull(response.snapshot)

            response = send(
                fixture.request(
                    WorkerOperation.ChooseReligiousBeliefs(
                        snapshot = snapshot,
                        actorCivilizationId = civilizationId,
                        beliefNames = beliefs,
                        religionIconName = null,
                        religionDisplayName = null,
                    ),
                    1_701_100_050_000L,
                ),
            )
            results += response
            snapshot = requireNotNull(response.snapshot)

            val finalProjection = project(1_701_100_060_000L)
            val finalGame = fixture.decode(snapshot)
            val finalCivilization = finalGame.getCivilization(civilizationId)
            assertEquals(voteCandidate, finalGame.diplomaticVictoryVotesCast[civilizationId])
            assertTrue(finalCivilization.units.getCivUnits().any { it.name == greatPerson })
            assertEquals(0, finalCivilization.greatPeople.freeGreatPeople)
            assertTrue(beliefs.all(finalCivilization.religionManager.religion!!::hasBelief))
            assertTrue(finalProjection.diplomaticVoteCandidates.isEmpty())
            assertTrue(finalProjection.selectableGreatPeople.isEmpty())
            assertNull(finalProjection.religionChoice)
            results
        }

        assertEquals(7, responses.size)
    }

    companion object {
        private const val actorId = "account-turn-decision-parity"

        @JvmStatic
        @BeforeClass
        fun initializeRulesets() = PackagedWorkerParityHarness.initializeRulesets()
    }
}
