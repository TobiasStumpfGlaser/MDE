package com.example.mde

/**
 * Enthält alle Informationen über eine verfügbare App-Aktualisierung,
 * die aus der `version.json` auf dem Update-Server gelesen werden.
 *
 * Beispiel-JSON:
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
 */
data class UpdateInfo(
    /** Versionsnummer als lesbarer Text, z. B. "6.1". */
    val version: String,
    /** Interner Versions-Code (wird mit [BuildConfig.VERSION_CODE] verglichen). */
    val versionCode: Int,
    /** SMB-URL zur APK-Datei auf dem Update-Server. */
    val releaseUrl: String,
    /** Beschreibung der Änderungen in dieser Version. */
    val releaseNotes: String,
    /** Wenn `true`, kann der Benutzer das Update nicht überspringen. */
    val mandatory: Boolean = false,
    /**
     * Erwarteter SHA-256-Hash der APK-Datei (Hex-String, Kleinbuchstaben).
     * Wenn angegeben, wird die heruntergeladene APK vor der Installation verifiziert.
     */
    val sha256: String? = null
)
