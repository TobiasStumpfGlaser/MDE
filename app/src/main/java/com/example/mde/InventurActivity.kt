package com.example.mde

import android.os.Bundle
import android.widget.*

class InventurActivity : BaseArtikelScanActivity() {
    private lateinit var settings: AppSettings
    private lateinit var username: String

    override val buchungMengeView: EditText
        get() = edtMenge

    override val buchungProjektView: AutoCompleteTextView
        get() = etProjekt

    override fun getLayoutId() = R.layout.activity_inventur

    override fun onCreate(savedInstanceState: Bundle?) {
        settings = AppSettings(this)
        when (settings.selectedTheme) {
            "dark" -> setTheme(R.style.Theme_MDE_Dark)
            "colorful" -> setTheme(R.style.Theme_MDE_Colorful)
            else -> setTheme(R.style.Theme_MDE_Light)
        }

        super.onCreate(savedInstanceState)

        username = intent.getStringExtra("USERNAME") ?: "?"

        val btnCount = findViewById<Button>(R.id.btnCount)

        btnCount.setOnClickListener { doBuchen(true, count = true) }
    }
}