package com.example.mde

import android.os.Bundle
import android.text.Editable
import android.text.InputType
import android.text.SpannableStringBuilder
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.AutoCompleteTextView
import android.widget.EditText
import android.widget.Spinner
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.recyclerview.widget.DividerItemDecoration
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Date
import java.util.Locale

data class ListItem(val nummer: String, val projektNr: String, val projektName: String)
data class ListDetail(
    val artNr: String,
    var menge: String,
    val pos: String,
    val info: String,
    val listenNummer: String,
    var serials: List<String> = emptyList(),
    var lagerOrtW1: String = "",
    var lagerOrtW2: String = "",
    var grossInfo: String = ""
)

abstract class BasePickDropActivity : BaseArtikelScanActivity() {

    protected abstract val overviewCommand: String
    protected abstract val detailCommandPrefix: String
    protected abstract val actionLabel: String
    protected abstract val buchungsVorzeichen: Int
    protected abstract val listFilterHint: String

    private val dialogLayoutId = R.layout.dialog_pick_drop_options
    private val dialogInfoViewId = R.id.tvItemInfo

    override fun getLayoutId(): Int = R.layout.activity_pick_drop_list

    override val buchungProjektView = null
    override val buchungMengeView = null

    override val autoLoadArtikelUndProjekte = false

    protected lateinit var etProjectNumber: EditText
    protected lateinit var etListFilter: AutoCompleteTextView
    protected lateinit var etDetailFilter: AutoCompleteTextView
    private lateinit var listView: RecyclerView
    private lateinit var detailsView: RecyclerView
    private lateinit var spinnerSort: Spinner
    private lateinit var listAdapter: ListOverviewAdapter
    private lateinit var detailsAdapter: ListDetailsAdapter

    private var liste: List<ListItem> = emptyList()
    private var detailsListe: List<ListDetail> = emptyList()
    private var detailsOriginal: List<ListDetail> = emptyList()
    protected var currentProjektNr: String = ""

    protected lateinit var settings: AppSettings
    protected lateinit var username: String

    private var loadListJob: Job? = null
    private var loadDetailsJob: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        settings = AppSettings(this)
        when (settings.selectedTheme) {
            "dark" -> setTheme(R.style.Theme_MDE_Dark)
            "colorful" -> setTheme(R.style.Theme_MDE_Colorful)
            else -> setTheme(R.style.Theme_MDE_Light)
        }

        super.onCreate(savedInstanceState)

        username = intent.getStringExtra("USERNAME") ?: "?"

        etProjectNumber = findViewById(R.id.etProjectNumber)
        etListFilter = findViewById(R.id.etListFilter)
        etDetailFilter = findViewById(R.id.etDetailFilter)
        listView = findViewById(R.id.rvList)
        detailsView = findViewById(R.id.rvDetails)
        spinnerSort = findViewById(R.id.spinnerSort)

        etListFilter.hint = listFilterHint

        setupSpinner()

        listAdapter = ListOverviewAdapter(emptyList())
        listView.layoutManager = LinearLayoutManager(this)
        listView.adapter = listAdapter
        listView.addItemDecoration(DividerItemDecoration(this, DividerItemDecoration.VERTICAL))

        detailsAdapter = ListDetailsAdapter(emptyList())
        detailsView.layoutManager = LinearLayoutManager(this)
        detailsView.adapter = detailsAdapter
        detailsView.addItemDecoration(DividerItemDecoration(this, DividerItemDecoration.VERTICAL))
        detailsView.visibility = View.GONE

        setupListFilter()
        setupDetailFilter()
        loadList()
    }

    private fun setupSpinner() {
        val options = listOf("Sortiere nach Pos", "Sortiere nach W1", "Sortiere nach W2")

        val spinnerAdapter = object : ArrayAdapter<String>(
            this,
            android.R.layout.simple_spinner_item,
            options
        ) {
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                val view = super.getView(position, convertView, parent) as TextView
                view.setTextColor(getThemeColor(android.R.attr.textColorPrimary))
                view.setPadding(24, 12, 24, 12)
                return view
            }

            override fun getDropDownView(
                position: Int,
                convertView: View?,
                parent: ViewGroup
            ): View {
                val view = super.getDropDownView(position, convertView, parent) as TextView
                view.setTextColor(getThemeColor(android.R.attr.textColorPrimary))
                view.setBackgroundColor(getThemeColor(android.R.attr.windowBackground))
                view.setPadding(24, 24, 24, 24)
                return view
            }
        }

        spinnerAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spinnerSort.adapter = spinnerAdapter

        spinnerSort.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(
                parent: AdapterView<*>?, view: View?, position: Int, id: Long
            ) {
                val sorted = when (position) {
                    0 -> detailsListe.sortedBy { it.pos.toIntOrNull() ?: Int.MAX_VALUE }
                    1 -> detailsListe.sortedWith { a, b ->
                        val c1 = compareLocationBlankLast(
                            a.lagerOrtW1.firstLocationToken(),
                            b.lagerOrtW1.firstLocationToken()
                        )
                        if (c1 != 0) return@sortedWith c1
                        naturalCompare(a.artNr, b.artNr)
                    }

                    2 -> detailsListe.sortedWith { a, b ->
                        val c1 = compareLocationBlankLast(
                            a.lagerOrtW2.firstLocationToken(),
                            b.lagerOrtW2.firstLocationToken()
                        )
                        if (c1 != 0) return@sortedWith c1
                        naturalCompare(a.artNr, b.artNr)
                    }

                    else -> detailsListe
                }
                detailsAdapter.updateList(sorted)
            }

            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
    }

    private fun getThemeColor(attrResId: Int): Int {
        val typedValue = android.util.TypedValue()
        theme.resolveAttribute(attrResId, typedValue, true)
        return typedValue.data
    }

    private fun String.firstLocationToken(): String {
        // "1A, 2B" -> "1A"
        return this.split(",")
            .firstOrNull()
            ?.trim()
            .orEmpty()
    }

    private fun tokenizeNatural(s: String): List<Any> {
        // zerlegt "100A-2" -> [100, "a-", 2]
        val out = mutableListOf<Any>()
        val regex = Regex("""\d+|\D+""")
        for (m in regex.findAll(s)) {
            val part = m.value
            out += part.toIntOrNull() ?: part.lowercase(Locale.GERMANY)
        }
        return out
    }

    private fun naturalCompare(a: String, b: String): Int {
        val ta = tokenizeNatural(a)
        val tb = tokenizeNatural(b)
        val n = minOf(ta.size, tb.size)

        for (i in 0 until n) {
            val va = ta[i]
            val vb = tb[i]
            val c = when {
                va is Int && vb is Int -> va.compareTo(vb)
                va is String && vb is String -> va.compareTo(vb)
                va is Int && vb is String -> -1 // Zahlen vor Text
                va is String && vb is Int -> 1
                else -> 0
            }
            if (c != 0) return c
        }
        return ta.size.compareTo(tb.size)
    }

    private fun compareLocationBlankLast(a: String, b: String): Int {
        val aa = a.trim()
        val bb = b.trim()
        val aBlank = aa.isBlank()
        val bBlank = bb.isBlank()
        return when {
            aBlank && bBlank -> 0
            aBlank -> 1 // blank -> nach hinten
            bBlank -> -1
            else -> naturalCompare(aa, bb)
        }
    }

    private fun setupListFilter() {
        etListFilter.addTextChangedListener(object : TextWatcher {
            var ignoreChanges = false
            override fun afterTextChanged(s: Editable?) {
                if (ignoreChanges) return
                val input = s.toString().trim()
                val filtered = liste.filter {
                    it.nummer.contains(input, true) ||
                            it.projektNr.contains(input, true) ||
                            it.projektName.contains(input, true)
                }
                listAdapter.updateList(filtered)
                if (filtered.size == 1) {
                    ignoreChanges = true
                    val item = filtered[0]
                    currentProjektNr = item.projektNr
                    etListFilter.setText(item.nummer)
                    etProjectNumber.setText(currentProjektNr)
                    etListFilter.setSelection(0)
                    ignoreChanges = false
                    listView.visibility = View.GONE
                    loadDetails(item.nummer)
                } else {
                    listView.visibility = if (filtered.isEmpty()) View.GONE else View.VISIBLE
                }
            }

            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
        })
    }

    private fun setupDetailFilter() {
        etDetailFilter.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) {
                val input = s.toString().trim()
                if (input.isEmpty()) {
                    detailsAdapter.updateList(detailsOriginal)
                    return
                }
                val filtered = detailsOriginal.filter {
                    it.artNr.contains(input, true) ||
                            it.menge.contains(input, true) ||
                            it.pos.contains(input, true) ||
                            it.info.contains(input, true)
                }
                detailsAdapter.updateList(filtered)
                if (filtered.size == 1) {
                    etDetailFilter.setText("")
                    showItemDialog(filtered[0])
                }
            }

            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
        })
    }

    private fun showItemDialog(item: ListDetail) {
        val dialogView = layoutInflater.inflate(dialogLayoutId, null)
        val tvMessage = dialogView.findViewById<TextView>(dialogInfoViewId)
        val btnYes = dialogView.findViewById<View>(R.id.btnYes)
        val btnNo = dialogView.findViewById<View>(R.id.btnNo)
        val btnChangeAmount = dialogView.findViewById<View>(R.id.btnChangeAmount)
        val btnSerials = dialogView.findViewById<View>(R.id.btnSerials)

        fun updateDialogMessage() {
            tvMessage.text = """
                Artikel: ${item.artNr}
                Menge: ${item.menge}
                Pos: ${item.pos}
                Info: ${item.info}
                Lagerorte W1: ${item.lagerOrtW1}
                Lagerorte W2: ${item.lagerOrtW2}
                Seriennummer(n): ${item.serials.joinToString("; ")}
            """.trimIndent()
        }
        updateDialogMessage()

        val dialog = AlertDialog.Builder(this).setView(dialogView).create()

        btnYes.setOnClickListener {
            if (!btnYes.isEnabled) return@setOnClickListener
            btnYes.isEnabled = false

            val artikel = item.artNr
            val projekt = currentProjektNr
            val menge = item.menge
            if (artikel.isBlank() || projekt.isBlank() || menge.isBlank()) {
                showMessageDialog("❌ Fehler: Alle Felder müssen ausgefüllt sein!")
                UiLoadingHelper.playErrorSound(this)
                btnYes.isEnabled = true
                return@setOnClickListener
            }

            val statusText = TextView(this).apply {
                text = "Buchung läuft…"
                setPadding(50, 50, 50, 50)
                textSize = 18f
            }
            val statusDialog = AlertDialog.Builder(this)
                .setView(statusText)
                .setCancelable(false)
                .create()
            statusDialog.show()

            val now =
                java.text.SimpleDateFormat("dd.MM.yyyy HH:mm:ss", Locale.GERMANY).format(Date())
            val serialsString =
                if (item.serials.isNotEmpty()) item.serials.joinToString(";") else ""
            val buchungsMenge = (item.menge.toIntOrNull() ?: 0) * buchungsVorzeichen
            val request = buildString {
                append("{SetBuchung}")
                append("$artikel||$buchungsMenge|${item.listenNummer}|${item.pos}|$projekt|${settings.werkNummer}|$username|$now|")
                if (serialsString.isNotEmpty()) {
                    append(serialsString)
                }
                append("|{/SetBuchung}")
            }

            CoroutineScope(Dispatchers.IO).launch {
                var attempts = 0
                while (attempts < 3) {
                    attempts++
                    try {
                        val response = TcpClient.sendCommand(
                            context = this@BasePickDropActivity,
                            settings = settings,
                            command = "SetBuchung",
                            request = request,
                            endTag = "{/SetBuchung}"
                        )
                        val cleaned = response.replace("\r", "").trim()

                        withContext(Dispatchers.Main) {
                            if (cleaned == "{SetBuchung}\nok\n{/SetBuchung}") {
                                statusText.text = "✅ Buchung erfolgreich"
                                detailsListe = detailsListe.filter { it != item }
                                detailsOriginal = detailsOriginal.filter { it != item }
                                detailsAdapter.updateList(detailsListe)
                                delay(1000)
                                statusDialog.dismiss()
                                dialog.dismiss()
                            } else {
                                statusText.text = "❌ Buchung fehlgeschlagen:\n$response"
                                UiLoadingHelper.playErrorSound(this@BasePickDropActivity)
                                delay(2000)
                                statusDialog.dismiss()
                                btnYes.isEnabled = true
                            }
                        }
                        return@launch
                    } catch (e: Exception) {
                        if (attempts < 3) {
                            withContext(Dispatchers.Main) {
                                statusText.text = "⏳ Timeout – Wiederhole... ($attempts/3)"
                            }
                            delay(1000)
                        }
                    }
                }

                withContext(Dispatchers.Main) {
                    statusText.text = "❌ Kein Server erreichbar nach 3 Versuchen"
                    UiLoadingHelper.playErrorSound(this@BasePickDropActivity)
                    statusDialog.setButton(AlertDialog.BUTTON_POSITIVE, "OK") { _, _ ->
                        statusDialog.dismiss()
                        btnYes.isEnabled = true
                    }
                }
            }
        }

        btnNo.setOnClickListener { dialog.dismiss() }
        btnChangeAmount.setOnClickListener { showChangeAmountDialog(item) { updateDialogMessage() } }
        btnSerials.setOnClickListener {
            val mengeInt = item.menge.toIntOrNull() ?: 0
            showSerialDialog(mengeInt) { serials ->
                item.serials = serials
                updateDialogMessage()
            }
        }
        dialog.show()
    }

    private fun showChangeAmountDialog(item: ListDetail, onAmountChanged: () -> Unit) {
        val et = AutoCompleteTextView(this)
        et.inputType = InputType.TYPE_CLASS_NUMBER
        et.setText(item.menge)
        AlertDialog.Builder(this)
            .setTitle("Menge ändern")
            .setView(et)
            .setPositiveButton("OK") { _, _ ->
                item.menge = et.text.toString()
                detailsAdapter.updateList(detailsListe)
                onAmountChanged()
            }
            .setNegativeButton("Abbrechen", null)
            .show()
    }

    private fun loadList() {
        loadListJob?.cancel()
        val loadJob = Job()
        loadListJob = loadJob

        UiLoadingHelper.show(
            this,
            "Lade Liste... Versuch 1/3",
            UiLoadingHelper.LoadingStatus.LOADING,
            onCancel = { loadJob.cancel() }
        )

        CoroutineScope(Dispatchers.IO + loadJob).launch {
            var success = false
            var attempts = 0
            var lastError = ""

            while (attempts < 3 && !success) {
                if (!isActive) return@launch
                attempts++
                try {
                    if (attempts > 1) {
                        withContext(Dispatchers.Main) {
                            UiLoadingHelper.update(
                                this@BasePickDropActivity,
                                "Lade Liste... Versuch $attempts/3",
                                UiLoadingHelper.LoadingStatus.LOADING
                            )
                        }
                    }

                    val response = TcpClient.sendCommand(
                        context = this@BasePickDropActivity,
                        settings = settings,
                        command = overviewCommand,
                        request = "{$overviewCommand}",
                        endTag = "{/$overviewCommand}"
                    )

                    if (response.isBlank()) {
                        lastError = "Keine Daten vom Server (Timeout: ${settings.timeoutS}s)"
                        delay(500)
                        continue
                    }

                    val lines = response.lines()
                        .filter { it.isNotBlank() && !it.startsWith("{") }
                        .drop(1)
                    val items = lines.mapNotNull { line ->
                        val parts = line.split("|").map { it.trim() }
                        if (parts.size >= 3) ListItem(parts[0], parts[1], parts[2]) else null
                    }

                    withContext(Dispatchers.Main) {
                        if (!isActive) return@withContext
                        liste = items
                        listAdapter.updateList(liste)
                        UiLoadingHelper.hide()
                    }
                    success = true

                } catch (e: Exception) {
                    lastError = e.message ?: "Unbekannter Fehler"
                    delay(500)
                }
            }

            if (!isActive) return@launch

            withContext(Dispatchers.Main) {
                if (!success) {
                    UiLoadingHelper.hide()
                    UiLoadingHelper.playErrorSound(this@BasePickDropActivity)
                    AlertDialog.Builder(this@BasePickDropActivity)
                        .setTitle("Fehler")
                        .setMessage("Fehler nach 3 Versuchen:\n$lastError")
                        .setPositiveButton("Retry") { _, _ ->
                            etListFilter.post { loadList() }
                        }
                        .setNegativeButton("Abbrechen", null)
                        .show()
                    liste = emptyList()
                    listAdapter.updateList(emptyList())
                }
            }
        }

        etListFilter.requestFocus()
        etListFilter.setSelection(0)
    }

    fun loadDetails(nummer: String) {
        loadDetailsJob?.cancel()
        val loadJob = Job()
        loadDetailsJob = loadJob

        UiLoadingHelper.hide()
        UiLoadingHelper.show(
            this,
            "Lade Details... Versuch 1/3",
            UiLoadingHelper.LoadingStatus.LOADING,
            onCancel = { loadJob.cancel() }
        )

        CoroutineScope(Dispatchers.IO + loadJob).launch {
            var success = false
            var attempts = 0
            var lastError = ""

            while (attempts < 3 && !success) {
                if (!isActive) return@launch
                attempts++
                try {
                    if (attempts > 1) {
                        withContext(Dispatchers.Main) {
                            UiLoadingHelper.update(
                                this@BasePickDropActivity,
                                "Lade Details... Versuch $attempts/3",
                                UiLoadingHelper.LoadingStatus.LOADING
                            )
                        }
                    }

                    val command = "$detailCommandPrefix$nummer"
                    val response = TcpClient.sendCommand(
                        context = this@BasePickDropActivity,
                        settings = settings,
                        command = command,
                        request = "{$command}",
                        endTag = "{/$command}"
                    )

                    if (response.isBlank()) {
                        lastError = "Keine Daten vom Server (Timeout: ${settings.timeoutS}s)"
                        delay(500)
                        continue
                    }

                    val lines = response.lines()
                        .filter { it.isNotBlank() && !it.startsWith("{") }
                        .drop(1)
                    val details = lines.mapNotNull { line ->
                        val parts = line.split("|").map { it.trim() }
                        if (parts.size >= 3) {
                            val artNr = parts[0]
                            if (artNr == "000.9999") return@mapNotNull null
                            ListDetail(
                                artNr = artNr,
                                menge = parts[1],
                                pos = parts[2],
                                info = if (parts.size >= 4) parts[3] else "",
                                listenNummer = nummer
                            )
                        } else null
                    }

                    // Artikeldaten still laden ohne neuen Dialog
                    if (DataRepository.artikelListe.isEmpty()) {
                        try {
                            val artikelResponse = TcpClient.sendCommand(
                                context = this@BasePickDropActivity,
                                settings = settings,
                                command = "GetArtikel",
                                request = "{GetArtikel}",
                                endTag = "{/GetArtikel}"
                            )
                            val artikel = parseArtikelResponse(artikelResponse)
                            withContext(Dispatchers.Main) {
                                if (isActive) artikelListe = artikel
                            }
                        } catch (_: Exception) {
                        }
                    }

                    withContext(Dispatchers.Main) {
                        if (!isActive) return@withContext
                        detailsListe = details.toMutableList()
                        detailsOriginal = details.toMutableList()
                        detailsAdapter.updateList(detailsListe)
                        etDetailFilter.visibility = View.VISIBLE
                        detailsView.visibility = if (details.isEmpty()) View.GONE else View.VISIBLE
                        etDetailFilter.requestFocus()
                        etDetailFilter.setSelection(etDetailFilter.text.length)
                        UiLoadingHelper.hide()  // ← Dialog schließen
                        fillDetailsWithLagerorte()  // ← direkt aufrufen, kein ensureArtikelLoaded
                    }
                    success = true

                } catch (e: Exception) {
                    lastError = e.message ?: "Unbekannter Fehler"
                    delay(500)
                }
            }

            if (!isActive) return@launch

            withContext(Dispatchers.Main) {
                if (!success) {
                    UiLoadingHelper.hide()
                    UiLoadingHelper.playErrorSound(this@BasePickDropActivity)
                    AlertDialog.Builder(this@BasePickDropActivity)
                        .setTitle("Fehler")
                        .setMessage("Fehler nach 3 Versuchen:\n$lastError")
                        .setPositiveButton("Retry") { _, _ ->
                            etDetailFilter.post { loadDetails(nummer) }
                        }
                        .setNegativeButton("Abbrechen", null)
                        .show()
                }
            }
        }
    }

    private fun fillDetailsWithLagerorte() {
        detailsListe.forEach { detail ->
            val artikel = DataRepository.artikelListe.find { it.artNr == detail.artNr }
            if (artikel != null) {
                detail.lagerOrtW1 =
                    artikel.lagerorteW1.filter { it.isNotBlank() }.joinToString(", ")
                detail.lagerOrtW2 =
                    artikel.lagerorteW2.filter { it.isNotBlank() }.joinToString(", ")
                detail.grossInfo = artikel.grossInfo
            } else {
                detail.lagerOrtW1 = ""
                detail.lagerOrtW2 = ""
                detail.grossInfo = ""
            }
        }
        detailsAdapter.updateList(detailsListe)
    }

    override fun onSerialError(message: String) {
        showMessageDialog(message)
        UiLoadingHelper.playErrorSound(this)
    }

    inner class ListDetailsAdapter(private var items: List<ListDetail>) :
        RecyclerView.Adapter<ListDetailsAdapter.DetailViewHolder>() {

        inner class DetailViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
            val tvItem: TextView = itemView.findViewById(android.R.id.text1)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            DetailViewHolder(
                LayoutInflater.from(parent.context)
                    .inflate(android.R.layout.simple_list_item_1, parent, false)
            )

        override fun onBindViewHolder(holder: DetailViewHolder, position: Int) {
            val item = items[position]
            val builder = SpannableStringBuilder()
            builder.appendBoldAfterColon("Art.Nr: ${item.artNr}")
            builder.append("\n")
            builder.appendBoldAfterColon("$actionLabel: ${item.menge}")
            builder.append("\n")
            builder.appendBoldAfterColon("Pos: ${item.pos}")
            builder.append("\n")
            builder.appendBoldAfterColon("Info: ${item.info}")
            builder.append("\n")
            builder.appendBoldAfterColon("Groß-Info: ${item.grossInfo}")
            builder.append("\n")
            builder.appendBoldAfterColon("Lagerorte W1: ${item.lagerOrtW1}")
            builder.append("\n")
            builder.appendBoldAfterColon("Lagerorte W2: ${item.lagerOrtW2}")
            holder.tvItem.text = builder
            val oddColor = getThemeColor(R.attr.tableRowOddColor)
            val evenColor = getThemeColor(R.attr.tableRowEvenColor)
            holder.itemView.setBackgroundColor(if (position % 2 == 0) oddColor else evenColor)
            holder.tvItem.setTextColor(getThemeColor(android.R.attr.textColorPrimary))
            holder.itemView.setOnClickListener { showItemDialog(item) }
        }

        override fun getItemCount() = items.size
        fun updateList(newItems: List<ListDetail>) {
            items = newItems
            notifyDataSetChanged()
        }
    }

    inner class ListOverviewAdapter(private var items: List<ListItem>) :
        RecyclerView.Adapter<ListOverviewAdapter.OverviewViewHolder>() {

        inner class OverviewViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
            val tvItem: TextView = itemView.findViewById(android.R.id.text1)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            OverviewViewHolder(
                LayoutInflater.from(parent.context)
                    .inflate(android.R.layout.simple_list_item_1, parent, false)
            )

        override fun onBindViewHolder(holder: OverviewViewHolder, position: Int) {
            val item = items[position]
            val builder = SpannableStringBuilder()
            builder.appendBoldAfterColon("Nummer: ${item.nummer}")
            builder.append("\n")
            builder.appendBoldAfterColon("Projekt-Nr: ${item.projektNr}")
            builder.append("\n")
            builder.appendBoldAfterColon("Projekt-Name: ${item.projektName}")
            holder.tvItem.text = builder
            val oddColor = getThemeColor(R.attr.tableRowOddColor)
            val evenColor = getThemeColor(R.attr.tableRowEvenColor)
            holder.itemView.setBackgroundColor(if (position % 2 == 0) oddColor else evenColor)
            holder.tvItem.setTextColor(getThemeColor(android.R.attr.textColorPrimary))
            holder.itemView.setOnClickListener {
                CoroutineScope(Dispatchers.Main).launch {
                    currentProjektNr = item.projektNr
                    etListFilter.setText(item.nummer)
                    etProjectNumber.setText(currentProjektNr)
                    etListFilter.setSelection(etListFilter.text.length)
                    listView.visibility = View.GONE
                    loadDetails(item.nummer)
                }
            }
        }

        override fun getItemCount() = items.size
        fun updateList(newItems: List<ListItem>) {
            items = newItems
            notifyDataSetChanged()
        }
    }
}