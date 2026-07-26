package com.unciv.logic.multiplayer.authoritative

import com.unciv.logic.GameInfo
import com.unciv.logic.civilization.AlertType
import com.unciv.logic.civilization.Civilization
import com.unciv.logic.civilization.PopupAlert
import com.unciv.logic.civilization.NotificationCategory
import com.unciv.logic.civilization.diplomacy.Demand
import com.unciv.logic.civilization.diplomacy.DeclareWarReason
import com.unciv.logic.civilization.diplomacy.DiplomacyFlags
import com.unciv.logic.civilization.diplomacy.WarType
import com.unciv.models.ruleset.unique.UniqueType

/** Major-civilization diplomacy intents executed only by the canonical worker. */
object DiplomacyCommandExecutor {
    fun partners(actor: Civilization): List<ProjectedDiplomacyPartner> = actor.getKnownCivs()
        .asSequence()
        .filter { it.isMajorCiv() && !it.isDefeated() && it != actor }
        .map { other ->
            val diplomacy = actor.getDiplomacyManager(other)!!
            val peaceful = !actor.isAtWarWith(other)
            val relationshipsCanChange = !actor.gameInfo.ruleset.modOptions
                .hasUnique(UniqueType.DiplomaticRelationshipsCannotChange)
            ProjectedDiplomacyPartner(
                civilizationId = other.civID,
                adoptedPolicyBranches = other.policies.getAdoptedPolicies().asSequence()
                    .filter(other.gameInfo.ruleset.policyBranches::containsKey)
                    .sorted()
                    .toList(),
                canDeclareWar = relationshipsCanChange && diplomacy.canDeclareWar(),
                canDenounce = peaceful && !diplomacy.hasFlag(DiplomacyFlags.Denunciation) &&
                    !diplomacy.hasFlag(DiplomacyFlags.DeclarationOfFriendship),
                canOfferFriendship = peaceful && !diplomacy.hasFlag(DiplomacyFlags.DeclarationOfFriendship) &&
                    other.popupAlerts.none { it.type == AlertType.DeclarationOfFriendship && it.value == actor.civID },
                availableDemands = Demand.entries.asSequence()
                    .filter { it.show(actor) }
                    .filter { !diplomacy.hasFlag(it.agreedToDemand) }
                    .filter { demand -> other.popupAlerts.none { it.type == demand.demandAlert && it.value == actor.civID } }
                    .map { DiplomaticDemand.valueOf(it.name) }
                    .toList(),
            )
        }
        .sortedBy { it.civilizationId }
        .toList()

    fun prompts(actor: Civilization): List<ProjectedDiplomacyPrompt> = actor.popupAlerts.mapNotNull { alert ->
        when (alert.type) {
            AlertType.DeclarationOfFriendship -> ProjectedDiplomacyPrompt(
                promptId(alert), alert.value, DiplomacyPromptType.Friendship, null,
            )
            AlertType.BulliedProtectedMinor, AlertType.AttackedProtectedMinor, AlertType.AttackedAllyMinor ->
                protectionPrompt(actor, alert)
            else -> Demand.entries.singleOrNull { it.demandAlert == alert.type }?.let { demand ->
                ProjectedDiplomacyPrompt(
                    promptId(alert), alert.value, DiplomacyPromptType.Demand,
                    DiplomaticDemand.valueOf(demand.name),
                )
            }
        }
    }.sortedBy { it.promptId }

    private fun protectionPrompt(actor: Civilization, alert: PopupAlert): ProjectedDiplomacyPrompt? {
        val ids = alert.value.split('@')
        if (ids.size != 2) return null
        val attacker = actor.gameInfo.civilizations.singleOrNull { it.civID == ids[0] } ?: return null
        val cityState = actor.gameInfo.civilizations.singleOrNull { it.civID == ids[1] } ?: return null
        if (attacker.isDefeated() || !attacker.isMajorCiv() || !cityState.isCityState) return null
        val responses = buildList {
            if (!actor.gameInfo.ruleset.modOptions.hasUnique(UniqueType.DiplomaticRelationshipsCannotChange) &&
                !actor.isAtWarWith(attacker) && actor.getDiplomacyManager(attacker)?.canDeclareWar() == true)
                add(CityStateProtectionResponse.DeclareWar)
            add(CityStateProtectionResponse.Condemn)
            add(CityStateProtectionResponse.WithdrawProtection)
        }
        val type = when (alert.type) {
            AlertType.BulliedProtectedMinor -> DiplomacyPromptType.BulliedProtectedMinor
            AlertType.AttackedProtectedMinor -> DiplomacyPromptType.AttackedProtectedMinor
            else -> DiplomacyPromptType.AttackedAllyMinor
        }
        return ProjectedDiplomacyPrompt(promptId(alert), attacker.civID, type, null, cityState.civID, responses)
    }

    fun declareWar(game: GameInfo, actor: Civilization, otherId: String) {
        requireCurrentActor(game, actor)
        val other = game.civilizations.singleOrNull { it.civID == otherId }
            ?: error("Unknown diplomatic counterpart")
        require(other != actor && (other.isMajorCiv() || other.isCityState) && !other.isDefeated() && actor.knows(other)) {
            "Civilization is not an available war counterpart"
        }
        val diplomacy = actor.getDiplomacyManager(other)!!
        require(!game.ruleset.modOptions.hasUnique(UniqueType.DiplomaticRelationshipsCannotChange)) {
            "Diplomatic relationships cannot change in this game"
        }
        require(diplomacy.canDeclareWar()) { "War declaration is not legal in canonical state" }
        diplomacy.declareWar()
    }

    fun denounce(game: GameInfo, actor: Civilization, otherId: String) {
        requireCurrentActor(game, actor)
        val other = partner(game, actor, otherId)
        val diplomacy = actor.getDiplomacyManager(other)!!
        require(partners(actor).any { it.civilizationId == otherId && it.canDenounce }) {
            "Denouncement is not legal in canonical state"
        }
        diplomacy.denounce()
    }

    fun offerFriendship(game: GameInfo, actor: Civilization, otherId: String) {
        requireCurrentActor(game, actor)
        val other = partner(game, actor, otherId)
        require(partners(actor).any { it.civilizationId == otherId && it.canOfferFriendship }) {
            "Friendship offer is not legal in canonical state"
        }
        other.popupAlerts.add(PopupAlert(AlertType.DeclarationOfFriendship, actor.civID))
    }

    fun makeDemand(game: GameInfo, actor: Civilization, otherId: String, demand: DiplomaticDemand) {
        requireCurrentActor(game, actor)
        val other = partner(game, actor, otherId)
        require(partners(actor).single { it.civilizationId == otherId }.availableDemands.contains(demand)) {
            "Diplomatic demand is not legal in canonical state"
        }
        val canonical = Demand.valueOf(demand.name)
        other.popupAlerts.add(PopupAlert(canonical.demandAlert, actor.civID))
    }

    fun respond(game: GameInfo, actor: Civilization, promptId: String, accept: Boolean) {
        requireCurrentActor(game, actor)
        val alert = actor.popupAlerts.singleOrNull { promptId(it) == promptId }
            ?: error("Diplomatic prompt is not pending for the authenticated civilization")
        val other = partner(game, actor, alert.value)
        val diplomacy = actor.getDiplomacyManager(other)!!
        when (alert.type) {
            AlertType.DeclarationOfFriendship -> if (accept) diplomacy.signDeclarationOfFriendship()
                else diplomacy.otherCivDiplomacy().setFlag(DiplomacyFlags.DeclinedDeclarationOfFriendship, 20)
            else -> {
                val demand = Demand.entries.singleOrNull { it.demandAlert == alert.type }
                    ?: error("Diplomatic prompt type is not actionable")
                if (accept) diplomacy.agreeToDemand(demand)
                else {
                    diplomacy.refuseDemand(demand)
                    if (demand == Demand.DoNotAttackUs) {
                        require(diplomacy.canDeclareWar()) { "Refusing this ultimatum cannot currently declare war" }
                        diplomacy.declareWar()
                    }
                }
            }
        }
        actor.popupAlerts.remove(alert)
    }

    fun respondToCityStateProtectionPrompt(game: GameInfo, actor: Civilization, promptId: String, response: CityStateProtectionResponse) {
        requireCurrentActor(game, actor)
        val projected = prompts(actor).singleOrNull { it.promptId == promptId && response in it.availableCityStateResponses }
            ?: error("City-state protection response is not available in canonical state")
        val alert = actor.popupAlerts.single { promptId(it) == promptId }
        val attacker = partner(game, actor, projected.requestingCivilizationId)
        val cityState = game.civilizations.single { it.civID == projected.cityStateCivilizationId && it.isCityState }
        when (response) {
            CityStateProtectionResponse.DeclareWar -> {
                actor.getDiplomacyManager(attacker)!!.sideWithCityState()
                val reason = if (alert.type == AlertType.AttackedAllyMinor) WarType.AlliedCityStateWar else WarType.ProtectedCityStateWar
                actor.getDiplomacyManager(attacker)!!.declareWar(DeclareWarReason(reason, cityState))
                cityState.getDiplomacyManager(actor)!!.addInfluence(20f)
            }
            CityStateProtectionResponse.Condemn -> actor.getDiplomacyManager(attacker)!!.sideWithCityState()
            CityStateProtectionResponse.WithdrawProtection -> {
                actor.addNotification("You have broken your Pledge to Protect [${cityState.civName}]!",
                    cityState.cityStateFunctions.getNotificationActions(), NotificationCategory.Diplomacy, cityState.civName)
                cityState.cityStateFunctions.removeProtectorCiv(actor, forced = true)
            }
        }
        actor.popupAlerts.remove(alert)
    }

    private fun diplomacyWith(game: GameInfo, actor: Civilization, otherId: String) =
        actor.getDiplomacyManager(partner(game, actor, otherId))!!

    private fun partner(game: GameInfo, actor: Civilization, otherId: String): Civilization {
        val other = game.civilizations.singleOrNull { it.civID == otherId }
            ?: error("Unknown diplomatic counterpart")
        require(other != actor && other.isMajorCiv() && !other.isDefeated() && actor.knows(other)) {
            "Civilization is not an available diplomatic counterpart"
        }
        return other
    }

    private fun requireCurrentActor(game: GameInfo, actor: Civilization) =
        require(game.currentPlayer == actor.civID) { "Authenticated actor cannot perform diplomacy outside their turn" }

    private fun promptId(alert: PopupAlert): String =
        "${alert.type.name}:${alert.value}".toByteArray().let { bytes ->
            java.security.MessageDigest.getInstance("SHA-256").digest(bytes)
                .joinToString("") { "%02x".format(it) }
        }
}
