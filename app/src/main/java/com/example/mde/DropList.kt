package com.example.mde

import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.text.*
import android.text.InputType
import android.text.style.StyleSpan
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.AutoCompleteTextView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.recyclerview.widget.DividerItemDecoration
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.*
import java.util.Date
import java.util.Locale
import android.widget.Spinner
import android.widget.ArrayAdapter
import android.widget.AdapterView

data class DropItem(val nummer: String, val projektNr: String, val projektName: String)
data class DropDetail(
    val artNr: String,
    var menge: String,
    val pos: String,
    val info: String,
    val dropNummer: String,
    var serials: List<String> = emptyList(),
    var lagerOrtW1: String = "",
    var lagerOrtW2: String = "",
    var grossInfo: String = ""
)

class DropListActivity : BaseArtikelScanActivity() {

    override val buchungProjektView = null
    override val buchungMengeView = null

    private lateinit var settings: AppSettings
    private lateinit var username: String

    private lateinit var etDropFilter: AutoCompleteTextView
    private lateinit var etDropDetailFilter: AutoCompleteTextView

    private lateinit var dropListView: RecyclerView
    private lateinit var dropAdapter: DropAdapter

    private lateinit var spinnerSort: Spinner

    private lateinit var dropDetailsView: RecyclerView
    private lateinit var dropDetailsAdapter: DropDetailsAdapter

    private var currentProjektNr: String = ""
    private var dropListe: List<DropItem> = emptyList()
    private var dropDetailsListe: List<DropDetail> = emptyList()
    private var dropDetailsOriginal: List<DropDetail> = emptyList()

    private val artikelLagerMapW1 = mutableMapOf<String, String>() // artNr -> Lagerorte W1
    private val artikelLagerMapW2 = mutableMapOf<String, String>() // artNr -> Lagerorte W2
    private var artikellisteFetched = false

    override fun getLayoutId(): Int = R.layout.activity_drop_list

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        settings = AppSettings(this)
        username = intent.getStringExtra("USERNAME") ?: "?"

        spinnerSort = findViewById(R.id.spinnerSort)
        val options = listOf("Sortiere nach Pos", "Sortiere nach W1", "Sortiere nach W2")

        val adapter = object : ArrayAdapter<String>(
            this,
            android.R.layout.simple_spinner_item,
            options
        ) {
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                val v = super.getView(position, convertView, parent) as TextView
                v.setTextColor(Color.WHITE) // Hauptanzeige
                return v
            }

            override fun getDropDownView(position: Int, convertView: View?, parent: ViewGroup): View {
                val v = super.getDropDownView(position, convertView, parent) as TextView
                v.setTextColor(Color.WHITE) // Dropdown-Liste
                return v
            }
        }

        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spinnerSort.adapter = adapter

        // Listener setzen
        spinnerSort.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                val sorted = when (position) {
                    0 -> dropDetailsListe.sortedBy { it.pos.toIntOrNull() ?: Int.MAX_VALUE } // Pos numerisch
                    1 -> dropDetailsListe.sortedBy { it.lagerOrtW1 }  // W1 alphabetisch
                    2 -> dropDetailsListe.sortedBy { it.lagerOrtW2 }  // W2 alphabetisch
                    else -> dropDetailsListe
                }
                dropDetailsAdapter.updateList(sorted)
            }

            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        etDropFilter = findViewById(R.id.etDropFilter)
        etDropDetailFilter = findViewById(R.id.etDropDetailFilter)
        dropListView = findViewById(R.id.rvDropList)
        dropDetailsView = findViewById(R.id.rvDropDetails)

        setupDetailFilter()
        setupDropFilter()

        dropAdapter = DropAdapter(emptyList())
        dropListView.layoutManager = LinearLayoutManager(this)
        dropListView.adapter = dropAdapter
        dropListView.addItemDecoration(DividerItemDecoration(this, DividerItemDecoration.VERTICAL))

        dropDetailsAdapter = DropDetailsAdapter(emptyList())
        dropDetailsView.layoutManager = LinearLayoutManager(this)
        dropDetailsView.adapter = dropDetailsAdapter
        dropDetailsView.addItemDecoration(
            DividerItemDecoration(
                this,
                DividerItemDecoration.VERTICAL
            )
        )
        dropDetailsView.visibility = View.GONE

        loadDropList()
    }

    private fun sortDropDetailsByPos() {
        dropDetailsAdapter.updateList(dropDetailsListe.sortedBy { it.pos })
    }

    private fun sortDropDetailsByW1() {
        dropDetailsAdapter.updateList(dropDetailsListe.sortedBy { it.lagerOrtW1 })
    }

    private fun sortDropDetailsByW2() {
        dropDetailsAdapter.updateList(dropDetailsListe.sortedBy { it.lagerOrtW2 })
    }

    // --------------------------------------------------
    // Dropliste Filter
    // --------------------------------------------------
    private fun setupDropFilter() {
        etDropFilter.addTextChangedListener(object : TextWatcher {
            var ignoreChanges = false
            override fun afterTextChanged(s: Editable?) {
                if (ignoreChanges) return
                val input = s.toString().trim()
                val filtered = dropListe.filter {
                    it.nummer.contains(input, true) ||
                            it.projektNr.contains(input, true) ||
                            it.projektName.contains(input, true)
                }
                dropAdapter.updateList(filtered)
                if (filtered.size == 1) {
                    ignoreChanges = true
                    val drop = filtered[0]
                    val dropNummer = drop.nummer
                    currentProjektNr = drop.projektNr
                    etDropFilter.setText(dropNummer)
                    etDropFilter.setSelection(0)
                    ignoreChanges = false
                    dropListView.visibility = View.GONE
                    loadDropDetails(dropNummer)
                } else {
                    dropListView.visibility = if (filtered.isEmpty()) View.GONE else View.VISIBLE
                }
            }

            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
        })
    }

    // --------------------------------------------------
    // Detail Filter + Scannerlogik
    // --------------------------------------------------
    private fun setupDetailFilter() {
        etDropDetailFilter.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) {
                val input = s.toString().trim()
                if (input.isEmpty()) {
                    dropDetailsAdapter.updateList(dropDetailsOriginal)
                    return
                }
                val filtered = dropDetailsOriginal.filter {
                    it.artNr.contains(input, true) ||
                            it.menge.contains(input, true) ||
                            it.pos.contains(input, true) ||
                            it.info.contains(input, true)
                }
                dropDetailsAdapter.updateList(filtered)
                if (filtered.size == 1) {
                    etDropDetailFilter.setText("")
                    showDropDialog(filtered[0])
                }
            }

            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
        })
    }

    // --------------------------------------------------
    // Drop Dialog
    // --------------------------------------------------
    private fun showDropDialog(item: DropDetail) {
        val dialogView = layoutInflater.inflate(R.layout.dialog_drop_options, null)
        val tvMessage = dialogView.findViewById<TextView>(R.id.tvDropInfo)
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
                Lagerorte W1: ${artikelLagerMapW1[item.artNr.trim()] ?: ""}
                Lagerorte W2: ${artikelLagerMapW2[item.artNr.trim()] ?: ""}
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
                playErrorSound(this@DropListActivity)
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

            CoroutineScope(Dispatchers.IO).launch {
                try {
                    val dropNummer = item.dropNummer
                    val now = java.text.SimpleDateFormat("dd.MM.yyyy HH:mm:ss", Locale.GERMANY)
                        .format(Date())
                    val serialsString =
                        if (item.serials.isNotEmpty()) item.serials.joinToString(";") else ""
                    val buchungsMenge = (item.menge.toIntOrNull() ?: 0)
                    val request = buildString {
                        append("{SetBuchung}")
                        append("$artikel||$buchungsMenge|$dropNummer|${item.pos}|$projekt|${settings.werkNummer}|$username|$now|")
                        if (serialsString.isNotEmpty()) append("$serialsString")
                        append("{/SetBuchung}")
                    }

                    TcpLogHelper.logRequest(this@DropListActivity, "SetBuchung", request)
                    val response = TcpClient.sendCommand(
                        context = this@DropListActivity,
                        settings = settings,
                        command = "SetBuchung",
                        request = request,
                        endTag = "{/SetBuchung}"
                    )
                    TcpLogHelper.logResponse(this@DropListActivity, "SetBuchung", response)

                    withContext(Dispatchers.Main) {
                        val cleaned = response.replace("\r", "").trim()
                        if (cleaned == "{SetBuchung}\nok\n{/SetBuchung}") {
                            statusText.text = "✅ Buchung erfolgreich"
                            dropDetailsListe = dropDetailsListe.filter { it != item }
                            dropDetailsOriginal = dropDetailsOriginal.filter { it != item }
                            dropDetailsAdapter.updateList(dropDetailsListe)
                            delay(1000)
                            statusDialog.dismiss()
                            dialog.dismiss()
                            btnYes.isEnabled = true
                        } else {
                            playErrorSound(this@DropListActivity)
                            statusText.text = "❌ Buchung fehlgeschlagen:\n$response"
                            delay(2000)
                            statusDialog.dismiss()
                            btnYes.isEnabled = true
                        }
                    }
                } catch (e: Exception) {
                    withContext(Dispatchers.Main) {
                        playErrorSound(this@DropListActivity)
                        statusText.text = "❌ Fehler bei der Buchung:\n${e.message}"
                        statusDialog.setButton(
                            AlertDialog.BUTTON_POSITIVE,
                            "OK"
                        ) { _, _ ->
                            statusDialog.dismiss()
                            btnYes.isEnabled = true
                        }
                    }
                }
            }
        }

        btnNo.setOnClickListener { dialog.dismiss() }
        btnChangeAmount.setOnClickListener { showChangeAmountDialog(item) { updateDialogMessage() } }
        btnSerials.setOnClickListener {
            val mengeInt = item.menge.toIntOrNull() ?: 0
            showSerialDialog(mengeInt) { serials -> item.serials = serials; updateDialogMessage() }
        }

        dialog.show()
    }

    // --------------------------------------------------
    // Change Amount / Serial Dialogs
    // --------------------------------------------------
    private fun showChangeAmountDialog(item: DropDetail, onAmountChanged: () -> Unit) {
        val et = AutoCompleteTextView(this)
        et.inputType = InputType.TYPE_CLASS_NUMBER
        et.setText(item.menge)
        AlertDialog.Builder(this)
            .setTitle("Menge ändern")
            .setView(et)
            .setPositiveButton("OK") { _, _ ->
                item.menge =
                    et.text.toString(); dropDetailsAdapter.updateList(dropDetailsListe); onAmountChanged()
            }
            .setNegativeButton("Abbrechen", null)
            .show()
    }

    private fun showSerialDialog(maxMenge: Int, onSerialsConfirmed: (List<String>) -> Unit) {
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
            val imm = getSystemService(INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager
            imm.showSoftInput(etSerial, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT)
        }

        dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled = false

        etSerial.addTextChangedListener(object : TextWatcher {

            override fun afterTextChanged(s: Editable?) {

                val text = s.toString()

                // Scanner sendet meistens \n oder \r\n
                if (text.contains("\n") || text.contains("\r")) {

                    val cleaned = text.replace("\n", "").replace("\r", "").trim()

                    if (cleaned.isNotEmpty()) {

                        if (serials.contains(cleaned)) {
                            showMessageDialog("Seriennummer bereits vorhanden")
                            playErrorSound(this@DropListActivity)
                        }
                        else if (serials.size >= maxMenge) {
                            showMessageDialog("Maximale Menge erreicht")
                            playErrorSound(this@DropListActivity)
                        }
                        else {
                            serials.add(cleaned)

                            tvSerialList.text =
                                "${serials.size} / $maxMenge\n${serials.joinToString("\n")}"

                            dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled =
                                serials.size == maxMenge
                        }
                    }

                    etSerial.text.clear()
                }

                btnAdd.visibility = if (s.isNullOrBlank()) View.GONE else View.VISIBLE
            }

            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
        })

        etSerial.setOnEditorActionListener { v, actionId, event ->

            if (actionId == android.view.inputmethod.EditorInfo.IME_ACTION_DONE ||
                event?.keyCode == android.view.KeyEvent.KEYCODE_ENTER) {

                val input = etSerial.text.toString().trim()

                if (input.isNotEmpty()) {

                    if (serials.contains(input)) {
                        showMessageDialog("Seriennummer bereits vorhanden")
                        playErrorSound(this@DropListActivity)
                    }
                    else if (serials.size >= maxMenge) {
                        showMessageDialog("Maximale Menge erreicht")
                        playErrorSound(this@DropListActivity)
                    }
                    else {

                        serials.add(input)

                        tvSerialList.text =
                            "${serials.size} / $maxMenge\n${serials.joinToString("\n")}"

                        dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled =
                            serials.size == maxMenge
                    }
                }

                etSerial.text.clear()
                true
            }
            else {
                false
            }
        }

        btnAdd.setOnClickListener {
            val input = etSerial.text.toString().trim()
            if (input.isNotEmpty() && !serials.contains(input)) {
                if (serials.size < maxMenge) {
                    serials.add(input)
                    tvSerialList.text = "${serials.size} / $maxMenge\n${serials.joinToString("\n")}"
                } else {
                    showMessageDialog("Maximale Menge erreicht")
                    playErrorSound(this@DropListActivity)
                }
            } else if (serials.contains(input))
            {
                showMessageDialog("Seriennummer bereits vorhanden")
                playErrorSound(this@DropListActivity)
            }
            etSerial.text.clear()
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled = serials.size == maxMenge
        }
    }

    private fun showMessageDialog(message: String) {
        AlertDialog.Builder(this).setMessage(message).setPositiveButton("OK", null).show()
    }

    // --------------------------------------------------
    // Dropliste laden
    // --------------------------------------------------
    private fun loadDropList() {
        CoroutineScope(Dispatchers.IO).launch {
            withContext(Dispatchers.Main) {
                UiLoadingHelper.show(
                    this@DropListActivity,
                    "Lade Liste..."
                )
            }
            try {
                val response = TcpClient.sendCommand(
                    context = this@DropListActivity,
                    settings = settings,
                    command = "GetDropOverview",
                    request = "{GetDropOverview}",
                    endTag = "{/GetDropOverview}"
                )
                if (response.isBlank()) {
                    withContext(Dispatchers.Main) {
                        UiLoadingHelper.hide()
                        playErrorSound(this@DropListActivity)
                        AlertDialog.Builder(this@DropListActivity)
                            .setTitle("Keine Daten")
                            .setMessage("Keine Daten vom Server erhalten (Timeout: ${settings.timeoutS}s).")
                            .setPositiveButton("Retry") { _, _ -> loadDropList() }
                            .setNegativeButton("Abbrechen", null)
                            .show()
                        dropListe = emptyList()
                        dropAdapter.updateList(emptyList())
                    }
                    return@launch
                }

                val lines =
                    response.lines().filter { it.isNotBlank() && !it.startsWith("{") }.drop(1)
                val items = lines.mapNotNull { line ->
                    val parts = line.split("|").map { it.trim() }
                    if (parts.size >= 3) DropItem(parts[0], parts[1], parts[2]) else null
                }

                withContext(Dispatchers.Main) {
                    dropListe = items
                    dropAdapter.updateList(dropListe)
                }

            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    UiLoadingHelper.hide()
                    playErrorSound(this@DropListActivity)
                    AlertDialog.Builder(this@DropListActivity)
                        .setTitle("Fehler")
                        .setMessage("Fehler beim Laden der Dropliste:\n${e.message}")
                        .setPositiveButton("Retry") { _, _ -> loadDropList() }
                        .setNegativeButton("Abbrechen", null)
                        .show()
                    dropListe = emptyList()
                    dropAdapter.updateList(emptyList())
                }
            } finally {
                withContext(Dispatchers.Main) { UiLoadingHelper.hide() }
            }
        }
        etDropFilter.requestFocus()
        etDropFilter.setSelection(0)
    }

    // --------------------------------------------------
    // Drop Details laden + Lagerorte direkt setzen
    // --------------------------------------------------
    private var loadDetailsJob: Job? = null

    fun loadDropDetails(dropNummer: String){
        // Abbrechen, falls schon läuft
        loadDetailsJob?.cancel()
        loadDetailsJob = CoroutineScope(Dispatchers.IO).launch {
            withContext(Dispatchers.Main){ UiLoadingHelper.show(this@DropListActivity,"Lade Details...") }
            try{
                val response = TcpClient.sendCommand(
                    context = this@DropListActivity,
                    settings = settings,
                    command = "GetDrop_$dropNummer",
                    request = "{GetDrop_$dropNummer}",
                    endTag = "{/GetDrop_$dropNummer}"
                )

                val lines = response.lines().filter { it.isNotBlank() && !it.startsWith("{") }.drop(1)

                val details = lines.mapNotNull { line ->
                    val parts = line.split("|").map { it.trim() }
                    if (parts.size >= 3) {
                        val artNr = parts[0]
                        if (artNr == "000.9999") return@mapNotNull null
                        DropDetail(
                            artNr = artNr,
                            menge = parts[1],
                            pos = parts[2],
                            info = if (parts.size >= 4) parts[3] else "",
                            dropNummer = dropNummer
                        )
                    } else null
                }

                withContext(Dispatchers.Main){
                    // DropDetails nur initial setzen, nicht ersetzen
                    dropDetailsListe = details.toMutableList()  // MutableList zur Sicherheit
                    dropDetailsOriginal = details.toMutableList()
                    dropDetailsAdapter.updateList(dropDetailsListe)
                    etDropDetailFilter.visibility = View.VISIBLE
                    dropDetailsView.visibility = if(details.isEmpty()) View.GONE else View.VISIBLE
                    etDropDetailFilter.requestFocus()
                    etDropDetailFilter.setSelection(etDropDetailFilter.text.length)
                }

                // Lagerorte laden nur beim ersten Mal
                if(!artikellisteFetched){
                    fetchArtikelListeAndUpdateDetails()
                    if (DataRepository.artikelListe.isNotEmpty()) {
                        withContext(Dispatchers.Main) {
                            fillDropDetailsWithLagerorte()
                        }
                    } else {
                        fetchArtikelListeAndUpdateDetails()
                    }
                }

            } catch(e:Exception){
                withContext(Dispatchers.Main){
                    UiLoadingHelper.hide()
                    showMessageDialog("Fehler beim Laden der Drop-Details: ${e.message}")
                    playErrorSound(this@DropListActivity)
                }
            } finally { withContext(Dispatchers.Main){ UiLoadingHelper.hide() } }
        }
    }

    fun fillDropDetailsWithLagerorte() {
        dropDetailsListe.forEach { detail ->
            val artikel = DataRepository.artikelListe.find { it.artNr == detail.artNr }
            if (artikel != null) {
                detail.lagerOrtW1 = artikel.lagerorteW1
                    .filter { it.isNotBlank() }
                    .joinToString(", ")

                detail.lagerOrtW2 = artikel.lagerorteW2
                    .filter { it.isNotBlank() }
                    .joinToString(", ")
                detail.grossInfo = artikel.grossInfo
            } else {
                detail.lagerOrtW1 = ""
                detail.lagerOrtW2 = ""
                detail.grossInfo = ""
            }
        }
        dropDetailsAdapter.updateList(dropDetailsListe)
    }

    // --------------------------------------------------
    // Lagerorte nachladen – Pos bleibt unverändert
    // --------------------------------------------------
    private var fetchLagerJob: Job? = null

    private fun fetchArtikelListeAndUpdateDetails() {
        // Wenn schon läuft, abbrechen
        if (fetchLagerJob?.isActive == true) return

        fetchLagerJob = CoroutineScope(Dispatchers.IO).launch {
            withContext(Dispatchers.Main) {
                UiLoadingHelper.show(this@DropListActivity, "Lade Lagerorte...")
            }

            try {
                val response = TcpClient.sendCommand(
                    context = this@DropListActivity,
                    settings = settings,
                    command = "GetArtikel",
                    request = "{GetArtikel}",
                    endTag = "{/GetArtikel}"
                )

                val lines = response.lines().filter {
                    it.isNotBlank() && !it.startsWith("{") && !it.contains("{GetArtikel}") && !it.contains("{/GetArtikel}")
                }

                lines.forEach { line ->
                    val parts = line.split("|").map { it.trim() }
                    if (parts.size >= 10) {
                        val artNr = parts[0]
                        val lagerW1 = listOf(parts[2], parts[3], parts[4])
                            .filter { it.isNotBlank() }
                            .joinToString(",")

                        val lagerW2 = listOf(parts[5], parts[6], parts[7])
                            .filter { it.isNotBlank() }
                            .joinToString(",")

                        artikelLagerMapW1[artNr] = lagerW1
                        artikelLagerMapW2[artNr] = lagerW2

                        dropDetailsListe.forEach { detail ->
                            if (detail.artNr == artNr) {
                                detail.lagerOrtW1 = lagerW1
                                detail.lagerOrtW2 = lagerW2
                            }
                        }
                    }
                }

                artikellisteFetched = true // erst hier setzen, danach kein zweiter Aufruf
                withContext(Dispatchers.Main) {
                    dropDetailsAdapter.updateList(dropDetailsListe)
                }

            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    showMessageDialog("Fehler beim Nachladen der Lagerorte:\n${e.message}")
                }
            } finally {
                withContext(Dispatchers.Main) { UiLoadingHelper.hide() }
            }
        }
    }

    // --------------------------------------------------
    // DropDetails Adapter
    // --------------------------------------------------
    inner class DropDetailsAdapter(private var items: List<DropDetail>) :
        RecyclerView.Adapter<DropDetailsAdapter.DetailViewHolder>() {
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
            builder.appendBoldAfterColon("To drop: ${item.menge}")
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
            holder.tvItem.setTextColor(Color.WHITE)
            holder.itemView.setBackgroundColor(if (position % 2 == 0) Color.DKGRAY else Color.GRAY)
            holder.itemView.setOnClickListener { showDropDialog(item) }
        }

        override fun getItemCount() = items.size
        fun updateList(newItems: List<DropDetail>) {
            items = newItems
            notifyDataSetChanged()
        }
    }

    // --------------------------------------------------
    // DropListe Adapter
    // --------------------------------------------------
    inner class DropAdapter(private var items: List<DropItem>) :
        RecyclerView.Adapter<DropAdapter.DropViewHolder>() {
        inner class DropViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
            val tvItem: TextView = itemView.findViewById(android.R.id.text1)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            DropViewHolder(
                LayoutInflater.from(parent.context)
                    .inflate(android.R.layout.simple_list_item_1, parent, false)
            )

        override fun onBindViewHolder(holder: DropViewHolder, position: Int) {
            val item = items[position]
            val builder = SpannableStringBuilder()
            builder.appendBoldAfterColon("Nummer: ${item.nummer}")
            builder.append("\n")
            builder.appendBoldAfterColon("Projekt-Nr: ${item.projektNr}")
            builder.append("\n")
            builder.appendBoldAfterColon("Projekt-Name: ${item.projektName}")
            holder.tvItem.text = builder
            holder.tvItem.setTextColor(Color.WHITE)
            holder.itemView.setBackgroundColor(if (position % 2 == 0) Color.DKGRAY else Color.GRAY)
            holder.itemView.setOnClickListener {
                CoroutineScope(Dispatchers.Main).launch {
                    currentProjektNr = item.projektNr
                    etDropFilter.setText(item.projektNr)
                    etDropFilter.setSelection(item.projektNr.length)
                    dropListView.visibility = View.GONE
                    loadDropDetails(item.nummer)
                }
            }
        }

        override fun getItemCount() = items.size
        fun updateList(newItems: List<DropItem>) {
            items = newItems
            notifyDataSetChanged()
        }
    }
}

// --------------------------------------------------
// Hilfsfunktion
// --------------------------------------------------
private fun SpannableStringBuilder.appendBoldAfterColon(text: String){
    val colonIndex = text.indexOf(":")
    if(colonIndex==-1){ append(text); return }
    val start = length
    append(text)
    setSpan(StyleSpan(Typeface.BOLD), start, start+colonIndex+1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
}