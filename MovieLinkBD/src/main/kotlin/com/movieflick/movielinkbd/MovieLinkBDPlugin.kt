package com.movieflick.movielinkbd

import com.lagradost.cloudstream3.plugins.BasePlugin
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin

@CloudstreamPlugin
class MovieLinkBDPlugin : BasePlugin() {
    override fun load() { registerMainAPI(MovieLinkBD()) }
}