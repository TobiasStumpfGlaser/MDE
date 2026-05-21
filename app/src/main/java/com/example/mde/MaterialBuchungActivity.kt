package com.example.mde

import android.content.Context
import android.os.Bundle
import android.widget.*

class MaterialBuchungActivity : BaseArtikelScanActivity() {

    private lateinit var settings: AppSettings
    private lateinit var username: String

    // LayoutScale wirkt nur, wenn es VOR dem Layout-Inflate gesetzt wird -> attachBaseContext
    override fun attachBaseContext(newBase: Context) {
        val s = AppSettings(newBase)
        val scaledBase = LayoutScaleUtil.applyLayoutScale(newBase, s.layoutScale)
        super.attachBaseContext(scaledBase)
    }

    override val buchungMengeView: EditText
        get() = edtMenge

    override val buchungProjektView: AutoCompleteTextView
        get() = etProjekt

    override fun getLayoutId() = R.layout.activity_material_buchung

    override fun onCreate(savedInstanceState: Bundle?) {
        // hier ist "this" bereits das skalierte Context (wegen attachBaseContext)
        settings = AppSettings(this)

        // Theme muss VOR super.onCreate(), damit es beim Inflate aktiv ist
        when (settings.selectedTheme) {
            "dark" -> setTheme(R.style.Theme_MDE_Dark)
            "colorful" -> setTheme(R.style.Theme_MDE_Colorful)
            else -> setTheme(R.style.Theme_MDE_Light)
        }

        super.onCreate(savedInstanceState) // Base inflatet Layout via getLayoutId()

        // FontScale (sp) NACH dem Inflate anwenden
        FontScaleUtil.applyFontScale(findViewById(android.R.id.content), settings.fontScale)

        username = intent.getStringExtra("USERNAME") ?: "?"

        val btnEinlagern = findViewById<Button>(R.id.btnEinlagern)
        val btnAuslagern = findViewById<Button>(R.id.btnAuslagern)

        btnEinlagern.setOnClickListener {
            if (!btnEinlagern.isEnabled || !btnAuslagern.isEnabled) return@setOnClickListener
            btnEinlagern.isEnabled = false
            doBuchen(true)
            btnEinlagern.isEnabled = true
        }

        btnAuslagern.setOnClickListener {
            if (!btnAuslagern.isEnabled || !btnEinlagern.isEnabled) return@setOnClickListener
            btnAuslagern.isEnabled = false
            doBuchen(false)
            btnAuslagern.isEnabled = true
        }

        val txtHeader = findViewById<TextView>(R.id.txtHeader)
        txtHeader.text = "BW MDE - Werk: ${settings.werkNummer}"
    }
}