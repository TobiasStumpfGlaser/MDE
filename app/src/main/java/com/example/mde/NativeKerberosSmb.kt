package com.example.mde

import android.content.Context
import java.io.File
import java.io.IOException

/**
 * Android-kompatibler Kerberos-/SMB2-Client auf Basis der nativen Rust-Bibliothek.
 *
 * Die native Seite führt AS, TGS und AP-REQ selbst aus und verwendet weder
 * JAAS/JGSS noch NTLM. Alle Aufrufe blockieren und gehören auf einen Hintergrund-Thread.
 */
internal object NativeKerberosSmb {
    private val nativeLibraryLoaded: Unit by lazy {
        System.loadLibrary("mde_kerberos_smb")
    }

    fun readFile(
        context: Context,
        config: OtaServerConfig,
        path: String,
        maxBytes: Long
    ): ByteArray = OtaDiagnosticLog.operation(
        context = context,
        name = "Kerberos/SMB-Datei lesen",
        secrets = OtaDiagnosticLog.credentialSecrets(config.username, config.password)
    ) {
        OtaDiagnosticLog.event(
            context,
            "Kerberos/SMB-Lesen/Eingabe",
            "Server=${config.server}; Verbindungsziel=${config.connectHost}:" +
                "${OtaConfig.SMB_PORT}; " +
                "Freigabe=${config.share}; Pfad=$path; Limit=$maxBytes Bytes; " +
                "Realm=${config.realm}; KDC=${config.kdcAddress}; " +
                "Signierung erforderlich=$REQUIRE_SIGNING; " +
                "Verbindungs-Timeout=$CONNECT_TIMEOUT_MILLIS ms; " +
                "Antwort-Timeout=$RESPONSE_TIMEOUT_MILLIS ms; " +
                "Zugangsdaten=<gesetzt>"
        )
        require(maxBytes in 1..Int.MAX_VALUE.toLong()) {
            "maxBytes muss zwischen 1 und ${Int.MAX_VALUE} liegen"
        }

        val remotePath = validateRequest(context, config, path, "Kerberos/SMB-Lesen")
        ensureNativeLibraryLoaded(context)

        OtaDiagnosticLog.event(
            context,
            "Kerberos/SMB-Lesen/Native",
            "TCP-Verbindung, SMB-Aushandlung, Kerberos-Anmeldung, Freigabe und Dateilesen gestartet"
        )
        val bytes = runNativeCall(context, config) {
            nativeReadFile(
                server = config.server,
                connectHost = config.connectHost,
                share = config.share,
                path = remotePath,
                username = config.username,
                password = config.password,
                realm = config.realm,
                kdcAddress = config.kdcAddress,
                requireSigning = REQUIRE_SIGNING,
                connectTimeoutMillis = CONNECT_TIMEOUT_MILLIS,
                responseTimeoutMillis = RESPONSE_TIMEOUT_MILLIS,
                maxBytes = maxBytes
            )
        }
        OtaDiagnosticLog.event(
            context,
            "Kerberos/SMB-Lesen/Native",
            "Alle nativen Stufen erfolgreich (TCP, SMB-Aushandlung, Kerberos AS/TGS/" +
                "AP-REQ, Freigabe, Dateiinfo und Dateilesen); ${bytes.size} Bytes gelesen"
        )
        bytes
    }

    /**
     * Streamt eine SMB-Datei direkt nach [destination], ohne sie vollständig
     * im Kotlin- oder nativen Heap zu halten.
     *
     * Das Ziel muss eine absolute `.part`-Datei unter `cacheDir`, `filesDir`
     * oder `noBackupFilesDir` sein. Symbolische Links unterhalb dieser Wurzeln
     * werden abgelehnt. Alte beziehungsweise fehlgeschlagene Teildateien werden
     * nach Möglichkeit entfernt.
     *
     * @return Anzahl der nach [destination] geschriebenen Bytes.
     */
    @Throws(IOException::class)
    fun downloadFile(
        context: Context,
        config: OtaServerConfig,
        path: String,
        destination: File,
        maxBytes: Long
    ): Long = OtaDiagnosticLog.operation(
        context = context,
        name = "Kerberos/SMB-Datei herunterladen",
        secrets = OtaDiagnosticLog.credentialSecrets(config.username, config.password)
    ) {
        OtaDiagnosticLog.event(
            context,
            "Kerberos/SMB-Download/Eingabe",
            "Server=${config.server}; Verbindungsziel=${config.connectHost}:" +
                "${OtaConfig.SMB_PORT}; " +
                "Freigabe=${config.share}; Pfad=$path; Ziel=${destination.absolutePath}; " +
                "Limit=$maxBytes Bytes; " +
                "Realm=${config.realm}; KDC=${config.kdcAddress}; " +
                "Signierung erforderlich=$REQUIRE_SIGNING; " +
                "Verbindungs-Timeout=$CONNECT_TIMEOUT_MILLIS ms; " +
                "Antwort-Timeout=$RESPONSE_TIMEOUT_MILLIS ms; " +
                "Zugangsdaten=<gesetzt>"
        )
        require(maxBytes > 0) { "maxBytes muss positiv sein" }

        val remotePath = validateRequest(context, config, path, "Kerberos/SMB-Download")

        val partFile = preparePrivatePartFile(context, destination)
        OtaDiagnosticLog.event(
            context,
            "Kerberos/SMB-Download/Zieldatei",
            "App-privates .part-Ziel geprüft: ${partFile.absolutePath}"
        )

        // Vor dem Löschen einer alten Teildatei laden, damit ein ABI-Fehler
        // als aussagekräftigste Ursache erhalten bleibt.
        ensureNativeLibraryLoaded(context)
        val stalePartFileExisted = partFile.exists()
        removeStalePartFile(partFile)
        OtaDiagnosticLog.event(
            context,
            "Kerberos/SMB-Download/Zieldatei",
            if (stalePartFileExisted) {
                "Alte native Teildatei entfernt"
            } else {
                "Keine alte native Teildatei vorhanden"
            }
        )

        OtaDiagnosticLog.event(
            context,
            "Kerberos/SMB-Download/Native",
            "TCP-Verbindung, SMB-Aushandlung, Kerberos-Anmeldung, Freigabe und Download gestartet"
        )
        val bytesWritten = try {
            runNativeCall(context, config) {
                nativeDownloadFile(
                    server = config.server,
                    connectHost = config.connectHost,
                    share = config.share,
                    path = remotePath,
                    username = config.username,
                    password = config.password,
                    realm = config.realm,
                    kdcAddress = config.kdcAddress,
                    requireSigning = REQUIRE_SIGNING,
                    connectTimeoutMillis = CONNECT_TIMEOUT_MILLIS,
                    responseTimeoutMillis = RESPONSE_TIMEOUT_MILLIS,
                    destinationPath = partFile.absolutePath,
                    maxBytes = maxBytes,
                    expectedSizeBytes = NO_EXPECTED_SIZE
                )
            }
        } catch (error: Throwable) {
            removeFailedPartFile(partFile, error)
            throw error
        }
        OtaDiagnosticLog.event(
            context,
            "Kerberos/SMB-Download/Native",
            "Native Kerberos/SMB-Operation beendet; $bytesWritten Bytes gemeldet"
        )

        val localSize = if (partFile.isFile) partFile.length() else -1L
        if (bytesWritten < 0 || localSize != bytesWritten) {
            val error = IOException(
                "Unvollständiger SMB-Download: native=$bytesWritten Bytes, " +
                    "lokal=$localSize Bytes"
            )
            removeFailedPartFile(partFile, error)
            throw error
        }

        OtaDiagnosticLog.event(
            context,
            "Kerberos/SMB-Download/Ergebnis",
            "Alle nativen Stufen erfolgreich (TCP, SMB-Aushandlung, Kerberos AS/TGS/" +
                "AP-REQ, Freigabe, Dateiinfo und Download); native=$bytesWritten Bytes; " +
                "lokal=$localSize Bytes"
        )
        bytesWritten
    }

    private fun validateRequest(
        context: Context,
        config: OtaServerConfig,
        path: String,
        stage: String
    ): String {
        val remotePath = validateOtaRelativePath(path, "SMB-Dateipfad")
        OtaDiagnosticLog.event(
            context,
            "$stage/Validierung",
            "Ziel validiert: //${config.server}/${config.share}/$remotePath; " +
                "Realm=${config.realm}; KDC=${config.kdcAddress}; Benutzer=<redacted>"
        )
        return remotePath
    }

    private fun ensureNativeLibraryLoaded(context: Context) {
        OtaDiagnosticLog.event(
            context,
            "Kerberos/Native-Bibliothek",
            "Laden von libmde_kerberos_smb wird geprüft"
        )
        nativeLibraryLoaded
        OtaDiagnosticLog.event(
            context,
            "Kerberos/Native-Bibliothek",
            "Native Bibliothek ist geladen"
        )
    }

    private inline fun <T> runNativeCall(
        context: Context,
        config: OtaServerConfig,
        block: () -> T
    ): T {
        val networkState = OtaNetworkDiagnostics.capture(context)
        val startedNanos = System.nanoTime()
        return try {
            block()
        } catch (error: IOException) {
            OtaNetworkDiagnostics.logFailure(
                context = context,
                beforeNativeCall = networkState,
                smbHost = config.connectHost,
                kdcAddress = config.kdcAddress,
                originalError = error,
                nativeDurationMillis =
                    (System.nanoTime() - startedNanos).coerceAtLeast(0L) / 1_000_000L
            )
            throw error
        }
    }

    private external fun nativeReadFile(
        server: String,
        connectHost: String,
        share: String,
        path: String,
        username: String,
        password: String,
        realm: String,
        kdcAddress: String,
        requireSigning: Boolean,
        connectTimeoutMillis: Int,
        responseTimeoutMillis: Int,
        maxBytes: Long
    ): ByteArray

    private external fun nativeDownloadFile(
        server: String,
        connectHost: String,
        share: String,
        path: String,
        username: String,
        password: String,
        realm: String,
        kdcAddress: String,
        requireSigning: Boolean,
        connectTimeoutMillis: Int,
        responseTimeoutMillis: Int,
        destinationPath: String,
        maxBytes: Long,
        expectedSizeBytes: Long
    ): Long

    internal fun preparePrivatePartFile(context: Context, destination: File): File {
        require(destination.isAbsolute) { "Download-Zielpfad muss absolut sein" }
        requirePartFileName(destination)
        require(destination.path.split(File.separatorChar).none { it == "." || it == ".." }) {
            "Download-Zielpfad darf keine .- oder ..-Segmente enthalten"
        }

        val privateRoots = listOf(
            context.cacheDir,
            context.filesDir,
            context.noBackupFilesDir
        ).map { root -> PrivateRoot(root.absoluteFile, root.canonicalFile) }

        val absoluteDestination = destination.absoluteFile
        val privateRoot = privateRoots.firstNotNullOfOrNull { root ->
            absoluteDestination.relativeChildPath(root.absolute)?.let { relativePath ->
                MatchedPrivateRoot(root, relativePath)
            } ?: absoluteDestination.relativeChildPath(root.canonical)?.let { relativePath ->
                MatchedPrivateRoot(root, relativePath)
            }
        } ?: throw IllegalArgumentException(
            "Download-Zieldatei muss in einem app-privaten Verzeichnis liegen"
        )

        var canonicalDestination = resolveCanonicalDestination(absoluteDestination, privateRoot)

        val parent = canonicalDestination.parentFile
            ?: throw IllegalArgumentException("Download-Zieldatei hat kein Elternverzeichnis")
        if (!parent.exists() && !parent.mkdirs()) {
            throw IOException("Download-Zielverzeichnis konnte nicht erstellt werden")
        }
        if (!parent.isDirectory) {
            throw IOException("Download-Zielpfad ist kein Verzeichnis")
        }

        // Nach mkdirs erneut kanonisieren, damit ein vorhandener Symlink im
        // Elternpfad das Ziel nicht aus dem privaten App-Speicher verschiebt.
        canonicalDestination = resolveCanonicalDestination(absoluteDestination, privateRoot)
        return canonicalDestination
    }

    private fun resolveCanonicalDestination(
        destination: File,
        matchedRoot: MatchedPrivateRoot
    ): File {
        val canonicalDestination = destination.canonicalFile
        val expectedDestination = File(
            matchedRoot.root.canonical,
            matchedRoot.relativePath
        ).absoluteFile
        require(canonicalDestination.path == expectedDestination.path) {
            "Download-Zielpfad darf unterhalb des privaten Verzeichnisses " +
                "keine symbolischen Links enthalten"
        }
        requirePartFileName(canonicalDestination)
        require(canonicalDestination.isStrictChildOf(matchedRoot.root.canonical)) {
            "Download-Zieldatei muss in einem app-privaten Verzeichnis liegen"
        }
        return canonicalDestination
    }

    private fun requirePartFileName(file: File) {
        require(file.name.length > PART_SUFFIX.length &&
            file.name.endsWith(PART_SUFFIX, ignoreCase = true)
        ) {
            "Download-Zieldatei muss auf $PART_SUFFIX enden"
        }
    }

    private fun File.isStrictChildOf(root: File): Boolean {
        val rootPath = root.path.trimEnd(File.separatorChar) + File.separator
        return path.startsWith(rootPath)
    }

    private fun File.relativeChildPath(root: File): String? {
        val rootPath = root.path.trimEnd(File.separatorChar) + File.separator
        return path.takeIf { it.startsWith(rootPath) }?.substring(rootPath.length)
    }

    private fun removeStalePartFile(file: File) {
        if (!file.exists()) return
        if (!file.isFile) {
            throw IOException("Download-Zieldatei ist keine reguläre Datei")
        }
        if (!file.delete()) {
            throw IOException("Alte unvollständige Download-Datei konnte nicht entfernt werden")
        }
    }

    private fun removeFailedPartFile(file: File, error: Throwable) {
        if (file.exists() && !file.delete()) {
            error.addSuppressed(
                IOException("Unvollständige Download-Datei konnte nicht entfernt werden")
            )
        }
    }

    private const val PART_SUFFIX = ".part"
    // -1 hält die bestehende JNI-ABI stabil; das Manifest liefert keine Sollgröße.
    private const val NO_EXPECTED_SIZE = -1L
    private const val REQUIRE_SIGNING = true
    private const val CONNECT_TIMEOUT_MILLIS = 15_000
    private const val RESPONSE_TIMEOUT_MILLIS = 30_000

    private data class PrivateRoot(val absolute: File, val canonical: File)

    private data class MatchedPrivateRoot(
        val root: PrivateRoot,
        val relativePath: String
    )
}
