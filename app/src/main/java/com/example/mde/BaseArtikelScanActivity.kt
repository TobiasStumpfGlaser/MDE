package com.example.mde

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.MenuItem
import android.view.MotionEvent
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import com.example.mde.model.Artikel
import kotlinx.coroutines.*
import android.text.SpannableString
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.graphics.Typeface
import android.text.SpannableStringBuilder
import java.util.*

abstract class BaseArtikelScanActivity : AppCompatActivity() {

    abstract fun getLayoutId(): Int

    protected lateinit var etFilterKeyListener: android.text.method.KeyListener

    protected lateinit var btnScan: Button
    protected lateinit var etFilter: AutoCompleteTextView
    protected lateinit var tvArtikelInfo: TextView
    protected lateinit var btnClear: Button
    protected lateinit var btnReloadArtikel: Button

    protected var artikelListe: List<Artikel> = emptyList()
    protected var projektListe: List<String> = emptyList()

    protected lateinit var adapter: ArrayAdapter<String>

    private lateinit var handler: Handler
    private lateinit var timeoutRunnable: Runnable
    private var logoutTimeoutMillis = 0L

    private var requestRunning = false
    private val ioScope = CoroutineScope(Dispatchers.IO)

    protected var textWatcherEnabled = true

    /** --- Abstrakte Buchungs-Views, Child müssen definieren --- */
    protected abstract val buchungProjektView: AutoCompleteTextView?
    protected abstract val buchungMengeView: EditText?
    protected abstract val buchungStatusView: TextView?

    /** --- Child müssen implementieren --- */
    protected abstract fun getSettings(): AppSettings
    protected abstract fun getUsername(): String
    protected abstract fun getWerkNummer(): String

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(getLayoutId())

        setupToolbar()
        setupTimeout()
        setupViews()
        setupDropdown()

        loadArtikelUndProjekteSequential()
    }

    private fun setupToolbar() {
        val toolbar = findViewById<Toolbar>(R.id.toolbar)
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        toolbar.navigationIcon?.setTint(resources.getColor(android.R.color.white, theme))
    }

    private fun setupTimeout() {
        val settings = AppSettings(this)
        logoutTimeoutMillis = settings.logoutTimeSec * 1000L
        handler = Handler(Looper.getMainLooper())
        timeoutRunnable = Runnable {
            startActivity(Intent(this, LoginActivity::class.java))
            finish()
        }
    }

    private fun setupViews() {
        etFilter = findViewById(R.id.etBarcode) ?: AutoCompleteTextView(this).apply { visibility = View.GONE }
        tvArtikelInfo = findViewById(R.id.tvArtikelInfo) ?: TextView(this).apply { visibility = View.GONE }
        btnClear = findViewById(R.id.btnClear) ?: Button(this).apply { visibility = View.GONE }
        btnReloadArtikel = findViewById(R.id.btnReloadArtikel) ?: Button(this).apply { visibility = View.GONE }
        btnScan = findViewById(R.id.btnScan) ?: Button(this).apply { visibility = View.GONE }

        etFilterKeyListener = etFilter.keyListener

        btnClear.setOnClickListener { btnClearClicked() }
        btnReloadArtikel.setOnClickListener {
            btnClearClicked()
            loadArtikelUndProjekteSequential()
        }
        btnScan.setOnClickListener {
            val intent = Intent(this, ScannerActivity::class.java)
            startActivityForResult(intent, 1001)
        }
    }

    open fun btnClearClicked() {
        tvArtikelInfo.text = ""
        etFilter.text.clear()
        etFilter.isFocusable = true
        etFilter.isFocusableInTouchMode = true
        etFilter.keyListener = etFilterKeyListener
        textWatcherEnabled = true

        // Child Buchungs-Views optional leeren
        buchungProjektView?.text?.clear()
        buchungMengeView?.text?.clear()
        buchungStatusView?.text = ""
    }

    protected fun loadArtikelUndProjekteSequential() {

        if (requestRunning) return
        requestRunning = true
        UiLoadingHelper.show(this, "Lade Artikelliste...")

        ioScope.launch {
            try {
                val settings = AppSettings(this@BaseArtikelScanActivity)

                val artikelResponse = TcpClient.sendCommand(
                    context = this@BaseArtikelScanActivity,
                    settings = settings,
                    command = "GetArtikel",
                    request = "{GetArtikel}",
                    endTag = "{/GetArtikel}"
                )

                val artikel = parseArtikelResponse(artikelResponse)

                withContext(Dispatchers.Main) {
                    artikelListe = artikel
                    if (!::adapter.isInitialized) {
                        adapter = ArrayAdapter(
                            this@BaseArtikelScanActivity,
                            android.R.layout.simple_dropdown_item_1line,
                            mutableListOf()
                        )
                        etFilter.setAdapter(adapter)
                    }
                    adapter.clear()
                    adapter.addAll(artikel.map { "${it.artNr} | ${it.bez}" })
                    adapter.notifyDataSetChanged()
                    UiLoadingHelper.show(this@BaseArtikelScanActivity, "Lade Projekte...")
                }

                val projekteResponse = TcpClient.sendCommand(
                    context = this@BaseArtikelScanActivity,
                    settings = settings,
                    command = "GetProjekte",
                    request = "{GetProjekte}",
                    endTag = "{/GetProjekte}"
                )

                val projekte = parseProjektList(projekteResponse)

                withContext(Dispatchers.Main) {
                    projektListe = projekte
                    UiLoadingHelper.hide()
                    requestRunning = false
                    onProjekteGeladen()
                }

            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    UiLoadingHelper.hide()
                    requestRunning = false
                    showReloadDialog(e.message ?: "Server Fehler")
                }
            }
        }
    }

    protected open fun onProjekteGeladen() {}

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 1001 && resultCode == RESULT_OK) {
            val barcode = data?.getStringExtra("barcode")?.trim()
            if (!barcode.isNullOrEmpty()) {
                val matchedArtikel = artikelListe.find { it.artNr == barcode }
                etFilter.setText(barcode)
                etFilter.setSelection(barcode.length)

                if (matchedArtikel == null) {
                    tvArtikelInfo.text = "⚠ Kein Artikel gefunden!"
                    tvArtikelInfo.setTextColor(Color.RED)
                } else {
                    tvArtikelInfo.setTextColor(Color.WHITE)
                    showArtikelInfo(matchedArtikel)
                    etFilter.clearFocus()
                    etFilter.isFocusable = false
                    etFilter.isFocusableInTouchMode = false
                    etFilter.keyListener = null
                }
            }
        }
    }

    protected fun showArtikelInfo(artikel: Artikel) {

        val infoLines = listOf(
            "Artikelnummer: ${artikel.artNr}",
            "Bezeichnung: ${artikel.bez}",
            "Lagerorte W1: ${artikel.lagerorteW1.joinToString(", ")}",
            "Lagerorte W2: ${artikel.lagerorteW2.joinToString(", ")}",
            "Maßeinheit: ${artikel.masseinheit}",
            "Bestand: ${artikel.bestand}",
            "Mindestbestand: ${artikel.mindestbestand}",
            "Empf. Bestellmenge: ${artikel.empfBestMenge}",
            "Bestell-Trigger: ${artikel.bestellTrigger}",
            "Groß-Info: ${artikel.grossInfo}",
            "LiefBestNr: ${artikel.liefBestNr}"
        )

        val finalSpannable = SpannableStringBuilder()

        infoLines.forEachIndexed { index, line ->
            val spannable = SpannableString(line + if (index < infoLines.size - 1) "\n" else "")
            val colonIndex = line.indexOf(":")
            if (colonIndex != -1) {
                spannable.setSpan(StyleSpan(Typeface.BOLD), 0, colonIndex, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
            spannable.setSpan(ForegroundColorSpan(Color.WHITE), 0, spannable.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            finalSpannable.append(spannable)
        }

        tvArtikelInfo.text = finalSpannable
    }

    private fun setupDropdown() {

        adapter = ArrayAdapter(this, android.R.layout.simple_dropdown_item_1line, mutableListOf())
        etFilter.setAdapter(adapter)
        etFilter.threshold = 1

        etFilter.setOnItemClickListener { _, _, position, _ ->
            val selected = adapter.getItem(position) ?: return@setOnItemClickListener
            val artNr = selected.split("|")[0].trim()
            val artikel = artikelListe.find { it.artNr == artNr }
            artikel?.let { showArtikelInfo(it) }

            textWatcherEnabled = false
            etFilter.setText(selected)
            etFilter.setSelection(selected.length)
            textWatcherEnabled = true

            val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
            imm.hideSoftInputFromWindow(etFilter.windowToken, 0)

            etFilter.clearFocus()
            etFilter.isFocusable = false
            etFilter.isFocusableInTouchMode = false
            etFilter.keyListener = null
        }

        etFilter.addTextChangedListener(object : android.text.TextWatcher {
            override fun afterTextChanged(s: android.text.Editable?) {
                if (!textWatcherEnabled) return
                val input = s.toString().trim()
                if (input.isEmpty()) {
                    tvArtikelInfo.text = ""
                    return
                }
                val filterText = input.split("|")[0].trim()
                val matches = artikelListe.filter { it.artNr.contains(filterText, true) || it.bez.contains(filterText, true) }
                when (matches.size) {
                    0 -> {
                        tvArtikelInfo.text = "⚠ Kein Artikel gefunden!"
                        tvArtikelInfo.setTextColor(Color.RED)
                    }
                    1 -> {
                        val artikel = matches.first()
                        showArtikelInfo(artikel)
                        val text = "${artikel.artNr} | ${artikel.bez}"
                        textWatcherEnabled = false
                        etFilter.setText(text)
                        etFilter.setSelection(text.length)
                        textWatcherEnabled = true
                        val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
                        imm.hideSoftInputFromWindow(etFilter.windowToken, 0)
                        etFilter.clearFocus()
                        etFilter.isFocusable = false
                        etFilter.isFocusableInTouchMode = false
                        etFilter.keyListener = null
                    }
                    else -> {
                        tvArtikelInfo.text = ""
                        etFilter.showDropDown()
                    }
                }
            }

            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
        })
    }

    protected fun showError(msg: String) {
        runOnUiThread {
            AlertDialog.Builder(this)
                .setTitle("Fehler")
                .setMessage(msg)
                .setPositiveButton("OK", null)
                .show()
        }
    }

    private fun showReloadDialog(message: String) {
        AlertDialog.Builder(this)
            .setTitle("Serverfehler")
            .setMessage("$message\n\nErneut versuchen?")
            .setPositiveButton("Ja") { _, _ ->
                btnClearClicked()
                loadArtikelUndProjekteSequential()
            }
            .setNegativeButton("Nein", null)
            .setCancelable(false)
            .show()
    }

    private fun parseProjektList(raw: String): List<String> {
        val list = mutableListOf<String>()
        raw.lines().forEach {
            val parts = it.split("|")
            if (parts.size == 2 && !it.startsWith("{")) list.add("${parts[0]} – ${parts[1]}")
        }
        return list
    }

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
                    if (p.size < 15) return@forEach
                    liste.add(
                        Artikel(
                            artNr = p[0],
                            bez = p[1],
                            lagerorteW1 = p.subList(2, 5),
                            lagerorteW2 = p.subList(5, 8),
                            masseinheit = p[8],
                            bestand = p[9],
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

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) {
            finish()
            return true
        }
        return super.onOptionsItemSelected(item)
    }

    /** --- Buchung wiederverwendbar --- */
    protected fun doBuchen() {

        val artikel = etFilter.text.toString().trim()
        val projekt = buchungProjektView?.text?.toString()?.trim()
        val mengeStr = buchungMengeView?.text?.toString()?.trim()

        buchungStatusView?.text = ""

        if (artikel.isBlank() || projekt.isNullOrBlank() || mengeStr.isNullOrBlank()) {
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
                sendBuchung(artikel, projekt, serverMenge)
            }
            .setNegativeButton("Abbrechen", null)
            .show()
    }

    private fun sendBuchung(artikel: String, projekt: String, menge: String) {

        val statusView = buchungStatusView

        CoroutineScope(Dispatchers.IO).launch {
            try {
                val now = java.text.SimpleDateFormat("dd.MM.yyyy HH:mm:ss", Locale.GERMANY).format(Date())

                val request = """
                    {SetBuchung}
                    $artikel||$menge|FORMULAR|$projekt|${getWerkNummer()}|${getUsername()}|$now|
                    {/SetBuchung}
                """.trimIndent()

                TcpLogHelper.logRequest(this@BaseArtikelScanActivity, "SetBuchung", request)

                val response = TcpClient.sendCommand(
                    context = this@BaseArtikelScanActivity,
                    settings = getSettings(),
                    command = "SetBuchung",
                    request = request,
                    endTag = "{/SetBuchung}"
                )

                TcpLogHelper.logResponse(this@BaseArtikelScanActivity, "SetBuchung", response)

                withContext(Dispatchers.Main) {
                    val cleaned = response.replace("\r", "").trim()
                    if (cleaned == "{SetBuchung}\nok\n{/SetBuchung}") {
                        statusView?.text = "✅ Buchung erfolgreich"
                    } else {
                        showError("Buchung fehlgeschlagen:\n$response")
                    }
                }

            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    statusView?.text = "❌ Verbindungsfehler"
                }
            }
        }
    }
}