package com.example.mde

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.MenuItem
import android.view.MotionEvent
import android.widget.ArrayAdapter
import android.widget.AutoCompleteTextView
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import java.net.ConnectException
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException

// =======================
// Datenmodell
// =======================
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

// =======================
// Activity
// =======================
class InfoScanActivity : AppCompatActivity() {

    // Views
    private lateinit var btnScan: Button
    private lateinit var etFilter: AutoCompleteTextView
    private lateinit var tvArtikelInfo: TextView
    private lateinit var btnClear: Button
    private lateinit var btnReloadArtikel: Button

    // Artikel
    private var artikelListe: List<Artikel> = emptyList()
    private lateinit var adapter: ArrayAdapter<String>

    // Inaktivitäts-Timeout
    private lateinit var handler: Handler
    private lateinit var timeoutRunnable: Runnable
    private var logoutTimeoutMillis = 0L

    // =======================
    // Lifecycle
    // =======================
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_info_scan)

        setupToolbar()
        setupTimeout()
        setupViews()
        setupDropdown()

        // Initial laden
        loadArtikelList()
    }

    // =======================
    // Toolbar
    // =======================
    private fun setupToolbar() {
        val toolbar = findViewById<Toolbar>(R.id.toolbar)
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        toolbar.navigationIcon?.setColorFilter(
            resources.getColor(android.R.color.white, theme),
            android.graphics.PorterDuff.Mode.SRC_ATOP
        )
    }

    // =======================
    // Timeout (User)
    // =======================
    private fun setupTimeout() {
        val settings = AppSettings(this)
        logoutTimeoutMillis = settings.logoutTimeSec * 1000L

        handler = Handler(Looper.getMainLooper())
        timeoutRunnable = Runnable {
            startActivity(Intent(this, LoginActivity::class.java))
            finish()
        }
    }

    // =======================
    // Views
    // =======================
    private fun setupViews() {
        etFilter = findViewById(R.id.etBarcode)
        tvArtikelInfo = findViewById(R.id.tvArtikelInfo)
        btnClear = findViewById(R.id.btnClear)
        btnReloadArtikel = findViewById(R.id.btnReloadArtikel)
        btnScan = findViewById(R.id.btnScan)

        btnClear.setOnClickListener {
            if (tvArtikelInfo.text.isNotBlank()) {
                tvArtikelInfo.text = ""
            }
            etFilter.text.clear()
        }

        btnReloadArtikel.setOnClickListener {
            loadArtikelList()
        }

        btnScan.setOnClickListener {
            val intent = Intent(this, ScannerActivity::class.java)
            startActivityForResult(intent, 1001)
        }
    }

        // Ergebnis abfangen
        override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
            super.onActivityResult(requestCode, resultCode, data)
            if (requestCode == 1001 && resultCode == RESULT_OK) {
                val barcode = data?.getStringExtra("barcode")
                if (!barcode.isNullOrEmpty()) {
                    // Prüfen, ob der Barcode in der Liste vorhanden ist
                    val matchedArtikel = artikelListe.find { it.artNr == barcode }
                    if (matchedArtikel == null) {
                        tvArtikelInfo.text = "⚠ Kein Artikel gefunden!"
                        tvArtikelInfo.setTextColor(Color.RED)
                    } else {
                        tvArtikelInfo.text = ""
                    }
                    etFilter.setText(barcode)
                    etFilter.showDropDown()
                    val artikel = artikelListe.find { it.artNr == barcode }
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
            }
        }

    // =======================
    // Dropdown / Filter
    // =======================
    private fun setupDropdown() {
        adapter = ArrayAdapter(
            this,
            android.R.layout.simple_dropdown_item_1line,
            mutableListOf()
        )
        etFilter.setAdapter(adapter)
        etFilter.threshold = 1

        etFilter.setOnItemClickListener { _, _, position, _ ->
            val selected = adapter.getItem(position) ?: return@setOnItemClickListener
            val artikel = artikelListe.find { "${it.artNr} | ${it.bez}" == selected }

            artikel?.let {
                tvArtikelInfo.text = """
                    Artikelnummer: ${it.artNr}
                    Bezeichnung: ${it.bez}
                    Lagerorte: ${it.lagerorte.joinToString(", ")}
                    Bestand: ${it.bestand}
                    Mindestbestand: ${it.mindestbestand}
                    Empf. Bestellmenge: ${it.empfBestMenge}
                    Bestell-Trigger: ${it.bestellTrigger}
                    Groß-Info: ${it.grossInfo}
                    LiefBestNr: ${it.liefBestNr}
                """.trimIndent()
            }
        }
    }

    // =======================
    // Artikel laden
    // =======================
    private fun loadArtikelList() {
        fetchArtikelList(
            onSuccess = { liste ->
                artikelListe = liste
                adapter.clear()
                adapter.addAll(liste.map { "${it.artNr} | ${it.bez}" })
                adapter.notifyDataSetChanged()
                tvArtikelInfo.text = ""
            },
            onFailure = { message ->
                showReloadDialog(message)
            }
        )
    }

    // =======================
    // Dialog
    // =======================
    private fun showReloadDialog(message: String) {
        AlertDialog.Builder(this)
            .setTitle("Serverfehler")
            .setMessage("$message\n\nErneut versuchen?")
            .setPositiveButton("Ja") { _, _ -> loadArtikelList() }
            .setNegativeButton("Nein") { _, _ ->
                tvArtikelInfo.text = "Keine Verbindung zum Server"
            }
            .setCancelable(false)
            .show()
    }

    // =======================
    // Netzwerk
    // =======================
    private fun fetchArtikelList(
        onSuccess: (List<Artikel>) -> Unit,
        onFailure: (String) -> Unit
    ) {
        Thread {
            try {
                val settings = AppSettings(this)
                val serverTimeout = settings.timeoutS * 1000

                val socket = Socket()
                socket.connect(
                    InetSocketAddress(settings.serverIp, settings.serverPort),
                    serverTimeout
                )
                socket.soTimeout = serverTimeout

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

                runOnUiThread {
                    if (liste.isEmpty()) {
                        onFailure("Keine Artikel vom Server erhalten")
                    } else {
                        onSuccess(liste)
                    }
                }

            } catch (e: SocketTimeoutException) {
                runOnUiThread {
                    onFailure("Server-Zeitüberschreitung (${AppSettings(this).timeoutS}s)")
                }
            } catch (e: ConnectException) {
                runOnUiThread {
                    onFailure("Server nicht erreichbar")
                }
            } catch (e: Exception) {
                runOnUiThread {
                    onFailure(e.message ?: "Unbekannter Fehler")
                }
            }
        }.start()
    }

    // =======================
    // Parser
    // =======================
    private fun parseArtikelResponse(raw: String): List<Artikel> {
        val liste = mutableListOf<Artikel>()
        var parse = false

        raw.lines().forEach { line ->
            when {
                line.contains("{GetArtikel}") -> parse = true
                line.contains("{/GetArtikel}") -> return@forEach
                !parse || line.isBlank() -> return@forEach
                else -> {
                    val p = line.split("|")
                    if (p.size < 14) return@forEach

                    liste.add(
                        Artikel(
                            artNr = p[0],
                            bez = p[1],
                            lagerorte = p.subList(2, 8),
                            bestand = p[8].toIntOrNull() ?: 0,
                            empfBestMenge = p[9].toIntOrNull() ?: 0,
                            bestellTrigger = p[10].toIntOrNull() ?: 0,
                            mindestbestand = p[11].toIntOrNull() ?: 0,
                            grossInfo = p[12],
                            liefBestNr = p[13]
                        )
                    )
                }
            }
        }
        return liste
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

    // =======================
    // Navigation
    // =======================
    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) {
            finish()
            return true
        }
        return super.onOptionsItemSelected(item)
    }
}
