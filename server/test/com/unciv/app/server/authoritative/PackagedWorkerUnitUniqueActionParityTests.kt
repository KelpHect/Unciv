package com.unciv.app.server.authoritative

import com.unciv.Constants
import com.unciv.logic.civilization.Civilization
import com.unciv.models.ruleset.unique.UniqueType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test

class PackagedWorkerUnitUniqueActionParityTests {
    @Test(timeout = 300_000)
    fun instantImprovementAndTriggerActionsAreStableAcrossFreshWorkers() {
        val fixture = PackagedWorkerScenarioFixture(
            actorId = actorId,
            gameId = "00000000-0000-4000-8000-000000001601",
            serverSeed = 874_116_649L,
        )
        val responses = PackagedWorkerParityHarness.assertStableScenario { send ->
            val results = mutableListOf<WorkerResponse>()
            var response = send(fixture.createGameRequest(1_701_600_000_000L))
            results += response

            val civilizationId = requireNotNull(response.actorCivilizationId)
            val game = fixture.decode(response.snapshot)
            val actor = game.getCivilization(civilizationId)
            val capital = ensureCapital(actor)
            actor.units.getCivUnits()
                .filter { it.hasUnique(UniqueType.FoundCity) }
                .toList()
                .forEach { it.destroy() }
            val unitTiles = capital.getTiles()
                .filter { !it.isCityCenter() && !it.isWater && it.civilianUnit == null }
                .take(2)
                .toList()
            assertEquals("Fixture requires two empty owned land tiles", 2, unitTiles.size)
            unitTiles.forEach { tile ->
                tile.setBaseTerrain(requireNotNull(game.ruleset.terrains[Constants.grassland]))
                tile.setTerrainFeatures(listOf())
                tile.naturalWonder = null
                tile.removeImprovement()
            }

            val engineer = actor.units.addUnit(
                requireNotNull(game.ruleset.units["Great Engineer"]),
                capital,
            )!!
            engineer.removeFromTile()
            engineer.putInTile(unitTiles[0])
            val general = actor.units.addUnit(
                requireNotNull(game.ruleset.units["Great General"]),
                capital,
            )!!
            general.removeFromTile()
            general.putInTile(unitTiles[1])
            var snapshot = fixture.encode(game)

            fun project(serverTime: Long) = send(
                fixture.request(
                    WorkerOperation.ProjectState(snapshot, civilizationId),
                    serverTime,
                ),
            ).also(results::add)

            var projection = requireNotNull(project(1_701_600_010_000L).playerProjection)
            val projectedEngineer = projection.ownUnits.single { it.id == engineer.id }
            val improvementAction = projectedEngineer.availableInstantImprovementActions.single()
            assertTrue(improvementAction.title.contains("Manufactory"))
            val projectedGeneral = projection.ownUnits.single { it.id == general.id }
            val triggerAction = projectedGeneral.availableTriggerActions.single()
            assertTrue(triggerAction.title.contains("Golden Age"))

            response = send(
                fixture.request(
                    WorkerOperation.CreateInstantImprovement(
                        snapshot = snapshot,
                        actorCivilizationId = civilizationId,
                        unitId = projectedEngineer.id,
                        actionId = improvementAction.actionId,
                    ),
                    1_701_600_010_001L,
                ),
            )
            results += response
            snapshot = requireNotNull(response.snapshot)
            var canonicalGame = fixture.decode(snapshot)
            var canonicalActor = canonicalGame.getCivilization(civilizationId)
            assertNull(canonicalActor.units.getUnitById(engineer.id))
            assertEquals(
                "Manufactory",
                canonicalGame.tileMap[unitTiles[0].position.x, unitTiles[0].position.y].improvement,
            )

            projection = requireNotNull(project(1_701_600_020_000L).playerProjection)
            val refreshedGeneral = projection.ownUnits.single { it.id == general.id }
            val refreshedTrigger = refreshedGeneral.availableTriggerActions.single {
                it.title == triggerAction.title
            }
            response = send(
                fixture.request(
                    WorkerOperation.TriggerUnitUnique(
                        snapshot = snapshot,
                        actorCivilizationId = civilizationId,
                        unitId = refreshedGeneral.id,
                        actionId = refreshedTrigger.actionId,
                    ),
                    1_701_600_020_001L,
                ),
            )
            results += response

            canonicalGame = fixture.decode(response.snapshot)
            canonicalActor = canonicalGame.getCivilization(civilizationId)
            assertNull(canonicalActor.units.getUnitById(general.id))
            assertTrue(canonicalActor.goldenAges.isGoldenAge())
            results
        }

        assertEquals(5, responses.size)
    }

    private fun ensureCapital(civilization: Civilization) = civilization.getCapital()
        ?: civilization.addCity(
            civilization.units.getCivUnits()
                .first { it.hasUnique(UniqueType.FoundCity) }
                .currentTile.position,
        )

    companion object {
        private const val actorId = "account-unit-unique-action-parity"

        @JvmStatic
        @BeforeClass
        fun initializeRulesets() = PackagedWorkerParityHarness.initializeRulesets()
    }
}
