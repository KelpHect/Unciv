package com.unciv.logic.multiplayer

import com.sun.net.httpserver.HttpServer
import com.unciv.logic.multiplayer.authoritative.CommandEnvelope
import com.unciv.logic.multiplayer.authoritative.PlayerProjection
import java.net.InetSocketAddress
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ApiVersionDetectionTests {
    @Test
    fun detectsTheCurrentAuthoritativeProtocolWithoutALiteralVersion() = runBlocking {
        withCapabilitiesServer(CommandEnvelope.CURRENT_PROTOCOL_VERSION, wholeStateUpload = false) {
            assertEquals(ApiVersion.APIv3, ApiVersion.detect(it, suppress = false))
        }
    }

    @Test
    fun rejectsOldOrWholeStateAuthoritativeCapabilities() = runBlocking {
        withCapabilitiesServer(
            CommandEnvelope.CURRENT_PROTOCOL_VERSION - 1,
            wholeStateUpload = false,
        ) {
            assertNull(ApiVersion.detect(it))
        }
        withCapabilitiesServer(CommandEnvelope.CURRENT_PROTOCOL_VERSION, wholeStateUpload = true) {
            assertNull(ApiVersion.detect(it))
        }
    }

    private suspend fun withCapabilitiesServer(
        protocolVersion: Int,
        wholeStateUpload: Boolean,
        block: suspend (String) -> Unit,
    ) {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/api/v3/capabilities") { exchange ->
            val body =
                """
                {
                  "protocol_version": $protocolVersion,
                  "projection_version": ${PlayerProjection.CURRENT_PROJECTION_VERSION},
                  "commands": [],
                  "whole_state_upload": $wholeStateUpload,
                  "websocket_notifications": true,
                  "projection_deltas": true
                }
                """.trimIndent().toByteArray()
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(200, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
        server.start()
        try {
            block("http://127.0.0.1:${server.address.port}")
        } finally {
            server.stop(0)
        }
    }
}
