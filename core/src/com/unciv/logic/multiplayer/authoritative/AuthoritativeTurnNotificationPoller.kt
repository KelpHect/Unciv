package com.unciv.logic.multiplayer.authoritative

data class AuthoritativeTurnNotification(
    val gameId: String,
    val committedRevision: Long,
)

/**
 * Background-safe V3 turn check. Membership metadata discovers games, while
 * the player-scoped projection is the only authority for whose turn it is.
 */
class AuthoritativeTurnNotificationPoller(
    private val listGames: suspend (String?, Int) -> ApiV3GamePage,
    private val projection: suspend (String) -> ApiV3GameProjection,
) {
    constructor(transport: ApiV3Transport) : this(
        transport::listGames,
        transport::projection,
    )

    suspend fun poll(
        pageSize: Int = 50,
        maximumGames: Int = 1_000,
    ): List<AuthoritativeTurnNotification> {
        require(pageSize in 1..100 && maximumGames in 1..10_000)
        val summaries = linkedMapOf<String, ApiV3GameSummary>()
        val cursors = mutableSetOf<String>()
        var cursor: String? = null
        do {
            val page = listGames(cursor, minOf(pageSize, maximumGames - summaries.size))
            require(page.games.size <= pageSize)
            page.games.forEach {
                require(summaries.putIfAbsent(it.gameId, it) == null)
                require(summaries.size <= maximumGames)
            }
            val nextCursor = page.nextCursor
            if (nextCursor != null) {
                require(nextCursor.isNotBlank() && cursors.add(nextCursor))
                require(summaries.size < maximumGames)
            }
            cursor = nextCursor
        } while (cursor != null)
        val turns = mutableListOf<AuthoritativeTurnNotification>()
        for (summary in summaries.values) {
            if (!summary.available || summary.role != "player" ||
                summary.lifecycleStatus != "active") continue
            val game = projection(summary.gameId)
            if (game.projection.isCurrentTurn) {
                turns += AuthoritativeTurnNotification(
                    game.gameId,
                    game.committedRevision,
                )
            }
        }
        return turns.sortedBy { it.gameId }
    }
}
