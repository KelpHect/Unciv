package com.unciv.app.server.authoritative

import com.unciv.logic.GameInfo
import com.unciv.logic.files.UncivFiles
import com.unciv.logic.multiplayer.authoritative.UnitControlProjection
import com.unciv.models.ruleset.RulesetCache
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.BeforeClass
import org.junit.Test

class PackagedWorkerUnitOrderParityTests {
    @Test(timeout = 300_000)
    fun unitMovementNamingPostureAndDisbandAreStableAcrossFreshWorkers() {
        val responses = PackagedWorkerParityHarness.assertStableScenario { send ->
            val results = mutableListOf<WorkerResponse>()
            var response = send(createGameRequest())
            results += response

            var game = decode(response.snapshot)
            val civilizationId = requireNotNull(response.actorCivilizationId)
            var civilization = game.getCivilization(civilizationId)
            val military = civilization.units.getCivUnits().first { it.isMilitary() }
            val civilian = civilization.units.getCivUnits().first { it.isCivilian() }
            val destination = military.movement.getDistanceToTiles().keys.first {
                it != military.getTile() && military.movement.canMoveTo(it)
            }

            response = send(
                request(
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
                request(
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

            game = decode(response.snapshot)
            civilization = game.getCivilization(civilizationId)
            val posture = UnitControlProjection.availablePostures(
                civilization.units.getUnitById(military.id)!!,
            ).first()
            response = send(
                request(
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
                request(
                    WorkerOperation.DisbandUnit(
                        snapshot = requireNotNull(response.snapshot),
                        actorCivilizationId = civilizationId,
                        unitId = civilian.id,
                    ),
                    1_700_200_040_000L,
                ),
            )
            results += response

            game = decode(response.snapshot)
            civilization = game.getCivilization(civilizationId)
            assertEquals(destination.position, civilization.units.getUnitById(military.id)!!.getTile().position)
            assertEquals("Fresh Process Guard", civilization.units.getUnitById(military.id)!!.instanceName)
            assertNull(civilization.units.getUnitById(civilian.id))
            results
        }

        assertEquals(5, responses.size)
    }

    private fun createGameRequest(): WorkerRequest {
        val baseName = "Civ V - Vanilla"
        val base = InstalledRulesetCatalog.named(baseName)
        val ruleset = requireNotNull(RulesetCache[baseName])
        return WorkerRequest(
            protocolVersion = EngineWorkerProtocol.VERSION,
            serverTimeMillis = 1_700_200_000_000L,
            actorId = actorId,
            rulesetManifest = manifest,
            operation = WorkerOperation.CreateGame(
                gameId = "00000000-0000-4000-8000-000000000201",
                serverSeed = 864_209_753L,
                setup = WorkerGameSetup(
                    difficulty = ruleset.difficulties.keys.first(),
                    speed = ruleset.speeds.keys.first(),
                    startingEra = ruleset.eras.keys.first(),
                    victoryTypes = ruleset.victories.values
                        .filterNot { it.hiddenInVictoryScreen }
                        .map { it.name }
                        .sorted(),
                    majorCivilizations = 2,
                    cityStates = 0,
                    maxTurns = 500,
                    mapType = GeneratedMapType.Pangaea,
                    mapShape = GeneratedMapShape.Rectangular,
                    mapSize = GeneratedMapSize.Tiny,
                    mapResources = MapResourceDensity.Default,
                    barbarians = BarbarianMode.Disabled,
                    oneCityChallenge = false,
                    nuclearWeaponsEnabled = true,
                    espionageEnabled = true,
                    noStartBias = true,
                    shufflePlayerOrder = false,
                    noCityRazing = false,
                    worldWrap = false,
                    strategicBalance = false,
                    legendaryStart = false,
                    noRuins = true,
                    noNaturalWonders = true,
                    minutesUntilSkipTurn = 1_440,
                    minutesUntilForceResign = 4_320,
                    minutesRecoveredPerTurn = 1_440,
                ),
            ),
        )
    }

    private fun request(operation: WorkerOperation, serverTime: Long) = WorkerRequest(
        protocolVersion = EngineWorkerProtocol.VERSION,
        serverTimeMillis = serverTime,
        actorId = actorId,
        rulesetManifest = manifest,
        operation = operation,
    )

    private fun decode(snapshot: String?): GameInfo =
        UncivFiles.gameInfoFromString(requireNotNull(snapshot)).also(GameInfo::setTransients)

    companion object {
        private const val actorId = "account-unit-order-parity"
        private val manifest by lazy {
            WorkerRulesetManifest(
                engineBuild = InstalledRulesetCatalog.engineBuild,
                baseRuleset = InstalledRulesetCatalog.named("Civ V - Vanilla"),
            )
        }

        @JvmStatic
        @BeforeClass
        fun initializeRulesets() = PackagedWorkerParityHarness.initializeRulesets()
    }
}
