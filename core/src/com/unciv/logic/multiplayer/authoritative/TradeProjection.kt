package com.unciv.logic.multiplayer.authoritative

import com.unciv.logic.civilization.Civilization

/** Retains trade counterparts while exposing mutable offers only on turn. */
internal object TradeProjection {
    fun partners(
        actor: Civilization,
        canIssueTurnCommands: Boolean,
    ): List<ProjectedTradePartner> =
        TradeCommandExecutor.availablePartners(actor).map {
            if (canIssueTurnCommands) it else it.copy(
                ourAvailableOffers = emptyList(),
                theirAvailableOffers = emptyList(),
                hasPendingOutgoingOffer = false,
            )
        }

    fun pendingRequests(
        actor: Civilization,
        canIssueTurnCommands: Boolean,
    ): List<ProjectedTradeRequest> =
        if (canIssueTurnCommands) TradeCommandExecutor.pendingRequests(actor)
        else emptyList()
}
