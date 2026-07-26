package com.unciv.logic.multiplayer.authoritative

/**
 * Completes an invited player's server-owned lobby transition.
 *
 * Invitation acceptance assigns an unclaimed civilization inside the
 * authoritative worker. The client then rediscovers that committed membership
 * and opens only its player-scoped projection; it never chooses or uploads a
 * civilization assignment.
 */
class AuthoritativeInvitationFlow(
    private val invitations: AuthoritativeInvitationCoordinator,
    private val directory: AuthoritativeGameDirectory,
) {
    suspend fun refresh(): List<ApiV3PlayerInvitation> = invitations.refresh()

    suspend fun acceptAndOpen(
        invitation: ApiV3PlayerInvitation,
    ): OpenedAuthoritativePlayerGame {
        val accepted = invitations.accept(invitation)
        val summary = directory.refresh()
            .singleOrNull { it.gameId == invitation.gameId }
            ?: error("Accepted authoritative game is absent from account membership discovery")

        require(summary.role == PLAYER_ROLE) {
            "Accepted authoritative invitation did not create a player membership"
        }
        require(!summary.civilizationId.isNullOrBlank()) {
            "Accepted authoritative player membership has no server-assigned civilization"
        }
        require(summary.lifecycleStatus == ACTIVE_STATUS && summary.available) {
            "Accepted authoritative game is not available for projection opening"
        }
        require(summary.committedRevision >= accepted.committedRevision) {
            "Discovered authoritative membership predates the accepted join"
        }
        require(
            summary.committedRevision != accepted.committedRevision ||
                summary.canonicalStateHash == accepted.canonicalStateHash,
        ) {
            "Discovered authoritative membership disagrees with the accepted join"
        }

        val opened = directory.open(summary)
        require(opened is OpenedAuthoritativeGame.Player) {
            "Accepted authoritative invitation did not open a player projection"
        }
        return OpenedAuthoritativePlayerGame(summary, opened.projection)
    }

    private companion object {
        const val PLAYER_ROLE = "player"
        const val ACTIVE_STATUS = "active"
    }
}
