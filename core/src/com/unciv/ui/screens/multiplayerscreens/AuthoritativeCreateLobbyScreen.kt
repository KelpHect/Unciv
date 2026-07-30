package com.unciv.ui.screens.multiplayerscreens

import com.badlogic.gdx.scenes.scene2d.ui.Container
import com.badlogic.gdx.scenes.scene2d.ui.SelectBox
import com.badlogic.gdx.scenes.scene2d.ui.Table
import com.unciv.Constants
import com.unciv.logic.multiplayer.authoritative.ApiV3RulesetManifestSummary
import com.unciv.logic.multiplayer.authoritative.AuthoritativeCreationMeaning
import com.unciv.logic.multiplayer.authoritative.AuthoritativeCreationRetryState
import com.unciv.logic.multiplayer.authoritative.AuthoritativeMultiplayerSession
import com.unciv.logic.multiplayer.authoritative.createDefaultApiV3GameSetup
import com.unciv.models.ruleset.Ruleset
import com.unciv.models.ruleset.RulesetCache
import com.unciv.ui.components.extensions.disable
import com.unciv.ui.components.extensions.enable
import com.unciv.ui.components.extensions.toLabel
import com.unciv.ui.components.extensions.toTextButton
import com.unciv.ui.components.input.onActivation
import com.unciv.ui.components.input.onChange
import com.unciv.ui.components.widgets.AutoScrollPane
import com.unciv.ui.components.widgets.UncivTextField
import com.unciv.ui.images.ImageGetter
import com.unciv.ui.popups.Popup
import com.unciv.ui.popups.ToastPopup
import com.unciv.ui.screens.multiplayerscreens.LobbyChrome.control
import com.unciv.ui.screens.pickerscreens.PickerScreen
import com.unciv.utils.Concurrency
import com.unciv.utils.Log
import com.unciv.utils.launchOnGLThread

/**
 * API-v3-only first stage, kept deliberately short like a Civilization "host
 * game" step: it establishes the room, its access policy, and the host's own
 * faction. Map, rules, victories and advanced settings are then edited live in
 * the staging room, where every change is a server-validated revision.
 */
class AuthoritativeCreateLobbyScreen(
    private val manifests: List<ApiV3RulesetManifestSummary>,
    private val session: AuthoritativeMultiplayerSession,
    private val retryState: AuthoritativeCreationRetryState = AuthoritativeCreationRetryState(),
) : PickerScreen() {
    private val content = Table(skin).apply { defaults().pad(6f) }
    private val matchName = UncivTextField("Match name", "New multiplayer match")
    private val humanSlots = UncivTextField("Human players", "2")
    private val password = UncivTextField("Optional password (12+ characters)").apply {
        isPasswordMode = true
    }
    private val rulesetSelect = SelectBox<String>(skin)
    private val factionSelect = SelectBox<String>(skin)
    private val factionPortrait = Container<com.badlogic.gdx.scenes.scene2d.Actor?>()
    private val createButton = "Create lobby".toTextButton()
    private var selectedRuleset: Ruleset = rulesetFor(
        manifests.firstOrNull() ?: error("The server has no compatible game rulesets."),
    )

    init {
        setDefaultCloseAction()
        rightSideButton.disable()
        rightSideButton.isVisible = false
        rulesetSelect.items = com.badlogic.gdx.utils.Array(
            manifests.map(::manifestLabel).toTypedArray(),
        )
        refreshFactions()
        rulesetSelect.onChange {
            selectedRuleset = rulesetFor(manifests[rulesetSelect.selectedIndex])
            refreshFactions()
        }
        factionSelect.onChange { refreshFactionPortrait() }
        createButton.onActivation(::createLobby)

        content.top()
        content.add(header()).growX().row()
        content.add(roomCard()).growX().row()
        content.add(leaderCard()).growX().row()
        content.add(createButton).growX().padTop(12f).row()
        topTable.add(AutoScrollPane(content)).grow().row()
    }

    private fun header() = LobbyChrome.card().apply {
        add(LobbyChrome.caption("Host a match")).colspan(2).growX().left().row()
        add(LobbyChrome.title("CREATE MULTIPLAYER LOBBY  •  STEP 1 OF 2"))
            .colspan(2).growX().left().row()
        add(
            LobbyChrome.hint(
                "Create the room first. Map, rules, victories and advanced settings " +
                    "stay editable in the live staging room.",
            ),
        ).colspan(2).growX().left().row()
    }

    private fun roomCard() = LobbyChrome.card("Room").apply {
        control("Match name", matchName, fieldWidth())
        control("Server ruleset", rulesetSelect, fieldWidth())
        control("Human player slots", humanSlots, fieldWidth())
        control("Private match password", password, fieldWidth())
        add(
            LobbyChrome.hint(
                "Leave the password blank to list the match publicly.",
            ),
        ).colspan(2).growX().left().row()
    }

    private fun leaderCard() = LobbyChrome.card("Your civilization").apply {
        add(factionPortrait).left().padRight(8f)
        add(factionSelect).minWidth(fieldWidth()).growX().left().row()
        add(
            LobbyChrome.hint(
                "Every other player claims their own unclaimed civilization after joining.",
            ),
        ).colspan(2).growX().left().row()
    }

    private fun fieldWidth() = stage.width.coerceAtMost(900f) * 0.5f

    private fun refreshFactions() {
        val factions = selectedRuleset.nations.values
            .asSequence()
            .filter { it.isMajorCiv }
            .map { it.name }
            .sorted()
            .toList()
        check(factions.isNotEmpty()) { "The selected server ruleset has no playable factions." }
        factionSelect.items = com.badlogic.gdx.utils.Array(factions.toTypedArray())
        refreshFactionPortrait()
    }

    private fun refreshFactionPortrait() {
        // Nation portraits resolve their icon atlas and ring colours through the
        // globally selected ruleset, so a modded manifest needs its own set first.
        ImageGetter.setNewRuleset(selectedRuleset, ignoreIfModsAreEqual = true)
        factionPortrait.actor =
            LobbyChrome.nationBadge(selectedRuleset, factionSelect.selected.orEmpty(), 64f)
    }

    private fun createLobby() {
        try {
            val slots = humanSlots.text.trim().toInt()
            val displayName = matchName.text.trim()
            require(displayName.isNotEmpty() && displayName.length <= 80) {
                "Match name must contain 1-80 characters."
            }
            val availableCivilizations = selectedRuleset.nations.values
                .filter { it.isMajorCiv }
                .map { it.name }
                .sorted()
            require(slots in 1..minOf(16, availableCivilizations.size)) {
                "Human slots must fit the selected ruleset's playable factions."
            }
            val requestedPassword = password.text.takeIf(String::isNotEmpty)
            require(requestedPassword == null || requestedPassword.length in 12..256) {
                "Private match passwords must contain 12-256 characters."
            }
            val manifest = manifests[rulesetSelect.selectedIndex]
            val setup = createDefaultApiV3GameSetup(
                selectedRuleset,
                factionSelect.selected,
                slots,
            )
            val meaning = AuthoritativeCreationMeaning(
                manifest.baseRuleset.name,
                manifest.mods.mapTo(linkedSetOf()) { it.name },
                setup,
            )
            createButton.disable()
            createButton.setText(Constants.working)
            val progress = Popup(this).apply {
                addGoodSizedLabel("Creating server lobby...").row()
                open()
            }
            Concurrency.runOnNonDaemonThreadPool("Create V3 lobby") {
                try {
                    val creation = session.createAuthoritativeGame(
                        meaning.baseRulesetName,
                        meaning.modNames,
                        meaning.setup,
                        retryState.operationIdFor(meaning),
                        displayName,
                        slots,
                        requestedPassword,
                        availableCivilizations,
                    )
                    val lobby = session.lobby(creation.metadata.gameId)
                    launchOnGLThread {
                        progress.close()
                        game.replaceCurrentScreen(AuthoritativeLobbyScreen(lobby, session))
                    }
                } catch (exception: Exception) {
                    Log.error("Error while creating authoritative lobby", exception)
                    launchOnGLThread {
                        progress.reuseWith(
                            authoritativeLobbyErrorMessage(
                                exception,
                                "Could not create the server lobby.",
                            ),
                            true,
                        )
                        createButton.enable()
                        createButton.setText("Create lobby")
                    }
                }
            }
        } catch (exception: Exception) {
            ToastPopup(exception.message ?: "Check the labeled lobby fields.", this)
        }
    }

    private fun rulesetFor(manifest: ApiV3RulesetManifestSummary): Ruleset =
        RulesetCache.getComplexRuleset(
            manifest.mods.mapTo(linkedSetOf()) { it.name },
            manifest.baseRuleset.name,
        )

    private fun manifestLabel(manifest: ApiV3RulesetManifestSummary) = buildString {
        append(manifest.baseRuleset.name)
        if (manifest.mods.isNotEmpty()) {
            append(" + ")
            append(manifest.mods.joinToString { it.name })
        }
    }
}
