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
    val userPinMap = mutableMapOf<String, String>()
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

    private var requestRunning = false
    private val ioScope = CoroutineScope(Dispatchers.IO + Job())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        TcpLogHelper.clearLogs(this)

        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE)
        setContentView(R.layout.activity_login)

        settings = AppSettings(this)

        val toolbar = findViewById<Toolbar>(R.id.toolbar)
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayShowTitleEnabled(false)

        txtUsername = findViewById(R.id.txtUsername)
        txtPin = findViewById(R.id.txtPin)
        btnLogin = findViewById(R.id.btnLogin)
        btnReload = findViewById(R.id.btnReload)

        val txtVersion = findViewById<TextView>(R.id.txtVersion)
        txtVersion.text = "App-Version: ${BuildConfig.VERSION_NAME}"

        if (userList.isNotEmpty()) {
            val adapter = ArrayAdapter(this, android.R.layout.simple_dropdown_item_1line, userList)
            txtUsername.setAdapter(adapter)
            selectDefaultUserIfAvailable()
        }

        btnLogin.setOnClickListener { attemptLogin() }
        btnReload.setOnClickListener { loadUserList() }

        loadUserList()
    }

    override fun onDestroy() {
        super.onDestroy()
        ioScope.cancel()
    }

    /* ================= BENUTZERLISTE LADEN ================= */
    private fun loadUserList() {
        if (requestRunning) return
        requestRunning = true

        UiLoadingHelper.show(this, "Lade Benutzerliste...", UiLoadingHelper.LoadingStatus.LOADING)

        ioScope.launch {
            var success = false
            var attempts = 0

            while (attempts < 3 && !success) {
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
            val adapter = ArrayAdapter(
                this@LoginActivity,
                android.R.layout.simple_dropdown_item_1line,
                userList
            )
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