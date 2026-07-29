package com.unciv.ui.screens.multiplayerscreens

import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.scenes.scene2d.ui.SelectBox
import com.badlogic.gdx.scenes.scene2d.ui.Table
import com.unciv.Constants
import com.unciv.logic.multiplayer.authoritative.ApiV3GameSummary
import com.unciv.logic.multiplayer.authoritative.ApiV3Lobby
import com.unciv.logic.multiplayer.authoritative.AuthoritativeAdministrationCoordinator
import com.unciv.logic.multiplayer.authoritative.AuthoritativeGameDirectory
import com.unciv.logic.multiplayer.authoritative.AuthoritativeLobbyConfiguration
import com.unciv.logic.multiplayer.authoritative.AuthoritativeSessionStatus
import com.unciv.logic.multiplayer.authoritative.OpenedAuthoritativeGame
import com.unciv.logic.multiplayer.authoritative.normalizeApiV3BaseUrl
import com.unciv.models.metadata.GameSetupInfo
import com.unciv.models.translations.tr
import com.unciv.ui.components.extensions.disable
import com.unciv.ui.components.extensions.toLabel
import com.unciv.ui.components.extensions.toTextButton
import com.unciv.ui.components.input.onClick
import com.unciv.ui.components.widgets.UncivTextField
import com.unciv.ui.popups.Popup
import com.unciv.ui.popups.ToastPopup
import com.unciv.ui.screens.newgamescreen.NewGameScreen
import com.unciv.ui.screens.pickerscreens.PickerScreen
import com.unciv.utils.Concurrency
import com.unciv.utils.launchOnGLThread

/** Production multiplayer entry point. It deliberately exposes API v3 only;
 * legacy save-upload multiplayer remains isolated for compatibility imports. */
class MultiplayerScreen : PickerScreen() {
    private val session get() = game.onlineMultiplayer.authoritativeSession
    private val content = Table().apply { defaults().pad(8f) }

    init {
        setDefaultCloseAction()
        rightSideButton.disable()
        rightSideButton.isVisible = false
        topTable.add(content).grow().row()
        pickerPane.bottomTable.background = skinStrings.getUiBackground(
            "MultiplayerScreen/BottomTable",
            tintColor = skinStrings.skinConfig.clearColor,
        )
        showLoading()
    }

    private fun showLoading() {
        content.clear()
        content.add("Authoritative multiplayer".tr().toLabel()).colspan(4).row()
        content.add("Server: [${game.settings.multiplayer.getServer()}]".toLabel()).colspan(4).row()
        val status = game.onlineMultiplayer.authoritativeStatus
        content.add("Status: [$status]".toLabel()).colspan(4).row()
        content.add("Server settings".toTextButton().onClick(::openServerPopup))
        when (status) {
            AuthoritativeSessionStatus.Authenticated ->
                content.add("Manage account".toTextButton().onClick {
                    AuthoritativeAccountManagementPopup(this) { refresh() }.open()
                })
            AuthoritativeSessionStatus.LoginRequired ->
                content.add("Create account or log in".toTextButton().onClick {
                    AuthoritativeAccountPopup(this) { refresh() }.open()
                })
            else -> content.add("Reconnect".toTextButton().onClick(::restoreSession))
        }
        content.add("Create match".toTextButton().onClick(::openCreateMatchPopup))
        content.add("Friends".toTextButton().onClick {
            session?.let {
                AuthoritativeFriendsPopup(this, it.socialCoordinator()).openAndRefresh()
            }
        })
        content.add("Refresh".toTextButton().onClick(::refresh)).row()
        content.add(Constants.working.toLabel()).colspan(4).row()
        if (status == AuthoritativeSessionStatus.Authenticated) loadDirectory()
    }

    private fun refresh() {
        game.replaceCurrentScreen(MultiplayerScreen())
    }

    private fun restoreSession() {
        Concurrency.runOnNonDaemonThreadPool("Restore V3 multiplayer session") {
            game.onlineMultiplayer.restoreConfiguredAuthoritativeSession(
                game.settings.multiplayer.getServer(),
                game::createApiV3SessionTokenStore,
            )
            launchOnGLThread { refresh() }
        }
    }

    private fun loadDirectory() {
        val activeSession = session ?: return
        Concurrency.runOnNonDaemonThreadPool("Load V3 lobby directory") {
            try {
                val lobbies = activeSession.listOpenLobbies().lobbies
                val games = activeSession.listGames().games
                launchOnGLThread { renderDirectory(lobbies, games) }
            } catch (ex: Exception) {
                launchOnGLThread {
                    content.add((ex.message ?: "Could not load multiplayer games.").toLabel(Color.RED))
                        .colspan(4).row()
                }
            }
        }
    }

    private fun renderDirectory(lobbies: List<ApiV3Lobby>, games: List<ApiV3GameSummary>) {
        content.removeActorAt(content.children.size - 1, true)
        content.add("Open matches".tr().toLabel()).colspan(4).left().row()
        if (lobbies.isEmpty()) content.add("No open matches".toLabel()).colspan(4).row()
        for (lobby in lobbies) {
            content.add(lobby.displayName.toLabel()).left()
            content.add("${lobby.occupiedSlots}/${lobby.humanSlots} players".toLabel())
            content.add((if (lobby.passwordRequired) "Password" else "Open").toLabel())
            content.add("View".toTextButton().onClick { LobbyPopup(lobby).open() }).row()
        }
        content.add("Your games".tr().toLabel()).colspan(4).left().row()
        val visibleGames = games.filter { it.lifecycleStatus != "archived" }
        if (visibleGames.isEmpty()) content.add("No games".toLabel()).colspan(4).row()
        for (summary in visibleGames) {
            content.add(summary.displayName.toLabel()).colspan(2).left()
            content.add(
                "${summary.lifecycleStatus} - revision [${summary.committedRevision}]".toLabel(),
            )
            if (summary.available && summary.lifecycleStatus == "active")
                content.add("Play".toTextButton().onClick { openGame(summary) }).row()
            else content.add().row()
            content.add("Chat".toTextButton().onClick {
                session?.let {
                    AuthoritativeGameChatPopup(this, it.chatCoordinator(), summary.gameId)
                        .openAndRefresh()
                }
            })
            if (
                summary.lifecycleStatus == "active" &&
                summary.role in setOf("owner", "player")
            ) {
                content.add("Rewind turn".toTextButton().onClick {
                    session?.let {
                        AuthoritativeRewindPopup(this, summary, it, ::refresh).open()
                    }
                })
            } else content.add()
            if (
                summary.role == "owner" &&
                summary.lifecycleStatus in setOf("active", "closed")
            ) {
                content.add("Manage".toTextButton().onClick {
                    session?.let {
                        AuthoritativeAdministrationPopup(
                            this,
                            summary,
                            AuthoritativeAdministrationCoordinator(it),
                            ::refresh,
                        ).open()
                    }
                })
            } else content.add()
            content.add().row()
        }
    }

    private fun openGame(summary: ApiV3GameSummary) {
        val activeSession = session ?: return
        val popup = Popup(this).apply {
            addGoodSizedLabel(Constants.working).row()
            open()
        }
        Concurrency.runOnNonDaemonThreadPool("Open V3 game") {
            try {
                val opened = AuthoritativeGameDirectory(activeSession).open(summary)
                launchOnGLThread {
                    when (opened) {
                        is OpenedAuthoritativeGame.Player -> {
                            popup.close()
                            game.pushScreen(
                                AuthoritativeWorldScreen(
                                    summary,
                                    AuthoritativeGameDirectory(activeSession),
                                    opened.projection,
                                    activeSession,
                                ),
                            )
                        }
                        is OpenedAuthoritativeGame.Spectator ->
                            popup.reuseWith("This account is a spectator.", true)
                    }
                }
            } catch (ex: Exception) {
                launchOnGLThread { popup.reuseWith(ex.message ?: "Could not open game.", true) }
            }
        }
    }

    private fun openServerPopup() {
        val popup = Popup(this)
        val address = UncivTextField("Server URL or IP", game.settings.multiplayer.getServer())
        popup.addGoodSizedLabel("Authoritative server").row()
        popup.add(address).minWidth(stage.width / 2f).row()
        popup.add("Save and connect".toTextButton().onClick {
            try {
                val normalized = normalizeApiV3BaseUrl(address.text)
                game.settings.multiplayer.setServer(normalized)
                game.settings.save()
                popup.close()
                restoreSession()
            } catch (_: Exception) {
                ToastPopup(
                    "Use https:// for a hostname, or http:// with a literal test IP.",
                    this,
                )
            }
        }).row()
        popup.addCloseButton()
        popup.open()
    }

    private fun openCreateMatchPopup() {
        if (session == null) {
            ToastPopup("Log in to an authoritative server first.", this)
            return
        }
        val popup = Popup(this)
        val name = UncivTextField("Match name", "New multiplayer match")
        val slots = UncivTextField("Human players", "2")
        val password = UncivTextField("Optional password (12+ characters)", "").apply {
            isPasswordMode = true
        }
        popup.addGoodSizedLabel("Create authoritative match").row()
        popup.add(name).minWidth(stage.width / 2f).row()
        popup.add(slots).row()
        popup.add(password).minWidth(stage.width / 2f).row()
        popup.add("Configure game".toTextButton().onClick {
            try {
                val configuration = AuthoritativeLobbyConfiguration(
                    name.text.trim(),
                    slots.text.toInt(),
                    password.text.takeIf(String::isNotEmpty),
                )
                val setup = GameSetupInfo.fromSettings()
                setup.gameParameters.isOnlineMultiplayer = true
                popup.close()
                game.pushScreen(NewGameScreen(setup, lobbyConfiguration = configuration))
            } catch (_: Exception) {
                ToastPopup("Use a name, 1-16 human players, and an optional 12+ character password.", this)
            }
        }).row()
        popup.addCloseButton()
        popup.open()
    }

    private inner class LobbyPopup(private var lobby: ApiV3Lobby) : Popup(this@MultiplayerScreen) {
        private val civilization = SelectBox<String>(skin)
        private val password = UncivTextField("Lobby password", "").apply { isPasswordMode = true }

        init {
            rebuild()
        }

        private fun rebuild() {
            clear()
            addGoodSizedLabel(lobby.displayName).row()
            addGoodSizedLabel("Hosted by [${lobby.ownerUsername}]").row()
            for (member in lobby.members)
                addGoodSizedLabel(
                    "[${member.username}] — [${member.civilizationId}]" +
                        if (member.ready) " — Ready" else " — Not ready",
                ).row()
            val taken = lobby.members.mapTo(hashSetOf()) { it.civilizationId }
            civilization.items = com.badlogic.gdx.utils.Array(
                lobby.availableCivilizations.filterNot(taken::contains).toTypedArray(),
            )
            if (lobby.actorRole == null) {
                if (civilization.items.notEmpty()) {
                    add(civilization).minWidth(stage.width / 3f).row()
                    if (lobby.passwordRequired)
                        add(password).minWidth(stage.width / 3f).row()
                    add("Join selected faction".toTextButton().onClick {
                        runLobbyAction {
                            session!!.joinLobby(
                                lobby,
                                civilization.selected,
                                password.text.takeIf(String::isNotEmpty),
                            )
                            session!!.lobby(lobby.gameId)
                        }
                    }).row()
                } else addGoodSizedLabel("No faction slot is available.").row()
            } else {
                add(
                    (if (lobby.actorReady == true) "Not ready" else "Ready")
                        .toTextButton().onClick {
                            runLobbyAction {
                                session!!.setLobbyReady(lobby, lobby.actorReady != true)
                            }
                        },
                ).row()
            }
            if (lobby.actorRole == "owner")
                add("Start match".toTextButton().onClick {
                    runLobbyAction { session!!.startLobby(lobby) }
                }).row()
            add("Refresh lobby".toTextButton().onClick {
                runLobbyAction { session!!.lobby(lobby.gameId) }
            }).row()
            addCloseButton()
        }

        private fun runLobbyAction(action: suspend () -> ApiV3Lobby) {
            Concurrency.runOnNonDaemonThreadPool("Update V3 lobby") {
                try {
                    lobby = action()
                    launchOnGLThread {
                        if (lobby.started) {
                            close()
                            refresh()
                        } else rebuild()
                    }
                } catch (ex: Exception) {
                    launchOnGLThread {
                        ToastPopup(ex.message ?: "Lobby action failed.", this@MultiplayerScreen)
                    }
                }
            }
        }
    }
}
