package com.example.mde

import androidx.appcompat.app.AppCompatActivity
import android.os.Bundle
import android.widget.*
import androidx.appcompat.widget.Toolbar

class SettingsActivity : AppCompatActivity() {

    private lateinit var settings: AppSettings
    private lateinit var cbClear: CheckBox

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        val toolbar = findViewById<Toolbar>(R.id.toolbar)
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayShowTitleEnabled(false)
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
        val cbClear = findViewById<CheckBox>(R.id.cbClearAfterSuccess)
        val cbConfirmM = findViewById<CheckBox>(R.id.cbConfirmBook)
        val btnSave = findViewById<Button>(R.id.btnSave)

        // Laden
        etIp.setText(settings.serverIp)
        etPort.setText(settings.serverPort.toString())
        etTimeout.setText(settings.timeoutS.toString())
        etLogout.setText(settings.logoutTimeSec.toString())
        etWerk.setText(settings.werkNummer)
        etDefUser.setText(settings.defaultUser)
        cbClear.isChecked = settings.clearAfterSuccess
        cbConfirmM.isChecked = settings.confirmBook

        // Speichern
        btnSave.setOnClickListener {
            settings.serverIp = etIp.text.toString()
            settings.serverPort = etPort.text.toString().toInt()
            settings.timeoutS = etTimeout.text.toString().toInt()
            settings.logoutTimeSec = etLogout.text.toString().toInt()
            settings.werkNummer = etWerk.text.toString()
            settings.defaultUser = etDefUser.text.toString()
            settings.clearAfterSuccess = cbClear.isChecked
            settings.confirmBook = cbConfirmM.isChecked

            finish()
        }
    }
}
