package com.unciv.logic.multiplayer.authoritative

/** A validated account membership paired with its synchronized player view. */
data class OpenedAuthoritativePlayerGame(
    val summary: ApiV3GameSummary,
    val projection: ApiV3GameProjection,
)

fun openAuthoritativePlayerGame(
    metadata: ApiV3GameMetadata,
    projection: ApiV3GameProjection,
): OpenedAuthoritativePlayerGame {
    require(metadata.role == OWNER_ROLE) {
        "New authoritative game did not create an owner membership"
    }
    require(!metadata.civilizationId.isNullOrBlank()) {
        "New authoritative owner membership has no server-assigned civilization"
    }
    require(metadata.lifecycleStatus == ACTIVE_STATUS) {
        "New authoritative game is not active"
    }
    require(projection.gameId == metadata.gameId) {
        "New authoritative projection game does not match creation metadata"
    }
    require(
        projection.committedRevision == metadata.committedRevision &&
            projection.canonicalStateHash == metadata.canonicalStateHash,
    ) {
        "New authoritative projection disagrees with creation metadata"
    }
    require(projection.projection.civilizationId == metadata.civilizationId) {
        "New authoritative projection civilization does not match owner membership"
    }
    return OpenedAuthoritativePlayerGame(
        ApiV3GameSummary(
            gameId = metadata.gameId,
            committedRevision = metadata.committedRevision,
            canonicalStateHash = metadata.canonicalStateHash,
            role = metadata.role,
            civilizationId = metadata.civilizationId,
            available = true,
            lifecycleStatus = metadata.lifecycleStatus,
        ),
        projection,
    )
}

private const val OWNER_ROLE = "owner"
private const val ACTIVE_STATUS = "active"
