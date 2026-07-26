package com.unciv.ui.screens.multiplayerscreens

import com.unciv.logic.multiplayer.authoritative.AuthoritativeCityControlActions
import com.unciv.logic.multiplayer.authoritative.AuthoritativeCityEconomyActions
import com.unciv.logic.multiplayer.authoritative.AuthoritativeCombatActions
import com.unciv.logic.multiplayer.authoritative.AuthoritativeMultiplayerSession

internal fun authoritativeCityEconomyActions(
    session: AuthoritativeMultiplayerSession,
    gameId: String,
) = AuthoritativeCityEconomyActions(
    queue = { cityId, construction ->
        session.queueConstructionIfOpen(gameId, cityId, construction)
    },
    queueAtTile = { cityId, construction, x, y ->
        session.queueConstructionAtTileIfOpen(gameId, cityId, construction, x, y)
    },
    setPerpetual = { cityId, construction ->
        session.setPerpetualConstructionIfOpen(gameId, cityId, construction)
    },
    remove = { cityId, index, construction ->
        session.removeConstructionIfOpen(gameId, cityId, index, construction)
    },
    move = { cityId, from, to, construction ->
        session.moveConstructionIfOpen(gameId, cityId, from, to, construction)
    },
    manage = { cityId, construction, index, action ->
        session.manageConstructionQueuesIfOpen(
            gameId, cityId, construction, index, action,
        )
    },
    purchase = { cityId, construction, currency, index ->
        session.purchaseConstructionIfOpen(
            gameId, cityId, construction, currency, index,
        )
    },
    purchaseAtTile = { cityId, construction, currency, x, y, index ->
        session.purchaseConstructionAtTileIfOpen(
            gameId, cityId, construction, currency, x, y, index,
        )
    },
)

internal fun authoritativeCityControlActions(
    session: AuthoritativeMultiplayerSession,
    gameId: String,
) = AuthoritativeCityControlActions(
    buyTile = { cityId, x, y ->
        session.buyCityTileIfOpen(gameId, cityId, x, y)
    },
    buyTileBatch = { cityId, ring ->
        session.buyCityTileBatchIfOpen(gameId, cityId, ring)
    },
    sellBuilding = { cityId, building ->
        session.sellBuildingIfOpen(gameId, cityId, building)
    },
    setGovernance = { cityId, action ->
        session.setCityGovernanceIfOpen(gameId, cityId, action)
    },
    resolveDisposition = { cityId, action ->
        session.resolveCityDispositionIfOpen(gameId, cityId, action)
    },
    setTileAssignment = { cityId, x, y, assignment ->
        session.setCityTileAssignmentIfOpen(gameId, cityId, x, y, assignment)
    },
    setSpecialistCount = { cityId, specialist, count ->
        session.setSpecialistCountIfOpen(gameId, cityId, specialist, count)
    },
    setManualSpecialists = { cityId, enabled ->
        session.setManualSpecialistsIfOpen(gameId, cityId, enabled)
    },
    resetCitizens = { cityId ->
        session.resetCitizensIfOpen(gameId, cityId)
    },
    setAvoidGrowth = { cityId, enabled ->
        session.setAvoidGrowthIfOpen(gameId, cityId, enabled)
    },
    setCitizenFocus = { cityId, focus ->
        session.setCitizenFocusIfOpen(gameId, cityId, focus)
    },
)

internal fun authoritativeCombatActions(
    session: AuthoritativeMultiplayerSession,
    gameId: String,
) = AuthoritativeCombatActions(
    attack = { unitId, x, y ->
        session.attackWithUnitIfOpen(gameId, unitId, x, y)
    },
    launchNuclearStrike = { unitId, x, y ->
        session.launchNuclearStrikeIfOpen(gameId, unitId, x, y)
    },
    airSweep = { unitId, x, y ->
        session.airSweepIfOpen(gameId, unitId, x, y)
    },
    bombard = { cityId, x, y ->
        session.bombardWithCityIfOpen(gameId, cityId, x, y)
    },
)
