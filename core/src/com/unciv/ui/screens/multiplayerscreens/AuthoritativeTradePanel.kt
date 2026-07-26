package com.unciv.ui.screens.multiplayerscreens

import com.badlogic.gdx.scenes.scene2d.ui.Table
import com.badlogic.gdx.scenes.scene2d.ui.TextButton
import com.unciv.logic.multiplayer.authoritative.AuthoritativeTradeController
import com.unciv.logic.multiplayer.authoritative.PlayerProjection
import com.unciv.logic.multiplayer.authoritative.ProjectedTrade
import com.unciv.logic.multiplayer.authoritative.ProjectedTradeOffer
import com.unciv.logic.multiplayer.authoritative.ProjectedTradePartner
import com.unciv.ui.components.extensions.disable
import com.unciv.ui.components.extensions.toLabel
import com.unciv.ui.components.extensions.toTextButton
import com.unciv.ui.components.input.onClick
import com.unciv.ui.components.widgets.UncivTextField

/** Bounded quantity composition from exact projected bilateral trade offers. */
internal class AuthoritativeTradePanel(
    private val projection: PlayerProjection,
    private val controller: AuthoritativeTradeController,
    private val busy: Boolean,
    private val submit: (taskName: String, operation: suspend () -> Unit) -> Unit,
) {
    fun build(): Table = Table().apply {
        defaults().pad(3f)
        if (!projection.isCurrentTurn) return@apply
        for (partner in projection.tradePartners) {
            add("Trade with ${partner.civilizationId}".toLabel()).left().row()
            if (partner.hasPendingOutgoingOffer) {
                add(actionButton("Retract pending offer") {
                    controller.retract(partner.civilizationId)
                }).left().row()
            } else {
                add(composer(partner) { trade ->
                    controller.offer(partner.civilizationId, trade)
                }).left().row()
            }
        }
        for (request in projection.pendingTradeRequests) {
            add(
                (
                    "Trade request from ${request.requestingCivilizationId}: " +
                        request.trade.summary()
                ).toLabel(),
            ).left().row()
            add(actionButton("Accept trade") {
                controller.accept(request.requestId)
            })
            add(actionButton("Decline trade") {
                controller.decline(request.requestId)
            }).row()
            val partner = projection.tradePartners.singleOrNull {
                it.civilizationId == request.requestingCivilizationId
            }
            if (partner != null && !partner.hasPendingOutgoingOffer) {
                add(composer(partner, "Counteroffer") { trade ->
                    controller.counter(request.requestId, trade)
                }).left().row()
            }
        }
    }

    private fun composer(
        partner: ProjectedTradePartner,
        submitTitle: String = "Send trade offer",
        operation: suspend (ProjectedTrade) -> Unit,
    ): Table = Table().apply {
        val ours = offerInputs("We offer", partner.ourAvailableOffers)
        val theirs = offerInputs("We request", partner.theirAvailableOffers)
        add(actionButton(submitTitle) {
            operation(ProjectedTrade(ours.selected(), theirs.selected()))
        }).left().row()
    }

    private fun Table.offerInputs(
        title: String,
        offers: List<ProjectedTradeOffer>,
    ): List<OfferInput> {
        if (offers.isNotEmpty()) add("$title:".toLabel()).left().row()
        return offers.map { offer ->
            val amount = UncivTextField.Integer(
                "0-${offer.amount}",
                null,
            )
            add(
                "${offer.name} (${offer.type}, duration ${offer.duration}, " +
                    "max ${offer.amount})".toLabel(),
            ).left()
            add(amount).width(100f).row()
            OfferInput(offer, amount)
        }
    }

    private fun List<OfferInput>.selected(): List<ProjectedTradeOffer> =
        mapNotNull { input ->
            input.amount.intValue?.takeIf { it > 0 }?.let {
                input.offer.copy(amount = it)
            }
        }

    private fun ProjectedTrade.summary(): String =
        "offers [${ourOffers.joinToString { "${it.amount} ${it.name}" }}], " +
            "requests [${theirOffers.joinToString { "${it.amount} ${it.name}" }}]"

    private fun actionButton(
        title: String,
        operation: suspend () -> Unit,
    ): TextButton = title.toTextButton().apply {
        if (busy) disable()
        onClick { submit("Submit authoritative trade", operation) }
    }

    private data class OfferInput(
        val offer: ProjectedTradeOffer,
        val amount: UncivTextField.Integer,
    )
}
