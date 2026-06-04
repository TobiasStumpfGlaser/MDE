package com.example.mde

import org.junit.Test
import org.junit.Assert.*

/**
 * Tests für Art isFullArtNr und isArtNrExactMatch Funktionen.
 * Diese Funktionen sind in BasePickDropActivity.kt definiert.
 */
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

    // Additional edge cases for isFullArtNr
    @Test fun fullArtNr_specialChars_returnsFalse()    = assertFalse(isFullArtNr("123.456!"))
    @Test fun fullArtNr_negativeName_returnsFalse()    = assertFalse(isFullArtNr("-23.4567"))
    @Test fun fullArtNr_multipleDots_returnsFalse()    = assertFalse(isFullArtNr("12.3.4567"))
    @Test fun fullArtNr_onlyDots_returnsFalse()        = assertFalse(isFullArtNr("......."))

    // isArtNrExactMatch — matching
    @Test fun exactMatch_sameCase_returnsTrue()       = assertTrue(isArtNrExactMatch("123.4567", "123.4567"))
    @Test fun exactMatch_upperInput_returnsTrue()     = assertTrue(isArtNrExactMatch("ABC.DEFG", "abc.defg"))
    @Test fun exactMatch_trailingSpace_returnsTrue()  = assertTrue(isArtNrExactMatch("123.4567", "123.4567 "))
    @Test fun exactMatch_leadingSpace_returnsTrue()   = assertTrue(isArtNrExactMatch("123.4567", " 123.4567"))

    // isArtNrExactMatch — not matching
    @Test fun exactMatch_different_returnsFalse()     = assertFalse(isArtNrExactMatch("123.4567", "999.9999"))
    @Test fun exactMatch_partial_returnsFalse()       = assertFalse(isArtNrExactMatch("123.4567", "123.456"))
    @Test fun exactMatch_empty_returnsFalse()         = assertFalse(isArtNrExactMatch("123.4567", ""))

    // Additional edge cases for isArtNrExactMatch
    @Test fun exactMatch_bothSpaces_returnsTrue()     = assertTrue(isArtNrExactMatch(" 123.4567 ", " 123.4567 "))
    @Test fun exactMatch_mixedCase_returnsTrue()      = assertTrue(isArtNrExactMatch("AbC.dEf gH", "aBc.DeF Gh"))
    @Test fun exactMatch_emptyBoth_returnsTrue()      = assertTrue(isArtNrExactMatch("", ""))
    @Test fun exactMatch_onlySpaces_returnsFalse()    = assertFalse(isArtNrExactMatch("123.4567", "   "))
}
