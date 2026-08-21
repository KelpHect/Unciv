package com.unciv.ui.screens.multiplayerscreens

import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.scenes.scene2d.ui.Table
import com.unciv.Constants
import com.unciv.logic.multiplayer.authoritative.ApiV3GameSummary
import com.unciv.logic.multiplayer.authoritative.ApiV3Lobby
import com.unciv.logic.multiplayer.authoritative.AuthoritativeAdministrationCoordinator
import com.unciv.logic.multiplayer.authoritative.AuthoritativeGameDirectory
import com.unciv.logic.multiplayer.authoritative.AuthoritativeMultiplayerSession
import com.unciv.logic.multiplayer.authoritative.AuthoritativeSessionStatus
import com.unciv.logic.multiplayer.authoritative.OpenedAuthoritativeGame
import com.unciv.logic.multiplayer.authoritative.normalizeApiV3BaseUrl
import com.unciv.models.ruleset.RulesetCache
import com.unciv.ui.components.SmallButtonStyle
import com.unciv.ui.components.extensions.disable
import com.unciv.ui.components.extensions.toLabel
import com.unciv.ui.components.extensions.toTextButton
import com.unciv.ui.components.input.onActivation
import com.unciv.ui.components.input.onChange
import com.unciv.ui.components.widgets.AutoScrollPane
import com.unciv.ui.components.widgets.UncivTextField
import com.unciv.ui.popups.Popup
import com.unciv.ui.popups.ToastPopup
import com.unciv.ui.screens.multiplayerscreens.LobbyChrome.field
import com.unciv.ui.screens.pickerscreens.PickerScreen
import com.unciv.utils.Concurrency
import com.unciv.utils.launchOnGLThread

/**
 * Production multiplayer entry point, shaped like a Civilization game browser:
 * a server/account header, a searchable list of open staging rooms, and the
 * account's own matches. It deliberately exposes API v3 only; legacy
 * save-upload multiplayer remains isolated for compatibility imports.
 */
class MultiplayerScreen : PickerScreen() {
    private val session get() = game.onlineMultiplayer.authoritativeSession
    private val content = Table(skin).apply {
        top()
        defaults().pad(6f)
    }
    private val browserCard = LobbyChrome.card("Open staging rooms")
    private val matchesCard = LobbyChrome.card("Your matches")
    private val search = UncivTextField("Filter by name, host or ruleset")
    private var lobbies = emptyList<ApiV3Lobby>()
    private var games = emptyList<ApiV3GameSummary>()

    init {
        setDefaultCloseAction()
        rightSideButton.disable()
        rightSideButton.isVisible = false
        topTable.add(AutoScrollPane(content)).grow().row()
        pickerPane.bottomTable.background = skinStrings.getUiBackground(
            "MultiplayerScreen/BottomTable",
            tintColor = skinStrings.skinConfig.clearColor,
        )
        search.onChange { renderBrowser() }
        content.add(header()).growX().row()
        content.add(browserCard).growX().row()
        content.add(matchesCard).growX().row()
        renderBrowser()
        renderMatches()
        if (game.onlineMultiplayer.authoritativeStatus == AuthoritativeSessionStatus.Authenticated)
            loadDirectory()
    }

    private fun header() = LobbyChrome.card().apply {
        val status = game.onlineMultiplayer.authoritativeStatus
        add(LobbyChrome.caption("Multiplayer")).colspan(2).growX().left().row()
        add(LobbyChrome.title("Server-hosted matches")).colspan(2).growX().left().row()
        add(
            LobbyChrome.hint(
                "Create a server-hosted match, join a live staging room, " +
                    "or continue one of your games.",
            ),
        ).colspan(2).growX().left().padBottom(8f).row()
        field("Server", game.settings.multiplayer.getServer())
        add(LobbyChrome.hint("Connection")).left()
        add(
            status.toString().toLabel(
                when (status) {
                    AuthoritativeSessionStatus.Authenticated -> LobbyChrome.ready
                    AuthoritativeSessionStatus.Failed -> LobbyChrome.danger
                    else -> Color.WHITE
                },
            ),
        ).growX().left().row()
        if (status == AuthoritativeSessionStatus.Failed)
            add(
                (
                    game.onlineMultiplayer.authoritativeFailureMessage
                        ?: "Could not connect to the authoritative server."
                    ).toLabel(LobbyChrome.danger),
            ).colspan(2).growX().left().row()
        add(accountActions(status)).colspan(2).growX().left().padTop(6f).row()
    }

    private fun accountActions(status: AuthoritativeSessionStatus) = Table(skin).apply {
        defaults().pad(4f)
        add("Server settings".toTextButton(SmallButtonStyle()).onActivation(::openServerPopup))
        when (status) {
            AuthoritativeSessionStatus.Authenticated ->
                add(
                    "Manage account".toTextButton(SmallButtonStyle()).onActivation {
                        AuthoritativeAccountManagementPopup(this@MultiplayerScreen) { refresh() }
                            .open()
                    },
                )
            AuthoritativeSessionStatus.LoginRequired ->
                add(
                    "Create account or log in".toTextButton(SmallButtonStyle()).onActivation {
                        AuthoritativeAccountPopup(this@MultiplayerScreen) { refresh() }.open()
                    },
                )
            else -> add("Reconnect".toTextButton(SmallButtonStyle()).onActivation(::restoreSession))
        }
        add(
            "Friends".toTextButton(SmallButtonStyle()).onActivation {
                session?.let {
                    AuthoritativeFriendsPopup(this@MultiplayerScreen, it.socialCoordinator())
                        .openAndRefresh()
                }
            },
        )
        if (isNarrowerThan4to3()) row()
        add("Create new match".toTextButton().onActivation(::openCreateMatchPopup))
        add(
            "Watch public matches".toTextButton(SmallButtonStyle()).onActivation {
                session?.let { openPublicMatches(it) }
            },
        )
        add("Refresh".toTextButton(SmallButtonStyle()).onActivation(::refresh))
    }

    private fun renderBrowser() {
        LobbyChrome.resetCard(browserCard, "Open staging rooms")
        browserCard.add(search).colspan(2).growX().padBottom(4f).row()
        val status = game.onlineMultiplayer.authoritativeStatus
        if (status != AuthoritativeSessionStatus.Authenticated) {
            browserCard.add(
                LobbyChrome.hint(
                    if (status == AuthoritativeSessionStatus.Detecting) Constants.working
                    else "Log in to this server to browse staging rooms.",
                ),
            ).colspan(2).left().row()
            return
        }
        val visible = lobbies.filter(::matchesSearch)
        if (visible.isEmpty()) {
            browserCard.add(
                LobbyChrome.hint(
                    if (lobbies.isEmpty()) "No public staging rooms are waiting for players."
                    else "No staging room matches this filter.",
                ),
            ).colspan(2).left().row()
            return
        }
        for (lobby in visible) browserCard.add(lobbyRow(lobby)).colspan(2).growX().row()
    }

    private fun matchesSearch(lobby: ApiV3Lobby): Boolean {
        val needle = search.text.trim().lowercase()
        if (needle.isEmpty()) return true
        return needle in lobby.displayName.lowercase() ||
            needle in lobby.ownerUsername.lowercase() ||
            needle in lobby.baseRulesetName.lowercase() ||
            lobby.modNames.any { needle in it.lowercase() }
    }

    private fun lobbyRow(lobby: ApiV3Lobby) = LobbyChrome.row().apply {
        val full = lobby.occupiedSlots >= lobby.humanSlots
        val aiCount = lobby.setup.aiCivilizations?.size
            ?: (lobby.setup.majorCivilizations - 1 - lobby.humanSlots).coerceAtLeast(0)
        add(
            Table(skin).apply {
                add(lobby.displayName.toLabel(hideIcons = true)).left().row()
                add(
                    LobbyChrome.hint(
                        "Host [${lobby.ownerUsername}]  •  ${rulesetLabel(lobby)}  •  " +
                            "${lobby.setup.mapType.name} ${lobby.setup.mapSize.name}" +
                            if (aiCount > 0) "  •  $aiCount AI" else "",
                    ),
                ).left().row()
            },
        ).growX().left()
        add(
            "[${lobby.occupiedSlots}]/[${lobby.humanSlots}]".toLabel(
                if (full) LobbyChrome.waiting else LobbyChrome.ready,
            ),
        ).right().padRight(6f)
        add(
            (if (lobby.passwordRequired) "Private" else "Open").toLabel(LobbyChrome.muted),
        ).right().padRight(6f)
        if (isNarrowerThan4to3()) row()
        add(
            (if (lobby.actorRole != null) "Return to room" else "Join")
                .toTextButton().onActivation {
                    session?.let { game.pushScreen(AuthoritativeLobbyScreen(lobby, it)) }
                },
        ).right()
    }

    private fun rulesetLabel(lobby: ApiV3Lobby) = buildString {
        append(lobby.baseRulesetName.ifBlank { "Server ruleset" })
        if (lobby.modNames.isNotEmpty()) append(" + ").append(lobby.modNames.joinToString())
    }

    private fun renderMatches() {
        LobbyChrome.resetCard(matchesCard, "Your matches")
        val status = game.onlineMultiplayer.authoritativeStatus
        if (status != AuthoritativeSessionStatus.Authenticated) {
            matchesCard.add(LobbyChrome.hint("Log in to see your matches."))
                .colspan(2).left().row()
            return
        }
        val visible = games.filter { it.lifecycleStatus != "archived" }
        if (visible.isEmpty()) {
            matchesCard.add(LobbyChrome.hint("You have no active matches yet."))
                .colspan(2).left().row()
            return
        }
        for (summary in visible) matchesCard.add(matchRow(summary)).colspan(2).growX().row()
    }

    private fun matchRow(summary: ApiV3GameSummary) = LobbyChrome.row().apply {
        add(
            Table(skin).apply {
                add(summary.displayName.toLabel(hideIcons = true)).left().row()
                add(
                    LobbyChrome.hint(
                        "${summary.lifecycleStatus}  •  revision [${summary.committedRevision}]" +
                            if (summary.aiCount > 0) "  •  ${summary.aiCount} AI" else "",
                    ),
                ).left().row()
            },
        ).growX().left()
        if (isNarrowerThan4to3()) row()
        if (summary.available && summary.lifecycleStatus == "active")
            add("Play".toTextButton().onActivation { openGame(summary) })
        else add()
        add(
            "Chat".toTextButton(SmallButtonStyle()).onActivation {
                session?.let {
                    AuthoritativeGameChatPopup(
                        this@MultiplayerScreen,
                        it.chatCoordinator(),
                        summary.gameId,
                    ).openAndRefresh()
                }
            },
        )
        if (summary.lifecycleStatus == "active" && summary.role in setOf("owner", "player")) {
            add(
                "Rewind turn".toTextButton(SmallButtonStyle()).onActivation {
                    session?.let {
                        AuthoritativeRewindPopup(
                            this@MultiplayerScreen,
                            summary,
                            it,
                            this@MultiplayerScreen::refresh,
                        ).open()
                    }
                },
            )
        } else add()
        if (summary.role == "owner" && summary.lifecycleStatus in setOf("active", "closed")) {
            add(
                "Manage".toTextButton(SmallButtonStyle()).onActivation {
                    session?.let {
                        AuthoritativeAdministrationPopup(
                            this@MultiplayerScreen,
                            summary,
                            AuthoritativeAdministrationCoordinator(it),
                            this@MultiplayerScreen::refresh,
                        ).open()
                    }
                },
            )
        } else add()
    }

    private fun refresh() {
        game.replaceCurrentScreen(MultiplayerScreen())
    }
    private fun openPublicMatches(session: AuthoritativeMultiplayerSession) {
        Concurrency.run("loadPublicMatches") {
            try {
                val matches = session.listAllPublicMatches()
                launchOnGLThread {
                    if (matches.isEmpty()) {
                        ToastPopup("No public matches available.", this@MultiplayerScreen)
                    } else {
                        AuthoritativePublicMatchesPopup(this@MultiplayerScreen, matches, session)
                            .open()
                    }
                }
            } catch (e: Exception) {
                launchOnGLThread {
                    ToastPopup("Error loading public matches: ${e.message}", this@MultiplayerScreen)
                }
            }
        }
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
                val openLobbies = activeSession.listAllOpenLobbies()
                val ownGames = AuthoritativeGameDirectory(activeSession).refresh()
                launchOnGLThread {
                    lobbies = openLobbies
                    games = ownGames
                    renderBrowser()
                    renderMatches()
                }
            } catch (ex: Exception) {
                launchOnGLThread {
                    browserCard.add(
                        (ex.message ?: "Could not load multiplayer games.")
                            .toLabel(LobbyChrome.danger),
                    ).colspan(2).row()
                }
            }
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
                // The projection deliberately carries no ruleset identity, so the
                // server's own manifest names are read here, off the GL thread.
                val lobby = activeSession.lobby(summary.gameId)
                val ruleset = RulesetCache.getComplexRuleset(
                    lobby.modNames.toCollection(linkedSetOf()),
                    lobby.baseRulesetName,
                )
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
                                    ruleset,
                                ),
                            )
                        }
                        is OpenedAuthoritativeGame.Spectator -> {
                            popup.close()
                            game.pushScreen(
                                AuthoritativeSpectatorScreen(
                                    summary,
                                    opened.projection,
                                    activeSession,
                                    ruleset,
                                ),
                            )
                        }
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
        popup.addGoodSizedLabel("AUTHORITATIVE MULTIPLAYER SERVER").growX().left().row()
        popup.add(
            LobbyChrome.hint(
                "One account works across desktop and Android when both clients use this server.",
            ),
        ).growX().left().row()
        popup.add("Server URL or test IP".toLabel()).growX().left().row()
        popup.add(address).minWidth(stage.width.coerceAtMost(1000f) * 0.65f).growX().row()
        popup.add(
            LobbyChrome.hint(
                "Use HTTPS for a hostname. Plain HTTP is allowed only for a literal test IP.",
            ),
        ).growX().left().row()
        popup.add(
            "Save and connect".toTextButton().onActivation {
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
            },
        ).row()
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
                launchOnGLThread {
                    popup.close()
                    game.pushScreen(AuthoritativeCreateLobbyScreen(manifests, activeSession))
                }
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
}
