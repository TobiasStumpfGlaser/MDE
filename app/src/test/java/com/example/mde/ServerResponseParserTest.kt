package com.example.mde

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ServerResponseParserTest {

    @Test
    fun parseProjektList_validAndInvalidLines_parsesOnlyValidEntries() {
        val raw = """
            {GetProjekte}

            P100|Projekt Eins
            Ungueltig
            P200|Projekt Zwei|Extra
            P300|Projekt Drei
            {/GetProjekte}
        """.trimIndent()

        assertEquals(
            listOf("P100 – Projekt Eins", "P300 – Projekt Drei"),
            parseProjektList(raw)
        )
    }

    @Test
    fun parseProjektList_missingTagAndBlankLines_stillParsesPairs() {
        val raw = "\n\nP10|A\n\nP20|B\n"
        assertEquals(listOf("P10 – A", "P20 – B"), parseProjektList(raw))
    }

    @Test
    fun parseArtikelResponse_validBlock_extractsAllFields() {
        val raw = """
            prefix
            {GetArtikel}

            123.4567|Artikel A|W1A|W1B|W1C|W2A|W2B|W2C|ST|10|5|2|1|Grossinfo|LiefBest

            {/GetArtikel}
            suffix
        """.trimIndent()

        val result = parseArtikelResponse(raw)
        assertEquals(1, result.size)
        val artikel = result.first()
        assertEquals("123.4567", artikel.artNr)
        assertEquals("Artikel A", artikel.bez)
        assertEquals(listOf("W1A", "W1B", "W1C"), artikel.lagerorteW1)
        assertEquals(listOf("W2A", "W2B", "W2C"), artikel.lagerorteW2)
        assertEquals(5, artikel.empfBestMenge)
    }

    @Test
    fun parseArtikelResponse_missingTags_returnsEmpty() {
        val raw = "123.4567|Artikel A|W1A|W1B|W1C|W2A|W2B|W2C|ST|10|5|2|1|G|L"
        assertTrue(parseArtikelResponse(raw).isEmpty())
    }

    @Test
    fun parseArtikelResponse_tooFewFieldsAndExtraBlankLines_skipsInvalidRows() {
        val raw = """
            {GetArtikel}

            123.4567|Artikel A|W1A
            234.5678|Artikel B|W1A|W1B|W1C|W2A|W2B|W2C|ST|10|x|y|z|G|L

            {/GetArtikel}
        """.trimIndent()

        val result = parseArtikelResponse(raw)
        assertEquals(1, result.size)
        assertEquals(0, result.first().empfBestMenge)
        assertEquals(0, result.first().bestellTrigger)
        assertEquals(0, result.first().mindestbestand)
    }
}
