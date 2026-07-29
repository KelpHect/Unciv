package com.unciv.logic.multiplayer.authoritative

import com.unciv.Constants
import com.unciv.logic.civilization.PlayerType
import com.unciv.models.metadata.GameSetupInfo
import com.unciv.models.metadata.Player
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class ApiV3GameSetupTests {
    @Test
    fun productionMapperAcceptsOnlyServerOwnedCreationMeaning() {
        val setup = authoritativeSetup()
        setup.mapParameters.apply {
            seed = 8675309L
            temperatureShift = 0.25f
            mirroring = "Left-right"
            tilesPerBiomeArea = 9
        }

        val mapped = ApiV3GameSetup.from(setup)

        assertEquals(setup.gameParameters.players.size, mapped.majorCivilizations)
        assertEquals(setup.gameParameters.numberOfCityStates, mapped.cityStates)
        assertEquals("Rome", mapped.ownerCivilizationId)
        assertEquals(8675309L, mapped.mapSeed)
        assertEquals(ApiV3MirroringType.LeftRight, mapped.mirroring)
        assertEquals(0.25f, mapped.temperatureShift)
        assertEquals(9, mapped.tilesPerBiomeArea)
    }

    @Test
    fun productionMapperRejectsClientOwnedIdentityAndGenerationInputs() {
        assertRejected { it.gameParameters.players[0].chosenCiv = Constants.random }
        assertRejected {
            it.gameParameters.players += Player(Constants.random, PlayerType.Human)
        }
        assertRejected { it.gameParameters.players[0].chosenCiv = Constants.spectator }
        assertRejected { it.gameParameters.anyoneCanSpectate = true }
        assertRejected { it.gameParameters.enableRandomNationsPool = true }
        assertRejected { it.gameParameters.godMode = true }
    }

    @Test
    fun publicSetupEncodingAndStoredWorkerSetupDecodingRemainCompatible() {
        val setup = ApiV3GameSetup.from(authoritativeSetup())
        val publicJson = Json.encodeToString(setup)
        assertTrue(publicJson.contains("\"owner_civilization_id\""))
        val storedWorkerJson = publicJson
            .replace("\"owner_civilization_id\"", "\"ownerCivilizationId\"")
            .replace("\"starting_era\"", "\"startingEra\"")
            .replace("\"victory_types\"", "\"victoryTypes\"")
            .replace("\"major_civilizations\"", "\"majorCivilizations\"")
            .replace("\"city_states\"", "\"cityStates\"")
            .replace("\"max_turns\"", "\"maxTurns\"")
            .replace("\"map_type\"", "\"mapType\"")
            .replace("\"map_shape\"", "\"mapShape\"")
            .replace("\"map_size\"", "\"mapSize\"")
            .replace("\"map_resources\"", "\"mapResources\"")
            .replace("\"one_city_challenge\"", "\"oneCityChallenge\"")
            .replace("\"nuclear_weapons_enabled\"", "\"nuclearWeaponsEnabled\"")
            .replace("\"espionage_enabled\"", "\"espionageEnabled\"")
            .replace("\"no_start_bias\"", "\"noStartBias\"")
            .replace("\"shuffle_player_order\"", "\"shufflePlayerOrder\"")
            .replace("\"no_city_razing\"", "\"noCityRazing\"")
            .replace("\"world_wrap\"", "\"worldWrap\"")
            .replace("\"strategic_balance\"", "\"strategicBalance\"")
            .replace("\"legendary_start\"", "\"legendaryStart\"")
            .replace("\"no_ruins\"", "\"noRuins\"")
            .replace("\"no_natural_wonders\"", "\"noNaturalWonders\"")
            .replace("\"map_seed\"", "\"mapSeed\"")
            .replace("\"tiles_per_biome_area\"", "\"tilesPerBiomeArea\"")
            .replace("\"max_coast_extension\"", "\"maxCoastExtension\"")
            .replace("\"elevation_exponent\"", "\"elevationExponent\"")
            .replace("\"temperature_intensity\"", "\"temperatureIntensity\"")
            .replace("\"temperature_shift\"", "\"temperatureShift\"")
            .replace("\"vegetation_richness\"", "\"vegetationRichness\"")
            .replace("\"rare_features_richness\"", "\"rareFeaturesRichness\"")
            .replace("\"resource_richness\"", "\"resourceRichness\"")
            .replace("\"water_threshold\"", "\"waterThreshold\"")

        assertEquals(setup, Json.decodeFromString<ApiV3GameSetup>(storedWorkerJson))
    }

    private fun assertRejected(change: (GameSetupInfo) -> Unit) {
        val setup = authoritativeSetup()
        change(setup)
        assertThrows(IllegalArgumentException::class.java) {
            ApiV3GameSetup.from(setup)
        }
    }

    private fun authoritativeSetup() = GameSetupInfo().apply {
        gameParameters.isOnlineMultiplayer = true
        gameParameters.anyoneCanSpectate = false
        gameParameters.players.forEach {
            it.chosenCiv = Constants.random
            it.playerType = PlayerType.AI
        }
        gameParameters.players.first().playerType = PlayerType.Human
        gameParameters.players.first().chosenCiv = "Rome"
    }
}
