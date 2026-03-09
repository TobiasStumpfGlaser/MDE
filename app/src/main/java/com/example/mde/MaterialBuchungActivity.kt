package com.example.mde

import android.os.Bundle
import android.widget.*

class MaterialBuchungActivity : BaseArtikelScanActivity() {

    private lateinit var txtProjekt: AutoCompleteTextView
    private lateinit var edtMenge: EditText
    private lateinit var txtStatus: TextView

    private lateinit var settings: AppSettings
    private lateinit var username: String

    override val buchungProjektView get() = txtProjekt
    override val buchungMengeView get() = edtMenge
    override val buchungStatusView get() = txtStatus

    override fun getSettings() = settings
    override fun getUsername() = username
    override fun getWerkNummer() = settings.werkNummer

    override fun getLayoutId() = R.layout.activity_material_buchung

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        settings = AppSettings(this)
        username = intent.getStringExtra("USERNAME") ?: "?"

        txtProjekt = findViewById(R.id.txtProjekt)
        edtMenge = findViewById(R.id.edtMenge)
        txtStatus = findViewById(R.id.txtStatus)

        val projektAdapter = ArrayAdapter<String>(
            this,
            android.R.layout.simple_dropdown_item_1line,
            mutableListOf<String>()
        )
        txtProjekt.setAdapter(projektAdapter)
        txtProjekt.threshold = 1
        txtProjekt.setOnClickListener { txtProjekt.showDropDown() }

        findViewById<Button>(R.id.btnBuchen).setOnClickListener { doBuchen() }
    }

    override fun btnClearClicked() {
        super.btnClearClicked()
        txtProjekt.text.clear()
        edtMenge.text.clear()
        txtStatus.text = ""
    }

    override fun onProjekteGeladen() {
        val adapter = txtProjekt.adapter as? ArrayAdapter<String> ?: return
        adapter.clear()
        adapter.addAll(projektListe)
        adapter.notifyDataSetChanged()
        txtStatus.text = "✅ Daten aktualisiert"
    }
}