use super::*;

pub(super) fn asyncapi_document_value() -> serde_json::Value {
    let protocol_version = PROTOCOL_VERSION;
    serde_json::json!({
        "asyncapi": "3.1.0",
        "id": "urn:unciv:authoritative-multiplayer:v3:notifications",
        "info": {
            "title": "Unciv authoritative multiplayer notifications",
            "version": "3.0.0",
            "description": "Authenticated WebSocket hints for API-v3 canonical revision changes. Frames are non-authoritative: clients always reconcile through authenticated HTTP projections.",
            "license": {
                "name": "Mozilla Public License 2.0",
                "url": "https://www.mozilla.org/MPL/2.0/"
            }
        },
        "defaultContentType": "application/json",
        "servers": {
            "deployed": {
                "host": "{authority}",
                "pathname": "/",
                "protocol": "wss",
                "description": "The API-v3 deployment. Plain ws is not a production transport.",
                "variables": {
                    "authority": {
                        "default": "api.example.invalid",
                        "description": "Deployment-specific DNS authority and optional port."
                    }
                },
                "security": [
                    {"$ref": "#/components/securitySchemes/opaqueBearerSession"}
                ]
            }
        },
        "channels": {
            "revisionHints": {
                "address": "/api/v3/notifications",
                "title": "Authenticated revision hints",
                "summary": "One account-scoped server-to-client hint stream.",
                "description": "The HTTP GET upgrade requires a live opaque API-v3 bearer session. The server sends revision_committed after a durable canonical commit and resync_required whenever exact hint continuity is uncertain. Clients do not send application messages; text and binary input are ignored and never mutate state.",
                "servers": [
                    {"$ref": "#/servers/deployed"}
                ],
                "messages": {
                    "revisionCommitted": {
                        "$ref": "#/components/messages/revisionCommitted"
                    },
                    "resyncRequired": {
                        "$ref": "#/components/messages/resyncRequired"
                    }
                },
                "bindings": {
                    "ws": {
                        "method": "GET",
                        "query": {
                            "type": "object",
                            "properties": {},
                            "additionalProperties": false
                        },
                        "headers": {
                            "type": "object",
                            "required": ["Authorization"],
                            "properties": {
                                "Authorization": {
                                    "type": "string",
                                    "pattern": "^Bearer [^\\s]+$",
                                    "description": "Opaque revocable API-v3 session token."
                                }
                            }
                        },
                        "bindingVersion": "0.1.0"
                    }
                }
            }
        },
        "operations": {
            "receiveRevisionHint": {
                "action": "receive",
                "title": "Receive a revision or resynchronization hint",
                "summary": "Receive one of the two closed server message shapes.",
                "description": "Receive is from the client application's perspective. Duplicate, missing, delayed, or reordered hints are expected and cannot authorize or commit gameplay.",
                "channel": {
                    "$ref": "#/channels/revisionHints"
                },
                "messages": [
                    {"$ref": "#/channels/revisionHints/messages/revisionCommitted"},
                    {"$ref": "#/channels/revisionHints/messages/resyncRequired"}
                ]
            }
        },
        "components": {
            "messages": {
                "revisionCommitted": {
                    "name": "revision_committed",
                    "title": "Canonical revision committed",
                    "summary": "A game visible to the authenticated account has a new canonical revision.",
                    "contentType": "application/json",
                    "payload": {
                        "$ref": "#/components/schemas/revisionCommittedPayload"
                    }
                },
                "resyncRequired": {
                    "name": "resync_required",
                    "title": "Full HTTP resynchronization required",
                    "summary": "Hint continuity is uncertain; discard delta assumptions and fetch full authenticated projections.",
                    "contentType": "application/json",
                    "payload": {
                        "$ref": "#/components/schemas/resyncRequiredPayload"
                    }
                }
            },
            "schemas": {
                "revisionCommittedPayload": {
                    "type": "object",
                    "required": [
                        "type",
                        "protocol_version",
                        "game_id",
                        "committed_revision",
                        "canonical_state_hash"
                    ],
                    "properties": {
                        "type": {
                            "type": "string",
                            "const": "revision_committed"
                        },
                        "protocol_version": {
                            "type": "integer",
                            "const": protocol_version
                        },
                        "game_id": {
                            "type": "string",
                            "format": "uuid"
                        },
                        "committed_revision": {
                            "type": "integer",
                            "minimum": 0
                        },
                        "canonical_state_hash": {
                            "type": "string",
                            "pattern": "^[0-9a-f]{64}$"
                        }
                    },
                    "additionalProperties": false
                },
                "resyncRequiredPayload": {
                    "type": "object",
                    "required": ["type", "protocol_version"],
                    "properties": {
                        "type": {
                            "type": "string",
                            "const": "resync_required"
                        },
                        "protocol_version": {
                            "type": "integer",
                            "const": protocol_version
                        }
                    },
                    "additionalProperties": false
                }
            },
            "securitySchemes": {
                "opaqueBearerSession": {
                    "type": "http",
                    "scheme": "bearer",
                    "description": "Opaque revocable API-v3 session token. This is not a JWT."
                }
            }
        },
        "x-unciv-lifecycle": {
            "authority": "Messages are hints only. Canonical state changes only through typed HTTP commands executed by the private Kotlin engine and committed by Rust.",
            "connect": "Fetch the latest authenticated game list and projection before relying on hints.",
            "heartbeat": "The server sends numbered WebSocket Ping control frames. The peer must answer with Pong; arbitrary application data does not extend liveness.",
            "lag": "A bounded local queue emits resync_required instead of replaying guessed revisions.",
            "disconnect": "Reconnect with bounded exponential delay and jitter, then fetch full authenticated projections.",
            "delivery": "At-most-transient hints may be duplicated, delayed, reordered, or lost. HTTP revision and projection hashes determine convergence.",
            "limits": {
                "maximumMessageBytes": 4096,
                "maximumFrameBytes": 4096,
                "maximumWriteBufferBytes": 65536,
                "accountQueueHints": 64
            }
        }
    })
}

#[utoipa::path(
    get,
    path = "/api/v3/asyncapi.json",
    responses((status = 200, description = "Generated AsyncAPI 3.1 notification lifecycle contract", body = serde_json::Value))
)]
pub(super) async fn asyncapi_document() -> Json<serde_json::Value> {
    Json(asyncapi_document_value())
}
