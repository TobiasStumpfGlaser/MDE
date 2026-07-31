package com.example.mde

import android.content.Context
import io.mockk.every
import io.mockk.mockk
import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NativeKerberosSmbTest {

    @Test
    fun preparePrivatePartFile_acceptsCanonicalFileBelowPrivateCache() {
        withPrivateDirectories { context, cacheDir, _ ->
            val destination = File(cacheDir, "ota/mde-85.apk.part")

            val prepared = NativeKerberosSmb.preparePrivatePartFile(context, destination)

            assertEquals(destination.canonicalFile, prepared)
            assertTrue(prepared.parentFile!!.isDirectory)
        }
    }

    @Test(expected = IllegalArgumentException::class)
    fun preparePrivatePartFile_rejectsDestinationOutsidePrivateDirectories() {
        withPrivateDirectories { context, _, root ->
            NativeKerberosSmb.preparePrivatePartFile(
                context,
                File(root, "outside/mde-85.apk.part")
            )
        }
    }

    @Test(expected = IllegalArgumentException::class)
    fun preparePrivatePartFile_rejectsParentTraversalSegment() {
        withPrivateDirectories { context, cacheDir, _ ->
            NativeKerberosSmb.preparePrivatePartFile(
                context,
                File(cacheDir, "ota/../mde-85.apk.part")
            )
        }
    }

    private fun withPrivateDirectories(
        block: (context: Context, cacheDir: File, root: File) -> Unit
    ) {
        val root = Files.createTempDirectory("mde-smb-test").toFile()
        try {
            val cacheDir = File(root, "cache").apply { mkdirs() }
            val filesDir = File(root, "files").apply { mkdirs() }
            val noBackupFilesDir = File(root, "no-backup").apply { mkdirs() }
            val context = mockk<Context>()
            every { context.cacheDir } returns cacheDir
            every { context.filesDir } returns filesDir
            every { context.noBackupFilesDir } returns noBackupFilesDir

            block(context, cacheDir, root)
        } finally {
            root.deleteRecursively()
        }
    }
}
