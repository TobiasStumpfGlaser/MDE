package com.example.mde

import android.Manifest
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.MenuItem
import android.view.MotionEvent
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.common.InputImage
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Scanner-Activity für Barcode-Erfassung.
 *
 * Unterstützt sowohl die Kamera-basierte ML-Kit-Erkennung als auch den
 * HID-Barcode-Scanner des Datalogic-Geräts über einen unsichtbaren [EditText].
 * Nach erfolgreichem Scan wird der Barcode per [RESULT_OK] an die rufende Activity
 * zurückgegeben.
 */
class ScannerActivity : AppCompatActivity() {

    companion object {
        /** Request-Code für die Kamera-Berechtigung. */
        private const val CAMERA_PERMISSION_REQUEST = 100
    }

    // ── Views ────────────────────────────────────────────────────────────────

    private lateinit var previewView: PreviewView
    private lateinit var btnCancel: Button

    /** Unsichtbarer EditText für HID-Barcode-Eingaben des Datalogic-Scanners. */
    private lateinit var hiddenScanInput: EditText

    // ── Kamera & Scanner-Zustand ──────────────────────────────────────────────

    private lateinit var cameraExecutor: ExecutorService
    private var currentBarcodeText: String? = null
    private var barcodeConfirmed = false

    // ── Inaktivitäts-Timer ───────────────────────────────────────────────────

    private lateinit var handler: Handler
    private lateinit var timeoutRunnable: Runnable
    private var timeoutMillis = 0L

    private val barcodeBuffer = StringBuilder()

    // ── Lifecycle ────────────────────────────────────────────────────────────

    override fun onCreate(savedInstanceState: Bundle?) {
        val settings = AppSettings(this)
        when (settings.selectedTheme) {
            "dark" -> setTheme(R.style.Theme_MDE_Dark)
            "colorful" -> setTheme(R.style.Theme_MDE_Colorful)
            else -> setTheme(R.style.Theme_MDE_Light)
        }

        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_scanner)

        val toolbar = findViewById<Toolbar>(R.id.toolbar)
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayShowTitleEnabled(false)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        toolbar.navigationIcon?.setTint(resources.getColor(android.R.color.white, theme))

        previewView = findViewById(R.id.previewView)
        btnCancel = findViewById(R.id.btnCancel)
        hiddenScanInput = findViewById(R.id.hiddenScanInput)

        timeoutMillis = settings.logoutTimeSec * 1000L
        handler = Handler(Looper.getMainLooper())
        timeoutRunnable = Runnable {
            startActivity(Intent(this, LoginActivity::class.java))
            overridePendingTransition(0, 0)
            finish()
        }

        cameraExecutor = Executors.newSingleThreadExecutor()

        // Kamera-Berechtigung prüfen
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.CAMERA),
                CAMERA_PERMISSION_REQUEST
            )
        } else {
            startCamera()
        }

        // Hardware-Scanner aktivieren
        setupDatalogicScanInput()

        btnCancel.setOnClickListener { finish() }

        val txtHeader = findViewById<TextView>(R.id.txtHeader)
        txtHeader.text = "BW MDE - Werk: ${settings.werkNummer}"
    }

    override fun onResume() {
        super.onResume()
        resetTimeout()
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(timeoutRunnable)
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor.shutdown()
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) {
            finish()
            return true
        }
        return super.onOptionsItemSelected(item)
    }

    /** Verarbeitet das Ergebnis der Kamera-Berechtigungsanfrage. */
    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)

        if (requestCode == CAMERA_PERMISSION_REQUEST &&
            grantResults.isNotEmpty() &&
            grantResults[0] == PackageManager.PERMISSION_GRANTED
        ) {
            startCamera()
        }
    }

    // ── Datalogic HID-Scanner ─────────────────────────────────────────────────

    /**
     * Richtet den unsichtbaren [EditText] für HID-Barcode-Eingaben ein.
     * Zeichen werden im [barcodeBuffer] gesammelt; ein Enter-Zeichen signalisiert
     * das Ende des Barcodes.
     */
    private fun setupDatalogicScanInput() {
        // Fokus auf unsichtbaren EditText setzen
        hiddenScanInput.requestFocus()

        // KeyListener für HID Barcode Scans
        hiddenScanInput.setOnKeyListener { _, keyCode, event ->
            if (event.action == KeyEvent.ACTION_DOWN) {
                val char = event.unicodeChar.toChar()
                if (char == '\n') {
                    // Enter → Barcode fertig
                    val barcodeText = barcodeBuffer.toString()
                    barcodeBuffer.clear()
                    if (barcodeText.isNotEmpty() && !barcodeConfirmed) {
                        barcodeConfirmed = true
                        showConfirmDialog(barcodeText)
                    }
                    true
                } else {
                    // Zeichen zum Buffer hinzufügen
                    barcodeBuffer.append(char)
                    true
                }
            } else false
        }
    }

    // ── Kamera-Scan ───────────────────────────────────────────────────────────

    /** Initialisiert die CameraX-Preview und den ML-Kit-Barcode-Analyzer. */
    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            val cameraProvider = cameraProviderFuture.get()

            val preview = Preview.Builder().build().also {
                it.surfaceProvider = previewView.surfaceProvider
            }

            val scannerOptions = BarcodeScannerOptions.Builder()
                .setBarcodeFormats(
                    com.google.mlkit.vision.barcode.common.Barcode.FORMAT_ALL_FORMATS
                )
                .build()
            val scanner = BarcodeScanning.getClient(scannerOptions)

            val analysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
                .also { analysisUseCase ->
                    analysisUseCase.setAnalyzer(cameraExecutor) { imageProxy ->
                        val mediaImage = imageProxy.image
                        if (mediaImage != null && !barcodeConfirmed) {
                            val image = InputImage.fromMediaImage(mediaImage, imageProxy.imageInfo.rotationDegrees)
                            scanner.process(image)
                                .addOnSuccessListener { barcodes ->
                                    val barcodeText = barcodes.firstOrNull()?.rawValue
                                    if (!barcodeText.isNullOrEmpty()) {
                                        currentBarcodeText = barcodeText
                                        barcodeConfirmed = true
                                        runOnUiThread { showConfirmDialog(barcodeText) }
                                    }
                                }
                                .addOnFailureListener { }
                                .addOnCompleteListener { imageProxy.close() }
                        } else {
                            imageProxy.close()
                        }
                    }
                }

            try {
                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(
                    this,
                    CameraSelector.DEFAULT_BACK_CAMERA,
                    preview,
                    analysis
                )
            } catch (e: Exception) {
                Toast.makeText(this, "Fehler Kamera: ${e.message}", Toast.LENGTH_LONG).show()
            }

        }, ContextCompat.getMainExecutor(this))
    }

    // ── Barcode-Bestätigung ───────────────────────────────────────────────────

    /**
     * Zeigt einen Bestätigungsdialog an, nachdem ein Barcode erkannt wurde.
     * Bei Bestätigung wird der Barcode als [RESULT_OK]-Ergebnis zurückgegeben.
     */
    private fun showConfirmDialog(barcodeText: String) {
        AlertDialog.Builder(this)
            .setTitle("Barcode erkannt")
            .setMessage("Meinst du diesen Barcode?\n$barcodeText")
            .setPositiveButton("Ja") { _, _ ->
                val intent = Intent()
                intent.putExtra("barcode", barcodeText)
                setResult(RESULT_OK, intent)
                finish()
            }
            .setNegativeButton("Nein") { _, _ ->
                barcodeConfirmed = false
                currentBarcodeText = null
            }
            .setCancelable(false)
            .show()
    }

    // ── Inaktivitäts-Timer ───────────────────────────────────────────────────

    /** Setzt den Logout-Timer bei jeder Berührung zurück. */
    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        resetTimeout()
        return super.dispatchTouchEvent(ev)
    }

    /**
     * Entfernt ausstehende Timeout-Callbacks und plant einen neuen
     * Logout-Runnable nach [timeoutMillis] Millisekunden.
     */
    private fun resetTimeout() {
        handler.removeCallbacks(timeoutRunnable)
        handler.postDelayed(timeoutRunnable, timeoutMillis)
    }
}