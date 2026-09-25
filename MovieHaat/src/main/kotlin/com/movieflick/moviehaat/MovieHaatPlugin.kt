package com.movieflick.moviehaat

import com.lagradost.cloudstream3.plugins.BasePlugin
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin

@CloudstreamPlugin
class MovieHaatPlugin : BasePlugin() {
    override fun load() {
        registerMainAPI(MovieHaat())
    }
}
