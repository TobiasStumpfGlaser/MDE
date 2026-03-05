package com.example.mde

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.view.inputmethod.InputMethodManager
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
import kotlinx.coroutines.*
import java.net.ConnectException
import java.net.SocketTimeoutException

// =======================
// Datenmodell
// =======================
data class Artikel(
    val artNr: String,
    val bez: String,
    val lagerorte: List<String>,
    val masseinheit: String,
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

    private lateinit var etFilterKeyListener: android.text.method.KeyListener
    // Views
    private lateinit var btnScan: Button
    private lateinit var etFilter: AutoCompleteTextView
    private lateinit var tvArtikelInfo: TextView
    private lateinit var btnClear: Button
    private lateinit var btnReloadArtikel: Button

    // Artikel
    private var artikelListe: List<Artikel> = emptyList()
    private lateinit var adapter: ArrayAdapter<String>

    // Timeout / Inaktivität
    private lateinit var handler: Handler
    private lateinit var timeoutRunnable: Runnable
    private var logoutTimeoutMillis = 0L

    // TCP / Anfrage Kontrolle
    private var requestRunning = false
    private val ioScope = CoroutineScope(Dispatchers.IO)

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
        etFilterKeyListener = etFilter.keyListener  // <- hier speichern
        tvArtikelInfo = findViewById(R.id.tvArtikelInfo)
        btnClear = findViewById(R.id.btnClear)
        btnReloadArtikel = findViewById(R.id.btnReloadArtikel)
        btnScan = findViewById(R.id.btnScan)

        etFilter.setOnItemClickListener { parent, view, position, id ->
            val selected = adapter.getItem(position) ?: return@setOnItemClickListener

            // Artikel auswählen
            val artikel = artikelListe.find { "${it.artNr} | ${it.bez}" == selected }
            artikel?.let {
                tvArtikelInfo.text = """
            Artikelnummer: ${it.artNr}
            Bezeichnung: ${it.bez}
            Lagerorte: ${it.lagerorte.joinToString(", ")}
            Maßeinheit: ${it.masseinheit}
            Bestand: ${it.bestand}
            Mindestbestand: ${it.mindestbestand}
            Empf. Bestellmenge: ${it.empfBestMenge}
            Bestell-Trigger: ${it.bestellTrigger}
            Groß-Info: ${it.grossInfo}
            LiefBestNr: ${it.liefBestNr}
        """.trimIndent()
            }

            // Tastatur ausblenden
            val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
            imm.hideSoftInputFromWindow(etFilter.windowToken, 0)
        }

        btnClear.setOnClickListener {
            tvArtikelInfo.text = ""
            etFilter.text.clear()

            // Feld wieder editierbar machen
            etFilter.isFocusable = true
            etFilter.isFocusableInTouchMode = true
            etFilter.keyListener = etFilterKeyListener
        }

        btnReloadArtikel.setOnClickListener {
            loadArtikelList()
        }

        btnScan.setOnClickListener {
            val intent = Intent(this, ScannerActivity::class.java)
            startActivityForResult(intent, 1001)
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 1001 && resultCode == RESULT_OK) {
            val barcode = data?.getStringExtra("barcode")
            if (!barcode.isNullOrEmpty()) {
                val matchedArtikel = artikelListe.find { it.artNr == barcode }
                if (matchedArtikel == null) {
                    tvArtikelInfo.text = "⚠ Kein Artikel gefunden!"
                    tvArtikelInfo.setTextColor(Color.RED)
                } else {
                    tvArtikelInfo.text = ""
                }
                etFilter.setText(barcode)
                etFilter.showDropDown()
                matchedArtikel?.let { showArtikelInfo(it) }
            }
        }
    }

    private fun showArtikelInfo(artikel: Artikel) {
        tvArtikelInfo.text = """
            Artikel: ${artikel.artNr}
            Bezeichnung: ${artikel.bez}
            Lagerorte: ${artikel.lagerorte.joinToString(", ")}
            Maßeinheit: ${artikel.masseinheit}
            Bestand: ${artikel.bestand}
            Mindestbestand: ${artikel.mindestbestand}
            Bestell-Menge: ${artikel.empfBestMenge}
            Groß-Info: ${artikel.grossInfo}
            LiefBestNr: ${artikel.liefBestNr}
        """.trimIndent()
    }

    // =======================
    // Dropdown / Filter
    // =======================
    private fun setupDropdown() {
        adapter = ArrayAdapter(this, android.R.layout.simple_dropdown_item_1line, mutableListOf())
        etFilter.setAdapter(adapter)
        etFilter.threshold = 1

        // Dropdown normal nutzbar
        etFilter.setOnItemClickListener { _, _, position, _ ->
            val selected = adapter.getItem(position) ?: return@setOnItemClickListener
            val artikel = artikelListe.find { "${it.artNr} | ${it.bez}" == selected }
            artikel?.let { showArtikelInfo(it) }

            // Nach Auswahl: Tastatur schließen
            val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
            imm.hideSoftInputFromWindow(etFilter.windowToken, 0)

            // Fokus entfernen → Cursor verschwindet
            etFilter.clearFocus()

            // Feld readonly machen
            etFilter.isFocusable = false
            etFilter.isFocusableInTouchMode = false
            etFilter.keyListener = null
        }
    }

    // =======================
    // Artikel laden (TCP, nur 1 Anfrage gleichzeitig)
    // =======================
    private fun loadArtikelList() {
        if (requestRunning) return
        requestRunning = true
        UiLoadingHelper.show(this, "Lade Artikelliste...")

        ioScope.launch {
            try {
                val settings = AppSettings(this@InfoScanActivity)

                val response = TcpClient.sendCommand(
                    context = this@InfoScanActivity,
                    settings = settings,
                    command = "GetArtikel",
                    request = "{GetArtikel}",
                    endTag = "{/GetArtikel}"
                )

                val liste = parseArtikelResponse(response)

                withContext(Dispatchers.Main) {
                    UiLoadingHelper.hide()
                    requestRunning = false

                    if (liste.isEmpty()) {
                        showReloadDialog("Keine Artikel vom Server erhalten")
                    } else {
                        artikelListe = liste
                        adapter.clear()
                        adapter.addAll(liste.map { "${it.artNr} | ${it.bez}" })
                        adapter.notifyDataSetChanged()
                        tvArtikelInfo.text = ""
                    }
                }

            } catch (e: SocketTimeoutException) {
                withContext(Dispatchers.Main) {
                    UiLoadingHelper.hide()
                    requestRunning = false
                    showReloadDialog("Server-Zeitüberschreitung (${AppSettings(this@InfoScanActivity).timeoutS}s)")
                }
            } catch (e: ConnectException) {
                withContext(Dispatchers.Main) {
                    UiLoadingHelper.hide()
                    requestRunning = false
                    showReloadDialog("Server nicht erreichbar")
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    UiLoadingHelper.hide()
                    requestRunning = false
                    showReloadDialog(e.message ?: "Unbekannter Fehler")
                }
            }
        }
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
                            masseinheit = p[8],
                            bestand = p[9].toIntOrNull() ?: 0,
                            empfBestMenge = p[10].toIntOrNull() ?: 0,
                            bestellTrigger = p[11].toIntOrNull() ?: 0,
                            mindestbestand = p[12].toIntOrNull() ?: 0,
                            grossInfo = p[13],
                            liefBestNr = p[14]
                        )
                    )
                }
            }
        }
        return liste
    }

    // =======================
    // Timeout Handling
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

    private fun showReloadDialog(message: String) {
        AlertDialog.Builder(this)
            .setTitle("Serverfehler")
            .setMessage("$message\n\nErneut versuchen?")
            .setPositiveButton("Ja") { _, _ -> loadArtikelList() }
            .setNegativeButton("Nein") { _, _ -> tvArtikelInfo.text = "Keine Verbindung zum Server" }
            .setCancelable(false)
            .show()
    }
}