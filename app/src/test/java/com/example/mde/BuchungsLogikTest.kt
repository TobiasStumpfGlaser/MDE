package com.example.mde

import android.content.Intent
import android.widget.AutoCompleteTextView
import android.widget.EditText
import androidx.test.core.app.ApplicationProvider
import com.example.mde.model.Artikel
import io.mockk.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

// ---------------------------------------------------------------------------
// TcpClientTest — removeTableHeaderLine direkt testen (kein Netzwerk nötig)
// ---------------------------------------------------------------------------

class TcpClientTest {

    @Test
    fun removeTableHeaderLine_entferntKopfzeileNachStartTag() {
        val raw = "{GetArtikel}\nHEADER\nDATA1\n{/GetArtikel}"
        val result = TcpClient.removeTableHeaderLine(raw, "{/GetArtikel}")
        assertFalse(result.contains("HEADER"))
        assertTrue(result.contains("DATA1"))
        assertTrue(result.contains("{GetArtikel}"))
        assertTrue(result.contains("{/GetArtikel}"))
    }

    @Test
    fun removeTableHeaderLine_ohneStartTag_bleibtUnveraendert() {
        val raw = "kein tag hier\nzeile2"
        val result = TcpClient.removeTableHeaderLine(raw, "{/GetArtikel}")
        assertEquals(raw, result)
    }

    @Test
    fun removeTableHeaderLine_mehrereBlöcke_jedeKopfzeileWirdEntfernt() {
        val raw = "{GetArtikel}\nHEADER1\nDATA1\n{/GetArtikel}\n{GetArtikel}\nHEADER2\nDATA2\n{/GetArtikel}"
        val result = TcpClient.removeTableHeaderLine(raw, "{/GetArtikel}")
        assertFalse(result.contains("HEADER1"))
        assertFalse(result.contains("HEADER2"))
        assertTrue(result.contains("DATA1"))
        assertTrue(result.contains("DATA2"))
    }

    @Test
    fun removeTableHeaderLine_leererString_bleibtLeer() {
        val result = TcpClient.removeTableHeaderLine("", "{/GetArtikel}")
        assertEquals("", result)
    }

    @Test
    fun removeTableHeaderLine_setBuchungFormat_entferntOkAlsKopfzeile() {
        // removeTableHeaderLine würde "ok" fälschlicherweise als Kopfzeile entfernen.
        // Genau deshalb gilt in sendCommand: shouldStripHeader = false für SetBuchung.
        val raw = "{SetBuchung}\nok\n{/SetBuchung}"
        val result = TcpClient.removeTableHeaderLine(raw, "{/SetBuchung}")
        assertFalse(result.contains("ok"))
        assertTrue(result.contains("{SetBuchung}"))
        assertTrue(result.contains("{/SetBuchung}"))
    }
}

// ---------------------------------------------------------------------------
// DoBuchenWithDetailsTest — Robolectric + mockkObject(TcpClient)
// ---------------------------------------------------------------------------

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class DoBuchenWithDetailsTest {

    class TestActivity : BaseArtikelScanActivity() {
        override fun getLayoutId() = R.layout.activity_inventur
        override val buchungMengeView: EditText? get() = null
        override val buchungProjektView: AutoCompleteTextView? get() = null
    }

    private lateinit var activity: TestActivity

    @Before
    fun setUp() {
        mockkObject(TcpClient)
        every { TcpClient.sendCommand(any(), any(), any(), any(), any()) } returns
                "{SetBuchung}\nok\n{/SetBuchung}"

        DataRepository.artikelListe = listOf(
            Artikel("123.4567", "Test Artikel", listOf("W1A", "", ""), listOf("", "", ""), "ST", "10", 5, 2, 1, "", "")
        )
        DataRepository.projektListe = listOf("P100 – Projekt Eins")

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
        DataRepository.clear()
    }

    // --- Validierungs-Tests (sendCommand darf nicht aufgerufen werden) ---

    @Test
    fun doBuchenWithDetails_artikelBlank_ruftSendCommandNichtAuf() {
        activity.doBuchenWithDetails(einlagern = true, artikelText = "", projektText = "P100", mengeText = "5")
        verify(exactly = 0) { TcpClient.sendCommand(any(), any(), any(), any(), any()) }
    }

    @Test
    fun doBuchenWithDetails_mengeBlank_ruftSendCommandNichtAuf() {
        activity.doBuchenWithDetails(einlagern = true, artikelText = "123.4567", projektText = "P100", mengeText = "")
        verify(exactly = 0) { TcpClient.sendCommand(any(), any(), any(), any(), any()) }
    }

    @Test
    fun doBuchenWithDetails_mengeUngueltig_ruftSendCommandNichtAuf() {
        activity.doBuchenWithDetails(einlagern = true, artikelText = "123.4567", projektText = "P100", mengeText = "abc")
        verify(exactly = 0) { TcpClient.sendCommand(any(), any(), any(), any(), any()) }
    }

    @Test
    fun doBuchenWithDetails_projektBlank_ohneCount_ruftSendCommandNichtAuf() {
        activity.doBuchenWithDetails(einlagern = true, artikelText = "123.4567", projektText = "", mengeText = "5")
        verify(exactly = 0) { TcpClient.sendCommand(any(), any(), any(), any(), any()) }
    }

    // --- Request-Format-Tests (prüfen was an den Server gesendet wird) ---

    @Test
    fun doBuchenWithDetails_einlagern_sendetPlusMengeImRequest() {
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
                request = match { it.contains("+5") && it.contains("123.4567") && it.contains("P100") },
                endTag = eq("{/SetBuchung}")
            )
        }
    }

    @Test
    fun doBuchenWithDetails_auslagern_sendetMinusMengeImRequest() {
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
                request = match { it.contains("-3") && it.contains("123.4567") },
                endTag = eq("{/SetBuchung}")
            )
        }
    }

    @Test
    fun doBuchenWithDetails_count_sendetGleichzeichen() {
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
                request = match { it.contains("=10") },
                endTag = eq("{/SetBuchung}")
            )
        }
    }

    @Test
    fun doBuchenWithDetails_kommaDecimal_wirdKorrektKonvertiert() {
        activity.doBuchenWithDetails(
            einlagern = true,
            artikelText = "123.4567",
            projektText = "P100",
            mengeText = "5,5"
        )
        Thread.sleep(300)
        verify {
            TcpClient.sendCommand(
                context = any(),
                settings = any(),
                command = eq("SetBuchung"),
                request = match { it.contains("+5,5") },
                endTag = eq("{/SetBuchung}")
            )
        }
    }

    @Test
    fun doBuchenWithDetails_usernameWirdInRequestEingebaut() {
        activity.doBuchenWithDetails(
            einlagern = true,
            artikelText = "123.4567",
            projektText = "P100",
            mengeText = "1"
        )
        Thread.sleep(300)
        verify {
            TcpClient.sendCommand(
                context = any(),
                settings = any(),
                command = eq("SetBuchung"),
                request = match { it.contains("testuser") },
                endTag = eq("{/SetBuchung}")
            )
        }
    }
}
