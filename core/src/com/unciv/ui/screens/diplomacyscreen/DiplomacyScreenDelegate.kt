package com.unciv.ui.screens.diplomacyscreen

import com.unciv.logic.civilization.Civilization

/**
 * Optional behavior seam for [DiplomacyScreen]: when a host screen supplies a
 * delegate, every relationship-changing action routes through it instead of
 * writing canonical state directly, and the left-hand civilization list comes
 * from [knownCivs] instead of the viewer's canonical diplomacy map.
 *
 * Null (the default) preserves classic single-player behavior exactly.
 */
interface DiplomacyScreenDelegate {
    /** The civilizations the viewer knows, in display order. */
    fun knownCivs(): List<Civilization>

    fun declareWar(otherCivName: String)
    fun denounce(otherCivName: String)
    fun offerFriendship(otherCivName: String)
    fun makeDemand(otherCivName: String, demandName: String)
    fun negotiatePeace(otherCivName: String)
    fun goToOnMap(otherCivName: String)

    /**
     * Whether the classic trade tables can open for the selected partner.
     * Hosts whose trades live outside this screen return false, hiding the
     * Trade and Negotiate Peace entries.
     */
    val canOpenTrade: Boolean

    /**
     * Called when the player selects a city-state; returning true means the
     * delegate rendered its own right side, false falls through to the classic
     * city-state table.
     */
    fun onCityStateSelected(otherCiv: Civilization, screen: DiplomacyScreen): Boolean
}
