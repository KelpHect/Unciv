package com.unciv.app.server.authoritative

import com.unciv.logic.GameInfo
import com.unciv.logic.civilization.AlertType
import com.unciv.logic.civilization.PopupAlert
import com.unciv.logic.files.UncivFiles
import com.unciv.logic.map.MapShape
import com.unciv.logic.map.MapType
import com.unciv.logic.map.mapgenerator.MapResourceSetting
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
    fun everyGeneratedSetupDimensionHasFreshWorkerParity() {
        val baseName = "Civ V - Gods & Kings"
        val ruleset = requireNotNull(RulesetCache[baseName])
        val manifest = WorkerRulesetManifest(
            engineBuild = InstalledRulesetCatalog.engineBuild,
            baseRuleset = InstalledRulesetCatalog.named(baseName),
        )
        val difficulties = ruleset.difficulties.keys.sorted()
        val speeds = ruleset.speeds.keys.sorted()
        val eras = ruleset.eras.keys.sorted()
        val victories = ruleset.victories.values
            .filterNot { it.hiddenInVictoryScreen }
            .map { it.name }
            .sorted()
        val caseCount = listOf(
            difficulties.size,
            speeds.size,
            eras.size,
            victories.size,
            GeneratedMapShape.entries.size,
            GeneratedMapSize.entries.size,
            MapResourceDensity.entries.size,
            BarbarianMode.entries.size,
        ).max()
        val cases = (0 until caseCount).map { index ->
            SetupCase(
                difficulty = difficulties[index % difficulties.size],
                speed = speeds[index % speeds.size],
                era = eras[index % eras.size],
                victories = if (index == caseCount - 1) {
                    victories
                } else {
                    listOf(victories[index % victories.size])
                },
                shape = GeneratedMapShape.entries[index % GeneratedMapShape.entries.size],
                size = GeneratedMapSize.entries[index % GeneratedMapSize.entries.size],
                resources = MapResourceDensity.entries[index % MapResourceDensity.entries.size],
                barbarians = BarbarianMode.entries[index % BarbarianMode.entries.size],
                enabled = index % 2 == 0,
            )
        }
        assertEquals(difficulties.toSet(), cases.map { it.difficulty }.toSet())
        assertEquals(speeds.toSet(), cases.map { it.speed }.toSet())
        assertEquals(eras.toSet(), cases.map { it.era }.toSet())
        assertEquals(victories.toSet(), cases.flatMap { it.victories }.toSet())
        assertEquals(GeneratedMapShape.entries.toSet(), cases.map { it.shape }.toSet())
        assertEquals(GeneratedMapSize.entries.toSet(), cases.map { it.size }.toSet())
        assertEquals(MapResourceDensity.entries.toSet(), cases.map { it.resources }.toSet())
        assertEquals(BarbarianMode.entries.toSet(), cases.map { it.barbarians }.toSet())
        assertEquals(setOf(false, true), cases.map { it.enabled }.toSet())

        val responses = PackagedWorkerParityHarness.assertStableScenario { send ->
            cases.mapIndexed { index, case ->
                send(
                    WorkerRequest(
                        protocolVersion = EngineWorkerProtocol.VERSION,
                        serverTimeMillis = 1_700_110_000_000L + index,
                        actorId = actorId,
                        rulesetManifest = manifest,
                        operation = WorkerOperation.CreateGame(
                            gameId = "00000000-0000-4000-8000-${
                                (300 + index).toString().padStart(12, '0')
                            }",
                            serverSeed = 319_754_826L + index,
                            setup = setup(baseName).copy(
                                difficulty = case.difficulty,
                                speed = case.speed,
                                startingEra = case.era,
                                victoryTypes = case.victories,
                                mapShape = case.shape,
                                mapSize = case.size,
                                mapResources = case.resources,
                                barbarians = case.barbarians,
                                oneCityChallenge = case.enabled,
                                nuclearWeaponsEnabled = case.enabled,
                                espionageEnabled = case.enabled,
                                noStartBias = case.enabled,
                                shufflePlayerOrder = case.enabled,
                                noCityRazing = case.enabled,
                                worldWrap = case.enabled && case.shape != GeneratedMapShape.FlatEarth,
                                strategicBalance = case.enabled,
                                legendaryStart = case.enabled,
                                noRuins = case.enabled,
                                noNaturalWonders = case.enabled,
                                minutesUntilSkipTurn = if (case.enabled) 10_080 else 5,
                                minutesUntilForceResign = if (case.enabled) 43_200 else 60,
                                minutesRecoveredPerTurn = if (case.enabled) 10_080 else 0,
                            ),
                        ),
                    ),
                )
            }
        }

        responses.zip(cases).forEach { (response, case) ->
            val game = decode(response.snapshot)
            assertEquals(case.difficulty, game.gameParameters.difficulty)
            assertEquals(case.speed, game.gameParameters.speed)
            assertEquals(case.era, game.gameParameters.startingEra)
            assertEquals(case.victories, game.gameParameters.victoryTypes)
            assertEquals(expectedShape(case.shape), game.tileMap.mapParameters.shape)
            assertEquals(case.size.name, game.tileMap.mapParameters.mapSize.name)
            assertEquals(expectedResources(case.resources), game.tileMap.mapParameters.mapResources)
            assertEquals(case.barbarians == BarbarianMode.Disabled, game.gameParameters.noBarbarians)
            assertEquals(case.barbarians == BarbarianMode.Raging, game.gameParameters.ragingBarbarians)
            assertEquals(case.enabled, game.gameParameters.oneCityChallenge)
            assertEquals(case.enabled, game.gameParameters.nuclearWeaponsEnabled)
            assertEquals(case.enabled, game.gameParameters.espionageEnabled)
            assertEquals(case.enabled, game.gameParameters.noStartBias)
            assertEquals(case.enabled, game.gameParameters.shufflePlayerOrder)
            assertEquals(case.enabled, game.gameParameters.noCityRazing)
            assertEquals(
                case.enabled && case.shape != GeneratedMapShape.FlatEarth,
                game.tileMap.mapParameters.worldWrap,
            )
            assertEquals(case.enabled, game.tileMap.mapParameters.strategicBalance)
            assertEquals(case.enabled, game.tileMap.mapParameters.legendaryStart)
            assertEquals(case.enabled, game.tileMap.mapParameters.noRuins)
            assertEquals(case.enabled, game.tileMap.mapParameters.noNaturalWonders)
            assertEquals(if (case.enabled) 10_080 else 5, game.gameParameters.minutesUntilSkipTurn)
            assertEquals(
                if (case.enabled) 43_200 else 60,
                game.gameParameters.minutesUntilForceResign,
            )
            assertEquals(
                if (case.enabled) 10_080 else 0,
                game.gameParameters.minutesRecoveredPerTurn,
            )
        }
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

    private fun expectedShape(shape: GeneratedMapShape) = when (shape) {
        GeneratedMapShape.Rectangular -> MapShape.rectangular
        GeneratedMapShape.Hexagonal -> MapShape.hexagonal
        GeneratedMapShape.FlatEarth -> MapShape.flatEarth
    }

    private fun expectedResources(resources: MapResourceDensity) = when (resources) {
        MapResourceDensity.Sparse -> MapResourceSetting.sparse.label
        MapResourceDensity.Default -> MapResourceSetting.default.label
        MapResourceDensity.Abundant -> MapResourceSetting.abundant.label
    }

    private data class Fixture(
        val manifest: WorkerRulesetManifest,
        val response: WorkerResponse,
    )

    private data class SetupCase(
        val difficulty: String,
        val speed: String,
        val era: String,
        val victories: List<String>,
        val shape: GeneratedMapShape,
        val size: GeneratedMapSize,
        val resources: MapResourceDensity,
        val barbarians: BarbarianMode,
        val enabled: Boolean,
    )

    companion object {
        private const val actorId = "account-random-parity"

        @JvmStatic
        @BeforeClass
        fun initializeRulesets() = PackagedWorkerParityHarness.initializeRulesets()
    }
}
