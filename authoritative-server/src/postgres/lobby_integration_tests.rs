use super::*;

#[tokio::test]
#[ignore = "requires an explicit UNCIV_V3_DATABASE_URL"]
async fn lobby_password_slots_civilizations_and_start_are_transactionally_enforced() {
    let repository = PostgresGameRepository::connect(&database_url())
        .await
        .unwrap();
    repository.migrate().await.unwrap();
    let (owner, game) = seed_repository(&repository).await;
    let player = insert_account(&repository).await;
    let outsider = insert_account(&repository).await;
    let password_hash = PasswordService
        .hash("correct horse battery staple")
        .unwrap();

    sqlx::query(
        "INSERT INTO game_lobbies
            (game_id, owner_account_id, human_slots, setup, password_hash,
             available_civilizations)
         VALUES ($1, $2, 2, '{}'::jsonb, $3, ARRAY['test-civilization', 'Greece'])",
    )
    .bind(game)
    .bind(owner)
    .bind(password_hash)
    .execute(&repository.pool)
    .await
    .unwrap();
    sqlx::query("INSERT INTO game_lobby_readiness (game_id, account_id) VALUES ($1, $2)")
        .bind(game)
        .bind(owner)
        .execute(&repository.pool)
        .await
        .unwrap();

    assert_eq!(
        repository
            .authorize_lobby_join(player, game, "Greece", None)
            .await,
        Err(CommitError::Unauthorized),
    );
    assert_eq!(
        repository
            .authorize_lobby_join(player, game, "Greece", Some("wrong password"))
            .await,
        Err(CommitError::Unauthorized),
    );
    repository
        .authorize_lobby_join(player, game, "Greece", Some("correct horse battery staple"))
        .await
        .unwrap();

    insert_lobby_member(&repository, game, player, "Greece").await;
    assert_eq!(
        repository
            .authorize_lobby_join(
                outsider,
                game,
                "Greece",
                Some("correct horse battery staple")
            )
            .await,
        Err(CommitError::InvalidCommand),
    );
    assert_eq!(
        repository
            .authorize_lobby_join(outsider, game, "Rome", Some("correct horse battery staple"))
            .await,
        Err(CommitError::InvalidCommand),
    );

    assert_eq!(
        repository.start_lobby(outsider, game, 0).await,
        Err(CommitError::Unauthorized),
    );
    assert_eq!(
        repository.start_lobby(owner, game, 0).await,
        Err(CommitError::InvalidCommand),
    );

    let owner_ready = repository
        .set_lobby_ready(owner, game, 0, true)
        .await
        .unwrap();
    assert_eq!(owner_ready.lobby_revision, 1);
    assert_eq!(owner_ready.base_ruleset_name, "Civ V - Vanilla");
    assert!(owner_ready.mod_names.is_empty());
    assert_eq!(
        repository.set_lobby_ready(player, game, 0, true).await,
        Err(CommitError::Stale {
            expected: 0,
            actual: 1,
        }),
    );
    let all_ready = repository
        .set_lobby_ready(player, game, 1, true)
        .await
        .unwrap();
    assert_eq!(all_ready.lobby_revision, 2);

    assert_eq!(
        repository.start_lobby(owner, game, 1).await,
        Err(CommitError::Stale {
            expected: 1,
            actual: 2,
        }),
    );
    let started = repository.start_lobby(owner, game, 2).await.unwrap();
    assert!(started.started);
    assert_eq!(started.lobby_revision, 3);
    assert_eq!(
        repository.set_lobby_ready(owner, game, 3, false).await,
        Err(CommitError::InvalidCommand),
    );
}

#[tokio::test]
#[ignore = "requires an explicit UNCIV_V3_DATABASE_URL"]
async fn open_lobby_browser_excludes_started_games_and_hides_password_hashes() {
    let repository = PostgresGameRepository::connect(&database_url())
        .await
        .unwrap();
    repository.migrate().await.unwrap();
    let (owner, game) = seed_repository(&repository).await;
    let password_hash = PasswordService.hash("not returned to clients").unwrap();
    sqlx::query(
        "INSERT INTO game_lobbies
            (game_id, owner_account_id, human_slots, setup, password_hash,
             available_civilizations)
         VALUES ($1, $2, 1, '{\"difficulty\":\"Prince\"}'::jsonb, $3,
                 ARRAY['test-civilization'])",
    )
    .bind(game)
    .bind(owner)
    .bind(password_hash)
    .execute(&repository.pool)
    .await
    .unwrap();
    sqlx::query(
        "INSERT INTO game_lobby_readiness (game_id, account_id, ready)
         VALUES ($1, $2, TRUE)",
    )
    .bind(game)
    .bind(owner)
    .execute(&repository.pool)
    .await
    .unwrap();

    let page = repository.list_open_lobbies(owner, None, 20).await.unwrap();
    assert_eq!(page.lobbies.len(), 1);
    assert!(page.lobbies[0].password_required);
    assert_eq!(page.lobbies[0].members.len(), 1);
    assert_eq!(page.lobbies[0].base_ruleset_name, "Civ V - Vanilla");
    assert!(page.lobbies[0].mod_names.is_empty());

    repository.start_lobby(owner, game, 0).await.unwrap();
    assert!(
        repository
            .list_open_lobbies(owner, None, 20)
            .await
            .unwrap()
            .lobbies
            .is_empty()
    );
    assert!(repository.lobby_started(game).await.unwrap());
}

async fn insert_account(repository: &PostgresGameRepository) -> Uuid {
    let account = Uuid::new_v4();
    sqlx::query(
        "INSERT INTO accounts (id, username_normalized, password_hash)
         VALUES ($1, $2, 'test-hash')",
    )
    .bind(account)
    .bind(format!("account-{account}"))
    .execute(&repository.pool)
    .await
    .unwrap();
    account
}

async fn insert_lobby_member(
    repository: &PostgresGameRepository,
    game: Uuid,
    account: Uuid,
    civilization: &str,
) {
    sqlx::query(
        "INSERT INTO game_members (game_id, account_id, role, civilization_id)
         VALUES ($1, $2, 'player', $3)",
    )
    .bind(game)
    .bind(account)
    .bind(civilization)
    .execute(&repository.pool)
    .await
    .unwrap();
    sqlx::query("INSERT INTO game_lobby_readiness (game_id, account_id) VALUES ($1, $2)")
        .bind(game)
        .bind(account)
        .execute(&repository.pool)
        .await
        .unwrap();
}
