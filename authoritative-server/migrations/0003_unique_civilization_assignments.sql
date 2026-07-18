-- A civilization can be controlled by at most one server-side membership.
-- Existing pre-v3-development rows may be NULL and are therefore safely
-- unable to execute civilization-scoped commands until explicitly migrated.
CREATE UNIQUE INDEX game_members_unique_civilization_idx
    ON game_members (game_id, civilization_id)
    WHERE civilization_id IS NOT NULL;
