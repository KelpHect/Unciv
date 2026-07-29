package com.unciv.logic.multiplayer.authoritative

import java.net.URI
import java.security.MessageDigest

/**
 * Produces one canonical credential scope per authoritative server origin.
 * Remote hostnames require TLS. Plaintext HTTP is accepted for literal IP
 * addresses so a player can explicitly test a self-hosted VPS before DNS/TLS
 * is installed; credentials and game traffic are exposed in that mode.
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
    require(scheme == "https" || scheme == "http" && isLiteralIpAddress(host)) {
        "Remote API-v3 hostnames require HTTPS; http:// is only available for literal test IPs"
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

private fun isLiteralIpAddress(host: String): Boolean =
    host in LOOPBACK_HOSTS ||
        ':' in host && host.all { it.isDigit() || it.lowercaseChar() in 'a'..'f' || it in ":." } ||
        host.split('.').let { parts ->
            parts.size == 4 && parts.all { part ->
                part.isNotEmpty() && part.all(Char::isDigit) &&
                    part.toIntOrNull() in 0..255
            }
        }
