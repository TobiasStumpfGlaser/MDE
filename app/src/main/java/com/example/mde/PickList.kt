package com.example.mde

import android.os.Bundle
import android.view.View
import android.widget.AutoCompleteTextView
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.*

data class PickItem(val nummer: String, val projektNr: String, val projektName: String)

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

        pickAdapter = PickAdapter(emptyList())
        pickListView.layoutManager = LinearLayoutManager(this)
        pickListView.adapter = pickAdapter

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

                val lines = response.lines().filter { it.isNotBlank() && !it.startsWith("{") }
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

    inner class PickAdapter(private var items: List<PickItem>) :
        RecyclerView.Adapter<PickAdapter.PickViewHolder>() {

        inner class PickViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
            val tvItem: TextView = itemView.findViewById(android.R.id.text1)
        }

        override fun onCreateViewHolder(parent: android.view.ViewGroup, viewType: Int): PickViewHolder {
            val view = android.view.LayoutInflater.from(parent.context)
                .inflate(android.R.layout.simple_list_item_1, parent, false)
            return PickViewHolder(view)
        }

        override fun onBindViewHolder(holder: PickViewHolder, position: Int) {
            val item = items[position]
            holder.tvItem.text = "${item.nummer} | ${item.projektNr} – ${item.projektName}"
            holder.tvItem.setTextColor(android.graphics.Color.WHITE) // ✅ Text auf Weiß setzen
            holder.itemView.setOnClickListener {
                etPickFilter.setText(item.projektNr)
                etPickFilter.setSelection(item.projektNr.length)
                android.widget.Toast.makeText(holder.itemView.context, "Ausgewählt: ${item.projektNr}", android.widget.Toast.LENGTH_SHORT).show()
            }
        }

        override fun getItemCount(): Int = items.size

        fun updateList(newItems: List<PickItem>) {
            items = newItems
            notifyDataSetChanged()
        }
    }
}