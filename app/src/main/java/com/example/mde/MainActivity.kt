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
    private lateinit var timeoutRunnable: Runnable
    private lateinit var settings: AppSettings

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

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

        val btnInfoScan = findViewById<Button>(R.id.btnInfoScan)
        val btnPickList = findViewById<Button>(R.id.btnPickList)
        val btnDropList = findViewById<Button>(R.id.btnDropList)
        val btnSingleAdd = findViewById<Button>(R.id.btnSingleAdd)
        val btnSingleRemove = findViewById<Button>(R.id.btnSingleRemove)
        val btnTransfer = findViewById<Button>(R.id.btnTransfer)
        val btnInventory = findViewById<Button>(R.id.btnInventory)

        btnInfoScan.setOnClickListener {
            val intent = Intent(this@MainActivity, InfoScanActivity::class.java)
            startActivity(intent)
        }

//        btnPickList.setOnClickListener {
//            val intent = Intent(this@MainActivity, ListRemoveActivity::class.java)
//            startActivity(intent)
//        }
//
//        btnDropList.setOnClickListener {
//            val intent = Intent(this@MainActivity, ListAddActivity::class.java)
//            startActivity(intent)
//        }
//
//        btnSingleAdd.setOnClickListener {
//            val intent = Intent(this@MainActivity, SingleAddActivity::class.java)
//            startActivity(intent)
//        }
//
//        btnSingleRemove.setOnClickListener {
//            val intent = Intent(this@MainActivity, SingleRemoveActivity::class.java)
//            startActivity(intent)
//        }
//
//        btnTransfer.setOnClickListener {
//            val intent = Intent(this@MainActivity, TransferActivity::class.java)
//            startActivity(intent)
//        }
//
//        btnInventory.setOnClickListener {
//            val intent = Intent(this@MainActivity, InventoryActivity::class.java)
//            startActivity(intent)
//        }
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
