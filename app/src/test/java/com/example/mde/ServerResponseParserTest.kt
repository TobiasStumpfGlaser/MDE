package com.example.mde

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ServerResponseParserTest {

    // ─────────────────────────────────────────────
    // parseProjektList
    // ─────────────────────────────────────────────

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

        assertEquals(
            listOf("P100 – Projekt Eins", "P200 – Projekt Zwei"),
            parseProjektList(raw)
        )
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
    fun parseProjektList_lineWithMultiplePipes_ignored() {
        val raw = """
            P100|Projekt Eins
            P200|Projekt Zwei|Extra
        """.trimIndent()

        assertEquals(listOf("P100 – Projekt Eins"), parseProjektList(raw))
    }

    @Test
    fun parseProjektList_specialCharacters_preserved() {
        val raw = "P100|Projekt-Äöü_123"
        assertEquals(listOf("P100 – Projekt-Äöü_123"), parseProjektList(raw))
    }

    // ─────────────────────────────────────────────
    // parseArtikelResponse
    // ─────────────────────────────────────────────

    private fun artikelLine(
        artNr: String = "123.4567",
        bez: String = "Artikel A",
        w1a: String = "W1A",
        w1b: String = "W1B",
        w1c: String = "W1C",
        w2a: String = "W2A",
        w2b: String = "W2B",
        w2c: String = "W2C",
        me: String = "ST",
        bestand: String = "10",
        empf: String = "5",
        trigger: String = "2",
        min: String = "1",
        gross: String = "Grossinfo",
        bestellt3M: Int = 6,
        bestellt6M: Int = 10,
        lief: String = "LiefBest",
        sn: String = "true",
        ean: String = "4001234567890",
        such: String = "TEST"
    ): String {
        return listOf(
            artNr, bez,
            w1a, w1b, w1c,
            w2a, w2b, w2c,
            me, bestand,
            empf, trigger, min,
            gross, sn, bestellt3M, bestellt6M,
            ean, such, lief
        ).joinToString("|")
    }

    @Test
    fun parseArtikelResponse_empty_returnsEmptyList() {
        assertTrue(parseArtikelResponse("").isEmpty())
    }

    @Test
    fun parseArtikelResponse_validGetArtikelBlock_returnsArtikelList() {
        val raw = """
            ignored-before-tag
            {GetArtikel}
            ${artikelLine()}
            {/GetArtikel}
        """.trimIndent()

        val result = parseArtikelResponse(raw)

        assertEquals(1, result.size)

        val artikel = result.first()
        assertEquals("123.4567", artikel.artNr)
        assertEquals("Artikel A", artikel.bez)
        assertEquals(listOf("W1A", "W1B", "W1C"), artikel.lagerorteW1)
        assertEquals(listOf("W2A", "W2B", "W2C"), artikel.lagerorteW2)
        assertEquals("ST", artikel.masseinheit)
        assertEquals("10", artikel.bestand)
        assertEquals(5, artikel.empfBestMenge)
        assertEquals(2, artikel.bestellTrigger)
        assertEquals(1, artikel.mindestbestand)
        assertEquals("Grossinfo", artikel.grossInfo)
        assertEquals("LiefBest", artikel.liefBestNr)
        assertTrue(artikel.snPflicht)
        assertEquals("4001234567890", artikel.EAN)
        assertEquals("TEST", artikel.suchZusatz)
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
            ${artikelLine(artNr = "999.9999")}
            {GetArtikel}
            ${artikelLine()}
            {/GetArtikel}
        """.trimIndent()

        val result = parseArtikelResponse(raw)

        assertEquals(1, result.size)
        assertEquals("123.4567", result.first().artNr)
    }

    @Test
    fun parseArtikelResponse_blankLineInsideBlock_skipped() {
        val raw = """
            {GetArtikel}

            ${artikelLine()}

            {/GetArtikel}
        """.trimIndent()

        val result = parseArtikelResponse(raw)
        assertEquals(1, result.size)
    }

    @Test
    fun parseArtikelResponse_nonNumericEmpfBestMenge_defaultsToZero() {
        val raw = """
            {GetArtikel}
            ${artikelLine(empf = "X", trigger = "Y", min = "Z")}
            {/GetArtikel}
        """.trimIndent()

        val result = parseArtikelResponse(raw)

        assertEquals(1, result.size)
        val artikel = result.first()
        assertEquals(0, artikel.empfBestMenge)
        assertEquals(0, artikel.bestellTrigger)
        assertEquals(0, artikel.mindestbestand)
    }

    @Test
    fun parseArtikelResponse_multipleArtikel_returnsAll() {
        val raw = buildString {
            appendLine("{GetArtikel}")
            appendLine(artikelLine(artNr = "001.0001", bez = "Artikel 1"))
            appendLine(artikelLine(artNr = "002.0002", bez = "Artikel 2"))
            append("{/GetArtikel}")
        }

        val result = parseArtikelResponse(raw)

        assertEquals(2, result.size)
        assertEquals("001.0001", result[0].artNr)
        assertEquals("002.0002", result[1].artNr)
    }

    @Test
    fun parseArtikelResponse_noClosingTag_parsesAllLines() {
        val raw = """
            {GetArtikel}
            ${artikelLine()}
        """.trimIndent()

        val result = parseArtikelResponse(raw)
        assertEquals(1, result.size)
    }

    @Test
    fun parseArtikelResponse_exactly17Fields_skipped() {
        val raw = """
            {GetArtikel}
            123.4567|Artikel A|W1A|W1B|W1C|W2A|W2B|W2C|ST|10|5|2|1|Grossinfo|LiefBest|true|4001234567890
            {/GetArtikel}
        """.trimIndent()

        assertTrue(parseArtikelResponse(raw).isEmpty())
    }

    @Test
    fun parseArtikelResponse_validBlock_extractsAllFields() {
        val raw = """
            prefix
            {GetArtikel}

            ${artikelLine()}

            {/GetArtikel}
            suffix
        """.trimIndent()

        val result = parseArtikelResponse(raw)

        assertEquals(1, result.size)

        val artikel = result.first()
        assertEquals("123.4567", artikel.artNr)
        assertEquals("Artikel A", artikel.bez)
        assertEquals(5, artikel.empfBestMenge)
        assertTrue(artikel.snPflicht)
        assertEquals("4001234567890", artikel.EAN)
        assertEquals("TEST", artikel.suchZusatz)
    }

    @Test
    fun parseArtikelResponse_missingTags_returnsEmpty() {
        val raw = artikelLine()
        assertTrue(parseArtikelResponse(raw).isEmpty())
    }

    @Test
    fun parseArtikelResponse_skipsTooFewFields_andDefaultsInvalidNumbersToZero() {
        val raw = """
            {GetArtikel}

            123.4567|Artikel A|W1A
            ${artikelLine(artNr = "234.5678", bez = "Artikel B", empf = "x", trigger = "y", min = "z")}

            {/GetArtikel}
        """.trimIndent()

        val result = parseArtikelResponse(raw)

        assertEquals(1, result.size)

        val artikel = result.first()
        assertEquals("234.5678", artikel.artNr)
        assertEquals(0, artikel.empfBestMenge)
        assertEquals(0, artikel.bestellTrigger)
        assertEquals(0, artikel.mindestbestand)
    }

    // ─────────────────────────────────────────────
    // Edge Cases
    // ─────────────────────────────────────────────

    @Test
    fun parseArtikelResponse_moreThan18Fields_stillValid() {
        val raw = """
            {GetArtikel}
            ${artikelLine()}|Extra|Field
            {/GetArtikel}
        """.trimIndent()

        val result = parseArtikelResponse(raw)

        assertEquals(1, result.size)
        assertEquals("123.4567", result.first().artNr)
    }

    @Test
    fun parseArtikelResponse_emptyFields_stillValid() {
        val raw = """
            {GetArtikel}
            |||||||||||||||||||
            {/GetArtikel}
        """.trimIndent()

        val result = parseArtikelResponse(raw)

        assertEquals(1, result.size)

        val artikel = result.first()
        assertEquals("", artikel.artNr)
        assertEquals("", artikel.bez)
    }

    @Test
    fun parseArtikelResponse_negativeNumbers_parsedCorrectly() {
        val raw = """
            {GetArtikel}
            ${artikelLine(empf = "-5", trigger = "-2", min = "-1")}
            {/GetArtikel}
        """.trimIndent()

        val result = parseArtikelResponse(raw)

        assertEquals(1, result.size)

        val artikel = result.first()
        assertEquals(-5, artikel.empfBestMenge)
        assertEquals(-2, artikel.bestellTrigger)
        assertEquals(-1, artikel.mindestbestand)
    }
}