package com.unciv.ui.screens.multiplayerscreens

import com.badlogic.gdx.scenes.scene2d.ui.TextButton
import com.unciv.logic.multiplayer.authoritative.ApiV3RecoveryCodes
import com.unciv.logic.multiplayer.authoritative.AuthoritativeAccountMessages
import com.unciv.ui.components.extensions.toTextButton
import com.unciv.ui.components.input.onClick
import com.unciv.ui.components.widgets.UncivTextField
import com.unciv.ui.popups.ConfirmPopup
import com.unciv.ui.popups.Popup
import com.unciv.ui.popups.ToastPopup
import com.unciv.ui.screens.basescreen.BaseScreen
import com.unciv.utils.Concurrency
import com.unciv.utils.launchOnGLThread

class AuthoritativeAccountManagementPopup(
    private val screen: BaseScreen,
    private val onSignedOut: () -> Unit,
) : Popup(screen) {
    private val currentPassword =
        UncivTextField("Current password").apply { isPasswordMode = true }
    private val newPassword =
        UncivTextField("New password").apply { isPasswordMode = true }
    private val confirmNewPassword =
        UncivTextField("Confirm new password").apply { isPasswordMode = true }
    private val changePasswordButton = "Change password and log out other devices".toTextButton()
    private val recoveryCodesButton = "Replace recovery codes".toTextButton()
    private val logoutButton = "Log out this device".toTextButton()
    private val logoutAllButton = "Log out every device".toTextButton()
    private val disableButton = "Disable account".toTextButton()
    private val deleteButton = "Delete account".toTextButton()
    private val actionButtons =
        listOf(
            changePasswordButton,
            recoveryCodesButton,
            logoutButton,
            logoutAllButton,
            disableButton,
            deleteButton,
        )

    init {
        addGoodSizedLabel("Manage authoritative multiplayer account").row()
        addGoodSizedLabel(
            "Recovery codes are shown once. Keep them outside this device. " +
                "There is no operator or client-save recovery override.",
        ).row()
        add(currentPassword).width(screen.stage.width / 2).row()
        add(newPassword).width(screen.stage.width / 2).row()
        add(confirmNewPassword).width(screen.stage.width / 2).row()

        changePasswordButton.onClick { changePassword() }
        recoveryCodesButton.onClick { replaceRecoveryCodes() }
        logoutButton.onClick { confirm("Log out this device?", "Log out") { logout(false) } }
        logoutAllButton.onClick {
            confirm("Log out every device using this account?", "Log out all") { logout(true) }
        }
        disableButton.onClick {
            confirm(
                "Disable this account and revoke every session? An operator cannot undo this.",
                "Disable account",
            ) { disableAccount() }
        }
        deleteButton.onClick {
            confirm(
                "Permanently delete this account identity and revoke every session?",
                "Delete account",
            ) { deleteAccount() }
        }
        for (button in actionButtons) add(button).growX().row()
        addCloseButton()
    }

    private fun changePassword() {
        val old = currentPassword.text
        val replacement = newPassword.text
        if (old.isEmpty() || replacement.isEmpty()) {
            ToastPopup("Current and new passwords are required.", screen)
            return
        }
        if (replacement != confirmNewPassword.text) {
            ToastPopup("The new passwords do not match.", screen)
            return
        }
        runAction("Change authoritative account password", signedOut = false) {
            screen.game.onlineMultiplayer.changeAuthoritativePassword(old, replacement)
            ToastPopup("Password changed. Other devices were logged out.", screen)
        }
    }

    private fun replaceRecoveryCodes() {
        val password = currentPassword.text
        if (password.isEmpty()) {
            ToastPopup("Enter the current password first.", screen)
            return
        }
        setButtonsDisabled(true)
        Concurrency.runOnNonDaemonThreadPool("Replace authoritative recovery codes") {
            try {
                val codes =
                    screen.game.onlineMultiplayer.replaceAuthoritativeRecoveryCodes(password)
                launchOnGLThread {
                    clearPasswords()
                    setButtonsDisabled(false)
                    AuthoritativeRecoveryCodesPopup(screen, codes).open()
                }
            } catch (exception: Exception) {
                showFailure(exception)
            }
        }
    }

    private fun logout(all: Boolean) =
        runAction("Authoritative account logout", signedOut = true) {
            if (all) screen.game.onlineMultiplayer.logoutAllAuthoritative()
            else screen.game.onlineMultiplayer.logoutAuthoritative()
        }

    private fun disableAccount() {
        val password = currentPassword.text
        if (password.isEmpty()) {
            ToastPopup("Enter the current password first.", screen)
            return
        }
        runAction("Disable authoritative account", signedOut = true) {
            screen.game.onlineMultiplayer.disableAuthoritativeAccount(password)
        }
    }

    private fun deleteAccount() {
        val password = currentPassword.text
        if (password.isEmpty()) {
            ToastPopup("Enter the current password first.", screen)
            return
        }
        runAction("Delete authoritative account", signedOut = true) {
            screen.game.onlineMultiplayer.deleteAuthoritativeAccount(password)
        }
    }

    private fun runAction(
        taskName: String,
        signedOut: Boolean,
        action: suspend () -> Unit,
    ) {
        setButtonsDisabled(true)
        Concurrency.runOnNonDaemonThreadPool(taskName) {
            try {
                action()
                launchOnGLThread {
                    clearPasswords()
                    setButtonsDisabled(false)
                    if (signedOut) {
                        close()
                        onSignedOut()
                    }
                }
            } catch (exception: Exception) {
                showFailure(exception)
            }
        }
    }

    private fun showFailure(exception: Exception) {
        Concurrency.runOnGLThread {
            clearPasswords()
            setButtonsDisabled(false)
            ToastPopup(AuthoritativeAccountMessages.forException(exception), screen)
        }
    }

    private fun clearPasswords() {
        currentPassword.text = ""
        newPassword.text = ""
        confirmNewPassword.text = ""
    }

    private fun setButtonsDisabled(disabled: Boolean) {
        for (button: TextButton in actionButtons) button.isDisabled = disabled
    }

    private fun confirm(message: String, confirmText: String, action: () -> Unit) {
        ConfirmPopup(screen, message, confirmText, action = action).open()
    }
}

private class AuthoritativeRecoveryCodesPopup(
    private val screen: BaseScreen,
    codes: ApiV3RecoveryCodes,
) : Popup(screen) {
    private val plaintext = codes.recoveryCodes.joinToString("\n")

    init {
        require(codes.recoveryCodes.size == 8)
        addGoodSizedLabel("New one-time recovery codes").row()
        addGoodSizedLabel(
            "These codes expire in [${codes.expiresInDays}] days. " +
                "Generating another batch invalidates every code shown here.",
        ).row()
        for (code in codes.recoveryCodes) addGoodSizedLabel(code).row()
        add("Copy all codes".toTextButton().apply {
            onClick {
                com.badlogic.gdx.Gdx.app.clipboard.contents = plaintext
                ToastPopup("Recovery codes copied. Store them securely.", screen)
            }
        }).growX().row()
        addCloseButton()
    }
}
