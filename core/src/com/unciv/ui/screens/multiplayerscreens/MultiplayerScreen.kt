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
import com.unciv.models.ruleset.RulesetCache
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
    private val content = Table().apply {
        top()
        defaults().pad(8f)
    }

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
        content.add("MULTIPLAYER".toLabel(Color.GOLD, 30)).colspan(4).left().row()
        content.add(
            "Create a server-hosted match, join a live lobby, or continue one of your games."
                .toLabel(),
        ).colspan(4).growX().left().padBottom(18f).row()
        content.add("SERVER & ACCOUNT".toLabel(Color.GOLD)).colspan(4).growX().left().row()
        content.add("Server".toLabel(Color.LIGHT_GRAY)).left()
        content.add(game.settings.multiplayer.getServer().toLabel()).colspan(3).growX().left().row()
        val status = game.onlineMultiplayer.authoritativeStatus
        content.add("Connection".toLabel(Color.LIGHT_GRAY)).left()
        content.add(
            status.toString().toLabel(
                if (status == AuthoritativeSessionStatus.Authenticated) Color.GREEN else Color.WHITE,
            ),
        ).colspan(3).growX().left().row()
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
        content.add("Create new match".toTextButton().onClick(::openCreateMatchPopup))
        content.add("Friends".toTextButton().onClick {
            session?.let {
                AuthoritativeFriendsPopup(this, it.socialCoordinator()).openAndRefresh()
            }
        })
        content.add("Refresh".toTextButton().onClick(::refresh)).row()
        when (status) {
            AuthoritativeSessionStatus.Authenticated -> {
                content.add(Constants.working.toLabel()).colspan(4).row()
                loadDirectory()
            }
            AuthoritativeSessionStatus.Detecting ->
                content.add(Constants.working.toLabel()).colspan(4).row()
            AuthoritativeSessionStatus.Failed ->
                content.add(
                    (
                        game.onlineMultiplayer.authoritativeFailureMessage
                            ?: "Could not connect to the authoritative server."
                    ).toLabel(Color.RED),
                ).colspan(4).row()
            else -> Unit
        }
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
        content.add("OPEN LOBBIES".tr().toLabel(Color.GOLD)).colspan(4).growX().left()
            .padTop(18f).row()
        if (lobbies.isEmpty())
            content.add("No public lobbies are waiting for players.".toLabel(Color.LIGHT_GRAY))
                .colspan(4).left().row()
        for (lobby in lobbies)
            content.add(lobbyCard(lobby)).colspan(4).growX().row()
        content.add("YOUR MATCHES".tr().toLabel(Color.GOLD)).colspan(4).growX().left()
            .padTop(18f).row()
        val visibleGames = games.filter { it.lifecycleStatus != "archived" }
        if (visibleGames.isEmpty())
            content.add("You have no active matches yet.".toLabel(Color.LIGHT_GRAY))
                .colspan(4).left().row()
        for (summary in visibleGames)
            content.add(gameCard(summary)).colspan(4).growX().row()
    }

    private fun lobbyCard(lobby: ApiV3Lobby) = Table().apply {
        defaults().pad(6f)
        add(lobby.displayName.toLabel()).growX().left()
        add("${lobby.occupiedSlots}/${lobby.humanSlots} players".toLabel())
        add((if (lobby.passwordRequired) "PASSWORD" else "OPEN").toLabel(Color.LIGHT_GRAY))
        if (isNarrowerThan4to3()) row()
        add("Open lobby".toTextButton().onClick {
            session?.let { game.pushScreen(AuthoritativeLobbyScreen(lobby, it)) }
        }).right()
    }

    private fun gameCard(summary: ApiV3GameSummary) = Table().apply {
        defaults().pad(6f)
        add(summary.displayName.toLabel()).growX().left()
        add(
            "${summary.lifecycleStatus} • revision [${summary.committedRevision}]".toLabel(),
        ).left()
        if (isNarrowerThan4to3()) row()
        if (summary.available && summary.lifecycleStatus == "active")
            add("Play".toTextButton().onClick { openGame(summary) })
        else add()
        add("Chat".toTextButton().onClick {
            session?.let {
                AuthoritativeGameChatPopup(this@MultiplayerScreen, it.chatCoordinator(), summary.gameId)
                    .openAndRefresh()
            }
        })
        if (
            summary.lifecycleStatus == "active" &&
            summary.role in setOf("owner", "player")
        ) {
            add("Rewind turn".toTextButton().onClick {
                session?.let {
                    AuthoritativeRewindPopup(
                        this@MultiplayerScreen,
                        summary,
                        it,
                        this@MultiplayerScreen::refresh,
                    ).open()
                }
            })
        } else add()
        if (
            summary.role == "owner" &&
            summary.lifecycleStatus in setOf("active", "closed")
        ) {
            add("Manage".toTextButton().onClick {
                session?.let {
                    AuthoritativeAdministrationPopup(
                        this@MultiplayerScreen,
                        summary,
                        AuthoritativeAdministrationCoordinator(it),
                        this@MultiplayerScreen::refresh,
                    ).open()
                }
            })
        } else add()
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
        val activeSession = session
        if (activeSession == null) {
            ToastPopup("Log in to an authoritative server first.", this)
            return
        }
        val popup = Popup(this).apply {
            addGoodSizedLabel("Loading server game options...").row()
            addCloseButton()
            open()
        }
        Concurrency.runOnNonDaemonThreadPool("Load V3 match rulesets") {
            try {
                val manifests = activeSession.listRulesetManifests().filter { manifest ->
                    RulesetCache.containsKey(manifest.baseRuleset.name) &&
                        manifest.mods.all { RulesetCache.containsKey(it.name) }
                }
                check(manifests.isNotEmpty()) {
                    "This client does not have any ruleset installed by the server."
                }
                launchOnGLThread { renderCreateMatchPopup(popup, manifests) }
            } catch (exception: Exception) {
                launchOnGLThread {
                    popup.reuseWith(
                        exception.message ?: "Could not load server game options.",
                        true,
                    )
                }
            }
        }
    }

    private fun renderCreateMatchPopup(
        popup: Popup,
        manifests: List<com.unciv.logic.multiplayer.authoritative.ApiV3RulesetManifestSummary>,
    ) {
        popup.clear()
        val name = UncivTextField("Match name", "New multiplayer match")
        val slots = UncivTextField("Human players", "2")
        val password = UncivTextField("Optional password (12+ characters)", "").apply {
            isPasswordMode = true
        }
        val ruleset = SelectBox<String>(skin).apply {
            items = com.badlogic.gdx.utils.Array(
                manifests.map { manifest ->
                    buildString {
                        append(manifest.baseRuleset.name)
                        if (manifest.mods.isNotEmpty()) {
                            append(" + ")
                            append(manifest.mods.joinToString { it.name })
                        }
                    }
                }.toTypedArray(),
            )
        }
        popup.addGoodSizedLabel("Create authoritative match").row()
        popup.addGoodSizedLabel("Match name").left().row()
        popup.add(name).minWidth(stage.width / 2f).row()
        popup.addGoodSizedLabel("Server ruleset").left().row()
        popup.add(ruleset).minWidth(stage.width / 2f).row()
        popup.addGoodSizedLabel("Human player slots").left().row()
        popup.add(slots).row()
        popup.addGoodSizedLabel("Private match password").left().row()
        popup.add(password).minWidth(stage.width / 2f).row()
        popup.add("Configure game".toTextButton().onClick {
            try {
                val configuration = AuthoritativeLobbyConfiguration(
                    name.text.trim(),
                    slots.text.toInt(),
                    password.text.takeIf(String::isNotEmpty),
                    manifests[ruleset.selectedIndex],
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
    }

}
