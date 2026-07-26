# Projection disclosure policy

Authoritative multiplayer API v3 returns closed player and spectator
projections, never a redacted `GameInfo`. The machine-readable policy in
`projection-disclosure-policy.tsv` assigns an explicit disclosure decision to
every serialized leaf in both projection contracts.

## Classifications

- `public`: safe for every audience represented by that projection.
- `player_private`: scoped to the authenticated player's own state.
- `legally_known`: derived by the private worker only after canonical
  visibility, exploration, or diplomatic-knowledge checks.
- `action_allowlist`: a bounded set of actions the server currently permits.
  It is authorization input, not merely UI advice.
- `presentation`: bounded synchronization or display metadata with no canonical
  save payload.
- `redacted`: the shared DTO contains the field, but this audience must receive
  its neutral or null wire value. A non-redacted value is a protocol violation.

## Enforcement

`ProjectionDisclosurePolicyTests` recursively walks the kotlinx.serialization
descriptors for `PlayerProjection` and `SpectatorProjection`. Its leaf set must
exactly equal the TSV path set. Adding, removing, renaming, or nesting a field
therefore fails tests until a reviewer adds or removes the corresponding
policy decision.

This is deliberately independent of `GameInfo`: adding canonical state cannot
silently expand either public contract. Kotlin builds each projection from
purpose-specific DTOs, Rust rejects unknown fields with
`deny_unknown_fields`, and semantic validation rejects private/action data in
foreign-unit projections.

## Review procedure

For every projection schema change:

1. Decide the audience and classification before updating the TSV.
2. Add a deterministic positive test for the intended audience.
3. Add a negative sentinel for any secret or role-dependent source field.
4. Bump the player or spectator projection version when the wire meaning or
   shape changes, preserving previous fixtures as historical contracts.
5. Regenerate and verify the checked-in OpenAPI document.

Projection version 59 makes exact remaining movement player-private. Own units
must include `currentMovement`; visible foreign units must serialize it as
`null`. Kotlin builder coverage proves the secret value is absent from the
serialized projection, and Rust semantic validation rejects either a missing
owner value or a disclosed foreign value.
