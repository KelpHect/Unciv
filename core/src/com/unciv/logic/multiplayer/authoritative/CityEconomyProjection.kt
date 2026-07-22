package com.unciv.logic.multiplayer.authoritative

import com.unciv.logic.city.City
import com.unciv.models.ruleset.Building
import com.unciv.models.ruleset.INonPerpetualConstruction
import com.unciv.models.ruleset.PerpetualConstruction
import com.unciv.models.stats.Stat
import kotlinx.serialization.Serializable

@Serializable
data class ProjectedConstructionQueueEntry(
    val name: String,
    val storedProduction: Int,
    val productionCost: Int?,
    val estimatedTurns: Int?,
    val purchases: List<ProjectedConstructionPurchase>,
)

@Serializable
data class ProjectedConstructionOption(
    val name: String,
    val queueable: Boolean,
    val storedProduction: Int,
    val productionCost: Int?,
    val estimatedTurns: Int?,
    val placementTargets: List<ProjectedTargetCoordinate>,
    val purchases: List<ProjectedConstructionPurchase>,
)

@Serializable
data class ProjectedConstructionPurchase(
    val currency: String,
    val cost: Int,
    val availableAmount: Int,
    val allowed: Boolean,
    val requiresTile: Boolean,
    val legalTargets: List<ProjectedTargetCoordinate>,
)

@Serializable
data class ProjectedCityTileState(
    val x: Int,
    val y: Int,
    val ownedByActor: Boolean,
    val owningCityId: String?,
    val workingCityId: String?,
    val worked: Boolean,
    val locked: Boolean,
)

@Serializable
data class ProjectedCityTilePurchase(
    val x: Int,
    val y: Int,
    val goldCost: Int,
    val affordable: Boolean,
)

/** Builds bounded city-economy choices from canonical worker state. */
internal object CityEconomyProjection {
    private const val MAX_OPTIONS = 10_000
    private const val MAX_TILES = 10_000

    fun queueEntries(city: City): List<ProjectedConstructionQueueEntry> =
        city.cityConstructions.constructionQueue.mapIndexed { index, name ->
            val construction = city.cityConstructions.getConstruction(name)
            val useStoredProduction = city.cityConstructions.isFirstConstructionOfItsKind(index, name)
            ProjectedConstructionQueueEntry(
                name,
                storedProduction = if (useStoredProduction)
                    city.cityConstructions.getWorkDone(name) else 0,
                productionCost = (construction as? INonPerpetualConstruction)
                    ?.getProductionCost(city.civ, city),
                estimatedTurns = (construction as? INonPerpetualConstruction)
                    ?.let { city.cityConstructions.turnsToConstruction(name, useStoredProduction) },
                purchases = purchases(city, construction as? INonPerpetualConstruction, index),
            )
        }

    fun options(city: City): List<ProjectedConstructionOption> {
        val mayChangeProduction = city.civ.gameInfo.currentPlayer == city.civ.civID && !city.isPuppet
        val ordinary = (city.getRuleset().units.values.asSequence() +
            city.getRuleset().buildings.values.asSequence())
            .filter { it.shouldBeDisplayed(city.cityConstructions) }
            .map { construction ->
                val useStoredProduction = construction is Building ||
                    !city.cityConstructions.isBeingConstructedOrEnqueued(construction.name)
                ProjectedConstructionOption(
                    construction.name,
                    queueable = mayChangeProduction && city.cityConstructions.canAddToQueue(construction),
                    storedProduction = if (useStoredProduction)
                        city.cityConstructions.getWorkDone(construction.name) else 0,
                    productionCost = construction.getProductionCost(city.civ, city),
                    estimatedTurns = city.cityConstructions.turnsToConstruction(
                        construction.name, useStoredProduction,
                    ),
                    placementTargets = placementTargets(city, construction),
                    purchases = purchases(city, construction, queueIndex = null),
                )
            }
        val perpetual = PerpetualConstruction.perpetualConstructionsMap.values.asSequence()
            .filter { it.shouldBeDisplayed(city.cityConstructions) }
            .map { construction ->
                ProjectedConstructionOption(
                    construction.name,
                    queueable = mayChangeProduction && city.cityConstructions.canAddToQueue(construction) &&
                        !city.cityConstructions.isBeingConstructedOrEnqueued(construction.name),
                    storedProduction = 0,
                    productionCost = null,
                    estimatedTurns = null,
                    placementTargets = emptyList(),
                    purchases = emptyList(),
                )
            }
        return (ordinary + perpetual).sortedBy { it.name }.take(MAX_OPTIONS).toList()
    }

    fun tileStates(city: City): List<ProjectedCityTileState> = city.tilesInRange.asSequence()
        .filter { it.isExplored(city.civ) }
        .map { tile ->
            val owner = tile.getOwner()
            val owningCity = tile.getCity().takeIf { owner == city.civ }
            val workingCity = tile.getWorkingCity().takeIf { owner == city.civ }
            ProjectedCityTileState(
                tile.position.x,
                tile.position.y,
                ownedByActor = owner == city.civ,
                owningCityId = owningCity?.id,
                workingCityId = workingCity?.id,
                worked = workingCity != null && tile.isWorked(),
                locked = workingCity != null && tile.isLocked(),
            )
        }
        .sortedWith(compareBy<ProjectedCityTileState> { it.x }.thenBy { it.y })
        .take(MAX_TILES)
        .toList()

    fun tilePurchases(city: City): List<ProjectedCityTilePurchase> = city.tilesInRange.asSequence()
        .filter { it.isExplored(city.civ) && city.expansion.canBuyTile(it) }
        .map { tile ->
            val cost = city.expansion.getGoldCostOfTile(tile)
            ProjectedCityTilePurchase(
                tile.position.x,
                tile.position.y,
                cost,
                city.civ.gameInfo.gameParameters.godMode || city.civ.gold >= cost,
            )
        }
        .sortedWith(compareBy<ProjectedCityTilePurchase> { it.x }.thenBy { it.y })
        .take(MAX_TILES)
        .toList()

    private fun purchases(
        city: City,
        construction: INonPerpetualConstruction?,
        queueIndex: Int?,
    ): List<ProjectedConstructionPurchase> {
        if (construction == null) return emptyList()
        val targets = purchaseTargets(city, construction, queueIndex)
        val requiresTile = construction is Building && construction.hasCreateOneImprovementUnique()
        return Stat.statsUsableToBuy.mapNotNull { currency ->
            val cost = construction.getStatBuyCost(city, currency) ?: return@mapNotNull null
            ProjectedConstructionPurchase(
                currency.name,
                cost,
                city.getStatReserve(currency),
                city.cityConstructions.isConstructionPurchaseAllowed(construction, currency, cost) &&
                    (!requiresTile || targets.isNotEmpty()),
                requiresTile,
                if (requiresTile) targets else emptyList(),
            )
        }.sortedBy { it.currency }
    }

    private fun placementTargets(
        city: City,
        construction: INonPerpetualConstruction,
    ): List<ProjectedTargetCoordinate> {
        val building = construction as? Building ?: return emptyList()
        val improvement = building.getImprovementToCreate(city.getRuleset(), city.civ)
            ?: return emptyList()
        return city.tilesInRange.asSequence()
            .filter {
                it.isExplored(city.civ) &&
                    city.cityConstructions.canPlaceCreateOneImprovementOn(improvement, it)
            }
            .map { ProjectedTargetCoordinate(it.position.x, it.position.y) }
            .sortedWith(compareBy<ProjectedTargetCoordinate> { it.x }.thenBy { it.y })
            .take(MAX_TILES)
            .toList()
    }

    private fun purchaseTargets(
        city: City,
        construction: INonPerpetualConstruction,
        queueIndex: Int?,
    ): List<ProjectedTargetCoordinate> {
        val building = construction as? Building ?: return emptyList()
        val improvement = building.getImprovementToCreate(city.getRuleset(), city.civ)
            ?: return emptyList()
        if (queueIndex == null) return placementTargets(city, construction)
        if (city.cityConstructions.constructionQueue.getOrNull(queueIndex) != construction.name)
            return emptyList()
        val tile = city.cityConstructions.getTileForImprovement(improvement.name)
            ?: return emptyList()
        return listOf(ProjectedTargetCoordinate(tile.position.x, tile.position.y))
    }
}
