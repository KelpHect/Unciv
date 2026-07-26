package com.unciv.logic.multiplayer.authoritative

/**
 * Opens and resigns one API-v3 membership without crossing into legacy saves.
 *
 * An uncertain response remains pending in the session command bus, so the
 * next call retries the same command identity rather than creating a new one.
 */
class AuthoritativeResignationCoordinator(
    private val isOpen: (String) -> Boolean,
    private val open: suspend (String) -> Unit,
    private val resignIfOpen: suspend (String) -> AuthoritativeCommandOutcome?,
) {
    constructor(session: AuthoritativeMultiplayerSession) : this(
        session::isGameOpen,
        { session.openGame(it) },
        session::resignIfOpen,
    )

    suspend fun resign(gameId: String): AuthoritativeCommandOutcome {
        require(gameId.isNotBlank()) { "Authoritative game ID must not be blank" }
        if (!isOpen(gameId)) open(gameId)
        return requireNotNull(resignIfOpen(gameId)) {
            "Authoritative game did not remain open for resignation"
        }
    }
}
