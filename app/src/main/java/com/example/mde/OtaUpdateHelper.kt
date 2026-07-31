package com.example.mde

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.Log
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Zeigt die OTA-Update-Abfrage, lädt die geprüfte APK und übergibt sie an den
 * Android-Paketinstaller.
 *
 * Die Instanz muss während [AppCompatActivity.onCreate] erzeugt werden, damit
 * der Activity-Result-Handler für die Berechtigung "Unbekannte Apps
 * installieren" rechtzeitig registriert ist.
 */
class OtaUpdateHelper(
    private val activity: AppCompatActivity,
    restoredPendingApkPath: String? = null
) {
    companion object {
        private const val TAG = "OtaUpdateHelper"
        private const val OTA_CACHE_DIRECTORY = "ota"
    }

    private var pendingApk: File? = restoredPendingApkPath
        ?.let(::File)
        ?.let(::validatedOtaApkOrNull)

    private val unknownSourcesLauncher = activity.registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        continueInstallAfterPermissionSettings()
    }

    /** Pfad einer noch ausstehenden APK für die Zustandswiederherstellung. */
    fun pendingApkPathForState(): String? = pendingApk?.absolutePath

    /** Zeigt ausschließlich die vom Nutzer gewünschte Ja-/Nein-Abfrage. */
    fun showUpdateDialog(updateInfo: UpdateInfo) {
        if (activity.isFinishing || activity.isDestroyed) return

        AlertDialog.Builder(activity)
            .setTitle("Neue Version verfügbar")
            .setMessage("Update durchführen?")
            .setPositiveButton("Ja") { _, _ -> downloadAndInstall(updateInfo) }
            .setNegativeButton("Nein", null)
            .setCancelable(false)
            .show()
    }

    private fun downloadAndInstall(updateInfo: UpdateInfo) {
        val progressDialog = AlertDialog.Builder(activity)
            .setTitle("Update wird heruntergeladen")
            .setMessage("Bitte warten...")
            .setCancelable(false)
            .show()

        activity.lifecycleScope.launch(Dispatchers.IO) {
            try {
                val apkFile = UpdateManager(activity).downloadApk(updateInfo)
                withContext(Dispatchers.Main) {
                    progressDialog.dismiss()
                    requestPackageInstall(apkFile)
                }
            } catch (error: SecurityException) {
                Log.e(TAG, "OTA-APK konnte nicht verifiziert werden", error)
                withContext(Dispatchers.Main) {
                    progressDialog.dismiss()
                    showError(
                        "Update fehlgeschlagen",
                        "Die heruntergeladene Datei konnte nicht verifiziert werden. " +
                            "Bitte wenden Sie sich an den Administrator."
                    )
                }
            } catch (error: Exception) {
                Log.e(TAG, "OTA-Update fehlgeschlagen", error)
                withContext(Dispatchers.Main) {
                    progressDialog.dismiss()
                    showError(
                        "Update fehlgeschlagen",
                        "Das Update konnte nicht heruntergeladen oder installiert werden. " +
                            "Bitte versuchen Sie es später erneut."
                    )
                }
            }
        }
    }

    private fun requestPackageInstall(apkFile: File) {
        val validatedApk = validatedOtaApk(apkFile)

        if (!canRequestPackageInstalls()) {
            pendingApk = validatedApk
            openUnknownSourcesSettings()
            return
        }

        launchPackageInstaller(validatedApk)
    }

    private fun openUnknownSourcesSettings() {
        val packageUri = Uri.parse("package:${activity.packageName}")
        val appSpecificSettings = Intent(
            Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
            packageUri
        )

        try {
            unknownSourcesLauncher.launch(appSpecificSettings)
        } catch (error: RuntimeException) {
            Log.w(TAG, "App-spezifische Installationsfreigabe nicht verfügbar", error)
            try {
                unknownSourcesLauncher.launch(Intent(Settings.ACTION_SECURITY_SETTINGS))
            } catch (fallbackError: RuntimeException) {
                pendingApk = null
                Log.e(TAG, "Installationsfreigabe konnte nicht geöffnet werden", fallbackError)
                showError(
                    "Installation nicht möglich",
                    "Die Android-Einstellung zum Installieren unbekannter Apps konnte nicht " +
                        "geöffnet werden."
                )
            }
        }
    }

    private fun continueInstallAfterPermissionSettings() {
        val apkFile = pendingApk ?: return
        pendingApk = null

        if (!canRequestPackageInstalls()) {
            showError(
                "Installation nicht freigegeben",
                "Bitte erlauben Sie dieser App das Installieren unbekannter Apps, " +
                    "um das Update einzuspielen."
            )
            return
        }

        validatedOtaApkOrNull(apkFile)?.let(::launchPackageInstaller) ?: showError(
            "Update nicht mehr verfügbar",
            "Die heruntergeladene Update-Datei ist nicht mehr vorhanden."
        )
    }

    private fun launchPackageInstaller(apkFile: File) {
        try {
            val uri = FileProvider.getUriForFile(
                activity,
                "${activity.packageName}.fileprovider",
                validatedOtaApk(apkFile)
            )
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                clipData = ClipData.newRawUri("MDE-Update", uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            activity.startActivity(intent)
        } catch (error: ActivityNotFoundException) {
            Log.e(TAG, "Kein Android-Paketinstaller gefunden", error)
            showError(
                "Installation nicht möglich",
                "Auf diesem Gerät wurde kein Android-Paketinstaller gefunden."
            )
        } catch (error: SecurityException) {
            Log.e(TAG, "Paketinstaller hat den APK-Zugriff abgelehnt", error)
            showError(
                "Installation nicht möglich",
                "Android hat den Zugriff auf die Update-Datei abgelehnt."
            )
        } catch (error: IllegalArgumentException) {
            Log.e(TAG, "Ungültige OTA-Datei oder FileProvider-Konfiguration", error)
            showError(
                "Installation nicht möglich",
                "Die Update-Datei konnte nicht sicher an Android übergeben werden."
            )
        }
    }

    private fun canRequestPackageInstalls(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return true
        return runCatching { activity.packageManager.canRequestPackageInstalls() }
            .onFailure { error ->
                Log.e(TAG, "Installationsberechtigung konnte nicht geprüft werden", error)
            }
            .getOrDefault(false)
    }

    private fun validatedOtaApkOrNull(file: File): File? =
        runCatching { validatedOtaApk(file) }.getOrNull()

    private fun validatedOtaApk(file: File): File {
        val otaDirectory = File(activity.cacheDir, OTA_CACHE_DIRECTORY).canonicalFile
        val apkFile = file.canonicalFile
        val otaPrefix = otaDirectory.path + File.separator

        require(apkFile.path.startsWith(otaPrefix)) {
            "Update-Datei liegt außerhalb des geschützten OTA-Cache-Verzeichnisses"
        }
        require(apkFile.isFile && apkFile.extension.equals("apk", ignoreCase = true)) {
            "Update-Datei ist keine vorhandene APK"
        }
        return apkFile
    }

    private fun showError(title: String, message: String) {
        if (activity.isFinishing || activity.isDestroyed) return
        AlertDialog.Builder(activity)
            .setTitle(title)
            .setMessage(message)
            .setPositiveButton("OK", null)
            .show()
    }
}
