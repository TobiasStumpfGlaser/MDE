package com.example.mde

import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.widget.ArrayAdapter
import android.widget.AutoCompleteTextView
import android.widget.Button
import android.widget.EditText
import android.widget.Filter
import android.widget.TextView
import androidx.appcompat.app.AlertDialog

class MaterialBuchungActivity : BaseArtikelScanActivity() {

    companion object {
        private const val CHARGE_PREFIX = "Charge:"
    }

    private lateinit var settings: AppSettings
    private lateinit var username: String
    private var bookingDialogOpen = false

    override fun attachBaseContext(newBase: Context) {
        val s = AppSettings(newBase)
        val scaledBase = LayoutScaleUtil.applyLayoutScale(newBase, s.layoutScale)
        super.attachBaseContext(scaledBase)
    }

    override val buchungMengeView: EditText?
        get() = null

    override val buchungProjektView: AutoCompleteTextView?
        get() = null

    override fun getLayoutId() = R.layout.activity_material_buchung

    override fun onCreate(savedInstanceState: Bundle?) {
        settings = AppSettings(this)

        when (settings.selectedTheme) {
            "dark" -> setTheme(R.style.Theme_MDE_Dark)
            "colorful" -> setTheme(R.style.Theme_MDE_Colorful)
            else -> setTheme(R.style.Theme_MDE_Light)
        }

        super.onCreate(savedInstanceState)

        FontScaleUtil.applyFontScale(findViewById(android.R.id.content), settings.fontScale)

        username = intent.getStringExtra("USERNAME") ?: "?"

        val btnEinlagern = findViewById<Button>(R.id.btnEinlagern)
        val btnAuslagern = findViewById<Button>(R.id.btnAuslagern)

        btnEinlagern.setOnClickListener {
            if (!btnEinlagern.isEnabled || !btnAuslagern.isEnabled) return@setOnClickListener
            openBookingDialogIfArticleValid(einlagern = true)
        }

        btnAuslagern.setOnClickListener {
            if (!btnAuslagern.isEnabled || !btnEinlagern.isEnabled) return@setOnClickListener
            openBookingDialogIfArticleValid(einlagern = false)
        }

        etFilter.setOnClickListener {
            if (hasValidSelectedArticle()) {
                clearSelectedArticleForManualInput()
            }
            etFilter.requestFocus()
            val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
            imm.showSoftInput(etFilter, InputMethodManager.SHOW_IMPLICIT)
        }

        val txtHeader = findViewById<TextView>(R.id.txtHeader)
        txtHeader.text = "BW MDE - Werk: ${settings.werkNummer}"
    }

    override fun onBarcodeScanned(barcode: String) {
        if (barcode.isBlank()) return
        if (hasValidSelectedArticle()) {
            clearSelectedArticleForScanOverride()
        }
        handleArtikelBarcodeScan(barcode)
    }

    private fun clearSelectedArticleForScanOverride() {
        clearInlineError()
        showEmptyArtikelInfo()
        etFilter.text.clear()
        textWatcherEnabled = true
    }

    private fun clearSelectedArticleForManualInput() {
        clearSelectedArticleForScanOverride()
        etFilter.isFocusable = true
        etFilter.isFocusableInTouchMode = true
        etFilter.isCursorVisible = true
        etFilter.keyListener = etFilterKeyListener
    }

    private fun hasValidSelectedArticle(): Boolean {
        val artikel = etFilter.text.toString().trim().split("|").firstOrNull()?.trim().orEmpty()
        if (artikel.isBlank()) return false
        return DataRepository.artikelListe.any { it.artNr.equals(artikel, ignoreCase = true) }
    }

    private fun normalizeProjektFilter(value: String): String {
        return value.lowercase().replace(Regex("[^a-z0-9]+"), "")
    }

    private fun sortProjekteWithRecents(projekte: List<String>): List<String> {
        val recent = DataRepository.recentProjektListe
        return projekte.sortedWith(
            compareBy<String> {
                val idx = recent.indexOf(it)
                if (idx >= 0) idx else Int.MAX_VALUE
            }.thenBy { it.lowercase() }
        )
    }

    private fun openBookingDialogIfArticleValid(einlagern: Boolean) {
        if (bookingDialogOpen) return

        if (!hasValidSelectedArticle()) {
            showErrorWithLoadingHelper("Bitte zuerst einen gültigen Artikel auswählen")
            return
        }
        showBookingDetailsDialog(einlagern)
    }

    private fun showBookingDetailsDialog(einlagern: Boolean) {
        if (bookingDialogOpen) return
        bookingDialogOpen = true

        val dialogView =
            LayoutInflater.from(this).inflate(R.layout.dialog_material_buchung_details, null)

        val etDialogProjekt = dialogView.findViewById<AutoCompleteTextView>(R.id.etDialogProjekt)
        val edtDialogMenge = dialogView.findViewById<EditText>(R.id.edtDialogMenge)
        val edtDialogSerials = dialogView.findViewById<TextView>(R.id.edtDialogSerials)
        val btnDialogSerials = dialogView.findViewById<Button>(R.id.btnDialogSerials)
        val btnDialogCancel = dialogView.findViewById<Button>(R.id.btnDialogCancel)
        val btnDialogOk = dialogView.findViewById<Button>(R.id.btnDialogOk)

        etDialogProjekt.setText("", false)
        edtDialogMenge.setText("")
        edtDialogSerials.text = ""

        if (DataRepository.projektListe.isNotEmpty()) {
            val sortedProjects = sortProjekteWithRecents(DataRepository.projektListe)
            val projektAdapter = object : ArrayAdapter<String>(
                this,
                android.R.layout.simple_dropdown_item_1line,
                sortedProjects.toMutableList()
            ) {
                private val allProjects = sortedProjects.toMutableList()

                override fun getFilter(): Filter {
                    return object : Filter() {
                        override fun performFiltering(constraint: CharSequence?): FilterResults {
                            val results = FilterResults()
                            val query = constraint?.toString()?.trim().orEmpty()
                            val normalizedQuery = normalizeProjektFilter(query)
                            val filtered = if (query.isBlank()) {
                                allProjects
                            } else {
                                allProjects.filter { project ->
                                    normalizeProjektFilter(project).contains(normalizedQuery)
                                }
                            }
                            results.values = filtered
                            results.count = filtered.size
                            return results
                        }

                        override fun publishResults(
                            constraint: CharSequence?,
                            results: FilterResults?
                        ) {
                            clear()
                            if (results?.values is List<*>) {
                                @Suppress("UNCHECKED_CAST")
                                addAll(results.values as List<String>)
                            }
                            notifyDataSetChanged()
                        }
                    }
                }
            }
            etDialogProjekt.setAdapter(projektAdapter)
            etDialogProjekt.threshold = 1
            etDialogProjekt.setOnClickListener {
                etDialogProjekt.showDropDown()
                val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
                imm.showSoftInput(etDialogProjekt, InputMethodManager.SHOW_IMPLICIT)
            }
            etDialogProjekt.setOnFocusChangeListener { _, hasFocus: Boolean ->
                if (hasFocus) etDialogProjekt.showDropDown()
            }
            etDialogProjekt.setOnItemClickListener { _, _, position, _ ->
                val projekt = etDialogProjekt.adapter.getItem(position).toString()
                DataRepository.rememberProjekt(projekt)
                edtDialogMenge.post {
                    edtDialogMenge.requestFocus()
                    val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
                    imm.showSoftInput(edtDialogMenge, InputMethodManager.SHOW_IMPLICIT)
                }
            }
        }

        val dialog = AlertDialog.Builder(this)
            .setTitle(if (einlagern) "Zubuchung erfassen" else "Entnahme erfassen")
            .setView(dialogView)
            .create()

        dialog.setOnDismissListener {
            bookingDialogOpen = false
        }
        dialog.setOnCancelListener {
            bookingDialogOpen = false
        }

        btnDialogSerials.setOnClickListener {
            val menge = edtDialogMenge.text.toString().trim().replace(",", ".").toDoubleOrNull()
            if (menge == null || menge <= 0.0) {
                showErrorWithLoadingHelper("Bitte zuerst eine gültige Menge eingeben")
                return@setOnClickListener
            }
            if (!isWholeNumber(menge)) {
                showErrorWithLoadingHelper("Für Seriennummern bitte eine ganze Menge eingeben")
                return@setOnClickListener
            }

            val mengeInt = menge.toInt()
            showSerialDialog(mengeInt) { serials, isCharge ->
                val value = formatSerialNumbers(serials, isCharge)
                edtDialogSerials.text = value
            }
        }

        btnDialogCancel.setOnClickListener {
            dialog.dismiss()
        }

        btnDialogOk.setOnClickListener {
            val projekt = etDialogProjekt.text.toString().trim()
            val menge = edtDialogMenge.text.toString().trim()
            val serials = edtDialogSerials.text.toString().trim()

            if (projekt.isBlank()) {
                showErrorWithLoadingHelper("Bitte ein Projekt eingeben")
                return@setOnClickListener
            }
            if (menge.isBlank()) {
                showErrorWithLoadingHelper("Bitte eine Menge eingeben")
                return@setOnClickListener
            }
            if (serials.isNotBlank()) {
                val mengeValue = menge.replace(",", ".").toDoubleOrNull()
                if (mengeValue == null || !isWholeNumber(mengeValue)) {
                    showErrorWithLoadingHelper("Für Seriennummern bitte eine ganze Menge eingeben")
                    return@setOnClickListener
                }
            }

            DataRepository.rememberProjekt(projekt)

            val started = doBuchenWithDetails(
                einlagern = einlagern,
                projektText = projekt,
                mengeText = menge,
                serialsText = serials
            )
            if (started) {
                dialog.dismiss()
            }
        }

        dialog.show()
        dialog.window?.setLayout(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        )

        etDialogProjekt.post {
            etDialogProjekt.requestFocus()
            etDialogProjekt.showDropDown()
        }
    }

    private fun formatSerialNumbers(serials: List<String>, isCharge: Boolean): String {
        if (serials.isEmpty()) return ""
        if (isCharge) {
            val nr = serials.first().trim()
            return if (nr.isBlank()) "" else "$CHARGE_PREFIX$nr"
        }
        return serials.joinToString(";") { it.trim() }
    }

    private fun isWholeNumber(value: Double): Boolean = value % 1.0 == 0.0
}
