package com.unciv.app.server

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class LegacyWriteControlTests {
    @Test
    fun enabled_control_records_only_completed_writes() {
        val control = LegacyWriteControl(writesEnabled = true)

        assertFalse(control.rejectIfDisabled(LegacyWriteKind.File))
        control.recordAccepted(LegacyWriteKind.File)
        control.recordAccepted(LegacyWriteKind.Authentication)

        assertEquals(
            LegacyWriteTelemetry(
                writesEnabled = true,
                acceptedFileWrites = 1,
                rejectedFileWrites = 0,
                acceptedAuthenticationWrites = 1,
                rejectedAuthenticationWrites = 0,
            ),
            control.snapshot(),
        )
    }

    @Test
    fun disabled_control_rejects_and_counts_each_write_class() {
        val control = LegacyWriteControl(writesEnabled = false)

        assertTrue(control.rejectIfDisabled(LegacyWriteKind.File))
        assertTrue(control.rejectIfDisabled(LegacyWriteKind.Authentication))
        assertThrows(IllegalStateException::class.java) {
            control.recordAccepted(LegacyWriteKind.File)
        }

        assertEquals(
            LegacyWriteTelemetry(
                writesEnabled = false,
                acceptedFileWrites = 0,
                rejectedFileWrites = 1,
                acceptedAuthenticationWrites = 0,
                rejectedAuthenticationWrites = 1,
            ),
            control.snapshot(),
        )
    }
}
