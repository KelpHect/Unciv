package com.unciv.app.server.authoritative

import com.unciv.Constants
import com.unciv.logic.civilization.Civilization
import com.unciv.models.ruleset.unique.UniqueType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test

class PackagedWorkerParityModTests {
    @Test(timeout = 300_000)
    fun tileConstructionPurchaseAndUnitTransformAreStableAcrossFreshWorkers() {
        val fixture = PackagedWorkerScenarioFixture(
            actorId = actorId,
            gameId = "00000000-0000-4000-8000-000000001701",
            serverSeed = 575_775_177L,
            modNames = listOf(parityMod),
        )
        val responses = PackagedWorkerParityHarness.assertStableScenario { send ->
            val results = mutableListOf<WorkerResponse>()
            var response = send(fixture.createGameRequest(1_701_700_000_000L))
            results += response

            val civilizationId = requireNotNull(response.actorCivilizationId)
            val game = fixture.decode(response.snapshot)
            game.gameParameters.godMode = true
            val actor = game.getCivilization(civilizationId)
            val capital = ensureCapital(actor)
            actor.tech.techsResearched.addAll(game.ruleset.technologies.keys)
            actor.units.getCivUnits()
                .filter { it.hasUnique(UniqueType.FoundCity) }
                .toList()
                .forEach { it.destroy() }

            val target = capital.getTiles()
                .first { !it.isCityCenter() && !it.isWater && it.civilianUnit == null }
            target.setBaseTerrain(requireNotNull(game.ruleset.terrains[Constants.grassland]))
            target.setTerrainFeatures(listOf())
            target.naturalWonder = null
            target.removeImprovement()

            val transformer = actor.units.addUnit(
                requireNotNull(game.ruleset.units[transformerName]),
                capital,
            )!!
            transformer.removeFromTile()
            transformer.putInTile(target)
            var snapshot = fixture.encode(game)

            fun project(serverTime: Long) = send(
                fixture.request(
                    WorkerOperation.ProjectState(snapshot, civilizationId),
                    serverTime,
                ),
            ).also(results::add).playerProjection!!

            var projection = project(1_701_700_010_000L)
            val city = projection.ownCities.single { it.id == capital.id }
            val district = city.constructionOptions.single { it.name == districtName }
            val placement = district.placementTargets.single {
                it.x == target.position.x && it.y == target.position.y
            }
            val projectedTransformer = projection.ownUnits.single { it.id == transformer.id }
            val transformAction = projectedTransformer.availableTransformActions.single()
            assertEquals("Scout", transformAction.targetUnitName)

            response = send(
                fixture.request(
                    WorkerOperation.QueueConstructionAtTile(
                        snapshot = snapshot,
                        actorCivilizationId = civilizationId,
                        cityId = city.id,
                        constructionName = district.name,
                        x = placement.x,
                        y = placement.y,
                    ),
                    1_701_700_010_001L,
                ),
            )
            results += response
            snapshot = requireNotNull(response.snapshot)
            var canonicalGame = fixture.decode(snapshot)
            var canonicalCity = canonicalGame.getCivilization(civilizationId)
                .cities.single { it.id == city.id }
            assertEquals(districtName, canonicalCity.cityConstructions.constructionQueue.single())
            assertTrue(
                canonicalGame.tileMap[placement.x, placement.y]
                    .isMarkedForCreatesOneImprovement("Farm"),
            )

            projection = project(1_701_700_020_000L)
            val queuedDistrict = projection.ownCities.single { it.id == city.id }
                .constructionQueueEntries.single { it.name == districtName }
            val purchase = queuedDistrict.purchases.single {
                it.allowed && it.requiresTile && it.currency == "Gold"
            }
            assertEquals(listOf(placement), purchase.legalTargets)

            response = send(
                fixture.request(
                    WorkerOperation.PurchaseConstructionAtTile(
                        snapshot = snapshot,
                        actorCivilizationId = civilizationId,
                        cityId = city.id,
                        constructionName = districtName,
                        currencyName = purchase.currency,
                        x = placement.x,
                        y = placement.y,
                        queueIndex = 0,
                    ),
                    1_701_700_020_001L,
                ),
            )
            results += response
            snapshot = requireNotNull(response.snapshot)
            canonicalGame = fixture.decode(snapshot)
            canonicalCity = canonicalGame.getCivilization(civilizationId)
                .cities.single { it.id == city.id }
            assertTrue(canonicalCity.cityConstructions.isBuilt(districtName))
            assertEquals("Farm", canonicalGame.tileMap[placement.x, placement.y].improvement)

            projection = project(1_701_700_030_000L)
            val refreshedTransformer = projection.ownUnits.single { it.id == transformer.id }
            val refreshedAction = refreshedTransformer.availableTransformActions.single {
                it.actionId == transformAction.actionId
            }
            response = send(
                fixture.request(
                    WorkerOperation.TransformUnit(
                        snapshot = snapshot,
                        actorCivilizationId = civilizationId,
                        unitId = transformer.id,
                        actionId = refreshedAction.actionId,
                    ),
                    1_701_700_030_001L,
                ),
            )
            results += response

            val finalActor = fixture.decode(response.snapshot).getCivilization(civilizationId)
            val transformed = finalActor.units.getUnitById(transformer.id)
            assertNotNull(transformed)
            assertEquals("Scout", transformed!!.name)
            assertNull(finalActor.units.getCivUnits().firstOrNull { it.name == transformerName })
            results
        }

        assertEquals(7, responses.size)
    }

    private fun ensureCapital(civilization: Civilization) = civilization.getCapital()
        ?: civilization.addCity(
            civilization.units.getCivUnits()
                .first { it.hasUnique(UniqueType.FoundCity) }
                .currentTile.position,
        )

    companion object {
        private const val actorId = "account-parity-mod"
        private const val parityMod = "Authoritative V3 Parity"
        private const val districtName = "Authoritative Parity District"
        private const val transformerName = "Authoritative Parity Transformer"

        @JvmStatic
        @BeforeClass
        fun initializeRulesets() = PackagedWorkerParityHarness.initializeRulesets()
    }
}
