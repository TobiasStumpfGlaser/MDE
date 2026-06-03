package com.example.mde

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigDecimal

class BuchungsHelperTest {

    @Test
    fun parseMengeOrNull_trimsSpacesAndNbsp_andHandlesComma() {
        assertEquals(0, parseMengeOrNull(" 1\u00A0234,5 ")!!.compareTo(BigDecimal("1234.5")))
    }

    @Test
    fun parseMengeOrNull_invalidOrBlank_returnsNull() {
        assertNull(parseMengeOrNull(""))
        assertNull(parseMengeOrNull("   "))
        assertNull(parseMengeOrNull("abc"))
        assertNull(parseMengeOrNull("1.234,5"))
    }

    @Test
    fun formatMengeForServer_formatsGermanWithoutGrouping() {
        assertEquals("5", formatMengeForServer(BigDecimal("5")))
        assertEquals("5,125", formatMengeForServer(BigDecimal("5.125")))
        assertEquals("12345", formatMengeForServer(BigDecimal("12345")))
    }

    @Test
    fun isIntegerValue_validatesWholeAndFractionalValues() {
        assertTrue(isIntegerValue(BigDecimal("7")))
        assertTrue(isIntegerValue(BigDecimal("7.0")))
        assertTrue(isIntegerValue(BigDecimal("-3")))
        assertFalse(isIntegerValue(BigDecimal("7.1")))
        assertFalse(isIntegerValue(BigDecimal("-3.5")))
    }
}
