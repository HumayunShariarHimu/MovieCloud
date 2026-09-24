package com.movieflick.hianime

import com.lagradost.cloudstream3.plugins.BasePlugin
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin

@CloudstreamPlugin
class HiAnimePlugin : BasePlugin() {

    override fun load() {
        registerMainAPI(HiAnime())
    }
}