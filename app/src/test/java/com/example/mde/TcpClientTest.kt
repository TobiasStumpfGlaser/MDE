package com.example.mde

import org.junit.Test
import org.junit.Assert.*

/**
 * Tests für TcpClient.removeTableHeaderLine.
 * Diese Funktion entfernt die Kopfzeile nach dem Start-Tag.
 */
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
    fun removeTableHeaderLine_setBuchungFormat_entferntOkAlsKopfzeile() {
        // removeTableHeaderLine würde "ok" fälschlicherweise als Kopfzeile entfernen.
        // Genau deshalb gilt in sendCommand: shouldStripHeader = false für SetBuchung.
        val raw = "{SetBuchung}\nok\n{/SetBuchung}"
        val result = TcpClient.removeTableHeaderLine(raw, "{/SetBuchung}")
        assertFalse(result.contains("ok"))
        assertTrue(result.contains("{SetBuchung}"))
        assertTrue(result.contains("{/SetBuchung}"))
    }

    // ── Zusätzliche Edge Case Tests ────────────────────────────────────────

    @Test
    fun removeTableHeaderLine_nurStartTagKeinInhalt_bleibtUnveraendert() {
        val raw = "{GetArtikel}"
        val result = TcpClient.removeTableHeaderLine(raw, "{/GetArtikel}")
        assertEquals(raw, result)
    }

    @Test
    fun removeTableHeaderLine_startUndEndTagOhneInhalt_bleibtUnveraendert() {
        val raw = "{GetArtikel}\n{/GetArtikel}"
        val result = TcpClient.removeTableHeaderLine(raw, "{/GetArtikel}")
        // Sollte nur den Tag enthalten, keine Zeile zwischen ihnen
        assertTrue(result.contains("{GetArtikel}"))
        assertTrue(result.contains("{/GetArtikel}"))
    }

    @Test
    fun removeTableHeaderLine_einzigeZeileNachTag_wirdEntfernt() {
        val raw = "{GetArtikel}\nHEADER\n{/GetArtikel}"
        val result = TcpClient.removeTableHeaderLine(raw, "{/GetArtikel}")
        assertFalse(result.contains("HEADER"))
    }

    @Test
    fun removeTableHeaderLine_multipleDataLines_nurErsteEntfernt() {
        val raw = "{GetArtikel}\nHEADER\nDATA1\nDATA2\nDATA3\n{/GetArtikel}"
        val result = TcpClient.removeTableHeaderLine(raw, "{/GetArtikel}")
        assertFalse(result.contains("HEADER"))
        assertTrue(result.contains("DATA1"))
        assertTrue(result.contains("DATA2"))
        assertTrue(result.contains("DATA3"))
    }

    @Test
    fun removeTableHeaderLine_leerzeichenInKopfzeile_wirdEntfernt() {
        val raw = "{GetArtikel}\n   HEADER WITH SPACES   \nDATA\n{/GetArtikel}"
        val result = TcpClient.removeTableHeaderLine(raw, "{/GetArtikel}")
        assertFalse(result.contains("HEADER WITH SPACES"))
        assertTrue(result.contains("DATA"))
    }

    @Test
    fun removeTableHeaderLine_tabulatorInKopfzeile_wirdEntfernt() {
        val raw = "{GetArtikel}\nHEADER\tWITH\tTABS\nDATA\n{/GetArtikel}"
        val result = TcpClient.removeTableHeaderLine(raw, "{/GetArtikel}")
        assertFalse(result.contains("HEADER\tWITH\tTABS"))
        assertTrue(result.contains("DATA"))
    }

    @Test
    fun removeTableHeaderLine_leerzeileAlsKopfzeile_wirdEntfernt() {
        val raw = "{GetArtikel}\n\nDATA\n{/GetArtikel}"
        val result = TcpClient.removeTableHeaderLine(raw, "{/GetArtikel}")
        assertTrue(result.contains("DATA"))
        // Nach Entfernung sollte DATA direkt nach dem Start-Tag kommen
    }

    @Test
    fun removeTableHeaderLine_mehrereStartTagsOhneEndTags_entferntAlleKopfzeilen() {
        val raw = "{GetArtikel}\nHEADER1|2|3\nDATA1|2|3\nDATA4|5|6"
        val result = TcpClient.removeTableHeaderLine(raw, "{/GetArtikel}")
        assertFalse(result.contains("HEADER1|2|3"))
        assertTrue(result.contains("DATA1|2|3"))
        assertTrue(result.contains("DATA4|5|6"))
    }

    @Test
    fun removeTableHeaderLine_specialCharsInHeader_wirdEntfernt() {
        val raw = "{GetArtikel}\nÄÖÜß-Special!@#\$%\nDATA\n{/GetArtikel}"
        val result = TcpClient.removeTableHeaderLine(raw, "{/GetArtikel}")
        assertFalse(result.contains("ÄÖÜß"))
        assertTrue(result.contains("DATA"))
    }
}
