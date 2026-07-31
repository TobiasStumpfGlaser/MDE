package com.example.mde

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NativeKerberosSmbInstrumentedTest {

    @Test
    fun preparePrivatePartFile_acceptsAndroidPrivateCacheAlias() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val testDirectory = File(context.cacheDir, "ota-path-test")
        val destination = File(testDirectory, "mde-85.apk.part")

        try {
            val prepared = NativeKerberosSmb.preparePrivatePartFile(context, destination)

            assertEquals(destination.canonicalFile, prepared)
            assertTrue(prepared.parentFile!!.isDirectory)
            assertTrue(prepared.path.startsWith(context.cacheDir.canonicalPath + File.separator))
        } finally {
            testDirectory.deleteRecursively()
        }
    }
}
