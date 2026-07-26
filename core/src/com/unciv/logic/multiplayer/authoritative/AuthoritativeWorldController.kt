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
    private val setResearch:
        suspend (technologyName: String, append: Boolean) -> AuthoritativeCommandOutcome? =
        { _, _ -> null },
    private val manageResearch:
        suspend (
            technologyName: String,
            queueIndex: Int,
            action: ResearchQueueAction,
        ) -> AuthoritativeCommandOutcome? = { _, _, _ -> null },
    private val adoptPolicy: suspend (policyName: String) -> AuthoritativeCommandOutcome? =
        { null },
    private val chooseFreeTechnology:
        suspend (technologyName: String) -> AuthoritativeCommandOutcome? = { null },
    private val acknowledgeResearchCompletion:
        suspend (promptId: String) -> AuthoritativeCommandOutcome? = { null },
    cityEconomyActions: AuthoritativeCityEconomyActions =
        AuthoritativeCityEconomyActions.Unavailable,
    cityControlActions: AuthoritativeCityControlActions =
        AuthoritativeCityControlActions.Unavailable,
    combatActions: AuthoritativeCombatActions =
        AuthoritativeCombatActions.Unavailable,
    unitActionActions: AuthoritativeUnitActions =
        AuthoritativeUnitActions.Unavailable,
    unitOrderActions: AuthoritativeUnitOrderActions =
        AuthoritativeUnitOrderActions.Unavailable,
    promptActions: AuthoritativePromptActions =
        AuthoritativePromptActions.Unavailable,
    spyActions: AuthoritativeSpyActions =
        AuthoritativeSpyActions.Unavailable,
    religionAction: suspend (
        beliefNames: List<String>,
        religionIconName: String?,
        religionDisplayName: String?,
    ) -> AuthoritativeCommandOutcome? = { _, _, _ -> null },
    diplomacyActions: AuthoritativeDiplomacyActions =
        AuthoritativeDiplomacyActions.Unavailable,
    tradeActions: AuthoritativeTradeActions =
        AuthoritativeTradeActions.Unavailable,
) {
    var current: ApiV3GameProjection = initial
        private set
    var selectedUnitId: Int? = null
        private set
    var unitTargetMode: AuthoritativeUnitTargetMode? = null
        private set
    var status: AuthoritativeWorldStatus = AuthoritativeWorldStatus.Synchronized
        private set

    init {
        validateProjection(initial)
    }

    val projection: PlayerProjection
        get() = current.projection

    val cityEconomy = AuthoritativeCityEconomyController(
        projection = { projection },
        submit = ::submit,
        actions = cityEconomyActions,
    )
    val cityControls = AuthoritativeCityControlController(
        projection = { projection },
        submit = ::submit,
        actions = cityControlActions,
    )
    val combat = AuthoritativeCombatController(
        projection = { projection },
        submit = ::submit,
        actions = combatActions,
    )
    val unitActions = AuthoritativeUnitActionController(
        projection = { projection },
        submit = ::submit,
        actions = unitActionActions,
    )
    val unitOrders = AuthoritativeUnitOrderController(
        projection = { projection },
        submit = ::submit,
        actions = unitOrderActions,
    )
    val prompts = AuthoritativePromptController(
        projection = { projection },
        submit = ::submit,
        actions = promptActions,
    )
    val spies = AuthoritativeSpyController(
        projection = { projection },
        submit = ::submit,
        actions = spyActions,
    )
    val religion = AuthoritativeReligionController(
        projection = { projection },
        submit = ::submit,
        action = religionAction,
    )
    val diplomacy = AuthoritativeDiplomacyController(
        projection = { projection },
        submit = ::submit,
        actions = diplomacyActions,
    )
    val trade = AuthoritativeTradeController(
        projection = { projection },
        submit = ::submit,
        actions = tradeActions,
    )

    fun selectUnit(unitId: Int) {
        require(projection.ownUnits.any { it.id == unitId }) {
            "Unit is absent from the current server projection"
        }
        selectedUnitId = unitId
        unitTargetMode = null
        status = AuthoritativeWorldStatus.Synchronized
    }

    fun selectedUnit(): ProjectedUnit? =
        selectedUnitId?.let { id -> projection.ownUnits.singleOrNull { it.id == id } }

    fun canMoveSelectedTo(x: Int, y: Int): Boolean =
        selectedUnit()?.moveDestinations?.any { it.x == x && it.y == y } == true

    fun canEndTurn(): Boolean =
        projection.isCurrentTurn && projection.pendingTurnActions.isEmpty()

    fun beginUnitTargetSelection(mode: AuthoritativeUnitTargetMode) {
        val unit = requireNotNull(selectedUnit()) { "Select a projected unit first" }
        val available = when (mode) {
            AuthoritativeUnitTargetMode.MoveToward -> unit.moveTowardDestinations
            AuthoritativeUnitTargetMode.RoadConnection -> unit.availableRoadDestinations
        }
        require(available.isNotEmpty()) {
            "Target mode has no choices in the current server projection"
        }
        unitTargetMode = mode
        status = AuthoritativeWorldStatus.Synchronized
    }

    fun cancelUnitTargetSelection() {
        unitTargetMode = null
    }

    fun canSubmitUnitTarget(x: Int, y: Int): Boolean {
        val unit = selectedUnit() ?: return false
        val choices = when (unitTargetMode) {
            AuthoritativeUnitTargetMode.MoveToward -> unit.moveTowardDestinations
            AuthoritativeUnitTargetMode.RoadConnection -> unit.availableRoadDestinations
            null -> return false
        }
        return choices.any { it.x == x && it.y == y }
    }

    suspend fun submitUnitTarget(x: Int, y: Int) {
        require(canSubmitUnitTarget(x, y)) {
            "Target is absent from the selected unit's server projection"
        }
        val unitId = requireNotNull(selectedUnitId)
        when (requireNotNull(unitTargetMode)) {
            AuthoritativeUnitTargetMode.MoveToward ->
                unitOrders.moveToward(unitId, x, y)
            AuthoritativeUnitTargetMode.RoadConnection ->
                unitOrders.setRoadOrder(unitId, x, y)
        }
        unitTargetMode = null
    }

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

    suspend fun selectResearch(technologyName: String, append: Boolean) {
        val targets = if (append) projection.research.appendableTargets
            else projection.research.selectableTargets
        require(technologyName in targets) {
            "Technology is absent from the current server projection"
        }
        submit {
            setResearch(technologyName, append)
        }
    }

    suspend fun manageResearchQueue(
        technologyName: String,
        queueIndex: Int,
        action: ResearchQueueAction,
    ) {
        val entry = projection.research.queueEntries.getOrNull(queueIndex)
        require(entry?.technologyName == technologyName && action in entry.availableActions) {
            "Research queue action is absent from the current server projection"
        }
        submit {
            manageResearch(technologyName, queueIndex, action)
        }
    }

    suspend fun adoptProjectedPolicy(policyName: String) {
        require(policyName in projection.policies.selectablePolicies) {
            "Policy is absent from the current server projection"
        }
        submit {
            adoptPolicy(policyName)
        }
    }

    suspend fun chooseProjectedFreeTechnology(technologyName: String) {
        require(technologyName in projection.research.freeTechnologyChoices) {
            "Free technology is absent from the current server projection"
        }
        submit {
            chooseFreeTechnology(technologyName)
        }
    }

    suspend fun acknowledgeProjectedResearchCompletion(promptId: String) {
        require(projection.research.completionPrompts.any { it.promptId == promptId }) {
            "Research completion is absent from the current server projection"
        }
        submit {
            acknowledgeResearchCompletion(promptId)
        }
    }

    private suspend fun submit(operation: suspend () -> AuthoritativeCommandOutcome?) {
        status = AuthoritativeWorldStatus.Submitting
        applyOutcome(requireNotNull(operation()) {
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
        unitTargetMode = null
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

enum class AuthoritativeUnitTargetMode {
    MoveToward,
    RoadConnection,
}

sealed interface AuthoritativeWorldStatus {
    data object Synchronized : AuthoritativeWorldStatus
    data object Refreshing : AuthoritativeWorldStatus
    data object Submitting : AuthoritativeWorldStatus
    data object RetryRequired : AuthoritativeWorldStatus
    data object StaleRefreshed : AuthoritativeWorldStatus
    data class Rejected(val code: String) : AuthoritativeWorldStatus
}
