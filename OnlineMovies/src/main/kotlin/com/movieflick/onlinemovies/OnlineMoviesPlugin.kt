package com.movieflick.onlinemovies

import com.lagradost.cloudstream3.plugins.BasePlugin
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin

@CloudstreamPlugin
class OnlineMoviesPlugin : BasePlugin() {
    override fun load() { registerMainAPI(OnlineMovies()) }
}