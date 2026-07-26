package com.unciv.logic.multiplayer.authoritative

/**
 * Projection-only boundary for closed, direct unit special actions.
 *
 * The client submits only identities advertised for the selected owned unit.
 * Canonical legality, costs, effects, and unit consumption remain worker-owned.
 */
class AuthoritativeUnitActionController internal constructor(
    private val projection: () -> PlayerProjection,
    private val submit: suspend (
        operation: suspend () -> AuthoritativeCommandOutcome?,
    ) -> Unit,
    private val actions: AuthoritativeUnitActions,
) {
    suspend fun useReligiousAction(unitId: Int, action: ReligiousUnitAction) {
        require(action in requireUnit(unitId).availableReligiousActions) {
            "Religious action is absent from the current server projection"
        }
        submit { actions.useReligiousAction(unitId, action) }
    }

    suspend fun useGreatPersonAction(unitId: Int, action: GreatPersonUnitAction) {
        require(action in requireUnit(unitId).availableGreatPersonActions) {
            "Great-person action is absent from the current server projection"
        }
        submit { actions.useGreatPersonAction(unitId, action) }
    }

    suspend fun gift(unitId: Int) {
        require(requireUnit(unitId).canGift) {
            "Unit gift is absent from the current server projection"
        }
        submit { actions.gift(unitId) }
    }

    suspend fun addToCapitalProject(unitId: Int) {
        require(requireUnit(unitId).capitalProjectName != null) {
            "Capital-project action is absent from the current server projection"
        }
        submit { actions.addToCapitalProject(unitId) }
    }

    suspend fun transform(unitId: Int, actionId: String) {
        require(requireUnit(unitId).availableTransformActions.any { it.actionId == actionId }) {
            "Unit transformation is absent from the current server projection"
        }
        submit { actions.transform(unitId, actionId) }
    }

    suspend fun triggerUnique(unitId: Int, actionId: String) {
        require(requireUnit(unitId).availableTriggerActions.any { it.actionId == actionId }) {
            "Triggered unit action is absent from the current server projection"
        }
        submit { actions.triggerUnique(unitId, actionId) }
    }

    suspend fun createInstantImprovement(unitId: Int, actionId: String) {
        require(
            requireUnit(unitId).availableInstantImprovementActions.any {
                it.actionId == actionId
            },
        ) {
            "Instant improvement is absent from the current server projection"
        }
        submit { actions.createInstantImprovement(unitId, actionId) }
    }

    private fun requireUnit(unitId: Int): ProjectedUnit =
        projection().ownUnits.singleOrNull { it.id == unitId }
            ?: error("Unit is absent from the current server projection")
}

data class AuthoritativeUnitActions(
    val useReligiousAction:
        suspend (Int, ReligiousUnitAction) -> AuthoritativeCommandOutcome?,
    val useGreatPersonAction:
        suspend (Int, GreatPersonUnitAction) -> AuthoritativeCommandOutcome?,
    val gift: suspend (Int) -> AuthoritativeCommandOutcome?,
    val addToCapitalProject: suspend (Int) -> AuthoritativeCommandOutcome?,
    val transform: suspend (Int, String) -> AuthoritativeCommandOutcome?,
    val triggerUnique: suspend (Int, String) -> AuthoritativeCommandOutcome?,
    val createInstantImprovement: suspend (Int, String) -> AuthoritativeCommandOutcome?,
) {
    companion object {
        val Unavailable = AuthoritativeUnitActions(
            useReligiousAction = { _, _ -> null },
            useGreatPersonAction = { _, _ -> null },
            gift = { null },
            addToCapitalProject = { null },
            transform = { _, _ -> null },
            triggerUnique = { _, _ -> null },
            createInstantImprovement = { _, _ -> null },
        )
    }
}
