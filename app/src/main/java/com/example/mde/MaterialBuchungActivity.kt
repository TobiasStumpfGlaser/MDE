package com.example.mde

import android.os.Bundle
import android.widget.*

class MaterialBuchungActivity : BaseArtikelScanActivity() {
    private lateinit var settings: AppSettings
    private lateinit var username: String

    override val buchungMengeView: EditText?
        get() = edtMenge

    override val buchungStatusView: TextView?
        get() = txtStatus

    override val buchungProjektView: AutoCompleteTextView?
        get() = etProjekt

    override fun getSettings() = settings
    override fun getUsername() = username
    override fun getWerkNummer() = settings.werkNummer

    override fun getLayoutId() = R.layout.activity_material_buchung

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        settings = AppSettings(this)
        username = intent.getStringExtra("USERNAME") ?: "?"

        // Neue Buttons Einlagern / Auslagern
        val btnEinlagern = findViewById<Button>(R.id.btnEinlagern)
        val btnAuslagern = findViewById<Button>(R.id.btnAuslagern)

        btnEinlagern.setOnClickListener { doBuchen(true) }   // true = Einlagern
        btnAuslagern.setOnClickListener { doBuchen(false) }  // false = Auslagern
    }
}