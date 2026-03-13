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
import android.view.WindowManager

object UserCache {
    val userList = mutableListOf<String>()
    val userPinMap = mutableMapOf<String,String>()
}

class LoginActivity : AppCompatActivity() {
    private var requestRunning = false
    private lateinit var settings: AppSettings
    private lateinit var txtServerStatus: TextView
    private lateinit var btnReconnect: Button
    private lateinit var txtUsername: AutoCompleteTextView
    private lateinit var txtPin: EditText
    private lateinit var btnLogin: Button
    private val defaultUser = "Produktion"
    private var serverConnected = false
    private var serverConnecting = false
    private var reconnectDialogVisible = false
    private var retryDialogVisible = false
    private val userList get() = UserCache.userList
    private val userPinMap get() = UserCache.userPinMap

    private val ioScope = CoroutineScope(Dispatchers.IO + Job())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE)
        setContentView(R.layout.activity_login)

        TcpLogHelper.clearLogs(this)
        settings = AppSettings(this)

        val toolbar = findViewById<Toolbar>(R.id.toolbar)
        setSupportActionBar(toolbar)

        txtServerStatus = findViewById(R.id.txtServerStatus)
        btnReconnect = findViewById(R.id.btnReconnect)
        txtUsername = findViewById(R.id.txtUsername)
        txtPin = findViewById(R.id.txtPin)
        btnLogin = findViewById(R.id.btnLogin)

        if (userList.isNotEmpty()) {
            val adapter = ArrayAdapter(
                this,
                android.R.layout.simple_dropdown_item_1line,
                userList
            )
            txtUsername.setAdapter(adapter)
            selectDefaultUserIfAvailable()
        }

        val txtVersion = findViewById<TextView>(R.id.txtVersion)
        txtVersion.text = "App-Version: ${BuildConfig.VERSION_NAME}"

        btnReconnect.setOnClickListener {
            connectToServer()
        }

        txtUsername.setOnClickListener {
            txtUsername.requestFocus()
            val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
            imm.showSoftInput(txtUsername, InputMethodManager.SHOW_IMPLICIT)

            if (!serverConnected) {
                showErrorDialog("Keine Serververbindung")
                return@setOnClickListener
            }

            if (userList.isEmpty()) {
                requestUserListWithRetry()
            } else {
                txtUsername.showDropDown()
            }
        }

        txtUsername.setOnItemClickListener { _, _, _, _ ->
            txtPin.requestFocus()
            val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
            imm.showSoftInput(txtPin, InputMethodManager.SHOW_IMPLICIT)
        }

        btnLogin.setOnClickListener {
            val username = txtUsername.text.toString()
            val pin = txtPin.text.toString()

            if (userList.isEmpty()) {
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

            proceedToNextScreen(username)
        }

        connectToServer()
    }

    override fun onDestroy() {
        super.onDestroy()
        ioScope.cancel()
    }

    /* ================= SERVER CONNECT ================= */

    private fun connectToServer() {
        setServerStatus(false, "Verbinde…")
        serverConnecting = true
        updateReconnectButton()

        ioScope.launch {
            try {
                Socket().use { socket ->
                    socket.connect(
                        InetSocketAddress(settings.serverIp, settings.serverPort),
                        settings.timeoutS * 1000
                    )
                }

                withContext(Dispatchers.Main) {
                    serverConnected = true
                    serverConnecting = false
                    setServerStatus(true, "Server verbunden")
                    updateReconnectButton()
                    if (userList.isEmpty()) requestUserListWithRetry()
                }

            } catch (e: Exception) {

                withContext(Dispatchers.Main) {
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

        if (requestRunning) return
        requestRunning = true
        UiLoadingHelper.show(this, "Lade Benutzerliste...")

        ioScope.launch {

            val success = requestUserList()

            withContext(Dispatchers.Main) {

                UiLoadingHelper.hide()
                requestRunning = false

                if (!success) {
                    showRetryDialog(
                        "Timeout beim Laden der Benutzer",
                        onRetry = { requestUserListWithRetry() }
                    )
                }
            }
        }
    }

    private suspend fun requestUserList(): Boolean {

        val response = withContext(Dispatchers.IO) {

            TcpClient.sendCommand(
                context = this@LoginActivity,
                settings = settings,
                command = "GetBediener",
                request = "{GetBediener}",
                endTag = "{/GetBediener}"
            )
        }

        if (response.isEmpty()) return false

        withContext(Dispatchers.Main) {

            parseUserList(response)
            selectDefaultUserIfAvailable()

            //if (!isFinishing && !isDestroyed) {
            //    txtUsername.showDropDown()
            //}
        }

        return true
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

        val adapter =
            ArrayAdapter(this, android.R.layout.simple_dropdown_item_1line, userList)

        txtUsername.setAdapter(adapter)
    }

    private fun selectDefaultUserIfAvailable() {

        if (userList.contains(defaultUser)) {
            txtUsername.setText(defaultUser, false)
            txtUsername.clearFocus()
            txtUsername.dismissDropDown()

            txtPin.post {
                txtPin.requestFocus()
                val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
                imm.showSoftInput(txtPin, InputMethodManager.SHOW_IMPLICIT)
            }
        }
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

    private fun proceedToNextScreen(username: String) {

        val intent = Intent(this, MainActivity::class.java)
        intent.putExtra("USERNAME", username)

        startActivity(intent)
        overridePendingTransition(0,0)
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