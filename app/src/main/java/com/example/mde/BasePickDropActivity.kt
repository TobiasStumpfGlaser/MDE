package com.example.mde

import android.os.Bundle
import android.os.SystemClock
import android.text.Editable
import android.text.InputType
import android.text.SpannableStringBuilder
import android.text.TextUtils
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
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
import java.math.BigDecimal
import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.util.Date
import java.util.Locale

data class ListItem(val nummer: String, val projektNr: String, val projektName: String)

/** Returns `true` when [input] exactly matches the Artikelnummer format `ddd.dddd` (8 chars). */
internal fun isFullArtNr(input: String): Boolean =
    input.length == 8 && input.matches(Regex("""\d{3}\.\d{4}"""))

/** Returns `true` when [input] equals [artNr] (both trimmed, case-insensitive). */
internal fun isArtNrExactMatch(input: String, artNr: String): Boolean =
    input.trim().equals(artNr.trim(), ignoreCase = true)

data class ListDetail(
    var artNr: String,
    var menge: String,
    val pos: String,
    var info: String,
    var isCharge: Boolean = false,
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

    private var replacementArtNr: String? = null

    private var ignoreDetailFilterChanges: Boolean = false
    private var detailDialogOpenOrPending: Boolean = false

    private var lastAutoOpenAtMs: Long = 0L
    private val autoOpenCooldownMs: Long = 250L

    // -------- Scroll / "weiter machen" nach Dialog/Buchung --------
    private data class ScrollAnchor(val key: String, val offsetPx: Int)

    private fun ListDetail.key(): String = "${listenNummer}|${pos}|${artNr}"

    private fun captureScrollAnchor(): ScrollAnchor? {
        val lm = detailsView.layoutManager as? LinearLayoutManager ?: return null
        val firstPos = lm.findFirstVisibleItemPosition()
        if (firstPos == RecyclerView.NO_POSITION) return null

        val firstView = lm.findViewByPosition(firstPos) ?: return null
        val offset = firstView.top - detailsView.paddingTop

        val item = detailsAdapter.getItems().getOrNull(firstPos) ?: return null
        return ScrollAnchor(item.key(), offset)
    }

    private fun restoreScrollAnchor(anchor: ScrollAnchor?) {
        if (anchor == null) return
        val lm = detailsView.layoutManager as? LinearLayoutManager ?: return

        val items = detailsAdapter.getItems()
        val idx = items.indexOfFirst { it.key() == anchor.key }
        if (idx >= 0) {
            lm.scrollToPositionWithOffset(idx, anchor.offsetPx)
        }
    }

    private fun scrollToNextAfterRemoval(bookedItemKey: String) {
        val lm = detailsView.layoutManager as? LinearLayoutManager ?: return

        val before = detailsAdapter.getItems()
        val bookedIndex = before.indexOfFirst { it.key() == bookedItemKey }
        if (bookedIndex < 0) return

        detailsView.post {
            val after = detailsAdapter.getItems()
            if (after.isEmpty()) return@post
            val target = bookedIndex.coerceAtMost(after.lastIndex)
            lm.scrollToPositionWithOffset(target, 0)
        }
    }

    // -------------------------------------------------------------

    private fun uiInfo(message: String) {
        UiLoadingHelper.show(
            activity = this,
            message = message,
            status = UiLoadingHelper.LoadingStatus.SUCCESS,
            onCancel = null
        )
    }

    private fun uiError(message: String) {
        UiLoadingHelper.showError(this, message)
    }

    private fun parseMengeOrNull(raw: String): BigDecimal? {
        val s = raw.trim()
            .replace(" ", "")
            .replace("\u00A0", "")
            .replace(",", ".")
        if (s.isBlank()) return null
        return try {
            BigDecimal(s)
        } catch (_: Exception) {
            null
        }
    }

    private fun formatMengeForServer(value: BigDecimal): String {
        val symbols = DecimalFormatSymbols(Locale.GERMANY).apply {
            decimalSeparator = ','
            groupingSeparator = '.'
        }
        val df = DecimalFormat("0.################", symbols).apply {
            isGroupingUsed = false
        }
        return df.format(value)
    }

    private fun isIntegerValue(value: BigDecimal): Boolean {
        return try {
            value.stripTrailingZeros().scale() <= 0
        } catch (_: Exception) {
            false
        }
    }

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

        val txtHeader = findViewById<TextView>(R.id.txtHeader)
        txtHeader.text = "BW MDE - Werk: ${settings.werkNummer}"

        setupListFilter()
        setupDetailFilter()
        setupDetailFilterKeyboardBehavior()

        loadList()
    }

    private fun setupDetailFilterKeyboardBehavior() {
        etDetailFilter.showSoftInputOnFocus = false
        etDetailFilter.isFocusable = true
        etDetailFilter.isFocusableInTouchMode = true

        etDetailFilter.setOnTouchListener { v, event ->
            v.onTouchEvent(event)
            if (event.action == MotionEvent.ACTION_UP) {
                etDetailFilter.requestFocus()
                showKeyboard(etDetailFilter)
            }
            true
        }
    }

    private fun showKeyboard(view: View) {
        val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
        imm.showSoftInput(view, InputMethodManager.SHOW_IMPLICIT)
    }

    private fun hideKeyboard(view: View) {
        val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(view.windowToken, 0)
    }

    private fun focusDetailFilterWithoutKeyboard() {
        etDetailFilter.requestFocus()
        hideKeyboard(etDetailFilter)
    }

    private fun applyCurrentSortAndShow() {
        val sorted = when (spinnerSort.selectedItemPosition) {
            0 -> detailsListe.sortedBy { it.pos.toIntOrNull() ?: Int.MAX_VALUE }

            1 -> {
                val useW1 = settings.werkNummer == "10"
                val useW2 = settings.werkNummer == "20"
                detailsListe.sortedWith { a, b ->
                    val la = when {
                        useW1 -> a.lagerOrtW1.firstLocationToken()
                        useW2 -> a.lagerOrtW2.firstLocationToken()
                        else -> a.lagerOrtW1.firstLocationToken()
                    }
                    val lb = when {
                        useW1 -> b.lagerOrtW1.firstLocationToken()
                        useW2 -> b.lagerOrtW2.firstLocationToken()
                        else -> b.lagerOrtW1.firstLocationToken()
                    }

                    val c1 = compareLocationBlankLast(la, lb)
                    if (c1 != 0) c1 else naturalCompare(a.artNr, b.artNr)
                }
            }

            else -> detailsListe
        }

        detailsAdapter.updateList(sorted)
    }

    private fun setupSpinner() {
        val options = listOf("Position", "Lagerort")

        val spinnerAdapter = object : ArrayAdapter<String>(
            this,
            android.R.layout.simple_spinner_item,
            options
        ) {
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                val tv = super.getView(position, convertView, parent) as TextView
                tv.setTextColor(getThemeColor(android.R.attr.textColorPrimary))

                tv.isSingleLine = true
                tv.maxLines = 1
                tv.ellipsize = TextUtils.TruncateAt.END

                val d = context.resources.displayMetrics.density
                tv.setPadding(
                    (16 * d).toInt(),
                    (10 * d).toInt(),
                    (16 * d).toInt(),
                    (10 * d).toInt()
                )
                return tv
            }

            override fun getDropDownView(
                position: Int,
                convertView: View?,
                parent: ViewGroup
            ): View {
                val tv = super.getDropDownView(position, convertView, parent) as TextView
                tv.setTextColor(getThemeColor(android.R.attr.textColorPrimary))
                tv.ellipsize = TextUtils.TruncateAt.END
                tv.isSingleLine = false
                tv.maxLines = 2
                return tv
            }
        }

        spinnerAdapter.setDropDownViewResource(R.layout.spinner_dropdown_item)
        spinnerSort.adapter = spinnerAdapter

        spinnerSort.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(
                parent: AdapterView<*>?,
                view: View?,
                position: Int,
                id: Long
            ) {
                applyCurrentSortAndShow()
            }

            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
    }

    private fun getThemeColor(attrResId: Int): Int {
        val typedValue = android.util.TypedValue()
        theme.resolveAttribute(attrResId, typedValue, true)
        return typedValue.data
    }

    private fun String.firstLocationToken(): String =
        this.split(",").firstOrNull()?.trim().orEmpty()

    private fun tokenizeNatural(s: String): List<Any> {
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
                va is Int && vb is String -> -1
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
            aBlank -> 1
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
                if (ignoreDetailFilterChanges) return

                val input = s.toString().trim()
                if (!isFullArtNr(input)) return
                if (detailDialogOpenOrPending) return

                val matches = detailsOriginal.filter { isArtNrExactMatch(input, it.artNr) }
                if (matches.isEmpty()) return

                val itemToOpen = matches.minByOrNull { it.pos.toIntOrNull() ?: Int.MAX_VALUE }
                    ?: return

                val nowMs = SystemClock.elapsedRealtime()
                if (nowMs - lastAutoOpenAtMs < autoOpenCooldownMs) return
                lastAutoOpenAtMs = nowMs

                detailDialogOpenOrPending = true

                ignoreDetailFilterChanges = true
                etDetailFilter.setText("")
                ignoreDetailFilterChanges = false

                showItemDialog(itemToOpen)
            }

            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
        })
    }

    private fun showItemDialog(item: ListDetail) {
        val dialogAnchor = captureScrollAnchor()

        val dialogView = layoutInflater.inflate(dialogLayoutId, null)
        val tvMessage = dialogView.findViewById<TextView>(dialogInfoViewId)
        val btnYes = dialogView.findViewById<View>(R.id.btnYes)
        val btnNo = dialogView.findViewById<View>(R.id.btnNo)
        val btnChangeAmount = dialogView.findViewById<View>(R.id.btnChangeAmount)
        val btnChangeArticle = dialogView.findViewById<View>(R.id.btnChangeArticle)
        val btnSerials = dialogView.findViewById<View>(R.id.btnSerials)

        fun updateDialogMessage() {
            val ersatz = if (!replacementArtNr.isNullOrBlank()) replacementArtNr else "-"

            val serialLine =
                if (item.serials.isEmpty()) {
                    "Seriennummer(n): -"
                } else if (item.isCharge) {
                    "Charge: ${item.serials.firstOrNull().orEmpty()}"
                } else {
                    "Seriennummer(n): ${item.serials.joinToString("; ")}"
                }

            tvMessage.text = """
                            Artikel: ${item.artNr}
                            Ersatz Artikel: $ersatz
                            Menge: ${item.menge}
                            Info: ${item.info}
                            $serialLine
                            """.trimIndent()
        }
        updateDialogMessage()

        val mengeValue = parseMengeOrNull(item.menge)
        val mengeIsInteger = mengeValue?.let { isIntegerValue(it) } ?: false
        btnSerials.isEnabled = mengeIsInteger

        val dialog = AlertDialog.Builder(this).setView(dialogView).create()

        dialog.setOnDismissListener {
            detailsView.post { restoreScrollAnchor(dialogAnchor) }
            detailDialogOpenOrPending = false
            focusDetailFilterWithoutKeyboard()
        }

        btnYes.setOnClickListener {
            if (!btnYes.isEnabled) return@setOnClickListener
            btnYes.isEnabled = false

            val artikelAlt = item.artNr
            val artikelNeu = replacementArtNr?.trim().orEmpty()
            val projekt = currentProjektNr

            fun proceedBooking() {
                if (artikelAlt.isBlank()) {
                    uiError("Fehler: Artikel darf nicht leer sein!")
                    btnYes.isEnabled = true
                    return
                }

                val mengeParsed = parseMengeOrNull(item.menge)
                if (mengeParsed == null) {
                    uiError("Fehler: Menge ist ungültig (z.B. 5 oder 5,1).")
                    btnYes.isEnabled = true
                    return
                }

                if (replacementArtNr != null && artikelNeu.isBlank()) {
                    uiError("Fehler: Neue Artikelnummer ist leer.")
                    btnYes.isEnabled = true
                    return
                }

                val bookedKey = item.key()
                val now =
                    java.text.SimpleDateFormat("dd.MM.yyyy HH:mm:ss", Locale.GERMANY).format(Date())

                val serialsString: String =
                    if (item.serials.isEmpty()) {
                        ""
                    } else if (!isIntegerValue(mengeParsed)) {
                        ""
                    } else if (item.isCharge) {
                        val chargeNr = item.serials.firstOrNull()?.trim().orEmpty()
                        if (chargeNr.isBlank()) "" else "Charge:$chargeNr"
                    } else {
                        item.serials.joinToString(";") { it.trim() }
                    }

                val buchungsMengeSigned = mengeParsed.multiply(BigDecimal(buchungsVorzeichen))
                val buchungsMengeServer = formatMengeForServer(buchungsMengeSigned)

                val request = buildString {
                    append("{SetBuchung}")

                    val artikelTeil = if (replacementArtNr.isNullOrBlank()) {
                        "$artikelAlt||$buchungsMengeServer"
                    } else {
                        "$artikelAlt|$artikelNeu|$buchungsMengeServer"
                    }

                    append("$artikelTeil|${item.listenNummer}|${item.pos}|$projekt|${settings.werkNummer}|$username|$now|")
                    if (serialsString.isNotEmpty()) append(serialsString)
                    append("|{/SetBuchung}")
                }

                UiLoadingHelper.show(
                    activity = this@BasePickDropActivity,
                    message = "Buchung läuft…",
                    status = UiLoadingHelper.LoadingStatus.LOADING,
                    onCancel = null
                )

                CoroutineScope(Dispatchers.IO).launch {
                    var attempts = 0
                    while (attempts < 3) {
                        attempts++
                        try {
                            if (attempts > 1) {
                                withContext(Dispatchers.Main) {
                                    UiLoadingHelper.update(
                                        activity = this@BasePickDropActivity,
                                        message = "Buchung läuft… Versuch $attempts/3",
                                        status = UiLoadingHelper.LoadingStatus.LOADING
                                    )
                                }
                            }

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
                                    UiLoadingHelper.update(
                                        activity = this@BasePickDropActivity,
                                        message = "Buchung erfolgreich",
                                        status = UiLoadingHelper.LoadingStatus.SUCCESS
                                    )

                                    replacementArtNr = null

                                    detailsListe = detailsListe.filterNot { it.key() == bookedKey }
                                    detailsOriginal =
                                        detailsOriginal.filterNot { it.key() == bookedKey }

                                    applyCurrentSortAndShow()
                                    scrollToNextAfterRemoval(bookedKey)

                                    ignoreDetailFilterChanges = true
                                    etDetailFilter.setText("")
                                    ignoreDetailFilterChanges = false

                                    focusDetailFilterWithoutKeyboard()
                                    dialog.dismiss()
                                } else {
                                    UiLoadingHelper.update(
                                        activity = this@BasePickDropActivity,
                                        message = "Buchung fehlgeschlagen:\n$response",
                                        status = UiLoadingHelper.LoadingStatus.ERROR
                                    )
                                    btnYes.isEnabled = true
                                }
                            }
                            return@launch
                        } catch (_: Exception) {
                            if (attempts < 3) {
                                withContext(Dispatchers.Main) {
                                    UiLoadingHelper.update(
                                        activity = this@BasePickDropActivity,
                                        message = "Timeout – Wiederhole... ($attempts/3)",
                                        status = UiLoadingHelper.LoadingStatus.LOADING
                                    )
                                }
                                delay(1000)
                            }
                        }
                    }

                    withContext(Dispatchers.Main) {
                        UiLoadingHelper.update(
                            activity = this@BasePickDropActivity,
                            message = "Kein Server erreichbar nach 3 Versuchen",
                            status = UiLoadingHelper.LoadingStatus.ERROR
                        )
                        btnYes.isEnabled = true
                    }
                }
            }

            // HIER: UiLoadingHelper.confirm(...) statt zweitem AlertDialog
            if (projekt.isBlank()) {
                UiLoadingHelper.confirm(
                    activity = this@BasePickDropActivity,
                    title = "Fehler",
                    message = "Projekt ist leer?! Trotzdem buchen?",
                    okText = "OK",
                    cancelText = "Abbruch",
                    cancelable = false,
                    playErrorSound = true,
                    onOk = {
                        proceedBooking()
                    },
                    onCancel = {
                        btnYes.isEnabled = true
                        dialog.dismiss()
                    }
                )
                return@setOnClickListener
            }

            proceedBooking()
        }

        btnNo.setOnClickListener { dialog.dismiss() }
        btnChangeAmount.setOnClickListener { showChangeAmountDialog(item) { updateDialogMessage() } }
        btnChangeArticle.setOnClickListener { showChangeArticleDialog(item) { updateDialogMessage() } }
        btnSerials.setOnClickListener {
            val mengeParsed = parseMengeOrNull(item.menge) ?: BigDecimal.ZERO
            if (!isIntegerValue(mengeParsed)) {
                uiError("Seriennummern sind nur bei ganzen Mengen möglich.")
                return@setOnClickListener
            }

            val mengeInt = try {
                mengeParsed.toInt()
            } catch (_: Exception) {
                0
            }

            showSerialDialog(mengeInt) { serials, isCharge ->
                item.serials = serials
                item.isCharge = isCharge
                updateDialogMessage()
            }
        }

        dialog.show()
    }

    private fun showChangeAmountDialog(item: ListDetail, onAmountChanged: () -> Unit) {
        val et = AutoCompleteTextView(this)
        et.inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
        et.setText(item.menge)

        AlertDialog.Builder(this)
            .setTitle("Menge ändern")
            .setView(et)
            .setPositiveButton("OK") { _, _ ->
                val raw = et.text.toString()
                val parsed = parseMengeOrNull(raw)
                if (parsed == null) {
                    uiError("Ungültige Menge. Beispiele: 5 oder 5,1 oder 5.1")
                    return@setPositiveButton
                }

                item.menge = formatMengeForServer(parsed)

                if (!isIntegerValue(parsed)) {
                    item.serials = emptyList()
                    item.isCharge = false
                }

                applyCurrentSortAndShow()
                onAmountChanged()
            }
            .setNegativeButton("Abbrechen", null)
            .show()
    }

    private fun showChangeArticleDialog(item: ListDetail, onArticleChanged: () -> Unit) {
        val et = AutoCompleteTextView(this).apply {
            hint = "Neue Artikelnummer eingeben oder scannen"
            inputType = InputType.TYPE_CLASS_TEXT
            setText("")
            setSelection(0)
        }

        AlertDialog.Builder(this)
            .setTitle("Artikel ersetzen")
            .setMessage("Aktuell: ${item.artNr}\nBitte neue Artikelnummer eingeben.")
            .setView(et)
            .setPositiveButton("OK") { _, _ ->
                val neueArtNr = et.text.toString().trim()
                if (neueArtNr.isBlank()) {
                    uiError("Neue Artikelnummer darf nicht leer sein.")
                    return@setPositiveButton
                }

                replacementArtNr = if (neueArtNr.equals(item.artNr, ignoreCase = true)) {
                    uiInfo("Ersatz entfernt (Artikel bleibt unverändert).")
                    null
                } else {
                    neueArtNr
                }

                onArticleChanged()
            }
            .setNegativeButton("Abbrechen", null)
            .show()
    }

    private fun loadList() {
        loadListJob?.cancel()
        val loadJob = Job()
        loadListJob = loadJob

        UiLoadingHelper.show(
            activity = this,
            message = "Lade Liste... Versuch 1/3",
            status = UiLoadingHelper.LoadingStatus.LOADING,
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
                                activity = this@BasePickDropActivity,
                                message = "Lade Liste... Versuch $attempts/3",
                                status = UiLoadingHelper.LoadingStatus.LOADING
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

                    val lines = response
                        .lineSequence()
                        .map { it.trim() }
                        .filter { it.isNotEmpty() }
                        .filterNot { it.startsWith("{") }
                        .toList()

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
                    UiLoadingHelper.update(
                        activity = this@BasePickDropActivity,
                        message = "Fehler nach 3 Versuchen:\n$lastError",
                        status = UiLoadingHelper.LoadingStatus.ERROR
                    )
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
            activity = this,
            message = "Lade Details... Versuch 1/3",
            status = UiLoadingHelper.LoadingStatus.LOADING,
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
                                activity = this@BasePickDropActivity,
                                message = "Lade Details... Versuch $attempts/3",
                                status = UiLoadingHelper.LoadingStatus.LOADING
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

                    val lines = response
                        .lineSequence()
                        .map { it.trim() }
                        .filter { it.isNotEmpty() }
                        .filterNot { it.startsWith("{") }
                        .toList()

                    val details = lines.mapNotNull { line ->
                        val parts = line.split("|", limit = 4).map { it.trim() }
                        if (parts.size >= 3) {
                            val artNr = parts[0]
                            if (artNr == "000.9999") return@mapNotNull null
                            ListDetail(
                                artNr = artNr,
                                menge = parts[1],
                                pos = parts[2],
                                info = parts.getOrElse(3) { "" },
                                listenNummer = nummer
                            )
                        } else null
                    }

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

                        focusDetailFilterWithoutKeyboard()
                        etDetailFilter.setSelection(etDetailFilter.text.length)

                        UiLoadingHelper.hide()
                        fillDetailsWithArtikelData()
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
                    UiLoadingHelper.update(
                        activity = this@BasePickDropActivity,
                        message = "Fehler nach 3 Versuchen:\n$lastError",
                        status = UiLoadingHelper.LoadingStatus.ERROR
                    )
                }
            }
        }
    }

    private fun fillDetailsWithArtikelData() {
        val byArtNr = DataRepository.artikelListe.associateBy { it.artNr }

        detailsListe.forEach { detail ->
            val artikel = byArtNr[detail.artNr]
            if (artikel != null) {
                detail.lagerOrtW1 =
                    artikel.lagerorteW1.filter { it.isNotBlank() }.joinToString(", ")
                detail.lagerOrtW2 =
                    artikel.lagerorteW2.filter { it.isNotBlank() }.joinToString(", ")
                detail.grossInfo = artikel.grossInfo

                if (detail.info.isBlank()) {
                    detail.info = artikel.bez.trim().orEmpty()
                }
            } else {
                detail.lagerOrtW1 = ""
                detail.lagerOrtW2 = ""
                detail.grossInfo = ""
            }
        }

        applyCurrentSortAndShow()
    }

    override fun onSerialError(message: String) {
        uiError(message)
    }

    inner class ListDetailsAdapter(private var items: List<ListDetail>) :
        RecyclerView.Adapter<ListDetailsAdapter.DetailViewHolder>() {

        fun getItems(): List<ListDetail> = items

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

            val lagerOrt = when (settings.werkNummer) {
                "10" -> item.lagerOrtW1
                "20" -> item.lagerOrtW2
                else -> item.lagerOrtW1
            }

            builder.appendBoldAfterColon("LagerOrt: $lagerOrt")
            builder.append("\n")
            builder.appendBoldAfterColon("Groß-Info: ${item.grossInfo}")
            builder.append("\n")
            builder.appendBoldAfterColon("Artikelnummer: ${item.artNr}")
            builder.append("\n")
            builder.appendBoldAfterColon("$actionLabel: ${item.menge}")
            builder.append("\n")
            builder.appendBoldAfterColon("Bezeichnung: ${item.info}")

            holder.tvItem.text = builder
            val oddColor = getThemeColor(R.attr.tableRowOddColor)
            val evenColor = getThemeColor(R.attr.tableRowEvenColor)
            holder.itemView.setBackgroundColor(if (position % 2 == 0) oddColor else evenColor)
            holder.tvItem.setTextColor(getThemeColor(android.R.attr.textColorPrimary))
            holder.itemView.setOnClickListener {
                if (!detailDialogOpenOrPending) {
                    detailDialogOpenOrPending = true
                    showItemDialog(item)
                }
            }
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