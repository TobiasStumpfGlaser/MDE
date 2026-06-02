package com.example.mde

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

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

    // serverIp
    @Test
    fun serverIp_default_returns192168() {
        assertEquals("192.168.0.1", settings.serverIp)
    }

    @Test
    fun serverIp_setAndGet_returnsNewValue() {
        settings.serverIp = "10.0.0.1"
        assertEquals("10.0.0.1", settings.serverIp)
    }

    // serverPort
    @Test
    fun serverPort_default_returns5000() {
        assertEquals(5000, settings.serverPort)
    }

    @Test
    fun serverPort_setAndGet_returnsNewValue() {
        settings.serverPort = 8080
        assertEquals(8080, settings.serverPort)
    }

    // timeoutS
    @Test
    fun timeoutS_default_returns3000() {
        assertEquals(3000, settings.timeoutS)
    }

    @Test
    fun timeoutS_setAndGet_returnsNewValue() {
        settings.timeoutS = 5000
        assertEquals(5000, settings.timeoutS)
    }

    // logoutTimeSec
    @Test
    fun logoutTimeSec_default_returns300() {
        assertEquals(300, settings.logoutTimeSec)
    }

    @Test
    fun logoutTimeSec_setAndGet_returnsNewValue() {
        settings.logoutTimeSec = 600
        assertEquals(600, settings.logoutTimeSec)
    }

    // werkNummer
    @Test
    fun werkNummer_default_returnsEmpty() {
        assertEquals("", settings.werkNummer)
    }

    @Test
    fun werkNummer_setAndGet_returnsNewValue() {
        settings.werkNummer = "10"
        assertEquals("10", settings.werkNummer)
    }

    // defaultUser
    @Test
    fun defaultUser_default_returnsEmpty() {
        assertEquals("", settings.defaultUser)
    }

    @Test
    fun defaultUser_setAndGet_returnsNewValue() {
        settings.defaultUser = "admin"
        assertEquals("admin", settings.defaultUser)
    }

    // clearAfterSuccess
    @Test
    fun clearAfterSuccess_default_returnsFalse() {
        assertFalse(settings.clearAfterSuccess)
    }

    @Test
    fun clearAfterSuccess_setTrue_returnsTrue() {
        settings.clearAfterSuccess = true
        assertTrue(settings.clearAfterSuccess)
    }

    // selectedTheme
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

    // fontScale
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

    // layoutScale
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

    // Persistenz: neues AppSettings-Objekt liest gespeicherte Werte
    @Test
    fun persistence_serverIp_survivesNewInstance() {
        settings.serverIp = "172.16.0.5"
        val settings2 = AppSettings(context)
        assertEquals("172.16.0.5", settings2.serverIp)
    }

    @Test
    fun persistence_selectedTheme_survivesNewInstance() {
        settings.selectedTheme = "dark"
        val settings2 = AppSettings(context)
        assertEquals("dark", settings2.selectedTheme)
    }
}
