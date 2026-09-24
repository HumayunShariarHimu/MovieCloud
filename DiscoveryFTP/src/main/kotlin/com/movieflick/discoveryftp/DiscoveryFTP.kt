package com.movieflick.discoveryftp

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class DiscoveryFTP : MainAPI() {

    // TV playback fix v22: HTTPS-upgrade Discovery CDN media URLs.

    override var mainUrl = BASE_URL
    override var name = "Discovery FTP"
    override var lang = "bn"

    override val hasMainPage = true
    override val hasQuickSearch = true

    override val supportedTypes = setOf(
        TvType.Movie,
        TvType.TvSeries,
        TvType.Anime
    )

    /*
     * Only these five rows are exposed to CloudStream.
     *
     * 1. Movies
     * 2. Dual Audio
     * 3. Hindi Movies
     * 4. TV Show
     * 5. Anime
     */
    override val mainPage = mainPageOf(
        "discovery://movies" to "Movies",
        "discovery://dual" to "Dual Audio",
        "discovery://hindi" to "Hindi Movies",
        "discovery://tv" to "TV Show",
        "discovery://anime" to "Anime"
    )

    private companion object {
        const val BASE_URL = "https://movies.discoveryftp.net"
        const val INITIAL_BATCH = 6
        const val CONTINUE_BATCH = 10
        const val SOURCE_PREFETCH = 4
        const val SEARCH_MAX_PAGES = 5
        const val DUPLICATE_INDEX_MAX_PAGES = 6

        const val DISCOVERY_USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) " +
                "AppleWebKit/537.36 (KHTML, like Gecko) " +
                "Chrome/153.0.0.0 Safari/537.36"

        val DUAL_SOURCES = listOf(
            Source("$BASE_URL/s/category/Dubbed", SourceKind.SERIES),
            Source("$BASE_URL/m/dual/Animation", SourceKind.MOVIE),
            Source("$BASE_URL/m/dual/Bangla", SourceKind.MOVIE),
            Source("$BASE_URL/m/dual/English", SourceKind.MOVIE),
            Source("$BASE_URL/m/dual/Others", SourceKind.MOVIE),
            Source("$BASE_URL/m/dual/Tamil", SourceKind.MOVIE)
        )

        val ANIME_SOURCES = listOf(
            Source("$BASE_URL/m/category/Animation", SourceKind.ANIME_MOVIE),
            Source("$BASE_URL/s/category/Animation", SourceKind.ANIME_SERIES)
        )

        val SEARCH_SOURCES = listOf(
            Source("$BASE_URL/m", SourceKind.MOVIE),
            Source("$BASE_URL/s", SourceKind.SERIES),
            Source("$BASE_URL/m/category/Animation", SourceKind.ANIME_MOVIE),
            Source("$BASE_URL/s/category/Animation", SourceKind.ANIME_SERIES)
        )
    }

    private enum class SourceKind {
        MOVIE,
        SERIES,
        ANIME_MOVIE,
        ANIME_SERIES
    }

    private data class Source(
        val url: String,
        val kind: SourceKind
    )

    private data class SiteItem(
        val title: String,
        val url: String,
        val poster: String?,
        val type: TvType,
        val source: String,
        val order: Int,
        val year: Int? = null,
        val qualityLabel: String = "",
        val qualityRank: Int = 0
    )

    private val pageCache =
        ConcurrentHashMap<String, List<SiteItem>>()

    private val protectedIndexMutex = Mutex()
    @Volatile
    private var protectedDuplicateKeys: Set<String>? = null

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

    private fun pageHeaders(referer: String = "$mainUrl/"): Map<String, String> = mapOf(
        "User-Agent" to DISCOVERY_USER_AGENT,
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
        "Accept-Language" to "en-US,en;q=0.9,bn;q=0.8",
        "Cache-Control" to "no-cache",
        "Pragma" to "no-cache",
        "Referer" to referer
    )

    private fun SiteItem.toSearchResponse(): SearchResponse {
        val displayTitle = formattedDisplayTitle()

        return when (type) {
            TvType.TvSeries -> newTvSeriesSearchResponse(
                displayTitle,
                url,
                TvType.TvSeries
            ) {
                posterUrl = poster
                this.year = year
                if (qualityLabel.isNotBlank()) {
                    addQuality(qualityLabel)
                }
            }

            TvType.Anime -> newMovieSearchResponse(
                displayTitle,
                url,
                TvType.Anime
            ) {
                posterUrl = poster
                this.year = year
                if (qualityLabel.isNotBlank()) {
                    addQuality(qualityLabel)
                }
            }

            else -> newMovieSearchResponse(
                displayTitle,
                url,
                TvType.Movie
            ) {
                posterUrl = poster
                this.year = year
                if (qualityLabel.isNotBlank()) {
                    addQuality(qualityLabel)
                }
            }
        }
    }

    private fun SiteItem.formattedDisplayTitle(): String {
        val clean = title
            .replace(Regex("\\s+"), " ")
            .trim()

        if (qualityLabel.isBlank()) return clean

        return "$clean [$qualityLabel]"
    }

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        val pageNumber = page.coerceAtLeast(1)

        return when (request.data) {
            "discovery://movies" -> buildSingleSourceHome(
                request,
                pageNumber,
                Source("$BASE_URL/m", SourceKind.MOVIE),
                excludeProtectedDuplicates = true
            )

            "discovery://dual" -> buildMergedHome(
                request,
                pageNumber,
                DUAL_SOURCES
            )

            "discovery://hindi" -> buildSingleSourceHome(
                request,
                pageNumber,
                Source("$BASE_URL/m/dual/Hindi", SourceKind.MOVIE),
                excludeProtectedDuplicates = true
            )

            "discovery://tv" -> buildSingleSourceHome(
                request,
                pageNumber,
                Source("$BASE_URL/s", SourceKind.SERIES)
            )

            "discovery://anime" -> buildMergedHome(
                request,
                pageNumber,
                ANIME_SOURCES
            )

            else -> newHomePageResponse(
                request,
                emptyList(),
                false
            )
        }
    }

    private suspend fun buildSingleSourceHome(
        request: MainPageRequest,
        page: Int,
        source: Source,
        excludeProtectedDuplicates: Boolean = false
    ): HomePageResponse {
        val required = requiredCount(page)
        val excluded = if (excludeProtectedDuplicates) {
            getProtectedDuplicateKeys()
        } else {
            emptySet()
        }

        val all = getItemsUpTo(
            source = source,
            requiredCount = required,
            excludedKeys = excluded
        )

        val offset = homeOffset(page)
        val take = homeTake(page)
        val current = all.drop(offset).take(take)

        val hasNext = all.size > offset + current.size ||
            fetchPage(source, page + 1).isNotEmpty()

        return newHomePageResponse(
            request,
            current.map { it.toSearchResponse() },
            hasNext
        )
    }

    private suspend fun buildMergedHome(
        request: MainPageRequest,
        page: Int,
        sources: List<Source>
    ): HomePageResponse = coroutineScope {
        if (sources.isEmpty()) {
            return@coroutineScope newHomePageResponse(
                request,
                emptyList(),
                false
            )
        }

        val required = requiredCount(page)

        /*
         * Every source contributes in round-robin order. The order within
         * each source is preserved, so the site's newest-first ordering is
         * not alphabetized or randomly shuffled.
         */
        val perSource = ((required + sources.size - 1) / sources.size) + SOURCE_PREFETCH

        val sourceLists = sources.map { source ->
            async {
                getItemsUpTo(source, perSource)
            }
        }.awaitAll()

        val merged = collapseByTitle(
            interleave(sourceLists)
        )

        val offset = homeOffset(page)
        val take = homeTake(page)
        val current = merged.drop(offset).take(take)

        val hasNext = merged.size > offset + current.size ||
            sources.any { fetchPage(it, page + 1).isNotEmpty() }

        newHomePageResponse(
            request,
            current.map { it.toSearchResponse() },
            hasNext
        )
    }

    private fun requiredCount(page: Int): Int =
        if (page <= 1) INITIAL_BATCH
        else INITIAL_BATCH + ((page - 1) * CONTINUE_BATCH)

    private fun homeOffset(page: Int): Int =
        if (page <= 1) 0
        else INITIAL_BATCH + ((page - 2) * CONTINUE_BATCH)

    private fun homeTake(page: Int): Int =
        if (page <= 1) INITIAL_BATCH else CONTINUE_BATCH

    private suspend fun getItemsUpTo(
        source: Source,
        requiredCount: Int,
        excludedKeys: Set<String> = emptySet()
    ): List<SiteItem> {
        if (requiredCount <= 0) return emptyList()

        val result = mutableListOf<SiteItem>()
        var serverPage = 1
        var previousSignature: String? = null

        while (result.size < requiredCount) {
            val pageItems = fetchPage(source, serverPage)

            if (pageItems.isEmpty()) break

            val pageSignature = pageItems
                .joinToString("|") { dedupeKey(it.url) }

            if (pageSignature.isNotBlank() && pageSignature == previousSignature) {
                break
            }

            previousSignature = pageSignature

            result += pageItems.filterNot {
                excludedKeys.contains(contentKey(it)) ||
                    excludedKeys.contains(urlKey(it.url))
            }

            serverPage++
        }

        return collapseByTitle(result)
            .take(requiredCount)
    }

    private suspend fun fetchPage(
        source: Source,
        page: Int,
        keepAllVariants: Boolean = false
    ): List<SiteItem> {
        val url = pagedUrl(source.url, page)
        val cacheKey = if (keepAllVariants) {
            "search-variants::$url"
        } else {
            url
        }

        pageCache[cacheKey]?.let { return it }

        val document = getDocument(url)
            ?: return emptyList()

        val items = parseListing(
            document = document,
            pageUrl = url,
            source = source,
            keepAllVariants = keepAllVariants
        )

        pageCache.putIfAbsent(cacheKey, items)
        return pageCache[cacheKey] ?: items
    }

    private suspend fun getDocument(url: String): Document? {
        val normalized = url.trim()
        if (normalized.isBlank()) return null

        val candidates = linkedSetOf<String>()
        candidates += normalized

        /*
         * Discovery's listing/detail HTML is HTTPS in the supplied
         * website URLs, but keep HTTP fallback for deployments where
         * redirects are configured differently.
         */
        if (normalized.startsWith("https://", true)) {
            candidates += "http://" + normalized.removePrefix("https://")
        } else if (normalized.startsWith("http://", true)) {
            candidates += "https://" + normalized.removePrefix("http://")
        }

        for (candidate in candidates) {
            val result = runCatching {
                app.get(
                    candidate,
                    headers = pageHeaders(candidate)
                ).document
            }.getOrNull()

            if (result != null) return result
        }

        return null
    }

    private fun parseListing(
        document: Document,
        pageUrl: String,
        source: Source,
        keepAllVariants: Boolean = false
    ): List<SiteItem> {
        val result = mutableListOf<SiteItem>()
        val seenContainers = HashSet<String>()

        /*
         * Discovery movie cards can contain several quality links inside one
         * .quality_stack. Those links represent the SAME movie, not separate
         * home cards. We therefore parse the card once and keep the highest
         * quality variant as the playable URL.
         */
        val rootFeed = isRootListingPage(pageUrl)

        /*
         * Root /s uses .row -> .fgrid -> .fcard for the real latest-upload
         * feed. Root /m uses normal .card containers with .quality_stack for
         * the real latest-upload feed. Featured content lives in the Owl
         * carousel and is deliberately not selected here.
         */
        val containers: List<Element> = when {
            rootFeed &&
                (source.kind == SourceKind.SERIES ||
                    source.kind == SourceKind.ANIME_SERIES) -> {
                document.select("div.row .fgrid .fcard")
            }

            rootFeed && source.kind == SourceKind.MOVIE -> {
                document.select("div.card")
                    .filter { card ->
                        card.selectFirst(".quality_stack") != null
                    }
            }

            else -> {
                document.select("div.card, div.fcard")
            }
        }

        containers.forEachIndexed { index, card ->
            val cardIdentity = card.outerHtml().hashCode().toString()
            if (!seenContainers.add(cardIdentity)) return@forEachIndexed

            val viewAnchors = listingViewAnchors(card)

            if (viewAnchors.isEmpty()) return@forEachIndexed

            val isMainTvGrid =
                rootFeed &&
                    (source.kind == SourceKind.SERIES ||
                        source.kind == SourceKind.ANIME_SERIES) &&
                    card.selectFirst(".fdetails") != null

            val title = if (isMainTvGrid) {
                cleanTitle(
                    firstNonBlank(
                        card.selectFirst(".fdetails")?.ownText(),
                        card.selectFirst(".fdetails")?.text(),
                        card.selectFirst(".details h3")?.text(),
                        card.selectFirst("h3")?.text()
                    )
                )
            } else {
                cleanTitle(
                    firstNonBlank(
                        card.selectFirst(".details h3")?.text(),
                        card.selectFirst(".ftitle")?.text(),
                        card.selectFirst("h3")?.text(),
                        card.selectFirst("h4")?.ownText()
                    )
                )
            }

            if (title.isBlank()) return@forEachIndexed

            val poster = extractPoster(card, pageUrl)

            val variants = viewAnchors.mapNotNull { anchor ->
                val absolute = absoluteUrl(
                    anchor.attr("href").trim(),
                    pageUrl
                )

                if (!absolute.contains("/m/view/") &&
                    !absolute.contains("/s/view/")
                ) {
                    return@mapNotNull null
                }

                val label = qualityLabelOrInfer(
                    firstNonBlank(
                        anchor.selectFirst(".movie_details_span_end")?.text(),
                        if (anchor.hasClass("movie_details_span_end")) anchor.text() else null,
                        anchor.attr("title"),
                        card.selectFirst(".quality_stack .movie_details_span_end")?.text(),
                        card.selectFirst(".ftitle span")?.text(),
                        card.selectFirst(".poster[title]")?.attr("title")
                    ),
                    absolute
                )

                SiteVariant(
                    url = absolute,
                    qualityLabel = label,
                    qualityRank = qualityRank(label, absolute)
                )
            }

            val type = when (source.kind) {
                SourceKind.SERIES,
                SourceKind.ANIME_SERIES -> TvType.TvSeries

                SourceKind.ANIME_MOVIE -> TvType.Anime

                SourceKind.MOVIE -> TvType.Movie
            }

            val year = card
                .selectFirst(".details .feedback span[title='views']")
                ?.text()
                ?.trim()
                ?.toIntOrNull()

            if (keepAllVariants) {
                variants.forEach { variant ->
                    result += SiteItem(
                        title = title,
                        url = variant.url,
                        poster = poster,
                        type = type,
                        source = source.url,
                        order = index,
                        year = year,
                        qualityLabel = variant.qualityLabel,
                        qualityRank = variant.qualityRank
                    )
                }
            } else {
                val bestVariant = variants
                    .maxWithOrNull(
                        compareBy<SiteVariant> { variantSelectionPriority(it) }
                            .thenBy { it.url }
                    )
                    ?: return@forEachIndexed

                result += SiteItem(
                    title = title,
                    url = bestVariant.url,
                    poster = poster,
                    type = type,
                    source = source.url,
                    order = index,
                    year = year,
                    qualityLabel = bestVariant.qualityLabel,
                    qualityRank = bestVariant.qualityRank
                )
            }
        }

        /*
         * Fallback for unusual cards where no .card/.fcard wrapper exists.
         */
        if (result.isEmpty()) {
            document
                .select("a[href*='/m/view/'], a[href*='/s/view/']")
                .filter { anchor ->
                    if (!rootFeed) {
                        true
                    } else {
                        val card = findCard(anchor)
                        when {
                            source.kind == SourceKind.SERIES ||
                                source.kind == SourceKind.ANIME_SERIES -> {
                                card?.parents()?.any {
                                    it.hasClass("fgrid")
                                } == true
                            }

                            source.kind == SourceKind.MOVIE -> {
                                card?.selectFirst(".quality_stack") != null
                            }

                            else -> false
                        }
                    }
                }
                .forEachIndexed { index, anchor ->
                    val absolute = absoluteUrl(anchor.attr("href").trim(), pageUrl)
                    if (!absolute.contains("/m/view/") && !absolute.contains("/s/view/")) {
                        return@forEachIndexed
                    }

                    val card = findCard(anchor)
                    val title = cleanTitle(
                        firstNonBlank(
                            card?.selectFirst(".fdetails")?.ownText(),
                            card?.selectFirst(".fdetails")?.text(),
                            card?.selectFirst(".details h3")?.text(),
                            card?.selectFirst(".ftitle")?.text(),
                            anchor.attr("title"),
                            titleFromUrl(absolute)
                        )
                    )

                    if (title.isBlank()) return@forEachIndexed

                    val label = qualityLabelOrInfer(
                        firstNonBlank(
                            anchor.selectFirst(".movie_details_span_end")?.text(),
                            if (anchor.hasClass("movie_details_span_end")) anchor.text() else null,
                            anchor.attr("title"),
                            card?.selectFirst(".poster[title]")?.attr("title")
                        ),
                        absolute
                    )

                    val type = when (source.kind) {
                        SourceKind.SERIES, SourceKind.ANIME_SERIES -> TvType.TvSeries
                        SourceKind.ANIME_MOVIE -> TvType.Anime
                        SourceKind.MOVIE -> TvType.Movie
                    }

                    result += SiteItem(
                        title = title,
                        url = absolute,
                        poster = card?.let { extractPoster(it, pageUrl) }
                            ?: extractPoster(anchor, pageUrl),
                        type = type,
                        source = source.url,
                        order = index,
                        qualityLabel = label,
                        qualityRank = qualityRank(label, absolute)
                    )
                }
        }

        return if (keepAllVariants) {
            result
        } else {
            collapseByTitle(result)
        }
    }

    private data class SiteVariant(
        val url: String,
        val qualityLabel: String,
        val qualityRank: Int
    )

    private fun isRootListingPage(url: String): Boolean {
        val path = runCatching {
            URI(url.trim()).path.orEmpty()
        }.getOrDefault("")

        val normalized = path
            .trimEnd('/')
            .lowercase(Locale.ROOT)

        if (normalized == "/m" || normalized == "/s") {
            return true
        }

        val parts = normalized
            .split('/')
            .filter { it.isNotBlank() }

        return parts.size == 2 &&
            (parts[0] == "m" || parts[0] == "s") &&
            parts[1].toIntOrNull() != null
    }

    private fun listingViewAnchors(card: Element): List<Element> {
        val anchors = LinkedHashSet<Element>()

        card.select(
            "a[href*='/m/view/'], a[href*='/s/view/']"
        ).forEach { anchors.add(it) }

        val parent = card.parent()
        if (parent?.tagName()?.equals("a", true) == true) {
            val href = parent.attr("href").trim()
            if (href.contains("/m/view/") || href.contains("/s/view/")) {
                anchors.add(parent)
            }
        }

        return anchors.toList()
    }

    private fun findCard(anchor: Element): Element? {
        var current: Element? = anchor

        repeat(10) {
            val element = current ?: return@repeat
            val className = element.className().lowercase(Locale.ROOT)

            if (className.contains("card") || className.contains("fcard")) {
                return element
            }

            if (element.selectFirst(".details h3") != null ||
                element.selectFirst(".ftitle") != null
            ) {
                return element
            }

            current = element.parent()
        }

        return anchor.parent()
    }

    private fun extractPoster(
        element: Element,
        baseUrl: String
    ): String? {
        val image = element.selectFirst("img[src]")
            ?: element.selectFirst("img[data-src]")
            ?: return null

        val raw = image.attr("src")
            .ifBlank { image.attr("data-src") }
            .trim()

        if (raw.isBlank()) return null

        return absoluteUrl(raw, baseUrl)
    }

    override suspend fun search(
        query: String,
        page: Int
    ): SearchResponseList {
        val rawQuery = query.trim()
        if (rawQuery.isBlank()) {
            return newSearchResponseList(
                emptyList(),
                false
            )
        }

        val q = normalizeSearchQuery(rawQuery)
        if (q.isBlank()) {
            return newSearchResponseList(emptyList(), false)
        }

        val pageNumber = page.coerceAtLeast(1)

        /*
         * The supplied site source exposes the search input, but the
         * backend search endpoint itself is not part of the provided source.
         * We therefore search the known listing sources locally.
         *
         * Season tokens such as "S1", "S01", "Season 1" are removed from
         * the query so "Mirzapur", "Mirzapur S1" and "Mirzapur Season 3"
         * can all resolve to the same base series. The load response then
         * exposes all available seasons.
         */
        val qualityQuery = Regex(
            "(?i)\\b(?:4k|2160p|1440p|1080p|720p|480p|360p|hd|web[- ]?dl|webdl|dual|cam[- ]?rip)\\b"
        ).containsMatchIn(rawQuery)

        val allMatches = mutableListOf<SiteItem>()

        for (source in SEARCH_SOURCES) {
            for (serverPage in 1..SEARCH_MAX_PAGES) {
                val items = fetchPage(
                    source,
                    serverPage,
                    keepAllVariants = qualityQuery
                )

                allMatches += items.filter {
                    val titleKey = normalizeTitleKey(it.title)
                    val qualityKey = normalizeTitleKey(it.qualityLabel)
                    val searchable = "$titleKey $qualityKey"

                    searchable.contains(q) ||
                        q.split(Regex("\\s+"))
                            .filter { token -> token.isNotBlank() }
                            .all { token -> searchable.contains(token) }
                }

                if (items.isEmpty()) break
            }
        }

        val rankedBase = if (qualityQuery) {
            allMatches.distinctBy {
                it.url.lowercase(Locale.ROOT)
            }
        } else {
            collapseByTitle(allMatches)
        }

        val ranked = rankedBase
            .sortedWith(
                compareByDescending<SiteItem> {
                    searchScore(
                        q,
                        normalizeTitleKey(
                            "${it.title} ${it.qualityLabel}"
                        )
                    )
                }.thenBy {
                    it.order
                }
            )

        val pageSize = 24
        val offset = (pageNumber - 1) * pageSize
        val pageItems = ranked
            .drop(offset)
            .take(pageSize)

        return newSearchResponseList(
            pageItems.map { it.toSearchResponse() },
            offset + pageSize < ranked.size
        )
    }

    override suspend fun load(
        url: String
    ): LoadResponse {
        val input = url.trim()

        if (isMediaUrl(input)) {
            return newMovieLoadResponse(
                titleFromUrl(input),
                input,
                if (input.contains("/s/", true)) TvType.TvSeries else TvType.Movie,
                input
            )
        }

        val document = getDocument(input)
            ?: return newMovieLoadResponse(
                titleFromUrl(input),
                input,
                if (input.contains("/s/view/", true)) TvType.TvSeries else TvType.Movie,
                input
            )

        val title = firstNonBlank(
            document.selectFirst(".movie-detail-content-test h3")?.text(),
            document.selectFirst(".movie-detail-content h3")?.text(),
            document.selectFirst("h1")?.text(),
            document.selectFirst("title")?.text(),
            titleFromUrl(input)
        ).trim()

        val poster = extractDetailPoster(
            document,
            input
        )

        val plot = extractDetailPlot(document)

        val isSeries = input.contains("/s/view/", true) ||
            input.contains("/s/category/", true)

        if (isSeries) {
            val seasonLinks = discoverAllSeasonLinks(
                document = document,
                inputUrl = input
            )

            val episodes = if (seasonLinks.isNotEmpty()) {
                coroutineScope {
                    seasonLinks.map { seasonLink ->
                        async {
                            val seasonDocument =
                                getDocument(seasonLink.url)

                            parseEpisodes(
                                document = seasonDocument,
                                seasonUrl = seasonLink.url,
                                defaultSeason = seasonLink.season,
                                fallbackPoster = poster
                            )
                        }
                    }.awaitAll().flatten()
                }
            } else {
                parseEpisodes(
                    document = document,
                    seasonUrl = input,
                    defaultSeason = 1,
                    fallbackPoster = poster
                )
            }

            val distinctEpisodes = episodes
                .distinctBy { episodeKey(it) }
                .sortedWith(
                    compareByDescending<Episode> { it.season ?: 1 }
                        .thenBy { it.episode ?: Int.MAX_VALUE }
                )

            return newTvSeriesLoadResponse(
                title,
                input,
                TvType.TvSeries,
                distinctEpisodes
            ) {
                posterUrl = poster
                this.plot = plot
                seasonNames = seasonLinks
                    .map { it.season }
                    .distinct()
                    .sortedDescending()
                    .map { season ->
                        SeasonData(
                            season = season,
                            name = null,
                            displaySeason = season
                        )
                    }
            }
        }

        val anime = input.contains("/dual/Animation", true) ||
            input.contains("/category/Animation", true)

        val directMovieMedia =
            extractDirectDownloadMedia(
                document = document,
                baseUrl = input
            ).firstOrNull()
                ?: extractAnyDirectMedia(
                    document = document,
                    baseUrl = input
                ).firstOrNull()

        return newMovieLoadResponse(
            title,
            input,
            if (anime) TvType.Anime else TvType.Movie,
            buildMoviePlaybackData(
                mediaUrl = directMovieMedia ?: input,
                detailUrl = input
            )
        ) {
            posterUrl = poster
            this.plot = plot
        }
    }

    private data class SeasonLink(
        val url: String,
        val season: Int
    )

    private fun seriesBaseUrl(url: String): String {
        val match = Regex(
            """(?i)(https?://[^/]+/s/view/\d+)"""
        ).find(url.trim())

        return match?.groupValues?.getOrNull(1)
            ?: url.trim().removeSuffix("/")
    }

    private fun seasonVariantUrl(
        baseUrl: String,
        season: Int
    ): String {
        return baseUrl.trimEnd('/') + "/" +
            season.toString().padStart(2, '0')
    }