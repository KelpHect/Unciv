package com.unciv.logic.multiplayer.authoritative

/**
 * Account-scoped API-v3 game discovery.
 *
 * This deliberately has no dependency on multiplayer save files. A new client
 * installation can rebuild the directory from server membership metadata and
 * open the permitted server projection directly.
 */
class AuthoritativeGameDirectory(
    private val listPage: suspend (String?, Int) -> ApiV3GamePage,
    private val openPlayerProjection: suspend (String) -> ApiV3GameProjection,
    private val openSpectatorGame: suspend (String) -> ApiV3SpectatorGameProjection,
) {
    constructor(session: AuthoritativeMultiplayerSession) : this(
        session::listGames,
        {
            val commandBus = session.openGame(it)
            (commandBus.state as AuthoritativeSyncState.Synchronized).current
        },
        session::spectatorProjection,
    )

    suspend fun refresh(
        pageSize: Int = DEFAULT_PAGE_SIZE,
        maximumGames: Int = DEFAULT_MAXIMUM_GAMES,
    ): List<ApiV3GameSummary> {
        require(pageSize in 1..MAXIMUM_PAGE_SIZE) {
            "Authoritative game page size must be between 1 and $MAXIMUM_PAGE_SIZE"
        }
        require(maximumGames in 1..MAXIMUM_GAME_LIMIT) {
            "Authoritative game limit must be between 1 and $MAXIMUM_GAME_LIMIT"
        }

        val gamesById = linkedMapOf<String, ApiV3GameSummary>()
        val seenCursors = mutableSetOf<String>()
        var cursor: String? = null

        do {
            val page = listPage(cursor, minOf(pageSize, maximumGames - gamesById.size))
            require(page.games.size <= pageSize) {
                "Authoritative game page exceeded the requested size"
            }
            for (summary in page.games) {
                validateSummary(summary)
                require(gamesById.putIfAbsent(summary.gameId, summary) == null) {
                    "Authoritative game directory repeated game ${summary.gameId}"
                }
                require(gamesById.size <= maximumGames) {
                    "Authoritative game directory exceeded the configured limit"
                }
            }

            val nextCursor = page.nextCursor
            if (nextCursor != null) {
                require(nextCursor.isNotBlank()) {
                    "Authoritative game directory returned a blank cursor"
                }
                require(seenCursors.add(nextCursor)) {
                    "Authoritative game directory repeated a cursor"
                }
                require(gamesById.size < maximumGames) {
                    "Authoritative game directory has more than $maximumGames games"
                }
            }
            cursor = nextCursor
        } while (cursor != null)

        return gamesById.values.toList()
    }

    suspend fun open(summary: ApiV3GameSummary): OpenedAuthoritativeGame {
        validateSummary(summary)
        require(canOpen(summary)) {
            if (!summary.available)
                "Authoritative game ${summary.gameId} is temporarily unavailable"
            else "Administrative memberships do not expose a player projection"
        }
        return if (summary.role == SPECTATOR_ROLE) {
            val projection = openSpectatorGame(summary.gameId)
            require(projection.projectionVersion == SpectatorProjection.CURRENT_PROJECTION_VERSION) {
                "Authoritative spectator projection uses an incompatible version"
            }
            validateOpenedProjection(
                summary,
                projection.gameId,
                projection.committedRevision,
                projection.canonicalStateHash,
            )
            OpenedAuthoritativeGame.Spectator(projection)
        } else {
            val projection = openPlayerProjection(summary.gameId)
            validateOpenedProjection(
                summary,
                projection.gameId,
                projection.committedRevision,
                projection.canonicalStateHash,
            )
            require(
                summary.civilizationId == null ||
                    projection.projection.civilizationId == summary.civilizationId,
            ) {
                "Authoritative projection civilization does not match membership metadata"
            }
            OpenedAuthoritativeGame.Player(projection)
        }
    }

    fun canOpen(summary: ApiV3GameSummary): Boolean =
        summary.available && summary.role != ADMIN_ROLE

    private fun validateSummary(summary: ApiV3GameSummary) {
        require(summary.gameId.isNotBlank()) { "Authoritative game ID must not be blank" }
        require(summary.committedRevision >= 0) {
            "Authoritative game revision must not be negative"
        }
        require(summary.canonicalStateHash.isNotBlank()) {
            "Authoritative game state hash must not be blank"
        }
        require(summary.role in SUPPORTED_ROLES) {
            "Unsupported authoritative game role ${summary.role}"
        }
        require(summary.lifecycleStatus in SUPPORTED_LIFECYCLE_STATUSES) {
            "Unsupported authoritative lifecycle status ${summary.lifecycleStatus}"
        }
    }

    private fun validateOpenedProjection(
        summary: ApiV3GameSummary,
        projectedGameId: String,
        projectedRevision: Long,
        projectedStateHash: String,
    ) {
        require(projectedGameId == summary.gameId) {
            "Authoritative projection game does not match the selected membership"
        }
        require(projectedRevision >= summary.committedRevision) {
            "Authoritative projection is older than membership metadata"
        }
        require(
            projectedRevision != summary.committedRevision ||
                projectedStateHash == summary.canonicalStateHash,
        ) {
            "Authoritative projection disagrees with membership metadata"
        }
    }

    companion object {
        const val DEFAULT_PAGE_SIZE = 50
        const val DEFAULT_MAXIMUM_GAMES = 1_000
        const val MAXIMUM_PAGE_SIZE = 100
        const val MAXIMUM_GAME_LIMIT = 5_000

        private const val SPECTATOR_ROLE = "spectator"
        private const val ADMIN_ROLE = "admin"
        private val SUPPORTED_ROLES = setOf("owner", "player", SPECTATOR_ROLE, ADMIN_ROLE)
        private val SUPPORTED_LIFECYCLE_STATUSES = setOf("active", "closed", "archived")
    }
}

sealed interface OpenedAuthoritativeGame {
    data class Player(val projection: ApiV3GameProjection) : OpenedAuthoritativeGame

    data class Spectator(val projection: ApiV3SpectatorGameProjection) : OpenedAuthoritativeGame
}
