package com.unciv.logic.multiplayer.authoritative

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

class AuthoritativeInvitationFlowTests {
    @Test
    fun acceptedInvitationDiscoversServerAssignmentAndOpensProjection() = runBlocking {
        val fixture = fixture()
        var acceptanceCalls = 0
        var projectionCalls = 0
        val flow = flow(
            summary = summary(),
            projection = fixture,
            accept = { invitation, commandId ->
                acceptanceCalls++
                accepted(invitation, commandId)
            },
            onProjection = { projectionCalls++ },
        )

        val opened = flow.acceptAndOpen(invitation())

        assertEquals("Rome", opened.summary.civilizationId)
        assertEquals("Rome", opened.projection.projection.civilizationId)
        assertEquals(1, acceptanceCalls)
        assertEquals(1, projectionCalls)
    }

    @Test
    fun acceptedInvitationRejectsMissingServerCivilizationAssignment() = runBlocking {
        val flow = flow(summary = summary().copy(civilizationId = null))

        assertThrows<IllegalArgumentException> {
            flow.acceptAndOpen(invitation())
        }
        Unit
    }

    @Test
    fun acceptedInvitationRejectsMembershipOlderThanCommittedJoin() = runBlocking {
        val flow = flow(
            summary = summary().copy(
                committedRevision = 7,
                canonicalStateHash = "hash-7",
            ),
        )

        assertThrows<IllegalArgumentException> {
            flow.acceptAndOpen(invitation())
        }
        Unit
    }

    @Test
    fun acceptedInvitationRejectsSameRevisionHashDisagreement() = runBlocking {
        val flow = flow(
            summary = summary().copy(canonicalStateHash = "different-hash"),
        )

        assertThrows<IllegalArgumentException> {
            flow.acceptAndOpen(invitation())
        }
        Unit
    }

    private fun flow(
        summary: ApiV3GameSummary,
        projection: ApiV3GameProjection = fixture(),
        accept: suspend (ApiV3PlayerInvitation, String) -> ApiV3CommandAccepted =
            { invitation, commandId -> accepted(invitation, commandId) },
        onProjection: () -> Unit = {},
    ): AuthoritativeInvitationFlow {
        val coordinator = AuthoritativeInvitationCoordinator(
            listInvitations = { listOf(invitation()) },
            acceptInvitation = accept,
            sendInvitation = { _, _, _ -> },
            newId = { "join-command" },
        )
        val directory = AuthoritativeGameDirectory(
            listPage = { _, _ -> ApiV3GamePage(listOf(summary)) },
            openPlayerProjection = {
                onProjection()
                projection
            },
            openSpectatorGame = { error("Player invitation must not open a spectator projection") },
        )
        return AuthoritativeInvitationFlow(coordinator, directory)
    }

    private fun invitation() = ApiV3PlayerInvitation(
        gameId = GAME_ID,
        invitationId = "invitation-1",
        invitedBy = "owner",
        committedRevision = 7,
        canonicalStateHash = "hash-7",
    )

    private fun accepted(
        invitation: ApiV3PlayerInvitation,
        commandId: String,
    ) = ApiV3CommandAccepted(
        gameId = invitation.gameId,
        commandId = commandId,
        previousRevision = invitation.committedRevision,
        committedRevision = 8,
        canonicalStateHash = "accepted-hash",
    )

    private fun summary() = ApiV3GameSummary(
        gameId = GAME_ID,
        committedRevision = 8,
        canonicalStateHash = "accepted-hash",
        role = "player",
        civilizationId = "Rome",
        available = true,
    )

    private fun fixture(): ApiV3GameProjection {
        val projection = Json {
            ignoreUnknownKeys = false
            explicitNulls = true
        }.decodeFromString(
            PlayerProjection.serializer(),
            projectionFixture().readText(),
        )
        return ApiV3GameProjection(
            gameId = GAME_ID,
            projectionVersion = PlayerProjection.CURRENT_PROJECTION_VERSION,
            committedRevision = 8,
            canonicalStateHash = "accepted-hash",
            projectionHash = "projection-8",
            projection = projection,
        )
    }

    private fun projectionFixture(): File = generateSequence(
        File(System.getProperty("user.dir")),
    ) { it.parentFile }
        .map { File(it, "protocol/player-projection-v60.fixture.json") }
        .first(File::isFile)

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

    private companion object {
        const val GAME_ID = "11111111-1111-4111-8111-111111111111"
    }
}
