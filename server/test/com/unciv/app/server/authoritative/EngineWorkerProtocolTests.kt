package com.unciv.app.server.authoritative

import com.badlogic.gdx.files.FileHandle
import com.badlogic.gdx.ApplicationListener
import com.badlogic.gdx.Gdx
import com.badlogic.gdx.backends.headless.HeadlessApplication
import com.badlogic.gdx.backends.headless.HeadlessApplicationConfiguration
import com.unciv.UncivGame
import com.unciv.logic.GameInfo
import com.unciv.logic.files.UncivFiles
import com.unciv.json.json
import com.unciv.models.metadata.GameSettings
import com.unciv.models.ruleset.RulesetCache
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import java.nio.file.Files

class EngineWorkerProtocolTests {
    @Test
    fun handshakeNeedsNoActorOrManifest() {
        val response = AuthoritativeEngineWorker().execute(
            WorkerRequest(
                protocolVersion = EngineWorkerProtocol.VERSION,
                operation = WorkerOperation.Handshake,
            ),
        )

        assertNull(response.error)
        assertEquals(InstalledRulesetCatalog.engineBuild, response.engineBuild)
        assertEquals(EngineWorkerProtocol.VERSION, response.protocolVersion)
        assertTrue(response.installedRulesets?.any { it.name == "Civ V - Vanilla" } == true)
    }

    @Test
    fun mismatchedRulesetBytesAreRejectedBeforeSnapshotParsing() {
        val response = AuthoritativeEngineWorker().execute(
            WorkerRequest(
                protocolVersion = EngineWorkerProtocol.VERSION,
                serverTimeMillis = 1_700_000_000_000L,
                actorId = "account-1",
                rulesetManifest = WorkerRulesetManifest(
                    engineBuild = InstalledRulesetCatalog.engineBuild,
                    baseRuleset = WorkerRuleset("Civ V - Vanilla", "0".repeat(64)),
                ),
                operation = WorkerOperation.EndTurn("not-a-save", "Rome"),
            ),
        )

        assertEquals("engine_rejected", response.error?.code)
        assertTrue(response.error?.message?.contains("ruleset content", ignoreCase = true) == true)
        assertNull(response.snapshot)
    }

    @Test
    fun mismatchedEngineBuildIsRejectedBeforeGameExecution() {
        val response = AuthoritativeEngineWorker().execute(
            WorkerRequest(
                protocolVersion = EngineWorkerProtocol.VERSION,
                serverTimeMillis = 1_700_000_000_000L,
                actorId = "account-1",
                rulesetManifest = WorkerRulesetManifest(
                    engineBuild = "not-this-worker",
                    baseRuleset = WorkerRuleset("Civ V - Vanilla", "0".repeat(64)),
                ),
                operation = WorkerOperation.CreateGame(
                    "00000000-0000-4000-8000-000000000001",
                    1234L,
                    defaultSetup("Civ V - Vanilla"),
                ),
            ),
        )

        assertEquals("engine_rejected", response.error?.code)
        assertTrue(response.error?.message?.contains("engine build") == true)
        assertNull(response.snapshot)
    }

    @Test
    fun createGameDerivesSetupFromPinnedManifestAndServerSeed() {
        val capabilities = AuthoritativeEngineWorker().execute(
            WorkerRequest(
                protocolVersion = EngineWorkerProtocol.VERSION,
                operation = WorkerOperation.Handshake,
            ),
        )
        val baseRuleset = capabilities.installedRulesets!!.first { it.name == "Civ V - Vanilla" }
        val manifest = WorkerRulesetManifest(
            engineBuild = requireNotNull(capabilities.engineBuild),
            baseRuleset = baseRuleset,
        )

        val response = AuthoritativeEngineWorker().execute(
            WorkerRequest(
                protocolVersion = EngineWorkerProtocol.VERSION,
                serverTimeMillis = 1_700_000_000_000L,
                actorId = "account-1",
                rulesetManifest = manifest,
                operation = WorkerOperation.CreateGame(
                    "00000000-0000-4000-8000-000000000001",
                    987654321L,
                    defaultSetup(baseRuleset.name).copy(
                        majorCivilizations = 2,
                        cityStates = 0,
                        mapShape = GeneratedMapShape.Rectangular,
                        mapSize = GeneratedMapSize.Tiny,
                        barbarians = BarbarianMode.Disabled,
                        noRuins = true,
                    ),
                ),
            ),
        )

        assertNull(response.error)
        assertEquals(1_700_000_000_000L, response.serverTimeMillis)
        val game = json().fromJson(GameInfo::class.java, requireNotNull(response.snapshot))
        assertEquals(987654321L, game.tileMap.mapParameters.seed)
        assertEquals(1_700_000_000_000L, game.currentTurnStartTime)
        assertEquals(baseRuleset.name, game.gameParameters.baseRuleset)
        assertEquals(2, game.gameParameters.players.size)
        assertEquals(0, game.gameParameters.numberOfCityStates)
        assertTrue(game.gameParameters.noBarbarians)
        assertTrue(game.tileMap.mapParameters.noRuins)
        assertEquals("Rectangular", game.tileMap.mapParameters.shape)
        assertEquals("Tiny", game.tileMap.mapParameters.mapSize.name)
        assertTrue(game.gameParameters.isOnlineMultiplayer)
        assertEquals(
            "account-1",
            game.civilizations.single {
                it.civID == requireNotNull(response.actorCivilizationId)
            }.playerId,
        )
        assertEquals("00000000-0000-4000-8000-000000000001", game.gameId)
        val replay = AuthoritativeEngineWorker().execute(
            WorkerRequest(
                protocolVersion = EngineWorkerProtocol.VERSION,
                serverTimeMillis = 1_700_000_000_000L,
                actorId = "account-1",
                rulesetManifest = manifest,
                operation = WorkerOperation.CreateGame(
                    "00000000-0000-4000-8000-000000000001",
                    987654321L,
                    defaultSetup(baseRuleset.name).copy(
                        majorCivilizations = 2,
                        cityStates = 0,
                        mapShape = GeneratedMapShape.Rectangular,
                        mapSize = GeneratedMapSize.Tiny,
                        barbarians = BarbarianMode.Disabled,
                        noRuins = true,
                    ),
                ),
            ),
        )
        assertNull(replay.error)
        assertEquals(response.canonicalStateHash, replay.canonicalStateHash)
        assertEquals(response.snapshot, replay.snapshot)
    }

    @Test
    fun createGameRejectsSetupChoicesOutsidePinnedRuleset() {
        val baseRuleset = InstalledRulesetCatalog.named("Civ V - Vanilla")
        val manifest = WorkerRulesetManifest(
            engineBuild = InstalledRulesetCatalog.engineBuild,
            baseRuleset = baseRuleset,
        )
        val response = AuthoritativeEngineWorker().execute(
            WorkerRequest(
                protocolVersion = EngineWorkerProtocol.VERSION,
                serverTimeMillis = 1_700_000_000_000L,
                actorId = "account-1",
                rulesetManifest = manifest,
                operation = WorkerOperation.CreateGame(
                    "00000000-0000-4000-8000-000000000001",
                    1234L,
                    defaultSetup(baseRuleset.name).copy(difficulty = "Client invented difficulty"),
                ),
            ),
        )

        assertEquals("engine_rejected", response.error?.code)
        assertTrue(response.error?.message?.contains("Difficulty") == true)
        assertNull(response.snapshot)
    }

    @Test
    fun rulesetHashIsStableAcrossCreationOrderAndChangesWithContent() {
        val firstRoot = Files.createTempDirectory("unciv-ruleset-a")
        val secondRoot = Files.createTempDirectory("unciv-ruleset-b")
        try {
            Files.createDirectories(firstRoot.resolve("nested"))
            Files.writeString(firstRoot.resolve("z.json"), "z")
            Files.writeString(firstRoot.resolve("nested/a.json"), "a")
            Files.createDirectories(secondRoot.resolve("nested"))
            Files.writeString(secondRoot.resolve("nested/a.json"), "a")
            Files.writeString(secondRoot.resolve("z.json"), "z")

            val first = InstalledRulesetCatalog.hashDirectory(FileHandle(firstRoot.toFile()))
            val same = InstalledRulesetCatalog.hashDirectory(FileHandle(secondRoot.toFile()))
            assertEquals(first, same)

            Files.writeString(secondRoot.resolve("nested/a.json"), "changed")
            val changed = InstalledRulesetCatalog.hashDirectory(FileHandle(secondRoot.toFile()))
            assertNotEquals(first, changed)
        } finally {
            firstRoot.toFile().deleteRecursively()
            secondRoot.toFile().deleteRecursively()
        }
    }

    companion object {
        private fun defaultSetup(baseRulesetName: String): WorkerGameSetup {
            val ruleset = requireNotNull(RulesetCache[baseRulesetName])
            return WorkerGameSetup(
                difficulty = ruleset.difficulties.keys.first(),
                speed = ruleset.speeds.keys.first(),
                startingEra = ruleset.eras.keys.first(),
                victoryTypes = ruleset.victories.values
                    .filterNot { it.hiddenInVictoryScreen }
                    .map { it.name }
                    .sorted(),
                majorCivilizations = 4,
                cityStates = minOf(6, ruleset.nations.values.count { it.isCityState }),
                maxTurns = 500,
                mapType = GeneratedMapType.Pangaea,
                mapShape = GeneratedMapShape.Hexagonal,
                mapSize = GeneratedMapSize.Medium,
                mapResources = MapResourceDensity.Default,
                barbarians = BarbarianMode.Normal,
                oneCityChallenge = false,
                nuclearWeaponsEnabled = true,
                espionageEnabled = true,
                noStartBias = false,
                shufflePlayerOrder = false,
                noCityRazing = false,
                worldWrap = false,
                strategicBalance = false,
                legendaryStart = false,
                noRuins = false,
                noNaturalWonders = false,
                minutesUntilSkipTurn = 1_440,
                minutesUntilForceResign = 4_320,
                minutesRecoveredPerTurn = 1_440,
            )
        }

        @JvmStatic
        @BeforeClass
        fun loadInstalledRulesets() {
            HeadlessApplication(object : ApplicationListener {
                override fun create() {}
                override fun render() {}
                override fun resize(width: Int, height: Int) {}
                override fun pause() {}
                override fun resume() {}
                override fun dispose() {}
            }, HeadlessApplicationConfiguration())
            UncivGame.Current = UncivGame().apply {
                files = UncivFiles(Gdx.files)
                settings = GameSettings()
            }
            RulesetCache.loadRulesets(consoleMode = true, noMods = true)
        }
    }
}
