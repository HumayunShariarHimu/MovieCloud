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
        document.select("template[id^=P:]").forEach { template ->
            val targetId = template.id()
            val sourceIds = rsMappings[targetId]
                ?.distinct()
                .orEmpty()

            if (sourceIds.isEmpty()) return@forEach

            var parent: Element? = template.parent()
            while (parent != null && parent?.tagName() != "a") {
                parent = parent.parent()
            }

            val anchor = parent ?: return@forEach
            val raw = sequenceOf(
                anchor.attr("href"),
                anchor.attr("data-href"),
                anchor.attr("data-url"),
                anchor.attr("data-link")
            ).firstOrNull { it.isNotBlank() }
                ?: return@forEach

            val fragments = sourceIds.mapNotNull { sourceId ->
                document.getElementById(sourceId)?.html()
            }

            if (fragments.isEmpty()) return@forEach

            val fragmentDocument = org.jsoup.Jsoup.parseBodyFragment(
                fragments.joinToString("\n")
            )

            addItem(
                rawUrl = raw,
                card = fragmentDocument.body(),
                fallbackElement = anchor
            )
        }

        return result.values.toList()
    }

    private fun SiteItem.toSearchResponse(): SearchResponse {
        return when (type) {
            TvType.TvSeries -> newTvSeriesSearchResponse(
                title,
                url,
                TvType.TvSeries
            ) {
                posterUrl = poster
            }

            TvType.Anime -> newAnimeSearchResponse(
                title,
                url,
                TvType.Anime
            ) {
                posterUrl = poster
            }

            else -> newMovieSearchResponse(
                title,
                url,
                TvType.Movie
            ) {
                posterUrl = poster
            }
        }
    }

    /*
     * Find CTG's actual playback page from a movie/series/anime detail page.
     *
     * The supplied CTG source shows the Movie Play button using:
     *   href="/watch/<id>?type=movie"
     *
     * We use the DOM link instead of a giant regex.
     */
    private fun extractPlaybackPageUrl(
        document: Document,
        baseUrl: String
    ): String? {
        val elements = document.select(
            "a[href], [data-href], [data-url]"
        )

        /*
         * Prefer an explicit Play/Watch link.
         */
        for (element in elements) {
            val raw = sequenceOf(
                element.attr("href"),
                element.attr("data-href"),
                element.attr("data-url")
            ).firstOrNull { it.isNotBlank() } ?: continue

            val absolute = absoluteUrl(
                cleanUrl(raw),
                baseUrl
            )

            val path = runCatching {
                URI(absolute).path.orEmpty().lowercase(Locale.ROOT)
            }.getOrElse {
                absolute.lowercase(Locale.ROOT)
            }

            val label = element.text()
                .trim()
                .lowercase(Locale.ROOT)

            if (
                path.startsWith("/watch/") &&
                (
                    label.contains("play") ||
                    label.contains("watch") ||
                    absolute.contains("type=movie", true) ||
                    absolute.contains("type=series", true) ||
                    absolute.contains("type=tv", true) ||
                    absolute.contains("type=anime", true)
                )
            ) {
                return absolute
            }
        }

        /*
         * Fallback for buttons/links whose visible label is rendered by JS.
         */
        for (element in elements) {
            val raw = sequenceOf(
                element.attr("href"),
                element.attr("data-href"),
                element.attr("data-url")
            ).firstOrNull { it.isNotBlank() } ?: continue

            val absolute = absoluteUrl(
                cleanUrl(raw),
                baseUrl
            )

            val path = runCatching {
                URI(absolute).path.orEmpty().lowercase(Locale.ROOT)
            }.getOrElse {
                absolute.lowercase(Locale.ROOT)
            }

            if (path.startsWith("/watch/")) {
                return absolute
            }
        }

        return null
    }

    private fun parseEpisodes(
        document: Document,
        rawHtml: String,
        baseUrl: String
    ): List<Episode> {
        val result = linkedMapOf<String, Episode>()

        /*
         * ============================================================
         * PASS 1 — REAL EPISODE CARDS FROM THE CTG HTML
         * ============================================================
         *
         * CTG's episode cards contain:
         *   - S01E01
         *   - air date
         *   - runtime
         *   - Episode N
         *   - overview/plot
         *   - still/poster
         *   - /watch/<episode-id>?type=episode&series=<slug>
         *
         * Parse those cards directly instead of treating the first media
         * file on the page as "Episode 1".
         */
        document.select("section").forEach { section ->
            val heading = section.selectFirst("h2")
                ?.text()
                ?.trim()
                ?.lowercase(Locale.ROOT)
                ?: return@forEach

            if (heading != "episodes") {
                return@forEach
            }

            section.select("li").forEach { item ->
                val anchor = item.selectFirst(
                    "a[href]"
                ) ?: return@forEach

                val raw = anchor.attr("href")
                    .trim()

                val absolute = absoluteUrl(
                    cleanUrl(raw),
                    baseUrl
                )

                if (!isEpisodeWatchUrl(absolute)) {
                    return@forEach
                }

                val infoText = item
                    .selectFirst(
                        ".font-mono"
                    )
                    ?.text()
                    ?.trim()
                    .orEmpty()

                val seasonEpisodeText =
                    sequenceOf(
                        infoText,
                        item.text()
                    ).firstOrNull {
                        Regex(
                            """(?i)\bS\d{1,2}\s*E\d{1,3}\b"""
                        ).containsMatchIn(it)
                    }
                        .orEmpty()

                val season =
                    seasonNumber(
                        item,
                        absolute
                    )

                val episode =
                    episodeNumber(
                        item,
                        absolute
                    )

                val title = cleanTitle(
                    firstNonBlank(
                        item.selectFirst("h4")?.text(),
                        item.selectFirst("h3")?.text(),
                        anchor.text(),
                        "Episode $episode"
                    )
                )

                val description =
                    item.selectFirst("p")
                        ?.text()
                        ?.trim()
                        ?.takeIf { it.isNotBlank() }

                val poster =
                    item.selectFirst(
                        "img[src], img[data-src], " +
                            "img[data-poster]"
                    )?.let { image ->
                        firstNonBlank(
                            image.attr("data-poster"),
                            image.attr("data-src"),
                            image.attr("src")
                        ).takeIf {
                            it.isNotBlank()
                        }?.let {
                            absoluteUrl(
                                it,
                                baseUrl
                            )
                        }
                    }

                val airDate =
                    Regex(
                        """\b(\d{4}-\d{2}-\d{2})\b"""
                    )
                        .find(
                            sequenceOf(
                                infoText,
                                item.text()
                            ).joinToString(" ")
                        )
                        ?.groupValues
                        ?.getOrNull(1)

                val runTime =
                    Regex(
                        """\b(\d+)\s*m\b"""
                    )
                        .find(
                            sequenceOf(
                                infoText,
                                item.text()
                            ).joinToString(" ")
                        )
                        ?.groupValues
                        ?.getOrNull(1)
                        ?.toIntOrNull()

                addEpisode(
                    result = result,
                    dataUrl = absolute,
                    name = title,
                    season = season,
                    episode = episode,
                    posterUrl = poster,
                    description = description,
                    airDate = airDate,
                    runTime = runTime
                )
            }
        }

        /*
         * ============================================================
         * PASS 2 — NEXT.JS `allEpisodes` SERIALIZED DATA
         * ============================================================
         *
         * This is the authoritative dynamic backup. CTG serializes all
         * episodes here even when the DOM shell is incomplete while the page
         * is streaming. It gives us the exact episode count, ids, overview,
         * still URL, season/episode numbers and runtime.
         */
        val seriesSlug = seriesSlugFromUrl(baseUrl)
        val serialized = parseSerializedEpisodes(
            /*
             * IMPORTANT: use the original response body, not document.html().
             * CTG's streamed Next.js payload contains the authoritative
             * allEpisodes + links objects, and the original response preserves
             * the escape structure needed by the parser.
             */
            html = rawHtml.ifBlank { document.html() },
            baseUrl = baseUrl,
            seriesSlug = seriesSlug
        )

        serialized.forEach { episodeData ->
            addEpisode(
                result = result,
                dataUrl = episodeData.dataUrl,
                name = episodeData.name,
                season = episodeData.season,
                episode = episodeData.episode,
                posterUrl = episodeData.posterUrl,
                description = episodeData.description,
                airDate = episodeData.airDate,
                runTime = episodeData.runTime,
                playbackSources = episodeData.playbackSources
            )
        }

        /*
         * Only if CTG exposes no structured episode data at all do we keep the
         * old single-media fallback. This prevents a broken/empty series UI
         * while preserving compatibility with unusual pages.
         */
        if (result.isEmpty()) {
            val media = extractMediaUrls(
                document,
                document.html(),
                baseUrl
            ).firstOrNull()

            if (media != null) {
                result[media] = newEpisode(media) {
                    name = "Episode 1"
                    season = 1
                    episode = 1
                }
            }
        }

        return result.values.sortedWith(
            compareBy<Episode> {
                it.season ?: 1
            }
                .thenBy {
                    it.episode ?: Int.MAX_VALUE
                }
        )
    }

    private data class ParsedEpisode(
        val dataUrl: String,
        val name: String,
        val season: Int,
        val episode: Int,
        val posterUrl: String?,
        val description: String?,
        val airDate: String?,
        val runTime: Int?,
        val playbackSources: List<CtgPlaybackSource>
    )

    private fun addEpisode(
        result: MutableMap<String, Episode>,
        dataUrl: String,
        name: String,
        season: Int,
        episode: Int,
        posterUrl: String?,
        description: String?,
        airDate: String?,
        runTime: Int?,
        playbackSources: List<CtgPlaybackSource> = emptyList()
    ) {
        val cleanData = cleanUrl(dataUrl)
        if (cleanData.isBlank()) return

        /*
         * A CTG episode can be discovered twice:
         *
         *   1) from the rendered <li> card (watch URL only), and
         *   2) from Next.js `allEpisodes[]` (authoritative episode object + links[]).
         *
         * The previous implementation keyed the map by the complete data URL.
         * That meant the DOM version was already present, so the richer
         * serialized version was discarded by putIfAbsent(). The CloudStream
         * Episode therefore kept only the watch URL and loadLinks() later had
         * to rediscover the media source, causing "No Links Found" on episodes
         * whose playable links only exist inside serialized `links[]`.
         *
         * Episode number + season are the stable identity here. When the
         * serialized record arrives, it replaces the shell record so its
         * exact source payload is cached and reused by loadLinks().
         */
        val existingKey = result.keys.firstOrNull { key ->
            key.startsWith("$season:$episode:")
        }

        /*
         * Put the exact episode source payload into Episode.data.
         *
         * This follows the same final playback concept as the working movie
         * path: the selected Episode carries its concrete media URL(s).
         * Therefore loadLinks() does not depend on an in-memory cache surviving
         * between the series detail screen and the player.
         *
         * If CTG has one or more links[] entries, serialize those exact
         * episode sources here. Otherwise retain the exact watch URL as a
         * runtime fallback.
         */
        /*
         * Use the same data model as the original CTG implementation:
         * the Episode carries its exact watch URL. The selected episode_id
         * is recovered from that URL at Play time, so playback always starts
         * from the same per-episode page that works in a browser.
         *
         * The rich episode metadata is still assigned below.
         */
        val episodeDataPayload = cleanData

        val episodeData = newEpisode(episodeDataPayload) {
            this.name = name.ifBlank {
                "Episode $episode"
            }
            this.season = season
            this.episode = episode
            this.posterUrl = posterUrl
            this.description = description
            this.date = parseEpisodeDate(
                airDate
            )
            this.runTime = runTime
        }

        /*
         * Serialized `allEpisodes[]` is the authoritative episode record.
         * When it contains one or more real links[], replace the lightweight
         * DOM shell with the link-bearing episode data.
         */
        if (existingKey != null) {
            // The serialized CTG episode record is authoritative for metadata.
            // Always replace the lightweight DOM shell, even when links[] is
            // temporarily absent in a partial response. Playback can still
            // resolve the exact watch URL later.
            result.remove(existingKey)
        }

        result[episodeIdentity(cleanData, season, episode)] = episodeData
    }

    private fun parseSerializedEpisodes(
        html: String,
        baseUrl: String,
        seriesSlug: String?
    ): List<ParsedEpisode> {
        if (html.isBlank()) {
            return emptyList()
        }

        val normalized = normalizeCtgPayload(
            html
        )

        val arrays = extractJsonArraysAfterKey(
            normalized,
            "\"allEpisodes\""
        )

        if (arrays.isEmpty()) {
            return emptyList()
        }

        val result = linkedMapOf<String, ParsedEpisode>()

        arrays.forEach { arrayText ->
            extractTopLevelJsonObjects(
                arrayText
            ).forEach { objectText ->
                val season =
                    extractJsonPrimitive(
                        objectText,
                        "season_number"
                    )?.toIntOrNull()
                        ?: return@forEach

                val episode =
                    extractJsonPrimitive(
                        objectText,
                        "episode_number"
                    )?.toIntOrNull()
                        ?: return@forEach

                val id = extractJsonString(
                    objectText,
                    "id"
                ) ?: return@forEach

                val title =
                    extractJsonString(
                        objectText,
                        "name"
                    ).orEmpty().ifBlank {
                        "Episode $episode"
                    }

                val description =
                    extractJsonString(
                        objectText,
                        "overview"
                    )?.takeIf {
                        it.isNotBlank()
                    }

                val poster =
                    extractJsonString(
                        objectText,
                        "still_url"
                    )?.takeIf {
                        it.isNotBlank()
                    }?.let {
                        absoluteUrl(
                            cleanUrl(it),
                            baseUrl
                        )
                    }

                val airDate =
                    extractJsonString(
                        objectText,
                        "air_date"
                    )

                val runTime =
                    extractJsonPrimitive(
                        objectText,
                        "runtime"
                    )?.toIntOrNull()

                val watchUrl =
                    buildEpisodeWatchUrl(
                        episodeId = id,
                        seriesSlug = seriesSlug
                    )

                val playbackSources =
                    extractEpisodePlaybackSources(
                        objectText = objectText,
                        baseUrl = baseUrl,
                        episodeId = id
                    ).ifEmpty {
                        extractEpisodePlaybackSourcesFast(
                            html = normalized,
                            baseUrl = baseUrl,
                            episodeId = id
                        )
                    }

                if (playbackSources.isNotEmpty()) {
                    episodePlaybackCache[episodeCacheKey(seriesSlug, id)] =
                        playbackSources
                    episodePlaybackCache[id] = playbackSources
                }

                val dataUrl = watchUrl

                val parsed = ParsedEpisode(
                    dataUrl = dataUrl,
                    name = cleanTitle(title),
                    season = season,
                    episode = episode,
                    posterUrl = poster,
                    description = description,
                    airDate = airDate,
                    runTime = runTime,
                    playbackSources = playbackSources
                )

                result[
                    episodeIdentity(
                        dataUrl,
                        season,
                        episode
                    )
                ] = parsed
            }
        }

        return result.values.toList()
    }

    /**
     * Resolve one exact episode from CTG's authoritative allEpisodes[] payload.
     *
     * Unlike a generic whole-page media scan, this first selects the episode
     * object by its stable CTG episode id and only then reads that object's
     * links[]. This guarantees Episode N can only emit Episode N sources.
     */
    /** Exact fallback for the link object belonging to one episode id. */
    private fun extractEpisodePlaybackSourcesFast(
        html: String,
        baseUrl: String,
        episodeId: String
    ): List<CtgPlaybackSource> {
        if (html.isBlank() || episodeId.isBlank()) return emptyList()

        val marker = "\"episode_id\":\"$episodeId\""
        val result = mutableListOf<CtgPlaybackSource>()
        var from = 0

        while (true) {
            val markerIndex = html.indexOf(marker, from)
            if (markerIndex < 0) break

            val objectStart = html.lastIndexOf('{', markerIndex)
            val objectText = if (objectStart >= 0) {
                extractJsonObjectAt(html, objectStart)
            } else null

            if (objectText != null &&
                extractJsonString(objectText, "episode_id") == episodeId
            ) {
                val quality = extractJsonString(objectText, "quality")
                val sourceName = extractJsonString(objectText, "source")
                val language = extractJsonString(objectText, "language")
                val subtitles = extractSubtitleTracks(objectText, baseUrl)

                listOfNotNull(
                    extractJsonString(objectText, "url"),
                    extractJsonString(objectText, "hls_url")
                ).forEach { rawUrl ->
                    val media = absoluteUrl(cleanUrl(rawUrl), baseUrl)
                    if (isMediaUrl(media)) {
                        result += CtgPlaybackSource(
                            url = media,
                            quality = quality,
                            sourceName = sourceName,
                            language = language,
                            episodeId = episodeId,
                            movieId = null,
                            subtitleTracks = subtitles
                        )
                    }
                }
            }

            from = markerIndex + marker.length
        }

        return result.distinctBy { mediaDedupKey(it.url) }
    }

    private fun extractJsonObjectAt(
        text: String,
        objectStart: Int
    ): String? {
        var depth = 0
        var inString = false
        var escaped = false

        for (index in objectStart until text.length) {
            val ch = text[index]

            if (inString) {
                if (escaped) escaped = false
                else if (ch == '\\') escaped = true
                else if (ch == '"') inString = false
                continue
            }

            when (ch) {
                '"' -> inString = true
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) {
                        return text.substring(objectStart, index + 1)
                    }
                }
            }
        }

        return null
    }

    private fun extractEpisodeSourcesFromAllEpisodes(
        html: String,
        baseUrl: String,
        episodeId: String
    ): List<CtgPlaybackSource> {
        if (html.isBlank() || episodeId.isBlank()) return emptyList()

        val normalized = normalizeCtgPayload(html)
        val arrays = extractJsonArraysAfterKey(
            normalized,
            "\"allEpisodes\""
        )

        for (arrayText in arrays) {
            for (objectText in extractTopLevelJsonObjects(arrayText)) {
                val id = extractJsonString(objectText, "id")
                    ?: continue

                if (id != episodeId) continue

                val sources = extractEpisodePlaybackSources(
                    objectText = objectText,
                    baseUrl = baseUrl,
                    episodeId = episodeId
                )

                if (sources.isNotEmpty()) {
                    return sources
                }
            }
        }

        return emptyList()
    }

    private fun extractEpisodePlaybackSources(
        objectText: String,
        baseUrl: String,
        episodeId: String
    ): List<CtgPlaybackSource> {
        val arrays = extractJsonArraysAfterKey(
            objectText,
            "\"links\""
        )

        if (arrays.isEmpty()) return emptyList()

        val parsed = mutableListOf<CtgPlaybackSource>()

        arrays.forEach { arrayText ->
            extractTopLevelJsonObjects(arrayText)
                .forEach { linkObject ->
                    val linkEpisodeId = extractJsonString(
                        linkObject,
                        "episode_id"
                    )

                    if (
                        !linkEpisodeId.isNullOrBlank() &&
                        linkEpisodeId != episodeId
                    ) {
                        return@forEach
                    }

                    val quality = extractJsonString(
                        linkObject,
                        "quality"
                    )
                    val sourceName = extractJsonString(
                        linkObject,
                        "source"
                    )
                    val language = extractJsonString(
                        linkObject,
                        "language"
                    )
                    val subtitleTracks = extractSubtitleTracks(
                        linkObject,
                        baseUrl
                    )

                    listOfNotNull(
                        extractJsonString(linkObject, "url"),
                        extractJsonString(linkObject, "hls_url")
                    ).forEach { rawUrl ->
                        val media = absoluteUrl(
                            cleanUrl(rawUrl),
                            baseUrl
                        )

                        if (isMediaUrl(media)) {
                            parsed.add(
                                CtgPlaybackSource(
                                    url = media,
                                    quality = quality,
                                    sourceName = sourceName,
                                    language = language,
                                    episodeId = episodeId,
                                    movieId = null,
                                    subtitleTracks = subtitleTracks
                                )
                            )
                        }
                    }
                }
        }

        return parsed.distinctBy { mediaDedupKey(it.url) }
    }

    private fun mediaDedupKey(
        url: String
    ): String {
        var value = cleanUrl(url)

        repeat(2) {
            value = runCatching {
                URLDecoder.decode(
                    value,
                    StandardCharsets.UTF_8.toString()
                )
            }.getOrElse { value }
        }

        return runCatching {
            val uri = URI(value)
            val scheme = uri.scheme.orEmpty().lowercase(Locale.ROOT)
            val host = uri.host.orEmpty().lowercase(Locale.ROOT)
            val path = uri.path.orEmpty()
            val query = uri.rawQuery.orEmpty()
            "$scheme://$host$path${if (query.isNotBlank()) "?$query" else ""}"
        }.getOrElse {
            value
        }
    }

    private fun buildEpisodeWatchUrl(
        episodeId: String,
        seriesSlug: String?
    ): String {
        val encodedSlug =
            seriesSlug?.takeIf {
                it.isNotBlank()
            }?.let {
                URLEncoder.encode(
                    it,
                    StandardCharsets.UTF_8.toString()
                )
            }

        return if (encodedSlug.isNullOrBlank()) {
            "$mainUrl/watch/$episodeId?type=episode"
        } else {
            "$mainUrl/watch/$episodeId?type=episode&series=$encodedSlug"
        }
    }

    private fun seriesSlugFromUrl(
        url: String
    ): String? {
        val path = runCatching {
            URI(url).path.orEmpty()
        }.getOrNull() ?: return null

        return path
            .trimEnd('/')
            .substringAfterLast('/')
            .takeIf { it.isNotBlank() }
    }

    private fun watchIdOrEmpty(
        url: String
    ): String {
        return if (isWatchUrl(url)) watchId(url) else ""
    }

    private fun episodeIdentity(
        dataUrl: String,
        season: Int,
        episode: Int
    ): String {
        return "$season:$episode:${cleanUrl(dataUrl)}"
    }

    private fun parseEpisodeDate(
        value: String?
    ): Long? {
        if (value.isNullOrBlank()) {
            return null
        }

        return runCatching {
            SimpleDateFormat(
                "yyyy-MM-dd",
                Locale.ROOT
            ).apply {
                timeZone = TimeZone.getTimeZone(
                    "UTC"
                )
            }.parse(value)?.time
        }.getOrNull()
    }

    private fun isEpisodeWatchUrl(
        url: String
    ): Boolean {
        if (!isWatchUrl(url)) return false

        return queryParam(
            url,
            "type"
        )?.lowercase(Locale.ROOT) == "episode" ||
            url.contains(
                "type=episode",
                ignoreCase = true
            )
    }

    private fun episodeNumber(
        element: Element,
        url: String
    ): Int {
        val data = sequenceOf(
            element.attr("data-episode"),
            element.attr("data-ep")
        ).firstOrNull { it.isNotBlank() }

        if (data != null) {
            data.toIntOrNull()?.let { return it }
        }

        val candidates = listOf(
            Regex("""(?i)episode[\s._-]*(\d+)""").find(element.text()),
            Regex("""(?i)\bep[\s._-]*(\d+)""").find(element.text()),
            Regex("""(?i)episode=(\d+)""").find(url),
            Regex("""(?i)[?&]ep=(\d+)""").find(url),
            Regex("""(?i)/episode/(\d+)""").find(url),
            Regex("""(?i)\bs\d{1,2}\s*e(\d{1,3})""").find(element.text())
        )

        return candidates.firstNotNullOfOrNull {
            it?.groupValues?.getOrNull(1)?.toIntOrNull()
        } ?: 1
    }

    private fun seasonNumber(
        element: Element,
        url: String
    ): Int {
        element.attr("data-season")
            .toIntOrNull()
            ?.let { return it }

        val candidates = listOf(
            Regex("""(?i)season[\s._-]*(\d+)""").find(element.text()),
            Regex("""(?i)\bS(\d{1,2})E\d{1,3}\b""").find(element.text()),
            Regex("""(?i)season=(\d+)""").find(url)
        )

        return candidates.firstNotNullOfOrNull {
            it?.groupValues?.getOrNull(1)?.toIntOrNull()
        } ?: 1
    }

    private fun extractMediaUrls(
        document: Document,
        html: String,
        baseUrl: String
    ): List<String> {
        val found = linkedSetOf<String>()

        fun add(raw: String?) {
            if (raw.isNullOrBlank()) return

            val value = cleanUrl(raw)
            if (
                value.isBlank() ||
                value.startsWith("data:", true) ||
                value.startsWith("javascript:", true)
            ) {
                return
            }

            val absolute = absoluteUrl(                value,
                baseUrl
            )

            if (isMediaUrl(absolute)) {
                found.add(absolute)
            }
        }

        document.select(
            "video[src], " +
            "video[poster], " +
            "video source[src], " +
            "source[src], " +
            "[data-src], " +
            "[data-file], " +
            "[data-video], " +
            "[data-video-url], " +
            "[data-file-url], " +
            "[data-stream], " +
            "[data-manifest]"
        ).forEach { element ->
            add(element.attr("src"))
            add(element.attr("data-src"))
            add(element.attr("data-file"))
            add(element.attr("data-video"))
            add(element.attr("data-video-url"))
            add(element.attr("data-file-url"))
            add(element.attr("data-stream"))
            add(element.attr("data-manifest"))
        }

        /*
         * Small, valid media-only URL regex. It does not contain nested
         * optional groups like the broken CTG implementation.
         */
        val mediaRegex = Regex(
            """(?i)https?://[^"'<>\s]+\.(?:m3u8|mpd|mp4|mkv|webm|mov|m4v|avi|flv|ts)(?:\?[^"'<>\s]*)?"""
        )

        mediaRegex.findAll(
            html
                .replace("\\/", "/")
                .replace("&amp;", "&")
                .replace("\\u0026", "&")
        ).forEach { match ->
            add(match.value)
        }

        /*
         * Common JavaScript key/value forms.
         */
        val keyRegex = Regex(
            """(?i)(?:file|src|source|video|videoUrl|media|mediaUrl|fileUrl|stream|streamUrl|playlist|manifest)\s*[:=]\s*["']([^"']+)["']"""
        )

        keyRegex.findAll(
            html
                .replace("\\/", "/")
                .replace("\\u0026", "&")
        ).forEach { match ->
            add(match.groupValues[1])
        }

        return found.toList()
    }

    private fun recoverDownloadUrls(
        document: Document,
        html: String,
        baseUrl: String
    ): List<String> {
        val found = linkedSetOf<String>()

        document.select("a[href], button[data-url], [data-file-url]").forEach { element ->
            val raw = sequenceOf(
                element.attr("href"),
                element.attr("data-url"),
                element.attr("data-file-url")
            ).firstOrNull { it.isNotBlank() } ?: return@forEach

            recoverQueryMedia(
                raw,
                baseUrl
            )?.let(found::add)
        }

        /*
         * Also inspect the HTML without trying to match the whole JavaScript
         * structure. This keeps the parser safe when the site's markup changes.
         */
        html
            .replace("\\/", "/")
            .replace("&amp;", "&")
            .split('"', '\'', ' ', '\n', '\r', '\t', '<', '>', '(', ')')
            .forEach { token ->
                if (
                    token.contains("download", true) ||
                    token.contains("stream", true) ||
                    token.contains("file=", true)
                ) {
                    recoverQueryMedia(token, baseUrl)?.let(found::add)
                }
            }

        return found.toList()
    }

    private fun recoverQueryMedia(
        raw: String,
        baseUrl: String
    ): String? {
        val absolute = absoluteUrl(
            cleanUrl(raw),
            baseUrl
        )

        val query = runCatching {
            URI(absolute).rawQuery.orEmpty()
        }.getOrDefault("")

        if (query.isBlank()) return null

        query.split('&').forEach { part ->
            val key = part.substringBefore('=')
                .trim()
                .lowercase(Locale.ROOT)

            if (
                key != "file" &&
                key != "url" &&
                key != "src" &&
                key != "video" &&
                key != "stream" &&
                key != "source" &&
                key != "fileurl" &&
                key != "videourl"
            ) {
                return@forEach
            }

            val rawValue = part.substringAfter('=', "")
            val value = runCatching {
                URLDecoder.decode(
                    rawValue,
                    StandardCharsets.UTF_8.toString()
                )
            }.getOrNull()?.trim().orEmpty()

            if (value.isBlank()) return@forEach

            val candidate = absoluteUrl(
                value,
                baseUrl
            )

            if (isMediaUrl(candidate)) {
                return candidate
            }
        }

        return null
    }

    /*
     * Recover actual media URLs from CTG's server/download controls.
     *
     * This handles:
     * - direct FTP URLs
     * - percent-encoded FTP URLs
     * - file/url/src/video/stream query parameters
     * - href/data-* attributes
     *
     * It deliberately does not require a specific server name.
     */
    private fun recoverPlayableUrls(
        document: Document,
        html: String,
        baseUrl: String
    ): List<String> {
        val found = linkedSetOf<String>()

        fun addCandidate(raw: String?) {
            if (raw.isNullOrBlank()) return

            var value = cleanUrl(raw)

            repeat(2) {
                value = runCatching {
                    URLDecoder.decode(
                        value,
                        StandardCharsets.UTF_8.toString()
                    )
                }.getOrElse {
                    value
                }
            }

            val absolute = absoluteUrl(
                value,
                baseUrl
            )

            if (isMediaUrl(absolute)) {
                found.add(absolute)
            }
        }

        document.select(
            "a[href], " +
            "[data-url], " +
            "[data-href], " +
            "[data-file], " +
            "[data-src], " +
            "[data-video], " +
            "[data-video-url], " +
            "[data-file-url], " +
            "[data-stream]"
        ).forEach { element ->
            addCandidate(element.attr("href"))
            addCandidate(element.attr("data-url"))
            addCandidate(element.attr("data-href"))
            addCandidate(element.attr("data-file"))
            addCandidate(element.attr("data-src"))
            addCandidate(element.attr("data-video"))
            addCandidate(element.attr("data-video-url"))
            addCandidate(element.attr("data-file-url"))
            addCandidate(element.attr("data-stream"))

            val rawHref = element.attr("href")
            if (rawHref.contains("download", true) ||
                rawHref.contains("stream", true)
            ) {
                recoverQueryMedia(
                    rawHref,
                    baseUrl
                )?.let(found::add)
            }
        }

        /*
         * Search the raw HTML for ftp.ctgfun.com first. This is the actual
         * storage host shown by the supplied working browser URL.
         */
        val ftpRegex = Regex(
            """(?i)https?://ftp\.ctgfun\.com/[^"'<>\s\\]+"""
        )

        ftpRegex.findAll(
            html
                .replace("\\/", "/")
                .replace("\\u0026", "&")
                .replace("&amp;", "&")
        ).forEach { match ->
            addCandidate(match.value)
        }

        /*
         * Decode any percent-encoded FTP URL embedded in the page.
         */
        val encodedFtpRegex = Regex(
            """(?i)(?:https?%3A%2F%2F|https?%253A%252F%252F)ftp%\.ctgfun\.com%2F[^"'<>\s]+"""
        )

        encodedFtpRegex.findAll(
            html
                .replace("\\/", "/")
                .replace("&amp;", "&")
        ).forEach { match ->
            addCandidate(match.value)
        }

        /*
         * Finally inspect common query parameters.
         */
        html
            .replace("\\/", "/")
            .replace("&amp;", "&")
            .split(
                '"', '\'', ' ', '\n', '\r', '\t',
                '<', '>', '(', ')'
            )
            .forEach { token ->
                recoverQueryMedia(
                    token,
                    baseUrl
                )?.let(found::add)
            }

        return found.toList()
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
        val og = element.selectFirst(
            "meta[property=og:image], meta[name=twitter:image]"
        )?.attr("content")

        if (!og.isNullOrBlank()) {
            return absoluteUrl(
                og,
                pageUrl
            )
        }

        val image = element.select(
            "img[src], " +
            "img[data-src], " +
            "img[data-lazy-src], " +
            "img[data-original], " +
            "img[data-poster]"
        ).firstOrNull()

        if (image != null) {
            val source = sequenceOf(
                image.attr("data-poster"),
                image.attr("data-src"),
                image.attr("data-lazy-src"),
                image.attr("data-original"),
                image.attr("src")
            ).firstOrNull { it.isNotBlank() }

            if (!source.isNullOrBlank()) {
                return absoluteUrl(
                    source,
                    pageUrl
                )
            }
        }

        return null
    }

    private fun findCard(
        element: Element
    ): Element {
        var current: Element? = element

        repeat(8) {
            val node = current ?: return@repeat

            val className = node.className()
                .lowercase(Locale.ROOT)

            if (
                node.select("img").isNotEmpty() ||
                className.contains("card") ||
                className.contains("movie") ||
                className.contains("poster") ||
                className.contains("item")
            ) {
                return node
            }

            current = node.parent()
        }

        return element
    }

    private fun extractPageTitle(
        document: Document
    ): String {
        val candidates = listOf(
            document.selectFirst("h1")?.text(),
            document.selectFirst("h2")?.text(),
            document.selectFirst(".title")?.text(),
            document.selectFirst(".movie-title")?.text(),
            document.selectFirst(".movie_name")?.text(),
            document.selectFirst("meta[property=og:title]")?.attr("content"),
            document.title()
        )

        return cleanTitle(
            candidates.firstOrNull {
                !it.isNullOrBlank()
            }.orEmpty()
        )
    }

    private fun extractPlot(
        document: Document
    ): String? {
        /*
         * CTG uses a <section><h2>Synopsis</h2><p>...</p></section>
         * rather than .plot/.description classes.
         */
        val synopsis = document
            .select("section")
            .firstNotNullOfOrNull { section ->
                val heading = section
                    .selectFirst("h2")
                    ?.text()
                    ?.trim()

                if (
                    heading.equals(
                        "Synopsis",
                        ignoreCase = true
                    )
                ) {
                    section.selectFirst("p")
                        ?.text()
                        ?.trim()
                        ?.takeIf {
                            it.isNotBlank()
                        }
                } else {
                    null
                }
            }

        val values = listOf(
            synopsis,
            document.selectFirst(
                "meta[property=og:description]"
            )?.attr("content"),
            document.selectFirst(
                "meta[name=description]"
            )?.attr("content"),
            document.selectFirst(
                ".description"
            )?.text(),
            document.selectFirst(
                ".plot"
            )?.text(),
            document.selectFirst(
                ".overview"
            )?.text(),
            extractJsonStringFromDocument(
                document,
                "overview"
            )
        )

        return values.firstOrNull {
            !it.isNullOrBlank()
        }?.trim()
    }


    private fun extractJsonStringFromDocument(
        document: Document,
        key: String
    ): String? {
        val normalized = normalizeCtgPayload(
            document.html()
        )

        return extractJsonString(
            normalized,
            key
        )?.takeIf { it.isNotBlank() }
    }

    private fun extractYear(
        document: Document
    ): Int? {
        val text = document.text()

        return Regex("""(?<!\d)(19\d{2}|20\d{2}|21\d{2})(?!\d)""")
            .find(text)
            ?.groupValues
            ?.getOrNull(1)
            ?.toIntOrNull()
    }

    private fun queryParam(
        url: String,
        name: String
    ): String? {
        val query = runCatching {
            URI(url).rawQuery.orEmpty()
        }.getOrDefault("")

        if (query.isBlank()) {
            return null
        }

        val target = name.lowercase(Locale.ROOT)

        return query.split('&').firstNotNullOfOrNull { part ->
            val key = part.substringBefore('=')
                .lowercase(Locale.ROOT)

            if (key != target) {
                return@firstNotNullOfOrNull null
            }

            val rawValue =
                part.substringAfter('=', "")

            runCatching {
                URLDecoder.decode(
                    rawValue,
                    StandardCharsets.UTF_8.toString()
                )
            }.getOrNull()
        }
    }

    private fun watchId(
        url: String
    ): String {
        val path = runCatching {
            URI(url).path.orEmpty()
        }.getOrDefault(url)

        return path
            .trimEnd('/')
            .substringAfterLast('/')
            .trim()
    }

    private fun isWatchUrl(
        url: String
    ): Boolean {
        val path = runCatching {
            URI(url).path.orEmpty()
                .lowercase(Locale.ROOT)
        }.getOrDefault("")

        return path.startsWith("/watch/")
    }

    private fun canonicalContentUrl(
        url: String
    ): String {
        return runCatching {
            val uri = URI(url)

            URI(
                uri.scheme ?: "https",
                uri.host,
                uri.path.orEmpty()
                    .trimEnd('/'),
                null,
                null
            ).toString()
                .trimEnd('/')
        }.getOrElse {
            cleanUrl(url)
                .substringBefore('#')
                .substringBefore('?')
                .trimEnd('/')
        }
    }

    private fun typeFromUrl(
        url: String
    ): TvType {
        val path = runCatching {
            URI(url).path.orEmpty().lowercase(Locale.ROOT)
        }.getOrElse {
            url.lowercase(Locale.ROOT)
        }

        return when {
            path.startsWith("/tv/") -> TvType.TvSeries
            path.startsWith("/anime/") -> TvType.Anime
            else -> TvType.Movie
        }
    }

    private fun isContentUrl(
        url: String
    ): Boolean {
        val path = runCatching {
            URI(url).path.orEmpty().lowercase(Locale.ROOT)
        }.getOrElse {
            url.lowercase(Locale.ROOT)
        }

        return path.startsWith("/movies/") ||
            path.startsWith("/tv/") ||
            path.startsWith("/anime/")
    }

    private fun isAudioOnlyMediaUrl(
        url: String
    ): Boolean {
        val lower = url.lowercase(Locale.ROOT)

        return lower.contains(".audio.") ||
            lower.contains("/audio/") ||
            lower.contains("audio_")
    }

    private fun isMediaUrl(
        url: String
    ): Boolean {
        val path = runCatching {
            URI(url).path.orEmpty().lowercase(Locale.ROOT)
        }.getOrElse {
            url.lowercase(Locale.ROOT)
        }

        return mediaExtensions.any {
            path.endsWith(it)
        } || path.contains(".mp4") ||
            path.contains(".mkv") ||
            path.contains(".m3u8") ||
            path.contains(".mpd")
    }

    private fun qualityFromHint(
        quality: String?
    ): Int? {
        val lower = quality
            ?.lowercase(Locale.ROOT)
            ?: return null

        return when {
            lower.contains("4320") || lower.contains("8k") ->
                4320

            lower.contains("2160") || lower.contains("4k") ->
                Qualities.P2160.value

            lower.contains("1440") ->
                Qualities.P1440.value

            lower.contains("1080") ->
                Qualities.P1080.value

            lower.contains("720") ->
                Qualities.P720.value

            lower.contains("480") ->
                Qualities.P480.value

            lower.contains("360") ->
                Qualities.P360.value

            else ->
                null
        }
    }

    private fun qualityFromUrl(
        url: String
    ): Int {
        val lower = url.lowercase(Locale.ROOT)

        return when {
            lower.contains("4320") || lower.contains("8k") ->
                4320

            lower.contains("2160") || lower.contains("4k") ->
                Qualities.P2160.value

            lower.contains("1440") ->
                Qualities.P1440.value

            lower.contains("1080") ->
                Qualities.P1080.value

            lower.contains("720") ->
                Qualities.P720.value

            lower.contains("480") ->
                Qualities.P480.value

            lower.contains("360") ->
                Qualities.P360.value

            else ->
                Qualities.Unknown.value
        }
    }

    private fun hasNextPage(
        document: Document,
        currentPage: Int
    ): Boolean {
        val next = document.select("a[href]").firstOrNull { anchor ->
            val text = anchor.text()
                .trim()
                .lowercase(Locale.ROOT)

            val rel = anchor.attr("rel")
                .lowercase(Locale.ROOT)

            val href = anchor.attr("href")
                .lowercase(Locale.ROOT)

            text.contains("next") ||
                rel.contains("next") ||
                href.contains("page=${currentPage + 1}")
        }

        return next != null
    }

    private fun pageUrl(
        base: String,
        page: Int
    ): String {
        if (page <= 1) return base

        return if (base.contains("?")) {
            "$base&page=$page"
        } else {
            "$base?page=$page"
        }
    }

    private fun pageSuffix(
        page: Int
    ): String {
        return if (page > 1) {
            "&page=$page"
        } else {
            ""
        }
    }

    private fun absoluteUrl(
        raw: String,
        base: String
    ): String {
        val value = cleanUrl(raw)

        if (value.startsWith("//")) {
            val scheme = runCatching {
                URI(base).scheme
            }.getOrNull() ?: "https"

            return "$scheme:$value"
        }

        if (
            value.startsWith("http://", true) ||
            value.startsWith("https://", true)
        ) {
            return value
        }

        return runCatching {
            URI(base).resolve(value).toString()
        }.getOrElse {
            value
        }
    }

    private fun titleFromUrl(
        url: String
    ): String {
        val path = runCatching {
            URI(url).path.orEmpty()
        }.getOrElse {
            url
        }

        val slug = path
            .trimEnd('/')
            .substringAfterLast('/')

        return slug
            .replace('-', ' ')
            .replace('_', ' ')
            .replaceFirstChar {
                if (it.isLowerCase()) {
                    it.titlecase(Locale.ROOT)
                } else {
                    it.toString()
                }
            }
            .ifBlank {
                "CTG FTP"
            }
    }

    private fun cleanTitle(
        value: String
    ): String {
        return value
            .replace(Regex("""\s+"""), " ")
            .trim()
    }

    private fun cleanUrl(
        raw: String
    ): String {
        return raw
            .trim()
            .replace("\\/", "/")
            .replace("\\u0026", "&")
            .replace("&amp;", "&")
            .trim('"', '\'', '`')
            .trimEnd(',', ';', ')', ']', '}')
    }

    private fun firstNonBlank(
        vararg values: String?
    ): String {
        return values.firstOrNull {
            !it.isNullOrBlank()
        }?.trim().orEmpty()
    }

    private fun isNavigationTitle(
        value: String
    ): Boolean {
        return value.lowercase(Locale.ROOT) in setOf(
            "home",
            "movies",
            "tv",
            "tv shows",
            "anime",
            "games",
            "search",
            "all",
            "newest",
            "popular",
            "top rated",
            "next",
            "previous",
            "details",
            "play"
        )
    }
}