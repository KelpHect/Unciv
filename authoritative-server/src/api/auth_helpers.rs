use super::*;

pub(super) fn account_response(account: Account) -> AccountResponse {
    AccountResponse {
        account_id: account.id,
        username: account.username_normalized,
    }
}

pub(super) async fn authenticated_account(
    state: &AppState,
    headers: &HeaderMap,
) -> Result<Account, ApiError> {
    let bearer_token = bearer_token(headers).ok_or_else(ApiError::unauthorized)?;
    state
        .repository
        .authenticate_session(bearer_token)
        .await
        .map_err(login_error)
}
pub(super) fn bearer_token(headers: &HeaderMap) -> Option<&str> {
    headers
        .get("authorization")?
        .to_str()
        .ok()?
        .strip_prefix("Bearer ")
}
