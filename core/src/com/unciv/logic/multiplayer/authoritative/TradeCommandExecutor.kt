package com.unciv.logic.multiplayer.authoritative

import com.unciv.logic.GameInfo
import com.unciv.logic.civilization.Civilization
import com.unciv.logic.civilization.NotificationCategory
import com.unciv.logic.civilization.NotificationIcon
import com.unciv.logic.trade.Trade
import com.unciv.logic.trade.TradeLogic
import com.unciv.logic.trade.TradeOffer
import com.unciv.logic.trade.TradeOfferType
import com.unciv.logic.trade.TradeRequest
import java.security.MessageDigest

/** Executes bilateral trade intents exclusively against canonical worker state. */
object TradeCommandExecutor {
    fun availablePartners(actor: Civilization): List<ProjectedTradePartner> = actor.getKnownCivs()
        .asSequence()
        .filter { it.isMajorCiv() && !it.isDefeated() && it != actor }
        .map { other ->
            val logic = TradeLogic(actor, other)
            ProjectedTradePartner(
                other.civID,
                logic.ourAvailableOffers.map(::project),
                logic.theirAvailableOffers.map(::project),
                other.tradeRequests.any { it.requestingCiv == actor.civID },
            )
        }
        .sortedBy { it.civilizationId }
        .toList()

    fun pendingRequests(actor: Civilization): List<ProjectedTradeRequest> = actor.tradeRequests
        .map { request -> ProjectedTradeRequest(requestId(request), request.requestingCiv, project(request.trade)) }
        .sortedBy { it.requestId }

    fun offer(game: GameInfo, actor: Civilization, otherId: String, proposed: ProjectedTrade) {
        requireCurrentActor(game, actor)
        val other = partner(game, actor, otherId)
        require(other.tradeRequests.none { it.requestingCiv == actor.civID }) { "A trade offer is already pending for this civilization" }
        val logic = TradeLogic(actor, other)
        logic.currentTrade.set(validateTrade(proposed, logic))
        require(logic.currentTrade.ourOffers.isNotEmpty() || logic.currentTrade.theirOffers.isNotEmpty()) { "Trade must not be empty" }
        other.tradeRequests.add(TradeRequest(actor.civID, logic.currentTrade.reverse()))
        actor.cache.updateCivResources()
    }

    fun retract(game: GameInfo, actor: Civilization, otherId: String) {
        requireCurrentActor(game, actor)
        val other = partner(game, actor, otherId)
        require(other.tradeRequests.removeAll { it.requestingCiv == actor.civID }) { "No matching trade offer is pending" }
        actor.cache.updateCivResources()
    }

    fun accept(game: GameInfo, actor: Civilization, requestId: String) {
        requireCurrentActor(game, actor)
        val request = actor.tradeRequests.singleOrNull { requestId(it) == requestId }
            ?: error("Trade request is not pending for the authenticated civilization")
        val requester = partner(game, actor, request.requestingCiv)
        val logic = TradeLogic(actor, requester)
        logic.currentTrade.set(validateTrade(project(request.trade), logic))
        logic.acceptTrade()
        actor.tradeRequests.remove(request)
        requester.addNotification(
            "[${actor.civName}] has accepted your trade request",
            NotificationCategory.Trade,
            actor.civName,
            NotificationIcon.Trade,
        )
    }

    fun decline(game: GameInfo, actor: Civilization, requestId: String) {
        requireCurrentActor(game, actor)
        val request = actor.tradeRequests.singleOrNull { requestId(it) == requestId }
            ?: error("Trade request is not pending for the authenticated civilization")
        val requester = partner(game, actor, request.requestingCiv)
        request.decline(actor)
        actor.tradeRequests.remove(request)
        requester.addNotification(
            "[${actor.civName}] has denied your trade request",
            NotificationCategory.Trade,
            actor.civName,
            NotificationIcon.Trade,
        )
    }

    fun counter(game: GameInfo, actor: Civilization, requestId: String, proposed: ProjectedTrade) {
        requireCurrentActor(game, actor)
        val request = actor.tradeRequests.singleOrNull { requestId(it) == requestId }
            ?: error("Trade request is not pending for the authenticated civilization")
        val requester = partner(game, actor, request.requestingCiv)
        require(requester.tradeRequests.none { it.requestingCiv == actor.civID }) { "A counteroffer is already pending" }
        val logic = TradeLogic(actor, requester)
        logic.currentTrade.set(validateTrade(proposed, logic))
        require(logic.currentTrade.ourOffers.isNotEmpty() || logic.currentTrade.theirOffers.isNotEmpty()) { "Counteroffer must not be empty" }
        actor.tradeRequests.remove(request)
        requester.tradeRequests.add(TradeRequest(actor.civID, logic.currentTrade.reverse()))
        actor.cache.updateCivResources()
    }

    private fun validateTrade(proposed: ProjectedTrade, logic: TradeLogic): Trade {
        val trade = Trade()
        trade.ourOffers += validateOffers(proposed.ourOffers, logic.ourAvailableOffers)
        trade.theirOffers += validateOffers(proposed.theirOffers, logic.theirAvailableOffers)
        return trade
    }

    private fun validateOffers(proposed: List<ProjectedTradeOffer>, available: List<TradeOffer>): List<TradeOffer> {
        require(proposed.distinctBy { it.name to it.type }.size == proposed.size) {
            "Duplicate trade offer identity"
        }
        return proposed.map { candidate ->
            val type = TradeOfferType.valueOf(candidate.type)
            val source = available.singleOrNull {
                it.name == candidate.name &&
                    it.type == type &&
                    it.duration == candidate.duration
            }
                ?: error("Trade offer is not available in canonical state")
            require(candidate.amount > 0 && candidate.amount <= source.amount) { "Trade offer amount exceeds canonical availability" }
            source.copy(amount = candidate.amount)
        }
    }

    private fun requireCurrentActor(game: GameInfo, actor: Civilization) =
        require(game.currentPlayer == actor.civID) { "Authenticated actor cannot trade outside their turn" }

    private fun partner(game: GameInfo, actor: Civilization, id: String): Civilization {
        val other = game.civilizations.singleOrNull { it.civID == id } ?: error("Unknown trade partner")
        require(other != actor && actor.knows(other) && other.isMajorCiv() && !other.isDefeated()) { "Civilization is not an available trade partner" }
        return other
    }

    private fun project(trade: Trade) = ProjectedTrade(trade.ourOffers.map(::project), trade.theirOffers.map(::project))
    private fun project(offer: TradeOffer) = ProjectedTradeOffer(offer.name, offer.type.name, offer.amount, offer.duration)

    private fun requestId(request: TradeRequest): String {
        val canonical = buildString {
            append(request.requestingCiv)
            for (offer in request.trade.ourOffers.sortedWith(compareBy<TradeOffer> { it.type.name }.thenBy { it.name }.thenBy { it.amount }))
                append("|o:").append(offer.type.name).append(':').append(offer.name).append(':').append(offer.amount).append(':').append(offer.duration)
            for (offer in request.trade.theirOffers.sortedWith(compareBy<TradeOffer> { it.type.name }.thenBy { it.name }.thenBy { it.amount }))
                append("|t:").append(offer.type.name).append(':').append(offer.name).append(':').append(offer.amount).append(':').append(offer.duration)
        }
        return MessageDigest.getInstance("SHA-256").digest(canonical.toByteArray()).joinToString("") { "%02x".format(it) }
    }
}
