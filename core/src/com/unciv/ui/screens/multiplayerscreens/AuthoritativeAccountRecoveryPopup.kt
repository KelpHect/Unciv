package com.unciv.ui.screens.multiplayerscreens

import com.unciv.logic.multiplayer.authoritative.AuthoritativeAccountMessages
import com.unciv.ui.components.extensions.toTextButton
import com.unciv.ui.components.input.onClick
import com.unciv.ui.components.widgets.UncivTextField
import com.unciv.ui.popups.Popup
import com.unciv.ui.popups.ToastPopup
import com.unciv.ui.screens.basescreen.BaseScreen
import com.unciv.utils.Concurrency
import com.unciv.utils.launchOnGLThread

class AuthoritativeAccountRecoveryPopup(
    private val screen: BaseScreen,
    private val onAuthenticated: () -> Unit,
) : Popup(screen) {
    private val username = UncivTextField("Username")
    private val recoveryCode = UncivTextField("Recovery code")
    private val newPassword = UncivTextField("New password").apply { isPasswordMode = true }
    private val recoverButton = "Recover and log in".toTextButton()

    init {
        addGoodSizedLabel("Recover server account").row()
        addGoodSizedLabel(
            "A valid code changes the password, invalidates the complete code batch, " +
                "and logs out every other device.",
        ).row()
        add(username).width(screen.stage.width / 2).row()
        add(recoveryCode).width(screen.stage.width / 2).row()
        add(newPassword).width(screen.stage.width / 2).row()
        recoverButton.onClick { recover() }
        add(recoverButton).growX().row()
        addCloseButton()
    }

    private fun recover() {
        val enteredUsername = username.text.trim()
        val enteredCode = recoveryCode.text.trim()
        val enteredPassword = newPassword.text
        if (enteredUsername.isEmpty() || enteredCode.isEmpty() || enteredPassword.isEmpty()) {
            ToastPopup("Username, recovery code, and new password are required.", screen)
            return
        }
        recoverButton.isDisabled = true
        Concurrency.runOnNonDaemonThreadPool("Authoritative account recovery") {
            try {
                screen.game.onlineMultiplayer.recoverAuthoritative(
                    enteredUsername,
                    enteredCode,
                    enteredPassword,
                )
                launchOnGLThread {
                    recoveryCode.text = ""
                    newPassword.text = ""
                    close()
                    onAuthenticated()
                }
            } catch (exception: Exception) {
                launchOnGLThread {
                    recoveryCode.text = ""
                    newPassword.text = ""
                    recoverButton.isDisabled = false
                    ToastPopup(AuthoritativeAccountMessages.forException(exception), screen)
                }
            }
        }
    }
}
