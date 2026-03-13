package com.example.mde

import android.content.Context
import java.io.*
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException

object TcpClient {

    private var socket: Socket? = null
    private var writer: BufferedWriter? = null

    private fun isConnected(): Boolean {

        val s = socket ?: return false

        return s.isConnected &&
                !s.isClosed &&
                !s.isInputShutdown &&
                !s.isOutputShutdown
    }

    private fun ensureConnection(settings: AppSettings) {

        if (isConnected()) return

        try {
            socket?.close()
        } catch (_: Exception) {}

        socket = Socket()

        socket!!.connect(
            InetSocketAddress(settings.serverIp, settings.serverPort),
            settings.timeoutS * 1000
        )

        socket!!.soTimeout = settings.timeoutS * 1000

        writer = BufferedWriter(
            OutputStreamWriter(socket!!.getOutputStream(), Charsets.ISO_8859_1)
        )
    }

    @Synchronized
    fun sendCommand(
        context: Context,
        settings: AppSettings,
        command: String,
        request: String,
        endTag: String,
    ): String {

        val initialTimeout = settings.timeoutS * 1000 / 5
        val readTimeout = settings.timeoutS * 1000 / 2

        try {

            ensureConnection(settings)

            TcpLogHelper.logRequest(context, command, request)

            writer!!.write(request + "\n")
            writer!!.flush()

            val response = StringBuilder()
            val buffer = ByteArray(16 * 1024)

            val input = socket!!.getInputStream()

            var firstByteReceived = false

            while (true) {

                val read = try {

                    socket!!.soTimeout =
                        if (!firstByteReceived) initialTimeout else readTimeout

                    input.read(buffer)

                } catch (e: SocketTimeoutException) {

                    if (!firstByteReceived) {
                        throw SocketTimeoutException("Initial timeout – keine Serverantwort")
                    } else {
                        throw SocketTimeoutException("Read timeout – EndTag nicht erreicht")
                    }
                }

                if (read == -1) break

                firstByteReceived = true

                val chunk = String(buffer, 0, read, Charsets.ISO_8859_1)

                response.append(chunk)

                if (response.contains(endTag)) break
            }

            val stringResponse = response.toString()

            TcpLogHelper.logResponse(context, command, stringResponse)

            return stringResponse

        } catch (e: Exception) {

            closeConnection()

            e.printStackTrace()

            throw e
        }
    }

    fun closeConnection() {

        try {
            socket?.close()
        } catch (_: Exception) {}

        socket = null
        writer = null
    }
}