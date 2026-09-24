package com.movieflick.moviebox

import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.TvType

/**
 * Build-time placeholder only.
 *
 * The published MovieBox.cs3 is replaced by the proven MovieBoxProvider
 * artifact by the repository build workflow.
 */
class MovieBox : MainAPI() {
    override var mainUrl: String = "https://movieboxonline.net"
    override var name: String = "MovieBox"
    override var lang: String = "hi"

    override val supportedTypes: Set<TvType> = setOf(
        TvType.Movie,
        TvType.TvSeries
    )
}