package com.example.mde

import org.junit.Test
import java.math.BigDecimal

import org.junit.Assert.*
import org.junit.Before

/**
 * Example local unit test, which will execute on the development machine (host).
 *
 * See [testing documentation](http://d.android.com/tools/testing).
 */
class ExampleUnitTest {
    @Test
    fun addition_isCorrect() {
        assertEquals(4, 2 + 2)
    }
}

class ArtNrHelperTest {

    // isFullArtNr — valid patterns
    @Test fun fullArtNr_validFormat_returnsTrue() = assertTrue(isFullArtNr("123.4567"))
    @Test fun fullArtNr_leadingZeros_returnsTrue() = assertTrue(isFullArtNr("000.0000"))
    @Test fun fullArtNr_nineNines_returnsTrue()    = assertTrue(isFullArtNr("999.9999"))

    // isFullArtNr — invalid patterns
    @Test fun fullArtNr_tooShort_returnsFalse()       = assertFalse(isFullArtNr("12.4567"))
    @Test fun fullArtNr_tooLong_returnsFalse()         = assertFalse(isFullArtNr("1234.4567"))
    @Test fun fullArtNr_noDot_returnsFalse()           = assertFalse(isFullArtNr("12345678"))
    @Test fun fullArtNr_wrongDotPos_returnsFalse()     = assertFalse(isFullArtNr("1234.567"))
    @Test fun fullArtNr_letter_returnsFalse()          = assertFalse(isFullArtNr("12A.4567"))
    @Test fun fullArtNr_empty_returnsFalse()           = assertFalse(isFullArtNr(""))
    @Test fun fullArtNr_partialMatch_returnsFalse()    = assertFalse(isFullArtNr("123.456"))
    @Test fun fullArtNr_withSpaces_returnsFalse()      = assertFalse(isFullArtNr(" 23.4567"))
    @Test fun fullArtNr_trailingSpace_returnsFalse()   = assertFalse(isFullArtNr("123.4567 "))

    // isArtNrExactMatch — matching
    @Test fun exactMatch_sameCase_returnsTrue()       = assertTrue(isArtNrExactMatch("123.4567", "123.4567"))
    @Test fun exactMatch_upperInput_returnsTrue()     = assertTrue(isArtNrExactMatch("ABC.DEFG", "abc.defg"))
    @Test fun exactMatch_trailingSpace_returnsTrue()  = assertTrue(isArtNrExactMatch("123.4567", "123.4567 "))
    @Test fun exactMatch_leadingSpace_returnsTrue()   = assertTrue(isArtNrExactMatch("123.4567", " 123.4567"))

    // isArtNrExactMatch — not matching
    @Test fun exactMatch_different_returnsFalse()     = assertFalse(isArtNrExactMatch("123.4567", "999.9999"))
    @Test fun exactMatch_partial_returnsFalse()       = assertFalse(isArtNrExactMatch("123.4567", "123.456"))
    @Test fun exactMatch_empty_returnsFalse()         = assertFalse(isArtNrExactMatch("123.4567", ""))
}

class BuchungsHelperTest {

    @Test
    fun parseMengeOrNull_emptyString_returnsNull() {
        assertNull(parseMengeOrNull(""))
    }

    @Test
    fun parseMengeOrNull_whitespaceOnly_returnsNull() {
        assertNull(parseMengeOrNull("   "))
    }

    @Test
    fun parseMengeOrNull_integer_returnsBigDecimal() {
        assertEquals(0, parseMengeOrNull("5")?.compareTo(BigDecimal("5")))
    }

    @Test
    fun parseMengeOrNull_commaDecimal_returnsBigDecimal() {
        assertEquals(0, parseMengeOrNull("5,1")?.compareTo(BigDecimal("5.1")))
    }

    @Test
    fun parseMengeOrNull_dotDecimal_returnsBigDecimal() {
        assertEquals(0, parseMengeOrNull("5.1")?.compareTo(BigDecimal("5.1")))
    }

    @Test
    fun parseMengeOrNull_nbspRemoved_returnsBigDecimal() {
        assertEquals(0, parseMengeOrNull("5\u00A01")?.compareTo(BigDecimal("51")))
    }

    @Test
    fun parseMengeOrNull_spacesRemoved_returnsBigDecimal() {
        assertEquals(0, parseMengeOrNull("5 0")?.compareTo(BigDecimal("50")))
    }

    @Test
    fun parseMengeOrNull_invalid_returnsNull() {
        assertNull(parseMengeOrNull("abc"))
    }

    @Test
    fun formatMengeForServer_integer_returnsNoDecimal() {
        assertEquals("5", formatMengeForServer(BigDecimal("5")))
    }

    @Test
    fun formatMengeForServer_decimal_returnsGermanComma() {
        assertEquals("5,1", formatMengeForServer(BigDecimal("5.1")))
    }

    @Test
    fun formatMengeForServer_largeNumber_hasNoGrouping() {
        assertEquals("1234567890123456", formatMengeForServer(BigDecimal("1234567890123456")))
    }

    @Test
    fun formatMengeForServer_hasNoThousandsSeparator() {
        assertFalse(formatMengeForServer(BigDecimal("1000")).contains("."))
    }

    @Test
    fun isIntegerValue_whole_returnsTrue() {
        assertTrue(isIntegerValue(BigDecimal("5")))
    }

    @Test
    fun isIntegerValue_wholeWithOneDecimalZero_returnsTrue() {
        assertTrue(isIntegerValue(BigDecimal("5.0")))
    }

    @Test
    fun isIntegerValue_wholeWithTwoDecimalZeros_returnsTrue() {
        assertTrue(isIntegerValue(BigDecimal("5.00")))
    }

    @Test
    fun isIntegerValue_decimal_returnsFalse() {
        assertFalse(isIntegerValue(BigDecimal("5.1")))
    }

    @Test
    fun isIntegerValue_zero_returnsTrue() {
        assertTrue(isIntegerValue(BigDecimal("0")))
    }
}

class ServerResponseParserTest {

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
}

class DataRepositoryTest {

    @Before
    fun setUp() {
        DataRepository.recentProjektListe.clear()
    }

    @Test
    fun rememberProjekt_addsToFront() {
        DataRepository.recentProjektListe.addAll(listOf("P2", "P3"))

        DataRepository.rememberProjekt("P1")

        assertEquals(listOf("P1", "P2", "P3"), DataRepository.recentProjektListe)
    }

    @Test
    fun rememberProjekt_duplicateMovedToFront_notDuplicated() {
        DataRepository.recentProjektListe.addAll(listOf("P2", "P1", "P3"))

        DataRepository.rememberProjekt("P1")

        assertEquals(listOf("P1", "P2", "P3"), DataRepository.recentProjektListe)
    }

    @Test
    fun rememberProjekt_blankIgnored() {
        DataRepository.recentProjektListe.addAll(listOf("P1", "P2"))

        DataRepository.rememberProjekt("   ")

        assertEquals(listOf("P1", "P2"), DataRepository.recentProjektListe)
    }

    @Test
    fun rememberProjekt_maxEntriesRespected() {
        (1..10).forEach { idx -> DataRepository.rememberProjekt("P$idx", maxEntries = 8) }

        assertEquals(8, DataRepository.recentProjektListe.size)
        assertEquals("P10", DataRepository.recentProjektListe.first())
        assertEquals("P3", DataRepository.recentProjektListe.last())
    }
}