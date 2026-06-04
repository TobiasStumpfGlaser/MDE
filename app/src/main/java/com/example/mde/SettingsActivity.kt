package com.example.mde

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.MotionEvent
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import kotlin.math.roundToInt

/**
 * Einstellungs-Activity.
 *
 * Erlaubt das Anpassen von Server-Adresse, Timeouts, Werknummer, Theme sowie
 * Schrift- und Layout-Skalierung. Alle Werte werden in [AppSettings] persistiert.
 * Ein Neustart der App via [restartApp] ist nach dem Speichern notwendig,
 * damit Theme- und Skalierungsänderungen wirksam werden.
 */
class SettingsActivity : AppCompatActivity() {

    private lateinit var settings: AppSettings
    private lateinit var cbClear: CheckBox
    private lateinit var spTheme: Spinner

    private lateinit var sbFontScale: SeekBar
    private lateinit var tvFontScalePreview: TextView

    private lateinit var sbLayoutScale: SeekBar
    private lateinit var tvLayoutScalePreview: TextView
    private lateinit var handler: Handler
    private lateinit var timeoutRunnable: Runnable
    private var timeoutMillis: Long = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        settings = AppSettings(this)
        applyTheme(settings.selectedTheme)

        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        val toolbar = findViewById<Toolbar>(R.id.toolbar)
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayShowTitleEnabled(false)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        toolbar.navigationIcon?.setColorFilter(
            resources.getColor(android.R.color.white, theme),
            android.graphics.PorterDuff.Mode.SRC_ATOP
        )

        handler = Handler(Looper.getMainLooper())
        timeoutRunnable = Runnable {
            val intent = Intent(this@SettingsActivity, LoginActivity::class.java)
            intent.flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NEW_TASK
            startActivity(intent)
            finish()
        }
        timeoutMillis = settings.logoutTimeSec * 1000L
        resetInactivityTimer()

        val etIp = findViewById<EditText>(R.id.etServerIp)
        val etPort = findViewById<EditText>(R.id.etServerPort)
        val etTimeout = findViewById<EditText>(R.id.etTimeout)
        val etLogout = findViewById<EditText>(R.id.etLogoutTime)
        val etWerk = findViewById<EditText>(R.id.etWerkNummer)
        val etDefUser = findViewById<EditText>(R.id.etDefaultUser)
        cbClear = findViewById(R.id.cbClearAfterSuccess)
        val btnSave = findViewById<Button>(R.id.btnSave)
        spTheme = findViewById(R.id.spTheme)

        sbFontScale = findViewById(R.id.sbFontScale)
        tvFontScalePreview = findViewById(R.id.tvFontScalePreview)
        sbLayoutScale = findViewById(R.id.sbLayoutScale)
        tvLayoutScalePreview = findViewById(R.id.tvLayoutScalePreview)

        val themeItems = listOf("Hell", "Dunkel", "Bunt")
        val themeAdapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_item,
            themeItems
        )
        themeAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spTheme.adapter = themeAdapter

        // Laden
        etIp.setText(settings.serverIp)
        etPort.setText(settings.serverPort.toString())
        etTimeout.setText(settings.timeoutS.toString())
        etLogout.setText(settings.logoutTimeSec.toString())
        etWerk.setText(settings.werkNummer)
        etDefUser.setText(settings.defaultUser)
        cbClear.isChecked = settings.clearAfterSuccess

        spTheme.setSelection(
            when (settings.selectedTheme) {
                "dark" -> 1
                "colorful" -> 2
                else -> 0
            }
        )

        // 0.25er Schritte: 0..7 -> 0.25..2.00
        sbFontScale.progress = scaleToStep(settings.fontScale)
        updateFontPreview(stepToScale(sbFontScale.progress))
        sbFontScale.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                updateFontPreview(stepToScale(progress))
            }
            override fun onStartTrackingTouch(seekBar: SeekBar) = Unit
            override fun onStopTrackingTouch(seekBar: SeekBar) = Unit
        })

        sbLayoutScale.progress = scaleToStep(settings.layoutScale)
        updateLayoutPreview(stepToScale(sbLayoutScale.progress))
        sbLayoutScale.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                updateLayoutPreview(stepToScale(progress))
            }
            override fun onStartTrackingTouch(seekBar: SeekBar) = Unit
            override fun onStopTrackingTouch(seekBar: SeekBar) = Unit
        })

        btnSave.setOnClickListener {
            settings.serverIp = etIp.text.toString()
            settings.serverPort = etPort.text.toString().toIntOrNull() ?: 5000
            settings.timeoutS = etTimeout.text.toString().toIntOrNull() ?: 3000
            settings.logoutTimeSec = etLogout.text.toString().toIntOrNull() ?: 300
            settings.werkNummer = etWerk.text.toString()
            settings.defaultUser = etDefUser.text.toString()
            settings.clearAfterSuccess = cbClear.isChecked

            settings.selectedTheme = when (spTheme.selectedItem.toString()) {
                "Dunkel" -> "dark"
                "Bunt" -> "colorful"
                else -> "light"
            }

            // speichern (0.25er Schritte)
            settings.fontScale = stepToScale(sbFontScale.progress)
            settings.layoutScale = stepToScale(sbLayoutScale.progress)

            restartApp()
        }

        val txtHeader = findViewById<TextView>(R.id.txtHeader)
        txtHeader.text = "BW MDE - Werk: ${settings.werkNummer}"
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

    /** Wendet das gespeicherte Theme vor [super.onCreate] an. */
    private fun applyTheme(theme: String) {
        when (theme) {
            "dark" -> setTheme(R.style.Theme_MDE_Dark)
            "colorful" -> setTheme(R.style.Theme_MDE_Colorful)
            else -> setTheme(R.style.Theme_MDE_Light)
        }
    }

    /** Startet die App neu, damit Theme- und Skalierungsänderungen wirksam werden. */
    private fun restartApp() {
        val intent = packageManager.getLaunchIntentForPackage(packageName)
        intent?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        startActivity(intent)
        finish()
    }

    /**
     * Wandelt einen SeekBar-Step (0..7) in einen Skalierungsfaktor (0.25..2.00) um.
     * Schrittweite: 0,25.
     */
    // step 0..7 => scale = 0.25 + step*0.25 => 0.25..2.00
    private fun stepToScale(step: Int): Float =
        (0.25f + (step.coerceIn(0, 7) * 0.25f)).coerceIn(0.25f, 2.0f)

    /** Wandelt einen Skalierungsfaktor (0.25..2.00) in den nächstliegenden SeekBar-Step (0..7) um. */
    // scale -> nearest step (0..7)
    private fun scaleToStep(scale: Float): Int {
        val clamped = scale.coerceIn(0.25f, 2.0f)
        return ((clamped - 0.25f) / 0.25f).roundToInt().coerceIn(0, 7)
    }

    /** Aktualisiert die Vorschau-TextView mit dem aktuellen Schriftskalierungsfaktor. */
    private fun updateFontPreview(scale: Float) {
        tvFontScalePreview.text = "Schriftgröße: ${String.format("%.2f", scale)}x"
    }

    /** Aktualisiert die Vorschau-TextView mit dem aktuellen Layout-Skalierungsfaktor. */
    private fun updateLayoutPreview(scale: Float) {
        tvLayoutScalePreview.text = "Layout: ${String.format("%.2f", scale)}x"
    }

    private fun resetInactivityTimer() {
        handler.removeCallbacks(timeoutRunnable)
        handler.postDelayed(timeoutRunnable, timeoutMillis)
    }

    private fun stopInactivityTimer() {
        handler.removeCallbacks(timeoutRunnable)
    }
}