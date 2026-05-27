package com.example.mde

import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.MotionEvent
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.widget.AutoCompleteTextView
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import androidx.appcompat.app.AlertDialog

class InventurActivity : BaseArtikelScanActivity() {
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

        etFilter.setOnTouchListener { _, event ->
            if (event.action == MotionEvent.ACTION_UP) {
                if (hasValidSelectedArticle()) {
                    btnClearClicked()
                }
                showArticleSuggestions()
            }
            false
        }

        etFilter.setOnClickListener {
            if (hasValidSelectedArticle()) {
                btnClearClicked()
            }
            showArticleSuggestions()
        }

        etFilter.setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus && !hasValidSelectedArticle()) {
                showArticleSuggestions()
            }
        }

        etFilter.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) {
                if (hasValidSelectedArticle()) return
                if (s.isNullOrEmpty()) {
                    showArticleSuggestions()
                }
            }

            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
        })

        val txtHeader = findViewById<TextView>(R.id.txtHeader)
        txtHeader.text = "BW MDE - Werk: ${settings.werkNummer}"

        val btnCount = findViewById<Button>(R.id.btnCount)
        btnCount.setOnClickListener { openCountDialogIfArticleValid() }
    }

    override fun onBarcodeScanned(barcode: String) {
        val cleanedBarcode = barcode.trim()
        if (cleanedBarcode.isBlank()) return
        if (hasValidSelectedArticle()) {
            btnClearClicked()
        }
        etFilter.setText(cleanedBarcode)
        etFilter.setSelection(0)
        handleArtikelBarcodeScan(cleanedBarcode)
    }

    override fun btnClearClicked() {
        super.btnClearClicked()
        etFilter.isFocusable = true
        etFilter.isFocusableInTouchMode = true
        etFilter.isCursorVisible = true
        etFilter.keyListener = etFilterKeyListener
    }

    private fun showArticleSuggestions() {
        if (hasValidSelectedArticle()) return
        etFilter.post {
            etFilter.isFocusable = true
            etFilter.isFocusableInTouchMode = true
            etFilter.isCursorVisible = true
            etFilter.keyListener = etFilterKeyListener
            etFilter.requestFocus()
            val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
            imm.showSoftInput(etFilter, InputMethodManager.SHOW_IMPLICIT)
            adapter.filter.filter("")
            etFilter.showDropDown()
        }
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

        val mengeText = edtMenge.text.toString().trim()
        val dialogMenge = EditText(this).apply {
            setText(mengeText)
            inputType = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL
        }

        AlertDialog.Builder(this)
            .setTitle("Ist-Zählung setzen")
            .setView(dialogMenge)
            .setPositiveButton("OK") { _, _ ->
                val countValue = dialogMenge.text.toString().trim()
                edtMenge.setText(countValue)
                doBuchen(true, count = true)
            }
            .setNegativeButton("Abbrechen", null)
            .show()
            .window?.setLayout(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
    }
}
