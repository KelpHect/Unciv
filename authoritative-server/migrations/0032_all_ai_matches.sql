-- Allow zero-human (all-AI) matches by widening the human_slots constraint.
-- The owner creates the match as a spectator/observer when human_slots = 0.

ALTER TABLE game_lobbies
    DROP CONSTRAINT IF EXISTS game_lobbies_human_slots_check;

ALTER TABLE game_lobbies
    ADD CONSTRAINT game_lobbies_human_slots_check
        CHECK (human_slots BETWEEN 0 AND 16);
