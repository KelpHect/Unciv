package com.unciv.logic.multiplayer.authoritative

/**
 * Projection-only boundary for exact unit order state and direct controls.
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

    suspend fun setPosture(unitId: Int, posture: UnitPosture) {
        require(posture in requireCurrentTurnUnit(unitId).availablePostures) {
            "Posture is absent from the current server projection"
        }
        submit { actions.setPosture(unitId, posture) }
    }

    suspend fun disband(unitId: Int) {
        require(requireCurrentTurnUnit(unitId).canDisband) {
            "Disband is absent from the current server projection"
        }
        submit { actions.disband(unitId) }
    }

    suspend fun pillage(unitId: Int) {
        require(requireCurrentTurnUnit(unitId).canPillage) {
            "Pillage is absent from the current server projection"
        }
        submit { actions.pillage(unitId) }
    }

    suspend fun foundCity(unitId: Int) {
        require(requireCurrentTurnUnit(unitId).canFoundCity) {
            "Found city is absent from the current server projection"
        }
        submit { actions.foundCity(unitId) }
    }

    suspend fun paradrop(unitId: Int, x: Int, y: Int) {
        require(requireCurrentTurnUnit(unitId).paradropDestinations.any {
            it.x == x && it.y == y
        }) {
            "Paradrop destination is absent from the current server projection"
        }
        submit { actions.paradrop(unitId, x, y) }
    }

    suspend fun upgrade(unitId: Int, targetUnitName: String) {
        require(requireCurrentTurnUnit(unitId).availableUpgradeTargets.any {
            it.targetUnitName == targetUnitName
        }) {
            "Upgrade target is absent from the current server projection"
        }
        submit { actions.upgrade(listOf(unitId), targetUnitName) }
    }

    suspend fun rename(unitId: Int, instanceName: String?) {
        require(requireCurrentTurnUnit(unitId).canRename) {
            "Rename is absent from the current server projection"
        }
        require(instanceName == null ||
            (instanceName.isNotBlank() && instanceName.length <= 100 &&
                instanceName.none { it.isISOControl() })) {
            "Unit name must be null or 1-100 printable characters"
        }
        submit { actions.rename(unitId, instanceName) }
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
    val setPosture: suspend (Int, UnitPosture) -> AuthoritativeCommandOutcome?,
    val disband: suspend (Int) -> AuthoritativeCommandOutcome?,
    val pillage: suspend (Int) -> AuthoritativeCommandOutcome?,
    val foundCity: suspend (Int) -> AuthoritativeCommandOutcome?,
    val paradrop: suspend (Int, Int, Int) -> AuthoritativeCommandOutcome?,
    val upgrade: suspend (List<Int>, String) -> AuthoritativeCommandOutcome?,
    val rename: suspend (Int, String?) -> AuthoritativeCommandOutcome?,
) {
    companion object {
        val Unavailable = AuthoritativeUnitOrderActions(
            cancelMovement = { null },
            setExploration = { _, _ -> null },
            setAutomation = { _, _ -> null },
            promote = { _, _ -> null },
            swap = { _, _, _ -> null },
            setPosture = { _, _ -> null },
            disband = { null },
            pillage = { null },
            foundCity = { null },
            paradrop = { _, _, _ -> null },
            upgrade = { _, _ -> null },
            rename = { _, _ -> null },
        )
    }
}
