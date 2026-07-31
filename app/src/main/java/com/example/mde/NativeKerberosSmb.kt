package com.example.mde

import android.content.Context
import java.io.File
import java.io.IOException

/**
 * Android-compatible Kerberos/SMB2 client backed by a native Rust library.
 *
 * The native side performs AS, TGS and AP-REQ itself. It does not use JAAS,
 * JGSS or NTLM. Calls are blocking and must run on a background thread.
 */
object NativeKerberosSmb {
    private val libraryLoaded: Boolean by lazy {
        System.loadLibrary("mde_kerberos_smb")
        true
    }

    fun readFile(
        context: Context,
        config: KerberosSmbConfig,
        path: String,
        maxBytes: Long = 32L * 1024 * 1024,
        krbConfigAsset: String = "krb5.conf"
    ): ByteArray {
        require(maxBytes in 1..Int.MAX_VALUE.toLong()) {
            "maxBytes muss zwischen 1 und ${Int.MAX_VALUE} liegen"
        }

        val server = config.server.trim()
        validateHostName(server, "SMB-Servername")
        val connectHost = config.connectHost.trim()
        validateHostName(connectHost, "SMB-Verbindungsadresse")
        val share = validateShare(config.share)

        val krbConfig = deployKrbConfig(context, krbConfigAsset)
        val username = normalizeKerberosUsername(config.username)
        val remotePath = sanitizeSmbPath(path)
        require(remotePath.isNotEmpty()) { "SMB-Dateipfad darf nicht leer sein" }

        // Trigger the lazy load here so parser/validation unit tests never need
        // an Android native library.
        check(libraryLoaded)

        return nativeReadFile(
            server = server,
            connectHost = connectHost,
            share = share,
            path = remotePath,
            username = username,
            password = config.password,
            realm = krbConfig.defaultRealm,
            kdcAddress = krbConfig.kdcAddress,
            requireSigning = config.requireSigning,
            connectTimeoutMillis = config.connectTimeoutMillis,
            responseTimeoutMillis = config.responseTimeoutMillis,
            maxBytes = maxBytes
        )
    }

    /**
     * Streams a remote SMB file directly into [destination] without keeping
     * the complete file in either the Kotlin or native heap.
     *
     * [destination] must be an absolute, canonical `.part` file below one of
     * the app-private `cacheDir`, `filesDir` or `noBackupFilesDir` roots. A
     * stale regular `.part` file is removed before the native download starts.
     * On every failure, the incomplete destination is removed best-effort.
     *
     * Calls are blocking and must run on a background thread.
     *
     * @return the number of bytes written to [destination].
     */
    @Throws(IOException::class)
    fun downloadFile(
        context: Context,
        config: KerberosSmbConfig,
        path: String,
        destination: File,
        maxBytes: Long,
        expectedSizeBytes: Long? = null,
        krbConfigAsset: String = "krb5.conf"
    ): Long {
        require(maxBytes > 0) { "maxBytes muss positiv sein" }
        require(expectedSizeBytes == null || expectedSizeBytes in 0..maxBytes) {
            "expectedSizeBytes muss zwischen 0 und maxBytes liegen"
        }

        val server = config.server.trim()
        validateHostName(server, "SMB-Servername")
        val connectHost = config.connectHost.trim()
        validateHostName(connectHost, "SMB-Verbindungsadresse")
        val share = validateShare(config.share)
        val krbConfig = deployKrbConfig(context, krbConfigAsset)
        val username = normalizeKerberosUsername(config.username)
        val remotePath = sanitizeSmbPath(path)
        require(remotePath.isNotEmpty()) { "SMB-Dateipfad darf nicht leer sein" }

        val partFile = preparePrivatePartFile(context, destination)

        // Load before deleting a stale partial download. This preserves the
        // most useful failure if the native ABI is unavailable.
        check(libraryLoaded)
        removeStalePartFile(partFile)

        val bytesWritten = try {
            nativeDownloadFile(
                server = server,
                connectHost = connectHost,
                share = share,
                path = remotePath,
                username = username,
                password = config.password,
                realm = krbConfig.defaultRealm,
                kdcAddress = krbConfig.kdcAddress,
                requireSigning = config.requireSigning,
                connectTimeoutMillis = config.connectTimeoutMillis,
                responseTimeoutMillis = config.responseTimeoutMillis,
                destinationPath = partFile.absolutePath,
                maxBytes = maxBytes,
                expectedSizeBytes = expectedSizeBytes ?: NO_EXPECTED_SIZE
            )
        } catch (error: Throwable) {
            removeFailedPartFile(partFile, error)
            throw error
        }

        val localSize = if (partFile.isFile) partFile.length() else -1L
        if (bytesWritten < 0 || localSize != bytesWritten ||
            (expectedSizeBytes != null && bytesWritten != expectedSizeBytes)
        ) {
            val error = IOException(
                "Unvollständiger SMB-Download: native=$bytesWritten Bytes, " +
                    "lokal=$localSize Bytes" +
                    (expectedSizeBytes?.let { ", erwartet=$it Bytes" } ?: "")
            )
            removeFailedPartFile(partFile, error)
            throw error
        }

        return bytesWritten
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

    private fun validateShare(value: String): String {
        val share = value.trim()
        require(
            share.isNotEmpty() &&
                share.none(Char::isWhitespace) &&
                '/' !in share &&
                '\\' !in share &&
                ':' !in share
        ) {
            "Ungültiger SMB-Freigabename: $share"
        }
        return share
    }

    private fun preparePrivatePartFile(context: Context, destination: File): File {
        require(destination.isAbsolute) { "Download-Zielpfad muss absolut sein" }
        requirePartFileName(destination)

        val privateRoots = listOf(
            context.cacheDir,
            context.filesDir,
            context.noBackupFilesDir
        ).map(File::getCanonicalFile)

        val absoluteDestination = destination.absoluteFile
        var canonicalDestination = destination.canonicalFile
        require(absoluteDestination.path == canonicalDestination.path) {
            "Download-Zielpfad muss kanonisch sein und darf keine symbolischen Links enthalten"
        }
        requirePartFileName(canonicalDestination)
        require(privateRoots.any { root -> canonicalDestination.isStrictChildOf(root) }) {
            "Download-Zieldatei muss in einem app-privaten Verzeichnis liegen"
        }

        val parent = canonicalDestination.parentFile
            ?: throw IllegalArgumentException("Download-Zieldatei hat kein Elternverzeichnis")
        if (!parent.exists() && !parent.mkdirs()) {
            throw IOException("Download-Zielverzeichnis konnte nicht erstellt werden")
        }
        if (!parent.isDirectory) {
            throw IOException("Download-Zielpfad ist kein Verzeichnis")
        }

        // Resolve again after mkdirs, so a pre-existing symlink in the parent
        // path cannot move the destination outside app-private storage.
        canonicalDestination = destination.canonicalFile
        require(absoluteDestination.path == canonicalDestination.path) {
            "Download-Zielpfad muss kanonisch sein und darf keine symbolischen Links enthalten"
        }
        requirePartFileName(canonicalDestination)
        require(privateRoots.any { root -> canonicalDestination.isStrictChildOf(root) }) {
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

    private fun validateHostName(value: String, fieldName: String) {
        require(
            value.isNotEmpty() &&
                value.none(Char::isWhitespace) &&
                '/' !in value &&
                '\\' !in value &&
                '@' !in value &&
                ':' !in value
        ) {
            "Ungültige $fieldName: $value"
        }
    }

    private const val PART_SUFFIX = ".part"
    private const val NO_EXPECTED_SIZE = -1L
}
