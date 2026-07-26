package com.unciv.logic.multiplayer.authoritative

enum class MultiplayerCreationRoute {
    Local,
    LegacyApiV2,
    AuthoritativeApiV3,
}

fun multiplayerCreationRoute(
    isOnlineMultiplayer: Boolean,
    authoritativeSessionInstalled: Boolean,
) = when {
    !isOnlineMultiplayer -> MultiplayerCreationRoute.Local
    authoritativeSessionInstalled -> MultiplayerCreationRoute.AuthoritativeApiV3
    else -> MultiplayerCreationRoute.LegacyApiV2
}
