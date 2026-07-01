package com.example.mde

import com.example.mde.model.Artikel

internal fun parseProjektList(raw: String): List<String> {
    val list = mutableListOf<String>()
    raw.lines().forEach {
        val parts = it.split("|")
        if (parts.size == 2 && !it.startsWith("{")) list.add("${parts[0]} – ${parts[1]}")
    }
    return list
}

internal fun parseArtikelResponse(raw: String): List<Artikel> {
    val liste = mutableListOf<Artikel>()
    var parse = false
    raw.lines().forEach { line ->
        when {
            line.contains("{GetArtikel}") -> parse = true
            line.contains("{/GetArtikel}") -> return@forEach
            !parse || line.isBlank() -> return@forEach
            else -> {
                val p = line.split("|")
                if (p.size < 21) return@forEach
                liste.add(
                    Artikel(
                        artNr = p[0],
                        bez = p[1],
                        lagerorteW1 = p.subList(2, 5),
                        lagerorteW2 = p.subList(5, 8),
                        masseinheit = p[8],
                        bestand = p[9],
                        empfBestMenge = p[10].toIntOrNull() ?: 0,
                        bestellTrigger = p[11].toIntOrNull() ?: 0,
                        mindestbestand = p[12].toIntOrNull() ?: 0,
                        grossInfo = p[13],
                        handLager = !p[14].isNullOrBlank(),
                        snPflicht = !p[15].isNullOrBlank(),
                        bestellt3M = p[16].toIntOrNull() ?: 0,
                        bestellt6M = p[17].toIntOrNull() ?: 0,
                        EAN = p[18],
                        suchZusatz = p[19],
                        liefBestNr = p[20],
                    )
                )
            }
        }
    }
    return liste
}
