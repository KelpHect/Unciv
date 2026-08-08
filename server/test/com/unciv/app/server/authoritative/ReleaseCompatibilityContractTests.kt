package com.unciv.app.server.authoritative

import com.unciv.logic.multiplayer.authoritative.CommandEnvelope
import com.unciv.logic.multiplayer.authoritative.LobbyTerrainProjection
import com.unciv.logic.multiplayer.authoritative.PlayerProjection
import com.unciv.logic.multiplayer.authoritative.SpectatorProjection
import kotlinx.serialization.Serializable
import org.junit.Assert.assertEquals
import org.junit.Test
import java.nio.file.Paths

class ReleaseCompatibilityContractTests {
    @Test
    fun checkedInReleaseContractMatchesKotlinProtocolAndProjectionConstants() {
        val path = Paths.get(
            "../../authoritative-server/release/compatibility.json",
        ).toAbsolutePath().normalize()
        val contract = EngineWorkerProtocol.json
            .decodeFromString<CompatibilityContract>(path.toFile().readText())

        assertEquals(1, contract.schemaVersion)
        assertEquals(CommandEnvelope.CURRENT_PROTOCOL_VERSION, contract.publicProtocolVersion)
        assertEquals(PlayerProjection.CURRENT_PROJECTION_VERSION, contract.playerProjectionVersion)
        assertEquals(
            SpectatorProjection.CURRENT_PROJECTION_VERSION,
            contract.spectatorProjectionVersion,
        )
        assertEquals(
            LobbyTerrainProjection.CURRENT_PROJECTION_VERSION,
            contract.lobbyTerrainProjectionVersion,
        )
        assertEquals(EngineWorkerProtocol.VERSION, contract.workerProtocolVersion)
        assertEquals(35, contract.latestMigrationVersion)
        assertEquals(
            "postgres:19beta2-alpine@sha256:" +
                "bc62313e826eb44d5f608425b7665962b72820e686da017799e906604bfeb8a5",
            contract.postgresImage,
        )
    }
}

@Serializable
private data class CompatibilityContract(
    val schema_version: Int,
    val public_protocol_version: Int,
    val player_projection_version: Int,
    val spectator_projection_version: Int,
    val lobby_terrain_projection_version: Int,
    val worker_protocol_version: Int,
    val latest_migration_version: Int,
    val postgres_image: String,
) {
    val schemaVersion get() = schema_version
    val publicProtocolVersion get() = public_protocol_version
    val playerProjectionVersion get() = player_projection_version
    val spectatorProjectionVersion get() = spectator_projection_version
    val lobbyTerrainProjectionVersion get() = lobby_terrain_projection_version
    val workerProtocolVersion get() = worker_protocol_version
    val latestMigrationVersion get() = latest_migration_version
    val postgresImage get() = postgres_image
}
