package com.unciv.logic.multiplayer.authoritative

/** Projection-only boundary for major-civilization and city-state diplomacy. */
class AuthoritativeDiplomacyController internal constructor(
    private val projection: () -> PlayerProjection,
    private val submit: suspend (
        operation: suspend () -> AuthoritativeCommandOutcome?,
    ) -> Unit,
    private val actions: AuthoritativeDiplomacyActions,
) {
    suspend fun declareWar(civilizationId: String) {
        val current = requireCurrentTurn()
        require(
            current.diplomacyPartners.any {
                it.civilizationId == civilizationId && it.canDeclareWar
            } ||
                current.cityStatePartners.any {
                    it.civilizationId == civilizationId && it.canDeclareWar
                },
        ) { "War declaration is absent from the current server projection" }
        submit { actions.declareWar(civilizationId) }
    }

    suspend fun denounce(civilizationId: String) {
        requireMajor(civilizationId).also {
            require(it.canDenounce) {
                "Denunciation is absent from the current server projection"
            }
        }
        submit { actions.denounce(civilizationId) }
    }

    suspend fun offerFriendship(civilizationId: String) {
        requireMajor(civilizationId).also {
            require(it.canOfferFriendship) {
                "Friendship offer is absent from the current server projection"
            }
        }
        submit { actions.offerFriendship(civilizationId) }
    }

    suspend fun makeDemand(civilizationId: String, demand: DiplomaticDemand) {
        require(demand in requireMajor(civilizationId).availableDemands) {
            "Diplomatic demand is absent from the current server projection"
        }
        submit { actions.makeDemand(civilizationId, demand) }
    }

    suspend fun giftGold(cityStateId: String, amount: Int) {
        require(amount in requireCityState(cityStateId).availableGoldGifts) {
            "City-state gold gift is absent from the current server projection"
        }
        submit { actions.giftGold(cityStateId, amount) }
    }

    suspend fun setProtection(cityStateId: String, protect: Boolean) {
        val partner = requireCityState(cityStateId)
        require(if (protect) partner.canPledgeProtection else partner.canRevokeProtection) {
            "City-state protection action is absent from the current server projection"
        }
        submit { actions.setProtection(cityStateId, protect) }
    }

    suspend fun demandTribute(cityStateId: String, worker: Boolean) {
        val partner = requireCityState(cityStateId)
        require(if (worker) partner.canDemandWorker else partner.tributeGoldAmount != null) {
            "City-state tribute is absent from the current server projection"
        }
        submit { actions.demandTribute(cityStateId, worker) }
    }

    suspend fun giftImprovement(
        cityStateId: String,
        x: Int,
        y: Int,
        improvementName: String,
    ) {
        require(requireCityState(cityStateId).improvementGifts.any {
            it.x == x && it.y == y && it.improvementName == improvementName
        }) { "City-state improvement gift is absent from the current server projection" }
        submit { actions.giftImprovement(cityStateId, x, y, improvementName) }
    }

    suspend fun negotiatePeace(cityStateId: String) {
        require(requireCityState(cityStateId).canNegotiatePeace) {
            "City-state peace is absent from the current server projection"
        }
        submit { actions.negotiatePeace(cityStateId) }
    }

    suspend fun marry(cityStateId: String) {
        require(requireCityState(cityStateId).diplomaticMarriageCost != null) {
            "Diplomatic marriage is absent from the current server projection"
        }
        submit { actions.marry(cityStateId) }
    }

    private fun requireMajor(civilizationId: String): ProjectedDiplomacyPartner =
        requireCurrentTurn().diplomacyPartners.singleOrNull {
            it.civilizationId == civilizationId
        } ?: error("Diplomatic partner is absent from the current server projection")

    private fun requireCityState(civilizationId: String): ProjectedCityStatePartner =
        requireCurrentTurn().cityStatePartners.singleOrNull {
            it.civilizationId == civilizationId
        } ?: error("City-state is absent from the current server projection")

    private fun requireCurrentTurn(): PlayerProjection {
        val current = projection()
        require(current.isCurrentTurn) {
            "Diplomacy controls are unavailable outside the current turn"
        }
        return current
    }
}

data class AuthoritativeDiplomacyActions(
    val declareWar: suspend (String) -> AuthoritativeCommandOutcome?,
    val denounce: suspend (String) -> AuthoritativeCommandOutcome?,
    val offerFriendship: suspend (String) -> AuthoritativeCommandOutcome?,
    val makeDemand: suspend (String, DiplomaticDemand) -> AuthoritativeCommandOutcome?,
    val giftGold: suspend (String, Int) -> AuthoritativeCommandOutcome?,
    val setProtection: suspend (String, Boolean) -> AuthoritativeCommandOutcome?,
    val demandTribute: suspend (String, Boolean) -> AuthoritativeCommandOutcome?,
    val giftImprovement:
        suspend (String, Int, Int, String) -> AuthoritativeCommandOutcome?,
    val negotiatePeace: suspend (String) -> AuthoritativeCommandOutcome?,
    val marry: suspend (String) -> AuthoritativeCommandOutcome?,
) {
    companion object {
        val Unavailable = AuthoritativeDiplomacyActions(
            declareWar = { null },
            denounce = { null },
            offerFriendship = { null },
            makeDemand = { _, _ -> null },
            giftGold = { _, _ -> null },
            setProtection = { _, _ -> null },
            demandTribute = { _, _ -> null },
            giftImprovement = { _, _, _, _ -> null },
            negotiatePeace = { null },
            marry = { null },
        )
    }
}
