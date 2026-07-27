package com.unciv.app.server.authoritative

import com.unciv.logic.civilization.Civilization
import com.unciv.logic.multiplayer.authoritative.ReligiousUnitAction
import com.unciv.models.ruleset.BeliefType
import com.unciv.models.ruleset.unique.UniqueType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test

class PackagedWorkerReligiousUnitParityTests {
    @Test(timeout = 300_000)
    fun foundingReligionIsStableAcrossFreshWorkers() {
        val fixture = PackagedWorkerScenarioFixture(
            actorId = actorId,
            gameId = "00000000-0000-4000-8000-000000001401",
            serverSeed = 874_114_643L,
            baseName = "Civ V - Gods & Kings",
        )
        val responses = PackagedWorkerParityHarness.assertStableScenario { send ->
            val results = mutableListOf<WorkerResponse>()
            var response = send(fixture.createGameRequest(1_701_400_000_000L))
            results += response

            val civilizationId = requireNotNull(response.actorCivilizationId)
            val game = fixture.decode(response.snapshot)
            val actor = game.getCivilization(civilizationId)
            val capital = ensureCapital(actor)
            actor.units.getCivUnits()
                .filter { it.hasUnique(UniqueType.FoundCity) }
                .toList()
                .forEach { it.destroy() }
            val pantheon = game.ruleset.beliefs.values
                .filter { it.type == BeliefType.Pantheon }
                .minBy { it.name }
            actor.religionManager.storedFaith = 10_000
            actor.religionManager.chooseBeliefs(listOf(pantheon))
            val prophet = actor.units.addUnit(
                requireNotNull(game.ruleset.units["Great Prophet"]),
                capital,
            )!!
            prophet.removeFromTile()
            prophet.putInTile(capital.getCenterTile())
            prophet.religion = requireNotNull(actor.religionManager.religion).name
            val snapshot = fixture.encode(game)

            val projected = send(
                fixture.request(
                    WorkerOperation.ProjectState(snapshot, civilizationId),
                    1_701_400_010_000L,
                ),
            )
            results += projected
            val projectedProphet = requireNotNull(projected.playerProjection)
                .ownUnits
                .single { it.id == prophet.id }
            assertEquals(
                listOf(ReligiousUnitAction.FoundReligion),
                projectedProphet.availableReligiousActions,
            )

            response = send(
                fixture.request(
                    WorkerOperation.UseReligiousUnit(
                        snapshot = snapshot,
                        actorCivilizationId = civilizationId,
                        unitId = projectedProphet.id,
                        action = projectedProphet.availableReligiousActions.single(),
                    ),
                    1_701_400_010_001L,
                ),
            )
            results += response

            val canonicalGame = fixture.decode(response.snapshot)
            val canonicalActor = canonicalGame.getCivilization(civilizationId)
            assertNull(canonicalActor.units.getUnitById(prophet.id))
            val foundingProjection = send(
                fixture.request(
                    WorkerOperation.ProjectState(requireNotNull(response.snapshot), civilizationId),
                    1_701_400_010_002L,
                ),
            )
            results += foundingProjection
            val religionChoice = requireNotNull(foundingProjection.playerProjection?.religionChoice)
            assertTrue(religionChoice.requiresReligionIdentity)
            assertTrue(religionChoice.requiredBeliefTypes.isNotEmpty())
            assertTrue(religionChoice.availableBeliefs.isNotEmpty())
            assertTrue(religionChoice.availableReligionIcons.isNotEmpty())
            assertNotNull(canonicalActor.religionManager.religion)
            results
        }

        assertEquals(4, responses.size)
    }

    private fun ensureCapital(civilization: Civilization) = civilization.getCapital()
        ?: civilization.addCity(
            civilization.units.getCivUnits()
                .first { it.hasUnique(UniqueType.FoundCity) }
                .currentTile.position,
        )

    companion object {
        private const val actorId = "account-religious-unit-parity"

        @JvmStatic
        @BeforeClass
        fun initializeRulesets() = PackagedWorkerParityHarness.initializeRulesets()
    }
}
