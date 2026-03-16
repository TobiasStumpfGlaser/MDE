package com.example.mde

import android.os.Bundle
import android.widget.*

class InventurActivity : BaseArtikelScanActivity() {
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

    override fun getLayoutId() = R.layout.activity_inventur

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        settings = AppSettings(this)
        username = intent.getStringExtra("USERNAME") ?: "?"

        val btnCount = findViewById<Button>(R.id.btnCount)

        btnCount.setOnClickListener { doBuchen(true, count = true) }
    }
}