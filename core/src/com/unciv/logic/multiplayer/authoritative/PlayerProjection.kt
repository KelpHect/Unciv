package com.unciv.logic.multiplayer.authoritative

import com.unciv.logic.GameInfo
import com.unciv.logic.civilization.Civilization
import com.unciv.logic.map.mapunit.MapUnit
import kotlinx.serialization.Serializable

/**
 * Deliberately constructed player view. This is not a redacted GameInfo: fields
 * absent from these DTOs cannot leak through serialization accidentally.
 */
@Serializable
data class PlayerProjection(
    val protocolVersion: Int = CommandEnvelope.CURRENT_PROTOCOL_VERSION,
    val civilizationId: String,
    val turn: Int,
    val currentPlayerCivilizationId: String,
    val isCurrentTurn: Boolean,
    val gold: Int,
    val knownCivilizations: List<String>,
    val ownCities: List<ProjectedCity>,
    val ownUnits: List<ProjectedUnit>,
    val exploredTiles: List<ProjectedTileVisibility>,
    val visibleForeignUnits: List<ProjectedUnit>,
) {
    companion object {
        const val CURRENT_PROJECTION_VERSION = 2
    }
}

@Serializable
data class ProjectedCity(
    val id: String,
    val name: String,
    val x: Int,
    val y: Int,
    val population: Int,
    val health: Int,
    val constructionQueue: List<String>,
    val availableConstructions: List<String>,
)

@Serializable
data class ProjectedUnit(
    val id: Int,
    val civilizationId: String,
    val name: String,
    val x: Int,
    val y: Int,
    val health: Int,
    val currentMovement: Float,
)

@Serializable
data class ProjectedTileVisibility(
    val x: Int,
    val y: Int,
    val visible: Boolean,
)

object PlayerProjectionBuilder {
    fun build(game: GameInfo, actor: Civilization): PlayerProjection {
        val ownUnits = actor.units.getCivUnits().map(::unitProjection).sortedBy { it.id }.toList()
        val visibleForeignUnits = game.tileMap.tileList.asSequence()
            .filter { it in actor.viewableTiles }
            .flatMap { it.getUnits() }
            .filter { it.civ != actor }
            .filter { !it.isInvisible(actor) || it.getTile() in actor.viewableInvisibleUnitsTiles }
            .map(::unitProjection)
            .sortedWith(compareBy<ProjectedUnit> { it.civilizationId }.thenBy { it.id })
            .toList()
        return PlayerProjection(
            civilizationId = actor.civID,
            turn = game.turns,
            currentPlayerCivilizationId = game.currentPlayer,
            isCurrentTurn = game.currentPlayer == actor.civID,
            gold = actor.gold,
            knownCivilizations = actor.getKnownCivs().map { it.civID }.sorted().toList(),
            ownCities = actor.cities.map {
                ProjectedCity(
                    id = it.id,
                    name = it.name,
                    x = it.location.x,
                    y = it.location.y,
                    population = it.population.population,
                    health = it.health,
                    constructionQueue = it.cityConstructions.constructionQueue.toList(),
                    availableConstructions = (
                        it.cityConstructions.getBuildableBuildings().map { construction -> construction.name } +
                            it.cityConstructions.getConstructableUnits().map { construction -> construction.name }
                    ).sorted().toList(),
                )
            }.sortedBy { it.id },
            ownUnits = ownUnits,
            exploredTiles = game.tileMap.tileList.asSequence()
                .filter { it.isExplored(actor) }
                .map { ProjectedTileVisibility(it.position.x, it.position.y, it in actor.viewableTiles) }
                .sortedWith(compareBy<ProjectedTileVisibility> { it.x }.thenBy { it.y })
                .toList(),
            visibleForeignUnits = visibleForeignUnits,
        )
    }

    private fun unitProjection(unit: MapUnit) = ProjectedUnit(
        id = unit.id,
        civilizationId = unit.civ.civID,
        name = unit.name,
        x = unit.getTile().position.x,
        y = unit.getTile().position.y,
        health = unit.health,
        currentMovement = unit.currentMovement,
    )
}
