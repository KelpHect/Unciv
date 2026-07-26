package com.unciv.logic.multiplayer.authoritative

/**
 * Projection-only city production/economy input boundary.
 *
 * It validates that an input was advertised by the latest server projection.
 * Canonical affordability, queue, placement, and construction rules remain in
 * the private worker.
 */
class AuthoritativeCityEconomyController internal constructor(
    private val projection: () -> PlayerProjection,
    private val submit: suspend (
        operation: suspend () -> AuthoritativeCommandOutcome?,
    ) -> Unit,
    private val actions: AuthoritativeCityEconomyActions,
) {
    suspend fun selectConstruction(
        cityId: String,
        constructionName: String,
        target: ProjectedTargetCoordinate? = null,
    ) {
        val option = requireOption(cityId, constructionName).takeIf { it.queueable }
            ?: error("Construction is absent from the current server projection")
        when (option.kind) {
            ProjectedConstructionKind.Perpetual -> {
                require(target == null && option.placementTargets.isEmpty()) {
                    "Perpetual construction cannot use a placement target"
                }
                submit { actions.setPerpetual(cityId, constructionName) }
            }
            ProjectedConstructionKind.Ordinary -> {
                if (option.placementTargets.isEmpty()) {
                    require(target == null) {
                        "Construction does not accept a placement target"
                    }
                    submit { actions.queue(cityId, constructionName) }
                } else {
                    require(target in option.placementTargets) {
                        "Tile is absent from the construction's projected legal targets"
                    }
                    submit {
                        actions.queueAtTile(
                            cityId,
                            constructionName,
                            requireNotNull(target).x,
                            target.y,
                        )
                    }
                }
            }
        }
    }

    suspend fun removeConstruction(
        cityId: String,
        queueIndex: Int,
        expectedName: String,
    ) {
        requireQueueEntry(cityId, queueIndex, expectedName)
        submit { actions.remove(cityId, queueIndex, expectedName) }
    }

    suspend fun moveConstruction(
        cityId: String,
        fromIndex: Int,
        toIndex: Int,
        expectedName: String,
    ) {
        val city = requireCity(cityId)
        requireQueueEntry(cityId, fromIndex, expectedName)
        require(toIndex in city.constructionQueueEntries.indices &&
            kotlin.math.abs(fromIndex - toIndex) == 1) {
            "Construction destination is absent from the current server projection"
        }
        submit { actions.move(cityId, fromIndex, toIndex, expectedName) }
    }

    suspend fun manageQueues(
        cityId: String,
        constructionName: String,
        queueIndex: Int?,
        action: ConstructionQueueAction,
    ) {
        val available = if (queueIndex == null) {
            requireOption(cityId, constructionName).availableActions
        } else {
            requireQueueEntry(cityId, queueIndex, constructionName).availableActions
        }
        require(action in available) {
            "Construction queue action is absent from the current server projection"
        }
        submit {
            actions.manage(cityId, constructionName, queueIndex, action)
        }
    }

    suspend fun purchase(
        cityId: String,
        constructionName: String,
        currency: String,
        queueIndex: Int?,
        target: ProjectedTargetCoordinate? = null,
    ) {
        val purchases = if (queueIndex == null) {
            requireOption(cityId, constructionName).purchases
        } else {
            requireQueueEntry(cityId, queueIndex, constructionName).purchases
        }
        val purchase = purchases.singleOrNull { it.currency == currency && it.allowed }
            ?: error("Construction purchase is absent from the current server projection")
        if (purchase.requiresTile) {
            require(target in purchase.legalTargets) {
                "Purchase tile is absent from the current server projection"
            }
            submit {
                actions.purchaseAtTile(
                    cityId,
                    constructionName,
                    currency,
                    requireNotNull(target).x,
                    target.y,
                    queueIndex,
                )
            }
        } else {
            require(target == null && purchase.legalTargets.isEmpty()) {
                "Construction purchase does not accept a tile"
            }
            submit {
                actions.purchase(cityId, constructionName, currency, queueIndex)
            }
        }
    }

    private fun requireCity(cityId: String): ProjectedCity =
        projection().ownCities.singleOrNull { it.id == cityId }
            ?: error("City is absent from the current server projection")

    private fun requireOption(
        cityId: String,
        constructionName: String,
    ): ProjectedConstructionOption =
        requireCity(cityId).constructionOptions.singleOrNull { it.name == constructionName }
            ?: error("Construction is absent from the current server projection")

    private fun requireQueueEntry(
        cityId: String,
        queueIndex: Int,
        expectedName: String,
    ): ProjectedConstructionQueueEntry =
        requireCity(cityId).constructionQueueEntries.getOrNull(queueIndex)
            ?.takeIf { it.name == expectedName }
            ?: error("Construction queue entry is absent from the current server projection")
}

data class AuthoritativeCityEconomyActions(
    val queue: suspend (String, String) -> AuthoritativeCommandOutcome?,
    val queueAtTile: suspend (String, String, Int, Int) -> AuthoritativeCommandOutcome?,
    val setPerpetual: suspend (String, String) -> AuthoritativeCommandOutcome?,
    val remove: suspend (String, Int, String) -> AuthoritativeCommandOutcome?,
    val move: suspend (String, Int, Int, String) -> AuthoritativeCommandOutcome?,
    val manage:
        suspend (String, String, Int?, ConstructionQueueAction) -> AuthoritativeCommandOutcome?,
    val purchase: suspend (String, String, String, Int?) -> AuthoritativeCommandOutcome?,
    val purchaseAtTile:
        suspend (String, String, String, Int, Int, Int?) -> AuthoritativeCommandOutcome?,
) {
    companion object {
        val Unavailable = AuthoritativeCityEconomyActions(
            queue = { _, _ -> null },
            queueAtTile = { _, _, _, _ -> null },
            setPerpetual = { _, _ -> null },
            remove = { _, _, _ -> null },
            move = { _, _, _, _ -> null },
            manage = { _, _, _, _ -> null },
            purchase = { _, _, _, _ -> null },
            purchaseAtTile = { _, _, _, _, _, _ -> null },
        )
    }
}
