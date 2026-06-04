package com.example.mde

/**
 * Drop-Listen-Activity.
 *
 * Zeigt offene Droplisten an und ermöglicht das Abarbeiten der enthaltenen Positionen.
 * Erbt die gesamte Listen- und Buchungslogik von [BasePickDropActivity].
 */
class DropListActivity : BasePickDropActivity() {

    override val overviewCommand     = "GetDropOverview"
    override val detailCommandPrefix = "GetDrop_"
    override val actionLabel         = "To drop"
    override val buchungsVorzeichen  = +1
    override val listFilterHint      = "Droplisten Nummer"
}