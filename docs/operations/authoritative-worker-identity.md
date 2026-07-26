# Private worker service identity

Worker protocol version 2 requires mutual possession of one 256-bit deployment
secret. Loopback binding remains defense in depth; it is not treated as proof
that the peer is the authoritative Rust control plane or packaged Kotlin
worker.

## Configure

Generate the secret outside source control:

```text
openssl rand -hex 32
```

Provide the resulting 64 hexadecimal characters to both processes as
`UNCIV_ENGINE_WORKER_SECRET` through the deployment secret store. Do not place
the populated value in `.env.example`, Compose source, command-line arguments,
logs, support bundles, metrics, or canonical game data. Restrict access to the
two service identities.

Startup fails closed when the value is missing, malformed, or not exactly 32
bytes. The control plane also refuses to start if its authenticated capability
handshake fails.

## Wire contract

Each request and response frame contains:

```text
u32 big-endian JSON length
16-byte request nonce
32-byte HMAC-SHA256 tag
bounded JSON payload
```

The request nonce is generated from the operating system CSPRNG and must be
echoed in the response. Tags bind a direction-specific protocol-v2 domain,
nonce, encoded frame length, and exact payload. The Kotlin worker verifies a
request before JSON decoding or rule execution. Rust verifies the echoed nonce
and response tag before response parsing or persistence. Request tags cannot be
reflected as response tags, and captured responses cannot satisfy a later
request nonce.

Invalid authentication closes only that connection; it never produces a
worker diagnostic or canonical mutation. The existing total command deadline
still cancels connect, write, execution, and read together.

## Rotation and incident response

The first deployment supports one active key, so rotation is a coordinated
restart:

1. Stop routing commands and let in-flight commands reach a terminal result.
2. Generate and distribute a new secret through the secret store.
3. Restart the worker, then the Rust service.
4. Require a successful authenticated handshake before restoring traffic.
5. Remove the old secret and audit its distribution.

If the secret may be exposed, quarantine the host, rotate it, inspect local
process and deployment access, and run canonical reconciliation. HMAC
authentication does not replace host isolation, restrictive service accounts,
resource limits, or the planned production Unix-domain socket.
