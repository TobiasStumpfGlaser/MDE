package com.example.mde

import android.os.Bundle
import android.widget.*

class MaterialBuchungActivity : BaseArtikelScanActivity() {
    private lateinit var settings: AppSettings
    private lateinit var username: String

    override val buchungMengeView: EditText
        get() = edtMenge

    override val buchungProjektView: AutoCompleteTextView
        get() = etProjekt

    override fun getLayoutId() = R.layout.activity_material_buchung

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        settings = AppSettings(this)
        username = intent.getStringExtra("USERNAME") ?: "?"

        // Neue Buttons Einlagern / Auslagern
        val btnEinlagern = findViewById<Button>(R.id.btnEinlagern)
        val btnAuslagern = findViewById<Button>(R.id.btnAuslagern)

        btnEinlagern.setOnClickListener {
            if (!btnEinlagern.isEnabled || !btnAuslagern.isEnabled) return@setOnClickListener
            btnEinlagern.isEnabled = false
            doBuchen(true)
            btnEinlagern.isEnabled = true
        }   // true = Einlagern


        btnAuslagern.setOnClickListener {
            if (!btnAuslagern.isEnabled || !btnEinlagern.isEnabled) return@setOnClickListener
            btnAuslagern.isEnabled = false
            doBuchen(false)
            btnAuslagern.isEnabled = true
        }  // false = Auslagern
    }
}