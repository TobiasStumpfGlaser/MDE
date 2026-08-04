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
 * Prüft und lädt App-Updates aus der in [AppSettings] konfigurierten
 * Kerberos-/SMB-Freigabe.
 * Alle Methoden blockieren und müssen auf einem Hintergrund-Dispatcher laufen.
 *
 * Öffentliche API für die UI:
 * - [checkForUpdates] liefert nur Updates mit einem neueren `versionCode`.
 * - [downloadApk] lädt eine APK, prüft sie und stellt sie lokal bereit.
 *
 * `null` bedeutet „aktuell“. Netzwerk- und Validierungsfehler werden nicht als
 * `null` versteckt; die UI entscheidet selbst, ob sie bei einem Fehler fortfährt.
 */
class UpdateManager private constructor(
    private val context: Context,
    dependencies: Dependencies
) {
    private val serverConfig = dependencies.serverConfig
    private val remoteFiles = dependencies.remoteFiles
    private val apkVerifier = dependencies.apkVerifier
    private val installedVersionCode = dependencies.installedVersionCode
    private val diagnosticSecrets = OtaDiagnosticLog.credentialSecrets(
        serverConfig.username,
        serverConfig.password
    )

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

    /** Liefert eine neuere Serverversion oder `null`, wenn die App aktuell ist. */
    fun checkForUpdates(): UpdateInfo? = OtaDiagnosticLog.operation(
        context = context,
        name = "Versionsprüfung",
        secrets = diagnosticSecrets
    ) {
        val versionPath = serverConfig.versionFilePath
        OtaDiagnosticLog.event(
            context,
            "Versionsprüfung/Konfiguration",
            "Server=${serverConfig.server}; Verbindungsziel=${serverConfig.connectHost}:" +
                "${OtaConfig.SMB_PORT}; " +
                "Freigabe=${serverConfig.share}; Versionsdatei=$versionPath; " +
                "installierter versionCode=$installedVersionCode; " +
                "Dateilimit=${OtaConfig.MAX_VERSION_FILE_BYTES} Bytes; " +
                "Zugangsdaten=<konfiguriert>"
        )
        Log.d(TAG, "Prüfe OTA-Version: $versionPath")

        OtaDiagnosticLog.event(
            context,
            "Versionsprüfung/Versionsdatei",
            "Abruf über Kerberos/SMB gestartet"
        )
        val bytes = remoteFiles.readFile(versionPath, OtaConfig.MAX_VERSION_FILE_BYTES)
        OtaDiagnosticLog.event(
            context,
            "Versionsprüfung/Versionsdatei",
            "Abruf beendet; ${bytes.size} Bytes empfangen (Inhalt wird nicht protokolliert)"
        )
        require(bytes.isNotEmpty()) { "version.json ist leer" }
        require(bytes.size.toLong() <= OtaConfig.MAX_VERSION_FILE_BYTES) {
            "version.json überschreitet ${OtaConfig.MAX_VERSION_FILE_BYTES} Bytes"
        }

        val json = decodeUtf8Strict(bytes)
        OtaDiagnosticLog.event(
            context,
            "Versionsprüfung/UTF-8",
            "Strikte UTF-8-Prüfung erfolgreich"
        )
        val updateInfo = parseUpdateInfo(json)
        OtaDiagnosticLog.event(
            context,
            "Versionsprüfung/Manifest",
            "Schema geprüft; Server-versionCode=${updateInfo.versionCode}; " +
                "versionName=${updateInfo.versionName}; APK=${updateInfo.apkFile}"
        )
        Log.d(
            TAG,
            "OTA-Server-versionCode=${updateInfo.versionCode}, " +
                "installiert=$installedVersionCode"
        )

        val update = updateInfo.takeIf { it.versionCode > installedVersionCode }
        OtaDiagnosticLog.event(
            context,
            "Versionsprüfung/Ergebnis",
            if (update == null) {
                "Kein Update erforderlich; Server-versionCode=${updateInfo.versionCode}, " +
                    "installiert=$installedVersionCode"
            } else {
                "Update verfügbar; Server-versionCode=${update.versionCode}, " +
                    "installiert=$installedVersionCode"
            }
        )
        if (update == null) {
            OtaDiagnosticLog.summary(
                context = context,
                level = OtaDiagnosticLog.SummaryLevel.SUCCESS,
                title = "OTA OK: APP IST AKTUELL",
                lines = listOf(
                    "INSTALLIERT: ${BuildConfig.VERSION_NAME} (Code $installedVersionCode)",
                    "SERVER: ${updateInfo.versionName} (Code ${updateInfo.versionCode})",
                    "STAND: version.json erfolgreich geprüft",
                    "MASSNAHME: keine"
                ),
                secrets = diagnosticSecrets
            )
        } else {
            OtaDiagnosticLog.summary(
                context = context,
                level = OtaDiagnosticLog.SummaryLevel.ATTENTION,
                title = "OTA: UPDATE VERFÜGBAR",
                lines = listOf(
                    "INSTALLIERT: ${BuildConfig.VERSION_NAME} (Code $installedVersionCode)",
                    "SERVER: ${update.versionName} (Code ${update.versionCode})",
                    "APK: ${update.apkFile}",
                    "NÄCHSTER SCHRITT: wartet auf Bestätigung"
                ),
                secrets = diagnosticSecrets
            )
        }
        update
    }

    /**
     * Lädt [updateInfo] nach `cacheDir/ota` und liefert ausschließlich eine
     * vollständig geprüfte `.apk`. Die temporäre `.part`-Datei erreicht nie die UI.
     */
    @Throws(IOException::class, SecurityException::class)
    fun downloadApk(updateInfo: UpdateInfo): File = OtaDiagnosticLog.operation(
        context = context,
        name = "OTA-Download",
        secrets = diagnosticSecrets
    ) {
        OtaDiagnosticLog.event(
            context,
            "OTA-Download/Anforderung",
            "versionCode=${updateInfo.versionCode}; versionName=${updateInfo.versionName}; " +
                "APK=${updateInfo.apkFile}; installiert=$installedVersionCode"
        )
        require(updateInfo.versionCode > installedVersionCode) {
            "OTA-versionCode ${updateInfo.versionCode} ist nicht neuer als $installedVersionCode"
        }

        val otaDirectory = File(context.cacheDir, OtaConfig.CACHE_DIRECTORY)
        ensureDirectory(otaDirectory)
        OtaDiagnosticLog.event(
            context,
            "OTA-Download/Cache",
            "Cacheverzeichnis bereit: ${otaDirectory.absolutePath}"
        )

        val finalFile = File(otaDirectory, "update-${updateInfo.versionCode}.apk")
        val partFile = File(otaDirectory, "update-${updateInfo.versionCode}.apk.part")

        if (finalFile.isFile) {
            OtaDiagnosticLog.event(
                context,
                "OTA-Download/Cache",
                "Vorhandene APK wird vor Wiederverwendung vollständig geprüft; " +
                    "Größe=${finalFile.length()} Bytes"
            )
            try {
                verifyLocalApk(finalFile, updateInfo)
                OtaDiagnosticLog.event(
                    context,
                    "OTA-Download/Cache",
                    "Vorhandene APK ist gültig und wird wiederverwendet"
                )
                return@operation finalFile
            } catch (error: Exception) {
                OtaDiagnosticLog.warning(
                    context = context,
                    stage = "OTA-Download/Cache",
                    message = "Vorhandene APK ist ungültig und wird entfernt",
                    error = error,
                    secrets = diagnosticSecrets
                )
                deleteChecked(finalFile, "ungültige vorhandene OTA-Datei", error)
            }
        }
        if (finalFile.exists()) {
            throw IOException("OTA-Ziel ist keine reguläre Datei: ${finalFile.absolutePath}")
        }
        val stalePartFileExisted = partFile.exists()
        if (stalePartFileExisted && !partFile.delete()) {
            throw IOException("Alte OTA-Teildatei konnte nicht entfernt werden")
        }
        OtaDiagnosticLog.event(
            context,
            "OTA-Download/Cache",
            if (stalePartFileExisted) {
                "Alte Teildatei entfernt"
            } else {
                "Keine alte Teildatei vorhanden"
            }
        )

        try {
            val downloadLimit = availableDownloadBytes(otaDirectory)
            val remotePath = serverConfig.apkPath(updateInfo.apkFile)
            OtaDiagnosticLog.event(
                context,
                "OTA-Download/Übertragung",
                "Kerberos/SMB-Download gestartet; Remote-Pfad=$remotePath; " +
                    "lokales Ziel=${partFile.absolutePath}; Limit=$downloadLimit Bytes"
            )
            val bytesWritten = remoteFiles.downloadFile(
                path = remotePath,
                destination = partFile,
                maxBytes = downloadLimit
            )
            val localSize = if (partFile.isFile) partFile.length() else -1L
            OtaDiagnosticLog.event(
                context,
                "OTA-Download/Übertragung",
                "Übertragung beendet; gemeldet=$bytesWritten Bytes; lokal=$localSize Bytes"
            )
            if (bytesWritten !in 1..downloadLimit || localSize != bytesWritten) {
                throw IOException(
                    "Unvollständiges Update: übertragen=$bytesWritten, lokal=$localSize"
                )
            }

            // Nach dem nativen Flush an der Java-Grenze erneut synchronisieren,
            // bevor die Datei geprüft und umbenannt wird.
            FileOutputStream(partFile, true).use { it.fd.sync() }
            OtaDiagnosticLog.event(
                context,
                "OTA-Download/Dateisystem",
                "Teildatei synchronisiert"
            )
            verifyLocalApk(partFile, updateInfo)

            if (finalFile.exists() && !finalFile.delete()) {
                throw IOException("Vorhandenes OTA-Ziel konnte nicht ersetzt werden")
            }
            if (!partFile.renameTo(finalFile)) {
                throw IOException("Verifiziertes Update konnte nicht bereitgestellt werden")
            }
            OtaDiagnosticLog.event(
                context,
                "OTA-Download/Veröffentlichung",
                "Verifizierte APK bereitgestellt: ${finalFile.absolutePath}"
            )
            return@operation finalFile
        } catch (error: Throwable) {
            val partialFileExisted = partFile.exists()
            if (partFile.exists() && !partFile.delete()) {
                error.addSuppressed(IOException("OTA-Teildatei konnte nicht entfernt werden"))
            } else if (partialFileExisted) {
                OtaDiagnosticLog.event(
                    context,
                    "OTA-Download/Aufräumen",
                    "Unvollständige Teildatei entfernt"
                )
            }
            throw error
        }
    }

    private fun verifyLocalApk(file: File, updateInfo: UpdateInfo) {
        val fileSize = if (file.isFile) file.length() else -1L
        OtaDiagnosticLog.event(
            context,
            "APK-Prüfung",
            "Prüfung gestartet; Datei=${file.name}; Größe=$fileSize Bytes; " +
                "erwarteter versionCode=${updateInfo.versionCode}"
        )
        if (fileSize !in 1..OtaConfig.MAX_APK_BYTES) {
            throw SecurityException(
                "APK-Datei ist leer, fehlt oder überschreitet ${OtaConfig.MAX_APK_BYTES} Bytes"
            )
        }
        apkVerifier.verify(file, updateInfo)
        OtaDiagnosticLog.event(
            context,
            "APK-Prüfung",
            "Dateigröße, Paket, versionCode und Signaturbeziehung sind gültig"
        )
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
        if (usable <= 0) {
            OtaDiagnosticLog.warning(
                context,
                "OTA-Download/Speicher",
                "Freier Speicher konnte nicht bestimmt werden; festes APK-Limit wird verwendet"
            )
            return OtaConfig.MAX_APK_BYTES
        }

        val available = usable - MIN_FREE_SPACE_RESERVE
        if (available < 1) {
            throw IOException(
                "Nicht genügend Speicher für das Update: frei=$usable, " +
                    "Reserve=$MIN_FREE_SPACE_RESERVE"
            )
        }
        return min(OtaConfig.MAX_APK_BYTES, available).also { limit ->
            OtaDiagnosticLog.event(
                context,
                "OTA-Download/Speicher",
                "Frei=$usable Bytes; Reserve=$MIN_FREE_SPACE_RESERVE Bytes; " +
                    "Download-Limit=$limit Bytes"
            )
        }
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
        private const val MIN_FREE_SPACE_RESERVE = 16L * 1024 * 1024

        private fun createDefaultDependencies(context: Context): Dependencies {
            val serverConfig = OtaConfig.requireServerConfig(
                AppSettings(context.applicationContext)
            )
            return Dependencies(
                serverConfig = serverConfig,
                remoteFiles = NativeOtaRemoteFileSource(context, serverConfig),
                apkVerifier = AndroidOtaApkVerifier(context),
                installedVersionCode = BuildConfig.VERSION_CODE.toLong()
            )
        }
    }
}

/** Testnaht um die blockierenden nativen Kerberos-/SMB-Aufrufe. */
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
    private val serverConfig: OtaServerConfig
) : OtaRemoteFileSource {
    override fun readFile(path: String, maxBytes: Long): ByteArray =
        NativeKerberosSmb.readFile(
            context = context,
            config = serverConfig,
            path = path,
            maxBytes = maxBytes
        )

    override fun downloadFile(
        path: String,
        destination: File,
        maxBytes: Long
    ): Long = NativeKerberosSmb.downloadFile(
        context = context,
        config = serverConfig,
        path = path,
        destination = destination,
        maxBytes = maxBytes
    )
}

/** Testnaht für Android-Paketmetadaten und die Prüfung der Signaturzertifikate. */
internal fun interface OtaApkVerifier {
    fun verify(file: File, updateInfo: UpdateInfo)
}

private class AndroidOtaApkVerifier(private val context: Context) : OtaApkVerifier {
    override fun verify(file: File, updateInfo: UpdateInfo) {
        val packageManager = context.packageManager
        OtaDiagnosticLog.event(
            context,
            "APK-Prüfung/Paketmetadaten",
            "Android-Paketmetadaten der heruntergeladenen APK werden gelesen"
        )
        val archive = packageInfoForArchive(packageManager, file)
            ?: throw SecurityException("Heruntergeladene Datei ist keine lesbare APK")
        if (archive.packageName != context.packageName) {
            throw SecurityException(
                "APK-Paketname ${archive.packageName} stimmt nicht mit ${context.packageName} überein"
            )
        }
        val archiveVersionCode = packageVersionCode(archive)
        if (archiveVersionCode != updateInfo.versionCode) {
            throw SecurityException(
                "APK-versionCode $archiveVersionCode stimmt nicht mit " +
                    "${updateInfo.versionCode} überein"
            )
        }
        OtaDiagnosticLog.event(
            context,
            "APK-Prüfung/Paketmetadaten",
            "Paketname=${archive.packageName} und versionCode=$archiveVersionCode stimmen überein"
        )

        val installed = packageInfoForInstalled(packageManager, context.packageName)
        OtaDiagnosticLog.event(
            context,
            "APK-Prüfung/Signatur",
            "Signaturbeziehung zwischen installierter App und Update-APK wird geprüft"
        )
        verifySigningRelationship(installed, archive)
        OtaDiagnosticLog.event(
            context,
            "APK-Prüfung/Signatur",
            "Signaturbeziehung ist gültig; Zertifikate und Schlüssel werden nicht protokolliert"
        )
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
                OtaDiagnosticLog.event(
                    context,
                    "APK-Prüfung/Signatur",
                    "Mehrfachsignatur erkannt; installierte Zertifikate=${installedActive.size}; " +
                        "APK-Zertifikate=${archiveActive.size}"
                )
                if (installedActive.isEmpty() || installedActive != archiveActive) {
                    throw SecurityException("APK wurde nicht mit denselben Zertifikaten signiert")
                }
                return
            }

            val archiveHistory = archiveInfo.signingCertificateHistory.toCertificateIds()
            OtaDiagnosticLog.event(
                context,
                "APK-Prüfung/Signatur",
                "Signaturrotation wird geprüft; aktive installierte Zertifikate=" +
                    "${installedActive.size}; APK-Historie=${archiveHistory.size}"
            )
            if (installedActive.size != 1 || installedActive.first() !in archiveHistory) {
                throw SecurityException("APK-Signatur gehört nicht zur installierten App")
            }
        } else {
            val installedSignatures = installed.signatures.toCertificateIds()
            val archiveSignatures = archive.signatures.toCertificateIds()
            OtaDiagnosticLog.event(
                context,
                "APK-Prüfung/Signatur",
                "Legacy-Signaturprüfung; installierte Zertifikate=${installedSignatures.size}; " +
                    "APK-Zertifikate=${archiveSignatures.size}"
            )
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
    val root = try {
        tokener.nextValue()
    } catch (_: Exception) {
        throw IllegalArgumentException("version.json enthält ungültiges JSON")
    }
    val hasNoTrailingContent = try {
        tokener.nextClean() == '\u0000'
    } catch (_: Exception) {
        false
    }
    require(root is JSONObject && hasNoTrailingContent) {
        "version.json muss genau ein JSON-Objekt enthalten"
    }

    val actualKeys = root.keys().asSequence().toSet()
    require(actualKeys == VERSION_JSON_KEYS) {
        val missing = VERSION_JSON_KEYS - actualKeys
        val unknown = actualKeys - VERSION_JSON_KEYS
        "Ungültiges version.json-Schema; fehlend=$missing, " +
            "Anzahl unbekannter Felder=${unknown.size}"
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
