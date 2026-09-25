package com.movieflick.ctgftp

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URI
import java.text.SimpleDateFormat
import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.Locale
import java.util.TimeZone

/**
 * CTG FTP v12 — old-style episode watch playback + exact episode source matching
 *
 * Movie playback:
 * detail -> watch -> serialized links[] -> actual media URL -> ExtractorLink
 *
 * Existing TV/Anime parsing and fallback playback paths are preserved.
 */
class CTGFTP : MainAPI() {

    private companion object {
        const val EPISODE_DATA_PREFIX = "ctg-episode-v3|"
        const val LEGACY_EPISODE_DATA_PREFIX = "ctg-episode-v2|"
        const val SOURCE_SEPARATOR = "||"
        const val SOURCE_FIELD_SEPARATOR = "~"
        const val SUBTITLE_SEPARATOR = ";;"
        const val SUBTITLE_FIELD_SEPARATOR = "^"
    }

    override var mainUrl = "https://ctgmovies.com"
    override var name = "CTG FTP"
    override var lang = "bn"

    override val hasMainPage = true
    override val hasQuickSearch = true

    override val supportedTypes = setOf(
        TvType.Movie,
        TvType.TvSeries,
        TvType.Anime
    )

    /*
     * CTG FTP deliberately exposes only the three categories requested:
     * Movies, TV Shows and Anime.
     */
    override val mainPage = mainPageOf(
        "$mainUrl/movies?sort=newest" to "Movies",
        "$mainUrl/tv?sort=newest" to "TV Shows",
        "$mainUrl/anime?sort=newest" to "Anime"
    )

    private data class SiteItem(
        val title: String,
        val url: String,
        val poster: String?,
        val type: TvType
    )

    private val pageHeaders = mapOf(
        "User-Agent" to
            "Mozilla/5.0 (Linux; Android 13; Mobile) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36",
        "Accept" to
            "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
        "Accept-Language" to "en-US,en;q=0.9"
    )

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

    /* CTG per-episode source cache populated from the series response. */
    private val episodePlaybackCache =
        java.util.concurrent.ConcurrentHashMap<String, List<CtgPlaybackSource>>()

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        /*
         * CTG exposes an explicit "Newest" view for Movies, TV Shows and
         * Anime. Keep using that exact endpoint so the first page always
         * follows the site's current upload/newest ordering.
         *
         * parseItems() deduplicates the mobile + desktop streamed shells by
         * canonical content URL, so the same card is not emitted twice.
         */
        val url = pageUrl(request.data, page)
        val document = getDocument(url)
            ?: return newHomePageResponse(request, emptyList(), false)

        val items = parseItems(document, url)
            .take(30)

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

        /*
         * Search strategy:
         *
         * 1. Keep CTG's native search first for speed.
         * 2. Try normalized query variants so punctuation differences such as
         *    "Balan: The Boy" vs "Balan - The Boy" do not block a result.
         * 3. If CTG's search still returns nothing useful, perform a local
         *    fuzzy search over the three existing provider categories.
         *
         * Nothing else in the provider is changed by this search fallback.
         */
        val normalizedQuery = normalizeSearchText(q)

        val queryVariants = linkedSetOf(
            q,
            q.replace(':', ' '),
            q.replace('-', ' '),
            q.replace('_', ' '),
            q.replace(':', ' ').replace('-', ' '),
            q.replace(Regex("""\s+"""), " ").trim()
        ).filter { it.isNotBlank() }

        val nativeResults = linkedMapOf<String, SearchResponse>()

        for (variant in queryVariants) {
            val encoded = URLEncoder.encode(
                variant,
                StandardCharsets.UTF_8.toString()
            )

            val candidates = listOf(
                "$mainUrl/search?q=$encoded${pageSuffix(page)}",
                "$mainUrl/search?query=$encoded${pageSuffix(page)}",
                "$mainUrl/search?search=$encoded${pageSuffix(page)}"
            ).distinct()

            for (url in candidates) {
                val document = getDocument(url) ?: continue
                val items = parseItems(document, url)

                items.forEach { item ->
                    nativeResults.putIfAbsent(
                        item.url,
                        item.toSearchResponse()
                    )
                }

                /*
                 * Prefer the site's native search if it gives a strong match.
                 * A normalized exact match is stronger than the raw site's
                 * punctuation-sensitive matching.
                 */
                val strongNative = items.any {
                    normalizeSearchText(it.title) == normalizedQuery ||
                        normalizeSearchText(it.title)
                            .contains(normalizedQuery) ||
                        normalizedQuery.contains(
                            normalizeSearchText(it.title)
                        )
                }

                if (strongNative) {
                    return newSearchResponseList(
                        nativeResults.values
                            .take(30)
                            .toList(),
                        hasNextPage(document, page)
                    )
                }
            }
        }

        if (nativeResults.isNotEmpty()) {
            return newSearchResponseList(
                rankSearchResponses(
                    query = q,
                    responses = nativeResults.values.toList()
                ).take(30),
                false
            )
        }

        /*
         * ================================================================
         * LOCAL FUZZY FALLBACK
         * ================================================================
         *
         * CTG's server-side search can be punctuation-sensitive/exact.
         * When that happens, scan the same three category pages already used
         * by the provider and rank their titles against the user's query.
         *
         * Examples that now match:
         *
         *   Balan: The Boy
         *   Balan - The Boy
         *   Balan The Boy
         *
         * as well as small spelling/word-order differences.
         */
        val allItems = linkedMapOf<String, SiteItem>()

        val categoryUrls = listOf(
            "$mainUrl/movies",
            "$mainUrl/tv",
            "$mainUrl/anime"
        )

        for (categoryUrl in categoryUrls) {
            val document = getDocument(
                pageUrl(categoryUrl, page)
            ) ?: continue

            parseItems(
                document,
                document.location().ifBlank { categoryUrl }
            ).forEach { item ->
                allItems.putIfAbsent(item.url, item)
            }
        }

        if (allItems.isEmpty()) {
            return newSearchResponseList(emptyList(), false)
        }

        val ranked = allItems.values
            .map { item ->
                SearchCandidate(
                    item = item,
                    score = searchScore(
                        query = q,
                        title = item.title
                    )
                )
            }
            .filter { it.score >= SEARCH_MIN_SCORE }
            .sortedWith(
                compareByDescending<SearchCandidate> { it.score }
                    .thenBy { it.item.title.length }
            )
            .take(30)

        return newSearchResponseList(
            ranked.map { it.item.toSearchResponse() },
            false
        )
    }

    private data class SearchCandidate(
        val item: SiteItem,
        val score: Double
    )

    private val SEARCH_MIN_SCORE = 0.38

    private fun normalizeSearchText(
        value: String
    ): String {
        return value
            .lowercase(Locale.ROOT)
            /*
             * Keep letters/digits/whitespace only. This deliberately makes
             * punctuation variations such as colon, hyphen, apostrophe and
             * brackets insignificant.
             */
            .replace(Regex("""[^a-z0-9]+"""), " ")
            .replace(Regex("""\s+"""), " ")
            .trim()
    }

    private fun searchTokens(
        value: String
    ): List<String> {
        return normalizeSearchText(value)
            .split(' ')
            .filter { it.length >= 2 }
    }

    private fun searchScore(
        query: String,
        title: String
    ): Double {
        val qNorm = normalizeSearchText(query)
        val tNorm = normalizeSearchText(title)

        if (qNorm.isBlank() || tNorm.isBlank()) {
            return 0.0
        }

        if (qNorm == tNorm) {
            return 1.0
        }

        if (tNorm.contains(qNorm)) {
            return 0.96
        }

        if (qNorm.contains(tNorm)) {
            return 0.90
        }

        val qTokens = searchTokens(query).distinct()
        val tTokens = searchTokens(title).distinct()

        if (qTokens.isEmpty() || tTokens.isEmpty()) {
            return 0.0
        }

        /*
         * Token overlap handles punctuation and word-order differences.
         * "Balan: The Boy" and "Balan - The Boy" therefore score very high.
         */
        val matchedTokens = qTokens.count { qToken ->
            tTokens.any { tToken ->
                tToken == qToken ||
                    tToken.startsWith(qToken) ||
                    qToken.startsWith(tToken) ||
                    normalizedLevenshtein(
                        qToken,
                        tToken
                    ) >= 0.78
            }
        }

        val overlap = matchedTokens.toDouble() /
            maxOf(qTokens.size, tTokens.size)

        /*
         * Character-level similarity catches small typos while remaining
         * conservative enough to avoid unrelated titles.
         */
        val characterSimilarity =
            normalizedLevenshtein(qNorm, tNorm)

        /*
         * Give more weight to token overlap because movie titles often differ
         * only by punctuation, subtitles, or small suffixes.
         */
        return (overlap * 0.65) +
            (characterSimilarity * 0.35)
    }

    private fun normalizedLevenshtein(
        first: String,
        second: String
    ): Double {
        if (first == second) return 1.0
        if (first.isEmpty() || second.isEmpty()) return 0.0

        var previous = IntArray(second.length + 1) {
            it
        }
        var current = IntArray(second.length + 1)

        for (i in first.indices) {
            current[0] = i + 1

            for (j in second.indices) {
                val cost = if (first[i] == second[j]) 0 else 1

                current[j + 1] = minOf(
                    current[j] + 1,
                    previous[j + 1] + 1,
                    previous[j] + cost
                )
            }

            val swap = previous
            previous = current
            current = swap
        }

        val distance = previous[second.length]
        val maxLength = maxOf(
            first.length,
            second.length
        )

        return 1.0 - (
            distance.toDouble() / maxLength.toDouble()
        )
    }

    private fun rankSearchResponses(
        query: String,
        responses: List<SearchResponse>
    ): List<SearchResponse> {
        val queryLower = normalizeSearchText(query)

        return responses
            .map { response ->
                val score = searchScore(
                    query = queryLower,
                    title = response.name
                )

                response to score
            }
            .sortedByDescending { it.second }
            .map { it.first }
    }

    override suspend fun load(url: String): LoadResponse {
        val clean = cleanUrl(url)

        if (isMediaUrl(clean)) {
            return newMovieLoadResponse(
                titleFromUrl(clean),
                clean,
                TvType.Movie,
                clean
            )
        }

        /*
         * Keep the raw HTTP response for TV/Anime pages. CTG's Next.js
         * `allEpisodes[]` + per-episode `links[]` live inside streamed script
         * payloads. Jsoup's reconstructed document can hide/reshape that
         * payload, so episode parsing must use the original response text.
         */
        val pageResponse = runCatching {
            app.get(
                clean,
                headers = pageHeaders + ("Referer" to "$mainUrl/")
            )
        }.getOrNull()

        val document = pageResponse?.document
            ?: return newMovieLoadResponse(
                titleFromUrl(clean),
                clean,
                typeFromUrl(clean),
                clean
            )

        val rawPageHtml = pageResponse.text

        val title = extractPageTitle(document)
            .ifBlank { titleFromUrl(clean) }

        val poster = extractPoster(document, clean)
        val plot = extractPlot(document)
        val year = extractYear(document)

        when (typeFromUrl(clean)) {
            TvType.TvSeries -> {
                val episodes = parseEpisodes(
                    document = document,
                    rawHtml = rawPageHtml,
                    baseUrl = clean
                )

                if (episodes.isNotEmpty()) {
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
            }

            TvType.Anime -> {
                val episodes = parseEpisodes(
                    document = document,
                    rawHtml = rawPageHtml,
                    baseUrl = clean
                )

                if (episodes.isNotEmpty()) {
                    return newAnimeLoadResponse(
                        title,
                        clean,
                        TvType.Anime
                    ) {
                        posterUrl = poster
                        this.plot = plot
                        this.year = year
                        addEpisodes(DubStatus.Subbed, episodes)
                    }
                }
            }

            else -> Unit
        }

        /*
         * Movies use a two-step playback chain:
         *
         *   /movies/<slug>
         *       -> /watch/<id>?type=movie
         *       -> serialized CTG links[]
         *       -> actual media URL(s)
         *
         * Keep the movie detail URL as the CloudStream data. loadLinks()
         * resolves the current watch page and playback sources at Play time.
         * TV/Anime episode data remains unchanged above.
         */
        return newMovieLoadResponse(
            title,
            clean,
            typeFromUrl(clean),
            clean
        ) {
            posterUrl = poster
            this.plot = plot
            this.year = year
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val input = cleanUrl(data)
        if (input.isBlank()) return false

        /*
         * Direct media is still supported as a first-class input.
         */
        if (isMediaUrl(input)) {
            emitMediaLink(
                mediaUrl = input,
                referer = mainUrl,
                callback = callback
            )
            return true
        }

        /*
         * ============================================================
         * MOVIE-SPECIFIC PLAYBACK
         * ============================================================
         *
         * Keep the working movie chain exactly intact:
         *
         *   /movies/<slug>
         *       -> /watch/<movie-id>?type=movie
         *       -> serialized links[]
         *       -> actual media URL(s)
         *
         * The parser below is now shared with episode playback as well,
         * but movie labels remain unchanged so the existing player/source
         * presentation is not disturbed.
         */
        val isMovieDetail = runCatching {
            URI(input).path.orEmpty().lowercase(Locale.ROOT)
                .startsWith("/movies/")
        }.getOrDefault(false)

        if (isMovieDetail) {
            val detailResponse = runCatching {
                app.get(
                    input,
                    headers = pageHeaders + ("Referer" to "$mainUrl/")
                )
            }.getOrNull()

            if (detailResponse != null) {
                val detailDocument = detailResponse.document

                val watchUrl = extractPlaybackPageUrl(
                    document = detailDocument,
                    baseUrl = input
                )

                if (!watchUrl.isNullOrBlank()) {
                    val watchResponse = runCatching {
                        app.get(
                            watchUrl,
                            headers = pageHeaders + ("Referer" to input)
                        )
                    }.getOrNull()

                    if (watchResponse != null) {
                        val movieId = watchId(watchUrl)

                        val ctgSources = extractCtgPlaybackLinks(
                            html = watchResponse.text,
                            baseUrl = watchUrl,
                            preferredMovieId = movieId
                        )

                        if (ctgSources.isNotEmpty()) {
                            var emitted = false
                            val subtitleSeen = linkedSetOf<String>()

                            ctgSources.forEach { source ->
                                val mediaUrl = source.url
                                if (!isMediaUrl(mediaUrl)) return@forEach

                                source.subtitleTracks.forEach { track ->
                                    if (subtitleSeen.add(track.url)) {
                                        subtitleCallback(
                                            newSubtitleFile(
                                                lang = track.label.ifBlank {
                                                    track.language.ifBlank { "Subtitle" }
                                                },
                                                url = track.url
                                            )
                                        )
                                    }
                                }

                                emitMediaLink(
                                    mediaUrl = mediaUrl,
                                    referer = watchUrl,
                                    qualityHint = source.quality,
                                    sourceName = source.sourceName,
                                    language = source.language,
                                    includeLanguage = false,
                                    callback = callback
                                )
                                emitted = true
                            }

                            if (emitted) return true
                        }

                        /*
                         * Preserve the existing generic fallback as a secondary
                         * path for markup changes.
                         */
                        val fallbackSources = extractMediaUrls(
                            document = watchResponse.document,
                            html = watchResponse.text,
                            baseUrl = watchUrl
                        ).distinct()

                        if (fallbackSources.isNotEmpty()) {
                            fallbackSources.forEach { source ->
                                emitMediaLink(
                                    mediaUrl = source,
                                    referer = watchUrl,
                                    callback = callback
                                )
                            }
                            return true
                        }
                    }
                }
            }
        }

        /*
         * ============================================================
         * EMBEDDED EPISODE SOURCES
         * ============================================================
         *
         * For TV/Anime, parseSerializedEpisodes() reads the exact links[]
         * belonging to each episode and stores those real media URLs in the
         * Episode data. This makes every episode use the same final direct
         * media path as a working movie source: URL -> ExtractorLink.
         * No sibling episode scan and no resolution probing is performed.
         */
        if (input.startsWith(EPISODE_DATA_PREFIX) || input.startsWith(LEGACY_EPISODE_DATA_PREFIX)) {
            val embedded = parseEpisodeDataPayload(input)

            var emitted = false
            val subtitleSeen = linkedSetOf<String>()

            embedded.sources.forEach { source ->
                val mediaUrl = source.url
                if (!isMediaUrl(mediaUrl)) return@forEach

                source.subtitleTracks.forEach { track ->
                    if (subtitleSeen.add(track.url)) {
                        subtitleCallback(
                            newSubtitleFile(
                                lang = track.label.ifBlank {
                                    track.language.ifBlank { "Subtitle" }
                                },
                                url = track.url
                            )
                        )
                    }
                }

                emitMediaLink(
                    mediaUrl = mediaUrl,
                    referer = embedded.watchUrl.ifBlank { mainUrl },
                    qualityHint = source.quality,
                    sourceName = source.sourceName,
                    language = source.language,
                    includeLanguage = true,
                    callback = callback
                )
                emitted = true
            }

            if (emitted) return true

            /*
             * Old/partial cached episode data may contain the watch URL but no
             * embedded links. Fall back to the exact watch page in that case.
             */
            if (embedded.watchUrl.isNotBlank()) {
                return loadExactWatchSources(
                    input = embedded.watchUrl,
                    episodeId = embedded.episodeId,
                    subtitleCallback = subtitleCallback,
                    callback = callback
                )
            }

            return false
        }

        /*
         * ============================================================
         * EPISODE / WATCH-URL PLAYBACK
         * ============================================================
         *
         * Every parsed TV/Anime episode now stores its own CTG watch URL:
         *
         *   /watch/<episode-id>?type=episode&series=<slug>
         *
         * CTG's watch page contains the exact links[] for that episode,
         * including quality, server, language and subtitle tracks.
         *
         * This is the key fix for:
         *   - Episode 2..N not appearing as independent episodes
         *   - Episode 2..N not playing
         *   - losing per-episode quality/source information
         */
        if (isWatchUrl(input)) {
            val type = queryParam(input, "type")
                ?.lowercase(Locale.ROOT)

            if (type == "episode") {
                /*
                 * OLD WORKING CTG METHOD, APPLIED TO EVERY EPISODE:
                 *
                 * Episode stores its exact /watch/<episode-id> URL.
                 * When Play is pressed we fetch that exact page once and use
                 * the same serialized `links[]` resolver used by the working
                 * Movie path, but we constrain it to the selected episode_id.
                 *
                 * This means Episode 3 can only emit Episode 3 sources.
                 */
                val episodeId = watchId(input)

                val response = runCatching {
                    app.get(
                        input,
                        headers = pageHeaders + ("Referer" to "$mainUrl/")
                    )
                }.getOrNull() ?: return false

                val ctgSources = extractCtgPlaybackLinks(
                    html = response.text,
                    baseUrl = input,
                    preferredEpisodeId = episodeId
                )

                if (ctgSources.isNotEmpty()) {
                    var emitted = false
                    val subtitleSeen = linkedSetOf<String>()

                    ctgSources.forEach { source ->
                        if (!isMediaUrl(source.url)) return@forEach

                        source.subtitleTracks.forEach { track ->
                            if (subtitleSeen.add(track.url)) {
                                subtitleCallback(
                                    newSubtitleFile(
                                        lang = track.label.ifBlank {
                                            track.language.ifBlank { "Subtitle" }
                                        },
                                        url = track.url
                                    )
                                )
                            }
                        }

                        emitMediaLink(
                            mediaUrl = source.url,
                            referer = input,
                            qualityHint = source.quality,
                            sourceName = source.sourceName,
                            language = source.language,
                            includeLanguage = true,
                            callback = callback
                        )
                        emitted = true
                    }

                    if (emitted) return true
                }

                /*
                 * Exact-page legacy fallback. Only use it when the page exposes
                 * one unambiguous direct media URL; never emit sibling episode
                 * files just because they exist elsewhere in the response.
                 */
                val direct = extractMediaUrls(
                    document = response.document,
                    html = response.text,
                    baseUrl = input
                )
                    .filterNot(::isAudioOnlyMediaUrl)
                    .distinctBy { mediaDedupKey(it) }

                if (direct.size == 1) {
                    emitMediaLink(
                        mediaUrl = direct.first(),
                        referer = input,
                        callback = callback
                    )
                    return true
                }

                return false
            }

            val response = runCatching {
                app.get(
                    input,
                    headers = pageHeaders + ("Referer" to "$mainUrl/")
                )
            }.getOrNull()

            if (response != null) {
                val preferredMovieId =
                    watchId(input).takeIf { type == "movie" }

                val ctgSources = extractCtgPlaybackLinks(
                    html = response.text,
                    baseUrl = input,
                    preferredMovieId = preferredMovieId
                )

                if (ctgSources.isNotEmpty()) {
                    var emitted = false
                    val subtitleSeen = linkedSetOf<String>()

                    ctgSources.forEach { source ->
                        val mediaUrl = source.url
                        if (!isMediaUrl(mediaUrl)) return@forEach

                        source.subtitleTracks.forEach { track ->
                            if (subtitleSeen.add(track.url)) {
                                subtitleCallback(
                                    newSubtitleFile(
                                        lang = track.label.ifBlank {
                                            track.language.ifBlank { "Subtitle" }
                                        },
                                        url = track.url
                                    )
                                )
                            }
                        }

                        emitMediaLink(
                            mediaUrl = mediaUrl,
                            referer = input,
                            qualityHint = source.quality,
                            sourceName = source.sourceName,
                            language = source.language,
                            includeLanguage = false,
                            callback = callback
                        )
                        emitted = true
                    }

                    if (emitted) return true
                }
            }
        }

        /*
         * Existing generic playback path for TV/Anime and any non-watch page.
         */
        val response = runCatching {
            app.get(
                input,
                headers = pageHeaders + ("Referer" to "$mainUrl/")
            )
        }.getOrNull() ?: return false

        val document = response.document
        val html = response.text

        /*
         * Priority 1: explicit video/source/data-* values and direct media URLs.
         */
        val sources = extractMediaUrls(
            document = document,
            html = html,
            baseUrl = input
        ).distinct()

        if (sources.isNotEmpty()) {
            sources.forEach { source ->
                emitMediaLink(
                    mediaUrl = source,
                    referer = input,
                    callback = callback
                )
            }
            return true
        }

        /*
         * Priority 2: links such as Download/Server buttons whose query or
         * encoded value points to the actual media file.
         */
        val recovered = recoverPlayableUrls(
            document = document,
            html = html,
            baseUrl = input
        ).distinct()

        if (recovered.isNotEmpty()) {
            recovered.forEach { source ->
                emitMediaLink(
                    mediaUrl = source,
                    referer = input,
                    callback = callback
                )
            }
            return true
        }

        /*
         * Priority 3: embedded player fallback.
         */
        val iframes = document
            .select("iframe[src], iframe[data-src]")
            .mapNotNull { iframe ->
                val raw = iframe.attr("src")
                    .ifBlank { iframe.attr("data-src") }
                    .trim()

                raw.takeIf { it.isNotBlank() }
                    ?.let { absoluteUrl(it, input) }
            }
            .distinct()

        for (iframe in iframes) {
            val loaded = runCatching {
                loadExtractor(
                    iframe,
                    subtitleCallback,
                    callback
                )
            }.getOrDefault(false)

            if (loaded) return true
        }

        return false
    }

    private data class CtgSubtitleTrack(
        val url: String,
        val language: String,
        val label: String
    )

    private data class CtgPlaybackSource(
        val url: String,
        val quality: String?,
        val sourceName: String?,
        val language: String?,
        val episodeId: String?,
        val movieId: String?,
        val subtitleTracks: List<CtgSubtitleTrack>
    )

    /*     * Extract CTG's serialized links[] payload from the Next.js response.
     *
     * A series/watch response may contain several links[] arrays because CTG
     * serializes episode data and its related objects into the page. Therefore
     * we inspect every top-level links[] array and, when the caller gives us
     * an episode/movie id, keep only links belonging to that target.
     */
    private data class EpisodeDataPayload(
        val episodeId: String,
        val watchUrl: String,
        val sources: List<CtgPlaybackSource>
    )

    private fun episodeCacheKey(
        seriesSlug: String?,
        episodeId: String
    ): String {
        return if (seriesSlug.isNullOrBlank()) {
            episodeId
        } else {
            "${seriesSlug.lowercase(Locale.ROOT)}::$episodeId"
        }
    }

    private suspend fun emitEpisodeSources(
        sources: List<CtgPlaybackSource>,
        referer: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        var emitted = false
        val subtitleSeen = linkedSetOf<String>()

        sources.distinctBy { mediaDedupKey(it.url) }.forEach { source ->
            if (!isMediaUrl(source.url)) return@forEach

            source.subtitleTracks.forEach { track ->
                if (subtitleSeen.add(track.url)) {
                    subtitleCallback(
                        newSubtitleFile(
                            lang = track.label.ifBlank {
                                track.language.ifBlank { "Subtitle" }
                            },
                            url = track.url
                        )
                    )
                }
            }

            emitMediaLink(
                mediaUrl = source.url,
                referer = referer,
                qualityHint = source.quality,
                sourceName = source.sourceName,
                language = source.language,
                includeLanguage = true,
                callback = callback
            )
            emitted = true
        }

        return emitted
    }

    private suspend fun loadExactWatchSources(
        input: String,
        episodeId: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val response = runCatching {
            app.get(
                input,
                headers = pageHeaders + ("Referer" to "$mainUrl/")
            )
        }.getOrNull() ?: return false

        val targetId = episodeId?.takeIf { it.isNotBlank() }
            ?: watchId(input)

        // PRIMARY EPISODE RESOLUTION:
        // Read the authoritative allEpisodes[] object and select the exact
        // episode by id. This mirrors the movie flow but guarantees that the
        // selected episode can never receive a sibling episode source.
        val ctgSources = extractEpisodeSourcesFromAllEpisodes(
            html = response.text,
            baseUrl = input,
            episodeId = targetId
        ).ifEmpty {
            // FALLBACK: keep the existing targeted links[] resolver for older
            // or structurally different CTG watch responses.
            extractCtgPlaybackLinks(
                html = response.text,
                baseUrl = input,
                preferredEpisodeId = targetId
            )
        }

        if (ctgSources.isEmpty()) {
            /*
             * Direct media fallback is intentionally restricted to this exact
             * watch page. It never scans sibling episodes.
             */
            val direct = extractMediaUrls(
                document = response.document,
                html = response.text,
                baseUrl = input
            ).distinctBy { mediaDedupKey(it) }

            if (direct.isEmpty()) return false

            direct.forEach { source ->
                emitMediaLink(
                    mediaUrl = source,
                    referer = input,
                    callback = callback
                )
            }
            return true
        }

        var emitted = false
        val subtitleSeen = linkedSetOf<String>()

        ctgSources.forEach { source ->
            val mediaUrl = source.url
            if (!isMediaUrl(mediaUrl)) return@forEach

            source.subtitleTracks.forEach { track ->
                if (subtitleSeen.add(track.url)) {
                    subtitleCallback(
                        newSubtitleFile(
                            lang = track.label.ifBlank {
                                track.language.ifBlank { "Subtitle" }
                            },
                            url = track.url
                        )
                    )
                }
            }

            emitMediaLink(
                mediaUrl = mediaUrl,
                referer = input,
                qualityHint = source.quality,
                sourceName = source.sourceName,
                language = source.language,
                includeLanguage = true,
                callback = callback
            )
            emitted = true
        }

        return emitted
    }

    private fun buildEpisodeDataPayload(
        episodeId: String,
        watchUrl: String,
        sources: List<CtgPlaybackSource>
    ): String {
        val sourcePart = sources
            .distinctBy { mediaDedupKey(it.url) }
            .joinToString(SOURCE_SEPARATOR) { source ->
                listOf(
                    source.url,
                    source.quality.orEmpty(),
                    source.sourceName.orEmpty(),
                    source.language.orEmpty(),
                    serializeSubtitleTracks(source.subtitleTracks)
                ).joinToString(SOURCE_FIELD_SEPARATOR) { field ->
                    encodeDataField(field)
                }
            }

        return EPISODE_DATA_PREFIX +
            encodeDataField(episodeId) + "|" +
            encodeDataField(watchUrl) + "|" +
            sourcePart
    }

    private fun parseEpisodeDataPayload(
        payload: String
    ): EpisodeDataPayload {
        val parts = payload.split(
            '|',
            limit = 4
        )

        val episodeId = decodeDataField(
            parts.getOrNull(1).orEmpty()
        )

        val watchUrl = decodeDataField(
            parts.getOrNull(2).orEmpty()
        )

        val sourcePart = parts.getOrNull(3).orEmpty()
        val sources = sourcePart
            .split(SOURCE_SEPARATOR)
            .filter { it.isNotBlank() }
            .mapNotNull { encodedSource ->
                val fields = encodedSource.split(
                    SOURCE_FIELD_SEPARATOR,
                    limit = 5
                )

                val url = decodeDataField(
                    fields.getOrNull(0).orEmpty()
                )

                if (!isMediaUrl(url)) return@mapNotNull null

                CtgPlaybackSource(
                    url = url,
                    quality = decodeDataField(
                        fields.getOrNull(1).orEmpty()
                    ).ifBlank { null },
                    sourceName = decodeDataField(
                        fields.getOrNull(2).orEmpty()
                    ).ifBlank { null },
                    language = decodeDataField(
                        fields.getOrNull(3).orEmpty()
                    ).ifBlank { null },
                    episodeId = episodeId.ifBlank { null },
                    movieId = null,
                    subtitleTracks = deserializeSubtitleTracks(
                        fields.getOrNull(4).orEmpty()
                    )
                )
            }
            .distinctBy { mediaDedupKey(it.url) }

        return EpisodeDataPayload(
            episodeId = episodeId,
            watchUrl = watchUrl,
            sources = sources
        )
    }

    private fun serializeSubtitleTracks(
        tracks: List<CtgSubtitleTrack>
    ): String {
        return tracks
            .distinctBy { mediaDedupKey(it.url) }
            .joinToString(SUBTITLE_SEPARATOR) { track ->
                listOf(
                    track.url,
                    track.language,
                    track.label
                ).joinToString(SUBTITLE_FIELD_SEPARATOR) { field ->
                    encodeDataField(field)
                }
            }
    }

    private fun deserializeSubtitleTracks(
        value: String
    ): List<CtgSubtitleTrack> {
        if (value.isBlank()) return emptyList()

        return value
            .split(SUBTITLE_SEPARATOR)
            .filter { it.isNotBlank() }
            .mapNotNull { encodedTrack ->
                val fields = encodedTrack.split(
                    SUBTITLE_FIELD_SEPARATOR,
                    limit = 3
                )

                val url = decodeDataField(
                    fields.getOrNull(0).orEmpty()
                )
                if (url.isBlank()) return@mapNotNull null

                CtgSubtitleTrack(
                    url = url,
                    language = decodeDataField(
                        fields.getOrNull(1).orEmpty()
                    ),
                    label = decodeDataField(
                        fields.getOrNull(2).orEmpty()
                    )
                )
            }
            .distinctBy { mediaDedupKey(it.url) }
    }

    private fun encodeDataField(
        value: String
    ): String {
        return URLEncoder.encode(
            value,
            StandardCharsets.UTF_8.toString()
        )
    }

    private fun decodeDataField(
        value: String
    ): String {
        return runCatching {
            URLDecoder.decode(
                value,
                StandardCharsets.UTF_8.toString()
            )
        }.getOrElse {
            value
        }
    }

    private fun extractCtgPlaybackLinks(
        html: String,
        baseUrl: String,
        preferredEpisodeId: String? = null,
        preferredMovieId: String? = null
    ): List<CtgPlaybackSource> {
        if (html.isBlank()) return emptyList()

        /*
         * CTG's watch response may contain many serialized links[] objects.
         * The old movie resolver could take the first array because a movie
         * has one target. TV/Anime needs one extra constraint: exact
         * episode_id. We therefore locate the link object containing the
         * selected id and read ONLY that object.
         *
         * This keeps the same actual movie playback path:
         * watch page -> links object -> url/hls_url -> ExtractorLink.
         */
        val normalized = normalizeCtgPayload(html)
        val targetId = preferredEpisodeId?.takeIf { it.isNotBlank() }
            ?: preferredMovieId?.takeIf { it.isNotBlank() }

        val result = mutableListOf<CtgPlaybackSource>()

        fun addFromObject(objectText: String) {
            val linkEpisodeId = extractJsonString(
                objectText,
                "episode_id"
            )
            val linkMovieId = extractJsonString(
                objectText,
                "movie_id"
            )

            if (!targetId.isNullOrBlank()) {
                val matches = when {
                    !preferredEpisodeId.isNullOrBlank() ->
                        linkEpisodeId == preferredEpisodeId
                    !preferredMovieId.isNullOrBlank() ->
                        linkMovieId == preferredMovieId
                    else -> true
                }

                if (!matches) return
            }

            val quality = extractJsonString(objectText, "quality")
            val source = extractJsonString(objectText, "source")
            val language = extractJsonString(objectText, "language")
            val subtitles = extractSubtitleTracks(
                objectText,
                baseUrl
            )

            listOfNotNull(
                extractJsonString(objectText, "url"),
                extractJsonString(objectText, "hls_url")
            ).forEach { raw ->
                val media = absoluteUrl(
                    cleanUrl(raw),
                    baseUrl
                )

                if (isMediaUrl(media)) {
                    result += CtgPlaybackSource(
                        url = media,
                        quality = quality,
                        sourceName = source,
                        language = language,
                        episodeId = linkEpisodeId,
                        movieId = linkMovieId,
                        subtitleTracks = subtitles
                    )
                }
            }
        }

        /*
         * Fast exact lookup: every real CTG link object includes episode_id.
         * This avoids scanning every links[] array and avoids resolution
         * probing on unrelated episodes.
         */
        if (!targetId.isNullOrBlank()) {
            val markerKey =
                if (!preferredEpisodeId.isNullOrBlank()) {
                    "episode_id"
                } else {
                    "movie_id"
                }

            val marker = "\"$markerKey\":\"$targetId\""
            var from = 0

            while (true) {
                val markerIndex = normalized.indexOf(
                    marker,
                    from
                )

                if (markerIndex < 0) break

                val objectStart = normalized.lastIndexOf(
                    '{',
                    markerIndex
                )

                if (objectStart >= 0) {
                    val objectText = extractJsonObjectAt(
                        normalized,
                        objectStart
                    )

                    if (objectText != null) {
                        addFromObject(objectText)
                    }
                }

                from = markerIndex + marker.length
            }
        } else {
            /*
             * Movie fallback: preserve the original first-links-array behavior
             * when no target id is supplied.
             */
            val arrays = extractJsonArraysAfterKey(
                normalized,
                "\"links\""
            )

            arrays.forEach { arrayText ->
                extractTopLevelJsonObjects(
                    arrayText
                ).forEach(::addFromObject)
            }
        }

        return result
            .distinctBy { mediaDedupKey(it.url) }
    }

    private fun extractSubtitleTracks(
        objectText: String,
        baseUrl: String
    ): List<CtgSubtitleTrack> {
        val normalized = normalizeCtgPayload(objectText)
        val arrays = extractJsonArraysAfterKey(
            normalized,
            "\"subtitle_tracks\""
        )

        if (arrays.isEmpty()) return emptyList()

        val result = mutableListOf<CtgSubtitleTrack>()

        arrays.forEach { arrayText ->
            extractTopLevelJsonObjects(arrayText).forEach { trackObject ->
                val rawUrl = extractJsonString(
                    trackObject,
                    "url"
                ) ?: return@forEach

                val url = absoluteUrl(
                    cleanUrl(rawUrl),
                    baseUrl
                )

                if (
                    url.isNotBlank() &&
                    (
                        url.startsWith("http://", true) ||
                            url.startsWith("https://", true)
                    )
                ) {
                    result.add(
                        CtgSubtitleTrack(
                            url = url,
                            language = extractJsonString(
                                trackObject,
                                "language"
                            ).orEmpty(),
                            label = extractJsonString(
                                trackObject,
                                "label"
                            ).orEmpty()
                        )
                    )
                }
            }
        }

        val seen = linkedSetOf<String>()
        return result.filter { seen.add(it.url) }
    }

    private fun normalizeCtgPayload(
        html: String
    ): String {
        var value = html

        // Some captured/serialized Next.js payloads contain a second escape
        // layer. Two passes handle both one-level and double-level escaping
        // without affecting normal HTML attributes.
        repeat(2) {
            value = value
                .replace("\\\"", "\"")
                .replace("\\/", "/")
                .replace("\\u0026", "&")
                .replace("\\:", ":")
                .replace("&amp;", "&")
        }

        return value
    }

    private fun extractJsonArraysAfterKey(
        text: String,
        key: String
    ): List<String> {
        if (text.isBlank()) return emptyList()

        val result = mutableListOf<String>()
        var searchFrom = 0

        while (searchFrom < text.length) {
            val keyIndex = text.indexOf(
                key,
                searchFrom
            )

            if (keyIndex < 0) break

            val arrayStart = text.indexOf(
                '[',
                keyIndex + key.length
            )

            if (arrayStart < 0) break

            var depth = 0
            var inString = false
            var escaped = false

            for (index in arrayStart until text.length) {
                val ch = text[index]

                if (inString) {
                    if (escaped) {
                        escaped = false
                    } else if (ch == '\\') {
                        escaped = true
                    } else if (ch == '"') {
                        inString = false
                    }
                    continue
                }

                when (ch) {
                    '"' -> inString = true

                    '[' -> depth++

                    ']' -> {
                        depth--

                        if (depth == 0) {
                            result.add(
                                text.substring(
                                    arrayStart,
                                    index + 1
                                )
                            )
                            searchFrom = index + 1
                            break
                        }
                    }
                }
            }

            if (searchFrom <= keyIndex) break
        }

        return result
    }

    private fun extractTopLevelJsonObjects(
        arrayText: String
    ): List<String> {
        val result = mutableListOf<String>()

        var depth = 0
        var objectStart = -1
        var inString = false
        var escaped = false

        for (index in arrayText.indices) {
            val ch = arrayText[index]

            if (inString) {
                if (escaped) {
                    escaped = false
                } else if (ch == '\\') {
                    escaped = true
                } else if (ch == '"') {
                    inString = false
                }
                continue
            }

            when (ch) {
                '"' -> inString = true

                '{' -> {
                    if (depth == 0) {
                        objectStart = index
                    }
                    depth++
                }

                '}' -> {
                    if (depth > 0) {
                        depth--

                        if (
                            depth == 0 &&
                            objectStart >= 0
                        ) {
                            result.add(
                                arrayText.substring(
                                    objectStart,
                                    index + 1
                                )
                            )
                            objectStart = -1
                        }
                    }
                }
            }
        }

        return result
    }

    private fun extractJsonString(
        objectText: String,
        key: String
    ): String? {
        val marker = "\"$key\""
        val keyIndex = objectText.indexOf(marker)
        if (keyIndex < 0) return null

        val colonIndex = objectText.indexOf(
            ':',
            keyIndex + marker.length
        )
        if (colonIndex < 0) return null

        val valueStart = run {
            var index = colonIndex + 1

            while (
                index < objectText.length &&
                objectText[index].isWhitespace()
            ) {
                index++
            }

            index
        }

        if (
            valueStart >= objectText.length ||
            objectText[valueStart] != '"'
        ) {
            return null
        }

        val value = StringBuilder()
        var escaped = false

        for (
            index in valueStart + 1 until objectText.length
        ) {
            val ch = objectText[index]

            if (escaped) {
                when (ch) {
                    '"' -> value.append('"')
                    '\\' -> value.append('\\')
                    '/' -> value.append('/')
                    'b' -> value.append('\b')
                    'f' -> value.append('\u000C')
                    'n' -> value.append('\n')
                    'r' -> value.append('\r')
                    't' -> value.append('\t')

                    'u' -> {
                        if (index + 4 < objectText.length) {
                            val hex = objectText.substring(
                                index + 1,
                                index + 5
                            )
                            val decoded = hex.toIntOrNull(16)

                            if (decoded != null) {
                                value.append(
                                    decoded.toChar()
                                )
                                escaped = false
                                continue
                            }
                        }

                        value.append('u')
                    }

                    else -> value.append(ch)
                }

                escaped = false
                continue
            }

            when (ch) {
                '\\' -> escaped = true
                '"' -> return value.toString()
                else -> value.append(ch)
            }
        }

        return null
    }

    private fun extractJsonPrimitive(
        objectText: String,
        key: String
    ): String? {
        val marker = "\"$key\""
        val keyIndex = objectText.indexOf(marker)
        if (keyIndex < 0) return null

        val colonIndex = objectText.indexOf(
            ':',
            keyIndex + marker.length
        )
        if (colonIndex < 0) return null

        var start = colonIndex + 1
        while (
            start < objectText.length &&
            objectText[start].isWhitespace()
        ) {
            start++
        }

        if (start >= objectText.length) return null

        if (objectText[start] == '"') {
            return extractJsonString(objectText, key)
        }

        var end = start
        while (
            end < objectText.length &&
            objectText[end] !in charArrayOf(',', '}', ']') &&
            !objectText[end].isWhitespace()
        ) {
            end++
        }

        return objectText
            .substring(start, end)
            .trim()
            .takeIf { it.isNotBlank() && it != "null" }
    }

    private suspend fun emitMediaLink(
        mediaUrl: String,
        referer: String,
        qualityHint: String? = null,
        sourceName: String? = null,
        language: String? = null,
        includeLanguage: Boolean = false,
        callback: (ExtractorLink) -> Unit
    ) {
        val url = cleanUrl(mediaUrl)
        if (!isMediaUrl(url)) return

        val type = when {
            url.contains(".m3u8", ignoreCase = true) ->
                ExtractorLinkType.M3U8

            url.contains(".mpd", ignoreCase = true) ->
                ExtractorLinkType.DASH

            else ->
                ExtractorLinkType.VIDEO
        }

        val quality =
            qualityFromHint(qualityHint)
                ?: qualityFromUrl(
                    buildString {
                        append(url)
                        if (!qualityHint.isNullOrBlank()) {
                            append(' ')
                            append(qualityHint)
                        }
                    }
                )

        val baseSuffix = when {
            !sourceName.isNullOrBlank() ->
                " ${sourceName.trim()}"

            type == ExtractorLinkType.M3U8 ->
                " HLS"

            type == ExtractorLinkType.DASH ->
                " DASH"

            else ->
                " Direct"
        }

        val languageSuffix =
            if (
                includeLanguage &&
                !language.isNullOrBlank()
            ) {
                val normalizedLanguage =
                    language
                        .replace(
                            Regex("""\s+"""),
                            " "
                        )
                        .trim()

                " [$normalizedLanguage]"
            } else {
                ""
            }

        callback(
            newExtractorLink(
                source = name,
                name = "$name$baseSuffix$languageSuffix",
                url = url,
                type = type
            ) {
                this.referer = referer
                this.quality = quality
            }
        )
    }

    private suspend fun getDocument(url: String): Document? {
        val normalized = cleanUrl(url)
        if (normalized.isBlank()) return null

        return runCatching {
            app.get(
                normalized,
                headers = pageHeaders + ("Referer" to "$mainUrl/")
            ).document
        }.getOrNull()
    }

    private fun parseItems(
        document: Document,
        sourceUrl: String
    ): List<SiteItem> {
        val result = linkedMapOf<String, SiteItem>()

        val sourcePath = runCatching {
            URI(sourceUrl).path.orEmpty()
                .lowercase(Locale.ROOT)
        }.getOrDefault("")

        val expectedPrefix = when {
            sourcePath.startsWith("/movies") -> "/movies/"
            sourcePath.startsWith("/tv") -> "/tv/"
            sourcePath.startsWith("/anime") -> "/anime/"
            else -> null
        }

        fun addItem(
            rawUrl: String?,
            card: Element,
            fallbackElement: Element? = null
        ) {
            if (rawUrl.isNullOrBlank()) return

            val absolute = absoluteUrl(
                cleanUrl(rawUrl),
                sourceUrl
            )
            val canonical = canonicalContentUrl(absolute)
            val canonicalPath = runCatching {
                URI(canonical).path.orEmpty()
                    .lowercase(Locale.ROOT)
            }.getOrDefault("")

            if (
                !isContentUrl(canonical) ||
                (expectedPrefix != null &&
                    !canonicalPath.startsWith(expectedPrefix))
            ) {
                return
            }

            val title = cleanTitle(
                firstNonBlank(
                    card.selectFirst(".title")?.text(),
                    card.selectFirst(".movie-title")?.text(),
                    card.selectFirst(".movie_name")?.text(),
                    card.selectFirst(".name")?.text(),
                    card.selectFirst("img")?.attr("alt"),
                    card.selectFirst("h1")?.text(),
                    card.selectFirst("h2")?.text(),
                    card.selectFirst("h3")?.text(),
                    card.selectFirst("h4")?.text(),
                    fallbackElement?.attr("aria-label"),
                    fallbackElement?.text(),
                    titleFromUrl(canonical)
                )
            )

            if (title.isBlank() || isNavigationTitle(title)) return

            result.putIfAbsent(
                canonical,
                SiteItem(
                    title = title,
                    url = canonical,
                    poster = extractPosterFromElement(
                        card,
                        sourceUrl
                    ),
                    type = typeFromUrl(canonical)
                )
            )
        }

        /*
         * First parse fully materialized cards.
         * CTG's server response also contains streamed placeholder anchors; those
         * are handled in the second pass below.
         */
        document.select(
            "a[href], [data-href], [data-url], [data-link]"
        ).forEach { element ->
            val raw = sequenceOf(
                element.attr("href"),
                element.attr("data-href"),
                element.attr("data-url"),
                element.attr("data-link")
            ).firstOrNull { it.isNotBlank() }
                ?: return@forEach

            if (element.selectFirst("img") == null) {
                return@forEach
            }

            addItem(
                rawUrl = raw,
                card = element,
                fallbackElement = element
            )
        }

        /*
         * CTG/Next.js may stream a card as: placeholder anchor P:x + hidden S:y
         * fragments + $RS("S:y","P:x"). Rebuild those fragments so items such
         * as Heer Sara are not assigned the previous card's title/thumbnail.
         */
        val rsMappings = linkedMapOf<String, MutableList<String>>()
        val rsRegex = Regex(
            """${'$'}RS\("([^"]+)","([^"]+)"\)"""
        )

        document.select("script").forEach { script ->
            rsRegex.findAll(script.data()).forEach { match ->
                val sourceId = match.groupValues[1]
                val targetId = match.groupValues[2]
                rsMappings
                    .getOrPut(targetId) { mutableListOf() }
                    .add(sourceId)
            }
        }
