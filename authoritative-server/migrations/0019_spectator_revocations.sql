ALTER TABLE game_admin_operations
    DROP CONSTRAINT game_admin_operations_operation_kind_check,
    ADD CONSTRAINT game_admin_operations_operation_kind_check
        CHECK (
            operation_kind IN (
                'transfer_ownership',
                'close_game',
                'archive_game',
                'revoke_spectator'
            )
        );
