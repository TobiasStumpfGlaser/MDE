package com.example.mde

import android.content.Context
import java.io.*
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException

object TcpClient {

    private var socket: Socket? = null
    private var writer: BufferedWriter? = null
    private var reader: BufferedReader? = null

    // Prüft, ob Verbindung offen ist
    private fun isConnected(): Boolean {
        return socket?.isConnected == true && socket?.isClosed == false
    }

    // Verbindung aufbauen, falls noch nicht offen
    private fun ensureConnection(settings: AppSettings) {
        if (isConnected()) return

        socket?.close()
        socket = Socket()
        socket!!.connect(InetSocketAddress(settings.serverIp, settings.serverPort), settings.timeoutS * 1000)
        socket!!.soTimeout = settings.timeoutS * 1000
        reader = BufferedReader(InputStreamReader(socket!!.getInputStream(), Charsets.ISO_8859_1))
        writer = BufferedWriter(OutputStreamWriter(socket!!.getOutputStream(), Charsets.ISO_8859_1))
    }

    // Universelle Funktion zum Senden von Befehlen
    fun sendCommand(
        context: Context,
        settings: AppSettings,
        command: String,
        request: String,
        endTag: String,
    ): String {
        try {
            ensureConnection(settings) // Verbindung prüfen/aufbauen

            TcpLogHelper.logRequest(context, command, request)

            // Request senden
            writer!!.write(request + "\n")
            writer!!.flush()

            // Response lesen
            val response = StringBuilder()
            val buffer = ByteArray(16 * 1024) // 16 KB Puffer
            var read: Int
            while (true) {
                read = try {
                    socket!!.getInputStream().read(buffer)
                } catch (e: SocketTimeoutException) {
                    break
                }
                if (read == -1) break

                val chunk = String(buffer, 0, read, Charsets.ISO_8859_1)
                response.append(chunk)

                if (response.contains(endTag)) break
            }
            val StringResponse = response.toString()
            TcpLogHelper.logResponse(context, command, StringResponse)
            return StringResponse

        } catch (e: Exception) {
            closeConnection()
            ensureConnection(settings)
            e.printStackTrace()
            throw e
        }
    }

    // Verbindung schließen (z.B. beim Logout)
    fun closeConnection() {
        try {
            socket?.close()
        } catch (_: Exception) {}
        socket = null
        writer = null
        reader = null
    }
}