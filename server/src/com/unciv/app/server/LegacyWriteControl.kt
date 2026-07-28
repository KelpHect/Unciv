package com.unciv.app.server

import kotlinx.serialization.Serializable
import java.util.concurrent.atomic.AtomicLong

internal enum class LegacyWriteKind {
    File,
    Authentication,
}

@Serializable
internal data class LegacyWriteTelemetry(
    val writesEnabled: Boolean,
    val acceptedFileWrites: Long,
    val rejectedFileWrites: Long,
    val acceptedAuthenticationWrites: Long,
    val rejectedAuthenticationWrites: Long,
)

/**
 * Process-local, content-free retirement telemetry for the legacy file service.
 *
 * Counters deliberately contain no identity, filename, address, credential, or
 * save data. Fleet operators aggregate them externally before disabling writes.
 */
internal class LegacyWriteControl(
    val writesEnabled: Boolean,
) {
    private val acceptedFileWrites = AtomicLong()
    private val rejectedFileWrites = AtomicLong()
    private val acceptedAuthenticationWrites = AtomicLong()
    private val rejectedAuthenticationWrites = AtomicLong()

    fun rejectIfDisabled(kind: LegacyWriteKind): Boolean {
        if (writesEnabled) return false
        counter(kind, accepted = false).incrementAndGet()
        return true
    }

    fun recordAccepted(kind: LegacyWriteKind) {
        check(writesEnabled) { "disabled legacy writes cannot be accepted" }
        counter(kind, accepted = true).incrementAndGet()
    }

    fun snapshot() = LegacyWriteTelemetry(
        writesEnabled = writesEnabled,
        acceptedFileWrites = acceptedFileWrites.get(),
        rejectedFileWrites = rejectedFileWrites.get(),
        acceptedAuthenticationWrites = acceptedAuthenticationWrites.get(),
        rejectedAuthenticationWrites = rejectedAuthenticationWrites.get(),
    )

    private fun counter(kind: LegacyWriteKind, accepted: Boolean) = when (kind) {
        LegacyWriteKind.File ->
            if (accepted) acceptedFileWrites else rejectedFileWrites
        LegacyWriteKind.Authentication ->
            if (accepted) acceptedAuthenticationWrites else rejectedAuthenticationWrites
    }
}
