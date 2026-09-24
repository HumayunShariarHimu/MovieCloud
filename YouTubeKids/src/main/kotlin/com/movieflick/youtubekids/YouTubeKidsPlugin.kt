package com.movieflick.youtubekids

import com.lagradost.cloudstream3.plugins.BasePlugin
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin

@CloudStreamPlugin
class YouTubeKidsPlugin : BasePlugin() {
    override fun load() { registerMainAPI(YouTubeKids()) }
}