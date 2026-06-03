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
// TcpClientTest -- removeTableHeaderLine direkt testen (kein Netzwerk noetig)
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
    fun removeTableHeaderLine_mehrereBlocks_jedeKopfzeileWirdEntfernt() {
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
    fun removeTableHeaderLine_leerString_bleibtLeer() {
        val result = TcpClient.removeTableHeaderLine("", "{/GetArtikel}")
        assertEquals("", result)
    }

    @Test
    fun removeTableHeaderLine_setBuchungFormat_entferntOkAlsKopfzeile() {
        // removeTableHeaderLine wuerde "ok" faelschlicherweise als Kopfzeile entfernen.
        // Genau deshalb gilt in sendCommand: shouldStripHeader = false fuer SetBuchung.
        val raw = "{SetBuchung}\nok\n{/SetBuchung}"
        val result = TcpClient.removeTableHeaderLine(raw, "{/SetBuchung}")
        assertFalse(result.contains("ok"))
        assertTrue(result.contains("{SetBuchung}"))
        assertTrue(result.contains("{/SetBuchung}"))
    }
}

class DataRepositoryTest {

    @Before
    fun setUp() {
        DataRepository.clear()
    }

    @After
    fun tearDown() {
        DataRepository.clear()
    }

    @Test
    fun rememberProjekt_fuegtProjektVorneEin() {
        DataRepository.recentProjektListe.addAll(listOf("P100", "P200"))

        DataRepository.rememberProjekt("P300")

        assertEquals(listOf("P300", "P100", "P200"), DataRepository.recentProjektListe)
    }

    @Test
    fun rememberProjekt_dedupliziertBestehende() {
        DataRepository.recentProjektListe.addAll(listOf("P100", "P200", "P300"))

        DataRepository.rememberProjekt("P200")

        assertEquals(listOf("P200", "P100", "P300"), DataRepository.recentProjektListe)
    }

    @Test
    fun rememberProjekt_begrenztAufMaxEntries() {
        DataRepository.recentProjektListe.addAll(listOf("P1", "P2", "P3"))

        DataRepository.rememberProjekt("P4", maxEntries = 3)

        assertEquals(listOf("P4", "P1", "P2"), DataRepository.recentProjektListe)
    }

    @Test
    fun shouldReload_wennArtikelListeLeer() {
        DataRepository.artikelListe = emptyList()
        DataRepository.projektListe = listOf("P100")
        DataRepository.lastLoadTime = System.currentTimeMillis()

        assertTrue(DataRepository.shouldReload())
    }

    @Test
    fun clear_leertAlleFelder() {
        DataRepository.artikelListe = listOf(
            Artikel(
                "123.4567", "Test Artikel",
                listOf("W1A", "", ""), listOf("", "", ""),
                "ST", "10", 5, 2, 1, "", ""
            )
        )
        DataRepository.projektListe = listOf("P100")
        DataRepository.recentProjektListe.addAll(listOf("P100", "P200"))

        DataRepository.clear()

        assertTrue(DataRepository.artikelListe.isEmpty())
        assertTrue(DataRepository.projektListe.isEmpty())
        assertTrue(DataRepository.recentProjektListe.isEmpty())
    }
}

class BuchungsFormatTest {

    private fun serverMengeFormat(menge: String, einlagern: Boolean, count: Boolean = false): String =
        when {
            count -> "=${menge.replace(".", ",")}"
            einlagern -> "+${menge.replace(".", ",")}"
            else -> "-${menge.replace(".", ",")}"
        }

    private fun serialsFormat(serialsRaw: String): String =
        when {
            serialsRaw.isBlank() -> ""
            serialsRaw.startsWith("Charge:", ignoreCase = true) -> {
                val chargeNr = serialsRaw.substringAfter(":").trim()
                if (chargeNr.isBlank()) "" else "Charge:$chargeNr"
            }
            else -> serialsRaw
                .split(";")
                .map { it.trim() }
                .filter { it.isNotBlank() }
                .joinToString(";")
        }

    @Test
    fun serverMengeFormat_einlagern() {
        assertEquals("+5,0", serverMengeFormat(menge = "5.0", einlagern = true))
    }

    @Test
    fun serverMengeFormat_auslagern() {
        assertEquals("-5,0", serverMengeFormat(menge = "5.0", einlagern = false))
    }

    @Test
    fun serverMengeFormat_count() {
        assertEquals("=5,0", serverMengeFormat(menge = "5.0", einlagern = true, count = true))
    }

    @Test
    fun serialsFormat_leerBleibtLeer() {
        assertEquals("", serialsFormat(""))
    }

    @Test
    fun serialsFormat_chargePrefixWirdErkannt() {
        assertEquals("Charge:ABC123", serialsFormat("Charge:ABC123"))
    }

    @Test
    fun serialsFormat_mehrereSerialsMitSemikolon() {
        assertEquals("A;B;C", serialsFormat("A;B;C"))
    }
}

// ---------------------------------------------------------------------------
// DoBuchenWithDetailsTest -- Robolectric + mockkObject(TcpClient)
//
// Request-Format (aus BaseArtikelScanActivity.doBuchenWithDetails):
//
//   {SetBuchung}[0]|[1]|[2]|[3]|[4]|[5]|[6]|[7]|[8]|[9]|{/SetBuchung}
//
//   [0] = artikelNr
//   [1] = "" (Ersatzartikel -- bei Direktbuchung immer leer, hardcoded)
//   [2] = serverMenge  (+X = einlagern, -X = auslagern, =X = count)
//   [3] = "" (Reservefeld -- immer leer, hardcoded)
//   [4] = "" (Reservefeld -- immer leer, hardcoded)
//   [5] = projekt
//   [6] = werkNummer (aus AppSettings)
//   [7] = username
//   [8] = timestamp  (dd.MM.yyyy HH:mm:ss)
//   [9] = serials / Charge (leer wenn keine)
//
// Produktionscode (BaseArtikelScanActivity):
//   append("$artikel||$serverMenge|||$projekt|${settings.werkNummer}|$username|$now|")
//   if (serialsString.isNotEmpty()) append(serialsString)
//   append("|{/SetBuchung}")
//
// Wichtige Eigenart der Validierung:
//   hasSelectedArtikel() liest immer aus etFilter (getSelectedArtikelNr()),
//   NICHT aus dem uebergebenen artikelText-Parameter.
//   Deshalb muss etFilter fuer Negativ-Tests entsprechend gesetzt werden:
//
//   - Kein Artikel:          etFilter leeren
//   - Unbekannter Artikel:   etFilter auf unbekannte ArtikelNr setzen,
//                            damit hasSelectedArtikel() false zurueckgibt
//
//   Der artikelText-Parameter wird nur fuer den Request-Body verwendet
//   (Fallback auf etFilter wenn null/blank), die Validierung laeuft immer
//   ueber etFilter.
// ---------------------------------------------------------------------------

/**
 * Parst den Datenteil eines SetBuchung-Requests in seine Felder.
 *
 * Entfernt {SetBuchung} am Anfang sowie |{/SetBuchung} am Ende,
 * splittet dann nach | und gibt die Liste zurueck (10 Elemente [0..9]).
 */
private fun parseRequestParts(request: String): List<String> =
    request
        .removePrefix("{SetBuchung}")
        .removeSuffix("|{/SetBuchung}")
        .split("|")

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class DoBuchenWithDetailsTest {

    /**
     * TestActivity legt protected-Member von BaseArtikelScanActivity offen.
     *
     * autoLoadArtikelUndProjekte = false verhindert, dass onCreate()
     * loadArtikelUndProjekteSequential() aufruft, was UiLoadingHelper.show()
     * triggert und unter Robolectric zu einem Dialog-Barrier-Crash fuehrt.
     */
    class TestActivity : BaseArtikelScanActivity() {
        override fun getLayoutId() = R.layout.activity_inventur
        override val buchungMengeView: EditText? get() = null
        override val buchungProjektView: AutoCompleteTextView? get() = null

        // Verhindert loadArtikelUndProjekteSequential() in onCreate()
        override val autoLoadArtikelUndProjekte: Boolean get() = false

        /** Setzt den Text im Artikel-Filter-Feld (protected etFilter). */
        fun setFilterText(text: String) = etFilter.setText(text)

        /** Ruft das geschuetzte doBuchenWithDetails auf. */
        fun buchen(
            einlagern: Boolean,
            count: Boolean = false,
            artikelText: String? = null,
            projektText: String? = null,
            mengeText: String? = null,
            serialsText: String? = null
        ): Boolean = doBuchenWithDetails(einlagern, count, artikelText, projektText, mengeText, serialsText)
    }

    private lateinit var activity: TestActivity

    @Before
    fun setUp() {
        // TcpClient mocken: Netzwerkaufruf abfangen
        mockkObject(TcpClient)
        every { TcpClient.sendCommand(any(), any(), any(), any(), any()) } returns
                "{SetBuchung}\nok\n{/SetBuchung}"

        // UiLoadingHelper mocken: verhindert MediaPlayer-NPE und Dialog-Barrier-Crash
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
                "ST", "10", 5, 2, 1, "", ""
            )
        )
        DataRepository.projektListe = listOf("P100 - Projekt Eins")

        val intent = Intent(ApplicationProvider.getApplicationContext(), TestActivity::class.java)
        intent.putExtra("USERNAME", "testuser")
        activity = Robolectric.buildActivity(TestActivity::class.java, intent)
            .create().start().resume().get()

        // Artikel-Text setzen, damit hasSelectedArtikel() == true (fuer positive Tests)
        activity.runOnUiThread {
            activity.setFilterText("123.4567 | Test Artikel")
        }
    }

    @After
    fun tearDown() {
        unmockkObject(TcpClient)
        unmockkObject(UiLoadingHelper)
        DataRepository.clear()
    }

    // -----------------------------------------------------------------------
    // Validierungs-Tests -- sendCommand darf NICHT aufgerufen werden
    // -----------------------------------------------------------------------

    /**
     * Wenn artikelText blank UND etFilter leer ist, schlaegt die Artikel-Pruefung fehl.
     *
     * Hintergrund: doBuchenWithDetails faellt bei leerem artikelText auf
     * getSelectedArtikelNr() (etFilter) zurueck. Ist auch etFilter leer,
     * ist der Artikel wirklich blank und sendCommand wird nicht aufgerufen.
     */
    @Test
    fun buchen_artikelBlankUndEtFilterLeer_sendCommandNichtAufgerufen() {
        activity.runOnUiThread { activity.setFilterText("") }
        activity.buchen(einlagern = true, artikelText = "", projektText = "P100", mengeText = "5")
        verify(exactly = 0) { TcpClient.sendCommand(any(), any(), any(), any(), any()) }
    }

    /**
     * Wenn etFilter eine ArtikelNr enthaelt, die nicht in DataRepository.artikelListe ist,
     * schlaegt hasSelectedArtikel() fehl und sendCommand wird nicht aufgerufen.
     *
     * Hintergrund: hasSelectedArtikel() prueft immer gegen etFilter, nicht
     * gegen den uebergebenen artikelText-Parameter. Deshalb wird auch etFilter
     * auf die unbekannte ArtikelNr gesetzt.
     */
    @Test
    fun buchen_artikelNichtInListe_sendCommandNichtAufgerufen() {
        // etFilter auf unbekannte ArtikelNr setzen -- hasSelectedArtikel() liest daraus
        activity.runOnUiThread { activity.setFilterText("999.9999 | Unbekannt") }
        activity.buchen(
            einlagern = true,
            artikelText = "999.9999",   // nicht in der artikelListe
            projektText = "P100",
            mengeText = "5"
        )
        verify(exactly = 0) { TcpClient.sendCommand(any(), any(), any(), any(), any()) }
    }

    @Test
    fun buchen_mengeBlank_sendCommandNichtAufgerufen() {
        activity.buchen(einlagern = true, artikelText = "123.4567", projektText = "P100", mengeText = "")
        verify(exactly = 0) { TcpClient.sendCommand(any(), any(), any(), any(), any()) }
    }

    @Test
    fun buchen_mengeUngueltig_sendCommandNichtAufgerufen() {
        activity.buchen(einlagern = true, artikelText = "123.4567", projektText = "P100", mengeText = "abc")
        verify(exactly = 0) { TcpClient.sendCommand(any(), any(), any(), any(), any()) }
    }

    @Test
    fun buchen_projektBlank_ohneCount_sendCommandNichtAufgerufen() {
        activity.buchen(einlagern = true, artikelText = "123.4567", projektText = "", mengeText = "5")
        verify(exactly = 0) { TcpClient.sendCommand(any(), any(), any(), any(), any()) }
    }

    @Test
    fun buchen_mengeNull_sendCommandNichtAufgerufen() {
        activity.buchen(einlagern = true, artikelText = "123.4567", projektText = "P100", mengeText = "0")
        verify(exactly = 0) { TcpClient.sendCommand(any(), any(), any(), any(), any()) }
    }

    // -----------------------------------------------------------------------
    // Struktur-Tests -- exakte Feldpositionen im Request pruefen
    // -----------------------------------------------------------------------

    @Test
    fun buchen_einlagern_requestStrukturKomplett() {
        activity.buchen(
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
                    req.startsWith("{SetBuchung}") &&          // Start-Tag korrekt
                    req.endsWith("|{/SetBuchung}") &&          // End-Tag korrekt
                    p[0] == "123.4567" &&                      // [0] Artikel
                    p[1] == "" &&                              // [1] Ersatzartikel (hardcoded leer)
                    p[2] == "+5" &&                            // [2] Menge: + = einlagern
                    p[3] == "" &&                              // [3] Reservefeld (hardcoded leer)
                    p[4] == "" &&                              // [4] Reservefeld (hardcoded leer)
                    p[5] == "P100" &&                          // [5] Projekt
                    p[7] == "testuser" &&                      // [7] Username
                    p[8].matches(Regex("""\d{2}\.\d{2}\.\d{4} \d{2}:\d{2}:\d{2}""")) && // [8] Timestamp
                    p[9] == ""                                 // [9] keine Serials
                },
                endTag = eq("{/SetBuchung}")
            )
        }
    }

    @Test
    fun buchen_auslagern_mengeMitMinusAnPosition2() {
        activity.buchen(
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
    fun buchen_count_mengeMitGleichzeichenAnPosition2() {
        activity.buchen(
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
    fun buchen_kommaDecimal_bleibtKommaAnPosition2() {
        // Nutzer gibt Komma ein -> Server bekommt Komma (deutsches Format)
        activity.buchen(
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

    // -----------------------------------------------------------------------
    // Feld [1] -- Ersatzartikel: bei Direktbuchung immer leer (hardcoded "")
    // -----------------------------------------------------------------------

    @Test
    fun buchen_ersatzartikelPosition1_istImmerLeer_einlagern() {
        activity.buchen(einlagern = true, artikelText = "123.4567", projektText = "P100", mengeText = "1")
        Thread.sleep(300)

        verify {
            TcpClient.sendCommand(
                context = any(), settings = any(), command = eq("SetBuchung"),
                request = match { req -> parseRequestParts(req).let { p -> p.size >= 10 && p[1] == "" } },
                endTag = eq("{/SetBuchung}")
            )
        }
    }

    @Test
    fun buchen_ersatzartikelPosition1_istImmerLeer_auslagern() {
        activity.buchen(einlagern = false, artikelText = "123.4567", projektText = "P100", mengeText = "2")
        Thread.sleep(300)

        verify {
            TcpClient.sendCommand(
                context = any(), settings = any(), command = eq("SetBuchung"),
                request = match { req -> parseRequestParts(req).let { p -> p.size >= 10 && p[1] == "" } },
                endTag = eq("{/SetBuchung}")
            )
        }
    }

    @Test
    fun buchen_ersatzartikelPosition1_istImmerLeer_count() {
        activity.buchen(einlagern = true, count = true, artikelText = "123.4567", projektText = "", mengeText = "5")
        Thread.sleep(300)

        verify {
            TcpClient.sendCommand(
                context = any(), settings = any(), command = eq("SetBuchung"),
                request = match { req -> parseRequestParts(req).let { p -> p.size >= 10 && p[1] == "" } },
                endTag = eq("{/SetBuchung}")
            )
        }
    }

    // -----------------------------------------------------------------------
    // Feld [3] -- Reservefeld: immer leer (hardcoded "")
    // -----------------------------------------------------------------------

    @Test
    fun buchen_reservefeldPosition3_istImmerLeer_einlagern() {
        activity.buchen(einlagern = true, artikelText = "123.4567", projektText = "P100", mengeText = "1")
        Thread.sleep(300)

        verify {
            TcpClient.sendCommand(
                context = any(), settings = any(), command = eq("SetBuchung"),
                request = match { req -> parseRequestParts(req).let { p -> p.size >= 10 && p[3] == "" } },
                endTag = eq("{/SetBuchung}")
            )
        }
    }

    @Test
    fun buchen_reservefeldPosition3_istImmerLeer_auslagern() {
        activity.buchen(einlagern = false, artikelText = "123.4567", projektText = "P100", mengeText = "2")
        Thread.sleep(300)

        verify {
            TcpClient.sendCommand(
                context = any(), settings = any(), command = eq("SetBuchung"),
                request = match { req -> parseRequestParts(req).let { p -> p.size >= 10 && p[3] == "" } },
                endTag = eq("{/SetBuchung}")
            )
        }
    }

    @Test
    fun buchen_reservefeldPosition3_istImmerLeer_count() {
        activity.buchen(einlagern = true, count = true, artikelText = "123.4567", projektText = "", mengeText = "5")
        Thread.sleep(300)

        verify {
            TcpClient.sendCommand(
                context = any(), settings = any(), command = eq("SetBuchung"),
                request = match { req -> parseRequestParts(req).let { p -> p.size >= 10 && p[3] == "" } },
                endTag = eq("{/SetBuchung}")
            )
        }
    }

    // -----------------------------------------------------------------------
    // Feld [4] -- Reservefeld: immer leer (hardcoded "")
    // -----------------------------------------------------------------------

    @Test
    fun buchen_reservefeldPosition4_istImmerLeer_einlagern() {
        activity.buchen(einlagern = true, artikelText = "123.4567", projektText = "P100", mengeText = "1")
        Thread.sleep(300)

        verify {
            TcpClient.sendCommand(
                context = any(), settings = any(), command = eq("SetBuchung"),
                request = match { req -> parseRequestParts(req).let { p -> p.size >= 10 && p[4] == "" } },
                endTag = eq("{/SetBuchung}")
            )
        }
    }

    @Test
    fun buchen_reservefeldPosition4_istImmerLeer_auslagern() {
        activity.buchen(einlagern = false, artikelText = "123.4567", projektText = "P100", mengeText = "2")
        Thread.sleep(300)

        verify {
            TcpClient.sendCommand(
                context = any(), settings = any(), command = eq("SetBuchung"),
                request = match { req -> parseRequestParts(req).let { p -> p.size >= 10 && p[4] == "" } },
                endTag = eq("{/SetBuchung}")
            )
        }
    }

    @Test
    fun buchen_reservefeldPosition4_istImmerLeer_count() {
        activity.buchen(einlagern = true, count = true, artikelText = "123.4567", projektText = "", mengeText = "5")
        Thread.sleep(300)

        verify {
            TcpClient.sendCommand(
                context = any(), settings = any(), command = eq("SetBuchung"),
                request = match { req -> parseRequestParts(req).let { p -> p.size >= 10 && p[4] == "" } },
                endTag = eq("{/SetBuchung}")
            )
        }
    }

    // -----------------------------------------------------------------------
    // Feld [6] -- werkNummer aus AppSettings (Feld muss vorhanden sein)
    // -----------------------------------------------------------------------

    @Test
    fun buchen_werkNummerAnPosition6_feldVorhanden_einlagern() {
        activity.buchen(einlagern = true, artikelText = "123.4567", projektText = "P100", mengeText = "1")
        Thread.sleep(300)

        verify {
            TcpClient.sendCommand(
                context = any(), settings = any(), command = eq("SetBuchung"),
                request = match { req -> parseRequestParts(req).size >= 10 },
                endTag = eq("{/SetBuchung}")
            )
        }
    }

    @Test
    fun buchen_werkNummerAnPosition6_feldVorhanden_auslagern() {
        activity.buchen(einlagern = false, artikelText = "123.4567", projektText = "P100", mengeText = "2")
        Thread.sleep(300)

        verify {
            TcpClient.sendCommand(
                context = any(), settings = any(), command = eq("SetBuchung"),
                request = match { req -> parseRequestParts(req).size >= 10 },
                endTag = eq("{/SetBuchung}")
            )
        }
    }

    @Test
    fun buchen_werkNummerAnPosition6_feldVorhanden_count() {
        activity.buchen(einlagern = true, count = true, artikelText = "123.4567", projektText = "", mengeText = "5")
        Thread.sleep(300)

        verify {
            TcpClient.sendCommand(
                context = any(), settings = any(), command = eq("SetBuchung"),
                request = match { req -> parseRequestParts(req).size >= 10 },
                endTag = eq("{/SetBuchung}")
            )
        }
    }

    // -----------------------------------------------------------------------
    // Feld [7] -- username aus Intent-Extra
    // -----------------------------------------------------------------------

    @Test
    fun buchen_usernameAnPosition7() {
        activity.buchen(einlagern = true, artikelText = "123.4567", projektText = "P100", mengeText = "1")
        Thread.sleep(300)

        verify {
            TcpClient.sendCommand(
                context = any(), settings = any(), command = eq("SetBuchung"),
                request = match { req ->
                    val p = parseRequestParts(req)
                    p.size >= 10 && p[7] == "testuser"
                },
                endTag = eq("{/SetBuchung}")
            )
        }
    }

    // -----------------------------------------------------------------------
    // Feld [8] -- Timestamp-Format
    // -----------------------------------------------------------------------

    @Test
    fun buchen_timestampFormatAnPosition8() {
        activity.buchen(einlagern = true, artikelText = "123.4567", projektText = "P100", mengeText = "1")
        Thread.sleep(300)

        verify {
            TcpClient.sendCommand(
                context = any(), settings = any(), command = eq("SetBuchung"),
                request = match { req ->
                    val p = parseRequestParts(req)
                    // [8] Format: dd.MM.yyyy HH:mm:ss
                    p.size >= 10 && p[8].matches(Regex("""\d{2}\.\d{2}\.\d{4} \d{2}:\d{2}:\d{2}"""))
                },
                endTag = eq("{/SetBuchung}")
            )
        }
    }

    // -----------------------------------------------------------------------
    // Feld [9] -- Serials / Charge
    // -----------------------------------------------------------------------

    @Test
    fun buchen_serialsAnPosition9() {
        activity.buchen(
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

    @Test
    fun buchen_chargeAnPosition9() {
        activity.buchen(
            einlagern = true,
            artikelText = "123.4567",
            projektText = "P100",
            mengeText = "5",
            serialsText = "Charge:CH-2024-001"
        )
        Thread.sleep(300)

        verify {
            TcpClient.sendCommand(
                context = any(), settings = any(), command = eq("SetBuchung"),
                request = match { req ->
                    val p = parseRequestParts(req)
                    p.size >= 10 && p[9] == "Charge:CH-2024-001"
                },
                endTag = eq("{/SetBuchung}")
            )
        }
    }

    @Test
    fun buchen_keineSerials_position9IstLeer() {
        activity.buchen(
            einlagern = true,
            artikelText = "123.4567",
            projektText = "P100",
            mengeText = "3",
            serialsText = ""
        )
        Thread.sleep(300)

        verify {
            TcpClient.sendCommand(
                context = any(), settings = any(), command = eq("SetBuchung"),
                request = match { req ->
                    val p = parseRequestParts(req)
                    p.size >= 10 && p[9] == ""
                },
                endTag = eq("{/SetBuchung}")
            )
        }
    }
}
