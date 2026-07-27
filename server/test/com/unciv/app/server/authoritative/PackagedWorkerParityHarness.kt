package com.unciv.app.server.authoritative

import com.badlogic.gdx.ApplicationListener
import com.badlogic.gdx.Gdx
import com.badlogic.gdx.backends.headless.HeadlessApplication
import com.badlogic.gdx.backends.headless.HeadlessApplicationConfiguration
import com.badlogic.gdx.files.FileHandle
import com.unciv.UncivGame
import com.unciv.logic.files.UncivFiles
import com.unciv.models.metadata.GameSettings
import com.unciv.models.ruleset.RulesetCache
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.file.Paths
import java.security.SecureRandom
import java.util.concurrent.TimeUnit

/** Fresh-process parity boundary shared by packaged-worker scenario tests. */
internal object PackagedWorkerParityHarness {
    @Volatile
    private var rulesetsInitialized = false

    @Synchronized
    fun initializeRulesets() {
        if (rulesetsInitialized) return
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
        val modsRoot = parityModsRoot()
        WorkerRulesetAssets.validateModsRoot(modsRoot)
        RulesetCache.loadRulesets(
            consoleMode = true,
            noMods = false,
            modsFolder = FileHandle(modsRoot.toFile()),
        )
        InstalledRulesetCatalog.initialize()
        rulesetsInitialized = true
    }

    fun assertStable(request: WorkerRequest): WorkerResponse {
        return assertStableScenario { send -> listOf(send(request)) }.single()
    }

    fun assertStableScenario(
        scenario: ((WorkerRequest) -> WorkerResponse) -> List<WorkerResponse>,
    ): List<WorkerResponse> {
        val first = withWorker(scenario)
        val second = withWorker(scenario)
        assertEquals(first.size, second.size)
        first.zip(second).forEachIndexed { index, (firstResponse, secondResponse) ->
            assertNull("First worker response $index failed", firstResponse.error)
            assertNull("Second worker response $index failed", secondResponse.error)
            assertEquals(
                "Fresh worker response $index differs",
                EngineWorkerProtocol.json.encodeToString(
                    WorkerResponse.serializer(),
                    firstResponse,
                ),
                EngineWorkerProtocol.json.encodeToString(
                    WorkerResponse.serializer(),
                    secondResponse,
                ),
            )
        }
        return first
    }

    fun execute(request: WorkerRequest): WorkerResponse = withWorker { send -> send(request) }

    private fun <T> withWorker(block: ((WorkerRequest) -> WorkerResponse) -> T): T {
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
                environment()["UNCIV_ENGINE_WORKER_SECRET"] = workerSecret
                environment()["UNCIV_V3_UNPACKAGED_DEV"] = "1"
                environment()["UNCIV_ENGINE_WORKER_TEST_MODS_ROOT"] = parityModsRoot().toString()
            }
            .start()
        try {
            awaitWorker(process, loopback, port)
            return block { request -> sendRequest(loopback, port, request) }
        } finally {
            process.destroy()
            if (!process.waitFor(5, TimeUnit.SECONDS)) {
                process.destroyForcibly()
                process.waitFor(5, TimeUnit.SECONDS)
            }
        }
    }

    private fun awaitWorker(process: Process, address: InetAddress, port: Int) {
        var lastError: IOException? = null
        repeat(600) {
            if (!process.isAlive) {
                throw AssertionError(
                    "Fresh authoritative worker exited before readiness with code ${process.exitValue()}",
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
        throw AssertionError("Fresh authoritative worker did not become ready", lastError)
    }

    private fun sendRequest(
        address: InetAddress,
        port: Int,
        request: WorkerRequest,
    ): WorkerResponse {
        val payload = EngineWorkerProtocol.json
            .encodeToString(WorkerRequest.serializer(), request)
            .encodeToByteArray()
        val nonce = ByteArray(EngineWorkerAuthentication.nonceBytes).also(random::nextBytes)
        return Socket().use { socket ->
            socket.connect(java.net.InetSocketAddress(address, port), 5_000)
            socket.soTimeout = 120_000
            val output = DataOutputStream(socket.getOutputStream())
            output.writeInt(payload.size)
            output.write(nonce)
            output.write(
                authentication.sign(EngineWorkerFrameDirection.Request, nonce, payload),
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
            authentication.verify(
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

    private val workerSecret = "55".repeat(32)
    private val authentication = EngineWorkerAuthentication.fromHex(workerSecret)
    private val random = SecureRandom()

    private fun parityModsRoot() =
        Paths.get(requireNotNull(System.getProperty("unciv.authoritativeParityModsRoot")) {
            "The server test task must provide the authoritative parity mod root"
        }).toAbsolutePath().normalize()
}
