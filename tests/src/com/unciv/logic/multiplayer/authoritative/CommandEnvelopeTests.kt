package com.unciv.logic.multiplayer.authoritative

import org.junit.Assert
import org.junit.Test

class CommandEnvelopeTests {
    @Test
    fun acceptsAVersionedIntentWithoutClientActorIdentity() {
        val envelope = CommandEnvelope(
            protocolVersion = CommandEnvelope.CURRENT_PROTOCOL_VERSION,
            gameId = "game-123",
            commandId = "command-456",
            expectedRevision = 7,
            command = GameCommand.EndTurn,
        )

        Assert.assertEquals(CommandEnvelopeValidation.Valid, envelope.validate())
    }

    @Test
    fun rejectsUnsupportedProtocolAndInvalidRevision() {
        val oldProtocol = CommandEnvelope(
            protocolVersion = 2,
            gameId = "game-123",
            commandId = "command-456",
            expectedRevision = 0,
            command = GameCommand.EndTurn,
        )
        val negativeRevision = oldProtocol.copy(
            protocolVersion = CommandEnvelope.CURRENT_PROTOCOL_VERSION,
            expectedRevision = -1,
        )

        Assert.assertEquals(CommandEnvelopeValidation.UnsupportedProtocol(2), oldProtocol.validate())
        Assert.assertEquals(
            CommandEnvelopeValidation.Invalid("expectedRevision must not be negative"),
            negativeRevision.validate(),
        )
    }
}
