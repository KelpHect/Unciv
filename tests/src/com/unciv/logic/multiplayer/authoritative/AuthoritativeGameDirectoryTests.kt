package com.unciv.logic.multiplayer.authoritative

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AuthoritativeGameDirectoryTests {
    @Test
    fun freshClientDiscoversEveryMembershipThroughBoundedServerPages() = runBlocking {
        val requestedCursors = mutableListOf<String?>()
        val directory = directory { cursor, limit ->
            requestedCursors += cursor
            assertTrue(limit in 1..2)
            when (cursor) {
                null -> ApiV3GamePage(
                    listOf(summary("game-a", "owner"), summary("game-b", "player")),
                    nextCursor = "page-2",
                )
                "page-2" -> ApiV3GamePage(listOf(summary("game-c", "spectator")))
                else -> error("Unexpected cursor $cursor")
            }
        }

        val games = directory.refresh(pageSize = 2, maximumGames = 4)

        assertEquals(listOf(null, "page-2"), requestedCursors)
        assertEquals(listOf("game-a", "game-b", "game-c"), games.map { it.gameId })
    }

    @Test
    fun repeatedCursorFailsClosedInsteadOfLoopingForever() = runBlocking {
        var pageNumber = 0
        val directory = directory { _, _ ->
            pageNumber++
            ApiV3GamePage(listOf(summary("game-$pageNumber", "player")), "same-cursor")
        }

        assertThrows<IllegalArgumentException> {
            directory.refresh(pageSize = 1, maximumGames = 10)
        }
        assertEquals(2, pageNumber)
        Unit
    }

    @Test
    fun duplicateGameAcrossPagesFailsClosed() = runBlocking {
        val directory = directory { cursor, _ ->
            if (cursor == null) ApiV3GamePage(listOf(summary("game-a", "player")), "next")
            else ApiV3GamePage(listOf(summary("game-a", "player")))
        }

        assertThrows<IllegalArgumentException> { directory.refresh() }
        Unit
    }

    @Test
    fun spectatorMembershipOpensOnlyThePublicProjection() = runBlocking {
        var playerOpenCalls = 0
        var spectatorGameId: String? = null
        val expected = spectatorProjection("game-spectator")
        val directory = AuthoritativeGameDirectory(
            listPage = { _, _ -> ApiV3GamePage(emptyList()) },
            openPlayerProjection = {
                playerOpenCalls++
                error("Player projection endpoint must not be used")
            },
            openSpectatorGame = {
                spectatorGameId = it
                expected
            },
        )

        val opened = directory.open(summary("game-spectator", "spectator"))

        assertTrue(opened is OpenedAuthoritativeGame.Spectator)
        assertEquals(expected, (opened as OpenedAuthoritativeGame.Spectator).projection)
        assertEquals("game-spectator", spectatorGameId)
        assertEquals(0, playerOpenCalls)
    }

    @Test
    fun incompatibleSpectatorProjectionFailsClosed() = runBlocking {
        val directory = AuthoritativeGameDirectory(
            listPage = { _, _ -> ApiV3GamePage(emptyList()) },
            openPlayerProjection = { error("Unexpected player open") },
            openSpectatorGame = {
                spectatorProjection(it).copy(
                    projectionVersion = SpectatorProjection.CURRENT_PROJECTION_VERSION - 1,
                )
            },
        )

        assertThrows<IllegalArgumentException> {
            directory.open(summary("game-spectator", "spectator"))
        }
        Unit
    }

    @Test
    fun playerMembershipOpensOnlyThePlayerProjection() = runBlocking {
        var openedPlayerGameId: String? = null
        var spectatorOpenCalls = 0
        val expected = playerProjection("game-player")
        val directory = AuthoritativeGameDirectory(
            listPage = { _, _ -> ApiV3GamePage(emptyList()) },
            openPlayerProjection = {
                openedPlayerGameId = it
                expected
            },
            openSpectatorGame = {
                spectatorOpenCalls++
                error("Spectator projection endpoint must not be used")
            },
        )

        val opened = directory.open(summary("game-player", "player"))

        assertTrue(opened is OpenedAuthoritativeGame.Player)
        assertEquals(expected, (opened as OpenedAuthoritativeGame.Player).projection)
        assertEquals("game-player", openedPlayerGameId)
        assertEquals(0, spectatorOpenCalls)
    }

    @Test
    fun unavailableMembershipCannotOpenEitherProjectionEndpoint() = runBlocking {
        var endpointCalls = 0
        val directory = AuthoritativeGameDirectory(
            listPage = { _, _ -> ApiV3GamePage(emptyList()) },
            openPlayerProjection = {
                endpointCalls++
                error("Unexpected player open")
            },
            openSpectatorGame = {
                endpointCalls++
                spectatorProjection(it)
            },
        )

        assertThrows<IllegalArgumentException> {
            directory.open(summary("game-a", "player").copy(available = false))
        }
        assertEquals(0, endpointCalls)
    }

    @Test
    fun projectionIdentityMustMatchSelectedMembership() = runBlocking {
        val directory = AuthoritativeGameDirectory(
            listPage = { _, _ -> ApiV3GamePage(emptyList()) },
            openPlayerProjection = { playerProjection("different-game") },
            openSpectatorGame = { spectatorProjection(it) },
        )

        assertThrows<IllegalArgumentException> {
            directory.open(summary("selected-game", "player"))
        }
        Unit
    }

    private fun directory(
        listPage: suspend (String?, Int) -> ApiV3GamePage,
    ) = AuthoritativeGameDirectory(
        listPage = listPage,
        openPlayerProjection = { error("Unexpected player open") },
        openSpectatorGame = { spectatorProjection(it) },
    )

    private fun summary(gameId: String, role: String) = ApiV3GameSummary(
        gameId = gameId,
        committedRevision = 4,
        canonicalStateHash = "state-hash-$gameId",
        role = role,
        civilizationId = if (role == "spectator") null else "Rome",
        available = true,
    )

    private fun spectatorProjection(gameId: String) = ApiV3SpectatorGameProjection(
        gameId = gameId,
        projectionVersion = SpectatorProjection.CURRENT_PROJECTION_VERSION,
        committedRevision = 4,
        canonicalStateHash = "state-hash-$gameId",
        projectionHash = "projection-hash-$gameId",
        projection = SpectatorProjection(
            turn = 12,
            currentPlayerCivilizationId = "Rome",
            majorCivilizations = emptyList(),
        ),
    )

    private fun playerProjection(gameId: String) = ApiV3GameProjection(
        gameId = gameId,
        projectionVersion = PlayerProjection.CURRENT_PROJECTION_VERSION,
        committedRevision = 4,
        canonicalStateHash = "state-hash-$gameId",
        projectionHash = "projection-hash-$gameId",
        projection = PlayerProjection(
            civilizationId = "Rome",
            turn = 12,
            currentPlayerCivilizationId = "Rome",
            isCurrentTurn = true,
            pendingTurnActions = emptyList(),
            research = ProjectedResearch(
                currentTechnology = null,
                researchedTechnologies = emptyList(),
                queue = emptyList(),
                queueEntries = emptyList(),
                overflowScience = 0,
                selectableTargets = emptyList(),
                appendableTargets = emptyList(),
                freeTechnologyChoices = emptyList(),
                completionPrompts = emptyList(),
            ),
            policies = ProjectedPolicies(
                storedCulture = 0,
                cultureNeededForNextPolicy = 0,
                freePolicies = 0,
                adoptedPolicies = emptyList(),
                selectablePolicies = emptyList(),
            ),
            gold = 0,
            knownCivilizations = emptyList(),
            ownCities = emptyList(),
            ownUnits = emptyList(),
            exploredTiles = emptyList(),
            visibleForeignUnits = emptyList(),
        ),
    )

    private suspend inline fun <reified T : Throwable> assertThrows(
        crossinline block: suspend () -> Unit,
    ): T {
        try {
            block()
        } catch (throwable: Throwable) {
            if (throwable is T) return throwable
            throw AssertionError("Expected ${T::class.simpleName}, got $throwable", throwable)
        }
        throw AssertionError("Expected ${T::class.simpleName}")
    }
}
