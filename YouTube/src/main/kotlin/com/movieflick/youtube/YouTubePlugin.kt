package com.movieflick.youtube

import com.lagradost.cloudstream3.plugins.BasePlugin
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin

@CloudstreamPlugin
class YouTubePlugin : BasePlugin() {
    override fun load() { registerMainAPI(YouTube()) }
}