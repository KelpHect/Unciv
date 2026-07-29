package com.unciv.logic.multiplayer.authoritative

import com.unciv.Constants
import com.unciv.logic.civilization.PlayerType
import com.unciv.logic.map.MapParameters
import com.unciv.logic.map.MapShape
import com.unciv.logic.map.MapSize
import com.unciv.logic.map.MapType
import com.unciv.logic.map.MirroringType
import com.unciv.logic.map.mapgenerator.MapResourceSetting
import com.unciv.models.metadata.GameSetupInfo
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
enum class ApiV3GeneratedMapType {
    @SerialName("pangaea") Pangaea,
    @SerialName("small_continents") SmallContinents,
    @SerialName("perlin") Perlin,
    @SerialName("fractal") Fractal,
    @SerialName("continent_and_islands") ContinentAndIslands,
    @SerialName("archipelago") Archipelago,
    @SerialName("two_continents") TwoContinents,
    @SerialName("three_continents") ThreeContinents,
    @SerialName("inner_sea") InnerSea,
    @SerialName("lakes") Lakes,
    @SerialName("four_corners") FourCorners,
    @SerialName("spiral") Spiral,
    @SerialName("boreal") Boreal,
}

@Serializable
enum class ApiV3GeneratedMapShape {
    @SerialName("rectangular") Rectangular,
    @SerialName("hexagonal") Hexagonal,
    @SerialName("flat_earth") FlatEarth,
}

@Serializable
enum class ApiV3GeneratedMapSize {
    @SerialName("tiny") Tiny,
    @SerialName("small") Small,
    @SerialName("medium") Medium,
    @SerialName("large") Large,
    @SerialName("huge") Huge,
}

@Serializable
enum class ApiV3MapResourceDensity {
    @SerialName("sparse") Sparse,
    @SerialName("default") Default,
    @SerialName("abundant") Abundant,
}

@Serializable
enum class ApiV3BarbarianMode {
    @SerialName("disabled") Disabled,
    @SerialName("normal") Normal,
    @SerialName("raging") Raging,
}

@Serializable
data class ApiV3GameSetup(
    @SerialName("owner_civilization_id") val ownerCivilizationId: String,
    val difficulty: String,
    val speed: String,
    @SerialName("starting_era") val startingEra: String,
    @SerialName("victory_types") val victoryTypes: List<String>,
    @SerialName("major_civilizations") val majorCivilizations: Int,
    @SerialName("city_states") val cityStates: Int,
    @SerialName("max_turns") val maxTurns: Int,
    @SerialName("map_type") val mapType: ApiV3GeneratedMapType,
    @SerialName("map_shape") val mapShape: ApiV3GeneratedMapShape,
    @SerialName("map_size") val mapSize: ApiV3GeneratedMapSize,
    @SerialName("map_resources") val mapResources: ApiV3MapResourceDensity,
    val barbarians: ApiV3BarbarianMode,
    @SerialName("one_city_challenge") val oneCityChallenge: Boolean,
    @SerialName("nuclear_weapons_enabled") val nuclearWeaponsEnabled: Boolean,
    @SerialName("espionage_enabled") val espionageEnabled: Boolean,
    @SerialName("no_start_bias") val noStartBias: Boolean,
    @SerialName("shuffle_player_order") val shufflePlayerOrder: Boolean,
    @SerialName("no_city_razing") val noCityRazing: Boolean,
    @SerialName("world_wrap") val worldWrap: Boolean,
    @SerialName("strategic_balance") val strategicBalance: Boolean,
    @SerialName("legendary_start") val legendaryStart: Boolean,
    @SerialName("no_ruins") val noRuins: Boolean,
    @SerialName("no_natural_wonders") val noNaturalWonders: Boolean,
) {
    companion object {
        fun from(setup: GameSetupInfo): ApiV3GameSetup {
            val game = setup.gameParameters
            val map = setup.mapParameters
            require(!game.randomNumberOfPlayers && !game.randomNumberOfCityStates) {
                "API v3 setup currently requires fixed civilization counts"
            }
            require(map.name.isEmpty()) {
                "API v3 setup currently supports generated maps only"
            }
            require(game.players.count { it.playerType == PlayerType.Human } == 1) {
                "API v3 creates one authenticated owner; other players join the server lobby"
            }
            require(game.players.none { it.chosenCiv == Constants.spectator }) {
                "API v3 spectators are invited after game creation"
            }
            val owner = game.players.single { it.playerType == PlayerType.Human }
            require(owner.chosenCiv != Constants.random && owner.chosenCiv != Constants.spectator) {
                "Choose your civilization before creating the V3 lobby"
            }
            require(!game.enableRandomNationsPool && game.randomNationsPool.isEmpty()) {
                "API v3 does not accept a client-selected nation pool"
            }
            require(!game.anyoneCanSpectate) {
                "API v3 spectators require an owner invitation"
            }
            require(!game.godMode) {
                "API v3 does not support client god mode"
            }
            val defaultMap = MapParameters()
            require(
                map.mirroring == MirroringType.none &&
                    map.tilesPerBiomeArea == defaultMap.tilesPerBiomeArea &&
                    map.maxCoastExtension == defaultMap.maxCoastExtension &&
                    map.elevationExponent == defaultMap.elevationExponent &&
                    map.temperatureintensity == defaultMap.temperatureintensity &&
                    map.temperatureShift == defaultMap.temperatureShift &&
                    map.vegetationRichness == defaultMap.vegetationRichness &&
                    map.rareFeaturesRichness == defaultMap.rareFeaturesRichness &&
                    map.resourceRichness == defaultMap.resourceRichness &&
                    map.waterThreshold == defaultMap.waterThreshold
            ) {
                "API v3 does not accept client-authored advanced map generation values"
            }
            return ApiV3GameSetup(
                ownerCivilizationId = owner.chosenCiv,
                difficulty = game.difficulty,
                speed = game.speed,
                startingEra = game.startingEra,
                victoryTypes = game.victoryTypes.sorted(),
                majorCivilizations = game.players.size,
                cityStates = game.numberOfCityStates,
                maxTurns = game.maxTurns,
                mapType = map.type.toApiV3MapType(),
                mapShape = map.shape.toApiV3MapShape(),
                mapSize = map.mapSize.name.toApiV3MapSize(),
                mapResources = map.mapResources.toApiV3Resources(),
                barbarians = when {
                    game.noBarbarians -> ApiV3BarbarianMode.Disabled
                    game.ragingBarbarians -> ApiV3BarbarianMode.Raging
                    else -> ApiV3BarbarianMode.Normal
                },
                oneCityChallenge = game.oneCityChallenge,
                nuclearWeaponsEnabled = game.nuclearWeaponsEnabled,
                espionageEnabled = game.espionageEnabled,
                noStartBias = game.noStartBias,
                shufflePlayerOrder = game.shufflePlayerOrder,
                noCityRazing = game.noCityRazing,
                worldWrap = map.worldWrap,
                strategicBalance = map.strategicBalance,
                legendaryStart = map.legendaryStart,
                noRuins = map.noRuins,
                noNaturalWonders = map.noNaturalWonders,
            )
        }
    }
}

private fun String.toApiV3MapType() = when (this) {
    MapType.pangaea -> ApiV3GeneratedMapType.Pangaea
    MapType.smallContinents -> ApiV3GeneratedMapType.SmallContinents
    MapType.perlin -> ApiV3GeneratedMapType.Perlin
    MapType.fractal -> ApiV3GeneratedMapType.Fractal
    MapType.continentAndIslands -> ApiV3GeneratedMapType.ContinentAndIslands
    MapType.archipelago -> ApiV3GeneratedMapType.Archipelago
    MapType.twoContinents -> ApiV3GeneratedMapType.TwoContinents
    MapType.threeContinents -> ApiV3GeneratedMapType.ThreeContinents
    MapType.innerSea -> ApiV3GeneratedMapType.InnerSea
    MapType.lakes -> ApiV3GeneratedMapType.Lakes
    MapType.fourCorners -> ApiV3GeneratedMapType.FourCorners
    MapType.spiral -> ApiV3GeneratedMapType.Spiral
    MapType.boreal -> ApiV3GeneratedMapType.Boreal
    else -> error("Unsupported API v3 generated map type: $this")
}

private fun String.toApiV3MapShape() = when (this) {
    MapShape.rectangular -> ApiV3GeneratedMapShape.Rectangular
    MapShape.hexagonal -> ApiV3GeneratedMapShape.Hexagonal
    MapShape.flatEarth -> ApiV3GeneratedMapShape.FlatEarth
    else -> error("Unsupported API v3 map shape: $this")
}

private fun String.toApiV3MapSize() = when (this) {
    MapSize.Predefined.Tiny.name -> ApiV3GeneratedMapSize.Tiny
    MapSize.Predefined.Small.name -> ApiV3GeneratedMapSize.Small
    MapSize.Predefined.Medium.name -> ApiV3GeneratedMapSize.Medium
    MapSize.Predefined.Large.name -> ApiV3GeneratedMapSize.Large
    MapSize.Predefined.Huge.name -> ApiV3GeneratedMapSize.Huge
    else -> error("API v3 setup currently supports predefined map sizes only")
}

private fun String.toApiV3Resources() = when (this) {
    MapResourceSetting.sparse.label -> ApiV3MapResourceDensity.Sparse
    MapResourceSetting.default.label -> ApiV3MapResourceDensity.Default
    MapResourceSetting.abundant.label -> ApiV3MapResourceDensity.Abundant
    else -> error("Unsupported API v3 resource density: $this")
}
