use super::*;

#[utoipa::path(
    post,
    path = "/api/v3/games/{game_id}/commands/disband-unit",
    params(("game_id" = uuid::Uuid, Path)), security(("bearer_auth" = [])),
    request_body = DisbandUnitRequest,
    responses(
        (status = 200, body = unciv_authoritative_server::CommandAccepted),
        (status = 401, body = ErrorResponse), (status = 403, body = ErrorResponse),
        (status = 404, body = ErrorResponse), (status = 409, body = ErrorResponse),
        (status = 422, body = ErrorResponse), (status = 503, body = ErrorResponse)
    )
)]
pub(super) async fn disband_unit(
    State(state): State<AppState>,
    headers: HeaderMap,
    Path(game_id): Path<uuid::Uuid>,
    Json(request): Json<DisbandUnitRequest>,
) -> Result<Json<unciv_authoritative_server::CommandAccepted>, ApiError> {
    let actor = authenticated_account(&state, &headers).await?;
    let accepted = state
        .repository
        .execute_disband_unit(
            &state.worker,
            actor.id,
            CommandEnvelope {
                protocol_version: PROTOCOL_VERSION,
                game_id,
                command_id: request.command_id,
                expected_revision: request.expected_revision,
                client_observed_state_hash: request.client_observed_state_hash,
                command: GameCommand::DisbandUnit {
                    unit_id: request.unit_id,
                },
            },
        )
        .await
        .map_err(game_error)?;
    Ok(Json(accepted))
}

#[utoipa::path(
    post,
    path = "/api/v3/games/{game_id}/commands/upgrade-units",
    params(("game_id" = uuid::Uuid, Path)), security(("bearer_auth" = [])),
    request_body = UpgradeUnitsRequest,
    responses(
        (status = 200, body = unciv_authoritative_server::CommandAccepted),
        (status = 401, body = ErrorResponse), (status = 403, body = ErrorResponse),
        (status = 404, body = ErrorResponse), (status = 409, body = ErrorResponse),
        (status = 422, body = ErrorResponse), (status = 503, body = ErrorResponse)
    )
)]
pub(super) async fn upgrade_units(
    State(state): State<AppState>,
    headers: HeaderMap,
    Path(game_id): Path<uuid::Uuid>,
    Json(request): Json<UpgradeUnitsRequest>,
) -> Result<Json<unciv_authoritative_server::CommandAccepted>, ApiError> {
    let actor = authenticated_account(&state, &headers).await?;
    let accepted = state
        .repository
        .execute_upgrade_units(
            &state.worker,
            actor.id,
            CommandEnvelope {
                protocol_version: PROTOCOL_VERSION,
                game_id,
                command_id: request.command_id,
                expected_revision: request.expected_revision,
                client_observed_state_hash: request.client_observed_state_hash,
                command: GameCommand::UpgradeUnits {
                    unit_ids: request.unit_ids,
                    target_unit_name: request.target_unit_name,
                },
            },
        )
        .await
        .map_err(game_error)?;
    Ok(Json(accepted))
}

#[utoipa::path(
    post,
    path = "/api/v3/games/{game_id}/commands/promote-unit",
    params(("game_id" = uuid::Uuid, Path)), security(("bearer_auth" = [])),
    request_body = PromoteUnitRequest,
    responses(
        (status = 200, body = unciv_authoritative_server::CommandAccepted),
        (status = 401, body = ErrorResponse), (status = 403, body = ErrorResponse),
        (status = 404, body = ErrorResponse), (status = 409, body = ErrorResponse),
        (status = 422, body = ErrorResponse), (status = 503, body = ErrorResponse)
    )
)]
pub(super) async fn promote_unit(
    State(state): State<AppState>,
    headers: HeaderMap,
    Path(game_id): Path<uuid::Uuid>,
    Json(request): Json<PromoteUnitRequest>,
) -> Result<Json<unciv_authoritative_server::CommandAccepted>, ApiError> {
    let actor = authenticated_account(&state, &headers).await?;
    let accepted = state
        .repository
        .execute_promote_unit(
            &state.worker,
            actor.id,
            CommandEnvelope {
                protocol_version: PROTOCOL_VERSION,
                game_id,
                command_id: request.command_id,
                expected_revision: request.expected_revision,
                client_observed_state_hash: request.client_observed_state_hash,
                command: GameCommand::PromoteUnit {
                    unit_id: request.unit_id,
                    promotion_names: request.promotion_names,
                },
            },
        )
        .await
        .map_err(game_error)?;
    Ok(Json(accepted))
}

#[utoipa::path(
    post,
    path = "/api/v3/games/{game_id}/commands/rename-unit",
    params(("game_id" = uuid::Uuid, Path)), security(("bearer_auth" = [])),
    request_body = RenameUnitRequest,
    responses(
        (status = 200, body = unciv_authoritative_server::CommandAccepted),
        (status = 401, body = ErrorResponse), (status = 403, body = ErrorResponse),
        (status = 404, body = ErrorResponse), (status = 409, body = ErrorResponse),
        (status = 422, body = ErrorResponse), (status = 503, body = ErrorResponse)
    )
)]
pub(super) async fn rename_unit(
    State(state): State<AppState>,
    headers: HeaderMap,
    Path(game_id): Path<uuid::Uuid>,
    Json(request): Json<RenameUnitRequest>,
) -> Result<Json<unciv_authoritative_server::CommandAccepted>, ApiError> {
    let actor = authenticated_account(&state, &headers).await?;
    let accepted = state
        .repository
        .execute_rename_unit(
            &state.worker,
            actor.id,
            CommandEnvelope {
                protocol_version: PROTOCOL_VERSION,
                game_id,
                command_id: request.command_id,
                expected_revision: request.expected_revision,
                client_observed_state_hash: request.client_observed_state_hash,
                command: GameCommand::RenameUnit {
                    unit_id: request.unit_id,
                    instance_name: request.instance_name,
                },
            },
        )
        .await
        .map_err(game_error)?;
    Ok(Json(accepted))
}
