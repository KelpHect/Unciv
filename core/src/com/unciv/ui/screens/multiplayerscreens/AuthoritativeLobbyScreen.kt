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
import com.unciv.ui.components.extensions.enable
import com.unciv.ui.components.extensions.toLabel
import com.unciv.ui.components.extensions.toTextButton
import com.unciv.ui.components.input.onClick
import com.unciv.ui.components.input.onChange
import com.unciv.ui.components.widgets.AutoScrollPane
import com.unciv.ui.components.widgets.TabbedPager
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
    private var configurationEditor: AuthoritativeLobbyConfigurationEditor? = null
    private var requestInFlight = false
    private val content = Table(skin).apply { defaults().pad(8f) }
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
            (
                "Host: [${lobby.ownerUsername}]   •   " +
                    "${lobby.occupiedSlots}/${lobby.humanSlots} human players"
            ).toLabel(),
        ).growX().left().padBottom(12f).row()

        val leftColumn = Table(skin).apply {
            defaults().pad(6f)
            add(panel("PLAYERS & FACTIONS").also(::renderPlayers)).growX().row()
            add(panel("READY ROOM").also(::renderActions)).growX().row()
        }
        val settings = renderSettingsWorkspace()
        val body = Table(skin).apply {
            defaults().pad(6f).top()
            if (isNarrowerThan4to3()) {
                add(leftColumn).growX().row()
                add(settings).growX().row()
            } else {
                val screenWidth = this@AuthoritativeLobbyScreen.stage.width
                add(leftColumn).width(screenWidth * 0.34f).growY()
                add(settings).width(screenWidth * 0.60f).growY()
            }
        }
        content.add(body).growX().row()
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

    private fun renderSettingsWorkspace(): Table {
        val workspace = panel("MATCH CONFIGURATION")
        val pager = TabbedPager(
            minimumWidth = stage.width.coerceAtMost(620f),
            maximumWidth = stage.width.coerceAtMost(920f),
            minimumHeight = stage.height * 0.48f,
            maximumHeight = stage.height * 0.62f,
            separatorColor = Color.LIGHT_GRAY,
            shortcutScreen = this,
            capacity = 4,
        )
        if (lobby.actorRole == "owner") {
            val editor = AuthoritativeLobbyConfigurationEditor(lobby)
            configurationEditor = editor
            pager.addPage("Game", editor.gamePage)
            pager.addPage("World", editor.worldPage)
            pager.addPage("Victories", editor.victoryPage)
            pager.addPage("Advanced", editor.advancedPage)
            pager.selectPage(0)
            workspace.add(pager).colspan(2).grow().row()
            workspace.add("Apply lobby settings".toTextButton().onClick {
                saveConfiguration()
            }).colspan(2).growX().row()
            workspace.add(
                "All changes are server-validated and reset player readiness."
                    .toLabel(Color.LIGHT_GRAY),
            ).colspan(2).growX().left().row()
        } else {
            configurationEditor = null
            pager.addPage("Game", readOnlyGamePage())
            pager.addPage("World", readOnlyWorldPage(lobby.setup))
            pager.addPage("Victories", readOnlyVictoryPage(lobby.setup))
            pager.addPage("Advanced", readOnlyAdvancedPage(lobby.setup))
            pager.selectPage(0)
            workspace.add(pager).colspan(2).grow().row()
        }
        return workspace
    }

    private fun readOnlyGamePage(): Table = settingsPage("GAME RULES").apply {
        setting(
            this,
            "Ruleset",
            buildString {
                append(lobby.baseRulesetName.ifBlank { lobby.rulesetManifestHash.take(12) })
                if (lobby.modNames.isNotEmpty()) {
                    append(" + ")
                    append(lobby.modNames.joinToString())
                }
            },
        )
        setting(this, "Difficulty", lobby.setup.difficulty)
        setting(this, "Game speed", lobby.setup.speed)
        setting(this, "Starting era", lobby.setup.startingEra)
        setting(this, "Major civilizations", lobby.setup.majorCivilizations.toString())
        setting(this, "City-states", lobby.setup.cityStates.toString())
        setting(this, "Turn limit", lobby.setup.maxTurns.toString())
    }

    private fun readOnlyWorldPage(setup: ApiV3GameSetup) = settingsPage("WORLD SETTINGS").apply {
        setting(this, "Map type", setup.mapType.displayName())
        setting(this, "Map shape", setup.mapShape.displayName())
        setting(this, "World size", setup.worldSizeDisplayName())
        setting(this, "Game seed", setup.mapSeed?.toString() ?: "Server generated")
        setting(this, "Mirroring", setup.mirroring.displayName())
        setting(this, "Resources", setup.mapResources.displayName())
        setting(this, "Barbarians", setup.barbarianModeWireName())
        setting(this, "World wrap", setup.worldWrap.yesNo())
        setting(this, "Strategic balance", setup.strategicBalance.yesNo())
        setting(this, "Legendary start", setup.legendaryStart.yesNo())
        setting(this, "Ancient ruins", (!setup.noRuins).yesNo())
        setting(this, "Natural wonders", (!setup.noNaturalWonders).yesNo())
    }

    private fun readOnlyVictoryPage(setup: ApiV3GameSetup) =
        settingsPage("VICTORY CONDITIONS").apply {
            setup.victoryTypes.forEach { victory ->
                add("✓  $victory".toLabel(Color.GREEN)).colspan(2).growX().left().row()
            }
        }

    private fun readOnlyAdvancedPage(setup: ApiV3GameSetup) =
        settingsPage("ADVANCED SETTINGS").apply {
        setting(
            this,
            "Map generation",
            "Elevation ${setup.elevationExponent}, temperature ${setup.temperatureIntensity}, " +
                "shift ${setup.temperatureShift}, vegetation ${setup.vegetationRichness}, " +
                "rare features ${setup.rareFeaturesRichness}, water ${setup.waterThreshold}",
        )
        setting(
            this,
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
        setting(this, "Advanced rules", advanced.ifEmpty { listOf("Standard") }.joinToString())
    }

    private fun settingsPage(title: String) = Table(skin).apply {
        defaults().pad(8f)
        add(title.toLabel(Color.GOLD)).colspan(2).growX().left().row()
    }

    private fun saveConfiguration() {
        try {
            val update = requireNotNull(configurationEditor).build()
            runAction {
                session.reconfigureLobby(
                    lobby,
                    update.displayName,
                    update.humanSlots,
                    update.password,
                    update.setup,
                )
            }
        } catch (exception: Exception) {
            ToastPopup(
                exception.message ?: "Check every labeled lobby setting.",
                this,
            )
        }
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
            renderFactionSelection(target)
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

    private fun renderFactionSelection(target: Table) {
        val current = lobby.actorCivilizationId.orEmpty()
        val claimedByOthers = lobby.members
            .asSequence()
            .map { it.civilizationId }
            .filter { it.isNotBlank() && it != current }
            .toHashSet()
        val choices = lobby.availableCivilizations
            .filterNot(claimedByOthers::contains)
            .toMutableList()
            .apply {
                if (current.isNotBlank() && current !in this) add(0, current)
            }
            .distinct()
        if (choices.isEmpty()) {
            target.add("No civilization is currently available.".toLabel(Color.RED))
                .colspan(2).row()
            return
        }
        val civilization = SelectBox<String>(skin).apply {
            items = com.badlogic.gdx.utils.Array(choices.toTypedArray())
            if (current in choices) selected = current
        }
        target.add("Your faction".toLabel()).left()
        target.add(civilization).growX().row()
        val save = "Select faction".toTextButton()
        save.onClick {
            runAction { session.selectLobbyFaction(lobby, civilization.selected) }
        }
        if (civilization.selected == current) save.disable()
        civilization.onChange {
            if (civilization.selected == current) save.disable()
            else save.enable()
        }
        target.add(save).colspan(2).growX().row()
    }

    private fun setting(target: Table, label: String, value: String) {
        target.add(label.toLabel(Color.LIGHT_GRAY)).left()
        target.add(value.toLabel()).growX().left().row()
    }

    private fun panel(title: String) = Table(skin).apply {
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
                            authoritativeLobbyErrorMessage(
                                exception,
                                "Could not refresh lobby.",
                            ),
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
                        authoritativeLobbyErrorMessage(exception, "Lobby action failed."),
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

private fun ApiV3GameSetup.worldSizeDisplayName() = when (mapSize) {
    com.unciv.logic.multiplayer.authoritative.ApiV3GeneratedMapSize.Custom ->
        if (mapShape ==
            com.unciv.logic.multiplayer.authoritative.ApiV3GeneratedMapShape.Rectangular
        ) {
            "${customMapWidth} × ${customMapHeight}"
        } else {
            "Radius $customMapRadius"
        }
    else -> mapSize.displayName()
}

private fun Boolean.yesNo() = if (this) "Enabled" else "Disabled"
