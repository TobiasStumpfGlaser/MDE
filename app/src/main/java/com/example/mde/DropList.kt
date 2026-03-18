package com.example.mde

class DropListActivity : BasePickDropActivity() {

    override val overviewCommand     = "GetDropOverview"
    override val detailCommandPrefix = "GetDrop_"
    override val actionLabel         = "To drop"
    override val buchungsVorzeichen  = 1
    override val listFilterHint      = "Droplisten Nummer"
}