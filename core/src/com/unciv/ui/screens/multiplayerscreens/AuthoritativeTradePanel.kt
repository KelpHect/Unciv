package com.unciv.ui.screens.multiplayerscreens

import com.badlogic.gdx.scenes.scene2d.ui.Table
import com.unciv.logic.multiplayer.authoritative.AuthoritativeTradeController
import com.unciv.logic.multiplayer.authoritative.PlayerProjection
import com.unciv.logic.multiplayer.authoritative.ProjectedTrade
import com.unciv.logic.multiplayer.authoritative.ProjectedTradeOffer
import com.unciv.logic.multiplayer.authoritative.ProjectedTradePartner
import com.unciv.models.translations.tr
import com.unciv.ui.components.extensions.disable
import com.unciv.ui.components.extensions.toLabel
import com.unciv.ui.components.extensions.toTextButton
import com.unciv.ui.components.input.onClick
import com.unciv.ui.images.ImageGetter

/**
 * The classic trade table, driven by the projection: two offer columns - what
 * we give and what we ask - composed by clicking pooled offers, with counter-
 * offers on incoming requests. Every commit stays a typed command; the draft
 * lives on the hosting screen so projection refreshes never wipe composition.
 */
internal class AuthoritativeTradePanel(
    private val projection: PlayerProjection,
    private val controller: AuthoritativeTradeController,
    private val busy: Boolean,
    private val drafts: MutableMap<String, Draft>,
    private val submit: (taskName: String, operation: suspend () -> Unit) -> Unit,
) {
    /** One partner's in-progress composition, surviving projection refreshes. */
    class Draft {
        val ourOffers = mutableListOf<ProjectedTradeOffer>()
        val theirOffers = mutableListOf<ProjectedTradeOffer>()
    }

    fun build(): Table = Table().apply {
        defaults().pad(3f)
        if (!projection.isCurrentTurn) return@apply
        for (partner in projection.tradePartners) {
            add(partnerTable(partner)).growX().row()
        }
        for (request in projection.pendingTradeRequests) {
            add(requestTable(request)).growX().row()
        }
    }

    private fun draftFor(civilizationId: String) =
        drafts.getOrPut(civilizationId) { Draft() }

    private fun partnerTable(partner: ProjectedTradePartner): Table {
        val draft = draftFor(partner.civilizationId)
        return LobbyChrome.row().apply {
            defaults().pad(3f)
            add(LobbyChrome.caption("Trade")).left().row()

            val columns = Table().apply { defaults().pad(3f) }
            columns.add(offerColumn("We offer", partner.ourAvailableOffers, draft.ourOffers))
                .growX().uniformX()
            columns.add(offerColumn("We ask", partner.theirAvailableOffers, draft.theirOffers))
                .growX().uniformX()
            add(columns).growX().row()

            if (partner.hasPendingOutgoingOffer) {
                add(LobbyChrome.hint("An offer is waiting for their answer."))
                    .left().row()
                add(actionButton("Retract pending offer") {
                    controller.retract(partner.civilizationId)
                }).left().row()
            } else {
                add(actionButton("Send trade offer") {
                    controller.offer(
                        partner.civilizationId,
                        ProjectedTrade(draft.ourOffers.toList(), draft.theirOffers.toList()),
                    )
                }).left().row()
            }
        }
    }

    private fun requestTable(request: com.unciv.logic.multiplayer.authoritative.ProjectedTradeRequest): Table {
        val partner = projection.tradePartners.singleOrNull {
            it.civilizationId == request.requestingCivilizationId
        }
        return LobbyChrome.row().apply {
            defaults().pad(3f)
            add(LobbyChrome.caption("They propose")).left().row()
            add(tradeSummary(request.trade)).growX().left().row()
            val actions = Table().apply { defaults().pad(3f) }
            actions.add(actionButton("Accept") { controller.accept(request.requestId) })
            actions.add(actionButton("Decline") { controller.decline(request.requestId) })
            add(actions).left().row()

            if (partner != null && !partner.hasPendingOutgoingOffer) {
                val draft = draftFor("counter-" + request.requestId)
                val columns = Table().apply { defaults().pad(3f) }
                columns.add(offerColumn("We offer", partner.ourAvailableOffers, draft.ourOffers))
                    .growX().uniformX()
                columns.add(offerColumn("We ask", partner.theirAvailableOffers, draft.theirOffers))
                    .growX().uniformX()
                add(columns).growX().row()
                add(actionButton("Send counter-offer") {
                    controller.counter(
                        request.requestId,
                        ProjectedTrade(draft.ourOffers.toList(), draft.theirOffers.toList()),
                    )
                }).left().row()
            }
        }
    }

    /** One side of the composition: chosen offers on top, pool underneath. */
    private fun offerColumn(
        title: String,
        pool: List<ProjectedTradeOffer>,
        chosen: MutableList<ProjectedTradeOffer>,
    ): Table = Table().apply {
        defaults().pad(2f)
        add(LobbyChrome.hint(title)).left().row()

        for ((index, offer) in chosen.withIndex()) {
            val row = Table().apply { defaults().pad(1f) }
            row.add(offerLabel(offer)).left()
            val minus = "−".toTextButton()
            minus.onClick {
                if (offer.amount > 1) chosen[index] = offer.copy(amount = offer.amount - 1)
            }
            val plus = "+".toTextButton()
            plus.onClick {
                val max = pool.firstOrNull { it.name == offer.name }?.amount ?: offer.amount
                if (offer.amount < max) chosen[index] = offer.copy(amount = offer.amount + 1)
            }
            val remove = "✕".toTextButton()
            remove.onClick { chosen.removeAt(index) }
            if (busy) {
                minus.disable(); plus.disable(); remove.disable()
            }
            row.add(minus).width(34f)
            row.add(plus).width(34f)
            row.add(remove).width(34f)
            add(row).growX().left().row()
        }

        for (pooled in pool.sortedBy { it.name }) {
            val alreadyChosen = chosen.filter { it.name == pooled.name }.sumOf { it.amount }
            val remaining = pooled.amount - alreadyChosen
            if (remaining <= 0) continue
            val row = Table().apply { defaults().pad(1f) }
            row.add(offerLabel(pooled.copy(amount = remaining))).left()
            val addOne = "+".toTextButton()
            addOne.onClick { chosen.add(pooled.copy(amount = 1)) }
            if (busy) addOne.disable()
            row.add(addOne).width(34f)
            add(row).growX().left().row()
        }
        if (pool.isEmpty()) add(LobbyChrome.hint("Nothing available")).left().row()
    }

    private fun offerLabel(offer: ProjectedTradeOffer) =
        (
            "${offer.amount} ${offer.name.tr(hideIcons = false)}" +
                if (offer.duration > 0) " (${offer.duration})" else ""
            ).toLabel(hideIcons = true)

    private fun tradeSummary(trade: ProjectedTrade): Table = Table().apply {
        defaults().pad(2f)
        add(
            (
                "They give: [" +
                    trade.theirOffers.joinToString { "${it.amount} ${it.name}" }.ifEmpty { "nothing" } +
                    "]"
                ).toLabel(),
        ).growX().left().row()
        add(
            (
                "They want: [" +
                    trade.ourOffers.joinToString { "${it.amount} ${it.name}" }.ifEmpty { "nothing" } +
                    "]"
                ).toLabel(),
        ).growX().left().row()
    }

    private fun actionButton(
        title: String,
        operation: suspend () -> Unit,
    ): com.badlogic.gdx.scenes.scene2d.ui.TextButton = title.toTextButton().apply {
        if (busy) disable()
        onClick { submit("Submit authoritative trade", operation) }
    }
}
