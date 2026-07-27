package com.unciv.app.server.authoritative

import com.unciv.logic.GameInfo
import com.unciv.logic.civilization.AlertType
import com.unciv.logic.civilization.PopupAlert
import com.unciv.logic.files.UncivFiles
import com.unciv.logic.map.MapType
import com.unciv.logic.multiplayer.authoritative.EventChoiceCommandExecutor
import com.unciv.models.ruleset.RulesetCache
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test

class PackagedWorkerRandomParityTests {
    @Test(timeout = 300_000)
    fun everyGeneratedMapTypeIsByteStableAcrossFreshWorkers() {
        val baseName = "Civ V - Vanilla"
        val manifest = WorkerRulesetManifest(
            engineBuild = InstalledRulesetCatalog.engineBuild,
            baseRuleset = InstalledRulesetCatalog.named(baseName),
        )
        val mapTypes = listOf(
            GeneratedMapType.Pangaea to MapType.pangaea,
            GeneratedMapType.SmallContinents to MapType.smallContinents,
            GeneratedMapType.Perlin to MapType.perlin,
            GeneratedMapType.Fractal to MapType.fractal,
            GeneratedMapType.ContinentAndIslands to MapType.continentAndIslands,
            GeneratedMapType.Archipelago to MapType.archipelago,
            GeneratedMapType.TwoContinents to MapType.twoContinents,
            GeneratedMapType.ThreeContinents to MapType.threeContinents,
            GeneratedMapType.InnerSea to MapType.innerSea,
            GeneratedMapType.Lakes to MapType.lakes,
            GeneratedMapType.FourCorners to MapType.fourCorners,
            GeneratedMapType.Spiral to MapType.spiral,
            GeneratedMapType.Boreal to MapType.boreal,
        )
        assertEquals(GeneratedMapType.entries.size, mapTypes.size)

        val responses = PackagedWorkerParityHarness.assertStableScenario { send ->
            mapTypes.mapIndexed { index, (mapType, _) ->
                val gameIdSuffix = (200 + index).toString().padStart(12, '0')
                send(
                    WorkerRequest(
                        protocolVersion = EngineWorkerProtocol.VERSION,
                        serverTimeMillis = 1_700_090_000_000L + index,
                        actorId = actorId,
                        rulesetManifest = manifest,
                        operation = WorkerOperation.CreateGame(
                            gameId = "00000000-0000-4000-8000-$gameIdSuffix",
                            serverSeed = 741_852_963L + index,
                            setup = setup(baseName).copy(mapType = mapType),
                        ),
                    ),
                )
            }
        }

        responses.zip(mapTypes).forEachIndexed { index, (response, mapType) ->
            val game = decode(response.snapshot)
            assertEquals(mapType.second, game.tileMap.mapParameters.type)
            assertEquals(741_852_963L + index, game.tileMap.mapParameters.seed)
            assertTrue(game.tileMap.values.isNotEmpty())
        }
    }

    @Test(timeout = 300_000)
    fun richSeededGameCreationIsByteStableAcrossFreshWorkers() {
        val baseName = "Civ V - Gods & Kings"
        val base = InstalledRulesetCatalog.named(baseName)
        val manifest = WorkerRulesetManifest(
            engineBuild = InstalledRulesetCatalog.engineBuild,
            baseRuleset = base,
        )
        val operation = WorkerOperation.CreateGame(
            gameId = "00000000-0000-4000-8000-000000000100",
            serverSeed = 864_209_753L,
            setup = setup(baseName).copy(
                majorCivilizations = 3,
                cityStates = 3,
                mapType = GeneratedMapType.Fractal,
                mapShape = GeneratedMapShape.Hexagonal,
                mapSize = GeneratedMapSize.Small,
                mapResources = MapResourceDensity.Abundant,
                barbarians = BarbarianMode.Raging,
                noStartBias = false,
                shufflePlayerOrder = true,
                strategicBalance = true,
                noRuins = false,
                noNaturalWonders = false,
            ),
        )
        val request = WorkerRequest(
            protocolVersion = EngineWorkerProtocol.VERSION,
            serverTimeMillis = 1_700_100_000_000L,
            actorId = actorId,
            rulesetManifest = manifest,
            operation = operation,
        )

        val result = PackagedWorkerParityHarness.assertStable(request)
        val game = decode(result.snapshot)
        assertEquals(3, game.gameParameters.numberOfCityStates)
        assertTrue(game.gameParameters.ragingBarbarians)
        assertTrue(game.gameParameters.shufflePlayerOrder)
        assertTrue(game.tileMap.values.any { it.resource != null })
        assertTrue(game.tileMap.values.any { it.naturalWonder != null })
        assertTrue(
            game.tileMap.values.any { tile ->
                tile.improvement?.let {
                    game.ruleset.tileImprovements[it]?.isAncientRuinsEquivalent()
                } == true
            },
        )
        assertEquals(
            2,
            game.civilizations.count { it.isMajorCiv() && it.isAI() },
        )

        val changedSeed = PackagedWorkerParityHarness.execute(
            request.copy(
                operation = operation.copy(
                    serverSeed = operation.serverSeed + 1,
                ),
            ),
        )
        assertNull(changedSeed.error)
        assertNotEquals(result.canonicalStateHash, changedSeed.canonicalStateHash)
        assertNotEquals(result.snapshot, changedSeed.snapshot)
    }

    @Test(timeout = 300_000)
    fun canonicalCombatRandomnessIsByteStableAcrossFreshWorkers() {
        val fixture = createFixture("Civ V - Vanilla", "00000000-0000-4000-8000-000000000101")
        val game = decode(fixture.response.snapshot)
        val actor = game.getCivilization(requireNotNull(fixture.response.actorCivilizationId))
        val enemy = game.civilizations.first {
            it.isMajorCiv() && it.civID != actor.civID
        }
        actor.diplomacyFunctions.makeCivilizationsMeet(enemy)
        actor.getDiplomacyManager(enemy)!!.declareWar()
        val attacker = actor.units.getCivUnits().first { !it.isCivilian() }
        val defender = enemy.units.getCivUnits().first { !it.isCivilian() }
        val target = attacker.currentTile.neighbors
            .first { it.isLand && it.militaryUnit == null }
        defender.removeFromTile()
        defender.putInTile(target)
        actor.cache.updateViewableTiles()
        val beforeAttackerHealth = attacker.health
        val beforeDefenderHealth = defender.health
        val request = request(
            fixture,
            WorkerOperation.AttackWithUnit(
                snapshot = encode(game),
                actorCivilizationId = actor.civID,
                unitId = attacker.id,
                targetX = target.position.x,
                targetY = target.position.y,
            ),
            1_700_100_010_000L,
        )

        val result = PackagedWorkerParityHarness.assertStable(request)
        val after = decode(result.snapshot)
        val resultAttacker = after.getCivilization(actor.civID).units.getUnitById(attacker.id)
        val resultDefender = after.getCivilization(enemy.civID).units.getUnitById(defender.id)
        assertTrue(
            resultAttacker == null ||
                resultDefender == null ||
                resultAttacker.health != beforeAttackerHealth ||
                resultDefender.health != beforeDefenderHealth,
        )

        game.turns += 1
        val changedState = PackagedWorkerParityHarness.execute(
            request.copy(
                operation = (request.operation as WorkerOperation.AttackWithUnit)
                    .copy(snapshot = encode(game)),
            ),
        )
        assertNull(changedState.error)
        assertNotEquals(result.canonicalStateHash, changedState.canonicalStateHash)
        assertNotEquals(result.snapshot, changedState.snapshot)
    }

    @Test(timeout = 300_000)
    fun canonicalEventChoiceIsByteStableAcrossFreshWorkers() {
        val fixture = createFixture(
            "Civ V - Gods & Kings",
            "00000000-0000-4000-8000-000000000102",
        )
        val game = decode(fixture.response.snapshot)
        val actor = game.getCivilization(requireNotNull(fixture.response.actorCivilizationId))
        val eventName = "Tutorial Task: [Meet another civilization]"
        assertTrue(game.ruleset.events[eventName]?.choices?.isNotEmpty() == true)
        game.turns = 2
        val otherMajor = game.civilizations.first {
            it.isMajorCiv() && it.civID != actor.civID
        }
        actor.diplomacyFunctions.makeCivilizationsMeet(otherMajor)
        com.unciv.UncivGame.Current.settings.tutorialTasksCompleted.remove(
            "Meet another civilization",
        )
        actor.popupAlerts.add(PopupAlert(AlertType.Event, eventName))
        val prompt = EventChoiceCommandExecutor.prompts(actor).single()
        val request = request(
            fixture,
            WorkerOperation.ResolveEventChoice(
                snapshot = encode(game),
                actorCivilizationId = actor.civID,
                promptId = prompt.promptId,
                choiceId = prompt.choices.single().choiceId,
            ),
            1_700_100_020_000L,
        )

        val result = PackagedWorkerParityHarness.assertStable(request)
        val resolved = decode(result.snapshot)
        assertTrue(
            resolved.getCivilization(actor.civID).popupAlerts.none {
                it.type == AlertType.Event
            },
        )

        val forged = PackagedWorkerParityHarness.execute(
            request.copy(actorId = "forged-account"),
        )
        assertEquals("engine_rejected", forged.error?.code)
        assertNull(forged.snapshot)
        assertNull(forged.canonicalStateHash)
    }

    private fun createFixture(baseName: String, gameId: String): Fixture {
        val base = InstalledRulesetCatalog.named(baseName)
        val manifest = WorkerRulesetManifest(
            engineBuild = InstalledRulesetCatalog.engineBuild,
            baseRuleset = base,
        )
        val response = AuthoritativeEngineWorker().execute(
            WorkerRequest(
                protocolVersion = EngineWorkerProtocol.VERSION,
                serverTimeMillis = 1_700_100_000_000L,
                actorId = actorId,
                rulesetManifest = manifest,
                operation = WorkerOperation.CreateGame(
                    gameId = gameId,
                    serverSeed = 975_318_642L,
                    setup = setup(baseName),
                ),
            ),
        )
        assertNull(response.error)
        return Fixture(manifest, response)
    }

    private fun setup(baseName: String): WorkerGameSetup {
        val ruleset = requireNotNull(RulesetCache[baseName])
        return WorkerGameSetup(
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
        )
    }

    private fun request(
        fixture: Fixture,
        operation: WorkerOperation,
        serverTime: Long,
    ) = WorkerRequest(
        protocolVersion = EngineWorkerProtocol.VERSION,
        serverTimeMillis = serverTime,
        actorId = actorId,
        rulesetManifest = fixture.manifest,
        operation = operation,
    )

    private fun decode(snapshot: String?): GameInfo =
        UncivFiles.gameInfoFromString(requireNotNull(snapshot)).also(GameInfo::setTransients)

    private fun encode(game: GameInfo): String =
        UncivFiles.gameInfoToString(game, forceZip = false, updateChecksum = false)

    private data class Fixture(
        val manifest: WorkerRulesetManifest,
        val response: WorkerResponse,
    )

    companion object {
        private const val actorId = "account-random-parity"

        @JvmStatic
        @BeforeClass
        fun initializeRulesets() = PackagedWorkerParityHarness.initializeRulesets()
    }
}
