package com.example.mde

/**
 * Strikt validierter Inhalt der OTA-Datei `version.json`.
 *
 * Example:
 * ```json
 * {
 *   "versionCode": 85,
 *   "versionName": "6.1",
 *   "apkFile": "mde-85.apk"
 * }
 * ```
 *
 * Nur [versionCode] entscheidet, ob ein Update neuer ist. [versionName] ist
 * Anzeigetext; [apkFile] liegt relativ zum konfigurierten OTA-Basispfad.
 */
data class UpdateInfo(
    val versionCode: Long,
    val versionName: String,
    val apkFile: String
) {
    init {
        require(versionCode in 1..Int.MAX_VALUE.toLong()) {
            "versionCode muss zwischen 1 und ${Int.MAX_VALUE} liegen"
        }
        require(
            versionName.isNotBlank() &&
                versionName == versionName.trim() &&
                versionName.length <= 64 &&
                versionName.none { it.isISOControl() }
        ) {
            "versionName ist ungültig"
        }
        validateOtaApkPath(apkFile)
    }
}
