package com.example.mde

import android.content.Intent
import android.widget.AutoCompleteTextView
import android.widget.EditText
import androidx.test.core.app.ApplicationProvider
import com.example.mde.model.Artikel
import io.mockk.*
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

// Helper function to parse SetBuchung request format
private fun parseRequestParts(request: String): List<String> =
    request
        .removePrefix("{SetBuchung}")
        .removeSuffix("|{/SetBuchung}")
        .split("|")

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class DoBuchenWithDetailsTest {

    /**
     * TestActivity prevents autoLoadArtikelUndProjekte to avoid
     * UiLoadingHelper.show() Dialog-Barrier crash under Robolectric.
     */
    class TestActivity : BaseArtikelScanActivity() {
        override fun getLayoutId() = R.layout.activity_inventur
        override val buchungMengeView: EditText? get() = null
        override val buchungProjektView: AutoCompleteTextView? get() = null
        override val autoLoadArtikelUndProjekte: Boolean get() = false
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

        DataRepository.artikelListe = listOf(
            Artikel(
                "123.4567", "Test Artikel",
                listOf("W1A", "", ""), listOf("", "", ""),
                "ST", "10", 5,
                2, 1, "grInfo",
                snPflicht = false, bestellt3M = 2, bestellt6M =  4,
                EAN ="1234567654321", suchZusatz= "Artikel Zusatz", liefBestNr = "LiefBestNr"
            )
        )
        DataRepository.projektListe = listOf("P100 - Projekt Eins")

        val intent = Intent(ApplicationProvider.getApplicationContext(), TestActivity::class.java)
        intent.putExtra("USERNAME", "testuser")
        activity = Robolectric.buildActivity(TestActivity::class.java, intent)
            .create().start().resume().get()

        activity.runOnUiThread {
            activity.etFilter.setText("123.4567 | Test Artikel")
        }
    }

    @After
    fun tearDown() {
        unmockkObject(TcpClient)
        unmockkObject(UiLoadingHelper)
        DataRepository.clear()
    }

    @Test
    fun buchen_artikelBlankUndEtFilterLeer_sendCommandNichtAufgerufen() {
        activity.runOnUiThread { activity.etFilter.setText("") }
        activity.doBuchenWithDetails(einlagern = true, artikelText = "", projektText = "P100", mengeText = "5")
        verify(exactly = 0) { TcpClient.sendCommand(any(), any(), any(), any(), any()) }
    }

    @Test
    fun buchen_artikelNichtInListe_sendCommandNichtAufgerufen() {
        activity.runOnUiThread { activity.etFilter.setText("999.9999 | Unbekannt") }
        activity.doBuchenWithDetails(
            einlagern = true,
            artikelText = "999.9999",
            projektText = "P100",
            mengeText = "5"
        )
        verify(exactly = 0) { TcpClient.sendCommand(any(), any(), any(), any(), any()) }
    }

    @Test
    fun buchen_mengeBlank_sendCommandNichtAufgerufen() {
        activity.doBuchenWithDetails(einlagern = true, artikelText = "123.4567", projektText = "P100", mengeText = "")
        verify(exactly = 0) { TcpClient.sendCommand(any(), any(), any(), any(), any()) }
    }

    @Test
    fun buchen_mengeUngueltig_sendCommandNichtAufgerufen() {
        activity.doBuchenWithDetails(einlagern = true, artikelText = "123.4567", projektText = "P100", mengeText = "abc")
        verify(exactly = 0) { TcpClient.sendCommand(any(), any(), any(), any(), any()) }
    }

    @Test
    fun buchen_projektBlank_ohneCount_sendCommandNichtAufgerufen() {
        activity.doBuchenWithDetails(einlagern = true, artikelText = "123.4567", projektText = "", mengeText = "5")
        verify(exactly = 0) { TcpClient.sendCommand(any(), any(), any(), any(), any()) }
    }

    @Test
    fun buchen_mengeNull_sendCommandNichtAufgerufen() {
        activity.doBuchenWithDetails(einlagern = true, artikelText = "123.4567", projektText = "P100", mengeText = "0")
        verify(exactly = 0) { TcpClient.sendCommand(any(), any(), any(), any(), any()) }
    }

    @Test
    fun buchen_einlagern_requestStrukturKomplett() {
        activity.doBuchenWithDetails(
            einlagern = true,
            artikelText = "123.4567",
            projektText = "P100",
            mengeText = "5"
        )
        Thread.sleep(300)

        verify {
            TcpClient.sendCommand(
                context = any(),
                settings = any(),
                command = eq("SetBuchung"),
                request = match { req ->
                    val p = parseRequestParts(req)
                    p.size >= 10 &&
                    req.startsWith("{SetBuchung}") &&
                    req.endsWith("|{/SetBuchung}") &&
                    p[0] == "123.4567" &&
                    p[1] == "" &&
                    p[2] == "+5" &&
                    p[3] == "" &&
                    p[4] == "" &&
                    p[5] == "P100" &&
                    p[7] == "testuser" &&
                    p[8].matches(Regex("""\d{2}\.\d{2}\.\d{4} \d{2}:\d{2}:\d{2}""")) &&
                    p[9] == ""
                },
                endTag = eq("{/SetBuchung}")
            )
        }
    }

    @Test
    fun buchen_auslagern_mengeMitMinusAnPosition2() {
        activity.doBuchenWithDetails(
            einlagern = false,
            artikelText = "123.4567",
            projektText = "P100",
            mengeText = "3"
        )
        Thread.sleep(300)

        verify {
            TcpClient.sendCommand(
                context = any(),
                settings = any(),
                command = eq("SetBuchung"),
                request = match { req ->
                    val p = parseRequestParts(req)
                    p.size >= 10 &&
                    p[0] == "123.4567" &&
                    p[2] == "-3" &&
                    p[5] == "P100"
                },
                endTag = eq("{/SetBuchung}")
            )
        }
    }

    @Test
    fun buchen_count_mengeMitGleichzeichenAnPosition2() {
        activity.doBuchenWithDetails(
            einlagern = true,
            count = true,
            artikelText = "123.4567",
            projektText = "",
            mengeText = "10"
        )
        Thread.sleep(300)

        verify {
            TcpClient.sendCommand(
                context = any(),
                settings = any(),
                command = eq("SetBuchung"),
                request = match { req ->
                    val p = parseRequestParts(req)
                    p.size >= 10 &&
                    p[0] == "123.4567" &&
                    p[2] == "=10"
                },
                endTag = eq("{/SetBuchung}")
            )
        }
    }

    @Test
    fun buchen_serialsAnPosition9() {
        activity.doBuchenWithDetails(
            einlagern = true,
            artikelText = "123.4567",
            projektText = "P100",
            mengeText = "2",
            serialsText = "SN001;SN002"
        )
        Thread.sleep(300)

        verify {
            TcpClient.sendCommand(
                context = any(), settings = any(), command = eq("SetBuchung"),
                request = match { req ->
                    val p = parseRequestParts(req)
                    p.size >= 10 && p[9] == "SN001;SN002"
                },
                endTag = eq("{/SetBuchung}")
            )
        }
    }
}
