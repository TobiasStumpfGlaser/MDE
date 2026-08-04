package com.example.mde

import android.content.Context
import android.content.SharedPreferences
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/**
 * Unit-Tests für [AppSettings] mit MockK – kein Android-Laufzeitbedarf,
 * daher vollständig durch JaCoCo erfasst.
 */
class AppSettingsTest {

    // In-Memory-Map die SharedPreferences simuliert
    private val store = mutableMapOf<String, Any?>()

    private val editor: SharedPreferences.Editor = mockk(relaxed = true)
    private val prefs: SharedPreferences = mockk()
    private val context: Context = mockk()

    private lateinit var settings: AppSettings

    @Before
    fun setUp() {
        // editor.put* schreibt in store, apply() ist no-op
        every { editor.putString(any(), any()) } answers {
            store[firstArg()] = secondArg<String>(); editor
        }
        every { editor.putInt(any(), any()) } answers {
            store[firstArg()] = secondArg<Int>(); editor
        }
        every { editor.putFloat(any(), any()) } answers {
            store[firstArg()] = secondArg<Float>(); editor
        }
        every { editor.putBoolean(any(), any()) } answers {
            store[firstArg()] = secondArg<Boolean>(); editor
        }
        every { editor.remove(any()) } answers {
            store.remove(firstArg<String>()); editor
        }
        every { editor.apply() } returns Unit
        every { editor.commit() } returns true

        // prefs.get* liest aus store, Fallback auf Defaultwert
        every { prefs.getString(any(), any()) } answers {
            store[firstArg()] as? String ?: secondArg()
        }
        every { prefs.getInt(any(), any()) } answers {
            store[firstArg()] as? Int ?: secondArg()
        }
        every { prefs.getFloat(any(), any()) } answers {
            store[firstArg()] as? Float ?: secondArg()
        }
        every { prefs.getBoolean(any(), any()) } answers {
            store[firstArg()] as? Boolean ?: secondArg()
        }
        every { prefs.edit() } returns editor

        every { context.getSharedPreferences("bw_mde_settings", Context.MODE_PRIVATE) } returns prefs

        settings = AppSettings(context)
    }

    // ── serverIp ──────────────────────────────────────────────────────────────

    @Test
    fun serverIp_default_returns192168() {
        assertEquals("192.168.0.1", settings.serverIp)
    }

    @Test
    fun serverIp_set_storesAndReturnsNewValue() {
        settings.serverIp = "10.0.0.1"
        assertEquals("10.0.0.1", settings.serverIp)
    }

    // ── serverPort ────────────────────────────────────────────────────────────

    @Test
    fun serverPort_default_returns5000() {
        assertEquals(5000, settings.serverPort)
    }

    @Test
    fun serverPort_set_storesAndReturnsNewValue() {
        settings.serverPort = 8080
        assertEquals(8080, settings.serverPort)
    }

    // ── timeoutS ──────────────────────────────────────────────────────────────

    @Test
    fun timeoutS_default_returns3000() {
        assertEquals(3000, settings.timeoutS)
    }

    @Test
    fun timeoutS_set_storesAndReturnsNewValue() {
        settings.timeoutS = 5000
        assertEquals(5000, settings.timeoutS)
    }

    // ── logoutTimeSec ─────────────────────────────────────────────────────────

    @Test
    fun logoutTimeSec_default_returns300() {
        assertEquals(300, settings.logoutTimeSec)
    }

    @Test
    fun logoutTimeSec_set_storesAndReturnsNewValue() {
        settings.logoutTimeSec = 600
        assertEquals(600, settings.logoutTimeSec)
    }

    // ── werkNummer ────────────────────────────────────────────────────────────

    @Test
    fun werkNummer_default_returnsEmpty() {
        assertEquals("", settings.werkNummer)
    }

    @Test
    fun werkNummer_set_storesAndReturnsNewValue() {
        settings.werkNummer = "10"
        assertEquals("10", settings.werkNummer)
    }

    // ── defaultUser ───────────────────────────────────────────────────────────

    @Test
    fun defaultUser_default_returnsEmpty() {
        assertEquals("", settings.defaultUser)
    }

    @Test
    fun defaultUser_set_storesAndReturnsNewValue() {
        settings.defaultUser = "admin"
        assertEquals("admin", settings.defaultUser)
    }

    // ── clearAfterSuccess ─────────────────────────────────────────────────────

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
    fun clearAfterSuccess_setFalseAfterTrue_returnsFalse() {
        settings.clearAfterSuccess = true
        settings.clearAfterSuccess = false
        assertFalse(settings.clearAfterSuccess)
    }

    // ── OTA-Laufzeiteinstellungen ─────────────────────────────────────────────

    @Test
    fun otaRuntimeSettings_defaultsMatchAppSettings() {
        assertEquals(AppSettings.DEFAULT_OTA_ENABLED, settings.otaEnabled)
        assertEquals(AppSettings.DEFAULT_OTA_SERVER, settings.otaServer)
        assertEquals(AppSettings.DEFAULT_OTA_CONNECT_HOST, settings.otaConnectHost)
        assertEquals(AppSettings.DEFAULT_OTA_SHARE, settings.otaShare)
        assertEquals(AppSettings.DEFAULT_OTA_BASE_PATH, settings.otaBasePath)
        assertEquals(AppSettings.DEFAULT_OTA_REALM, settings.otaRealm)
        assertEquals(AppSettings.DEFAULT_OTA_KDC_ADDRESS, settings.otaKdcAddress)
    }

    @Test
    fun otaRuntimeSettings_storeTrimmedValuesUnderDedicatedKeys() {
        settings.otaEnabled = false
        settings.otaServer = "  fileserver  "
        settings.otaConnectHost = "  fileserver.example.test  "
        settings.otaShare = "  updates  "
        settings.otaBasePath = "releases/android"
        settings.otaRealm = "example.test"
        settings.otaKdcAddress = "kdc.example.test"

        assertFalse(settings.otaEnabled)
        assertEquals("fileserver", settings.otaServer)
        assertEquals("fileserver.example.test", settings.otaConnectHost)
        assertEquals("updates", settings.otaShare)
        assertEquals("releases/android", settings.otaBasePath)
        assertEquals("EXAMPLE.TEST", settings.otaRealm)
        assertEquals("kdc.example.test:88", settings.otaKdcAddress)
        verify { editor.putBoolean("ota_enabled", false) }
        verify { editor.putString("ota_server", "fileserver") }
        verify { editor.putString("ota_connect_host", "fileserver.example.test") }
        verify { editor.putString("ota_share", "updates") }
        verify { editor.putString("ota_base_path", "releases/android") }
        verify { editor.putString("ota_realm", "EXAMPLE.TEST") }
        verify { editor.putString("ota_kdc_address", "kdc.example.test:88") }
    }

    @Test
    fun otaRuntimeSettings_rejectInvalidHosts() {
        assertThrows(IllegalArgumentException::class.java) {
            settings.otaServer = "smb://fileserver"
        }
        assertThrows(IllegalArgumentException::class.java) {
            settings.otaConnectHost = "fileserver.example.test:445"
        }
        assertThrows(IllegalArgumentException::class.java) {
            settings.otaShare = "invalid share"
        }
        assertThrows(IllegalArgumentException::class.java) {
            settings.otaBasePath = "../updates"
        }
        assertThrows(IllegalArgumentException::class.java) {
            settings.otaRealm = "https://example.test"
        }
        assertThrows(IllegalArgumentException::class.java) {
            settings.otaKdcAddress = "kdc.example.test:not-a-port"
        }
    }

    @Test
    fun otaRuntimeSettings_invalidStoredValuesFallBackToDefaults() {
        store["ota_share"] = "invalid share"
        store["ota_base_path"] = "../updates"
        store["ota_realm"] = "invalid realm"
        store["ota_kdc_address"] = "https://kdc.example.test"

        assertEquals(AppSettings.DEFAULT_OTA_SHARE, settings.otaShare)
        assertEquals(AppSettings.DEFAULT_OTA_BASE_PATH, settings.otaBasePath)
        assertEquals(AppSettings.DEFAULT_OTA_REALM, settings.otaRealm)
        assertEquals(AppSettings.DEFAULT_OTA_KDC_ADDRESS, settings.otaKdcAddress)
    }

    @Test
    fun obsoletePlaintextOtaSettings_areRemovedOnce() {
        store["ota_settings_schema_version"] = 0
        store["ota_server_url"] = "smb://legacy"
        store["ota_domain"] = "LEGACY"
        store["ota_username"] = "legacy-user"
        store["ota_password"] = "legacy-password"

        AppSettings(context)

        assertFalse(store.containsKey("ota_server_url"))
        assertFalse(store.containsKey("ota_domain"))
        assertFalse(store.containsKey("ota_username"))
        assertFalse(store.containsKey("ota_password"))
        assertEquals(1, store["ota_settings_schema_version"])
    }

    // ── selectedTheme ─────────────────────────────────────────────────────────

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

    // ── fontScale ─────────────────────────────────────────────────────────────

    @Test
    fun fontScale_default_returns1f() {
        assertEquals(1.0f, settings.fontScale, 0.001f)
    }

    @Test
    fun fontScale_set_storesAndReturnsNewValue() {
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
    fun fontScale_exactMin_storesMin() {
        settings.fontScale = 0.25f
        assertEquals(0.25f, settings.fontScale, 0.001f)
    }

    @Test
    fun fontScale_exactMax_storesMax() {
        settings.fontScale = 2.0f
        assertEquals(2.0f, settings.fontScale, 0.001f)
    }

    // ── layoutScale ───────────────────────────────────────────────────────────

    @Test
    fun layoutScale_default_returns1f() {
        assertEquals(1.0f, settings.layoutScale, 0.001f)
    }

    @Test
    fun layoutScale_set_storesAndReturnsNewValue() {
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
    fun layoutScale_exactMin_storesMin() {
        settings.layoutScale = 0.25f
        assertEquals(0.25f, settings.layoutScale, 0.001f)
    }

    @Test
    fun layoutScale_exactMax_storesMax() {
        settings.layoutScale = 2.0f
        assertEquals(2.0f, settings.layoutScale, 0.001f)
    }

    // ── SharedPreferences-Interaktion ─────────────────────────────────────────

    @Test
    fun serverIp_set_callsEditAndApply() {
        settings.serverIp = "1.2.3.4"
        verify { prefs.edit() }
        verify { editor.putString("server_ip", "1.2.3.4") }
        verify { editor.apply() }
    }

    @Test
    fun serverPort_set_callsEditAndApply() {
        settings.serverPort = 9999
        verify { prefs.edit() }
        verify { editor.putInt("server_port", 9999) }
        verify { editor.apply() }
    }

    @Test
    fun fontScale_set_writesClampedValue() {
        settings.fontScale = 99f
        verify { editor.putFloat("font_scale", 2.0f) }
    }

    @Test
    fun layoutScale_set_writesClampedValue() {
        settings.layoutScale = -1f
        verify { editor.putFloat("layout_scale", 0.25f) }
    }
}
