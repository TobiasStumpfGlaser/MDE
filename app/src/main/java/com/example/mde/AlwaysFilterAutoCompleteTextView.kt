package com.example.mde

import android.content.Context
import android.util.AttributeSet
import androidx.appcompat.widget.AppCompatAutoCompleteTextView

/**
 * [AppCompatAutoCompleteTextView]-Unterklasse, die den Dropdown unabhängig von der
 * aktuellen Eingabelänge immer anzeigt.
 *
 * Überschreibt [enoughToFilter] mit `true`, sodass die Vorschlagsliste auch bei
 * leerer Eingabe sofort geöffnet werden kann.
 */
class AlwaysFilterAutoCompleteTextView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = androidx.appcompat.R.attr.autoCompleteTextViewStyle
) : AppCompatAutoCompleteTextView(context, attrs, defStyleAttr) {

    override fun enoughToFilter(): Boolean = true
}
