package com.unciv.logic.multiplayer.authoritative

enum class MultiplayerCreationRoute {
    Local,
    LegacyCreationDisabled,
    AuthoritativeApiV3,
    AuthoritativeUnavailable,
}

fun multiplayerCreationRoute(
    isOnlineMultiplayer: Boolean,
    authoritativeStatus: AuthoritativeSessionStatus,
) = when {
    !isOnlineMultiplayer -> MultiplayerCreationRoute.Local
    authoritativeStatus == AuthoritativeSessionStatus.LegacyServer ->
        MultiplayerCreationRoute.LegacyCreationDisabled
    authoritativeStatus in setOf(
        AuthoritativeSessionStatus.LoginRequired,
        AuthoritativeSessionStatus.Authenticated,
    ) -> MultiplayerCreationRoute.AuthoritativeApiV3
    else -> MultiplayerCreationRoute.AuthoritativeUnavailable
}
