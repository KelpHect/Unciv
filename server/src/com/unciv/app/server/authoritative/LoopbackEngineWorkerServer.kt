package com.unciv.app.server.authoritative

import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket

class LoopbackEngineWorkerServer(
    private val worker: AuthoritativeEngineWorker = AuthoritativeEngineWorker(),
    private val authentication: EngineWorkerAuthentication,
    private val runtimeLimits: EngineWorkerRuntimeLimits = EngineWorkerRuntimeLimits.fromEnvironment(),
    terminateProcess: (Int) -> Unit = { exitCode -> Runtime.getRuntime().halt(exitCode) },
) {
    private val commandWatchdog =
        EngineWorkerCommandWatchdog(runtimeLimits.commandTimeoutMillis, terminateProcess)

    fun serve(port: Int) =
        commandWatchdog.use {
            ServerSocket(port, backlog, InetAddress.getLoopbackAddress()).use { server ->
                while (true) {
                    server.accept().use { socket ->
                        socket.soTimeout = runtimeLimits.socketTimeoutMillis
                        // A readiness probe or malformed local peer must not kill
                        // the process. Invalid frames are dropped without a response.
                        runCatching { serveConnection(socket) }
                    }
                }
            }
        }

    private fun serveConnection(socket: Socket) {
        val input = DataInputStream(socket.getInputStream())
        val output = DataOutputStream(socket.getOutputStream())
        val frameSize = input.readInt()
        require(frameSize in 1..EngineWorkerProtocol.maxFrameBytes) { "Invalid frame length" }
        val nonce = input.readExact(EngineWorkerAuthentication.nonceBytes, "authentication nonce")
        require(nonce.size == EngineWorkerAuthentication.nonceBytes) {
            "Incomplete worker authentication nonce"
        }
        val requestTag = input.readExact(EngineWorkerAuthentication.tagBytes, "authentication tag")
        require(requestTag.size == EngineWorkerAuthentication.tagBytes) {
            "Incomplete worker authentication tag"
        }
        val requestPayload = input.readExact(frameSize, "request payload")
        authentication.verify(
            EngineWorkerFrameDirection.Request,
            nonce,
            requestPayload,
            requestTag,
        )
        val request = EngineWorkerProtocol.decodeRequest(frameSize, requestPayload)
        val expiry = commandWatchdog.arm()
        try {
            val response = EngineWorkerProtocol.json
                .encodeToString(WorkerResponse.serializer(), worker.execute(request))
                .encodeToByteArray()
            EngineWorkerProtocol.validateJsonFrame(response)
            output.writeInt(response.size)
            output.write(nonce)
            output.write(
                authentication.sign(
                    EngineWorkerFrameDirection.Response,
                    nonce,
                    response,
                ),
            )
            output.write(response)
            output.flush()
        } finally {
            expiry.cancel(false)
        }
    }

    private companion object {
        const val backlog = 50

        fun DataInputStream.readExact(size: Int, name: String): ByteArray {
            val bytes = ByteArray(size)
            try {
                readFully(bytes)
            } catch (error: java.io.EOFException) {
                throw IllegalArgumentException("Incomplete worker $name", error)
            }
            return bytes
        }
    }
}
