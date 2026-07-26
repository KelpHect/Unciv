package com.unciv.app.server.authoritative

import com.unciv.logic.civilization.Civilization
import com.unciv.logic.multiplayer.authoritative.GreatPersonUnitAction
import com.unciv.logic.multiplayer.authoritative.PlayerProjection
import com.unciv.models.ruleset.unique.UniqueType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test

class PackagedWorkerSpecialUnitParityTests {
    @Test(timeout = 300_000)
    fun greatPersonAndGiftActionsAreStableAcrossFreshWorkers() {
        val unitFixture = PackagedWorkerScenarioFixture(
            actorId = actorId,
            gameId = "00000000-0000-4000-8000-000000000901",
            serverSeed = 204_681_379L,
            cityStates = 1,
        )
        val responses = PackagedWorkerParityHarness.assertStableScenario { send ->
            val results = mutableListOf<WorkerResponse>()

            var response = send(unitFixture.createGameRequest(1_700_900_000_000L))
            results += response
            val civilizationId = requireNotNull(response.actorCivilizationId)
            val game = unitFixture.decode(response.snapshot)
            val actor = game.getCivilization(civilizationId)
            val cityStates = game.getAliveCityStates()
            assertEquals("Fixture requires one city state", 1, cityStates.size)
            val cityState = cityStates.first()
            val actorCity = ensureCapital(actor)
            ensureCapital(cityState)
            actor.diplomacyFunctions.makeCivilizationsMeet(cityState)
            val scientist = actor.units.addUnit(
                game.ruleset.units.values
                    .filter { it.hasUnique(UniqueType.CanHurryResearch) }
                    .minBy { it.name },
                actorCity,
            )!!
            actor.tech.techsToResearch.add("Pottery")
            actor.tech.scienceOfLast8Turns.fill(100)
            val recipientTile = game.tileMap.values.first {
                it.getOwner() == cityState && it.militaryUnit == null
            }
            val giftUnit = actor.units.addUnit(
                game.ruleset.units.values.first { it.isMilitary && !it.isWaterUnit },
                actorCity,
            )!!
            giftUnit.removeFromTile()
            giftUnit.putInTile(recipientTile)
            var snapshot = unitFixture.encode(game)

            fun project(
                fixture: PackagedWorkerScenarioFixture,
                currentSnapshot: String,
                currentCivilizationId: String,
                serverTime: Long,
            ): PlayerProjection {
                val projectionResponse = send(
                    fixture.request(
                        WorkerOperation.ProjectState(currentSnapshot, currentCivilizationId),
                        serverTime,
                    ),
                )
                results += projectionResponse
                return requireNotNull(projectionResponse.playerProjection)
            }

            var projection = project(unitFixture, snapshot, civilizationId, 1_700_900_010_000L)
            val projectedScientists = projection.ownUnits.filter { it.id == scientist.id }
            assertEquals("Projection must expose one scientist", 1, projectedScientists.size)
            val projectedScientist = projectedScientists.first()
            assertEquals(
                listOf(GreatPersonUnitAction.HurryResearch),
                projectedScientist.availableGreatPersonActions,
            )
            val projectedGiftUnits = projection.ownUnits.filter { it.id == giftUnit.id }
            assertEquals("Projection must expose one gift unit", 1, projectedGiftUnits.size)
            assertTrue(projectedGiftUnits.first().canGift)
            response = send(
                unitFixture.request(
                    WorkerOperation.UseGreatPersonUnit(
                        snapshot = snapshot,
                        actorCivilizationId = civilizationId,
                        unitId = projectedScientist.id,
                        action = projectedScientist.availableGreatPersonActions.first(),
                    ),
                    1_700_900_010_001L,
                ),
            )
            results += response
            snapshot = requireNotNull(response.snapshot)
            var canonicalGame = unitFixture.decode(snapshot)
            var canonicalActor = canonicalGame.getCivilization(civilizationId)
            assertNull(canonicalActor.units.getUnitById(scientist.id))
            assertTrue(
                canonicalActor.tech.isResearched("Pottery") ||
                    canonicalActor.tech.researchOfTech("Pottery") > 0,
            )

            projection = project(unitFixture, snapshot, civilizationId, 1_700_900_020_000L)
            val projectedGiftUnitsAfterResearch = projection.ownUnits.filter { it.id == giftUnit.id }
            assertEquals("Projection must retain one gift unit", 1, projectedGiftUnitsAfterResearch.size)
            val projectedGiftUnit = projectedGiftUnitsAfterResearch.first()
            assertTrue(projectedGiftUnit.canGift)
            response = send(
                unitFixture.request(
                    WorkerOperation.GiftUnit(
                        snapshot = snapshot,
                        actorCivilizationId = civilizationId,
                        unitId = projectedGiftUnit.id,
                    ),
                    1_700_900_020_001L,
                ),
            )
            results += response
            snapshot = requireNotNull(response.snapshot)
            canonicalGame = unitFixture.decode(snapshot)
            canonicalActor = canonicalGame.getCivilization(civilizationId)
            val canonicalCityState = canonicalGame.getCivilization(cityState.civID)
            assertNull(canonicalActor.units.getUnitById(giftUnit.id))
            assertNotNull(canonicalCityState.units.getUnitById(giftUnit.id))
            assertEquals(5f, canonicalCityState.getDiplomacyManager(canonicalActor)!!.getInfluence(), 0.001f)

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
        private const val actorId = "account-special-unit-parity"

        @JvmStatic
        @BeforeClass
        fun initializeRulesets() = PackagedWorkerParityHarness.initializeRulesets()
    }
}
