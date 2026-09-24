package com.movieflick.cineplexftp

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URI
import java.net.URLEncoder
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.text.SimpleDateFormat
import java.util.Locale
import org.json.JSONArray
import okhttp3.Headers

class CinePlexFTP : MainAPI() {

    override var mainUrl = "http://cineplexbd.net"
    override var name = "Cine Plex FTP"
    override var lang = "bn"

    override val hasMainPage = true
    override val hasQuickSearch = true

    override val supportedTypes = setOf(
        TvType.Movie,
        TvType.TvSeries,
        TvType.Anime
    )

    /*
     * HOME SECTIONS
     *
     * Movies -> Cine Plex's real "All Movies" feed.
     * The site renders the first 24 movies on category.php and then
     * appends more using load_more_movies.php?offset=24,48,72,...
     *
     * Other sections remain tied to their real Cine Plex categories.
     */
    /*
     * FIVE TOP-LEVEL SECTIONS
     *
     * Each section can merge several real Cine Plex pages into one
     * CloudStream home row.
     */
    override val mainPage = mainPageOf(
        "cineplex://movies" to "Movies",
        "cineplex://dual-audio" to "Dual Audio",
        "cineplex://hindi" to "Hindi",
        "cineplex://tv" to "TV Shows",
        "cineplex://anime" to "Anime"
    )

    private data class SiteItem(
        val title: String,
        val url: String,
        val poster: String?,
        val isSeries: Boolean,
        val sortTime: Long,
        val discoveryOrder: Long
    )

    private fun SiteItem.toSearchResponse(): SearchResponse {
        return if (isSeries) {
            newTvSeriesSearchResponse(title, url, TvType.TvSeries) {
                posterUrl = poster
            }
        } else {
            newMovieSearchResponse(title, url, TvType.Movie) {
                posterUrl = poster
            }
        }
    }

    private val mediaExtensions = setOf(
        ".m3u8",
        ".mpd",
        ".mp4",
        ".mkv",
        ".webm",
        ".mov",
        ".m4v",
        ".avi",
        ".flv",
        ".ts"
    )

    private val maxHomeItems = 25

    /*
     * Verified from Cine Plex's own All Movies page JavaScript:
     * the first 24 cards are rendered in the page; after that Cine Plex's
     * JavaScript requests 12 more cards at offsets 24, 36, 48, ...
     */
    private companion object {
        const val ALL_MOVIES_FIRST_BATCH_SIZE = 24
        const val ALL_MOVIES_LAZY_BATCH_SIZE = 12
        const val SEARCH_ALL_MOVIES_PAGES = 6
        const val SEARCH_CATEGORY_PAGES = 2
        const val SEARCH_TV_PAGES = 3
        const val ALL_TV_FIRST_BATCH_SIZE = 24
        const val ALL_TV_LAZY_BATCH_SIZE = 24
    }

    /*
     * Page requests use normal browser headers.
     *
     * IMPORTANT:
     * Media playback intentionally does NOT reuse this full header set.
     * The verified Cine Plex MP4 URL plays directly in Android Chrome,
     * so the safest CloudStream playback request is the exact source URL
     * with minimal metadata rather than an invented browser/CORS profile.
     */
    private fun pageHeaders(referer: String = "$mainUrl/"): Map<String, String> = mapOf(
        "User-Agent" to
            "Mozilla/5.0 (Linux; Android 13; Mobile) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36",
        "Accept" to
            "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
        "Accept-Language" to "en-US,en;q=0.9",
        "Cache-Control" to "no-cache",
        "Pragma" to "no-cache",
        "Referer" to referer
    )

    private suspend fun getDocument(url: String): Document? {
        val normalized = url.trim()
        if (normalized.isBlank()) return null

        val candidates = linkedSetOf<String>()
        candidates.add(normalized)

        // Only use the alternate scheme for Cine Plex page loading.
        // We do NOT change the scheme of discovered media URLs.
        if (normalized.startsWith("http://", true)) {
            candidates.add("https://" + normalized.removePrefix("http://"))
        } else if (normalized.startsWith("https://", true)) {
            candidates.add("http://" + normalized.removePrefix("https://"))
        }

        for (candidate in candidates) {
            val document = runCatching {
                app.get(candidate, headers = pageHeaders(candidate)).document
            }.getOrNull()

            if (document != null) {
                return document
            }
        }

        return null
    }

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        return when (request.data) {
            "cineplex://movies" -> {
                getAllMoviesHomePage(
                    request = request,
                    page = page
                )
            }

            "cineplex://dual-audio" -> {
                getMergedHomePage(
                    request = request,
                    page = page,
                    sources = listOf(
                        "$mainUrl/category.php?category=Dual+Audio",
                        "$mainUrl/category.php?category=Hindi+Dubbed"
                    )
                )
            }

            "cineplex://hindi" -> {
                getMergedHomePage(
                    request = request,
                    page = page,
                    sources = listOf(
                        "$mainUrl/category.php?category=Hindi"
                    )
                )
            }

            "cineplex://tv" -> {
                getAllTvSeriesHomePage(
                    request = request,
                    page = page
                )
            }

            "cineplex://anime" -> {
                getMergedHomePage(
                    request = request,
                    page = page,
                    sources = listOf(
                        "$mainUrl/category.php?category=Anime",
                        "$mainUrl/category.php?category=Animation"
                    )
                )
            }

            else -> {
                val url = pageUrl(request.data, page)
                val document = getDocument(url)
                    ?: return newHomePageResponse(
                        request,
                        emptyList(),
                        false
                    )

                val items = parseItems(
                    document = document,
                    sourceUrl = url,
                    sectionName = request.name,
                    page = page
                ).take(maxHomeItems)

                newHomePageResponse(
                    request,
                    items.map { it.toSearchResponse() },
                    hasNextPage(document, page)
                )
            }
        }
    }

    private suspend fun getAllMoviesHomePage(
        request: MainPageRequest,
        page: Int
    ): HomePageResponse {
        val items = getAllMoviesItems(page, request.name)

        return newHomePageResponse(
            request,
            items
                .take(maxHomeItems)
                .map { it.toSearchResponse() },
            if (page <= 1) {
                items.size >= ALL_MOVIES_FIRST_BATCH_SIZE
            } else {
                items.size >= ALL_MOVIES_LAZY_BATCH_SIZE
            }
        )
    }

    private suspend fun getAllTvSeriesHomePage(
        request: MainPageRequest,
        page: Int
    ): HomePageResponse {
        val items = getAllTvSeriesItems(page)
        val pageNumber = page.coerceAtLeast(1)

        return newHomePageResponse(
            request,
            items.take(maxHomeItems).map {
                it.copy(isSeries = true).toSearchResponse()
            },
            if (pageNumber == 1) {
                items.size >= ALL_TV_FIRST_BATCH_SIZE
            } else {
                items.size >= ALL_TV_LAZY_BATCH_SIZE
            }
        )
    }

    private suspend fun getAllTvSeriesItems(page: Int): List<SiteItem> {
        val pageNumber = page.coerceAtLeast(1)

        if (pageNumber == 1) {
            val url = "$mainUrl/tvs.php"
            val document = getDocument(url) ?: return emptyList()
            val tvGrid = document.selectFirst("#tvGrid") ?: return emptyList()

            val gridDocument = org.jsoup.Jsoup.parse(
                "<html><body></body></html>",
                "$mainUrl/"
            )
            gridDocument.body().appendChild(tvGrid.clone())

            return parseItems(
                document = gridDocument,
                sourceUrl = url,
                sectionName = "TV Shows",
                page = 1
            ).map { it.copy(isSeries = true) }
                .take(ALL_TV_FIRST_BATCH_SIZE)
        }

        val offset = ALL_TV_FIRST_BATCH_SIZE +
            ((pageNumber - 2) * ALL_TV_LAZY_BATCH_SIZE)
        val endpoint = "$mainUrl/tvs_more.php?offset=$offset&limit=$ALL_TV_LAZY_BATCH_SIZE"

        val response = runCatching {
            app.get(
                endpoint,
                headers = pageHeaders("$mainUrl/tvs.php") + mapOf(
                    "Cache-Control" to "no-cache, no-store, max-age=0",
                    "Pragma" to "no-cache"
                )
            )
        }.getOrNull() ?: return emptyList()

        val raw = response.text.trim()
        if (raw.isBlank()) return emptyList()

        val array = runCatching { JSONArray(raw) }.getOrNull() ?: return emptyList()
        val result = linkedMapOf<String, SiteItem>()

        for (index in 0 until array.length()) {
            val item = array.optJSONObject(index) ?: continue
            val id = item.optString("id").trim()
            val title = cleanTitle(item.optString("title").trim())
            if (id.isBlank() || title.isBlank()) continue

            val encodedId = URLEncoder.encode(id, StandardCharsets.UTF_8.toString())
            val absolute = "$mainUrl/watch.php?series_id=$encodedId&autoplay=1"
            val posterRaw = item.optString("poster").trim()
            val poster = posterRaw.takeIf { it.isNotBlank() }?.let {
                absoluteUrl(it, "$mainUrl/tvs.php")
            }

            result.putIfAbsent(absolute, SiteItem(
                title = title,
                url = absolute,
                poster = poster,
                isSeries = true,
                sortTime = parseJsonTimestamp(item.optString("created_at").trim()),
                discoveryOrder = pageNumber.toLong() * 1_000_000L + index
            ))
        }

        return result.values.toList().take(ALL_TV_LAZY_BATCH_SIZE)
    }

    private fun parseJsonTimestamp(value: String): Long {
        if (value.isBlank()) return 0L
        value.toLongOrNull()?.let { number ->
            return if (number < 10_000_000_000L) number * 1000L else number
        }
        return parseDate(value) ?: 0L
    }

    private suspend fun getMergedHomePage(
        request: MainPageRequest,
        page: Int,
        sources: List<String>
    ): HomePageResponse {
        val merged = linkedMapOf<String, SiteItem>()
        var anySourceHasNext = false

        for (source in sources) {
            val items = if (source.equals("$mainUrl/category.php", true)) {
                getAllMoviesItems(page, request.name)
            } else {
                getCategoryItems(
                    sourceUrl = source,
                    page = page,
                    sectionName = request.name
                )
            }

            items.forEach { item ->
                merged.putIfAbsent(item.url, item)
            }

            if (source.equals("$mainUrl/category.php", true)) {
                /*
                 * All Movies uses the site's lazy endpoint. If this page
                 * returned a full 24-item batch, another batch may exist.
                 */
                if (items.size >= ALL_MOVIES_LAZY_BATCH_SIZE) {
                    anySourceHasNext = true
                }
            } else {
                val document = getDocument(pageUrl(source, page))
                if (document != null && hasNextPage(document, page)) {
                    anySourceHasNext = true
                }
            }
        }

        val responses = merged.values
            .take(maxHomeItems)
            .map { it.toSearchResponse() }

        return newHomePageResponse(
            request,
            responses,
            anySourceHasNext
        )
    }

    private suspend fun getCategoryItems(
        sourceUrl: String,
        page: Int,
        sectionName: String
    ): List<SiteItem> {
        val url = pageUrl(sourceUrl, page)
        val document = getDocument(url) ?: return emptyList()

        return parseItems(
            document = document,
            sourceUrl = url,
            sectionName = sectionName,
            page = page
        )
    }

    private suspend fun getAllMoviesItems(
        page: Int,
        sectionName: String
    ): List<SiteItem> {
        val pageNumber = page.coerceAtLeast(1)

        if (pageNumber == 1) {
            val url = "$mainUrl/category.php"
            val document = getDocument(url) ?: return emptyList()

            /*
             * CRITICAL: category.php contains several sections, including
             * Weekly Top 20 Trending. The real All Movies list is ONLY the
             * #movieGrid element. Parsing the entire document mixes those
             * trending cards into the Movies section.
             */
            val movieGrid = document.selectFirst("#movieGrid") ?: return emptyList()
            val gridDocument = org.jsoup.Jsoup.parse(
                "<html><body></body></html>",
                "$mainUrl/"
            )
            gridDocument.body().appendChild(movieGrid.clone())

            return parseItems(
                document = gridDocument,
                sourceUrl = url,
                sectionName = sectionName,
                page = 1
            ).take(ALL_MOVIES_FIRST_BATCH_SIZE)
        }

        val offset = ALL_MOVIES_FIRST_BATCH_SIZE +
            ((pageNumber - 2) * ALL_MOVIES_LAZY_BATCH_SIZE)
        val endpoint = "$mainUrl/load_more_movies.php?offset=$offset"

        val response = runCatching {
            app.get(
                endpoint,
                headers = pageHeaders("$mainUrl/category.php")
            )
        }.getOrNull() ?: return emptyList()

        val html = response.text.trim()
        if (html.isBlank()) return emptyList()

        val document = org.jsoup.Jsoup.parse(
            "<html><body>$html</body></html>",
            "$mainUrl/"
        )

        return parseItems(
            document = document,
            sourceUrl = "$mainUrl/category.php",
            sectionName = sectionName,
            page = pageNumber
        ).take(ALL_MOVIES_LAZY_BATCH_SIZE)
    }

    override suspend fun search(
        query: String,
        page: Int
    ): SearchResponseList {
        val q = query.trim()
        if (q.isBlank()) return newSearchResponseList(emptyList(), false)

        val normalized = normalizeSearchText(q)
        val compact = compactSearchText(q)
        val variants = linkedSetOf<String>().apply {
            add(q)
            if (normalized.isNotBlank()) add(normalized)
            if (compact.isNotBlank()) add(compact)
        }

        /* First try Cine Plex's native search with equivalent query forms. */
        val native = linkedMapOf<String, SiteItem>()
        for (variant in variants) {
            val encoded = URLEncoder.encode(variant, StandardCharsets.UTF_8.toString())
            val candidates = listOf(
                "$mainUrl/search.php?q=$encoded${if (page > 1) "&page=$page" else ""}",
                "$mainUrl/search.php?query=$encoded${if (page > 1) "&page=$page" else ""}",
                "$mainUrl/search.php?search=$encoded${if (page > 1) "&page=$page" else ""}"
            )

            for (url in candidates) {
                val document = getDocument(url) ?: continue
                parseItems(document, url, "Search", page).forEach { item ->
                    native.putIfAbsent(item.url, item)
                }
            }
        }

        if (native.isNotEmpty()) {
            val ranked = rankSearchResults(q, native.values.toList())
            val strongest = ranked.firstOrNull()?.let { searchScore(q, it.title) } ?: 0.0

            if (strongest >= 0.72) {
                return newSearchResponseList(
                    ranked.take(maxHomeItems).map { it.toSearchResponse() },
                    false
                )
            }
        }

        /*
         * Local fallback for strict/limited site-search behavior.
         * Search is punctuation/space-insensitive and scans enough of the
         * real All Movies stream plus TV pages to find titles that the native
         * search misses.
         */
        val fallback = linkedMapOf<String, SiteItem>()

        for (scanPage in 1..SEARCH_ALL_MOVIES_PAGES) {
            getAllMoviesItems(scanPage, "Search").forEach { item ->
                fallback.putIfAbsent(item.url, item)
            }
        }

        val movieSources = listOf(
            "$mainUrl/category.php?category=Indian+Bangla",
            "$mainUrl/category.php?category=Korean",
            "$mainUrl/category.php?category=3D+Movies",
            "$mainUrl/category.php?category=Bangla+Dubbed",
            "$mainUrl/category.php?category=Bangla+Movies",
            "$mainUrl/category.php?category=English",
            "$mainUrl/category.php?category=Dual+Audio",
            "$mainUrl/category.php?category=Hindi+Dubbed",
            "$mainUrl/category.php?category=Hindi",
            "$mainUrl/category.php?category=Anime",
            "$mainUrl/category.php?category=Animation"
        )

        for (source in movieSources) {
            for (scanPage in 1..SEARCH_CATEGORY_PAGES) {
                val url = pageUrl(source, scanPage)
                val document = getDocument(url) ?: continue
                parseItems(document, url, "Search", scanPage).forEach { item ->
                    fallback.putIfAbsent(item.url, item)
                }
            }
        }

        for (scanPage in 1..SEARCH_TV_PAGES) {
            getAllTvSeriesItems(scanPage).forEach { item ->
                fallback.putIfAbsent(item.url, item.copy(isSeries = true))
            }
        }

        val ranked = rankSearchResults(q, fallback.values.toList())
        val start = (page - 1).coerceAtLeast(0) * maxHomeItems

        return newSearchResponseList(
            ranked
                .drop(start)
                .take(maxHomeItems)
                .map { it.toSearchResponse() },
            false
        )
    }

    private fun normalizeSearchText(value: String): String {
        return java.text.Normalizer
            .normalize(value, java.text.Normalizer.Form.NFKC)
            .lowercase(Locale.ROOT)
            .replace("&", " and ")
            .replace(Regex("[\u2010-\u2015\u2212\u2043\u30A0\u30FC]"), "-")
            .replace(Regex("[^a-z0-9\\p{L}]+"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    private fun compactSearchText(value: String): String =
        normalizeSearchText(value).replace(" ", "")

    private fun levenshtein(a: String, b: String): Int {
        if (a == b) return 0
        if (a.isEmpty()) return b.length
        if (b.isEmpty()) return a.length
        var prev = IntArray(b.length + 1) { it }
        var curr = IntArray(b.length + 1)
        for (i in a.indices) {
            curr[0] = i + 1
            for (j in b.indices) {
                val cost = if (a[i] == b[j]) 0 else 1
                curr[j + 1] = minOf(
                    curr[j] + 1,
                    prev[j + 1] + 1,
                    prev[j] + cost
                )
            }
            val tmp = prev; prev = curr; curr = tmp
        }
        return prev[b.length]
    }

    private fun similarity(a: String, b: String): Double {
        if (a == b) return 1.0
        if (a.isBlank() || b.isBlank()) return 0.0
        if (a.contains(b) || b.contains(a)) {
            val minLen = minOf(a.length, b.length).toDouble()
            val maxLen = maxOf(a.length, b.length).toDouble()
            return 0.80 + 0.20 * (minLen / maxLen)
        }
        val distance = levenshtein(a, b)
        return 1.0 - distance.toDouble() / maxOf(a.length, b.length)
    }

    private fun searchScore(query: String, title: String): Double {
        val qn = normalizeSearchText(query)
        val tn = normalizeSearchText(title)
        if (qn.isBlank() || tn.isBlank()) return 0.0
        if (qn == tn) return 1.0

        val qc = compactSearchText(query)
        val tc = compactSearchText(title)
        if (qc.isNotBlank() && qc == tc) return 0.995

        var score = 0.0

        if (tn.contains(qn)) {
            score = maxOf(score, 0.97)
        }

        if (qc.isNotBlank() && tc.contains(qc)) {
            val ratio = qc.length.toDouble() / tc.length.coerceAtLeast(1).toDouble()
            score = maxOf(score, 0.86 + (ratio * 0.11))
        }

        val qTokens = qn.split(' ').filter { it.length >= 2 }
        val tTokens = tn.split(' ').filter { it.length >= 2 }
        if (qTokens.isNotEmpty() && tTokens.isNotEmpty()) {
            val tokenScore = qTokens.map { qt ->
                tTokens.maxOfOrNull { tt ->
                    when {
                        tt == qt -> 1.0
                        tt.startsWith(qt) || qt.startsWith(tt) -> 0.92
                        else -> similarity(qt, tt)
                    }
                } ?: 0.0
            }.average()
            score = maxOf(score, tokenScore)
        }

        score = maxOf(score, similarity(qc, tc))
        return score.coerceIn(0.0, 1.0)
    }

    private fun rankSearchResults(query: String, items: List<SiteItem>): List<SiteItem> {
        return items
            .map { it to searchScore(query, it.title) }
            .filter { it.second >= 0.34 }
            .sortedWith(compareByDescending<Pair<SiteItem, Double>> { it.second }.thenBy { it.first.title })
            .map { it.first }
    }

    override suspend fun load(url: String): LoadResponse {
        if (isMediaUrl(url)) {
            return newMovieLoadResponse(
                titleFromUrl(url),
                url,
                if (isAnimeUrl(url)) TvType.Anime else TvType.Movie,
                url
            )
        }

        val detailUrl = normalizeContentUrl(url)
        val document = getDocument(detailUrl)

        if (document == null) {
            return newMovieLoadResponse(
                titleFromUrl(url),
                detailUrl,
                if (isAnimeUrl(url)) TvType.Anime else TvType.Movie,
                detailUrl
            )
        }

        val title = extractPageTitle(document)
            .ifBlank { titleFromUrl(url) }

        val poster = extractPoster(document, detailUrl)
        val series =
            isSeriesUrl(url) ||
                looksLikeSeriesPage(document) ||
                detailUrl.contains("watch.php?id=", true) ||
                detailUrl.contains("watch.php?series_id=", true) ||
                detailUrl.contains("watch.php?series-id=", true)

        if (series) {
            val episodes = parseEpisodes(document, detailUrl)
            if (episodes.isNotEmpty()) {
                return newTvSeriesLoadResponse(
                    title,
                    detailUrl,
                    TvType.TvSeries,
                    episodes
                ) {
                    posterUrl = poster
                }
            }
        }

        /*
         * IMPORTANT:
         * A Cine Plex All Movies card normally points to view.php?id=...
         * The actual full movie player is player.php?id=... .
         * Keep the detail page for metadata, but hand the player page to
         * loadLinks() as the canonical playback data.
         */
        val playbackUrl = toPlayerUrl(detailUrl) ?: detailUrl

        return newMovieLoadResponse(
            title,
            detailUrl,
            if (isAnimeUrl(url)) TvType.Anime else TvType.Movie,
            playbackUrl
        ) {
            posterUrl = poster
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val input = data.trim()
        if (input.isBlank()) return false

        if (isMediaUrl(input)) {
            if (isCinePlexTvMediaUrl(input)) {
                emitTvMediaLink(input, callback, "$mainUrl/")
                return true
            }
            if (isCinePlexFullMediaUrl(input)) {
                emitMediaLink(input, callback)
                return true
            }
            return false
        }

        /*
         * TV SERIES ONLY:
         * Cine Plex episode links use watch.php?id=...&season=...&ep=... .
         * The real episode source is an HLS master.m3u8 under /hls/tr/.
         * Handle that exact player flow before the movie player logic below.
         */
        if (input.contains("watch.php", true)) {
            /*
             * TV PLAYBACK — COOKIE-PRESERVED, BROWSER-ALIGNED HLS FLOW
             *
             * Verified from multiple Cine Plex episode pages:
             *   episode page -> <source .../Episode.mp4/master.m3u8>
             *   browser/HLS.js -> same directory -> index-v1-a1.m3u8
             *
             * The successful browser request also carries Cine Plex session
             * cookies (hlse/hlsk/cp_device_id/REDFORCE). Therefore the TV
             * resolver must preserve cookies obtained from Cine Plex page and
             * manifest requests and attach them to the final HLS ExtractorLink.
             *
             * We never invent the direct playlist name when the master
             * manifest itself exposes a concrete child .m3u8. We first parse
             * that child URL from the master's actual content. A deterministic
             * index-v1-a1 fallback is kept only when the master response does
             * not expose a child URI, because that is the browser-observed
             * Cine Plex naming convention.
             */
            val cleanEpisodeUrl = input.substringBefore('#').trim()
            if (cleanEpisodeUrl.isBlank()) return false

            val cookieJar = linkedMapOf<String, String>()
            val discoveredM3u8 = linkedSetOf<String>()

            /*
             * Establish a Cine Plex web session before touching the HLS
             * manifest. The site can set/refresh cookies on normal page loads.
             */
            val bootstrapUrls = linkedSetOf(
                "$mainUrl/",
                "$mainUrl/tvs.php",
                cleanEpisodeUrl
            )

            for (bootstrapUrl in bootstrapUrls) {
                val response = runCatching {
                    app.get(
                        bootstrapUrl,
                        headers = pageHeaders(
                            if (bootstrapUrl == cleanEpisodeUrl) "$mainUrl/" else "$mainUrl/"
                        )
                    )
                }.getOrNull() ?: continue

                captureSetCookies(response.headers, cookieJar)

                /*
                 * The episode page itself is the authoritative source for the
                 * published master manifest.
                 */
                if (bootstrapUrl == cleanEpisodeUrl) {
                    extractAnyCinePlexM3u8(
                        html = response.text,
                        baseUrl = bootstrapUrl
                    ).forEach { discoveredM3u8.add(cleanUrl(it)) }
                }
            }

            /*
             * Also try the episode page variants in case autoplay/page mode
             * affects which cookies or player markup are returned.
             */
            if (discoveredM3u8.isEmpty()) {
                for (episodePageUrl in buildTvEpisodePageCandidates(cleanEpisodeUrl)) {
                    val requestHeaders = pageHeaders("$mainUrl/") +
                        cookieHeaderMap(cookieJar)

                    val response = runCatching {
                        app.get(
                            episodePageUrl,
                            headers = requestHeaders
                        )
                    }.getOrNull() ?: continue

                    captureSetCookies(response.headers, cookieJar)

                    extractAnyCinePlexM3u8(
                        html = response.text,
                        baseUrl = episodePageUrl
                    ).forEach { discoveredM3u8.add(cleanUrl(it)) }

                    if (discoveredM3u8.isNotEmpty()) break
                }
            }

            /*
             * The site's own page source publishes master.m3u8. Prefer that
             * exact published URL.
             */
            val masterUrls = discoveredM3u8
                .filter {
                    it.substringBefore('?')
                        .lowercase(Locale.ROOT)
                        .endsWith("/master.m3u8")
                }
                .toList()

            if (masterUrls.isEmpty() && discoveredM3u8.isEmpty()) {
                return false
            }

            val candidateMasters =
                if (masterUrls.isNotEmpty()) masterUrls
                else discoveredM3u8.toList()

            /*
             * Ask the actual master manifest for its real HLS child playlist.
             * This is the key step missing from the older implementation:
             * do not manufacture index-v1-a1 unless the master did not expose
             * a child URI.
             */
            val childPlaylists = linkedSetOf<String>()

            for (masterUrl in candidateMasters) {
                for (requestUrl in tvManifestUrlVariants(masterUrl)) {
                    val headers = tvExactHlsHeaders(
                        episodeUrl = cleanEpisodeUrl,
                        cookieJar = cookieJar
                    )

                    val response = runCatching {
                        app.get(
                            requestUrl,
                            headers = headers
                        )
                    }.getOrNull() ?: continue

                    captureSetCookies(response.headers, cookieJar)

                    if (response.text.isBlank()) continue

                    extractHlsPlaylistUrisFromManifest(
                        manifestText = response.text,
                        manifestUrl = requestUrl
                    ).forEach { childPlaylists.add(cleanUrl(it)) }

                    /*
                     * If the master itself is a valid media playlist, retain it
                     * as a last-resort playable manifest.
                     */
                    if (
                        response.text.contains("#EXTINF", ignoreCase = true) ||
                        response.text.contains("#EXT-X-TARGETDURATION", ignoreCase = true)
                    ) {
                        discoveredM3u8.add(cleanUrl(requestUrl))
                    }

                    if (childPlaylists.isNotEmpty()) break
                }

                if (childPlaylists.isNotEmpty()) break
            }

            /*
             * Prefer a concrete media playlist under the same .mp4/.mkv/.../
             * directory. No playlist filename is assumed here.
             */
            val exactChild = childPlaylists.firstOrNull {
                isExactCinePlexHlsUrl(it)
            }

            /*
             * Browser-observed fallback. Only use this when the master itself
             * did not expose a child playlist in its response body.
             */
            val deterministicA1 =
                if (exactChild == null) {
                    val master = masterUrls.firstOrNull()
                        ?: candidateMasters.firstOrNull()

                    master?.let {
                        it.substringBefore('?')
                            .replace(
                                Regex("/master\\.m3u8$", RegexOption.IGNORE_CASE),
                                "/index-v1-a1.m3u8"
                            )
                    }
                } else {
                    null
                }

            val playable =
                exactChild
                    ?: deterministicA1
                    ?: discoveredM3u8.firstOrNull { isExactCinePlexHlsUrl(it) }
                    ?: masterUrls.firstOrNull()
                    ?: discoveredM3u8.firstOrNull()

            if (playable.isNullOrBlank()) return false

            /*
             * Re-test the exact selected media playlist using the SAME session
             * cookies before handing it to CloudStream. This mirrors the
             * successful browser request and prevents an anonymous M3U8 link
             * from being emitted when Cine Plex requires session cookies.
             */
            val preflightHeaders = tvExactHlsHeaders(
                episodeUrl = cleanEpisodeUrl,
                cookieJar = cookieJar
            )

            val selectedUrl =
                runCatching {
                    val response = app.get(
                        cleanUrl(playable),
                        headers = preflightHeaders
                    )
                    captureSetCookies(response.headers, cookieJar)

                    if (
                        response.text.contains("#EXTM3U", ignoreCase = true) ||
                        response.text.contains("#EXT-X-STREAM-INF", ignoreCase = true) ||
                        response.text.contains("#EXTINF", ignoreCase = true)
                    ) {
                        cleanUrl(playable)
                    } else {
                        null
                    }
                }.getOrNull()

            /*
             * If a preflight response body could not be read as text but the URL
             * is known to be an exact Cine Plex HLS endpoint, still emit it with
             * the preserved session.
             */
            val finalUrl = selectedUrl ?: cleanUrl(playable)

            /*
             * Rebuild headers AFTER all page/master/media requests so any
             * Set-Cookie values learned during preflight are included too.
             */
            val finalHeaders = tvExactHlsHeaders(
                episodeUrl = cleanEpisodeUrl,
                cookieJar = cookieJar
            )

            emitTvMediaLink(
                mediaUrl = finalUrl,
                callback = callback,
                referer = cleanEpisodeUrl,