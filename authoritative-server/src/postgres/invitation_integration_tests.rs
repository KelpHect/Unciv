use super::*;

async fn add_account(repository: &PostgresGameRepository, username: &str) -> Uuid {
    let account = Uuid::new_v4();
    sqlx::query(
        "INSERT INTO accounts (id, username_normalized, password_hash) VALUES ($1, $2, 'test-hash')",
    )
    .bind(account)
    .bind(username)
    .execute(&repository.pool)
    .await
    .unwrap();
    account
}

fn join_envelope(game_id: Uuid, command_id: Uuid, revision: u64) -> CommandEnvelope {
    CommandEnvelope {
        protocol_version: PROTOCOL_VERSION,
        game_id,
        command_id,
        expected_revision: revision,
        client_observed_state_hash: None,
        command: GameCommand::JoinGame,
    }
}

#[tokio::test]
#[ignore = "requires an explicit UNCIV_V3_DATABASE_URL"]
async fn owner_invitations_are_retry_safe_discoverable_and_atomically_consumed() {
    let repository = PostgresGameRepository::connect(&database_url())
        .await
        .unwrap();
    repository.migrate().await.unwrap();
    let (owner, game) = seed_repository(&repository).await;
    let first = add_account(&repository, "first-player").await;
    let second = add_account(&repository, "second-player").await;
    let outsider = add_account(&repository, "outsider").await;
    let first_invitation = Uuid::new_v4();
    let second_invitation = Uuid::new_v4();

    repository
        .invite_player(owner, game, first_invitation, " FIRST-player ")
        .await
        .unwrap();
    repository
        .invite_player(owner, game, first_invitation, "first-player")
        .await
        .unwrap();
    assert_eq!(
        repository
            .invite_player(owner, game, first_invitation, "second-player")
            .await
            .unwrap_err(),
        CommitError::InvalidCommand
    );
    assert_eq!(
        repository
            .invite_player(outsider, game, Uuid::new_v4(), "second-player")
            .await
            .unwrap_err(),
        CommitError::Unauthorized
    );
    assert_eq!(
        repository.list_player_invitations(first).await.unwrap(),
        vec![PlayerInvitation {
            game_id: game,
            invitation_id: first_invitation,
            invited_by: format!("account-{owner}"),
            committed_revision: 0,
            canonical_state_hash: state_hash(b"revision-0"),
        }]
    );
    assert_eq!(
        repository
            .require_pending_player_invitation(game, outsider)
            .await
            .unwrap_err(),
        CommitError::Unauthorized
    );
    assert_eq!(
        repository
            .commit_internal(
                outsider,
                join_envelope(game, Uuid::new_v4(), 0),
                proposal(0, b"unauthorized"),
                Some(NewMemberAssignment {
                    civilization_id: "outsider-civilization".to_owned(),
                }),
                None,
            )
            .await
            .unwrap_err(),
        CommitError::Unauthorized
    );

    let first_command = Uuid::new_v4();
    let first_accepted = repository
        .commit_internal(
            first,
            join_envelope(game, first_command, 0),
            proposal(0, b"revision-1"),
            Some(NewMemberAssignment {
                civilization_id: "first-civilization".to_owned(),
            }),
            None,
        )
        .await
        .unwrap();
    assert_eq!(first_accepted.committed_revision, 1);
    let first_journal_actor: String = sqlx::query_scalar(
        "SELECT actor_civilization_id FROM game_commands WHERE game_id=$1 AND command_id=$2",
    )
    .bind(game)
    .bind(first_command)
    .fetch_one(&repository.pool)
    .await
    .unwrap();
    assert_eq!(first_journal_actor, "first-civilization");
    assert!(
        repository
            .list_player_invitations(first)
            .await
            .unwrap()
            .is_empty()
    );
    let consumed: (bool, Option<i64>) = sqlx::query_as(
        "SELECT consumed_at IS NOT NULL, consumed_revision FROM game_player_invitations WHERE game_id=$1 AND invitation_id=$2",
    )
    .bind(game)
    .bind(first_invitation)
    .fetch_one(&repository.pool)
    .await
    .unwrap();
    assert_eq!(consumed, (true, Some(1)));
    repository
        .invite_player(owner, game, first_invitation, "first-player")
        .await
        .unwrap();
    assert_eq!(
        repository
            .invite_player(owner, game, Uuid::new_v4(), "first-player")
            .await
            .unwrap_err(),
        CommitError::InvalidCommand
    );

    // A second player can join the actual current revision; join is not a
    // revision-zero-only special case.
    repository
        .invite_player(owner, game, second_invitation, "second-player")
        .await
        .unwrap();
    let discovered = repository
        .list_player_invitations(second)
        .await
        .unwrap()
        .pop()
        .unwrap();
    assert_eq!(discovered.committed_revision, 1);
    assert_eq!(discovered.canonical_state_hash, state_hash(b"revision-1"));
    let second_accepted = repository
        .commit_internal(
            second,
            join_envelope(game, Uuid::new_v4(), 1),
            proposal(1, b"revision-2"),
            Some(NewMemberAssignment {
                civilization_id: "second-civilization".to_owned(),
            }),
            None,
        )
        .await
        .unwrap();
    assert_eq!(second_accepted.committed_revision, 2);

    let memberships: i64 = sqlx::query_scalar("SELECT count(*) FROM game_members WHERE game_id=$1")
        .bind(game)
        .fetch_one(&repository.pool)
        .await
        .unwrap();
    assert_eq!(memberships, 3);
}
