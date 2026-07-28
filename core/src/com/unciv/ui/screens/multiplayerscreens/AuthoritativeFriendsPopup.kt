package com.unciv.ui.screens.multiplayerscreens

import com.badlogic.gdx.scenes.scene2d.ui.Table
import com.badlogic.gdx.scenes.scene2d.ui.TextButton
import com.unciv.Constants
import com.unciv.logic.multiplayer.authoritative.ApiV3Friend
import com.unciv.logic.multiplayer.authoritative.ApiV3FriendRequest
import com.unciv.logic.multiplayer.authoritative.ApiV3SocialGraph
import com.unciv.logic.multiplayer.authoritative.AuthoritativeSocialCoordinator
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

class AuthoritativeFriendsPopup(
    private val screen: BaseScreen,
    private val coordinator: AuthoritativeSocialCoordinator,
) : Popup(screen) {
    private val username = UncivTextField("Account username")

    fun openAndRefresh() {
        open()
        refresh()
    }

    private fun refresh() {
        clear()
        addGoodSizedLabel(Constants.working)
        Concurrency.runOnNonDaemonThreadPool("Refresh authoritative friends") {
            try {
                val graph = coordinator.refresh()
                launchOnGLThread { showGraph(graph) }
            } catch (ex: Exception) {
                val (message) = LoadGameScreen.getLoadExceptionMessage(ex)
                launchOnGLThread { reuseWith(message, true) }
            }
        }
    }

    private fun showGraph(graph: ApiV3SocialGraph) {
        clear()
        addGoodSizedLabel("Server friends").row()
        add(requestForm()).growX().row()
        addGoodSizedLabel("Friends").row()
        if (graph.friends.isEmpty()) addGoodSizedLabel("No friends yet").row()
        graph.friends.forEach { add(friendRow(it)).growX().row() }

        addGoodSizedLabel("Friend requests").row()
        if (graph.requests.isEmpty()) addGoodSizedLabel("No pending requests").row()
        graph.requests.forEach { add(requestRow(it)).growX().row() }

        val refreshButton = "Refresh".toTextButton()
        refreshButton.onClick { refresh() }
        add(refreshButton)
        addCloseButton()
    }

    private fun requestForm() = Table().apply {
        add(username).growX()
        val requestButton = "Send friend request".toTextButton()
        requestButton.onClick {
            requestButton.disable()
            runAction("Send authoritative friend request") {
                coordinator.request(username.text)
                username.text = ""
            }
        }
        add(requestButton)
    }

    private fun friendRow(friend: ApiV3Friend) = Table().apply {
        add(friend.username.toLabel()).growX().left()
        val removeButton = "Remove".toTextButton()
        removeButton.onClick {
            removeButton.disable()
            runAction("Remove authoritative friend") { coordinator.remove(friend) }
        }
        add(removeButton)
    }

    private fun requestRow(request: ApiV3FriendRequest) = Table().apply {
        add("${request.direction}: ${request.username}".toLabel()).growX().left()
        if (request.direction == AuthoritativeSocialCoordinator.INCOMING) {
            val acceptButton = "Accept".toTextButton()
            acceptButton.onClick {
                acceptButton.disable()
                runAction("Accept authoritative friend request") {
                    coordinator.accept(request)
                }
            }
            add(acceptButton)
        }
        val removeButton =
            if (request.direction == AuthoritativeSocialCoordinator.INCOMING)
                "Reject".toTextButton()
            else "Cancel".toTextButton()
        removeButton.onClick {
            removeButton.disable()
            runAction("Remove authoritative friend request") {
                coordinator.rejectOrCancel(request)
            }
        }
        add(removeButton)
    }

    private fun runAction(name: String, action: suspend () -> Unit) {
        Concurrency.runOnNonDaemonThreadPool(name) {
            try {
                action()
                launchOnGLThread { refresh() }
            } catch (ex: Exception) {
                launchOnGLThread {
                    val (message) = LoadGameScreen.getLoadExceptionMessage(ex)
                    ToastPopup(message, screen)
                    refresh()
                }
            }
        }
    }

}
