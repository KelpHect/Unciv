package com.unciv.logic.multiplayer.authoritative

import java.util.UUID

data class AuthoritativeCreationMeaning(
    val baseRulesetName: String,
    val modNames: Set<String>,
    val setup: ApiV3GameSetup,
)

/**
 * Binds one caller-stable operation ID to one exact server creation meaning.
 * Network retries reuse the ID; editing any authoritative setup input rotates
 * it so the server never sees changed meaning under an existing idempotency key.
 */
class AuthoritativeCreationRetryState(
    private val newOperationId: () -> String = { UUID.randomUUID().toString() },
) {
    private var boundMeaning: AuthoritativeCreationMeaning? = null
    private var operationId: String? = null

    @Synchronized
    fun operationIdFor(meaning: AuthoritativeCreationMeaning): String {
        if (meaning != boundMeaning) {
            boundMeaning = meaning.copy(modNames = meaning.modNames.toSet())
            operationId = newOperationId()
        }
        return requireNotNull(operationId)
    }
}
