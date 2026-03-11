package com.example.mde

import android.os.Bundle
import android.widget.AutoCompleteTextView
import android.widget.EditText
import android.widget.TextView

class InfoScanActivity : BaseArtikelScanActivity() {

    private lateinit var txtStatus: TextView

    // Keine Buchungs-Views, weil Layout sie nicht enthält
    override val buchungProjektView: AutoCompleteTextView? = null
    override val buchungMengeView: EditText? = null
    override val buchungStatusView get() = txtStatus

    private lateinit var settings: AppSettings
    private lateinit var username: String

    override fun getSettings() = settings
    override fun getUsername() = username
    override fun getWerkNummer() = settings.werkNummer

    override fun getLayoutId(): Int {
        return R.layout.activity_info_scan
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        txtStatus = findViewById(R.id.txtStatus)
        settings = AppSettings(this)
        username = intent.getStringExtra("USERNAME") ?: "?"
    }

    override fun btnClearClicked() {
        super.btnClearClicked()
        txtStatus.text = ""
    }

    override fun onProjekteGeladen() {
        if (artikelListe.isNotEmpty()) {
            txtStatus.text = "✅ Daten aktualisiert"
        } else {
            txtStatus.text = "⚠ Fehler Kommunikation"
        }
    }
}