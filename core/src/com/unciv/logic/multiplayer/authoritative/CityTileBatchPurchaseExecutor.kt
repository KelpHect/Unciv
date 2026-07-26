package com.unciv.logic.multiplayer.authoritative

import com.unciv.logic.city.City
import com.unciv.logic.map.tile.Tile

/** Derives and atomically executes bounded city-border ring purchases. */
internal object CityTileBatchPurchaseExecutor {
    private const val MAX_RING = 32
    private const val MIN_BATCH_SIZE = 2

    fun options(city: City): List<ProjectedCityTileBatchPurchase> =
        (1..city.getWorkRange().coerceAtMost(MAX_RING)).mapNotNull { ring ->
            proposal(city, ring)?.takeIf { it.tiles.size >= MIN_BATCH_SIZE }?.let {
                ProjectedCityTileBatchPurchase(
                    ring = ring,
                    tileCount = it.tiles.size,
                    goldCost = it.goldCost,
                    affordable = city.civ.gameInfo.gameParameters.godMode ||
                        city.civ.gold >= it.goldCost,
                )
            }
        }

    fun execute(city: City, ring: Int) {
        require(ring in 1..city.getWorkRange().coerceAtMost(MAX_RING)) {
            "City tile batch ring is outside the bounded work range"
        }
        val proposal = proposal(city, ring)
            ?.takeIf { it.tiles.size >= MIN_BATCH_SIZE }
            ?: error("City tile batch is unavailable in canonical state")
        require(city.civ.gameInfo.gameParameters.godMode || city.civ.gold >= proposal.goldCost) {
            "Civilization cannot afford the canonical city tile batch"
        }
        val previousGold = city.civ.gold
        for (tile in proposal.tiles) {
            check(city.expansion.canBuyTile(tile)) {
                "Canonical city tile batch order became invalid"
            }
            city.expansion.buyTile(tile)
            check(tile.owningCity == city) {
                "Purchased batch tile ownership was not committed"
            }
        }
        check(city.civ.gold == previousGold - proposal.goldCost) {
            "Canonical city tile batch price was not applied"
        }
    }

    @Suppress("DEPRECATION")
    private fun proposal(city: City, ring: Int): Proposal? {
        if (city.isPuppet || city.isBeingRazed || city.isInResistance()) return null
        val remaining = city.getCenterTile().getTilesInDistance(ring)
            .filter { it.getOwner() == null && it in city.tilesInRange }
            .sortedWith(compareBy<Tile> { it.position.x }.thenBy { it.position.y })
            .toMutableList()
        if (remaining.isEmpty()) return null
        val ordered = mutableListOf<Tile>()
        while (remaining.isNotEmpty()) {
            val next = remaining.firstOrNull { tile ->
                tile.neighbors.any { neighbor ->
                    neighbor.getCity() == city || neighbor in ordered
                }
            } ?: return null
            remaining.remove(next)
            ordered += next
        }
        val goldCost = ordered.withIndex().sumOf { (index, tile) ->
            city.expansion.getGoldCostOfTile(tile, index)
        }
        return Proposal(ordered, goldCost)
    }

    private data class Proposal(val tiles: List<Tile>, val goldCost: Int)
}
