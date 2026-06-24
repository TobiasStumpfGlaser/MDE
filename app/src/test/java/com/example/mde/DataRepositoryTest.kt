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

    private fun testArtikel(
        artNr: String = "123.4567",
        bez: String = "Test"
    ) = Artikel(
        artNr = artNr,
        bez = bez,
        lagerorteW1 = emptyList(),
        lagerorteW2 = emptyList(),
        masseinheit = "ST",
        bestand = "10",
        mindestbestand = 0,
        empfBestMenge = 0,
        bestellTrigger = 0,
        grossInfo = "",
        suchZusatz = "",
        EAN = "",
        snPflicht = false,
        liefBestNr = ""
    )

    @Before
    fun setUp() {
        DataRepository.recentProjektListe.clear()
        DataRepository.artikelListe = emptyList()
        DataRepository.projektListe = emptyList()
        DataRepository.lastLoadTime = 0
    }

    // ───────── rememberProjekt ─────────

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
        DataRepository.rememberProjekt("   ")
        assertTrue(DataRepository.recentProjektListe.isEmpty())
    }

    @Test
    fun rememberProjekt_maxEntriesRespected() {
        (1..10).forEach { DataRepository.rememberProjekt("P$it", maxEntries = 8) }

        assertEquals(8, DataRepository.recentProjektListe.size)
        assertEquals("P10", DataRepository.recentProjektListe.first())
        assertEquals("P3", DataRepository.recentProjektListe.last())
    }

    @Test
    fun rememberProjekt_customMaxEntries3_limitsTo3() {
        (1..4).forEach { DataRepository.rememberProjekt("P$it", maxEntries = 3) }

        assertEquals(3, DataRepository.recentProjektListe.size)
        assertEquals("P4", DataRepository.recentProjektListe.first())
    }

    @Test
    fun rememberProjekt_duplicateMultipleTimes_onlyOnce() {
        repeat(3) { DataRepository.rememberProjekt("P100") }
        assertEquals(listOf("P100"), DataRepository.recentProjektListe)
    }

    // ───────── shouldReload ─────────

    @Test
    fun shouldReload_emptyLists_returnsTrue() {
        assertTrue(DataRepository.shouldReload())
    }

    @Test
    fun shouldReload_freshData_returnsFalse() {
        DataRepository.artikelListe = listOf(testArtikel())
        DataRepository.projektListe = listOf("P1")
        DataRepository.lastLoadTime = System.currentTimeMillis()

        assertFalse(DataRepository.shouldReload())
    }

    @Test
    fun shouldReload_staleData_returnsTrue() {
        DataRepository.artikelListe = listOf(testArtikel())
        DataRepository.projektListe = listOf("P1")
        DataRepository.lastLoadTime = System.currentTimeMillis() - (2 * 60 * 60 * 1000L)

        assertTrue(DataRepository.shouldReload())
    }

    @Test
    fun shouldReload_exactOneHour_returnsFalse() {
        DataRepository.artikelListe = listOf(testArtikel())
        DataRepository.projektListe = listOf("P1")
        DataRepository.lastLoadTime = System.currentTimeMillis() - (60 * 60 * 1000L)

        assertFalse(DataRepository.shouldReload())
    }

    // ───────── isLoaded ─────────

    @Test
    fun isLoaded_bothEmpty_returnsFalse() {
        assertFalse(DataRepository.isLoaded())
    }

    @Test
    fun isLoaded_bothFilled_returnsTrue() {
        DataRepository.artikelListe = listOf(testArtikel())
        DataRepository.projektListe = listOf("P1")

        assertTrue(DataRepository.isLoaded())
    }

    // ───────── clear ─────────

    @Test
    fun clear_resetsAllData() {
        DataRepository.recentProjektListe.add("P1")
        DataRepository.artikelListe = listOf(testArtikel())
        DataRepository.projektListe = listOf("P1")

        DataRepository.clear()

        assertTrue(DataRepository.recentProjektListe.isEmpty())
        assertTrue(DataRepository.artikelListe.isEmpty())
        assertTrue(DataRepository.projektListe.isEmpty())
    }
}
