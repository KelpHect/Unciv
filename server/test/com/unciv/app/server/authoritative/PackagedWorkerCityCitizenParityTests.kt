package com.unciv.app.server.authoritative

import com.unciv.logic.multiplayer.authoritative.CityTileAssignment
import com.unciv.logic.multiplayer.authoritative.CitizenFocus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test

class PackagedWorkerCityCitizenParityTests {
    @Test(timeout = 300_000)
    fun specialistTileAndCitizenPoliciesAreStableAcrossFreshWorkers() {
        val fixture = PackagedWorkerScenarioFixture(
            actorId = actorId,
            gameId = "00000000-0000-4000-8000-000000000501",
            serverSeed = 420_753_186L,
        )
        val responses = PackagedWorkerParityHarness.assertStableScenario { send ->
            val results = mutableListOf<WorkerResponse>()
            var response = send(fixture.createGameRequest(1_700_500_000_000L))
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
                        snapshot = requireNotNull(response.snapshot),
                        actorCivilizationId = civilizationId,
                        unitId = settlerId,
                    ),
                    1_700_500_010_000L,
                ),
            )
            results += response

            val preparedGame = fixture.decode(response.snapshot)
            val preparedCivilization = preparedGame.getCivilization(civilizationId)
            val preparedCity = preparedCivilization.cities.single()
            preparedCivilization.addGold(100_000)
            val specialistBuilding = preparedCity.getRuleset().buildings.values.first {
                it.specialistSlots.isNotEmpty() && it.isSellable()
            }
            preparedCity.cityConstructions.addBuilding(specialistBuilding)
            preparedCity.workedTiles.clear()
            preparedCity.lockedTiles.clear()
            var snapshot = fixture.encode(preparedGame)

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

            var projectedCity = project(1_700_500_020_000L).ownCities.single()
            val specialist = projectedCity.specialists.first { it.capacity > 0 }
            response = send(
                fixture.request(
                    WorkerOperation.SetSpecialistCount(
                        snapshot = snapshot,
                        actorCivilizationId = civilizationId,
                        cityId = projectedCity.id,
                        specialistName = specialist.name,
                        count = 1,
                    ),
                    1_700_500_030_000L,
                ),
            )
            results += response
            snapshot = requireNotNull(response.snapshot)

            response = send(
                fixture.request(
                    WorkerOperation.SetManualSpecialists(
                        snapshot = snapshot,
                        actorCivilizationId = civilizationId,
                        cityId = projectedCity.id,
                        enabled = false,
                    ),
                    1_700_500_040_000L,
                ),
            )
            results += response
            snapshot = requireNotNull(response.snapshot)

            projectedCity = project(1_700_500_050_000L).ownCities.single()
            val workedTile = projectedCity.assignableTiles.first { it.worked }
            response = send(
                fixture.request(
                    WorkerOperation.SetCityTileAssignment(
                        snapshot = snapshot,
                        actorCivilizationId = civilizationId,
                        cityId = projectedCity.id,
                        x = workedTile.x,
                        y = workedTile.y,
                        assignment = CityTileAssignment.Locked,
                    ),
                    1_700_500_060_000L,
                ),
            )
            results += response
            snapshot = requireNotNull(response.snapshot)

            response = send(
                fixture.request(
                    WorkerOperation.ResetCitizens(
                        snapshot = snapshot,
                        actorCivilizationId = civilizationId,
                        cityId = projectedCity.id,
                    ),
                    1_700_500_070_000L,
                ),
            )
            results += response
            snapshot = requireNotNull(response.snapshot)

            response = send(
                fixture.request(
                    WorkerOperation.SetAvoidGrowth(
                        snapshot = snapshot,
                        actorCivilizationId = civilizationId,
                        cityId = projectedCity.id,
                        enabled = true,
                    ),
                    1_700_500_080_000L,
                ),
            )
            results += response
            snapshot = requireNotNull(response.snapshot)

            projectedCity = project(1_700_500_090_000L).ownCities.single()
            val focus = projectedCity.selectableCitizenFocuses.first {
                it != CitizenFocus.NoFocus
            }
            response = send(
                fixture.request(
                    WorkerOperation.SetCitizenFocus(
                        snapshot = snapshot,
                        actorCivilizationId = civilizationId,
                        cityId = projectedCity.id,
                        focus = focus,
                    ),
                    1_700_500_100_000L,
                ),
            )
            results += response
            snapshot = requireNotNull(response.snapshot)

            projectedCity = project(1_700_500_110_000L).ownCities.single()
            val tilePurchase = projectedCity.tilePurchases.first { it.affordable }
            response = send(
                fixture.request(
                    WorkerOperation.BuyCityTile(
                        snapshot = snapshot,
                        actorCivilizationId = civilizationId,
                        cityId = projectedCity.id,
                        x = tilePurchase.x,
                        y = tilePurchase.y,
                    ),
                    1_700_500_120_000L,
                ),
            )
            results += response
            snapshot = requireNotNull(response.snapshot)

            projectedCity = project(1_700_500_130_000L).ownCities.single()
            val batchPurchase = projectedCity.tileBatchPurchases.first { it.affordable }
            response = send(
                fixture.request(
                    WorkerOperation.BuyCityTileBatch(
                        snapshot = snapshot,
                        actorCivilizationId = civilizationId,
                        cityId = projectedCity.id,
                        ring = batchPurchase.ring,
                    ),
                    1_700_500_140_000L,
                ),
            )
            results += response
            snapshot = requireNotNull(response.snapshot)

            response = send(
                fixture.request(
                    WorkerOperation.SellBuilding(
                        snapshot = snapshot,
                        actorCivilizationId = civilizationId,
                        cityId = projectedCity.id,
                        buildingName = specialistBuilding.name,
                    ),
                    1_700_500_150_000L,
                ),
            )
            results += response

            val finalGame = fixture.decode(response.snapshot)
            val finalCivilization = finalGame.getCivilization(civilizationId)
            val finalCity = finalCivilization.cities.single()
            assertFalse(finalCity.manualSpecialists)
            assertTrue(finalCity.lockedTiles.isEmpty())
            assertTrue(finalCity.avoidGrowth)
            assertEquals(focus.name, finalCity.getCityFocus().name)
            assertEquals(
                finalCity,
                finalGame.tileMap[tilePurchase.x, tilePurchase.y].owningCity,
            )
            assertTrue(finalCivilization.gold < 100_000)
            assertFalse(finalCity.cityConstructions.isBuilt(specialistBuilding.name))
            results
        }

        assertEquals(16, responses.size)
    }

    companion object {
        private const val actorId = "account-city-citizen-parity"

        @JvmStatic
        @BeforeClass
        fun initializeRulesets() = PackagedWorkerParityHarness.initializeRulesets()
    }
}
