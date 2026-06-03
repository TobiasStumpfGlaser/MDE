package com.example.mde

import org.junit.Before
import org.junit.Test
import org.junit.Assert.*
import com.example.mde.model.Artikel

/**
 * Tests für das DataRepository-Objekt.
 * Verifiziert die Funktionalität von rememberProjekt, shouldReload, isLoaded und clear.
 */
class DataRepositoryTest {

    @Before
    fun setUp() {
        DataRepository.recentProjektListe.clear()
        DataRepository.artikelListe = emptyList()
        DataRepository.projektListe = emptyList()
        DataRepository.lastLoadTime = 0
    }

    // ── rememberProjekt Tests ──────────────────────────────────────────────

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
    fun rememberProjekt_emptyStringIgnored() {
        DataRepository.recentProjektListe.addAll(listOf("P1"))
        DataRepository.rememberProjekt("")
        assertEquals(listOf("P1"), DataRepository.recentProjektListe)
    }

    @Test
    fun rememberProjekt_maxEntriesRespected() {
        (1..10).forEach { idx -> DataRepository.rememberProjekt("P$idx", maxEntries = 8) }
        assertEquals(8, DataRepository.recentProjektListe.size)
        assertEquals("P10", DataRepository.recentProjektListe.first())
        assertEquals("P3", DataRepository.recentProjektListe.last())
    }

    @Test
    fun rememberProjekt_exactlyMaxEntries_noTruncation() {
        (1..8).forEach { idx -> DataRepository.rememberProjekt("P$idx", maxEntries = 8) }
        assertEquals(8, DataRepository.recentProjektListe.size)
    }

    @Test
    fun rememberProjekt_listStartsEmpty_addsCorrectly() {
        DataRepository.rememberProjekt("P1")
        assertEquals(listOf("P1"), DataRepository.recentProjektListe)
    }

    @Test
    fun rememberProjekt_customMaxEntries3_limitsTo3() {
        DataRepository.rememberProjekt("P1", maxEntries = 3)
        DataRepository.rememberProjekt("P2", maxEntries = 3)
        DataRepository.rememberProjekt("P3", maxEntries = 3)
        DataRepository.rememberProjekt("P4", maxEntries = 3)
        assertEquals(3, DataRepository.recentProjektListe.size)
        assertEquals("P4", DataRepository.recentProjektListe[0])
        assertFalse(DataRepository.recentProjektListe.contains("P1"))
    }

    @Test
    fun rememberProjekt_singleEntry_maxEntries1_limitsTo1() {
        DataRepository.rememberProjekt("P1", maxEntries = 1)
        DataRepository.rememberProjekt("P2", maxEntries = 1)
        assertEquals(1, DataRepository.recentProjektListe.size)
        assertEquals("P2", DataRepository.recentProjektListe[0])
    }

    // ── shouldReload Tests ─────────────────────────────────────────────────

    @Test
    fun shouldReload_emptyLists_returnsTrue() {
        assertTrue(DataRepository.shouldReload())
    }

    @Test
    fun shouldReload_oldTimestamp_returnsTrue() {
        DataRepository.artikelListe = listOf()
        DataRepository.lastLoadTime = System.currentTimeMillis() - (2 * 60 * 60 * 1000L)
        assertTrue(DataRepository.shouldReload())
    }

    @Test
    fun shouldReload_dataLoadedAndFresh_returnsFalse() {
        DataRepository.artikelListe = listOf(Artikel("123.4567", "Test", emptyList(), emptyList(), "ST", "10", 0, 0, 0, "", ""))
        DataRepository.projektListe = listOf("P1 – Projekt 1")
        DataRepository.lastLoadTime = System.currentTimeMillis()
        assertFalse(DataRepository.shouldReload())
    }

    @Test
    fun shouldReload_artikelLoadedProjektEmpty_returnsTrue() {
        DataRepository.artikelListe = listOf(Artikel("123.4567", "Test", emptyList(), emptyList(), "ST", "10", 0, 0, 0, "", ""))
        DataRepository.projektListe = emptyList()
        DataRepository.lastLoadTime = System.currentTimeMillis()
        assertTrue(DataRepository.shouldReload())
    }

    @Test
    fun shouldReload_bothLoadedButStale_returnsTrue() {
        DataRepository.artikelListe = listOf(Artikel("123.4567", "Test", emptyList(), emptyList(), "ST", "10", 0, 0, 0, "", ""))
        DataRepository.projektListe = listOf("P1 – Projekt 1")
        DataRepository.lastLoadTime = System.currentTimeMillis() - (2 * 60 * 60 * 1000L)
        assertTrue(DataRepository.shouldReload())
    }

    // ── isLoaded Tests ─────────────────────────────────────────────────────

    @Test
    fun isLoaded_emptyLists_returnsFalse() {
        assertFalse(DataRepository.isLoaded())
    }

    @Test
    fun isLoaded_bothListsNonEmpty_returnsTrue() {
        DataRepository.artikelListe = listOf(Artikel("123.4567", "Test", emptyList(), emptyList(), "ST", "10", 0, 0, 0, "", ""))
        DataRepository.projektListe = listOf("P1 – Projekt 1")
        assertTrue(DataRepository.isLoaded())
    }

    @Test
    fun isLoaded_artikelEmptyProjektNonEmpty_returnsFalse() {
        DataRepository.artikelListe = emptyList()
        DataRepository.projektListe = listOf("P1 – Projekt 1")
        assertFalse(DataRepository.isLoaded())
    }

    // ── clear Tests ────────────────────────────────────────────────────────

    @Test
    fun clear_resetsAllData() {
        DataRepository.recentProjektListe.addAll(listOf("P1", "P2"))
        DataRepository.artikelListe = listOf(Artikel("123.4567", "Test", emptyList(), emptyList(), "ST", "10", 0, 0, 0, "", ""))
        DataRepository.projektListe = listOf("P1 – Projekt 1")
        DataRepository.clear()
        assertTrue(DataRepository.recentProjektListe.isEmpty())
        assertTrue(DataRepository.artikelListe.isEmpty())
        assertTrue(DataRepository.projektListe.isEmpty())
    }

    // ── Zusätzliche Edge Case Tests ────────────────────────────────────────

    @Test
    fun rememberProjekt_whitespaceOnlyString_ignored() {
        DataRepository.rememberProjekt("P1")
        DataRepository.rememberProjekt("\t\n  ")
        assertEquals(1, DataRepository.recentProjektListe.size)
        assertEquals("P1", DataRepository.recentProjektListe[0])
    }

    @Test
    fun rememberProjekt_multipleAddsSameItem_onlyOneEntry() {
        DataRepository.rememberProjekt("P100")
        DataRepository.rememberProjekt("P100")
        DataRepository.rememberProjekt("P100")
        assertEquals(1, DataRepository.recentProjektListe.size)
        assertEquals("P100", DataRepository.recentProjektListe[0])
    }

    @Test
    fun shouldReload_artikelEmptyProjektLoaded_returnsTrue() {
        DataRepository.artikelListe = emptyList()
        DataRepository.projektListe = listOf("P1 – Projekt 1")
        DataRepository.lastLoadTime = System.currentTimeMillis()
        assertTrue(DataRepository.shouldReload())
    }

    @Test
    fun shouldReload_exactlyOneHour_returnsFalse() {
        DataRepository.artikelListe = listOf(Artikel("123.4567", "Test", emptyList(), emptyList(), "ST", "10", 0, 0, 0, "", ""))
        DataRepository.projektListe = listOf("P1 – Projekt 1")
        DataRepository.lastLoadTime = System.currentTimeMillis() - (60 * 60 * 1000L)
        // Bei genau einer Stunde sollte nicht neu geladen werden (<=, nicht <)
        assertFalse(DataRepository.shouldReload())
    }

    @Test
    fun shouldReload_justOverOneHour_returnsTrue() {
        DataRepository.artikelListe = listOf(Artikel("123.4567", "Test", emptyList(), emptyList(), "ST", "10", 0, 0, 0, "", ""))
        DataRepository.projektListe = listOf("P1 – Projekt 1")
        DataRepository.lastLoadTime = System.currentTimeMillis() - (60 * 60 * 1000L + 1)
        assertTrue(DataRepository.shouldReload())
    }
}
