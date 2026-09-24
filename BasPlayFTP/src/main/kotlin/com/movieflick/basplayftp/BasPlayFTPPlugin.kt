package com.movieflick.basplayftp

import com.lagradost.cloudstream3.plugins.BasePlugin
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin

@CloudstreamPlugin
class BasPlayFTPPlugin : BasePlugin() {
    override fun load() {
        registerMainAPI(BasPlayFTP())
    }
}