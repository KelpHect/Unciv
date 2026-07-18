package com.unciv.logic.multiplayer.authoritative

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class ApiV3Capabilities(
    @SerialName("protocol_version") val protocolVersion: Int,
    @SerialName("projection_version") val projectionVersion: Int,
    val commands: List<String>,
    @SerialName("whole_state_upload") val wholeStateUpload: Boolean,
    @SerialName("websocket_notifications") val websocketNotifications: Boolean,
)

@Serializable
data class ApiV3Credentials(val username: String, val password: String)

@Serializable
data class ApiV3Account(
    @SerialName("account_id") val accountId: String,
    val username: String,
)

@Serializable
data class ApiV3Login(
    val account: ApiV3Account,
    @SerialName("session_token") val sessionToken: String,
)

@Serializable
data class ApiV3Session(@SerialName("session_token") val sessionToken: String)

@Serializable
data class ApiV3CreateGameRequest(
    @SerialName("ruleset_manifest_hash") val rulesetManifestHash: String,
)

@Serializable
data class ApiV3GameMetadata(
    @SerialName("game_id") val gameId: String,
    @SerialName("committed_revision") val committedRevision: Long,
    @SerialName("canonical_state_hash") val canonicalStateHash: String,
    val role: String,
    @SerialName("civilization_id") val civilizationId: String? = null,
)

@Serializable
data class ApiV3JoinGameRequest(
    @SerialName("command_id") val commandId: String,
    @SerialName("expected_revision") val expectedRevision: Long,
    @SerialName("client_observed_state_hash") val clientObservedStateHash: String,
)

@Serializable
data class ApiV3GameProjection(
    @SerialName("game_id") val gameId: String,
    @SerialName("projection_version") val projectionVersion: Int,
    @SerialName("committed_revision") val committedRevision: Long,
    @SerialName("canonical_state_hash") val canonicalStateHash: String,
    @SerialName("projection_hash") val projectionHash: String,
    val projection: PlayerProjection,
)

@Serializable
data class ApiV3CommandAccepted(
    @SerialName("game_id") val gameId: String,
    @SerialName("command_id") val commandId: String,
    @SerialName("previous_revision") val previousRevision: Long,
    @SerialName("committed_revision") val committedRevision: Long,
    @SerialName("canonical_state_hash") val canonicalStateHash: String,
)

@Serializable
data class ApiV3MoveUnitRequest(
    @SerialName("command_id") val commandId: String,
    @SerialName("expected_revision") val expectedRevision: Long,
    @SerialName("client_observed_state_hash") val clientObservedStateHash: String,
    @SerialName("unit_id") val unitId: Int,
    @SerialName("destination_x") val destinationX: Int,
    @SerialName("destination_y") val destinationY: Int,
)

@Serializable
data class ApiV3QueueConstructionRequest(
    @SerialName("command_id") val commandId: String,
    @SerialName("expected_revision") val expectedRevision: Long,
    @SerialName("client_observed_state_hash") val clientObservedStateHash: String,
    @SerialName("city_id") val cityId: String,
    @SerialName("construction_name") val constructionName: String,
)

@Serializable
data class ApiV3EndTurnRequest(
    @SerialName("command_id") val commandId: String,
    @SerialName("expected_revision") val expectedRevision: Long,
    @SerialName("client_observed_state_hash") val clientObservedStateHash: String,
)

@Serializable
data class ApiV3ErrorResponse(
    val code: String,
    @SerialName("current_revision") val currentRevision: Long? = null,
)

@Serializable
data class ApiV3RevisionNotification(
    val type: String,
    @SerialName("protocol_version") val protocolVersion: Int,
    @SerialName("game_id") val gameId: String? = null,
    @SerialName("committed_revision") val committedRevision: Long? = null,
    @SerialName("canonical_state_hash") val canonicalStateHash: String? = null,
)

class ApiV3Exception(
    val httpStatus: Int,
    val error: ApiV3ErrorResponse,
) : Exception(error.code)
