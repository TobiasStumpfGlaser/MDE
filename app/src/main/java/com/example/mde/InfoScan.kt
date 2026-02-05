package com.example.mde

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.MenuItem
import android.view.MotionEvent
import android.widget.ArrayAdapter
import android.widget.AutoCompleteTextView
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar

data class Artikel(
    val artNr: String,
    val bez: String,
    val lagerorte: List<String>,
    val bestand: Int,
    val empfBestMenge: Int,
    val bestellTrigger: Int,
    val mindestbestand: Int,
    val grossInfo: String,
    val liefBestNr: String
)

class InfoScanActivity : AppCompatActivity() {

    private lateinit var etFilter: AutoCompleteTextView
    private lateinit var tvArtikelInfo: TextView
    private lateinit var btnClear: Button
    private lateinit var btnReloadArtikel: Button
    private lateinit var handler: Handler
    private lateinit var timeoutRunnable: Runnable
    private var timeoutMillis = 0L
    private var artikelListe: List<Artikel> = listOf()
    private lateinit var adapter: ArrayAdapter<String>

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_info_scan)

        // Toolbar
        val toolbar = findViewById<Toolbar>(R.id.toolbar)
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        toolbar.navigationIcon?.setColorFilter(
            resources.getColor(android.R.color.white, theme),
            android.graphics.PorterDuff.Mode.SRC_ATOP
        )

        timeoutMillis = AppSettings(this).logoutTimeSec * 1000L
        handler = Handler(Looper.getMainLooper())
        timeoutRunnable = Runnable {
            startActivity(Intent(this, LoginActivity::class.java))
            finish()
        }

        // Views
        etFilter = findViewById(R.id.etBarcode)
        tvArtikelInfo = findViewById(R.id.tvArtikelInfo)
        btnClear = findViewById(R.id.btnClear)
        btnReloadArtikel = findViewById(R.id.btnReloadArtikel)

        // Adapter für Dropdown
        adapter = ArrayAdapter(this, android.R.layout.simple_dropdown_item_1line, mutableListOf())
        etFilter.setAdapter(adapter)
        etFilter.threshold = 1

        // Wenn ein Element ausgewählt wird → Info anzeigen
        etFilter.setOnItemClickListener { _, _, position, _ ->
            val selectedText = adapter.getItem(position)
            val artikel = artikelListe.find { it.artNr + " | " + it.bez == selectedText }
            artikel?.let {
                tvArtikelInfo.text = """
                    Artikel: ${it.artNr}
                    Bezeichnung: ${it.bez}
                    Lagerorte: ${it.lagerorte.joinToString(", ")}
                    Bestand: ${it.bestand}
                    Mindestbestand: ${it.mindestbestand}
                    Bestell-Menge: ${it.empfBestMenge}
                    Groß-Info: ${it.grossInfo}
                    LiefBestNr: ${it.liefBestNr}
                """.trimIndent()
            }
        }

        // Clear-Button
        btnClear.setOnClickListener {
            val infoText = tvArtikelInfo.text.toString()
            if (infoText.isNotBlank() &&
                !infoText.contains("Keine Verbindung") &&
                !infoText.contains("Fehler")
            ) {
                tvArtikelInfo.text = ""
            }
            etFilter.text.clear()
            etFilter.showDropDown()
        }

        btnReloadArtikel.setOnClickListener {
            loadArtikelList()
        }
        loadArtikelList()
    }

    private fun loadArtikelList() {
        fetchArtikelList(
            onSuccess = { liste ->
                if (liste.isEmpty()) {
                    tvArtikelInfo.text = "Keine Artikel vom Server erhalten."
                    return@fetchArtikelList
                }

                artikelListe = liste
                val items = artikelListe.map { "${it.artNr} | ${it.bez}" }
                adapter.clear()
                adapter.addAll(items)
                adapter.notifyDataSetChanged()
                tvArtikelInfo.text = ""
            },
            onFailure = { message ->
                tvArtikelInfo.text = "Fehler beim Laden der Artikel:\n$message"
            }
        )
    }

    // Timeout / Inaktivität
    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        resetTimeout()
        return super.dispatchTouchEvent(ev)
    }

    private fun resetTimeout() {
        handler.removeCallbacks(timeoutRunnable)
        handler.postDelayed(timeoutRunnable, timeoutMillis)
    }

    override fun onResume() { super.onResume(); resetTimeout() }
    override fun onPause() { super.onPause(); handler.removeCallbacks(timeoutRunnable) }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) { finish(); return true }
        return super.onOptionsItemSelected(item)
    }

    // Netzwerk
    private fun fetchArtikelList(onSuccess: (List<Artikel>) -> Unit, onFailure: (String) -> Unit) {
        Thread {
            try {
                val socket = java.net.Socket(AppSettings(this).serverIp, AppSettings(this).serverPort)
                socket.soTimeout = AppSettings(this).timeoutS * 1000
                val writer = socket.getOutputStream()
                val reader = socket.getInputStream().bufferedReader()

                writer.write("{GetArtikel}\n".toByteArray())
                writer.flush()

                val response = StringBuilder()
                var line: String?
                while (reader.readLine().also { line = it } != null) {
                    response.append(line).append("\n")
                    if (line!!.contains("{/GetArtikel}")) break
                }
                socket.close()

                val liste = parseArtikelResponse(response.toString())
                runOnUiThread { onSuccess(liste) }

            } catch (e: Exception) {
                runOnUiThread { onFailure(e.message ?: "Unbekannter Fehler") }
            }
        }.start()
    }

    private fun parseArtikelResponse(raw: String): List<Artikel> {
        val liste = mutableListOf<Artikel>()
        val lines = raw.lines()
        var startParsing = false
        for (line in lines) {
            if (line.contains("{GetArtikel}")) { startParsing = true; continue }
            if (line.contains("{/GetArtikel}")) break
            if (!startParsing || line.isBlank()) continue
            val parts = line.split("|")
            if (parts.size < 14) continue
            val lagerorte = parts.subList(2, 8)
            liste.add(
                Artikel(
                    artNr = parts[0],
                    bez = parts[1],
                    lagerorte = lagerorte,
                    bestand = parts[8].toIntOrNull() ?: 0,
                    empfBestMenge = parts[9].toIntOrNull() ?: 0,
                    bestellTrigger = parts[10].toIntOrNull() ?: 0,
                    mindestbestand = parts[11].toIntOrNull() ?: 0,
                    grossInfo = parts[12],
                    liefBestNr = parts[13]
                )
            )
        }
        return liste
    }
}
