use super::*;

#[derive(Deserialize, utoipa::IntoParams)]
#[serde(deny_unknown_fields)]
#[into_params(parameter_in = Query)]
pub(super) struct ListRulesetManifestsQuery {
    after: Option<String>,
    limit: Option<u32>,
}

#[utoipa::path(
    get,
    path = "/api/v3/ruleset-manifests",
    params(ListRulesetManifestsQuery),
    security(("bearer_auth" = [])),
    responses(
        (status = 200, body = unciv_authoritative_server::postgres::RulesetManifestPage),
        (status = 400, body = ErrorResponse),
        (status = 401, body = ErrorResponse),
        (status = 500, body = ErrorResponse)
    )
)]
pub(super) async fn list_ruleset_manifests(
    State(state): State<AppState>,
    headers: HeaderMap,
    Query(query): Query<ListRulesetManifestsQuery>,
) -> Result<Json<unciv_authoritative_server::postgres::RulesetManifestPage>, ApiError> {
    authenticated_account(&state, &headers).await?;
    let limit = game_page_limit(query.limit)?;
    if query.after.as_deref().is_some_and(|cursor| {
        cursor.len() != 64 || !cursor.bytes().all(|byte| byte.is_ascii_hexdigit())
    }) {
        return Err(ApiError::bad_request("invalid_ruleset_manifest_cursor"));
    }
    state
        .repository
        .list_ruleset_manifests(query.after.as_deref(), limit)
        .await
        .map(Json)
        .map_err(game_error)
}
