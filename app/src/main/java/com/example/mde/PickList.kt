package com.example.mde

import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.text.SpannableString
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.StyleSpan
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.AutoCompleteTextView
import android.widget.TextView
import android.widget.Toast
import androidx.recyclerview.widget.DividerItemDecoration
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.*

data class PickItem(val nummer: String, val projektNr: String, val projektName: String)
data class PickDetail(val artNr: String, val menge: String, val pos: String, val info: String)

class PickListActivity : BaseArtikelScanActivity() {

    override val buchungProjektView = null
    override val buchungMengeView = null
    override val buchungStatusView = null

    override var skiploading: Boolean = true
    private lateinit var settings: AppSettings
    private lateinit var username: String

    private lateinit var etPickFilter: AutoCompleteTextView
    private lateinit var pickListView: RecyclerView
    private lateinit var pickAdapter: PickAdapter

    private lateinit var pickDetailsView: RecyclerView
    private lateinit var pickDetailsAdapter: PickDetailsAdapter
    private var pickDetailsListe: List<PickDetail> = emptyList()

    private var pickListe: List<PickItem> = emptyList()

    override fun getLayoutId(): Int = R.layout.activity_pick_list
    override fun getSettings() = settings
    override fun getUsername() = username
    override fun getWerkNummer() = settings.werkNummer

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        settings = AppSettings(this)
        username = intent.getStringExtra("USERNAME") ?: "?"

        etPickFilter = findViewById(R.id.etPickFilter)
        pickListView = findViewById(R.id.rvPickList)
        pickDetailsView = findViewById(R.id.rvPickDetails)

        // Pickliste
        pickAdapter = PickAdapter(emptyList())
        pickListView.layoutManager = LinearLayoutManager(this)
        pickListView.adapter = pickAdapter
        pickListView.addItemDecoration(DividerItemDecoration(this, DividerItemDecoration.VERTICAL))

        // Pick-Details, zunächst unsichtbar
        pickDetailsAdapter = PickDetailsAdapter(emptyList())
        pickDetailsView.layoutManager = LinearLayoutManager(this)
        pickDetailsView.adapter = pickDetailsAdapter
        pickDetailsView.addItemDecoration(DividerItemDecoration(this, DividerItemDecoration.VERTICAL))
        pickDetailsView.visibility = View.GONE

        setupPickFilter()
        loadPickList()
    }

    private fun setupPickFilter() {
        etPickFilter.addTextChangedListener(object : android.text.TextWatcher {
            override fun afterTextChanged(s: android.text.Editable?) {
                val input = s.toString().trim()
                val filtered = pickListe.filter {
                    it.nummer.contains(input, true) ||
                            it.projektNr.contains(input, true) ||
                            it.projektName.contains(input, true)
                }
                pickAdapter.updateList(filtered)
            }
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
        })
    }

    private fun loadPickList() {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val response = TcpClient.sendCommand(
                    context = this@PickListActivity,
                    settings = settings,
                    command = "GetPickOverview",
                    request = "{GetPickOverview}",
                    endTag = "{/GetPickOverview}"
                )

                val lines = response.lines()
                    .filter { it.isNotBlank() && !it.startsWith("{") }
                    .drop(1) // Kopfzeile entfernen
                val items = lines.mapNotNull { line ->
                    val parts = line.split("|")
                    if (parts.size >= 3) PickItem(parts[0], parts[1], parts[2]) else null
                }

                withContext(Dispatchers.Main) {
                    pickListe = items
                    pickAdapter.updateList(pickListe)
                }

            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    showError("Fehler beim Laden der Pickliste:\n${e.message}")
                }
            }
        }
    }

    // --- Adapter Pickliste ---
    inner class PickAdapter(private var items: List<PickItem>) :
        RecyclerView.Adapter<PickAdapter.PickViewHolder>() {

        inner class PickViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
            val tvItem: TextView = itemView.findViewById(android.R.id.text1)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PickViewHolder {
            val view = LayoutInflater.from(parent.context)
                .inflate(android.R.layout.simple_list_item_1, parent, false)
            return PickViewHolder(view)
        }

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
                etPickFilter.setText(item.projektNr)
                etPickFilter.setSelection(item.projektNr.length)
                Toast.makeText(holder.itemView.context, "Ausgewählt: ${item.projektNr}", Toast.LENGTH_SHORT).show()

                pickListView.visibility = View.GONE
                loadPickDetails(item.nummer)
            }
        }

        override fun getItemCount(): Int = items.size

        fun updateList(newItems: List<PickItem>) {
            items = newItems
            notifyDataSetChanged()
        }
    }

    // --- Pick-Details ---
    private fun loadPickDetails(pickNummer: String) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val response = TcpClient.sendCommand(
                    context = this@PickListActivity,
                    settings = settings,
                    command = "GetPick_$pickNummer",
                    request = "{GetPick_$pickNummer}",
                    endTag = "{/GetPick_$pickNummer}"
                )

                val lines = response.lines()
                    .filter { it.isNotBlank() && !it.startsWith("{") }
                    .drop(1) // Kopfzeile entfernen
                val details = lines.mapNotNull { line ->
                    val parts = line.split("|")
                    if (parts.size >= 3) {
                        PickDetail(
                            artNr = parts[0],
                            menge = parts[1],
                            pos = parts[2],
                            info = if (parts.size >= 4) parts[3] else ""  // Info optional
                        )
                    } else null
                }

                withContext(Dispatchers.Main) {
                    pickDetailsListe = details
                    pickDetailsAdapter.updateList(pickDetailsListe)
                    pickDetailsView.visibility = View.VISIBLE
                }

            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    showError("Fehler beim Laden der Picklist-Details:\n${e.message}")
                }
            }
        }
    }

    inner class PickDetailsAdapter(private var items: List<PickDetail>) :
        RecyclerView.Adapter<PickDetailsAdapter.DetailViewHolder>() {

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
            builder.appendBoldAfterColon("To Pick: ${item.menge}")
            builder.append("\n")
            builder.appendBoldAfterColon("Pos: ${item.pos}")
            builder.append("\n")
            builder.appendBoldAfterColon("Info: ${item.info}")

            holder.tvItem.text = builder
            holder.tvItem.setTextColor(Color.WHITE)
            holder.itemView.setBackgroundColor(if (position % 2 == 0) Color.DKGRAY else Color.GRAY)
        }

        override fun getItemCount(): Int = items.size

        fun updateList(newItems: List<PickDetail>) {
            items = newItems
            notifyDataSetChanged()
        }
    }
}

// --- Hilfsfunktion: nur linker Teil fett ---
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