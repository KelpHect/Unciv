use serde_json::Value;

use super::{MAX_FRAME_BYTES, WorkerClientError};

const MAX_JSON_DEPTH: usize = 64;
const MAX_JSON_COLLECTION_ITEMS: usize = 65_536;
const MAX_JSON_NODES: usize = 262_144;

pub(super) fn validate_worker_json(payload: &[u8]) -> Result<Value, WorkerClientError> {
    if payload.is_empty() || payload.len() > MAX_FRAME_BYTES {
        return Err(WorkerClientError::FrameTooLarge);
    }
    let value: Value = serde_json::from_slice(payload).map_err(|_| WorkerClientError::Transport)?;
    let mut stack = vec![(&value, 1_usize)];
    let mut nodes = 0_usize;
    while let Some((value, depth)) = stack.pop() {
        nodes += 1;
        if nodes > MAX_JSON_NODES || depth > MAX_JSON_DEPTH {
            return Err(WorkerClientError::Protocol);
        }
        match value {
            Value::String(value) if value.len() > MAX_FRAME_BYTES => {
                return Err(WorkerClientError::FrameTooLarge);
            }
            Value::Array(values) => {
                if values.len() > MAX_JSON_COLLECTION_ITEMS {
                    return Err(WorkerClientError::Protocol);
                }
                stack.extend(values.iter().map(|value| (value, depth + 1)));
            }
            Value::Object(values) => {
                if values.len() > MAX_JSON_COLLECTION_ITEMS
                    || values.keys().any(|key| key.len() > 128)
                {
                    return Err(WorkerClientError::Protocol);
                }
                stack.extend(values.values().map(|value| (value, depth + 1)));
            }
            _ => {}
        }
    }
    Ok(value)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn worker_json_depth_keys_and_collections_are_bounded() {
        let deep = format!(
            "{}0{}",
            "[".repeat(MAX_JSON_DEPTH),
            "]".repeat(MAX_JSON_DEPTH)
        );
        assert!(matches!(
            validate_worker_json(deep.as_bytes()),
            Err(WorkerClientError::Protocol)
        ));
        let long_key = format!("{{\"{}\":0}}", "k".repeat(129));
        assert!(matches!(
            validate_worker_json(long_key.as_bytes()),
            Err(WorkerClientError::Protocol)
        ));
        let oversized_collection = format!(
            "[{}]",
            std::iter::repeat_n("0", MAX_JSON_COLLECTION_ITEMS + 1)
                .collect::<Vec<_>>()
                .join(",")
        );
        assert!(matches!(
            validate_worker_json(oversized_collection.as_bytes()),
            Err(WorkerClientError::Protocol)
        ));
    }
}
