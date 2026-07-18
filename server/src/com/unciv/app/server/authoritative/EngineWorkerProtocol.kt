package com.unciv.app.server.authoritative

import com.badlogic.gdx.files.FileHandle
import com.unciv.UncivGame
import com.unciv.logic.ContentAddressedRuleset
import com.unciv.logic.GameExecutionContext
import com.unciv.logic.RulesetManifest
import com.unciv.logic.multiplayer.authoritative.HeadlessGameEngine
import com.unciv.logic.multiplayer.authoritative.PlayerProjection
import com.unciv.logic.map.HexCoord
import com.unciv.json.json
import com.unciv.models.metadata.GameSetupInfo
import com.unciv.models.ruleset.RulesetCache
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.ServerSocket
import java.net.Socket
import java.nio.ByteBuffer
import java.security.MessageDigest

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
    val actorId: String? = null,
    val rulesetManifest: WorkerRulesetManifest? = null,
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
    /** Capability handshake performed before a control plane routes work to
     * this process. It intentionally needs neither actor nor game state. */
    @Serializable @SerialName("handshake")
    data object Handshake : WorkerOperation

    /** A setup intent, never a client-created GameInfo. The worker invokes the
     * shared GameStarter to create canonical revision zero. */
    @Serializable @SerialName("create_game")
    data class CreateGame(val setup: String) : WorkerOperation

    @Serializable @SerialName("assign_player")
    data class AssignPlayer(val snapshot: String) : WorkerOperation

    @Serializable @SerialName("end_turn")
    data class EndTurn(
        val snapshot: String,
        val actorCivilizationId: String,
    ) : WorkerOperation

    @Serializable @SerialName("move_unit")
    data class MoveUnit(
        val snapshot: String,
        val actorCivilizationId: String,
        val unitId: Int,
        val destinationX: Int,
        val destinationY: Int,
    ) : WorkerOperation

    @Serializable @SerialName("queue_construction")
    data class QueueConstruction(
        val snapshot: String,
        val actorCivilizationId: String,
        val cityId: String,
        val constructionName: String,
    ) : WorkerOperation

    @Serializable @SerialName("remove_construction")
    data class RemoveConstruction(
        val snapshot: String,
        val actorCivilizationId: String,
        val cityId: String,
        val queueIndex: Int,
        val expectedConstructionName: String,
    ) : WorkerOperation

    @Serializable @SerialName("move_construction")
    data class MoveConstruction(
        val snapshot: String,
        val actorCivilizationId: String,
        val cityId: String,
        val fromIndex: Int,
        val toIndex: Int,
        val expectedConstructionName: String,
    ) : WorkerOperation

    @Serializable @SerialName("set_research_path")
    data class SetResearchPath(
        val snapshot: String,
        val actorCivilizationId: String,
        val technologyName: String,
    ) : WorkerOperation

    @Serializable @SerialName("adopt_policy")
    data class AdoptPolicy(
        val snapshot: String,
        val actorCivilizationId: String,
        val policyName: String,
    ) : WorkerOperation

    @Serializable @SerialName("choose_free_technology")
    data class ChooseFreeTechnology(
        val snapshot: String,
        val actorCivilizationId: String,
        val technologyName: String,
    ) : WorkerOperation

    @Serializable @SerialName("project_state")
    data class ProjectState(
        val snapshot: String,
        val actorCivilizationId: String,
    ) : WorkerOperation
}

@Serializable
data class WorkerResponse(
    val protocolVersion: Int = EngineWorkerProtocol.VERSION,
    val engineBuild: String? = null,
    val installedRulesets: List<WorkerRuleset>? = null,
    val snapshot: String? = null,
    val canonicalStateHash: String? = null,
    val actorCivilizationId: String? = null,
    val playerProjection: PlayerProjection? = null,
    val error: WorkerError? = null,
)

@Serializable
data class WorkerError(val code: String, val message: String)

class AuthoritativeEngineWorker {
    fun execute(request: WorkerRequest): WorkerResponse = try {
        require(request.protocolVersion == EngineWorkerProtocol.VERSION) { "Unsupported protocol version" }
        if (request.operation is WorkerOperation.Handshake) return WorkerResponse(
            engineBuild = InstalledRulesetCatalog.engineBuild,
            installedRulesets = InstalledRulesetCatalog.all(),
        )
        val actorId = requireNotNull(request.actorId) { "Execution requires an authenticated actor" }
        val manifest = requireNotNull(request.rulesetManifest) { "Execution requires a ruleset manifest" }
        InstalledRulesetCatalog.requireAvailable(manifest)
        val engine = HeadlessGameEngine(GameExecutionContext.authoritative(
            actorId = actorId,
            rulesetManifest = manifest.toCore(),
        ))
        when (val operation = request.operation) {
            WorkerOperation.Handshake -> error("Handshake was not handled")
            is WorkerOperation.CreateGame -> {
                val setup = json().fromJson(GameSetupInfo::class.java, operation.setup)
                setup.gameParameters.isOnlineMultiplayer = true
                setup.gameParameters.multiplayerServerUrl = null
                val owner = setup.gameParameters.players.firstOrNull()
                    ?: error("Game setup requires at least one player")
                owner.playerType = com.unciv.logic.civilization.PlayerType.Human
                require(setup.gameParameters.baseRuleset == manifest.baseRuleset.name) {
                    "Setup base ruleset does not match the pinned manifest"
                }
                require(setup.gameParameters.mods == manifest.mods.map { it.name }.toSet()) {
                    "Setup mods do not match the pinned manifest"
                }
                owner.playerId = actorId
                val result = engine.createGame(setup)
                val ownerCivilization = result.game.civilizations.singleOrNull {
                    it.playerId == actorId
                } ?: error("GameStarter did not assign the authenticated owner")
                responseForGame(engine, result.game, ownerCivilization.civID)
            }
            is WorkerOperation.AssignPlayer -> {
                val game = engine.loadSnapshot(operation.snapshot)
                val assignment = engine.assignPlayer(game)
                responseForGame(engine, assignment.result.game, assignment.civilizationId)
            }
            is WorkerOperation.EndTurn -> {
                val game = engine.loadSnapshot(operation.snapshot)
                val result = engine.endTurn(game, operation.actorCivilizationId)
                responseForGame(engine, result.game)
            }
            is WorkerOperation.MoveUnit -> {
                val game = engine.loadSnapshot(operation.snapshot)
                val result = engine.moveUnit(
                    game,
                    operation.actorCivilizationId,
                    operation.unitId,
                    HexCoord(operation.destinationX, operation.destinationY),
                )
                responseForGame(engine, result.game)
            }
            is WorkerOperation.QueueConstruction -> {
                val game = engine.loadSnapshot(operation.snapshot)
                val result = engine.queueConstruction(
                    game,
                    operation.actorCivilizationId,
                    operation.cityId,
                    operation.constructionName,
                )
                responseForGame(engine, result.game)
            }
            is WorkerOperation.RemoveConstruction -> {
                val game = engine.loadSnapshot(operation.snapshot)
                val result = engine.removeConstruction(
                    game,
                    operation.actorCivilizationId,
                    operation.cityId,
                    operation.queueIndex,
                    operation.expectedConstructionName,
                )
                responseForGame(engine, result.game)
            }
            is WorkerOperation.MoveConstruction -> {
                val game = engine.loadSnapshot(operation.snapshot)
                val result = engine.moveConstruction(
                    game,
                    operation.actorCivilizationId,
                    operation.cityId,
                    operation.fromIndex,
                    operation.toIndex,
                    operation.expectedConstructionName,
                )
                responseForGame(engine, result.game)
            }
            is WorkerOperation.SetResearchPath -> {
                val game = engine.loadSnapshot(operation.snapshot)
                val result = engine.setResearchPath(
                    game,
                    operation.actorCivilizationId,
                    operation.technologyName,
                )
                responseForGame(engine, result.game)
            }
            is WorkerOperation.AdoptPolicy -> {
                val game = engine.loadSnapshot(operation.snapshot)
                val result = engine.adoptPolicy(
                    game,
                    operation.actorCivilizationId,
                    operation.policyName,
                )
                responseForGame(engine, result.game)
            }
            is WorkerOperation.ChooseFreeTechnology -> {
                val game = engine.loadSnapshot(operation.snapshot)
                val result = engine.chooseFreeTechnology(
                    game,
                    operation.actorCivilizationId,
                    operation.technologyName,
                )
                responseForGame(engine, result.game)
            }
            is WorkerOperation.ProjectState -> {
                val game = engine.loadSnapshot(operation.snapshot)
                val projection = engine.playerProjection(game, operation.actorCivilizationId)
                WorkerResponse(playerProjection = projection)
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

    /** Hash exactly the bytes returned over the worker protocol. Serializing a
     * mutable game twice is not a valid canonical-hash operation. */
    private fun responseForGame(
        engine: HeadlessGameEngine,
        game: com.unciv.logic.GameInfo,
        actorCivilizationId: String? = null,
    ): WorkerResponse {
        val snapshot = engine.serializeSnapshot(game)
        val hash = sha256(snapshot.toByteArray(Charsets.UTF_8))
        return WorkerResponse(
            snapshot = snapshot,
            canonicalStateHash = hash,
            actorCivilizationId = actorCivilizationId,
        )
    }

    private fun sha256(bytes: ByteArray) = MessageDigest.getInstance("SHA-256")
        .digest(bytes)
        .joinToString("") { "%02x".format(it) }
}

/** Computes content identities over the exact ruleset JSON bytes visible to
 * this worker. Paths are sorted and length-framed so concatenation cannot be
 * ambiguous. Media is intentionally excluded from gameplay identity. */
object InstalledRulesetCatalog {
    val engineBuild: String get() = UncivGame.VERSION.toSerializeString()

    fun all(): List<WorkerRuleset> = RulesetCache.keys.sorted().map { named(it) }

    fun requireAvailable(manifest: WorkerRulesetManifest) {
        require(manifest.engineBuild == engineBuild) {
            "Pinned engine build is unavailable"
        }
        val requested = listOf(manifest.baseRuleset) + manifest.mods
        require(requested.map { it.name }.distinct().size == requested.size) {
            "Ruleset manifest contains duplicate names"
        }
        requested.forEach { expected ->
            val installed = named(expected.name)
            require(installed.sha256.equals(expected.sha256, ignoreCase = true)) {
                "Pinned ruleset content is unavailable: ${expected.name}"
            }
        }
    }

    fun named(name: String): WorkerRuleset {
        val ruleset = RulesetCache[name] ?: error("Pinned ruleset is unavailable: $name")
        val jsonFolder = ruleset.folderLocation?.child("jsons") ?: FileHandle("jsons/$name")
        require(jsonFolder.exists() && jsonFolder.isDirectory) { "Ruleset JSON is unavailable: $name" }
        return WorkerRuleset(name, hashDirectory(jsonFolder))
    }

    internal fun hashDirectory(root: FileHandle): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val files = collectFiles(root).sortedBy { it.first }
        require(files.isNotEmpty()) { "Ruleset JSON directory is empty" }
        files.forEach { (relativePath, file) ->
            val path = relativePath.replace('\\', '/').toByteArray(Charsets.UTF_8)
            val bytes = file.readBytes()
            digest.update(ByteBuffer.allocate(Int.SIZE_BYTES).putInt(path.size).array())
            digest.update(path)
            digest.update(ByteBuffer.allocate(Long.SIZE_BYTES).putLong(bytes.size.toLong()).array())
            digest.update(bytes)
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun collectFiles(root: FileHandle, prefix: String = ""): List<Pair<String, FileHandle>> =
        root.list().flatMap { child ->
            val relative = if (prefix.isEmpty()) child.name() else "$prefix/${child.name()}"
            if (child.isDirectory) collectFiles(child, relative) else listOf(relative to child)
        }
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
