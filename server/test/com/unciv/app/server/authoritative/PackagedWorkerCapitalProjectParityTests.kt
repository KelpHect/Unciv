package com.unciv.app.server.authoritative

import com.unciv.logic.civilization.Civilization
import com.unciv.models.ruleset.unique.UniqueType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.BeforeClass
import org.junit.Test

class PackagedWorkerCapitalProjectParityTests {
    @Test(timeout = 300_000)
    fun addingSpaceshipPartToCapitalIsStableAcrossFreshWorkers() {
        val fixture = PackagedWorkerScenarioFixture(
            actorId = actorId,
            gameId = "00000000-0000-4000-8000-000000001501",
            serverSeed = 874_115_647L,
        )
        val responses = PackagedWorkerParityHarness.assertStableScenario { send ->
            val results = mutableListOf<WorkerResponse>()
            var response = send(fixture.createGameRequest(1_701_500_000_000L))
            results += response

            val civilizationId = requireNotNull(response.actorCivilizationId)
            val game = fixture.decode(response.snapshot)
            val actor = game.getCivilization(civilizationId)
            val capital = ensureCapital(actor)
            actor.units.getCivUnits()
                .filter { it.hasUnique(UniqueType.FoundCity) }
                .toList()
                .forEach { it.destroy() }
            val spaceshipPartDefinition = game.ruleset.units.values
                .filter { it.hasUnique(UniqueType.AddInCapital) }
                .minBy { it.name }
            val spaceshipPart = actor.units.addUnit(spaceshipPartDefinition, capital)!!
            spaceshipPart.removeFromTile()
            spaceshipPart.putInTile(capital.getCenterTile())
            val snapshot = fixture.encode(game)

            val projected = send(
                fixture.request(
                    WorkerOperation.ProjectState(snapshot, civilizationId),
                    1_701_500_010_000L,
                ),
            )
            results += projected
            val projectedPart = requireNotNull(projected.playerProjection)
                .ownUnits
                .single { it.id == spaceshipPart.id }
            assertEquals("The Spaceship", projectedPart.capitalProjectName)

            response = send(
                fixture.request(
                    WorkerOperation.AddUnitToCapitalProject(
                        snapshot = snapshot,
                        actorCivilizationId = civilizationId,
                        unitId = projectedPart.id,
                    ),
                    1_701_500_010_001L,
                ),
            )
            results += response

            val canonicalGame = fixture.decode(response.snapshot)
            val canonicalActor = canonicalGame.getCivilization(civilizationId)
            assertNull(canonicalActor.units.getUnitById(spaceshipPart.id))
            assertEquals(
                1,
                canonicalActor.victoryManager.currentsSpaceshipParts[spaceshipPart.name],
            )
            results
        }

        assertEquals(3, responses.size)
    }

    private fun ensureCapital(civilization: Civilization) = civilization.getCapital()
        ?: civilization.addCity(
            civilization.units.getCivUnits()
                .first { it.hasUnique(UniqueType.FoundCity) }
                .currentTile.position,
        )

    companion object {
        private const val actorId = "account-capital-project-parity"

        @JvmStatic
        @BeforeClass
        fun initializeRulesets() = PackagedWorkerParityHarness.initializeRulesets()
    }
}
