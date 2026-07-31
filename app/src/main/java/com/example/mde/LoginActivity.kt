package com.example.mde

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
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
import android.widget.LinearLayout
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
        private const val VERSION_STATUS_DISABLED = "Versionsprüfung deaktiviert"
        private const val VERSION_STATUS_INSTALL_PENDING =
            "Updateinstallation wird fortgesetzt"
        private const val KERBEROS_TEST_SERVER = "w2-fs-wks"
        private const val KERBEROS_TEST_CONNECT_HOST =
            "w2-fs-wks.werkstatt.brainware-solutions.de"
        private const val KERBEROS_TEST_SHARE = "transfer"
        private const val KERBEROS_TEST_FILE = "Temp/Tobias S/AppTest.txt"
        private const val KERBEROS_TEST_MAX_BYTES = 256L * 1024
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
            !BuildConfig.OTA_ENABLE -> showVersionStatus(VERSION_STATUS_DISABLED)
            otaUpdateHelper.pendingApkPathForState() != null ->
                showVersionStatus(VERSION_STATUS_INSTALL_PENDING)
            OtaConfig.isConfigured -> checkForUpdatesOnStartup()
            else -> {
                showVersionStatus(VERSION_STATUS_NOT_CONFIGURED)
                Log.w(TAG, "OTA-Check übersprungen: Serverpfad oder Build-Zugangsdaten fehlen")
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

        ioScope.launch {
            val result = runCatching {
                UpdateManager(this@LoginActivity).checkForUpdates()
            }

            result.exceptionOrNull()?.let { error ->
                Log.e(TAG, "Update-Check fehlgeschlagen; Anmeldung bleibt verfügbar", error)
            }

            withContext(Dispatchers.Main) {
                if (!isFinishing && !isDestroyed) {
                    showUpdateCheckResult(result)
                }
            }
        }
    }

    internal fun showUpdateCheckResult(result: Result<UpdateInfo?>) {
        result.fold(
            onSuccess = { updateInfo ->
                if (updateInfo == null) {
                    showVersionStatus(VERSION_STATUS_CURRENT)
                } else {
                    showVersionStatus("Neue Version ${updateInfo.versionName} verfügbar")
                    otaUpdateHelper.showUpdateDialog(updateInfo)
                }
            },
            onFailure = {
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

    /**
     * Asks for AD credentials without persisting them and reads the configured
     * test file through Kerberos/SMB. The normal MDE user/PIN is deliberately
     * not reused because it is not an Active Directory credential.
     */
    private fun showKerberosFileTestDialog() {
        val padding = (24 * resources.displayMetrics.density).toInt()
        val usernameInput = EditText(this).apply {
            hint = "AD-Benutzername"
            inputType = InputType.TYPE_CLASS_TEXT
            isSingleLine = true
        }
        val passwordInput = EditText(this).apply {
            hint = "AD-Passwort"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            isSingleLine = true
        }
        val inputLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(padding, padding / 2, padding, 0)
            addView(usernameInput)
            addView(passwordInput)
        }

        val dialog = AlertDialog.Builder(this)
            .setTitle("Kerberos-Dateitest")
            .setMessage(
                "Ziel: \\\\$KERBEROS_TEST_SERVER\\$KERBEROS_TEST_SHARE\\" +
                    KERBEROS_TEST_FILE.replace('/', '\\')
            )
            .setView(inputLayout)
            .setNegativeButton("Abbrechen", null)
            .setPositiveButton("Test starten", null)
            .create()

        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val username = usernameInput.text.toString().trim()
                val password = passwordInput.text.toString()
                when {
                    username.isEmpty() -> usernameInput.error = "AD-Benutzername fehlt"
                    password.isEmpty() -> passwordInput.error = "AD-Passwort fehlt"
                    else -> {
                        passwordInput.text.clear()
                        dialog.dismiss()
                        runKerberosFileTest(username, password)
                    }
                }
            }
        }
        dialog.show()
    }

    private fun runKerberosFileTest(username: String, password: String) {
        UiLoadingHelper.show(
            this,
            "Kerberos-Anmeldung und SMB-Dateizugriff werden geprüft...",
            UiLoadingHelper.LoadingStatus.LOADING
        )

        ioScope.launch {
            val result = runCatching {
                NativeKerberosSmb.readFile(
                    context = this@LoginActivity,
                    config = KerberosSmbConfig(
                        server = KERBEROS_TEST_SERVER,
                        connectHost = KERBEROS_TEST_CONNECT_HOST,
                        share = KERBEROS_TEST_SHARE,
                        username = username,
                        password = password
                    ),
                    path = KERBEROS_TEST_FILE,
                    maxBytes = KERBEROS_TEST_MAX_BYTES
                ).toString(Charsets.UTF_8).removePrefix("\uFEFF")
            }

            withContext(Dispatchers.Main) {
                UiLoadingHelper.hide()
                result.fold(
                    onSuccess = { content ->
                        AlertDialog.Builder(this@LoginActivity)
                            .setTitle("AppTest.txt")
                            .setMessage(content.ifEmpty { "(Datei ist leer)" })
                            .setPositiveButton("OK", null)
                            .show()
                    },
                    onFailure = { error ->
                        Log.e(TAG, "Kerberos-Dateitest fehlgeschlagen", error)
                        AlertDialog.Builder(this@LoginActivity)
                            .setIcon(android.R.drawable.ic_dialog_alert)
                            .setTitle("Kerberos-Dateitest fehlgeschlagen")
                            .setMessage(
                                "${error.javaClass.simpleName}: " +
                                    (error.localizedMessage ?: "Unbekannter Fehler")
                            )
                            .setPositiveButton("OK", null)
                            .show()
                    }
                )
            }
        }
    }

    override fun onCreateOptionsMenu(menu: android.view.Menu?): Boolean {
        menuInflater.inflate(R.menu.login_menu, menu)
        menu?.findItem(R.id.menu_kerberos_test)?.isVisible = BuildConfig.DEBUG
        return true
    }

    override fun onOptionsItemSelected(item: android.view.MenuItem): Boolean {
        return when (item.itemId) {
            R.id.menu_settings -> {
                startActivity(Intent(this, SettingsActivity::class.java))
                true
            }

            R.id.menu_kerberos_test -> {
                showKerberosFileTestDialog()
                true
            }

            else -> super.onOptionsItemSelected(item)
        }
    }
}
