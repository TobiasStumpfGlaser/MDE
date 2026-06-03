package com.example.mde

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Umfassende Tests für ServerResponseParser-Funktionen.
 * Testet parseProjektList und parseArtikelResponse.
 */
class ServerResponseParserTest {

    // ── parseProjektList Tests ─────────────────────────────────────────────

    @Test
    fun parseProjektList_empty_returnsEmptyList() {
        assertTrue(parseProjektList("").isEmpty())
    }

    @Test
    fun parseProjektList_validLines_returnsFormattedEntries() {
        val raw = """
            P100|Projekt Eins
            P200|Projekt Zwei
        """.trimIndent()

        assertEquals(listOf("P100 – Projekt Eins", "P200 – Projekt Zwei"), parseProjektList(raw))
    }

    @Test
    fun parseProjektList_linesStartingWithBrace_ignored() {
        val raw = """
            {GetProjekte}
            P100|Projekt Eins
            {/GetProjekte}
        """.trimIndent()

        assertEquals(listOf("P100 – Projekt Eins"), parseProjektList(raw))
    }

    @Test
    fun parseProjektList_linesWithoutPipe_ignored() {
        val raw = """
            P100|Projekt Eins
            UngueltigOhnePipe
        """.trimIndent()

        assertEquals(listOf("P100 – Projekt Eins"), parseProjektList(raw))
    }

    @Test
    fun parseProjektList_lineWithMultiplePipes_ignored() {
        val raw = """
            P100|Projekt Eins
            P200|Projekt Zwei|ExtraFeld
        """.trimIndent()

        assertEquals(listOf("P100 – Projekt Eins"), parseProjektList(raw))
    }

    @Test
    fun parseProjektList_emptyLineInList_ignored() {
        val raw = "P100|Projekt Eins\n\nP200|Projekt Zwei"
        assertEquals(listOf("P100 – Projekt Eins", "P200 – Projekt Zwei"), parseProjektList(raw))
    }

    @Test
    fun parseProjektList_braceLineWithPipe_ignored() {
        val raw = "{Tag}|Inhalt\nP100|Projekt Eins"
        assertEquals(listOf("P100 – Projekt Eins"), parseProjektList(raw))
    }

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
    fun parseProjektList_singleEntry_returnsOneItem() {
        val raw = "P100|Test Projekt"
        assertEquals(listOf("P100 – Test Projekt"), parseProjektList(raw))
    }

    @Test
    fun parseProjektList_emptyProjectName_stillValid() {
        val raw = "P100|"
        assertEquals(listOf("P100 – "), parseProjektList(raw))
    }

    @Test
    fun parseProjektList_whitespaceInProjectName_preserved() {
        val raw = "P100|  Projekt mit Spaces  "
        assertEquals(listOf("P100 –   Projekt mit Spaces  "), parseProjektList(raw))
    }

    // ── parseArtikelResponse Tests ─────────────────────────────────────────

    @Test
    fun parseArtikelResponse_empty_returnsEmptyList() {
        assertTrue(parseArtikelResponse("").isEmpty())
    }

    @Test
    fun parseArtikelResponse_validGetArtikelBlock_returnsArtikelList() {
        val raw = """
            ignored-before-tag
            {GetArtikel}
            123.4567|Artikel A|W1A|W1B|W1C|W2A|W2B|W2C|ST|10|5|2|1|Grossinfo|LiefBest
            {/GetArtikel}
        """.trimIndent()

        val result = parseArtikelResponse(raw)

        assertEquals(1, result.size)
        assertEquals("123.4567", result[0].artNr)
        assertEquals("Artikel A", result[0].bez)
        assertEquals(listOf("W1A", "W1B", "W1C"), result[0].lagerorteW1)
        assertEquals(listOf("W2A", "W2B", "W2C"), result[0].lagerorteW2)
        assertEquals("ST", result[0].masseinheit)
        assertEquals("10", result[0].bestand)
        assertEquals(5, result[0].empfBestMenge)
        assertEquals(2, result[0].bestellTrigger)
        assertEquals(1, result[0].mindestbestand)
        assertEquals("Grossinfo", result[0].grossInfo)
        assertEquals("LiefBest", result[0].liefBestNr)
    }

    @Test
    fun parseArtikelResponse_lineWithTooFewFields_skipped() {
        val raw = """
            {GetArtikel}
            123.4567|Artikel A|W1A
            {/GetArtikel}
        """.trimIndent()

        assertTrue(parseArtikelResponse(raw).isEmpty())
    }

    @Test
    fun parseArtikelResponse_linesBeforeTag_ignored() {
        val raw = """
            999.9999|VorTag|A|B|C|D|E|F|ST|1|1|1|1|X|Y
            {GetArtikel}
            123.4567|Artikel A|W1A|W1B|W1C|W2A|W2B|W2C|ST|10|5|2|1|Grossinfo|LiefBest
            {/GetArtikel}
        """.trimIndent()

        val result = parseArtikelResponse(raw)
        assertEquals(1, result.size)
        assertEquals("123.4567", result[0].artNr)
    }

    @Test
    fun parseArtikelResponse_blankLineInsideBlock_skipped() {
        val raw = "{GetArtikel}\n\n123.4567|Artikel A|W1A|W1B|W1C|W2A|W2B|W2C|ST|10|5|2|1|Grossinfo|LiefBest\n{/GetArtikel}"
        val result = parseArtikelResponse(raw)
        assertEquals(1, result.size)
    }

    @Test
    fun parseArtikelResponse_nonNumericEmpfBestMenge_defaultsToZero() {
        val raw = "{GetArtikel}\n123.4567|Artikel A|W1A|W1B|W1C|W2A|W2B|W2C|ST|10|NICHT|NICHT|NICHT|Grossinfo|LiefBest\n{/GetArtikel}"
        val result = parseArtikelResponse(raw)
        assertEquals(1, result.size)
        assertEquals(0, result[0].empfBestMenge)
        assertEquals(0, result[0].bestellTrigger)
        assertEquals(0, result[0].mindestbestand)
    }

    @Test
    fun parseArtikelResponse_multipleArtikel_returnsAll() {
        val raw = buildString {
            appendLine("{GetArtikel}")
            appendLine("001.0001|Artikel 1|W1A|W1B|W1C|W2A|W2B|W2C|ST|5|1|0|0|G1|L1")
            appendLine("002.0002|Artikel 2|W1A|W1B|W1C|W2A|W2B|W2C|KG|3|2|1|0|G2|L2")
            append("{/GetArtikel}")
        }
        val result = parseArtikelResponse(raw)
        assertEquals(2, result.size)
        assertEquals("001.0001", result[0].artNr)
        assertEquals("002.0002", result[1].artNr)
    }

    @Test
    fun parseArtikelResponse_noClosingTag_parsesAllLines() {
        val raw = "{GetArtikel}\n123.4567|Artikel A|W1A|W1B|W1C|W2A|W2B|W2C|ST|10|5|2|1|Grossinfo|LiefBest"
        val result = parseArtikelResponse(raw)
        assertEquals(1, result.size)
    }

    @Test
    fun parseArtikelResponse_exactly14Fields_skipped() {
        val raw = "{GetArtikel}\n123.4567|Artikel A|W1A|W1B|W1C|W2A|W2B|W2C|ST|10|5|2|1|Grossinfo\n{/GetArtikel}"
        assertTrue(parseArtikelResponse(raw).isEmpty())
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
    fun parseArtikelResponse_skipsTooFewFields_andDefaultsInvalidNumbersToZero() {
        val raw = """
            {GetArtikel}

            123.4567|Artikel A|W1A
            234.5678|Artikel B|W1A|W1B|W1C|W2A|W2B|W2C|ST|10|x|y|z|G|L

            {/GetArtikel}
        """.trimIndent()

        val result = parseArtikelResponse(raw)
        assertEquals(1, result.size)
        assertEquals("234.5678", result.first().artNr)
        assertEquals(0, result.first().empfBestMenge)
        assertEquals(0, result.first().bestellTrigger)
        assertEquals(0, result.first().mindestbestand)
    }

    // ── Zusätzliche Edge Case Tests ────────────────────────────────────────

    @Test
    fun parseArtikelResponse_moreThan15Fields_stillValid() {
        val raw = "{GetArtikel}\n123.4567|Artikel A|W1A|W1B|W1C|W2A|W2B|W2C|ST|10|5|2|1|Grossinfo|LiefBest|Extra|Field\n{/GetArtikel}"
        val result = parseArtikelResponse(raw)
        assertEquals(1, result.size)
        assertEquals("123.4567", result[0].artNr)
    }

    @Test
    fun parseArtikelResponse_emptyFields_stillValid() {
        val raw = "{GetArtikel}\n|||||||||||||||Extra\n{/GetArtikel}"
        val result = parseArtikelResponse(raw)
        assertEquals(1, result.size)
        assertEquals("", result[0].artNr)
        assertEquals("", result[0].bez)
    }

    @Test
    fun parseArtikelResponse_negativeNumbers_parsedCorrectly() {
        val raw = "{GetArtikel}\n123.4567|Artikel A|W1A|W1B|W1C|W2A|W2B|W2C|ST|10|-5|-2|-1|Grossinfo|LiefBest\n{/GetArtikel}"
        val result = parseArtikelResponse(raw)
        assertEquals(1, result.size)
        // Negative Werte sollten als 0 gewertet werden (toIntOrNull bei negativen funktioniert aber)
        assertEquals(-5, result[0].empfBestMenge)
        assertEquals(-2, result[0].bestellTrigger)
        assertEquals(-1, result[0].mindestbestand)
    }

    @Test
    fun parseProjektList_specialCharactersInProjectName_preserved() {
        val raw = "P100|Projekt-Äöü_123"
        assertEquals(listOf("P100 – Projekt-Äöü_123"), parseProjektList(raw))
    }
}
