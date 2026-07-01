package com.example.mde

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.MotionEvent
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.AppCompatButton
import androidx.appcompat.widget.Toolbar

/**
 * Hauptmenü der App.
 *
 * Zeigt die vier Hauptfunktionen an (Material-Buchung, Pickliste, Dropliste, Inventur)
 * und verwaltet einen Inaktivitäts-Timer, der nach Ablauf automatisch zur
 * [LoginActivity] navigiert.
 */
class MainActivity : AppCompatActivity() {

    // ── Inaktivitäts-Timer ───────────────────────────────────────────────────

    private var timeoutMillis: Long = 0L
    private lateinit var handler: Handler
    private lateinit var username: String
    private lateinit var timeoutRunnable: Runnable

    private val allowedOrderUsers = setOf("RKL", "RG", "MRE", "AMA", "SRE", "CBE")

    // ── Lifecycle ────────────────────────────────────────────────────────────

    override fun onCreate(savedInstanceState: Bundle?) {
        val settings = AppSettings(this)
        when (settings.selectedTheme) {
            "dark" -> setTheme(R.style.Theme_MDE_Dark)
            "colorful" -> setTheme(R.style.Theme_MDE_Colorful)
            else -> setTheme(R.style.Theme_MDE_Light)
        }

        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        username = intent.getStringExtra("USERNAME") ?: ""

        handler = Handler(Looper.getMainLooper())
        timeoutRunnable = Runnable {
            val intent = Intent(this@MainActivity, LoginActivity::class.java)
            intent.flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NEW_TASK
            startActivity(intent)
            finish()
        }

        // timeoutMillis muss vor dem ersten resetInactivityTimer()-Aufruf gesetzt werden,
        // damit der Handler mit dem korrekten Delay startet.
        timeoutMillis = settings.logoutTimeSec * 1000L
        resetInactivityTimer()

        // Toolbar
        val toolbar = findViewById<Toolbar>(R.id.toolbar)
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayShowTitleEnabled(false)

        val btnMaterialBook = findViewById<Button>(R.id.btnMaterialBook)
        val btnPicklist = findViewById<AppCompatButton>(R.id.btnPicklist)
        val btnDroplist = findViewById<AppCompatButton>(R.id.btnDroplist)
        val btnBestellung = findViewById<AppCompatButton>(R.id.btnBestellung)
        val btnInventur = findViewById<AppCompatButton>(R.id.btnInventur)

        val txtHeader = findViewById<TextView>(R.id.txtHeader)
        txtHeader.text = "BW MDE - Werk: ${settings.werkNummer}"

        btnMaterialBook.setOnClickListener {
            val intent = Intent(this@MainActivity, MaterialBuchungActivity::class.java)
            intent.putExtra("USERNAME", username)
            startActivity(intent)
        }

        btnPicklist.setOnClickListener {
            val intent = Intent(this@MainActivity, PickListActivity::class.java)
            intent.putExtra("USERNAME", username)
            startActivity(intent)
        }

        btnDroplist.setOnClickListener {
            val intent = Intent(this@MainActivity, DropListActivity::class.java)
            intent.putExtra("USERNAME", username)
            startActivity(intent)
        }

        btnInventur.setOnClickListener {
            val intent = Intent(this@MainActivity, InventurActivity::class.java)
            intent.putExtra("USERNAME", username)
            startActivity(intent)
        }

        btnBestellung.setOnClickListener {
            val intent = Intent(this@MainActivity, BestellungActivity::class.java)
            intent.putExtra("USERNAME", username)
            startActivity(intent)
        }

        if (allowedOrderUsers.contains(username)) {
            btnBestellung.visibility = android.view.View.VISIBLE
        } else {
            btnBestellung.visibility = android.view.View.GONE
        }
    }

    /** Startet den Inaktivitäts-Timer neu, wenn die Activity wieder sichtbar wird. */
    override fun onResume() {
        super.onResume()
        resetInactivityTimer()
    }

    /** Stoppt den Inaktivitäts-Timer, solange die Activity im Hintergrund ist. */
    override fun onPause() {
        super.onPause()
        stopInactivityTimer()
    }

    /** Setzt den Inaktivitäts-Timer bei jeder Berührung zurück. */
    override fun dispatchTouchEvent(ev: MotionEvent?): Boolean {
        resetInactivityTimer()
        return super.dispatchTouchEvent(ev)
    }

    // ── Timer-Hilfsmethoden ──────────────────────────────────────────────────

    /**
     * Entfernt ausstehende Callbacks und plant einen neuen Logout-Runnable
     * nach [timeoutMillis] Millisekunden.
     */
    private fun resetInactivityTimer() {
        handler.removeCallbacks(timeoutRunnable)
        handler.postDelayed(timeoutRunnable, timeoutMillis)
    }

    /** Entfernt ausstehende Logout-Callbacks (z. B. beim Pausieren der Activity). */
    private fun stopInactivityTimer() {
        handler.removeCallbacks(timeoutRunnable)
    }

    // ── Menü ─────────────────────────────────────────────────────────────────

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
