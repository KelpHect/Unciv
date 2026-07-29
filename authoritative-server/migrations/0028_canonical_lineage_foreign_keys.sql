-- Make the canonical append-only lineage a PostgreSQL invariant in addition
-- to the control-plane CAS and reconciliation checks.
ALTER TABLE game_revisions
    ADD CONSTRAINT game_revisions_parent_fk
    FOREIGN KEY (game_id, parent_revision)
    REFERENCES game_revisions (game_id, revision)
    ON DELETE RESTRICT;

ALTER TABLE game_revisions
    ADD CONSTRAINT game_revisions_command_fk
    FOREIGN KEY (game_id, command_id)
    REFERENCES game_commands (game_id, command_id)
    ON DELETE RESTRICT;

ALTER TABLE game_outbox
    ADD CONSTRAINT game_outbox_revision_fk
    FOREIGN KEY (game_id, revision)
    REFERENCES game_revisions (game_id, revision)
    ON DELETE RESTRICT;
