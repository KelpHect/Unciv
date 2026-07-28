use std::{
    io::{Read, Write},
    net::{SocketAddr, TcpListener, TcpStream},
    process::{Command, Stdio},
    thread,
    time::{Duration, Instant},
};

const HELPER_ENV: &str = "UNCIV_V3_OBSERVABILITY_TEST_HELPER";

#[test]
fn telemetry_helper_process() {
    if std::env::var_os(HELPER_ENV).is_none() {
        return;
    }
    let address = unciv_authoritative_server::telemetry::initialize().unwrap();
    assert!(address.ip().is_loopback());
    metrics::counter!(
        "unciv_v3_http_requests_total",
        "route" => "command",
        "method" => "POST",
        "status_class" => "2xx"
    )
    .increment(1);
    thread::sleep(Duration::from_secs(30));
}

#[test]
fn loopback_prometheus_listener_exports_bounded_metrics() {
    let address = unused_address();
    let mut child = Command::new(std::env::current_exe().unwrap())
        .args(["--exact", "telemetry_helper_process", "--nocapture"])
        .env(HELPER_ENV, "1")
        .env("UNCIV_V3_METRICS_BIND", address.to_string())
        .env("UNCIV_V3_LOG", "off")
        .stdin(Stdio::null())
        .stdout(Stdio::null())
        .stderr(Stdio::null())
        .spawn()
        .unwrap();

    let started = Instant::now();
    let response = loop {
        assert!(
            started.elapsed() < Duration::from_secs(10),
            "Prometheus listener did not become ready"
        );
        match scrape(address) {
            Ok(response) if response.contains("unciv_v3_http_requests_total") => break response,
            _ => thread::sleep(Duration::from_millis(50)),
        }
    };
    child.kill().unwrap();
    child.wait().unwrap();

    assert!(response.starts_with("HTTP/1.1 200"));
    assert!(response.contains("route=\"command\""));
    assert!(response.contains("method=\"POST\""));
    assert!(response.contains("status_class=\"2xx\""));
    for private in ["game_id", "account_id", "command_id", "session_id"] {
        assert!(!response.contains(private));
    }
}

fn scrape(address: SocketAddr) -> std::io::Result<String> {
    let mut stream = TcpStream::connect_timeout(&address, Duration::from_millis(200))?;
    stream.set_read_timeout(Some(Duration::from_secs(2)))?;
    stream.write_all(b"GET /metrics HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n")?;
    let mut response = String::new();
    stream.read_to_string(&mut response)?;
    Ok(response)
}

fn unused_address() -> SocketAddr {
    let listener = TcpListener::bind("127.0.0.1:0").unwrap();
    let address = listener.local_addr().unwrap();
    drop(listener);
    address
}
