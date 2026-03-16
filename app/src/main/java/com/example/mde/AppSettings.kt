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
}
