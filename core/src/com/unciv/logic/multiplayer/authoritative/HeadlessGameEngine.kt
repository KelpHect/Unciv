package com.unciv.logic.multiplayer.authoritative

import com.unciv.logic.GameExecutionContext
import com.unciv.logic.GameInfo
import com.unciv.logic.GameStarter
import com.unciv.logic.files.UncivFiles
import com.unciv.models.metadata.GameSetupInfo
import java.security.MessageDigest

/**
 * Kotlin-side execution boundary for API v3. This is intentionally private to
 * the future worker protocol: it reuses Unciv's existing game engine and does
 * not listen on a network port or commit persistence.
 */
class HeadlessGameEngine(
    private val executionContext: GameExecutionContext,
) {
    init {
        require(executionContext.actorId != null) { "Authoritative execution requires an authenticated actor" }
        require(!executionContext.persistLocalSettings) { "Authoritative execution must not persist client settings" }
        require(!executionContext.allowUiSideEffects) { "Authoritative execution must not trigger UI effects" }
        require(executionContext.rulesetManifest != null) { "Authoritative execution requires a pinned ruleset manifest" }
    }

    fun createGame(setup: GameSetupInfo): EngineResult {
        val game = GameStarter.startNewGame(setup, executionContext)
        return result(game)
    }

    /** Runs shared turn processing, including AI/turn rotation, in the worker. */
    fun endTurn(game: GameInfo): EngineResult {
        game.nextTurn(executionContext = executionContext)
        return result(game)
    }

    /**
     * Rebuilds the ruleset-dependent transient graph from a canonical snapshot.
     * Snapshot validation, size limits, and version selection belong to the
     * Rust control plane before a worker receives this payload.
     */
    fun loadSnapshot(snapshot: String): GameInfo = UncivFiles.gameInfoFromString(snapshot)

    fun serializeSnapshot(game: GameInfo): String =
        UncivFiles.gameInfoToString(game, forceZip = false, updateChecksum = false)

    fun stateHash(game: GameInfo): String {
        val bytes = serializeSnapshot(game).toByteArray(Charsets.UTF_8)
        return MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    }

    private fun result(game: GameInfo) = EngineResult(game, stateHash(game))
}

data class EngineResult(
    val game: GameInfo,
    val canonicalStateHash: String,
)
