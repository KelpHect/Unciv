package com.unciv.app.server.authoritative

import com.unciv.logic.civilization.AlertType
import com.unciv.logic.civilization.Civilization
import com.unciv.logic.civilization.diplomacy.DiplomacyFlags
import com.unciv.models.ruleset.unique.UniqueType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test

class PackagedWorkerCityStateMarriageParityTests {
    @Test(timeout = 300_000)
    fun diplomaticMarriageIsStableAcrossFreshWorkers() {
        val fixture = PackagedWorkerScenarioFixture(
            actorId = actorId,
            gameId = "00000000-0000-4000-8000-000000000035",
            serverSeed = 941_278_360L,
            baseName = "Civ V - Gods & Kings",
            cityStates = 1,
            ownerCivilizationId = "Austria",
        )
        val responses = PackagedWorkerParityHarness.assertStableScenario { send ->
            val results = mutableListOf<WorkerResponse>()
            var response = send(fixture.createGameRequest(1_701_400_000_000L))
            results += response

            val civilizationId = requireNotNull(response.actorCivilizationId)
            val game = fixture.decode(response.snapshot)
            val actor = game.getCivilization(civilizationId)
            assertEquals("Austria", actor.civName)
            val cityState = game.getAliveCityStates().single()
            ensureCapital(cityState)
            actor.diplomacyFunctions.makeCivilizationsMeet(cityState)
            cityState.getDiplomacyManager(actor)!!.addInfluence(100f)
            actor.getDiplomacyManager(cityState)!!.removeFlag(DiplomacyFlags.MarriageCooldown)
            actor.addGold(10_000 - actor.gold)
            val cityIds = cityState.cities.map { it.id }
            val snapshot = fixture.encode(game)

            val projection = send(
                fixture.request(
                    WorkerOperation.ProjectState(snapshot, civilizationId),
                    1_701_400_010_000L,
                ),
            )
            results += projection
            val cost = requireNotNull(
                projection.playerProjection!!.cityStatePartners.single().diplomaticMarriageCost,
            )
            response = send(
                fixture.request(
                    WorkerOperation.MarryCityState(snapshot, civilizationId, cityState.civID),
                    1_701_400_010_001L,
                ),
            )
            results += response

            val canonical = fixture.decode(response.snapshot)
            val canonicalActor = canonical.getCivilization(civilizationId)
            assertEquals(10_000 - cost, canonicalActor.gold)
            assertTrue(canonical.getCivilization(cityState.civID).isDefeated())
            assertTrue(cityIds.all { id -> canonicalActor.cities.any { it.id == id } })
            assertEquals(
                cityIds.toSet(),
                canonicalActor.popupAlerts
                    .filter { it.type == AlertType.DiplomaticMarriage }
                    .map { it.value }
                    .toSet(),
            )
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
        private const val actorId = "account-city-state-marriage-parity"

        @JvmStatic
        @BeforeClass
        fun initializeRulesets() = PackagedWorkerParityHarness.initializeRulesets()
    }
}
