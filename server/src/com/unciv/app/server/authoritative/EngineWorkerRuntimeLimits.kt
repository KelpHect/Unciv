package com.unciv.app.server.authoritative

import java.util.concurrent.ScheduledFuture
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit

data class EngineWorkerRuntimeLimits(
    val socketTimeoutMillis: Int,
    val commandTimeoutMillis: Long,
) {
    init {
        require(socketTimeoutMillis in minSocketTimeoutMillis..maxTimeoutMillis.toInt())
        require(commandTimeoutMillis in minCommandTimeoutMillis..maxTimeoutMillis)
    }

    companion object {
        const val defaultSocketTimeoutMillis = 5_000
        const val defaultCommandTimeoutMillis = 30_000L
        const val minSocketTimeoutMillis = 100
        const val minCommandTimeoutMillis = 1_000L
        const val maxTimeoutMillis = 600_000L

        fun fromEnvironment(environment: Map<String, String> = System.getenv()) =
            EngineWorkerRuntimeLimits(
                socketTimeoutMillis = parseInt(
                    environment,
                    "UNCIV_ENGINE_WORKER_SOCKET_TIMEOUT_MS",
                    defaultSocketTimeoutMillis,
                ),
                commandTimeoutMillis = parseLong(
                    environment,
                    "UNCIV_ENGINE_WORKER_COMMAND_TIMEOUT_MS",
                    defaultCommandTimeoutMillis,
                ),
            )

        private fun parseInt(environment: Map<String, String>, name: String, default: Int): Int =
            environment[name]?.takeIf { it.isNotEmpty() }?.toIntOrNull()
                ?: if (name in environment) error("$name must be an integer") else default

        private fun parseLong(environment: Map<String, String>, name: String, default: Long): Long =
            environment[name]?.takeIf { it.isNotEmpty() }?.toLongOrNull()
                ?: if (name in environment) error("$name must be an integer") else default
    }
}

class EngineWorkerCommandWatchdog(
    private val commandTimeoutMillis: Long,
    private val terminateProcess: (Int) -> Unit,
) : AutoCloseable {
    private val scheduler =
        ScheduledThreadPoolExecutor(1) { operation ->
            Thread(operation, "authoritative-worker-command-watchdog").apply {
                isDaemon = true
            }
        }.apply {
            removeOnCancelPolicy = true
            executeExistingDelayedTasksAfterShutdownPolicy = false
        }

    fun arm(): ScheduledFuture<*> =
        scheduler.schedule(
            { terminateProcess(timeoutExitCode) },
            commandTimeoutMillis,
            TimeUnit.MILLISECONDS,
        )

    override fun close() {
        scheduler.shutdownNow()
    }

    companion object {
        const val timeoutExitCode = 124
    }
}
