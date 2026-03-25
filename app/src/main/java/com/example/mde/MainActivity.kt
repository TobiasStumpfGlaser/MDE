package com.example.mde

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import android.os.Handler
import android.os.Looper
import android.view.MotionEvent
import android.widget.*


class MainActivity : AppCompatActivity() {
    private var timeoutMillis: Long = 0L
    private lateinit var handler: Handler
    private lateinit var username: String
    private lateinit var timeoutRunnable: Runnable
    private lateinit var settings: AppSettings

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        username = intent.getStringExtra("USERNAME") ?: ""

        // Handler erst nach onCreate initialisieren
        handler = Handler(Looper.getMainLooper())

        // Runnable auch hier, nicht als Property
        timeoutRunnable = Runnable {
            val intent = Intent(this@MainActivity, LoginActivity::class.java)
            intent.flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NEW_TASK
            startActivity(intent)
            finish()
        }

        resetInactivityTimer()

        settings = AppSettings(this) // Context übergeben
        timeoutMillis = settings.logoutTimeSec * 1000L

        // Toolbar
        val toolbar = findViewById<Toolbar>(R.id.toolbar)
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayShowTitleEnabled(false)

        val btnMaterialBook = findViewById<Button>(R.id.btnMaterialBook)
        val btnPicklist = findViewById<androidx.appcompat.widget.AppCompatButton>(R.id.btnPicklist)
        val btnDroplist = findViewById<androidx.appcompat.widget.AppCompatButton>(R.id.btnDroplist)
        val btnInventur = findViewById<androidx.appcompat.widget.AppCompatButton>(R.id.btnInventur)

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
    }

    /* ================= Inaktivität ================= */
    override fun onResume() {
        super.onResume()
        resetInactivityTimer()
    }

    override fun onPause() {
        super.onPause()
        stopInactivityTimer()
    }

    override fun dispatchTouchEvent(ev: MotionEvent?): Boolean {
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
