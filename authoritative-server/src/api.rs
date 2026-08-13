mod administration;
mod administration_contracts;
mod asyncapi;
mod auth;
mod auth_helpers;
mod bootstrap;
mod boundary_limits;
mod capital_project;
mod city_disposition;
mod city_economy;
mod city_economy_contracts;
mod city_governance;
mod city_population;
mod city_state;
mod city_tile_batches;
mod client_network;
mod commands;
mod construction_commands;
mod contracts;
mod diplomacy;
mod error;
mod espionage;
mod event_choices;
mod game_chat;
mod game_chat_contracts;
mod game_helpers;
mod game_setup;
mod games;
mod great_people;
mod http_security;
mod instant_improvements;
mod lifecycle;
mod lifecycle_contracts;
mod lobbies;
mod major_diplomacy;
mod manifests;
mod notifications;
mod observability;
mod openapi;
mod religion;
mod replay;
mod research;
mod rewind_contracts;
mod rewinds;
mod social;
mod social_contracts;
mod spectators;
mod state;
mod trade;
mod unit_actions;
mod unit_gifts;
mod unit_movement;
mod unit_orders;
mod unit_transforms;
mod unit_triggers;

use administration::*;
use administration_contracts::*;
use asyncapi::*;
use auth::*;
use auth_helpers::*;
use boundary_limits::*;
use capital_project::*;
use city_disposition::*;
use city_economy::*;
use city_economy_contracts::*;
use city_governance::*;
use city_population::*;
use city_state::*;
use city_tile_batches::*;
use client_network::*;
use commands::*;
use construction_commands::*;
use diplomacy::*;
use espionage::*;
use event_choices::*;
use game_chat::*;
use game_chat_contracts::*;
use game_helpers::*;
use game_setup::*;
pub(super) use games::*;
use great_people::*;
use http_security::*;
use instant_improvements::*;
use lifecycle::*;
use lifecycle_contracts::*;
use lobbies::*;
use major_diplomacy::*;
use manifests::*;
pub(super) use notifications::*;
use observability::*;
use religion::*;
use replay::*;
use research::*;
use rewind_contracts::*;
use rewinds::*;
use social::*;
use social_contracts::*;
use spectators::*;
use trade::*;
use unit_actions::*;
use unit_gifts::*;
use unit_movement::*;
use unit_orders::*;
use unit_transforms::*;
use unit_triggers::*;

pub(super) use std::net::{IpAddr, SocketAddr};

pub(super) use axum::{
    Json, Router,
    extract::ws::{Message, WebSocket, WebSocketUpgrade},
    extract::{ConnectInfo, DefaultBodyLimit, Path, Query, State},
    http::{HeaderMap, HeaderValue, StatusCode, header},
    response::{IntoResponse, Response},
    routing::{delete, get, post, put},
};
pub(super) use futures_util::{SinkExt, StreamExt};
pub(super) use serde::{Deserialize, Serialize};
pub(super) use unciv_authoritative_server::{
    CityDispositionAction, CityGovernanceAction, CityTileAssignment, CommandEnvelope, CommitError,
    GameCommand, PROJECTION_VERSION, PROTOCOL_VERSION, ResearchQueueAction,
    auth::{Account, AuthError, PasswordService, SessionPolicy},
    notifications::{NotificationHub, start_notification_runtime},
    postgres::{GameMetadata, PostgresGameRepository, SecurityAuditEvent, SecurityAuditOutcome},
    worker::EngineWorkerClient,
};
pub(super) use utoipa::{Modify, OpenApi, ToSchema};

pub(crate) use bootstrap::run;
use contracts::*;
use error::*;
pub(super) use openapi::*;
use state::*;

#[cfg(test)]
mod tests;
