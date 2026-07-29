package com.unciv.app.server.authoritative

import com.unciv.logic.GameInfo
import com.unciv.logic.files.UncivFiles
import com.unciv.models.ruleset.RulesetCache

/** Shared deterministic game/request fixture for stateful packaged-worker scenarios. */
internal class PackagedWorkerScenarioFixture(
    private val actorId: String,
    private val gameId: String,
    private val serverSeed: Long,
    private val baseName: String = "Civ V - Vanilla",
    private val startingEra: String? = null,
    private val majorCivilizations: Int = 2,
    private val cityStates: Int = 0,
    private val barbarians: BarbarianMode = BarbarianMode.Disabled,
    private val modNames: List<String> = emptyList(),
    private val ownerCivilizationId: String = "Rome",
) {
    val manifest by lazy {
        WorkerRulesetManifest(
            engineBuild = InstalledRulesetCatalog.engineBuild,
            baseRuleset = InstalledRulesetCatalog.named(baseName),
            mods = modNames.sorted().map(InstalledRulesetCatalog::named),
        )
    }

    fun createGameRequest(serverTime: Long): WorkerRequest {
        val ruleset = requireNotNull(RulesetCache[baseName])
        return request(
            WorkerOperation.CreateGame(
                gameId = gameId,
                serverSeed = serverSeed,
                setup = WorkerGameSetup(
                    difficulty = ruleset.difficulties.keys.first(),
                    speed = ruleset.speeds.keys.first(),
                    startingEra = startingEra ?: ruleset.eras.keys.first(),
                    victoryTypes = ruleset.victories.values
                        .filterNot { it.hiddenInVictoryScreen }
                        .map { it.name }
                        .sorted(),
                    majorCivilizations = majorCivilizations,
                    cityStates = cityStates,
                    maxTurns = 500,
                    mapType = GeneratedMapType.Pangaea,
                    mapShape = GeneratedMapShape.Rectangular,
                    mapSize = GeneratedMapSize.Tiny,
                    mapResources = MapResourceDensity.Default,
                    barbarians = barbarians,
                    oneCityChallenge = false,
                    nuclearWeaponsEnabled = true,
                    espionageEnabled = true,
                    noStartBias = true,
                    shufflePlayerOrder = false,
                    noCityRazing = false,
                    worldWrap = false,
                    strategicBalance = false,
                    legendaryStart = false,
                    noRuins = true,
                    noNaturalWonders = true,
                    ownerCivilizationId = ownerCivilizationId,
                ),
            ),
            serverTime,
        )
    }

    fun request(
        operation: WorkerOperation,
        serverTime: Long,
        authenticatedActorId: String = actorId,
    ) = WorkerRequest(
        protocolVersion = EngineWorkerProtocol.VERSION,
        serverTimeMillis = serverTime,
        actorId = authenticatedActorId,
        rulesetManifest = manifest,
        operation = operation,
    )

    fun decode(snapshot: String?): GameInfo =
        UncivFiles.gameInfoFromString(requireNotNull(snapshot)).also(GameInfo::setTransients)

    fun encode(game: GameInfo): String =
        UncivFiles.gameInfoToString(game, forceZip = false, updateChecksum = false)
}
