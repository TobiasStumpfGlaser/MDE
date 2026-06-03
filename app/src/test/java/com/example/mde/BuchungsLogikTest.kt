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
//
// Request-Format (aus BaseArtikelScanActivity.doBuchenWithDetails):
//   {SetBuchung}[0]|[1]|[2]|[3]|[4]|[5]|[6]|[7]|[8]|[9]|{/SetBuchung}
//
//   [0] = artikelNr
//   [1] = "" (Ersatzartikel, immer leer bei Direktbuchung)
//   [2] = serverMenge  (+X = einlagern, -X = auslagern, =X = count)
//   [3] = "" (leer)
//   [4] = "" (leer)
//   [5] = projekt
//   [6] = werkNummer
//   [7] = username
//   [8] = timestamp  (dd.MM.yyyy HH:mm:ss)
//   [9] = serials / Charge (leer wenn keine)
// ---------------------------------------------------------------------------

/** Parst den Mittelteil eines SetBuchung-Requests in seine Felder.
 *
 * Entfernt `{SetBuchung}` am Anfang und `|{/SetBuchung}` am Ende,
 * splittet dann nach `|` und gibt das Array zurück.
 */
private fun parseRequestParts(request: String): List<String> =
    request
        .removePrefix("{SetBuchung}")
        .removeSuffix("|{/SetBuchung}")
        .split("|")

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

        // Artikel in etFilter setzen, damit hasSelectedArtikel() = true
        activity.runOnUiThread {
            activity.etFilter.setText("123.4567 | Test Artikel")
        }
    }

    @After
    fun tearDown() {
        unmockkObject(TcpClient)
        DataRepository.clear()
    }

    // -----------------------------------------------------------------------
    // Validierungs-Tests — sendCommand darf NICHT aufgerufen werden
    // -----------------------------------------------------------------------

    @Test
    fun doBuchenWithDetails_artikelBlank_sendCommandWirdNichtAufgerufen() {
        activity.doBuchenWithDetails(einlagern = true, artikelText = "", projektText = "P100", mengeText = "5")
        verify(exactly = 0) { TcpClient.sendCommand(any(), any(), any(), any(), any()) }
    }

    @Test
    fun doBuchenWithDetails_mengeBlank_sendCommandWirdNichtAufgerufen() {
        activity.doBuchenWithDetails(einlagern = true, artikelText = "123.4567", projektText = "P100", mengeText = "")
        verify(exactly = 0) { TcpClient.sendCommand(any(), any(), any(), any(), any()) }
    }

    @Test
    fun doBuchenWithDetails_mengeUngueltig_sendCommandWirdNichtAufgerufen() {
        activity.doBuchenWithDetails(einlagern = true, artikelText = "123.4567", projektText = "P100", mengeText = "abc")
        verify(exactly = 0) { TcpClient.sendCommand(any(), any(), any(), any(), any()) }
    }

    @Test
    fun doBuchenWithDetails_projektBlank_ohneCount_sendCommandWirdNichtAufgerufen() {
        activity.doBuchenWithDetails(einlagern = true, artikelText = "123.4567", projektText = "", mengeText = "5")
        verify(exactly = 0) { TcpClient.sendCommand(any(), any(), any(), any(), any()) }
    }

    @Test
    fun doBuchenWithDetails_mengeNull_sendCommandWirdNichtAufgerufen() {
        activity.doBuchenWithDetails(einlagern = true, artikelText = "123.4567", projektText = "P100", mengeText = "0")
        verify(exactly = 0) { TcpClient.sendCommand(any(), any(), any(), any(), any()) }
    }

    // -----------------------------------------------------------------------
    // Struktur-Tests — exakte Feldpositionen im Request prüfen
    // -----------------------------------------------------------------------

    @Test
    fun doBuchenWithDetails_einlagern_requestStrukturKorrekt() {
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
                    req.startsWith("{SetBuchung}") &&     // Start-Tag korrekt
                    req.endsWith("|{/SetBuchung}") &&     // End-Tag korrekt
                    p[0] == "123.4567" &&                 // [0] Artikel
                    p[1] == "" &&                         // [1] Ersatzartikel (leer)
                    p[2] == "+5" &&                       // [2] Menge: + = einlagern
                    p[3] == "" &&                         // [3] leer
                    p[4] == "" &&                         // [4] leer
                    p[5] == "P100" &&                     // [5] Projekt
                    p[7] == "testuser" &&                 // [7] Username
                    p[8].matches(Regex("""\d{2}\.\d{2}\.\d{4} \d{2}:\d{2}:\d{2}""")) && // [8] Timestamp
                    p[9] == ""                            // [9] keine Serials
                },
                endTag = eq("{/SetBuchung}")
            )
        }
    }

    @Test
    fun doBuchenWithDetails_auslagern_mengeMitMinusAnPosition2() {
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
                    p[0] == "123.4567" &&   // [0] Artikel
                    p[2] == "-3" &&         // [2] Menge: - = auslagern
                    p[5] == "P100"          // [5] Projekt
                },
                endTag = eq("{/SetBuchung}")
            )
        }
    }

    @Test
    fun doBuchenWithDetails_count_mengeMitGleichzeichenAnPosition2() {
        activity.doBuchenWithDetails(
            einlagern = true,
            count = true,
            artikelText = "123.4567",
            projektText = "",   // count ignoriert Pflicht-Projekt
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
                    p[0] == "123.4567" &&   // [0] Artikel
                    p[2] == "=10"           // [2] Menge: = = Inventur/Count
                },
                endTag = eq("{/SetBuchung}")
            )
        }
    }

    @Test
    fun doBuchenWithDetails_kommaDecimal_bleibtKommaAnPosition2() {
        // Nutzer gibt Komma ein → Server bekommt Komma (deutsches Format)
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
                request = match { req ->
                    val p = parseRequestParts(req)
                    p.size >= 10 &&
                    p[2] == "+5,5"          // [2] Komma bleibt Komma
                },
                endTag = eq("{/SetBuchung}")
            )
        }
    }

    @Test
    fun doBuchenWithDetails_usernameAnPosition7() {
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
                request = match { req ->
                    val p = parseRequestParts(req)
                    p.size >= 10 &&
                    p[7] == "testuser"      // [7] Username aus Intent-Extra
                },
                endTag = eq("{/SetBuchung}")
            )
        }
    }

    @Test
    fun doBuchenWithDetails_timestampFormatAnPosition8() {
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
                request = match { req ->
                    val p = parseRequestParts(req)
                    p.size >= 10 &&
                    // [8] Format: dd.MM.yyyy HH:mm:ss
                    p[8].matches(Regex("""\d{2}\.\d{2}\.\d{4} \d{2}:\d{2}:\d{2}"""))
                },
                endTag = eq("{/SetBuchung}")
            )
        }
    }

    @Test
    fun doBuchenWithDetails_serialsAnPosition9() {
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
                context = any(),
                settings = any(),
                command = eq("SetBuchung"),
                request = match { req ->
                    val p = parseRequestParts(req)
                    p.size >= 10 &&
                    p[9] == "SN001;SN002"   // [9] Serials semikolonsepariert
                },
                endTag = eq("{/SetBuchung}")
            )
        }
    }

    @Test
    fun doBuchenWithDetails_chargeAnPosition9() {
        activity.doBuchenWithDetails(
            einlagern = true,
            artikelText = "123.4567",
            projektText = "P100",
            mengeText = "5",
            serialsText = "Charge:CH-2024-001"
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
                    p[9] == "Charge:CH-2024-001"  // [9] Charge-Format erhalten
                },
                endTag = eq("{/SetBuchung}")
            )
        }
    }

    @Test
    fun doBuchenWithDetails_keineSerials_position9IstLeer() {
        activity.doBuchenWithDetails(
            einlagern = true,
            artikelText = "123.4567",
            projektText = "P100",
            mengeText = "3",
            serialsText = ""
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
                    p[9] == ""              // [9] keine Serials → leer
                },
                endTag = eq("{/SetBuchung}")
            )
        }
    }

    @Test
    fun doBuchenWithDetails_ersatzartikelPosition1_immerLeer() {
        // Bei Direktbuchungen (nicht Pick/Drop) ist Feld [1] immer leer
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
                request = match { req ->
                    val p = parseRequestParts(req)
                    p.size >= 10 &&
                    p[1] == ""              // [1] kein Ersatzartikel bei Direktbuchung
                },
                endTag = eq("{/SetBuchung}")
            )
        }
    }
}
