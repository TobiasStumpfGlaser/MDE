package com.example.mde

class PickListActivity : BasePickDropActivity() {

    override val overviewCommand     = "GetPickOverview"
    override val detailCommandPrefix = "GetPick_"
    override val actionLabel         = "To pick"
    override val buchungsVorzeichen  = -1
    override val listFilterHint      = "Picklisten Nummer"
}