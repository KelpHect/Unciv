package com.unciv.logic.multiplayer.authoritative

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class PlayerProjectionContractTests {
    private val json = Json {
        ignoreUnknownKeys = false
        encodeDefaults = true
    }

    @Test
    fun sharedRustKotlinProjectionFixtureRoundTripsSemantically() {
        val fixture = projectionFixture().readText()
        val projection = json.decodeFromString(PlayerProjection.serializer(), fixture)

        assertEquals(3, projection.protocolVersion)
        assertEquals(listOf(PendingEndTurnAction.PickPolicy), projection.pendingTurnActions)
        assertEquals(listOf("Monument"), projection.ownCities.single().constructionQueue)
        assertEquals(
            json.parseToJsonElement(fixture),
            json.parseToJsonElement(json.encodeToString(PlayerProjection.serializer(), projection)),
        )
    }

    @Test
    fun unknownCanonicalFieldsCannotCrossTheProjectionContract() {
        val fixture = projectionFixture().readText().replaceFirst(
            "{",
            "{\"canonicalGameInfo\":{\"secret\":true},",
        )
        assertTrue(runCatching {
            json.decodeFromString(PlayerProjection.serializer(), fixture)
        }.isFailure)
    }

    @Test
    fun unknownPendingTurnActionsCannotCrossTheProjectionContract() {
        val fixture = projectionFixture().readText().replace(
            "pick_policy",
            "replace_canonical_state",
        )
        assertTrue(runCatching {
            json.decodeFromString(PlayerProjection.serializer(), fixture)
        }.isFailure)
    }

    private fun projectionFixture(): File = generateSequence(
        File(System.getProperty("user.dir")).absoluteFile,
        File::getParentFile,
    ).map { File(it, "protocol/player-projection-v3.fixture.json") }
        .first { it.isFile }
}
