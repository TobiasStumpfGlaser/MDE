package com.example.mde

import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.widget.*
import androidx.appcompat.app.AlertDialog

class MaterialBuchungActivity : BaseArtikelScanActivity() {

    companion object {
        private const val CHARGE_PREFIX = "Charge:"
    }

    private lateinit var settings: AppSettings
    private lateinit var username: String

    // LayoutScale wirkt nur, wenn es VOR dem Layout-Inflate gesetzt wird -> attachBaseContext
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
            openBookingDialogIfArticleValid(einlagern = true)
        }

        btnAuslagern.setOnClickListener {
            if (!btnAuslagern.isEnabled || !btnEinlagern.isEnabled) return@setOnClickListener
            openBookingDialogIfArticleValid(einlagern = false)
        }

        val txtHeader = findViewById<TextView>(R.id.txtHeader)
        txtHeader.text = "BW MDE - Werk: ${settings.werkNummer}"
    }

    private fun hasValidSelectedArticle(): Boolean {
        val artikel = etFilter.text.toString().trim().split("|").firstOrNull()?.trim().orEmpty()
        if (artikel.isBlank()) return false
        return DataRepository.artikelListe.any { it.artNr.equals(artikel, ignoreCase = true) }
    }

    private fun openBookingDialogIfArticleValid(einlagern: Boolean) {
        if (!hasValidSelectedArticle()) {
            showErrorWithLoadingHelper("Bitte zuerst einen gültigen Artikel auswählen")
            return
        }
        showBookingDetailsDialog(einlagern)
    }

    private fun showBookingDetailsDialog(einlagern: Boolean) {
        val dialogView =
            LayoutInflater.from(this).inflate(R.layout.dialog_material_buchung_details, null)

        val tvDialogActionInfo = dialogView.findViewById<TextView>(R.id.tvDialogActionInfo)
        val etDialogProjekt = dialogView.findViewById<AutoCompleteTextView>(R.id.etDialogProjekt)
        val edtDialogMenge = dialogView.findViewById<EditText>(R.id.edtDialogMenge)
        val edtDialogSerials = dialogView.findViewById<EditText>(R.id.edtDialogSerials)
        val btnDialogSerials = dialogView.findViewById<Button>(R.id.btnDialogSerials)

        val actionText = if (einlagern) "Zubuchung" else "Entnahme"
        tvDialogActionInfo.text = "Buchungsart: $actionText"

        if (DataRepository.projektListe.isNotEmpty()) {
            etDialogProjekt.setAdapter(
                ArrayAdapter(
                    this,
                    android.R.layout.simple_dropdown_item_1line,
                    DataRepository.projektListe
                )
            )
            etDialogProjekt.threshold = 1
            etDialogProjekt.setOnClickListener { etDialogProjekt.showDropDown() }
        }

        DataRepository.recentProjektListe.firstOrNull()?.let { recent ->
            etDialogProjekt.setText(recent, false)
        }

        btnDialogSerials.setOnClickListener {
            val menge = edtDialogMenge.text.toString().trim().replace(",", ".").toDoubleOrNull()
            if (menge == null || menge <= 0.0) {
                showErrorWithLoadingHelper("Bitte zuerst eine gültige Menge eingeben")
                return@setOnClickListener
            }
            if (menge != menge.toInt().toDouble()) {
                showErrorWithLoadingHelper("Für Seriennummern bitte eine ganze Menge eingeben")
                return@setOnClickListener
            }
            val mengeInt = menge.toInt()
            showSerialDialog(mengeInt) { serials, isCharge ->
                val value = formatSerialNumbers(serials, isCharge)
                edtDialogSerials.setText(value)
                edtDialogSerials.setSelection(edtDialogSerials.text.length)
            }
        }

        val dialog = AlertDialog.Builder(this)
            .setTitle(if (einlagern) "Zubuchung erfassen" else "Entnahme erfassen")
            .setView(dialogView)
            .setPositiveButton("OK", null)
            .setNegativeButton("Abbrechen") { d, _ -> d.dismiss() }
            .create()

        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
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

                val started = doBuchenWithDetails(
                    einlagern = einlagern,
                    projektText = projekt,
                    mengeText = menge,
                    serialsText = serials
                )
                if (started) {
                    DataRepository.rememberProjekt(projekt)
                    dialog.dismiss()
                }
            }
        }

        dialog.show()
    }

    /**
     * Formatiert Seriennummern für die Buchung:
     * - Charge-Modus: genau ein Eintrag als `Charge:<Wert>`
     * - Normalmodus: Einträge als `;`-getrennte Liste
     */
    private fun formatSerialNumbers(serials: List<String>, isCharge: Boolean): String {
        if (serials.isEmpty()) return ""
        if (isCharge) {
            val nr = serials.first().trim()
            return if (nr.isBlank()) "" else "$CHARGE_PREFIX$nr"
        }
        return serials.joinToString(";") { it.trim() }
    }
}