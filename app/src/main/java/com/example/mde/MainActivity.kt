package com.example.mde

import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar

data class Article(
    val barcode: String,
    val name: String,
    var quantity: Int,
    val location: String
)

class MainActivity : AppCompatActivity() {

    private var currentArticle: Article? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // Toolbar
        val toolbar = findViewById<Toolbar>(R.id.toolbar)
        setSupportActionBar(toolbar)

        // UI Elemente
        val etBarcode = findViewById<EditText>(R.id.etBarcode)
        val tvArticleInfo = findViewById<TextView>(R.id.tvArticleInfo)
        val btnScan = findViewById<Button>(R.id.btnScan)
        val btnAdd = findViewById<Button>(R.id.btnAdd)
        val btnRemove = findViewById<Button>(R.id.btnRemove)

        fun showQuantityDialog(title: String, onConfirm: (Int) -> Unit) {
            val input = EditText(this)
            input.inputType = android.text.InputType.TYPE_CLASS_NUMBER
            input.hint = "Anzahl eingeben"

            val dialog = AlertDialog.Builder(this)
                .setTitle(title)
                .setView(input)
                .setPositiveButton("Bestätigen", null)
                .setNegativeButton("Abbrechen") { d, _ -> d.dismiss() }
                .create()

            dialog.setOnShowListener {
                val button = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
                button.setOnClickListener {
                    val qty = input.text.toString().toIntOrNull()
                    if (qty == null || qty <= 0) {
                        Toast.makeText(this, "Bitte gültige Menge eingeben!", Toast.LENGTH_SHORT).show()
                    } else {
                        onConfirm(qty)
                        dialog.dismiss()
                    }
                }
            }

            dialog.show()
        }

        // Scan Button
        btnScan.setOnClickListener {
            sendTcpRequest(
                serverIp = "192.168.151.100",
                port = 5000,
                message = "TEST"
            ) { response ->
                tvArticleInfo.text = "Antwort vom Server: $response"
            }
        }

        // Zubuchung
        btnAdd.setOnClickListener {
            currentArticle?.let { article ->
                showQuantityDialog("Zubuchung") { qty ->
                    article.quantity += qty
                    tvArticleInfo.text = "Name: ${article.name}\nMenge: ${article.quantity}\nStandort: ${article.location}"
                }
            } ?: Toast.makeText(this, "Kein Artikel ausgewählt!", Toast.LENGTH_SHORT).show()
        }

        // Entnahme
        btnRemove.setOnClickListener {
            currentArticle?.let { article ->
                showQuantityDialog("Entnahme") { qty ->
                    article.quantity = (article.quantity - qty).coerceAtLeast(0)
                    tvArticleInfo.text = "Name: ${article.name}\nMenge: ${article.quantity}\nStandort: ${article.location}"
                }
            } ?: Toast.makeText(this, "Kein Artikel ausgewählt!", Toast.LENGTH_SHORT).show()
        }
    }

    /* ================= TCP HELPER ================= */
    fun sendTcpRequest(
        serverIp: String,
        port: Int,
        message: String,
        onResult: (String) -> Unit
    ) {
        Thread {
            try {
                val socket = java.net.Socket(serverIp, port)
                val writer = socket.getOutputStream()
                val reader = socket.getInputStream().bufferedReader()

                writer.write((message + "\n").toByteArray())
                writer.flush()

                val response = reader.readLine()
                socket.close()

                runOnUiThread {
                    onResult(response ?: "Keine Antwort")
                }
            } catch (e: Exception) {
                runOnUiThread {
                    onResult("FEHLER: ${e.message}")
                }
            }
        }.start()
    }

    /* ================= MENU ================= */
    override fun onCreateOptionsMenu(menu: android.view.Menu?): Boolean {
        menuInflater.inflate(R.menu.main_menu, menu)
        return true
    }

    override fun onOptionsItemSelected(item: android.view.MenuItem): Boolean {
        return when (item.itemId) {
            R.id.menu_settings -> {
                startActivity(Intent(this, SettingsActivity::class.java))
                true
            }

            R.id.menu_logout -> {
                val intent = Intent(this, LoginActivity::class.java)
                intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                startActivity(intent)
                finish()
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }
}
