package com.example.mde

import android.content.Intent
import android.util.Log
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Zeigt den Update-Dialog an und startet den Download sowie die Installation der APK.
 *
 * Verwendungsbeispiel:
 * ```kotlin
 * OtaUpdateHelper(activity, settings).showUpdateDialog(updateInfo) {
 *     // Wird aufgerufen, wenn der Benutzer "Nein" wählt oder den Dialog schließt
 *     navigateToMain()
 * }
 * ```
 */
class OtaUpdateHelper(
    private val activity: AppCompatActivity,
    private val settings: AppSettings
) {
    companion object {
        private const val TAG = "OtaUpdateHelper"
    }

    /**
     * Zeigt den Update-Dialog.
     *
     * @param updateInfo  Informationen zur verfügbaren Version.
     * @param onContinue  Wird aufgerufen, wenn der Benutzer "Nein" wählt oder der Dialog
     *                    abgebrochen wird (nur wenn [UpdateInfo.mandatory] `false` ist).
     */
    fun showUpdateDialog(updateInfo: UpdateInfo, onContinue: () -> Unit) {
        val message = buildString {
            append("Version: ${updateInfo.version}")
            if (updateInfo.releaseNotes.isNotBlank()) {
                append("\n\n")
                append(updateInfo.releaseNotes)
            }
        }

        AlertDialog.Builder(activity)
            .setTitle("Neue Version verfügbar")
            .setMessage("Möchten Sie aktualisieren?\n\n$message")
            .setPositiveButton("Ja") { _, _ ->
                downloadAndInstall(updateInfo)
            }
            .setNegativeButton("Nein") { _, _ ->
                onContinue()
            }
            .setCancelable(!updateInfo.mandatory)
            .apply {
                if (!updateInfo.mandatory) {
                    setOnCancelListener { onContinue() }
                }
            }
            .show()
    }

    private fun downloadAndInstall(updateInfo: UpdateInfo) {
        val updateManager = UpdateManager(activity)

        val progressDialog = AlertDialog.Builder(activity)
            .setTitle("Update wird heruntergeladen")
            .setMessage("Bitte warten...")
            .setCancelable(false)
            .show()

        activity.lifecycleScope.launch(Dispatchers.IO) {
            try {
                val apkFile = updateManager.downloadApk(updateInfo, settings)
                withContext(Dispatchers.Main) {
                    progressDialog.dismiss()
                    installApk(apkFile)
                }
            } catch (e: SecurityException) {
                Log.e(TAG, "Fehler beim Herunterladen/Installieren des Updates", e)
                withContext(Dispatchers.Main) {
                    progressDialog.dismiss()
                    AlertDialog.Builder(activity)
                        .setTitle("Update fehlgeschlagen")
                        .setMessage("Die heruntergeladene Datei konnte nicht verifiziert werden. Bitte wenden Sie sich an den Administrator.")
                        .setPositiveButton("OK", null)
                        .show()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Fehler beim Herunterladen/Installieren des Updates", e)
                withContext(Dispatchers.Main) {
                    progressDialog.dismiss()
                    AlertDialog.Builder(activity)
                        .setTitle("Update fehlgeschlagen")
                        .setMessage("Das Update konnte nicht heruntergeladen werden. Bitte versuchen Sie es später erneut.")
                        .setPositiveButton("OK", null)
                        .show()
                }
            }
        }
    }

    private fun installApk(apkFile: File) {
        val uri = FileProvider.getUriForFile(
            activity,
            "${activity.packageName}.fileprovider",
            apkFile
        )
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        activity.startActivity(intent)
    }
}
