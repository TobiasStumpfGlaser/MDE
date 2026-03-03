package com.example.mde

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object TcpLogHelper {
    private val dateFormat =
        SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.getDefault())

    private fun getLogFile(context: Context, command: String): File {
        val dir = File(
            context.getExternalFilesDir(null),
            "tcp_logs"
        )
        //val dir = File(context.filesDir, "tcp_logs")
        if (!dir.exists()) dir.mkdirs()

        return File(dir, "${command}.txt")
    }

    fun logRequest(context: Context, command: String, text: String) {
        write(context, command, "REQUEST", text)
    }

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
}