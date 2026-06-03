package com.example.mde

import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import com.example.mde.model.Artikel
import io.mockk.Runs
import io.mockk.clearMocks
import io.mockk.every
import io.mockk.just
import io.mockk.mockkObject
import io.mockk.unmockkObject
import io.mockk.verify
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class InventurActivityTest {

    class TestActivity : InventurActivity() {
        override val autoLoadArtikelUndProjekte: Boolean = false

        fun setFilterText(text: String) = etFilter.setText(text)
        fun setMengeText(text: String) = edtMenge.setText(text)
        fun buchenCount(): Boolean = doBuchenWithDetails(
            einlagern = true,
            count = true,
            artikelText = null,
            projektText = "",
            mengeText = edtMenge.text.toString(),
            serialsText = ""
        )
    }

    private lateinit var activity: TestActivity

    @Before
    fun setUp() {
        mockkObject(TcpClient)
        every { TcpClient.sendCommand(any(), any(), any(), any(), any()) } returns
                "{SetBuchung}\nok\n{/SetBuchung}"

        mockkObject(UiLoadingHelper)
        every { UiLoadingHelper.show(any(), any(), any(), any()) } just Runs
        every { UiLoadingHelper.hide() } just Runs
        every { UiLoadingHelper.showError(any(), any()) } just Runs
        every { UiLoadingHelper.update(any(), any(), any()) } just Runs
        every { UiLoadingHelper.playErrorSound(any()) } just Runs

        DataRepository.clear()
        DataRepository.artikelListe = listOf(
            Artikel(
                "123.4567", "Test Artikel",
                listOf("A", "", ""), listOf("", "", ""),
                "ST", "10", 0, 0, 0, "", ""
            )
        )

        val intent = Intent(ApplicationProvider.getApplicationContext(), TestActivity::class.java)
        intent.putExtra("USERNAME", "testuser")
        activity = Robolectric.buildActivity(TestActivity::class.java, intent)
            .create().start().resume().get()
    }

    @After
    fun tearDown() {
        unmockkObject(TcpClient)
        unmockkObject(UiLoadingHelper)
        DataRepository.clear()
    }

    @Test
    fun countBooking_usesEqualsPrefixInServerAmount() {
        activity.runOnUiThread {
            activity.setFilterText("123.4567 | Test Artikel")
            activity.setMengeText("9")
        }

        clearMocks(TcpClient, answers = false, recordedCalls = true)
        activity.buchenCount()

        verify(timeout = 2000) {
            TcpClient.sendCommand(
                context = any(),
                settings = any(),
                command = "SetBuchung",
                request = io.mockk.match { it.contains("||=9|") },
                endTag = "{/SetBuchung}"
            )
        }
    }

    @Test
    fun validation_emptyArticleOrMissingAmount_preventsBooking() {
        activity.runOnUiThread {
            activity.setFilterText("")
            activity.setMengeText("5")
        }
        activity.buchenCount()

        activity.runOnUiThread {
            activity.setFilterText("123.4567 | Test Artikel")
            activity.setMengeText("")
        }
        activity.buchenCount()

        verify(exactly = 0) { TcpClient.sendCommand(any(), any(), any(), any(), any()) }
    }

    @Test
    fun validCountBooking_callsServerSuccessfully() {
        activity.runOnUiThread {
            activity.setFilterText("123.4567 | Test Artikel")
            activity.setMengeText("3")
        }

        val started = activity.buchenCount()

        assertEquals(true, started)
        verify(timeout = 2000, exactly = 1) { TcpClient.sendCommand(any(), any(), "SetBuchung", any(), any()) }
    }
}
