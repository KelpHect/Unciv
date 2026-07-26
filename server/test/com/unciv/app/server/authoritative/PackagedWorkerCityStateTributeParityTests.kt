package com.unciv.app.server.authoritative

import com.unciv.logic.multiplayer.authoritative.PlayerProjection
import com.unciv.logic.civilization.Civilization
import com.unciv.models.ruleset.unique.UniqueType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test

class PackagedWorkerCityStateTributeParityTests {
    @Test(timeout = 300_000)
    fun cityStateGoldTributeIsStableAcrossFreshWorkers() {
        val fixture = PackagedWorkerScenarioFixture(
            actorId = actorId,
            gameId = "00000000-0000-4000-8000-000000001201",
            serverSeed = 874_112_639L,
            cityStates = 1,
        )
        val responses = PackagedWorkerParityHarness.assertStableScenario { send ->
            val results = mutableListOf<WorkerResponse>()
            var response = send(fixture.createGameRequest(1_701_200_000_000L))
            results += response

            val civilizationId = requireNotNull(response.actorCivilizationId)
            val game = fixture.decode(response.snapshot)
            val actor = game.getCivilization(civilizationId)
            val cityState = game.getAliveCityStates().single()
            actor.diplomacyFunctions.makeCivilizationsMeet(cityState)
            val cityStateCapital = ensureCapital(cityState)
            val strongestLandUnit = game.ruleset.units.values
                .filter { it.isMilitary && it.isLandUnit }
                .maxBy { it.strength }
            val intimidationTiles = buildList {
                cityStateCapital.getCenterTile().forEachTileInDistanceRange(1..5) { tile ->
                    if (size < 8 && tile.militaryUnit == null && !tile.isWater) add(tile)
                }
            }
            assertEquals("Fixture needs enough empty land tiles", 8, intimidationTiles.size)
            intimidationTiles.forEach { tile ->
                game.tileMap.placeUnitNearTile(tile.position, strongestLandUnit, actor)
                    ?: error("Could not place intimidation unit")
            }
            var snapshot = fixture.encode(game)

            fun project(serverTime: Long): PlayerProjection {
                val projected = send(
                    fixture.request(
                        WorkerOperation.ProjectState(snapshot, civilizationId),
                        serverTime,
                    ),
                )
                results += projected
                return requireNotNull(projected.playerProjection)
            }

            val partner = project(1_701_200_010_000L).cityStatePartners.single {
                it.civilizationId == cityState.civID
            }
            val tribute = requireNotNull(partner.tributeGoldAmount)
            val goldBefore = actor.gold
            val influenceBefore = cityState.getDiplomacyManager(actor)!!.getInfluence()
            response = send(
                fixture.request(
                    WorkerOperation.DemandCityStateTribute(
                        snapshot = snapshot,
                        actorCivilizationId = civilizationId,
                        cityStateCivilizationId = partner.civilizationId,
                        worker = false,
                    ),
                    1_701_200_010_001L,
                ),
            )
            results += response

            val canonicalGame = fixture.decode(response.snapshot)
            val canonicalActor = canonicalGame.getCivilization(civilizationId)
            val canonicalCityState = canonicalGame.getCivilization(cityState.civID)
            assertEquals(goldBefore + tribute, canonicalActor.gold)
            assertEquals(
                influenceBefore - 15f,
                canonicalCityState.getDiplomacyManager(canonicalActor)!!.getInfluence(),
                0.001f,
            )
            assertNotNull(canonicalCityState.getRecentBullyingCountdown())
            assertTrue(canonicalCityState.getRecentBullyingCountdown()!! > 0)
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
        private const val actorId = "account-city-state-tribute-parity"

        @JvmStatic
        @BeforeClass
        fun initializeRulesets() = PackagedWorkerParityHarness.initializeRulesets()
    }
}
