package com.unciv.ui.screens.diplomacyscreen

import com.badlogic.gdx.scenes.scene2d.ui.Table
import com.unciv.Constants
import com.unciv.UncivGame
import com.unciv.logic.civilization.Civilization
import com.unciv.logic.multiplayer.authoritative.AuthoritativeCommandOutcome
import com.unciv.logic.multiplayer.authoritative.AuthoritativeMultiplayerSession
import com.unciv.logic.multiplayer.authoritative.ProjectedTrade
import com.unciv.logic.multiplayer.authoritative.ProjectedTradeOffer
import com.unciv.logic.trade.TradeLogic
import com.unciv.logic.trade.TradeRequest
import com.unciv.logic.trade.TradeOfferType
import com.unciv.models.translations.tr
import com.unciv.ui.components.extensions.isEnabled
import com.unciv.ui.components.extensions.toTextButton
import com.unciv.ui.components.input.onClick
import com.unciv.ui.screens.basescreen.BaseScreen
import com.unciv.ui.popups.ToastPopup
import com.unciv.utils.Concurrency

class TradeTable(
    private val civ: Civilization,
    private val otherCivilization: Civilization,
    private val diplomacyScreen: DiplomacyScreen
): Table(BaseScreen.skin) {
    private val authoritative = civ.gameInfo.gameParameters.isOnlineMultiplayer &&
        UncivGame.Current.onlineMultiplayer.authoritativeSession?.isGameOpen(civ.gameInfo.gameId) == true
    internal val tradeLogic = TradeLogic(civ, otherCivilization)
    internal val offerColumnsTable = OfferColumnsTable(tradeLogic, diplomacyScreen , civ, otherCivilization) { onChange() }
    // This is so that after a trade has been traded, we can switch out the offersToDisplay to start anew - this is the easiest way
    private val offerColumnsTableWrapper = Table()

    val offerTradeText = "{Offer trade}\n({They'll decide on their turn})"
    private val offerButton = offerTradeText.toTextButton()

    private fun isTradeOffered() = otherCivilization.tradeRequests.any { it.requestingCiv == civ.civID }

    private fun retractOffer() {
        if (authoritative && isTradeOffered()) {
            submitAuthoritative("retract trade offer") { it.retractTradeOfferIfOpen(civ.gameInfo.gameId, otherCivilization.civID) }
            return
        }
        if (authoritative) return
        otherCivilization.tradeRequests.removeAll { it.requestingCiv == civ.civID }
        civ.cache.updateCivResources()
        offerButton.setText(offerTradeText.tr())
    }

    init {
        offerColumnsTableWrapper.add(offerColumnsTable)
        add(offerColumnsTableWrapper).row()

        val lowerTable = Table().apply { defaults().pad(10f) }

        val existingOffer = otherCivilization.tradeRequests.firstOrNull { it.requestingCiv == civ.civID }
        if (existingOffer != null) {
            tradeLogic.currentTrade.set(existingOffer.trade.reverse())
            offerColumnsTable.update()
        }

        if (isTradeOffered()) offerButton.setText("Retract offer".tr())
        else offerButton.apply { isEnabled = false }.setText(offerTradeText.tr())

        offerButton.onClick {
            if (isTradeOffered()) {
                retractOffer()
                return@onClick
            }
            // If there is a research agreement trade, make sure both civilizations should be able to pay for it.
            // If not lets add an extra gold offer to satisfy this.
            // There must be enough gold to add to the offer to satisfy this, otherwise the research agreement button would be disabled
            if (tradeLogic.currentTrade.ourOffers.any { it.name == Constants.researchAgreement}) {
                val researchCost = civ.diplomacyFunctions.getResearchAgreementCost(otherCivilization)
                val currentPlayerOfferedGold = tradeLogic.currentTrade.ourOffers.firstOrNull { it.type == TradeOfferType.Gold }?.amount ?: 0
                val otherCivOfferedGold = tradeLogic.currentTrade.theirOffers.firstOrNull { it.type == TradeOfferType.Gold }?.amount ?: 0
                val newCurrentPlayerGold = civ.gold + otherCivOfferedGold - researchCost
                val newOtherCivGold = otherCivilization.gold + currentPlayerOfferedGold - researchCost
                // Check if we require more gold from them
                if (newCurrentPlayerGold < 0) {
                    offerColumnsTable.addOffer( tradeLogic.theirAvailableOffers.first { it.type == TradeOfferType.Gold }
                            .copy(amount = -newCurrentPlayerGold), tradeLogic.currentTrade.theirOffers, tradeLogic.currentTrade.ourOffers)
                }
                // Check if they require more gold from us
                if (newOtherCivGold < 0) {
                    offerColumnsTable.addOffer( tradeLogic.ourAvailableOffers.first { it.type == TradeOfferType.Gold }
                            .copy(amount = -newOtherCivGold), tradeLogic.currentTrade.ourOffers, tradeLogic.currentTrade.theirOffers)
                }
            }

            if (authoritative) {
                val trade = ProjectedTrade(
                    tradeLogic.currentTrade.ourOffers.map { ProjectedTradeOffer(it.name, it.type.name, it.amount, it.duration) },
                    tradeLogic.currentTrade.theirOffers.map { ProjectedTradeOffer(it.name, it.type.name, it.amount, it.duration) },
                )
                submitAuthoritative(if (diplomacyScreen.selectTrade == null) "offer trade" else "counter trade") { session ->
                    if (diplomacyScreen.selectTrade == null)
                        session.offerTradeIfOpen(civ.gameInfo.gameId, otherCivilization.civID, trade)
                    else {
                        val request = session.projectionIfOpen(civ.gameInfo.gameId)?.pendingTradeRequests?.singleOrNull {
                            it.requestingCivilizationId == otherCivilization.civID
                        } ?: error("Projected trade request no longer matches this counteroffer")
                        session.counterTradeIfOpen(civ.gameInfo.gameId, request.requestId, trade)
                    }
                }
                return@onClick
            }
            otherCivilization.tradeRequests.add(TradeRequest(civ.civID, tradeLogic.currentTrade.reverse()))
            civ.cache.updateCivResources()
            offerButton.setText("Retract offer".tr())
        }

        lowerTable.add(offerButton)

        lowerTable.pack()
        lowerTable.y = 10f
        add(lowerTable)
        pack()
    }

    private fun submitAuthoritative(
        label: String,
        submit: suspend (AuthoritativeMultiplayerSession) -> AuthoritativeCommandOutcome?,
    ) {
        offerButton.isEnabled = false
        Concurrency.runOnNonDaemonThreadPool("Authoritative $label") {
            val outcome = try {
                val session = UncivGame.Current.onlineMultiplayer.authoritativeSession
                    ?: return@runOnNonDaemonThreadPool
                submit(session)
            } catch (ex: Exception) {
                Concurrency.runOnGLThread {
                    offerButton.isEnabled = true
                    ToastPopup("Could not submit $label: [${ex.message ?: "Unknown"}]", diplomacyScreen)
                }
                return@runOnNonDaemonThreadPool
            }
            Concurrency.runOnGLThread {
                civ.gameInfo.isUpToDate = false
                offerButton.isEnabled = true
                val message = if (outcome is AuthoritativeCommandOutcome.Accepted)
                    "$label committed by the authoritative server"
                else "$label synchronized with the authoritative server"
                ToastPopup(message, diplomacyScreen)
            }
        }
    }

    private fun onChange() {
        offerColumnsTable.update()
        retractOffer()
        offerButton.isEnabled = !(tradeLogic.currentTrade.theirOffers.size == 0 && tradeLogic.currentTrade.ourOffers.size == 0)
    }

    fun enableOfferButton(isEnabled: Boolean) {
        offerButton.isEnabled = isEnabled
    }
}
