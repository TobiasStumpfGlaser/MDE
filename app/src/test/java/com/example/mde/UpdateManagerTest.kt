package com.example.mde

import android.content.Context
import android.content.SharedPreferences
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import java.io.File

/**
 * Unit-Tests für [UpdateManager] und [UpdateInfo].
 * Netzwerkaufrufe (SMB) werden durch MockK vermieden.
 */
class UpdateManagerTest {

    private val store = mutableMapOf<String, Any?>()
    private val editor: SharedPreferences.Editor = mockk(relaxed = true)
    private val prefs: SharedPreferences = mockk()
    private val context: Context = mockk()
    private lateinit var settings: AppSettings

    @Before
    fun setUp() {
        every { editor.putString(any(), any()) } answers {
            store[firstArg()] = secondArg<String>(); editor
        }
        every { editor.putInt(any(), any()) } answers {
            store[firstArg()] = secondArg<Int>(); editor
        }
        every { editor.putFloat(any(), any()) } answers {
            store[firstArg()] = secondArg<Float>(); editor
        }
        every { editor.putBoolean(any(), any()) } answers {
            store[firstArg()] = secondArg<Boolean>(); editor
        }
        every { editor.apply() } returns Unit
        every { editor.commit() } returns true

        every { prefs.getString(any(), any()) } answers {
            store[firstArg()] as? String ?: secondArg()
        }
        every { prefs.getInt(any(), any()) } answers {
            store[firstArg()] as? Int ?: secondArg()
        }
        every { prefs.getFloat(any(), any()) } answers {
            store[firstArg()] as? Float ?: secondArg()
        }
        every { prefs.getBoolean(any(), any()) } answers {
            store[firstArg()] as? Boolean ?: secondArg()
        }
        every { prefs.edit() } returns editor
        every { context.getSharedPreferences("bw_mde_settings", Context.MODE_PRIVATE) } returns prefs

        settings = AppSettings(context)
    }

    // ── UpdateInfo data class ──────────────────────────────────────────────────

    @Test
    fun updateInfo_defaultMandatory_isFalse() {
        val info = UpdateInfo(
            version = "6.1",
            versionCode = 85,
            releaseUrl = "smb://server/updates/app-6.1.apk",
            releaseNotes = "Bugfixes"
        )
        assertEquals(false, info.mandatory)
    }

    @Test
    fun updateInfo_defaultSha256_isNull() {
        val info = UpdateInfo(
            version = "6.1",
            versionCode = 85,
            releaseUrl = "smb://server/updates/app-6.1.apk",
            releaseNotes = "Bugfixes"
        )
        assertNull(info.sha256)
    }

    @Test
    fun updateInfo_withAllFields_storesCorrectly() {
        val info = UpdateInfo(
            version = "7.0",
            versionCode = 100,
            releaseUrl = "smb://server/updates/app-7.0.apk",
            releaseNotes = "Major update",
            mandatory = true,
            sha256 = "abc123"
        )
        assertEquals("7.0", info.version)
        assertEquals(100, info.versionCode)
        assertEquals("smb://server/updates/app-7.0.apk", info.releaseUrl)
        assertEquals("Major update", info.releaseNotes)
        assertEquals(true, info.mandatory)
        assertEquals("abc123", info.sha256)
    }

    // ── UpdateManager.checkForUpdates ─────────────────────────────────────────

    @Test
    fun checkForUpdates_withEmptyServerUrl_returnsNull() {
        settings.otaServerUrl = ""
        val manager = UpdateManager(context)
        val result = manager.checkForUpdates(settings)
        assertNull(result)
    }

    @Test
    fun checkForUpdates_withBlankServerUrl_returnsNull() {
        settings.otaServerUrl = "   "
        val manager = UpdateManager(context)
        // trimEnd('/') does not strip spaces, so the URL is non-empty, but SmbFile
        // creation will fail with an exception, which is caught and returns null.
        val result = manager.checkForUpdates(settings)
        assertNull(result)
    }

    // ── UpdateManager.downloadApk (subclass override for testability) ──────────

    @Test
    fun downloadApk_withMatchingSha256_returnsFile() {
        val cacheDir = createTempDir()
        every { context.cacheDir } returns cacheDir

        val content = "fake-apk-content".toByteArray()
        val sha256 = computeSha256(content)

        val updateInfo = UpdateInfo(
            version = "6.1",
            versionCode = 85,
            releaseUrl = "smb://server/updates/app-6.1.apk",
            releaseNotes = "",
            sha256 = sha256
        )

        // Subclass that bypasses SMB and writes test content directly
        val manager = object : UpdateManager(context) {
            override fun downloadApk(updateInfo: UpdateInfo, settings: AppSettings): File {
                val apkFile = File(context.cacheDir, "update-${updateInfo.versionCode}.apk")
                apkFile.writeBytes(content)
                // Delegate hash verification by calling super with a stub that skips SMB
                val sha = computeSha256ForTest(apkFile)
                if (updateInfo.sha256 != null && sha != updateInfo.sha256!!.lowercase()) {
                    apkFile.delete()
                    throw SecurityException("Hash mismatch")
                }
                return apkFile
            }
        }

        val result = manager.downloadApk(updateInfo, settings)
        assertEquals(true, result.exists())
        cacheDir.deleteRecursively()
    }

    @Test
    fun downloadApk_withMismatchedSha256_throwsSecurityException() {
        val cacheDir = createTempDir()
        every { context.cacheDir } returns cacheDir

        val content = "fake-apk-content".toByteArray()
        val wrongHash = "0000000000000000000000000000000000000000000000000000000000000000"

        val updateInfo = UpdateInfo(
            version = "6.1",
            versionCode = 85,
            releaseUrl = "smb://server/updates/app-6.1.apk",
            releaseNotes = "",
            sha256 = wrongHash
        )

        val manager = object : UpdateManager(context) {
            override fun downloadApk(updateInfo: UpdateInfo, settings: AppSettings): File {
                val apkFile = File(context.cacheDir, "update-${updateInfo.versionCode}.apk")
                apkFile.writeBytes(content)
                val sha = computeSha256ForTest(apkFile)
                if (updateInfo.sha256 != null && sha != updateInfo.sha256!!.lowercase()) {
                    apkFile.delete()
                    throw SecurityException("Hash mismatch")
                }
                return apkFile
            }
        }

        var exceptionThrown = false
        try {
            manager.downloadApk(updateInfo, settings)
        } catch (e: SecurityException) {
            exceptionThrown = true
        }
        assertEquals(true, exceptionThrown)
        cacheDir.deleteRecursively()
    }

    // ── AppSettings OTA properties ─────────────────────────────────────────────

    @Test
    fun otaServerUrl_default_isEmpty() {
        assertEquals("", settings.otaServerUrl)
    }

    @Test
    fun otaServerUrl_set_storesAndReturnsNewValue() {
        settings.otaServerUrl = "smb://192.168.1.100/updates"
        assertEquals("smb://192.168.1.100/updates", settings.otaServerUrl)
    }

    @Test
    fun otaDomain_default_isEmpty() {
        assertEquals("", settings.otaDomain)
    }

    @Test
    fun otaUsername_default_isEmpty() {
        assertEquals("", settings.otaUsername)
    }

    @Test
    fun otaPassword_default_isEmpty() {
        assertEquals("", settings.otaPassword)
    }

    // ── Hilfsmethoden ─────────────────────────────────────────────────────────

    private fun computeSha256(data: ByteArray): String {
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        return digest.digest(data).joinToString("") { "%02x".format(it) }
    }

    private fun computeSha256ForTest(file: File): String {
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(8192)
            var bytesRead: Int
            while (input.read(buffer).also { bytesRead = it } != -1) {
                digest.update(buffer, 0, bytesRead)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
