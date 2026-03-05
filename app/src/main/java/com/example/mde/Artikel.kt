package com.example.mde.model

data class Artikel(
    val artNr: String,
    val bez: String,
    val lagerorte: List<String>,
    val masseinheit: String,
    val bestand: Int,
    val empfBestMenge: Int,
    val bestellTrigger: Int,
    val mindestbestand: Int,
    val grossInfo: String,
    val liefBestNr: String
)