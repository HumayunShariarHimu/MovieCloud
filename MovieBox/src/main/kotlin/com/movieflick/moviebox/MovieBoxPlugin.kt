package com.movieflick.moviebox

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

/**
 * Build-time placeholder only.
 *
 * The workflow replaces the generated MovieBox.cs3 with the
 * prebuilt, proven MovieBoxProvider artifact before publishing.
 */
@CloudstreamPlugin
class MovieBoxPlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(MovieBox())
    }
}