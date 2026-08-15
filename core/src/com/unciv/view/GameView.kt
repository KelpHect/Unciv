package com.unciv.view

import com.unciv.logic.GameInfo
import com.unciv.logic.city.City
import com.unciv.logic.civilization.Civilization
import com.unciv.logic.map.TileMap
import com.unciv.logic.map.tile.Tile
import yairm210.purity.annotations.Readonly

/**
 * View of a game from the perspective of [viewer].
 *
 * The map is the only thing this needs from a game, so an API-v3 client that
 * holds a server projection instead of a [GameInfo] can build one from the
 * [TileMap] it materialized from that projection.
 */
class GameView(tileMap: TileMap, internal val viewer: Civilization, val spectatorMode: Boolean = false) {
    constructor(gameInfo: GameInfo, viewer: Civilization, spectatorMode: Boolean = false) :
        this(gameInfo.tileMap, viewer, spectatorMode)

    val civView: CivView = CivView(viewer, viewer, spectatorMode, this)
    val tileMapView: TileMapView = TileMapView(tileMap, viewer, spectatorMode, this)

    // Navigation
    // These can be cached in the future if we see a need, for now - simplicity
    @Readonly fun getCivView(civ: Civilization): CivView = CivView(civ, viewer, spectatorMode, this)
    @Readonly fun getCityView(city: City): CityView = CityView(city, viewer, spectatorMode, this)
    @Readonly fun getForeignCityView(city: City): ForeignCityView = ForeignCityView(city, viewer, spectatorMode, this)

    // Data retrieval
    @Readonly fun getTile(tile: Tile): TileView = tileMapView.getTile(tile)
}
