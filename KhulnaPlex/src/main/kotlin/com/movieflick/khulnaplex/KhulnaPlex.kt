package com.movieflick.khulnaplex

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

class KhulnaPlex : MainAPI() {

    override var mainUrl = "http://khulnaplex.com"
    override var name = "Khulna Plex"
    override var lang = "bn"

    override val hasMainPage = true
    override val hasQuickSearch = true

    override val supportedTypes = setOf(
        TvType.Movie,
        TvType.TvSeries,
        TvType.Anime
    )

    override val mainPage = mainPageOf(
        "$mainUrl/movies.php" to "All Movies",
        "$mainUrl/movies.php?category=hindi" to "Hindi Movies",
        "$mainUrl/movies.php?category=english" to "English Movies",
        "$mainUrl/movies.php?category=bangla" to "Bangla Movies",
        "$mainUrl/movies.php?category=dub" to "Dubbed Movies",
        "$mainUrl/series.php" to "TV Shows",
        "$mainUrl/movies.php?category=animation" to "Anime"
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
     * Page requests use normal browser headers.
     *
     * IMPORTANT:
     * Media playback intentionally does NOT reuse this full header set.
     * The verified Khulna Plex MP4 URL plays directly in Android Chrome,
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
        "Referer" to referer
    )

    private suspend fun getDocument(url: String): Document? {
        val normalized = url.trim()
        if (normalized.isBlank()) return null

        val candidates = linkedSetOf<String>()
        candidates.add(normalized)

        // Only use the alternate scheme for Khulna Plex page loading.
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
        val url = pageUrl(request.data, page)
        val document = getDocument(url)
            ?: return newHomePageResponse(request, emptyList(), false)

        /*
         * Khulna Plex puts many cards on one listing page.
         * Keep the home request fast: parse only the current page.
         */
        val items = parseItems(
            document = document,
            sourceUrl = url,
            sectionName = request.name,
            page = page
        ).take(maxHomeItems)

        return newHomePageResponse(
            request,
            items.map { it.toSearchResponse() },
            hasNextPage(document, page)
        )
    }

    override suspend fun search(
        query: String,
        page: Int
    ): SearchResponseList {
        val q = query.trim()
        if (q.isBlank()) {
            return newSearchResponseList(emptyList(), false)
        }

        val encoded = URLEncoder.encode(
            q,
            StandardCharsets.UTF_8.toString()
        )

        val candidates = listOf(
            "$mainUrl/search.php?q=$encoded${if (page > 1) "&page=$page" else ""}",
            "$mainUrl/search.php?query=$encoded${if (page > 1) "&page=$page" else ""}",
            "$mainUrl/search.php?search=$encoded${if (page > 1) "&page=$page" else ""}"
        )

        for (url in candidates) {
            val document = getDocument(url) ?: continue

            val items = parseItems(
                document = document,
                sourceUrl = url,
                sectionName = "Search",
                page = page
            )

            if (items.isNotEmpty()) {
                return newSearchResponseList(
                    items.take(maxHomeItems).map { it.toSearchResponse() },
                    hasNextPage(document, page)
                )
            }
        }

        return newSearchResponseList(emptyList(), false)
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

        val document = getDocument(url)

        if (document == null) {
            return newMovieLoadResponse(
                titleFromUrl(url),
                url,
                TvType.Movie,
                url
            )
        }

        val title = extractPageTitle(document)
            .ifBlank { titleFromUrl(url) }

        val poster = extractPoster(document, url)
        val series = isSeriesUrl(url) || looksLikeSeriesPage(document)

        if (series) {
            val episodes = parseEpisodes(document, url)

            if (episodes.isNotEmpty()) {
                return newTvSeriesLoadResponse(
                    title,
                    url,
                    TvType.TvSeries,
                    episodes
                ) {
                    posterUrl = poster
                }
            }
        }

        /*
         * Keep the watch page as the canonical data.
         *
         * Playback is resolved at Play time by loadLinks(), which fetches
         * the same page and extracts the exact media URL currently published
         * inside <source src="..."> / <video> / player metadata.
         */
        return newMovieLoadResponse(
            title,
            url,
            if (isAnimeUrl(url)) TvType.Anime else TvType.Movie,
            url
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

        /*
         * Direct media URL:
         * pass the exact URL straight to the native player.
         */
        if (isMediaUrl(input)) {
            emitMediaLink(
                mediaUrl = input,
                callback = callback
            )
            return true
        }

        /*
         * Normal Khulna Plex watch page:
         * fetch it and resolve the REAL media file published by the site.
         */
        val response = runCatching {
            app.get(
                input,
                headers = pageHeaders(input)
            )
        }.getOrNull() ?: return false

        val document = response.document
        val html = response.text

        /*
         * 1) Prefer the real <video>/<source> file.
         * 2) If no source exists, use the "Download Video" URL only to
         *    recover its `file=` path and rebuild the DIRECT media URL.
         *
         * We do NOT play download.php itself because it may be served as
         * Content-Disposition: attachment. The actual /uploads/videos/...mp4
         * file is what Chrome plays directly on Android.
         */
        val directSources = extractMediaUrls(
            document = document,
            html = html,
            baseUrl = input
        ).distinct()

        if (directSources.isNotEmpty()) {
            directSources.forEach { source ->
                emitMediaLink(
                    mediaUrl = source,
                    callback = callback
                )
            }
            return true
        }

        /*
         * Recover direct media paths from download.php links.
         */
        val recoveredSources = extractDirectMediaFromDownloads(
            document = document,
            html = html,
            baseUrl = input
        ).distinct()

        if (recoveredSources.isNotEmpty()) {
            recoveredSources.forEach { source ->
                emitMediaLink(
                    mediaUrl = source,
                    callback = callback
                )
            }
            return true
        }

        /*
         * Last fallback for externally embedded players.
         */
        val iframes = document.select(
            "iframe[src], iframe[data-src]"
        ).mapNotNull { iframe ->
            val raw = iframe.attr("src")
                .ifBlank { iframe.attr("data-src") }
                .trim()

            raw.takeIf { it.isNotBlank() }
                ?.let { absoluteUrl(it, input) }
        }.distinct()

        for (iframe in iframes) {
            val extracted = runCatching {
                loadExtractor(
                    iframe,
                    subtitleCallback,
                    callback
                )
            }.getOrDefault(false)

            if (extracted) return true
        }

        return false
    }

    /*
     * Emits a native CloudStream media source.
     *
     * No forced HTTP->HTTPS rewrite.
     * No artificial Range header.
     * No CORS/Sec-Fetch headers.
     *
     * The player itself handles the HTTP media request and byte-range
     * negotiation.
     */
    private suspend fun emitMediaLink(
        mediaUrl: String,
        callback: (ExtractorLink) -> Unit
    ) {
        val type = when {
            mediaUrl.contains(".m3u8", true) ->
                ExtractorLinkType.M3U8

            mediaUrl.contains(".mpd", true) ->
                ExtractorLinkType.DASH

            else ->
                ExtractorLinkType.VIDEO
        }

        val lower = mediaUrl.lowercase(Locale.ROOT)

        val quality = when {
            "2160" in lower || "4k" in lower ->
                Qualities.P2160.value

            "1440" in lower ->
                Qualities.P1440.value

            "1080" in lower ->
                Qualities.P1080.value

            "720" in lower ->
                Qualities.P720.value

            "480" in lower ->
                Qualities.P480.value

            "360" in lower ->
                Qualities.P360.value

            else ->
                Qualities.Unknown.value
        }

        callback(
            newExtractorLink(
                source = name,
                name = "Khulna Plex Direct",
                url = mediaUrl,
                type = type
            ) {
                this.quality = quality
            }
        )
    }

    private fun extractDirectMediaFromDownloads(
        document: Document,
        html: String,
        baseUrl: String
    ): List<String> {
        val found = linkedSetOf<String>()

        fun addDownload(raw: String?) {
            if (raw.isNullOrBlank()) return

            val cleaned = cleanUrl(raw)

            if (!cleaned.contains("download.php", true)) return

            val query = runCatching {
                URI(absoluteUrl(cleaned, baseUrl)).rawQuery.orEmpty()
            }.getOrDefault("")

            val fileValue = query
                .split('&')
                .firstOrNull {
                    it.substringBefore('=')
                        .equals("file", ignoreCase = true)
                }
                ?.substringAfter('=', "")

            if (fileValue.isNullOrBlank()) return

            val decoded = runCatching {
                URLDecoder.decode(
                    fileValue,
                    StandardCharsets.UTF_8.toString()
                )
            }.getOrNull()?.trim().orEmpty()

            if (decoded.isBlank()) return

            /*
             * Only recover a real media file path.
             * Example:
             * uploads/videos/1788517057_Neru_2023.mp4
             */
            if (!isMediaUrl("http://example.com/$decoded")) return

            val direct = if (
                decoded.startsWith("http://", true) ||
                decoded.startsWith("https://", true)
            ) {
                decoded
            } else {
                absoluteUrl(
                    if (decoded.startsWith("/")) decoded else "/$decoded",
                    baseUrl
                )
            }

            if (isMediaUrl(direct)) {
                found.add(direct)
            }
        }

        document.select(
            "a[href*='download.php'], " +
                "a.download-btn"
        ).forEach { anchor ->
            addDownload(anchor.attr("href"))
        }

        val cleanedHtml = html
            .replace("\\/", "/")
            .replace("\\u0026", "&")
            .replace("&amp;", "&")

        val regex = Regex(
            """(?i)(?:https?://[^"'<>\s]+|(?:/|\.\.?/)?download\.php\?[^"'<>\s)]+)"""
        )

        regex.findAll(cleanedHtml).forEach { match ->
            addDownload(match.value)
        }

        return found.toList()
    }

    /*
     * Extract every real media URL exposed by the watch page.
     *
     * Supports:
     * - <video src>
     * - <source src>
     * - data-video / data-src / data-file / ...
     * - URLs embedded in JavaScript player objects
     * - direct media files such as .mp4, .mkv, .webm, .m3u8, and .mpd
     */
    private fun extractMediaUrls(
        document: Document,
        html: String,
        baseUrl: String
    ): List<String> {
        val found = linkedSetOf<String>()

        fun add(raw: String?) {
            if (raw.isNullOrBlank()) return

            val value = cleanUrl(raw)
                .replace("\\x2F", "/")
                .trim()

            if (value.isBlank() || value.startsWith("data:", true) || value.startsWith("javascript:", true)) {
                return
            }

            /*
             * IMPORTANT: KhulnaPlex uses plain relative paths such as
             * `uploads/videos/1788517057_Neru_2023.mp4` without a leading `/`.
             * Always resolve relative URLs against the watch-page URL instead
             * of requiring the path to begin with '/', './' or '../'.
             */
            val fixed = absoluteUrl(value, baseUrl)

            if (isMediaUrl(fixed)) {
                found.add(fixed)
            }
        }

        /*
         * DOM first: this is the most trustworthy source because it matches
         * the website's actual <video>/<source> element.
         */
        document.select(
            "video[src], " +
                "video source[src], " +
                "source[src], " +
                "[src], " +
                "[data-src], " +
                "[data-video], " +
                "[data-file], " +
                "[data-url], " +
                "[data-source], " +
                "[data-stream], " +
                "[data-file-url], " +
                "[data-video-url], " +
                "[data-playlist], " +
                "[data-manifest]"
        ).forEach { element ->
            add(element.attr("src"))
            add(element.attr("data-src"))
            add(element.attr("data-video"))
            add(element.attr("data-file"))
            add(element.attr("data-url"))
            add(element.attr("data-source"))
            add(element.attr("data-stream"))
            add(element.attr("data-file-url"))
            add(element.attr("data-video-url"))
            add(element.attr("data-playlist"))
            add(element.attr("data-manifest"))
            add(element.attr("href"))
        }

        val cleanedHtml = html
            .replace("\\/", "/")
            .replace("\\u0026", "&")
            .replace("\\u003A", ":")
            .replace("&amp;", "&")

        /*
         * Direct media URL regex.
         */
        val directRegex = Regex(
            """(?i)(?:https?://[^"'<>\s\\]+|(?<![A-Za-z0-9_./-])(?:uploads/|videos/|media/|files/)[^"'<>\s\\]+)\.(?:m3u8|mpd|mp4|mkv|webm|mov|m4v|avi|flv|ts)(?:\?[^"'<>\s\\]*)?"""
        )

        directRegex.findAll(cleanedHtml).forEach {
            add(it.value)
        }

        /*
         * Player object / JavaScript variable fallback.
         */
        val keyRegex = Regex(
            """(?i)(?:file|src|source|url|video|videoUrl|media|mediaUrl|fileUrl|video_url|stream|streamUrl)\s*[:=]\s*["']([^"']+)["']"""
        )

        keyRegex.findAll(cleanedHtml).forEach {
            add(it.groupValues[1])
        }

        return found.toList()
    }

    /*
     * Parse Khulna Plex cards.
     *
     * The site uses:
     *   <div class="movie-card" onclick="openMovie(994)">
     *   <div class="movie-card" onclick="openSeries(99)">
     *
     * Titles come from the card's visible title / heading / image alt,
     * and the poster comes from the card's image.
     */
    private fun parseItems(
        document: Document,
        sourceUrl: String,
        sectionName: String,
        page: Int
    ): List<SiteItem> {
        val result = linkedMapOf<String, SiteItem>()

        var order = page.toLong() * 1_000_000L

        val elements = document.select(
            ".movie-card, " +
                ".movie-grid .movie-card, " +
                "a[href], " +
                "[data-href], " +
                "[data-url], " +
                "[data-link], " +
                "[onclick]"
        )

        elements.forEach { element ->
            val rawCandidates = listOf(
                element.attr("href"),
                element.attr("data-href"),
                element.attr("data-url"),
                element.attr("data-link"),
                element.attr("onclick")
            ).filter { it.isNotBlank() }

            val href = rawCandidates.asSequence()
                .mapNotNull { extractContentUrl(it) }
                .firstOrNull()
                ?: return@forEach

            val absolute = absoluteUrl(href, sourceUrl)

            if (!looksLikeContentLink(absolute)) {
                return@forEach
            }

            val card = findCard(element)

            val title = cleanTitle(
                extractCardTitle(element, card)
                    .ifBlank { titleFromUrl(absolute) }
            )

            if (title.isBlank() || isNavigationTitle(title)) {
                return@forEach
            }

            val series =
                isSeriesUrl(absolute) ||
                    sectionName.contains("TV", true) ||
                    absolute.contains("type=series", true) ||
                    absolute.contains("type=tv", true)

            if (!result.containsKey(absolute)) {
                result[absolute] = SiteItem(
                    title = title,
                    url = absolute,
                    poster = extractPosterFromElement(
                        card,
                        sourceUrl
                    ),
                    isSeries = series,
                    sortTime = extractSortTime(
                        card,
                        element
                    ),
                    discoveryOrder = order++
                )
            }
        }

        val values = result.values.toList()

        /*
         * Preserve Khulna Plex's own listing order.
         *
         * If the site exposes a real upload/created timestamp, use it.
         * Never treat a plain "2026" release year as upload time.
         */
        val hasRealTimestamp = values.any {
            it.sortTime > 0L
        }

        return if (hasRealTimestamp) {
            values.sortedWith(
                compareByDescending<SiteItem> { it.sortTime }
                    .thenBy { it.discoveryOrder }
            )
        } else {
            values.sortedBy {
                it.discoveryOrder
            }
        }
    }

    private fun extractSortTime(
        card: Element,
        anchor: Element
    ): Long {
        val values = listOf(
            card.attr("data-created"),
            card.attr("data-uploaded"),
            card.attr("data-upload-date"),
            card.attr("data-created-at"),
            card.attr("data-uploaded-at"),
            card.attr("data-published"),
            card.attr("data-published-at"),
            card.attr("datetime"),

            anchor.attr("data-created"),
            anchor.attr("data-uploaded"),
            anchor.attr("data-upload-date"),
            anchor.attr("data-created-at"),
            anchor.attr("data-uploaded-at"),
            anchor.attr("data-published"),
            anchor.attr("data-published-at"),
            anchor.attr("datetime"),

            card.selectFirst("time[datetime]")
                ?.attr("datetime")
        )
            .filterNotNull()
            .filter { it.isNotBlank() }

        for (value in values) {
            val clean = value.trim()

            if (Regex("""^\d{4}$""").matches(clean)) {
                continue
            }

            clean.toLongOrNull()?.let { number ->
                return if (number < 10_000_000_000L) {
                    number * 1000L
                } else {
                    number
                }
            }

            parseDate(clean)?.let {
                return it
            }
        }

        return 0L
    }

    private fun parseDate(
        value: String
    ): Long? {
        val patterns = listOf(
            "yyyy-MM-dd HH:mm:ss",
            "yyyy-MM-dd HH:mm",
            "yyyy-MM-dd",

            "dd-MM-yyyy HH:mm:ss",
            "dd-MM-yyyy HH:mm",
            "dd-MM-yyyy",

            "dd/MM/yyyy HH:mm:ss",
            "dd/MM/yyyy HH:mm",
            "dd/MM/yyyy",

            "MMM dd, yyyy HH:mm:ss",
            "MMM dd, yyyy HH:mm",
            "MMM dd, yyyy"
        )

        for (pattern in patterns) {
            val parsed = runCatching {
                SimpleDateFormat(
                    pattern,
                    Locale.ENGLISH
                ).parse(value)?.time
            }.getOrNull()

            if (parsed != null) {
                return parsed
            }
        }

        return null
    }

    private fun parseEpisodes(
        document: Document,
        baseUrl: String
    ): List<Episode> {
        val result = linkedMapOf<String, Episode>()

        document.select("a[href]").forEach { anchor ->
            val href = anchor.attr("href").trim()

            if (!looksLikeEpisodeLink(href)) {
                return@forEach
            }

            val absolute = absoluteUrl(
                href,
                baseUrl
            )

            if (absolute == baseUrl) {
                return@forEach
            }

            val title = cleanTitle(
                anchor.text().ifBlank {
                    "Episode"
                }
            )

            result[absolute] = newEpisode(
                absolute
            ) {
                name = title
                season = 1
                episode = episodeNumber(
                    anchor,
                    title,
                    absolute
                )
            }
        }

        /*
         * If the series page itself exposes one direct file,
         * expose it as Episode 1.
         */
        if (result.isEmpty()) {
            val direct = extractMediaUrls(
                document,
                document.html(),
                baseUrl
            ).firstOrNull()

            if (direct != null) {
                result[direct] = newEpisode(
                    direct
                ) {
                    name = "Episode 1"
                    season = 1
                    episode = 1
                }
            }
        }

        return result.values.sortedBy {
            it.episode ?: Int.MAX_VALUE
        }
    }

    private fun findCard(
        anchor: Element
    ): Element {
        var current: Element? = anchor

        repeat(8) {
            val element = current ?: return@repeat

            val cls = element
                .className()
                .lowercase(Locale.ROOT)

            if (
                element.select("img").isNotEmpty() ||
                cls.contains("card") ||
                cls.contains("movie") ||
                cls.contains("item") ||
                cls.contains("poster")
            ) {
                return element
            }

            current = element.parent()
        }

        return anchor
    }

    private fun extractCardTitle(
        anchor: Element,
        card: Element
    ): String {
        val selectors = listOf(
            ".title",
            ".movie-title",
            ".movie_name",
            ".name",
            "h1",
            "h2",
            "h3",
            "h4",
            "strong"
        )

        for (selector in selectors) {
            val text = card
                .selectFirst(selector)
                ?.text()
                ?.trim()
                .orEmpty()

            if (text.isNotBlank()) {
                return text
            }
        }

        val aria = anchor
            .attr("aria-label")
            .trim()

        if (aria.isNotBlank()) {
            return aria
        }

        val alt = card
            .selectFirst("img")
            ?.attr("alt")
            ?.trim()

        if (!alt.isNullOrBlank()) {
            return alt
        }

        return anchor.text().trim()
    }

    private fun extractPoster(
        document: Document,
        pageUrl: String
    ): String? {
        return extractPosterFromElement(
            document,
            pageUrl
        )
    }

    private fun extractPosterFromElement(
        element: Element,
        pageUrl: String
    ): String? {
        val videoPoster = element
            .select("video[poster], video[data-poster]")
            .asSequence()
            .mapNotNull {
                it.attr("poster")
                    .ifBlank {
                        it.attr("data-poster")
                    }
                    .takeIf(String::isNotBlank)
            }
            .map {
                absoluteUrl(
                    it,
                    pageUrl
                )
            }
            .firstOrNull()

        if (!videoPoster.isNullOrBlank()) {
            return videoPoster
        }

        val meta = element
            .selectFirst(
                "meta[property=og:image], " +
                    "meta[name=twitter:image]"
            )
            ?.attr("content")
            ?.trim()

        if (!meta.isNullOrBlank()) {
            return absoluteUrl(
                meta,
                pageUrl
            )
        }
