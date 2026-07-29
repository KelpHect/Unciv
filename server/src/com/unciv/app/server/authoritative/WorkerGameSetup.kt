package com.unciv.app.server.authoritative

import com.unciv.Constants
import com.unciv.logic.civilization.PlayerType
import com.unciv.logic.map.MapParameters
import com.unciv.logic.map.MapShape
import com.unciv.logic.map.MapSize
import com.unciv.logic.map.MapType
import com.unciv.logic.map.MirroringType
import com.unciv.logic.map.mapgenerator.MapResourceSetting
import com.unciv.models.metadata.GameSetupInfo
import com.unciv.models.metadata.Player
import com.unciv.models.ruleset.RulesetCache
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
enum class GeneratedMapType {
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
enum class GeneratedMapShape {
    @SerialName("rectangular") Rectangular,
    @SerialName("hexagonal") Hexagonal,
    @SerialName("flat_earth") FlatEarth,
}

@Serializable
enum class WorkerMirroringType {
    @SerialName("none") None,
    @SerialName("top_bottom") TopBottom,
    @SerialName("left_right") LeftRight,
    @SerialName("around_center_tile") AroundCenterTile,
    @SerialName("four_way") FourWay,
}

@Serializable
enum class GeneratedMapSize {
    @SerialName("tiny") Tiny,
    @SerialName("small") Small,
    @SerialName("medium") Medium,
    @SerialName("large") Large,
    @SerialName("huge") Huge,
}

@Serializable
enum class MapResourceDensity {
    @SerialName("sparse") Sparse,
    @SerialName("default") Default,
    @SerialName("abundant") Abundant,
}

@Serializable
enum class BarbarianMode {
    @SerialName("disabled") Disabled,
    @SerialName("normal") Normal,
    @SerialName("raging") Raging,
}

@Serializable
data class WorkerLobbyParticipant(
    val accountId: String,
    val civilizationId: String,
)

@Serializable
data class WorkerGameSetup(
    val ownerCivilizationId: String,
    val difficulty: String,
    val speed: String,
    val startingEra: String,
    val victoryTypes: List<String>,
    val majorCivilizations: Int,
    val cityStates: Int,
    val maxTurns: Int,
    val mapType: GeneratedMapType,
    val mapShape: GeneratedMapShape,
    val mapSize: GeneratedMapSize,
    val mapResources: MapResourceDensity,
    val barbarians: BarbarianMode,
    val oneCityChallenge: Boolean,
    val nuclearWeaponsEnabled: Boolean,
    val espionageEnabled: Boolean,
    val noStartBias: Boolean,
    val shufflePlayerOrder: Boolean,
    val noCityRazing: Boolean,
    val worldWrap: Boolean,
    val strategicBalance: Boolean,
    val legendaryStart: Boolean,
    val noRuins: Boolean,
    val noNaturalWonders: Boolean,
    val mapSeed: Long? = null,
    val mirroring: WorkerMirroringType = WorkerMirroringType.None,
    val tilesPerBiomeArea: Int = 6,
    val maxCoastExtension: Int = 2,
    val elevationExponent: Float = 0.7f,
    val temperatureIntensity: Float = 0.6f,
    val temperatureShift: Float = 0f,
    val vegetationRichness: Float = 0.4f,
    val rareFeaturesRichness: Float = 0.05f,
    val resourceRichness: Float = 0.1f,
    val waterThreshold: Float = 0f,
) {
    fun materialize(
        manifest: WorkerRulesetManifest,
        actorId: String,
        serverSeed: Long,
        participants: List<WorkerLobbyParticipant>? = null,
    ): GameSetupInfo {
        require(actorId.isNotBlank()) { "Authenticated owner identity is blank" }
        require(majorCivilizations in 2..16) { "Major civilization count is outside server bounds" }
        require(cityStates in 0..64) { "City-state count is outside server bounds" }
        require(maxTurns in 100..1500) { "Maximum turns is outside server bounds" }
        require(victoryTypes.isNotEmpty() && victoryTypes.size <= 16) {
            "Victory selection is outside server bounds"
        }
        require(victoryTypes.distinct().size == victoryTypes.size) {
            "Victory selection must be unique"
        }
        require(tilesPerBiomeArea in 1..15 && maxCoastExtension in 1..5) {
            "Advanced map sizes are outside server bounds"
        }
        require(elevationExponent in 0.6f..0.8f)
        require(temperatureIntensity in 0.4f..0.8f)
        require(temperatureShift in -0.4f..0.4f)
        require(vegetationRichness in 0f..1f)
        require(rareFeaturesRichness in 0f..0.5f)
        require(resourceRichness in 0f..0.5f)
        require(waterThreshold in -0.1f..0.1f)

        val mods = manifest.mods.mapTo(linkedSetOf()) { it.name }
        val ruleset = RulesetCache.getComplexRuleset(mods, manifest.baseRuleset.name)
        require(difficulty in ruleset.difficulties) { "Difficulty is unavailable in the pinned ruleset" }
        require(speed in ruleset.speeds) { "Game speed is unavailable in the pinned ruleset" }
        require(startingEra in ruleset.eras) { "Starting era is unavailable in the pinned ruleset" }
        require(victoryTypes.all { ruleset.victories[it]?.hiddenInVictoryScreen == false }) {
            "Victory type is unavailable in the pinned ruleset"
        }
        require(majorCivilizations <= ruleset.nations.values.count { it.isMajorCiv }) {
            "The pinned ruleset has too few major civilizations"
        }
        require(cityStates <= ruleset.nations.values.count { it.isCityState }) {
            "The pinned ruleset has too few city-states"
        }
        val ownerNation = ruleset.nations[ownerCivilizationId]
        require(ownerNation?.isMajorCiv == true && ownerCivilizationId != Constants.spectator) {
            "The selected owner civilization is unavailable in the pinned ruleset"
        }
        val humans = participants ?: listOf(WorkerLobbyParticipant(actorId, ownerCivilizationId))
        require(humans.isNotEmpty() && humans.size <= majorCivilizations) {
            "Human participant count is outside setup bounds"
        }
        require(humans.first() == WorkerLobbyParticipant(actorId, ownerCivilizationId)) {
            "The authenticated owner must be the first exact participant"
        }
        require(humans.map { it.accountId }.distinct().size == humans.size) {
            "Human account assignments must be unique"
        }
        require(humans.map { it.civilizationId }.distinct().size == humans.size) {
            "Human civilization assignments must be unique"
        }
        require(humans.all { participant ->
            participant.accountId.isNotBlank() &&
                ruleset.nations[participant.civilizationId]?.isMajorCiv == true &&
                participant.civilizationId != Constants.spectator
        }) { "A human participant is unavailable in the pinned ruleset" }

        return GameSetupInfo().apply {
            gameParameters.difficulty = difficulty
            gameParameters.speed = speed
            gameParameters.startingEra = startingEra
            gameParameters.victoryTypes = ArrayList(victoryTypes)
            gameParameters.players = ArrayList<Player>(majorCivilizations).apply {
                humans.forEach { participant ->
                    add(Player(participant.civilizationId, PlayerType.Human, participant.accountId))
                }
                repeat(majorCivilizations - humans.size) { add(Player()) }
            }
            gameParameters.numberOfCityStates = cityStates
            gameParameters.maxTurns = maxTurns
            gameParameters.noBarbarians = barbarians == BarbarianMode.Disabled
            gameParameters.ragingBarbarians = barbarians == BarbarianMode.Raging
            gameParameters.oneCityChallenge = oneCityChallenge
            gameParameters.nuclearWeaponsEnabled = nuclearWeaponsEnabled
            gameParameters.espionageEnabled = espionageEnabled
            gameParameters.noStartBias = noStartBias
            gameParameters.shufflePlayerOrder = shufflePlayerOrder
            gameParameters.noCityRazing = noCityRazing
            // V3 matches have no skip, force-resign, or total-play clock.
            gameParameters.minutesUntilSkipTurn = Int.MAX_VALUE
            gameParameters.minutesUntilForceResign = Int.MAX_VALUE
            gameParameters.minutesRecoveredPerTurn = 0
            gameParameters.isOnlineMultiplayer = true
            gameParameters.multiplayerServerUrl = null
            gameParameters.anyoneCanSpectate = false
            gameParameters.baseRuleset = manifest.baseRuleset.name
            gameParameters.mods = mods

            mapParameters.seed = mapSeed ?: serverSeed
            mapParameters.type = mapType.toEngine()
            mapParameters.shape = mapShape.toEngine()
            mapParameters.mapSize = mapSize.toEngine()
            mapParameters.mapResources = mapResources.toEngine()
            mapParameters.mirroring = mirroring.toEngine()
            mapParameters.tilesPerBiomeArea = tilesPerBiomeArea
            mapParameters.maxCoastExtension = maxCoastExtension
            mapParameters.elevationExponent = elevationExponent
            mapParameters.temperatureintensity = temperatureIntensity
            mapParameters.temperatureShift = temperatureShift
            mapParameters.vegetationRichness = vegetationRichness
            mapParameters.rareFeaturesRichness = rareFeaturesRichness
            mapParameters.resourceRichness = resourceRichness
            mapParameters.waterThreshold = waterThreshold
            mapParameters.worldWrap = worldWrap
            mapParameters.strategicBalance = strategicBalance
            mapParameters.legendaryStart = legendaryStart
            mapParameters.noRuins = noRuins
            mapParameters.noNaturalWonders = noNaturalWonders
            mapParameters.baseRuleset = manifest.baseRuleset.name
            mapParameters.mods = LinkedHashSet(mods)
        }
    }
}

private fun WorkerMirroringType.toEngine() = when (this) {
    WorkerMirroringType.None -> MirroringType.none
    WorkerMirroringType.TopBottom -> MirroringType.topbottom
    WorkerMirroringType.LeftRight -> MirroringType.leftright
    WorkerMirroringType.AroundCenterTile -> MirroringType.aroundCenterTile
    WorkerMirroringType.FourWay -> MirroringType.fourway
}

private fun GeneratedMapType.toEngine() = when (this) {
    GeneratedMapType.Pangaea -> MapType.pangaea
    GeneratedMapType.SmallContinents -> MapType.smallContinents
    GeneratedMapType.Perlin -> MapType.perlin
    GeneratedMapType.Fractal -> MapType.fractal
    GeneratedMapType.ContinentAndIslands -> MapType.continentAndIslands
    GeneratedMapType.Archipelago -> MapType.archipelago
    GeneratedMapType.TwoContinents -> MapType.twoContinents
    GeneratedMapType.ThreeContinents -> MapType.threeContinents
    GeneratedMapType.InnerSea -> MapType.innerSea
    GeneratedMapType.Lakes -> MapType.lakes
    GeneratedMapType.FourCorners -> MapType.fourCorners
    GeneratedMapType.Spiral -> MapType.spiral
    GeneratedMapType.Boreal -> MapType.boreal
}

private fun GeneratedMapShape.toEngine() = when (this) {
    GeneratedMapShape.Rectangular -> MapShape.rectangular
    GeneratedMapShape.Hexagonal -> MapShape.hexagonal
    GeneratedMapShape.FlatEarth -> MapShape.flatEarth
}

private fun GeneratedMapSize.toEngine() = when (this) {
    GeneratedMapSize.Tiny -> MapSize.Tiny
    GeneratedMapSize.Small -> MapSize.Small
    GeneratedMapSize.Medium -> MapSize.Medium
    GeneratedMapSize.Large -> MapSize.Large
    GeneratedMapSize.Huge -> MapSize.Huge
}

private fun MapResourceDensity.toEngine() = when (this) {
    MapResourceDensity.Sparse -> MapResourceSetting.sparse.label
    MapResourceDensity.Default -> MapResourceSetting.default.label
    MapResourceDensity.Abundant -> MapResourceSetting.abundant.label
}
