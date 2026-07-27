# Authoritative API HTTP security boundary

The API is deny-by-default for browser origins. Native Android and desktop
clients do not send an `Origin` header and continue to work without any CORS
configuration. A browser deployment must set `UNCIV_V3_ALLOWED_ORIGINS` to a
comma-separated list of exact HTTPS origins:

```text
UNCIV_V3_ALLOWED_ORIGINS=https://play.example.com,https://admin.example.com
```

The process refuses to start when this variable contains an empty entry, an
HTTP or malformed URI, user information, a path/query, an origin over 256
bytes, or more than 16 distinct origins. Wildcards and reflected origins are
not supported. Reverse proxies must preserve the client-supplied `Origin`
header; they must not synthesize an allowlisted value.

Requests with an unapproved `Origin` receive the stable redacted
`origin_not_allowed` error before an endpoint handler runs. Approved browser
preflights permit only `GET`, `POST`, `PUT`, `DELETE`, and `OPTIONS`, and only
the `Authorization` and `Content-Type` request headers. Bearer authentication
does not use cookies, so credentialed CORS is deliberately disabled.

Every response, including boundary failures and CORS rejections, overrides
caching with `Cache-Control: no-store, max-age=0` and `Pragma: no-cache`. It
also emits:

- `X-Content-Type-Options: nosniff`
- `Referrer-Policy: no-referrer`
- `Permissions-Policy: camera=(), microphone=(), geolocation=()`

TLS and HSTS remain the responsibility of the separately qualified trusted
reverse-proxy boundary. Do not add HSTS to a process that can still be reached
over plain HTTP; qualify TLS termination first.
