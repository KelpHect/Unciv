package com.unciv.logic.multiplayer.authoritative

/** What a tap on the projected world map means. */
sealed interface ProjectedTap {
    /** Target mode is active and this tile is a legal target for the order. */
    data class SubmitUnitTarget(val x: Int, val y: Int) : ProjectedTap

    /** Target mode is active but this tile is not a legal target: submit nothing. */
    data object RejectedUnitTarget : ProjectedTap

    /** One of the player's own cities stands here. */
    data class SelectCity(val cityId: String) : ProjectedTap

    /** One of the player's own units stands here; the unit table picks which. */
    data object SelectUnit : ProjectedTap

    /** The selected unit may legally move here. */
    data class MoveSelectedUnit(val x: Int, val y: Int) : ProjectedTap

    /** Nothing to act on - the tap only changes what is being looked at. */
    data object InspectOnly : ProjectedTap
}

/**
 * The projected world screen's selection decisions.
 *
 * Deliberately outside the screen: `BaseScreen` needs a live GL context, so
 * anything living there cannot be tested, and defects in exactly this logic have
 * had to be found by reading. Nothing here touches Gdx, a screen, or a widget -
 * it maps a projection plus a tap to a decision, and remembers which city is
 * open across revisions.
 */
class AuthoritativeWorldSelection {
    /** The city whose panel is open, or null for none. */
    var selectedCityId: String? = null
        private set

    fun decide(
        projection: PlayerProjection,
        x: Int,
        y: Int,
        unitTargetModeActive: Boolean,
        canSubmitUnitTarget: Boolean,
        canMoveSelectedTo: Boolean,
    ): ProjectedTap {
        // A pending order consumes the tap outright: while picking a target,
        // tapping must not quietly re-select something else instead.
        if (unitTargetModeActive) {
            return if (canSubmitUnitTarget) ProjectedTap.SubmitUnitTarget(x, y)
            else ProjectedTap.RejectedUnitTarget
        }

        // City before unit, matching the game's own tile-selection order.
        val city = projection.ownCities.firstOrNull { it.x == x && it.y == y }
        if (city != null) return ProjectedTap.SelectCity(city.id)

        if (projection.ownUnits.any { it.x == x && it.y == y }) return ProjectedTap.SelectUnit
        if (canMoveSelectedTo) return ProjectedTap.MoveSelectedUnit(x, y)
        return ProjectedTap.InspectOnly
    }

    fun selectCity(cityId: String?) {
        selectedCityId = cityId
    }

    /**
     * Drops a city the new projection no longer lists - it may have been razed,
     * captured, or simply be absent, and a panel for it would show state the
     * server has already replaced.
     */
    fun onProjectionReplaced(projection: PlayerProjection) {
        if (projection.ownCities.none { it.id == selectedCityId }) selectedCityId = null
    }

    /** The selected city as the current projection describes it, if still present. */
    fun selectedCity(projection: PlayerProjection): ProjectedCity? =
        projection.ownCities.firstOrNull { it.id == selectedCityId }
}
