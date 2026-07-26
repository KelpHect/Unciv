use std::{
    net::TcpListener,
    process::{Command, Output},
    time::{Duration, Instant},
};

use uuid::Uuid;

pub(super) const POSTGRES_IMAGE: &str = "postgres:19beta2-alpine@sha256:bc62313e826eb44d5f608425b7665962b72820e686da017799e906604bfeb8a5";
const DATABASE: &str = "unciv_failover";
const USER: &str = "unciv_failover";
const PASSWORD: &str = "unciv-failover-test-only";
const PGDATA: &str = "/var/lib/postgresql/19/docker";

pub(super) struct PostgresCluster {
    prefix: String,
    pub(super) primary_port: u16,
    pub(super) standby_port: u16,
}

impl PostgresCluster {
    pub(super) fn start() -> Self {
        ensure_docker_available();
        let prefix = format!("unciv-v3-failover-{}", Uuid::new_v4().simple());
        let primary_port = unused_port();
        let standby_port = unused_port();
        let cluster = Self {
            prefix,
            primary_port,
            standby_port,
        };
        cluster.create();
        cluster
    }

    pub(super) fn database_url(&self, port: u16) -> String {
        format!("postgres://{USER}:{PASSWORD}@127.0.0.1:{port}/{DATABASE}")
    }

    pub(super) fn kill_primary(&self) {
        checked(["kill", &self.primary_name()]);
    }

    pub(super) fn promote_standby(&self) {
        checked([
            "exec",
            &self.standby_name(),
            "psql",
            "-U",
            USER,
            "-d",
            DATABASE,
            "-v",
            "ON_ERROR_STOP=1",
            "-c",
            "SELECT pg_promote(true, 60)",
        ]);
        self.wait_ready(&self.standby_name());
    }

    fn create(&self) {
        checked(["network", "create", &self.network_name()]);
        checked(["volume", "create", &self.primary_volume()]);
        checked(["volume", "create", &self.standby_volume()]);
        checked([
            "run",
            "-d",
            "--name",
            &self.primary_name(),
            "--network",
            &self.network_name(),
            "-p",
            &format!("127.0.0.1:{}:5432", self.primary_port),
            "-e",
            &format!("POSTGRES_DB={DATABASE}"),
            "-e",
            &format!("POSTGRES_USER={USER}"),
            "-e",
            &format!("POSTGRES_PASSWORD={PASSWORD}"),
            "-v",
            &format!("{}:/var/lib/postgresql", self.primary_volume()),
            POSTGRES_IMAGE,
            "postgres",
            "-c",
            "wal_level=replica",
            "-c",
            "max_wal_senders=10",
            "-c",
            "max_replication_slots=10",
            "-c",
            "wal_keep_size=256MB",
        ]);
        self.wait_ready(&self.primary_name());

        self.primary_sql(
            "CREATE ROLE unciv_replication WITH REPLICATION LOGIN PASSWORD 'unciv-replication-test-only'",
        );
        checked([
            "exec",
            &self.primary_name(),
            "sh",
            "-c",
            &format!(
                "printf '\\nhost replication unciv_replication all scram-sha-256\\n' >> {PGDATA}/pg_hba.conf"
            ),
        ]);
        self.primary_sql("SELECT pg_reload_conf()");

        checked([
            "run",
            "--rm",
            "--user",
            "0",
            "-v",
            &format!("{}:/var/lib/postgresql", self.standby_volume()),
            POSTGRES_IMAGE,
            "sh",
            "-c",
            &format!("mkdir -p {PGDATA} && chown -R postgres:postgres /var/lib/postgresql"),
        ]);
        checked([
            "run",
            "--rm",
            "--user",
            "postgres",
            "--network",
            &self.network_name(),
            "-e",
            "PGPASSWORD=unciv-replication-test-only",
            "-v",
            &format!("{}:/var/lib/postgresql", self.standby_volume()),
            POSTGRES_IMAGE,
            "pg_basebackup",
            "-h",
            &self.primary_name(),
            "-U",
            "unciv_replication",
            "-D",
            PGDATA,
            "-R",
            "-X",
            "stream",
            "-c",
            "fast",
        ]);
        checked([
            "run",
            "-d",
            "--name",
            &self.standby_name(),
            "--network",
            &self.network_name(),
            "-p",
            &format!("127.0.0.1:{}:5432", self.standby_port),
            "-v",
            &format!("{}:/var/lib/postgresql", self.standby_volume()),
            POSTGRES_IMAGE,
            "postgres",
            "-c",
            "hot_standby=on",
        ]);
        self.wait_ready(&self.standby_name());
        self.wait_for_streaming();

        self.primary_sql("ALTER SYSTEM SET synchronous_standby_names = '*'");
        self.primary_sql("ALTER SYSTEM SET synchronous_commit = 'remote_apply'");
        self.primary_sql("SELECT pg_reload_conf()");
        self.wait_for_synchronous_standby();
    }

    fn wait_ready(&self, container: &str) {
        wait_until("PostgreSQL readiness", Duration::from_secs(60), || {
            command(["exec", container, "pg_isready", "-U", USER, "-d", DATABASE])
                .status
                .success()
                && command([
                    "exec", container, "psql", "-U", USER, "-d", DATABASE, "-At", "-c", "SELECT 1",
                ])
                .status
                .success()
        });
    }

    fn wait_for_streaming(&self) {
        wait_until("streaming replication", Duration::from_secs(60), || {
            self.primary_scalar("SELECT count(*) FROM pg_stat_replication WHERE state='streaming'")
                == "1"
        });
    }

    fn wait_for_synchronous_standby(&self) {
        wait_until("synchronous standby", Duration::from_secs(60), || {
            self.primary_scalar("SELECT count(*) FROM pg_stat_replication WHERE sync_state='sync'")
                == "1"
        });
    }

    fn primary_sql(&self, sql: &str) {
        checked([
            "exec",
            &self.primary_name(),
            "psql",
            "-U",
            USER,
            "-d",
            DATABASE,
            "-v",
            "ON_ERROR_STOP=1",
            "-c",
            sql,
        ]);
    }

    fn primary_scalar(&self, sql: &str) -> String {
        let output = command([
            "exec",
            &self.primary_name(),
            "psql",
            "-U",
            USER,
            "-d",
            DATABASE,
            "-At",
            "-v",
            "ON_ERROR_STOP=1",
            "-c",
            sql,
        ]);
        assert!(
            output.status.success(),
            "psql failed: {}",
            String::from_utf8_lossy(&output.stderr)
        );
        String::from_utf8_lossy(&output.stdout).trim().to_owned()
    }

    fn primary_name(&self) -> String {
        format!("{}-primary", self.prefix)
    }

    fn standby_name(&self) -> String {
        format!("{}-standby", self.prefix)
    }

    fn network_name(&self) -> String {
        format!("{}-network", self.prefix)
    }

    fn primary_volume(&self) -> String {
        format!("{}-primary-data", self.prefix)
    }

    fn standby_volume(&self) -> String {
        format!("{}-standby-data", self.prefix)
    }
}

impl Drop for PostgresCluster {
    fn drop(&mut self) {
        let _ = command(["rm", "-f", &self.primary_name(), &self.standby_name()]);
        let _ = command(["network", "rm", &self.network_name()]);
        let _ = command([
            "volume",
            "rm",
            "-f",
            &self.primary_volume(),
            &self.standby_volume(),
        ]);
    }
}

fn ensure_docker_available() {
    assert!(
        command(["info"]).status.success(),
        "Docker with the Linux engine is required for this ignored test"
    );
}

fn unused_port() -> u16 {
    TcpListener::bind(("127.0.0.1", 0))
        .expect("bind disposable port")
        .local_addr()
        .expect("read disposable port")
        .port()
}

fn wait_until(description: &str, timeout: Duration, mut predicate: impl FnMut() -> bool) {
    let started = Instant::now();
    while started.elapsed() < timeout {
        if predicate() {
            return;
        }
        std::thread::sleep(Duration::from_millis(250));
    }
    panic!("timed out waiting for {description}");
}

fn checked<const N: usize>(args: [&str; N]) {
    let output = command(args);
    assert!(
        output.status.success(),
        "docker command failed\nstdout: {}\nstderr: {}",
        String::from_utf8_lossy(&output.stdout),
        String::from_utf8_lossy(&output.stderr)
    );
}

fn command<I, S>(args: I) -> Output
where
    I: IntoIterator<Item = S>,
    S: AsRef<std::ffi::OsStr>,
{
    Command::new("docker")
        .args(args)
        .output()
        .expect("launch docker")
}
