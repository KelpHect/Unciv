package com.unciv.logic.multiplayer.authoritative

/**
 * Projection-only boundary for exact unit order state and immediate promotions.
 *
 * It intentionally omits actions whose legal choices are not yet advertised
 * per unit by the worker.
 */
class AuthoritativeUnitOrderController internal constructor(
    private val projection: () -> PlayerProjection,
    private val submit: suspend (
        operation: suspend () -> AuthoritativeCommandOutcome?,
    ) -> Unit,
    private val actions: AuthoritativeUnitOrderActions,
) {
    suspend fun cancelMovement(unitId: Int) {
        val unit = requireCurrentTurnUnit(unitId)
        require(unit.movementDestinationX != null && unit.movementDestinationY != null) {
            "Movement order is absent from the current server projection"
        }
        submit { actions.cancelMovement(unitId) }
    }

    suspend fun setExploration(unitId: Int, enabled: Boolean) {
        val unit = requireCurrentTurnUnit(unitId)
        require(unit.exploring != enabled) {
            "Exploration state already matches the server projection"
        }
        submit { actions.setExploration(unitId, enabled) }
    }

    suspend fun setAutomation(unitId: Int, enabled: Boolean) {
        val unit = requireCurrentTurnUnit(unitId)
        require(unit.automated != enabled) {
            "Automation state already matches the server projection"
        }
        submit { actions.setAutomation(unitId, enabled) }
    }

    suspend fun promote(unitId: Int, promotionName: String) {
        val unit = requireCurrentTurnUnit(unitId)
        require(promotionName in unit.availablePromotions) {
            "Promotion is absent from the current server projection"
        }
        submit { actions.promote(unitId, promotionName) }
    }

    suspend fun swap(unitId: Int, x: Int, y: Int) {
        val unit = requireCurrentTurnUnit(unitId)
        require(unit.swapDestinations.any { it.x == x && it.y == y }) {
            "Swap destination is absent from the current server projection"
        }
        submit { actions.swap(unitId, x, y) }
    }

    private fun requireCurrentTurnUnit(unitId: Int): ProjectedUnit {
        require(projection().isCurrentTurn) {
            "Unit orders are unavailable outside the current turn"
        }
        return projection().ownUnits.singleOrNull { it.id == unitId }
            ?: error("Unit is absent from the current server projection")
    }
}

data class AuthoritativeUnitOrderActions(
    val cancelMovement: suspend (Int) -> AuthoritativeCommandOutcome?,
    val setExploration: suspend (Int, Boolean) -> AuthoritativeCommandOutcome?,
    val setAutomation: suspend (Int, Boolean) -> AuthoritativeCommandOutcome?,
    val promote: suspend (Int, String) -> AuthoritativeCommandOutcome?,
    val swap: suspend (Int, Int, Int) -> AuthoritativeCommandOutcome?,
) {
    companion object {
        val Unavailable = AuthoritativeUnitOrderActions(
            cancelMovement = { null },
            setExploration = { _, _ -> null },
            setAutomation = { _, _ -> null },
            promote = { _, _ -> null },
            swap = { _, _, _ -> null },
        )
    }
}
