package com.example.mde

import android.content.Intent
import android.os.Bundle
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar

class SettingsActivity : AppCompatActivity() {

    private lateinit var settings: AppSettings
    private lateinit var cbClear: CheckBox
    private lateinit var spTheme: Spinner

    private lateinit var sbFontScale: SeekBar
    private lateinit var tvFontScalePreview: TextView

    private lateinit var sbLayoutScale: SeekBar
    private lateinit var tvLayoutScalePreview: TextView

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

        val etIp = findViewById<EditText>(R.id.etServerIp)
        val etPort = findViewById<EditText>(R.id.etServerPort)
        val etTimeout = findViewById<EditText>(R.id.etTimeout)
        val etLogout = findViewById<EditText>(R.id.etLogoutTime)
        val etWerk = findViewById<EditText>(R.id.etWerkNummer)
        val etDefUser = findViewById<EditText>(R.id.etDefaultUser)
        cbClear = findViewById(R.id.cbClearAfterSuccess)
        val cbConfirmM = findViewById<CheckBox>(R.id.cbConfirmBook)
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

        // --- Laden (deine bestehenden Felder) ---
        etIp.setText(settings.serverIp)
        etPort.setText(settings.serverPort.toString())
        etTimeout.setText(settings.timeoutS.toString())
        etLogout.setText(settings.logoutTimeSec.toString())
        etWerk.setText(settings.werkNummer)
        etDefUser.setText(settings.defaultUser)
        cbClear.isChecked = settings.clearAfterSuccess
        cbConfirmM.isChecked = settings.confirmBook

        spTheme.setSelection(
            when (settings.selectedTheme) {
                "dark" -> 1
                "colorful" -> 2
                else -> 0
            }
        )

        // --- FontScale 0.50 .. 2.00 (progress 0..150 in 0.01-Schritten) ---
        sbFontScale.max = 150
        sbFontScale.progress = scaleToProgress(settings.fontScale, min = 0.5f)
        updateFontPreview(progressToScale(sbFontScale.progress, min = 0.5f))

        sbFontScale.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                updateFontPreview(progressToScale(progress, min = 0.5f))
            }
            override fun onStartTrackingTouch(seekBar: SeekBar) = Unit
            override fun onStopTrackingTouch(seekBar: SeekBar) = Unit
        })

        // --- LayoutScale 0.50 .. 2.00 (progress 0..150 in 0.01-Schritten) ---
        sbLayoutScale.max = 150
        sbLayoutScale.progress = scaleToProgress(settings.layoutScale, min = 0.5f)
        updateLayoutPreview(progressToScale(sbLayoutScale.progress, min = 0.5f))

        sbLayoutScale.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                updateLayoutPreview(progressToScale(progress, min = 0.5f))
            }
            override fun onStartTrackingTouch(seekBar: SeekBar) = Unit
            override fun onStopTrackingTouch(seekBar: SeekBar) = Unit
        })

        // --- Speichern ---
        btnSave.setOnClickListener {
            settings.serverIp = etIp.text.toString()
            settings.serverPort = etPort.text.toString().toIntOrNull() ?: 5000
            settings.timeoutS = etTimeout.text.toString().toIntOrNull() ?: 3000
            settings.logoutTimeSec = etLogout.text.toString().toIntOrNull() ?: 300
            settings.werkNummer = etWerk.text.toString()
            settings.defaultUser = etDefUser.text.toString()
            settings.clearAfterSuccess = cbClear.isChecked
            settings.confirmBook = cbConfirmM.isChecked

            settings.selectedTheme = when (spTheme.selectedItem.toString()) {
                "Dunkel" -> "dark"
                "Bunt" -> "colorful"
                else -> "light"
            }

            settings.fontScale = progressToScale(sbFontScale.progress, min = 0.5f)
            settings.layoutScale = progressToScale(sbLayoutScale.progress, min = 0.5f)

            restartApp()
        }
    }

    private fun applyTheme(theme: String) {
        when (theme) {
            "dark" -> setTheme(R.style.Theme_MDE_Dark)
            "colorful" -> setTheme(R.style.Theme_MDE_Colorful)
            else -> setTheme(R.style.Theme_MDE_Light)
        }
    }

    private fun restartApp() {
        val intent = packageManager.getLaunchIntentForPackage(packageName)
        intent?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        startActivity(intent)
        finish()
    }

    // progress 0..150 -> scale min..(min+1.50) => 0.50..2.00
    private fun progressToScale(progress: Int, min: Float): Float = min + (progress / 100f)

    private fun scaleToProgress(scale: Float, min: Float): Int =
        (((scale.coerceIn(min, 2.0f)) - min) * 100).toInt()

    private fun updateFontPreview(scale: Float) {
        tvFontScalePreview.text = "Schriftgröße: ${String.format("%.2f", scale)}x"
    }

    private fun updateLayoutPreview(scale: Float) {
        tvLayoutScalePreview.text = "Layout: ${String.format("%.2f", scale)}x"
    }
}