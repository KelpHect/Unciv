package com.unciv.ui.screens.multiplayerscreens

import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.scenes.scene2d.ui.SelectBox
import com.badlogic.gdx.scenes.scene2d.ui.Table
import com.badlogic.gdx.utils.Timer
import com.unciv.logic.multiplayer.authoritative.ApiV3GameSetup
import com.unciv.logic.multiplayer.authoritative.ApiV3Lobby
import com.unciv.logic.multiplayer.authoritative.AuthoritativeMultiplayerSession
import com.unciv.logic.multiplayer.authoritative.AuthoritativeGameDirectory
import com.unciv.logic.multiplayer.authoritative.OpenedAuthoritativeGame
import com.unciv.models.translations.tr
import com.unciv.ui.components.extensions.disable
import com.unciv.ui.components.extensions.toLabel
import com.unciv.ui.components.extensions.toTextButton
import com.unciv.ui.components.input.onClick
import com.unciv.ui.components.widgets.AutoScrollPane
import com.unciv.ui.components.widgets.UncivTextField
import com.unciv.ui.popups.ToastPopup
import com.unciv.ui.screens.pickerscreens.PickerScreen
import com.unciv.utils.Concurrency
import com.unciv.utils.launchOnGLThread

/**
 * Revisioned pregame room for API v3. WebSocket notifications remain hints;
 * the short poll is an explicit reconciliation fallback for dropped hints and
 * older servers while the lobby is open.
 */
class AuthoritativeLobbyScreen(
    private val initialLobby: ApiV3Lobby,
    private val session: AuthoritativeMultiplayerSession,
) : PickerScreen() {
    private var lobby = initialLobby
    private var requestInFlight = false
    private val content = Table().apply { defaults().pad(8f) }
    private val notificationSubscription: AutoCloseable
    private val refreshTask = object : Timer.Task() {
        override fun run() = refresh(silent = true)
    }

    init {
        setDefaultCloseAction()
        rightSideButton.disable()
        rightSideButton.isVisible = false
        topTable.add(AutoScrollPane(content)).grow().row()
        render()
        notificationSubscription = session.observeLobby(lobby.gameId) {
            Concurrency.runOnGLThread { refresh(silent = true) }
        }
        Timer.schedule(refreshTask, 1.5f, 1.5f)
    }

    private fun render() {
        content.clear()
        content.top()
        content.add("MULTIPLAYER LOBBY  •  STEP 2 OF 2".toLabel(Color.GOLD))
            .growX().left().row()
        content.add(lobby.displayName.toLabel(Color.WHITE, 30)).growX().left().row()
        content.add(
            "Host: [${lobby.ownerUsername}]   •   " +
                "${lobby.occupiedSlots}/${lobby.humanSlots} human players".toLabel(),
        ).growX().left().padBottom(12f).row()

        val players = panel("PLAYERS & FACTIONS")
        renderPlayers(players)
        val settings = panel("MATCH SETTINGS")
        renderSettings(settings, lobby.setup)
        val body = Table().apply {
            defaults().pad(6f).top()
            if (isNarrowerThan4to3()) {
                add(players).growX().row()
                add(settings).growX().row()
            } else {
                add(players).width(stage.width * 0.38f).growY()
                add(settings).width(stage.width * 0.56f).growY()
            }
        }
        content.add(body).growX().row()

        val actions = panel("READY ROOM")
        renderActions(actions)
        content.add(actions).growX().row()
    }

    private fun renderPlayers(target: Table) {
        for (member in lobby.members) {
            val identity = buildString {
                append(member.username)
                if (member.role == "owner") append("  •  Host")
                append("\n")
                append(member.civilizationId.ifBlank { "Selecting civilization" })
            }
            target.add(identity.toLabel()).growX().left()
            target.add(
                (if (member.ready) "READY" else "NOT READY")
                    .toLabel(if (member.ready) Color.GREEN else Color.LIGHT_GRAY),
            ).right().row()
        }
        repeat((lobby.humanSlots - lobby.occupiedSlots).coerceAtLeast(0)) {
            target.add("Open human slot".toLabel(Color.LIGHT_GRAY)).growX().left()
            target.add("WAITING".toLabel(Color.LIGHT_GRAY)).right().row()
        }
    }

    private fun renderSettings(target: Table, setup: ApiV3GameSetup) {
        setting(
            target,
            "Ruleset",
            buildString {
                append(lobby.baseRulesetName.ifBlank { lobby.rulesetManifestHash.take(12) })
                if (lobby.modNames.isNotEmpty()) {
                    append(" + ")
                    append(lobby.modNames.joinToString())
                }
            },
        )
        setting(target, "Map", "${setup.mapType.displayName()} • ${setup.mapSize.displayName()}")
        setting(target, "Map shape", setup.mapShape.displayName())
        setting(target, "Map seed", setup.mapSeed?.toString() ?: "Server generated")
        setting(target, "Mirroring", setup.mirroring.displayName())
        setting(target, "Resources", setup.mapResources.displayName())
        setting(target, "Difficulty", setup.difficulty)
        setting(target, "Game speed", setup.speed)
        setting(target, "Starting era", setup.startingEra)
        setting(target, "Major civilizations", setup.majorCivilizations.toString())
        setting(target, "City-states", setup.cityStates.toString())
        setting(target, "Turn limit", setup.maxTurns.toString())
        setting(target, "Victory conditions", setup.victoryTypes.joinToString())
        setting(
            target,
            "Map generation",
            "Elevation ${setup.elevationExponent}, temperature ${setup.temperatureIntensity}, " +
                "shift ${setup.temperatureShift}, vegetation ${setup.vegetationRichness}, " +
                "rare features ${setup.rareFeaturesRichness}, water ${setup.waterThreshold}",
        )
        setting(
            target,
            "Terrain scale",
            "Biome ${setup.tilesPerBiomeArea}, coast ${setup.maxCoastExtension}, " +
                "resources ${setup.resourceRichness}",
        )
        val advanced = buildList {
            if (setup.barbarianModeWireName() != "Normal") add(setup.barbarianModeWireName() + " barbarians")
            if (setup.oneCityChallenge) add("One City Challenge")
            if (!setup.nuclearWeaponsEnabled) add("No nuclear weapons")
            if (!setup.espionageEnabled) add("No espionage")
            if (setup.noStartBias) add("No start bias")
            if (setup.shufflePlayerOrder) add("Shuffle player order")
            if (setup.noCityRazing) add("No city razing")
            if (setup.worldWrap) add("World wrap")
            if (setup.strategicBalance) add("Strategic balance")
            if (setup.legendaryStart) add("Legendary start")
            if (setup.noRuins) add("No ruins")
            if (setup.noNaturalWonders) add("No natural wonders")
        }
        setting(target, "Advanced rules", advanced.ifEmpty { listOf("Standard") }.joinToString())
    }

    private fun renderActions(target: Table) {
        if (lobby.actorRole == null) {
            val taken = lobby.members.mapTo(hashSetOf()) { it.civilizationId }
            val available = lobby.availableCivilizations.filterNot(taken::contains)
            if (available.isEmpty()) {
                target.add("No civilization is currently available.".toLabel(Color.RED))
                    .colspan(2).row()
            } else {
                val civilization = SelectBox<String>(skin).apply {
                    items = com.badlogic.gdx.utils.Array(available.toTypedArray())
                }
                val password = UncivTextField("Lobby password").apply {
                    isPasswordMode = true
                    isVisible = lobby.passwordRequired
                }
                target.add("Your civilization".toLabel()).left()
                target.add(civilization).growX().row()
                if (lobby.passwordRequired) {
                    target.add("Match password".toLabel()).left()
                    target.add(password).growX().row()
                }
                target.add("Join lobby".toTextButton().onClick {
                    runAction {
                        session.joinLobby(
                            lobby,
                            civilization.selected,
                            password.text.takeIf(String::isNotEmpty),
                        )
                        session.lobby(lobby.gameId)
                    }
                }).colspan(2).growX().row()
            }
        } else {
            val ready = lobby.actorReady == true
            target.add(
                (if (ready) "Cancel ready" else "Ready up")
                    .toTextButton().onClick {
                        runAction { session.setLobbyReady(lobby, !ready) }
                    },
            ).colspan(2).growX().row()
        }
        if (lobby.actorRole == "owner") {
            val canStart =
                lobby.occupiedSlots == lobby.humanSlots &&
                    lobby.members.all { it.ready && it.civilizationId.isNotBlank() }
            val start = "Start match".toTextButton()
            start.onClick {
                runAction { session.startLobby(lobby) }
            }
            if (!canStart) start.disable()
            target.add(start).colspan(2).growX().row()
            if (!canStart)
                target.add(
                    "Every human slot must be filled, assigned a civilization, and ready."
                        .toLabel(Color.LIGHT_GRAY),
                ).colspan(2).left().row()
        }
        target.add("Refresh now".toTextButton().onClick { refresh() }).colspan(2).growX().row()
    }

    private fun setting(target: Table, label: String, value: String) {
        target.add(label.toLabel(Color.LIGHT_GRAY)).left()
        target.add(value.toLabel()).growX().left().row()
    }

    private fun panel(title: String) = Table().apply {
        background = skinStrings.getUiBackground(
            "MultiplayerScreen/Section",
            tintColor = skinStrings.skinConfig.baseColor,
        )
        defaults().pad(8f)
        add(title.tr().toLabel(Color.GOLD)).colspan(2).growX().left().row()
    }

    private fun refresh(silent: Boolean = false) {
        if (requestInFlight) return
        requestInFlight = true
        Concurrency.runOnNonDaemonThreadPool("Reconcile V3 lobby") {
            try {
                val refreshed = session.lobby(lobby.gameId)
                launchOnGLThread {
                    requestInFlight = false
                    if (refreshed != lobby) {
                        lobby = refreshed
                        if (lobby.started) {
                            openStartedGame()
                        } else {
                            render()
                        }
                    }
                }
            } catch (exception: Exception) {
                launchOnGLThread {
                    requestInFlight = false
                    if (!silent)
                        ToastPopup(
                            exception.message ?: "Could not refresh lobby.",
                            this@AuthoritativeLobbyScreen,
                        )
                }
            }
        }
    }

    private fun runAction(action: suspend () -> ApiV3Lobby) {
        if (requestInFlight) return
        requestInFlight = true
        Concurrency.runOnNonDaemonThreadPool("Update V3 lobby") {
            try {
                val updated = action()
                launchOnGLThread {
                    requestInFlight = false
                    lobby = updated
                    if (lobby.started) openStartedGame()
                    else render()
                }
            } catch (exception: Exception) {
                launchOnGLThread {
                    requestInFlight = false
                    ToastPopup(
                        exception.message ?: "Lobby action failed.",
                        this@AuthoritativeLobbyScreen,
                    )
                    refresh(silent = true)
                }
            }
        }
    }

    private fun openStartedGame() {
        requestInFlight = true
        Concurrency.runOnNonDaemonThreadPool("Open started V3 lobby") {
            try {
                val summary = session.listGames().games.single { it.gameId == lobby.gameId }
                val directory = AuthoritativeGameDirectory(session)
                val opened = directory.open(summary)
                check(opened is OpenedAuthoritativeGame.Player) {
                    "A lobby player did not receive a player projection"
                }
                launchOnGLThread {
                    requestInFlight = false
                    game.replaceCurrentScreen(
                        AuthoritativeWorldScreen(
                            summary,
                            directory,
                            opened.projection,
                            session,
                        ),
                    )
                }
            } catch (exception: Exception) {
                launchOnGLThread {
                    requestInFlight = false
                    ToastPopup(
                        exception.message ?: "The match started, but could not be opened.",
                        this@AuthoritativeLobbyScreen,
                    )
                }
            }
        }
    }

    override fun dispose() {
        refreshTask.cancel()
        notificationSubscription.close()
        super.dispose()
    }
}

private fun Enum<*>.displayName() = name
    .replace(Regex("([a-z])([A-Z])"), "$1 $2")
    .replaceFirstChar(Char::uppercase)

private fun com.unciv.logic.multiplayer.authoritative.ApiV3BarbarianMode.displayName() =
    name.lowercase().replaceFirstChar(Char::uppercase)

private fun ApiV3GameSetup.barbarianModeWireName() = barbarians.displayName()
