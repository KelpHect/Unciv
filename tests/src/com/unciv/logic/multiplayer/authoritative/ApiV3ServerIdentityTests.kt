package com.unciv.logic.multiplayer.authoritative

import org.junit.Assert.assertEquals
import org.junit.Test

class ApiV3ServerIdentityTests {

    @Test
    fun notificationWebSocketUrlDerivesWsFromPlainHttp() {
        assertEquals(
            "ws://127.0.0.1:3060/api/v3/notifications",
            apiV3NotificationWebSocketUrl("http://127.0.0.1:3060"),
        )
        assertEquals(
            "ws://127.0.0.1:3060/api/v3/notifications",
            apiV3NotificationWebSocketUrl("http://127.0.0.1:3060/"),
        )
    }

    @Test
    fun notificationWebSocketUrlDerivesWssFromHttps() {
        assertEquals(
            "wss://host.example/api/v3/notifications",
            apiV3NotificationWebSocketUrl("https://host.example/"),
        )
        assertEquals(
            "wss://host.example:8443/api/v3/notifications",
            apiV3NotificationWebSocketUrl("https://host.example:8443"),
        )
    }

    @Test
    fun notificationWebSocketUrlKeepsCustomPathPrefix() {
        assertEquals(
            "ws://127.0.0.1:3060/gate/api/v3/notifications",
            apiV3NotificationWebSocketUrl("http://127.0.0.1:3060/gate"),
        )
    }
}
