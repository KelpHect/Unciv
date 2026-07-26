use rand_core::{OsRng, RngCore};
use serde::Serialize;
use tokio::{
    io::{AsyncReadExt, AsyncWriteExt},
    net::TcpStream,
    time::timeout,
};

use super::authentication::{FrameDirection, NONCE_BYTES, TAG_BYTES, sign_frame, verify_frame};
use super::json_limits::validate_worker_json;
use super::*;

impl EngineWorkerClient {
    pub(crate) async fn replay_operation(
        &self,
        previous_revision: u64,
        actor_id: &str,
        manifest: &WorkerManifest,
        server_time_millis: i64,
        snapshot: &str,
        replay_operation: serde_json::Value,
    ) -> Result<CommitProposal, WorkerClientError> {
        if !manifest.is_valid() {
            return Err(WorkerClientError::Protocol);
        }
        if server_time_millis < 0 {
            return Err(WorkerClientError::Protocol);
        }
        let mut operation = replay_operation.clone();
        let fields = operation
            .as_object_mut()
            .ok_or(WorkerClientError::Protocol)?;
        if fields
            .get("type")
            .and_then(serde_json::Value::as_str)
            .is_none()
            || fields.contains_key("snapshot")
        {
            return Err(WorkerClientError::Protocol);
        }
        fields.insert(
            "snapshot".to_owned(),
            serde_json::Value::String(snapshot.to_owned()),
        );
        let request = serde_json::json!({
            "protocolVersion": WORKER_PROTOCOL_VERSION,
            "serverTimeMillis": server_time_millis,
            "actorId": actor_id,
            "rulesetManifest": manifest,
            "operation": operation,
        });
        let mut response = self.execute_request(request).await?;
        if response.server_time_millis != Some(server_time_millis) {
            return Err(WorkerClientError::Protocol);
        }
        response.replay_operation = Some(replay_operation);
        commit_proposal(previous_revision, response)
    }

    pub(super) async fn execute(
        &self,
        actor_id: &str,
        manifest: &WorkerManifest,
        operation: WorkerOperation<'_>,
    ) -> Result<WorkerResponse, WorkerClientError> {
        if !manifest.is_valid() {
            return Err(WorkerClientError::Protocol);
        }
        let server_time_millis = server_time_millis()?;
        let mut replay_operation =
            serde_json::to_value(&operation).map_err(|_| WorkerClientError::Transport)?;
        replay_operation
            .as_object_mut()
            .ok_or(WorkerClientError::Protocol)?
            .remove("snapshot");
        let request = WorkerRequest {
            protocol_version: WORKER_PROTOCOL_VERSION,
            server_time_millis: Some(server_time_millis),
            actor_id: Some(actor_id),
            ruleset_manifest: Some(manifest),
            operation,
        };
        let mut response = self.execute_request(request).await?;
        if response.server_time_millis != Some(server_time_millis) {
            return Err(WorkerClientError::Protocol);
        }
        response.replay_operation = Some(replay_operation);
        Ok(response)
    }

    pub(super) async fn execute_request(
        &self,
        request: impl Serialize,
    ) -> Result<WorkerResponse, WorkerClientError> {
        let payload = serde_json::to_vec(&request).map_err(|_| WorkerClientError::Transport)?;
        if payload.len() > MAX_FRAME_BYTES {
            return Err(WorkerClientError::FrameTooLarge);
        }
        validate_worker_json(&payload)?;
        let mut request_nonce = [0_u8; NONCE_BYTES];
        OsRng.fill_bytes(&mut request_nonce);
        let request_tag = sign_frame(
            &self.identity_key,
            FrameDirection::Request,
            &request_nonce,
            &payload,
        )?;
        let response = timeout(self.request_timeout, async {
            let mut stream = TcpStream::connect(self.address)
                .await
                .map_err(|_| WorkerClientError::Transport)?;
            stream
                .write_u32(payload.len() as u32)
                .await
                .map_err(|_| WorkerClientError::Transport)?;
            stream
                .write_all(&request_nonce)
                .await
                .map_err(|_| WorkerClientError::Transport)?;
            stream
                .write_all(&request_tag)
                .await
                .map_err(|_| WorkerClientError::Transport)?;
            stream
                .write_all(&payload)
                .await
                .map_err(|_| WorkerClientError::Transport)?;
            stream
                .flush()
                .await
                .map_err(|_| WorkerClientError::Transport)?;
            let size = stream
                .read_u32()
                .await
                .map_err(|_| WorkerClientError::Transport)? as usize;
            if !valid_frame_size(size) {
                return Err(WorkerClientError::FrameTooLarge);
            }
            let mut response_nonce = [0_u8; NONCE_BYTES];
            stream
                .read_exact(&mut response_nonce)
                .await
                .map_err(|_| WorkerClientError::Transport)?;
            if response_nonce != request_nonce {
                return Err(WorkerClientError::Identity);
            }
            let mut response_tag = [0_u8; TAG_BYTES];
            stream
                .read_exact(&mut response_tag)
                .await
                .map_err(|_| WorkerClientError::Transport)?;
            let mut response = vec![0; size];
            stream
                .read_exact(&mut response)
                .await
                .map_err(|_| WorkerClientError::Transport)?;
            verify_frame(
                &self.identity_key,
                FrameDirection::Response,
                &response_nonce,
                &response,
                &response_tag,
            )?;
            decode_worker_response(&response)
        })
        .await
        .map_err(|_| WorkerClientError::Transport)??;
        validate_worker_response(response)
    }
}

#[cfg(test)]
pub(crate) async fn read_authenticated_test_frame(
    stream: &mut TcpStream,
) -> ([u8; NONCE_BYTES], serde_json::Value) {
    let size = stream.read_u32().await.unwrap() as usize;
    let mut nonce = [0_u8; NONCE_BYTES];
    stream.read_exact(&mut nonce).await.unwrap();
    let mut tag = [0_u8; TAG_BYTES];
    stream.read_exact(&mut tag).await.unwrap();
    let mut payload = vec![0; size];
    stream.read_exact(&mut payload).await.unwrap();
    verify_frame(
        &WorkerIdentityKey::for_test(),
        FrameDirection::Request,
        &nonce,
        &payload,
        &tag,
    )
    .unwrap();
    let value = serde_json::from_slice(&payload).unwrap();
    (nonce, value)
}

#[cfg(test)]
pub(crate) async fn write_authenticated_test_frame(
    stream: &mut TcpStream,
    nonce: [u8; NONCE_BYTES],
    response: serde_json::Value,
) {
    let payload = serde_json::to_vec(&response).unwrap();
    let tag = sign_frame(
        &WorkerIdentityKey::for_test(),
        FrameDirection::Response,
        &nonce,
        &payload,
    )
    .unwrap();
    stream.write_u32(payload.len() as u32).await.unwrap();
    stream.write_all(&nonce).await.unwrap();
    stream.write_all(&tag).await.unwrap();
    stream.write_all(&payload).await.unwrap();
}

fn valid_frame_size(size: usize) -> bool {
    (1..=MAX_FRAME_BYTES).contains(&size)
}

fn decode_worker_response(payload: &[u8]) -> Result<WorkerResponse, WorkerClientError> {
    let value = validate_worker_json(payload)?;
    serde_json::from_value(value).map_err(|_| WorkerClientError::Transport)
}

fn validate_worker_response(response: WorkerResponse) -> Result<WorkerResponse, WorkerClientError> {
    if response.protocol_version != WORKER_PROTOCOL_VERSION {
        return Err(WorkerClientError::Protocol);
    }
    if let Some(error) = response.error.as_ref() {
        return Err(WorkerClientError::Rejected(format!(
            "{}: {}",
            error.code, error.message
        )));
    }
    Ok(response)
}

#[cfg(test)]
mod property_tests {
    use proptest::prelude::*;
    use proptest::test_runner::RngSeed;
    use serde_json::json;
    use tokio::{io::AsyncReadExt, net::TcpListener};

    use super::*;

    #[tokio::test]
    async fn worker_deadline_cancels_the_connection_without_waiting_for_a_response() {
        let listener = TcpListener::bind("127.0.0.1:0").await.unwrap();
        let address = listener.local_addr().unwrap();
        let server = tokio::spawn(async move {
            let (mut stream, _) = listener.accept().await.unwrap();
            let size = stream.read_u32().await.unwrap() as usize;
            let mut nonce = [0_u8; NONCE_BYTES];
            stream.read_exact(&mut nonce).await.unwrap();
            let mut tag = [0_u8; TAG_BYTES];
            stream.read_exact(&mut tag).await.unwrap();
            let mut request = vec![0; size];
            stream.read_exact(&mut request).await.unwrap();
            let mut byte = [0_u8; 1];
            tokio::time::timeout(Duration::from_secs(1), stream.read(&mut byte))
                .await
                .unwrap()
                .unwrap()
        });
        let result = EngineWorkerClient::new(
            address,
            Duration::from_millis(10),
            WorkerIdentityKey::for_test(),
        )
        .execute_request(json!({
            "protocolVersion": WORKER_PROTOCOL_VERSION,
            "operation": {"type": "handshake"},
        }))
        .await;
        assert!(matches!(result, Err(WorkerClientError::Transport)));
        assert_eq!(server.await.unwrap(), 0);
    }

    #[tokio::test]
    async fn worker_response_requires_the_same_service_identity() {
        let listener = TcpListener::bind("127.0.0.1:0").await.unwrap();
        let address = listener.local_addr().unwrap();
        let server = tokio::spawn(async move {
            let (mut stream, _) = listener.accept().await.unwrap();
            let (nonce, _) = read_authenticated_test_frame(&mut stream).await;
            let response =
                serde_json::to_vec(&json!({"protocolVersion": WORKER_PROTOCOL_VERSION})).unwrap();
            stream.write_u32(response.len() as u32).await.unwrap();
            stream.write_all(&nonce).await.unwrap();
            stream.write_all(&[0_u8; TAG_BYTES]).await.unwrap();
            stream.write_all(&response).await.unwrap();
        });
        let result = EngineWorkerClient::new(
            address,
            Duration::from_secs(1),
            WorkerIdentityKey::for_test(),
        )
        .execute_request(json!({
            "protocolVersion": WORKER_PROTOCOL_VERSION,
            "operation": {"type": "handshake"},
        }))
        .await;
        assert!(matches!(result, Err(WorkerClientError::Identity)));
        server.await.unwrap();
    }

    proptest! {
        #![proptest_config(ProptestConfig {
            cases: 256,
            rng_seed: RngSeed::Fixed(0x554E_4349_5650_3303),
            ..ProptestConfig::default()
        })]

        #[test]
        fn length_prefixes_are_accepted_only_within_the_frame_bound(size in any::<u32>()) {
            prop_assert_eq!(
                valid_frame_size(size as usize),
                (1..=MAX_FRAME_BYTES).contains(&(size as usize)),
            );
        }

        #[test]
        fn arbitrary_worker_frames_never_panic_or_bypass_the_closed_parser(
            payload in prop::collection::vec(any::<u8>(), 0..16_384),
        ) {
            if let Ok(response) = decode_worker_response(&payload) {
                let result = validate_worker_response(response);
                prop_assert!(result.is_ok() || matches!(
                    result,
                    Err(WorkerClientError::Protocol | WorkerClientError::Rejected(_))
                ));
            }
        }

        #[test]
        fn worker_responses_reject_unknown_fields(
            field in "[a-z]{1,24}",
            value in any::<i64>(),
        ) {
            let mut response = json!({"protocolVersion": WORKER_PROTOCOL_VERSION});
            response
                .as_object_mut()
                .unwrap()
                .insert(format!("unknown{field}"), json!(value));
            prop_assert!(decode_worker_response(&serde_json::to_vec(&response).unwrap()).is_err());
        }

        #[test]
        fn ruleset_manifests_enforce_hash_name_count_and_uniqueness(
            engine_build in "[A-Za-z0-9._ -]{1,128}",
            base_name in "[A-Za-z0-9._ -]{1,128}",
            mods in prop::collection::vec(
                ("[A-Za-z0-9._ -]{1,128}", "[0-9a-f]{64}"),
                0..70,
            ),
        ) {
            let manifest = WorkerManifest {
                engine_build,
                base_ruleset: WorkerRuleset {
                    name: base_name,
                    sha256: "a".repeat(64),
                },
                mods: mods
                    .into_iter()
                    .map(|(name, sha256)| WorkerRuleset { name, sha256 })
                    .collect(),
            };
            let unique = {
                let mut names = std::collections::HashSet::new();
                names.insert(manifest.base_ruleset.name.as_str())
                    && manifest.mods.iter().all(|item| names.insert(item.name.as_str()))
            };
            prop_assert_eq!(manifest.is_valid(), manifest.mods.len() <= 64 && unique);

            let mut encoded = serde_json::to_value(&manifest).unwrap();
            encoded
                .as_object_mut()
                .unwrap()
                .insert("unknownField".to_owned(), json!(true));
            prop_assert!(serde_json::from_value::<WorkerManifest>(encoded).is_err());
        }
    }
}
