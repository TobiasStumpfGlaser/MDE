package com.example.mde

import android.content.Context

class AppSettings(context: Context) {

    private val prefs =
        context.getSharedPreferences("bw_mde_settings", Context.MODE_PRIVATE)

    var serverIp: String
        get() = prefs.getString("server_ip", "192.168.0.1")!!
        set(value) = prefs.edit().putString("server_ip", value).apply()

    var serverPort: Int
        get() = prefs.getInt("server_port", 5000)
        set(value) = prefs.edit().putInt("server_port", value).apply()

    var timeoutS: Int
        get() = prefs.getInt("timeout_s", 3000)
        set(value) = prefs.edit().putInt("timeout_s", value).apply()

    var logoutTimeSec: Int
        get() = prefs.getInt("logout_time_sec", 300)
        set(value) = prefs.edit().putInt("logout_time_sec", value).apply()

    var werkNummer: String
        get() = prefs.getString("werk_nummer", "")!!
        set(value) = prefs.edit().putString("werk_nummer", value).apply()

    var defaultUser: String
        get() = prefs.getString("default_user", "")!!
        set(value) = prefs.edit().putString("default_user", value).apply()

    var clearAfterSuccess: Boolean
        get() = prefs.getBoolean("clearAfterSuccess", false)
        set(value) = prefs.edit().putBoolean("clearAfterSuccess", value).apply()

    var confirmBook: Boolean
        get() = prefs.getBoolean("confirmBook", false)
        set(value) = prefs.edit().putBoolean("confirmBook", value).apply()

    var selectedTheme: String
        get() = prefs.getString("selected_theme", "light") ?: "light"
        set(value) = prefs.edit().putString("selected_theme", value).apply()

    var fontScale: Float
        get() = prefs.getFloat("font_scale", 1.0f)
        set(value) = prefs.edit().putFloat("font_scale", value.coerceIn(0.25f, 2.0f)).apply()

    var layoutScale: Float
        get() = prefs.getFloat("layout_scale", 1.0f)
        set(value) = prefs.edit().putFloat("layout_scale", value.coerceIn(0.25f, 2.0f)).apply()
}