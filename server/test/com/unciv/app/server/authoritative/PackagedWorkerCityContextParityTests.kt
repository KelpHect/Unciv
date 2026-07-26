package com.unciv.app.server.authoritative

import com.unciv.logic.civilization.AlertType
import com.unciv.logic.civilization.PopupAlert
import com.unciv.logic.multiplayer.authoritative.CityDispositionAction
import com.unciv.logic.multiplayer.authoritative.CityGovernanceAction
import com.unciv.logic.multiplayer.authoritative.ProjectedConstructionKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test

class PackagedWorkerCityContextParityTests {
    @Test(timeout = 300_000)
    fun perpetualPurchaseDispositionAndGovernanceAreStableAcrossFreshWorkers() {
        val fixture = PackagedWorkerScenarioFixture(
            actorId = actorId,
            gameId = "00000000-0000-4000-8000-000000001001",
            serverSeed = 315_792_648L,
        )
        val responses = PackagedWorkerParityHarness.assertStableScenario { send ->
            val results = mutableListOf<WorkerResponse>()
            var response = send(fixture.createGameRequest(1_701_000_000_000L))
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
                    1_701_000_010_000L,
                ),
            )
            results += response

            val game = fixture.decode(response.snapshot)
            val civilization = game.getCivilization(civilizationId)
            civilization.tech.techsResearched.addAll(game.ruleset.technologies.keys)
            civilization.addGold(100_000)
            val capital = civilization.cities.single()
            val capturedTile = civilization.viewableTiles
                .filter { it.isLand && !it.isCityCenter() && it.getUnits().none() }
                .minWith(compareBy({ it.position.x }, { it.position.y }))
            val captured = civilization.addCity(capturedTile.position)
            captured.isPuppet = true
            civilization.popupAlerts += PopupAlert(AlertType.CityConquered, captured.id)
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

            var projection = project(1_701_000_020_000L)
            var projectedCapital = projection.ownCities.single { it.id == capital.id }
            val perpetual = projectedCapital.constructionOptions.first {
                it.kind == ProjectedConstructionKind.Perpetual && it.queueable
            }
            response = send(
                fixture.request(
                    WorkerOperation.SetPerpetualConstruction(
                        snapshot, civilizationId, capital.id, perpetual.name,
                    ),
                    1_701_000_030_000L,
                ),
            )
            results += response
            snapshot = requireNotNull(response.snapshot)

            projectedCapital = project(1_701_000_040_000L)
                .ownCities.single { it.id == capital.id }
            val purchaseOption = projectedCapital.constructionOptions.first { option ->
                option.name in game.ruleset.buildings &&
                    option.purchases.any { it.allowed && !it.requiresTile }
            }
            val purchase = purchaseOption.purchases.first { it.allowed && !it.requiresTile }
            response = send(
                fixture.request(
                    WorkerOperation.PurchaseConstruction(
                        snapshot, civilizationId, capital.id, purchaseOption.name,
                        purchase.currency, null,
                    ),
                    1_701_000_050_000L,
                ),
            )
            results += response
            snapshot = requireNotNull(response.snapshot)

            projection = project(1_701_000_060_000L)
            val disposition = projection.pendingCityDispositions.single {
                it.cityId == captured.id
            }
            assertTrue(CityDispositionAction.Annex in disposition.availableActions)
            response = send(
                fixture.request(
                    WorkerOperation.ResolveCityDisposition(
                        snapshot, civilizationId, captured.id, CityDispositionAction.Annex,
                    ),
                    1_701_000_070_000L,
                ),
            )
            results += response
            snapshot = requireNotNull(response.snapshot)

            projection = project(1_701_000_080_000L)
            val projectedCaptured = projection.ownCities.single { it.id == captured.id }
            val governance = projectedCaptured.availableGovernanceActions.first()
            response = send(
                fixture.request(
                    WorkerOperation.SetCityGovernance(
                        snapshot, civilizationId, captured.id, governance,
                    ),
                    1_701_000_090_000L,
                ),
            )
            results += response
            snapshot = requireNotNull(response.snapshot)

            val finalGame = fixture.decode(snapshot)
            val finalCapital = finalGame.getCivilization(civilizationId)
                .cities.single { it.id == capital.id }
            val finalCaptured = finalGame.getCivilization(civilizationId)
                .cities.single { it.id == captured.id }
            assertTrue(perpetual.name in finalCapital.cityConstructions.constructionQueue)
            assertTrue(finalCapital.cityConstructions.isBuilt(purchaseOption.name))
            assertFalse(finalCaptured.isPuppet)
            assertEquals(governance == CityGovernanceAction.StartRazing, finalCaptured.isBeingRazed)
            assertTrue(finalGame.getCivilization(civilizationId).popupAlerts.none {
                it.type == AlertType.CityConquered && it.value == captured.id
            })
            results
        }

        assertEquals(10, responses.size)
    }

    companion object {
        private const val actorId = "account-city-context-parity"

        @JvmStatic
        @BeforeClass
        fun initializeRulesets() = PackagedWorkerParityHarness.initializeRulesets()
    }
}
