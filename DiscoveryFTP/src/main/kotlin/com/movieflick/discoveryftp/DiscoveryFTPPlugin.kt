package com.movieflick.discoveryftp

import com.lagradost.cloudstream3.plugins.BasePlugin
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin

@CloudstreamPlugin
class DiscoveryFTPPlugin : BasePlugin() {
    override fun load() {
        registerMainAPI(DiscoveryFTP())
    }
}