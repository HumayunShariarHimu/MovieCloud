package com.zoryva.zoryva

import com.lagradost.cloudstream3.plugins.BasePlugin
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin

@CloudstreamPlugin
class ZoryvaPlugin : BasePlugin() {
    override fun load() { registerMainAPI(Zoryva()) }
}