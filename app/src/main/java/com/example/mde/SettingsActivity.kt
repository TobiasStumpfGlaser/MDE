package com.example.mde

import androidx.appcompat.app.AppCompatActivity
import android.os.Bundle
import android.widget.*
import androidx.appcompat.widget.Toolbar

class SettingsActivity : AppCompatActivity() {

    private lateinit var settings: AppSettings

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        val toolbar = findViewById<Toolbar>(R.id.toolbar)
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        toolbar.navigationIcon?.setColorFilter(
            resources.getColor(android.R.color.white, theme),
            android.graphics.PorterDuff.Mode.SRC_ATOP
        )

        settings = AppSettings(this)

        val etIp = findViewById<EditText>(R.id.etServerIp)
        val etPort = findViewById<EditText>(R.id.etServerPort)
        val etTimeout = findViewById<EditText>(R.id.etTimeout)
        val etLogout = findViewById<EditText>(R.id.etLogoutTime)
        val etWerk = findViewById<EditText>(R.id.etWerkNummer)
        val etDefUser = findViewById<EditText>(R.id.etDefaultUser)
        val btnSave = findViewById<Button>(R.id.btnSave)

        // Laden
        etIp.setText(settings.serverIp)
        etPort.setText(settings.serverPort.toString())
        etTimeout.setText(settings.timeoutS.toString())
        etLogout.setText(settings.logoutTimeSec.toString())
        etWerk.setText(settings.werkNummer)
        etDefUser.setText(settings.defaultUser)

        // Speichern
        btnSave.setOnClickListener {
            settings.serverIp = etIp.text.toString()
            settings.serverPort = etPort.text.toString().toInt()
            settings.timeoutS = etTimeout.text.toString().toInt()
            settings.logoutTimeSec = etLogout.text.toString().toInt()
            settings.werkNummer = etWerk.text.toString()
            settings.defaultUser = etDefUser.text.toString()

            finish()
        }
    }
}
