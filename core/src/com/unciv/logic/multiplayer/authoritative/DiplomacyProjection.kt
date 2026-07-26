package com.unciv.logic.multiplayer.authoritative

import com.unciv.logic.civilization.Civilization

/** Retains visible counterparts while exposing mutable diplomacy only on turn. */
internal object DiplomacyProjection {
    fun majorPartners(
        actor: Civilization,
        canIssueTurnCommands: Boolean,
    ): List<ProjectedDiplomacyPartner> =
        DiplomacyCommandExecutor.partners(actor).map {
            if (canIssueTurnCommands) it else it.copy(
                canDeclareWar = false,
                canDenounce = false,
                canOfferFriendship = false,
                availableDemands = emptyList(),
            )
        }

    fun prompts(
        actor: Civilization,
        canIssueTurnCommands: Boolean,
    ): List<ProjectedDiplomacyPrompt> =
        if (canIssueTurnCommands) DiplomacyCommandExecutor.prompts(actor)
        else emptyList()

    fun cityStatePartners(
        actor: Civilization,
        canIssueTurnCommands: Boolean,
    ): List<ProjectedCityStatePartner> =
        CityStateCommandExecutor.partners(actor).map {
            if (canIssueTurnCommands) it else it.copy(
                availableGoldGifts = emptyList(),
                canPledgeProtection = false,
                canRevokeProtection = false,
                tributeGoldAmount = null,
                canDemandWorker = false,
                improvementGifts = emptyList(),
                canNegotiatePeace = false,
                canDeclareWar = false,
                diplomaticMarriageCost = null,
            )
        }
}
