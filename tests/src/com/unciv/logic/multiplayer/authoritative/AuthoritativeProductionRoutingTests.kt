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

        assertTrue(multiplayer.contains("\"MULTIPLAYER\""))
        assertTrue(multiplayer.contains("OPEN LOBBIES"))
        assertTrue(multiplayer.contains("YOUR MATCHES"))
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
        assertTrue(lobby.contains("Your civilization"))
        assertTrue(lobby.contains("Join lobby"))
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
        assertTrue(lobby.contains("MULTIPLAYER LOBBY  •  STEP 2 OF 2"))
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
        assertTrue(editor.contains("forEach(SelectBox<*>::hideList)"))
        assertFalse(multiplayer.contains("NewGameScreen"))
        assertFalse(lobby.contains("NewGameScreen"))
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

        assertTrue(decisions.contains("controller.selectResearch("))
        assertTrue(decisions.contains("controller.manageResearchQueue("))
        assertTrue(decisions.contains("controller::chooseProjectedFreeTechnology"))
        assertTrue(decisions.contains("controller::adoptProjectedPolicy"))
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

    @Test
    fun lobbyHeaderAlwaysAddsAnActorInsteadOfUsingSkinDependentStringOverload() {
        val lobby = sourceFile(
            "core/src/com/unciv/ui/screens/multiplayerscreens/AuthoritativeLobbyScreen.kt",
        ).readText()
        val header = lobby.substring(
            lobby.indexOf("\"Host: [\${lobby.ownerUsername}]"),
            lobby.indexOf("val leftColumn = Table"),
        )

        assertTrue(header.contains(").toLabel()"))
        assertFalse(header.contains("\" human players\".toLabel()"))
        assertFalse(lobby.contains("Table().apply"))
        assertTrue(lobby.contains("this@AuthoritativeLobbyScreen.stage.width"))
        assertFalse(lobby.contains("width(stage.width"))
        val browser = sourceFile(
            "core/src/com/unciv/ui/screens/multiplayerscreens/MultiplayerScreen.kt",
        ).readText()
        assertFalse(browser.contains("Table().apply"))
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
