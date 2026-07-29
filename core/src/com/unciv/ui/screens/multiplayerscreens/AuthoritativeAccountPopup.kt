package com.unciv.ui.screens.multiplayerscreens

import com.badlogic.gdx.scenes.scene2d.ui.TextButton
import com.unciv.logic.multiplayer.authoritative.AuthoritativeAccountMessages
import com.unciv.ui.components.extensions.toTextButton
import com.unciv.ui.components.input.onClick
import com.unciv.ui.components.widgets.UncivTextField
import com.unciv.ui.popups.Popup
import com.unciv.ui.popups.ToastPopup
import com.unciv.ui.screens.basescreen.BaseScreen
import com.unciv.utils.Concurrency
import com.unciv.utils.launchOnGLThread

class AuthoritativeAccountPopup(
    private val screen: BaseScreen,
    private val onAuthenticated: () -> Unit,
) : Popup(screen) {
    private val username = UncivTextField("Username")
    private val password = UncivTextField("Password").apply { isPasswordMode = true }
    private val loginButton = actionButton("Log in", register = false)
    private val registerButton = actionButton("Create account", register = true)
    private val recoverButton = "Recover account".toTextButton().apply {
        onClick {
            AuthoritativeAccountRecoveryPopup(screen, onAuthenticated).open()
            close()
        }
    }

    init {
        addGoodSizedLabel("MULTIPLAYER ACCOUNT").colspan(3).row()
        addGoodSizedLabel(
            "Use one account on desktop and Android. Your matches are stored on the server.",
        ).colspan(3).growX().padBottom(12f).row()
        addGoodSizedLabel("Username").colspan(3).left().row()
        add(username).colspan(3).growX().pad(12f).row()
        addGoodSizedLabel("Password").colspan(3).left().row()
        add(password).colspan(3).growX().pad(12f).row()
        addGoodSizedLabel(
            "New accounts require a strong password. Recovery codes are shown once after creation.",
        ).colspan(3).growX().padBottom(8f).row()
        add(recoverButton).colspan(3).growX().pad(0f, 12f, 12f, 12f).row()
        addCloseButton().growX().padRight(6f)
        add(loginButton).growX().pad(0f, 6f, 0f, 6f)
        add(registerButton).growX().padLeft(6f)
    }

    private fun actionButton(label: String, register: Boolean): TextButton =
        label.toTextButton().apply {
            onClick {
                val enteredUsername = username.text.trim()
                val enteredPassword = password.text
                if (enteredUsername.isEmpty() || enteredPassword.isEmpty()) {
                    reuseWith("Username and password are required.", true)
                    return@onClick
                }
                loginButton.isDisabled = true
                registerButton.isDisabled = true
                Concurrency.runOnNonDaemonThreadPool("Authoritative account authentication") {
                    try {
                        if (register) {
                            screen.game.onlineMultiplayer.registerAuthoritative(
                                enteredUsername,
                                enteredPassword,
                            )
                        } else {
                            screen.game.onlineMultiplayer.loginAuthoritative(
                                enteredUsername,
                                enteredPassword,
                            )
                        }
                        launchOnGLThread {
                            password.text = ""
                            close()
                            onAuthenticated()
                        }
                    } catch (exception: Exception) {
                        launchOnGLThread {
                            password.text = ""
                            loginButton.isDisabled = false
                            registerButton.isDisabled = false
                            ToastPopup(
                                AuthoritativeAccountMessages.forException(exception),
                                screen,
                            )
                        }
                    }
                }
            }
        }
}
