package com.unciv.app.server.authoritative

import com.unciv.Constants
import com.unciv.logic.civilization.Civilization
import com.unciv.logic.multiplayer.authoritative.CityStateCommandExecutor
import com.unciv.models.ruleset.unique.UniqueType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test

class PackagedWorkerCityStateImprovementGiftParityTests {
    @Test(timeout = 300_000)
    fun cityStateImprovementGiftIsStableAcrossFreshWorkers() {
        val fixture = PackagedWorkerScenarioFixture(
            actorId = actorId,
            gameId = "00000000-0000-4000-8000-000000001301",
            serverSeed = 874_113_641L,
            cityStates = 1,
        )
        val responses = PackagedWorkerParityHarness.assertStableScenario { send ->
            val results = mutableListOf<WorkerResponse>()
            var response = send(fixture.createGameRequest(1_701_300_000_000L))
            results += response

            val civilizationId = requireNotNull(response.actorCivilizationId)
            val game = fixture.decode(response.snapshot)
            val actor = game.getCivilization(civilizationId)
            val cityState = game.getAliveCityStates().single()
            actor.diplomacyFunctions.makeCivilizationsMeet(cityState)
            actor.addGold(500)
            cityState.getDiplomacyManager(actor)!!.addInfluence(100f)
            game.ruleset.technologies.keys.forEach {
                cityState.tech.addTechnology(it, showNotification = false)
            }

            val capital = ensureCapital(cityState)
            val targetTile = capital.getCenterTile().neighbors
                .first { !it.isWater && !it.isCityCenter() }
            capital.expansion.takeOwnership(targetTile)
            targetTile.setBaseTerrain(requireNotNull(game.ruleset.terrains[Constants.grassland]))
            targetTile.setTerrainFeatures(listOf())
            targetTile.naturalWonder = null
            targetTile.setTileResource("Iron")
            targetTile.resourceAmount = 1
            targetTile.removeImprovement()

            val mine = requireNotNull(game.ruleset.tileImprovements["Mine"])
            assertTrue(game.ruleset.tileResources["Iron"]!!.isImprovedBy(mine.name))
            assertTrue(targetTile.improvementFunctions.canBuildImprovement(mine, cityState.state))
            val initialGift = CityStateCommandExecutor.partners(actor)
                .single { it.civilizationId == cityState.civID }
                .improvementGifts
                .single { it.x == targetTile.position.x && it.y == targetTile.position.y && it.improvementName == mine.name }
            var snapshot = fixture.encode(game)

            val projected = send(
                fixture.request(
                    WorkerOperation.ProjectState(snapshot, civilizationId),
                    1_701_300_010_000L,
                ),
            )
            results += projected
            val projectedGift = requireNotNull(projected.playerProjection)
                .cityStatePartners
                .single { it.civilizationId == cityState.civID }
                .improvementGifts
                .single { it.x == initialGift.x && it.y == initialGift.y && it.improvementName == initialGift.improvementName }
            assertEquals(initialGift.goldCost, projectedGift.goldCost)

            val goldBefore = actor.gold
            response = send(
                fixture.request(
                    WorkerOperation.GiftCityStateImprovement(
                        snapshot = snapshot,
                        actorCivilizationId = civilizationId,
                        cityStateCivilizationId = cityState.civID,
                        x = projectedGift.x,
                        y = projectedGift.y,
                        improvementName = projectedGift.improvementName,
                    ),
                    1_701_300_010_001L,
                ),
            )
            results += response
            snapshot = requireNotNull(response.snapshot)

            val canonicalGame = fixture.decode(snapshot)
            val canonicalActor = canonicalGame.getCivilization(civilizationId)
            val canonicalTile = canonicalGame.tileMap[projectedGift.x, projectedGift.y]
            assertEquals(goldBefore - projectedGift.goldCost, canonicalActor.gold)
            assertEquals(projectedGift.improvementName, canonicalTile.improvement)
            assertEquals(cityState.civID, canonicalTile.getOwner()?.civID)
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
        private const val actorId = "account-city-state-improvement-gift-parity"

        @JvmStatic
        @BeforeClass
        fun initializeRulesets() = PackagedWorkerParityHarness.initializeRulesets()
    }
}
