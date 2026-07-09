package com.example.mde

import android.content.Context
import android.util.Log
import jcifs.smb.NtlmPasswordAuthentication
import jcifs.smb.SmbFile
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

/**
 * Prüft über einen SMB-Share (NTLM-Authentifizierung), ob eine neuere
 * App-Version vorhanden ist, und lädt die APK bei Bedarf herunter.
 *
 * Der Update-Server wird über [AppSettings.otaServerUrl] konfiguriert.
 * Auf dem Server muss eine `version.json` mit folgendem Format liegen:
 * ```json
 * {
 *   "latestVersion": "6.1",
 *   "versionCode": 85,
 *   "releaseUrl": "smb://server/updates/app-6.1.apk",
 *   "releaseNotes": "Bugfixes und Performance-Verbesserungen",
 *   "mandatory": false,
 *   "sha256": "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"
 * }
 * ```
 *
 * **Hinweis:** Diese Klasse darf **nicht** auf dem Main-Thread aufgerufen werden.
 *
 * **Sicherheitshinweis:** Diese Implementierung verwendet jcifs 1.3.x mit NTLM-
 * Authentifizierung. Für Produktionsumgebungen empfiehlt sich die Verwendung von
 * jcifs-ng (eu.agno3.jcifs:jcifs-ng) mit NTLMv2-Unterstützung.
 */
open class UpdateManager(private val context: Context) {

    companion object {
        private const val TAG = "UpdateManager"
    }

    /**
     * Prüft, ob eine neuere Version auf dem Update-Server verfügbar ist.
     *
     * @param settings App-Einstellungen mit OTA-Konfiguration.
     * @return [UpdateInfo] wenn eine neuere Version vorhanden ist, sonst `null`.
     */
    open fun checkForUpdates(settings: AppSettings): UpdateInfo? {
        val serverUrl = settings.otaServerUrl.trimEnd('/')
        if (serverUrl.isEmpty()) {
            Log.w(TAG, "OTA-Server-URL nicht konfiguriert – Update-Check übersprungen")
            return null
        }

        return try {
            val auth = buildAuthentication(settings)
            val versionUrl = "$serverUrl/version.json"

            Log.d(TAG, "Prüfe Version auf: $versionUrl")
            val smbFile = SmbFile(versionUrl, auth)
            val versionJson = smbFile.inputStream.bufferedReader().use { it.readText() }

            val updateInfo = parseVersionJson(versionJson)
            Log.d(TAG, "Server-Version: ${updateInfo.versionCode}, App-Version: ${BuildConfig.VERSION_CODE}")

            if (updateInfo.versionCode > BuildConfig.VERSION_CODE) updateInfo else null
        } catch (e: Exception) {
            Log.e(TAG, "Fehler beim Update-Check", e)
            null
        }
    }

    /**
     * Lädt die APK vom Update-Server herunter, verifiziert optional den SHA-256-Hash
     * und speichert sie im Cache-Verzeichnis.
     *
     * @param updateInfo Update-Informationen inkl. Ziel-URL und optionalem SHA-256-Hash.
     * @param settings App-Einstellungen mit OTA-Konfiguration.
     * @return Die heruntergeladene und verifizierte APK-Datei.
     * @throws SecurityException wenn der SHA-256-Hash nicht übereinstimmt.
     */
    open fun downloadApk(updateInfo: UpdateInfo, settings: AppSettings): File {
        Log.d(TAG, "Lade APK herunter: ${updateInfo.releaseUrl}")
        val auth = buildAuthentication(settings)
        val smbFile = SmbFile(updateInfo.releaseUrl, auth)

        val apkFile = File(context.cacheDir, "update.apk")
        smbFile.inputStream.use { input ->
            apkFile.outputStream().use { output ->
                input.copyTo(output)
            }
        }
        Log.d(TAG, "APK heruntergeladen: ${apkFile.absolutePath} (${apkFile.length()} Bytes)")

        updateInfo.sha256?.let { expectedHash ->
            val actualHash = computeSha256(apkFile)
            if (!actualHash.equals(expectedHash, ignoreCase = true)) {
                apkFile.delete()
                throw SecurityException(
                    "SHA-256-Prüfsumme stimmt nicht überein: erwartet=$expectedHash, tatsächlich=$actualHash"
                )
            }
            Log.d(TAG, "SHA-256-Prüfsumme verifiziert")
        }

        return apkFile
    }

    private fun buildAuthentication(settings: AppSettings): NtlmPasswordAuthentication {
        val domain = settings.otaDomain.ifEmpty { null }
        val username = settings.otaUsername.ifEmpty { null }
        val password = settings.otaPassword.ifEmpty { null }
        return NtlmPasswordAuthentication(domain, username, password)
    }

    private fun parseVersionJson(json: String): UpdateInfo {
        val obj = JSONObject(json)
        return UpdateInfo(
            version = obj.getString("latestVersion"),
            versionCode = obj.getInt("versionCode"),
            releaseUrl = obj.getString("releaseUrl"),
            releaseNotes = obj.optString("releaseNotes", ""),
            mandatory = obj.optBoolean("mandatory", false),
            sha256 = if (obj.has("sha256")) obj.getString("sha256") else null
        )
    }

    private fun computeSha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(8192)
            var bytesRead: Int
            while (input.read(buffer).also { bytesRead = it } != -1) {
                digest.update(buffer, 0, bytesRead)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
