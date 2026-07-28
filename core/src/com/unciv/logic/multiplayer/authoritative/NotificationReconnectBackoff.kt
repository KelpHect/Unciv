package com.unciv.logic.multiplayer.authoritative

import kotlin.math.roundToLong
import kotlin.random.Random
import org.jetbrains.annotations.VisibleForTesting

/**
 * Produces bounded equal-jitter delays for API-v3 notification reconnects.
 *
 * This type is public only so the separate JVM test module can inject a
 * deterministic fraction. Gameplay code should use the default source.
 */
@VisibleForTesting
class NotificationReconnectBackoff(
    private val randomFraction: () -> Double = { Random.nextDouble() },
) {
    private var retryCeilingMillis = INITIAL_RETRY_MILLIS

    /** Returns the next delay and advances the capped exponential ceiling. */
    fun nextDelayMillis(): Long {
        val half = retryCeilingMillis / 2
        val boundedFraction = randomFraction().coerceIn(0.0, 1.0)
        val delay = half + (retryCeilingMillis - half) * boundedFraction
        retryCeilingMillis = (retryCeilingMillis * 2).coerceAtMost(MAX_RETRY_MILLIS)
        return delay.roundToLong().coerceIn(half, MAX_RETRY_MILLIS)
    }

    /** Restores the initial ceiling after a successful socket connection. */
    fun reset() {
        retryCeilingMillis = INITIAL_RETRY_MILLIS
    }

    private companion object {
        const val INITIAL_RETRY_MILLIS = 250L
        const val MAX_RETRY_MILLIS = 10_000L
    }
}
