package com.unciv.ui.popups.options

import com.badlogic.gdx.Application
import com.badlogic.gdx.Gdx
import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.scenes.scene2d.ui.Table
import com.unciv.logic.files.IMediaFinder.LabeledSounds
import com.unciv.logic.multiplayer.authoritative.normalizeApiV3BaseUrl
import com.unciv.ui.audio.SoundPlayer
import com.unciv.ui.components.extensions.addSeparator
import com.unciv.ui.components.extensions.toLabel
import com.unciv.ui.components.extensions.toTextButton
import com.unciv.ui.components.input.onChange
import com.unciv.ui.components.input.onClick
import com.unciv.ui.components.widgets.UncivTextField
import com.unciv.ui.screens.multiplayerscreens.MultiplayerScreen
import com.unciv.ui.popups.options.MultiplayerSelectBoxHelpers.RefreshSelectOptions
import java.time.temporal.ChronoUnit
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.map

/** V3 multiplayer preferences. Legacy user IDs and file-server passwords are
 * intentionally absent; V3 credentials live in the platform secure store. */
internal class MultiplayerTab(
    optionsPopup: OptionsPopup,
) : OptionsPopupTab(optionsPopup), MultiplayerSelectBoxHelpers {
    private val mpSettings by settings::multiplayer

    override fun lateInitialize() {
        addAuthoritativeServer()
        addSeparator()

        val curRefresh = RefreshSelectOptions(
            mpSettings::currentGameRefreshDelay,
            createRefreshOptions(ChronoUnit.SECONDS, 3, 5),
            createRefreshOptions(ChronoUnit.SECONDS, 10, 20, 30, 60),
        )
        curRefresh.selectBox = addSelectBox(
            "Update the open match every:",
            curRefresh::value,
            curRefresh.getItems(),
        )
        val allRefresh = RefreshSelectOptions(
            mpSettings::allGameRefreshDelay,
            createRefreshOptions(ChronoUnit.SECONDS, 15, 30),
            createRefreshOptions(ChronoUnit.MINUTES, 1, 2, 5, 15),
        )
        allRefresh.selectBox = addSelectBox(
            "Update the multiplayer game list every:",
            allRefresh::value,
            allRefresh.getItems(),
        )

        addSeparator()
        addNotificationSettings()
        super.lateInitialize()
    }

    private fun addAuthoritativeServer() {
        add("Authoritative multiplayer V3".toLabel()).colspan(2).row()
        val address = UncivTextField("Server URL or IP", mpSettings.getServer())
        val error = "".toLabel(Color.RED).apply { isVisible = false }
        address.setTextFieldFilter { _, character -> character !in " \r\n\t\\" }
        address.onChange {
            try {
                val normalized = normalizeApiV3BaseUrl(address.text)
                mpSettings.setServer(normalized)
                error.isVisible = false
                address.color = Color.GREEN
            } catch (ex: Exception) {
                error.setText(ex.message)
                error.isVisible = true
                address.color = Color.RED
            }
        }
        val table = Table()
        table.add("Server address".toLabel()).left().row()
        table.add(address).minWidth(optionsPopup.stageToShowOn.width / 3f).growX().row()
        table.add(error).left().row()
        table.add(
            "Hostnames require HTTPS. Plain HTTP is accepted only for a literal test IP."
                .toLabel(Color.GRAY),
        ).left().row()
        table.add("Account, login, and match browser".toTextButton().onClick {
            settings.save()
            optionsPopup.close()
            game.pushScreen(MultiplayerScreen())
        }).row()
        add(table).colspan(2).fillX().row()
    }

    private fun addNotificationSettings() {
        addCheckbox(
            "Enable multiplayer status button in singleplayer games",
            mpSettings::statusButtonInSinglePlayer,
            updateWorld = true,
        )
        val sounds = LabeledSounds()
        fun soundItems() = synchronized(sounds) {
            sounds.getLabeledSounds().asFlow().map {
                MultiplayerSelectBoxHelpers.UncivSoundLabeled(it)
            }
        }
        val current = MultiplayerSelectBoxHelpers.UncivSoundProxy(
            mpSettings::currentGameTurnNotificationSound,
        )
        val other = MultiplayerSelectBoxHelpers.UncivSoundProxy(
            mpSettings::otherGameTurnNotificationSound,
        )
        addAsyncSelectBox(
            "Sound when it is your turn in the open match:",
            current::value,
            ::soundItems,
        ) { SoundPlayer.play(it.value) }
        addAsyncSelectBox(
            "Sound when it is your turn in another match:",
            other::value,
            ::soundItems,
        ) { SoundPlayer.play(it.value) }

        if (Gdx.app.type != Application.ApplicationType.Android) return
        addCheckbox("Enable Android turn notifications", mpSettings::turnCheckerEnabled) {
            reopenOptions(force = true)
        }
        if (!mpSettings.turnCheckerEnabled) return
        val turnChecker = RefreshSelectOptions(
            mpSettings::turnCheckerDelay,
            createRefreshOptions(ChronoUnit.SECONDS, 30),
            createRefreshOptions(ChronoUnit.MINUTES, 1, 2, 5, 15),
        )
        turnChecker.selectBox = addSelectBox(
            "Check for turns in the background every:",
            turnChecker::value,
            turnChecker.getItems(),
        )
        addCheckbox(
            "Show persistent notification for turn notifier service",
            mpSettings::turnCheckerPersistentNotificationEnabled,
        )
    }
}
