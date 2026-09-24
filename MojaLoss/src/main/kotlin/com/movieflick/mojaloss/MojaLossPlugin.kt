package com.movieflick.mojaloss

import com.lagradost.cloudstream3.plugins.BasePlugin
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin

@CloudstreamPlugin
class MojaLossPlugin : BasePlugin() {
    override fun load() {
        registerMainAPI(MojaLoss())
    }
}