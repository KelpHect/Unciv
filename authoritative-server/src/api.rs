mod auth;
mod auth_helpers;
mod bootstrap;
mod city_economy;
mod city_governance;
mod city_population;
mod commands;
mod contracts;
mod error;
mod game_helpers;
mod games;
mod notifications;
mod openapi;
mod state;
mod unit_actions;
mod unit_movement;
mod unit_orders;

use auth::*;
use auth_helpers::*;
use city_economy::*;
use city_governance::*;
use city_population::*;
use commands::*;
use game_helpers::*;
pub(super) use games::*;
pub(super) use notifications::*;
use unit_actions::*;
use unit_movement::*;
use unit_orders::*;

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
    CityGovernanceAction, CityTileAssignment, CommandEnvelope, CommitError, GameCommand,
    PROJECTION_VERSION, PROTOCOL_VERSION,
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
