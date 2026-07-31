package com.example.mde

/**
 * Strictly validated contents of the OTA server's `version.json`.
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
 * Only [versionCode] decides whether an update is newer. [versionName] is
 * display text. [apkFile] is always relative to [OtaConfig.BASE_PATH].
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
        require(apkFile == validateOtaApkPath(apkFile)) {
            "apkFile ist ungültig"
        }
    }
}
