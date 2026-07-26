package com.unciv.logic.multiplayer.authoritative

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class PlayerProjectionDeltaApplierTests {
    private val json = Json {
        encodeDefaults = true
        ignoreUnknownKeys = false
    }

    @Test
    fun validDeltaReconstructsAndHashesTheTargetProjection() {
        val base = fixture()
        val targetProjection = base.projection.copy(
            turn = base.projection.turn + 1,
            gold = base.projection.gold + 25,
            ownUnits = base.projection.ownUnits.mapIndexed { index, unit ->
                if (index == 0) unit.copy(health = 87) else unit
            },
        )
        val delta = delta(
            base,
            targetProjection,
            listOf(
                ApiV3ProjectionDeltaOperation("/gold", JsonPrimitive(targetProjection.gold)),
                ApiV3ProjectionDeltaOperation(
                    "/ownUnits/0/health",
                    JsonPrimitive(targetProjection.ownUnits[0].health),
                ),
                ApiV3ProjectionDeltaOperation("/turn", JsonPrimitive(targetProjection.turn)),
            ),
        )

        val applied = PlayerProjectionDeltaApplier.apply(base, delta)

        assertEquals(targetProjection, applied.projection)
        assertEquals(base.committedRevision + 1, applied.committedRevision)
        assertEquals(delta.canonicalStateHash, applied.canonicalStateHash)
        assertEquals(delta.projectionHash, applied.projectionHash)
    }

    @Test
    fun staleOrWrongBaseIdentityIsRejectedBeforeApplication() {
        val base = fixture()
        val target = base.projection.copy(turn = base.projection.turn + 1)
        val valid = delta(
            base,
            target,
            listOf(ApiV3ProjectionDeltaOperation("/turn", JsonPrimitive(target.turn))),
        )

        assertFails { PlayerProjectionDeltaApplier.apply(base, valid.copy(baseRevision = 6)) }
        assertFails {
            PlayerProjectionDeltaApplier.apply(
                base,
                valid.copy(baseCanonicalStateHash = hash('b')),
            )
        }
        assertFails {
            PlayerProjectionDeltaApplier.apply(
                base,
                valid.copy(baseProjectionHash = hash('c')),
            )
        }
        assertFails {
            PlayerProjectionDeltaApplier.apply(
                base,
                valid.copy(gameId = "00000000-0000-0000-0000-000000000099"),
            )
        }
    }

    @Test
    fun tamperingUnknownPathsAndTargetHashMismatchFailClosed() {
        val base = fixture()
        val target = base.projection.copy(turn = base.projection.turn + 1)
        val valid = delta(
            base,
            target,
            listOf(ApiV3ProjectionDeltaOperation("/turn", JsonPrimitive(target.turn))),
        )

        assertFails {
            PlayerProjectionDeltaApplier.apply(
                base,
                valid.copy(operations = listOf(
                    ApiV3ProjectionDeltaOperation("/canonicalGameInfo", JsonPrimitive("secret")),
                )),
            )
        }
        assertFails {
            PlayerProjectionDeltaApplier.apply(base, valid.copy(projectionHash = hash('d')))
        }
        assertFails {
            PlayerProjectionDeltaApplier.apply(
                base,
                valid.copy(operations = listOf(
                    ApiV3ProjectionDeltaOperation("/turn", JsonPrimitive(target.turn)),
                    ApiV3ProjectionDeltaOperation("/turn", JsonPrimitive(target.turn)),
                )),
            )
        }
    }

    @Test
    fun fixtureEncodingUsesTheSameStableProjectionHashInput() {
        val projection = fixture().projection

        assertEquals(
            sha256(json.encodeToString(PlayerProjection.serializer(), projection)),
            PlayerProjectionDeltaApplier.projectionHash(projection),
        )
    }

    private fun fixture(): ApiV3GameProjection {
        val projection = json.decodeFromString(
            PlayerProjection.serializer(),
            projectionFixture().readText(),
        )
        return ApiV3GameProjection(
            gameId = GAME_ID,
            projectionVersion = PlayerProjection.CURRENT_PROJECTION_VERSION,
            committedRevision = 7,
            canonicalStateHash = hash('a'),
            projectionHash = PlayerProjectionDeltaApplier.projectionHash(projection),
            projection = projection,
        )
    }

    private fun delta(
        base: ApiV3GameProjection,
        target: PlayerProjection,
        operations: List<ApiV3ProjectionDeltaOperation>,
    ) = ApiV3GameProjectionDelta(
        gameId = base.gameId,
        projectionVersion = base.projectionVersion,
        baseRevision = base.committedRevision,
        baseCanonicalStateHash = base.canonicalStateHash,
        baseProjectionHash = base.projectionHash,
        committedRevision = base.committedRevision + 1,
        canonicalStateHash = hash('e'),
        projectionHash = PlayerProjectionDeltaApplier.projectionHash(target),
        operations = operations,
    )

    private fun assertFails(block: () -> Unit) {
        assertTrue(runCatching(block).isFailure)
    }

    private fun projectionFixture(): File = generateSequence(
        File(System.getProperty("user.dir")).absoluteFile,
        File::getParentFile,
    ).map { File(it, "protocol/player-projection-v59.fixture.json") }
        .first { it.isFile }

    private fun hash(character: Char) = character.toString().repeat(64)

    private fun sha256(value: String) =
        java.security.MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray())
            .joinToString("") { "%02x".format(it) }

    private companion object {
        const val GAME_ID = "00000000-0000-0000-0000-000000000001"
    }
}
