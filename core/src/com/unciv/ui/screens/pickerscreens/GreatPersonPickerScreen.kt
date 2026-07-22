package com.unciv.ui.screens.pickerscreens

import com.unciv.UncivGame
import com.unciv.logic.civilization.Civilization
import com.unciv.logic.multiplayer.authoritative.AuthoritativeCommandOutcome
import com.unciv.models.UncivSound
import com.unciv.models.ruleset.unit.BaseUnit
import com.unciv.models.translations.tr
import com.unciv.ui.images.ImageGetter
import com.unciv.ui.components.extensions.isEnabled
import com.unciv.ui.components.extensions.disable
import com.unciv.ui.components.extensions.enable
import com.unciv.ui.components.input.onClick
import com.unciv.ui.components.input.onDoubleClick
import com.unciv.ui.screens.worldscreen.WorldScreen
import com.unciv.ui.popups.ToastPopup
import com.unciv.utils.Concurrency
import kotlinx.coroutines.CancellationException

class GreatPersonPickerScreen(val worldScreen: WorldScreen, val civInfo: Civilization) : PickerScreen() {
    private var theChosenOne: BaseUnit? = null
    private var authoritativeSubmissionInProgress = false

    init {
        worldScreen.autoPlay.stopAutoPlay()
        closeButton.isVisible = false
        rightSideButton.setText("Choose a free great person".tr())

        val authoritativeChoices = if (worldScreen.mapHolder.usesAuthoritativeCommands())
            worldScreen.game.onlineMultiplayer.authoritativeSession
                ?.cachedProjectionIfOpen(civInfo.gameInfo.gameId)
                ?.selectableGreatPeople
                ?.toSet()
                ?: emptySet()
        else null
        val greatPersonUnits = civInfo.greatPeople.getGreatPeople()
            .filter { authoritativeChoices == null || it.name in authoritativeChoices }
        val useMayaLongCount = authoritativeChoices == null && civInfo.greatPeople.mayaLimitedFreeGP > 0

        for (unit in greatPersonUnits) {
            val button =
                PickerPane.getPickerOptionButton(ImageGetter.getUnitIcon(unit), unit.name)
            button.pack()
            button.isEnabled = !useMayaLongCount || unit.name in civInfo.greatPeople.longCountGPPool
            if (button.isEnabled) {
                button.onClick {
                    theChosenOne = unit
                    pick("Get [${unit.name}]".tr())
                    descriptionLabel.setText(unit.getShortDescription())
                }

                button.onDoubleClick(UncivSound.Choir) { confirmAction(useMayaLongCount) }
            }
            topTable.add(button).pad(10f).row()
        }

        rightSideButton.onClick(UncivSound.Choir) {
            confirmAction(useMayaLongCount)
        }

    }

    private fun confirmAction(useMayaLongCount: Boolean) {
        if (worldScreen.mapHolder.usesAuthoritativeCommands()) {
            submitAuthoritativeChoice(theChosenOne?.name ?: return)
            return
        }
        civInfo.units.addUnit(theChosenOne!!, civInfo.getCapital())
        civInfo.greatPeople.freeGreatPeople--
        if (useMayaLongCount) {
            civInfo.greatPeople.mayaLimitedFreeGP--
            civInfo.greatPeople.longCountGPPool.remove(theChosenOne!!.name)
        }
        UncivGame.Current.popScreen()
    }

    private fun submitAuthoritativeChoice(unitName: String) {
        if (authoritativeSubmissionInProgress) return
        authoritativeSubmissionInProgress = true
        rightSideButton.disable()
        Concurrency.runOnNonDaemonThreadPool("Choose authoritative great person") {
            val outcome = try {
                worldScreen.game.onlineMultiplayer.authoritativeSession?.chooseGreatPersonIfOpen(
                    civInfo.gameInfo.gameId,
                    unitName,
                )
            } catch (ex: Exception) {
                if (ex is CancellationException) throw ex
                Concurrency.runOnGLThread {
                    authoritativeSubmissionInProgress = false
                    rightSideButton.enable()
                    ToastPopup(
                        "Could not submit great person choice: [${ex.message ?: "Unknown"}]",
                        this@GreatPersonPickerScreen,
                    )
                }
                return@runOnNonDaemonThreadPool
            }
            Concurrency.runOnGLThread {
                when (outcome) {
                    is AuthoritativeCommandOutcome.Accepted -> {
                        civInfo.gameInfo.isUpToDate = false
                        game.popScreen()
                        ToastPopup("Great person committed by the authoritative server", worldScreen)
                    }
                    is AuthoritativeCommandOutcome.StaleRefreshed -> {
                        civInfo.gameInfo.isUpToDate = false
                        game.popScreen()
                        ToastPopup("Game changed on the server - great person was not chosen", worldScreen)
                    }
                    is AuthoritativeCommandOutcome.Rejected -> {
                        authoritativeSubmissionInProgress = false
                        rightSideButton.enable()
                        ToastPopup("Server rejected great person choice: [${outcome.code}]", this@GreatPersonPickerScreen)
                    }
                    AuthoritativeCommandOutcome.RetryRequired -> {
                        authoritativeSubmissionInProgress = false
                        rightSideButton.enable()
                        ToastPopup("Server response was lost - retry will use the same choice", this@GreatPersonPickerScreen)
                    }
                    null -> {
                        authoritativeSubmissionInProgress = false
                        rightSideButton.enable()
                        ToastPopup("Authoritative game was closed before the great person choice", this@GreatPersonPickerScreen)
                    }
                }
            }
        }
    }
}
