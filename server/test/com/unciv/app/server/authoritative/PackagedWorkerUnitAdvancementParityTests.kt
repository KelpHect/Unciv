package com.unciv.app.server.authoritative

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test

class PackagedWorkerUnitAdvancementParityTests {
    @Test(timeout = 300_000)
    fun promotionsCityDefaultsAndBatchUpgradesAreStableAcrossFreshWorkers() {
        val fixture = PackagedWorkerScenarioFixture(
            actorId = actorId,
            gameId = "00000000-0000-4000-8000-000000000801",
            serverSeed = 198_531_764L,
        )
        val responses = PackagedWorkerParityHarness.assertStableScenario { send ->
            val results = mutableListOf<WorkerResponse>()
            var response = send(fixture.createGameRequest(1_700_800_000_000L))
            results += response

            val civilizationId = requireNotNull(response.actorCivilizationId)
            val preparedGame = fixture.decode(response.snapshot)
            val civilization = preparedGame.getCivilization(civilizationId)
            val promotionUnit = civilization.units.getCivUnits().first {
                it.promotions.getAvailablePromotions().any()
            }
            val city = civilization.addCity(promotionUnit.currentTile.position)
            promotionUnit.promotions.XP = 1_000
            civilization.addGold(10_000)
            val upgradeUnits = listOf(
                requireNotNull(civilization.units.addUnit("Archer", city)),
                requireNotNull(civilization.units.addUnit("Archer", city)),
            )
            val fixtureUpgradeTarget = civilization.getEquivalentUnit(
                upgradeUnits.first().baseUnit
                    .getUpgradeUnits(upgradeUnits.first().cache.state)
                    .first(),
            )
            fixtureUpgradeTarget.requiredTech?.let(civilization.tech::addTechnology)
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

            var projection = project(1_700_800_010_000L)
            val projectedPromotionUnit = projection.ownUnits.single {
                it.id == promotionUnit.id
            }
            val promotion = projectedPromotionUnit.availablePromotions.first()
            val projectedUpgradeUnits = upgradeUnits.map { unit ->
                projection.ownUnits.single { it.id == unit.id }
            }
            val upgradeTarget = projectedUpgradeUnits
                .map { unit -> unit.availableUpgradeTargets.map { it.targetUnitName }.toSet() }
                .reduce(Set<String>::intersect)
                .first()

            response = send(
                fixture.request(
                    WorkerOperation.PromoteUnit(
                        snapshot = snapshot,
                        actorCivilizationId = civilizationId,
                        unitId = promotionUnit.id,
                        promotionNames = listOf(promotion),
                        saveAsCityDefault = true,
                    ),
                    1_700_800_020_000L,
                ),
            )
            results += response
            snapshot = requireNotNull(response.snapshot)

            projection = project(1_700_800_030_000L)
            val preference = projection.ownCities.single { it.id == city.id }
                .unitPromotionPreferences.single {
                    it.baseUnitName == promotionUnit.baseUnit.name
                }
            assertTrue(preference.enabled)
            response = send(
                fixture.request(
                    WorkerOperation.SetCityUnitPromotionPreference(
                        snapshot = snapshot,
                        actorCivilizationId = civilizationId,
                        cityId = city.id,
                        baseUnitName = preference.baseUnitName,
                        enabled = false,
                    ),
                    1_700_800_040_000L,
                ),
            )
            results += response
            snapshot = requireNotNull(response.snapshot)

            response = send(
                fixture.request(
                    WorkerOperation.UpgradeUnits(
                        snapshot = snapshot,
                        actorCivilizationId = civilizationId,
                        unitIds = upgradeUnits.map { it.id },
                        targetUnitName = upgradeTarget,
                    ),
                    1_700_800_050_000L,
                ),
            )
            results += response
            snapshot = requireNotNull(response.snapshot)

            val finalProjection = project(1_700_800_060_000L)
            assertTrue(promotion in finalProjection.ownUnits
                .single { it.id == promotionUnit.id }.promotions)
            assertFalse(finalProjection.ownCities.single { it.id == city.id }
                .unitPromotionPreferences.single().enabled)
            assertTrue(upgradeUnits.all { unit ->
                finalProjection.ownUnits.single { it.id == unit.id }.name == upgradeTarget
            })
            results
        }

        assertEquals(7, responses.size)
    }

    companion object {
        private const val actorId = "account-unit-advancement-parity"

        @JvmStatic
        @BeforeClass
        fun initializeRulesets() = PackagedWorkerParityHarness.initializeRulesets()
    }
}
