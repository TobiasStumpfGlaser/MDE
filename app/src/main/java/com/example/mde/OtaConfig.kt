package com.example.mde

/**
 * Fixed connection settings for the internal Kerberos-protected OTA share.
 *
 * [BASE_PATH] optionally points to an OTA directory below [SHARE]. An empty
 * value means that `version.json` and the APK files are stored in the share root.
 *
 * The service account is supplied at build time through the generated
 * [BuildConfig]. The corresponding Gradle values must come from untracked
 * `local.properties`, Gradle properties, or environment variables. They must
 * never be stored in `SharedPreferences` or logged.
 */
object OtaConfig {
    const val SERVER = "vzeiterfassungw2k22"
    const val CONNECT_HOST = "vzeiterfassungw2k22.brainware-solutions.de"
    const val SHARE = "mde-update"

    /** Relative directory inside [SHARE]; empty means the share root. */
    const val BASE_PATH = ""

    // const val SERVER = "w2-fs-wks"
    // const val CONNECT_HOST = "w2-fs-wks.werkstatt.brainware-solutions.de"
    // const val SHARE = "transfer"

    /** Relative directory inside [SHARE]. */
    // const val BASE_PATH = "Temp/Tobias S/AppUpdate"

    const val VERSION_FILE_NAME = "version.json"
    const val MAX_VERSION_FILE_BYTES = 64L * 1024
    const val MAX_APK_BYTES = 150L * 1024 * 1024

    val isConfigured: Boolean
        get() = SERVER.isNotBlank() &&
            CONNECT_HOST.isNotBlank() &&
            SHARE.isNotBlank() &&
            BuildConfig.OTA_USERNAME.isNotBlank() &&
            BuildConfig.OTA_PASSWORD.isNotEmpty()

    fun requireServerConfig(): OtaServerConfig {
        check(BuildConfig.OTA_USERNAME.isNotBlank()) {
            "OTA-Kerberos-Benutzer fehlt (MDE_OTA_USERNAME)"
        }
        check(BuildConfig.OTA_PASSWORD.isNotEmpty()) {
            "OTA-Kerberos-Passwort fehlt (MDE_OTA_PASSWORD)"
        }

        return OtaServerConfig(
            server = SERVER,
            connectHost = CONNECT_HOST,
            share = SHARE,
            basePath = BASE_PATH,
            username = BuildConfig.OTA_USERNAME,
            password = BuildConfig.OTA_PASSWORD
        )
    }
}

/**
 * Validated OTA connection settings. The password is intentionally redacted
 * from [toString] so an exception or debug log cannot expose it accidentally.
 */
class OtaServerConfig(
    val server: String,
    val connectHost: String,
    val share: String,
    basePath: String,
    username: String,
    val password: String
) {
    val basePath: String = if (basePath.isEmpty()) {
        ""
    } else {
        validateOtaRelativePath(basePath, "OTA-Basispfad")
    }
    val username: String = username.trim()

    init {
        require(server.isNotBlank()) { "OTA-SMB-Server darf nicht leer sein" }
        require(connectHost.isNotBlank()) { "OTA-SMB-Verbindungsadresse darf nicht leer sein" }
        require(share.isNotBlank()) { "OTA-SMB-Freigabe darf nicht leer sein" }
        require(this.username.isNotEmpty()) { "OTA-Kerberos-Benutzer darf nicht leer sein" }
        require(password.isNotEmpty()) { "OTA-Kerberos-Passwort darf nicht leer sein" }
    }

    val versionFilePath: String
        get() = pathBelowBase(OtaConfig.VERSION_FILE_NAME)

    fun apkPath(apkFile: String): String = pathBelowBase(validateOtaApkPath(apkFile))

    private fun pathBelowBase(relativePath: String): String =
        if (basePath.isEmpty()) relativePath else "$basePath/$relativePath"

    fun kerberosConfig(): KerberosSmbConfig = KerberosSmbConfig(
        server = server,
        connectHost = connectHost,
        share = share,
        username = username,
        password = password,
        requireSigning = true
    )

    override fun toString(): String =
        "OtaServerConfig(server=$server, connectHost=$connectHost, share=$share, " +
            "basePath=$basePath, username=$username, password=<redacted>)"
}

internal fun validateOtaApkPath(path: String): String {
    val normalized = validateOtaRelativePath(path, "APK-Dateipfad")
    require(normalized.endsWith(".apk", ignoreCase = true)) {
        "APK-Dateipfad muss auf .apk enden"
    }
    return normalized
}

internal fun validateOtaRelativePath(path: String, fieldName: String): String {
    require(path.isNotEmpty() && path == path.trim()) {
        "$fieldName darf nicht leer sein und keine äußeren Leerzeichen enthalten"
    }
    require(path.length <= 512) { "$fieldName ist zu lang" }
    require(!path.startsWith('/') && !path.startsWith('\\')) {
        "$fieldName muss relativ zur OTA-Freigabe sein"
    }
    require('\\' !in path && ':' !in path && '\u0000' !in path) {
        "$fieldName enthält unzulässige Zeichen"
    }
    require(path.none { it.isISOControl() }) { "$fieldName enthält Steuerzeichen" }

    val segments = path.split('/')
    require(segments.none { it.isEmpty() || it == "." || it == ".." }) {
        "$fieldName enthält ein unzulässiges Pfadsegment"
    }
    return segments.joinToString("/")
}
