package com.unciv.logic.multiplayer.authoritative

import com.unciv.logic.GameInfo
import kotlinx.serialization.Serializable

/**
 * Spectator view of a started match.
 *
 * Since v4 this carries the full revealed world - terrain, cities with their
 * borders, and public unit markers - because an invited spectator watches the
 * match exactly like a single-player spectator would: everything visible, no
 * private detail. What stays withheld is every owner-private family: yields,
 * construction queues, research, policies, diplomacy modifiers, spy networks,
 * notifications, movement orders and combat plans.
 */
@Serializable
data class SpectatorProjection(
    val protocolVersion: Int = CommandEnvelope.CURRENT_PROTOCOL_VERSION,
    val turn: Int,
    val currentPlayerCivilizationId: String,
    val activePlayerCivilizationIds: List<String> = emptyList(),
    val victory: ProjectedVictory? = null,
    val majorCivilizations: List<SpectatorCivilization>,
    val worldWrap: Boolean = false,
    /** Every tile of the map, fully revealed - the SP-spectator visibility rule. */
    val mapTiles: List<ProjectedTileVisibility> = emptyList(),
    /** Every city with its border tiles, so the map draws centres and borders. */
    val mapCities: List<ProjectedForeignCity> = emptyList(),
    /** Public unit markers: who stands where, nothing about orders or plans. */
    val mapUnits: List<SpectatorMapUnit> = emptyList(),
) {
    companion object {
        const val CURRENT_PROJECTION_VERSION = 4
    }
}

@Serializable
data class SpectatorCivilization(
    val civilizationId: String,
    val displayName: String,
    val humanControlled: Boolean,
    val defeated: Boolean,
)

/** A unit as a spectator sees it: identity and position, nothing else. */
@Serializable
data class SpectatorMapUnit(
    val id: Int,
    val civilizationId: String,
    val name: String,
    val x: Int,
    val y: Int,
)

object SpectatorProjectionBuilder {
    fun build(game: GameInfo) = SpectatorProjection(
        turn = game.turns,
        currentPlayerCivilizationId = game.currentPlayer,
        activePlayerCivilizationIds = if (game.gameParameters.simultaneousHumanTurns) {
            game.civilizations.asSequence()
                .filter { it.isHuman() && it.isAlive() && !it.isSpectator() }
                .filter { it.civID !in game.playersWhoEndedTurn }
                .map { it.civID }
                .sorted()
                .toList()
        } else listOf(game.currentPlayer),
        victory = game.victoryData?.let {
            ProjectedVictory(it.winningCiv, it.victoryType, it.victoryTurn)
        },
        majorCivilizations = game.civilizations.asSequence()
            .filter { it.isMajorCiv() }
            .sortedBy { it.civID }
            .map {
                SpectatorCivilization(
                    civilizationId = it.civID,
                    displayName = it.civName,
                    humanControlled = it.isHuman(),
                    defeated = it.isDefeated(),
                )
            }
            .toList(),
        worldWrap = game.tileMap.mapParameters.worldWrap,
        mapTiles = game.tileMap.tileList.asSequence()
            .map { tile ->
                ProjectedTileVisibility(
                    x = tile.position.x,
                    y = tile.position.y,
                    visible = true,
                    improvementName = tile.improvement,
                    improvementPillaged = tile.improvementIsPillaged.takeIf {
                        tile.improvement != null
                    },
                    roadStatus = tile.roadStatus.name,
                    roadPillaged = tile.roadIsPillaged,
                    baseTerrain = tile.baseTerrain,
                    terrainFeatures = tile.terrainFeatures.sorted(),
                    naturalWonderName = tile.naturalWonder,
                    resourceName = tile.resource?.takeIf(String::isNotBlank),
                    resourceAmount = tile.resourceAmount,
                )
            }
            .sortedWith(compareBy<ProjectedTileVisibility> { it.x }.thenBy { it.y })
            .toList(),
        mapCities = game.getCities().asSequence()
            .map { city ->
                ProjectedForeignCity(
                    id = city.id,
                    name = city.name,
                    civilizationId = city.civ.civID,
                    x = city.location.x,
                    y = city.location.y,
                    ownedTiles = city.tiles.asSequence()
                        .map { ProjectedTargetCoordinate(it.x, it.y) }
                        .sortedWith(compareBy<ProjectedTargetCoordinate> { it.x }.thenBy { it.y })
                        .toList(),
                )
            }
            .sortedWith(compareBy<ProjectedForeignCity> { it.id })
            .toList(),
        mapUnits = game.civilizations.asSequence()
            .flatMap { it.units.getCivUnits() }
            .mapIndexed { index, unit ->
                SpectatorMapUnit(
                    id = index + 1,
                    civilizationId = unit.civ.civID,
                    name = unit.name,
                    x = unit.getTile().position.x,
                    y = unit.getTile().position.y,
                )
            }
            .sortedBy { it.id }
            .toList(),
    )
}
