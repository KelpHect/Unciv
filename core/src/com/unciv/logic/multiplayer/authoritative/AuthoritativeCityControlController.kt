package com.unciv.logic.multiplayer.authoritative

/**
 * Projection-only city tiles, citizens, governance, and disposition boundary.
 *
 * Every accepted input must occur in the latest player projection. Canonical
 * legality and effects are re-derived by the private worker.
 */
class AuthoritativeCityControlController internal constructor(
    private val projection: () -> PlayerProjection,
    private val submit: suspend (
        operation: suspend () -> AuthoritativeCommandOutcome?,
    ) -> Unit,
    private val actions: AuthoritativeCityControlActions,
) {
    suspend fun buyTile(cityId: String, x: Int, y: Int) {
        val city = requireCity(cityId)
        require(city.tilePurchases.any { it.x == x && it.y == y && it.affordable }) {
            "Tile purchase is absent from the current server projection"
        }
        submit { actions.buyTile(cityId, x, y) }
    }

    suspend fun buyTileBatch(cityId: String, ring: Int) {
        val city = requireCity(cityId)
        require(city.tileBatchPurchases.any { it.ring == ring && it.affordable }) {
            "Tile batch is absent from the current server projection"
        }
        submit { actions.buyTileBatch(cityId, ring) }
    }

    suspend fun sellBuilding(cityId: String, buildingName: String) {
        require(buildingName in requireCity(cityId).sellableBuildings) {
            "Building sale is absent from the current server projection"
        }
        submit { actions.sellBuilding(cityId, buildingName) }
    }

    suspend fun setGovernance(cityId: String, action: CityGovernanceAction) {
        require(action in requireCity(cityId).availableGovernanceActions) {
            "City governance action is absent from the current server projection"
        }
        submit { actions.setGovernance(cityId, action) }
    }

    suspend fun resolveDisposition(cityId: String, action: CityDispositionAction) {
        val disposition = projection().pendingCityDispositions.singleOrNull {
            it.cityId == cityId
        } ?: error("City disposition is absent from the current server projection")
        require(action in disposition.availableActions) {
            "City disposition action is absent from the current server projection"
        }
        submit { actions.resolveDisposition(cityId, action) }
    }

    suspend fun setTileAssignment(
        cityId: String,
        x: Int,
        y: Int,
        assignment: CityTileAssignment,
    ) {
        require(requireCity(cityId).assignableTiles.any { it.x == x && it.y == y }) {
            "Assignable tile is absent from the current server projection"
        }
        submit { actions.setTileAssignment(cityId, x, y, assignment) }
    }

    suspend fun setSpecialistCount(cityId: String, specialistName: String, count: Int) {
        val specialist = requireCity(cityId).specialists.singleOrNull {
            it.name == specialistName
        } ?: error("Specialist is absent from the current server projection")
        require(count in 0..specialist.capacity) {
            "Specialist count exceeds projected capacity"
        }
        submit { actions.setSpecialistCount(cityId, specialistName, count) }
    }

    suspend fun setManualSpecialists(cityId: String, enabled: Boolean) {
        require(requireCity(cityId).specialists.isNotEmpty()) {
            "City has no projected specialist controls"
        }
        submit { actions.setManualSpecialists(cityId, enabled) }
    }

    suspend fun resetCitizens(cityId: String) {
        requireCity(cityId)
        submit { actions.resetCitizens(cityId) }
    }

    suspend fun setAvoidGrowth(cityId: String, enabled: Boolean) {
        requireCity(cityId)
        submit { actions.setAvoidGrowth(cityId, enabled) }
    }

    suspend fun setCitizenFocus(cityId: String, focus: CitizenFocus) {
        require(focus in requireCity(cityId).selectableCitizenFocuses) {
            "Citizen focus is absent from the current server projection"
        }
        submit { actions.setCitizenFocus(cityId, focus) }
    }

    private fun requireCity(cityId: String): ProjectedCity =
        projection().ownCities.singleOrNull { it.id == cityId }
            ?: error("City is absent from the current server projection")
}

data class AuthoritativeCityControlActions(
    val buyTile: suspend (String, Int, Int) -> AuthoritativeCommandOutcome?,
    val buyTileBatch: suspend (String, Int) -> AuthoritativeCommandOutcome?,
    val sellBuilding: suspend (String, String) -> AuthoritativeCommandOutcome?,
    val setGovernance: suspend (String, CityGovernanceAction) -> AuthoritativeCommandOutcome?,
    val resolveDisposition:
        suspend (String, CityDispositionAction) -> AuthoritativeCommandOutcome?,
    val setTileAssignment:
        suspend (String, Int, Int, CityTileAssignment) -> AuthoritativeCommandOutcome?,
    val setSpecialistCount: suspend (String, String, Int) -> AuthoritativeCommandOutcome?,
    val setManualSpecialists: suspend (String, Boolean) -> AuthoritativeCommandOutcome?,
    val resetCitizens: suspend (String) -> AuthoritativeCommandOutcome?,
    val setAvoidGrowth: suspend (String, Boolean) -> AuthoritativeCommandOutcome?,
    val setCitizenFocus: suspend (String, CitizenFocus) -> AuthoritativeCommandOutcome?,
) {
    companion object {
        val Unavailable = AuthoritativeCityControlActions(
            buyTile = { _, _, _ -> null },
            buyTileBatch = { _, _ -> null },
            sellBuilding = { _, _ -> null },
            setGovernance = { _, _ -> null },
            resolveDisposition = { _, _ -> null },
            setTileAssignment = { _, _, _, _ -> null },
            setSpecialistCount = { _, _, _ -> null },
            setManualSpecialists = { _, _ -> null },
            resetCitizens = { null },
            setAvoidGrowth = { _, _ -> null },
            setCitizenFocus = { _, _ -> null },
        )
    }
}
