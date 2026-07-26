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
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.file.Files
import java.nio.file.Paths
import java.util.Random
import java.util.concurrent.TimeUnit

class EngineWorkerProtocolTests {
    @Test
    fun workerJsonDepthAndCollectionLimitsFailBeforeDeserialization() {
        val deep = "${"[".repeat(EngineWorkerProtocolTestLimits.maxJsonDepth + 1)}0${
            "]".repeat(EngineWorkerProtocolTestLimits.maxJsonDepth + 1)
        }".encodeToByteArray()
        assertThrows(IllegalArgumentException::class.java) {
            EngineWorkerProtocol.validateJsonFrame(deep)
        }
        val oversizedCollection = buildString {
            append('[')
            repeat(EngineWorkerProtocolTestLimits.maxJsonCollectionItems + 1) { index ->
                if (index > 0) append(',')
                append('0')
            }
            append(']')
        }.encodeToByteArray()
        assertThrows(IllegalArgumentException::class.java) {
            EngineWorkerProtocol.validateJsonFrame(oversizedCollection)
        }
        EngineWorkerProtocol.validateJsonFrame(
            """{"protocolVersion":${EngineWorkerProtocol.VERSION},"operation":{"type":"handshake"}}"""
                .encodeToByteArray(),
        )
    }

    @Test
    fun workerAuthenticationRejectsChangedPayloadTagAndMalformedKeys() {
        val authentication = EngineWorkerAuthentication.fromHex(TEST_WORKER_SECRET)
        val payload = "authenticated request".encodeToByteArray()
        val nonce = ByteArray(EngineWorkerAuthentication.nonceBytes) { 7 }
        val tag = authentication.sign(EngineWorkerFrameDirection.Request, nonce, payload)
        authentication.verify(EngineWorkerFrameDirection.Request, nonce, payload, tag)
        assertThrows(IllegalArgumentException::class.java) {
            authentication.verify(
                EngineWorkerFrameDirection.Request,
                nonce,
                "changed request".encodeToByteArray(),
                tag,
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            authentication.verify(
                EngineWorkerFrameDirection.Request,
                nonce,
                payload,
                tag.clone().also { it[0] = (it[0].toInt() xor 1).toByte() },
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            authentication.verify(EngineWorkerFrameDirection.Response, nonce, payload, tag)
        }
        assertThrows(IllegalArgumentException::class.java) {
            EngineWorkerAuthentication.fromHex("00")
        }
    }

    @Test
    fun crossLanguageProtocolV2TagVectorIsStable() {
        val authentication = EngineWorkerAuthentication.fromHex(TEST_WORKER_SECRET)
        val nonce = ByteArray(EngineWorkerAuthentication.nonceBytes) { it.toByte() }
        val payload = "cross-language-v2".encodeToByteArray()
        assertEquals(
            "9aadde07280bfbb985dd6d2838648ae5357e7d7fe6cc5884f70a9b94f200a37c",
            authentication.sign(EngineWorkerFrameDirection.Request, nonce, payload)
                .joinToString("") { "%02x".format(it) },
        )
        assertEquals(
            "69e97ebc3a5df91ec83e2730cf9290e58a5d378e5868e4a9e79aa14392feda1a",
            authentication.sign(EngineWorkerFrameDirection.Response, nonce, payload)
                .joinToString("") { "%02x".format(it) },
        )
    }

    @Test
    fun seededMalformedFramesRequestsSnapshotsAndManifestsFailClosed() {
        val random = Random(0x554E434956L)
        repeat(256) {
            val payload = ByteArray(random.nextInt(4096)) { random.nextInt(256).toByte() }
            val frameSize = when (random.nextInt(4)) {
                0 -> 0
                1 -> EngineWorkerProtocol.maxFrameBytes + 1
                2 -> payload.size + 1
                else -> payload.size
            }
            runCatching { EngineWorkerProtocol.decodeRequest(frameSize, payload) }
                .onSuccess { request ->
                    assertEquals(EngineWorkerProtocol.VERSION, request.protocolVersion)
                }
        }

        val installed = InstalledRulesetCatalog.named("Civ V - Vanilla")
        val validManifest = WorkerRulesetManifest(
            engineBuild = InstalledRulesetCatalog.engineBuild,
            baseRuleset = installed,
        )
        repeat(128) {
            val malformedSnapshot = buildString {
                repeat(random.nextInt(1024) + 1) {
                    append((32 + random.nextInt(95)).toChar())
                }
            }
            val response = AuthoritativeEngineWorker().execute(
                WorkerRequest(
                    protocolVersion = EngineWorkerProtocol.VERSION,
                    serverTimeMillis = 1_700_000_000_000L,
                    actorId = "account-1",
                    rulesetManifest = validManifest,
                    operation = WorkerOperation.EndTurn(malformedSnapshot, "Rome"),
                ),
            )
            assertEquals("engine_rejected", response.error?.code)
            assertNull(response.snapshot)

            val invalidManifest = validManifest.copy(
                mods = listOf(WorkerRuleset(
                    name = "unknown-${random.nextLong()}",
                    sha256 = random.nextLong().toString(16).padStart(64, '0').takeLast(64),
                )),
            )
            val manifestResponse = AuthoritativeEngineWorker().execute(
                WorkerRequest(
                    protocolVersion = EngineWorkerProtocol.VERSION,
                    serverTimeMillis = 1_700_000_000_000L,
                    actorId = "account-1",
                    rulesetManifest = invalidManifest,
                    operation = WorkerOperation.EndTurn("not-a-save", "Rome"),
                ),
            )
            assertEquals("engine_rejected", manifestResponse.error?.code)
            assertNull(manifestResponse.snapshot)
        }
    }

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

    @Test(timeout = 300_000)
    fun cityStateCreationIsByteStableAcrossFreshWorkerProcesses() {
        val baseRuleset = InstalledRulesetCatalog.named("Civ V - Vanilla")
        val request = WorkerRequest(
            protocolVersion = EngineWorkerProtocol.VERSION,
            serverTimeMillis = 1_700_000_000_000L,
            actorId = "account-1",
            rulesetManifest = WorkerRulesetManifest(
                engineBuild = InstalledRulesetCatalog.engineBuild,
                baseRuleset = baseRuleset,
            ),
            operation = WorkerOperation.CreateGame(
                "00000000-0000-4000-8000-000000000002",
                246813579L,
                defaultSetup(baseRuleset.name).copy(
                    majorCivilizations = 2,
                    cityStates = 2,
                    mapShape = GeneratedMapShape.Rectangular,
                    mapSize = GeneratedMapSize.Tiny,
                    barbarians = BarbarianMode.Disabled,
                    noRuins = true,
                ),
            ),
        )

        val first = executeInFreshWorker(request)
        val second = executeInFreshWorker(request)

        assertNull(first.error)
        assertNull(second.error)
        assertEquals(first.actorCivilizationId, second.actorCivilizationId)
        assertEquals(first.canonicalStateHash, second.canonicalStateHash)
        assertEquals(first.snapshot, second.snapshot)
        val game = json().fromJson(GameInfo::class.java, requireNotNull(first.snapshot))
        assertEquals(2, game.gameParameters.numberOfCityStates)
        val ruleset = requireNotNull(RulesetCache[baseRuleset.name])
        assertEquals(
            2,
            game.civilizations.count { ruleset.nations[it.civName]?.isCityState == true },
        )

        val replayRequest = WorkerRequest(
            protocolVersion = EngineWorkerProtocol.VERSION,
            serverTimeMillis = 1_700_000_060_000L,
            actorId = "account-2",
            rulesetManifest = request.rulesetManifest,
            operation = WorkerOperation.AssignPlayer(requireNotNull(first.snapshot)),
        )
        val firstReplay = executeInFreshWorker(replayRequest)
        val secondReplay = executeInFreshWorker(replayRequest)
        assertNull(firstReplay.error)
        assertNull(secondReplay.error)
        assertNotEquals(first.actorCivilizationId, firstReplay.actorCivilizationId)
        assertEquals(firstReplay.actorCivilizationId, secondReplay.actorCivilizationId)
        assertEquals(firstReplay.canonicalStateHash, secondReplay.canonicalStateHash)
        assertEquals(firstReplay.snapshot, secondReplay.snapshot)
    }

    @Test(timeout = 300_000)
    fun researchAndAllAiTurnsAreByteStableAcrossFreshWorkerProcesses() {
        val baseRuleset = InstalledRulesetCatalog.named("Civ V - Vanilla")
        val manifest = WorkerRulesetManifest(
            engineBuild = InstalledRulesetCatalog.engineBuild,
            baseRuleset = baseRuleset,
        )
        val creation = executeInFreshWorker(
            WorkerRequest(
                protocolVersion = EngineWorkerProtocol.VERSION,
                serverTimeMillis = 1_700_000_000_000L,
                actorId = "account-ai-parity",
                rulesetManifest = manifest,
                operation = WorkerOperation.CreateGame(
                    "00000000-0000-4000-8000-000000000003",
                    135792468L,
                    defaultSetup(baseRuleset.name).copy(
                        majorCivilizations = 3,
                        cityStates = 1,
                        mapShape = GeneratedMapShape.Rectangular,
                        mapSize = GeneratedMapSize.Tiny,
                        barbarians = BarbarianMode.Disabled,
                        noRuins = true,
                    ),
                ),
            ),
        )
        assertNull(creation.error)
        val actorCivilizationId = requireNotNull(creation.actorCivilizationId)
        val createdGame = json().fromJson(GameInfo::class.java, requireNotNull(creation.snapshot))
        createdGame.setTransients()
        val actor = createdGame.getCivilization(actorCivilizationId)
        val technology = createdGame.ruleset.technologies.values
            .filter { actor.tech.canBeResearched(it.name) }
            .minBy { it.name }
            .name
        val researchRequest = WorkerRequest(
            protocolVersion = EngineWorkerProtocol.VERSION,
            serverTimeMillis = 1_700_000_010_000L,
            actorId = "account-ai-parity",
            rulesetManifest = manifest,
            operation = WorkerOperation.SetResearchPath(
                requireNotNull(creation.snapshot),
                actorCivilizationId,
                technology,
                append = false,
            ),
        )

        val firstResearch = executeInFreshWorker(researchRequest)
        val secondResearch = executeInFreshWorker(researchRequest)
        assertNull(firstResearch.error)
        assertNull(secondResearch.error)
        assertEquals(firstResearch.canonicalStateHash, secondResearch.canonicalStateHash)
        assertEquals(firstResearch.snapshot, secondResearch.snapshot)

        val endTurnRequest = WorkerRequest(
            protocolVersion = EngineWorkerProtocol.VERSION,
            serverTimeMillis = 1_700_000_060_000L,
            actorId = "account-ai-parity",
            rulesetManifest = manifest,
            operation = WorkerOperation.EndTurn(
                requireNotNull(firstResearch.snapshot),
                actorCivilizationId,
            ),
        )
        val firstTurn = executeInFreshWorker(endTurnRequest)
        val secondTurn = executeInFreshWorker(endTurnRequest)
        assertNull(firstTurn.error)
        assertNull(secondTurn.error)
        assertEquals(firstTurn.canonicalStateHash, secondTurn.canonicalStateHash)
        assertEquals(firstTurn.snapshot, secondTurn.snapshot)
        assertNotEquals(firstResearch.canonicalStateHash, firstTurn.canonicalStateHash)

        val advanced = json().fromJson(GameInfo::class.java, requireNotNull(firstTurn.snapshot))
        assertEquals(1, advanced.turns)
        assertEquals(actorCivilizationId, advanced.currentPlayer)
        assertEquals(1_700_000_060_000L, advanced.currentTurnStartTime)
        val ruleset = requireNotNull(RulesetCache[baseRuleset.name])
        assertEquals(
            2,
            advanced.civilizations.count {
                ruleset.nations[it.civName]?.isMajorCiv == true && it.isAI()
            },
        )

        val forgedActor = executeInFreshWorker(
            endTurnRequest.copy(actorId = "forged-account"),
        )
        assertEquals("engine_rejected", forgedActor.error?.code)
        assertNull(forgedActor.snapshot)
        assertNull(forgedActor.canonicalStateHash)

        val changedClock = executeInFreshWorker(
            endTurnRequest.copy(serverTimeMillis = 1_700_000_060_001L),
        )
        assertNull(changedClock.error)
        assertNotEquals(firstTurn.canonicalStateHash, changedClock.canonicalStateHash)
        assertNotEquals(firstTurn.snapshot, changedClock.snapshot)
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

            val first = RulesetCatalogSnapshot.hashDirectory(FileHandle(firstRoot.toFile()))
            val same = RulesetCatalogSnapshot.hashDirectory(FileHandle(secondRoot.toFile()))
            assertEquals(first, same)

            Files.writeString(secondRoot.resolve("nested/a.json"), "changed")
            val changed = RulesetCatalogSnapshot.hashDirectory(FileHandle(secondRoot.toFile()))
            assertNotEquals(first, changed)
        } finally {
            firstRoot.toFile().deleteRecursively()
            secondRoot.toFile().deleteRecursively()
        }
    }

    companion object {
        private fun executeInFreshWorker(request: WorkerRequest): WorkerResponse {
            val loopback = InetAddress.getLoopbackAddress()
            val port = ServerSocket(0, 50, loopback).use { it.localPort }
            val javaExecutable = Paths.get(
                System.getProperty("java.home"),
                "bin",
                if (System.getProperty("os.name").startsWith("Windows")) "java.exe" else "java",
            )
            val workerJar = requireNotNull(System.getProperty("unciv.authoritativeWorkerJar")) {
                "The server test task must provide the packaged authoritative worker"
            }
            val process = ProcessBuilder(
                javaExecutable.toString(),
                "-Djava.awt.headless=true",
                "-jar",
                workerJar,
            )
                .directory(File("."))
                .redirectErrorStream(true)
                .redirectOutput(ProcessBuilder.Redirect.INHERIT)
                .apply {
                    environment()["UNCIV_ENGINE_WORKER_PORT"] = port.toString()
                    environment()["UNCIV_ENGINE_WORKER_SECRET"] = TEST_WORKER_SECRET
                    environment()["UNCIV_V3_UNPACKAGED_DEV"] = "1"
                }
                .start()
            try {
                awaitWorker(process, loopback, port)
                return sendRequest(loopback, port, request)
            } finally {
                process.destroy()
                if (!process.waitFor(5, TimeUnit.SECONDS)) {
                    process.destroyForcibly()
                    process.waitFor(5, TimeUnit.SECONDS)
                }
            }
        }

        private fun awaitWorker(
            process: Process,
            address: InetAddress,
            port: Int,
        ) {
            var lastError: IOException? = null
            repeat(600) {
                if (!process.isAlive) {
                    throw AssertionError(
                        "Fresh authoritative worker exited before becoming ready with code ${process.exitValue()}",
                    )
                }
                try {
                    val response = sendRequest(
                        address,
                        port,
                        WorkerRequest(
                            protocolVersion = EngineWorkerProtocol.VERSION,
                            operation = WorkerOperation.Handshake,
                        ),
                    )
                    assertNull(response.error)
                    return
                } catch (error: IOException) {
                    lastError = error
                    Thread.sleep(50)
                }
            }
            throw AssertionError(
                "Fresh authoritative worker did not become ready",
                lastError,
            )
        }

        private fun sendRequest(
            address: InetAddress,
            port: Int,
            request: WorkerRequest,
        ): WorkerResponse {
            val payload = EngineWorkerProtocol.json
                .encodeToString(WorkerRequest.serializer(), request)
                .encodeToByteArray()
            val nonce = ByteArray(EngineWorkerAuthentication.nonceBytes).also(TEST_RANDOM::nextBytes)
            return Socket().use { socket ->
                socket.connect(java.net.InetSocketAddress(address, port), 500)
                socket.soTimeout = 120_000
                val output = DataOutputStream(socket.getOutputStream())
                output.writeInt(payload.size)
                output.write(nonce)
                output.write(
                    TEST_AUTHENTICATION.sign(
                        EngineWorkerFrameDirection.Request,
                        nonce,
                        payload,
                    ),
                )
                output.write(payload)
                output.flush()
                val input = DataInputStream(socket.getInputStream())
                val responseSize = input.readInt()
                require(responseSize in 1..EngineWorkerProtocol.maxFrameBytes)
                val responseNonce = input.readNBytes(EngineWorkerAuthentication.nonceBytes)
                require(responseNonce.contentEquals(nonce))
                val responseTag = input.readNBytes(EngineWorkerAuthentication.tagBytes)
                require(responseTag.size == EngineWorkerAuthentication.tagBytes)
                val responsePayload = input.readNBytes(responseSize)
                TEST_AUTHENTICATION.verify(
                    EngineWorkerFrameDirection.Response,
                    responseNonce,
                    responsePayload,
                    responseTag,
                )
                EngineWorkerProtocol.json.decodeFromString(
                    WorkerResponse.serializer(),
                    responsePayload.decodeToString(),
                )
            }
        }

        private const val TEST_WORKER_SECRET =
            "5555555555555555555555555555555555555555555555555555555555555555"
        private val TEST_AUTHENTICATION =
            EngineWorkerAuthentication.fromHex(TEST_WORKER_SECRET)
        private val TEST_RANDOM = java.security.SecureRandom()

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
            InstalledRulesetCatalog.initialize()
        }
    }
}

private object EngineWorkerProtocolTestLimits {
    const val maxJsonDepth = 64
    const val maxJsonCollectionItems = 65_536
}
