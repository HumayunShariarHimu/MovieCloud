package com.movieflick.onlinemovies

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URI
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.Locale

class OnlineMovies : MainAPI() {

    override var mainUrl = "https://111.90.159.132"
    override var name = "Online Movies"
    override var lang = "hi"

    override val hasMainPage = true
    override val hasQuickSearch = true

    override val supportedTypes = setOf(
        TvType.Movie,
        TvType.TvSeries
    )

    /*
     * ============================================================
     * MAIN PAGE
     * ============================================================
     *
     * Exact requested order:
     *
     * 1. Latest Movies
     * 2. Movies
     * 3. TV Show
     */
    override val mainPage = mainPageOf(
        "$mainUrl/year/2026/" to "Latest Movies",
        "onlinemovies://movies" to "Movies",
        "$mainUrl/tv-show/" to "TV Show"
    )

    /*
     * ============================================================
     * MOVIE GENRES
     * ============================================================
     */
    private val movieGenreUrls = listOf(
        "$mainUrl/drama/",
        "$mainUrl/action/",
        "$mainUrl/comedy/",
        "$mainUrl/romance/",
        "$mainUrl/thriller/",
        "$mainUrl/crime/",
        "$mainUrl/horror/",
        "$mainUrl/adventure-movies/",
        "$mainUrl/science-fiction/",
        "$mainUrl/mystery/",
        "$mainUrl/fantasy/"
    )

    /*
     * ============================================================
     * HEADERS
     * ============================================================
     */
    private val pageHeaders = mapOf(
        "User-Agent" to
            "Mozilla/5.0 (Linux; Android 13; Mobile) " +
                "AppleWebKit/537.36 (KHTML, like Gecko) " +
                "Chrome/131.0.0.0 Mobile Safari/537.36",

        "Accept" to
            "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",

        "Accept-Language" to
            "en-US,en;q=0.9",

        "Cache-Control" to "no-cache",
        "Pragma" to "no-cache"
    )

    /*
     * ============================================================
     * DATA MODELS
     * ============================================================
     */
    private data class SiteItem(
        val title: String,
        val url: String,
        val poster: String?,
        val isSeries: Boolean
    )

    private data class EpisodeInfo(
        val url: String,
        val name: String,
        val season: Int?,
        val episode: Int?
    )

    /*
     * ============================================================
     * MAIN PAGE
     * ============================================================
     */
    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {

        val currentPage = page.coerceAtLeast(1)

        /*
         * --------------------------------------------------------
         * MOVIES = MERGED 11 GENRES
         * --------------------------------------------------------
         */
        if (request.data == "onlinemovies://movies") {

            val merged =
                linkedMapOf<String, SiteItem>()

            movieGenreUrls.forEach { genreUrl ->

                val url =
                    buildPageUrl(
                        genreUrl,
                        currentPage
                    )

                val document =
                    getDocument(url)
                        ?: return@forEach

                parseArchiveItems(
                    document = document,
                    sourceUrl = url,
                    forceSeries = false
                ).forEach { item ->

                    merged.putIfAbsent(
                        item.url,
                        item
                    )
                }
            }

            val responses =
                merged.values
                    .take(MAX_ITEMS_PER_PAGE)
                    .map {
                        it.toSearchResponse()
                    }

            return newHomePageResponse(
                request,
                responses,
                currentPage < MAX_MOVIE_PAGES
            )
        }

        /*
         * --------------------------------------------------------
         * LATEST MOVIES / TV SHOW
         * --------------------------------------------------------
         */
        val url =
            buildPageUrl(
                request.data,
                currentPage
            )

        val document =
            getDocument(url)
                ?: return newHomePageResponse(
                    request,
                    emptyList(),
                    false
                )

        val forceSeries =
            request.name.equals(
                "TV Show",
                ignoreCase = true
            )

        val items =
            parseArchiveItems(
                document = document,
                sourceUrl = url,
                forceSeries = forceSeries
            )

        val responses =
            items
                .take(MAX_ITEMS_PER_PAGE)
                .map {
                    it.toSearchResponse()
                }

        return newHomePageResponse(
            request,
            responses,
            hasNextPage(document)
        )
    }

    /*
     * ============================================================
     * SEARCH
     * ============================================================
     */
    override suspend fun search(
        query: String,
        page: Int
    ): SearchResponseList {

        val original =
            query.trim()

        if (original.isBlank()) {
            return newSearchResponseList(
                emptyList(),
                false
            )
        }

        val normalized =
            normalizeSearchText(original)

        val compact =
            compactSearchText(original)

        val variants =
            linkedSetOf<String>()

        variants.add(original)

        if (normalized.isNotBlank()) {
            variants.add(normalized)
        }

        if (compact.isNotBlank()) {
            variants.add(compact)
        }

        variants.add(
            original
                .replace(":", " ")
                .replace("-", " ")
                .replace("_", " ")
        )

        variants.add(
            original
                .replace(
                    Regex("\\s+"),
                    " "
                )
        )

        val candidates =
            linkedMapOf<String, SiteItem>()

        /*
         * --------------------------------------------------------
         * NATIVE SEARCH
         * --------------------------------------------------------
         */
        for (variant in variants) {

            val encoded =
                URLEncoder.encode(
                    variant,
                    StandardCharsets.UTF_8.toString()
                )

            val searchUrls =
                listOf(
                    "$mainUrl/?s=$encoded",
                    "$mainUrl/?search=$encoded",
                    "$mainUrl/search/$encoded/"
                )

            for (searchUrl in searchUrls) {

                val document =
                    getDocument(searchUrl)
                        ?: continue

                parseArchiveItems(
                    document = document,
                    sourceUrl = searchUrl
                ).forEach { item ->

                    candidates.putIfAbsent(
                        item.url,
                        item
                    )
                }

                if (
                    candidates.size >=
                    SEARCH_NATIVE_LIMIT
                ) {
                    break
                }
            }

            if (
                candidates.size >=
                SEARCH_NATIVE_LIMIT
            ) {
                break
            }
        }

        /*
         * --------------------------------------------------------
         * GENRE FALLBACK
         * --------------------------------------------------------
         */
        if (
            candidates.size <
            SEARCH_FALLBACK_MINIMUM
        ) {

            movieGenreUrls.forEach { genreUrl ->

                val url =
                    buildPageUrl(
                        genreUrl,
                        1
                    )

                val document =
                    getDocument(url)
                        ?: return@forEach

                parseArchiveItems(
                    document = document,
                    sourceUrl = url
                ).forEach { item ->

                    candidates.putIfAbsent(
                        item.url,
                        item
                    )
                }
            }
        }

        /*
         * --------------------------------------------------------
         * TV FALLBACK
         * --------------------------------------------------------
         */
        if (
            candidates.size <
            SEARCH_FALLBACK_LIMIT
        ) {

            val tvUrl =
                buildPageUrl(
                    "$mainUrl/tv-show/",
                    1
                )

            val tvDocument =
                getDocument(tvUrl)

            if (tvDocument != null) {

                parseArchiveItems(
                    document = tvDocument,
                    sourceUrl = tvUrl,
                    forceSeries = true
                ).forEach { item ->

                    candidates.putIfAbsent(
                        item.url,
                        item.copy(
                            isSeries = true
                        )
                    )
                }
            }
        }

        /*
         * --------------------------------------------------------
         * FUZZY RANKING
         * --------------------------------------------------------
         */
        val ranked =
            candidates.values
                .map { item ->

                    Pair(
                        item,
                        searchScore(
                            normalized,
                            normalizeSearchText(
                                item.title
                            )
                        )
                    )
                }
                .filter {
                    it.second >=
                        SEARCH_MIN_SCORE
                }
                .sortedWith(
                    compareByDescending<
                        Pair<SiteItem, Double>
                        > {
                        it.second
                    }.thenBy {
                        it.first.title.lowercase(
                            Locale.ROOT
                        )
                    }
                )
                .map {
                    it.first
                }

        val pageSize =
            MAX_ITEMS_PER_PAGE

        val currentPage =
            page.coerceAtLeast(1)

        val start =
            (currentPage - 1) *
                pageSize

        val pageResults =
            ranked
                .drop(start)
                .take(pageSize)

        return newSearchResponseList(
            pageResults.map {
                it.toSearchResponse()
            },
            start + pageSize <
                ranked.size
        )
    }

    /*
     * ============================================================
     * LOAD DETAIL
     * ============================================================
     */
    override suspend fun load(
        url: String
    ): LoadResponse {

        val clean =
            cleanUrlLocal(url)

        if (clean.isBlank()) {

            return newMovieLoadResponse(
                "Online Movies",
                mainUrl,
                TvType.Movie,
                mainUrl
            )
        }

        val document =
            getDocument(clean)

        if (document == null) {

            if (looksLikeTvUrl(clean)) {

                return newTvSeriesLoadResponse(
                    titleFromUrlLocal(clean),
                    clean,
                    TvType.TvSeries,
                    emptyList()
                )
            }

            return newMovieLoadResponse(
                titleFromUrlLocal(clean),
                clean,
                TvType.Movie,
                clean
            )
        }

        val title =
            extractDetailTitle(document)
                .ifBlank {
                    titleFromUrlLocal(clean)
                }

        val poster =
            extractPoster(
                document,
                clean
            )

        val plot =
            extractPlot(document)

        val year =
            extractYear(
                document,
                title
            )

        val episodeLinks =
            parseEpisodeLinks(
                document,
                clean
            )

        /*
         * Important:
         * /tv/ and /eps/ are treated as TV URLs.
         * We do NOT classify a page merely because some text says
         * "TV Show".
         */
        val isSeries =
            looksLikeTvUrl(clean) ||
                episodeLinks.isNotEmpty()

        /*
         * --------------------------------------------------------
         * TV SERIES
         * --------------------------------------------------------
         */
        if (isSeries) {

            val episodes =
                episodeLinks.mapIndexed {
                    index,
                    info ->

                    newEpisode(
                        info.url
                    ) {

                        name =
                            info.name

                        season =
                            info.season

                        episode =
                            info.episode
                                ?: (index + 1)
                    }
                }

            return newTvSeriesLoadResponse(
                title,
                clean,
                TvType.TvSeries,
                episodes
            ) {

                posterUrl = poster
                this.plot = plot
                this.year = year
            }
        }

        /*
         * --------------------------------------------------------
         * MOVIE
         * --------------------------------------------------------
         */
        return newMovieLoadResponse(
            title,
            clean,
            TvType.Movie,
            clean
        ) {

            posterUrl = poster
            this.plot = plot
            this.year = year
        }
    }

    /*
     * ============================================================
     * LINK LOADER
     * ============================================================
     *
     * Playback crawler:
     * - scans the selected detail / episode page
     * - ignores known advertising / tracking URLs
     * - keeps crawling even after the first playable URL is found
     * - collects multiple resolutions instead of stopping at 360p
     * - preserves the page URL as Referer
     * - ignores YouTube trailer embeds
     */
    
override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {

        val input = cleanUrlLocal(data)
        if (input.isBlank()) return false

        val visitedPages = linkedSetOf<String>()
        val found = linkedMapOf<String, MediaCandidate>()

        suspend fun crawlPage(
            pageUrl: String,
            depth: Int
        ) {
            if (depth > MAX_CRAWL_DEPTH) return

            val cleanPage = cleanUrlLocal(pageUrl)
            if (cleanPage.isBlank()) return
            if (!visitedPages.add(cleanPage)) return
            if (visitedPages.size > MAX_PLAYBACK_PAGES) return

            if (isIgnoredPlaybackHost(cleanPage)) return
            if (isAdvertisingUrl(cleanPage)) return

            val response = runCatching {
                app.get(
                    cleanPage,
                    headers = pageHeaders + (
                        "Referer" to if (depth == 0) {
                            "$mainUrl/"
                        } else {
                            input
                        }
                    )
                )
            }.getOrNull() ?: return

            val document = response.document
            val html = response.text

            /*
             * Collect EVERY playable media candidate on this page.
             * We deliberately never stop after the first URL.
             */
            val pageCandidates = extractMediaCandidates(
                document = document,
                html = html,
                baseUrl = cleanPage
            )

            pageCandidates.forEach { candidate ->
                if (isPlayableMedia(candidate.url) &&
                    !isAdvertisingUrl(candidate.url) &&
                    !isIgnoredPlaybackHost(candidate.url)
                ) {
                    val old = found[candidate.url]

                    if (
                        old == null ||
                        betterMediaCandidate(candidate, old)
                    ) {
                        found[candidate.url] = candidate
                    }
                }
            }

            /*
             * Continue into player/watch/embed pages even after we already
             * found a source. This is important when 360p is exposed on the
             * first page while 720p/1080p are exposed by another player page.
             */
            val nestedPages =
                extractPlaybackPageCandidates(
                    document = document,
                    baseUrl = cleanPage
                )

            for (nested in nestedPages) {
                if (visitedPages.size >= MAX_PLAYBACK_PAGES) break
                crawlPage(
                    nested,
                    depth + 1
                )
            }
        }

        crawlPage(
            input,
            0
        )

        if (found.isEmpty()) {
            return false
        }

        /*
         * First expand HLS master playlists. A single master.m3u8 can contain
         * 360p/480p/720p/1080p variants even when the page itself exposes only
         * one m3u8 URL.
         */
        val expanded =
            linkedMapOf<String, MediaCandidate>()

        for (candidate in found.values) {

            if (
                candidate.url
                    .lowercase(Locale.ROOT)
                    .contains(".m3u8")
            ) {

                val variants =
                    expandHlsVariants(
                        candidate
                    )

                if (variants.isNotEmpty()) {

                    variants.forEach { variant ->
                        expanded[
                            variant.url
                        ] = variant
                    }

                    /*
                     * Keep the master as a fallback too. This is useful when
                     * the playlist is not a conventional multi-variant HLS
                     * master or the player wants to handle the manifest itself.
                     */
                    expanded.putIfAbsent(
                        candidate.url,
                        candidate
                    )

                } else {

                    expanded.putIfAbsent(
                        candidate.url,
                        candidate
                    )

                }

            } else {

                expanded.putIfAbsent(
                    candidate.url,
                    candidate
                )
            }
        }

        /*
         * One best source per known resolution.
         *
         * IMPORTANT:
         * Quality is determined from the actual source URL / source tag
         * metadata, not from arbitrary surrounding HTML. This prevents a
         * page containing the text "360" somewhere else from incorrectly
         * turning a 720p/1080p source into "360p".
         */
        val bestByQuality =
            linkedMapOf<Int, MediaCandidate>()

        val unknownSources =
            linkedMapOf<String, MediaCandidate>()

        expanded.values.forEach { candidate ->

            if (
                isAdvertisingUrl(candidate.url) ||
                isIgnoredPlaybackHost(candidate.url)
            ) {
                return@forEach
            }

            val q =
                qualityValue(
                    candidate.quality
                )

            if (q > 0) {

                val old =
                    bestByQuality[q]

                if (
                    old == null ||
                    betterMediaCandidate(
                        candidate,
                        old
                    )
                ) {
                    bestByQuality[q] = candidate
                }

            } else {

                unknownSources.putIfAbsent(
                    candidate.url,
                    candidate
                )
            }
        }

        val sorted =
            (
                bestByQuality.values +
                    unknownSources.values
                )
                .sortedWith(
                    compareByDescending<MediaCandidate> {
                        qualityValue(
                            it.quality
                        )
                    }.thenBy {
                        it.url.length
                    }
                )
                .take(
                    MAX_PLAYBACK_LINKS
                )

        if (sorted.isEmpty()) {
            return false
        }

        sorted.forEach { candidate ->
            emitMediaLink(
                candidate = candidate,
                callback = callback
            )
        }

        return true
    }

    /*
     * ============================================================
     * PLAYBACK DATA MODEL
     * ============================================================
     */
    private data class MediaCandidate(
        val url: String,
        val quality: Int,
        val referer: String
    )

    /*
     * ============================================================
     * PLAYBACK MEDIA EXTRACTION
     * ============================================================
     */
    private fun extractMediaCandidates(
        document: Document,
        html: String,
        baseUrl: String
    ): List<MediaCandidate> {

        val result =
            linkedMapOf<String, MediaCandidate>()

        fun add(
            raw: String?,
            qualityHint: String? = null
        ) {

            if (raw.isNullOrBlank()) {
                return
            }

            val cleanedRaw =
                raw
                    .trim()
                    .replace(
                        "\\/",
                        "/"
                    )
                    .replace(
                        "\\u0026",
                        "&"
                    )

            if (cleanedRaw.isBlank()) {
                return
            }

            val absolute =
                absoluteUrlLocal(
                    cleanedRaw,
                    baseUrl
                )

            val cleaned =
                cleanUrlLocal(
                    absolute
                )

            if (!isHttpUrl(cleaned)) {
                return
            }

            if (isIgnoredPlaybackHost(cleaned)) {
                return
            }

            if (isAdvertisingUrl(cleaned)) {
                return
            }

            if (!isPlayableMedia(cleaned)) {
                return
            }

            /*
             * ONLY source-local information is used to determine quality.
             * We never inspect the whole surrounding HTML for "360", "720",
             * etc., because unrelated page text can create false labels.
             */
            val quality =
                detectQuality(
                    qualityHint,
                    cleaned
                )

            val candidate =
                MediaCandidate(
                    url = cleaned,
                    quality = quality,
                    referer = baseUrl
                )

            val old =
                result[cleaned]

            if (
                old == null ||
                betterMediaCandidate(
                    candidate,
                    old
                )
            ) {
                result[cleaned] =
                    candidate
            }
        }

        /*
         * ------------------------------------------------------------
         * HTML5 <video>
         * ------------------------------------------------------------
         */
        document
            .select(
                "video[src], " +
                    "video[data-src], " +
                    "video[data-file], " +
                    "video[data-video]"
            )
            .forEach { video ->

                val hint =
                    firstNonBlank(
                        video.attr(
                            "data-quality"
                        ),
                        video.attr(
                            "data-resolution"
                        ),
                        video.attr(
                            "data-res"
                        ),
                        video.attr(
                            "resolution"
                        ),
                        video.attr(
                            "label"
                        ),
                        video.attr(
                            "res"
                        ),
                        video.attr(
                            "size"
                        )
                    )

                add(
                    video.attr(
                        "src"
                    ),
                    hint
                )

                add(
                    video.attr(
                        "data-src"
                    ),
                    hint
                )

                add(
                    video.attr(
                        "data-file"
                    ),
                    hint
                )

                add(
                    video.attr(
                        "data-video"
                    ),
                    hint
                )
            }

        /*
         * ------------------------------------------------------------
         * HTML5 <source>
         * ------------------------------------------------------------
         */
        document
            .select(
                "video source, source"
            )
            .forEach { source ->

                val hint =
                    firstNonBlank(
                        source.attr(
                            "label"
                        ),
                        source.attr(
                            "res"
                        ),
                        source.attr(
                            "resolution"
                        ),
                        source.attr(
                            "data-quality"
                        ),
                        source.attr(
                            "data-resolution"
                        ),
                        source.attr(
                            "data-res"
                        ),
                        source.attr(
                            "data-label"
                        ),
                        source.attr(
                            "size"
                        ),
                        source.attr(
                            "title"
                        )
                    )

                add(
                    source.attr(
                        "src"
                    ),
                    hint
                )

                add(
                    source.attr(
                        "data-src"
                    ),
                    hint
                )

                add(
                    source.attr(
                        "data-file"
                    ),
                    hint
                )

                add(
                    source.attr(
                        "data-video"
                    ),
                    hint
                )

                add(
                    source.attr(
                        "data-url"
                    ),
                    hint
                )
            }

        /*
         * ------------------------------------------------------------
         * data-* media attributes
         * ------------------------------------------------------------
         */
        document
            .select(
                "[data-src], " +
                    "[data-file], " +
                    "[data-video], " +
                    "[data-url], " +
                    "[data-hls], " +
                    "[data-m3u8], " +
                    "[data-mp4], " +
                    "[data-stream], " +
                    "[data-source]"
            )
            .forEach { element ->

                val hint =
                    firstNonBlank(
                        element.attr(
                            "data-quality"
                        ),
                        element.attr(
                            "data-resolution"
                        ),
                        element.attr(
                            "data-res"
                        ),
                        element.attr(
                            "resolution"
                        ),
                        element.attr(
                            "res"
                        ),
                        element.attr(
                            "label"
                        ),
                        element.attr(
                            "title"
                        ),
                        element.attr(
                            "size"
                        )
                    )

                add(
                    element.attr(
                        "data-src"
                    ),
                    hint
                )

                add(
                    element.attr(
                        "data-file"
                    ),
                    hint
                )

                add(
                    element.attr(
                        "data-video"
                    ),
                    hint
                )

                add(
                    element.attr(
                        "data-url"
                    ),
                    hint
                )

                add(
                    element.attr(
                        "data-hls"
                    ),
                    hint
                )

                add(
                    element.attr(
                        "data-m3u8"
                    ),
                    hint
                )

                add(
                    element.attr(
                        "data-mp4"
                    ),
                    hint
                )

                add(
                    element.attr(
                        "data-stream"
                    ),
                    hint
                )

                add(
                    element.attr(
                        "data-source"
                    ),
                    hint
                )
            }

        /*
         * ------------------------------------------------------------
         * Direct links / iframe / embed
         * ------------------------------------------------------------
         */
        document
            .select(
                "a[href], iframe[src], embed[src]"
            )
            .forEach { element ->

                val raw =
                    when {

                        element.hasAttr(
                            "href"
                        ) ->
                            element.attr(
                                "href"
                            )

                        element.hasAttr(
                            "src"
                        ) ->
                            element.attr(
                                "src"
                            )

                        else ->
                            ""
                    }

                val hint =
                    firstNonBlank(
                        element.attr(
                            "label"
                        ),
                        element.attr(
                            "data-quality"
                        ),
                        element.attr(
                            "data-resolution"
                        ),
                        element.attr(
                            "data-res"
                        ),
                        element.attr(
                            "resolution"
                        ),
                        element.attr(
                            "res"
                        ),
                        element.attr(
                            "size"
                        )
                    )

                add(
                    raw,
                    hint
                )
            }

        /*
         * ------------------------------------------------------------
         * Absolute media URLs in JavaScript / JSON
         * ------------------------------------------------------------
         */
        val mediaRegex =
            Regex(
                """(?i)(https?:\/\/[^\s"'<>]+?(?:\.m3u8|\.mpd|\.mp4|\.mkv|\.webm|\.mov|\.m4v|\.avi|\.flv|\.ts)(?:\?[^\s"'<>]*)?)"""
            )

        mediaRegex
            .findAll(html)
            .forEach { match ->

                add(
                    match.value,
                    null
                )
            }

        val quotedMediaRegex =
            Regex(
                """(?i)[\"']([^\"']+?(?:\.m3u8|\.mpd|\.mp4|\.mkv|\.webm|\.mov|\.m4v|\.avi|\.flv|\.ts)(?:\?[^\"']*)?)[\"']"""
            )

        quotedMediaRegex
            .findAll(html)
            .forEach { match ->

                add(
                    match.groupValues[1],
                    null
                )
            }

        return result.values.toList()
    }

    /*
     * ============================================================
     * HLS MASTER PLAYLIST EXPANSION
     * ============================================================
     */
    private suspend fun expandHlsVariants(
        master: MediaCandidate
    ): List<MediaCandidate> {

        val result =
            linkedMapOf<String, MediaCandidate>()

        val response =
            runCatching {

                app.get(
                    master.url,
                    headers =
                        pageHeaders +
                            (
                                "Referer" to
                                    master.referer
                                )
                )

            }.getOrNull()
                ?: return emptyList()

        val body =
            response.text

        if (
            !body.contains(
                "#EXT-X-STREAM-INF",
                ignoreCase = true
            )
        ) {
            return emptyList()
        }

        val lines =
            body.lines()

        for (index in lines.indices) {

            val line =
                lines[index].trim()

            if (
                !line.startsWith(
                    "#EXT-X-STREAM-INF",
                    ignoreCase = true
                )
            ) {
                continue
            }

            val resolutionMatch =
                Regex(
                    """(?i)RESOLUTION\s*=\s*(\d+)\s*x\s*(\d+)"""
                ).find(line)

            val bandwidth =
                Regex(
                    """(?i)BANDWIDTH\s*=\s*(\d+)"""
                )
                    .find(line)
                    ?.groupValues
                    ?.getOrNull(1)
                    ?.toLongOrNull()

            val resolution =
                resolutionMatch?.let {
                    val width =
                        it.groupValues[1]
                            .toIntOrNull()

                    val height =
                        it.groupValues[2]
                            .toIntOrNull()

                    if (
                        width != null &&
                        height != null
                    ) {
                        Pair(
                            width,
                            height
                        )
                    } else {
                        null
                    }
                }

            var variantUrl: String? =
                null

            var nextIndex =
                index + 1

            while (
                nextIndex < lines.size
            ) {

                val next =
                    lines[nextIndex]
                        .trim()

                if (next.isBlank()) {
                    nextIndex++
                    continue
                }

                if (
                    next.startsWith("#")
                ) {
                    nextIndex++
                    continue
                }

                variantUrl =
                    next

                break
            }

            if (variantUrl.isNullOrBlank()) {
                continue
            }

            val absolute =
                absoluteUrlLocal(
                    variantUrl,
                    master.url
                )

            if (
                !isHttpUrl(
                    absolute
                )
            ) {
                continue
            }

            if (
                isAdvertisingUrl(
                    absolute
                )
            ) {
                continue
            }

            val quality =
                when {

                    resolution != null &&
                        resolution.second >= 2160 ->
                        Qualities.P2160.value

                    resolution != null &&
                        resolution.second >= 1440 ->
                        Qualities.P1440.value

                    resolution != null &&
                        resolution.second >= 1080 ->
                        Qualities.P1080.value

                    resolution != null &&
                        resolution.second >= 720 ->
                        Qualities.P720.value

                    resolution != null &&
                        resolution.second >= 480 ->
                        Qualities.P480.value

                    resolution != null &&
                        resolution.second >= 360 ->
                        Qualities.P360.value

                    else ->
                        detectQuality(
                            null,
                            absolute
                        )
                }

            /*
             * The bitrate is not used as the displayed quality. It only
             * helps when several variants accidentally share the same height.
             */
            val candidate =
                MediaCandidate(
                    url = absolute,
                    quality = quality,
                    referer = master.referer
                )

            val old =
                result[absolute]

            if (
                old == null ||
                betterMediaCandidate(
                    candidate,
                    old
                )
            ) {
                result[absolute] =
                    candidate
            }

            @Suppress("UNUSED_VARIABLE")
            val ignoredBandwidth =
                bandwidth
        }

        return result.values.toList()
    }

    /*
     * ============================================================
     * SEARCH RESPONSE
     * ============================================================
     */
private fun firstNonBlank(
        vararg values: String
    ): String? {
        return values
            .firstOrNull {
                it.isNotBlank()
            }
            ?.trim()
            ?.takeIf {
                it.isNotBlank()
            }
    }

    private fun extractPlaybackPageCandidates(
        document: Document,
        baseUrl: String
    ): List<String> {

        val result = linkedSetOf<String>()

        document.select(
            "a[href], iframe[src], embed[src], " +
                "[data-src], [data-url]"
        ).forEach { element ->

            val raw = when {
                element.hasAttr("href") -> element.attr("href")
                element.hasAttr("src") -> element.attr("src")
                element.hasAttr("data-src") -> element.attr("data-src")
                element.hasAttr("data-url") -> element.attr("data-url")
                else -> ""
            }.trim()

            if (raw.isBlank()) return@forEach

            val absolute = absoluteUrlLocal(
                raw,
                baseUrl
            )

            if (!isHttpUrl(absolute)) return@forEach
            if (isPlayableMedia(absolute)) return@forEach
            if (isIgnoredPlaybackHost(absolute)) return@forEach
            if (isAdvertisingUrl(absolute)) return@forEach

            val lower = absolute.lowercase(Locale.ROOT)
            val text = (
                element.text() + " " +
                    element.attr("title") + " " +
                    element.attr("class") + " " +
                    element.attr("id") + " " +
                    element.attr("data-label")
                ).lowercase(Locale.ROOT)

            val looksLikePlayer =
                lower.contains("watch") ||
                    lower.contains("player") ||
                    lower.contains("stream") ||
                    lower.contains("play") ||
                    lower.contains("embed") ||
                    lower.contains("video") ||
                    lower.contains("server") ||
                    lower.contains("source") ||
                    text.contains("watch") ||
                    text.contains("player") ||
                    text.contains("play") ||
                    text.contains("stream") ||
                    text.contains("server") ||
                    text.contains("source")

            if (looksLikePlayer) {
                result.add(absolute)
            }
        }

        return result
            .take(MAX_NESTED_PAGES)
    }

    private fun isPlayableMedia(
        url: String
    ): Boolean {

        val lower = url.lowercase(Locale.ROOT)

        return lower.contains(".m3u8") ||
            lower.contains(".mpd") ||
            lower.contains(".mp4") ||
            lower.contains(".mkv") ||
            lower.contains(".webm") ||
            lower.contains(".mov") ||
            lower.contains(".m4v") ||
            lower.contains(".avi") ||
            lower.contains(".flv") ||
            lower.contains(".ts")
    }

    private fun isHttpUrl(
        url: String
    ): Boolean {
        return url.startsWith("http://", true) ||
            url.startsWith("https://", true)
    }

    /*
     * Known ad / tracker destinations are ignored.
     * This keeps advertising media from becoming CloudStream sources.
     */
    private fun isAdvertisingUrl(
        url: String
    ): Boolean {

        val lower = url.lowercase(Locale.ROOT)

        val blockedHosts = listOf(
            "doubleclick.net",
            "googlesyndication.com",
            "googleadservices.com",
            "adservice.google.com",
            "adsafeprotected.com",
            "adnxs.com",
            "adsrvr.org",
            "adskeeper.com",
            "popads.net",
            "propellerads.com",
            "exoclick.com",
            "juicyads.com",
            "trafficjunky.com",
            "outbrain.com",
            "taboola.com",
            "criteo.com",
            "prebid.org",
            "adsterra.com",
            "onclickads.net",
            "onclicka.com",
            "ad-maven.com"
        )

        if (blockedHosts.any { host -> lower.contains(host) }) {
            return true
        }

        val adPatterns = listOf(
            "preroll",
            "pre-roll",
            "midroll",
            "mid-roll",
            "postroll",
            "post-roll",
            "advertisement",
            "advertising",
            "/ads/",
            "-ads-",
            "_ads_",
            "?ad=",
            "&ad=",
            "adtype=",
            "vast=",
            "vpaid=",
            "adtag=",
            "adserver=",
            "adurl=",
            "adsource=",
            "bannerad",
            "banner_ad"
        )

        return adPatterns.any { pattern ->
            lower.contains(pattern)
        }
    }

    /*
     * Reject a playable-looking URL when the surrounding HTML explicitly
     * identifies it as advertising/trailer material. This catches ad media
     * even when the ad CDN hostname itself is not in the host deny-list.
     */
    private fun isAdvertisingContext(
        hint: String?,
        contextText: String?
    ): Boolean {

        val context = (
            (hint ?: "") + " " + (contextText ?: "")
            ).lowercase(Locale.ROOT)

        val patterns = listOf(
            "preroll",
            "pre-roll",
            "midroll",
            "mid-roll",
            "postroll",
            "post-roll",
            "advertisement",
            "advertising",
            "adserver",
            "adtag",
            "vast",
            "vpaid",
            "bannerad",
            "banner_ad",
            "\"ad\":",
            "\"adurl\":",
            "trailer",
            "youtube.com",
            "youtu.be"
        )

        return patterns.any { pattern ->
            context.contains(pattern)
        }
    }

    /* YouTube is used by the site's trailer widget and is not a movie source. */
    private fun isIgnoredPlaybackHost(
        url: String
    ): Boolean {

        val lower = url.lowercase(Locale.ROOT)

        return lower.contains("youtube.com") ||
            lower.contains("youtu.be") ||
            lower.contains("youtube-nocookie.com")
    }

    
private fun detectQuality(
        hint: String?,
        url: String
    ): Int {

        /*
         * Resolution must come from source-local metadata or the URL itself.
         * Do NOT inspect arbitrary surrounding page HTML.
         */
        val source =
            (
                (hint ?: "") +
                    " " +
                    url
                )
                .lowercase(
                    Locale.ROOT
                )

        return when {

            Regex(
                """\b2160(?:p)?\b"""
            ).containsMatchIn(source) ||
                Regex(
                    """\b3840x2160\b"""
                ).containsMatchIn(source) ||
                "4k" in source ||
                "uhd" in source ->

                Qualities.P2160.value

            Regex(
                """\b1440(?:p)?\b"""
            ).containsMatchIn(source) ||
                Regex(
                    """\b2560x1440\b"""
                ).containsMatchIn(source) ||
                "2k" in source ->

                Qualities.P1440.value

            Regex(
                """\b1080(?:p)?\b"""
            ).containsMatchIn(source) ||
                Regex(
                    """\b1920x1080\b"""
                ).containsMatchIn(source) ||
                "fullhd" in source ||
                "full-hd" in source ||
                "fhd" in source ->

                Qualities.P1080.value

            Regex(
                """\b720(?:p)?\b"""
            ).containsMatchIn(source) ||
                Regex(
                    """\b1280x720\b"""
                ).containsMatchIn(source) ||
                Regex(
                    """\b720x\d+\b"""
                ).containsMatchIn(source) ->

                Qualities.P720.value

            Regex(
                """\b480(?:p)?\b"""
            ).containsMatchIn(source) ||
                Regex(
                    """\b854x480\b"""
                ).containsMatchIn(source) ||
                Regex(
                    """\b480x\d+\b"""
                ).containsMatchIn(source) ->

                Qualities.P480.value

            Regex(
                """\b360(?:p)?\b"""
            ).containsMatchIn(source) ||
                Regex(
                    """\b640x360\b"""
                ).containsMatchIn(source) ||
                Regex(
                    """\b360x\d+\b"""
                ).containsMatchIn(source) ->

                Qualities.P360.value

            else ->
                Qualities.Unknown.value
        }
    }

private fun qualityValue(
        quality: Int
    ): Int {
        return when {
            quality >= Qualities.P2160.value -> 2160
            quality >= Qualities.P1440.value -> 1440
            quality >= Qualities.P1080.value -> 1080
            quality >= Qualities.P720.value -> 720
            quality >= Qualities.P480.value -> 480
            quality >= Qualities.P360.value -> 360
            else -> 0
        }
    }

    private fun betterMediaCandidate(
        a: MediaCandidate,
        b: MediaCandidate
    ): Boolean {

        val aq = qualityValue(a.quality)
        val bq = qualityValue(b.quality)

        if (aq != bq) {
            return aq > bq
        }

        val aLower = a.url.lowercase(Locale.ROOT)
        val bLower = b.url.lowercase(Locale.ROOT)

        val aDirect =
            aLower.contains(".mp4") ||
                aLower.contains(".webm") ||
                aLower.contains(".mkv")

        val bDirect =
            bLower.contains(".mp4") ||
                bLower.contains(".webm") ||
                bLower.contains(".mkv")

        return aDirect && !bDirect
    }

    private suspend fun emitMediaLink(
        candidate: MediaCandidate,
        callback: (ExtractorLink) -> Unit
    ) {

        val mediaUrl = cleanUrlLocal(candidate.url)
        if (!isPlayableMedia(mediaUrl)) return
        if (isAdvertisingUrl(mediaUrl)) return
        if (isIgnoredPlaybackHost(mediaUrl)) return

        val lower = mediaUrl.lowercase(Locale.ROOT)

        val type = when {
            lower.contains(".m3u8") ->
                ExtractorLinkType.M3U8

            lower.contains(".mpd") ->
                ExtractorLinkType.DASH

            else ->
                ExtractorLinkType.VIDEO
        }

        val quality = candidate.quality

        val label = when {
            quality >= Qualities.P2160.value ->
                "Online Movies 2160p"

            quality >= Qualities.P1440.value ->
                "Online Movies 1440p"

            quality >= Qualities.P1080.value ->
                "Online Movies 1080p"

            quality >= Qualities.P720.value ->
                "Online Movies 720p"

            quality >= Qualities.P480.value ->
                "Online Movies 480p"

            quality >= Qualities.P360.value ->
                "Online Movies 360p"

            else ->
                "Online Movies Direct"
        }

        callback(
            newExtractorLink(
                source = name,
                name = label,
                url = mediaUrl,
                type = type
            ) {
                this.referer = candidate.referer
                this.quality = quality
            }
        )
    }

    /*
     * ============================================================
     * SEARCH RESPONSE
     * ============================================================
     */
    private fun SiteItem.toSearchResponse():
        SearchResponse {

        return if (isSeries) {

            newTvSeriesSearchResponse(
                name = title,
                url = url,
                type = TvType.TvSeries
            ) {
                posterUrl = poster
            }

        } else {

            newMovieSearchResponse(
                name = title,
                url = url,
                type = TvType.Movie
            ) {
                posterUrl = poster
            }
        }
    }

    /*
     * ============================================================
     * DOCUMENT LOADER
     * ============================================================
     */
    private suspend fun getDocument(
        url: String
    ): Document? {

        val normalized =
            cleanUrlLocal(url)

        if (normalized.isBlank()) {
            return null
        }

        return runCatching {

            app.get(
                normalized,
                headers =
                    pageHeaders +
                        (
                            "Referer" to
                                "$mainUrl/"
                            )
            ).document

        }.getOrNull()
    }

    /*
     * ============================================================
     * ARCHIVE PARSER
     * ============================================================
     */
    private fun parseArchiveItems(
        document: Document,
        sourceUrl: String,
        forceSeries: Boolean = false
    ): List<SiteItem> {

        val result =
            linkedMapOf<String, SiteItem>()

        /*
         * Primary WordPress article structure
         */
        document
            .select("article")
            .forEach { article ->

                val anchor =
                    article.selectFirst(
                        "p.entry-title a[href], " +
                            ".entry-title a[href], " +
                            "a[rel='bookmark'][href]"
                    )
                        ?: article.selectFirst(
                            "a[href]"
                        )
                        ?: return@forEach

                val href =
                    anchor
                        .attr("href")
                        .trim()

                if (href.isBlank()) {
                    return@forEach
                }

                val absolute =
                    absoluteUrlLocal(
                        href,
                        sourceUrl
                    )

                if (
                    !isContentUrl(
                        absolute
                    )
                ) {
                    return@forEach
                }

                val title =
                    cleanArchiveTitle(
                        anchor
                            .text()
                            .trim()
                            .ifBlank {
                                anchor
                                    .attr("title")
                                    .trim()
                            }
                    )

                if (title.isBlank()) {
                    return@forEach
                }

                val poster =
                    extractCardPoster(
                        article,
                        sourceUrl
                    )

                val series =
                    forceSeries ||
                        looksLikeTvUrl(
                            absolute
                        )

                result.putIfAbsent(
                    absolute,
                    SiteItem(
                        title = title,
                        url = absolute,
                        poster = poster,
                        isSeries = series
                    )
                )
            }

        /*
         * Fallback selectors
         */
        if (result.isEmpty()) {

            document
                .select(
                    ".gmr-box-content, " +
                        ".item-article, " +
                        ".content-thumbnail"
                )
                .forEach { element ->

                    val anchor =
                        element.selectFirst(
                            "a[href]"
                        )
                            ?: return@forEach

                    val href =
                        anchor
                            .attr("href")
                            .trim()

                    if (href.isBlank()) {
                        return@forEach
                    }

                    val absolute =
                        absoluteUrlLocal(
                            href,
                            sourceUrl
                        )

                    if (
                        !isContentUrl(
                            absolute
                        )
                    ) {
                        return@forEach
                    }

                    val title =
                        cleanArchiveTitle(
                            anchor
                                .attr("title")
                                .trim()
                                .ifBlank {
                                    anchor
                                        .text()
                                        .trim()
                                }
                        )

                    if (title.isBlank()) {
                        return@forEach
                    }

                    val poster =
                        extractCardPoster(
                            element,
                            sourceUrl
                        )

                    result.putIfAbsent(
                        absolute,
                        SiteItem(
                            title = title,
                            url = absolute,
                            poster = poster,
                            isSeries =
                                forceSeries ||
                                    looksLikeTvUrl(
                                        absolute
                                    )
                        )
                    )
                }
        }

        return result.values.toList()
    }

    /*
     * ============================================================
     * POSTER
     * ============================================================
     */
    private fun extractCardPoster(
        element: Element,
        sourceUrl: String
    ): String? {

        val image =
            element.selectFirst(
                "img[src], " +
                    "img[data-src], " +
                    "img[data-lazy-src], " +
                    "img[data-original]"
            )
                ?: return null

        val preferred =
            image
                .attr("data-src")
                .ifBlank {
                    image.attr(
                        "data-lazy-src"
                    )
                }
                .ifBlank {
                    image.attr(
                        "data-original"
                    )
                }
                .ifBlank {
                    image.attr("src")
                }
                .trim()

        if (preferred.isNotBlank()) {

            return absoluteUrlLocal(
                preferred,
                sourceUrl
            )
        }

        val srcSet =
            image
                .attr("srcset")
                .trim()

        if (srcSet.isNotBlank()) {

            val largest =
                srcSet
                    .split(",")
                    .map {
                        it.trim()
                    }
                    .lastOrNull()
                    ?.substringBefore(" ")
                    ?.trim()

            if (
                !largest.isNullOrBlank()
            ) {

                return absoluteUrlLocal(
                    largest,
                    sourceUrl
                )
            }
        }

        return null
    }

    /*
     * ============================================================
     * TV EPISODES
     * ============================================================
     */
    private fun parseEpisodeLinks(
        document: Document,
        baseUrl: String
    ): List<EpisodeInfo> {

        val result =
            linkedMapOf<String, EpisodeInfo>()

        /*
         * Primary source:
         * .gmr-listseries a[href]
         */
        document
            .select(
                ".gmr-listseries a[href]"
            )
            .forEachIndexed {
                index,
                anchor ->

                val href =
                    anchor
                        .attr("href")
                        .trim()

                if (href.isBlank()) {
                    return@forEachIndexed
                }

                val absolute =
                    absoluteUrlLocal(
                        href,
                        baseUrl
                    )

                if (
                    !absolute.contains(
                        "/eps/",
                        ignoreCase = true
                    )
                ) {
                    return@forEachIndexed
                }

                val visible =
                    anchor
                        .text()
                        .trim()

                val titleAttr =
                    anchor
                        .attr("title")
                        .trim()

                val sourceName =
                    visible
                        .ifBlank {
                            titleAttr
                        }

                val parsed =
                    parseSeasonEpisode(
                        sourceName
                    )

                val season =
                    parsed?.first

                val episode =
                    parsed?.second
                        ?: (index + 1)

                result.putIfAbsent(
                    absolute,
                    EpisodeInfo(
                        url = absolute,
                        name =
                            buildEpisodeName(
                                season,
                                episode
                            ),
                        season = season,
                        episode = episode
                    )
                )
            }

        /*
         * Fallback:
         * any /eps/ links
         */
        if (result.isEmpty()) {

            document
                .select(
                    "a[href*='/eps/']"
                )
                .forEachIndexed {
                    index,
                    anchor ->

                    val href =
                        anchor
                            .attr("href")
                            .trim()

                    if (href.isBlank()) {
                        return@forEachIndexed
                    }

                    val absolute =
                        absoluteUrlLocal(
                            href,
                            baseUrl
                        )

                    val parsed =
                        parseSeasonEpisode(
                            anchor
                                .text()
                                .trim()
                        )

                    val episode =
                        parsed?.second
                            ?: (index + 1)

                    result.putIfAbsent(
                        absolute,
                        EpisodeInfo(
                            url = absolute,
                            name =
                                buildEpisodeName(
                                    parsed?.first,
                                    episode
                                ),
                            season =
                                parsed?.first,
                            episode =
                                episode
                        )
                    )
                }
        }

        return result.values.toList()
    }

    /*
     * ============================================================
     * SEASON / EPISODE PARSER
     * ============================================================
     */
    private fun parseSeasonEpisode(
        value: String
    ): Pair<Int, Int>? {

        if (value.isBlank()) {
            return null
        }

        /*
         * Examples:
         * S1 E1
         * S1 Ep1
         * S1 Eps1
         */
        val shortPattern =
            Regex(
                """(?i)\bS\s*(\d+)\s*E(?:p(?:s)?)?\s*(\d+)\b"""
            )

        val shortMatch =
            shortPattern.find(value)

        if (shortMatch != null) {

            return Pair(
                shortMatch
                    .groupValues[1]
                    .toIntOrNull()
                    ?: 1,

                shortMatch
                    .groupValues[2]
                    .toIntOrNull()
                    ?: 1
            )
        }

        /*
         * Examples:
         * Season 1 Episode 1
         */
        val longPattern =
            Regex(
                """(?i)\bSeason\s*(\d+).*?\bEpisode\s*(\d+)\b"""
            )

        val longMatch =
            longPattern.find(value)

        if (longMatch != null) {

            return Pair(
                longMatch
                    .groupValues[1]
                    .toIntOrNull()
                    ?: 1,

                longMatch
                    .groupValues[2]
                    .toIntOrNull()
                    ?: 1
            )
        }

        return null
    }

    private fun buildEpisodeName(
        season: Int?,
        episode: Int?
    ): String {

        return when {

            season != null &&
                episode != null ->
                "S${season} E${episode}"

            episode != null ->
                "Episode $episode"

            else ->
                "Episode"
        }
    }

    /*
     * ============================================================
     * DETAIL TITLE
     * ============================================================
     */
    private fun extractDetailTitle(
        document: Document
    ): String {

        val ogTitle =
            document
                .selectFirst(
                    "meta[property='og:title']"
                )
                ?.attr("content")
                ?.trim()

        if (!ogTitle.isNullOrBlank()) {

            return cleanDetailTitle(
                ogTitle
            )
        }

        val h1 =
            document
                .selectFirst(
                    "h1.entry-title, h1"
                )
                ?.text()
                ?.trim()

        if (!h1.isNullOrBlank()) {

            return cleanDetailTitle(
                h1
            )
        }

        return ""
    }

    /*
     * ============================================================
     * PLOT
     * ============================================================
     */
    private fun extractPlot(
        document: Document
    ): String? {

        return document
            .selectFirst(
                "meta[name='description']"
            )
            ?.attr("content")
            ?.trim()
            ?.takeIf {
                it.isNotBlank()
            }
    }

    /*
     * ============================================================
     * DETAIL POSTER
     * ============================================================
     */
    private fun extractPoster(
        document: Document,
        baseUrl: String
    ): String? {

        val raw =
            document
                .selectFirst(
                    "meta[property='og:image']"
                )
                ?.attr("content")
                ?.trim()

        if (raw.isNullOrBlank()) {
            return null
        }

        return absoluteUrlLocal(
            raw,
            baseUrl
        )
    }

    /*
     * ============================================================
     * YEAR
     * ============================================================
     */
    private fun extractYear(
        document: Document,
        title: String
    ): Int? {

        val date =
            document
                .selectFirst(
                    "time[datetime]"
                )
                ?.attr("datetime")
                ?.trim()

        val dateYear =
            date
                ?.let {
                    Regex(
                        """\b(19|20)\d{2}\b"""
                    )
                        .find(it)
                        ?.value
                        ?.toIntOrNull()
                }

        if (dateYear != null) {
            return dateYear
        }

        return Regex(
            """\b(19|20)\d{2}\b"""
        )
            .find(title)
            ?.value
            ?.toIntOrNull()
    }

    /*
     * ============================================================
     * URL HELPERS
     * ============================================================
     */
    private fun cleanUrlLocal(
        url: String
    ): String {

        return url
            .trim()
            .replace(
                "&amp;",
                "&"
            )
            .replace(
                "\\/",
                "/"
            )
            .substringBefore("#")
            .trim()
    }

    private fun absoluteUrlLocal(
        rawUrl: String,
        baseUrl: String
    ): String {

        var value =
            rawUrl
                .trim()
                .replace(
                    "&amp;",
                    "&"
                )
                .replace(
                    "\\/",
                    "/"
                )

        if (value.isBlank()) {
            return baseUrl
        }

        if (
            value.startsWith(
                "http://",
                true
            ) ||
            value.startsWith(
                "https://",
                true
            )
        ) {
            return value
        }

        if (
            value.startsWith("//")
        ) {

            val scheme =
                runCatching {
                    URI(baseUrl)
                        .scheme
                }.getOrNull()
                    ?: "https"

            return "$scheme:$value"
        }

        return runCatching {

            URI(baseUrl)
                .resolve(value)
                .toString()

        }.getOrElse {

            if (
                value.startsWith("/")
            ) {

                val uri =
                    URI(baseUrl)

                "${uri.scheme}://${uri.authority}$value"

            } else {

                baseUrl.trimEnd('/') +
                    "/" +
                    value.trimStart('/')
            }
        }
    }

    private fun buildPageUrl(
        baseUrl: String,
        page: Int
    ): String {

        val current =
            page.coerceAtLeast(1)

        if (current == 1) {
            return baseUrl
        }

        return "${
            baseUrl.trimEnd('/')
        }/page/$current/"
    }

    /*
     * ============================================================
     * CONTENT URL DETECTION
     * ============================================================
     */
    private fun isContentUrl(
        url: String
    ): Boolean {

        val lower =
            url.lowercase(
                Locale.ROOT
            )

        if (
            lower.contains(
                "youtube.com"
            ) ||
            lower.contains(
                "youtu.be"
            )
        ) {
            return false
        }

        return lower.contains(
            "/eps/"
        ) ||
            lower.contains(
                "/tv/"
            ) ||
            lower.contains(
                "/tv-show/"
            ) ||
            movieGenreUrls.any {
                lower.startsWith(
                    it.lowercase(
                        Locale.ROOT
                    )
                )
            }
    }

    private fun looksLikeTvUrl(
        url: String
    ): Boolean {

        val lower =
            url.lowercase(
                Locale.ROOT
            )

        return lower.contains(
            "/eps/"
        ) ||
            lower.contains(
                "/tv/"
            ) ||
            lower.contains(
                "/tv-show/"
            )
    }

    private fun titleFromUrlLocal(
        url: String
    ): String {

        return runCatching {

            val path =
                URI(url)
                    .path
                    .orEmpty()
                    .trim('/')

            val last =
                path
                    .substringAfterLast("/")
                    .ifBlank {
                        "Online Movies"
                    }

            last
                .replace(
                    Regex(
                        "[-_]+"
                    ),
                    " "
                )
                .replace(
                    Regex(
                        "\\s+"
                    ),
                    " "
                )
                .trim()

        }.getOrElse {
            "Online Movies"
        }
    }

    /*
     * ============================================================
     * TITLE CLEANUP
     * ============================================================
     */
    private fun cleanArchiveTitle(
        value: String
    ): String {

        return value
            .replace(
                Regex(
                    """(?i)^\s*(watch|download)\s*(movie|tv\s*show)?\s*[:\-]?\s*"""
                ),
                ""
            )
            .replace(
                Regex(
                    """(?i)\s*(for\s+free|free\s+watch|watch\s+online)\s*[!.\-]*\s*$"""
                ),
                ""
            )
            .replace(
                Regex("\\s+"),
                " "
            )
            .trim()
    }

    private fun cleanDetailTitle(
        value: String
    ): String {

        return value
            .replace(
                Regex(
                    """(?i)^\s*(watch\s+and\s+download|watch|download)\s+"""
                ),
                ""
            )
            .replace(
                Regex(
                    """(?i)^\s*(movie|tv\s+show)\s+video\s*[:\-]\s*"""
                ),
                ""
            )
            .replace(
                Regex(
                    """(?i)\s*(for\s+free|free\s+here|watch\s+online)\s*[!.\-]*\s*$"""
                ),
                ""
            )
            .replace(
                Regex("\\s+"),
                " "
            )
            .trim()
    }

    /*
     * ============================================================
     * SEARCH NORMALIZATION
     * ============================================================
     */
    private fun normalizeSearchText(
        value: String
    ): String {

        return java.text.Normalizer
            .normalize(
                value,
                java.text.Normalizer.Form.NFKC
            )
            .lowercase(
                Locale.ROOT
            )
            .replace(
                "&",
                " and "
            )
            .replace(
                Regex(
                    "[\\u2010-\\u2015\\u2212]"
                ),
                "-"
            )
            .replace(
                Regex(
                    "[^a-z0-9\\p{L}\\p{N}]+"
                ),
                " "
            )
            .replace(
                Regex(
                    "\\s+"
                ),
                " "
            )
            .trim()
    }

    private fun compactSearchText(
        value: String
    ): String {

        return normalizeSearchText(
            value
        ).replace(
            " ",
            ""
        )
    }

    /*
     * ============================================================
     * LEVENSHTEIN
     * ============================================================
     */
    private fun levenshtein(
        a: String,
        b: String
    ): Int {

        if (a == b) {
            return 0
        }

        if (a.isEmpty()) {
            return b.length
        }

        if (b.isEmpty()) {
            return a.length
        }

        var previous =
            IntArray(
                b.length + 1
            ) {
                it
            }

        var current =
            IntArray(
                b.length + 1
            )

        for (i in a.indices) {

            current[0] =
                i + 1

            for (j in b.indices) {

                val cost =
                    if (a[i] == b[j]) {
                        0
                    } else {
                        1
                    }

                current[j + 1] =
                    minOf(
                        current[j] + 1,
                        previous[j + 1] + 1,
                        previous[j] + cost
                    )
            }

            val temp =
                previous

            previous =
                current

            current =
                temp
        }

        return previous[
            b.length
        ]
    }

    private fun similarity(
        a: String,
        b: String
    ): Double {

        if (a == b) {
            return 1.0
        }

        if (
            a.isBlank() ||
            b.isBlank()
        ) {
            return 0.0
        }

        if (
            a.contains(b) ||
            b.contains(a)
        ) {

            val minLength =
                minOf(
                    a.length,
                    b.length
                ).toDouble()

            val maxLength =
                maxOf(
                    a.length,
                    b.length
                ).toDouble()

            return (
                0.80 +
                    0.20 *
                    (
                        minLength /
                            maxLength
                        )
                )
        }

        val distance =
            levenshtein(
                a,
                b
            )

        val maxLength =
            maxOf(
                a.length,
                b.length
            )

        if (maxLength == 0) {
            return 0.0
        }

        return (
            1.0 -
                distance.toDouble() /
                maxLength.toDouble()
            ).coerceIn(
                0.0,
                1.0
            )
    }

    /*
     * ============================================================
     * SEARCH SCORE
     * ============================================================
     */
    private fun searchScore(
        query: String,
        title: String
    ): Double {

        val q =
            normalizeSearchText(
                query
            )

        val t =
            normalizeSearchText(
                title
            )

        if (
            q.isBlank() ||
            t.isBlank()
        ) {
            return 0.0
        }

        if (q == t) {
            return 1.0
        }

        val qc =
            compactSearchText(q)

        val tc =
            compactSearchText(t)

        if (
            qc.isNotBlank() &&
            qc == tc
        ) {
            return 0.995
        }

        var score = 0.0

        /*
         * Full substring match
         */
        if (t.contains(q)) {

            score =
                maxOf(
                    score,
                    0.97
                )
        }

        /*
         * Spacing-insensitive match
         */
        if (
            qc.isNotBlank() &&
            tc.contains(qc)
        ) {

            val ratio =
                qc.length.toDouble() /
                    tc.length
                        .coerceAtLeast(1)
                        .toDouble()

            score =
                maxOf(
                    score,
                    0.86 +
                        ratio * 0.11
                )
        }

        /*
         * Token-by-token matching
         */
        val qTokens =
            q.split(
                " "
            ).filter {
                it.length >= 2
            }

        val tTokens =
            t.split(
                " "
            ).filter {
                it.length >= 2
            }

        if (
            qTokens.isNotEmpty() &&
            tTokens.isNotEmpty()
        ) {

            val tokenScore =
                qTokens
                    .map { qToken ->

                        tTokens
                            .maxOfOrNull {
                                tToken ->

                                when {

                                    tToken ==
                                        qToken ->
                                        1.0

                                    tToken.startsWith(
                                        qToken
                                    ) ||
                                        qToken.startsWith(
                                            tToken
                                        ) ->
                                        0.92

                                    else ->
                                        similarity(
                                            qToken,
                                            tToken
                                        )
                                }
                            }
                            ?: 0.0

                    }
                    .average()

            score =
                maxOf(
                    score,
                    tokenScore
                )
        }

        /*
         * Whole-string fuzzy matching
         */
        score =
            maxOf(
                score,
                similarity(
                    qc,
                    tc
                )
            )

        return score.coerceIn(
            0.0,
            1.0
        )
    }

    /*
     * ============================================================
     * NEXT PAGE
     * ============================================================
     */
    private fun hasNextPage(
        document: Document
    ): Boolean {

        return document.selectFirst(
            "link[rel='next']"
        ) != null ||

            document.selectFirst(
                "a.next"
            ) != null ||

            document.selectFirst(
                "a[rel='next']"
            ) != null ||

            document.selectFirst(
                ".pagination a.next"
            ) != null ||

            document.selectFirst(
                ".nav-links a.next"
            ) != null
    }

    /*
     * ============================================================
     * CONSTANTS
     * ============================================================
     */
    private companion object {

        const val MAX_PLAYBACK_PAGES = 10

        const val MAX_CRAWL_DEPTH = 3

        const val MAX_NESTED_PAGES = 10

        const val MAX_PLAYBACK_LINKS = 12

        const val MAX_ITEMS_PER_PAGE = 30

        const val MAX_MOVIE_PAGES = 12

        const val SEARCH_NATIVE_LIMIT = 80

        const val SEARCH_FALLBACK_MINIMUM = 20

        const val SEARCH_FALLBACK_LIMIT = 100

        const val SEARCH_MIN_SCORE = 0.32
    }
}
