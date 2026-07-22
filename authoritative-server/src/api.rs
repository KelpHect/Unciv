mod auth;
mod auth_helpers;
mod bootstrap;
mod city_disposition;
mod city_economy;
mod city_governance;
mod city_population;
mod city_state;
mod commands;
mod contracts;
mod diplomacy;
mod error;
mod espionage;
mod event_choices;
mod game_helpers;
mod games;
mod great_people;
mod major_diplomacy;
mod notifications;
mod openapi;
mod religion;
mod state;
mod trade;
mod unit_actions;
mod unit_gifts;
mod unit_movement;
mod unit_orders;
mod unit_transforms;

use auth::*;
use auth_helpers::*;
use city_disposition::*;
use city_economy::*;
use city_governance::*;
use city_population::*;
use city_state::*;
use commands::*;
use diplomacy::*;
use espionage::*;
use event_choices::*;
use game_helpers::*;
pub(super) use games::*;
use great_people::*;
use major_diplomacy::*;
pub(super) use notifications::*;
use religion::*;
use trade::*;
use unit_actions::*;
use unit_gifts::*;
use unit_movement::*;
use unit_orders::*;
use unit_transforms::*;

pub(super) use std::{
    net::{IpAddr, SocketAddr},
    time::Duration,
};

pub(super) use axum::{
    Json, Router,
    extract::ws::{Message, WebSocket, WebSocketUpgrade},
    extract::{ConnectInfo, DefaultBodyLimit, Path, Query, State},
    http::{HeaderMap, HeaderValue, StatusCode, header},
    response::{IntoResponse, Response},
    routing::{delete, get, post},
};
pub(super) use futures_util::{SinkExt, StreamExt};
pub(super) use serde::{Deserialize, Serialize};
pub(super) use unciv_authoritative_server::{
    CityDispositionAction, CityGovernanceAction, CityTileAssignment, CommandEnvelope, CommitError,
    GameCommand, PROJECTION_VERSION, PROTOCOL_VERSION,
    auth::{Account, AuthError},
    notifications::{NotificationHub, run_outbox_dispatcher},
    postgres::{GameMetadata, PostgresGameRepository},
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
