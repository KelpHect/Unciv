package com.unciv.logic.multiplayer.authoritative

data class AuthoritativeLobbyConfiguration(
    val displayName: String,
    val humanSlots: Int,
    val password: String?,
) {
    init {
        require(displayName.isNotBlank() && displayName.length <= 80)
        require(humanSlots in 1..16)
        require(password == null || password.length in 12..256)
    }
}
