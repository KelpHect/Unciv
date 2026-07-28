package com.unciv.logic.multiplayer.authoritative

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class AuthoritativeTurnNotificationPollerTests {
    @Test
    fun derivesTurnsOnlyFromAuthenticatedPlayerProjections() = runBlocking {
        val projected = mutableListOf<String>()
        val summaries = listOf(
            summary("game-current", "player"),
            summary("game-waiting", "player"),
            summary("game-spectator", "spectator"),
        )
        val turns = AuthoritativeTurnNotificationPoller(
            listGames = { _, _ -> ApiV3GamePage(summaries) },
            projection = {
                projected += it
                projectionFixture(it, it == "game-current")
            },
        ).poll(pageSize = 3)

        assertEquals(
            listOf(AuthoritativeTurnNotification("game-current", 9)),
            turns,
        )
        assertEquals(listOf("game-current", "game-waiting"), projected.sorted())
    }

    private fun summary(gameId: String, role: String) = ApiV3GameSummary(
        gameId, 9, "hash-$gameId", role,
        civilizationId = if (role == "player") "Rome" else null,
        available = true,
    )

    private fun projectionFixture(gameId: String, current: Boolean) = ApiV3GameProjection(
        gameId,
        PlayerProjection.CURRENT_PROJECTION_VERSION,
        9,
        "hash-$gameId",
        "projection-$gameId",
        PlayerProjection(
            civilizationId = "Rome",
            turn = 9,
            currentPlayerCivilizationId = if (current) "Rome" else "Greece",
            isCurrentTurn = current,
            pendingTurnActions = emptyList(),
            research = ProjectedResearch(
                null, emptyList(), emptyList(), emptyList(), 0,
                emptyList(), emptyList(), emptyList(), emptyList(),
            ),
            policies = ProjectedPolicies(0, 1, 0, emptyList(), emptyList()),
            gold = 0,
            knownCivilizations = emptyList(),
            ownCities = emptyList(),
            ownUnits = emptyList(),
            exploredTiles = emptyList(),
            visibleForeignUnits = emptyList(),
        )
    )
}
