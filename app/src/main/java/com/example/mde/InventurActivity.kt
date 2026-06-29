package com.example.mde

import android.os.Bundle
import android.view.MotionEvent
import android.widget.AutoCompleteTextView
import android.widget.Button
import android.widget.EditText
import android.widget.TextView

/**
 * Inventur-Activity.
 *
 * Ermöglicht das Scannen und manuelle Auswählen eines Artikels sowie die anschließende
 * Mengenzählung per [doBuchen]. Erbt die Artikel-Lade- und Scanner-Logik von
 * [BaseArtikelScanActivity].
 */
open class InventurActivity : BaseArtikelScanActivity() {
    private lateinit var settings: AppSettings
    private lateinit var username: String

    override val buchungMengeView: EditText
        get() = edtMenge

    override val buchungProjektView: AutoCompleteTextView?
        get() = null

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

        etFilter.setOnClickListener {
            if (etFilter.text.isNotBlank()) {
                btnClearClicked()
                etFilter.setSelection(0)
            }
        }

        val txtHeader = findViewById<TextView>(R.id.txtHeader)
        txtHeader.text = "BW MDE - Werk: ${settings.werkNummer}"

        val btnCount = findViewById<Button>(R.id.btnCount)
        btnCount.setOnClickListener { openCountDialogIfArticleValid() }
    }

    override fun onBarcodeScanned(barcode: String) {
        val cleanedBarcode = barcode.trim()
        if (cleanedBarcode.isBlank()) return
        if (tryHandleWithActiveDialogHandler(cleanedBarcode)) return
        if (hasValidSelectedArticle()) {
            btnClearClicked()
        }
        etFilter.setText(cleanedBarcode)
        etFilter.setSelection(0)
        handleArtikelBarcodeScan(cleanedBarcode)
    }

    private fun hasValidSelectedArticle(): Boolean {
        val artikel = etFilter.text.toString().trim().split("|").firstOrNull()?.trim().orEmpty()
        if (artikel.isBlank()) return false
        return DataRepository.artikelListe.any { it.artNr.equals(artikel, ignoreCase = true) }
    }

    private fun openCountDialogIfArticleValid() {
        if (!hasValidSelectedArticle()) {
            showErrorWithLoadingHelper("Bitte zuerst einen gültigen Artikel auswählen")
            return
        }

        doBuchen(true, count = true)
        btnClearClicked()
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        resetLogoutTimer()
        return super.dispatchTouchEvent(ev)
    }
}