package com.example.mde

import android.content.Context
import java.io.BufferedWriter
import java.io.OutputStreamWriter
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException

object TcpClient {

    @Synchronized
    private fun createConnection(settings: AppSettings): Socket {
        val socket = Socket()
        socket.keepAlive = true
        socket.connect(
            InetSocketAddress(settings.serverIp, settings.serverPort),
            settings.timeoutS * 1000
        )
        socket.soTimeout = settings.timeoutS * 1000
        return socket
    }

    /**
     * Entfernt genau 1 Tabellenkopf-Zeile:
     * - Wir lassen Start-/End-Tags im Response drin.
     * - Wir entfernen die erste Nicht-Tag-Zeile NACH dem Start-Tag (Tabellenkopf).
     *
     * Annahme: Response-Format ist:
     *   {Command}
     *   <TABELLENKOPF>
     *   <DATENZEILE 1>
     *   ...
     *   {/Command}
     */
    private fun removeTableHeaderLine(raw: String, endTag: String): String {
        val lines = raw.replace("\r", "").split("\n")

        val out = ArrayList<String>(lines.size)
        var removedHeader = false
        var started = false

        for (lineRaw in lines) {
            val line = lineRaw // bewusst nicht trimmen, Format beibehalten

            val trimmed = line.trim()

            // Start-Tag erkennen: "{...}" aber nicht das EndTag
            if (!started && trimmed.startsWith("{") && trimmed.endsWith("}") && trimmed != endTag.trim()) {
                started = true
                out.add(line)
                continue
            }

            // End-Tag -> ab hier normal weiter
            if (trimmed == endTag.trim()) {
                out.add(line)
                // optional: reset, falls mehrere Blöcke vorkommen sollten
                started = false
                removedHeader = false
                continue
            }

            // Erste Zeile nach Start-Tag entfernen (Tabellenkopf)
            if (started && !removedHeader) {
                removedHeader = true
                continue
            }

            out.add(line)
        }

        return out.joinToString("\n")
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

        var socket: Socket? = null
        var writer: BufferedWriter? = null

        try {
            socket = createConnection(settings)

            writer = BufferedWriter(
                OutputStreamWriter(socket.getOutputStream(), Charsets.ISO_8859_1)
            )

            TcpLogHelper.logRequest(context, command, request)

            writer.write(request + "\n")
            writer.flush()

            val response = StringBuilder()
            val buffer = ByteArray(16 * 1024)
            val input = socket.getInputStream()
            var firstByteReceived = false

            while (true) {
                val read = try {
                    socket.soTimeout = if (!firstByteReceived) initialTimeout else readTimeout
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

            // NEU: immer Tabellenkopf entfernen, AUSSER bei SetBuchung
            val shouldStripHeader = !command.contains("SetBuchung", ignoreCase = true)

            return if (shouldStripHeader) {
                removeTableHeaderLine(stringResponse, endTag)
            } else {
                stringResponse
            }

        } catch (e: Exception) {
            e.printStackTrace()
            throw e
        } finally {
            try {
                writer?.close()
            } catch (_: Exception) {
            }

            try {
                socket?.close()
            } catch (_: Exception) {
            }
        }
    }
}