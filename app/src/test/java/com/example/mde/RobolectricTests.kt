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

// ---------------------------------------------------------------------------
// AppSettings
// ---------------------------------------------------------------------------

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AppSettingsTest {

    private lateinit var context: Context
    private lateinit var settings: AppSettings

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        // Frische SharedPreferences für jeden Test
        context.getSharedPreferences("bw_mde_settings", Context.MODE_PRIVATE)
            .edit().clear().commit()
        settings = AppSettings(context)
    }

    @Test
    fun serverIp_default_returns192168() {
        assertEquals("192.168.0.1", settings.serverIp)
    }

    @Test
    fun serverIp_setAndGet_returnsNewValue() {
        settings.serverIp = "10.0.0.1"
        assertEquals("10.0.0.1", settings.serverIp)
    }

    @Test
    fun serverPort_default_returns5000() {
        assertEquals(5000, settings.serverPort)
    }

    @Test
    fun serverPort_setAndGet_returnsNewValue() {
        settings.serverPort = 8080
        assertEquals(8080, settings.serverPort)
    }

    @Test
    fun timeoutS_default_returns3000() {
        assertEquals(3000, settings.timeoutS)
    }

    @Test
    fun timeoutS_setAndGet_returnsNewValue() {
        settings.timeoutS = 5000
        assertEquals(5000, settings.timeoutS)
    }

    @Test
    fun logoutTimeSec_default_returns300() {
        assertEquals(300, settings.logoutTimeSec)
    }

    @Test
    fun logoutTimeSec_setAndGet_returnsNewValue() {
        settings.logoutTimeSec = 600
        assertEquals(600, settings.logoutTimeSec)
    }

    @Test
    fun werkNummer_default_returnsEmpty() {
        assertEquals("", settings.werkNummer)
    }

    @Test
    fun werkNummer_setAndGet_returnsNewValue() {
        settings.werkNummer = "10"
        assertEquals("10", settings.werkNummer)
    }

    @Test
    fun defaultUser_default_returnsEmpty() {
        assertEquals("", settings.defaultUser)
    }

    @Test
    fun defaultUser_setAndGet_returnsNewValue() {
        settings.defaultUser = "admin"
        assertEquals("admin", settings.defaultUser)
    }

    @Test
    fun clearAfterSuccess_default_returnsFalse() {
        assertFalse(settings.clearAfterSuccess)
    }

    @Test
    fun clearAfterSuccess_setTrue_returnsTrue() {
        settings.clearAfterSuccess = true
        assertTrue(settings.clearAfterSuccess)
    }

    @Test
    fun selectedTheme_default_returnsLight() {
        assertEquals("light", settings.selectedTheme)
    }

    @Test
    fun selectedTheme_setDark_returnsDark() {
        settings.selectedTheme = "dark"
        assertEquals("dark", settings.selectedTheme)
    }

    @Test
    fun selectedTheme_setColorful_returnsColorful() {
        settings.selectedTheme = "colorful"
        assertEquals("colorful", settings.selectedTheme)
    }

    @Test
    fun fontScale_default_returns1f() {
        assertEquals(1.0f, settings.fontScale, 0.001f)
    }

    @Test
    fun fontScale_setAndGet_returnsNewValue() {
        settings.fontScale = 1.5f
        assertEquals(1.5f, settings.fontScale, 0.001f)
    }

    @Test
    fun fontScale_belowMin_clampsTo025() {
        settings.fontScale = 0.1f
        assertEquals(0.25f, settings.fontScale, 0.001f)
    }

    @Test
    fun fontScale_aboveMax_clampsTo200() {
        settings.fontScale = 3.0f
        assertEquals(2.0f, settings.fontScale, 0.001f)
    }

    @Test
    fun layoutScale_default_returns1f() {
        assertEquals(1.0f, settings.layoutScale, 0.001f)
    }

    @Test
    fun layoutScale_setAndGet_returnsNewValue() {
        settings.layoutScale = 1.2f
        assertEquals(1.2f, settings.layoutScale, 0.001f)
    }

    @Test
    fun layoutScale_belowMin_clampsTo025() {
        settings.layoutScale = 0.0f
        assertEquals(0.25f, settings.layoutScale, 0.001f)
    }

    @Test
    fun layoutScale_aboveMax_clampsTo200() {
        settings.layoutScale = 5.0f
        assertEquals(2.0f, settings.layoutScale, 0.001f)
    }

    @Test
    fun settings_persistedAcrossInstances() {
        settings.serverIp = "172.16.0.1"
        val settings2 = AppSettings(context)
        assertEquals("172.16.0.1", settings2.serverIp)
    }
}

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

        val logFile = File(logDir, "GetArtikel.txt")
        assertTrue(logFile.exists())
        val content = logFile.readText()
        assertTrue(content.contains("REQUEST"))
        assertTrue(content.contains("request-body"))
    }

    @Test
    fun logResponse_createsFileWithContent() {
        TcpLogHelper.logResponse(context, "GetArtikel", "response-body")

        val logFile = File(logDir, "GetArtikel.txt")
        assertTrue(logFile.exists())
        val content = logFile.readText()
        assertTrue(content.contains("RESPONSE"))
        assertTrue(content.contains("response-body"))
    }

    @Test
    fun logRequest_appendsMultipleEntries() {
        TcpLogHelper.logRequest(context, "GetArtikel", "erster request")
        TcpLogHelper.logRequest(context, "GetArtikel", "zweiter request")

        val content = File(logDir, "GetArtikel.txt").readText()
        assertTrue(content.contains("erster request"))
        assertTrue(content.contains("zweiter request"))
    }

    @Test
    fun logRequest_differentCommands_separateFiles() {
        TcpLogHelper.logRequest(context, "GetArtikel", "a")
        TcpLogHelper.logRequest(context, "SetBuchung", "b")

        assertTrue(File(logDir, "GetArtikel.txt").exists())
        assertTrue(File(logDir, "SetBuchung.txt").exists())
    }

    @Test
    fun clearLogs_afterLogging_deletesLogFiles() {
        TcpLogHelper.logRequest(context, "GetArtikel", "test")
        assertTrue(File(logDir, "GetArtikel.txt").exists())

        TcpLogHelper.clearLogs(context)

        assertEquals(0, logDir.listFiles()?.size ?: 0)
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
