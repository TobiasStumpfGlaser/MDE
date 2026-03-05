package com.example.mde

import android.app.AlertDialog
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.MotionEvent
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import kotlinx.coroutines.*
import java.io.*
import java.net.InetSocketAddress
import java.net.Socket
import java.text.SimpleDateFormat
import java.util.*

class MaterialBuchungActivity : AppCompatActivity() {
    private var artikelGeladen = false
    private var projekteGeladen = false
    private lateinit var settings: AppSettings
    private lateinit var txtArtikel: AutoCompleteTextView
    private lateinit var txtProjekt: AutoCompleteTextView
    private lateinit var edtMenge: EditText
    private lateinit var btnBuchen: Button
    private lateinit var btnClear: Button
    private lateinit var btnReloadData: Button
    private lateinit var txtStatus: TextView
    private lateinit var artikelAdapter: ArrayAdapter<String>
    private lateinit var projektAdapter: ArrayAdapter<String>
    private val artikelListe = mutableListOf<String>()
    private val projektListe = mutableListOf<String>()
    private val ioScope = CoroutineScope(Dispatchers.IO)
    private lateinit var username: String

    // Inaktivitäts-Timeout
    private lateinit var handler: Handler
    private lateinit var timeoutRunnable: Runnable
    private var logoutTimeoutMillis = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_material_buchung)

        settings = AppSettings(this)

        logoutTimeoutMillis = settings.logoutTimeSec * 1000L
        handler = Handler(Looper.getMainLooper())
        timeoutRunnable = Runnable {
            startActivity(Intent(this, LoginActivity::class.java))
            finish()
        }

        username = intent.getStringExtra("USERNAME") ?: "?"

        txtArtikel = findViewById(R.id.txtArtikel)
        txtProjekt = findViewById(R.id.txtProjekt)
        edtMenge = findViewById(R.id.edtMenge)
        btnBuchen = findViewById(R.id.btnBuchen)
        txtStatus = findViewById(R.id.txtStatus)
        btnClear = findViewById(R.id.btnClear)
        btnReloadData = findViewById(R.id.btnReloadData)
        val btnScanArtikel = findViewById<Button>(R.id.btnScanArtikel)
        btnScanArtikel.setOnClickListener {
            // ScannerActivity starten und Ergebnis erhalten
            val intent = Intent(this, ScannerActivity::class.java)
            startActivityForResult(intent, 1001)
        }

        val toolbar = findViewById<Toolbar>(R.id.toolbar)
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        toolbar.navigationIcon?.setColorFilter(
            resources.getColor(android.R.color.white, theme),
            android.graphics.PorterDuff.Mode.SRC_ATOP
        )

        artikelAdapter = ArrayAdapter(this, android.R.layout.simple_dropdown_item_1line, artikelListe)
        projektAdapter = ArrayAdapter(this, android.R.layout.simple_dropdown_item_1line, projektListe)

        txtArtikel.setAdapter(artikelAdapter)
        txtProjekt.setAdapter(projektAdapter)

        txtArtikel.threshold = 1
        txtProjekt.threshold = 1

        txtArtikel.setOnClickListener { txtArtikel.showDropDown() }
        txtProjekt.setOnClickListener { txtProjekt.showDropDown() }

        loadArtikel()
        loadProjekte()

        btnBuchen.setOnClickListener {
            onBuchen()
        }

        btnReloadData.setOnClickListener {
            onReloadData()
        }

        btnClear.setOnClickListener {
            txtArtikel.text.clear()
            txtProjekt.text.clear()
            txtStatus.text = ""
            edtMenge.text.clear()
        }
    }

    // Ergebnis abfangen
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 1001 && resultCode == RESULT_OK) {
            val barcode = data?.getStringExtra("barcode")
            if (!barcode.isNullOrEmpty()) {
                // Prüfen, ob der Barcode in der Liste vorhanden ist
                val matchedArtikel = artikelListe.find { it == barcode }
                txtArtikel.setText(barcode)
                txtArtikel.showDropDown()
                if (matchedArtikel == null) {
                    txtStatus.text = "⚠ Kein Artikel gefunden!"
                    txtStatus.setTextColor(Color.RED)
                } else {
                    txtStatus.text = ""
                }
            }
        }
    }

    /* ======================= LOAD ARTIKEL ======================= */
    private fun loadArtikel() {
        ioScope.launch {
            try {
                val request = "{GetArtikel}"
                val logfileName = "GetArtikel"
                TcpLogHelper.logRequest(this@MaterialBuchungActivity, logfileName, request)
                val response = sendCommand(request)
                TcpLogHelper.logResponse(this@MaterialBuchungActivity, logfileName, response)
                val list = parseSimpleList(response)
                runOnUiThread {
                    artikelListe.clear()
                    artikelListe.addAll(list)
                    artikelAdapter.notifyDataSetChanged()

                    artikelGeladen = true
                    checkDataLoaded()
                }
            } catch (e: Exception) {
                showError("Artikel konnten nicht geladen werden")
                txtStatus.text = "❌ Verbindungsfehler"
            }
        }
    }

    // =======================
    // Inaktivitäts-Handling
    // =======================
    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        resetTimeout()
        return super.dispatchTouchEvent(ev)
    }

    private fun resetTimeout() {
        handler.removeCallbacks(timeoutRunnable)
        handler.postDelayed(timeoutRunnable, logoutTimeoutMillis)
    }

    override fun onResume() {
        super.onResume()
        resetTimeout()
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(timeoutRunnable)
    }

    /* ======================= LOAD PROJEKTE ======================= */

    private fun loadProjekte() {
        ioScope.launch {
            try {
                val request = "{GetProjekte}"
                val logfileName = "GetProjekte"
                TcpLogHelper.logRequest(this@MaterialBuchungActivity, logfileName, request)
                val response = sendCommand(request)
                TcpLogHelper.logResponse(this@MaterialBuchungActivity, logfileName, response)
                val list = parseProjektList(response)
                runOnUiThread {
                    projektListe.clear()
                    projektListe.addAll(list)
                    projektAdapter.notifyDataSetChanged()

                    projekteGeladen = true
                    checkDataLoaded()
                }
            } catch (e: Exception) {
                showError("Projekte konnten nicht geladen werden")
                txtStatus.text = "❌ Verbindungsfehler"
            }
        }
    }
    /* ======================= RELOAD ======================= */
    private fun onReloadData() {
        loadArtikel()
        loadProjekte()
    }

    private fun checkDataLoaded() {
        if (artikelGeladen && projekteGeladen) {
            txtStatus.text = "✅ Daten aktualisiert"
        }
    }
    /* ======================= BUCHEN ======================= */

    private fun onBuchen() {
        txtStatus.text = ""
        val artikel = txtArtikel.text.toString()
        val projekt = txtProjekt.text.toString()
        val mengeStr = edtMenge.text.toString().trim()

        if (artikel.isBlank() || projekt.isBlank() || mengeStr.isBlank()) {
            showError("Bitte alle Felder ausfüllen")
            return
        }

        // Komma -> Punkt ersetzen
        val normalizedMenge = mengeStr.replace(",", ".")
        val serverMenge = mengeStr.replace(".", ",")
        val menge = normalizedMenge.toDoubleOrNull()
        if (menge == null || menge == 0.0) {
            showError("Ungültige Menge")
            return
        }

        AlertDialog.Builder(this)
            .setTitle("Buchung bestätigen")
            .setMessage("Artikel:\n$artikel\n\nProjekt:\n$projekt\n\nMenge: $menge")
            .setPositiveButton("Buchen") { _, _ ->
                sendBuchung(artikel, projekt, serverMenge)
            }
            .setNegativeButton("Abbrechen", null)
            .show()
    }

    private fun sendBuchung(artikel: String, projekt: String, menge: String) {
        ioScope.launch {
            try {
                val now = SimpleDateFormat("dd.MM.yyyy HH:mm:ss", Locale.GERMANY).format(Date())
                val request = """
                {SetBuchung}
                $artikel||$menge|FORMULAR|$projekt|10|$username|$now|
                {/SetBuchung}
                """.trimIndent()

                val logfileName = "SetBuchung"
                TcpLogHelper.logRequest(this@MaterialBuchungActivity, logfileName, request)
                val response = sendCommand(request)
                TcpLogHelper.logResponse(this@MaterialBuchungActivity, logfileName, response)
                runOnUiThread {
                    if (response.contains("{SetBuchung}\nok\n{/SetBuchung}")) {
                        txtStatus.text = "✅ Buchung erfolgreich"
                    } else {
                        showError(response)
                        txtStatus.text = "❌ Buchung fehlgeschlagen"
                    }
                }

            } catch (e: Exception) {
                runOnUiThread {
                    showRetryDialog(artikel, projekt, menge)
                    txtStatus.text = "❌ Verbindungsfehler"
                }
            }
        }
    }

    /* ======================= SOCKET ======================= */

    private fun sendCommand(command: String): String {
        val socket = Socket()
        socket.connect(
            InetSocketAddress(settings.serverIp, settings.serverPort),
            settings.timeoutS * 1000
        )
        socket.soTimeout = settings.timeoutS * 1000

        val writer = PrintWriter(BufferedWriter(OutputStreamWriter(socket.getOutputStream())), true)
        val reader = BufferedReader(InputStreamReader(socket.getInputStream()))

        writer.println(command)

        val response = StringBuilder()
        var line: String?
        while (reader.readLine().also { line = it } != null) {
            response.append(line).append("\n")
            if (line!!.startsWith("{/")) break
        }

        socket.close()
        return response.toString()
    }

    /* ======================= PARSING ======================= */

    private fun parseSimpleList(raw: String): List<String> =
        raw.lines().filter { it.contains("|") && !it.startsWith("{") }

    private fun parseProjektList(raw: String): List<String> {
        val list = mutableListOf<String>()
        raw.lines().forEach {
            val parts = it.split("|")
            if (parts.size == 2 && !it.startsWith("{")) {
                list.add("${parts[0]} – ${parts[1]}")
            }
        }
        return list
    }

    /* ======================= UI ======================= */

    private fun showError(msg: String) {
        runOnUiThread {
            AlertDialog.Builder(this)
                .setTitle("Fehler")
                .setMessage(msg)
                .setPositiveButton("OK", null)
                .show()
        }
    }

    private fun showRetryDialog(artikel: String, projekt: String, menge: String) {
        AlertDialog.Builder(this)
            .setTitle("Timeout")
            .setMessage("Buchung erneut senden?")
            .setPositiveButton("Erneut") { _, _ ->
                sendBuchung(artikel, projekt, menge)
            }
            .setNegativeButton("Abbrechen", null)
            .show()
    }
}
