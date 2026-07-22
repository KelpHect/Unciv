//! Rust API-v3 control-plane primitives. Rule execution is intentionally absent:
//! a private Kotlin worker produces a validated [`CommitProposal`].

mod command;
mod hashing;
mod projection_city_economy;
#[cfg(test)]
mod projection_city_economy_tests;
mod projection_combat;
#[cfg(test)]
mod projection_combat_tests;
#[cfg(test)]
mod projection_escort_tests;
mod projection_validation;
mod repository;

pub mod auth;
pub mod notifications;
pub mod operations;
pub mod postgres;
pub mod projection;
pub mod worker;

pub use command::{
    CitizenFocus, CityDispositionAction, CityGovernanceAction, CityTileAssignment, CommandAccepted,
    CommandEnvelope, CommitProposal, ConstructionQueueAction, GameCommand, GreatPersonUnitAction,
    ReligiousBeliefType, ReligiousUnitAction, UnitPosture,
};
pub use hashing::state_hash;
pub use repository::{CommitError, InMemoryGameRepository};

pub const PROTOCOL_VERSION: u16 = 3;
pub const PROJECTION_VERSION: u16 = 47;
pub const MAX_SNAPSHOT_BYTES: usize = 16 * 1024 * 1024;

#[cfg(test)]
#[path = "lib_tests.rs"]
mod tests;
