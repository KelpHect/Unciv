package com.unciv.logic.multiplayer.authoritative

/**
 * Projection-only combat input boundary.
 *
 * Targets and previews are server-authored. The private worker re-derives
 * canonical legality and resolves combat after the typed intent is submitted.
 */
class AuthoritativeCombatController internal constructor(
    private val projection: () -> PlayerProjection,
    private val submit: suspend (
        operation: suspend () -> AuthoritativeCommandOutcome?,
    ) -> Unit,
    private val actions: AuthoritativeCombatActions,
) {
    suspend fun attack(unitId: Int, x: Int, y: Int) {
        requireUnit(unitId).attackTargets.singleOrNull { it.x == x && it.y == y }
            ?: error("Attack target is absent from the current server projection")
        submit { actions.attack(unitId, x, y) }
    }

    suspend fun launchNuclearStrike(unitId: Int, x: Int, y: Int) {
        requireUnit(unitId).nuclearTargetCandidates.singleOrNull { it.x == x && it.y == y }
            ?: error("Nuclear target is absent from the current server projection")
        submit { actions.launchNuclearStrike(unitId, x, y) }
    }

    suspend fun airSweep(unitId: Int, x: Int, y: Int) {
        requireUnit(unitId).airSweepTargets.singleOrNull { it.x == x && it.y == y }
            ?: error("Air-sweep target is absent from the current server projection")
        submit { actions.airSweep(unitId, x, y) }
    }

    suspend fun bombard(cityId: String, x: Int, y: Int) {
        val city = projection().ownCities.singleOrNull { it.id == cityId }
            ?: error("City is absent from the current server projection")
        city.bombardTargets.singleOrNull { it.x == x && it.y == y }
            ?: error("Bombard target is absent from the current server projection")
        submit { actions.bombard(cityId, x, y) }
    }

    private fun requireUnit(unitId: Int): ProjectedUnit =
        projection().ownUnits.singleOrNull { it.id == unitId }
            ?: error("Unit is absent from the current server projection")
}

data class AuthoritativeCombatActions(
    val attack: suspend (Int, Int, Int) -> AuthoritativeCommandOutcome?,
    val launchNuclearStrike: suspend (Int, Int, Int) -> AuthoritativeCommandOutcome?,
    val airSweep: suspend (Int, Int, Int) -> AuthoritativeCommandOutcome?,
    val bombard: suspend (String, Int, Int) -> AuthoritativeCommandOutcome?,
) {
    companion object {
        val Unavailable = AuthoritativeCombatActions(
            attack = { _, _, _ -> null },
            launchNuclearStrike = { _, _, _ -> null },
            airSweep = { _, _, _ -> null },
            bombard = { _, _, _ -> null },
        )
    }
}
