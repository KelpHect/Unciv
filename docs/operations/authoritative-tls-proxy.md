# Authoritative API TLS and trusted-proxy deployment

Only Caddy listens on public ports 80 and 443. The Rust API listens on
`127.0.0.1:3000`, the Kotlin worker on loopback, and PostgreSQL on loopback or a
private encrypted database network. The checked-in Caddyfile uses automatic
HTTPS, redirects HTTP to HTTPS, emits one-year HSTS, removes the `Server`
header, actively checks `/readyz`, and forwards traffic only while the API and
worker dependencies are ready.

This deployment is qualified against Caddy 2.11.4. Install that exact release
from the official Caddy package repository and hold it until its replacement
passes the same TLS smoke. The container-based qualification pins:

```text
caddy:2.11.4-alpine@sha256:5f5c8640aae01df9654968d946d8f1a56c497f1dd5c5cda4cf95ab7c14d58648
```

After installing the exact package, run `apt-mark hold caddy`. Review security
releases promptly; replace the hold only through a tested pinned upgrade, not an
unattended mutable-version deployment.

## Install

Create the service identity and protected configuration:

```text
groupadd --system caddy
useradd --system --gid caddy --home /var/lib/caddy --shell /usr/sbin/nologin caddy
install -d -o root -g caddy -m 0750 /etc/unciv-authoritative/proxy
install -o root -g caddy -m 0640 authoritative-server/caddy/Caddyfile /etc/unciv-authoritative/proxy/
install -o root -g caddy -m 0640 authoritative-server/caddy/proxy.env.example /etc/unciv-authoritative/proxy/proxy.env
install -o root -g root -m 0444 docs/operations/authoritative-tls-proxy.md /opt/unciv-authoritative/docs/
install -o root -g root -m 0644 authoritative-server/systemd/unciv-authoritative-proxy.service /etc/systemd/system/
```

Replace the example domain and ACME email. Point public DNS at the host and
allow inbound TCP 80/443. Keep TCP 3000, the worker port, PostgreSQL, and
Caddy's loopback admin endpoint blocked externally. Configure the API with:

```text
UNCIV_V3_BIND=127.0.0.1:3000
UNCIV_V3_TRUSTED_PROXY=loopback
UNCIV_V3_ALLOWED_ORIGINS=https://play.example.com
```

Validate before activation:

```text
caddy version
caddy validate --config /etc/unciv-authoritative/proxy/Caddyfile --adapter caddyfile
systemd-analyze verify /etc/systemd/system/unciv-authoritative-proxy.service
systemctl daemon-reload
systemctl enable --now unciv-authoritative-proxy.service
curl --fail --proto '=https' --tlsv1.2 https://play.example.com/readyz
curl --head http://play.example.com/healthz
```

The HTTPS response must include
`Strict-Transport-Security: max-age=31536000`. The HTTP request must redirect to
HTTPS. Do not add `includeSubDomains` or `preload` until every subdomain is
permanently HTTPS.

## Forwarded client identity

Caddy's protected default ignores spoofed inbound forwarding values and derives
`X-Forwarded-For` from the direct remote host. This configuration also forces
`X-Forwarded-Proto: https` and removes
`Forwarded` and `X-Real-IP`. Rust trusts that one address only when its TCP peer
is loopback and trusted-proxy mode is explicitly enabled. Missing, repeated,
comma-chained, malformed, unspecified, multicast, or competing values are
rejected before registration/login rate-limit logic or any account mutation.
Headers from untrusted peers never replace the socket address.

Do not place a CDN/load balancer in front without a separate reviewed policy:
that changes Caddy's direct peer. Enumerate the proxy ranges, enable Caddy's
strict right-to-left trusted-proxy parsing, and extend the Rust allowlist only
after spoofing tests cover that topology.

## Rotation and failure

Caddy stores ACME account and certificate state only in its mode-0700 systemd
state directory and renews certificates automatically. Monitor renewal logs
and certificate expiry externally. A failed `/readyz` check removes the API
from service; operators can still query loopback liveness/readiness. Reload a
validated Caddyfile with `systemctl reload unciv-authoritative-proxy.service`.
Never disable certificate verification, serve a fallback plaintext API, or
expose the Rust listener during an incident.
