package com.unciv.app.server.authoritative

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.BeforeClass
import org.junit.Test

class PackagedWorkerUnitAutomationParityTests {
    @Test(timeout = 300_000)
    fun durableMovementExplorationAndAutomationAreStableAcrossFreshWorkers() {
        val fixture = PackagedWorkerScenarioFixture(
            actorId = actorId,
            gameId = "00000000-0000-4000-8000-000000000401",
            serverSeed = 531_864_297L,
        )
        val responses = PackagedWorkerParityHarness.assertStableScenario { send ->
            val results = mutableListOf<WorkerResponse>()
            var response = send(fixture.createGameRequest(1_700_400_000_000L))
            results += response

            val civilizationId = requireNotNull(response.actorCivilizationId)
            val game = fixture.decode(response.snapshot)
            val civilization = game.getCivilization(civilizationId)
            val movementUnit = civilization.units.getCivUnits().first { it.isCivilian() }
            val automationUnit = civilization.units.getCivUnits().first { it.isMilitary() }
            val explorationUnit = requireNotNull(
                civilization.units.placeUnitNearTile(
                    automationUnit.currentTile.position,
                    automationUnit.name,
                ),
            )
            val destination = game.tileMap.tileList.first {
                it != movementUnit.currentTile &&
                    movementUnit.movement.canReach(it) &&
                    movementUnit.movement.getTileToMoveToThisTurn(it) != it
            }
            destination.setExplored(civilization, true)
            var snapshot = fixture.encode(game)

            response = send(
                fixture.request(
                    WorkerOperation.MoveUnitToward(
                        snapshot = snapshot,
                        actorCivilizationId = civilizationId,
                        unitId = movementUnit.id,
                        destinationX = destination.position.x,
                        destinationY = destination.position.y,
                    ),
                    1_700_400_010_000L,
                ),
            )
            results += response
            snapshot = requireNotNull(response.snapshot)

            response = send(
                fixture.request(
                    WorkerOperation.CancelUnitMovementOrder(
                        snapshot = snapshot,
                        actorCivilizationId = civilizationId,
                        unitId = movementUnit.id,
                    ),
                    1_700_400_020_000L,
                ),
            )
            results += response
            snapshot = requireNotNull(response.snapshot)

            response = send(
                fixture.request(
                    WorkerOperation.SetUnitExploration(
                        snapshot = snapshot,
                        actorCivilizationId = civilizationId,
                        unitId = explorationUnit.id,
                        enabled = true,
                    ),
                    1_700_400_030_000L,
                ),
            )
            results += response
            snapshot = requireNotNull(response.snapshot)

            response = send(
                fixture.request(
                    WorkerOperation.SetUnitExploration(
                        snapshot = snapshot,
                        actorCivilizationId = civilizationId,
                        unitId = explorationUnit.id,
                        enabled = false,
                    ),
                    1_700_400_040_000L,
                ),
            )
            results += response
            snapshot = requireNotNull(response.snapshot)

            response = send(
                fixture.request(
                    WorkerOperation.SetUnitAutomation(
                        snapshot = snapshot,
                        actorCivilizationId = civilizationId,
                        unitId = automationUnit.id,
                        enabled = true,
                    ),
                    1_700_400_050_000L,
                ),
            )
            results += response
            snapshot = requireNotNull(response.snapshot)

            response = send(
                fixture.request(
                    WorkerOperation.SetUnitAutomation(
                        snapshot = snapshot,
                        actorCivilizationId = civilizationId,
                        unitId = automationUnit.id,
                        enabled = false,
                    ),
                    1_700_400_060_000L,
                ),
            )
            results += response

            val finalCivilization = fixture.decode(response.snapshot)
                .getCivilization(civilizationId)
            assertNull(finalCivilization.units.getUnitById(movementUnit.id)!!.action)
            assertFalse(finalCivilization.units.getUnitById(explorationUnit.id)!!.isExploring())
            assertFalse(finalCivilization.units.getUnitById(automationUnit.id)!!.isAutomated())
            results
        }

        assertEquals(7, responses.size)
    }

    companion object {
        private const val actorId = "account-unit-automation-parity"

        @JvmStatic
        @BeforeClass
        fun initializeRulesets() = PackagedWorkerParityHarness.initializeRulesets()
    }
}
