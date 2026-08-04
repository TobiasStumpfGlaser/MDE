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
    }

    private var pendingApk: File? = restoredPendingApkPath
        ?.let(::File)
        ?.let(::validatedOtaApkOrNull)
    private val diagnosticSecrets = OtaDiagnosticLog.credentialSecrets(
        BuildConfig.OTA_USERNAME,
        BuildConfig.OTA_PASSWORD
    )

    private val unknownSourcesLauncher = activity.registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        continueInstallAfterPermissionSettings()
    }

    init {
        if (restoredPendingApkPath != null) {
            OtaDiagnosticLog.event(
                activity,
                "OTA-Installation/Zustand",
                if (pendingApk != null) {
                    "Ausstehende APK aus gespeichertem Zustand wiederhergestellt"
                } else {
                    "Gespeicherte ausstehende APK war nicht mehr gültig"
                }
            )
        }
    }

    /** Pfad einer noch ausstehenden APK für die Zustandswiederherstellung. */
    fun pendingApkPathForState(): String? = pendingApk?.absolutePath

    /** Zeigt die nicht abbrechbare Bestätigung vor dem OTA-Download. */
    fun showUpdateDialog(updateInfo: UpdateInfo) {
        if (activity.isFinishing || activity.isDestroyed) {
            OtaDiagnosticLog.warning(
                activity,
                "OTA-Dialog",
                "Updatedialog nicht angezeigt: Activity wird beendet oder ist zerstört"
            )
            return
        }

        OtaDiagnosticLog.event(
            activity,
            "OTA-Dialog",
            "Updatedialog angezeigt; versionCode=${updateInfo.versionCode}; " +
                "versionName=${updateInfo.versionName}",
            secrets = diagnosticSecrets
        )

        AlertDialog.Builder(activity)
            .setTitle("Neue Version verfügbar")
            .setMessage("Update durchführen?")
            .setPositiveButton("Ja") { _, _ ->
                OtaDiagnosticLog.event(
                    activity,
                    "OTA-Dialog",
                    "Update wurde bestätigt"
                )
                downloadAndInstall(updateInfo)
            }
            .setNegativeButton("Nein") { _, _ ->
                OtaDiagnosticLog.event(
                    activity,
                    "OTA-Dialog",
                    "Update wurde abgelehnt"
                )
            }
            .setCancelable(false)
            .show()
    }

    private fun downloadAndInstall(updateInfo: UpdateInfo) {
        OtaDiagnosticLog.event(
            activity,
            "OTA-Download/UI",
            "Downloadanzeige geöffnet; versionCode=${updateInfo.versionCode}"
        )
        val progressDialog = AlertDialog.Builder(activity)
            .setTitle("Update wird heruntergeladen")
            .setMessage("Bitte warten...")
            .setCancelable(false)
            .show()

        activity.lifecycleScope.launch(Dispatchers.IO) {
            try {
                val apkFile = UpdateManager(activity).downloadApk(updateInfo)
                OtaDiagnosticLog.event(
                    activity,
                    "OTA-Download/UI",
                    "Download und Prüfung erfolgreich; Übergabe an Installationsablauf"
                )
                withContext(Dispatchers.Main) {
                    progressDialog.dismiss()
                    requestPackageInstall(apkFile)
                }
            } catch (error: SecurityException) {
                Log.e(TAG, "OTA-APK konnte nicht verifiziert werden", error)
                OtaDiagnosticLog.event(
                    context = activity,
                    stage = "OTA-Download/UI",
                    message = "Heruntergeladene APK konnte nicht verifiziert werden; " +
                        "${error.javaClass.simpleName}: ${error.message ?: "ohne Fehlermeldung"}",
                    secrets = diagnosticSecrets
                )
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
                OtaDiagnosticLog.event(
                    context = activity,
                    stage = "OTA-Download/UI",
                    message = "Update konnte nicht heruntergeladen oder vorbereitet werden; " +
                        "${error.javaClass.simpleName}: ${error.message ?: "ohne Fehlermeldung"}",
                    secrets = diagnosticSecrets
                )
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
        OtaDiagnosticLog.event(
            activity,
            "OTA-Installation/Vorbereitung",
            "Heruntergeladene APK wird vor Installer-Übergabe erneut auf sicheren Pfad geprüft"
        )
        val validatedApk = validatedOtaApk(apkFile)

        if (!canRequestPackageInstalls()) {
            pendingApk = validatedApk
            OtaDiagnosticLog.event(
                activity,
                "OTA-Installation/Berechtigung",
                "Installationsfreigabe fehlt; Android-Einstellungen werden geöffnet"
            )
            openUnknownSourcesSettings()
            return
        }

        OtaDiagnosticLog.event(
            activity,
            "OTA-Installation/Berechtigung",
            "Installationsfreigabe vorhanden"
        )
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
            OtaDiagnosticLog.event(
                activity,
                "OTA-Installation/Berechtigung",
                "App-spezifische Android-Einstellung geöffnet"
            )
        } catch (error: RuntimeException) {
            Log.w(TAG, "App-spezifische Installationsfreigabe nicht verfügbar", error)
            OtaDiagnosticLog.warning(
                context = activity,
                stage = "OTA-Installation/Berechtigung",
                message = "App-spezifische Einstellung nicht verfügbar; allgemeine " +
                    "Sicherheitseinstellungen werden versucht",
                error = error,
                secrets = diagnosticSecrets
            )
            try {
                unknownSourcesLauncher.launch(Intent(Settings.ACTION_SECURITY_SETTINGS))
                OtaDiagnosticLog.event(
                    activity,
                    "OTA-Installation/Berechtigung",
                    "Allgemeine Android-Sicherheitseinstellungen geöffnet"
                )
            } catch (fallbackError: RuntimeException) {
                pendingApk = null
                Log.e(TAG, "Installationsfreigabe konnte nicht geöffnet werden", fallbackError)
                OtaDiagnosticLog.error(
                    context = activity,
                    stage = "OTA-Installation/Berechtigung",
                    message = "Android-Einstellungen für die Installationsfreigabe konnten " +
                        "nicht geöffnet werden",
                    error = fallbackError,
                    secrets = diagnosticSecrets
                )
                showError(
                    "Installation nicht möglich",
                    "Die Android-Einstellung zum Installieren unbekannter Apps konnte nicht " +
                        "geöffnet werden."
                )
            }
        }
    }

    private fun continueInstallAfterPermissionSettings() {
        val apkFile = pendingApk ?: run {
            OtaDiagnosticLog.warning(
                activity,
                "OTA-Installation/Berechtigung",
                "Rückkehr aus Android-Einstellungen ohne ausstehende APK"
            )
            return
        }
        pendingApk = null

        if (!canRequestPackageInstalls()) {
            OtaDiagnosticLog.warning(
                activity,
                "OTA-Installation/Berechtigung",
                "Installationsfreigabe wurde nicht erteilt"
            )
            showError(
                "Installation nicht freigegeben",
                "Bitte erlauben Sie dieser App das Installieren unbekannter Apps, " +
                    "um das Update einzuspielen."
            )
            return
        }

        OtaDiagnosticLog.event(
            activity,
            "OTA-Installation/Berechtigung",
            "Installationsfreigabe wurde erteilt"
        )
        validatedOtaApkOrNull(apkFile)?.let(::launchPackageInstaller) ?: run {
            OtaDiagnosticLog.warning(
                activity,
                "OTA-Installation/Vorbereitung",
                "Ausstehende APK ist nicht mehr vorhanden oder nicht mehr gültig"
            )
            showError(
                "Update nicht mehr verfügbar",
                "Die heruntergeladene Update-Datei ist nicht mehr vorhanden."
            )
        }
    }

    private fun launchPackageInstaller(apkFile: File) {
        try {
            OtaDiagnosticLog.event(
                activity,
                "OTA-Installation/Installer",
                "FileProvider-URI und Android-Installer-Intent werden vorbereitet"
            )
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
            OtaDiagnosticLog.event(
                activity,
                "OTA-Installation/Installer",
                "Android-Paketinstaller gestartet"
            )
        } catch (error: ActivityNotFoundException) {
            Log.e(TAG, "Kein Android-Paketinstaller gefunden", error)
            OtaDiagnosticLog.error(
                context = activity,
                stage = "OTA-Installation/Installer",
                message = "Kein Android-Paketinstaller gefunden",
                error = error,
                secrets = diagnosticSecrets
            )
            showError(
                "Installation nicht möglich",
                "Auf diesem Gerät wurde kein Android-Paketinstaller gefunden."
            )
        } catch (error: SecurityException) {
            Log.e(TAG, "Paketinstaller hat den APK-Zugriff abgelehnt", error)
            OtaDiagnosticLog.error(
                context = activity,
                stage = "OTA-Installation/Installer",
                message = "Paketinstaller hat den APK-Zugriff abgelehnt",
                error = error,
                secrets = diagnosticSecrets
            )
            showError(
                "Installation nicht möglich",
                "Android hat den Zugriff auf die Update-Datei abgelehnt."
            )
        } catch (error: IllegalArgumentException) {
            Log.e(TAG, "Ungültige OTA-Datei oder FileProvider-Konfiguration", error)
            OtaDiagnosticLog.error(
                context = activity,
                stage = "OTA-Installation/Installer",
                message = "Ungültige OTA-Datei oder FileProvider-Konfiguration",
                error = error,
                secrets = diagnosticSecrets
            )
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
                OtaDiagnosticLog.error(
                    context = activity,
                    stage = "OTA-Installation/Berechtigung",
                    message = "Installationsberechtigung konnte nicht geprüft werden",
                    error = error,
                    secrets = diagnosticSecrets
                )
            }
            .getOrDefault(false)
    }

    private fun validatedOtaApkOrNull(file: File): File? =
        runCatching { validatedOtaApk(file) }.getOrNull()

    private fun validatedOtaApk(file: File): File {
        val otaDirectory = File(activity.cacheDir, OtaConfig.CACHE_DIRECTORY).canonicalFile
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
