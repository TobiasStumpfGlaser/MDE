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

data class DropItem(val nummer: String, val projektNr: String, val projektName: String)

data class DropDetail(
    val artNr: String,
    var menge: String,
    val pos: String,
    val info: String,
    var serials: List<String> = emptyList()
)

class DropListActivity : BaseArtikelScanActivity() {

    override val buchungProjektView = null
    override val buchungMengeView = null
    override val buchungStatusView = null
    override var skiploading: Boolean = true

    private lateinit var settings: AppSettings
    private lateinit var username: String

    private lateinit var etDropFilter: AutoCompleteTextView
    private lateinit var etDropDetailFilter: AutoCompleteTextView

    private lateinit var dropListView: RecyclerView
    private lateinit var dropAdapter: DropAdapter

    private lateinit var dropDetailsView: RecyclerView
    private lateinit var dropDetailsAdapter: DropDetailsAdapter

    private var dropListe: List<DropItem> = emptyList()
    private var dropDetailsListe: List<DropDetail> = emptyList()
    private var dropDetailsOriginal: List<DropDetail> = emptyList()

    override fun getLayoutId(): Int = R.layout.activity_drop_list
    override fun getSettings() = settings
    override fun getUsername() = username
    override fun getWerkNummer() = settings.werkNummer

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        settings = AppSettings(this)
        username = intent.getStringExtra("USERNAME") ?: "?"

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
        dropDetailsView.addItemDecoration(DividerItemDecoration(this, DividerItemDecoration.VERTICAL))
        dropDetailsView.visibility = View.GONE

        loadDropList()
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
                    val dropNummer = filtered[0].nummer
                    etDropFilter.setText(dropNummer)
                    etDropFilter.setSelection(dropNummer.length)
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
    // Drop Dialog mit Buchungsfunktion
    // --------------------------------------------------
    private fun showDropDialog(item: DropDetail) {
        val dialogView = layoutInflater.inflate(R.layout.dialog_drop_options, null)

        val tvMessage = dialogView.findViewById<TextView>(R.id.tvDropInfo)
        val btnYes = dialogView.findViewById<View>(R.id.btnYes)
        val btnNo = dialogView.findViewById<View>(R.id.btnNo)
        val btnChangeAmount = dialogView.findViewById<View>(R.id.btnChangeAmount)
        val btnSerials = dialogView.findViewById<View>(R.id.btnSerials)

        fun updateDialogMessage(text: String = """
            Artikel: ${item.artNr}
            Menge: ${item.menge}
            Pos: ${item.pos}
            Info: ${item.info}
        """.trimIndent()) {
            tvMessage.text = text
        }
        updateDialogMessage()

        val dialog = AlertDialog.Builder(this)
            .setView(dialogView)
            .create()

        btnYes.setOnClickListener {
            val artikel = item.artNr
            val projekt = etDropFilter.text.toString().trim()
            val menge = item.menge
            if (artikel.isBlank() || projekt.isBlank() || menge.isBlank()) {
                showMessageDialog("❌ Fehler: Alle Felder müssen ausgefüllt sein!")
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
                    val now = java.text.SimpleDateFormat("dd.MM.yyyy HH:mm:ss", Locale.GERMANY).format(Date())
                    val serialsString = if (item.serials.isNotEmpty()) item.serials.joinToString(";") else ""
                    val buchungsMenge = (item.menge.toIntOrNull() ?: 0)
                    val request = buildString {
                        appendLine("{SetBuchung}")
                        append("$artikel||$buchungsMenge|FORMULAR|$projekt|${getWerkNummer()}|${getUsername()}|$now|")
                        if (serialsString.isNotEmpty()) append("|$serialsString")
                        appendLine()
                        append("{/SetBuchung}")
                    }

                    val response = TcpClient.sendCommand(
                        context = this@DropListActivity,
                        settings = getSettings(),
                        command = "SetBuchung",
                        request = request,
                        endTag = "{/SetBuchung}"
                    )

                    withContext(Dispatchers.Main) {
                        if (response.contains("ok", true)) {
                            statusText.text = "✅ Buchung erfolgreich"
                            dropDetailsListe = dropDetailsListe.filter { it != item }
                            dropDetailsOriginal = dropDetailsOriginal.filter { it != item }
                            dropDetailsAdapter.updateList(dropDetailsListe)
                            delay(1000)
                            statusDialog.dismiss()
                            dialog.dismiss()
                        } else {
                            statusText.text = "❌ Buchung fehlgeschlagen:\n$response"
                            delay(2000)
                            statusDialog.dismiss()
                        }
                    }
                } catch (e: Exception) {
                    withContext(Dispatchers.Main) {
                        statusText.text = "❌ Fehler bei der Buchung:\n${e.message}"
                        statusDialog.setButton(AlertDialog.BUTTON_POSITIVE, "OK") { _, _ -> statusDialog.dismiss() }
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

    // --------------------------------------------------
    // Menge ändern Dialog
    // --------------------------------------------------
    private fun showChangeAmountDialog(item: DropDetail, onAmountChanged: () -> Unit) {
        val et = AutoCompleteTextView(this)
        et.inputType = InputType.TYPE_CLASS_NUMBER
        et.setText(item.menge)

        AlertDialog.Builder(this)
            .setTitle("Menge ändern")
            .setView(et)
            .setPositiveButton("OK") { _, _ ->
                val newMenge = et.text.toString().toIntOrNull() ?: item.menge.toInt()
                item.menge = newMenge.toString()
                dropDetailsAdapter.updateList(dropDetailsListe)
                onAmountChanged()
            }
            .setNegativeButton("Abbrechen", null)
            .show()
    }

    // --------------------------------------------------
    // Seriennummern Dialog
    // --------------------------------------------------
    private fun showSerialDialog(maxMenge: Int, onSerialsConfirmed: (List<String>) -> Unit) {
        val builder = AlertDialog.Builder(this)
        builder.setTitle("Seriennummern hinzufügen")

        val layout = layoutInflater.inflate(R.layout.dialog_serials, null)
        val etSerial = layout.findViewById<AutoCompleteTextView>(R.id.etSerial)
        val tvSerialList = layout.findViewById<TextView>(R.id.tvSerialList)
        val btnAdd = layout.findViewById<View>(R.id.btnAddSerial)

        val serials = mutableListOf<String>()
        tvSerialList.text = "0 / $maxMenge"
        btnAdd.visibility = View.GONE

        builder.setView(layout)
        builder.setPositiveButton("OK") { _, _ -> onSerialsConfirmed(serials) }
        builder.setNegativeButton("Abbrechen", null)

        val dialog = builder.create()
        dialog.show()

        dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled = false

        etSerial.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) {
                btnAdd.visibility = if (!s.isNullOrBlank()) View.VISIBLE else View.GONE
            }
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
        })

        btnAdd.setOnClickListener {
            val input = etSerial.text.toString().trim()
            if (input.isNotEmpty() && !serials.contains(input)) {
                if (serials.size < maxMenge) {
                    serials.add(input)
                    tvSerialList.text = "${serials.size} / $maxMenge\n${serials.joinToString("\n")}"
                } else {
                    showMessageDialog("Maximale Menge erreicht")
                }
            } else if (serials.contains(input)) {
                showMessageDialog("Seriennummer bereits vorhanden")
            }
            etSerial.text.clear()
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled = serials.size == maxMenge
        }
    }

    private fun showMessageDialog(message: String) {
        AlertDialog.Builder(this)
            .setMessage(message)
            .setPositiveButton("OK", null)
            .show()
    }

    // --------------------------------------------------
    // Droplist laden
    // --------------------------------------------------
    private fun loadDropList() {
        CoroutineScope(Dispatchers.IO).launch {
            withContext(Dispatchers.Main) {
                UiLoadingHelper.show(this@DropListActivity, "Lade liste...")
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

                val lines = response.lines().filter { it.isNotBlank() && !it.startsWith("{") }.drop(1)
                val items = lines.mapNotNull {
                    val parts = it.split("|")
                    if (parts.size >= 3) DropItem(parts[0], parts[1], parts[2]) else null
                }

                withContext(Dispatchers.Main) {
                    dropListe = items
                    dropAdapter.updateList(dropListe)
                }

            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    UiLoadingHelper.hide()
                    AlertDialog.Builder(this@DropListActivity)
                        .setTitle("Fehler")
                        .setMessage("Fehler beim Laden der Droplist:\n${e.message}")
                        .setPositiveButton("Retry") { _, _ -> loadDropList() }
                        .setNegativeButton("Abbrechen", null)
                        .show()
                }
            } finally {
                withContext(Dispatchers.Main) {
                    UiLoadingHelper.hide()
                }
            }
        }
    }

    private fun loadDropDetails(dropNummer: String) {
        CoroutineScope(Dispatchers.IO).launch {
            withContext(Dispatchers.Main) {
                UiLoadingHelper.show(this@DropListActivity, "Lade liste...")
            }

            try {
                val response = TcpClient.sendCommand(
                    context = this@DropListActivity,
                    settings = settings,
                    command = "GetDrop_$dropNummer",
                    request = "{GetDrop_$dropNummer}",
                    endTag = "{/GetDrop_$dropNummer}"
                )

                if (response.isBlank()) {
                    withContext(Dispatchers.Main) {
                        UiLoadingHelper.hide()
                        AlertDialog.Builder(this@DropListActivity)
                            .setTitle("Keine Daten")
                            .setMessage("Keine Daten vom Server für Drop-Nr. $dropNummer erhalten (Timeout: ${settings.timeoutS}s).")
                            .setPositiveButton("Retry") { _, _ -> loadDropDetails(dropNummer) }
                            .setNegativeButton("Abbrechen", null)
                            .show()
                        dropDetailsListe = emptyList()
                        dropDetailsOriginal = emptyList()
                        dropDetailsAdapter.updateList(emptyList())
                        dropDetailsView.visibility = View.GONE
                    }
                    return@launch
                }

                val lines = response.lines().filter { it.isNotBlank() && !it.startsWith("{") }.drop(1)
                val details = lines.mapNotNull {
                    val parts = it.split("|")
                    if (parts.size >= 3) DropDetail(
                        parts[0],
                        parts[1],
                        parts[2],
                        if (parts.size >= 4) parts[3] else ""
                    ) else null
                }

                withContext(Dispatchers.Main) {
                    dropDetailsListe = details
                    dropDetailsOriginal = details
                    dropDetailsAdapter.updateList(details)
                    etDropDetailFilter.visibility = View.VISIBLE
                    dropDetailsView.visibility = View.VISIBLE
                    etDropDetailFilter.requestFocus()
                    etDropDetailFilter.setSelection(etDropDetailFilter.text.length)
                }

            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    UiLoadingHelper.hide()
                    AlertDialog.Builder(this@DropListActivity)
                        .setTitle("Fehler")
                        .setMessage("Fehler beim Laden der Droplist-Details:\n${e.message}")
                        .setPositiveButton("Retry") { _, _ -> loadDropDetails(dropNummer) }
                        .setNegativeButton("Abbrechen", null)
                        .show()
                }
            } finally {
                withContext(Dispatchers.Main) {
                    UiLoadingHelper.hide()
                }
            }
        }
    }

    // --------------------------------------------------
    // Drop Adapter
    // --------------------------------------------------
    inner class DropAdapter(private var items: List<DropItem>) :
        RecyclerView.Adapter<DropAdapter.DropViewHolder>() {

        inner class DropViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
            val tvItem: TextView = itemView.findViewById(android.R.id.text1)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): DropViewHolder {
            val view = LayoutInflater.from(parent.context)
                .inflate(android.R.layout.simple_list_item_1, parent, false)
            return DropViewHolder(view)
        }

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
                etDropFilter.setText(item.projektNr)
                etDropFilter.setSelection(item.projektNr.length)
                dropListView.visibility = View.GONE
                loadDropDetails(item.nummer)
            }
        }

        override fun getItemCount(): Int = items.size
        fun updateList(newItems: List<DropItem>) {
            items = newItems
            notifyDataSetChanged()
        }
    }

    // --------------------------------------------------
    // Drop Details Adapter
    // --------------------------------------------------
    inner class DropDetailsAdapter(private var items: List<DropDetail>) :
        RecyclerView.Adapter<DropDetailsAdapter.DetailViewHolder>() {

        inner class DetailViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
            val tvItem: TextView = itemView.findViewById(android.R.id.text1)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): DetailViewHolder {
            val view = LayoutInflater.from(parent.context)
                .inflate(android.R.layout.simple_list_item_1, parent, false)
            return DetailViewHolder(view)
        }

        override fun onBindViewHolder(holder: DetailViewHolder, position: Int) {
            val item = items[position]
            val builder = SpannableStringBuilder()
            builder.appendBoldAfterColon("Art.Nr: ${item.artNr}")
            builder.append("\n")
            builder.appendBoldAfterColon("To Drop: ${item.menge}")
            builder.append("\n")
            builder.appendBoldAfterColon("Pos: ${item.pos}")
            builder.append("\n")
            builder.appendBoldAfterColon("Info: ${item.info}")
            holder.tvItem.text = builder
            holder.tvItem.setTextColor(Color.WHITE)
            holder.itemView.setBackgroundColor(if (position % 2 == 0) Color.DKGRAY else Color.GRAY)

            holder.itemView.setOnClickListener { showDropDialog(item) }
        }

        override fun getItemCount(): Int = items.size
        fun updateList(newItems: List<DropDetail>) {
            items = newItems
            notifyDataSetChanged()
        }
    }
}

// --------------------------------------------------
// Spannable Helper
// --------------------------------------------------
private fun SpannableStringBuilder.appendBoldAfterColon(text: String) {
    val colonIndex = text.indexOf(":")
    if (colonIndex == -1) {
        append(text)
        return
    }
    val start = length
    append(text)
    setSpan(StyleSpan(Typeface.BOLD), start, start + colonIndex + 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
}