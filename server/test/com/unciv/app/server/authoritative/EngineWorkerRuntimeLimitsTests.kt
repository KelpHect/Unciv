package com.unciv.app.server.authoritative

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class EngineWorkerRuntimeLimitsTests {
    @Test
    fun defaultsAndExplicitBoundsAreStable() {
        assertEquals(
            EngineWorkerRuntimeLimits(
                socketTimeoutMillis = 5_000,
                commandTimeoutMillis = 30_000,
            ),
            EngineWorkerRuntimeLimits.fromEnvironment(emptyMap()),
        )
        assertEquals(
            EngineWorkerRuntimeLimits(
                socketTimeoutMillis = 250,
                commandTimeoutMillis = 45_000,
            ),
            EngineWorkerRuntimeLimits.fromEnvironment(
                mapOf(
                    "UNCIV_ENGINE_WORKER_SOCKET_TIMEOUT_MS" to "250",
                    "UNCIV_ENGINE_WORKER_COMMAND_TIMEOUT_MS" to "45000",
                ),
            ),
        )
    }

    @Test
    fun malformedDisabledAndExcessiveLimitsFailClosed() {
        for ((name, value) in listOf(
            "UNCIV_ENGINE_WORKER_SOCKET_TIMEOUT_MS" to "",
            "UNCIV_ENGINE_WORKER_SOCKET_TIMEOUT_MS" to "99",
            "UNCIV_ENGINE_WORKER_SOCKET_TIMEOUT_MS" to "600001",
            "UNCIV_ENGINE_WORKER_COMMAND_TIMEOUT_MS" to "invalid",
            "UNCIV_ENGINE_WORKER_COMMAND_TIMEOUT_MS" to "999",
            "UNCIV_ENGINE_WORKER_COMMAND_TIMEOUT_MS" to "600001",
        )) {
            assertThrows(RuntimeException::class.java) {
                EngineWorkerRuntimeLimits.fromEnvironment(mapOf(name to value))
            }
        }
    }

    @Test
    fun armedWatchdogTerminatesAndCancelledWatchdogDoesNot() {
        val termination = CountDownLatch(1)
        EngineWorkerCommandWatchdog(10) { exitCode ->
            assertEquals(EngineWorkerCommandWatchdog.timeoutExitCode, exitCode)
            termination.countDown()
        }.use { watchdog ->
            watchdog.arm()
            assertTrue(termination.await(1, TimeUnit.SECONDS))
        }

        val cancelledTermination = CountDownLatch(1)
        EngineWorkerCommandWatchdog(25) {
            cancelledTermination.countDown()
        }.use { watchdog ->
            assertTrue(watchdog.arm().cancel(false))
            assertFalse(cancelledTermination.await(100, TimeUnit.MILLISECONDS))
        }
    }
}
