package com.unciv.logic.multiplayer.authoritative

/** Projection-only boundary for current-turn spy movement and coup controls. */
class AuthoritativeSpyController internal constructor(
    private val projection: () -> PlayerProjection,
    private val submit: suspend (
        operation: suspend () -> AuthoritativeCommandOutcome?,
    ) -> Unit,
    private val actions: AuthoritativeSpyActions,
) {
    suspend fun move(spyName: String, cityId: String?) {
        val spy = requireCurrentTurnSpy(spyName)
        require(if (cityId == null) spy.canMoveToHideout else cityId in spy.availableCityIds) {
            "Spy destination is absent from the current server projection"
        }
        submit { actions.move(spyName, cityId) }
    }

    suspend fun setCoup(spyName: String, enabled: Boolean) {
        val spy = requireCurrentTurnSpy(spyName)
        require(if (enabled) spy.canStageCoup else spy.canCancelCoup) {
            "Spy coup action is absent from the current server projection"
        }
        submit { actions.setCoup(spyName, enabled) }
    }

    private fun requireCurrentTurnSpy(spyName: String): ProjectedSpy {
        require(projection().isCurrentTurn) {
            "Spy controls are unavailable outside the current turn"
        }
        return projection().spies.singleOrNull { it.name == spyName }
            ?: error("Spy is absent from the current server projection")
    }
}

data class AuthoritativeSpyActions(
    val move: suspend (String, String?) -> AuthoritativeCommandOutcome?,
    val setCoup: suspend (String, Boolean) -> AuthoritativeCommandOutcome?,
) {
    companion object {
        val Unavailable = AuthoritativeSpyActions(
            move = { _, _ -> null },
            setCoup = { _, _ -> null },
        )
    }
}
