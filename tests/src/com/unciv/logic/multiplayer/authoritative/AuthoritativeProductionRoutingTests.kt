package com.unciv.logic.multiplayer.authoritative

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class AuthoritativeProductionRoutingTests {
    @Test
    fun everyApiV3TransportDataClassHasASerializer() {
        val lines = sourceFile(
            "core/src/com/unciv/logic/multiplayer/authoritative/ApiV3Contracts.kt",
        ).readLines()
        val missing = lines.mapIndexedNotNull { index, line ->
            if (!line.startsWith("data class ApiV3")) return@mapIndexedNotNull null
            val annotation = lines.subList(0, index)
                .asReversed()
                .firstOrNull { it.isNotBlank() }
            (index + 1).takeUnless { annotation == "@Serializable" }
        }

        assertTrue("API-v3 DTOs without @Serializable at lines $missing", missing.isEmpty())
    }

    @Test
    fun singlePlayerNewGameScreenContainsNoAuthoritativeMultiplayerBranch() {
        val singlePlayer = sourceFile(
            "core/src/com/unciv/ui/screens/newgamescreen/NewGameScreen.kt",
        ).readText()
        val multiplayer = sourceFile(
            "core/src/com/unciv/ui/screens/multiplayerscreens/MultiplayerScreen.kt",
        ).readText()
        val creation = sourceFile(
            "core/src/com/unciv/ui/screens/multiplayerscreens/" +
                "AuthoritativeCreateLobbyScreen.kt",
        ).readText()

        assertFalse(singlePlayer.contains("authoritative", ignoreCase = true))
        assertFalse(singlePlayer.contains("isOnlineMultiplayer = true"))
        assertTrue(multiplayer.contains("AuthoritativeCreateLobbyScreen("))
        assertTrue(creation.contains("session.createAuthoritativeGame("))
        assertFalse(creation.contains("GameStarter"))
        assertFalse(creation.contains("GameInfo"))
    }

    @Test
    fun productionNewGameUiCannotCreateLegacyWholeSaveGames() {
        val options = sourceFile(
            "core/src/com/unciv/ui/screens/newgamescreen/GameOptionsTable.kt",
        ).readText()
        val players = sourceFile(
            "core/src/com/unciv/ui/screens/newgamescreen/PlayerPickerTable.kt",
        ).readText()
        val newGame = sourceFile(
            "core/src/com/unciv/ui/screens/newgamescreen/NewGameScreen.kt",
        ).readText()

        assertFalse(options.contains("showDropboxWarning"))
        assertFalse(options.contains("\"Online Multiplayer\""))
        assertFalse(players.contains("Player ID from clipboard"))
        assertFalse(newGame.contains("checkConnectionToMultiplayerServer"))
        assertFalse(newGame.contains("onlineMultiplayer.legacy"))
        assertFalse(newGame.contains("Game ID copied to clipboard"))
    }

    @Test
    fun serverCreatedGameTransitionsIntoPregameLobbyBeforeProjectionOnlyWorld() {
        val creation = sourceFile(
            "core/src/com/unciv/ui/screens/multiplayerscreens/" +
                "AuthoritativeCreateLobbyScreen.kt",
        ).readText()

        assertTrue(creation.contains("manifests.firstOrNull()"))
        assertTrue(creation.contains("game.replaceCurrentScreen(AuthoritativeLobbyScreen("))
        assertFalse(creation.contains("openGame("))
        assertFalse(creation.contains("AuthoritativeWorldScreen("))
        assertTrue(creation.contains("manifest.baseRuleset.name"))
        assertTrue(creation.contains("manifest.mods.mapTo"))
        assertTrue(creation.contains("meaning.baseRulesetName"))
        assertTrue(creation.contains("meaning.modNames"))
        assertFalse(creation.contains("GameStarter"))
        assertFalse(creation.contains("GameInfo"))
        assertFalse(creation.contains("uploadGame"))
    }

    @Test
    fun productionStartupRestoresOnlyOsProtectedServerScopedCredentials() {
        val game = sourceFile("core/src/com/unciv/UncivGame.kt").readText()
        val platform = sourceFile("core/src/com/unciv/utils/PlatformSpecific.kt").readText()
        val windows = sourceFile(
            "desktop/src/com/unciv/app/desktop/WindowsApiV3SessionTokenStore.kt",
        ).readText()
        val macOs = sourceFile(
            "desktop/src/com/unciv/app/desktop/MacOsApiV3SessionTokenStore.kt",
        ).readText()
        val linux = sourceFile(
            "desktop/src/com/unciv/app/desktop/LinuxApiV3SessionTokenStore.kt",
        ).readText()
        val desktopRouting = sourceFile(
            "desktop/src/com/unciv/app/desktop/DesktopGame.kt",
        ).readText()
        val android = sourceFile(
            "android/src/com/unciv/app/AndroidApiV3SessionTokenStore.kt",
        ).readText()

        assertTrue(game.contains("restoreConfiguredAuthoritativeSession("))
        assertTrue(game.contains("::createApiV3SessionTokenStore"))
        assertTrue(platform.contains("createApiV3SessionTokenStore(serverBaseUrl: String)"))
        assertTrue(windows.contains("Crypt32Util.cryptProtectData"))
        assertTrue(windows.contains("Crypt32Util.cryptUnprotectData"))
        assertFalse(windows.contains("writeString"))
        assertTrue(macOs.contains("SecKeychainAddGenericPassword"))
        assertTrue(macOs.contains("SecKeychainFindGenericPassword"))
        assertTrue(linux.contains("\"secret-tool\""))
        assertTrue(linux.contains("process.outputStream"))
        assertFalse(linux.contains("environment()["))
        assertTrue(desktopRouting.contains("MacOsApiV3SessionTokenStore(scope)"))
        assertTrue(desktopRouting.contains("LinuxApiV3SessionTokenStore.create(scope)"))
        assertTrue(android.contains("AndroidKeyStore"))
        assertTrue(android.contains("AES/GCM/NoPadding"))
        assertTrue(android.contains("apiV3CredentialScope(serverBaseUrl)"))
        assertFalse(android.contains("putString(CIPHERTEXT"))
    }

    @Test
    fun productionMultiplayerRoutesOnlyThroughTheConfiguredAuthoritativeServer() {
        val source = sourceFile(
            "core/src/com/unciv/ui/screens/multiplayerscreens/MultiplayerScreen.kt",
        ).readText()

        assertTrue(source.contains("Server URL or IP"))
        assertTrue(source.contains("restoreConfiguredAuthoritativeSession"))
        assertTrue(source.contains("AuthoritativeGameDirectory(activeSession).open(summary)"))
        assertFalse(source.contains("selectedGame"))
        assertFalse(source.contains("downloadGame"))
        assertFalse(source.contains("multiplayerFiles"))
    }

    @Test
    fun supportedClientLabelsAuthorityAndKeepsOfflineProjectionReadOnly() {
        val multiplayer = sourceFile(
            "core/src/com/unciv/ui/screens/multiplayerscreens/MultiplayerScreen.kt",
        ).readText()
        val world = sourceFile(
            "core/src/com/unciv/ui/screens/multiplayerscreens/" +
                "AuthoritativeWorldScreen.kt",
        ).readText()

        assertTrue(multiplayer.contains("LobbyChrome.caption(\"Multiplayer\")"))
        assertTrue(multiplayer.contains("Open staging rooms"))
        assertTrue(multiplayer.contains("Your matches"))
        assertFalse(multiplayer.contains("legacy saved game", ignoreCase = true))
        assertTrue(world.contains("Retry uncertain action"))
        assertTrue(world.contains("retryPendingIfOpen"))
        assertTrue(world.contains("Reconnect and replace cached projection"))
        assertTrue(world.contains("cached projection is read-only"))
        assertTrue(world.contains("busy || !controller.canAcceptProjectedInput"))
    }

    @Test
    fun lobbyJoinTransitionsThroughServerMembershipIntoProjectionOnlyWorld() {
        val multiplayer = sourceFile(
            "core/src/com/unciv/ui/screens/multiplayerscreens/MultiplayerScreen.kt",
        ).readText()
        val lobby = sourceFile(
            "core/src/com/unciv/ui/screens/multiplayerscreens/AuthoritativeLobbyScreen.kt",
        ).readText()

        assertTrue(lobby.contains("session.joinLobby("))
        assertTrue(lobby.contains("\"Civilization\""))
        assertTrue(lobby.contains("Join match"))
        assertTrue(multiplayer.contains("AuthoritativeGameDirectory(activeSession).open(summary)"))
        assertTrue(multiplayer.contains("AuthoritativeWorldScreen("))
        assertFalse(multiplayer.contains("GameInfo") || lobby.contains("GameInfo"))
        assertFalse(multiplayer.contains("GameStarter") || lobby.contains("GameStarter"))
        assertFalse(multiplayer.contains("playerId") || lobby.contains("playerId"))
    }

    @Test
    fun stagedLobbyUsesServerRulesetsAndShowsTheCompleteSetupInRealtime() {
        val multiplayer = sourceFile(
            "core/src/com/unciv/ui/screens/multiplayerscreens/MultiplayerScreen.kt",
        ).readText()
        val creation = sourceFile(
            "core/src/com/unciv/ui/screens/multiplayerscreens/" +
                "AuthoritativeCreateLobbyScreen.kt",
        ).readText()
        val lobby = sourceFile(
            "core/src/com/unciv/ui/screens/multiplayerscreens/AuthoritativeLobbyScreen.kt",
        ).readText()
        val editor = sourceFile(
            "core/src/com/unciv/ui/screens/multiplayerscreens/" +
                "AuthoritativeGameSetupEditor.kt",
        ).readText()
        val defaults = sourceFile(
            "core/src/com/unciv/logic/multiplayer/authoritative/" +
                "ApiV3SetupDefaults.kt",
        ).readText()

        assertTrue(multiplayer.contains("listRulesetManifests()"))
        assertTrue(multiplayer.contains("AuthoritativeCreateLobbyScreen(manifests, activeSession)"))
        assertTrue(creation.contains("CREATE MULTIPLAYER LOBBY  •  STEP 1 OF 2"))
        assertTrue(lobby.contains("LobbyChrome.caption(\"Staging room\")"))
        for (label in listOf(
            "Ruleset",
            "Map shape",
            "Game seed",
            "Mirroring",
            "Resources",
            "Difficulty",
            "Game speed",
            "Starting era",
            "Major civilizations",
            "City-states",
            "Turn limit",
            "VICTORY CONDITIONS",
            "Map generation",
            "Terrain scale",
            "Advanced rules",
        )) assertTrue(
            "Missing lobby setup field: $label",
            lobby.contains("\"$label\"") || editor.contains("\"$label\""),
        )
        assertTrue(lobby.contains("session.observeLobby(lobby.gameId)"))
        assertTrue(lobby.contains("Timer.schedule(refreshTask"))
        assertTrue(lobby.contains("isNarrowerThan4to3()"))
        assertTrue(defaults.contains("ownerCivilizationId = ownerCivilizationId"))
        assertTrue(defaults.contains("humanSlots"))
        assertTrue(defaults.contains("filterNot { it.hiddenInVictoryScreen }"))
        assertTrue(editor.contains("filterNot { it.hiddenInVictoryScreen }"))
        assertTrue(editor.contains("TabbedPager.IPageExtensions"))
        // The deprecated hideList overload must not come back under -Werror.
        assertTrue(editor.contains("forEach(SelectBox<*>::hideScrollPane)"))
        assertFalse(editor.contains("hideList"))
        assertFalse(multiplayer.contains("NewGameScreen"))
        assertFalse(lobby.contains("NewGameScreen"))
    }

    /**
     * Owner settings reach the room without an explicit save, and every edit is
     * still one revisioned canonical mutation rather than a lobby JSON patch.
     */
    @Test
    fun ownerLobbyEditsAutoCommitAsDebouncedRevisionsInsteadOfAnApplyButton() {
        val lobby = sourceFile(
            "core/src/com/unciv/ui/screens/multiplayerscreens/AuthoritativeLobbyScreen.kt",
        ).readText()
        val editor = sourceFile(
            "core/src/com/unciv/ui/screens/multiplayerscreens/" +
                "AuthoritativeGameSetupEditor.kt",
        ).readText()
        val roomEditor = sourceFile(
            "core/src/com/unciv/ui/screens/multiplayerscreens/" +
                "AuthoritativeLobbyConfigurationEditor.kt",
        ).readText()

        // Every editable control raises the edit hook, and the screen coalesces.
        assertTrue(editor.contains("control.onChange { onEdited() }"))
        assertTrue(roomEditor.contains("control.onChange { onEdited() }"))
        assertTrue(lobby.contains("AuthoritativeLobbyConfigurationEditor(lobby) { scheduleConfigurationCommit() }"))
        assertTrue(lobby.contains("pendingConfigurationCommit?.cancel()"))
        assertTrue(lobby.contains("COMMIT_DEBOUNCE_SECONDS"))
        // A no-op edit must not burn a revision or reset everyone's readiness.
        assertTrue(lobby.contains("if (update.matches(lobby))"))
        // The owner's open controls must survive a live refresh.
        assertTrue(lobby.contains("private fun refreshPanels()"))
        assertTrue(lobby.contains("if (roleChanged) buildRoom()"))
        assertFalse(lobby.contains("Apply lobby settings"))
        // An unrelated action must not discard a pending owner edit: the debounce
        // task is cleared where it fires, never in the generic action path.
        val commitBody = lobby.substring(
            lobby.indexOf("private fun commitConfiguration()"),
            lobby.indexOf("private fun readOnlyGamePage()"),
        )
        val actionBody = lobby.substring(
            lobby.indexOf("        action: suspend () -> ApiV3Lobby,"),
            lobby.indexOf("private fun openStartedGame()"),
        )
        assertTrue(commitBody.contains("pendingConfigurationCommit = null"))
        assertFalse(actionBody.contains("pendingConfigurationCommit = null"))
    }

    /**
     * Nation portraits resolve their atlas and ring colours through the globally
     * selected ruleset, so a modded room must select its own before drawing them.
     */
    @Test
    fun lobbySurfacesSelectTheirOwnRulesetAtlasBeforeDrawingNationPortraits() {
        for (name in listOf("AuthoritativeLobbyScreen", "AuthoritativeCreateLobbyScreen")) {
            val source = sourceFile(
                "core/src/com/unciv/ui/screens/multiplayerscreens/$name.kt",
            ).readText()
            assertTrue(
                "$name draws nation portraits without selecting its ruleset atlas",
                source.contains("ImageGetter.setNewRuleset("),
            )
            assertTrue(source.contains("ignoreIfModsAreEqual = true"))
        }
        val chrome = sourceFile(
            "core/src/com/unciv/ui/screens/multiplayerscreens/AuthoritativeLobbyChrome.kt",
        ).readText()
        // Text colours come from the lobby's own ruleset, not the global atlas.
        assertTrue(chrome.contains("ruleset.nations[civilizationId]"))
        assertTrue(chrome.contains("nation.getInnerColor()"))
    }

    /**
     * The pregame map is a read of the committed revision. The client never
     * generates it, and never re-reads it on every poll.
     */
    @Test
    fun lobbyMapPreviewIsAReadOnlyProjectionOfTheCommittedRevision() {
        val lobby = sourceFile(
            "core/src/com/unciv/ui/screens/multiplayerscreens/AuthoritativeLobbyScreen.kt",
        ).readText()
        val preview = sourceFile(
            "core/src/com/unciv/ui/screens/multiplayerscreens/" +
                "AuthoritativeLobbyMapPreview.kt",
        ).readText()
        val session = sourceFile(
            "core/src/com/unciv/logic/multiplayer/authoritative/" +
                "AuthoritativeMultiplayerSession.kt",
        ).readText()
        val client = sourceFile(
            "core/src/com/unciv/logic/multiplayer/authoritative/ApiV3Client.kt",
        ).readText()

        assertTrue(session.contains("suspend fun lobbyMapPreview(gameId: String)"))
        assertTrue(session.contains("preview.terrain.isConsistent()"))
        assertTrue(client.contains("api/v3/lobbies/\$gameId/map-preview"))
        assertTrue(lobby.contains("session.lobbyMapPreview(lobby.gameId)"))
        // Fetched once per committed revision, not once per 1.5s reconciliation.
        assertTrue(lobby.contains("renderedPreviewRevision == lobby.lobbyRevision"))
        // The client renders the server's terrain; it must never generate a map.
        for (forbidden in listOf("MapGenerator", "GameInfo", "GameStarter")) {
            assertFalse(preview.contains("import com.unciv.logic.$forbidden"))
            assertFalse(preview.contains("import com.unciv.logic.map.mapgenerator.$forbidden"))
            assertFalse(preview.contains("$forbidden("))
            assertFalse(lobby.contains("$forbidden("))
        }
        // An unresolvable modded terrain name must degrade, not throw.
        assertTrue(preview.contains("ruleset.terrains::containsKey"))
        assertTrue(preview.contains("greyOutTerrainThisClientCannotName"))
    }

    /** Chat must be reachable from the staging room, not only from a started game. */
    @Test
    fun stagingRoomExposesRoomChatWithoutLeavingTheLobby() {
        val lobby = sourceFile(
            "core/src/com/unciv/ui/screens/multiplayerscreens/AuthoritativeLobbyScreen.kt",
        ).readText()
        val chat = sourceFile(
            "core/src/com/unciv/ui/screens/multiplayerscreens/" +
                "AuthoritativeLobbyChatPanel.kt",
        ).readText()

        assertTrue(lobby.contains("AuthoritativeLobbyChatPanel("))
        assertTrue(lobby.contains("session.chatCoordinator()"))
        assertTrue(chat.contains("coordinator.send(gameId, body)"))
        assertTrue(chat.contains("coordinator.refresh(gameId)"))
        assertFalse(chat.contains("GameInfo"))
    }

    @Test
    fun productionMultiplayerHasNoLegacyOrTimedTurnControls() {
        val source = sourceFile(
            "core/src/com/unciv/ui/screens/multiplayerscreens/MultiplayerScreen.kt",
        ).readText()
        val preferences = sourceFile(
            "core/src/com/unciv/ui/popups/options/MultiplayerTab.kt",
        ).readText()
        val settings = sourceFile(
            "core/src/com/unciv/models/metadata/GameSettings.kt",
        ).readText()

        for (removed in listOf(
            "Time until skip turn",
            "Total time to play",
            "Time recovered per turn",
            "skipCurrentPlayerTurn",
            "forceResignIfOpen",
            "onlineMultiplayer.legacy",
        )) assertFalse(source.contains(removed))
        assertFalse(preferences.contains("Update the open match every:"))
        assertFalse(preferences.contains("Update the multiplayer game list every:"))
        assertFalse(preferences.contains("Enable multiplayer status button in singleplayer games"))
        assertTrue(preferences.contains("Open account and multiplayer lobbies"))
        assertTrue(settings.contains("Constants.authoritativeMultiplayerServer"))
        assertTrue(settings.contains("server == Constants.uncivXyzServer"))
    }

    @Test
    fun projectionWorldCannotReachCanonicalOrLegacyStateOperations() {
        val source = sourceFile(
            "core/src/com/unciv/ui/screens/multiplayerscreens/" +
                "AuthoritativeWorldScreen.kt",
        ).readText()

        for (forbidden in listOf(
            "import com.unciv.logic.GameInfo",
            "import com.unciv.ui.screens.worldscreen.WorldScreen",
            "GameStarter.",
            "multiplayerServer",
            "multiplayerFiles",
            "downloadGame",
            "uploadGame",
            "loadGame(",
            ".nextTurn(",
        )) {
            assertFalse("Projection world must not reference $forbidden", source.contains(forbidden))
        }
    }

    @Test
    fun wholeSaveMultiplayerRequiresAnExplicitLegacyBoundary() {
        val facade = sourceFile(
            "core/src/com/unciv/logic/multiplayer/Multiplayer.kt",
        ).readText()
        val legacy = sourceFile(
            "core/src/com/unciv/logic/multiplayer/LegacyMultiplayer.kt",
        ).readText()

        assertTrue(facade.contains("val legacy = LegacyMultiplayer()"))
        assertTrue(facade.contains("val authoritativeSession get() = authoritative.session"))
        for (forbidden in listOf(
            "GameInfo",
            "MultiplayerServer",
            "MultiplayerFiles",
            "uploadGame",
            "downloadGame",
            "nextTurn(",
        )) {
            assertFalse("Multiplayer facade must not own $forbidden", facade.contains(forbidden))
        }
        assertTrue(legacy.contains("class LegacyMultiplayer"))
        assertTrue(legacy.contains("multiplayerServer.uploadGame"))
        assertTrue(legacy.contains("multiplayerServer.downloadGame"))
        assertTrue(legacy.contains("gameInfo.nextTurn()"))

        val directAccess = Regex("""onlineMultiplayer\.([A-Za-z_][A-Za-z0-9_]*)""")
        val allowedFacadeMembers = setOf(
            "authoritativeSession",
            "authoritativeStatus",
            "authoritativeFailureMessage",
            "clearAuthoritativeSession",
            "changeAuthoritativePassword",
            "close",
            "deleteAuthoritativeAccount",
            "disableAuthoritativeAccount",
            "isInitialized",
            "legacy",
            "loginAuthoritative",
            "logoutAllAuthoritative",
            "logoutAuthoritative",
            "recoverAuthoritative",
            "registerAuthoritative",
            "replaceAuthoritativeRecoveryCodes",
            "restoreConfiguredAuthoritativeSession",
        )
        val violations = sourceDirectory("core/src")
            .walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .flatMap { file ->
                directAccess.findAll(file.readText()).map { match ->
                    "${file.relativeTo(workspaceFile(""))}:${match.value}"
                }
            }
            .filter { access -> access.substringAfterLast(".") !in allowedFacadeMembers }
            .toList()

        assertTrue(
            "Every whole-save multiplayer access must opt into .legacy: $violations",
            violations.isEmpty(),
        )
    }

    @Test
    fun apiV3EndTurnExistsOnlyInProjectionWorldRouting() {
        val legacyWorld = sourceFile(
            "core/src/com/unciv/ui/screens/worldscreen/WorldScreen.kt",
        ).readText()
        val projectionWorld = sourceFile(
            "core/src/com/unciv/ui/screens/multiplayerscreens/" +
                "AuthoritativeWorldScreen.kt",
        ).readText()

        assertFalse(legacyWorld.contains("authoritativeSession"))
        assertFalse(legacyWorld.contains("endTurnIfOpen"))
        assertFalse(legacyWorld.contains("PendingEndTurnAction"))
        assertTrue(projectionWorld.contains("session.endTurnIfOpen"))
        assertTrue(projectionWorld.contains("controller.submitEndTurn()"))
    }

    @Test
    fun apiV3NeverExposesClientSideWholeTurnAutoplay() {
        val projectionWorld = sourceFile(
            "core/src/com/unciv/ui/screens/multiplayerscreens/" +
                "AuthoritativeWorldScreen.kt",
        ).readText()
        val legacyAutoplay = sourceFile(
            "core/src/com/unciv/ui/screens/worldscreen/status/AutoPlayMenu.kt",
        ).readText()
        val commandSchema = sourceFile(
            "protocol/command-envelope-v3.schema.json",
        ).readText()
        val rustCommands = sourceFile(
            "authoritative-server/src/command.rs",
        ).readText()

        for (forbidden in listOf(
            "AutoPlay",
            "TurnManager",
            "NextTurnAutomation",
            "automateTurn(",
            "settings.autoPlay",
        )) {
            assertFalse(
                "Projection-only world must not execute client AI through $forbidden",
                projectionWorld.contains(forbidden),
            )
        }
        assertFalse(legacyAutoplay.contains("authoritativeSession"))
        assertFalse(legacyAutoplay.contains("IfOpen("))
        assertFalse(commandSchema.contains("autoplay", ignoreCase = true))
        assertFalse(rustCommands.contains("autoplay", ignoreCase = true))
    }

    @Test
    fun apiV3DecisionsExistOnlyInProjectionWorldRouting() {
        for (legacyScreen in listOf(
            "pickerscreens/TechPickerScreen.kt",
            "pickerscreens/PolicyPickerScreen.kt",
            "pickerscreens/DiplomaticVotePickerScreen.kt",
            "pickerscreens/GreatPersonPickerScreen.kt",
            "pickerscreens/ReligionPickerScreenCommon.kt",
            "overviewscreen/EspionageOverviewScreen.kt",
            "diplomacyscreen/DiplomacyScreen.kt",
            "diplomacyscreen/MajorCivDiplomacyTable.kt",
            "diplomacyscreen/CityStateDiplomacyTable.kt",
            "diplomacyscreen/TradeTable.kt",
            "worldscreen/TradePopup.kt",
            "cityscreen/BuyButtonFactory.kt",
            "cityscreen/CitizenManagementTable.kt",
            "cityscreen/CityConstructionsTable.kt",
            "cityscreen/CityScreen.kt",
            "cityscreen/CityScreenTileTable.kt",
            "cityscreen/ConstructionInfoTable.kt",
            "cityscreen/SpecialistAllocationTable.kt",
            "worldscreen/AlertPopup.kt",
            "worldscreen/bottombar/BattleTable.kt",
            "worldscreen/status/AutoPlayMenu.kt",
            "worldscreen/status/NextTurnAction.kt",
            "worldscreen/status/NextTurnButton.kt",
            "worldscreen/unit/UnitTable.kt",
            "worldscreen/unit/actions/UnitActions.kt",
            "worldscreen/unit/actions/UnitActionsFromUniques.kt",
            "worldscreen/unit/actions/UnitActionsPillage.kt",
            "worldscreen/unit/actions/UnitActionsTable.kt",
            "worldscreen/worldmap/OverlayButtonData.kt",
            "worldscreen/worldmap/WorldMapHolder.kt",
            "worldscreen/worldmap/WorldMapTileUpdater.kt",
            "pickerscreens/ImprovementPickerScreen.kt",
            "pickerscreens/PromotionPickerScreen.kt",
            "pickerscreens/UnitRenamePopup.kt",
        )) {
            val source = sourceFile(
                "core/src/com/unciv/ui/screens/$legacyScreen",
            ).readText()

            assertFalse("$legacyScreen must not inspect API-v3 sessions", source.contains("authoritativeSession"))
            assertFalse("$legacyScreen must not submit API-v3 commands", source.contains("IfOpen("))
            assertFalse("$legacyScreen must not handle API-v3 outcomes", source.contains("AuthoritativeCommandOutcome"))
        }
        val legacyCityMenu = sourceFile(
            "core/src/com/unciv/ui/popups/CityScreenConstructionMenu.kt",
        ).readText()
        assertFalse(legacyCityMenu.contains("ConstructionQueueAction"))
        assertFalse(legacyCityMenu.contains("Authoritative"))
        val legacyUpgradeMenu = sourceFile(
            "core/src/com/unciv/ui/popups/UnitUpgradeMenu.kt",
        ).readText()
        assertFalse(legacyUpgradeMenu.contains("authoritative"))
        assertFalse(legacyUpgradeMenu.contains("Authoritative"))
        assertFalse(workspaceFile(
            "core/src/com/unciv/ui/screens/worldscreen/worldmap/AuthoritativeCombatUi.kt",
        ).exists())
        assertFalse(workspaceFile(
            "core/src/com/unciv/ui/screens/worldscreen/worldmap/AuthoritativeMovementUi.kt",
        ).exists())

        val decisions = sourceFile(
            "core/src/com/unciv/ui/screens/multiplayerscreens/" +
                "AuthoritativeWorldDecisions.kt",
        ).readText()
        val prompts = sourceFile(
            "core/src/com/unciv/ui/screens/multiplayerscreens/" +
                "AuthoritativePromptPanel.kt",
        ).readText()
        val projectionWorld = sourceFile(
            "core/src/com/unciv/ui/screens/multiplayerscreens/" +
                "AuthoritativeWorldScreen.kt",
        ).readText()

        assertTrue(decisions.contains("controller.acknowledgeProjectedResearchCompletion(prompt.promptId)"))
        // Research and policies are chosen on the real classic pickers - the
        // tech tree and policy screens - which the projection world opens fed
        // by its current projection and routed through the controller.
        assertTrue(projectionWorld.contains("private fun openTechTree()"))
        assertTrue(projectionWorld.contains("private fun openPolicyPicker()"))
        assertTrue(projectionWorld.contains("TechPickerScreen("))
        assertTrue(projectionWorld.contains("PolicyPickerScreen("))
        assertTrue(projectionWorld.contains("controller.commitResearchQueue(queue, ruleset)"))
        assertTrue(projectionWorld.contains("applyProjectionPresentation(controller.projection)"))
        // The flat button lists must not come back.
        assertFalse(decisions.contains("selectableTargets"))
        assertFalse(decisions.contains("adoptProjectedPolicy"))
        assertTrue(prompts.contains("controller.castDiplomaticVote("))
        assertTrue(prompts.contains("controller.chooseGreatPerson("))
        assertTrue(projectionWorld.contains("session.chooseReligiousBeliefsIfOpen("))
        val espionage = sourceFile(
            "core/src/com/unciv/ui/screens/multiplayerscreens/" +
                "AuthoritativeSpyPanel.kt",
        ).readText()
        assertTrue(espionage.contains("controller.move("))
        assertTrue(espionage.contains("controller.setCoup("))
        val diplomacy = sourceFile(
            "core/src/com/unciv/ui/screens/multiplayerscreens/" +
                "AuthoritativeDiplomacyPanel.kt",
        ).readText()
        val trades = sourceFile(
            "core/src/com/unciv/ui/screens/multiplayerscreens/" +
                "AuthoritativeTradePanel.kt",
        ).readText()
        assertTrue(diplomacy.contains("controller.declareWar("))
        assertTrue(diplomacy.contains("controller.giftImprovement("))
        assertTrue(diplomacy.contains("controller.marry("))
        assertTrue(trades.contains("controller.offer("))
        assertTrue(trades.contains("controller.accept("))
        assertTrue(trades.contains("controller.counter("))
        val cityControl = sourceFile(
            "core/src/com/unciv/ui/screens/multiplayerscreens/" +
                "AuthoritativeCityControlPanel.kt",
        ).readText()
        val cityEconomy = sourceFile(
            "core/src/com/unciv/ui/screens/multiplayerscreens/" +
                "AuthoritativeCityEconomyPanel.kt",
        ).readText()
        assertTrue(cityControl.contains("controller.buyTile("))
        assertTrue(cityControl.contains("controller.setTileAssignment("))
        assertTrue(cityControl.contains("controller.setSpecialistCount("))
        assertTrue(cityControl.contains("controller.setGovernance("))
        assertTrue(cityControl.contains("controller.setUnitPromotionPreference("))
        assertTrue(
            sourceFile(
                "core/src/com/unciv/ui/screens/multiplayerscreens/" +
                    "AuthoritativeWorldSessionActions.kt",
            ).readText().contains("session.setCityUnitPromotionPreferenceIfOpen("),
        )
        assertTrue(cityEconomy.contains("controller.selectConstruction("))
        assertTrue(cityEconomy.contains("controller.manageQueues("))
        assertTrue(cityEconomy.contains("controller.purchase("))
        val combat = sourceFile(
            "core/src/com/unciv/ui/screens/multiplayerscreens/" +
                "AuthoritativeCombatPanel.kt",
        ).readText()
        val unitActions = sourceFile(
            "core/src/com/unciv/ui/screens/multiplayerscreens/" +
                "AuthoritativeUnitActionPanel.kt",
        ).readText()
        val unitOrders = sourceFile(
            "core/src/com/unciv/ui/screens/multiplayerscreens/" +
                "AuthoritativeUnitOrderPanel.kt",
        ).readText()
        assertTrue(combat.contains("controller.attack("))
        assertTrue(combat.contains("controller.launchNuclearStrike("))
        assertTrue(combat.contains("controller.bombard("))
        assertTrue(unitActions.contains("controller.useReligiousAction("))
        assertTrue(unitActions.contains("controller.createInstantImprovement("))
        assertTrue(unitActions.contains("controller.triggerUnique("))
        assertTrue(unitOrders.contains("controller.cancelMovement("))
        assertTrue(unitOrders.contains("controller.setImprovementOrder("))
        assertTrue(unitOrders.contains("controller.disband("))
    }

    @Test
    fun everyAuthoritativeGameplaySessionCommandIsReachableFromProductionClientUi() {
        val sessionFile = sourceFile(
            "core/src/com/unciv/logic/multiplayer/authoritative/" +
                "AuthoritativeMultiplayerSession.kt",
        )
        val commandMethods = Regex("""(?:suspend\s+)?fun\s+([A-Za-z0-9]+IfOpen)\s*\(""")
            .findAll(sessionFile.readText())
            .map { it.groupValues[1] }
            .filterNot { it in setOf("cachedProjectionIfOpen", "projectionIfOpen") }
            .toSortedSet()
        val productionSources = sourceDirectory("core/src")
            .walkTopDown()
            .filter { it.isFile && it.extension == "kt" && it != sessionFile }
            .joinToString("\n") { it.readText() }
        val unreachable = commandMethods.filterNot(productionSources::contains)

        assertTrue(
            "Every typed gameplay command needs a production projection-only UI route: " +
                unreachable,
            unreachable.isEmpty(),
        )
    }

    @Test
    fun publicGameplayRouteInventoryExactlyMatchesTheSupportedClientTransport() {
        val openApi = sourceFile("authoritative-server/openapi/api-v3.json").readText()
        val expected = Regex(
            """/api/v3/games/\{game_id\}/commands/([a-z][a-z-]+)""",
        ).findAll(openApi).map { it.groupValues[1] }.toSortedSet()
        val client = sourceFile(
            "core/src/com/unciv/logic/multiplayer/authoritative/ApiV3Client.kt",
        ).readText()
        val literalRoutes = Regex("""commands/([a-z][a-z-]+)""")
            .findAll(client)
            .map { it.groupValues[1] }
        val delegatedDiplomacyRoutes = Regex(
            """diplomacyPartner\(gameId,\s*"([a-z][a-z-]+)"""",
        ).findAll(client).map { it.groupValues[1] }
        val actual = (literalRoutes + delegatedDiplomacyRoutes).toSortedSet()

        assertTrue("OpenAPI must advertise gameplay commands", expected.isNotEmpty())
        assertTrue(
            "Client/server gameplay route drift. Missing=${expected - actual}; " +
                "extra=${actual - expected}",
            actual == expected,
        )
    }

    @Test
    fun revisionedLobbySettingsAndFactionSelectionAreReachableFromProductionUi() {
        val lobby = sourceFile(
            "core/src/com/unciv/ui/screens/multiplayerscreens/" +
                "AuthoritativeLobbyScreen.kt",
        ).readText()
        val editor = sourceFile(
            "core/src/com/unciv/ui/screens/multiplayerscreens/" +
                "AuthoritativeLobbyConfigurationEditor.kt",
        ).readText()
        val setupEditor = sourceFile(
            "core/src/com/unciv/ui/screens/multiplayerscreens/" +
                "AuthoritativeGameSetupEditor.kt",
        ).readText()

        assertTrue(lobby.contains("session.selectLobbyFaction("))
        assertTrue(lobby.contains("session.reconfigureLobby("))
        assertTrue(lobby.contains("AuthoritativeLobbyConfigurationEditor(lobby)"))
        assertTrue(lobby.contains("pager.addPage(\"World\""))
        assertTrue(lobby.contains("pager.addPage(\"Victories\""))
        assertTrue(lobby.contains("pager.addPage(\"Advanced\""))
        assertTrue(editor.contains("ROOM & ACCESS"))
        assertTrue(editor.contains("AuthoritativeGameSetupEditor("))
        assertFalse(editor.contains("NewGameScreen"))
        assertFalse(setupEditor.contains("import com.unciv.logic.GameInfo"))
        assertFalse(lobby.contains("GameInfo"))
        assertFalse(lobby.contains("GameStarter"))
    }

    /**
     * Every multiplayer surface must build skin-bound tables and add actors, never
     * the skin-dependent `Table.add(String)` overload, and must never read `stage`
     * from inside a `Table.apply` block where it resolves to the actor's own
     * (still null) stage instead of the screen's.
     */
    @Test
    fun lobbySurfacesAreSkinSafeAndNeverReadAnAmbiguousStage() {
        val surfaces = listOf(
            "AuthoritativeLobbyScreen",
            "AuthoritativeCreateLobbyScreen",
            "AuthoritativeLobbyChatPanel",
            "AuthoritativeLobbyChrome",
            "AuthoritativeLobbyMapPreview",
            "MultiplayerScreen",
        ).associateWith {
            sourceFile("core/src/com/unciv/ui/screens/multiplayerscreens/$it.kt").readText()
        }

        for ((name, source) in surfaces) {
            assertFalse("$name must not build an unskinned Table", source.contains("Table()."))
            assertFalse("$name must not build an unskinned Table", source.contains("Table())"))
            // A Table cell is always chained; a bare list add never is.
            assertFalse(
                "$name must not add a raw String to a Table",
                Regex("""add\("[^"]*"\)\s*\.""").containsMatchIn(source),
            )
            assertFalse(
                "$name must not read stage width inside a Table.apply block",
                source.contains("width(stage.width"),
            )
        }
        val lobby = surfaces.getValue("AuthoritativeLobbyScreen")
        assertTrue(lobby.contains("this@AuthoritativeLobbyScreen.stage.width"))
        // The header is composed of actors, and states the host and occupancy.
        assertTrue(lobby.contains("Host [\${lobby.ownerUsername}]"))
        assertTrue(lobby.contains(").toLabel(LobbyChrome.muted),"))
    }

    /**
     * The playtest crashed on the lobby -> world and browser -> world hops, so
     * both routes are pinned here: only the owner may start a lobby, only at
     * exact capacity with every human ready, and the world screen is always
     * reached with the directory's player projection - never a GameInfo.
     */
    @Test
    fun lobbyToWorldRoutingOpensOnlyStartedPlayerProjections() {
        val lobby = sourceFile(
            "core/src/com/unciv/ui/screens/multiplayerscreens/AuthoritativeLobbyScreen.kt",
        ).readText()
        val multiplayer = sourceFile(
            "core/src/com/unciv/ui/screens/multiplayerscreens/MultiplayerScreen.kt",
        ).readText()
        val world = sourceFile(
            "core/src/com/unciv/ui/screens/multiplayerscreens/AuthoritativeWorldScreen.kt",
        ).readText()

        // Both entry points route through the same projection-only opening.
        assertTrue(lobby.contains("AuthoritativeWorldScreen("))
        assertTrue(lobby.contains("opened.projection"))
        assertTrue(lobby.contains("opened is OpenedAuthoritativeGame.Player"))
        assertTrue(multiplayer.contains("is OpenedAuthoritativeGame.Player"))
        assertTrue(multiplayer.contains("opened.projection"))
        assertTrue(multiplayer.contains("AuthoritativeWorldScreen("))
        // The world screen is a projection surface, not a save/simulation surface.
        assertTrue(world.contains("ApiV3GameProjection"))
        assertTrue(world.contains("AuthoritativeWorldController"))
        assertTrue(world.contains("OpenedAuthoritativeGame"))
        assertFalse(world.contains("import com.unciv.logic.GameInfo"))
        assertFalse(world.contains("GameStarter"))
        // Only the owner may start, and only at exact capacity with every human ready.
        assertTrue(lobby.contains("session.startLobby(lobby)"))
        assertTrue(lobby.contains("lobby.occupiedSlots == lobby.humanSlots"))
        assertTrue(lobby.contains("lobby.members.all { it.ready && it.civilizationId.isNotBlank() }"))
        assertTrue(lobby.contains("Every human slot must be filled, assigned a civilization, and ready."))
        assertTrue(lobby.contains("Everyone is ready."))
        assertTrue(lobby.contains("Only the host can start the match."))
    }

    /**
     * The playtest crashed the world screen with `Table must have a skin set`
     * because of two Kotlin/gdx traps that are invisible to the compiler:
     * 1. `"a" + "b" + "c".toLabel()` re-coerces the whole expression back to a
     *    String (`String.plus(Any)`), which then reaches `Table.add(CharSequence)`
     *    on a skinless table.
     * 2. a bare `skin` inside `Table().apply { }` resolves to the table's own
     *    null skin field instead of `BaseScreen.skin`.
     * These guards keep the fixed world surfaces from regressing into either trap.
     */
    @Test
    fun worldSurfacesAreSkinSafeAndNeverCoerceLabelsBackToStrings() {
        val surfaces = listOf(
            "AuthoritativeWorldScreen",
            "AuthoritativeWorldDecisions",
            "AuthoritativeSpyPanel",
            "AuthoritativeTradePanel",
        ).associateWith {
            sourceFile("core/src/com/unciv/ui/screens/multiplayerscreens/$it.kt").readText()
        }

        // A `+` whose continuation ends in "...".toLabel() is the Kotlin
        // precedence bug: the trailing fragment is a Label, the sum is a String.
        val coercedLabel = Regex("""\+[^()]*"[^"\n]*"\.toLabel\(\)""")
        // A bare `skin` reads the Actor's own null field, not BaseScreen.skin.
        val bareSkin = Regex("""(?<![.\w])skin(?![.\w])""")
        for ((name, source) in surfaces) {
            assertFalse(
                "$name must wrap concatenated text in parens before .toLabel()",
                coercedLabel.containsMatchIn(source),
            )
            assertFalse("$name must not use the ambiguous bare `skin`", bareSkin.containsMatchIn(source))
        }

        // The three panel files only build tables for the world screen, so a raw
        // string literal straight into add() is always a Table.add(CharSequence).
        // (AuthoritativeWorldScreen also builds a MutableList in tileText, so it
        // is excluded here and pinned positively below instead.)
        val rawLiteralAdd = Regex("""add\("[^"]*"\)""")
        for (name in listOf(
            "AuthoritativeWorldDecisions",
            "AuthoritativeSpyPanel",
            "AuthoritativeTradePanel",
        )) {
            assertFalse(
                "$name must not add a raw string literal to a Table",
                rawLiteralAdd.containsMatchIn(surfaces.getValue(name)),
            )
        }

        // The fixed form: the header is one parenthesized concat turned into a
        // single Label. The map itself no longer builds skinned text buttons —
        // it is drawn by the game's real hex renderer, pinned separately below.
        assertTrue(surfaces.getValue("AuthoritativeWorldScreen").contains("Server game [\${controller.current.gameId}]"))
        assertTrue(surfaces.getValue("AuthoritativeWorldScreen").contains(").toLabel(),"))
    }

    /**
     * The online world must look like the game, not like a debug harness. It is
     * drawn by the same hex renderer single-player uses, over a disposable
     * TileMap materialized from the server projection — never a text grid, and
     * still never a `GameInfo`.
     */
    @Test
    fun projectionWorldRendersWithTheRealHexRenderer() {
        val world = sourceFile(
            "core/src/com/unciv/ui/screens/multiplayerscreens/AuthoritativeWorldScreen.kt",
        ).readText()
        val map = sourceFile(
            "core/src/com/unciv/ui/screens/multiplayerscreens/" +
                "AuthoritativeProjectionWorldMap.kt",
        ).readText()

        assertTrue(world.contains("AuthoritativeProjectionMapHolder("))
        assertTrue(map.contains("WorldTileGroup("))
        assertTrue(map.contains("TileGroupMap("))
        // Fog of war stays server-decided rather than force-revealed.
        assertTrue(map.contains("viewer.viewableTiles"))
        assertTrue(map.contains("tile.setExplored(viewer, true)"))
        assertFalse(map.contains("isForceVisible = true"))
        for (forbidden in listOf(
            "import com.unciv.logic.GameInfo",
            "import com.unciv.ui.screens.worldscreen.WorldScreen",
            "GameStarter",
            "MapGenerator",
        )) assertFalse("Projection map must not reference $forbidden", map.contains(forbidden))
    }

    /**
     * The online match plays on the same kind of surface as a local one: the hex
     * map fills the screen under floating chrome - top bar, unit dock, End turn,
     * tile readout - and every server-advertised decision lives in one slide-in
     * panel instead of a permanent stack of buttons. The screen also hosts the
     * HUD seam for widgets that consult `GUI.isAllowedChangeState`, and answers
     * false once it is no longer the host.
     */
    @Test
    fun projectionWorldUsesAFloatingSinglePlayerStyleHudInsteadOfAButtonStack() {
        val world = sourceFile(
            "core/src/com/unciv/ui/screens/multiplayerscreens/AuthoritativeWorldScreen.kt",
        ).readText()

        // A fullscreen surface with floating chrome, not a picker table stack.
        assertTrue(world.contains("BaseScreen(), WorldHudHost, RecreateOnResize"))
        assertFalse(world.contains(": PickerScreen"))
        assertTrue(world.contains("stage.addActor(mapHolder)"))
        assertTrue(world.contains("mapHolder.setSize(stage.width, stage.height)"))
        for (widget in listOf("topBar", "unitDock", "endTurnButton", "sidePanel")) {
            assertTrue("Missing world HUD widget: $widget", world.contains("private val $widget"))
        }
        // The HUD seam is owned by this screen while it hosts it, and by nobody
        // afterwards.
        assertTrue(world.contains("GUI.hudHost = this"))
        assertTrue(world.contains("if (GUI.hudHost === this) GUI.hudHost = null"))
    }

    /**
     * The staging room must survive a device rotation or window resize, name
     * factions by what players recognize - the leader display name - while every
     * session call keeps using the server civilization ID, and show the AI seats
     * in the same roster as the humans.
     */
    @Test
    fun stagingRoomSurvivesResizeNamesFactionsByLeaderAndShowsAiSeats() {
        val lobby = sourceFile(
            "core/src/com/unciv/ui/screens/multiplayerscreens/AuthoritativeLobbyScreen.kt",
        ).readText()

        assertTrue(lobby.contains("PickerScreen(), RecreateOnResize"))
        assertTrue(lobby.contains("override fun recreate(): BaseScreen = AuthoritativeLobbyScreen(lobby, session)"))
        // The Civ-style leader-portrait grid replaced the raw-ID dropdown;
        // only server civilization IDs cross the transport.
        assertTrue(lobby.contains("private fun civilizationGrid("))
        assertTrue(lobby.contains("LobbyChrome.nationBadge(ruleset, id, 44f)"))
        assertFalse(lobby.contains("SelectBox<String>(skin)"))
        assertTrue(lobby.contains("renderAiSeats()"))
        assertTrue(lobby.contains("Server-run"))
        // Live-room feel: join/leave announcements and click feedback.
        assertTrue(lobby.contains("announceMembershipChanges(refreshed)"))
        assertTrue(lobby.contains("UncivSound.Click"))
    }

    /**
     * The online world gets the game's own minimap, and its city panel opens
     * the real city screen read-only with server figures fed in - a client
     * without canonical state must never compute costs locally.
     */
    @Test
    fun projectionWorldHasTheRealMinimapAndOpensAProjectionFedReadOnlyCityScreen() {
        val world = sourceFile(
            "core/src/com/unciv/ui/screens/multiplayerscreens/AuthoritativeWorldScreen.kt",
        ).readText()

        // The single-player minimap, over the projection map holder seam, and
        // rebuilt together with every committed revision.
        assertTrue(world.contains("MinimapHolder(this, mapHolder)"))
        assertTrue(world.contains("minimapWrapper.update(null)"))
        assertTrue(world.contains("minimapWrapper.remove()"))

        // The real CityScreen opens read-only and projection-fed.
        assertTrue(world.contains("Open city screen"))
        assertTrue(world.contains("forceReadOnly = true"))
        assertTrue(world.contains("projectedCity = city"))

        // Terminal moment and live chat are world-screen surfaces.
        assertTrue(world.contains("rebuildVictoryOverlay()"))
        assertTrue(world.contains("AuthoritativeGameChatPopup(this, session.chatCoordinator(), gameSummary.gameId)"))
        val decisionsSource = sourceFile(
            "core/src/com/unciv/ui/screens/multiplayerscreens/AuthoritativeWorldDecisions.kt",
        ).readText()
        assertTrue(decisionsSource.contains("AuthoritativeEmpirePanel(controller.projection)"))

        val cityScreen = sourceFile("core/src/com/unciv/ui/screens/cityscreen/CityScreen.kt").readText()
        assertTrue(cityScreen.contains("internal val projectedCity: ProjectedCity? = null"))
        assertTrue(cityScreen.contains("internal val hasCanonicalGame = cityView.city.civ.gameInfoOrNull != null"))
        // Stats that need difficulty are skipped for a projection viewer...
        assertTrue(cityScreen.contains("if (hasCanonicalGame) cityView.updateCityStats()"))
        // ...and paging, recreation, and re-entry keep both read-only flags.
        assertTrue(cityScreen.contains("forceReadOnly = forceReadOnly,\n            projectedCity = projectedCity,"))
        assertTrue(cityScreen.contains("newScreen is WorldHudHost"))

        val constructions = sourceFile(
            "core/src/com/unciv/ui/screens/cityscreen/CityConstructionsTable.kt",
        ).readText()
        // Queue and available list render server figures when projected.
        assertTrue(constructions.contains("updateProjectedConstructionQueue(projected)"))
        assertTrue(constructions.contains("getProjectedConstructionButtonDTOs"))

        val info = sourceFile(
            "core/src/com/unciv/ui/screens/cityscreen/ConstructionInfoTable.kt",
        ).readText()
        // Buy costs are difficulty-scaled canonical figures; gated off.
        assertTrue(info.contains("cityScreen.hasCanonicalGame && buyButtonFactory.hasBuyButtons(construction)"))
    }

    /** The minimap is hosted through a seam, never directly by a WorldScreen. */
    @Test
    fun minimapRendersThroughAMapHolderSeamBothOfflineAndOnline() {
        val minimap = sourceFile(
            "core/src/com/unciv/ui/screens/worldscreen/minimap/Minimap.kt",
        ).readText()
        val holder = sourceFile(
            "core/src/com/unciv/ui/screens/worldscreen/minimap/MinimapHolder.kt",
        ).readText()

        assertTrue(minimap.contains("mapHolder: MinimapMapHolder"))
        assertFalse(minimap.contains("WorldMapHolder"))
        assertTrue(holder.contains("private val host: WorldHudHost"))
        assertFalse(holder.contains("worldScreen.game.settings"))
        assertFalse(holder.contains("GUI.getViewingPlayer()"))
    }

    /**
     * The classic tech-tree and policy screens are the V3 decision surfaces:
     * they render from server-projected figures, gate pickability on the
     * server's advertised sets, and route every adoption through typed
     * commands - never local mutation, and never a session object.
     */
    @Test
    fun classicPickersRenderFromProjectionAndRouteThroughTypedCommands() {
        val tech = sourceFile(
            "core/src/com/unciv/ui/screens/pickerscreens/TechPickerScreen.kt",
        ).readText()
        val policy = sourceFile(
            "core/src/com/unciv/ui/screens/pickerscreens/PolicyPickerScreen.kt",
        ).readText()
        val techManager = sourceFile(
            "core/src/com/unciv/logic/civilization/managers/TechManager.kt",
        ).readText()
        val policyManager = sourceFile(
            "core/src/com/unciv/logic/civilization/managers/PolicyManager.kt",
        ).readText()

        // Projection-fed constructor seams.
        assertTrue(tech.contains("projectedResearch: ProjectedResearch?"))
        assertTrue(tech.contains("onCommitQueue"))
        assertTrue(tech.contains("onSelectFreeTechnology"))
        assertTrue(tech.contains("projectedPickable"))
        assertTrue(policy.contains("projectedPolicies: ProjectedPolicies?"))
        assertTrue(policy.contains("onAdopt"))

        // Committing routes to the caller's command bus instead of mutating.
        assertTrue(tech.contains("onSelectFreeTechnology!!(freeTech)"))
        assertTrue(tech.contains("onCommitQueue!!(tempTechsToResearch.toList())"))
        assertTrue(policy.contains("onAdopt(policy.name)"))
        assertTrue(policy.contains("onAdopt(branch.name)"))

        // No canonical-GameInfo reads may remain in either picker.
        for ((name, source) in listOf("TechPickerScreen" to tech, "PolicyPickerScreen" to policy)) {
            assertFalse("$name must resolve rulesets without a GameInfo", source.contains("gameInfo.ruleset"))
            assertFalse("$name must not read gameInfo.gameParameters directly", source.contains("gameInfo.gameParameters"))
        }
        // Engine ruleset resolution works for a detached civilization too.
        assertTrue(techManager.contains("private fun getRuleset() = civInfo.ruleset"))
        assertTrue(policyManager.contains("fun setPresentationState("))
    }

    @Test
    fun authoritativeAdministrationSeparatesActiveAndClosedLifecycleActions() {
        val source = sourceFile(
            "core/src/com/unciv/ui/screens/multiplayerscreens/" +
                "AuthoritativeAdministrationPopup.kt",
        ).readText()

        assertTrue(source.contains("lifecycleStatus in setOf(\"active\", \"closed\")"))
        assertTrue(source.contains("closeGameButton.isVisible = active"))
        assertTrue(source.contains("archiveGameButton.isVisible = !active"))
        assertFalse(source.contains("forceResignButton"))
        assertFalse(source.contains("timeout"))
    }

    @Test
    fun wholeTurnRewindIsProjectionOnlyAndReachableByEveryHumanPlayer() {
        val popup = sourceFile(
            "core/src/com/unciv/ui/screens/multiplayerscreens/AuthoritativeRewindPopup.kt",
        ).readText()
        val screen = sourceFile(
            "core/src/com/unciv/ui/screens/multiplayerscreens/MultiplayerScreen.kt",
        ).readText()
        val client = sourceFile(
            "core/src/com/unciv/logic/multiplayer/authoritative/ApiV3Client.kt",
        ).readText()

        assertTrue(popup.contains("session.rewindCheckpoints(game.gameId)"))
        assertTrue(popup.contains("session.proposeRewind("))
        assertTrue(popup.contains("session.voteRewind("))
        assertFalse(popup.contains("GameInfo"))
        assertFalse(popup.contains("GameStarter"))
        assertTrue(screen.contains("summary.role in setOf(\"owner\", \"player\")"))
        assertTrue(screen.contains("AuthoritativeRewindPopup("))
        assertTrue(client.contains("rewind-checkpoints"))
        assertTrue(client.contains("rewinds/\$requestId/vote"))
    }

    @Test
    fun productionAccountUiUsesOnlyTypedAccountTransport() {
        val login = sourceFile(
            "core/src/com/unciv/ui/screens/multiplayerscreens/AuthoritativeAccountPopup.kt",
        ).readText()
        val recovery = sourceFile(
            "core/src/com/unciv/ui/screens/multiplayerscreens/" +
                "AuthoritativeAccountRecoveryPopup.kt",
        ).readText()
        val management = sourceFile(
            "core/src/com/unciv/ui/screens/multiplayerscreens/" +
                "AuthoritativeAccountManagementPopup.kt",
        ).readText()
        val combined = login + recovery + management

        for (operation in listOf(
            "loginAuthoritative",
            "registerAuthoritative",
            "recoverAuthoritative",
            "changeAuthoritativePassword",
            "replaceAuthoritativeRecoveryCodes",
            "logoutAllAuthoritative",
            "disableAuthoritativeAccount",
            "deleteAuthoritativeAccount",
        )) {
            assertTrue("Production account UI must route $operation", combined.contains(operation))
        }
        for (forbidden in listOf("GameInfo", "GameStarter", "uploadGame", "worker")) {
            assertFalse("Account UI must not reach $forbidden", combined.contains(forbidden))
        }
        assertTrue(management.contains("clearPasswords()"))
        assertTrue(management.contains("There is no operator or client-save recovery override"))
    }

    @Test
    fun aiRosterIsOwnerAuthoredServerValidatedAndVisibleToEveryMember() {
        val editor = sourceFile(
            "core/src/com/unciv/ui/screens/multiplayerscreens/AuthoritativeGameSetupEditor.kt",
        ).readText()
        val screen = sourceFile(
            "core/src/com/unciv/ui/screens/multiplayerscreens/AuthoritativeLobbyScreen.kt",
        ).readText()

        // The roster is typed server intent, never a locally resolved nation.
        assertTrue(editor.contains("aiCivilizations = roster"))
        assertTrue(editor.contains("addAiSlot"))
        assertTrue(editor.contains("removeAiSlot"))
        assertFalse("The client must not resolve a random AI itself", editor.contains("random()"))
        // Import and call sites only: the class doc names GameInfo to say it
        // never touches one.
        for (forbidden in listOf("GameInfo", "GameStarter", "MapGenerator")) {
            assertFalse(
                "The setup editor must not reach $forbidden",
                editor.contains("import com.unciv.logic.$forbidden") ||
                    editor.contains("$forbidden("),
            )
        }
        // Both roles get an AI page, so a joiner sees the host's roster live.
        assertTrue(screen.contains("pager.addPage(\"AI\", editor.aiPage)"))
        assertTrue(screen.contains("pager.addPage(\"AI\", readOnlyAiPage(lobby.setup))"))
    }

    private fun workspaceFile(path: String): File = generateSequence(
        File(System.getProperty("user.dir")).absoluteFile,
        File::getParentFile,
    ).map { File(it, path) }.first { it.parentFile?.exists() == true }

    private fun sourceFile(path: String): File = workspaceFile(path).takeIf(File::isFile)
        ?: error("Could not locate $path")

    private fun sourceDirectory(path: String): File =
        workspaceFile(path).takeIf(File::isDirectory)
            ?: error("Could not locate $path")
}
