package com.example.mde.model

data class Artikel(
    val artNr: String,
    val bez: String,
    val lagerorteW1: List<String>,
    val lagerorteW2: List<String>,
    val masseinheit: String,
    val bestand: String,
    val empfBestMenge: Int,
    val bestellTrigger: Int,
    val mindestbestand: Int,
    val grossInfo: String,
    val liefBestNr: String
)