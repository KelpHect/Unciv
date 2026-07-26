package com.unciv.logic.multiplayer.authoritative

/**
 * Projection-only world state and input boundary.
 *
 * It never constructs or accepts GameInfo. Every actionable destination comes
 * from the current player projection and every mutation returns a replacement
 * server projection.
 */
class AuthoritativeWorldController(
    initial: ApiV3GameProjection,
    private val refreshProjection: suspend () -> ApiV3GameProjection,
    private val moveUnit:
        suspend (unitId: Int, x: Int, y: Int) -> AuthoritativeCommandOutcome?,
    private val endTurn: suspend () -> AuthoritativeCommandOutcome?,
) {
    var current: ApiV3GameProjection = initial
        private set
    var selectedUnitId: Int? = null
        private set
    var status: AuthoritativeWorldStatus = AuthoritativeWorldStatus.Synchronized
        private set

    init {
        validateProjection(initial)
    }

    val projection: PlayerProjection
        get() = current.projection

    fun selectUnit(unitId: Int) {
        require(projection.ownUnits.any { it.id == unitId }) {
            "Unit is absent from the current server projection"
        }
        selectedUnitId = unitId
        status = AuthoritativeWorldStatus.Synchronized
    }

    fun selectedUnit(): ProjectedUnit? =
        selectedUnitId?.let { id -> projection.ownUnits.singleOrNull { it.id == id } }

    fun canMoveSelectedTo(x: Int, y: Int): Boolean =
        selectedUnit()?.moveDestinations?.any { it.x == x && it.y == y } == true

    fun canEndTurn(): Boolean =
        projection.isCurrentTurn && projection.pendingTurnActions.isEmpty()

    suspend fun refresh() {
        status = AuthoritativeWorldStatus.Refreshing
        try {
            replaceProjection(refreshProjection())
        } catch (exception: Exception) {
            status = AuthoritativeWorldStatus.Rejected("refresh_failed")
            throw exception
        }
    }

    suspend fun moveSelectedTo(x: Int, y: Int) {
        val unit = requireNotNull(selectedUnit()) { "Select a projected unit first" }
        require(canMoveSelectedTo(x, y)) {
            "Destination is absent from the selected unit's server projection"
        }
        status = AuthoritativeWorldStatus.Submitting
        applyOutcome(requireNotNull(moveUnit(unit.id, x, y)) {
            "The authoritative game is no longer open"
        })
    }

    suspend fun submitEndTurn() {
        require(canEndTurn()) {
            "Resolve every projected end-turn requirement before ending the turn"
        }
        status = AuthoritativeWorldStatus.Submitting
        applyOutcome(requireNotNull(endTurn()) {
            "The authoritative game is no longer open"
        })
    }

    private fun applyOutcome(outcome: AuthoritativeCommandOutcome) {
        when (outcome) {
            is AuthoritativeCommandOutcome.Accepted -> replaceProjection(outcome.current)
            is AuthoritativeCommandOutcome.StaleRefreshed -> {
                replaceProjection(outcome.current)
                status = AuthoritativeWorldStatus.StaleRefreshed
            }
            is AuthoritativeCommandOutcome.Rejected ->
                status = AuthoritativeWorldStatus.Rejected(outcome.code)
            AuthoritativeCommandOutcome.RetryRequired ->
                status = AuthoritativeWorldStatus.RetryRequired
        }
    }

    private fun replaceProjection(replacement: ApiV3GameProjection) {
        validateProjection(replacement)
        require(replacement.gameId == current.gameId) {
            "Server projection changed game identity"
        }
        require(replacement.committedRevision >= current.committedRevision) {
            "Server projection revision moved backwards"
        }
        require(
            replacement.committedRevision != current.committedRevision ||
                replacement.canonicalStateHash == current.canonicalStateHash,
        ) {
            "Server projection changed the canonical hash without a revision"
        }
        current = replacement
        if (selectedUnit() == null) selectedUnitId = null
        status = AuthoritativeWorldStatus.Synchronized
    }

    private fun validateProjection(value: ApiV3GameProjection) {
        require(value.projectionVersion == PlayerProjection.CURRENT_PROJECTION_VERSION) {
            "Unsupported player projection version"
        }
        require(value.gameId.isNotBlank()) { "Projection game ID must not be blank" }
        require(value.committedRevision >= 0) { "Projection revision must not be negative" }
        require(value.canonicalStateHash.isNotBlank()) {
            "Projection canonical hash must not be blank"
        }
        require(value.projectionHash.isNotBlank()) { "Projection hash must not be blank" }
    }
}

sealed interface AuthoritativeWorldStatus {
    data object Synchronized : AuthoritativeWorldStatus
    data object Refreshing : AuthoritativeWorldStatus
    data object Submitting : AuthoritativeWorldStatus
    data object RetryRequired : AuthoritativeWorldStatus
    data object StaleRefreshed : AuthoritativeWorldStatus
    data class Rejected(val code: String) : AuthoritativeWorldStatus
}
