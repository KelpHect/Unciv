package com.unciv.ui.screens.multiplayerscreens

import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.scenes.scene2d.ui.Table
import com.unciv.logic.multiplayer.authoritative.AuthoritativeGameChatCoordinator
import com.unciv.ui.components.extensions.disable
import com.unciv.ui.components.extensions.enable
import com.unciv.ui.components.extensions.toLabel
import com.unciv.ui.components.extensions.toTextButton
import com.unciv.ui.components.input.ActivationTypes
import com.unciv.ui.components.input.keyShortcuts
import com.unciv.ui.components.input.onActivation
import com.unciv.ui.components.widgets.AutoScrollPane
import com.unciv.ui.components.widgets.UncivTextField
import com.unciv.ui.components.widgets.WrappableLabel
import com.unciv.ui.screens.basescreen.BaseScreen
import com.unciv.utils.Concurrency
import com.unciv.utils.launchOnGLThread

/**
 * Staging-room chat. Chat is an account service that never changes a canonical
 * revision, so it refreshes on the same hints as the rest of the lobby without
 * participating in revision bookkeeping.
 */
internal class AuthoritativeLobbyChatPanel(
    private val screen: BaseScreen,
    private val coordinator: AuthoritativeGameChatCoordinator,
    private val gameId: String,
    private val bodyWidth: Float,
    bodyHeight: Float,
) : Table(BaseScreen.skin) {
    private val messages = Table(BaseScreen.skin).apply { defaults().pad(3f) }
    private val scroll = AutoScrollPane(messages).apply { setOverscroll(false, false) }
    private val draft = UncivTextField("Message the room")
    private val send = "Send".toTextButton()
    private var sending = false
    private var renderedIds = emptyList<String>()

    init {
        defaults().pad(5f)
        add(scroll).height(bodyHeight).width(bodyWidth).colspan(2).grow().row()
        add(draft).growX()
        add(send).right().row()
        send.onActivation { submit() }
        // Keystroke only, with no Tap equivalence: tapping into the field to type
        // must never send the draft.
        draft.keyShortcuts.add(com.badlogic.gdx.Input.Keys.ENTER)
        draft.onActivation(ActivationTypes.Keystroke, noEquivalence = true) { submit() }
        messages.add(LobbyChrome.hint("No messages yet.")).left().row()
    }

    fun refresh() {
        Concurrency.runOnNonDaemonThreadPool("Load V3 lobby chat") {
            val page = runCatching { coordinator.refresh(gameId) }.getOrNull() ?: return@runOnNonDaemonThreadPool
            launchOnGLThread { render(page.messages) }
        }
    }

    private fun render(
        page: List<com.unciv.logic.multiplayer.authoritative.ApiV3GameChatMessage>,
    ) {
        val ordered = page.sortedBy { it.createdAtMillis }
        val ids = ordered.map { it.messageId }
        if (ids == renderedIds) return
        renderedIds = ids
        messages.clear()
        if (ordered.isEmpty()) {
            messages.add(LobbyChrome.hint("No messages yet.")).left().row()
            return
        }
        for (message in ordered) {
            messages.add(
                message.senderUsername.toLabel(LobbyChrome.accent, hideIcons = true),
            ).left().padTop(4f).row()
            messages.add(
                WrappableLabel(message.body, bodyWidth - 24f, Color.WHITE, hideIcons = true)
                    .apply { wrap = true },
            ).growX().left().row()
        }
        scroll.layout()
        scroll.scrollPercentY = 1f
    }

    private fun submit() {
        val body = draft.text.trim()
        if (sending || body.isEmpty()) return
        sending = true
        send.disable()
        Concurrency.runOnNonDaemonThreadPool("Send V3 lobby chat") {
            val failure = runCatching { coordinator.send(gameId, body) }
                .exceptionOrNull() as? Exception
            launchOnGLThread {
                sending = false
                send.enable()
                if (failure == null) {
                    draft.text = ""
                    refresh()
                } else {
                    com.unciv.ui.popups.ToastPopup(
                        authoritativeLobbyErrorMessage(failure, "Could not send the message."),
                        screen,
                    )
                }
            }
        }
    }
}
