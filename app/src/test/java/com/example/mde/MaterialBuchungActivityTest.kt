package com.example.mde

import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import com.example.mde.model.Artikel
import io.mockk.every
import io.mockk.just
import io.mockk.mockkObject
import io.mockk.unmockkObject
import io.mockk.verify
import io.mockk.Runs
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class MaterialBuchungActivityTest {

    private fun parseSetBuchungParts(request: String): List<String> =
        request.removePrefix("{SetBuchung}")
            .removeSuffix("|{/SetBuchung}")
            .split("|")

    private lateinit var activity: MaterialBuchungActivity

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

        // WICHTIG: EAN + Suchzusatz jetzt vorhanden
        DataRepository.artikelListe = listOf(
            Artikel(
                artNr = "123.4567",
                bez = "Test Artikel",
                lagerorteW1 = listOf("A", "", ""),
                lagerorteW2 = listOf("", "", ""),
                masseinheit = "ST",
                bestand = "10",
                mindestbestand = 0,
                empfBestMenge = 0,
                bestellTrigger = 0,
                grossInfo = "INFO",
                liefBestNr = "",
                bestellt3M = 4,
                bestellt6M = 6,
                snPflicht = true,          // <- wichtig für neue Logik
                EAN = "EAN123456",         // <- NEU relevant für Filter
                suchZusatz = "SUCH123"     // <- NEU relevant für Filter
            )
        )

        val intent = Intent(
            ApplicationProvider.getApplicationContext(),
            MaterialBuchungActivity::class.java
        )
        intent.putExtra("USERNAME", "testuser")

        activity = Robolectric.buildActivity(MaterialBuchungActivity::class.java, intent)
            .create().start().resume().get()

        activity.runOnUiThread {
            activity.etFilter.setText("EAN123456") // <-- jetzt realistischer Scan über EAN
        }
    }

    @After
    fun tearDown() {
        unmockkObject(TcpClient)
        unmockkObject(UiLoadingHelper)
        DataRepository.clear()
    }

    @Test
    fun doBuchenWithDetails_snPflicht_withoutSerials_fails() {
        val started = activity.doBuchenWithDetails(
            einlagern = true,
            artikelText = "123.4567",
            projektText = "P100",
            mengeText = "1",
            serialsText = ""   // <- SN fehlt
        )

        assertFalse(started)
        verify(exactly = 0) { TcpClient.sendCommand(any(), any(), any(), any(), any()) }
    }

    @Test
    fun doBuchenWithDetails_snPflicht_withSerials_passes() {
        val started = activity.doBuchenWithDetails(
            einlagern = true,
            artikelText = "123.4567",
            projektText = "P100",
            mengeText = "1",
            serialsText = "SN1"
        )

        assertEquals(true, started)
        verify(timeout = 2000) {
            TcpClient.sendCommand(any(), any(), "SetBuchung", any(), any())
        }
    }

    @Test
    fun doBuchenWithDetails_eanSearchStillWorks() {
        // Artikel wird jetzt über EAN gefunden
        activity.runOnUiThread {
            activity.etFilter.setText("EAN123456")
        }

        val started = activity.doBuchenWithDetails(
            einlagern = true,
            artikelText = "123.4567",
            projektText = "P100",
            mengeText = "2",
            serialsText = "SN1;SN2"
        )

        assertEquals(true, started)
        verify(timeout = 2000) {
            TcpClient.sendCommand(any(), any(), "SetBuchung", any(), any())
        }
    }

    @Test
    fun doBuchenWithDetails_searchZusatzStillFindsArticle() {
        activity.runOnUiThread {
            activity.etFilter.setText("SUCH123")
        }

        val started = activity.doBuchenWithDetails(
            einlagern = true,
            artikelText = "123.4567",
            projektText = "P100",
            mengeText = "2",
            serialsText = "SN1;SN2"
        )

        assertEquals(true, started)
    }

    @Test
    fun doBuchenWithDetails_missingArtikel_returnsFalse() {
        val started = activity.doBuchenWithDetails(
            einlagern = true,
            artikelText = "",
            projektText = "P100",
            mengeText = "1"
        )

        assertFalse(started)
    }

    @Test
    fun doBuchenWithDetails_invalidMenge_returnsFalse() {
        val started = activity.doBuchenWithDetails(
            einlagern = true,
            artikelText = "123.4567",
            projektText = "P100",
            mengeText = "abc"
        )

        assertFalse(started)
    }
}
