package com.unciv.app.server.authoritative

import java.nio.ByteBuffer
import java.security.MessageDigest
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** Mutual service identity for the private worker framing protocol.
 *
 * The shared secret is deployment configuration, never part of JSON or a
 * canonical game revision. Both request and response frames are authenticated.
 */
enum class EngineWorkerFrameDirection { Request, Response }

class EngineWorkerAuthentication private constructor(private val key: ByteArray) {
    fun sign(
        direction: EngineWorkerFrameDirection,
        nonce: ByteArray,
        payload: ByteArray,
    ): ByteArray {
        require(nonce.size == nonceBytes) { "Invalid worker authentication nonce" }
        val mac = Mac.getInstance(ALGORITHM)
        mac.init(SecretKeySpec(key, ALGORITHM))
        mac.update(
            when (direction) {
                EngineWorkerFrameDirection.Request -> REQUEST_DOMAIN
                EngineWorkerFrameDirection.Response -> RESPONSE_DOMAIN
            },
        )
        mac.update(nonce)
        mac.update(ByteBuffer.allocate(Int.SIZE_BYTES).putInt(payload.size).array())
        return mac.doFinal(payload)
    }

    fun verify(
        direction: EngineWorkerFrameDirection,
        nonce: ByteArray,
        payload: ByteArray,
        suppliedTag: ByteArray,
    ) {
        require(suppliedTag.size == tagBytes) { "Invalid worker authentication tag" }
        require(MessageDigest.isEqual(sign(direction, nonce, payload), suppliedTag)) {
            "Worker service identity verification failed"
        }
    }

    companion object {
        const val tagBytes = 32
        const val nonceBytes = 16
        private const val keyBytes = 32
        private const val ALGORITHM = "HmacSHA256"
        private val REQUEST_DOMAIN = "UNCIV-WORKER-V2\u0000request\u0000".encodeToByteArray()
        private val RESPONSE_DOMAIN = "UNCIV-WORKER-V2\u0000response\u0000".encodeToByteArray()

        fun fromHex(value: String): EngineWorkerAuthentication {
            require(value.length == keyBytes * 2) {
                "Worker secret must be exactly 32 bytes encoded as hexadecimal"
            }
            val bytes = ByteArray(keyBytes) { index ->
                value.substring(index * 2, index * 2 + 2).toIntOrNull(16)?.toByte()
                    ?: throw IllegalArgumentException("Worker secret is not hexadecimal")
            }
            return EngineWorkerAuthentication(bytes)
        }
    }
}
