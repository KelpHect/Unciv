package com.unciv.logic.multiplayer.authoritative

/** Validates a composed trade strictly against one projected partner view. */
internal object TradeProjectionValidation {
    fun requireValid(partner: ProjectedTradePartner, trade: ProjectedTrade) {
        require(trade.ourOffers.isNotEmpty() || trade.theirOffers.isNotEmpty()) {
            "Trade must not be empty"
        }
        requireOffers(trade.ourOffers, partner.ourAvailableOffers)
        requireOffers(trade.theirOffers, partner.theirAvailableOffers)
    }

    private fun requireOffers(
        proposed: List<ProjectedTradeOffer>,
        available: List<ProjectedTradeOffer>,
    ) {
        require(proposed.size <= MAX_OFFERS_PER_SIDE) {
            "Trade contains too many offers"
        }
        require(proposed.distinctBy { it.name to it.type }.size == proposed.size) {
            "Trade contains a duplicate offer identity"
        }
        for (candidate in proposed) {
            val source = available.singleOrNull {
                it.name == candidate.name &&
                    it.type == candidate.type &&
                    it.duration == candidate.duration
            } ?: throw IllegalArgumentException(
                "Trade offer is absent from the current server projection",
            )
            require(candidate.amount in 1..source.amount) {
                "Trade offer amount exceeds projected availability"
            }
        }
    }

    private const val MAX_OFFERS_PER_SIDE = 200
}
