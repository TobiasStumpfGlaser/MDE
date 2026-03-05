package com.example.mde

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.MenuItem
import android.view.MotionEvent
import android.view.inputmethod.InputMethodManager
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import kotlinx.coroutines.*
import com.example.mde.model.Artikel
import android.text.SpannableString
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.graphics.Typeface
import android.text.SpannableStringBuilder

abstract class BaseArtikelScanActivity : AppCompatActivity() {

    abstract fun getLayoutId(): Int

    protected lateinit var etFilterKeyListener: android.text.method.KeyListener

    protected lateinit var btnScan: Button
    protected lateinit var etFilter: AutoCompleteTextView
    protected lateinit var tvArtikelInfo: TextView
    protected lateinit var btnClear: Button
    protected lateinit var btnReloadArtikel: Button

    protected var artikelListe: List<Artikel> = emptyList()
    protected lateinit var adapter: ArrayAdapter<String>

    private lateinit var handler: Handler
    private lateinit var timeoutRunnable: Runnable
    private var logoutTimeoutMillis = 0L

    private var requestRunning = false
    private val ioScope = CoroutineScope(Dispatchers.IO)

    // TextWatcher kontrollieren, damit er temporär deaktiviert werden kann
    private var textWatcherEnabled = true

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(getLayoutId())

        setupToolbar()
        setupTimeout()
        setupViews()
        setupDropdown()

        loadArtikelList()
    }

    private fun setupToolbar() {
        val toolbar = findViewById<Toolbar>(R.id.toolbar)
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        // Weißer Pfeil
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
        etFilter = findViewById(R.id.etBarcode)
        etFilterKeyListener = etFilter.keyListener
        tvArtikelInfo = findViewById(R.id.tvArtikelInfo)
        btnClear = findViewById(R.id.btnClear)
        btnReloadArtikel = findViewById(R.id.btnReloadArtikel)
        btnScan = findViewById(R.id.btnScan)

        btnClear.setOnClickListener {
            tvArtikelInfo.text = ""
            etFilter.text.clear()
            etFilter.isFocusable = true
            etFilter.isFocusableInTouchMode = true
            etFilter.keyListener = etFilterKeyListener

            // TextWatcher wieder aktivieren
            textWatcherEnabled = true
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
            val barcode = data?.getStringExtra("barcode")?.trim()
            if (!barcode.isNullOrEmpty()) {
                val matchedArtikel = artikelListe.find { it.artNr == barcode }

                // Barcode immer ins EditText setzen
                etFilter.setText(barcode)
                etFilter.setSelection(barcode.length)

                if (matchedArtikel == null) {
                    tvArtikelInfo.text = "⚠ Kein Artikel gefunden!"
                    tvArtikelInfo.setTextColor(Color.RED)
                } else {
                    tvArtikelInfo.setTextColor(Color.WHITE)
                    showArtikelInfo(matchedArtikel)

                    // Optional: EditText readonly nach Auswahl
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
            "Lagerorte: ${artikel.lagerorte.joinToString(", ")}",
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
                // Links vom ":" fett
                spannable.setSpan(
                    StyleSpan(Typeface.BOLD),
                    0,
                    colonIndex,
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                )
            }
            // Gesamte Zeile weiß
            spannable.setSpan(
                ForegroundColorSpan(Color.WHITE),
                0,
                spannable.length,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
            )

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

            // Temporär TextWatcher deaktivieren
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
                val matches = artikelListe.filter {
                    it.artNr.contains(filterText, true) ||
                            it.bez.contains(filterText, true)
                }

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

    private fun loadArtikelList() {
        if (requestRunning) return
        requestRunning = true
        UiLoadingHelper.show(this, "Lade Artikelliste...")

        ioScope.launch {
            try {
                val settings = AppSettings(this@BaseArtikelScanActivity)
                val response = TcpClient.sendCommand(
                    context = this@BaseArtikelScanActivity,
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

                        etFilter.isFocusable = true
                        etFilter.isFocusableInTouchMode = true
                        etFilter.requestFocus()
                        etFilter.setSelection(etFilter.text.length)
                    }
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

    private fun showReloadDialog(message: String) {
        AlertDialog.Builder(this)
            .setTitle("Serverfehler")
            .setMessage("$message\n\nErneut versuchen?")
            .setPositiveButton("Ja") { _, _ -> loadArtikelList() }
            .setNegativeButton("Nein", null)
            .setCancelable(false)
            .show()
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
}