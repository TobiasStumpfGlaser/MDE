package com.example.mde

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * Schreibt TCP- und Diagnoseeinträge in Textdateien auf dem externen Speicher.
 *
 * Jeder Befehl erhält eine Tagesdatei unter
 * `<external>/tcp_logs/<command>_yyyy-MM-dd.txt`. Beim App-Start werden Dateien,
 * die älter als sieben Tage sind, über [cleanupOldLogs] entfernt.
 */
object TcpLogHelper {

    private val writeLock = Any()

    /**
     * Gibt das Verzeichnis für TCP-Logs zurück und legt es an, falls es nicht existiert.
     *
     * @param context Android-Kontext für den Zugriff auf den externen Speicher.
     * @return [File] des Log-Verzeichnisses oder `null`, wenn der externe
     * app-spezifische Speicher nicht verfügbar ist.
     */
    private fun getLogDir(context: Context): File? {
        val externalFilesDirectory = context.getExternalFilesDir(null)?.canonicalFile ?: return null
        val dir = File(externalFilesDirectory, "tcp_logs").absoluteFile
        check(dir.isDirectory || dir.mkdirs()) {
            "Log-Verzeichnis konnte nicht erstellt werden: ${dir.absolutePath}"
        }
        val canonicalDirectory = dir.canonicalFile
        check(canonicalDirectory.path == dir.path) {
            "Log-Verzeichnis darf kein symbolischer Link sein"
        }
        return canonicalDirectory
    }

    /**
     * Gibt die Log-Datei für den angegebenen Befehl zurück.
     *
     * @param context Android-Kontext.
     * @param command Name des TCP-Befehls (wird als Dateiname verwendet).
     * @return [File] der zugehörigen Log-Datei oder `null`, wenn der Speicher
     * nicht verfügbar ist.
     */
    private fun getLogFile(context: Context, command: String, now: Date): File? {
        val date = SimpleDateFormat("yyyy-MM-dd", Locale.ROOT).format(now)
        return getLogDir(context)?.let { directory ->
            val expectedFile = File(directory, "${safeFileName(command)}_$date.txt").absoluteFile
            val canonicalFile = expectedFile.canonicalFile
            check(canonicalFile.path == expectedFile.path && canonicalFile.parentFile == directory) {
                "Log-Datei darf kein symbolischer Link sein"
            }
            canonicalFile
        }
    }

    private fun getFixedLogFile(context: Context, command: String): File? =
        getLogDir(context)?.let { directory ->
            val expectedFile = File(directory, "${safeFileName(command)}.txt").absoluteFile
            val canonicalFile = expectedFile.canonicalFile
            check(canonicalFile.path == expectedFile.path && canonicalFile.parentFile == directory) {
                "Statusdatei darf kein symbolischer Link sein"
            }
            canonicalFile
        }

    fun cleanupOldLogs(context: Context) {
        try {
            val dir = getLogDir(context) ?: return
            if (!dir.exists()) return

            val format = SimpleDateFormat("yyyy-MM-dd", Locale.ROOT).apply {
                isLenient = false
            }
            val cutoff = Calendar.getInstance().apply {
                set(Calendar.HOUR_OF_DAY, 0)
                set(Calendar.MINUTE, 0)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
                add(Calendar.DAY_OF_YEAR, -LOG_RETENTION_DAYS)
            }.time

            dir.listFiles()?.forEach { file ->
                try {
                    val name = file.nameWithoutExtension

                    // erwartet: command_yyyy-MM-dd
                    val datePart = name.substringAfterLast("_", "")

                    val fileDate = format.parse(datePart) ?: return@forEach

                if (fileDate.before(cutoff)) {
                        file.delete()
                    }

                } catch (_: Exception) {
                    // ignorieren
                }
            }

        } catch (e: Exception) {
            e.printStackTrace()
        }
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

    /** Schreibt einen allgemeinen Diagnoseeintrag in dasselbe Verzeichnis. */
    fun logEvent(context: Context, command: String, text: String) {
        write(context, command, "EVENT", text)
    }

    /** Schreibt eine Diagnosewarnung in dasselbe Verzeichnis. */
    fun logWarning(context: Context, command: String, text: String) {
        write(context, command, "WARNING", text)
    }

    /** Schreibt einen bereits bereinigten Diagnosetext als Fehler. */
    fun logError(context: Context, command: String, text: String) {
        write(context, command, "ERROR", text)
    }

    /**
     * Ersetzt eine feste Statusdatei, sodass sie immer nur den neuesten,
     * kompakten Befund enthält und nicht durchsucht werden muss.
     */
    fun writeLatestStatus(
        context: Context,
        command: String,
        type: String,
        text: String
    ): Boolean = try {
            synchronized(writeLock) {
                val now = Date()
                val file = getFixedLogFile(context, command) ?: return false
                val timestamp = SimpleDateFormat(
                    "yyyy-MM-dd HH:mm:ss.SSS",
                    Locale.ROOT
                ).format(now)
                val entry = buildString {
                    append('[')
                    append(timestamp)
                    append("] ")
                    append(type)
                    append('\n')
                    append(text)
                    append('\n')
                }
                file.writeText(entry)
            }
            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }

    private fun write(context: Context, command: String, type: String, text: String) {
        try {
            synchronized(writeLock) {
                val now = Date()
                val file = getLogFile(context, command, now) ?: return
                val timestamp = SimpleDateFormat(
                    "yyyy-MM-dd HH:mm:ss.SSS",
                    Locale.ROOT
                ).format(now)

                val entry = """
                    [$timestamp] $type
                    ----------------------------------------
                    $text
                    ----------------------------------------

                """.trimIndent()

                file.appendText(entry + "\n")
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun safeFileName(command: String): String {
        val sanitized = command.trim()
            .map { character ->
                if (character.isLetterOrDigit() || character == '-' || character == '_') {
                    character
                } else {
                    '_'
                }
            }
            .joinToString("")
            .take(MAX_FILE_NAME_LENGTH)
        return sanitized.ifEmpty { "log" }
    }

    /** Löscht alle TCP- und Diagnoselogs. */
    fun clearLogs(context: Context) {
        try {
            val dir = getLogDir(context) ?: return
            if (dir.exists() && dir.isDirectory) {
                dir.listFiles()?.forEach { it.delete() }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private const val MAX_FILE_NAME_LENGTH = 80
    private const val LOG_RETENTION_DAYS = 7
}
