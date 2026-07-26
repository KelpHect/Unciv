package com.unciv.app.server.authoritative

import com.unciv.Constants
import com.unciv.logic.civilization.PlayerType
import com.unciv.logic.map.MapParameters
import com.unciv.logic.map.MapShape
import com.unciv.logic.map.MapSize
import com.unciv.logic.map.MapType
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
data class WorkerGameSetup(
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
    val minutesUntilSkipTurn: Int,
    val minutesUntilForceResign: Int,
    val minutesRecoveredPerTurn: Int,
) {
    fun materialize(
        manifest: WorkerRulesetManifest,
        actorId: String,
        serverSeed: Long,
    ): GameSetupInfo {
        require(actorId.isNotBlank()) { "Authenticated owner identity is blank" }
        require(majorCivilizations in 2..16) { "Major civilization count is outside server bounds" }
        require(cityStates in 0..64) { "City-state count is outside server bounds" }
        require(maxTurns in 100..1500) { "Maximum turns is outside server bounds" }
        require(minutesUntilSkipTurn in 5..10_080) { "Skip-turn timer is outside server bounds" }
        require(minutesUntilForceResign in 60..43_200) { "Force-resign timer is outside server bounds" }
        require(minutesRecoveredPerTurn in 0..10_080) { "Recovered-turn timer is outside server bounds" }
        require(victoryTypes.isNotEmpty() && victoryTypes.size <= 16) {
            "Victory selection is outside server bounds"
        }
        require(victoryTypes.distinct().size == victoryTypes.size) {
            "Victory selection must be unique"
        }

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

        return GameSetupInfo().apply {
            gameParameters.difficulty = difficulty
            gameParameters.speed = speed
            gameParameters.startingEra = startingEra
            gameParameters.victoryTypes = ArrayList(victoryTypes)
            gameParameters.players = ArrayList<Player>(majorCivilizations).apply {
                add(Player(Constants.random, PlayerType.Human, actorId))
                repeat(majorCivilizations - 1) { add(Player()) }
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
            gameParameters.minutesUntilSkipTurn = minutesUntilSkipTurn
            gameParameters.minutesUntilForceResign = minutesUntilForceResign
            gameParameters.minutesRecoveredPerTurn = minutesRecoveredPerTurn
            gameParameters.isOnlineMultiplayer = true
            gameParameters.multiplayerServerUrl = null
            gameParameters.anyoneCanSpectate = false
            gameParameters.baseRuleset = manifest.baseRuleset.name
            gameParameters.mods = mods

            mapParameters.seed = serverSeed
            mapParameters.type = mapType.toEngine()
            mapParameters.shape = mapShape.toEngine()
            mapParameters.mapSize = mapSize.toEngine()
            mapParameters.mapResources = mapResources.toEngine()
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
