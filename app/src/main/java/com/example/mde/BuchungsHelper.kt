package com.example.mde

import java.math.BigDecimal
import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.util.Locale

internal fun parseMengeOrNull(raw: String): BigDecimal? {
    val s = raw.trim()
        .replace(" ", "")
        .replace("\u00A0", "")
        .replace(",", ".")
    if (s.isBlank()) return null
    return try {
        BigDecimal(s)
    } catch (_: Exception) {
        null
    }
}

internal fun formatMengeForServer(value: BigDecimal): String {
    val symbols = DecimalFormatSymbols(Locale.GERMANY).apply {
        decimalSeparator = ','
        groupingSeparator = '.'
    }
    val df = DecimalFormat("0.################", symbols).apply {
        isGroupingUsed = false
    }
    return df.format(value)
}

internal fun isIntegerValue(value: BigDecimal): Boolean {
    return try {
        value.stripTrailingZeros().scale() <= 0
    } catch (_: Exception) {
        false
    }
}
