package com.unciv.logic.multiplayer.authoritative

/** Projection-only boundary for trade composition and request decisions. */
class AuthoritativeTradeController internal constructor(
    private val projection: () -> PlayerProjection,
    private val submit: suspend (
        operation: suspend () -> AuthoritativeCommandOutcome?,
    ) -> Unit,
    private val actions: AuthoritativeTradeActions,
) {
    suspend fun offer(civilizationId: String, trade: ProjectedTrade) {
        val partner = requirePartner(civilizationId)
        require(!partner.hasPendingOutgoingOffer) {
            "A projected outgoing trade is already pending"
        }
        TradeProjectionValidation.requireValid(partner, trade)
        submit { actions.offer(civilizationId, trade) }
    }

    suspend fun retract(civilizationId: String) {
        require(requirePartner(civilizationId).hasPendingOutgoingOffer) {
            "Pending outgoing trade is absent from the current server projection"
        }
        submit { actions.retract(civilizationId) }
    }

    suspend fun accept(requestId: String) {
        requireRequest(requestId)
        submit { actions.accept(requestId) }
    }

    suspend fun decline(requestId: String) {
        requireRequest(requestId)
        submit { actions.decline(requestId) }
    }

    suspend fun counter(requestId: String, trade: ProjectedTrade) {
        val request = requireRequest(requestId)
        val partner = requirePartner(request.requestingCivilizationId)
        require(!partner.hasPendingOutgoingOffer) {
            "A projected counteroffer is already pending"
        }
        TradeProjectionValidation.requireValid(partner, trade)
        submit { actions.counter(requestId, trade) }
    }

    private fun requireRequest(requestId: String): ProjectedTradeRequest =
        requireCurrentTurn().pendingTradeRequests.singleOrNull {
            it.requestId == requestId
        } ?: error("Trade request is absent from the current server projection")

    private fun requirePartner(civilizationId: String): ProjectedTradePartner =
        requireCurrentTurn().tradePartners.singleOrNull {
            it.civilizationId == civilizationId
        } ?: error("Trade partner is absent from the current server projection")

    private fun requireCurrentTurn(): PlayerProjection {
        val current = projection()
        require(current.isCurrentTurn) {
            "Trade controls are unavailable outside the current turn"
        }
        return current
    }
}

data class AuthoritativeTradeActions(
    val offer: suspend (String, ProjectedTrade) -> AuthoritativeCommandOutcome?,
    val retract: suspend (String) -> AuthoritativeCommandOutcome?,
    val accept: suspend (String) -> AuthoritativeCommandOutcome?,
    val decline: suspend (String) -> AuthoritativeCommandOutcome?,
    val counter: suspend (String, ProjectedTrade) -> AuthoritativeCommandOutcome?,
) {
    companion object {
        val Unavailable = AuthoritativeTradeActions(
            offer = { _, _ -> null },
            retract = { null },
            accept = { null },
            decline = { null },
            counter = { _, _ -> null },
        )
    }
}
