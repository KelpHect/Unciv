package com.unciv.ui.screens.multiplayerscreens

import com.badlogic.gdx.scenes.scene2d.ui.Table
import com.unciv.Constants
import com.unciv.logic.multiplayer.authoritative.ApiV3GameChatMessage
import com.unciv.logic.multiplayer.authoritative.ApiV3GameChatPage
import com.unciv.logic.multiplayer.authoritative.AuthoritativeGameChatCoordinator
import com.unciv.models.translations.tr
import com.unciv.ui.components.extensions.disable
import com.unciv.ui.components.extensions.toLabel
import com.unciv.ui.components.extensions.toTextButton
import com.unciv.ui.components.input.onClick
import com.unciv.ui.components.widgets.UncivTextField
import com.unciv.ui.popups.Popup
import com.unciv.ui.popups.ToastPopup
import com.unciv.ui.screens.basescreen.BaseScreen
import com.unciv.ui.screens.savescreens.LoadGameScreen
import com.unciv.utils.Concurrency
import com.unciv.utils.launchOnGLThread

class AuthoritativeGameChatPopup(
    private val screen: BaseScreen,
    private val coordinator: AuthoritativeGameChatCoordinator,
    private val gameId: String,
) : Popup(screen) {
    private val message = UncivTextField("Message")
    private val messages = mutableListOf<ApiV3GameChatMessage>()
    private var olderCursor: String? = null

    fun openAndRefresh() {
        open()
        refresh()
    }

    private fun refresh() {
        showWorking()
        runRequest("Refresh authoritative game chat") {
            val page = coordinator.refresh(gameId)
            messages.clear()
            messages += page.messages
            olderCursor = page.nextCursor
        }
    }

    private fun loadOlder() {
        val cursor = olderCursor ?: return
        showWorking()
        runRequest("Load older authoritative game chat") {
            val page = coordinator.older(gameId, cursor)
            messages.addAll(0, page.messages)
            olderCursor = page.nextCursor
        }
    }

    private fun send() {
        val body = message.text
        showWorking()
        runRequest("Send authoritative game chat", { message.text = "" }) {
            coordinator.send(gameId, body)
            val page = coordinator.refresh(gameId)
            messages.clear()
            messages += page.messages
            olderCursor = page.nextCursor
        }
    }

    private fun runRequest(
        name: String,
        onSuccess: () -> Unit = {},
        request: suspend () -> Unit,
    ) {
        Concurrency.runOnNonDaemonThreadPool(name) {
            try {
                request()
                launchOnGLThread {
                    onSuccess()
                    showMessages()
                }
            } catch (ex: Exception) {
                val (error) = LoadGameScreen.getLoadExceptionMessage(ex)
                launchOnGLThread {
                    ToastPopup(error, screen)
                    showMessages()
                }
            }
        }
    }

    private fun showWorking() {
        clear()
        addGoodSizedLabel(Constants.working)
    }

    private fun showMessages() {
        clear()
        addGoodSizedLabel("Server game chat").row()
        olderCursor?.let {
            val older = "Load older messages".toTextButton()
            older.onClick { loadOlder() }
            add(older).row()
        }
        if (messages.isEmpty()) addGoodSizedLabel("No messages yet").row()
        messages.forEach { add(messageRow(it)).growX().row() }
        val sendRow = Table()
        sendRow.add(message).growX()
        val sendButton = "Send".toTextButton()
        sendButton.onClick {
            sendButton.disable()
            send()
        }
        sendRow.add(sendButton)
        add(sendRow).growX().row()
        addCloseButton("Close".tr())
    }

    private fun messageRow(chatMessage: ApiV3GameChatMessage) = Table().apply {
        add("[${chatMessage.senderUsername}] ${chatMessage.body}".toLabel())
            .growX()
            .left()
    }
}
