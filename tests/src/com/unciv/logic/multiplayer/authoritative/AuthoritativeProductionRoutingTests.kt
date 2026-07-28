package com.unciv.logic.multiplayer.authoritative

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class AuthoritativeProductionRoutingTests {
    @Test
    fun authoritativeCreationReturnsBeforeAnyLocalGameConstruction() {
        val source = sourceFile(
            "core/src/com/unciv/ui/screens/newgamescreen/NewGameScreen.kt",
        ).readText()
        val start = source.substring(
            source.indexOf("private suspend fun startNewGame()"),
            source.indexOf("private fun multiplayerCreationRoute()"),
        )
        val authoritativeBranch =
            start.indexOf("MultiplayerCreationRoute.AuthoritativeApiV3 ->")
        val unavailableBranch =
            start.indexOf("MultiplayerCreationRoute.AuthoritativeUnavailable ->")
        val legacyDisabledBranch =
            start.indexOf("MultiplayerCreationRoute.LegacyCreationDisabled ->")
        val localConstruction = start.indexOf("GameStarter.startNewGame")

        assertTrue(authoritativeBranch >= 0)
        assertTrue(localConstruction > authoritativeBranch)
        assertTrue(localConstruction > unavailableBranch)
        assertTrue(localConstruction > legacyDisabledBranch)
        assertTrue(
            start.substring(authoritativeBranch, localConstruction)
                .contains("return@coroutineScope"),
        )
        assertTrue(
            start.substring(unavailableBranch, localConstruction)
                .contains("return@coroutineScope"),
        )
        assertTrue(
            start.substring(legacyDisabledBranch, localConstruction)
                .contains("return@coroutineScope"),
        )
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
        assertFalse(players.contains("Player ID from clipboard"))
        assertFalse(newGame.contains("checkConnectionToMultiplayerServer"))
        assertTrue(newGame.contains("LegacyCreationDisabled"))
        assertTrue(newGame.contains("Creating new legacy multiplayer games is disabled"))
    }

    @Test
    fun serverCreatedGameTransitionsDirectlyIntoProjectionOnlyWorld() {
        val newGame = sourceFile(
            "core/src/com/unciv/ui/screens/newgamescreen/NewGameScreen.kt",
        ).readText()
        val creation = newGame.substring(
            newGame.indexOf("private suspend fun startAuthoritativeGame("),
            newGame.indexOf("/** Updates our local"),
        )

        assertTrue(creation.contains("creation.openPlayerGame()"))
        assertTrue(creation.contains("AuthoritativeWorldScreen("))
        assertTrue(creation.contains("AuthoritativeGameDirectory(session)"))
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
    fun serverSelectionRoutesBeforeLegacyPreviewLoading() {
        val source = sourceFile(
            "core/src/com/unciv/ui/screens/multiplayerscreens/MultiplayerScreen.kt",
        ).readText()
        val routing = source.substring(
            source.indexOf("private fun setupRightSideButton()"),
            source.indexOf("private fun getGeneralActionsTable()"),
        )

        assertTrue(
            routing.indexOf("selectedAuthoritativeGame?.let") <
                routing.indexOf("selectedGame!!"),
        )
        assertTrue(routing.contains("return@onClick"))
    }

    @Test
    fun acceptedInvitationTransitionsDirectlyIntoProjectionOnlyWorld() {
        val inbox = sourceFile(
            "core/src/com/unciv/ui/screens/multiplayerscreens/" +
                "AuthoritativeInvitationPopups.kt",
        ).readText()
        val multiplayer = sourceFile(
            "core/src/com/unciv/ui/screens/multiplayerscreens/MultiplayerScreen.kt",
        ).readText()

        assertTrue(inbox.contains("flow.acceptAndOpen(invitation)"))
        assertTrue(inbox.contains("onAccepted(accepted)"))
        assertTrue(multiplayer.contains("private fun openAcceptedInvitation("))
        assertTrue(multiplayer.contains("AuthoritativeWorldScreen("))
        assertFalse(inbox.contains("GameInfo"))
        assertFalse(inbox.contains("GameStarter"))
        assertFalse(inbox.contains("playerId"))
        assertFalse(inbox.contains("chosenCiv"))
    }

    @Test
    fun legacyResignationHasNoConditionalAuthoritativeFallback() {
        val source = sourceFile(
            "core/src/com/unciv/ui/screens/multiplayerscreens/MultiplayerScreen.kt",
        ).readText()
        val legacyResignation = source.substring(
            source.indexOf("private fun resignPlayer("),
            source.indexOf("private fun skipCurrentPlayerTurn("),
        )

        assertTrue(legacyResignation.contains("onlineMultiplayer.legacy.resignPlayer"))
        assertFalse(legacyResignation.contains("authoritativeSession"))
        assertFalse(legacyResignation.contains("resignIfOpen"))
        assertFalse(legacyResignation.contains("forceResignIfOpen"))
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
    fun authoritativeAdministrationSeparatesActiveAndClosedLifecycleActions() {
        val source = sourceFile(
            "core/src/com/unciv/ui/screens/multiplayerscreens/" +
                "AuthoritativeAdministrationPopup.kt",
        ).readText()

        assertTrue(source.contains("lifecycleStatus in setOf(\"active\", \"closed\")"))
        assertTrue(source.contains("forceResignButton.isVisible = active"))
        assertTrue(source.contains("closeGameButton.isVisible = active"))
        assertTrue(source.contains("archiveGameButton.isVisible = !active"))
        assertTrue(source.contains("coordinator.forceResign(gameSummary.gameId)"))
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
