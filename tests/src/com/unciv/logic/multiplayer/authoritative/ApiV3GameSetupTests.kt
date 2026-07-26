package com.unciv.logic.multiplayer.authoritative

import com.unciv.Constants
import com.unciv.logic.civilization.PlayerType
import com.unciv.models.metadata.GameSetupInfo
import com.unciv.models.metadata.Player
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class ApiV3GameSetupTests {
    @Test
    fun creationRoutePreservesOfflineAndDisablesExplicitLegacyCreation() {
        assertEquals(
            MultiplayerCreationRoute.Local,
            multiplayerCreationRoute(
                isOnlineMultiplayer = false,
                authoritativeStatus = AuthoritativeSessionStatus.Authenticated,
            ),
        )
        assertEquals(
            MultiplayerCreationRoute.LegacyCreationDisabled,
            multiplayerCreationRoute(
                isOnlineMultiplayer = true,
                authoritativeStatus = AuthoritativeSessionStatus.LegacyServer,
            ),
        )
        assertEquals(
            MultiplayerCreationRoute.AuthoritativeApiV3,
            multiplayerCreationRoute(
                isOnlineMultiplayer = true,
                authoritativeStatus = AuthoritativeSessionStatus.Authenticated,
            ),
        )
        for (status in listOf(
            AuthoritativeSessionStatus.NotStarted,
            AuthoritativeSessionStatus.Detecting,
            AuthoritativeSessionStatus.SecureStoreUnavailable,
            AuthoritativeSessionStatus.Failed,
        )) {
            assertEquals(
                MultiplayerCreationRoute.AuthoritativeUnavailable,
                multiplayerCreationRoute(
                    isOnlineMultiplayer = true,
                    authoritativeStatus = status,
                ),
            )
        }
    }

    @Test
    fun productionMapperAcceptsOnlyServerOwnedCreationMeaning() {
        val setup = authoritativeSetup()

        val mapped = ApiV3GameSetup.from(setup)

        assertEquals(setup.gameParameters.players.size, mapped.majorCivilizations)
        assertEquals(setup.gameParameters.numberOfCityStates, mapped.cityStates)
    }

    @Test
    fun productionMapperRejectsClientOwnedIdentityAndGenerationInputs() {
        assertRejected { it.gameParameters.players[0].chosenCiv = "Rome" }
        assertRejected {
            it.gameParameters.players += Player(Constants.random, PlayerType.Human)
        }
        assertRejected { it.gameParameters.players[0].chosenCiv = Constants.spectator }
        assertRejected { it.gameParameters.anyoneCanSpectate = true }
        assertRejected { it.gameParameters.enableRandomNationsPool = true }
        assertRejected { it.gameParameters.godMode = true }
        assertRejected { it.mapParameters.temperatureShift = 0.25f }
        assertRejected { it.mapParameters.mirroring = "Left-right" }
    }

    private fun assertRejected(change: (GameSetupInfo) -> Unit) {
        val setup = authoritativeSetup()
        change(setup)
        assertThrows(IllegalArgumentException::class.java) {
            ApiV3GameSetup.from(setup)
        }
    }

    private fun authoritativeSetup() = GameSetupInfo().apply {
        gameParameters.isOnlineMultiplayer = true
        gameParameters.anyoneCanSpectate = false
        gameParameters.players.forEach {
            it.chosenCiv = Constants.random
            it.playerType = PlayerType.AI
        }
        gameParameters.players.first().playerType = PlayerType.Human
    }
}
