package com.movieflick.ctgftp

import com.lagradost.cloudstream3.plugins.BasePlugin
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin

@CloudstreamPlugin
class CTGFTPPlugin : BasePlugin() {
    override fun load() {
        registerMainAPI(CTGFTP())
    }
}