package com.unciv.ui.screens.worldscreen.minimap

import com.badlogic.gdx.math.Rectangle
import com.badlogic.gdx.scenes.scene2d.Stage
import com.unciv.logic.map.HexCoord
import com.unciv.logic.map.TileMap

/**
 * The part of a pannable world map that the [Minimap] needs.
 *
 * [com.unciv.ui.screens.worldscreen.worldmap.WorldMapHolder] implements this
 * for single-player, and the API-v3 projection map holder implements it for
 * online matches - neither the minimap nor its holder may know which one is
 * hosting them.
 */
interface MinimapMapHolder {
    val tileMap: TileMap
    var continuousScrollingX: Boolean

    /** Viewport of the main map changed; arguments as [onViewportChangedListener]. */
    fun onViewportChanged()

    /**
     * The smaller dimension of the hosting screen, used to scale the minimap.
     * Returns [default] before the host is attached to a stage.
     */
    fun minStageDimensionOr(default: Float): Float

    /** Viewport of the main map changed; arguments as [onViewportChangedListener]. */
    var onViewportChangedListener: ((width: Float, height: Float, viewport: Rectangle) -> Unit)?

    /** Center the main map on [position], as a minimap tap asks. */
    fun centerOn(position: HexCoord)
}
