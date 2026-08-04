package com.example.mde

import android.app.Application
import android.content.Context
import android.widget.LinearLayout
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

// ---------------------------------------------------------------------------
// TcpLogHelper
// ---------------------------------------------------------------------------

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TcpLogHelperTest {

    private lateinit var context: Context
    private lateinit var logDir: File

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        logDir = File(context.getExternalFilesDir(null), "tcp_logs")
        // Sicherstellen dass Verzeichnis leer ist
        logDir.deleteRecursively()
    }

    @Test
    fun clearLogs_dirDoesNotExist_noException() {
        assertFalse(logDir.exists())
        TcpLogHelper.clearLogs(context) // darf nicht crashen
    }

    @Test
    fun clearLogs_dirWithFiles_deletesFiles() {
        logDir.mkdirs()
        File(logDir, "test1.txt").writeText("inhalt1")
        File(logDir, "test2.txt").writeText("inhalt2")
        assertEquals(2, logDir.listFiles()?.size)

        TcpLogHelper.clearLogs(context)

        assertTrue(logDir.exists())
        assertEquals(0, logDir.listFiles()?.size ?: 0)
    }

    @Test
    fun logRequest_createsFileWithContent() {
        TcpLogHelper.logRequest(context, "GetArtikel", "request-body")

        val logFile = logFile("GetArtikel")
        assertTrue(logFile.exists())
        val content = logFile.readText()
        assertTrue(content.contains("REQUEST"))
        assertTrue(content.contains("request-body"))
    }

    @Test
    fun logResponse_createsFileWithContent() {
        TcpLogHelper.logResponse(context, "GetArtikel", "response-body")

        val logFile = logFile("GetArtikel")
        assertTrue(logFile.exists())
        val content = logFile.readText()
        assertTrue(content.contains("RESPONSE"))
        assertTrue(content.contains("response-body"))
    }

    @Test
    fun logRequest_appendsMultipleEntries() {
        TcpLogHelper.logRequest(context, "GetArtikel", "erster request")
        TcpLogHelper.logRequest(context, "GetArtikel", "zweiter request")

        val content = logContent("GetArtikel")
        assertTrue(content.contains("erster request"))
        assertTrue(content.contains("zweiter request"))
    }

    @Test
    fun logRequest_differentCommands_separateFiles() {
        TcpLogHelper.logRequest(context, "GetArtikel", "a")
        TcpLogHelper.logRequest(context, "SetBuchung", "b")

        assertTrue(logFile("GetArtikel").exists())
        assertTrue(logFile("SetBuchung").exists())
    }

    @Test
    fun clearLogs_afterLogging_deletesLogFiles() {
        TcpLogHelper.logRequest(context, "GetArtikel", "test")
        assertTrue(logFile("GetArtikel").exists())

        TcpLogHelper.clearLogs(context)

        assertEquals(0, logDir.listFiles()?.size ?: 0)
    }

    @Test
    fun diagnosticEntries_useSameDirectoryAndDedicatedTypes() {
        TcpLogHelper.logEvent(context, "OTA_Details", "Ablauf gestartet")
        TcpLogHelper.logWarning(context, "OTA_Details", "Langsame Antwort")
        TcpLogHelper.logError(context, "OTA_Details", "Verbindung fehlgeschlagen")

        val content = logContent("OTA_Details")
        assertTrue(content.contains("EVENT"))
        assertTrue(content.contains("WARNING"))
        assertTrue(content.contains("ERROR"))
        assertTrue(content.contains("Ablauf gestartet"))
    }

    @Test
    fun cleanupOldLogs_keepsFullBoundaryDayAndDeletesOlderFiles() {
        logDir.mkdirs()
        val boundaryFile = datedLogFile("boundary", daysAgo = 7).apply { writeText("keep") }
        val expiredFile = datedLogFile("expired", daysAgo = 8).apply { writeText("delete") }
        val unrelatedFile = File(logDir, "readme.txt").apply { writeText("keep") }

        TcpLogHelper.cleanupOldLogs(context)

        assertTrue(boundaryFile.exists())
        assertFalse(expiredFile.exists())
        assertTrue(unrelatedFile.exists())
    }

    @Test
    fun otaDiagnosticLog_nestedOperationsReuseIdAndRedactEventText() {
        val secret = "credential-that-must-not-be-logged"
        val username = "DOMAIN\\normuser"

        OtaDiagnosticLog.operation(
            context,
            "Äußerer Vorgang",
            secrets = OtaDiagnosticLog.credentialSecrets(username, secret)
        ) {
            OtaDiagnosticLog.event(
                context,
                "Test/Äußerer Vorgang",
                "Benutzer=normuser; Wert=$secret\n[FAKE-HEADER]\tEnde"
            )
            OtaDiagnosticLog.operation(context, "Innerer Vorgang") {
                OtaDiagnosticLog.event(context, "Test/Innerer Vorgang", "Inneres Ereignis")
            }
        }
        OtaDiagnosticLog.event(context, "Test/Danach", "Außerhalb")

        val content = logContent(OtaDiagnosticLog.DETAIL_LOG_COMMAND)
        val operationIds = Regex("Vorgang: ([^\\r\\n]+)")
            .findAll(content)
            .map { match -> match.groupValues[1] }
            .toList()
        assertFalse(content.contains(secret))
        assertFalse(content.contains("normuser"))
        assertTrue(
            content.contains(
                "Benutzer=<redacted>; Wert=<redacted>\\n[FAKE-HEADER]\\tEnde"
            )
        )
        assertEquals(1, operationIds.filterNot { it == "ohne-ID" }.distinct().size)
        assertTrue(operationIds.contains("ohne-ID"))
    }

    @Test
    fun otaStatus_containsOnlyLatestCompactResult() {
        OtaDiagnosticLog.summary(
            context,
            OtaDiagnosticLog.SummaryLevel.ERROR,
            "ALTER FEHLER",
            listOf("PROBLEM: alt")
        )
        OtaDiagnosticLog.summary(
            context,
            OtaDiagnosticLog.SummaryLevel.SUCCESS,
            "OTA OK: APP IST AKTUELL",
            listOf("SERVER: 6.1 (Code 85)", "MASSNAHME: keine")
        )

        val status = File(logDir, "${OtaDiagnosticLog.LOG_COMMAND}.txt").readText()
        assertTrue(status.contains("OTA OK: APP IST AKTUELL"))
        assertTrue(status.contains("SERVER: 6.1 (Code 85)"))
        assertFalse(status.contains("ALTER FEHLER"))
        assertFalse(status.contains("Vorgang:"))
        assertFalse(status.contains("Thread:"))
        assertFalse(status.contains("at com.example"))
    }

    private fun logFile(command: String): File {
        val matches = logFiles(command)
        assertTrue("Mindestens eine Tagesdatei für $command erwartet", matches.isNotEmpty())
        return matches.maxBy(File::lastModified)
    }

    private fun logContent(command: String): String =
        logFiles(command).joinToString("\n") { file -> file.readText() }

    private fun datedLogFile(command: String, daysAgo: Int): File {
        val date = Calendar.getInstance().apply {
            add(Calendar.DAY_OF_YEAR, -daysAgo)
        }.time
        val dateText = SimpleDateFormat("yyyy-MM-dd", Locale.ROOT).format(date)
        return File(logDir, "${command}_$dateText.txt")
    }

    private fun logFiles(command: String): List<File> =
        logDir.listFiles().orEmpty().filter { file ->
            file.name.startsWith("${command}_") && file.extension == "txt"
        }
}

// ---------------------------------------------------------------------------
// LayoutScaleUtil
// ---------------------------------------------------------------------------

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LayoutScaleUtilTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
    }

    @Test
    fun applyLayoutScale_scale1_returnsSameDensity() {
        val originalDpi = context.resources.configuration.densityDpi
        val newContext = LayoutScaleUtil.applyLayoutScale(context, 1.0f)
        val newDpi = newContext.resources.configuration.densityDpi
        assertEquals(originalDpi, newDpi)
    }

    @Test
    fun applyLayoutScale_scaleAbove1_increasesDensity() {
        val originalDpi = context.resources.configuration.densityDpi
        val newContext = LayoutScaleUtil.applyLayoutScale(context, 1.2f)
        val newDpi = newContext.resources.configuration.densityDpi
        assertTrue(newDpi > originalDpi)
    }

    @Test
    fun applyLayoutScale_scaleBelow1_decreasesDensity() {
        val originalDpi = context.resources.configuration.densityDpi
        val newContext = LayoutScaleUtil.applyLayoutScale(context, 0.9f)
        val newDpi = newContext.resources.configuration.densityDpi
        assertTrue(newDpi < originalDpi)
    }

    @Test
    fun applyLayoutScale_scaleAboveMax_clampsAt130() {
        val newContext130 = LayoutScaleUtil.applyLayoutScale(context, 1.30f)
        val newContext200 = LayoutScaleUtil.applyLayoutScale(context, 2.00f)
        assertEquals(
            newContext130.resources.configuration.densityDpi,
            newContext200.resources.configuration.densityDpi
        )
    }

    @Test
    fun applyLayoutScale_scaleBelowMin_clampsAt085() {
        val newContext085 = LayoutScaleUtil.applyLayoutScale(context, 0.85f)
        val newContext010 = LayoutScaleUtil.applyLayoutScale(context, 0.10f)
        assertEquals(
            newContext085.resources.configuration.densityDpi,
            newContext010.resources.configuration.densityDpi
        )
    }

    @Test
    fun applyLayoutScale_resultDpiAtLeast120() {
        val newContext = LayoutScaleUtil.applyLayoutScale(context, 0.85f)
        assertTrue(newContext.resources.configuration.densityDpi >= 120)
    }

    @Test
    fun applyLayoutScale_returnsNewContextNotSame() {
        val newContext = LayoutScaleUtil.applyLayoutScale(context, 1.1f)
        assertNotSame(context, newContext)
    }
}

// ---------------------------------------------------------------------------
// FontScaleUtil
// ---------------------------------------------------------------------------

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class FontScaleUtilTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext<Application>()
    }

    @Test
    fun applyFontScale_textView_scalesTextSize() {
        val tv = TextView(context)
        tv.textSize = 14f // setzt in sp, gespeichert in px
        val originalPx = tv.textSize

        FontScaleUtil.applyFontScale(tv, 2.0f)

        // Nach 2x-Skalierung muss textSize größer sein
        assertTrue(tv.textSize > originalPx)
    }

    @Test
    fun applyFontScale_scale1_doesNotChangeTextSize() {
        val tv = TextView(context)
        tv.textSize = 16f
        val originalPx = tv.textSize

        FontScaleUtil.applyFontScale(tv, 1.0f)

        assertEquals(originalPx, tv.textSize, 1.0f)
    }

    @Test
    fun applyFontScale_viewGroup_scalesAllTextViews() {
        val layout = LinearLayout(context)
        val tv1 = TextView(context).also { it.textSize = 12f }
        val tv2 = TextView(context).also { it.textSize = 18f }
        layout.addView(tv1)
        layout.addView(tv2)

        val px1Before = tv1.textSize
        val px2Before = tv2.textSize

        FontScaleUtil.applyFontScale(layout, 1.5f)

        assertTrue(tv1.textSize > px1Before)
        assertTrue(tv2.textSize > px2Before)
    }

    @Test
    fun applyFontScale_calledTwice_usesOriginalSize() {
        val tv = TextView(context)
        tv.textSize = 14f
        val originalPx = tv.textSize

        FontScaleUtil.applyFontScale(tv, 2.0f)
        FontScaleUtil.applyFontScale(tv, 1.0f) // zurück auf original

        // Sollte wieder nahe am Original sein (nicht 2x*1x = 2x)
        assertEquals(originalPx, tv.textSize, 2.0f)
    }

    @Test
    fun applyFontScale_emptyViewGroup_noException() {
        val layout = LinearLayout(context)
        FontScaleUtil.applyFontScale(layout, 1.5f) // darf nicht crashen
    }

    @Test
    fun applyFontScale_nonTextView_ignored() {
        val layout = LinearLayout(context)
        val inner = LinearLayout(context) // kein TextView
        layout.addView(inner)
        FontScaleUtil.applyFontScale(layout, 2.0f) // darf nicht crashen
    }
}

// ---------------------------------------------------------------------------
// AlwaysFilterAutoCompleteTextView
// ---------------------------------------------------------------------------

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AlwaysFilterAutoCompleteTextViewTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
    }

    @Test
    fun enoughToFilter_alwaysReturnsTrue() {
        val view = AlwaysFilterAutoCompleteTextView(context)
        assertTrue(view.enoughToFilter())
    }

    @Test
    fun enoughToFilter_whenEmpty_returnsTrue() {
        val view = AlwaysFilterAutoCompleteTextView(context)
        view.setText("")
        assertTrue(view.enoughToFilter())
    }

    @Test
    fun enoughToFilter_whenHasText_returnsTrue() {
        val view = AlwaysFilterAutoCompleteTextView(context)
        view.setText("abc")
        assertTrue(view.enoughToFilter())
    }

    @Test
    fun constructor_withAttrs_noException() {
        // Testet den @JvmOverloads-Konstruktor mit null attrs
        val view = AlwaysFilterAutoCompleteTextView(context, null)
        assertNotNull(view)
    }
}
