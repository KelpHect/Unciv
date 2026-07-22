package com.unciv.ui.screens.pickerscreens

import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.scenes.scene2d.Touchable
import com.badlogic.gdx.scenes.scene2d.ui.Button
import com.badlogic.gdx.scenes.scene2d.ui.Label
import com.badlogic.gdx.scenes.scene2d.ui.Label.LabelStyle
import com.badlogic.gdx.scenes.scene2d.ui.Table
import com.unciv.Constants
import com.unciv.UncivGame
import com.unciv.logic.civilization.Civilization
import com.unciv.logic.civilization.managers.ReligionManager
import com.unciv.logic.multiplayer.authoritative.AuthoritativeCommandOutcome
import com.unciv.models.UncivSound
import com.unciv.models.ruleset.Belief
import com.unciv.models.ruleset.BeliefType
import com.unciv.models.translations.tr
import com.unciv.ui.components.extensions.darken
import com.unciv.ui.components.extensions.disable
import com.unciv.ui.components.extensions.enable
import com.unciv.ui.components.extensions.toLabel
import com.unciv.ui.components.input.onClick
import com.unciv.ui.components.widgets.WrappableLabel
import com.unciv.ui.screens.civilopediascreen.MarkupRenderer
import com.unciv.ui.screens.worldscreen.WorldScreen
import com.unciv.ui.popups.ToastPopup
import com.unciv.utils.Concurrency
import kotlinx.coroutines.CancellationException
import yairm210.purity.annotations.Readonly

abstract class ReligionPickerScreenCommon(
    protected val choosingCiv: Civilization,
    disableScroll: Boolean = false,
    private val worldScreen: WorldScreen? = null,
) : PickerScreen(disableScroll) {

    protected val gameInfo = choosingCiv.gameInfo
    protected val ruleset = gameInfo.ruleset

    private val descriptionTable = Table(skin)
    private var authoritativeSubmissionInProgress = false

    protected data class AuthoritativeReligionChoice(
        val beliefNames: List<String>,
        val religionIconName: String? = null,
        val religionDisplayName: String? = null,
    )

    protected class Selection {
        var button: Button? = null
            private set
        fun switch(newButton: Button) {
            button?.enable()
            button = newButton
            newButton.disable()
        }
        fun clear() { button = null }
        @Readonly fun isEmpty() = button == null
    }

    init {
        // Replace the PickerScreen's descriptionLabel
        descriptionTable.defaults().top().left().pad(10f)
        descriptionLabel.remove()
        descriptionScroll.apply {
            setScrollingDisabled(true, false)
            setupFadeScrollBars(0f, 0f)
            setScrollbarsOnTop(true)
            actor = descriptionTable
        }

        closeButton.isVisible = true
        setDefaultCloseAction()
    }

    override fun getCivilopediaRuleset() = ruleset

    protected fun setOKAction(
        buttonText: String,
        authoritativeChoice: (() -> AuthoritativeReligionChoice)? = null,
        action: ReligionManager.() -> Unit,
    ) {
        rightSideButton.setText(buttonText.tr())
        rightSideButton.onClick(UncivSound.Choir) {
            if (worldScreen?.mapHolder?.usesAuthoritativeCommands() == true && authoritativeChoice != null) {
                submitAuthoritativeChoice(authoritativeChoice())
                return@onClick
            }
            choosingCiv.religionManager.action()
            UncivGame.Current.popScreen()
        }
    }

    private fun submitAuthoritativeChoice(choice: AuthoritativeReligionChoice) {
        val worldScreen = worldScreen ?: return
        if (authoritativeSubmissionInProgress) return
        authoritativeSubmissionInProgress = true
        rightSideButton.disable()
        Concurrency.runOnNonDaemonThreadPool("Choose authoritative religious beliefs") {
            val outcome = try {
                worldScreen.game.onlineMultiplayer.authoritativeSession?.chooseReligiousBeliefsIfOpen(
                    choosingCiv.gameInfo.gameId,
                    choice.beliefNames,
                    choice.religionIconName,
                    choice.religionDisplayName,
                )
            } catch (ex: Exception) {
                if (ex is CancellationException) throw ex
                Concurrency.runOnGLThread {
                    authoritativeSubmissionInProgress = false
                    rightSideButton.enable()
                    ToastPopup("Could not submit religious choice: [${ex.message ?: "Unknown"}]", this@ReligionPickerScreenCommon)
                }
                return@runOnNonDaemonThreadPool
            }
            Concurrency.runOnGLThread {
                when (outcome) {
                    is AuthoritativeCommandOutcome.Accepted -> {
                        choosingCiv.gameInfo.isUpToDate = false
                        game.popScreen()
                        ToastPopup("Religious choice committed by the authoritative server", worldScreen)
                    }
                    is AuthoritativeCommandOutcome.StaleRefreshed -> {
                        choosingCiv.gameInfo.isUpToDate = false
                        game.popScreen()
                        ToastPopup("Game changed on the server - religious choice was not committed", worldScreen)
                    }
                    is AuthoritativeCommandOutcome.Rejected -> {
                        authoritativeSubmissionInProgress = false
                        rightSideButton.enable()
                        ToastPopup("Server rejected religious choice: [${outcome.code}]", this@ReligionPickerScreenCommon)
                    }
                    AuthoritativeCommandOutcome.RetryRequired -> {
                        authoritativeSubmissionInProgress = false
                        rightSideButton.enable()
                        ToastPopup("Server response was lost - retry will use the same religious choice", this@ReligionPickerScreenCommon)
                    }
                    null -> {
                        authoritativeSubmissionInProgress = false
                        rightSideButton.enable()
                        ToastPopup("Authoritative game was closed before the religious choice", this@ReligionPickerScreenCommon)
                    }
                }
            }
        }
    }

    protected fun getBeliefButton(
        belief: Belief? = null,
        beliefType: BeliefType? = null,
        withTypeLabel: Boolean = true
    ): Button {
        val labelWidth = stage.width * 0.5f - 52f  // 32f empirically measured padding inside button, 20f outside padding
        return Button(skin).apply {
            when {
                belief != null -> {
                    if (withTypeLabel)
                        add(belief.type.name.toLabel(fontColor = Color.valueOf(belief.type.color))).row()
                    val nameLabel = WrappableLabel(belief.name, labelWidth, fontSize = Constants.headingFontSize)
                    add(nameLabel.apply { wrap = true }).row()
                    val effectLabel = WrappableLabel(belief.uniqueObjects.filter { !it.isHiddenToUsers() }.map { it.getDisplayText() }
                        .joinToString("\n") { it.tr() }, labelWidth)
                    add(effectLabel.apply { wrap = true })
                }
                beliefType == BeliefType.Any ->
                    add("Choose any belief!".toLabel())
                beliefType != null ->
                    add("Choose a [$beliefType] belief!".toLabel())
                else -> throw(IllegalArgumentException("getBeliefButton must have one non-null parameter"))
            }
        }
    }

    protected fun Button.onClickSelect(selection: Selection, belief: Belief?, function: () -> Unit) {
        onClick {
            selection.switch(this)
            function()
            descriptionTable.clear()
            if (belief == null) return@onClick
            descriptionScroll.scrollY = 0f
            descriptionScroll.updateVisualScroll()
            descriptionTable.apply {
                add(
                    MarkupRenderer.render(
                    belief.getCivilopediaTextLines(withHeader = true), width - 20f
                ) {
                    openCivilopedia(it)
                }).growX()
                // Icon should it be needed:  CivilopediaImageGetters.belief(belief.getIconName(), 50f)
            }
        }
    }

    /** Disable a [Button] by setting its [touchable][Button.touchable] and the given [color] */
    protected fun Button.disable(color: Color) {
        touchable = Touchable.disabled
        this.color = color
    }

    // This forces this label to use an own clone of the default style. If not, hovering over the button will gray
    // out all the default-styled Labels on the screen - exact cause and why this does not affect other buttons unknown.
    // Note - only used for "Choose a [$beliefType] belief!"," "Choose an Icon and name..", "Found [$newReligionName]"
    // and displayName, and those are the labels _not_ affected by the quirk!
    protected fun String.toLabel() = Label(this.tr(), LabelStyle(skin[LabelStyle::class.java]))

    protected companion object {
        val redDisableColor = Color.RED.darken(0.25f)
        val greenDisableColor = Color.GREEN.darken(0.25f)
    }
}
