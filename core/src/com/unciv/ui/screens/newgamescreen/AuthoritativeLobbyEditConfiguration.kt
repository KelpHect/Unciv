package com.unciv.ui.screens.newgamescreen

import com.unciv.logic.multiplayer.authoritative.ApiV3Lobby
import com.unciv.logic.multiplayer.authoritative.ApiV3LobbyPasswordUpdate
import java.util.UUID

/**
 * Carries one revision-bound lobby edit through screen recreation while the
 * original lobby remains underneath the setup editor.
 */
data class AuthoritativeLobbyEditConfiguration(
    val lobby: ApiV3Lobby,
    val initialEditorMapSeed: Long,
    val operationId: String = UUID.randomUUID().toString(),
    val passwordUpdate: ApiV3LobbyPasswordUpdate = ApiV3LobbyPasswordUpdate.keep(),
    val onSaved: (ApiV3Lobby) -> Unit,
)
