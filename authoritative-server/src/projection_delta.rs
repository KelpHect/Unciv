use serde::{Deserialize, Serialize};
use serde_json::Value;
use utoipa::ToSchema;
use uuid::Uuid;

pub const MAX_PROJECTION_DELTA_OPERATIONS: usize = 4_096;
pub const MAX_PROJECTION_DELTA_PATH_BYTES: usize = 1_024;

#[derive(Clone, Debug, Deserialize, Serialize, ToSchema, PartialEq)]
pub struct ProjectionDeltaOperation {
    pub path: String,
    pub value: Value,
}

#[derive(Clone, Debug, Deserialize, Serialize, ToSchema, PartialEq)]
pub struct GameProjectionDelta {
    pub game_id: Uuid,
    pub projection_version: u16,
    pub base_revision: u64,
    pub base_canonical_state_hash: String,
    pub base_projection_hash: String,
    pub committed_revision: u64,
    pub canonical_state_hash: String,
    pub projection_hash: String,
    pub operations: Vec<ProjectionDeltaOperation>,
}

pub(crate) fn projection_delta_operations(
    base: &Value,
    target: &Value,
) -> Option<Vec<ProjectionDeltaOperation>> {
    let mut operations = Vec::new();
    collect_replacements(base, target, "", &mut operations)?;
    if operations.len() > MAX_PROJECTION_DELTA_OPERATIONS {
        return None;
    }
    operations.sort_by(|left, right| left.path.cmp(&right.path));
    Some(operations)
}

fn collect_replacements(
    base: &Value,
    target: &Value,
    path: &str,
    operations: &mut Vec<ProjectionDeltaOperation>,
) -> Option<()> {
    if base == target {
        return Some(());
    }
    match (base, target) {
        (Value::Object(base), Value::Object(target))
            if base.len() == target.len() && base.keys().all(|key| target.contains_key(key)) =>
        {
            for (key, target_value) in target {
                let segment = escape_pointer_segment(key);
                collect_replacements(
                    &base[key],
                    target_value,
                    &format!("{path}/{segment}"),
                    operations,
                )?;
            }
        }
        (Value::Array(base), Value::Array(target)) if base.len() == target.len() => {
            for (index, target_value) in target.iter().enumerate() {
                collect_replacements(
                    &base[index],
                    target_value,
                    &format!("{path}/{index}"),
                    operations,
                )?;
            }
        }
        _ => {
            if path.is_empty() || path.len() > MAX_PROJECTION_DELTA_PATH_BYTES {
                return None;
            }
            operations.push(ProjectionDeltaOperation {
                path: path.to_owned(),
                value: target.clone(),
            });
        }
    }
    (operations.len() <= MAX_PROJECTION_DELTA_OPERATIONS).then_some(())
}

fn escape_pointer_segment(segment: &str) -> String {
    segment.replace('~', "~0").replace('/', "~1")
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn delta_is_deterministic_compact_and_reconstructs_the_target() {
        let base = serde_json::json!({
            "turn": 7,
            "units": [
                {"id": 1, "health": 100, "orders": null},
                {"id": 2, "health": 80, "orders": null}
            ],
            "cities": [{"id": "rome", "queue": ["Monument"]}]
        });
        let target = serde_json::json!({
            "turn": 8,
            "units": [
                {"id": 1, "health": 91, "orders": null},
                {"id": 2, "health": 80, "orders": null}
            ],
            "cities": [{"id": "rome", "queue": ["Monument", "Granary"]}]
        });

        let operations = projection_delta_operations(&base, &target).unwrap();

        assert_eq!(
            operations,
            [
                ProjectionDeltaOperation {
                    path: "/cities/0/queue".to_owned(),
                    value: serde_json::json!(["Monument", "Granary"]),
                },
                ProjectionDeltaOperation {
                    path: "/turn".to_owned(),
                    value: serde_json::json!(8),
                },
                ProjectionDeltaOperation {
                    path: "/units/0/health".to_owned(),
                    value: serde_json::json!(91),
                },
            ]
        );
        assert!(
            serde_json::to_vec(&operations).unwrap().len()
                < serde_json::to_vec(&target).unwrap().len()
        );
        assert_eq!(apply(base, &operations), target);
    }

    #[test]
    fn changed_collection_shape_replaces_only_that_collection() {
        let base = serde_json::json!({"items": [{"id": 1}], "unchanged": [1, 2, 3]});
        let target = serde_json::json!({"items": [{"id": 1}, {"id": 2}], "unchanged": [1, 2, 3]});

        assert_eq!(
            projection_delta_operations(&base, &target).unwrap(),
            [ProjectionDeltaOperation {
                path: "/items".to_owned(),
                value: target["items"].clone(),
            }]
        );
    }

    #[test]
    fn json_pointer_segments_are_escaped() {
        let base = serde_json::json!({"a/b": {"x~y": 1}});
        let target = serde_json::json!({"a/b": {"x~y": 2}});

        assert_eq!(
            projection_delta_operations(&base, &target).unwrap()[0].path,
            "/a~1b/x~0y"
        );
    }

    fn apply(mut value: Value, operations: &[ProjectionDeltaOperation]) -> Value {
        for operation in operations {
            let segments = operation
                .path
                .split('/')
                .skip(1)
                .map(|segment| segment.replace("~1", "/").replace("~0", "~"))
                .collect::<Vec<_>>();
            replace(&mut value, &segments, operation.value.clone());
        }
        value
    }

    fn replace(current: &mut Value, path: &[String], replacement: Value) {
        if path.len() == 1 {
            match current {
                Value::Object(object) => *object.get_mut(&path[0]).unwrap() = replacement,
                Value::Array(array) => {
                    array[path[0].parse::<usize>().unwrap()] = replacement;
                }
                _ => panic!("invalid test path"),
            }
            return;
        }
        let child = match current {
            Value::Object(object) => object.get_mut(&path[0]).unwrap(),
            Value::Array(array) => &mut array[path[0].parse::<usize>().unwrap()],
            _ => panic!("invalid test path"),
        };
        replace(child, &path[1..], replacement);
    }
}
