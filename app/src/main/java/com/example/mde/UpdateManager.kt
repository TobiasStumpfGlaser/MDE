package com.example.mde

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import org.json.JSONObject
import org.json.JSONTokener
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import kotlin.math.min

/**
 * Checks and downloads application updates from the fixed Kerberos SMB share.
 * All methods are blocking and must be called on a background dispatcher.
 *
 * UI-facing API:
 * - [checkForUpdates] returns an update only when its `versionCode` is newer.
 * - [downloadApk] downloads, verifies and atomically publishes a local APK.
 *
 * A `null` result means "up to date". Network and validation errors are not
 * hidden as `null`; the UI decides whether an update-check failure is fail-open.
 */
class UpdateManager private constructor(
    private val context: Context,
    dependencies: Dependencies
) {
    private val serverConfig = dependencies.serverConfig
    private val remoteFiles = dependencies.remoteFiles
    private val apkVerifier = dependencies.apkVerifier
    private val installedVersionCode = dependencies.installedVersionCode

    constructor(context: Context) : this(context, createDefaultDependencies(context))

    internal constructor(
        context: Context,
        serverConfig: OtaServerConfig,
        remoteFiles: OtaRemoteFileSource,
        apkVerifier: OtaApkVerifier,
        installedVersionCode: Long = BuildConfig.VERSION_CODE.toLong()
    ) : this(
        context,
        Dependencies(serverConfig, remoteFiles, apkVerifier, installedVersionCode)
    )

    /** Returns a newer server version, or `null` if this app is current. */
    fun checkForUpdates(): UpdateInfo? {
        val versionPath = serverConfig.versionFilePath
        Log.d(TAG, "Prüfe OTA-Version: $versionPath")

        val bytes = remoteFiles.readFile(versionPath, OtaConfig.MAX_VERSION_FILE_BYTES)
        require(bytes.isNotEmpty()) { "version.json ist leer" }
        require(bytes.size.toLong() <= OtaConfig.MAX_VERSION_FILE_BYTES) {
            "version.json überschreitet ${OtaConfig.MAX_VERSION_FILE_BYTES} Bytes"
        }

        val updateInfo = parseUpdateInfo(decodeUtf8Strict(bytes))
        Log.d(
            TAG,
            "OTA-Server-versionCode=${updateInfo.versionCode}, " +
                "installiert=$installedVersionCode"
        )
        return updateInfo.takeIf { it.versionCode > installedVersionCode }
    }

    /**
     * Downloads [updateInfo] into `cacheDir/ota` and returns only a fully
     * verified `.apk`. The temporary `.part` file is never handed to the UI.
     */
    @Throws(IOException::class, SecurityException::class)
    fun downloadApk(updateInfo: UpdateInfo): File {
        require(updateInfo.versionCode > installedVersionCode) {
            "OTA-versionCode ${updateInfo.versionCode} ist nicht neuer als $installedVersionCode"
        }

        val otaDirectory = File(context.cacheDir, OTA_CACHE_DIRECTORY)
        ensureDirectory(otaDirectory)

        val finalFile = File(otaDirectory, "update-${updateInfo.versionCode}.apk")
        val partFile = File(otaDirectory, "update-${updateInfo.versionCode}.apk.part")

        if (finalFile.isFile) {
            try {
                verifyLocalApk(finalFile, updateInfo)
                return finalFile
            } catch (error: Exception) {
                deleteChecked(finalFile, "ungültige vorhandene OTA-Datei", error)
            }
        }
        if (finalFile.exists()) {
            throw IOException("OTA-Ziel ist keine reguläre Datei: ${finalFile.absolutePath}")
        }
        if (partFile.exists() && !partFile.delete()) {
            throw IOException("Alte OTA-Teildatei konnte nicht entfernt werden")
        }

        try {
            val downloadLimit = availableDownloadBytes(otaDirectory)
            val bytesWritten = remoteFiles.downloadFile(
                path = serverConfig.apkPath(updateInfo.apkFile),
                destination = partFile,
                maxBytes = downloadLimit
            )
            val localSize = if (partFile.isFile) partFile.length() else -1L
            if (bytesWritten !in 1..downloadLimit || localSize != bytesWritten) {
                throw IOException(
                    "Unvollständiges Update: übertragen=$bytesWritten, lokal=$localSize"
                )
            }

            // Native downloadFile flushes its writer. Sync again at the Java
            // boundary before verification/rename so a returned final file is durable.
            FileOutputStream(partFile, true).use { it.fd.sync() }
            verifyLocalApk(partFile, updateInfo)

            if (finalFile.exists() && !finalFile.delete()) {
                throw IOException("Vorhandenes OTA-Ziel konnte nicht ersetzt werden")
            }
            if (!partFile.renameTo(finalFile)) {
                throw IOException("Verifiziertes Update konnte nicht atomar bereitgestellt werden")
            }
            return finalFile
        } catch (error: Throwable) {
            if (partFile.exists() && !partFile.delete()) {
                error.addSuppressed(IOException("OTA-Teildatei konnte nicht entfernt werden"))
            }
            throw error
        }
    }

    private fun verifyLocalApk(file: File, updateInfo: UpdateInfo) {
        val fileSize = if (file.isFile) file.length() else -1L
        if (fileSize !in 1..OtaConfig.MAX_APK_BYTES) {
            throw SecurityException(
                "APK-Datei ist leer, fehlt oder überschreitet ${OtaConfig.MAX_APK_BYTES} Bytes"
            )
        }
        apkVerifier.verify(file, updateInfo)
    }

    private fun ensureDirectory(directory: File) {
        if (!directory.isDirectory && !directory.mkdirs()) {
            throw IOException("OTA-Cacheverzeichnis konnte nicht erstellt werden")
        }
        if (!directory.isDirectory) {
            throw IOException("OTA-Cachepfad ist kein Verzeichnis")
        }
    }

    private fun availableDownloadBytes(directory: File): Long {
        val usable = directory.usableSpace
        // Some virtual/test filesystems report zero for "unknown".
        if (usable <= 0) return OtaConfig.MAX_APK_BYTES

        val available = usable - MIN_FREE_SPACE_RESERVE
        if (available < 1) {
            throw IOException(
                "Nicht genügend Speicher für das Update: frei=$usable, " +
                    "Reserve=$MIN_FREE_SPACE_RESERVE"
            )
        }
        return min(OtaConfig.MAX_APK_BYTES, available)
    }

    private fun deleteChecked(file: File, description: String, cause: Throwable) {
        if (file.exists() && !file.delete()) {
            throw IOException("$description konnte nicht entfernt werden", cause)
        }
    }

    private data class Dependencies(
        val serverConfig: OtaServerConfig,
        val remoteFiles: OtaRemoteFileSource,
        val apkVerifier: OtaApkVerifier,
        val installedVersionCode: Long
    )

    companion object {
        private const val TAG = "UpdateManager"
        private const val OTA_CACHE_DIRECTORY = "ota"
        private const val MIN_FREE_SPACE_RESERVE = 16L * 1024 * 1024

        private fun createDefaultDependencies(context: Context): Dependencies {
            val serverConfig = OtaConfig.requireServerConfig()
            return Dependencies(
                serverConfig = serverConfig,
                remoteFiles = NativeOtaRemoteFileSource(context, serverConfig),
                apkVerifier = AndroidOtaApkVerifier(context),
                installedVersionCode = BuildConfig.VERSION_CODE.toLong()
            )
        }
    }
}

/** Test seam around the blocking native Kerberos SMB calls. */
internal interface OtaRemoteFileSource {
    fun readFile(path: String, maxBytes: Long): ByteArray

    fun downloadFile(
        path: String,
        destination: File,
        maxBytes: Long
    ): Long
}

private class NativeOtaRemoteFileSource(
    private val context: Context,
    serverConfig: OtaServerConfig
) : OtaRemoteFileSource {
    private val kerberosConfig = serverConfig.kerberosConfig()

    override fun readFile(path: String, maxBytes: Long): ByteArray =
        NativeKerberosSmb.readFile(
            context = context,
            config = kerberosConfig,
            path = path,
            maxBytes = maxBytes
        )

    override fun downloadFile(
        path: String,
        destination: File,
        maxBytes: Long
    ): Long = NativeKerberosSmb.downloadFile(
        context = context,
        config = kerberosConfig,
        path = path,
        destination = destination,
        maxBytes = maxBytes
    )
}

/** Test seam for Android package metadata and signing-certificate validation. */
internal fun interface OtaApkVerifier {
    fun verify(file: File, updateInfo: UpdateInfo)
}

private class AndroidOtaApkVerifier(private val context: Context) : OtaApkVerifier {
    override fun verify(file: File, updateInfo: UpdateInfo) {
        val packageManager = context.packageManager
        val archive = packageInfoForArchive(packageManager, file)
            ?: throw SecurityException("Heruntergeladene Datei ist keine lesbare APK")
        if (archive.packageName != context.packageName) {
            throw SecurityException(
                "APK-Paketname ${archive.packageName} stimmt nicht mit ${context.packageName} überein"
            )
        }
        if (packageVersionCode(archive) != updateInfo.versionCode) {
            throw SecurityException(
                "APK-versionCode ${packageVersionCode(archive)} stimmt nicht mit " +
                    "${updateInfo.versionCode} überein"
            )
        }

        val installed = packageInfoForInstalled(packageManager, context.packageName)
        verifySigningRelationship(installed, archive)
    }

    @Suppress("DEPRECATION")
    private fun packageInfoForArchive(
        packageManager: PackageManager,
        file: File
    ): PackageInfo? {
        val flags = signingFlags()
        return if (Build.VERSION.SDK_INT >= 33) {
            packageManager.getPackageArchiveInfo(
                file.absolutePath,
                PackageManager.PackageInfoFlags.of(flags.toLong())
            )
        } else {
            packageManager.getPackageArchiveInfo(file.absolutePath, flags)
        }
    }

    @Suppress("DEPRECATION")
    private fun packageInfoForInstalled(
        packageManager: PackageManager,
        packageName: String
    ): PackageInfo {
        val flags = signingFlags()
        return if (Build.VERSION.SDK_INT >= 33) {
            packageManager.getPackageInfo(
                packageName,
                PackageManager.PackageInfoFlags.of(flags.toLong())
            )
        } else {
            packageManager.getPackageInfo(packageName, flags)
        }
    }

    @Suppress("DEPRECATION")
    private fun signingFlags(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            PackageManager.GET_SIGNING_CERTIFICATES
        } else {
            PackageManager.GET_SIGNATURES
        }

    @Suppress("DEPRECATION")
    private fun verifySigningRelationship(installed: PackageInfo, archive: PackageInfo) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val installedInfo = installed.signingInfo
                ?: throw SecurityException("Signatur der installierten App ist nicht lesbar")
            val archiveInfo = archive.signingInfo
                ?: throw SecurityException("Signatur der Update-APK ist nicht lesbar")

            val installedActive = installedInfo.apkContentsSigners.toCertificateIds()
            val archiveActive = archiveInfo.apkContentsSigners.toCertificateIds()
            if (installedInfo.hasMultipleSigners() || archiveInfo.hasMultipleSigners()) {
                if (installedActive.isEmpty() || installedActive != archiveActive) {
                    throw SecurityException("APK wurde nicht mit denselben Zertifikaten signiert")
                }
                return
            }

            val archiveHistory = archiveInfo.signingCertificateHistory.toCertificateIds()
            if (installedActive.size != 1 || installedActive.first() !in archiveHistory) {
                throw SecurityException("APK-Signatur gehört nicht zur installierten App")
            }
        } else {
            val installedSignatures = installed.signatures.toCertificateIds()
            val archiveSignatures = archive.signatures.toCertificateIds()
            if (installedSignatures.isEmpty() || installedSignatures != archiveSignatures) {
                throw SecurityException("APK wurde nicht mit denselben Zertifikaten signiert")
            }
        }
    }

    private fun Array<out android.content.pm.Signature>?.toCertificateIds(): Set<String> =
        this.orEmpty().mapTo(linkedSetOf()) { signature ->
            MessageDigest.getInstance("SHA-256")
                .digest(signature.toByteArray())
                .joinToString("") { byte -> "%02x".format(byte) }
        }

    @Suppress("DEPRECATION")
    private fun packageVersionCode(packageInfo: PackageInfo): Long =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            packageInfo.longVersionCode
        } else {
            packageInfo.versionCode.toLong()
        }
}

internal fun parseUpdateInfo(json: String): UpdateInfo {
    require(json.toByteArray(StandardCharsets.UTF_8).size <= OtaConfig.MAX_VERSION_FILE_BYTES) {
        "version.json überschreitet ${OtaConfig.MAX_VERSION_FILE_BYTES} Bytes"
    }

    val tokener = JSONTokener(json)
    val root = tokener.nextValue()
    require(root is JSONObject && tokener.nextClean() == '\u0000') {
        "version.json muss genau ein JSON-Objekt enthalten"
    }

    val actualKeys = root.keys().asSequence().toSet()
    require(actualKeys == VERSION_JSON_KEYS) {
        val missing = VERSION_JSON_KEYS - actualKeys
        val unknown = actualKeys - VERSION_JSON_KEYS
        "Ungültiges version.json-Schema; fehlend=$missing, unbekannt=$unknown"
    }

    val versionCode = root.strictLong("versionCode")
    val versionName = root.strictString("versionName")
    val apkFile = root.strictString("apkFile")

    return UpdateInfo(versionCode, versionName, apkFile)
}

private val VERSION_JSON_KEYS = setOf(
    "versionCode",
    "versionName",
    "apkFile"
)

private fun JSONObject.strictString(name: String): String {
    val value = get(name)
    require(value is String) { "$name muss eine JSON-Zeichenfolge sein" }
    return value
}

private fun JSONObject.strictLong(name: String): Long = when (val value = get(name)) {
    is Int -> value.toLong()
    is Long -> value
    else -> throw IllegalArgumentException("$name muss eine ganze JSON-Zahl sein")
}

private fun decodeUtf8Strict(bytes: ByteArray): String =
    StandardCharsets.UTF_8.newDecoder()
        .onMalformedInput(CodingErrorAction.REPORT)
        .onUnmappableCharacter(CodingErrorAction.REPORT)
        .decode(ByteBuffer.wrap(bytes))
        .toString()
