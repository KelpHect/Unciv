package com.unciv.app.server.authoritative

import com.unciv.logic.multiplayer.authoritative.UnitControlProjection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.BeforeClass
import org.junit.Test

class PackagedWorkerUnitOrderParityTests {
    @Test(timeout = 300_000)
    fun unitMovementNamingPostureAndDisbandAreStableAcrossFreshWorkers() {
        val fixture = PackagedWorkerScenarioFixture(
            actorId = actorId,
            gameId = "00000000-0000-4000-8000-000000000201",
            serverSeed = 864_209_753L,
        )
        val responses = PackagedWorkerParityHarness.assertStableScenario { send ->
            val results = mutableListOf<WorkerResponse>()
            var response = send(fixture.createGameRequest(1_700_200_000_000L))
            results += response

            var game = fixture.decode(response.snapshot)
            val civilizationId = requireNotNull(response.actorCivilizationId)
            var civilization = game.getCivilization(civilizationId)
            val military = civilization.units.getCivUnits().first { it.isMilitary() }
            val civilian = civilization.units.getCivUnits().first { it.isCivilian() }
            val destination = military.movement.getDistanceToTiles().keys.first {
                it != military.getTile() && military.movement.canMoveTo(it)
            }

            response = send(
                fixture.request(
                    WorkerOperation.MoveUnit(
                        snapshot = requireNotNull(response.snapshot),
                        actorCivilizationId = civilizationId,
                        unitId = military.id,
                        destinationX = destination.position.x,
                        destinationY = destination.position.y,
                    ),
                    1_700_200_010_000L,
                ),
            )
            results += response

            response = send(
                fixture.request(
                    WorkerOperation.RenameUnit(
                        snapshot = requireNotNull(response.snapshot),
                        actorCivilizationId = civilizationId,
                        unitId = military.id,
                        instanceName = "Fresh Process Guard",
                    ),
                    1_700_200_020_000L,
                ),
            )
            results += response

            game = fixture.decode(response.snapshot)
            civilization = game.getCivilization(civilizationId)
            val posture = UnitControlProjection.availablePostures(
                civilization.units.getUnitById(military.id)!!,
            ).first()
            response = send(
                fixture.request(
                    WorkerOperation.SetUnitPosture(
                        snapshot = requireNotNull(response.snapshot),
                        actorCivilizationId = civilizationId,
                        unitId = military.id,
                        posture = posture,
                    ),
                    1_700_200_030_000L,
                ),
            )
            results += response

            response = send(
                fixture.request(
                    WorkerOperation.DisbandUnit(
                        snapshot = requireNotNull(response.snapshot),
                        actorCivilizationId = civilizationId,
                        unitId = civilian.id,
                    ),
                    1_700_200_040_000L,
                ),
            )
            results += response

            game = fixture.decode(response.snapshot)
            civilization = game.getCivilization(civilizationId)
            assertEquals(destination.position, civilization.units.getUnitById(military.id)!!.getTile().position)
            assertEquals("Fresh Process Guard", civilization.units.getUnitById(military.id)!!.instanceName)
            assertNull(civilization.units.getUnitById(civilian.id))
            results
        }

        assertEquals(5, responses.size)
    }

    companion object {
        private const val actorId = "account-unit-order-parity"

        @JvmStatic
        @BeforeClass
        fun initializeRulesets() = PackagedWorkerParityHarness.initializeRulesets()
    }
}
