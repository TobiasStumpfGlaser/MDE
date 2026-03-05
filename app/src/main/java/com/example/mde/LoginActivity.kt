package com.example.mde

import android.app.AlertDialog
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.inputmethod.InputMethodManager
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import kotlinx.coroutines.*
import java.net.InetSocketAddress
import java.net.Socket

class LoginActivity : AppCompatActivity() {
    private var requestRunning = false
    private lateinit var settings: AppSettings
    private lateinit var txtServerStatus: TextView
    private lateinit var btnReconnect: Button
    private lateinit var txtUsername: AutoCompleteTextView
    private lateinit var txtPin: EditText
    private lateinit var btnLogin: Button
    private var serverConnected = false
    private var serverConnecting = false
    private var reconnectDialogVisible = false
    private var retryDialogVisible = false
    private val userList = mutableListOf<String>()
    private var userListLoaded = false
    // Map für Username -> PIN (aus Server)
    private val userPinMap = mutableMapOf<String, String>()
    private val ioScope = CoroutineScope(Dispatchers.IO)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_login)

        TcpLogHelper.clearLogs(this)
        settings = AppSettings(this) // Context übergeben

        // Toolbar
        val toolbar = findViewById<Toolbar>(R.id.toolbar)
        setSupportActionBar(toolbar)

        txtServerStatus = findViewById(R.id.txtServerStatus)
        btnReconnect = findViewById(R.id.btnReconnect)
        txtUsername = findViewById(R.id.txtUsername)
        txtPin = findViewById(R.id.txtPin)
        btnLogin = findViewById(R.id.btnLogin)

        val txtVersion = findViewById<TextView>(R.id.txtVersion)
        txtVersion.text = "App-Version: ${BuildConfig.VERSION_NAME}"

        btnReconnect.setOnClickListener {
            connectToServer()
        }

        // Tastatur automatisch beim Klick auf Username öffnen
        txtUsername.setOnClickListener {
            txtUsername.requestFocus()
            val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
            imm.showSoftInput(txtUsername, InputMethodManager.SHOW_IMPLICIT)

            if (!serverConnected) {
                showErrorDialog("Keine Serververbindung")
                return@setOnClickListener
            }

            if (!userListLoaded) {
                requestUserListWithRetry()
            } else {
                txtUsername.showDropDown()
            }
        }

        // Benutzer aus Dropdown ausgewählt → Fokus PIN
        txtUsername.setOnItemClickListener { _, _, _, _ ->
            txtPin.requestFocus()
            val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
            imm.showSoftInput(txtPin, InputMethodManager.SHOW_IMPLICIT)
        }

        btnLogin.setOnClickListener {
            val username = txtUsername.text.toString()
            val pin = txtPin.text.toString()

            if (!userListLoaded) {
                showErrorDialog("Benutzerliste noch nicht geladen")
                return@setOnClickListener
            }

            if (!userList.contains(username)) {
                showErrorDialog("Ungültiger Benutzername")
                txtPin.text.clear()
                return@setOnClickListener
            }

            val correctPin = userPinMap[username] ?: ""
            if (pin != correctPin) {
                showErrorDialog("PIN falsch")
                txtPin.text.clear()
                return@setOnClickListener
            }

            // Login erfolgreich → weiter zu MainActivity
            proceedToNextScreen(username)
        }

        connectToServer()
    }

    /* ================= SERVER CONNECT ================= */

    private fun connectToServer() {
        setServerStatus(false, "Verbinde…")
        serverConnecting = true
        updateReconnectButton() // Button ausblenden während Verbindung

        ioScope.launch {
            try {
                Socket().use { socket ->
                    socket.connect(InetSocketAddress(settings.serverIp , settings.serverPort), settings.timeoutS * 1000)
                }
                runOnUiThread {
                    serverConnected = true
                    serverConnecting = false
                    setServerStatus(true, "Server verbunden")
                    updateReconnectButton()
                    if (!userListLoaded) requestUserListWithRetry()
                }
            } catch (e: Exception) {
                runOnUiThread {
                    serverConnected = false
                    serverConnecting = false
                    setServerStatus(false, "Server nicht erreichbar")
                    updateReconnectButton()
                    showReconnectDialog()
                }
            }
        }
    }

    /* ================= GET BEDIENER ================= */

    private fun requestUserListWithRetry() {

        if (requestRunning) return   // <<< WICHTIG
        requestRunning = true
        UiLoadingHelper.show(this, "Lade Benutzerliste...")

        ioScope.launch {
            val success = requestUserList()

            withContext(Dispatchers.Main) {
                UiLoadingHelper.hide()
                requestRunning = false       // <<< Hier wieder freigeben

                if (!success) {
                    runOnUiThread {
                        showRetryDialog(
                            "Timeout beim Laden der Benutzer",
                            onRetry = { requestUserListWithRetry() }
                        )
                    }
                }
            }
        }
    }

    private suspend fun requestUserList(): Boolean = withContext(Dispatchers.IO) {

        val response = TcpClient.sendCommand(
            context = this@LoginActivity,
            settings = settings,
            command = "GetBediener",
            request = "{GetBediener}",
            endTag = "{/GetBediener}"
        )

        if (response.isEmpty()) return@withContext false

        runOnUiThread {
            parseUserList(response)
            userListLoaded = true
            txtUsername.showDropDown()
        }

        true
    }

    private fun parseUserList(raw: String) {
        userList.clear()
        userPinMap.clear()

        raw.lines().forEach { line ->
            val parts = line.split("|")
            if (parts.size >= 3 && parts[0] != "Initial") {
                val name = parts[2]
                val pin = parts[1]
                userList.add(name)
                userPinMap[name] = pin
            }
        }

        val adapter = ArrayAdapter(this, android.R.layout.simple_dropdown_item_1line, userList)
        txtUsername.setAdapter(adapter)
    }

    /* ================= UI ================= */

    private fun setServerStatus(ok: Boolean, text: String) {
        txtServerStatus.text = text
        txtServerStatus.setTextColor(if (ok) Color.GREEN else Color.RED)
    }

    private fun updateReconnectButton() {
        btnReconnect.visibility =
            if (!serverConnected && !serverConnecting && !reconnectDialogVisible && !retryDialogVisible)
                Button.VISIBLE
            else
                Button.GONE
    }

    private fun showReconnectDialog() {
        if (reconnectDialogVisible) return
        reconnectDialogVisible = true
        updateReconnectButton()

        AlertDialog.Builder(this)
            .setTitle("Server nicht erreichbar")
            .setMessage("Erneut verbinden?")
            .setPositiveButton("Ja") { _, _ ->
                reconnectDialogVisible = false
                updateReconnectButton()
                connectToServer()
            }
            .setNegativeButton("Nein") { _, _ ->
                reconnectDialogVisible = false
                updateReconnectButton()
            }
            .setCancelable(false)
            .show()
    }

    private fun showRetryDialog(message: String, onRetry: () -> Unit) {
        if (retryDialogVisible) return
        retryDialogVisible = true
        updateReconnectButton()

        AlertDialog.Builder(this)
            .setTitle("Fehler")
            .setMessage(message)
            .setPositiveButton("Erneut versuchen") { _, _ ->
                retryDialogVisible = false
                updateReconnectButton()
                onRetry()
            }
            .setNegativeButton("Abbrechen") { _, _ ->
                retryDialogVisible = false
                updateReconnectButton()
            }
            .setCancelable(false)
            .show()
    }

    private fun showErrorDialog(message: String) {
        AlertDialog.Builder(this)
            .setTitle("Fehler")
            .setMessage(message)
            .setPositiveButton("OK", null)
            .setCancelable(false)
            .show()
    }

    /* ================= PLACEHOLDER ================= */

    private fun proceedToNextScreen(username: String) {
        val intent = Intent(this, MainActivity::class.java)
        intent.putExtra("USERNAME", username)
        startActivity(intent)
        finish()
    }

    /* ================= MENU ================= */
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
