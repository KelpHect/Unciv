use std::{
    sync::Arc,
    time::{Duration, Instant},
};

use futures_util::StreamExt;
use reqwest::{Client, StatusCode};
use serde_json::{Value, json};
use tokio::sync::Mutex;
use tokio_tungstenite::{connect_async, tungstenite::client::IntoClientRequest};
use uuid::Uuid;

const DEFAULT_SCENARIOS: usize = 12;
const DEFAULT_CONTENTION: usize = 4;

pub async fn run() -> Result<Value, String> {
    let base_url = environment("UNCIV_V3_LOAD_BASE_URL", "http://127.0.0.1:8080");
    let scenarios = environment_count("UNCIV_V3_LOAD_SCENARIOS", DEFAULT_SCENARIOS, 1, 10_000)?;
    let contention = environment_count("UNCIV_V3_LOAD_CONTENTION", DEFAULT_CONTENTION, 2, 64)?;
    let client = Client::builder()
        .connect_timeout(Duration::from_secs(5))
        .timeout(Duration::from_secs(90))
        .pool_max_idle_per_host(32)
        .build()
        .map_err(|_| "load HTTP client construction failed")?;
    let run_id = Uuid::new_v4();
    let credentials = json!({
        "username": format!("load-{}", &run_id.simple().to_string()[..16]),
        "password": format!("Load-Only-{}-47!", run_id.simple()),
    });

    request_json(
        client.post(format!("{base_url}/api/v3/auth/register")),
        &credentials,
        StatusCode::CREATED,
    )
    .await?;
    let login = request_json(
        client.post(format!("{base_url}/api/v3/auth/login")),
        &credentials,
        StatusCode::OK,
    )
    .await?;
    let token = login["session_token"]
        .as_str()
        .ok_or("login response omitted session_token")?
        .to_owned();
    let manifest_page = authorized_get(
        &client,
        &base_url,
        "/api/v3/ruleset-manifests?limit=1",
        &token,
    )
    .await?;
    let manifest_hash = manifest_page["manifests"]
        .as_array()
        .and_then(|items| items.first())
        .and_then(|manifest| manifest["manifest_hash"].as_str())
        .ok_or("ruleset manifest page was empty")?
        .to_owned();

    let websocket_url = websocket_url(&base_url)?;
    let mut websocket_request = websocket_url
        .into_client_request()
        .map_err(|_| "notification WebSocket URL is invalid")?;
    websocket_request.headers_mut().insert(
        "Authorization",
        format!("Bearer {token}")
            .parse()
            .map_err(|_| "session token is not a valid header")?,
    );
    let (websocket, _) = connect_async(websocket_request)
        .await
        .map_err(|_| "notification WebSocket connection failed")?;
    let (_, mut notification_stream) = websocket.split();
    let notification_count = Arc::new(Mutex::new(0_u64));
    let notification_counter = notification_count.clone();
    let notification_reader = tokio::spawn(async move {
        while let Some(message) = notification_stream.next().await {
            match message {
                Ok(message) if message.is_text() => {
                    *notification_counter.lock().await += 1;
                }
                Ok(message) if message.is_close() => break,
                Ok(_) => {}
                Err(_) => break,
            }
        }
    });

    let started = Instant::now();
    let mut scenario_samples = Vec::with_capacity(scenarios);
    let mut creation_samples = Vec::with_capacity(scenarios);
    let mut projection_samples = Vec::with_capacity(scenarios);
    let mut ai_turn_samples = Vec::with_capacity(scenarios);
    let mut committed_commands = 0_u64;
    let mut stale_conflicts = 0_u64;
    for index in 0..scenarios {
        let scenario_started = Instant::now();
        let creation_started = Instant::now();
        let game = request_json_authorized(
            client.post(format!("{base_url}/api/v3/games")),
            &token,
            &json!({
                "operation_id": Uuid::new_v4(),
                "ruleset_manifest_hash": manifest_hash,
                "setup": load_setup(),
            }),
            StatusCode::CREATED,
        )
        .await?;
        creation_samples.push(creation_started.elapsed());
        let game_id = game["game_id"]
            .as_str()
            .ok_or("create response omitted game_id")?
            .to_owned();

        let projection_started = Instant::now();
        let mut projection = authorized_get(
            &client,
            &base_url,
            &format!("/api/v3/games/{game_id}/projection"),
            &token,
        )
        .await?;
        projection_samples.push(projection_started.elapsed());
        let mut revision = required_u64(&projection, "committed_revision")?;
        let mut state_hash = required_string(&projection, "canonical_state_hash")?;
        if let Some(technology_name) = projection["projection"]["research"]["selectableTargets"]
            .as_array()
            .and_then(|targets| targets.first())
            .and_then(Value::as_str)
        {
            let accepted = request_json_authorized(
                client.post(format!(
                    "{base_url}/api/v3/games/{game_id}/commands/set-research-path"
                )),
                &token,
                &json!({
                    "command_id": Uuid::new_v4(),
                    "expected_revision": revision,
                    "client_observed_state_hash": state_hash,
                    "technology_name": technology_name,
                    "append": false,
                }),
                StatusCode::OK,
            )
            .await?;
            revision = required_u64(&accepted, "committed_revision")?;
            state_hash = required_string(&accepted, "canonical_state_hash")?;
            projection = authorized_get(
                &client,
                &base_url,
                &format!("/api/v3/games/{game_id}/projection"),
                &token,
            )
            .await?;
            if required_u64(&projection, "committed_revision")? != revision {
                return Err("projection did not reach the accepted research revision".to_owned());
            }
        }

        let ai_started = Instant::now();
        let mut contenders = Vec::with_capacity(contention);
        for _ in 0..contention {
            let request_client = client.clone();
            let request_url = format!("{base_url}/api/v3/games/{game_id}/commands/end-turn");
            let request_token = token.clone();
            let request_hash = state_hash.clone();
            contenders.push(tokio::spawn(async move {
                let response = request_client
                    .post(request_url)
                    .bearer_auth(request_token)
                    .json(&json!({
                        "command_id": Uuid::new_v4(),
                        "expected_revision": revision,
                        "client_observed_state_hash": request_hash,
                    }))
                    .send()
                    .await
                    .map_err(|_| "contended end-turn transport failed".to_owned())?;
                Ok::<StatusCode, String>(response.status())
            }));
        }
        let mut scenario_commits = 0_u64;
        for contender in contenders {
            match contender
                .await
                .map_err(|_| "contended end-turn task failed")??
            {
                StatusCode::OK => {
                    scenario_commits += 1;
                    committed_commands += 1;
                }
                StatusCode::CONFLICT => stale_conflicts += 1,
                status => return Err(format!("unexpected end-turn status {status}")),
            }
        }
        if scenario_commits != 1 {
            return Err(format!(
                "scenario {index} committed {scenario_commits} contended commands instead of one"
            ));
        }
        ai_turn_samples.push(ai_started.elapsed());
        scenario_samples.push(scenario_started.elapsed());
    }

    let game_page = authorized_get(&client, &base_url, "/api/v3/games?limit=100", &token).await?;
    let listed_games = game_page["games"]
        .as_array()
        .ok_or("game list omitted games")?
        .len();
    if listed_games < scenarios {
        return Err("game list omitted created load games".to_owned());
    }
    tokio::time::sleep(Duration::from_secs(2)).await;
    let delivered_notifications = *notification_count.lock().await;
    notification_reader.abort();
    if delivered_notifications < committed_commands {
        return Err(format!(
            "WebSocket delivered {delivered_notifications} notifications for {committed_commands} committed AI turns"
        ));
    }

    Ok(json!({
        "schema_version": 1,
        "base_url": base_url,
        "scenarios": scenarios,
        "contention_per_game": contention,
        "elapsed_ms": started.elapsed().as_millis(),
        "created_games": listed_games,
        "committed_commands": committed_commands,
        "expected_stale_conflicts": stale_conflicts,
        "websocket_notifications": delivered_notifications,
        "scenario": summarize(&scenario_samples),
        "game_creation": summarize(&creation_samples),
        "projection": summarize(&projection_samples),
        "contended_end_turn_with_server_ai": summarize(&ai_turn_samples),
    }))
}

async fn authorized_get(
    client: &Client,
    base_url: &str,
    path: &str,
    token: &str,
) -> Result<Value, String> {
    let response = client
        .get(format!("{base_url}{path}"))
        .bearer_auth(token)
        .send()
        .await
        .map_err(|_| format!("GET {path} transport failed"))?;
    parse_response(response, StatusCode::OK).await
}

async fn request_json(
    builder: reqwest::RequestBuilder,
    body: &Value,
    expected: StatusCode,
) -> Result<Value, String> {
    let response = builder
        .json(body)
        .send()
        .await
        .map_err(|_| "HTTP request transport failed")?;
    parse_response(response, expected).await
}

async fn request_json_authorized(
    builder: reqwest::RequestBuilder,
    token: &str,
    body: &Value,
    expected: StatusCode,
) -> Result<Value, String> {
    request_json(builder.bearer_auth(token), body, expected).await
}

async fn parse_response(
    response: reqwest::Response,
    expected: StatusCode,
) -> Result<Value, String> {
    let status = response.status();
    let bytes = response
        .bytes()
        .await
        .map_err(|_| "HTTP response body failed")?;
    if status != expected {
        return Err(format!(
            "HTTP status {status}, expected {expected}: {}",
            String::from_utf8_lossy(&bytes)
        ));
    }
    serde_json::from_slice(&bytes).map_err(|_| "HTTP response was not valid JSON".to_owned())
}

fn required_u64(value: &Value, field: &str) -> Result<u64, String> {
    value[field]
        .as_u64()
        .ok_or_else(|| format!("response omitted {field}"))
}

fn required_string(value: &Value, field: &str) -> Result<String, String> {
    value[field]
        .as_str()
        .map(ToOwned::to_owned)
        .ok_or_else(|| format!("response omitted {field}"))
}

fn websocket_url(base_url: &str) -> Result<String, String> {
    if let Some(origin) = base_url.strip_prefix("http://") {
        Ok(format!("ws://{origin}/api/v3/notifications"))
    } else if let Some(origin) = base_url.strip_prefix("https://") {
        Ok(format!("wss://{origin}/api/v3/notifications"))
    } else {
        Err("UNCIV_V3_LOAD_BASE_URL must use http or https".to_owned())
    }
}

fn load_setup() -> Value {
    json!({
        "difficulty": "Prince",
        "speed": "Standard",
        "starting_era": "Ancient era",
        "victory_types": ["Domination", "Scientific", "Cultural", "Diplomatic"],
        "major_civilizations": 8,
        "city_states": 12,
        "max_turns": 500,
        "map_type": "two_continents",
        "map_shape": "rectangular",
        "map_size": "large",
        "map_resources": "default",
        "barbarians": "normal",
        "one_city_challenge": false,
        "nuclear_weapons_enabled": true,
        "espionage_enabled": true,
        "no_start_bias": false,
        "shuffle_player_order": true,
        "no_city_razing": false,
        "world_wrap": false,
        "strategic_balance": false,
        "legendary_start": false,
        "no_ruins": false,
        "no_natural_wonders": false,
        "minutes_until_skip_turn": 1440,
        "minutes_until_force_resign": 4320,
        "minutes_recovered_per_turn": 1440
    })
}

fn summarize(samples: &[Duration]) -> Value {
    let mut micros = samples.iter().map(Duration::as_micros).collect::<Vec<_>>();
    micros.sort_unstable();
    let total: u128 = micros.iter().sum();
    json!({
        "samples": micros.len(),
        "mean_ms": round_ms(total as f64 / micros.len() as f64),
        "p50_ms": round_ms(percentile(&micros, 50) as f64),
        "p95_ms": round_ms(percentile(&micros, 95) as f64),
        "p99_ms": round_ms(percentile(&micros, 99) as f64),
        "min_ms": round_ms(micros[0] as f64),
        "max_ms": round_ms(micros[micros.len() - 1] as f64),
    })
}

fn percentile(sorted: &[u128], percentile: usize) -> u128 {
    let index = ((sorted.len() - 1) * percentile).div_ceil(100);
    sorted[index]
}

fn round_ms(micros: f64) -> f64 {
    (micros / 10.0).round() / 100.0
}

fn environment(name: &str, default: &str) -> String {
    std::env::var(name).unwrap_or_else(|_| default.to_owned())
}

fn environment_count(
    name: &str,
    default: usize,
    minimum: usize,
    maximum: usize,
) -> Result<usize, String> {
    let value = std::env::var(name)
        .ok()
        .map(|value| value.parse::<usize>())
        .transpose()
        .map_err(|_| format!("{name} must be an integer"))?
        .unwrap_or(default);
    if !(minimum..=maximum).contains(&value) {
        return Err(format!("{name} must be between {minimum} and {maximum}"));
    }
    Ok(value)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn websocket_url_preserves_origin_and_rejects_other_schemes() {
        assert_eq!(
            websocket_url("http://127.0.0.1:8080").unwrap(),
            "ws://127.0.0.1:8080/api/v3/notifications"
        );
        assert_eq!(
            websocket_url("https://example.invalid").unwrap(),
            "wss://example.invalid/api/v3/notifications"
        );
        assert!(websocket_url("file:///tmp/socket").is_err());
    }

    #[test]
    fn summary_is_stable_and_millisecond_scaled() {
        let report = summarize(&[
            Duration::from_micros(100),
            Duration::from_micros(200),
            Duration::from_micros(300),
            Duration::from_micros(400),
        ]);
        assert_eq!(report["p50_ms"], 0.3);
        assert_eq!(report["p95_ms"], 0.4);
    }
}
