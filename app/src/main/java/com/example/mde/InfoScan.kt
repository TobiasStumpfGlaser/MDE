package com.example.mde

import android.os.Bundle
import android.widget.AutoCompleteTextView
import android.widget.EditText
import android.widget.TextView

class InfoScanActivity : BaseArtikelScanActivity() {
    // Keine Buchungs-Views, weil Layout sie nicht enthält
    override val buchungProjektView: AutoCompleteTextView? = null
    override val buchungMengeView: EditText? = null

    private lateinit var settings: AppSettings
    private lateinit var username: String

    override fun getLayoutId(): Int {
        return R.layout.activity_info_scan
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        settings = AppSettings(this)
        username = intent.getStringExtra("USERNAME") ?: "?"
    }
}