-- Add game visibility (public/private) for public match replay viewing.
-- Password-free matches are marked public by the canonical lobby transaction and
-- can be replayed by authenticated accounts without spectator membership.

ALTER TABLE games
    ADD COLUMN IF NOT EXISTS visibility TEXT NOT NULL DEFAULT 'private'
        CHECK (visibility IN ('private', 'public'));

-- Index for listing public matches efficiently
CREATE INDEX IF NOT EXISTS games_public_matches_idx
    ON games (lifecycle_status, visibility, created_at DESC)
    WHERE visibility = 'public';
