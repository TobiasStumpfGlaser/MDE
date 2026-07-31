package com.example.mde

import android.content.Context
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class UpdateManagerTest {
    private lateinit var cacheDirectory: File
    private lateinit var context: Context
    private lateinit var remote: FakeRemoteFiles
    private lateinit var verifier: RecordingApkVerifier
    private lateinit var manager: UpdateManager

    private val serverConfig = OtaServerConfig(
        server = "w2-fs-wks",
        connectHost = "w2-fs-wks.example.test",
        share = "transfer",
        basePath = "AppUpdate",
        username = "ota-reader",
        password = "not-logged"
    )

    @Before
    fun setUp() {
        cacheDirectory = createTempDir(prefix = "mde-ota-test-")
        context = mockk()
        every { context.cacheDir } returns cacheDirectory
        remote = FakeRemoteFiles()
        verifier = RecordingApkVerifier()
        manager = UpdateManager(
            context = context,
            serverConfig = serverConfig,
            remoteFiles = remote,
            apkVerifier = verifier,
            installedVersionCode = 84
        )
    }

    @Test
    fun parseUpdateInfo_acceptsExactMinimalSchema() {
        val info = parseUpdateInfo(
            versionJson(
                versionCode = 85,
                versionName = "6.1",
                apkFile = "releases/mde-85.apk"
            )
        )

        assertEquals(85L, info.versionCode)
        assertEquals("6.1", info.versionName)
        assertEquals("releases/mde-85.apk", info.apkFile)
    }

    @Test
    fun parseUpdateInfo_rejectsUnknownOrMissingFields() {
        assertFails<IllegalArgumentException> {
            parseUpdateInfo(versionJson().dropLast(1) + ",\"extra\":true}")
        }
        assertFails<IllegalArgumentException> {
            parseUpdateInfo(versionJson().dropLast(1) + ",\"sizeBytes\":123}")
        }
        assertFails<IllegalArgumentException> {
            parseUpdateInfo("""{"versionCode":85,"versionName":"6.1"}""")
        }
    }

    @Test
    fun parseUpdateInfo_rejectsStringVersionCode() {
        assertFails<IllegalArgumentException> {
            parseUpdateInfo(
                """{"versionCode":"85","versionName":"6.1","apkFile":"mde.apk"}"""
            )
        }
    }

    @Test
    fun updateInfo_rejectsTraversal() {
        assertFails<IllegalArgumentException> {
            UpdateInfo(85, "6.1", "../mde.apk")
        }
    }

    @Test
    fun serverConfig_buildsOnlyPathsBelowBaseAndRedactsPassword() {
        assertEquals("AppUpdate/version.json", serverConfig.versionFilePath)
        assertEquals("AppUpdate/releases/mde.apk", serverConfig.apkPath("releases/mde.apk"))
        assertFalse(serverConfig.toString().contains("not-logged"))
        assertFails<IllegalArgumentException> { serverConfig.apkPath("/mde.apk") }
        assertFails<IllegalArgumentException> { serverConfig.apkPath("..\\mde.apk") }
    }

    @Test
    fun serverConfig_allowsFilesDirectlyInShareRoot() {
        val rootConfig = OtaServerConfig(
            server = "vzeiterfassungw2k22",
            connectHost = "vzeiterfassungw2k22.example.test",
            share = "mde-update",
            basePath = "",
            username = "ota-reader",
            password = "not-logged"
        )

        assertEquals("version.json", rootConfig.versionFilePath)
        assertEquals("mde-85.apk", rootConfig.apkPath("mde-85.apk"))
    }

    @Test
    fun checkForUpdates_readsVersionFileWithKerberosSourceAndReturnsNewerVersion() {
        remote.versionBytes = versionJson(versionCode = 85).toByteArray()

        val result = manager.checkForUpdates()

        assertEquals(85L, result?.versionCode)
        assertEquals("AppUpdate/version.json", remote.lastReadPath)
        assertEquals(OtaConfig.MAX_VERSION_FILE_BYTES, remote.lastReadLimit)
    }

    @Test
    fun checkForUpdates_returnsNullForInstalledOrOlderVersion() {
        remote.versionBytes = versionJson(versionCode = 84).toByteArray()
        assertNull(manager.checkForUpdates())

        remote.versionBytes = versionJson(versionCode = 20).toByteArray()
        assertNull(manager.checkForUpdates())
    }

    @Test
    fun checkForUpdates_doesNotHideInvalidManifestAsNoUpdate() {
        remote.versionBytes = "{}".toByteArray()
        assertFails<IllegalArgumentException> { manager.checkForUpdates() }
    }

    @Test
    fun downloadApk_usesPartFileVerifiesAndPublishesFinalFile() {
        val content = "fake-signed-apk".toByteArray()
        val info = updateInfo()
        remote.downloadBytes = content

        val result = manager.downloadApk(info)

        assertTrue(result.isFile)
        assertEquals("update-85.apk", result.name)
        assertTrue(result.readBytes().contentEquals(content))
        assertFalse(File(result.parentFile, "update-85.apk.part").exists())
        assertEquals("AppUpdate/mde-85.apk", remote.lastDownloadPath)
        assertEquals(OtaConfig.MAX_APK_BYTES, remote.lastDownloadLimit)
        assertEquals(1, verifier.calls)
    }

    @Test
    fun downloadApk_emptyDownloadDeletesPartAndSkipsVerifier() {
        remote.downloadBytes = ByteArray(0)

        assertFails<java.io.IOException> { manager.downloadApk(updateInfo()) }

        assertFalse(File(cacheDirectory, "ota/update-85.apk.part").exists())
        assertFalse(File(cacheDirectory, "ota/update-85.apk").exists())
        assertEquals(0, verifier.calls)
    }

    @Test
    fun downloadApk_verifierFailureDeletesPartAndDoesNotPublishFinal() {
        remote.downloadBytes = "invalid-or-wrongly-signed-apk".toByteArray()
        verifier.failure = SecurityException("APK-Signatur ist ungültig")

        assertFails<SecurityException> { manager.downloadApk(updateInfo()) }

        val otaDirectory = File(cacheDirectory, "ota")
        assertFalse(File(otaDirectory, "update-85.apk.part").exists())
        assertFalse(File(otaDirectory, "update-85.apk").exists())
        assertEquals(1, verifier.calls)
    }

    @Test
    fun downloadApk_reportedSizeMismatchDeletesPart() {
        val content = "short".toByteArray()
        remote.downloadBytes = content
        remote.reportedDownloadSize = content.size + 1L

        assertFails<java.io.IOException> { manager.downloadApk(updateInfo()) }
        assertFalse(File(cacheDirectory, "ota/update-85.apk.part").exists())
        assertEquals(0, verifier.calls)
    }

    @Test
    fun downloadApk_reusesOnlyAnAlreadyVerifiedFinalFile() {
        val content = "cached-apk".toByteArray()
        val info = updateInfo()
        val otaDirectory = File(cacheDirectory, "ota").apply { mkdirs() }
        File(otaDirectory, "update-85.apk").writeBytes(content)

        val result = manager.downloadApk(info)

        assertEquals("update-85.apk", result.name)
        assertEquals(0, remote.downloadCalls)
        assertEquals(1, verifier.calls)
    }

    @Test
    fun downloadApk_rejectsNonNewerVersionBeforeNetworkAccess() {
        val info = UpdateInfo(84, "6.0", "mde-84.apk")

        assertFails<IllegalArgumentException> { manager.downloadApk(info) }
        assertEquals(0, remote.downloadCalls)
    }

    private fun updateInfo() = UpdateInfo(
        versionCode = 85,
        versionName = "6.1",
        apkFile = "mde-85.apk"
    )

    private fun versionJson(
        versionCode: Long = 85,
        versionName: String = "6.1",
        apkFile: String = "mde-85.apk"
    ): String =
        """{"versionCode":$versionCode,"versionName":"$versionName","apkFile":"$apkFile"}"""

    private inline fun <reified T : Throwable> assertFails(block: () -> Unit): T {
        try {
            block()
            fail("Erwartete Exception ${T::class.java.simpleName} wurde nicht ausgelöst")
        } catch (error: Throwable) {
            if (error !is T) throw error
            return error
        }
        throw AssertionError("unreachable")
    }

    private class FakeRemoteFiles : OtaRemoteFileSource {
        var versionBytes: ByteArray = ByteArray(0)
        var downloadBytes: ByteArray = ByteArray(0)
        var reportedDownloadSize: Long? = null
        var lastReadPath: String? = null
        var lastReadLimit: Long? = null
        var lastDownloadPath: String? = null
        var lastDownloadLimit: Long? = null
        var downloadCalls = 0

        override fun readFile(path: String, maxBytes: Long): ByteArray {
            lastReadPath = path
            lastReadLimit = maxBytes
            return versionBytes
        }

        override fun downloadFile(
            path: String,
            destination: File,
            maxBytes: Long
        ): Long {
            downloadCalls++
            lastDownloadPath = path
            lastDownloadLimit = maxBytes
            destination.parentFile?.mkdirs()
            destination.writeBytes(downloadBytes)
            return reportedDownloadSize ?: downloadBytes.size.toLong()
        }
    }

    private class RecordingApkVerifier : OtaApkVerifier {
        var calls = 0
        var failure: Throwable? = null

        override fun verify(file: File, updateInfo: UpdateInfo) {
            calls++
            failure?.let { throw it }
        }
    }
}
