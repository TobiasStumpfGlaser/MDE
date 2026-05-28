package com.example.mde

/**
 * Pick-Listen-Activity.
 *
 * Zeigt offene Picklisten an und ermöglicht das Kommissionieren der enthaltenen Positionen.
 * Erbt die gesamte Listen- und Buchungslogik von [BasePickDropActivity].
 */
class PickListActivity : BasePickDropActivity() {

    override val overviewCommand     = "GetPickOverview"
    override val detailCommandPrefix = "GetPick_"
    override val actionLabel         = "To pick"
    override val buchungsVorzeichen  = -1
    override val listFilterHint      = "Picklisten Nummer"
}