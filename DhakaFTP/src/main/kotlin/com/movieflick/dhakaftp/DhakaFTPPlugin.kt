package com.movieflick.dhakaftp

import com.lagradost.cloudstream3.plugins.BasePlugin
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin

@CloudstreamPlugin
class DhakaFTPPlugin : BasePlugin() {

    override fun load() {
        registerMainAPI(DhakaFTP())
    }
}