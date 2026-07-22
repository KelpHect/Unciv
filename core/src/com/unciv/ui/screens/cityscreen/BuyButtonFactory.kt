package com.unciv.ui.screens.cityscreen

import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.scenes.scene2d.ui.TextButton
import com.unciv.Constants
import com.unciv.GUI
import com.unciv.logic.city.CityConstructions
import com.unciv.logic.multiplayer.authoritative.AuthoritativeCommandOutcome
import com.unciv.logic.map.tile.Tile
import com.unciv.models.Religion
import com.unciv.models.ruleset.Building
import com.unciv.models.ruleset.IConstruction
import com.unciv.models.ruleset.INonPerpetualConstruction
import com.unciv.models.ruleset.PerpetualConstruction
import com.unciv.models.ruleset.unique.UniqueType
import com.unciv.models.stats.Stat
import com.unciv.models.translations.tr
import com.unciv.ui.audio.SoundPlayer
import com.unciv.ui.components.UncivTooltip.Companion.addTooltip
import com.unciv.ui.components.extensions.disable
import com.unciv.ui.components.extensions.isEnabled
import com.unciv.ui.components.extensions.toTextButton
import com.unciv.ui.components.input.KeyboardBinding
import com.unciv.ui.components.input.onActivation
import com.unciv.ui.popups.Popup
import com.unciv.ui.popups.ToastPopup
import com.unciv.ui.popups.closeAllPopups
import com.unciv.ui.screens.basescreen.BaseScreen
import com.unciv.utils.Concurrency
import kotlinx.coroutines.CancellationException

/**
 * This class handles everything related to buying constructions. This includes
 * showing and handling [ConfirmBuyPopup] and the actual purchase in [purchaseConstruction].
 */
class BuyButtonFactory(val cityScreen: CityScreen) {

    private var preferredBuyStat = Stat.Gold  // Used for keyboard buy
    private var authoritativePurchaseSubmissionInProgress = false

    fun hasBuyButtons(construction: IConstruction?): Boolean = getBuyButtons(construction).isNotEmpty()
    
    fun getBuyButtons(construction: IConstruction?): List<TextButton> {
        val selection = cityScreen.selectedConstruction!=null || cityScreen.selectedQueueEntry >= 0
        if (selection && construction != null && construction !is PerpetualConstruction)
            return Stat.statsUsableToBuy.mapNotNull {
                getBuyButton(construction as INonPerpetualConstruction, it)
            }
        return emptyList()
    }

    private fun getBuyButton(construction: INonPerpetualConstruction?, stat: Stat = Stat.Gold): TextButton? {
        if (stat !in Stat.statsUsableToBuy || construction == null)
            return null

        val city = cityScreen.city
        if (cityScreen.isAuthoritativeGame())
            return getAuthoritativeBuyButton(construction, stat)
        val button = "".toTextButton()

        if (!isConstructionPurchaseShown(construction, stat)) {
            // This can't ever be bought with the given currency.
            // We want one disabled "buy" button without a price for "priceless" buildings such as wonders
            // We don't want such a button when the construction can be bought using a different currency
            if (stat != Stat.Gold || construction.canBePurchasedWithAnyStat(city))
                return null
            button.setText("Buy".tr())
            button.disable()
        } else {
            val constructionBuyCost = construction.getStatBuyCost(city, stat)!!
            button.setText("Buy".tr() + " " + constructionBuyCost.tr() + stat.character)

            button.onActivation(binding = KeyboardBinding.BuyConstruction) {
                button.disable()
                buyButtonOnClick(construction, stat)
            }
            // allow puppets, since isConstructionPurchaseAllowed handles that and exceptions to that rule
            button.isEnabled = cityScreen.canChangeState &&
                city.cityConstructions.isConstructionPurchaseAllowed(construction, stat, constructionBuyCost)
            preferredBuyStat = stat  // Not very intelligent, but the least common currency "wins"
            if (city.cityConstructions.isConstructionPurchaseBlockedByUnit(construction)) {
                button.addTooltip("Move unit out of city first", 26f, false)
            }
        }

        button.labelCell.pad(5f)

        return button
    }

    private fun getAuthoritativeBuyButton(
        construction: INonPerpetualConstruction,
        stat: Stat,
    ): TextButton? {
        val queueIndex = cityScreen.selectedQueueEntry.takeIf { it >= 0 }
        val purchase = cityScreen.authoritativeProjectedPurchase(
            construction.name, stat.name, queueIndex,
        ) ?: return null
        preferredBuyStat = stat
        return ("Buy".tr() + " " + purchase.cost.tr() + stat.character).toTextButton().apply {
            onActivation(binding = KeyboardBinding.BuyConstruction) {
                disable()
                buyButtonOnClick(construction, stat)
            }
            isEnabled = cityScreen.canChangeState && purchase.allowed
            labelCell.pad(5f)
        }
    }

    private fun buyButtonOnClick(construction: INonPerpetualConstruction, stat: Stat = preferredBuyStat) {
        if (cityScreen.isAuthoritativeGame()) {
            val queueIndex = cityScreen.selectedQueueEntry.takeIf { it >= 0 }
            val purchase = cityScreen.authoritativeProjectedPurchase(
                construction.name, stat.name, queueIndex,
            ) ?: return
            if (!purchase.allowed) return
            if (!purchase.requiresTile) return askToBuyConstruction(construction, stat)
            if (queueIndex == null)
                return (construction as? Building)?.let {
                    cityScreen.startPickTileForCreatesOneImprovement(it, stat, true)
                } ?: Unit
            val target = purchase.legalTargets.singleOrNull() ?: return
            val tile = cityScreen.city.civ.gameInfo.tileMap.getIfTileExistsOrNull(target.x, target.y)
                ?: return
            return askToBuyConstruction(construction, stat, tile)
        }
        if (construction !is Building || !construction.hasCreateOneImprovementUnique())
            return askToBuyConstruction(construction, stat)
        if (cityScreen.selectedQueueEntry < 0)
            return cityScreen.startPickTileForCreatesOneImprovement(construction, stat, true)
        // Buying a UniqueType.CreatesOneImprovement building from queue must pass down
        // the already selected tile, otherwise a new one is chosen from Automation code.
        val improvement = construction.getImprovementToCreate(
            cityScreen.city.getRuleset(), cityScreen.city.civ)!!
        val tileForImprovement = cityScreen.city.cityConstructions.getTileForImprovement(improvement.name)
        askToBuyConstruction(construction, stat, tileForImprovement)
    }

    /** Ask whether user wants to buy [construction] for [stat].
     *
     * Used from onClick and keyboard dispatch, thus only minimal parameters are passed,
     * and it needs to do all checks and the sound as appropriate.
     */
    fun askToBuyConstruction(
        construction: INonPerpetualConstruction,
        stat: Stat = preferredBuyStat,
        tile: Tile? = null
    ) {
        val city = cityScreen.city
        val queueIndex = cityScreen.selectedQueueEntry.takeIf { it >= 0 }
        val projectedPurchase = if (cityScreen.isAuthoritativeGame())
            cityScreen.authoritativeProjectedPurchase(construction.name, stat.name, queueIndex)
        else null
        if (cityScreen.isAuthoritativeGame()) {
            if (projectedPurchase?.allowed != true) return
            if (projectedPurchase.requiresTile != (tile != null)) return
            if (tile != null && projectedPurchase.legalTargets.none {
                    it.x == tile.position.x && it.y == tile.position.y
                }) return
        } else {
            if (!isConstructionPurchaseShown(construction, stat)) return
            val constructionStatBuyCost = construction.getStatBuyCost(city, stat)!!
            if (!city.cityConstructions.isConstructionPurchaseAllowed(
                    construction, stat, constructionStatBuyCost,
                )) return
        }
        val constructionStatBuyCost = projectedPurchase?.cost
            ?: construction.getStatBuyCost(city, stat)!!
        val availableAmount = projectedPurchase?.availableAmount ?: city.getStatReserve(stat)

        cityScreen.closeAllPopups()
        ConfirmBuyPopup(construction, stat, constructionStatBuyCost, availableAmount, tile)
    }

    private inner class ConfirmBuyPopup(
        construction: INonPerpetualConstruction,
        stat: Stat,
        constructionStatBuyCost: Int,
        availableAmount: Int,
        tile: Tile?
    ) : Popup(cityScreen.stage) {
        init {
            val city = cityScreen.city
            val balance = availableAmount
            val majorityReligion = city.religion.getMajorityReligion()
            val yourReligion = city.civ.religionManager.religion
            val isBuyingWithFaithForForeignReligion = construction.hasUnique(UniqueType.ReligiousUnit)
                && !construction.hasUnique(UniqueType.TakeReligionOverBirthCity)
                && majorityReligion != yourReligion

            addGoodSizedLabel("Currently you have [$balance] [${stat.name}].").padBottom(10f).row()
            if (isBuyingWithFaithForForeignReligion) {
                // Earlier tests should forbid this Popup unless both religions are non-null, but to be safe:
                fun Religion?.getName() = this?.getReligionDisplayName() ?: Constants.unknownCityName
                addGoodSizedLabel("You are buying a religious unit in a city that doesn't follow the religion you founded ([${yourReligion.getName()}]). " +
                    "This means that the unit is tied to that foreign religion ([${majorityReligion.getName()}]) and will be less useful.").row()
                addGoodSizedLabel("Are you really sure you want to purchase this unit?", Constants.headingFontSize).run {
                    actor.color = Color.FIREBRICK
                    padBottom(10f)
                    row()
                }
            }
            addGoodSizedLabel("Would you like to purchase [${construction.name}] for [$constructionStatBuyCost] [${stat.character}]?").row()

            addCloseButton(Constants.cancel, KeyboardBinding.Cancel) { cityScreen.update() }
            val confirmStyle = BaseScreen.skin.get("positive", TextButton.TextButtonStyle::class.java)
            addOKButton("Purchase", KeyboardBinding.Confirm, confirmStyle) {
                purchaseConstruction(construction, stat, tile)
            }
            equalizeLastTwoButtonWidths()
            open(true)
        }
    }

    /** This tests whether the buy button should be _shown_ */
    private fun isConstructionPurchaseShown(construction: INonPerpetualConstruction, stat: Stat): Boolean {
        val city = cityScreen.city
        return construction.canBePurchasedWithStat(city, stat)
    }

    /** Called only by askToBuyConstruction's Yes answer - not to be confused with [CityConstructions.purchaseConstruction]
     * @param tile supports [UniqueType.CreatesOneImprovement]
     */
    private fun purchaseConstruction(
        construction: INonPerpetualConstruction,
        stat: Stat = Stat.Gold,
        tile: Tile? = null
    ) {
        if (cityScreen.isAuthoritativeGame()) {
            submitAuthoritativePurchase(construction, stat, tile)
            return
        }
        SoundPlayer.play(stat.purchaseSound)
        val city = cityScreen.city
        if (!city.cityConstructions.purchaseConstruction(construction, cityScreen.selectedQueueEntry, false, stat, tile)) {
            Popup(cityScreen).apply {
                add("No space available to place [${construction.name}] near [${city.name}]".tr()).row()
                addCloseButton()
                open()
            }
            return
        }
        if (cityScreen.selectedQueueEntry>=0 || cityScreen.selectedConstruction?.isBuildable(city.cityConstructions) != true) {
            cityScreen.selectedQueueEntry = -1
            cityScreen.clearSelection()

            // Allow buying next queued or auto-assigned construction right away
            city.cityConstructions.chooseNextConstruction()
            if (city.cityConstructions. currentConstructionName().isNotEmpty()) {
                val newConstruction = city.cityConstructions.getCurrentConstruction()
                if (newConstruction is INonPerpetualConstruction)
                    cityScreen.selectConstruction(newConstruction)
            }
        }
        cityScreen.city.reassignPopulation()
        cityScreen.update()
    }

    private fun submitAuthoritativePurchase(
        construction: INonPerpetualConstruction,
        stat: Stat,
        tile: Tile? = null,
    ) {
        if (authoritativePurchaseSubmissionInProgress) return
        authoritativePurchaseSubmissionInProgress = true
        val city = cityScreen.city
        val queueIndex = cityScreen.selectedQueueEntry.takeIf { it >= 0 }
        Concurrency.runOnNonDaemonThreadPool("Purchase authoritative construction") {
            val outcome = try {
                val session = cityScreen.game.onlineMultiplayer.authoritativeSession
                if (tile == null)
                    session?.purchaseConstructionIfOpen(
                        city.civ.gameInfo.gameId, city.id, construction.name, stat.name, queueIndex,
                    )
                else
                    session?.purchaseConstructionAtTileIfOpen(
                        city.civ.gameInfo.gameId, city.id, construction.name, stat.name,
                        tile.position.x, tile.position.y, queueIndex,
                    )
            } catch (ex: Exception) {
                if (ex is CancellationException) throw ex
                Concurrency.runOnGLThread {
                    authoritativePurchaseSubmissionInProgress = false
                    ToastPopup("Could not submit purchase: [${ex.message ?: "Unknown"}]", cityScreen)
                }
                return@runOnNonDaemonThreadPool
            }
            Concurrency.runOnGLThread {
                when (outcome) {
                    is AuthoritativeCommandOutcome.Accepted -> {
                        SoundPlayer.play(stat.purchaseSound)
                        city.civ.gameInfo.isUpToDate = false
                        cityScreen.game.popScreen()
                        ToastPopup("Purchase committed by the authoritative server", GUI.getWorldScreen())
                    }
                    is AuthoritativeCommandOutcome.StaleRefreshed -> {
                        city.civ.gameInfo.isUpToDate = false
                        cityScreen.game.popScreen()
                        ToastPopup("Game changed on the server - purchase was not submitted", GUI.getWorldScreen())
                    }
                    is AuthoritativeCommandOutcome.Rejected -> {
                        authoritativePurchaseSubmissionInProgress = false
                        ToastPopup("Server rejected purchase: [${outcome.code}]", cityScreen)
                    }
                    AuthoritativeCommandOutcome.RetryRequired -> {
                        authoritativePurchaseSubmissionInProgress = false
                        ToastPopup("Server response was lost - retry will use the same command", cityScreen)
                    }
                    null -> {
                        authoritativePurchaseSubmissionInProgress = false
                        ToastPopup("Authoritative game was closed before purchase could be submitted", cityScreen)
                    }
                }
            }
        }
    }

}
