package com.example.mde

import android.content.Context
import android.util.Log
import java.io.*
import java.net.InetSocketAddress
import java.net.Socket

object TcpClient {

    fun sendCommand(
        context: Context,
        settings: AppSettings,
        command: String,
        request: String,
        endTag: String? = null
    ): String {

        val response = StringBuilder()

        try {
            Socket().use { socket ->

                socket.connect(
                    InetSocketAddress(settings.serverIp, settings.serverPort),
                    settings.timeoutS * 1000
                )

                socket.soTimeout = settings.timeoutS * 1000

                val writer = PrintWriter(
                    BufferedWriter(OutputStreamWriter(socket.getOutputStream())),
                    true
                )

                val input = socket.getInputStream()

                // Request senden
                writer.println(request)

                TcpLogHelper.logRequest(context, command, request)

                val buffer = ByteArray(1024)

                try {
                    while (true) {

                        val bytesRead = input.read(buffer)

                        if (bytesRead == -1) break

                        val chunk = String(buffer, 0, bytesRead)

                        response.append(chunk)

                        Log.d("TCP", chunk)

                        TcpLogHelper.logResponse(context, command, chunk)

                        // EndTag erkannt → fertig
                        if (endTag != null && response.contains(endTag)) {
                            break
                        }
                    }

                } catch (e: java.net.SocketTimeoutException) {

                    // Timeout = Server sendet nichts mehr
                    Log.d("TCP", "SocketTimeout erreicht → Ende Response")
                }
            }

        } catch (e: Exception) {
            Log.e("TCP", "TCP Fehler", e)
        }

        return response.toString()
    }
}