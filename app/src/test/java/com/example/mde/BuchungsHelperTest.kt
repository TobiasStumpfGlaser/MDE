package com.example.mde

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigDecimal

/**
 * Umfassende Tests für BuchungsHelper-Funktionen.
 * Testet parseMengeOrNull, formatMengeForServer und isIntegerValue.
 */
class BuchungsHelperTest {

    // ── parseMengeOrNull Tests ─────────────────────────────────────────────

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
    fun parseMengeOrNull_trimsSpacesAndNbsp_andHandlesComma() {
        assertEquals(0, parseMengeOrNull(" 1\u00A0234,5 ")!!.compareTo(BigDecimal("1234.5")))
    }

    @Test
    fun parseMengeOrNull_invalidOrBlank_returnsNull() {
        assertNull(parseMengeOrNull(""))
        assertNull(parseMengeOrNull("   "))
        assertNull(parseMengeOrNull("abc"))
        // Tausendertrennzeichen werden in parseMengeOrNull nicht unterstützt.
        assertNull(parseMengeOrNull("1.234,5"))
    }

    @Test
    fun parseMengeOrNull_onlyNbsp_returnsNull() {
        assertNull(parseMengeOrNull("\u00A0\u00A0"))
    }

    @Test
    fun parseMengeOrNull_commaAndDot_returnsNull() {
        assertNull(parseMengeOrNull("1.234,5"))
    }

    @Test
    fun parseMengeOrNull_negativeValue_returnsBigDecimal() {
        assertEquals(0, parseMengeOrNull("-5")?.compareTo(BigDecimal("-5")))
    }

    @Test
    fun parseMengeOrNull_negativeWithComma_returnsBigDecimal() {
        assertEquals(0, parseMengeOrNull("-5,5")?.compareTo(BigDecimal("-5.5")))
    }

    @Test
    fun parseMengeOrNull_zero_returnsBigDecimal() {
        assertEquals(0, parseMengeOrNull("0")?.compareTo(BigDecimal("0")))
    }

    @Test
    fun parseMengeOrNull_leadingZeros_returnsBigDecimal() {
        assertEquals(0, parseMengeOrNull("005")?.compareTo(BigDecimal("5")))
    }

    @Test
    fun parseMengeOrNull_multipleSpaces_returnsCorrectValue() {
        assertEquals(0, parseMengeOrNull(" 1 2 3 ")?.compareTo(BigDecimal("123")))
    }

    // ── formatMengeForServer Tests ─────────────────────────────────────────

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
    fun formatMengeForServer_formatsGermanWithoutGrouping() {
        assertEquals("5", formatMengeForServer(BigDecimal("5")))
        assertEquals("5,125", formatMengeForServer(BigDecimal("5.125")))
        assertEquals("12345", formatMengeForServer(BigDecimal("12345")))
    }

    @Test
    fun formatMengeForServer_zero_returnsZero() {
        assertEquals("0", formatMengeForServer(BigDecimal("0")))
    }

    @Test
    fun formatMengeForServer_negativeDecimal_returnsGermanComma() {
        assertEquals("-1,5", formatMengeForServer(BigDecimal("-1.5")))
    }

    @Test
    fun formatMengeForServer_negativeInteger_returnsInteger() {
        assertEquals("-10", formatMengeForServer(BigDecimal("-10")))
    }

    @Test
    fun formatMengeForServer_verySmallDecimal_formatsCorrectly() {
        assertEquals("0,001", formatMengeForServer(BigDecimal("0.001")))
    }

    @Test
    fun formatMengeForServer_manyDecimalPlaces_formatsCorrectly() {
        assertEquals("1,123456789", formatMengeForServer(BigDecimal("1.123456789")))
    }

    // ── isIntegerValue Tests ───────────────────────────────────────────────

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

    @Test
    fun isIntegerValue_negativeWhole_returnsTrue() {
        assertTrue(isIntegerValue(BigDecimal("-3")))
    }

    @Test
    fun isIntegerValue_negativeDecimal_returnsFalse() {
        assertFalse(isIntegerValue(BigDecimal("-3.5")))
    }

    @Test
    fun isIntegerValue_validatesWholeAndFractionalValues() {
        assertTrue(isIntegerValue(BigDecimal("7")))
        assertTrue(isIntegerValue(BigDecimal("7.0")))
        assertTrue(isIntegerValue(BigDecimal("-3")))
        assertFalse(isIntegerValue(BigDecimal("7.1")))
        assertFalse(isIntegerValue(BigDecimal("-3.5")))
    }

    @Test
    fun isIntegerValue_largeWhole_returnsTrue() {
        assertTrue(isIntegerValue(BigDecimal("999999999")))
    }

    @Test
    fun isIntegerValue_verySmallDecimal_returnsFalse() {
        assertFalse(isIntegerValue(BigDecimal("0.001")))
    }

    @Test
    fun isIntegerValue_trailingZeros_returnsTrue() {
        assertTrue(isIntegerValue(BigDecimal("100.000")))
    }
}
