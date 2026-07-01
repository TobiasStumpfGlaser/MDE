package com.example.mde

import android.graphics.Typeface
import android.os.Bundle
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.StyleSpan
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.widget.ArrayAdapter
import android.widget.AutoCompleteTextView
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.Filter
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import com.example.mde.model.Artikel

/**
 * Inventur-Activity.
 *
 * Ermöglicht das Scannen und manuelle Auswählen eines Artikels sowie die anschließende
 * Mengenzählung per [doBuchen]. Erbt die Artikel-Lade- und Scanner-Logik von
 * [BaseArtikelScanActivity].
 */
open class BestellungActivity : BaseArtikelScanActivity() {
    private lateinit var settings: AppSettings
    private lateinit var username: String

    private var bestellDialogOpen = false

    override val buchungMengeView: EditText
        get() = edtMenge

    override val buchungProjektView: AutoCompleteTextView?
        get() = null

    override fun getLayoutId() = R.layout.activity_bestellung

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

        val btnBestell= findViewById<Button>(R.id.btnBestell)
        btnBestell.setOnClickListener { openBestellDialogIfArticleValid() }
    }

    private fun hasValidSelectedArticle(): Boolean {
        val artikel = etFilter.text.toString().trim()
            .split("|")
            .firstOrNull()
            ?.trim()
            .orEmpty()

        if (artikel.isBlank()) return false

        return DataRepository.artikelListe.any {
            it.artNr.equals(artikel, ignoreCase = true)
        }
    }

    override fun showArtikelInfo(artikel: Artikel) {
        val infoLines = listOf(
            "Artikelnummer: ${artikel.artNr}",
            "Bezeichnung: ${artikel.bez}",
            "Bestellt 3M: ${artikel.bestellt3M}",
            "Bestellt 6M: ${artikel.bestellt6M}",
            "Handlager: ${if (artikel.handLager) "Ja" else "Nein"}",
            "Bestand: ${artikel.bestand}",
            "Mindestbestand: ${artikel.mindestbestand}",
            "Groß-Info: ${artikel.grossInfo}"
        )

        val finalSpannable = SpannableStringBuilder()
        infoLines.forEachIndexed { index, line ->
            val lineStart = finalSpannable.length
            finalSpannable.append(line)
            if (index < infoLines.size - 1) finalSpannable.append("\n")

            val colonIndex = line.indexOf(":")
            if (colonIndex != -1) {
                finalSpannable.setSpan(
                    StyleSpan(Typeface.BOLD),
                    lineStart,
                    lineStart + colonIndex,
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                )
            }
        }

        tvArtikelInfo.text = finalSpannable
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

    private fun openBestellDialogIfArticleValid() {
        if (!hasValidSelectedArticle()) {
            showErrorWithLoadingHelper("Bitte zuerst einen gültigen Artikel auswählen")
            return
        }

        showBestellDetailsDialog()
    }

    private fun showBestellDetailsDialog() {
        if (bestellDialogOpen) return
        bestellDialogOpen = true

        val dialogView =
            LayoutInflater.from(this).inflate(R.layout.dialog_bestell_details, null)

        val etDialogProjekt = dialogView.findViewById<AutoCompleteTextView>(R.id.etDialogProjekt)
        val edtDialogMenge = dialogView.findViewById<EditText>(R.id.edtDialogMenge)
        val cbEilig = dialogView.findViewById<CheckBox>(R.id.cbEilig)
        val btnDialogCancel = dialogView.findViewById<Button>(R.id.btnDialogCancel)
        val btnDialogOk = dialogView.findViewById<Button>(R.id.btnDialogOk)

        etDialogProjekt.setText("", false)
        edtDialogMenge.setText("")

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
            .setTitle("Bestellung erfassen")
            .setView(dialogView)
            .create()

        dialog.setOnDismissListener {
            bestellDialogOpen = false
        }
        dialog.setOnCancelListener {
            bestellDialogOpen = false
        }

        btnDialogCancel.setOnClickListener {
            dialog.dismiss()
        }

        btnDialogOk.setOnClickListener {
            val projekt = etDialogProjekt.text.toString().trim()
            val menge = edtDialogMenge.text.toString().trim()
            val eilig = cbEilig.isChecked

            if (projekt.isBlank()) {
                showErrorWithLoadingHelper("Bitte ein Projekt eingeben")
                return@setOnClickListener
            }
            if (menge.isBlank()) {
                showErrorWithLoadingHelper("Bitte eine Menge eingeben")
                return@setOnClickListener
            }

            DataRepository.rememberProjekt(projekt)

            val started = doBestellenWithDetails(null, projektText=projekt, mengeText=menge, eilig)
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

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        resetLogoutTimer()
        return super.dispatchTouchEvent(ev)
    }
}
