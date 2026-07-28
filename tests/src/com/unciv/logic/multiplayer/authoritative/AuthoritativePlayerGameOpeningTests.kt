package com.unciv.logic.multiplayer.authoritative

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

class AuthoritativePlayerGameOpeningTests {
    @Test
    fun serverCreatedOwnerOpensItsExactRevisionZeroProjection() {
        val opened = openAuthoritativePlayerGame(metadata(), projection())

        assertEquals("owner", opened.summary.role)
        assertEquals("Rome", opened.summary.civilizationId)
        assertEquals(0, opened.projection.committedRevision)
    }

    @Test(expected = IllegalArgumentException::class)
    fun serverCreatedOwnerRejectsClientVisibleCivilizationMismatch() {
        openAuthoritativePlayerGame(
            metadata().copy(civilizationId = "Greece"),
            projection(),
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun serverCreatedOwnerRejectsProjectionRevisionMismatch() {
        openAuthoritativePlayerGame(
            metadata(),
            projection().copy(committedRevision = 1),
        )
    }

    private fun metadata() = ApiV3GameMetadata(
        gameId = GAME_ID,
        committedRevision = 0,
        canonicalStateHash = "state-0",
        role = "owner",
        civilizationId = "Rome",
    )

    private fun projection(): ApiV3GameProjection {
        val player = Json {
            ignoreUnknownKeys = false
            explicitNulls = true
        }.decodeFromString(
            PlayerProjection.serializer(),
            projectionFixture().readText(),
        )
        return ApiV3GameProjection(
            gameId = GAME_ID,
            projectionVersion = PlayerProjection.CURRENT_PROJECTION_VERSION,
            committedRevision = 0,
            canonicalStateHash = "state-0",
            projectionHash = "projection-0",
            projection = player,
        )
    }

    private fun projectionFixture(): File = generateSequence(
        File(System.getProperty("user.dir")),
    ) { it.parentFile }
        .map { File(it, "protocol/player-projection-v60.fixture.json") }
        .first(File::isFile)

    private companion object {
        const val GAME_ID = "11111111-1111-4111-8111-111111111111"
    }
}
