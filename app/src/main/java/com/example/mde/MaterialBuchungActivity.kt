package com.example.mde

import android.app.AlertDialog
import android.os.Bundle
import android.widget.*
import kotlinx.coroutines.*
import java.text.SimpleDateFormat
import java.util.*

class MaterialBuchungActivity : BaseArtikelScanActivity() {

    private lateinit var txtProjekt: AutoCompleteTextView
    private lateinit var edtMenge: EditText
    private lateinit var btnBuchen: Button
    private lateinit var txtStatus: TextView

    private lateinit var projektAdapter: ArrayAdapter<String>

    private lateinit var settings: AppSettings
    private lateinit var username: String

    override fun getLayoutId(): Int = R.layout.activity_material_buchung

    override fun onCreate(savedInstanceState: Bundle?) {

        super.onCreate(savedInstanceState)

        settings = AppSettings(this)
        username = intent.getStringExtra("USERNAME") ?: "?"

        txtProjekt = findViewById(R.id.txtProjekt)
        edtMenge = findViewById(R.id.edtMenge)
        btnBuchen = findViewById(R.id.btnBuchen)
        txtStatus = findViewById(R.id.txtStatus)

        projektAdapter = ArrayAdapter(
            this,
            android.R.layout.simple_dropdown_item_1line,
            mutableListOf()
        )

        txtProjekt.setAdapter(projektAdapter)
        txtProjekt.threshold = 1

        txtProjekt.setOnClickListener {
            txtProjekt.showDropDown()
        }

        btnBuchen.setOnClickListener {
            onBuchen()
        }

    }

    override fun btnClearClicked() {
        super.btnClearClicked()

        txtProjekt.text.clear()
        edtMenge.text.clear()
        txtStatus.text = ""
    }

    override fun onProjekteGeladen() {

        projektAdapter.clear()
        projektAdapter.addAll(projektListe)
        projektAdapter.notifyDataSetChanged()

        txtStatus.text = "✅ Daten aktualisiert"
    }

    private fun onBuchen() {

        val artikel = etFilter.text.toString().trim()
        val projekt = txtProjekt.text.toString().trim()
        val mengeStr = edtMenge.text.toString().trim()

        txtStatus.text = ""

        if (artikel.isBlank() || projekt.isBlank() || mengeStr.isBlank()) {
            showError("Bitte alle Felder ausfüllen")
            return
        }

        val menge = mengeStr.replace(",", ".").toDoubleOrNull()

        if (menge == null || menge == 0.0) {
            showError("Ungültige Menge")
            return
        }

        AlertDialog.Builder(this)
            .setTitle("Buchung bestätigen")
            .setMessage("Artikel: $artikel\nProjekt: $projekt\nMenge: $menge")
            .setPositiveButton("Buchen") { _, _ ->

                val serverMenge = mengeStr.replace(".", ",")

                sendBuchung(
                    artikel,
                    projekt,
                    serverMenge
                )
            }
            .setNegativeButton("Abbrechen", null)
            .show()
    }

    private fun sendBuchung(
        artikel: String,
        projekt: String,
        menge: String
    ) {

        CoroutineScope(Dispatchers.IO).launch {

            try {

                val now = SimpleDateFormat(
                    "dd.MM.yyyy HH:mm:ss",
                    Locale.GERMANY
                ).format(Date())

                val request = """
                    {SetBuchung}
                    $artikel||$menge|FORMULAR|$projekt|${settings.werkNummer}|$username|$now|
                    {/SetBuchung}
                """.trimIndent()

                TcpLogHelper.logRequest(
                    this@MaterialBuchungActivity,
                    "SetBuchung",
                    request
                )

                val response = TcpClient.sendCommand(
                    context = this@MaterialBuchungActivity,
                    settings = settings,
                    command = "SetBuchung",
                    request = request,
                    endTag = "{/SetBuchung}"
                )

                TcpLogHelper.logResponse(
                    this@MaterialBuchungActivity,
                    "SetBuchung",
                    response
                )

                withContext(Dispatchers.Main) {
                    val cleaned = response.replace("\r", "").trim()
                    if (cleaned == "{SetBuchung}\nok\n{/SetBuchung}") {
                        txtStatus.text = "✅ Buchung erfolgreich"
                    } else {
                        showError("Buchung fehlgeschlagen:\n$response")
                    }
                }

            } catch (e: Exception) {

                withContext(Dispatchers.Main) {
                    txtStatus.text = "❌ Verbindungsfehler"
                }
            }
        }
    }
}