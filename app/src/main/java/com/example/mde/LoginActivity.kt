package com.example.mde

import android.content.Intent
import android.os.Bundle
import android.view.inputmethod.InputMethodManager
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import kotlinx.coroutines.*
import android.view.WindowManager

object UserCache {
    val userList = mutableListOf<String>()
    val userPinMap = mutableMapOf<String,String>()
}

class LoginActivity : AppCompatActivity() {

    private lateinit var settings: AppSettings
    private lateinit var txtUsername: AutoCompleteTextView
    private lateinit var txtPin: EditText
    private lateinit var btnLogin: Button
    private lateinit var btnReload: ImageButton

    private val userList get() = UserCache.userList
    private val userPinMap get() = UserCache.userPinMap
    private val nameToInitials = mutableMapOf<String, String>()

    private var serverConnected = false
    private var requestRunning = false

    private val ioScope = CoroutineScope(Dispatchers.IO + Job())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE)
        setContentView(R.layout.activity_login)

        settings = AppSettings(this)

        val toolbar = findViewById<Toolbar>(R.id.toolbar)
        setSupportActionBar(toolbar)

        txtUsername = findViewById(R.id.txtUsername)
        txtPin = findViewById(R.id.txtPin)
        btnLogin = findViewById(R.id.btnLogin)
        btnReload = findViewById(R.id.btnReload)

        val txtVersion = findViewById<TextView>(R.id.txtVersion)
        txtVersion.text = "App-Version: ${BuildConfig.VERSION_NAME}"

        // Adapter für Autocomplete, falls UserCache schon Daten hat
        if (userList.isNotEmpty()) {
            val adapter = ArrayAdapter(this, android.R.layout.simple_dropdown_item_1line, userList)
            txtUsername.setAdapter(adapter)
            selectDefaultUserIfAvailable()
        }

        btnLogin.setOnClickListener { attemptLogin() }

        // Reload-Button für manuelles Nachladen
        btnReload.setOnClickListener {
            connectAndLoadUsers()
        }

        // Initiale Verbindung & Benutzerliste laden
        connectAndLoadUsers()
    }

    override fun onDestroy() {
        super.onDestroy()
        ioScope.cancel()
    }

    /* ================= SERVERVERBINDUNG + BENUTZERLISTE ================= */
    private fun connectAndLoadUsers() {
        UiLoadingHelper.show(this, "Verbinde mit Server...", UiLoadingHelper.LoadingStatus.LOADING)

        ioScope.launch {
            var serverSuccess = false
            var attempts = 0
            while (attempts < 3 && !serverSuccess) {
                attempts++

                withContext(Dispatchers.Main) {
                    // Loading Screen mit aktuellem Versuch aktualisieren
                    UiLoadingHelper.update(
                        this@LoginActivity,
                        "Verbinde mit Server... Versuch $attempts/3",
                        UiLoadingHelper.LoadingStatus.LOADING
                    )
                }

                serverSuccess = try {
                    TcpClient.ensureConnection(settings)
                    true
                } catch (_: Exception) {
                    delay(500) // kleine Pause vor Retry
                    false
                }
            }

            withContext(Dispatchers.Main) {
                serverConnected = serverSuccess
                if (serverSuccess) {
                    UiLoadingHelper.update(this@LoginActivity, "Server verbunden", UiLoadingHelper.LoadingStatus.SUCCESS)
                    loadUserList()
                } else {
                    UiLoadingHelper.update(this@LoginActivity, "Server nicht erreichbar nach 3 Versuchen", UiLoadingHelper.LoadingStatus.ERROR)
                }
            }
        }
    }

    private fun loadUserList() {
        if (requestRunning) return
        requestRunning = true

        UiLoadingHelper.update(this, "Lade Benutzerliste...", UiLoadingHelper.LoadingStatus.LOADING)

        ioScope.launch {
            var success = false
            var attempts = 0

            while (attempts < 3 && !success) {
                attempts++

                withContext(Dispatchers.Main) {
                    // Loading Screen mit aktuellem Versuch aktualisieren
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
                        parseUserList(response)  // Adapter wird auf MainThread gesetzt
                        true
                    } else {
                        delay(500) // kleine Pause vor Retry
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
                    UiLoadingHelper.update(this@LoginActivity, "Benutzerliste geladen", UiLoadingHelper.LoadingStatus.SUCCESS)
                    selectDefaultUserIfAvailable()
                } else {
                    UiLoadingHelper.update(this@LoginActivity, "Fehler beim Laden der Benutzer nach 3 Versuchen", UiLoadingHelper.LoadingStatus.ERROR)
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
            val adapter = ArrayAdapter(this@LoginActivity,
                android.R.layout.simple_dropdown_item_1line, userList)
            txtUsername.setAdapter(adapter)
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

    /* ================= LOGIN ================= */
    private fun attemptLogin() {
        val username = txtUsername.text.toString()
        val pin = txtPin.text.toString()

        if (!serverConnected) {
            UiLoadingHelper.showError(this, "Keine Serververbindung")
            return
        }

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
        val intent = Intent(this, MainActivity::class.java)
        intent.putExtra("USERNAME", initials)
        startActivity(intent)
        overridePendingTransition(0,0)
        finish()
    }
}