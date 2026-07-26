package com.unciv.logic.multiplayer.authoritative

import java.net.URI
import java.security.MessageDigest

/**
 * Produces one canonical credential scope per authoritative server origin.
 * Remote API-v3 traffic requires TLS; plaintext HTTP is accepted only for
 * loopback development and integration tests.
 */
fun normalizeApiV3BaseUrl(baseUrl: String): String {
    val uri = URI(baseUrl.trim()).normalize()
    val scheme = uri.scheme?.lowercase()
        ?: throw IllegalArgumentException("API-v3 server URL must include a scheme")
    val host = uri.host?.trim('[', ']')?.lowercase()
        ?: throw IllegalArgumentException("API-v3 server URL must include a host")
    require(uri.userInfo == null) { "API-v3 server URL must not contain user information" }
    require(uri.query == null && uri.fragment == null) {
        "API-v3 server URL must not contain a query or fragment"
    }
    require(scheme == "https" || scheme == "http" && host in LOOPBACK_HOSTS) {
        "Remote API-v3 servers require HTTPS"
    }

    val canonicalPort = uri.port.takeUnless {
        scheme == "https" && it == 443 || scheme == "http" && it == 80
    } ?: -1
    val formattedHost = if (':' in host) "[$host]" else host
    val authority = if (canonicalPort < 0) formattedHost else "$formattedHost:$canonicalPort"
    val path = uri.path.orEmpty().trimEnd('/')
    return "$scheme://$authority${if (path.isEmpty()) "/" else "$path/"}"
}

fun apiV3CredentialScope(baseUrl: String): String {
    val digest = MessageDigest.getInstance("SHA-256")
        .digest(normalizeApiV3BaseUrl(baseUrl).toByteArray(Charsets.UTF_8))
    return digest.joinToString("") { "%02x".format(it) }
}

private val LOOPBACK_HOSTS = setOf("localhost", "127.0.0.1", "::1")
