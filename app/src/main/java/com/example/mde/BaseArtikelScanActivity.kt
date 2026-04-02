package com.example.mde

import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.*
import android.text.style.StyleSpan
import android.view.MenuItem
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import com.example.mde.model.Artikel
import androidx.compose.ui.graphics.Color
import kotlinx.coroutines.*
import java.util.*

class ArtikelAdapter(context: Context, artikelListe: List<Artikel>) :
    ArrayAdapter<Artikel>(
        context,
        android.R.layout.simple_dropdown_item_1line,
        artikelListe.toMutableList()
    ) {

    private val allItems: MutableList<Artikel> = artikelListe.toMutableList()

    fun updateList(newListe: List<Artikel>) {
        allItems.clear()
        allItems.addAll(newListe)
        clear()
        addAll(allItems)
        notifyDataSetChanged()
    }

    override fun getFilter(): Filter {
        return object : Filter() {
            override fun performFiltering(constraint: CharSequence?): FilterResults {
                val results = FilterResults()
                val filtered: List<Artikel> = if (constraint.isNullOrBlank()) {
                    allItems
                } else {
                    val query = constraint.toString().lowercase()
                    allItems.filter {
                        it.artNr.lowercase().contains(query) || it.bez.lowercase().contains(query)
                    }
                }
                results.values = filtered.toMutableList()
                results.count = filtered.size
                return results
            }

            override fun publishResults(constraint: CharSequence?, results: FilterResults?) {
                clear()
                if (results?.values is List<*>) {
                    @Suppress("UNCHECKED_CAST")
                    addAll((results.values as List<Artikel>).toMutableList())
                }
                notifyDataSetChanged()
            }
        }
    }

    override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
        val view = super.getView(position, convertView, parent) as TextView
        val artikel = getItem(position)
        view.text = "${artikel?.artNr} | ${artikel?.bez}"
        return view
    }
}

object DataRepository {
    var artikelListe: List<Artikel> = emptyList()
    var projektListe: List<String> = emptyList()
    var recentProjektListe: MutableList<String> = mutableListOf()

    fun isLoaded(): Boolean = artikelListe.isNotEmpty() && projektListe.isNotEmpty()

    var lastLoadTime: Long = 0

    fun shouldReload(): Boolean {
        val time = 60 * 60 * 1000
        return artikelListe.isEmpty() ||
                projektListe.isEmpty() ||
                System.currentTimeMillis() - lastLoadTime > time
    }

    fun clear() {
        artikelListe = emptyList()
        projektListe = emptyList()
        recentProjektListe.clear()
    }

    fun rememberProjekt(projekt: String, maxEntries: Int = 8) {
        if (projekt.isBlank()) return
        recentProjektListe.remove(projekt)
        recentProjektListe.add(0, projekt)
        while (recentProjektListe.size > maxEntries) {
            recentProjektListe.removeAt(recentProjektListe.lastIndex)
        }
    }
}

abstract class BaseArtikelScanActivity : AppCompatActivity() {

    abstract fun getLayoutId(): Int

    protected lateinit var etFilterKeyListener: android.text.method.KeyListener
    protected lateinit var btnScan: Button
    protected lateinit var etFilter: AutoCompleteTextView
    protected lateinit var etProjekt: AutoCompleteTextView
    protected lateinit var edtMenge: EditText
    protected lateinit var edtSerials: EditText
    protected lateinit var tvArtikelInfo: TextView
    protected lateinit var btnClear: Button
    protected lateinit var btnReloadArtikel: ImageButton

    protected var artikelListe: List<Artikel>
        get() = DataRepository.artikelListe
        set(value) {
            DataRepository.artikelListe = value
            DataRepository.lastLoadTime = System.currentTimeMillis()
        }

    protected var projektListe: List<String>
        get() = DataRepository.projektListe
        set(value) {
            DataRepository.projektListe = value
            DataRepository.lastLoadTime = System.currentTimeMillis()
        }

    protected lateinit var adapter: ArtikelAdapter
    private lateinit var handler: Handler
    private lateinit var timeoutRunnable: Runnable
    private var logoutTimeoutMillis = 0L
    private var requestRunning = false
    private val ioScope = CoroutineScope(Dispatchers.IO)
    protected var textWatcherEnabled = true

    protected abstract val buchungProjektView: AutoCompleteTextView?
    protected abstract val buchungMengeView: EditText?
    protected open val autoLoadArtikelUndProjekte: Boolean = true

    private var lastBookingTime = 0L
    private val bookingCooldown = 2000L

    override fun onCreate(savedInstanceState: Bundle?) {
        val settings = AppSettings(this)
        when (settings.selectedTheme) {
            "dark" -> setTheme(R.style.Theme_MDE_Dark)
            "colorful" -> setTheme(R.style.Theme_MDE_Colorful)
            else -> setTheme(R.style.Theme_MDE_Light)
        }

        super.onCreate(savedInstanceState)

        setContentView(getLayoutId())

        setupToolbar()
        setupTimeout()
        setupViews()
        setupDropdown()

        if (autoLoadArtikelUndProjekte) {
            if (DataRepository.shouldReload()) {
                loadArtikelUndProjekteSequential()
            } else {
                setupProjektAdapter()
            }
        }

        etFilter.requestFocus()
        etFilter.setSelection(0)
    }

    private fun setupToolbar() {
        val toolbar = findViewById<Toolbar>(R.id.toolbar)
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayShowTitleEnabled(false)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
    }

    private fun setupTimeout() {
        val settings = AppSettings(this)
        logoutTimeoutMillis = settings.logoutTimeSec * 1000L
        handler = Handler(Looper.getMainLooper())
        timeoutRunnable = Runnable {
            startActivity(Intent(this, LoginActivity::class.java))
            overridePendingTransition(0, 0)
            finish()
        }
    }

    private fun setupViews() {
        etFilter = findViewById(R.id.etBarcode) ?: AutoCompleteTextView(this).apply { visibility = View.GONE }
        tvArtikelInfo = findViewById(R.id.tvArtikelInfo) ?: TextView(this).apply { visibility = View.GONE }
        btnClear = findViewById(R.id.btnClear) ?: Button(this).apply { visibility = View.GONE }
        btnReloadArtikel = findViewById(R.id.btnReloadArtikel) ?: ImageButton(this).apply { visibility = View.GONE }
        btnScan = findViewById(R.id.btnScan) ?: Button(this).apply { visibility = View.GONE }
        etProjekt = findViewById(R.id.txtProjekt) ?: AutoCompleteTextView(this).apply { visibility = View.GONE }
        edtMenge = findViewById(R.id.edtMenge) ?: EditText(this).apply { visibility = View.GONE }
        edtSerials = findViewById(R.id.edtSerials) ?: EditText(this).apply { visibility = View.GONE }

        edtSerials.apply {
            isFocusable = false
            isClickable = true
            isCursorVisible = false
            keyListener = null
            setTextIsSelectable(false)
            setOnClickListener {
                val mengeInt = edtMenge.text.toString().trim().toIntOrNull() ?: 0
                if (mengeInt <= 0) {
                    showError("Bitte zuerst eine gültige Menge eingeben")
                    return@setOnClickListener
                }
                showSerialDialog(mengeInt) { serials ->
                    setText(serials.joinToString(";"))
                    setSelection(text.length)
                }
            }
        }
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

    protected fun showSerialDialog(maxMenge: Int, onSerialsConfirmed: (List<String>) -> Unit) {
        val builder = AlertDialog.Builder(this)
        builder.setTitle("Seriennummern hinzufügen")
        val layout = layoutInflater.inflate(R.layout.dialog_serials, null)
        val etSerial = layout.findViewById<AutoCompleteTextView>(R.id.etSerial)
        val tvSerialList = layout.findViewById<TextView>(R.id.tvSerialList)
        val btnAdd = layout.findViewById<View>(R.id.btnAddSerial)
        etSerial.setSingleLine(true)
        etSerial.imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_DONE
        val serials = mutableListOf<String>()
        tvSerialList.text = "0 / $maxMenge"
        btnAdd.visibility = View.GONE
        builder.setView(layout)
        builder.setPositiveButton("OK") { _, _ -> onSerialsConfirmed(serials) }
        builder.setNegativeButton("Abbrechen", null)
        val dialog = builder.create()
        dialog.show()

        etSerial.requestFocus()
        etSerial.post {
            val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
            imm.showSoftInput(etSerial, InputMethodManager.SHOW_IMPLICIT)
        }

        dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled = false

        fun tryAddSerial(input: String) {
            when {
                serials.contains(input) -> onSerialError("Seriennummer bereits vorhanden")
                serials.size >= maxMenge -> onSerialError("Maximale Menge erreicht")
                else -> {
                    serials.add(input)
                    tvSerialList.text = "${serials.size} / $maxMenge\n${serials.joinToString("\n")}"
                    dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled = serials.size == maxMenge
                }
            }
        }

        etSerial.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) {
                val text = s.toString()
                if (text.contains("\n") || text.contains("\r")) {
                    val cleaned = text.replace("\n", "").replace("\r", "").trim()
                    if (cleaned.isNotEmpty()) tryAddSerial(cleaned)
                    etSerial.text.clear()
                }
                btnAdd.visibility = if (s.isNullOrBlank()) View.GONE else View.VISIBLE
            }
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
        })

        etSerial.setOnEditorActionListener { _, actionId, event ->
            if (actionId == android.view.inputmethod.EditorInfo.IME_ACTION_DONE ||
                event?.keyCode == android.view.KeyEvent.KEYCODE_ENTER
            ) {
                val input = etSerial.text.toString().trim()
                if (input.isNotEmpty()) tryAddSerial(input)
                etSerial.text.clear()
                true
            } else false
        }

        btnAdd.setOnClickListener {
            val input = etSerial.text.toString().trim()
            if (input.isNotEmpty()) tryAddSerial(input)
            etSerial.text.clear()
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled = serials.size == maxMenge
        }
    }

    protected open fun onSerialError(message: String) {
        UiLoadingHelper.playErrorSound(this)
        showMessageDialog(message)
    }

    protected fun showMessageDialog(message: String) {
        AlertDialog.Builder(this).setMessage(message).setPositiveButton("OK", null).show()
    }

    protected fun ensureArtikelLoaded(onLoaded: () -> Unit) {
        if (DataRepository.artikelListe.isNotEmpty()) {
            onLoaded()
            return
        }
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val settings = AppSettings(this@BaseArtikelScanActivity)
                val response = TcpClient.sendCommand(
                    context = this@BaseArtikelScanActivity,
                    settings = settings,
                    command = "GetArtikel",
                    request = "{GetArtikel}",
                    endTag = "{/GetArtikel}"
                )
                val artikel = parseArtikelResponse(response)
                withContext(Dispatchers.Main) {
                    artikelListe = artikel
                    onLoaded()
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    showMessageDialog("Fehler beim Laden der Artikelliste:\n${e.message}")
                }
            }
        }
    }

    open fun btnClearClicked() {
        tvArtikelInfo.text = ""
        edtSerials.text.clear()
        etFilter.text.clear()
        etFilter.isFocusable = true
        etFilter.isFocusableInTouchMode = true
        etFilter.keyListener = etFilterKeyListener
        textWatcherEnabled = true
        buchungProjektView?.text?.clear()
        buchungMengeView?.text?.clear()
        etFilter.requestFocus()
        etFilter.setSelection(0)
    }

    protected fun loadArtikelUndProjekteSequential() {
        if (requestRunning) return
        requestRunning = true
        UiLoadingHelper.show(this, "Lade Serverdaten...", UiLoadingHelper.LoadingStatus.LOADING)

        ioScope.launch {
            val settings = AppSettings(this@BaseArtikelScanActivity)
            var success = false
            var attempts = 0

            while (attempts < 3 && !success) {
                attempts++
                try {
                    withContext(Dispatchers.Main) {
                        UiLoadingHelper.update(
                            this@BaseArtikelScanActivity,
                            "Lade Serverdaten... Versuch $attempts/3",
                            UiLoadingHelper.LoadingStatus.LOADING
                        )
                    }

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
                            adapter = ArtikelAdapter(this@BaseArtikelScanActivity, artikelListe)
                            etFilter.setAdapter(adapter)
                        } else {
                            adapter.updateList(artikelListe)
                        }
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
                        setupProjektAdapter()
                    }

                    success = artikelListe.isNotEmpty() && projektListe.isNotEmpty()
                } catch (_: Exception) {
                    delay(500)
                }
            }

            withContext(Dispatchers.Main) {
                requestRunning = false
                UiLoadingHelper.update(
                    this@BaseArtikelScanActivity,
                    if (success) "Daten aktualisiert" else "Fehler Server Kommunikation nach 3 Versuchen",
                    if (success) UiLoadingHelper.LoadingStatus.SUCCESS else UiLoadingHelper.LoadingStatus.ERROR
                )
            }
        }
    }

    private fun sortProjekteWithRecents(projekte: List<String>): List<String> {
        val recent = DataRepository.recentProjektListe
        return projekte.sortedWith(
            compareBy<String> {
                val idx = recent.indexOf(it)
                if (idx >= 0) idx else Int.MAX_VALUE
            }.thenBy { it.lowercase() }
        )
    }

    private fun setupProjektAdapter() {
        val sortedProjekte = sortProjekteWithRecents(projektListe)
        val projektView = etProjekt
        projektView.setAdapter(
            object : ArrayAdapter<String>(
                this,
                android.R.layout.simple_dropdown_item_1line,
                sortedProjekte.toMutableList()
            ) {
                private val allItems = sortedProjekte.toMutableList()

                override fun getFilter(): Filter {
                    return object : Filter() {
                        override fun performFiltering(constraint: CharSequence?): FilterResults {
                            val results = FilterResults()
                            results.values =
                                if (constraint.isNullOrBlank()) allItems else allItems.filter {
                                    it.lowercase().contains(constraint.toString().lowercase())
                                }
                            results.count = (results.values as List<*>).size
                            return results
                        }

                        override fun publishResults(
                            constraint: CharSequence?,
                            results: FilterResults?
                        ) {
                            clear()
                            if (results?.values is List<*>) addAll(results.values as List<String>)
                            notifyDataSetChanged()
                        }
                    }
                }
            }
        )
        projektView.threshold = 1

        projektView.setOnClickListener {
            projektView.post {
                projektView.setText("", false)
                projektView.showDropDown()
            }
        }

        projektView.setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus) {
                projektView.post {
                    projektView.setText("", false)
                    projektView.showDropDown()
                }
            }
        }

        projektView.setOnItemClickListener { _, _, position, _ ->
            val projekt = projektView.adapter.getItem(position).toString()
            projektView.setText(projekt)
            projektView.setSelection(0)
            DataRepository.rememberProjekt(projekt)
            setupProjektAdapter()
            buchungMengeView?.let { mengeView ->
                mengeView.post {
                    mengeView.requestFocus()
                    val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
                    imm.showSoftInput(mengeView, InputMethodManager.SHOW_IMPLICIT)
                }
            }
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 1001 && resultCode == RESULT_OK) {
            val barcode = data?.getStringExtra("barcode")?.trim()
            if (!barcode.isNullOrEmpty()) {
                val matchedArtikel = artikelListe.find { it.artNr == barcode }
                etFilter.setText(barcode)
                etFilter.setSelection(0)
                if (matchedArtikel == null) {
                    tvArtikelInfo.text = "⚠ Kein Artikel gefunden!"
                    tvArtikelInfo.setTextColor(getThemeColor(android.R.attr.textColorPrimary))
                } else {
                    showArtikelInfo(matchedArtikel)
                    etFilter.clearFocus()
                    etFilter.isFocusable = false
                    etFilter.isFocusableInTouchMode = false
                    etFilter.keyListener = null
                    buchungProjektView?.let { projektView ->
                        projektView.post {
                            projektView.requestFocus()
                            val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
                            imm.showSoftInput(projektView, InputMethodManager.SHOW_IMPLICIT)
                            projektView.showDropDown()
                        }
                    }
                }
            }
        }
    }

    protected fun showArtikelInfo(artikel: Artikel) {
        hideKeyboardAndClearFocus()

        val itemTextColor = getThemeColor(android.R.attr.textColorPrimary)
        getThemeColor(android.R.attr.colorAccent)

        val infoLines = listOf(
            "Artikelnummer: ${artikel.artNr}",
            "Bezeichnung: ${artikel.bez}",
            "Lagerorte W1: ${artikel.lagerorteW1.filter { it.isNotBlank() }.joinToString(", ")}",
            "Lagerorte W2: ${artikel.lagerorteW2.filter { it.isNotBlank() }.joinToString(", ")}",
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

        tvArtikelInfo.setTextColor(itemTextColor)
        tvArtikelInfo.text = finalSpannable
    }

    private fun getThemeColor(attrResId: Int): Int {
        val typedValue = android.util.TypedValue()
        theme.resolveAttribute(attrResId, typedValue, true)
        return typedValue.data
    }

    private fun setupDropdown() {
        adapter = ArtikelAdapter(this, artikelListe)
        etFilter.setAdapter(adapter)
        etFilter.threshold = 1

        etFilter.setOnItemClickListener { _, _, position, _ ->
            val artikel = adapter.getItem(position) ?: return@setOnItemClickListener
            showArtikelInfo(artikel)
            val text = "${artikel.artNr} | ${artikel.bez}"
            textWatcherEnabled = false
            etFilter.setText(text)
            etFilter.setSelection(0)
            textWatcherEnabled = true
            buchungProjektView?.let { projektView ->
                projektView.post {
                    projektView.requestFocus()
                    val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
                    imm.showSoftInput(projektView, InputMethodManager.SHOW_IMPLICIT)
                    projektView.showDropDown()
                }
            }
        }

        etFilter.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) {
                if (!textWatcherEnabled) return
                if (etFilter.isPerformingCompletion) return
                val input = s.toString().trim()
                if (input.isEmpty()) {
                    tvArtikelInfo.text = ""
                    return
                }
                val filterText = input.split("|")[0].trim()
                val matches = artikelListe.filter {
                    it.artNr.contains(filterText, true) || it.bez.contains(filterText, true)
                }
                when (matches.size) {
                    0 -> {
                        tvArtikelInfo.text = "⚠ Kein Artikel gefunden!"
                        tvArtikelInfo.setTextColor(getThemeColor(android.R.attr.colorError))
                    }

                    1 -> {
                        val artikel = matches.first()
                        showArtikelInfo(artikel)
                        val text = "${artikel.artNr} | ${artikel.bez}"
                        textWatcherEnabled = false
                        etFilter.setText(text)
                        etFilter.setSelection(0)
                        textWatcherEnabled = true
                        etFilter.post {
                            val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
                            imm.hideSoftInputFromWindow(etFilter.windowToken, 0)
                            etFilter.clearFocus()
                            etFilter.isFocusable = false
                            etFilter.isFocusableInTouchMode = false
                            etFilter.keyListener = null
                        }
                        buchungProjektView?.let { projektView ->
                            projektView.post {
                                projektView.requestFocus()
                                val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
                                imm.showSoftInput(projektView, InputMethodManager.SHOW_IMPLICIT)
                                projektView.showDropDown()
                            }
                        }
                    }

                    else -> {
                        tvArtikelInfo.text = ""
                        etFilter.post { etFilter.showDropDown() }
                    }
                }
            }
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
        })
    }

    fun doBuchen(einlagern: Boolean, count: Boolean = false) {
        val artikelText = etFilter.text.toString().trim()
        val artikel = artikelText.split("|")[0].trim()
        val projektText = buchungProjektView?.text?.toString()?.trim()
        val projekt = projektText?.let { text -> text.split("–")[0].trim() } ?: ""
        val mengeStr = buchungMengeView?.text?.toString()?.trim()

        if (artikel.isNullOrBlank() || (projekt.isNullOrBlank() && !count) || mengeStr.isNullOrBlank()) {
            showError("Bitte alle Felder ausfüllen")
            return
        }
        val menge = mengeStr.replace(",", ".").toDoubleOrNull()
        if (menge == null || (!count && menge == 0.0)) {
            showError("Ungültige Menge")
            return
        }
        val serverMenge = if (count) {
            "=${mengeStr.replace(".", ",")}"
        } else if (einlagern) {
            "+${mengeStr.replace(".", ",")}"
        } else {
            "-${mengeStr.replace(".", ",")}"
        }
        val buttonText = if (count) "Zählstand setzen" else if (einlagern) "Zubuchen" else "Entnehmen"

        if (AppSettings(this@BaseArtikelScanActivity).confirmBook) {
            AlertDialog.Builder(this)
                .setTitle("Buchung bestätigen")
                .setMessage("Artikel: $artikel\nProjekt: $projekt\nMenge: $menge\nSeriennummer(n): ${edtSerials.text}")
                .setPositiveButton(buttonText) { _, _ ->
                    sendBuchung(artikel, projekt, serverMenge)
                }
                .setNegativeButton("Abbrechen", null)
                .show()
        } else {
            sendBuchung(artikel, projekt, serverMenge)
        }
    }

    private fun sendBuchung(artikel: String, projekt: String, menge: String) {
        val now = System.currentTimeMillis()
        if (now - lastBookingTime < bookingCooldown) return
        lastBookingTime = now

        CoroutineScope(Dispatchers.IO).launch {
            withContext(Dispatchers.Main) {
                UiLoadingHelper.show(
                    this@BaseArtikelScanActivity,
                    "Buchung wird gesendet...",
                    UiLoadingHelper.LoadingStatus.LOADING
                )
            }

            val nowStr = java.text.SimpleDateFormat("dd.MM.yyyy HH:mm:ss", Locale.GERMANY).format(Date())
            val username = intent.getStringExtra("USERNAME") ?: "?"
            val serials = withContext(Dispatchers.Main) { edtSerials.text.toString().trim() }
            val request = buildString {
                append("{SetBuchung}")
                append("$artikel||$menge|||$projekt|${AppSettings(this@BaseArtikelScanActivity).werkNummer}|$username|$nowStr|")
                if (serials.isNotEmpty()) {
                    append(serials)
                }
                append("{/SetBuchung}")
            }

            var attempts = 0
            while (attempts < 3) {
                attempts++
                try {
                    val response = TcpClient.sendCommand(
                        context = this@BaseArtikelScanActivity,
                        settings = AppSettings(this@BaseArtikelScanActivity),
                        command = "SetBuchung",
                        request = request,
                        endTag = "{/SetBuchung}"
                    )
                    val cleaned = response.replace("\r", "").trim()

                    withContext(Dispatchers.Main) {
                        if (cleaned == "{SetBuchung}\nok\n{/SetBuchung}") {
                            UiLoadingHelper.update(
                                this@BaseArtikelScanActivity,
                                "Buchung erfolgreich",
                                UiLoadingHelper.LoadingStatus.SUCCESS
                            )
                            delay(2000)
                            if (AppSettings(this@BaseArtikelScanActivity).clearAfterSuccess) {
                                btnClearClicked()
                            }
                        } else {
                            UiLoadingHelper.update(
                                this@BaseArtikelScanActivity,
                                "$response\nBuchung fehlgeschlagen",
                                UiLoadingHelper.LoadingStatus.ERROR
                            )
                        }
                    }
                    return@launch
                } catch (_: Exception) {
                    if (attempts < 3) {
                        withContext(Dispatchers.Main) {
                            UiLoadingHelper.update(
                                this@BaseArtikelScanActivity,
                                "Timeout – Wiederhole... ($attempts/3)",
                                UiLoadingHelper.LoadingStatus.LOADING
                            )
                        }
                        delay(1000)
                    }
                }
            }

            withContext(Dispatchers.Main) {
                UiLoadingHelper.update(
                    this@BaseArtikelScanActivity,
                    "Buchung fehlgeschlagen – kein Server erreichbar (3 Versuche)",
                    UiLoadingHelper.LoadingStatus.ERROR
                )
            }
        }
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

    protected fun parseProjektList(raw: String): List<String> {
        val list = mutableListOf<String>()
        raw.lines().forEach {
            val parts = it.split("|")
            if (parts.size == 2 && !it.startsWith("{")) list.add("${parts[0]} – ${parts[1]}")
        }
        return list
    }

    protected fun parseArtikelResponse(raw: String): List<Artikel> {
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

    fun hideKeyboardAndClearFocus() {
        val view = currentFocus
        view?.clearFocus()
        val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
        view?.let { imm.hideSoftInputFromWindow(it.windowToken, 0) }
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

fun SpannableStringBuilder.appendBoldAfterColon(text: String) {
    val colonIndex = text.indexOf(":")
    if (colonIndex == -1) {
        append(text)
        return
    }
    val start = length
    append(text)
    setSpan(
        StyleSpan(Typeface.BOLD),
        start,
        start + colonIndex + 1,
        Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
    )
}