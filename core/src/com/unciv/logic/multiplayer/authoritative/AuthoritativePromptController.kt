package com.unciv.logic.multiplayer.authoritative

/**
 * Projection-only boundary for blocking votes/selections and exact popup choices.
 */
class AuthoritativePromptController internal constructor(
    private val projection: () -> PlayerProjection,
    private val submit: suspend (
        operation: suspend () -> AuthoritativeCommandOutcome?,
    ) -> Unit,
    private val actions: AuthoritativePromptActions,
) {
    suspend fun castDiplomaticVote(candidateCivilizationId: String?) {
        val current = projection()
        require(PendingEndTurnAction.CastDiplomaticVote in current.pendingTurnActions) {
            "Diplomatic vote is absent from the current server projection"
        }
        require(
            candidateCivilizationId == null ||
                candidateCivilizationId in current.diplomaticVoteCandidates,
        ) {
            "Diplomatic vote candidate is absent from the current server projection"
        }
        submit { actions.castDiplomaticVote(candidateCivilizationId) }
    }

    suspend fun chooseGreatPerson(unitName: String) {
        val current = projection()
        require(
            PendingEndTurnAction.PickGreatPerson in current.pendingTurnActions &&
                unitName in current.selectableGreatPeople,
        ) {
            "Great person is absent from the current server projection"
        }
        submit { actions.chooseGreatPerson(unitName) }
    }

    suspend fun resolveEvent(promptId: String, choiceId: String) {
        require(projection().eventPrompts.any { prompt ->
            prompt.promptId == promptId && prompt.choices.any { it.choiceId == choiceId }
        }) {
            "Event choice is absent from the current server projection"
        }
        submit { actions.resolveEvent(promptId, choiceId) }
    }

    suspend fun respondToDiplomacy(promptId: String, accept: Boolean) {
        val prompt = projection().diplomacyPrompts.singleOrNull {
            it.promptId == promptId
        } ?: error("Diplomatic prompt is absent from the current server projection")
        require(
            prompt.type == DiplomacyPromptType.Friendship ||
                prompt.type == DiplomacyPromptType.Demand,
        ) {
            "Diplomatic prompt requires a projected city-state response"
        }
        submit { actions.respondToDiplomacy(promptId, accept) }
    }

    suspend fun respondToCityState(
        promptId: String,
        response: CityStateProtectionResponse,
    ) {
        val prompt = projection().diplomacyPrompts.singleOrNull {
            it.promptId == promptId
        } ?: error("Diplomatic prompt is absent from the current server projection")
        require(response in prompt.availableCityStateResponses) {
            "City-state response is absent from the current server projection"
        }
        submit { actions.respondToCityState(promptId, response) }
    }
}

data class AuthoritativePromptActions(
    val castDiplomaticVote: suspend (String?) -> AuthoritativeCommandOutcome?,
    val chooseGreatPerson: suspend (String) -> AuthoritativeCommandOutcome?,
    val resolveEvent: suspend (String, String) -> AuthoritativeCommandOutcome?,
    val respondToDiplomacy: suspend (String, Boolean) -> AuthoritativeCommandOutcome?,
    val respondToCityState:
        suspend (String, CityStateProtectionResponse) -> AuthoritativeCommandOutcome?,
) {
    companion object {
        val Unavailable = AuthoritativePromptActions(
            castDiplomaticVote = { null },
            chooseGreatPerson = { null },
            resolveEvent = { _, _ -> null },
            respondToDiplomacy = { _, _ -> null },
            respondToCityState = { _, _ -> null },
        )
    }
}
