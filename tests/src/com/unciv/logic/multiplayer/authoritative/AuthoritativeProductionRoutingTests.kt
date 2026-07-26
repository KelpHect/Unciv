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
            source.indexOf("private fun usesAuthoritativeCreation()"),
        )
        val authoritativeBranch = start.indexOf("if (usesAuthoritativeCreation())")
        val localConstruction = start.indexOf("GameStarter.startNewGame")

        assertTrue(authoritativeBranch >= 0)
        assertTrue(localConstruction > authoritativeBranch)
        assertTrue(
            start.substring(authoritativeBranch, localConstruction)
                .contains("return@coroutineScope"),
        )
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
    fun legacyResignationHasNoConditionalAuthoritativeFallback() {
        val source = sourceFile(
            "core/src/com/unciv/ui/screens/multiplayerscreens/MultiplayerScreen.kt",
        ).readText()
        val legacyResignation = source.substring(
            source.indexOf("private fun resignPlayer("),
            source.indexOf("private fun skipCurrentPlayerTurn("),
        )

        assertTrue(legacyResignation.contains("onlineMultiplayer.resignPlayer"))
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
    fun apiV3DecisionsExistOnlyInProjectionWorldRouting() {
        for (legacyPicker in listOf(
            "TechPickerScreen.kt",
            "PolicyPickerScreen.kt",
            "DiplomaticVotePickerScreen.kt",
            "GreatPersonPickerScreen.kt",
            "ReligionPickerScreenCommon.kt",
        )) {
            val source = sourceFile(
                "core/src/com/unciv/ui/screens/pickerscreens/$legacyPicker",
            ).readText()

            assertFalse("$legacyPicker must not inspect API-v3 sessions", source.contains("authoritativeSession"))
            assertFalse("$legacyPicker must not submit API-v3 commands", source.contains("IfOpen("))
            assertFalse("$legacyPicker must not handle API-v3 outcomes", source.contains("AuthoritativeCommandOutcome"))
        }

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

    private fun sourceFile(path: String): File = generateSequence(
        File(System.getProperty("user.dir")).absoluteFile,
        File::getParentFile,
    ).map { File(it, path) }.firstOrNull(File::isFile)
        ?: error("Could not locate $path")
}
