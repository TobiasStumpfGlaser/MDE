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

    private val prefs =
        context.getSharedPreferences("bw_mde_settings", Context.MODE_PRIVATE)

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

    /**
     * SMB-Basis-URL des Update-Verzeichnisses, z. B. `smb://192.168.1.100/updates`.
     * Darunter werden `version.json` und die APK-Dateien erwartet.
     */
    var otaServerUrl: String
        get() = prefs.getString("ota_server_url", "")!!
        set(value) = prefs.edit().putString("ota_server_url", value).apply()

    /** Windows-Domäne für die NTLM-Authentifizierung am Update-Server (kann leer sein). */
    var otaDomain: String
        get() = prefs.getString("ota_domain", "")!!
        set(value) = prefs.edit().putString("ota_domain", value).apply()

    /** Benutzername für die NTLM-Authentifizierung am Update-Server. */
    var otaUsername: String
        get() = prefs.getString("ota_username", "")!!
        set(value) = prefs.edit().putString("ota_username", value).apply()

    /**
     * Passwort für die NTLM-Authentifizierung am Update-Server.
     *
     * **Sicherheitshinweis:** Für Produktionsumgebungen empfiehlt sich die Verwendung von
     * `EncryptedSharedPreferences` (androidx.security:security-crypto) statt
     * plaintext SharedPreferences.
     */
    var otaPassword: String
        get() = prefs.getString("ota_password", "")!!
        set(value) = prefs.edit().putString("ota_password", value).apply()
}