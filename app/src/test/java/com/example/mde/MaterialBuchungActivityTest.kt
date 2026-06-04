package com.example.mde

import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import com.example.mde.model.Artikel
import io.mockk.every
import io.mockk.just
import io.mockk.match
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

    // Helper function to parse SetBuchung request format
    private fun parseSetBuchungParts(request: String): List<String> =
        request.removePrefix("{SetBuchung}").removeSuffix("|{/SetBuchung}").split("|")

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
        DataRepository.artikelListe = listOf(
            Artikel(
                "123.4567", "Test Artikel",
                listOf("A", "", ""), listOf("", "", ""),
                "ST", "10", 0, 0, 0, "", ""
            )
        )

        val intent = Intent(ApplicationProvider.getApplicationContext(), MaterialBuchungActivity::class.java)
        intent.putExtra("USERNAME", "testuser")
        activity = Robolectric.buildActivity(MaterialBuchungActivity::class.java, intent)
            .create().start().resume().get()

        activity.runOnUiThread { activity.etFilter.setText("123.4567 | Test Artikel") }
    }

    @After
    fun tearDown() {
        unmockkObject(TcpClient)
        unmockkObject(UiLoadingHelper)
        DataRepository.clear()
    }

    @Test
    fun normalizeProjektFilter_normalizesSpecialCharsAndCase() {
        assertEquals("p100projektalpha", activity.normalizeProjektFilter("P-100 / Projekt Alpha"))
    }

    @Test
    fun normalizeProjektFilter_removesSpaces() {
        assertEquals("abc123", activity.normalizeProjektFilter("  A B C  1 2 3  "))
    }

    @Test
    fun normalizeProjektFilter_handlesUmlauts() {
        assertEquals("", activity.normalizeProjektFilter("ÄÖÜäöü"))
    }

    @Test
    fun normalizeProjektFilter_emptyInput_returnsEmpty() {
        assertEquals("", activity.normalizeProjektFilter(""))
    }

    @Test
    fun sortProjekteWithRecents_placesRecentProjectsFirstThenAlphabetic() {
        DataRepository.recentProjektListe.clear()
        DataRepository.recentProjektListe.addAll(listOf("P2 - Zwei", "P1 - Eins"))

        val sorted = activity.sortProjekteWithRecents(
            listOf("P3 - Drei", "P1 - Eins", "P2 - Zwei", "P4 - A")
        )

        assertEquals(listOf("P2 - Zwei", "P1 - Eins", "P4 - A", "P3 - Drei"), sorted)
    }

    @Test
    fun sortProjekteWithRecents_emptyRecentsList_returnsAlphabetic() {
        DataRepository.recentProjektListe.clear()

        val sorted = activity.sortProjekteWithRecents(
            listOf("P3 - Drei", "P1 - Eins", "P2 - Zwei")
        )

        assertEquals(listOf("P1 - Eins", "P2 - Zwei", "P3 - Drei"), sorted)
    }

    @Test
    fun sortProjekteWithRecents_recentNotInList_stillSortsCorrectly() {
        DataRepository.recentProjektListe.clear()
        DataRepository.recentProjektListe.addAll(listOf("P999 - Not In List"))

        val sorted = activity.sortProjekteWithRecents(
            listOf("P3 - Drei", "P1 - Eins")
        )

        assertEquals(listOf("P1 - Eins", "P3 - Drei"), sorted)
    }

    @Test
    fun doBuchenWithDetails_validationFailures_doNotCallSendCommand() {
        activity.doBuchenWithDetails(einlagern = true, artikelText = "123.4567", projektText = "", mengeText = "1")
        activity.doBuchenWithDetails(
            einlagern = true,
            artikelText = "123.4567",
            projektText = "P100",
            mengeText = "2",
            serialsText = "SN1"
        )

        verify(exactly = 0) { TcpClient.sendCommand(any(), any(), any(), any(), any()) }
    }

    @Test
    fun doBuchenWithDetails_missingArtikel_returnsFalse() {
        activity.runOnUiThread { activity.etFilter.setText("") }
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

    @Test
    fun doBuchenWithDetails_validInput_callsSetBuchungWithExpectedPayload() {
        val started = activity.doBuchenWithDetails(
            einlagern = true,
            artikelText = "123.4567",
            projektText = "P100",
            mengeText = "2",
            serialsText = "SN1;SN2"
        )

        assertEquals(true, started)
        verify(timeout = 2000) {
            TcpClient.sendCommand(
                context = any(),
                settings = any(),
                command = "SetBuchung",
                request = match { req ->
                    val p = parseSetBuchungParts(req)
                    p.size >= 10 && p[0] == "123.4567" && p[2] == "+2" && p[5] == "P100" && p[9] == "SN1;SN2"
                },
                endTag = "{/SetBuchung}"
            )
        }
    }

    @Test
    fun doBuchenWithDetails_auslagernWithNegativeSign() {
        val started = activity.doBuchenWithDetails(
            einlagern = false,
            artikelText = "123.4567",
            projektText = "P100",
            mengeText = "5"
        )

        assertEquals(true, started)
        verify(timeout = 2000) {
            TcpClient.sendCommand(
                context = any(),
                settings = any(),
                command = "SetBuchung",
                request = match { req ->
                    val p = parseSetBuchungParts(req)
                    p.size >= 10 && p[2] == "-5"
                },
                endTag = "{/SetBuchung}"
            )
        }
    }
}
