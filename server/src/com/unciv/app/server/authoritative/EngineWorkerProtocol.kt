package com.unciv.app.server.authoritative

import com.unciv.logic.ContentAddressedRuleset
import com.unciv.logic.GameExecutionContext
import com.unciv.logic.RulesetManifest
import com.unciv.logic.multiplayer.authoritative.HeadlessGameEngine
import com.unciv.json.json
import com.unciv.models.metadata.GameSetupInfo
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.ServerSocket
import java.net.Socket

/** Private length-prefixed JSON protocol. Bind only to loopback in development;
 * production launches this process behind a Unix-domain socket. */
object EngineWorkerProtocol {
    const val VERSION = 1
    const val maxFrameBytes = 16 * 1024 * 1024
    val json = Json { ignoreUnknownKeys = false; encodeDefaults = true }
}

@Serializable
data class WorkerRequest(
    val protocolVersion: Int,
    val actorId: String,
    val rulesetManifest: WorkerRulesetManifest,
    val operation: WorkerOperation,
)

@Serializable
data class WorkerRulesetManifest(
    val engineBuild: String,
    val baseRuleset: WorkerRuleset,
    val mods: List<WorkerRuleset> = emptyList(),
)

@Serializable
data class WorkerRuleset(val name: String, val sha256: String)

@Serializable
sealed interface WorkerOperation {
    /** A setup intent, never a client-created GameInfo. The worker invokes the
     * shared GameStarter to create canonical revision zero. */
    @Serializable @SerialName("create_game")
    data class CreateGame(val setup: String) : WorkerOperation

    @Serializable @SerialName("end_turn")
    data class EndTurn(val snapshot: String) : WorkerOperation
}

@Serializable
data class WorkerResponse(
    val protocolVersion: Int = EngineWorkerProtocol.VERSION,
    val snapshot: String? = null,
    val canonicalStateHash: String? = null,
    val error: WorkerError? = null,
)

@Serializable
data class WorkerError(val code: String, val message: String)

class AuthoritativeEngineWorker {
    fun execute(request: WorkerRequest): WorkerResponse = try {
        require(request.protocolVersion == EngineWorkerProtocol.VERSION) { "Unsupported protocol version" }
        val engine = HeadlessGameEngine(GameExecutionContext.authoritative(
            actorId = request.actorId,
            rulesetManifest = request.rulesetManifest.toCore(),
        ))
        when (val operation = request.operation) {
            is WorkerOperation.CreateGame -> {
                val setup = json().fromJson(GameSetupInfo::class.java, operation.setup)
                setup.gameParameters.isOnlineMultiplayer = true
                setup.gameParameters.multiplayerServerUrl = null
                val result = engine.createGame(setup)
                WorkerResponse(snapshot = engine.serializeSnapshot(result.game), canonicalStateHash = result.canonicalStateHash)
            }
            is WorkerOperation.EndTurn -> {
                val result = engine.endTurn(engine.loadSnapshot(operation.snapshot))
                WorkerResponse(snapshot = engine.serializeSnapshot(result.game), canonicalStateHash = result.canonicalStateHash)
            }
        }
    } catch (exception: Exception) {
        WorkerResponse(error = WorkerError("engine_rejected", exception.message ?: "Engine execution failed"))
    }

    private fun WorkerRulesetManifest.toCore() = RulesetManifest(
        engineBuild,
        ContentAddressedRuleset(baseRuleset.name, baseRuleset.sha256),
        mods.map { ContentAddressedRuleset(it.name, it.sha256) },
    )
}

class LoopbackEngineWorkerServer(private val worker: AuthoritativeEngineWorker = AuthoritativeEngineWorker()) {
    fun serve(port: Int) = ServerSocket(port, 50, java.net.InetAddress.getLoopbackAddress()).use { server ->
        while (true) {
            server.accept().use { socket ->
                // A readiness probe or a malformed local peer must not kill
                // the long-lived worker process. Valid requests receive their
                // structured engine result; invalid frames are simply dropped.
                runCatching { serveConnection(socket) }
            }
        }
    }

    private fun serveConnection(socket: Socket) {
        val input = DataInputStream(socket.getInputStream())
        val output = DataOutputStream(socket.getOutputStream())
        val frameSize = input.readInt()
        require(frameSize in 1..EngineWorkerProtocol.maxFrameBytes) { "Invalid frame length" }
        val request = EngineWorkerProtocol.json.decodeFromString<WorkerRequest>(input.readNBytes(frameSize).decodeToString())
        val response = EngineWorkerProtocol.json.encodeToString(WorkerResponse.serializer(), worker.execute(request)).encodeToByteArray()
        output.writeInt(response.size)
        output.write(response)
        output.flush()
    }
}
