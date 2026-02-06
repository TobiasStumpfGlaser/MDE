package com.example.mde

import android.app.AlertDialog
import android.content.Intent
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

    private lateinit var settings: AppSettings
    private lateinit var txtArtikel: AutoCompleteTextView
    private lateinit var txtProjekt: AutoCompleteTextView
    private lateinit var edtMenge: EditText
    private lateinit var btnBuchen: Button
    private lateinit var btnClear: Button
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

        btnClear.setOnClickListener {
            txtArtikel.text.clear()
            txtProjekt.text.clear()
            edtMenge.text.clear()
        }
    }

    /* ======================= LOAD ARTIKEL ======================= */

    private fun loadArtikel() {
        ioScope.launch {
            try {
                val response = sendCommand("{GetArtikel}")
                val list = parseSimpleList(response)
                runOnUiThread {
                    artikelListe.clear()
                    artikelListe.addAll(list)
                    artikelAdapter.notifyDataSetChanged()
                }
            } catch (e: Exception) {
                showError("Artikel konnten nicht geladen werden")
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
                val response = sendCommand("{GetProjekte}")
                val list = parseProjektList(response)
                runOnUiThread {
                    projektListe.clear()
                    projektListe.addAll(list)
                    projektAdapter.notifyDataSetChanged()
                }
            } catch (e: Exception) {
                showError("Projekte konnten nicht geladen werden")
            }
        }
    }

    /* ======================= BUCHEN ======================= */

    private fun onBuchen() {
        val artikel = txtArtikel.text.toString()
        val projekt = txtProjekt.text.toString()
        val mengeStr = edtMenge.text.toString()

        if (artikel.isBlank() || projekt.isBlank() || mengeStr.isBlank()) {
            showError("Bitte alle Felder ausfüllen")
            return
        }

        val menge = mengeStr.toIntOrNull()
        if (menge == null || menge == 0) {
            showError("Ungültige Menge")
            return
        }

        AlertDialog.Builder(this)
            .setTitle("Buchung bestätigen")
            .setMessage("Artikel:\n$artikel\n\nProjekt:\n$projekt\n\nMenge: $menge")
            .setPositiveButton("Buchen") { _, _ ->
                sendBuchung(artikel, projekt, menge)
            }
            .setNegativeButton("Abbrechen", null)
            .show()
    }

    private fun sendBuchung(artikel: String, projekt: String, menge: Int) {
        ioScope.launch {
            try {
                val now = SimpleDateFormat("dd.MM.yyyy HH:mm:ss", Locale.GERMANY).format(Date())

                val payload = """
{SetBuchungen}
$artikel||$menge|FORMULAR|$projekt|10|$username|$now|
{/SetBuchungen}
""".trimIndent()

                val response = sendCommand(payload)

                runOnUiThread {
                    if (response.trim() == "ok") {
                        txtStatus.text = "✅ Buchung erfolgreich"
                    } else {
                        showError(response)
                        txtStatus.text = "❌ Buchung fehlgeschlagen"
                    }
                }

            } catch (e: Exception) {
                runOnUiThread {
                    showRetryDialog(artikel, projekt, menge)
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

    private fun showRetryDialog(artikel: String, projekt: String, menge: Int) {
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
