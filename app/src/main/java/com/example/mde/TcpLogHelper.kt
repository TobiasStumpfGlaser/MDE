package com.example.mde

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Schreibt TCP-Request- und Response-Daten in Textdateien auf dem externen Speicher.
 *
 * Jeder Befehl erhält eine eigene Logdatei unter `<external>/tcp_logs/<command>.txt`.
 * Die Logs werden beim App-Start über [clearLogs] geleert.
 */
object TcpLogHelper {

    private val dateFormat =
        SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.getDefault())

    /**
     * Gibt das Verzeichnis für TCP-Logs zurück und legt es an, falls es nicht existiert.
     *
     * @param context Android-Kontext für den Zugriff auf den externen Speicher.
     * @return [File] des Log-Verzeichnisses.
     */
    private fun getLogDir(context: Context): File {
        val dir = File(context.getExternalFilesDir(null), "tcp_logs")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    /**
     * Gibt die Log-Datei für den angegebenen Befehl zurück.
     *
     * @param context Android-Kontext.
     * @param command Name des TCP-Befehls (wird als Dateiname verwendet).
     * @return [File] der zugehörigen Log-Datei.
     */
    private fun getLogFile(context: Context, command: String): File {
        return File(getLogDir(context), "$command.txt")
    }

    /**
     * Schreibt eine REQUEST-Zeile in die Log-Datei des angegebenen Befehls.
     *
     * @param context Android-Kontext.
     * @param command Name des TCP-Befehls.
     * @param text Inhalt der Anfrage.
     */
    fun logRequest(context: Context, command: String, text: String) {
        write(context, command, "REQUEST", text)
    }

    /**
     * Schreibt eine RESPONSE-Zeile in die Log-Datei des angegebenen Befehls.
     *
     * @param context Android-Kontext.
     * @param command Name des TCP-Befehls.
     * @param text Inhalt der Antwort.
     */
    fun logResponse(context: Context, command: String, text: String) {
        write(context, command, "RESPONSE", text)
    }

    private fun write(context: Context, command: String, type: String, text: String) {
        try {
            val file = getLogFile(context, command)
            val timestamp = dateFormat.format(Date())

            val entry = """
                [$timestamp] $type
                ----------------------------------------
                $text
                ----------------------------------------

            """.trimIndent()

            file.appendText(entry + "\n")
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    /** Löscht alle TCP-Logs beim App-Start. */
    fun clearLogs(context: Context) {
        try {
            val dir = getLogDir(context)
            if (dir.exists() && dir.isDirectory) {
                dir.listFiles()?.forEach { it.delete() }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}