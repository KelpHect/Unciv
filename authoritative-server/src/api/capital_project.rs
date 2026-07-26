use super::*;

#[derive(Deserialize, ToSchema)]
#[serde(deny_unknown_fields)]
pub(super) struct AddUnitToCapitalProjectRequest {
    pub(super) command_id: uuid::Uuid,
    pub(super) expected_revision: u64,
    pub(super) client_observed_state_hash: Option<String>,
    pub(super) unit_id: i32,
}

#[utoipa::path(
    post,
    path = "/api/v3/games/{game_id}/commands/add-unit-to-capital-project",
    params(("game_id" = uuid::Uuid, Path)),
    security(("bearer_auth" = [])),
    request_body = AddUnitToCapitalProjectRequest,
    responses(
        (status = 200, body = unciv_authoritative_server::CommandAccepted),
        (status = 400, body = ErrorResponse),
        (status = 401, body = ErrorResponse),
        (status = 403, body = ErrorResponse),
        (status = 404, body = ErrorResponse),
        (status = 409, body = ErrorResponse),
        (status = 422, body = ErrorResponse),
        (status = 503, body = ErrorResponse)
    )
)]
pub(super) async fn add_unit_to_capital_project(
    State(state): State<AppState>,
    headers: HeaderMap,
    Path(game_id): Path<uuid::Uuid>,
    Json(request): Json<AddUnitToCapitalProjectRequest>,
) -> Result<Json<unciv_authoritative_server::CommandAccepted>, ApiError> {
    if request.unit_id < 0 {
        return Err(ApiError::bad_request("invalid_command"));
    }
    let actor = authenticated_account(&state, &headers).await?;
    let accepted = state
        .repository
        .execute_add_unit_to_capital_project(
            &state.worker,
            actor.id,
            CommandEnvelope {
                protocol_version: PROTOCOL_VERSION,
                game_id,
                command_id: request.command_id,
                expected_revision: request.expected_revision,
                client_observed_state_hash: request.client_observed_state_hash,
                command: GameCommand::AddUnitToCapitalProject {
                    unit_id: request.unit_id,
                },
            },
        )
        .await
        .map_err(game_error)?;
    Ok(Json(accepted))
}
