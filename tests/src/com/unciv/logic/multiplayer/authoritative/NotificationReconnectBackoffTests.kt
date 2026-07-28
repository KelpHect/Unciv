package com.unciv.logic.multiplayer.authoritative

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NotificationReconnectBackoffTests {
    @Test
    fun `retry delay grows exponentially with bounded equal jitter`() {
        val minimum = NotificationReconnectBackoff { 0.0 }
        assertEquals(
            listOf(125L, 250L, 500L, 1000L, 2000L, 4000L, 5000L, 5000L),
            List(8) { minimum.nextDelayMillis() },
        )

        val maximum = NotificationReconnectBackoff { 1.0 }
        assertEquals(
            listOf(250L, 500L, 1000L, 2000L, 4000L, 8000L, 10000L, 10000L),
            List(8) { maximum.nextDelayMillis() },
        )
    }

    @Test
    fun `invalid jitter source cannot escape bounds and reset removes history`() {
        val belowRange = NotificationReconnectBackoff { -10.0 }
        assertTrue(List(100) { belowRange.nextDelayMillis() }.all { it in 125L..10_000L })

        val aboveRange = NotificationReconnectBackoff { 10.0 }
        repeat(20) { aboveRange.nextDelayMillis() }
        aboveRange.reset()
        assertEquals(250L, aboveRange.nextDelayMillis())
    }
}
