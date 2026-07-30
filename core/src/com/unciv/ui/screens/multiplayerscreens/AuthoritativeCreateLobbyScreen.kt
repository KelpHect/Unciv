package com.unciv.ui.screens.multiplayerscreens

import com.badlogic.gdx.graphics.Color
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
import com.unciv.ui.components.input.onChange
import com.unciv.ui.components.input.onClick
import com.unciv.ui.components.widgets.AutoScrollPane
import com.unciv.ui.components.widgets.UncivTextField
import com.unciv.ui.popups.Popup
import com.unciv.ui.screens.pickerscreens.PickerScreen
import com.unciv.utils.Concurrency
import com.unciv.utils.Log
import com.unciv.utils.launchOnGLThread

/**
 * API-v3-only first stage. Creation establishes the room, access policy and
 * owner's faction; detailed map/rule settings are then edited in the live room.
 */
class AuthoritativeCreateLobbyScreen(
    private val manifests: List<ApiV3RulesetManifestSummary>,
    private val session: AuthoritativeMultiplayerSession,
    private val retryState: AuthoritativeCreationRetryState = AuthoritativeCreationRetryState(),
) : PickerScreen() {
    private val content = Table(skin).apply { defaults().pad(8f) }
    private val matchName = UncivTextField("Match name", "New multiplayer match")
    private val humanSlots = UncivTextField("Human players", "2")
    private val password = UncivTextField("Optional password (12+ characters)").apply {
        isPasswordMode = true
    }
    private val rulesetSelect = SelectBox<String>(skin)
    private val factionSelect = SelectBox<String>(skin)
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
        createButton.onClick(::createLobby)

        content.top()
        content.add("CREATE MULTIPLAYER LOBBY  •  STEP 1 OF 2".toLabel(Color.GOLD))
            .colspan(2).growX().left().row()
        content.add("Create the room first. Map, rules, victories and advanced settings stay editable in the live lobby."
            .toLabel()).colspan(2).growX().left().padBottom(18f).row()
        addField("Match name", matchName)
        addField("Server ruleset", rulesetSelect)
        addField("Human player slots", humanSlots)
        addField("Private match password", password)
        addField("Your faction", factionSelect)
        content.add("Each player chooses their own unclaimed faction after joining."
            .toLabel(Color.LIGHT_GRAY)).colspan(2).growX().left().row()
        content.add(createButton).colspan(2).growX().padTop(16f).row()
        topTable.add(AutoScrollPane(content)).grow().row()
    }

    private fun addField(label: String, actor: com.badlogic.gdx.scenes.scene2d.Actor) {
        content.add(label.toLabel(Color.LIGHT_GRAY)).left()
        content.add(actor).minWidth(stage.width.coerceAtMost(900f) * 0.55f).growX().row()
    }

    private fun refreshFactions() {
        val factions = selectedRuleset.nations.values
            .asSequence()
            .filter { it.isMajorCiv }
            .map { it.name }
            .sorted()
            .toList()
        check(factions.isNotEmpty()) { "The selected server ruleset has no playable factions." }
        factionSelect.items = com.badlogic.gdx.utils.Array(factions.toTypedArray())
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
            com.unciv.ui.popups.ToastPopup(
                exception.message ?: "Check the labeled lobby fields.",
                this,
            )
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
