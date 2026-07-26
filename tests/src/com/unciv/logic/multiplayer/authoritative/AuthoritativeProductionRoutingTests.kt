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
