package com.example.mde

import android.content.Context

/**
 * Verwaltet alle persistenten App-Einstellungen via [android.content.SharedPreferences].
 *
 * Alle Properties werden unter dem Preferences-Namen `bw_mde_settings` gespeichert.
 * Eine neue Instanz kann jederzeit mit einem [Context] erzeugt werden; die Daten
 * werden sofort aus den SharedPreferences geladen bzw. beim Setzen geschrieben.
 */
class AppSettings(context: Context) {

    companion object {
        internal const val DEFAULT_OTA_ENABLED = true
        internal const val DEFAULT_OTA_SERVER = "mde-server"
        internal const val DEFAULT_OTA_CONNECT_HOST =
            "mde-server.brainware-solutions.de"
        internal const val DEFAULT_OTA_SHARE = "mde-update"
        internal const val DEFAULT_OTA_BASE_PATH = ""
        internal const val DEFAULT_OTA_REALM = "BRAINWARE-SOLUTIONS.DE"
        internal const val DEFAULT_OTA_KDC_ADDRESS =
            "werk-1-vdcw2k22.brainware-solutions.de:88"

        private const val PREFERENCES_NAME = "bw_mde_settings"
        private const val KEY_OTA_ENABLED = "ota_enabled"
        private const val KEY_OTA_SERVER = "ota_server"
        private const val KEY_OTA_CONNECT_HOST = "ota_connect_host"
        private const val KEY_OTA_SHARE = "ota_share"
        private const val KEY_OTA_BASE_PATH = "ota_base_path"
        private const val KEY_OTA_REALM = "ota_realm"
        private const val KEY_OTA_KDC_ADDRESS = "ota_kdc_address"
        private const val KEY_SETTINGS_SCHEMA_VERSION = "ota_settings_schema_version"
        private const val SETTINGS_SCHEMA_VERSION = 1
    }

    private val prefs =
        context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    init {
        removeObsoletePlaintextOtaSettings()
    }

    /** IP-Adresse des TCP-Servers. */
    var serverIp: String
        get() = prefs.getString("server_ip", "192.168.0.1")!!
        set(value) = prefs.edit().putString("server_ip", value).apply()

    /** Port des TCP-Servers. */
    var serverPort: Int
        get() = prefs.getInt("server_port", 5000)
        set(value) = prefs.edit().putInt("server_port", value).apply()

    /** Verbindungs-Timeout in Millisekunden. */
    var timeoutS: Int
        get() = prefs.getInt("timeout_s", 3000)
        set(value) = prefs.edit().putInt("timeout_s", value).apply()

    /** Inaktivitäts-Timeout bis zum automatischen Logout in Sekunden. */
    var logoutTimeSec: Int
        get() = prefs.getInt("logout_time_sec", 300)
        set(value) = prefs.edit().putInt("logout_time_sec", value).apply()

    /** Werknummer, die in der Toolbar angezeigt wird. */
    var werkNummer: String
        get() = prefs.getString("werk_nummer", "")!!
        set(value) = prefs.edit().putString("werk_nummer", value).apply()

    /** Vorausgefüllter Standard-Benutzername im Login-Formular. */
    var defaultUser: String
        get() = prefs.getString("default_user", "")!!
        set(value) = prefs.edit().putString("default_user", value).apply()

    /** Gibt an, ob Felder nach einer erfolgreichen Buchung automatisch geleert werden. */
    var clearAfterSuccess: Boolean
        get() = prefs.getBoolean("clearAfterSuccess", false)
        set(value) = prefs.edit().putBoolean("clearAfterSuccess", value).apply()

    /** Ausgewähltes App-Theme: `"light"`, `"dark"` oder `"colorful"`. */
    var selectedTheme: String
        get() = prefs.getString("selected_theme", "light") ?: "light"
        set(value) = prefs.edit().putString("selected_theme", value).apply()

    /** Skalierungsfaktor für die Schriftgrößen (0.25..2.00). */
    var fontScale: Float
        get() = prefs.getFloat("font_scale", 1.0f)
        set(value) = prefs.edit().putFloat("font_scale", value.coerceIn(0.25f, 2.0f)).apply()

    /** Skalierungsfaktor für die Layout-Dichte (0.25..2.00). */
    var layoutScale: Float
        get() = prefs.getFloat("layout_scale", 1.0f)
        set(value) = prefs.edit().putFloat("layout_scale", value.coerceIn(0.25f, 2.0f)).apply()

    // -------------------------------------------------------------------------
    // OTA-Update-Einstellungen
    // -------------------------------------------------------------------------

    /** Schaltet die automatische OTA-Versionsprüfung und Updates zur Laufzeit ein oder aus. */
    var otaEnabled: Boolean
        get() = prefs.getBoolean(KEY_OTA_ENABLED, DEFAULT_OTA_ENABLED)
        set(value) = prefs.edit().putBoolean(KEY_OTA_ENABLED, value).apply()

    /** Logischer SMB-Servername für UNC-Pfad und Kerberos-SPN (`cifs/<server>`). */
    var otaServer: String
        get() = readOtaValue(KEY_OTA_SERVER, DEFAULT_OTA_SERVER) {
            validateOtaHost(it, "OTA-SMB-Server")
        }
        set(value) = writeOtaValue(KEY_OTA_SERVER, value) {
            validateOtaHost(it, "OTA-SMB-Server")
        }

    /** DNS-Name oder IPv4-Adresse, über die das Gerät die SMB-Verbindung aufbaut. */
    var otaConnectHost: String
        get() = readOtaValue(KEY_OTA_CONNECT_HOST, DEFAULT_OTA_CONNECT_HOST) {
            validateOtaHost(it, "OTA-Connect-Host")
        }
        set(value) = writeOtaValue(KEY_OTA_CONNECT_HOST, value) {
            validateOtaHost(it, "OTA-Connect-Host")
        }

    /** SMB-Freigabe, in der die OTA-Dateien liegen. */
    var otaShare: String
        get() = readOtaValue(KEY_OTA_SHARE, DEFAULT_OTA_SHARE, ::validateOtaShare)
        set(value) = writeOtaValue(KEY_OTA_SHARE, value, ::validateOtaShare)

    /** Relativer Ordner innerhalb der Freigabe; leer bedeutet Freigabewurzel. */
    var otaBasePath: String
        get() = readOtaValue(
            KEY_OTA_BASE_PATH,
            DEFAULT_OTA_BASE_PATH,
            ::validateOtaBasePath
        )
        set(value) = writeOtaValue(KEY_OTA_BASE_PATH, value, ::validateOtaBasePath)

    /** Kerberos-Realm; wird kanonisch in Großbuchstaben gespeichert. */
    var otaRealm: String
        get() = readOtaValue(KEY_OTA_REALM, DEFAULT_OTA_REALM, ::normalizeOtaRealm)
        set(value) = writeOtaValue(KEY_OTA_REALM, value, ::normalizeOtaRealm)

    /** Kerberos-KDC als Host mit optionalem Port; ohne Port wird `88` ergänzt. */
    var otaKdcAddress: String
        get() = readOtaValue(
            KEY_OTA_KDC_ADDRESS,
            DEFAULT_OTA_KDC_ADDRESS,
            ::normalizeOtaKdcAddress
        )
        set(value) = writeOtaValue(KEY_OTA_KDC_ADDRESS, value, ::normalizeOtaKdcAddress)

    private fun readOtaValue(
        key: String,
        defaultValue: String,
        normalize: (String) -> String
    ): String {
        val storedValue = prefs.getString(key, defaultValue) ?: defaultValue
        return runCatching { normalize(storedValue) }
            .getOrDefault(defaultValue)
    }

    private fun writeOtaValue(
        key: String,
        value: String,
        normalize: (String) -> String
    ) {
        prefs.edit().putString(key, normalize(value)).apply()
    }

    /** Entfernt einmalig Zugangsdaten und NTLM-Werte des nicht mehr verwendeten OTA-Pfads. */
    private fun removeObsoletePlaintextOtaSettings() {
        if (prefs.getInt(KEY_SETTINGS_SCHEMA_VERSION, 0) >= SETTINGS_SCHEMA_VERSION) return

        prefs.edit()
            .remove("ota_server_url")
            .remove("ota_domain")
            .remove("ota_username")
            .remove("ota_password")
            .putInt(KEY_SETTINGS_SCHEMA_VERSION, SETTINGS_SCHEMA_VERSION)
            .apply()
    }
}
