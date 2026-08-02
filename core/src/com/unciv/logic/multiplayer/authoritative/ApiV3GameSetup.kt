package com.unciv.logic.multiplayer.authoritative

import com.unciv.Constants
import com.unciv.logic.civilization.PlayerType
import com.unciv.logic.map.MapShape
import com.unciv.logic.map.MapSize
import com.unciv.logic.map.MapType
import com.unciv.logic.map.MirroringType
import com.unciv.logic.map.mapgenerator.MapResourceSetting
import com.unciv.models.metadata.GameSetupInfo
import com.unciv.models.metadata.Player
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.JsonNames

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
enum class ApiV3MirroringType {
    @SerialName("none") None,
    @SerialName("top_bottom") TopBottom,
    @SerialName("left_right") LeftRight,
    @SerialName("around_center_tile") AroundCenterTile,
    @SerialName("four_way") FourWay,
}

@Serializable
enum class ApiV3GeneratedMapSize {
    @SerialName("tiny") Tiny,
    @SerialName("small") Small,
    @SerialName("medium") Medium,
    @SerialName("large") Large,
    @SerialName("huge") Huge,
    @SerialName("custom") Custom,
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

/**
 * One AI seat the host authored in the staging room. Every field is optional:
 * a blank [civilizationId] lets the server draw the nation, a blank [difficulty]
 * uses the match's AI difficulty, and a blank [personality] uses whatever
 * personality the chosen nation already carries.
 */
@Serializable
@OptIn(ExperimentalSerializationApi::class)
data class ApiV3AiSlot(
    @JsonNames("civilizationId")
    @SerialName("civilization_id") val civilizationId: String = "",
    val difficulty: String = "",
    val personality: String = "",
)

@Serializable
@OptIn(ExperimentalSerializationApi::class)
data class ApiV3GameSetup(
    @JsonNames("ownerCivilizationId")
    @SerialName("owner_civilization_id") val ownerCivilizationId: String,
    val difficulty: String,
    val speed: String,
    @JsonNames("startingEra")
    @SerialName("starting_era") val startingEra: String,
    @JsonNames("victoryTypes")
    @SerialName("victory_types") val victoryTypes: List<String>,
    @JsonNames("majorCivilizations")
    @SerialName("major_civilizations") val majorCivilizations: Int,
    @JsonNames("cityStates")
    @SerialName("city_states") val cityStates: Int,
    /**
     * Owner-authored AI roster: one entry per AI civilization. `null` keeps the
     * legacy count-only meaning, where the server chooses every non-human major
     * civilization and they all share the match's AI difficulty.
     */
    @JsonNames("aiCivilizations")
    @SerialName("ai_civilizations") val aiCivilizations: List<ApiV3AiSlot>? = null,
    @JsonNames("maxTurns")
    @SerialName("max_turns") val maxTurns: Int,
    @JsonNames("mapType")
    @SerialName("map_type") val mapType: ApiV3GeneratedMapType,
    @JsonNames("mapShape")
    @SerialName("map_shape") val mapShape: ApiV3GeneratedMapShape,
    @JsonNames("mapSize")
    @SerialName("map_size") val mapSize: ApiV3GeneratedMapSize,
    @JsonNames("customMapRadius")
    @SerialName("custom_map_radius") val customMapRadius: Int? = null,
    @JsonNames("customMapWidth")
    @SerialName("custom_map_width") val customMapWidth: Int? = null,
    @JsonNames("customMapHeight")
    @SerialName("custom_map_height") val customMapHeight: Int? = null,
    @JsonNames("mapResources")
    @SerialName("map_resources") val mapResources: ApiV3MapResourceDensity,
    val barbarians: ApiV3BarbarianMode,
    @JsonNames("oneCityChallenge")
    @SerialName("one_city_challenge") val oneCityChallenge: Boolean,
    @JsonNames("nuclearWeaponsEnabled")
    @SerialName("nuclear_weapons_enabled") val nuclearWeaponsEnabled: Boolean,
    @JsonNames("espionageEnabled")
    @SerialName("espionage_enabled") val espionageEnabled: Boolean,
    @JsonNames("noStartBias")
    @SerialName("no_start_bias") val noStartBias: Boolean,
    @JsonNames("shufflePlayerOrder")
    @SerialName("shuffle_player_order") val shufflePlayerOrder: Boolean,
    @JsonNames("noCityRazing")
    @SerialName("no_city_razing") val noCityRazing: Boolean,
    @JsonNames("worldWrap")
    @SerialName("world_wrap") val worldWrap: Boolean,
    @JsonNames("strategicBalance")
    @SerialName("strategic_balance") val strategicBalance: Boolean,
    @JsonNames("legendaryStart")
    @SerialName("legendary_start") val legendaryStart: Boolean,
    @JsonNames("noRuins")
    @SerialName("no_ruins") val noRuins: Boolean,
    @JsonNames("noNaturalWonders")
    @SerialName("no_natural_wonders") val noNaturalWonders: Boolean,
    @JsonNames("mapSeed")
    @SerialName("map_seed") val mapSeed: Long? = null,
    val mirroring: ApiV3MirroringType = ApiV3MirroringType.None,
    @JsonNames("tilesPerBiomeArea")
    @SerialName("tiles_per_biome_area") val tilesPerBiomeArea: Int = 6,
    @JsonNames("maxCoastExtension")
    @SerialName("max_coast_extension") val maxCoastExtension: Int = 2,
    @JsonNames("elevationExponent")
    @SerialName("elevation_exponent") val elevationExponent: Float = 0.7f,
    @JsonNames("temperatureIntensity")
    @SerialName("temperature_intensity") val temperatureIntensity: Float = 0.6f,
    @JsonNames("temperatureShift")
    @SerialName("temperature_shift") val temperatureShift: Float = 0f,
    @JsonNames("vegetationRichness")
    @SerialName("vegetation_richness") val vegetationRichness: Float = 0.4f,
    @JsonNames("rareFeaturesRichness")
    @SerialName("rare_features_richness") val rareFeaturesRichness: Float = 0.05f,
    @JsonNames("resourceRichness")
    @SerialName("resource_richness") val resourceRichness: Float = 0.1f,
    @JsonNames("waterThreshold")
    @SerialName("water_threshold") val waterThreshold: Float = 0f,
) {
    fun toGameSetupInfo(): GameSetupInfo = GameSetupInfo().apply {
        gameParameters.apply {
            difficulty = this@ApiV3GameSetup.difficulty
            speed = this@ApiV3GameSetup.speed
            startingEra = this@ApiV3GameSetup.startingEra
            victoryTypes = ArrayList(this@ApiV3GameSetup.victoryTypes)
            players = ArrayList<Player>().apply {
                add(Player(ownerCivilizationId, PlayerType.Human))
                aiCivilizations.orEmpty().forEach { slot ->
                    add(
                        Player(
                            chosenCiv = slot.civilizationId.ifBlank { Constants.random },
                            aiDifficulty = slot.difficulty,
                            personality = slot.personality,
                        ),
                    )
                }
                repeat(
                    (majorCivilizations - 1 - aiCivilizations.orEmpty().size).coerceAtLeast(0),
                ) { add(Player()) }
            }
            randomNumberOfPlayers = false
            numberOfCityStates = cityStates
            randomNumberOfCityStates = false
            maxTurns = this@ApiV3GameSetup.maxTurns
            noBarbarians = barbarians == ApiV3BarbarianMode.Disabled
            ragingBarbarians = barbarians == ApiV3BarbarianMode.Raging
            oneCityChallenge = this@ApiV3GameSetup.oneCityChallenge
            nuclearWeaponsEnabled = this@ApiV3GameSetup.nuclearWeaponsEnabled
            espionageEnabled = this@ApiV3GameSetup.espionageEnabled
            noStartBias = this@ApiV3GameSetup.noStartBias
            shufflePlayerOrder = this@ApiV3GameSetup.shufflePlayerOrder
            noCityRazing = this@ApiV3GameSetup.noCityRazing
            isOnlineMultiplayer = true
            anyoneCanSpectate = false
        }
        mapParameters.apply {
            name = ""
            type = mapType.toMapType()
            shape = mapShape.toMapShape()
            mapSize = this@ApiV3GameSetup.toMapSize()
            mapResources = this@ApiV3GameSetup.mapResources.toMapResources()
            mirroring = this@ApiV3GameSetup.mirroring.toMirroring()
            noRuins = this@ApiV3GameSetup.noRuins
            noNaturalWonders = this@ApiV3GameSetup.noNaturalWonders
            worldWrap = this@ApiV3GameSetup.worldWrap
            strategicBalance = this@ApiV3GameSetup.strategicBalance
            legendaryStart = this@ApiV3GameSetup.legendaryStart
            mapSeed?.let { seed = it }
            tilesPerBiomeArea = this@ApiV3GameSetup.tilesPerBiomeArea
            maxCoastExtension = this@ApiV3GameSetup.maxCoastExtension
            elevationExponent = this@ApiV3GameSetup.elevationExponent
            temperatureintensity = temperatureIntensity
            temperatureShift = this@ApiV3GameSetup.temperatureShift
            vegetationRichness = this@ApiV3GameSetup.vegetationRichness
            rareFeaturesRichness = this@ApiV3GameSetup.rareFeaturesRichness
            resourceRichness = this@ApiV3GameSetup.resourceRichness
            waterThreshold = this@ApiV3GameSetup.waterThreshold
        }
    }

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
            return ApiV3GameSetup(
                ownerCivilizationId = owner.chosenCiv,
                difficulty = game.difficulty,
                speed = game.speed,
                startingEra = game.startingEra,
                victoryTypes = game.victoryTypes.sorted(),
                majorCivilizations = game.players.size,
                cityStates = game.numberOfCityStates,
                // Only authored rosters travel: an untouched roster stays `null` so
                // existing count-only setups keep their exact wire meaning.
                aiCivilizations = game.players
                    .filter { it !== owner }
                    .map { player ->
                        ApiV3AiSlot(
                            civilizationId = player.chosenCiv.takeIf {
                                it != Constants.random
                            }.orEmpty(),
                            difficulty = player.aiDifficulty,
                            personality = player.personality,
                        )
                    }
                    .takeIf { roster -> roster.any { it != ApiV3AiSlot() } },
                maxTurns = game.maxTurns,
                mapType = map.type.toApiV3MapType(),
                mapShape = map.shape.toApiV3MapShape(),
                mapSize = map.mapSize.name.toApiV3MapSize(),
                customMapRadius = map.mapSize.radius.takeIf {
                    map.mapSize.name == MapSize.custom && map.shape != MapShape.rectangular
                },
                customMapWidth = map.mapSize.width.takeIf {
                    map.mapSize.name == MapSize.custom && map.shape == MapShape.rectangular
                },
                customMapHeight = map.mapSize.height.takeIf {
                    map.mapSize.name == MapSize.custom && map.shape == MapShape.rectangular
                },
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
                mapSeed = map.seed,
                mirroring = map.mirroring.toApiV3Mirroring(),
                tilesPerBiomeArea = map.tilesPerBiomeArea,
                maxCoastExtension = map.maxCoastExtension,
                elevationExponent = map.elevationExponent,
                temperatureIntensity = map.temperatureintensity,
                temperatureShift = map.temperatureShift,
                vegetationRichness = map.vegetationRichness,
                rareFeaturesRichness = map.rareFeaturesRichness,
                resourceRichness = map.resourceRichness,
                waterThreshold = map.waterThreshold,
            )
        }
    }
}

private fun String.toApiV3Mirroring() = when (this) {
    MirroringType.none -> ApiV3MirroringType.None
    MirroringType.topbottom -> ApiV3MirroringType.TopBottom
    MirroringType.leftright -> ApiV3MirroringType.LeftRight
    MirroringType.aroundCenterTile -> ApiV3MirroringType.AroundCenterTile
    MirroringType.fourway -> ApiV3MirroringType.FourWay
    else -> error("Unsupported API v3 mirroring type: $this")
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
    MapSize.custom -> ApiV3GeneratedMapSize.Custom
    else -> error("Unsupported API v3 map size: $this")
}

private fun String.toApiV3Resources() = when (this) {
    MapResourceSetting.sparse.label -> ApiV3MapResourceDensity.Sparse
    MapResourceSetting.default.label -> ApiV3MapResourceDensity.Default
    MapResourceSetting.abundant.label -> ApiV3MapResourceDensity.Abundant
    else -> error("Unsupported API v3 resource density: $this")
}

private fun ApiV3MirroringType.toMirroring() = when (this) {
    ApiV3MirroringType.None -> MirroringType.none
    ApiV3MirroringType.TopBottom -> MirroringType.topbottom
    ApiV3MirroringType.LeftRight -> MirroringType.leftright
    ApiV3MirroringType.AroundCenterTile -> MirroringType.aroundCenterTile
    ApiV3MirroringType.FourWay -> MirroringType.fourway
}

private fun ApiV3GeneratedMapType.toMapType() = when (this) {
    ApiV3GeneratedMapType.Pangaea -> MapType.pangaea
    ApiV3GeneratedMapType.SmallContinents -> MapType.smallContinents
    ApiV3GeneratedMapType.Perlin -> MapType.perlin
    ApiV3GeneratedMapType.Fractal -> MapType.fractal
    ApiV3GeneratedMapType.ContinentAndIslands -> MapType.continentAndIslands
    ApiV3GeneratedMapType.Archipelago -> MapType.archipelago
    ApiV3GeneratedMapType.TwoContinents -> MapType.twoContinents
    ApiV3GeneratedMapType.ThreeContinents -> MapType.threeContinents
    ApiV3GeneratedMapType.InnerSea -> MapType.innerSea
    ApiV3GeneratedMapType.Lakes -> MapType.lakes
    ApiV3GeneratedMapType.FourCorners -> MapType.fourCorners
    ApiV3GeneratedMapType.Spiral -> MapType.spiral
    ApiV3GeneratedMapType.Boreal -> MapType.boreal
}

private fun ApiV3GeneratedMapShape.toMapShape() = when (this) {
    ApiV3GeneratedMapShape.Rectangular -> MapShape.rectangular
    ApiV3GeneratedMapShape.Hexagonal -> MapShape.hexagonal
    ApiV3GeneratedMapShape.FlatEarth -> MapShape.flatEarth
}

private fun ApiV3GameSetup.toMapSize() = when (mapSize) {
    ApiV3GeneratedMapSize.Tiny -> MapSize.Tiny
    ApiV3GeneratedMapSize.Small -> MapSize.Small
    ApiV3GeneratedMapSize.Medium -> MapSize.Medium
    ApiV3GeneratedMapSize.Large -> MapSize.Large
    ApiV3GeneratedMapSize.Huge -> MapSize.Huge
    ApiV3GeneratedMapSize.Custom -> when (mapShape) {
        ApiV3GeneratedMapShape.Hexagonal,
        ApiV3GeneratedMapShape.FlatEarth,
        -> MapSize(requireNotNull(customMapRadius) { "Custom map radius is required" })
        ApiV3GeneratedMapShape.Rectangular -> MapSize(
            requireNotNull(customMapWidth) { "Custom map width is required" },
            requireNotNull(customMapHeight) { "Custom map height is required" },
        )
    }
}

private fun ApiV3MapResourceDensity.toMapResources() = when (this) {
    ApiV3MapResourceDensity.Sparse -> MapResourceSetting.sparse.label
    ApiV3MapResourceDensity.Default -> MapResourceSetting.default.label
    ApiV3MapResourceDensity.Abundant -> MapResourceSetting.abundant.label
}
