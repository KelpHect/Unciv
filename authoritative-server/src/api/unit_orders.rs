use super::*;

#[utoipa::path(
    post,
    path = "/api/v3/games/{game_id}/commands/set-unit-posture",
    params(("game_id" = uuid::Uuid, Path)), security(("bearer_auth" = [])),
    request_body = SetUnitPostureRequest,
    responses(
        (status = 200, body = unciv_authoritative_server::CommandAccepted),
        (status = 401, body = ErrorResponse), (status = 403, body = ErrorResponse),
        (status = 404, body = ErrorResponse), (status = 409, body = ErrorResponse),
        (status = 422, body = ErrorResponse), (status = 503, body = ErrorResponse)
    )
)]
pub(super) async fn set_unit_posture(
    State(state): State<AppState>,
    headers: HeaderMap,
    Path(game_id): Path<uuid::Uuid>,
    Json(request): Json<SetUnitPostureRequest>,
) -> Result<Json<unciv_authoritative_server::CommandAccepted>, ApiError> {
    let actor = authenticated_account(&state, &headers).await?;
    let accepted = state
        .repository
        .execute_set_unit_posture(
            &state.worker,
            actor.id,
            CommandEnvelope {
                protocol_version: PROTOCOL_VERSION,
                game_id,
                command_id: request.command_id,
                expected_revision: request.expected_revision,
                client_observed_state_hash: request.client_observed_state_hash,
                command: GameCommand::SetUnitPosture {
                    unit_id: request.unit_id,
                    posture: request.posture,
                },
            },
        )
        .await
        .map_err(game_error)?;
    Ok(Json(accepted))
}

macro_rules! unit_order_handler {
    ($name:ident, $path:literal, $request:ty, $execute:ident, $command:ident) => {
        #[utoipa::path(
                    post,
                    path = $path,
                    params(("game_id" = uuid::Uuid, Path)), security(("bearer_auth" = [])),
                    request_body = $request,
                    responses(
                        (status = 200, body = unciv_authoritative_server::CommandAccepted),
                        (status = 401, body = ErrorResponse), (status = 403, body = ErrorResponse),
                        (status = 404, body = ErrorResponse), (status = 409, body = ErrorResponse),
                        (status = 422, body = ErrorResponse), (status = 503, body = ErrorResponse)
                    )
                )]
        pub(super) async fn $name(
            State(state): State<AppState>,
            headers: HeaderMap,
            Path(game_id): Path<uuid::Uuid>,
            Json(request): Json<$request>,
        ) -> Result<Json<unciv_authoritative_server::CommandAccepted>, ApiError> {
            let actor = authenticated_account(&state, &headers).await?;
            let accepted = state
                .repository
                .$execute(
                    &state.worker,
                    actor.id,
                    CommandEnvelope {
                        protocol_version: PROTOCOL_VERSION,
                        game_id,
                        command_id: request.command_id,
                        expected_revision: request.expected_revision,
                        client_observed_state_hash: request.client_observed_state_hash,
                        command: GameCommand::$command {
                            unit_id: request.unit_id,
                            enabled: request.enabled,
                        },
                    },
                )
                .await
                .map_err(game_error)?;
            Ok(Json(accepted))
        }
    };
}

unit_order_handler!(
    set_unit_exploration,
    "/api/v3/games/{game_id}/commands/set-unit-exploration",
    SetUnitExplorationRequest,
    execute_set_unit_exploration,
    SetUnitExploration
);

unit_order_handler!(
    set_unit_automation,
    "/api/v3/games/{game_id}/commands/set-unit-automation",
    SetUnitAutomationRequest,
    execute_set_unit_automation,
    SetUnitAutomation
);
