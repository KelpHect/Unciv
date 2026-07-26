package com.unciv.app.server.authoritative

import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test

class PackagedWorkerUnitConstructionParityTests {
    @Test(timeout = 300_000)
    fun improvementRoadAndSwapOrdersAreStableAcrossFreshWorkers() {
        val fixture = PackagedWorkerScenarioFixture(
            actorId = actorId,
            gameId = "00000000-0000-4000-8000-000000000901",
            serverSeed = 951_426_837L,
        )
        val responses = PackagedWorkerParityHarness.assertStableScenario { send ->
            val results = mutableListOf<WorkerResponse>()
            var response = send(fixture.createGameRequest(1_700_900_000_000L))
            results += response

            val civilizationId = requireNotNull(response.actorCivilizationId)
            val game = fixture.decode(response.snapshot)
            val civilization = game.getCivilization(civilizationId)
            civilization.tech.techsResearched.addAll(game.ruleset.technologies.keys)
            val military = civilization.units.getCivUnits().first { it.isMilitary() }
            val city = civilization.addCity(military.currentTile.position)
            val openLand = civilization.viewableTiles
                .filter { it.isLand && !it.isCityCenter() && it.getUnits().none() }
                .sortedWith(compareBy({ it.position.x }, { it.position.y }))
            val improvementWorker = requireNotNull(civilization.units.addUnit("Worker", city))
            improvementWorker.removeFromTile()
            improvementWorker.putInTile(openLand.first())
            val roadWorker = requireNotNull(civilization.units.addUnit("Worker", city))
            roadWorker.removeFromTile()
            roadWorker.putInTile(openLand.first { it != improvementWorker.currentTile })
            val swapOrigin = openLand.first {
                it != improvementWorker.currentTile && it != roadWorker.currentTile &&
                    it.neighbors.any { neighbor -> neighbor in openLand }
            }
            val swapDestination = swapOrigin.neighbors
                .filter { it in openLand }
                .minWith(compareBy({ it.position.x }, { it.position.y }))
            val swapUnit = requireNotNull(civilization.units.addUnit(military.baseUnit.name, city))
            swapUnit.removeFromTile()
            swapUnit.putInTile(swapOrigin)
            val swapPeer = requireNotNull(civilization.units.addUnit(military.baseUnit.name, city))
            swapPeer.removeFromTile()
            swapPeer.putInTile(swapDestination)
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

            var projection = project(1_700_900_010_000L)
            val projectedImprovementWorker = projection.ownUnits.single {
                it.id == improvementWorker.id
            }
            val improvement = projectedImprovementWorker.availableImprovementOrders.first {
                it.improvementName != null
            }
            response = send(
                fixture.request(
                    WorkerOperation.SetTileImprovementOrder(
                        snapshot, civilizationId, improvementWorker.id,
                        improvement.improvementName, improvement.queuedImprovementName,
                    ),
                    1_700_900_020_000L,
                ),
            )
            results += response
            snapshot = requireNotNull(response.snapshot)

            projection = project(1_700_900_030_000L)
            assertTrue(projection.ownUnits.single { it.id == improvementWorker.id }
                .improvementOrder.isNotEmpty())
            response = send(
                fixture.request(
                    WorkerOperation.SetTileImprovementOrder(
                        snapshot, civilizationId, improvementWorker.id, null, null,
                    ),
                    1_700_900_040_000L,
                ),
            )
            results += response
            snapshot = requireNotNull(response.snapshot)

            projection = project(1_700_900_050_000L)
            val projectedSwapUnit = projection.ownUnits.single { it.id == swapUnit.id }
            val projectedSwap = projectedSwapUnit.swapDestinations.single {
                it.x == swapDestination.position.x && it.y == swapDestination.position.y
            }
            response = send(
                fixture.request(
                    WorkerOperation.SwapUnits(
                        snapshot, civilizationId, swapUnit.id, projectedSwap.x, projectedSwap.y,
                    ),
                    1_700_900_060_000L,
                ),
            )
            results += response
            snapshot = requireNotNull(response.snapshot)

            projection = project(1_700_900_070_000L)
            val projectedRoadWorker = projection.ownUnits.single { it.id == roadWorker.id }
            val roadDestination = projectedRoadWorker.availableRoadDestinations.maxBy {
                abs(it.x - projectedRoadWorker.x) + abs(it.y - projectedRoadWorker.y)
            }
            response = send(
                fixture.request(
                    WorkerOperation.SetRoadConnectionOrder(
                        snapshot, civilizationId, roadWorker.id,
                        roadDestination.x, roadDestination.y,
                    ),
                    1_700_900_080_000L,
                ),
            )
            results += response
            snapshot = requireNotNull(response.snapshot)

            val finalProjection = project(1_700_900_090_000L)
            assertTrue(finalProjection.ownUnits.single { it.id == improvementWorker.id }
                .improvementOrder.isEmpty())
            val finalSwapUnit = finalProjection.ownUnits.single { it.id == swapUnit.id }
            val finalSwapPeer = finalProjection.ownUnits.single { it.id == swapPeer.id }
            assertEquals(swapDestination.position.x, finalSwapUnit.x)
            assertEquals(swapDestination.position.y, finalSwapUnit.y)
            assertEquals(swapOrigin.position.x, finalSwapPeer.x)
            assertEquals(swapOrigin.position.y, finalSwapPeer.y)
            results
        }

        assertEquals(10, responses.size)
    }

    companion object {
        private const val actorId = "account-unit-construction-parity"

        @JvmStatic
        @BeforeClass
        fun initializeRulesets() = PackagedWorkerParityHarness.initializeRulesets()
    }
}
