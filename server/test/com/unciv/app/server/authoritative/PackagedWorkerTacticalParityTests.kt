package com.unciv.app.server.authoritative

import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test

class PackagedWorkerTacticalParityTests {
    @Test(timeout = 300_000)
    fun pillageDropBombardSweepAndNukeAreStableAcrossFreshWorkers() {
        val fixture = PackagedWorkerScenarioFixture(
            actorId = actorId,
            gameId = "00000000-0000-4000-8000-000000001201",
            serverSeed = 297_531_864L,
        )
        val responses = PackagedWorkerParityHarness.assertStableScenario { send ->
            val results = mutableListOf<WorkerResponse>()
            var response = send(fixture.createGameRequest(1_701_200_000_000L))
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
                    1_701_200_010_000L,
                ),
            )
            results += response

            val game = fixture.decode(response.snapshot)
            val actor = game.getCivilization(civilizationId)
            val opponent = game.civilizations.single {
                it.isMajorCiv() && it.civID != civilizationId
            }
            actor.tech.techsResearched.addAll(game.ruleset.technologies.keys)
            opponent.tech.techsResearched.addAll(game.ruleset.technologies.keys)
            actor.diplomacyFunctions.makeCivilizationsMeet(opponent)
            actor.getDiplomacyManager(opponent)!!.declareWar()
            val city = actor.cities.single()
            val pillager = actor.units.getCivUnits().first { it.isMilitary() }
            val pillageTile = actor.viewableTiles
                .filter {
                    it.isLand && !it.isCityCenter() && it.militaryUnit == null &&
                        it.getOwner() != actor
                }
                .minWith(compareBy({ it.position.x }, { it.position.y }))
            pillager.removeFromTile()
            pillager.putInTile(pillageTile)
            pillageTile.setImprovement("Farm")
            pillager.health = 50
            val paratrooper = requireNotNull(actor.units.placeUnitNearTile(city.location, "Paratrooper"))
            val bombardDefender = opponent.units.getCivUnits().first { it.isMilitary() }
            val bombardTile = game.tileMap.tileList.asSequence()
                .filter {
                    city.getCenterTile().aerialDistanceTo(it) == 2 &&
                        it.isLand && it.militaryUnit == null
                }
                .minWith(compareBy({ it.position.x }, { it.position.y }))
            bombardDefender.removeFromTile()
            bombardDefender.putInTile(bombardTile)
            val enemyCityTile = game.tileMap.tileList.asSequence()
                .filter {
                    city.getCenterTile().aerialDistanceTo(it) == 4 &&
                        it.isLand && !it.isCityCenter() && it.getUnits().none()
                }
                .minWith(compareBy({ it.position.x }, { it.position.y }))
            val enemyCity = opponent.addCity(enemyCityTile.position)
            val fighter = requireNotNull(actor.units.placeUnitNearTile(city.location, "Fighter"))
            requireNotNull(opponent.units.placeUnitNearTile(enemyCity.location, "Fighter"))
            requireNotNull(opponent.units.placeUnitNearTile(enemyCity.location, "Fighter"))
            val nuke = requireNotNull(actor.units.placeUnitNearTile(city.location, "Nuclear Missile"))
            actor.cache.updateViewableTiles()
            opponent.cache.updateViewableTiles()
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

            var projection = project(1_701_200_020_000L)
            assertTrue(projection.ownUnits.single { it.id == pillager.id }.canPillage)
            response = send(
                fixture.request(
                    WorkerOperation.PillageTile(snapshot, civilizationId, pillager.id),
                    1_701_200_030_000L,
                ),
            )
            results += response
            snapshot = requireNotNull(response.snapshot)

            projection = project(1_701_200_040_000L)
            val projectedDrop = projection.ownUnits.single { it.id == paratrooper.id }
                .paradropDestinations.first()
            response = send(
                fixture.request(
                    WorkerOperation.ParadropUnit(
                        snapshot, civilizationId, paratrooper.id,
                        projectedDrop.x, projectedDrop.y,
                    ),
                    1_701_200_050_000L,
                ),
            )
            results += response
            snapshot = requireNotNull(response.snapshot)

            projection = project(1_701_200_060_000L)
            val projectedBombard = projection.ownCities.single { it.id == city.id }
                .bombardTargets.single {
                    it.x == bombardTile.position.x && it.y == bombardTile.position.y
                }
            response = send(
                fixture.request(
                    WorkerOperation.BombardWithCity(
                        snapshot, civilizationId, city.id,
                        projectedBombard.x, projectedBombard.y,
                    ),
                    1_701_200_070_000L,
                ),
            )
            results += response
            snapshot = requireNotNull(response.snapshot)

            projection = project(1_701_200_080_000L)
            val projectedSweep = projection.ownUnits.single { it.id == fighter.id }
                .airSweepTargets.single {
                    it.x == enemyCity.location.x && it.y == enemyCity.location.y
                }
            response = send(
                fixture.request(
                    WorkerOperation.AirSweep(
                        snapshot, civilizationId, fighter.id,
                        projectedSweep.x, projectedSweep.y,
                    ),
                    1_701_200_090_000L,
                ),
            )
            results += response
            snapshot = requireNotNull(response.snapshot)

            projection = project(1_701_200_100_000L)
            val projectedNukeUnit = projection.ownUnits.single { it.id == nuke.id }
            val projectedNuke = projectedNukeUnit.nuclearTargetCandidates.maxBy {
                abs(it.x - projectedNukeUnit.x) + abs(it.y - projectedNukeUnit.y)
            }
            response = send(
                fixture.request(
                    WorkerOperation.LaunchNuclearStrike(
                        snapshot, civilizationId, nuke.id,
                        projectedNuke.x, projectedNuke.y,
                    ),
                    1_701_200_110_000L,
                ),
            )
            results += response

            val finalGame = fixture.decode(response.snapshot)
            val finalActor = finalGame.getCivilization(civilizationId)
            assertTrue(finalActor.units.getUnitById(pillager.id)!!.currentTile.improvementIsPillaged)
            assertEquals(75, finalActor.units.getUnitById(pillager.id)!!.health)
            assertEquals(projectedDrop.x, finalActor.units.getUnitById(paratrooper.id)!!.currentTile.position.x)
            assertEquals(projectedDrop.y, finalActor.units.getUnitById(paratrooper.id)!!.currentTile.position.y)
            assertTrue(finalActor.attacksSinceTurnStart.any { it.target == bombardTile.position })
            assertEquals(1, finalActor.units.getUnitById(fighter.id)!!.attacksThisTurn)
            assertNull(finalActor.units.getUnitById(nuke.id))
            results
        }

        assertEquals(12, responses.size)
    }

    companion object {
        private const val actorId = "account-tactical-parity"

        @JvmStatic
        @BeforeClass
        fun initializeRulesets() = PackagedWorkerParityHarness.initializeRulesets()
    }
}
