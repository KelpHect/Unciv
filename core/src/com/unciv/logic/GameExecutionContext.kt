package com.unciv.logic

/**
 * Supplies execution-only dependencies to state-changing game logic.
 *
 * It is deliberately not serialized in [GameInfo]. A server worker supplies
 * this context for canonical execution; a client may use the default context
 * only for local/single-player behavior and optional prediction.
 */
data class GameExecutionContext(
    val actorId: String? = null,
    val clockMillis: () -> Long = System::currentTimeMillis,
    val persistLocalSettings: Boolean = true,
    val allowUiSideEffects: Boolean = true,
    val rulesetManifest: RulesetManifest? = null,
    val featureFlags: Set<String> = emptySet(),
) {
    companion object {
        fun client() = GameExecutionContext()

        fun authoritative(
            actorId: String,
            rulesetManifest: RulesetManifest,
            clockMillis: () -> Long = System::currentTimeMillis,
            featureFlags: Set<String> = emptySet(),
        ) = GameExecutionContext(
            actorId = actorId,
            clockMillis = clockMillis,
            persistLocalSettings = false,
            allowUiSideEffects = false,
            rulesetManifest = rulesetManifest,
            featureFlags = featureFlags,
        )
    }
}

/**
 * Immutable identity for the engine/ruleset bundle selected by an authoritative
 * game. Content hashes are introduced now so future persistence cannot mistake
 * a display name for an immutable ruleset identity.
 */
data class RulesetManifest(
    val engineBuild: String,
    val baseRuleset: ContentAddressedRuleset,
    val mods: List<ContentAddressedRuleset> = emptyList(),
)

data class ContentAddressedRuleset(
    val name: String,
    val sha256: String,
) {
    init {
        require(name.isNotBlank()) { "Ruleset name must not be blank" }
        require(sha256.matches(Regex("[0-9a-fA-F]{64}"))) { "Ruleset SHA-256 must be 64 hexadecimal characters" }
    }
}
