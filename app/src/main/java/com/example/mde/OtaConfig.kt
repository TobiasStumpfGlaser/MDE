package com.example.mde

import java.net.Inet6Address
import java.net.InetAddress
import java.util.Locale

/**
 * Technische Konstanten und Aufbau der validierten OTA-Konfiguration.
 *
 * Betriebsabhängige Werte kommen aus [AppSettings]. Das Dienstkonto wird zur
 * Build-Zeit über [BuildConfig] eingebettet und bewusst nicht in unverschlüsselten
 * `SharedPreferences` gespeichert.
 */
object OtaConfig {
    const val VERSION_FILE_NAME = "version.json"
    const val MAX_VERSION_FILE_BYTES = 64L * 1024
    const val MAX_APK_BYTES = 150L * 1024 * 1024
    internal const val CACHE_DIRECTORY = "ota"
    internal const val KERBEROS_DEFAULT_PORT = 88
    internal const val SMB_PORT = 445

    fun isConfigured(settings: AppSettings): Boolean =
        runCatching { requireServerConfig(settings) }.isSuccess

    fun requireServerConfig(settings: AppSettings): OtaServerConfig {
        check(BuildConfig.OTA_USERNAME.isNotBlank()) {
            "OTA-Kerberos-Benutzer fehlt (MDE_OTA_USERNAME)"
        }
        check(BuildConfig.OTA_PASSWORD.isNotEmpty()) {
            "OTA-Kerberos-Passwort fehlt (MDE_OTA_PASSWORD)"
        }

        return OtaServerConfig(
            server = settings.otaServer,
            connectHost = settings.otaConnectHost,
            share = settings.otaShare,
            basePath = settings.otaBasePath,
            realm = settings.otaRealm,
            kdcAddress = settings.otaKdcAddress,
            username = BuildConfig.OTA_USERNAME,
            password = BuildConfig.OTA_PASSWORD
        )
    }
}

/**
 * Validierter, unveränderlicher Snapshot aller Werte für einen OTA-Lauf.
 * Benutzername und Passwort werden in [toString] immer redigiert.
 */
class OtaServerConfig(
    server: String,
    connectHost: String,
    share: String,
    basePath: String,
    realm: String,
    kdcAddress: String,
    username: String,
    val password: String
) {
    val server: String = validateOtaHost(server, "OTA-SMB-Server")
    val connectHost: String = validateOtaHost(connectHost, "OTA-Connect-Host")
    val share: String = validateOtaShare(share)
    val basePath: String = validateOtaBasePath(basePath)
    val realm: String = normalizeOtaRealm(realm)
    val kdcAddress: String = normalizeOtaKdcAddress(kdcAddress)
    val username: String = normalizeKerberosUsername(username)

    init {
        require(password.isNotEmpty()) { "OTA-Kerberos-Passwort darf nicht leer sein" }
    }

    val versionFilePath: String
        get() = pathBelowBase(OtaConfig.VERSION_FILE_NAME)

    fun apkPath(apkFile: String): String = pathBelowBase(validateOtaApkPath(apkFile))

    private fun pathBelowBase(relativePath: String): String =
        if (basePath.isEmpty()) relativePath else "$basePath/$relativePath"

    override fun toString(): String =
        "OtaServerConfig(server=$server, connectHost=$connectHost, share=$share, " +
            "basePath=$basePath, realm=$realm, kdcAddress=$kdcAddress, " +
            "username=<redacted>, password=<redacted>)"
}

internal fun validateOtaHost(value: String, fieldName: String): String {
    val normalized = value.trim()
    require(normalized.isNotEmpty()) { "$fieldName darf nicht leer sein" }
    require(normalized.length <= 255) { "$fieldName ist zu lang" }
    require(
        normalized.none(Char::isWhitespace) &&
            normalized.none(Char::isISOControl) &&
            '/' !in normalized &&
            '\\' !in normalized &&
            '@' !in normalized &&
            ':' !in normalized
    ) {
        "$fieldName darf keine Leerzeichen oder die Zeichen /, \\, @, : enthalten"
    }
    return normalized
}

internal fun validateOtaShare(value: String): String {
    val normalized = value.trim()
    require(
        normalized.isNotEmpty() &&
            normalized.length <= 255 &&
            normalized.none(Char::isWhitespace) &&
            normalized.none(Char::isISOControl) &&
            '/' !in normalized &&
            '\\' !in normalized &&
            ':' !in normalized
    ) {
        "OTA-SMB-Freigabe darf nicht leer sein und keine Leerzeichen oder die Zeichen /, \\, : enthalten"
    }
    return normalized
}

internal fun validateOtaBasePath(path: String): String =
    if (path.isEmpty()) "" else validateOtaRelativePath(path, "OTA-Basispfad")

internal fun normalizeOtaRealm(value: String): String {
    val normalized = value.trim().uppercase(Locale.ROOT)
    require(normalized.length in 1..255 && KERBEROS_REALM_REGEX.matches(normalized)) {
        "OTA-Kerberos-Realm ist ungültig"
    }
    return normalized
}

internal data class HostPortEndpoint(val host: String, val port: Int) {
    fun display(): String = if (':' in host) "[$host]:$port" else "$host:$port"
}

/** Parst und validiert `Host[:Port]`, ohne DNS-Anfragen für Hostnamen auszuführen. */
internal fun parseHostPortEndpoint(
    value: String,
    defaultPort: Int,
    fieldName: String
): HostPortEndpoint {
    val normalized = value.trim()
    require(defaultPort in 1..65535) { "$fieldName: ungültiger Standardport" }
    require(
        normalized.isNotEmpty() &&
            normalized.length <= 255 &&
            normalized.none(Char::isWhitespace)
    ) {
        "$fieldName darf nicht leer sein und keine Leerzeichen enthalten"
    }
    require(
        "://" !in normalized &&
            '/' !in normalized &&
            '\\' !in normalized &&
            '@' !in normalized
    ) {
        "$fieldName muss als Host mit optionalem Port angegeben werden"
    }

    val host: String
    val portText: String?
    if (normalized.startsWith('[')) {
        val closingBracket = normalized.indexOf(']')
        require(closingBracket > 1) { "$fieldName enthält eine ungültige IPv6-Adresse" }
        host = normalized.substring(1, closingBracket)
        val remainder = normalized.substring(closingBracket + 1)
        portText = when {
            remainder.isEmpty() -> null
            remainder.startsWith(':') && remainder.length > 1 -> remainder.substring(1)
            else -> throw IllegalArgumentException("$fieldName enthält einen ungültigen Port")
        }
        require(
            ':' in host &&
                host.all { it.isDigit() || it.lowercaseChar() in 'a'..'f' || it == ':' } &&
                runCatching { InetAddress.getByName(host) is Inet6Address }.getOrDefault(false)
        ) {
            "$fieldName enthält eine ungültige IPv6-Adresse"
        }
    } else {
        require(normalized.count { it == ':' } <= 1) {
            "$fieldName: IPv6-Adressen müssen in eckigen Klammern stehen"
        }
        val separator = normalized.indexOf(':')
        host = if (separator < 0) normalized else normalized.substring(0, separator)
        portText = if (separator < 0) {
            null
        } else {
            normalized.substring(separator + 1).ifEmpty {
                throw IllegalArgumentException("$fieldName enthält einen ungültigen Port")
            }
        }
        require(DNS_OR_IPV4_REGEX.matches(host) && ".." !in host) {
            "$fieldName enthält einen ungültigen Hostnamen: $host"
        }
    }

    val port = portText?.toIntOrNull()
        ?: if (portText == null) defaultPort else {
            throw IllegalArgumentException("$fieldName enthält einen ungültigen Port: $portText")
        }
    require(port in 1..65535) { "$fieldName enthält einen ungültigen Port: $port" }
    return HostPortEndpoint(host, port)
}

/** Normalisiert `Host[:Port]`; fehlt der Port, wird der Kerberos-Port 88 ergänzt. */
internal fun normalizeOtaKdcAddress(value: String): String {
    val endpoint = parseHostPortEndpoint(
        value = value,
        defaultPort = OtaConfig.KERBEROS_DEFAULT_PORT,
        fieldName = "OTA-KDC"
    )
    // Der aktuelle native Kerberos-Client bindet seinen UDP-Socket ausschließlich über IPv4.
    require(':' !in endpoint.host) { "OTA-KDC unterstützt derzeit keine IPv6-Adresse" }
    return endpoint.display()
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
    return path
}

internal fun normalizeKerberosUsername(username: String): String {
    val normalized = username.trim()
        .substringAfterLast('\\')
        .substringBefore('@')
        .trim()
    require(normalized.isNotEmpty()) { "Kerberos-Benutzer darf nicht leer sein" }
    require(
        normalized.none(Char::isWhitespace) &&
            '\\' !in normalized &&
            '/' !in normalized &&
            '\u0000' !in normalized
    ) {
        "Ungültiger Kerberos-Benutzername"
    }
    return normalized
}

private val KERBEROS_REALM_REGEX = Regex("^[A-Z0-9][A-Z0-9._-]*$")
private val DNS_OR_IPV4_REGEX =
    Regex("^[A-Za-z0-9](?:[A-Za-z0-9.-]*[A-Za-z0-9])?$")
