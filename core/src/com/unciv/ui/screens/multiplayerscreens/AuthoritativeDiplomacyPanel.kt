package com.unciv.ui.screens.multiplayerscreens

import com.badlogic.gdx.scenes.scene2d.ui.Table
import com.badlogic.gdx.scenes.scene2d.ui.TextButton
import com.unciv.logic.multiplayer.authoritative.AuthoritativeDiplomacyController
import com.unciv.logic.multiplayer.authoritative.PlayerProjection
import com.unciv.models.ruleset.Ruleset
import com.unciv.ui.components.extensions.disable
import com.unciv.ui.components.extensions.toLabel
import com.unciv.ui.components.extensions.toTextButton
import com.unciv.ui.components.input.onClick

/**
 * Renders only worker-advertised diplomacy capabilities and exact inputs.
 *
 * [ruleset] supplies presentation identity - leader names and portraits -
 * for the server's civilization IDs; it never decides anything.
 */
internal class AuthoritativeDiplomacyPanel(
    private val projection: PlayerProjection,
    private val controller: AuthoritativeDiplomacyController,
    private val busy: Boolean,
    private val submit: (taskName: String, operation: suspend () -> Unit) -> Unit,
    private val ruleset: Ruleset? = null,
) {
    /** Leader display name for a server civilization ID, or the raw ID. */
    private fun nationName(civilizationId: String): String =
        ruleset?.nations?.get(civilizationId)?.getLeaderDisplayName() ?: civilizationId

    private fun Table.partnerHeader(civilizationId: String, caption: String? = null) {
        val row = Table()
        if (ruleset != null)
            row.add(LobbyChrome.nationBadge(ruleset, civilizationId, 34f)).left()
        row.add(
            (
                (caption?.plus(" ") ?: "") + nationName(civilizationId)
                ).toLabel(hideIcons = true),
        ).left().padLeft(6f)
        add(row).left().row()
    }

    fun build(): Table = Table().apply {
        defaults().pad(3f)
        if (!projection.isCurrentTurn) return@apply
        for (partner in projection.diplomacyPartners) {
            partnerHeader(partner.civilizationId)
            add(
                (
                    "relationship: " +
                        partner.relationshipLevel.name.replaceFirstChar(Char::lowercase) +
                        "  •  opinion of us: ${partner.opinionOfUs}"
                    ).toLabel(LobbyChrome.muted),
            ).left().padLeft(40f).row()
            partner.peaceTreatyCooldownTurns?.let { turns ->
                if (turns > 0) add(
                    "Peace treaty possible in $turns turns".toLabel(LobbyChrome.muted),
                ).left().padLeft(40f).row()
            }
            // The classic screen's modifier breakdown and promise table.
            for (modifier in partner.modifiersTowardUs.sortedByDescending { it.amount }) {
                add(
                    (
                        (if (modifier.amount >= 0) "+" else "") +
                            "${modifier.amount} ${modifier.label}"
                        ).toLabel(
                        if (modifier.amount >= 0) LobbyChrome.ready else LobbyChrome.danger,
                    ),
                ).left().padLeft(40f).row()
            }
            for (promise in partner.promisesTheyMadeUs)
                add(
                    "Promised us: [${promise.name}] (${requireNotNull(partner.theyPromiseTurns[promise])} turns)"
                        .toLabel(LobbyChrome.muted),
                ).left().padLeft(40f).row()
            for (promise in partner.promisesWeMadeThem)
                add(
                    (
                        "We promised: [${promise.name}] (${requireNotNull(partner.wePromiseTurns[promise])} turns)"
                        ).toLabel(LobbyChrome.muted),
                ).left().padLeft(40f).row()
            add(
                (
                    "policies: " +
                        partner.adoptedPolicyBranches.joinToString().ifEmpty { "none" }
                    ).toLabel(),
            ).left().padLeft(40f).row()
            if (partner.canDeclareWar) add(actionButton("Declare war") {
                controller.declareWar(partner.civilizationId)
            }).left().row()
            if (partner.canDenounce) add(actionButton("Denounce") {
                controller.denounce(partner.civilizationId)
            }).left().row()
            if (partner.canOfferFriendship) add(actionButton("Offer friendship") {
                controller.offerFriendship(partner.civilizationId)
            }).left().row()
            for (demand in partner.availableDemands) {
                add(actionButton("Demand: ${demand.name}") {
                    controller.makeDemand(partner.civilizationId, demand)
                }).left().row()
            }
        }
        for (partner in projection.cityStatePartners) {
            partnerHeader(partner.civilizationId, "City-state:")
            add(
                ("influence: " + partner.influenceLevel).toLabel(LobbyChrome.accent)
            ).left().padLeft(40f).row()
            if (partner.canDeclareWar) add(actionButton("Declare war") {
                controller.declareWar(partner.civilizationId)
            }).left().row()
            for (amount in partner.availableGoldGifts) {
                add(actionButton("Gift $amount gold") {
                    controller.giftGold(partner.civilizationId, amount)
                }).left().row()
            }
            if (partner.canPledgeProtection) add(actionButton("Pledge protection") {
                controller.setProtection(partner.civilizationId, true)
            }).left().row()
            if (partner.canRevokeProtection) add(actionButton("Revoke protection") {
                controller.setProtection(partner.civilizationId, false)
            }).left().row()
            partner.tributeGoldAmount?.let { amount ->
                add(actionButton("Demand $amount gold tribute") {
                    controller.demandTribute(partner.civilizationId, false)
                }).left().row()
            }
            if (partner.canDemandWorker) add(actionButton("Demand worker tribute") {
                controller.demandTribute(partner.civilizationId, true)
            }).left().row()
            for (gift in partner.improvementGifts) {
                add(actionButton(
                    "Gift ${gift.improvementName} at ${gift.x},${gift.y} " +
                        "(${gift.goldCost} gold)",
                ) {
                    controller.giftImprovement(
                        partner.civilizationId,
                        gift.x,
                        gift.y,
                        gift.improvementName,
                    )
                }).left().row()
            }
            if (partner.canNegotiatePeace) add(actionButton("Negotiate peace") {
                controller.negotiatePeace(partner.civilizationId)
            }).left().row()
            partner.diplomaticMarriageCost?.let { cost ->
                add(actionButton("Diplomatic marriage ($cost gold)") {
                    controller.marry(partner.civilizationId)
                }).left().row()
            }
        }
    }

    private fun actionButton(
        title: String,
        operation: suspend () -> Unit,
    ): TextButton = title.toTextButton().apply {
        if (busy) disable()
        onClick { submit("Submit authoritative diplomacy", operation) }
    }
}
