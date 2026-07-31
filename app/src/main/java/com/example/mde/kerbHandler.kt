package com.example.mde

import android.content.Context
import jcifs.CIFSContext
import jcifs.config.PropertyConfiguration
import jcifs.context.BaseContext
import jcifs.smb.NtlmPasswordAuthenticator
import jcifs.smb.SmbFile
import java.io.Closeable
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.util.Properties

/**
 * Connection data for an Active Directory protected SMB share.
 *
 * [server] is the logical UNC server name used for the Kerberos service
 * principal `cifs/<server>@<realm>`. [connectHost] is the DNS name used only
 * for the TCP connection and may differ when [server] is a short Windows name.
 * Realm and KDC are read from `app/src/main/assets/krb5.conf`.
 */
data class KerberosSmbConfig(
    val server: String,
    val share: String,
    val username: String,
    val password: String,
    val connectHost: String = server,
    val requireSigning: Boolean = true,
    val connectTimeoutMillis: Int = 15_000,
    val responseTimeoutMillis: Int = 30_000
) {
    init {
        require(server.isNotBlank()) { "SMB-Server darf nicht leer sein" }
        require(connectHost.isNotBlank()) { "SMB-Verbindungsadresse darf nicht leer sein" }
        require(share.isNotBlank()) { "SMB-Freigabe darf nicht leer sein" }
        require(username.isNotBlank()) { "Kerberos-Benutzer darf nicht leer sein" }
        require(password.isNotEmpty()) { "Kerberos-Passwort darf nicht leer sein" }
        require(connectTimeoutMillis > 0) { "connectTimeoutMillis muss positiv sein" }
        require(responseTimeoutMillis > 0) { "responseTimeoutMillis muss positiv sein" }
    }
}

data class SmbFileEntry(
    val name: String,
    val path: String,
    val isDirectory: Boolean,
    val size: Long,
    val lastModified: Long
)

data class DeployedKrbConfig(
    val file: File,
    val defaultRealm: String,
    val kdcAddress: String
)

/**
 * Explicit NTLMv2 SMB2/SMB3 file client.
 *
 * Android has no JAAS/JGSS runtime, so true Kerberos access is implemented by
 * [NativeKerberosSmb]. This jCIFS based class remains available only for
 * callers that deliberately request NTLM.
 */
class KerbHandler private constructor(
    private val rootUrl: String,
    private val cifsContext: CIFSContext
) : Closeable {

    companion object {
        /**
         * Explicit SMB2/3 NTLMv2 fallback for non-Kerberos servers.
         */
        fun connectWithNtlm(
            serverUrl: String,
            domain: String,
            username: String,
            password: String
        ): KerbHandler {
            require(username.isNotBlank()) { "SMB-Benutzer darf nicht leer sein" }
            val base = BaseContext(PropertyConfiguration(commonProperties()))
            val credentials = NtlmPasswordAuthenticator(domain, username, password)
            return KerbHandler(
                rootUrl = normalizeSmbUrl(serverUrl),
                cifsContext = base.withCredentials(credentials)
            )
        }

        private fun commonProperties(
            connectTimeoutMillis: Int = 15_000,
            responseTimeoutMillis: Int = 30_000,
            requireSigning: Boolean = true
        ) = Properties().apply {
            setProperty("jcifs.smb.client.minVersion", "SMB202")
            setProperty("jcifs.smb.client.maxVersion", "SMB311")
            setProperty("jcifs.smb.client.connTimeout", connectTimeoutMillis.toString())
            setProperty("jcifs.smb.client.responseTimeout", responseTimeoutMillis.toString())
            setProperty("jcifs.smb.client.soTimeout", responseTimeoutMillis.toString())
            setProperty("jcifs.smb.client.signingPreferred", requireSigning.toString())
            setProperty("jcifs.smb.client.ipcSigningEnforced", requireSigning.toString())
            setProperty("jcifs.smb.client.dfs.convertToFQDN", "true")
        }

        private fun normalizeSmbUrl(url: String): String {
            val normalized = url.trim().replace('\\', '/')
            require(normalized.startsWith("smb://", ignoreCase = true)) {
                "SMB-URL muss mit smb:// beginnen"
            }
            require(!normalized.substringAfter("smb://").contains('@')) {
                "Zugangsdaten dürfen nicht in der SMB-URL stehen"
            }
            return normalized.trimEnd('/') + "/"
        }
    }

    fun exists(path: String = ""): Boolean = resource(path).use { it.exists() }

    fun list(path: String = ""): List<SmbFileEntry> =
        resource(path, directory = true).use { directory ->
            require(directory.exists()) { "SMB-Pfad existiert nicht: $path" }
            require(directory.isDirectory) { "SMB-Pfad ist kein Verzeichnis: $path" }
            directory.listFiles()?.map { child ->
                child.use {
                    SmbFileEntry(
                        name = it.name.trimEnd('/'),
                        path = joinPath(path, it.name.trimEnd('/')),
                        isDirectory = it.isDirectory,
                        size = if (it.isDirectory) 0L else it.length(),
                        lastModified = it.lastModified()
                    )
                }
            }.orEmpty()
        }

    fun openInput(path: String): InputStream = resource(path).inputStream

    fun openOutput(path: String, append: Boolean = false): OutputStream {
        val file = resource(path)
        return if (append) file.openOutputStream(true) else file.outputStream
    }

    fun readBytes(path: String, maxBytes: Long = 32L * 1024 * 1024): ByteArray =
        resource(path).use { file ->
            val length = file.length()
            require(length <= maxBytes) {
                "SMB-Datei ist mit $length Bytes größer als das Limit von $maxBytes Bytes"
            }
            file.inputStream.use { it.readBytes() }
        }

    fun download(path: String, destination: File, maxBytes: Long = Long.MAX_VALUE): File =
        resource(path).use { source ->
            val length = source.length()
            require(length <= maxBytes) {
                "SMB-Datei ist mit $length Bytes größer als das Limit von $maxBytes Bytes"
            }
            destination.parentFile?.mkdirs()
            source.inputStream.use { input ->
                destination.outputStream().use { output -> input.copyTo(output) }
            }
            destination
        }

    fun upload(source: File, path: String, overwrite: Boolean = false) {
        require(source.isFile) { "Lokale Quelldatei existiert nicht: ${source.absolutePath}" }
        resource(path).use { target ->
            check(overwrite || !target.exists()) { "SMB-Zieldatei existiert bereits: $path" }
            source.inputStream().use { input ->
                target.outputStream.use { output -> input.copyTo(output) }
            }
        }
    }

    fun createDirectory(path: String) {
        resource(path, directory = true).use { directory ->
            if (!directory.exists()) directory.mkdirs()
        }
    }

    fun delete(path: String, recursive: Boolean = false) {
        require(path.trim('/').isNotEmpty()) { "Die Wurzel der Freigabe darf nicht gelöscht werden" }
        resource(path).use { target ->
            if (!target.exists()) return
            if (target.isDirectory && target.listFiles()?.isNotEmpty() == true && !recursive) {
                throw IllegalStateException("Verzeichnis ist nicht leer: $path")
            }
            target.delete()
        }
    }

    private fun resource(path: String, directory: Boolean = false): SmbFile {
        val safePath = sanitizeSmbPath(path)
        val suffix = if (directory && safePath.isNotEmpty()) "$safePath/" else safePath
        return SmbFile(rootUrl + suffix, cifsContext)
    }

    override fun close() {
        cifsContext.close()
    }
}

/**
 * Copies [assetName] to app-private storage and parses the settings needed by
 * the native Kerberos client.
 */
@Synchronized
fun deployKrbConfig(
    context: Context,
    assetName: String = "krb5.conf"
): DeployedKrbConfig {
    require(assetName.isNotBlank() && '/' !in assetName && '\\' !in assetName) {
        "Ungültiger Name der Kerberos-Konfigurationsdatei: $assetName"
    }

    val configBytes = context.assets.open(assetName).use { input ->
        val bytes = input.readBytes()
        require(bytes.isNotEmpty()) { "$assetName ist leer" }
        require(bytes.size <= MAX_KRB_CONFIG_BYTES) {
            "$assetName überschreitet das Limit von $MAX_KRB_CONFIG_BYTES Bytes"
        }
        bytes
    }
    val configText = configBytes.toString(Charsets.UTF_8)
    val parsedConfig = parseKrbConfig(configText)

    val configDirectory = File(context.noBackupFilesDir, "kerberos")
    check(configDirectory.exists() || configDirectory.mkdirs()) {
        "Kerberos-Konfigurationsverzeichnis konnte nicht erstellt werden"
    }
    val configFile = File(configDirectory, assetName)
    if (!configFile.exists() || !configFile.readBytes().contentEquals(configBytes)) {
        configFile.outputStream().use { it.write(configBytes) }
    }

    return DeployedKrbConfig(
        file = configFile,
        defaultRealm = parsedConfig.defaultRealm,
        kdcAddress = parsedConfig.kdcAddress
    )
}

internal data class ParsedKrbConfig(
    val defaultRealm: String,
    val kdcAddress: String
)

/**
 * Reads only the settings required for password-based Kerberos from
 * `[libdefaults]` and the matching realm block in `[realms]`.
 */
internal fun parseKrbConfig(configText: String): ParsedKrbConfig {
    var section = ""
    var defaultRealm: String? = null
    var currentRealm: String? = null
    val realmKdcs = linkedMapOf<String, MutableList<String>>()

    configText.removePrefix("\uFEFF").lineSequence().forEach { rawLine ->
        val line = rawLine
            .substringBefore('#')
            .substringBefore(';')
            .trim()
        if (line.isEmpty()) return@forEach

        SECTION_REGEX.matchEntire(line)?.let { match ->
            section = match.groupValues[1].lowercase()
            currentRealm = null
            return@forEach
        }

        when (section) {
            "libdefaults" -> {
                val assignment = splitKrbAssignment(line) ?: return@forEach
                if (assignment.first.equals("default_realm", ignoreCase = true)) {
                    defaultRealm = assignment.second.uppercase()
                }
            }

            "realms" -> {
                if (currentRealm == null) {
                    REALM_START_REGEX.matchEntire(line)?.let { match ->
                        currentRealm = match.groupValues[1].uppercase()
                        realmKdcs.getOrPut(currentRealm!!) { mutableListOf() }
                    }
                } else if (line == "}") {
                    currentRealm = null
                } else {
                    val assignment = splitKrbAssignment(line) ?: return@forEach
                    if (assignment.first.equals("kdc", ignoreCase = true)) {
                        realmKdcs.getValue(currentRealm!!)
                            .add(normalizeKdcAddress(assignment.second))
                    }
                }
            }
        }
    }

    val realm = defaultRealm
    require(!realm.isNullOrBlank()) {
        "In krb5.conf fehlt [libdefaults] default_realm"
    }
    val kdc = realmKdcs[realm]?.firstOrNull()
    require(!kdc.isNullOrBlank()) {
        "In krb5.conf fehlt für Realm $realm ein kdc-Eintrag"
    }
    return ParsedKrbConfig(realm, kdc)
}

private const val MAX_KRB_CONFIG_BYTES = 64 * 1024
private val SECTION_REGEX = Regex("""^\[\s*([A-Za-z0-9_-]+)\s*]$""")
private val REALM_START_REGEX =
    Regex("""^([A-Za-z0-9._-]+)\s*=\s*\{$""")
private val DNS_OR_IPV4_REGEX =
    Regex("""^[A-Za-z0-9](?:[A-Za-z0-9.-]*[A-Za-z0-9])?$""")

private fun splitKrbAssignment(line: String): Pair<String, String>? {
    val separator = line.indexOf('=')
    if (separator <= 0) return null
    val key = line.substring(0, separator).trim()
    val value = line.substring(separator + 1).trim()
    if (key.isEmpty() || value.isEmpty()) return null
    return key to value
}

private fun normalizeKdcAddress(value: String): String {
    val trimmed = value.trim()
    require(trimmed.isNotEmpty() && trimmed.none(Char::isWhitespace)) {
        "Ungültiger kdc-Eintrag in krb5.conf"
    }
    require(
        "://" !in trimmed &&
            '/' !in trimmed &&
            '\\' !in trimmed &&
            '@' !in trimmed
    ) {
        "Ungültiger kdc-Eintrag in krb5.conf: $trimmed"
    }

    val host: String
    val portText: String?
    if (trimmed.startsWith('[')) {
        val closingBracket = trimmed.indexOf(']')
        require(closingBracket > 1) { "Ungültiger IPv6-kdc-Eintrag: $trimmed" }
        host = trimmed.substring(1, closingBracket)
        val remainder = trimmed.substring(closingBracket + 1)
        require(remainder.isEmpty() || remainder.startsWith(':')) {
            "Ungültiger kdc-Eintrag in krb5.conf: $trimmed"
        }
        portText = remainder.removePrefix(":").ifEmpty { null }
        require(host.all { it.isDigit() || it.lowercaseChar() in 'a'..'f' || it == ':' }) {
            "Ungültige IPv6-Adresse im kdc-Eintrag: $trimmed"
        }
    } else {
        require(trimmed.count { it == ':' } <= 1) {
            "IPv6-Adressen müssen im kdc-Eintrag in eckigen Klammern stehen"
        }
        host = trimmed.substringBefore(':')
        portText = trimmed.substringAfter(':', "").ifEmpty { null }
        require(DNS_OR_IPV4_REGEX.matches(host) && ".." !in host) {
            "Ungültiger KDC-Hostname in krb5.conf: $host"
        }
    }

    val port = portText?.toIntOrNull() ?: 88
    require(port in 1..65535) { "Ungültiger KDC-Port in krb5.conf: ${portText ?: ""}" }
    val formattedHost = if (':' in host) "[$host]" else host
    return "$formattedHost:$port"
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

internal fun sanitizeSmbPath(path: String): String {
    val normalized = path.trim().replace('\\', '/').trim('/')
    if (normalized.isEmpty()) return ""
    val parts = normalized.split('/')
    require(parts.none { it.isEmpty() || it == "." || it == ".." }) {
        "Ungültiger SMB-Pfad: $path"
    }
    require(parts.none { ':' in it || '\u0000' in it }) { "Ungültiger SMB-Pfad: $path" }
    return parts.joinToString("/")
}

private fun joinPath(parent: String, child: String): String =
    listOf(parent.trim('/'), child.trim('/')).filter { it.isNotEmpty() }.joinToString("/")
