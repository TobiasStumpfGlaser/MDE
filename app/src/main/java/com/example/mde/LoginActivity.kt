package com.example.mde

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.MotionEvent
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.widget.ArrayAdapter
import android.widget.AutoCompleteTextView
import android.widget.Button
import android.widget.EditText
import android.widget.Filter
import android.widget.ImageButton
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import com.datalogic.decode.BarcodeManager
import com.datalogic.decode.configuration.ScannerProperties
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

object UserCache {
    val userList = mutableListOf<String>()
    val userPinMap = mutableMapOf<String, String>()
    val nameToInitials = mutableMapOf<String, String>()
}

object DatalogicWedgeController {
    fun setWedgeConfig(manager: BarcodeManager): Boolean {
        return try {
            val cfg = ScannerProperties.edit(manager) ?: return false

            val iWedge = cfg.intentWedge ?: return false
            iWedge.enable.set(true)

            val kWedge = cfg.keyboardWedge ?: return false
            kWedge.enable.set(false)

            val errorCode = cfg.store(manager, true)
            errorCode == 0
        } catch (_: Throwable) {
            false
        }
    }
}

class UserAdapter(
    context: android.content.Context,
    userList: List<String>
) : ArrayAdapter<String>(
    context,
    android.R.layout.simple_dropdown_item_1line,
    userList.toMutableList()
) {
    private val allItems = userList.toMutableList()

    fun updateList(newList: List<String>) {
        allItems.clear()
        allItems.addAll(newList)
        clear()
        addAll(allItems)
        notifyDataSetChanged()
    }

    override fun getFilter(): Filter {
        return object : Filter() {
            override fun performFiltering(constraint: CharSequence?): FilterResults {
                val results = FilterResults()
                val filtered = if (constraint.isNullOrBlank()) {
                    allItems
                } else {
                    val query = constraint.toString().lowercase()
                    allItems.filter { it.lowercase().contains(query) }
                }
                results.values = filtered.toMutableList()
                results.count = filtered.size
                return results
            }

            override fun publishResults(constraint: CharSequence?, results: FilterResults?) {
                clear()
                if (results?.values is List<*>) {
                    @Suppress("UNCHECKED_CAST")
                    addAll(results.values as List<String>)
                }
                notifyDataSetChanged()
            }
        }
    }
}

class LoginActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "LoginActivity"
        private const val STATE_PENDING_OTA_APK = "pending_ota_apk"
        private const val VERSION_STATUS_CHECKING = "Versionsprüfung läuft …"
        private const val VERSION_STATUS_CURRENT = "Version ist aktuell"
        private const val VERSION_STATUS_FAILED = "Versionsprüfung fehlgeschlagen"
        private const val VERSION_STATUS_NOT_CONFIGURED =
            "Versionsprüfung nicht konfiguriert"
        private const val VERSION_STATUS_INSTALL_PENDING =
            "Updateinstallation wird fortgesetzt"
    }

    private lateinit var settings: AppSettings
    private lateinit var txtUsername: AutoCompleteTextView
    private lateinit var txtPin: EditText
    private lateinit var btnLogin: Button
    private lateinit var btnReload: ImageButton
    private lateinit var userAdapter: UserAdapter
    private lateinit var otaUpdateHelper: OtaUpdateHelper
    private lateinit var txtVersion: TextView

    private val userList get() = UserCache.userList
    private val userPinMap get() = UserCache.userPinMap
    private val nameToInitials = UserCache.nameToInitials

    private var requestRunning = false
    private val ioScope = CoroutineScope(Dispatchers.IO + Job())
    private lateinit var barcodeManager: BarcodeManager
    private lateinit var handler: Handler
    private lateinit var timeoutRunnable: Runnable
    private var timeoutMillis: Long = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        settings = AppSettings(this)
        when (settings.selectedTheme) {
            "dark" -> setTheme(R.style.Theme_MDE_Dark)
            "colorful" -> setTheme(R.style.Theme_MDE_Colorful)
            else -> setTheme(R.style.Theme_MDE_Light)
        }

        super.onCreate(savedInstanceState)
        otaUpdateHelper = OtaUpdateHelper(
            activity = this,
            restoredPendingApkPath = savedInstanceState?.getString(STATE_PENDING_OTA_APK)
        )
        TcpLogHelper.cleanupOldLogs(this)

        handler = Handler(Looper.getMainLooper())

        timeoutRunnable = Runnable {
            resetLoginForm()
        }

        barcodeManager = BarcodeManager()

        val wedgeOk = try {
            DatalogicWedgeController.setWedgeConfig(barcodeManager)
        } catch (_: Throwable) {
            false
        }

        if (!wedgeOk) {
            handler.post {
                showScannerErrorDialog()
            }
        }

        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE)
        setContentView(R.layout.activity_login)

        val toolbar = findViewById<Toolbar>(R.id.toolbar)
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayShowTitleEnabled(false)

        timeoutMillis = settings.logoutTimeSec * 1000L
        resetInactivityTimer()

        txtUsername = findViewById(R.id.txtUsername)
        txtPin = findViewById(R.id.txtPin)
        btnLogin = findViewById(R.id.btnLogin)
        btnReload = findViewById(R.id.btnReload)

        txtVersion = findViewById(R.id.txtVersion)
        showVersionStatus()

        val txtHeader = findViewById<TextView>(R.id.txtHeader)
        txtHeader.text = "BW MDE - Werk: ${settings.werkNummer}"

        userAdapter = UserAdapter(this, userList)
        txtUsername.setAdapter(userAdapter)
        selectDefaultUserIfAvailable()

        txtUsername.setOnClickListener { userTextClicked() }
        txtUsername.setOnFocusChangeListener { _, hasFocus ->
            userTextFocusChanged(hasFocus)
        }
        btnLogin.setOnClickListener { attemptLogin() }
        btnReload.setOnClickListener { loadUserList() }

        loadUserList()

        when {
            !settings.otaEnabled -> {
                OtaDiagnosticLog.event(
                    this,
                    "Versionsprüfung/Start",
                    "Übersprungen: OTA ist in den App-Einstellungen deaktiviert"
                )
                OtaDiagnosticLog.summary(
                    context = this,
                    level = OtaDiagnosticLog.SummaryLevel.ATTENTION,
                    title = "OTA DEAKTIVIERT",
                    lines = listOf(
                        "STATUS: Versionsprüfung in den App-Einstellungen ausgeschaltet",
                        "MASSNAHME: keine"
                    )
                )
                showVersionStatus()
            }
            otaUpdateHelper.pendingApkPathForState() != null -> {
                OtaDiagnosticLog.event(
                    this,
                    "Versionsprüfung/Start",
                    "Übersprungen: Eine heruntergeladene APK wartet bereits auf die Installation"
                )
                showVersionStatus(VERSION_STATUS_INSTALL_PENDING)
            }
            OtaConfig.isConfigured(settings) -> {
                OtaDiagnosticLog.event(
                    this,
                    "Versionsprüfung/Start",
                    "Automatische Versionsprüfung beim Login wird gestartet"
                )
                checkForUpdatesOnStartup()
            }
            else -> {
                showVersionStatus(VERSION_STATUS_NOT_CONFIGURED)
                Log.w(TAG, "OTA-Check übersprungen: Serverpfad oder Build-Zugangsdaten fehlen")
                OtaDiagnosticLog.warning(
                    this,
                    "Versionsprüfung/Start",
                    "Übersprungen: Serverpfad oder Build-Zugangsdaten fehlen"
                )
                OtaDiagnosticLog.summary(
                    context = this,
                    level = OtaDiagnosticLog.SummaryLevel.ERROR,
                    title = "OTA BLOCKIERT: KONFIGURATION",
                    lines = listOf(
                        "PROBLEM: Serverdaten oder Build-Zugangsdaten fehlen",
                        "MASSNAHME: OTA-Einstellungen und Build-Konfiguration prüfen"
                    )
                )
            }
        }
    }

    private fun showScannerErrorDialog() {
        AlertDialog.Builder(this)
            .setIcon(android.R.drawable.ic_dialog_alert)
            .setTitle("Scanner-Konfiguration-Error")
            .setMessage(
                "Die Datalogic Scanner-Konfiguration konnte nicht gesetzt werden.\n\n" +
                        "Bitte prüfen Sie die Scanner-Einstellungen -> Intent-Wedge"
            )
            .setCancelable(false)
            .setPositiveButton("OK", null)
            .show()
    }

    override fun onDestroy() {
        super.onDestroy()
        ioScope.cancel()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        otaUpdateHelper.pendingApkPathForState()?.let { path ->
            outState.putString(STATE_PENDING_OTA_APK, path)
        }
        super.onSaveInstanceState(outState)
    }

    override fun onResume() {
        super.onResume()
        resetInactivityTimer()
    }

    override fun onPause() {
        super.onPause()
        stopInactivityTimer()
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        resetInactivityTimer()
        return super.dispatchTouchEvent(ev)
    }

    private fun resetInactivityTimer() {
        handler.removeCallbacks(timeoutRunnable)
        handler.postDelayed(timeoutRunnable, timeoutMillis)
    }

    private fun stopInactivityTimer() {
        handler.removeCallbacks(timeoutRunnable)
    }

    private fun resetLoginForm() {
        txtPin.text.clear()
        if (userList.contains(settings.defaultUser)) {
            selectDefaultUserIfAvailable()
        } else {
            txtUsername.setText("", false)
            txtUsername.clearFocus()
            txtUsername.dismissDropDown()
        }
    }

    private fun userTextClicked() {
        txtUsername.post {
            txtUsername.setText("", false)
            userAdapter.updateList(userList)
            txtUsername.dismissDropDown()
            txtUsername.showDropDown()
        }
    }

    private fun userTextFocusChanged(hasFocus: Boolean) {
        if (!hasFocus) return

        txtUsername.post {
            if (::userAdapter.isInitialized) {
                userAdapter.updateList(userList)
            }

            txtUsername.dismissDropDown()
        }
    }

    private fun loadUserList() {
        if (requestRunning) return
        requestRunning = true

        val loadJob = Job()

        UiLoadingHelper.show(
            this,
            "Lade Benutzerliste...",
            UiLoadingHelper.LoadingStatus.LOADING,
            onCancel = {
                loadJob.cancel()
                requestRunning = false
            }
        )

        ioScope.launch(loadJob) {
            var success = false
            var attempts = 0

            while (attempts < 3 && !success) {
                if (!isActive) return@launch
                attempts++

                withContext(Dispatchers.Main) {
                    UiLoadingHelper.update(
                        this@LoginActivity,
                        "Lade Benutzerliste... Versuch $attempts/3",
                        UiLoadingHelper.LoadingStatus.LOADING
                    )
                }

                success = try {
                    val response = TcpClient.sendCommand(
                        context = this@LoginActivity,
                        settings = settings,
                        command = "GetBediener",
                        request = "{GetBediener}",
                        endTag = "{/GetBediener}"
                    )

                    if (response.isNotEmpty()) {
                        parseUserList(response)
                        true
                    } else {
                        delay(500)
                        false
                    }
                } catch (_: Exception) {
                    delay(500)
                    false
                }
            }

            withContext(Dispatchers.Main) {
                requestRunning = false
                if (success) {
                    UiLoadingHelper.update(
                        this@LoginActivity,
                        "Benutzerliste geladen",
                        UiLoadingHelper.LoadingStatus.SUCCESS
                    )
                    selectDefaultUserIfAvailable()
                } else {
                    UiLoadingHelper.update(
                        this@LoginActivity,
                        "Fehler beim Laden der Benutzer nach 3 Versuchen",
                        UiLoadingHelper.LoadingStatus.ERROR
                    )
                }
            }
        }
    }

    private suspend fun parseUserList(raw: String) {
        userList.clear()
        userPinMap.clear()
        nameToInitials.clear()

        raw.lines().forEach { line ->
            val trimmed = line.trim()
            if (trimmed.isEmpty() || trimmed.startsWith("{") || trimmed.startsWith("Initial")) return@forEach

            val parts = trimmed.split("|")
            if (parts.size >= 3) {
                val initials = parts[0].trim()
                val pin = parts[1].trim()
                val fullName = parts[2].trim()

                userList.add(fullName)
                userPinMap[fullName] = pin
                nameToInitials[fullName] = initials
            }
        }

        withContext(Dispatchers.Main) {
            if (::userAdapter.isInitialized) {
                userAdapter.updateList(userList)
            } else {
                userAdapter = UserAdapter(this@LoginActivity, userList)
                txtUsername.setAdapter(userAdapter)
            }
        }
    }

    private fun selectDefaultUserIfAvailable() {
        if (userList.contains(settings.defaultUser)) {
            txtUsername.setText(settings.defaultUser, false)
            txtUsername.clearFocus()
            txtUsername.dismissDropDown()
            txtPin.post {
                txtPin.requestFocus()
                val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
                imm.showSoftInput(txtPin, InputMethodManager.SHOW_IMPLICIT)
            }
        }
    }

    private fun attemptLogin() {
        val username = txtUsername.text.toString()
        val pin = txtPin.text.toString()

        if (userList.isEmpty()) {
            UiLoadingHelper.showError(this, "Benutzerliste noch nicht geladen")
            return
        }

        if (!userList.contains(username)) {
            UiLoadingHelper.showError(this, "Ungültiger Benutzername")
            txtPin.text.clear()
            return
        }

        val correctPin = userPinMap[username] ?: ""
        if (pin != correctPin) {
            UiLoadingHelper.showError(this, "PIN falsch")
            txtPin.text.clear()
            return
        }

        val initials = nameToInitials[username] ?: username

        navigateToMain(initials)
    }

    private fun checkForUpdatesOnStartup() {
        showVersionStatus(VERSION_STATUS_CHECKING)
        OtaDiagnosticLog.event(
            this,
            "Versionsprüfung/UI",
            "Status auf '$VERSION_STATUS_CHECKING' gesetzt; Hintergrundprüfung eingeplant"
        )

        ioScope.launch {
            val result = runCatching {
                UpdateManager(this@LoginActivity).checkForUpdates()
            }

            result.exceptionOrNull()?.let { error ->
                Log.e(TAG, "Update-Check fehlgeschlagen; Anmeldung bleibt verfügbar", error)
                OtaDiagnosticLog.event(
                    context = this@LoginActivity,
                    stage = "Versionsprüfung/UI",
                    message = "Versionsprüfung fehlgeschlagen; Anmeldung bleibt verfügbar; " +
                        "${error.javaClass.simpleName}: ${error.message ?: "ohne Fehlermeldung"}",
                    secrets = OtaDiagnosticLog.credentialSecrets(
                        BuildConfig.OTA_USERNAME,
                        BuildConfig.OTA_PASSWORD
                    )
                )
            }

            withContext(Dispatchers.Main) {
                if (!isFinishing && !isDestroyed) {
                    showUpdateCheckResult(result)
                }
            }
        }
    }

    internal fun showUpdateCheckResult(result: Result<UpdateInfo?>) {
        if (!settings.otaEnabled) {
            OtaDiagnosticLog.event(
                this,
                "Versionsprüfung/UI",
                "Ergebnis verworfen: OTA wurde zwischenzeitlich deaktiviert"
            )
            showVersionStatus()
            return
        }

        result.fold(
            onSuccess = { updateInfo ->
                if (updateInfo == null) {
                    OtaDiagnosticLog.event(
                        this,
                        "Versionsprüfung/UI",
                        "Ergebnis angezeigt: App ist aktuell"
                    )
                    showVersionStatus(VERSION_STATUS_CURRENT)
                } else {
                    OtaDiagnosticLog.event(
                        this,
                        "Versionsprüfung/UI",
                        "Ergebnis angezeigt: Version ${updateInfo.versionName} " +
                            "(versionCode=${updateInfo.versionCode}) ist verfügbar",
                        secrets = OtaDiagnosticLog.credentialSecrets(
                            BuildConfig.OTA_USERNAME,
                            BuildConfig.OTA_PASSWORD
                        )
                    )
                    showVersionStatus("Neue Version ${updateInfo.versionName} verfügbar")
                    otaUpdateHelper.showUpdateDialog(updateInfo)
                }
            },
            onFailure = {
                OtaDiagnosticLog.event(
                    this,
                    "Versionsprüfung/UI",
                    "Fehlerstatus angezeigt"
                )
                showVersionStatus(VERSION_STATUS_FAILED)
            }
        )
    }

    private fun showVersionStatus(status: String? = null) {
        txtVersion.text = buildString {
            append("App-Version: ")
            append(BuildConfig.VERSION_NAME)
            if (!status.isNullOrBlank()) {
                append('\n')
                append(status)
            }
        }
    }

    private fun navigateToMain(initials: String) {
        val intent = Intent(this, MainActivity::class.java)
        intent.putExtra("USERNAME", initials)
        startActivity(intent)
        overridePendingTransition(0, 0)
        finish()
    }

    override fun onCreateOptionsMenu(menu: android.view.Menu?): Boolean {
        menuInflater.inflate(R.menu.login_menu, menu)
        return true
    }

    override fun onOptionsItemSelected(item: android.view.MenuItem): Boolean {
        return when (item.itemId) {
            R.id.menu_settings -> {
                startActivity(Intent(this, SettingsActivity::class.java))
                true
            }

            else -> super.onOptionsItemSelected(item)
        }
    }
}
