package com.example.mde

import android.content.Context
import java.util.concurrent.atomic.AtomicLong

/**
 * Sicheres Ablaufprotokoll für Versionsprüfung, OTA-Download und Kerberos/SMB.
 *
 * Die Einträge landen über [TcpLogHelper] neben den TCP-Kommunikationslogs.
 * Aufrufer dürfen ausschließlich explizit ausgewählte Metadaten übergeben.
 * Zugangsdaten in Einträgen und Fehlermeldungen werden über [secrets] redigiert.
 */
internal object OtaDiagnosticLog {
    /** Kurze, direkt lesbare Datei mit ausschließlich dem neuesten Ergebnis. */
    internal const val LOG_COMMAND = "OTA_Status"

    /** Vollständiger technischer Ablauf für eine tiefergehende Analyse. */
    internal const val DETAIL_LOG_COMMAND = "OTA_Details"

    private val operationCounter = AtomicLong()
    private val currentOperationId = ThreadLocal<String?>()
    private val currentSecrets = ThreadLocal<List<String>?>()
    private val currentSummaryWritten = ThreadLocal<Boolean?>()

    /** Liefert alle üblichen Schreibweisen eines Benutzernamens plus Passwort. */
    fun credentialSecrets(username: String, password: String): List<String> {
        val trimmedUsername = username.trim()
        val normalizedUsername = trimmedUsername
            .substringAfterLast('\\')
            .substringBefore('@')
            .trim()
        return listOf(password, username, trimmedUsername, normalizedUsername)
            .filter(String::isNotEmpty)
            .distinct()
    }

    fun <T> operation(
        context: Context,
        name: String,
        secrets: Collection<String> = emptyList(),
        block: () -> T
    ): T {
        val inheritedId = currentOperationId.get()
        val inheritedSecrets = currentSecrets.get().orEmpty()
        val isRootOperation = inheritedId == null
        val activeSecrets = (inheritedSecrets + secrets)
            .filter(String::isNotEmpty)
            .distinct()
        if (isRootOperation) {
            currentOperationId.set(newOperationId())
            currentSummaryWritten.set(false)
        }
        currentSecrets.set(activeSecrets)
        val startedNanos = System.nanoTime()
        event(context, name, "Start")

        return try {
            val result = block()
            event(context, name, "Erfolgreich beendet (${elapsedMillis(startedNanos)} ms)")
            result
        } catch (error: Throwable) {
            val failureMessage = "Fehlgeschlagen nach ${elapsedMillis(startedNanos)} ms"
            if (isRootOperation) {
                error(
                    context = context,
                    stage = name,
                    message = failureMessage,
                    error = error,
                    secrets = secrets
                )
            } else {
                event(
                    context = context,
                    stage = name,
                    message = "$failureMessage; ${error.javaClass.simpleName}: " +
                        (error.message ?: "ohne Fehlermeldung"),
                    secrets = secrets
                )
            }
            if (isRootOperation && currentSummaryWritten.get() != true) {
                summary(
                    context = context,
                    level = SummaryLevel.ERROR,
                    title = "OTA FEHLER",
                    lines = listOf(
                        "STELLE: $name",
                        "PROBLEM: ${error.message ?: error.javaClass.simpleName}"
                    ),
                    secrets = secrets
                )
            }
            throw error
        } finally {
            if (isRootOperation) {
                currentOperationId.remove()
                currentSecrets.remove()
                currentSummaryWritten.remove()
            } else {
                currentOperationId.set(inheritedId)
                currentSecrets.set(inheritedSecrets)
            }
        }
    }

    fun event(
        context: Context,
        stage: String,
        message: String,
        secrets: Collection<String> = emptyList()
    ) {
        val safeMessage = sanitizeLine(redact(message, combinedSecrets(secrets)))
        TcpLogHelper.logEvent(context, DETAIL_LOG_COMMAND, entry(stage, safeMessage))
    }

    fun warning(
        context: Context,
        stage: String,
        message: String,
        error: Throwable? = null,
        secrets: Collection<String> = emptyList()
    ) {
        val activeSecrets = combinedSecrets(secrets)
        val details = buildString {
            append(sanitizeLine(redact(message, activeSecrets)))
            if (error != null) {
                append('\n')
                append(formatThrowable(error, activeSecrets))
            }
        }
        TcpLogHelper.logWarning(context, DETAIL_LOG_COMMAND, entry(stage, details))
    }

    fun error(
        context: Context,
        stage: String,
        message: String,
        error: Throwable,
        secrets: Collection<String> = emptyList()
    ) {
        val activeSecrets = combinedSecrets(secrets)
        val details = buildString {
            append(sanitizeLine(redact(message, activeSecrets)))
            append('\n')
            append(formatThrowable(error, activeSecrets))
        }
        TcpLogHelper.logError(context, DETAIL_LOG_COMMAND, entry(stage, details))
    }

    /**
     * Schreibt genau einen kompakten Ergebnisblock ohne Stacktrace in
     * `OTA_Status.txt` und ersetzt dabei den vorherigen Inhalt.
     */
    fun summary(
        context: Context,
        level: SummaryLevel,
        title: String,
        lines: List<String>,
        secrets: Collection<String> = emptyList()
    ) {
        val activeSecrets = combinedSecrets(secrets)
        val safeTitle = sanitizeLine(redact(title, activeSecrets))
        val safeLines = lines
            .asSequence()
            .filter(String::isNotBlank)
            .take(MAX_SUMMARY_LINES)
            .map { line -> sanitizeLine(redact(line, activeSecrets)) }
            .toList()
        val text = buildString {
            append(safeTitle)
            safeLines.forEach { line ->
                append('\n')
                append(line)
            }
        }
        val type = when (level) {
            SummaryLevel.SUCCESS -> "OK"
            SummaryLevel.ATTENTION -> "HINWEIS"
            SummaryLevel.ERROR -> "FEHLER"
        }
        val written = TcpLogHelper.writeLatestStatus(context, LOG_COMMAND, type, text)
        if (written && currentOperationId.get() != null) {
            currentSummaryWritten.set(true)
        }
    }

    private fun entry(stage: String, details: String): String = buildString {
        append("Vorgang: ")
        append(currentOperationId.get() ?: "ohne-ID")
        append('\n')
        append("Thread: ")
        append(sanitizeLine(Thread.currentThread().name))
        append('\n')
        append("Stufe: ")
        append(sanitizeLine(stage))
        append('\n')
        append(details.take(MAX_ENTRY_CHARACTERS))
        if (details.length > MAX_ENTRY_CHARACTERS) {
            append("\n<Diagnoseeintrag gekürzt>")
        }
    }

    private fun formatThrowable(error: Throwable, secrets: Collection<String>): String {
        val redacted = sanitizeSensitiveExceptionValues(
            redact(error.stackTraceToString(), secrets)
        )
        return redacted.lineSequence()
            .joinToString("\n") { line -> "| ${sanitizeLine(line)}" }
            .let { text ->
                if (text.length <= MAX_STACK_TRACE_CHARACTERS) {
                    text
                } else {
                    text.take(MAX_STACK_TRACE_CHARACTERS) + "\n| <Stacktrace gekürzt>"
                }
            }
    }

    private fun sanitizeSensitiveExceptionValues(text: String): String {
        val assignmentsRedacted = SENSITIVE_ASSIGNMENT_REGEX.replace(text) { match ->
            "${match.groupValues[1]}=<redacted>"
        }
        val hexRedacted = LONG_HEX_VALUE_REGEX.replace(assignmentsRedacted, "<redacted-hex>")
        return LONG_TOKEN_REGEX.replace(hexRedacted, "<redacted-token>")
    }

    private fun redact(text: String, secrets: Collection<String>): String {
        var result = text
        secrets.asSequence()
            .filter(String::isNotEmpty)
            .distinct()
            .sortedByDescending(String::length)
            .forEach { secret -> result = result.replace(secret, "<redacted>") }
        return result
    }

    private fun combinedSecrets(secrets: Collection<String>): List<String> =
        (currentSecrets.get().orEmpty() + secrets)
            .filter(String::isNotEmpty)
            .distinct()

    private fun sanitizeLine(text: String): String = buildString(text.length) {
        text.forEach { character ->
            when {
                character == '\r' || character == '\n' -> append("\\n")
                character == '\t' -> append("\\t")
                character.isISOControl() -> append('?')
                else -> append(character)
            }
        }
    }

    private fun elapsedMillis(startedNanos: Long): Long =
        (System.nanoTime() - startedNanos).coerceAtLeast(0L) / 1_000_000L

    private fun newOperationId(): String =
        "${System.currentTimeMillis().toString(36)}-${operationCounter.incrementAndGet()}"

    private const val MAX_ENTRY_CHARACTERS = 72 * 1024
    private const val MAX_STACK_TRACE_CHARACTERS = 64 * 1024
    private const val MAX_SUMMARY_LINES = 6

    enum class SummaryLevel { SUCCESS, ATTENTION, ERROR }

    private val SENSITIVE_ASSIGNMENT_REGEX = Regex(
        """(?i)\b(password|passwd|pwd|secret|ticket|token|authorization|""" +
            """session[ _-]?key|signing[ _-]?key|encryption[ _-]?key)\b\s*[:=]\s*""" +
            """(?:"[^"]*"|'[^']*'|[^\s,;]+)"""
    )
    private val LONG_HEX_VALUE_REGEX = Regex("(?i)(?<![0-9a-f])[0-9a-f]{32,}(?![0-9a-f])")
    private val LONG_TOKEN_REGEX = Regex(
        "(?<![A-Za-z0-9+/=_-])[A-Za-z0-9+/_-]{40,}={0,2}(?![A-Za-z0-9+/=_-])"
    )
}
