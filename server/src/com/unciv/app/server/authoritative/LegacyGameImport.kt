package com.unciv.app.server.authoritative

import com.badlogic.gdx.utils.Base64Coder
import com.unciv.logic.CompatibilityVersion
import com.unciv.logic.GameInfo
import com.unciv.logic.multiplayer.authoritative.HeadlessGameEngine
import kotlinx.serialization.Serializable
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.util.UUID
import java.util.zip.GZIPInputStream

@Serializable
data class LegacyPlayerMapping(
    val legacyPlayerId: String,
    val accountId: String,
)

@Serializable
data class LegacyImportedMember(
    val legacyPlayerId: String,
    val accountId: String,
    val civilizationId: String,
    val spectator: Boolean,
)

@Serializable
data class LegacyImportMetadata(
    val legacyGameId: String,
    val canonicalGameId: String,
    val serializationVersion: Int,
    val createdWith: String,
    val turns: Int,
    val currentPlayer: String,
    val baseRuleset: String,
    val mods: List<String>,
    val members: List<LegacyImportedMember>,
)

internal data class LegacyNormalization(
    val game: GameInfo,
    val ownerCivilizationId: String,
    val metadata: LegacyImportMetadata,
)

/** One-way migration validation implemented beside the worker protocol rather
 * than inside its dispatcher. It never persists or mutates the source file. */
internal object LegacyGameImporter {
    const val maxSnapshotBytes = EngineWorkerProtocol.maxFrameBytes

    fun normalize(
        engine: HeadlessGameEngine,
        manifest: WorkerRulesetManifest,
        actorId: String,
        operation: WorkerOperation.NormalizeLegacyGame,
        maximumBytes: Int = maxSnapshotBytes,
    ): LegacyNormalization {
        require(UUID.fromString(operation.canonicalGameId).toString() ==
            operation.canonicalGameId.lowercase()) {
            "Canonical game ID must be a normalized UUID"
        }
        require(operation.expectedLegacyGameId.isNotBlank()) {
            "Expected legacy game ID must not be blank"
        }
        require(operation.playerMappings.isNotEmpty()) {
            "Every imported game requires human player mappings"
        }
        require(operation.playerMappings.map { it.legacyPlayerId }.distinct().size ==
            operation.playerMappings.size) {
            "Legacy player IDs must be unique"
        }
        require(operation.playerMappings.map { it.accountId }.distinct().size ==
            operation.playerMappings.size) {
            "V3 account IDs must be unique"
        }
        operation.playerMappings.forEach {
            require(it.legacyPlayerId.isNotBlank()) { "Legacy player IDs must not be blank" }
            require(UUID.fromString(it.accountId).toString() == it.accountId.lowercase()) {
                "V3 account IDs must be normalized UUIDs"
            }
        }

        val game = engine.loadSnapshot(decodeSnapshot(operation.snapshot, maximumBytes))
        require(game.version.number in 1..CompatibilityVersion.CURRENT_COMPATIBILITY_NUMBER) {
            "Legacy serialization version is unsupported"
        }
        require(game.gameId == operation.expectedLegacyGameId) {
            "Legacy game ID does not match the requested import"
        }
        require(game.gameParameters.isOnlineMultiplayer) {
            "Only legacy online multiplayer games may be imported"
        }
        require(game.gameParameters.baseRuleset == manifest.baseRuleset.name) {
            "Legacy base ruleset does not match the pinned manifest"
        }
        require(game.gameParameters.mods == manifest.mods.map { it.name }.toSet()) {
            "Legacy mods do not match the pinned manifest"
        }
        require(game.currentPlayer.isNotBlank() &&
            game.civilizations.count { it.civID == game.currentPlayer } == 1) {
            "Legacy current player is invalid"
        }

        val humans = game.civilizations.filter { it.isHuman() }
        require(humans.any { !it.isSpectator() }) { "Legacy game has no human players" }
        require(humans.all { it.playerId.isNotBlank() }) {
            "Every legacy human civilization must have a player ID"
        }
        require(humans.map { it.playerId }.distinct().size == humans.size) {
            "Each legacy human civilization must have a unique player ID"
        }
        val mappings = operation.playerMappings.associate { it.legacyPlayerId to it.accountId }
        require(humans.map { it.playerId }.toSet() == mappings.keys) {
            "Player mappings must exactly cover legacy human players and spectators"
        }
        val members = humans.map {
            LegacyImportedMember(
                legacyPlayerId = it.playerId,
                accountId = mappings.getValue(it.playerId),
                civilizationId = it.civID,
                spectator = it.isSpectator(),
            )
        }.sortedBy { it.civilizationId }
        val owner = members.singleOrNull { it.accountId == actorId && !it.spectator }
            ?: error("Authenticated importing owner must map to exactly one playable civilization")

        humans.forEach { it.playerId = mappings.getValue(it.playerId) }
        val metadata = LegacyImportMetadata(
            legacyGameId = game.gameId,
            canonicalGameId = operation.canonicalGameId,
            serializationVersion = game.version.number,
            createdWith = game.version.createdWith.toSerializeString(),
            turns = game.turns,
            currentPlayer = game.currentPlayer,
            baseRuleset = game.gameParameters.baseRuleset,
            mods = game.gameParameters.mods.sorted(),
            members = members,
        )
        game.gameId = operation.canonicalGameId
        return LegacyNormalization(game, owner.civilizationId, metadata)
    }

    /** Legacy saves are either plain JSON or Base64-encoded gzip. The existing
     * client decoder is intentionally permissive and unbounded, so imports use
     * this streaming cap before the shared serializer rebuilds transients. */
    internal fun decodeSnapshot(snapshot: String, maximumBytes: Int = maxSnapshotBytes): String {
        require(maximumBytes > 0) { "Legacy snapshot byte limit must be positive" }
        val fixed = snapshot.trim().replace("\r", "").replace("\n", "")
        require(fixed.toByteArray(Charsets.UTF_8).size <= maximumBytes) {
            "Legacy snapshot exceeded the import byte limit"
        }
        if (fixed.startsWith('{')) return fixed
        val compressed = try {
            Base64Coder.decode(fixed)
        } catch (_: Exception) {
            return fixed
        }
        require(compressed.size <= maximumBytes) {
            "Legacy compressed snapshot exceeded the import byte limit"
        }
        return try {
            decodeGzip(compressed, maximumBytes)
        } catch (_: IOException) {
            fixed
        }
    }

    private fun decodeGzip(compressed: ByteArray, maximumBytes: Int): String {
        val output = ByteArrayOutputStream(minOf(maximumBytes, compressed.size * 4))
        GZIPInputStream(ByteArrayInputStream(compressed)).use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            var total = 0
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                total = Math.addExact(total, read)
                require(total <= maximumBytes) {
                    "Legacy uncompressed snapshot exceeded the import byte limit"
                }
                output.write(buffer, 0, read)
            }
        }
        val decoder = Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
        return decoder.decode(ByteBuffer.wrap(output.toByteArray())).toString()
    }
}
