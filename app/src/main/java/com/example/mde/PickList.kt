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

data class PickItem(val nummer: String, val projektNr: String, val projektName: String)
data class PickDetail(
    val artNr: String,
    var menge: String,
    val pos: String,
    val info: String,
    val pickNummer: String,
    var serials: List<String> = emptyList(),
    var lagerOrtW1: String = "",
    var lagerOrtW2: String = "",
    var grossInfo: String = ""
)

class PickListActivity : BaseArtikelScanActivity() {

    override val buchungProjektView = null
    override val buchungMengeView = null
    override val buchungStatusView = null

    private lateinit var settings: AppSettings
    private lateinit var username: String

    private lateinit var etPickFilter: AutoCompleteTextView
    private lateinit var etPickDetailFilter: AutoCompleteTextView

    private lateinit var pickListView: RecyclerView
    private lateinit var pickAdapter: PickAdapter

    private lateinit var spinnerSort: Spinner

    private lateinit var pickDetailsView: RecyclerView
    private lateinit var pickDetailsAdapter: PickDetailsAdapter

    private var pickListe: List<PickItem> = emptyList()
    private var pickDetailsListe: List<PickDetail> = emptyList()
    private var pickDetailsOriginal: List<PickDetail> = emptyList()
    private var currentProjektNr: String = ""

    private val artikelLagerMapW1 = mutableMapOf<String, String>() // artNr -> Lagerorte W1
    private val artikelLagerMapW2 = mutableMapOf<String, String>() // artNr -> Lagerorte W2
    private var artikellisteFetched = false

    override fun getLayoutId(): Int = R.layout.activity_pick_list

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
                    0 -> pickDetailsListe.sortedBy { it.pos.toIntOrNull() ?: Int.MAX_VALUE } // Pos numerisch
                    1 -> pickDetailsListe.sortedBy { it.lagerOrtW1 }  // W1 alphabetisch
                    2 -> pickDetailsListe.sortedBy { it.lagerOrtW2 }  // W2 alphabetisch
                    else -> pickDetailsListe
                }
                pickDetailsAdapter.updateList(sorted)
            }

            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        etPickFilter = findViewById(R.id.etPickFilter)
        etPickDetailFilter = findViewById(R.id.etPickDetailFilter)
        pickListView = findViewById(R.id.rvPickList)
        pickDetailsView = findViewById(R.id.rvPickDetails)

        setupDetailFilter()
        setupPickFilter()

        pickAdapter = PickAdapter(emptyList())
        pickListView.layoutManager = LinearLayoutManager(this)
        pickListView.adapter = pickAdapter
        pickListView.addItemDecoration(DividerItemDecoration(this, DividerItemDecoration.VERTICAL))

        pickDetailsAdapter = PickDetailsAdapter(emptyList())
        pickDetailsView.layoutManager = LinearLayoutManager(this)
        pickDetailsView.adapter = pickDetailsAdapter
        pickDetailsView.addItemDecoration(
            DividerItemDecoration(
                this,
                DividerItemDecoration.VERTICAL
            )
        )
        pickDetailsView.visibility = View.GONE

        loadPickList()
    }

    private fun sortPickDetailsByPos() {
        pickDetailsAdapter.updateList(pickDetailsListe.sortedBy { it.pos })
    }

    private fun sortPickDetailsByW1() {
        pickDetailsAdapter.updateList(pickDetailsListe.sortedBy { it.lagerOrtW1 })
    }

    private fun sortPickDetailsByW2() {
        pickDetailsAdapter.updateList(pickDetailsListe.sortedBy { it.lagerOrtW2 })
    }

    // --------------------------------------------------
    // Pickliste Filter
    // --------------------------------------------------
    private fun setupPickFilter() {
        etPickFilter.addTextChangedListener(object : TextWatcher {
            var ignoreChanges = false
            override fun afterTextChanged(s: Editable?) {
                if (ignoreChanges) return
                val input = s.toString().trim()
                val filtered = pickListe.filter {
                    it.nummer.contains(input, true) ||
                            it.projektNr.contains(input, true) ||
                            it.projektName.contains(input, true)
                }
                pickAdapter.updateList(filtered)
                if (filtered.size == 1) {
                    ignoreChanges = true
                    val pick = filtered[0]
                    val pickNummer = pick.nummer
                    currentProjektNr = pick.projektNr
                    etPickFilter.setText(pickNummer)
                    etPickFilter.setSelection(0)
                    ignoreChanges = false
                    pickListView.visibility = View.GONE
                    loadPickDetails(pickNummer)
                } else {
                    pickListView.visibility = if (filtered.isEmpty()) View.GONE else View.VISIBLE
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
        etPickDetailFilter.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) {
                val input = s.toString().trim()
                if (input.isEmpty()) {
                    pickDetailsAdapter.updateList(pickDetailsOriginal)
                    return
                }
                val filtered = pickDetailsOriginal.filter {
                    it.artNr.contains(input, true) ||
                            it.menge.contains(input, true) ||
                            it.pos.contains(input, true) ||
                            it.info.contains(input, true)
                }
                pickDetailsAdapter.updateList(filtered)
                if (filtered.size == 1) {
                    etPickDetailFilter.setText("")
                    showPickDialog(filtered[0])
                }
            }

            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
        })
    }

    // --------------------------------------------------
    // Pick Dialog
    // --------------------------------------------------
    private fun showPickDialog(item: PickDetail) {
        val dialogView = layoutInflater.inflate(R.layout.dialog_pick_options, null)
        val tvMessage = dialogView.findViewById<TextView>(R.id.tvPickInfo)
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
            val artikel = item.artNr
            val projekt = currentProjektNr
            val menge = item.menge
            if (artikel.isBlank() || projekt.isBlank() || menge.isBlank()) {
                showMessageDialog("❌ Fehler: Alle Felder müssen ausgefüllt sein!")
                playErrorSound(this@PickListActivity)
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
                    val pickNummer = item.pickNummer
                    val now = java.text.SimpleDateFormat("dd.MM.yyyy HH:mm:ss", Locale.GERMANY)
                        .format(Date())
                    val serialsString =
                        if (item.serials.isNotEmpty()) item.serials.joinToString(";") else ""
                    val buchungsMenge = (item.menge.toIntOrNull() ?: 0) * -1
                    val request = buildString {
                        append("{SetBuchung}")
                        append("$artikel||$buchungsMenge|$pickNummer|${item.pos}|$projekt|${settings.werkNummer}|$username|$now|")
                        if (serialsString.isNotEmpty()) append("$serialsString")
                        append("{/SetBuchung}")
                    }

                    TcpLogHelper.logRequest(this@PickListActivity, "SetBuchung", request)
                    val response = TcpClient.sendCommand(
                        context = this@PickListActivity,
                        settings = settings,
                        command = "SetBuchung",
                        request = request,
                        endTag = "{/SetBuchung}"
                    )
                    TcpLogHelper.logResponse(this@PickListActivity, "SetBuchung", response)

                    withContext(Dispatchers.Main) {
                        val cleaned = response.replace("\r", "").trim()
                        if (cleaned == "{SetBuchung}\nok\n{/SetBuchung}") {
                            statusText.text = "✅ Buchung erfolgreich"
                            pickDetailsListe = pickDetailsListe.filter { it != item }
                            pickDetailsOriginal = pickDetailsOriginal.filter { it != item }
                            pickDetailsAdapter.updateList(pickDetailsListe)
                            delay(1000)
                            statusDialog.dismiss()
                            dialog.dismiss()
                        } else {
                            statusText.text = "❌ Buchung fehlgeschlagen:\n$response"
                            playErrorSound(this@PickListActivity)
                            delay(2000)
                            statusDialog.dismiss()
                        }
                    }
                } catch (e: Exception) {
                    withContext(Dispatchers.Main) {
                        statusText.text = "❌ Fehler bei der Buchung:\n${e.message}"
                        playErrorSound(this@PickListActivity)
                        statusDialog.setButton(
                            AlertDialog.BUTTON_POSITIVE,
                            "OK"
                        ) { _, _ -> statusDialog.dismiss() }
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
    private fun showChangeAmountDialog(item: PickDetail, onAmountChanged: () -> Unit) {
        val et = AutoCompleteTextView(this)
        et.inputType = InputType.TYPE_CLASS_NUMBER
        et.setText(item.menge)
        AlertDialog.Builder(this)
            .setTitle("Menge ändern")
            .setView(et)
            .setPositiveButton("OK") { _, _ ->
                item.menge =
                    et.text.toString(); pickDetailsAdapter.updateList(pickDetailsListe); onAmountChanged()
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
                            playErrorSound(this@PickListActivity)
                        }
                        else if (serials.size >= maxMenge) {
                            showMessageDialog("Maximale Menge erreicht")
                            playErrorSound(this@PickListActivity)
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
                        playErrorSound(this@PickListActivity)
                    }
                    else if (serials.size >= maxMenge) {
                        showMessageDialog("Maximale Menge erreicht")
                        playErrorSound(this@PickListActivity)
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
                } else
                {
                    showMessageDialog("Maximale Menge erreicht")
                    playErrorSound(this@PickListActivity)
                }
            } else if (serials.contains(input))
            {
                showMessageDialog("Seriennummer bereits vorhanden")
                playErrorSound(this@PickListActivity)
            }
            etSerial.text.clear()
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled = serials.size == maxMenge
        }
    }

    private fun showMessageDialog(message: String) {
        AlertDialog.Builder(this).setMessage(message).setPositiveButton("OK", null).show()
    }

    // --------------------------------------------------
    // Pickliste laden
    // --------------------------------------------------
    private fun loadPickList() {
        CoroutineScope(Dispatchers.IO).launch {
            withContext(Dispatchers.Main) {
                UiLoadingHelper.show(
                    this@PickListActivity,
                    "Lade Liste..."
                )
            }
            try {
                val response = TcpClient.sendCommand(
                    context = this@PickListActivity,
                    settings = settings,
                    command = "GetPickOverview",
                    request = "{GetPickOverview}",
                    endTag = "{/GetPickOverview}"
                )
                if (response.isBlank()) {
                    withContext(Dispatchers.Main) {
                        UiLoadingHelper.hide()
                        playErrorSound(this@PickListActivity)
                        AlertDialog.Builder(this@PickListActivity)
                            .setTitle("Keine Daten")
                            .setMessage("Keine Daten vom Server erhalten (Timeout: ${settings.timeoutS}s).")
                            .setPositiveButton("Retry") { _, _ -> loadPickList() }
                            .setNegativeButton("Abbrechen", null)
                            .show()
                        pickListe = emptyList()
                        pickAdapter.updateList(emptyList())
                    }
                    return@launch
                }

                val lines =
                    response.lines().filter { it.isNotBlank() && !it.startsWith("{") }.drop(1)
                val items = lines.mapNotNull { line ->
                    val parts = line.split("|").map { it.trim() }
                    if (parts.size >= 3) PickItem(parts[0], parts[1], parts[2]) else null
                }

                withContext(Dispatchers.Main) {
                    pickListe = items
                    pickAdapter.updateList(pickListe)
                }

            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    UiLoadingHelper.hide()
                    playErrorSound(this@PickListActivity)
                    AlertDialog.Builder(this@PickListActivity)
                        .setTitle("Fehler")
                        .setMessage("Fehler beim Laden der Pickliste:\n${e.message}")
                        .setPositiveButton("Retry") { _, _ -> loadPickList() }
                        .setNegativeButton("Abbrechen", null)
                        .show()
                    pickListe = emptyList()
                    pickAdapter.updateList(emptyList())
                }
            } finally {
                withContext(Dispatchers.Main) { UiLoadingHelper.hide() }
            }
        }
        etPickFilter.requestFocus()
        etPickFilter.setSelection(0)
    }

    // --------------------------------------------------
    // Pick Details laden + Lagerorte direkt setzen
    // --------------------------------------------------
    private var loadDetailsJob: Job? = null

    fun loadPickDetails(pickNummer: String){
        // Abbrechen, falls schon läuft
        loadDetailsJob?.cancel()
        loadDetailsJob = CoroutineScope(Dispatchers.IO).launch {
            withContext(Dispatchers.Main){ UiLoadingHelper.show(this@PickListActivity,"Lade Details...") }
            try{
                val response = TcpClient.sendCommand(
                    context = this@PickListActivity,
                    settings = settings,
                    command = "GetPick_$pickNummer",
                    request = "{GetPick_$pickNummer}",
                    endTag = "{/GetPick_$pickNummer}"
                )

                val lines = response.lines().filter { it.isNotBlank() && !it.startsWith("{") }.drop(1)

                val details = lines.mapNotNull { line ->
                    val parts = line.split("|").map { it.trim() }
                    if (parts.size >= 3) {
                        val artNr = parts[0]
                        if (artNr == "000.9999") return@mapNotNull null
                        PickDetail(
                            artNr = artNr,
                            menge = parts[1],
                            pos = parts[2],
                            info = if (parts.size >= 4) parts[3] else "",
                            pickNummer = pickNummer
                        )
                    } else null
                }

                withContext(Dispatchers.Main){
                    // PickDetails nur initial setzen, nicht ersetzen
                    pickDetailsListe = details.toMutableList()  // MutableList zur Sicherheit
                    pickDetailsOriginal = details.toMutableList()
                    pickDetailsAdapter.updateList(pickDetailsListe)
                    etPickDetailFilter.visibility = View.VISIBLE
                    pickDetailsView.visibility = if(details.isEmpty()) View.GONE else View.VISIBLE
                    etPickDetailFilter.requestFocus()
                    etPickDetailFilter.setSelection(etPickDetailFilter.text.length)
                }
                // Lagerorte laden nur beim ersten Mal
                if(!artikellisteFetched){
                    if (DataRepository.artikelListe.isNotEmpty()) {
                        withContext(Dispatchers.Main) {
                            fillPickDetailsWithLagerorte()
                        }
                    } else {
                        fetchArtikelListeAndUpdateDetails()
                    }
                }

            } catch(e:Exception){
                withContext(Dispatchers.Main){
                    UiLoadingHelper.hide()
                    showMessageDialog("Fehler beim Laden der Pick-Details: ${e.message}")
                    playErrorSound(this@PickListActivity)
                }
            } finally { withContext(Dispatchers.Main){ UiLoadingHelper.hide() } }
        }
    }

    fun fillPickDetailsWithLagerorte() {
        pickDetailsListe.forEach { detail ->
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
        pickDetailsAdapter.updateList(pickDetailsListe)
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
                UiLoadingHelper.show(this@PickListActivity, "Lade Lagerorte...")
            }

            try {
                val response = TcpClient.sendCommand(
                    context = this@PickListActivity,
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

                        pickDetailsListe.forEach { detail ->
                            if (detail.artNr == artNr) {
                                detail.lagerOrtW1 = lagerW1
                                detail.lagerOrtW2 = lagerW2
                            }
                        }
                    }
                }

                artikellisteFetched = true // erst hier setzen, danach kein zweiter Aufruf
                withContext(Dispatchers.Main) {
                    pickDetailsAdapter.updateList(pickDetailsListe)
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
    // PickDetails Adapter
    // --------------------------------------------------
    inner class PickDetailsAdapter(private var items: List<PickDetail>) :
        RecyclerView.Adapter<PickDetailsAdapter.DetailViewHolder>() {
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
            builder.appendBoldAfterColon("To pick: ${item.menge}")
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
            holder.itemView.setOnClickListener { showPickDialog(item) }
        }

        override fun getItemCount() = items.size
        fun updateList(newItems: List<PickDetail>) {
            items = newItems
            notifyDataSetChanged()
        }
    }

    // --------------------------------------------------
    // PickListe Adapter
    // --------------------------------------------------
    inner class PickAdapter(private var items: List<PickItem>) :
        RecyclerView.Adapter<PickAdapter.PickViewHolder>() {
        inner class PickViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
            val tvItem: TextView = itemView.findViewById(android.R.id.text1)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            PickViewHolder(
                LayoutInflater.from(parent.context)
                    .inflate(android.R.layout.simple_list_item_1, parent, false)
            )

        override fun onBindViewHolder(holder: PickViewHolder, position: Int) {
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
                    etPickFilter.setText(item.projektNr)
                    etPickFilter.setSelection(etPickFilter.text.length)
                    pickListView.visibility = View.GONE
                    loadPickDetails(item.nummer)
                }
            }
        }

        override fun getItemCount() = items.size
        fun updateList(newItems: List<PickItem>) {
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