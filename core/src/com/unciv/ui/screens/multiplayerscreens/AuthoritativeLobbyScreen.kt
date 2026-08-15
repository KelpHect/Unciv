package com.unciv.ui.screens.multiplayerscreens

import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.scenes.scene2d.ui.Container
import com.badlogic.gdx.scenes.scene2d.ui.Table
import com.badlogic.gdx.utils.Timer
import com.unciv.logic.multiplayer.authoritative.ApiV3AiSlot
import com.unciv.logic.multiplayer.authoritative.ApiV3GameSetup
import com.unciv.logic.multiplayer.authoritative.ApiV3GeneratedMapShape
import com.unciv.logic.multiplayer.authoritative.ApiV3GeneratedMapSize
import com.unciv.logic.multiplayer.authoritative.ApiV3Lobby
import com.unciv.logic.multiplayer.authoritative.ApiV3LobbyMapPreview
import com.unciv.logic.multiplayer.authoritative.AuthoritativeMultiplayerSession
import com.unciv.logic.multiplayer.authoritative.AuthoritativeGameDirectory
import com.unciv.logic.multiplayer.authoritative.OpenedAuthoritativeGame
import com.unciv.models.ruleset.Ruleset
import com.unciv.models.ruleset.RulesetCache
import com.unciv.ui.components.extensions.disable
import com.unciv.ui.components.extensions.enable
import com.unciv.ui.components.extensions.toLabel
import com.unciv.ui.components.extensions.toTextButton
import com.unciv.ui.components.input.onActivation
import com.unciv.ui.components.input.onChange
import com.unciv.ui.components.widgets.AutoScrollPane
import com.unciv.ui.components.widgets.ExpanderTab
import com.unciv.ui.components.widgets.LoadingImage
import com.unciv.ui.components.widgets.TabbedPager
import com.unciv.ui.components.widgets.UncivTextField
import com.unciv.ui.images.ImageGetter
import com.unciv.ui.popups.ToastPopup
import com.unciv.ui.screens.multiplayerscreens.LobbyChrome.control
import com.unciv.ui.screens.multiplayerscreens.LobbyChrome.field
import com.unciv.ui.screens.pickerscreens.PickerScreen
import com.unciv.utils.Concurrency
import com.unciv.utils.launchOnGLThread

/**
 * Civilization-style staging room for API v3.
 *
 * The room is assembled once and then refreshed panel by panel, so an owner's
 * open setup controls keep their focus and value while players, readiness, chat,
 * and the committed map update live. WebSocket notifications remain hints; the
 * short poll is the explicit reconciliation fallback for dropped hints and older
 * servers while the lobby is open.
 */
class AuthoritativeLobbyScreen(
    private val initialLobby: ApiV3Lobby,
    private val session: AuthoritativeMultiplayerSession,
) : PickerScreen() {
    private var lobby = initialLobby
    private var configurationEditor: AuthoritativeLobbyConfigurationEditor? = null
    private var requestInFlight = false

    private val content = Table(skin).apply { defaults().pad(6f) }
    private val headerCard = LobbyChrome.card()
    private val playersCard = LobbyChrome.card("Players", columns = 4)
    private val seatCard = LobbyChrome.card("Your seat")
    private val mapCard = LobbyChrome.card("Map preview")
    private val summaryCard = LobbyChrome.card("Match summary")
    private val statusLine = LobbyChrome.hint("")

    private val ruleset: Ruleset = RulesetCache.getComplexRuleset(
        lobby.modNames.toCollection(linkedSetOf()),
        lobby.baseRulesetName,
    )

    private val mapPreviewHolder = Container<com.badlogic.gdx.scenes.scene2d.Actor?>()
    private val mapPreviewSpinner = LoadingImage(30f)
    private var renderedPreviewRevision: Long? = null
    private var previewRequestInFlight = false

    private val chatPanel = AuthoritativeLobbyChatPanel(
        this,
        session.chatCoordinator(),
        lobby.gameId,
        bodyWidth = chatWidth(),
        bodyHeight = stage.height * 0.30f,
    )

    private val notificationSubscription: AutoCloseable
    private val refreshTask = object : Timer.Task() {
        override fun run() = refresh(silent = true)
    }

    /**
     * Owner edits are canonical mutations, so they are coalesced instead of
     * committed per keystroke. One debounce window becomes one revisioned
     * `lobby_reconfiguration`.
     */
    private var pendingConfigurationCommit: Timer.Task? = null

    init {
        setDefaultCloseAction()
        // Start lives in the picker's own bottom bar, so it is reachable at any
        // scroll position instead of hiding under the setup editor.
        rightSideButton.setText("Start match")
        rightSideButton.onActivation { runAction { session.startLobby(lobby) } }
        // Nation portraits resolve their icon atlas and ring colours through the
        // globally selected ruleset, so a modded room needs its own set first.
        ImageGetter.setNewRuleset(ruleset, ignoreIfModsAreEqual = true)
        topTable.add(AutoScrollPane(content)).grow().row()
        buildRoom()
        refreshPanels()
        chatPanel.refresh()
        notificationSubscription = session.observeLobby(lobby.gameId) {
            Concurrency.runOnGLThread { refresh(silent = true) }
        }
        Timer.schedule(refreshTask, 1.5f, 1.5f)
    }

    // region layout

    private fun buildRoom() {
        content.clear()
        content.top()
        content.add(headerCard).growX().padBottom(2f).row()
        if (isNarrowerThan4to3()) content.add(stackedWorkspace()).growX().row()
        else content.add(columnWorkspace()).grow().row()
    }

    /**
     * Desktop: roster, setup, and the match panels sit side by side and each
     * column scrolls on its own, so the whole room stays on one screen and the
     * host never has to scroll past the editor to find Start.
     */
    private fun columnWorkspace(): Table {
        val available = stage.width - 40f
        val height = workspaceHeight()
        return Table(skin).apply {
            defaults().pad(5f).top()
            add(scrollingColumn(playersCard, seatCard)).width(available * 0.28f).height(height)
            add(settingsWorkspace()).width(available * 0.46f).height(height)
            add(scrollingColumn(mapCard, summaryCard, roomChatCard()))
                .width(available * 0.26f).height(height)
        }
    }

    /**
     * The room occupies everything between the header and the picker's bottom
     * bar. Columns are given this height explicitly so a long roster or a long
     * setup page scrolls inside its own column rather than pushing the room down.
     */
    private fun workspaceHeight() = stage.height * 0.66f

    private fun scrollingColumn(vararg cards: Table): AutoScrollPane {
        val column = Table(skin).apply {
            top()
            defaults().pad(4f).growX()
            for (card in cards) add(card).row()
        }
        return AutoScrollPane(column).apply {
            setScrollingDisabled(true, false)
            setOverscroll(false, false)
            fadeScrollBars = false
        }
    }

    private fun roomChatCard() = LobbyChrome.card("Room chat").apply {
        add(chatPanel).colspan(2).growX().row()
    }

    /** Touch and portrait: one column of collapsible sections. */
    private fun stackedWorkspace(): Table = Table(skin).apply {
        defaults().pad(4f).growX()
        add(playersCard).row()
        add(seatCard).row()
        add(section("Game setup", settingsWorkspace())).row()
        add(section("Map preview", mapCard)).row()
        add(section("Match summary", summaryCard)).row()
        add(section("Room chat", chatPanel, startsOpened = false)).row()
    }

    private fun section(
        title: String,
        content: com.badlogic.gdx.scenes.scene2d.Actor,
        startsOpened: Boolean = true,
    ) = ExpanderTab(
        title,
        startsOutOpened = startsOpened,
        persistenceID = "AuthoritativeLobby.$title",
    ) { it.add(content).growX().row() }

    private fun chatWidth() =
        if (isNarrowerThan4to3()) stage.width - 70f
        else (this@AuthoritativeLobbyScreen.stage.width - 40f) * 0.28f - 40f

    // endregion
    // region live panels

    private fun refreshPanels() {
        renderHeader()
        renderPlayers()
        renderSeat()
        renderSummary()
        renderActions()
        refreshMapPreview()
    }

    private fun renderHeader() {
        LobbyChrome.resetCard(headerCard)
        headerCard.left()
        val identity = Table(skin).apply {
            defaults().left()
            add(LobbyChrome.caption("Staging room")).left().row()
            add(LobbyChrome.title(lobby.displayName)).left().row()
            add(
                (
                    "Host [${lobby.ownerUsername}]  •  " +
                        "[${lobby.occupiedSlots}]/[${lobby.humanSlots}] human players  •  " +
                        "revision [${lobby.lobbyRevision}]"
                ).toLabel(LobbyChrome.muted),
            ).left().row()
        }
        headerCard.add(identity).growX().left()
        headerCard.add(statusLine).right().padRight(8f)
        headerCard.add("Refresh now".toTextButton().onActivation { refresh() }).right().row()
    }

    private fun renderPlayers() {
        LobbyChrome.resetCard(playersCard, "Players", columns = 4)
        for (member in lobby.members) {
            // Highlight the actor's own seat, not simply the host's.
            val isActor = lobby.actorRole != null &&
                member.civilizationId.isNotBlank() &&
                member.civilizationId == lobby.actorCivilizationId
            val row = LobbyChrome.row(highlighted = isActor).apply { left() }
            row.add(LobbyChrome.nationBadge(ruleset, member.civilizationId, 44f)).left()
            row.add(
                Table(skin).apply {
                    left()
                    add(member.username.toLabel(hideIcons = true)).left().row()
                    add(LobbyChrome.nationLabel(ruleset, member.civilizationId)).left().row()
                },
            ).growX().left().padLeft(6f)
            if (member.role == "owner")
                row.add(LobbyChrome.caption("Host")).right().padRight(6f)
            else row.add()
            row.add(LobbyChrome.readyBadge(member.ready)).right()
            playersCard.add(row).colspan(4).growX().row()
        }
        repeat((lobby.humanSlots - lobby.occupiedSlots).coerceAtLeast(0)) {
            val row = LobbyChrome.row().apply { left() }
            row.add(LobbyChrome.nationBadge(ruleset, "", 44f)).left()
            row.add(LobbyChrome.hint("Open human slot")).growX().left().padLeft(6f)
            row.add()
            row.add(LobbyChrome.readyBadge(false, waitingText = "Waiting")).right()
            playersCard.add(row).colspan(4).growX().row()
        }
    }

    private fun renderSeat() {
        LobbyChrome.resetCard(seatCard, "Your seat")
        if (lobby.actorRole == null) renderJoin() else renderMembership()
    }

    private fun renderJoin() {
        val available = selectableCivilizations(currentCivilization = "")
        if (available.isEmpty()) {
            seatCard.add("No civilization is currently available.".toLabel(LobbyChrome.danger))
                .colspan(2).left().row()
            return
        }
        val civilization = civilizationSelect(available, available.first())
        val password = UncivTextField("Lobby password").apply { isPasswordMode = true }
        seatCard.control("Civilization", civilization)
        if (lobby.passwordRequired) seatCard.control("Match password", password)
        seatCard.add(
            "Join match".toTextButton().onActivation {
                runAction {
                    session.joinLobby(
                        lobby,
                        civilization.selected,
                        password.text.takeIf(String::isNotEmpty),
                    )
                    session.lobby(lobby.gameId)
                }
            },
        ).colspan(2).growX().row()
    }

    private fun renderMembership() {
        val current = lobby.actorCivilizationId.orEmpty()
        val choices = selectableCivilizations(current)
        if (choices.isEmpty()) {
            seatCard.add("No civilization is currently available.".toLabel(LobbyChrome.danger))
                .colspan(2).left().row()
        } else {
            val civilization = civilizationSelect(choices, current)
            seatCard.add(LobbyChrome.nationBadge(ruleset, current, 56f)).left().padRight(6f)
            seatCard.add(civilization).growX().left().row()
            // Choosing a faction is the member's own canonical mutation, so it
            // commits immediately: everyone sees the claim before readying up.
            civilization.onChange {
                if (civilization.selected != current)
                    runAction { session.selectLobbyFaction(lobby, civilization.selected) }
            }
        }
        val ready = lobby.actorReady == true
        val toggle = (if (ready) "Cancel ready" else "Ready up").toTextButton()
        toggle.onActivation { runAction { session.setLobbyReady(lobby, !ready) } }
        if (current.isBlank()) toggle.disable()
        seatCard.add(toggle).colspan(2).growX().row()
        if (current.isBlank())
            seatCard.add(LobbyChrome.hint("Choose a civilization before readying up."))
                .colspan(2).left().row()
    }

    private fun selectableCivilizations(currentCivilization: String): List<String> {
        val claimedByOthers = lobby.members
            .asSequence()
            .map { it.civilizationId }
            .filter { it.isNotBlank() && it != currentCivilization }
            .toHashSet()
        return lobby.availableCivilizations
            .asSequence()
            .filterNot(claimedByOthers::contains)
            .plus(currentCivilization.takeIf(String::isNotBlank).orEmpty())
            .filter(String::isNotBlank)
            .distinct()
            .toList()
    }

    private fun civilizationSelect(choices: List<String>, selectedValue: String) =
        com.badlogic.gdx.scenes.scene2d.ui.SelectBox<String>(skin).apply {
            items = com.badlogic.gdx.utils.Array(choices.toTypedArray())
            if (selectedValue in choices) selected = selectedValue
        }

    private fun renderSummary() {
        LobbyChrome.resetCard(summaryCard, "Match summary")
        val setup = lobby.setup
        summaryCard.field("Ruleset", rulesetLabel())
        summaryCard.field("Difficulty", setup.difficulty)
        summaryCard.field("Game speed", setup.speed)
        summaryCard.field("Starting era", setup.startingEra)
        summaryCard.field("Map type", setup.mapType.displayName())
        summaryCard.field("Map shape", setup.mapShape.displayName())
        summaryCard.field("World size", setup.worldSizeDisplayName())
        summaryCard.field("Resources", setup.mapResources.displayName())
        summaryCard.field("Barbarians", setup.barbarians.displayName())
        summaryCard.field("Major civilizations", setup.majorCivilizations.toString())
        summaryCard.field("City-states", setup.cityStates.toString())
        summaryCard.field("Turn limit", setup.maxTurns.toString())
        summaryCard.field("Victories", setup.victoryTypes.joinToString().ifBlank { "None" })
        val aiRoster = setup.aiCivilizations.orEmpty()
        if (aiRoster.isNotEmpty()) {
            for ((index, slot) in aiRoster.withIndex()) {
                val label = slot.civilizationId.ifBlank { "Server-chosen" }
                val traits = buildString {
                    if (slot.difficulty.isNotBlank()) append(slot.difficulty)
                    if (slot.personality.isNotBlank()) {
                        if (isNotEmpty()) append(", ")
                        append(slot.personality)
                    }
                }
                summaryCard.field(
                    "AI ${index + 1}",
                    label + if (traits.isNotBlank()) "  ($traits)" else "",
                )
            }
        }
        summaryCard.add(LobbyChrome.hint("Turn time is unlimited in server-hosted matches."))
            .colspan(2).growX().left().row()
    }

    private fun rulesetLabel() = buildString {
        append(lobby.baseRulesetName.ifBlank { "Server ruleset" })
        if (lobby.modNames.isNotEmpty()) append(" + ").append(lobby.modNames.joinToString())
    }

    /** Start and its reason live in the picker's bottom bar, always on screen. */
    private fun renderActions() {
        val isOwner = lobby.actorRole == "owner"
        val canStart = isOwner && lobby.occupiedSlots == lobby.humanSlots &&
            lobby.members.all { it.ready && it.civilizationId.isNotBlank() }
        rightSideButton.isVisible = isOwner
        if (canStart) rightSideButton.enable() else rightSideButton.disable()
        descriptionLabel.setText(
            when {
                !isOwner -> "Only the host can start the match."
                canStart -> "Everyone is ready."
                else -> "Every human slot must be filled, assigned a civilization, and ready."
            },
        )
    }

    // endregion
    // region committed map preview

    /**
     * The map is generated by the private worker when a revision commits, so the
     * preview is a read of the committed revision, fetched once per revision
     * rather than on every poll.
     */
    private fun refreshMapPreview() {
        LobbyChrome.resetCard(mapCard, "Map preview")
        mapCard.add(mapPreviewHolder).colspan(2).row()
        mapCard.add(mapPreviewSpinner).colspan(2).row()
        mapCard.add(
            LobbyChrome.hint(
                "The committed map for revision [${lobby.lobbyRevision}]. " +
                    "Stars mark start positions.",
            ),
        ).colspan(2).growX().left().row()
        if (renderedPreviewRevision == lobby.lobbyRevision || previewRequestInFlight) return
        previewRequestInFlight = true
        mapPreviewSpinner.show()
        val requestedRevision = lobby.lobbyRevision
        Concurrency.runOnNonDaemonThreadPool("Load V3 lobby map preview") {
            val preview = runCatching { session.lobbyMapPreview(lobby.gameId) }.getOrNull()
            launchOnGLThread {
                previewRequestInFlight = false
                mapPreviewSpinner.hide()
                if (preview == null) {
                    mapPreviewHolder.actor =
                        LobbyChrome.hint("The server could not supply a map preview yet.")
                    return@launchOnGLThread
                }
                renderedPreviewRevision = requestedRevision
                mapPreviewHolder.actor = buildPreview(preview)
            }
        }
    }

    private fun buildPreview(preview: ApiV3LobbyMapPreview): com.badlogic.gdx.scenes.scene2d.Actor {
        val side =
            if (isNarrowerThan4to3()) stage.width * 0.72f
            else (this@AuthoritativeLobbyScreen.stage.width - 40f) * 0.30f
        return runCatching {
            AuthoritativeLobbyMapPreview(preview.terrain, ruleset, side, side)
        }.getOrElse { LobbyChrome.hint("This client cannot render the server's map.") }
    }

    // endregion
    // region owner settings

    private fun settingsWorkspace(): Table {
        val workspace = LobbyChrome.card("Game setup")
        // Sized to its own column so the pager scrolls internally instead of
        // growing the room past the bottom of the screen.
        val columnWidth =
            if (isNarrowerThan4to3()) stage.width - 70f else (stage.width - 40f) * 0.46f - 24f
        val pagerHeight =
            if (isNarrowerThan4to3()) stage.height * 0.45f else workspaceHeight() - 70f
        val pager = TabbedPager(
            // A range rather than one pinned width: a page whose controls want
            // more room must be allowed to shrink into the column instead of
            // overflowing it and clipping its own labels.
            minimumWidth = columnWidth * 0.5f,
            maximumWidth = columnWidth,
            minimumHeight = pagerHeight,
            maximumHeight = pagerHeight,
            separatorColor = LobbyChrome.accent,
            shortcutScreen = this,
            capacity = 5,
        )
        if (lobby.actorRole == "owner") {
            val editor = AuthoritativeLobbyConfigurationEditor(lobby) { scheduleConfigurationCommit() }
            configurationEditor = editor
            pager.addPage("Game", editor.gamePage)
            pager.addPage("AI", editor.aiPage)
            pager.addPage("World", editor.worldPage)
            pager.addPage("Victories", editor.victoryPage)
            pager.addPage("Advanced", editor.advancedPage)
        } else {
            configurationEditor = null
            pager.addPage("Game", readOnlyGamePage())
            pager.addPage("AI", readOnlyAiPage(lobby.setup))
            pager.addPage("World", readOnlyWorldPage(lobby.setup))
            pager.addPage("Victories", readOnlyVictoryPage(lobby.setup))
            pager.addPage("Advanced", readOnlyAdvancedPage(lobby.setup))
        }
        pager.selectPage(0)
        workspace.add(pager).colspan(2).grow().row()
        workspace.add(
            LobbyChrome.hint(
                if (lobby.actorRole == "owner")
                    "Every change is applied to the room automatically and clears readiness."
                else "Only the host can change these settings. They update here live.",
            ),
        ).colspan(2).growX().left().row()
        return workspace
    }

    private fun scheduleConfigurationCommit() {
        pendingConfigurationCommit?.cancel()
        val task = object : Timer.Task() {
            override fun run() = commitConfiguration()
        }
        pendingConfigurationCommit = task
        statusLine.setText("Applying settings…")
        Timer.schedule(task, COMMIT_DEBOUNCE_SECONDS)
    }

    private fun commitConfiguration() {
        // This task has fired; clearing it here rather than in runAction keeps an
        // unrelated action (readying up, claiming a faction) from silently
        // discarding an owner edit that is still waiting to commit.
        pendingConfigurationCommit = null
        val editor = configurationEditor ?: return
        if (requestInFlight) {
            // A revision is already in flight; coalesce into the next window.
            scheduleConfigurationCommit()
            return
        }
        val update = try {
            editor.build()
        } catch (exception: Exception) {
            statusLine.setText(exception.message ?: "Check every labeled lobby setting.")
            return
        }
        if (update.matches(lobby)) {
            statusLine.setText("")
            return
        }
        runAction(onFailureMessage = { statusLine.setText(it) }) {
            session.reconfigureLobby(
                lobby,
                update.displayName,
                update.humanSlots,
                update.password,
                update.setup,
            )
        }
    }

    private fun readOnlyGamePage(): Table = settingsPage("Game rules").apply {
        field("Ruleset", rulesetLabel())
        field("Difficulty", lobby.setup.difficulty)
        field("Game speed", lobby.setup.speed)
        field("Starting era", lobby.setup.startingEra)
        field("Major civilizations", lobby.setup.majorCivilizations.toString())
        field("City-states", lobby.setup.cityStates.toString())
        field("Turn limit", lobby.setup.maxTurns.toString())
        field("Human player slots", lobby.humanSlots.toString())
    }

    /** The AI roster the host authored, as every other member sees it live. */
    private fun readOnlyAiPage(setup: ApiV3GameSetup): Table = settingsPage("AI civilizations").apply {
        val roster = setup.aiCivilizations
            ?: List((setup.majorCivilizations - lobby.humanSlots).coerceAtLeast(0)) {
                ApiV3AiSlot()
            }
        if (roster.isEmpty())
            add(LobbyChrome.hint("This match has no AI civilizations.")).colspan(2).left().row()
        roster.forEach { slot ->
            add(LobbyChrome.nationBadge(ruleset, slot.civilizationId, 32f)).left()
            add(
                if (slot.civilizationId.isBlank()) LobbyChrome.hint("Chosen by the server")
                else LobbyChrome.nationLabel(ruleset, slot.civilizationId),
            ).growX().left().row()
            val traits = listOfNotNull(
                slot.difficulty.takeIf(String::isNotBlank),
                slot.personality.takeIf(String::isNotBlank),
            )
            if (traits.isNotEmpty()) {
                add()
                add(LobbyChrome.hint(traits.joinToString("  •  "))).growX().left().row()
            }
        }
    }

    private fun readOnlyWorldPage(setup: ApiV3GameSetup): Table = settingsPage("World").apply {
        field("Map type", setup.mapType.displayName())
        field("Map shape", setup.mapShape.displayName())
        field("World size", setup.worldSizeDisplayName())
        field("Resources", setup.mapResources.displayName())
        field("Barbarians", setup.barbarians.displayName())
        field("Game seed", setup.mapSeed?.toString() ?: "Server chosen")
        field("Mirroring", setup.mirroring.displayName())
        field("World wrap", setup.worldWrap.yesNo())
        field("Strategic balance", setup.strategicBalance.yesNo())
        field("Legendary start", setup.legendaryStart.yesNo())
        field("Ancient ruins", (!setup.noRuins).yesNo())
        field("Natural wonders", (!setup.noNaturalWonders).yesNo())
    }

    private fun readOnlyVictoryPage(setup: ApiV3GameSetup) =
        settingsPage("Victory conditions").apply {
            if (setup.victoryTypes.isEmpty())
                add(LobbyChrome.hint("No victory condition selected.")).colspan(2).left().row()
            setup.victoryTypes.forEach { victory ->
                add("✓  $victory".toLabel(LobbyChrome.ready)).colspan(2).growX().left().row()
            }
        }

    private fun readOnlyAdvancedPage(setup: ApiV3GameSetup) =
        settingsPage("Advanced").apply {
            field(
                "Map generation",
                "Elevation ${setup.elevationExponent}, temperature " +
                    "${setup.temperatureIntensity}, shift ${setup.temperatureShift}, " +
                    "vegetation ${setup.vegetationRichness}, rare features " +
                    "${setup.rareFeaturesRichness}, water ${setup.waterThreshold}",
            )
            field(
                "Terrain scale",
                "Biome ${setup.tilesPerBiomeArea}, coast ${setup.maxCoastExtension}, " +
                    "resources ${setup.resourceRichness}",
            )
            field("Advanced rules", advancedRuleSummary(setup))
        }

    private fun advancedRuleSummary(setup: ApiV3GameSetup): String {
        val advanced = buildList {
            if (setup.barbarians.displayName() != "Normal")
                add(setup.barbarians.displayName() + " barbarians")
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
        return advanced.ifEmpty { listOf("Standard") }.joinToString()
    }

    private fun settingsPage(title: String) = Table(skin).apply {
        defaults().pad(7f)
        add(LobbyChrome.caption(title)).colspan(2).growX().left().row()
    }

    // endregion
    // region reconciliation

    private fun refresh(silent: Boolean = false) {
        if (requestInFlight) return
        requestInFlight = true
        Concurrency.runOnNonDaemonThreadPool("Reconcile V3 lobby") {
            try {
                val refreshed = session.lobby(lobby.gameId)
                launchOnGLThread {
                    requestInFlight = false
                    if (refreshed != lobby) apply(refreshed)
                }
            } catch (exception: Exception) {
                launchOnGLThread {
                    requestInFlight = false
                    if (!silent)
                        ToastPopup(
                            authoritativeLobbyErrorMessage(exception, "Could not refresh lobby."),
                            this@AuthoritativeLobbyScreen,
                        )
                }
            }
        }
    }

    /**
     * Adopts a newer lobby state. The room shell and the owner's setup controls
     * survive; only the live panels re-render, so an open control keeps focus.
     * A role change is the one case that has to rebuild the setup workspace.
     */
    private fun apply(refreshed: ApiV3Lobby) {
        val roleChanged = refreshed.actorRole != lobby.actorRole
        lobby = refreshed
        if (lobby.started) {
            openStartedGame()
            return
        }
        if (roleChanged) buildRoom()
        refreshPanels()
        chatPanel.refresh()
        if (pendingConfigurationCommit == null) statusLine.setText("")
    }

    private fun runAction(
        onFailureMessage: ((String) -> Unit)? = null,
        action: suspend () -> ApiV3Lobby,
    ) {
        if (requestInFlight) return
        requestInFlight = true
        Concurrency.runOnNonDaemonThreadPool("Update V3 lobby") {
            try {
                val updated = action()
                launchOnGLThread {
                    requestInFlight = false
                    statusLine.setText("")
                    apply(updated)
                }
            } catch (exception: Exception) {
                val message =
                    authoritativeLobbyErrorMessage(exception, "Lobby action failed.")
                launchOnGLThread {
                    requestInFlight = false
                    if (onFailureMessage == null)
                        ToastPopup(message, this@AuthoritativeLobbyScreen)
                    else onFailureMessage(message)
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
                            ruleset,
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
        pendingConfigurationCommit?.cancel()
        refreshTask.cancel()
        notificationSubscription.close()
        super.dispose()
    }

    // endregion

    private companion object {
        /**
         * Long enough that typing a name or dragging a value is one revision, short
         * enough that the room feels live to everyone else.
         */
        const val COMMIT_DEBOUNCE_SECONDS = 0.9f
    }
}

private fun AuthoritativeLobbyConfiguration.matches(lobby: ApiV3Lobby) =
    displayName == lobby.displayName &&
        humanSlots == lobby.humanSlots &&
        password.action == "keep" &&
        setup == lobby.setup

private fun Enum<*>.displayName() = name
    .replace(Regex("([a-z])([A-Z])"), "$1 $2")
    .replaceFirstChar(Char::uppercase)

private fun ApiV3GameSetup.worldSizeDisplayName() = when (mapSize) {
    ApiV3GeneratedMapSize.Custom ->
        if (mapShape == ApiV3GeneratedMapShape.Rectangular) "$customMapWidth × $customMapHeight"
        else "Radius $customMapRadius"
    else -> mapSize.displayName()
}

private fun Boolean.yesNo() = if (this) "Enabled" else "Disabled"
