package com.unciv.logic.multiplayer.authoritative

import com.unciv.Constants
import com.unciv.logic.civilization.PlayerType
import com.unciv.logic.map.MapShape
import com.unciv.logic.map.MapSize
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
    fun canonicalLobbySetupRoundTripsThroughTheProductionEditorModel() {
        val canonical = ApiV3GameSetup.from(authoritativeSetup().apply {
            gameParameters.apply {
                difficulty = "King"
                startingEra = "Classical era"
                noCityRazing = true
                shufflePlayerOrder = true
                victoryTypes.addAll(listOf("Domination", "Scientific"))
            }
            mapParameters.apply {
                type = "Archipelago"
                shape = "Rectangular"
                mapSize = com.unciv.logic.map.MapSize.Large
                worldWrap = true
                seed = 123456L
                temperatureShift = -0.2f
            }
        })

        val edited = ApiV3GameSetup.from(canonical.toGameSetupInfo())

        assertEquals(canonical, edited)
        assertEquals(
            1,
            canonical.toGameSetupInfo().gameParameters.players.count {
                it.playerType == PlayerType.Human
            },
        )
    }

    @Test
    fun customWorldDimensionsRoundTripWithoutBecomingAClientSave() {
        val rectangular = ApiV3GameSetup.from(authoritativeSetup().apply {
            mapParameters.shape = MapShape.rectangular
            mapParameters.mapSize = MapSize(72, 48)
        })
        val hexagonal = ApiV3GameSetup.from(authoritativeSetup().apply {
            mapParameters.shape = MapShape.hexagonal
            mapParameters.mapSize = MapSize(28)
        })

        assertEquals(ApiV3GeneratedMapSize.Custom, rectangular.mapSize)
        assertEquals(72, rectangular.customMapWidth)
        assertEquals(48, rectangular.customMapHeight)
        assertEquals(null, rectangular.customMapRadius)
        assertEquals(72, rectangular.toGameSetupInfo().mapParameters.mapSize.width)
        assertEquals(ApiV3GeneratedMapSize.Custom, hexagonal.mapSize)
        assertEquals(28, hexagonal.customMapRadius)
        assertEquals(null, hexagonal.customMapWidth)
        assertEquals(28, hexagonal.toGameSetupInfo().mapParameters.mapSize.radius)
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
            .replace("\"custom_map_radius\"", "\"customMapRadius\"")
            .replace("\"custom_map_width\"", "\"customMapWidth\"")
            .replace("\"custom_map_height\"", "\"customMapHeight\"")
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

    @Test
    fun aiRosterRoundTripsAndStaysAbsentWhenNothingIsPinned() {
        // An all-random roster keeps the legacy count-only wire shape.
        assertEquals(null, ApiV3GameSetup.from(authoritativeSetup()).aiCivilizations)

        val pinned = authoritativeSetup().apply {
            gameParameters.players[1].chosenCiv = "Greece"
            gameParameters.players[1].aiDifficulty = "Immortal"
            gameParameters.players[1].personality = "Aggressive"
            // A server-drawn nation can still carry a pinned personality.
            gameParameters.players[2].personality = "Expansionist"
        }
        val mapped = ApiV3GameSetup.from(pinned)
        val roster = requireNotNull(mapped.aiCivilizations)
        assertEquals(pinned.gameParameters.players.size - 1, roster.size)
        assertEquals(ApiV3AiSlot("Greece", "Immortal", "Aggressive"), roster[0])
        assertEquals(ApiV3AiSlot(personality = "Expansionist"), roster[1])
        assertTrue(roster.drop(2).all { it == ApiV3AiSlot() })

        // Materializing it back produces the same owner, count and pinned AI.
        val materialized = mapped.toGameSetupInfo().gameParameters
        assertEquals(mapped.majorCivilizations, materialized.players.size)
        assertEquals("Rome", materialized.players.first().chosenCiv)
        assertEquals(PlayerType.Human, materialized.players.first().playerType)
        assertEquals("Greece", materialized.players[1].chosenCiv)
        assertEquals(PlayerType.AI, materialized.players[1].playerType)
        assertEquals("Immortal", materialized.players[1].aiDifficulty)
        assertEquals("Aggressive", materialized.players[1].personality)
        assertEquals(Constants.random, materialized.players[2].chosenCiv)
        assertEquals("Expansionist", materialized.players[2].personality)
        assertTrue(materialized.players.drop(3).all { it.chosenCiv == Constants.random })
        assertEquals(mapped, ApiV3GameSetup.from(mapped.toGameSetupInfo()))
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
