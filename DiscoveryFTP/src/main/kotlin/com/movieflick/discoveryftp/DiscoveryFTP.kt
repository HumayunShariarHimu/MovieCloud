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
    private suspend fun discoverAllSeasonLinks(
        document: Document,
        inputUrl: String
    ): List<SeasonLink> {
        val baseUrl = seriesBaseUrl(inputUrl)
        val discovered = LinkedHashMap<Int, SeasonLink>()

        extractSeasonLinks(
            document = document,
            baseUrl = inputUrl
        ).forEach { link ->
            discovered[link.season] = link
        }

        /*
         * If the server presents a season-specific URL without the complete
         * season switcher, probe a bounded set of conventional season URLs.
         * This keeps the system dynamic without hardcoding any specific show.
         */
        if (discovered.size <= 1 && inputUrl.contains("/s/view/", true)) {
            coroutineScope {
                (1..12)
                    .filterNot { discovered.containsKey(it) }
                    .map { season ->
                        async {
                            val candidate = seasonVariantUrl(baseUrl, season)
                            val seasonDocument = getDocument(candidate)
                            Triple(season, candidate, seasonDocument)
                        }
                    }
                    .awaitAll()
                    .forEach { (season, candidate, seasonDocument) ->
                        if (seasonDocument != null &&
                            seasonDocument.select(
                                "a[href*='.mkv'], a[href*='.mp4'], " +
                                    "a[href*='.m3u8'], video source[src]"
                            ).isNotEmpty()
                        ) {
                            discovered.putIfAbsent(
                                season,
                                SeasonLink(candidate, season)
                            )
                        }
                    }
            }
        }

        return discovered.values
            .sortedByDescending { it.season }
    }

    private fun extractSeasonLinks(
        document: Document,
        baseUrl: String
    ): List<SeasonLink> {
        val result = linkedMapOf<String, SeasonLink>()

        document.select("a[href*='/s/view/']").forEach { anchor ->
            val href = absoluteUrl(
                anchor.attr("href").trim(),
                baseUrl
            )

            val season = Regex(
                "(?i)(?:/|season\\s*)0*(\\d+)\\s*$"
            ).find(href)?.groupValues?.getOrNull(1)?.toIntOrNull()
                ?: Regex("(?i)\\bseason\\s*0*(\\d+)\\b")
                    .find(anchor.text())
                    ?.groupValues
                    ?.getOrNull(1)
                    ?.toIntOrNull()

            if (season != null) {
                result.putIfAbsent(
                    "$href#$season",
                    SeasonLink(href, season)
                )
            }
        }

        return result.values
            .sortedByDescending { it.season }
            .toList()
    }

    private fun parseEpisodes(
        document: Document?,
        seasonUrl: String,
        defaultSeason: Int,
        fallbackPoster: String?
    ): List<Episode> {
        if (document == null) return emptyList()

        val episodes = mutableListOf<Episode>()

        /*
         * Discovery's supplied TV season source places the actual
         * downloadable/playable media URL in the episode card:
         *
         * h5 -> "S1 | EP 1"
         * a[href="...mkv"] -> media
         * h4 -> episode title
         */
        val mediaAnchors = document.select(
            "a[href]"
        ).filter { anchor ->
            val href = anchor.attr("href").trim()
            isMediaUrl(href)
        }

        mediaAnchors.forEachIndexed { index, anchor ->
            val mediaUrl = absoluteUrl(
                anchor.attr("href").trim(),
                seasonUrl
            )

            val card = findEpisodeCard(anchor)

            val headerText = cleanTitle(
                card?.selectFirst("h5")?.text()
                    ?: anchor.parent()?.selectFirst("h5")?.text()
                    ?: ""
            )

            val episodeTitle = cleanTitle(
                firstNonBlank(
                    card?.selectFirst("h4")?.ownText(),
                    card?.selectFirst("h4")?.text(),
                    cleanEpisodeTitle(headerText),
                    "Episode ${index + 1}"
                )
            )

            val season = parseSeasonNumber(
                headerText,
                mediaUrl,
                defaultSeason
            )

            val episodeNumber = parseEpisodeNumber(
                headerText,
                mediaUrl,
                index + 1
            )

            val episodeDisplayName = if (episodeTitle.isBlank()) {
                "S$season | EP $episodeNumber"
            } else {
                "$episodeTitle [S$season | EP $episodeNumber]"
            }

            episodes += newEpisode(
                buildDirectEpisodePlaybackData(
                    mediaUrl = mediaUrl,
                    referer = seasonUrl
                )
            ) {
                name = episodeDisplayName
                this.season = season
                this.episode = episodeNumber
                posterUrl = fallbackPoster
                description = card?.selectFirst(".season_overview p")?.text()?.trim()
            }
        }

        /*
         * Fallback for pages where the episode media is published via
         * a normal episode page instead of directly in the href.
         */
        if (episodes.isEmpty()) {
            val episodeAnchors = document.select(
                "a[href*='/s/view/']"
            )

            episodeAnchors.forEachIndexed { index, anchor ->
                val href = anchor.attr("href").trim()
                if (href.isBlank()) return@forEachIndexed

                val absolute = absoluteUrl(
                    href,
                    seasonUrl
                )

                val text = cleanTitle(
                    firstNonBlank(
                        anchor.attr("title"),
                        anchor.text(),
                        "Episode ${index + 1}"
                    )
                )

                episodes += newEpisode(
                    buildEpisodePlaybackData(
                        mediaUrl = absolute,
                        referer = seasonUrl
                    )
                ) {
                    name = text
                    season = parseSeasonNumber(
                        text,
                        absolute,
                        defaultSeason
                    )
                    episode = parseEpisodeNumber(
                        text,
                        absolute,
                        index + 1
                    )
                    posterUrl = fallbackPoster
                }
            }
        }

        return episodes.sortedWith(
            compareBy<Episode> { it.season ?: defaultSeason }
                .thenBy { it.episode ?: Int.MAX_VALUE }
        )
    }

    private fun findEpisodeCard(anchor: Element): Element? {
        var current: Element? = anchor
        var fallback: Element? = null

        repeat(12) {
            val element = current ?: return@repeat
            val className = element.className().lowercase(Locale.ROOT)

            // Discovery puts each episode inside a .card container. Prefer
            // that full card because it contains both the episode code (h5)
            // and the actual episode title (h4).
            if (className.contains("card") || className.contains("fcard")) {
                return element
            }

            if (element.selectFirst("h4") != null) {
                fallback = element
            }

            current = element.parent()
        }

        return fallback ?: anchor.parent()
    }

    private fun episodeDetailUrl(
        card: Element?,
        baseUrl: String
    ): String? {
        val clickable = card?.selectFirst("[onClick*='view(']")
            ?: return null

        val onclick = clickable.attr("onClick").trim()

        val id = Regex(
            """(?i)view\(\s*['"]([0-9]+)['"]\s*\)"""
        )
            .find(onclick)
            ?.groupValues
            ?.getOrNull(1)
            ?: return null

        return absoluteUrl(
            "/s/view/$id",
            baseUrl
        )
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val request = parseEpisodePlaybackData(data)
        val input = normalizeMediaUrl(request.mediaUrl)

        if (input.isBlank()) return false

        /*
         * IMPORTANT FAST PATH:
         * If the episode/movie data is already the real CDN media URL, do not
         * fetch another webpage first. Discovery's TV source explicitly
         * publishes the exact .mkv in the episode/download anchor, and the
         * supplied BasPlay implementation follows this same architecture:
         * direct media -> ExtractorLink -> player.
         *
         * Discovery publishes actual video files (.mkv/.mp4/etc.) in the
         * episode/movie anchors. Once such a URL is present, it is already the
         * playable source and must not be filtered through a page-only or
         * "download-only" classifier.
         *
         * This removes the failing page-resolution step that was causing some
         * episodes to end in "No Links Found".
         */
        if (isMediaUrl(input)) {
            emitMedia(
                mediaUrl = input,
                callback = callback,
                referer = request.referer.ifBlank { "$mainUrl/" }
            )
            return true
        }

        val session = DiscoverySession()

        /*
         * Establish the same lightweight web session used by the browser.
         * Start with the site root, then revisit the exact originating/detail
         * page before sending the final CDN request.
         */
        val bootstrapPages = linkedSetOf<String>().apply {
            add("$mainUrl/")

            request.referer
                .takeIf { it.startsWith(mainUrl, true) }
                ?.let(::add)

            request.detailUrl
                ?.takeIf { it.startsWith(mainUrl, true) }
                ?.let(::add)

            if (!isMediaUrl(input) && input.startsWith(mainUrl, true)) {
                add(input)
            }
        }

        for (pageUrl in bootstrapPages) {
            runCatching {
                fetchWithDiscoverySession(
                    url = pageUrl,
                    referer = "$mainUrl/",
                    session = session
                )
            }
        }

        /*
         * Non-media playback data points to a Discovery page. Fetch that page
         * with the same session so any refreshed MOVIESID is retained.
         */
        val pageResult = fetchWithDiscoverySession(
            url = input,
            referer = request.referer.ifBlank { "$mainUrl/" },
            session = session
        ) ?: return false

        val document = pageResult.first

        /*
         * Discovery detail pages expose the SAME playable file from several
         * UI entry points:
         *
         *   Stream      -> /m/playlist/<id>
         *   WEB Play    -> /m/play/<id>
         *   Download   -> direct CDN .mkv/.mp4
         *
         * We resolve the direct file first. The important point is that the
         * anchor's HREF/title/data attributes are all inspected, not only
         * the visible button text.
         */

        val directCandidates = linkedSetOf<String>()
        directCandidates += extractDirectDownloadMedia(
            document = document,
            baseUrl = input
        )
        directCandidates += extractAnyDirectMedia(
            document = document,
            baseUrl = input
        )
        directCandidates += extractMediaFromHtml(
            html = document.html(),
            baseUrl = input
        )

        pickBestMedia(directCandidates.toList())?.let { media ->
            emitMedia(
                mediaUrl = media,
                callback = callback,
                referer = "$mainUrl/",
                cookieHeader = session.headerValue()
            )
            return true
        }

        /*
         * If the detail HTML only contains the three UI links, resolve both
         * the WEB Play and Stream pages. They are cheap HTML requests and are
         * tried concurrently, then the first valid direct media URL wins.
         */
        val linkedPages = document.select("a[href]")
            .mapNotNull { anchor ->
                val href = anchor.attr("href").trim()
                if (href.isBlank()) return@mapNotNull null

                val text = anchor.text().trim().lowercase(Locale.ROOT)
                val title = anchor.attr("title").trim().lowercase(Locale.ROOT)

                val isPlaybackPage =
                    href.contains("/m/play/", true) ||
                        href.contains("/s/play/", true) ||
                        href.contains("/m/playlist/", true) ||
                        href.contains("/s/playlist/", true) ||
                        href.contains("/m/stream/", true) ||
                        href.contains("/s/stream/", true) ||
                        text.contains("web play") ||
                        text == "stream" ||
                        title.contains("web play") ||
                        title.contains("stream")

                if (isPlaybackPage) {
                    absoluteUrl(href, input)
                } else {
                    null
                }
            }
            .distinct()

        val pageMedia = coroutineScope {
            linkedPages.map { pageUrl ->
                async {
                    val pageDocument = fetchWithDiscoverySession(
                        url = pageUrl,
                        referer = "$mainUrl/",
                        session = session
                    )?.first

                    if (pageDocument == null) {
                        emptyList()
                    } else {
                        val candidates = linkedSetOf<String>()
                        candidates += extractDirectDownloadMedia(
                            document = pageDocument,
                            baseUrl = pageUrl
                        )
                        candidates += extractAnyDirectMedia(
                            document = pageDocument,
                            baseUrl = pageUrl
                        )
                        candidates += extractMediaFromHtml(
                            html = pageDocument.html(),
                            baseUrl = pageUrl
                        )

                        candidates.toList()
                    }
                }
            }.awaitAll().flatten()
        }

        pickBestMedia(pageMedia)?.let { media ->
            emitMedia(
                mediaUrl = media,
                callback = callback,
                referer = "$mainUrl/",
                cookieHeader = session.headerValue()
            )
            return true
        }

        /*
         * Final detail-page fallback:
         * episode cards sometimes carry an onclick/view(...) target instead of
         * the media URL itself.
         */
        request.detailUrl?.let { detailUrl ->
            val detailDocument = fetchWithDiscoverySession(
                url = detailUrl,
                referer = "$mainUrl/",
                session = session
            )?.first

            if (detailDocument != null) {
                val candidates = linkedSetOf<String>()
                candidates += extractDirectDownloadMedia(
                    document = detailDocument,
                    baseUrl = detailUrl
                )
                candidates += extractAnyDirectMedia(
                    document = detailDocument,
                    baseUrl = detailUrl
                )
                candidates += extractMediaFromHtml(
                    html = detailDocument.html(),
                    baseUrl = detailUrl
                )

                pickBestMedia(candidates.toList())?.let { media ->
                    emitMedia(
                        mediaUrl = media,
                        callback = callback,
                        referer = "$mainUrl/",
                        cookieHeader = session.headerValue()
                    )
                    return true
                }

                /*
                 * If the detail page contains WEB Play / Stream links, resolve
                 * those too.
                 */
                val nestedPages = detailDocument.select("a[href]")
                    .mapNotNull { anchor ->
                        val href = anchor.attr("href").trim()
                        if (href.isBlank()) return@mapNotNull null

                        val text = anchor.text().trim().lowercase(Locale.ROOT)
                        val target = absoluteUrl(href, detailUrl)

                        if (
                            target.contains("/play/", true) ||
                            target.contains("/playlist/", true) ||
                            text.contains("stream") ||
                            text.contains("web play")
                        ) {
                            target
                        } else {
                            null
                        }
                    }
                    .distinct()

                val nestedMedia = resolveMediaFromPages(
                    pageUrls = nestedPages,
                    maxPages = 8,
                    pageReferer = "$mainUrl/",
                    session = session
                )

                pickBestMedia(nestedMedia)?.let { media ->
                    emitMedia(
                        mediaUrl = media,
                        callback = callback,
                        referer = "$mainUrl/",
                        cookieHeader = session.headerValue()
                    )
                    return true
                }
            }
        }

        /*
         * Some player pages use an iframe. Resolve its HTML once before
         * giving up.
         */
        val frameUrls = document.select("iframe[src], frame[src]")
            .mapNotNull { frame ->
                val raw = frame.attr("src").trim()
                raw.takeIf { it.isNotBlank() }?.let {
                    absoluteUrl(it, input)
                }
            }
            .distinct()

        for (frameUrl in frameUrls.take(8)) {
            val frameDocument = fetchWithDiscoverySession(
                url = frameUrl,
                referer = input,
                session = session
            )?.first ?: continue

            val candidates = linkedSetOf<String>()
            candidates += extractAnyDirectMedia(
                document = frameDocument,
                baseUrl = frameUrl
            )
            candidates += extractMediaFromHtml(
                html = frameDocument.html(),
                baseUrl = frameUrl
            )

            pickBestMedia(candidates.toList())?.let { media ->
                emitMedia(
                    mediaUrl = media,
                    callback = callback,
                    referer = "$mainUrl/",
                    cookieHeader = session.headerValue()
                )
                return true
            }
        }

        return false
    }

    private suspend fun resolveMediaFromPages(
        pageUrls: List<String>,
        maxPages: Int,
        pageReferer: String,
        session: DiscoverySession? = null
    ): List<String> {
        if (pageUrls.isEmpty()) return emptyList()

        for (pageUrl in pageUrls.take(maxPages)) {
            val pageDocument = if (session != null) {
                fetchWithDiscoverySession(
                    url = pageUrl,
                    referer = pageReferer,
                    session = session
                )?.first
            } else {
                getDocumentWithReferer(
                    url = pageUrl,
                    referer = pageReferer
                )
            } ?: continue

            val direct = extractAnyDirectMedia(
                document = pageDocument,
                baseUrl = pageUrl
            )

            if (direct.isNotEmpty()) {
                return direct
            }

            /* iframe/player-page fallback */
            val frames = pageDocument
                .select("iframe[src], frame[src]")
                .mapNotNull { frame ->
                    val raw = frame.attr("src").trim()
                    if (raw.isBlank()) null
                    else absoluteUrl(raw, pageUrl)
                }
                .distinct()

            for (frameUrl in frames.take(4)) {
                val frameDocument = getDocumentWithReferer(
                    url = frameUrl,
                    referer = pageUrl
                ) ?: continue

                val frameMedia = extractAnyDirectMedia(
                    document = frameDocument,
                    baseUrl = frameUrl
                )

                if (frameMedia.isNotEmpty()) {
                    return frameMedia
                }

                val frameScriptMedia = extractMediaFromHtml(
                    html = frameDocument.html(),
                    baseUrl = frameUrl
                )

                if (frameScriptMedia.isNotEmpty()) {
                    return frameScriptMedia
                }
            }

            val scriptMedia = extractMediaFromHtml(
                html = pageDocument.html(),
                baseUrl = pageUrl
            )

            if (scriptMedia.isNotEmpty()) {
                return scriptMedia
            }
        }

        return emptyList()
    }

    private suspend fun fetchWithDiscoverySession(
        url: String,
        referer: String,
        session: DiscoverySession
    ): Pair<Document, String?>? {
        val normalized = url.trim()
        if (normalized.isBlank()) return null

        val candidates = linkedSetOf<String>()
        candidates += normalized

        if (normalized.startsWith("http://", true)) {
            candidates += "https://" +
                normalized.removePrefix("http://")
        } else if (normalized.startsWith("https://", true)) {
            candidates += "http://" +
                normalized.removePrefix("https://")
        }

        for (candidate in candidates) {
            val headers = linkedMapOf<String, String>().apply {
                putAll(pageHeaders(referer))

                /*
                 * Carry the valid name=value cookies captured from the
                 * previous Discovery response into the next request.
                 */
                session.headerValue()?.let { cookie ->
                    put("Cookie", cookie)
                }
            }

            val response = runCatching {
                app.get(
                    candidate,
                    headers = headers
                )
            }.getOrNull() ?: continue

            session.capture(
                response.headers.values("Set-Cookie")
            )

            return response.document to session.headerValue()
        }

        return null
    }

    private suspend fun getDocumentWithReferer(
        url: String,
        referer: String
    ): Document? {
        val normalized = url.trim()
        if (normalized.isBlank()) return null

        val candidates = linkedSetOf<String>()
        candidates += normalized

        if (normalized.startsWith("http://", true)) {
            candidates += "https://" +
                normalized.removePrefix("http://")
        } else if (normalized.startsWith("https://", true)) {
            candidates += "http://" +
                normalized.removePrefix("https://")
        }

        for (candidate in candidates) {
            val result = runCatching {
                app.get(
                    candidate,
                    headers = pageHeaders(referer)
                ).document
            }.getOrNull()

            if (result != null) return result
        }

        return null
    }

    private fun extractDirectDownloadMedia(
        document: Document,
        baseUrl: String
    ): List<String> {
        val result = LinkedHashSet<String>()

        document.select("a[href], [data-href], [data-url]").forEach { anchor ->
            val rawHref = firstNonBlank(
                anchor.attr("href").trim(),
                anchor.attr("data-href").trim(),
                anchor.attr("data-url").trim()
            )

            val title = anchor.attr("title").trim()
            val text = anchor.text().trim()
            val classes = anchor.classNames()
                .joinToString(" ")

            val onclick = anchor.attr("onclick").trim()

            /*
             * A file may be hidden behind an icon-only download button, so
             * look at all identifying attributes. Direct .mkv/.mp4 hrefs are
             * accepted even when the button text is empty.
             */
            val looksLikeDownload =
                text.contains("download", true) ||
                    title.contains("download", true) ||
                    classes.contains("download", true) ||
                    anchor.selectFirst(
                        "ion-icon[name*='download'], i[class*='download']"
                    ) != null ||
                    isMediaUrl(rawHref) ||
                    onclick.contains(".mkv", true) ||
                    onclick.contains(".mp4", true) ||
                    onclick.contains(".m3u8", true)

            if (!looksLikeDownload) return@forEach

            val candidates = linkedSetOf<String>()
            if (rawHref.isNotBlank()) {
                candidates += absoluteUrl(rawHref, baseUrl)
            }

            candidates += extractMediaFromHtml(
                html = "$title $onclick",
                baseUrl = baseUrl
            )

            candidates.forEach { candidate ->
                val normalized = normalizeMediaUrl(candidate)
                if (isMediaUrl(normalized)) {
                    result += normalized
                }
            }
        }

        return result.toList()
    }

    private fun extractAnyDirectMedia(
        document: Document,
        baseUrl: String
    ): List<String> {
        val result = LinkedHashSet<String>()

        /*
         * Anchors and media-like attributes.
         */
        document.select(
            "a[href], [src], [data-src], [data-video], [data-file], " +
                "[data-default-src], [data-video-src], " +
                "[data-playback-url], [data-url], [href]"
        ).forEach { element ->
            val rawValues = listOf(
                element.attr("href"),
                element.attr("src"),
                element.attr("data-src"),
                element.attr("data-video"),
                element.attr("data-file"),
                element.attr("data-default-src"),
                element.attr("data-video-src"),
                element.attr("data-playback-url"),
                element.attr("data-url"),
                element.attr("title"),
                element.attr("onclick")
            )

            rawValues.forEach { raw ->
                if (raw.isBlank()) return@forEach

                val absolute = normalizeMediaUrl(
                    absoluteUrl(raw.trim(), baseUrl)
                )

                if (isMediaUrl(absolute)) {
                    result += absolute
                }

                /*
                 * onclick/title can contain a quoted absolute media URL rather
                 * than the whole attribute itself being a URL.
                 */
                extractMediaFromHtml(
                    html = raw,
                    baseUrl = baseUrl
                ).forEach { result += it }
            }
        }

        /*
         * Native HTML5 player.
         */
        document.select(
            "video[src], video source[src], source[src]"
        ).forEach { element ->
            val raw = element.attr("src").trim()
            if (raw.isBlank()) return@forEach

            val absolute = normalizeMediaUrl(
                absoluteUrl(raw, baseUrl)
            )

            if (isMediaUrl(absolute)) {
                result += absolute
            }
        }

        return result.toList()
    }

    private fun extractMediaFromHtml(
        html: String,
        baseUrl: String
    ): List<String> {
        val result = LinkedHashSet<String>()

        val cleaned = html
            .replace("\\/", "/")
            .replace("&amp;", "&")
            .replace("\\u0026", "&")
            .replace("\\u003D", "=")

        /* Absolute media URLs. */
        val absoluteRegex = Regex(
            """(?i)https?://[^\"'<>\s]+(?:${mediaExtensions.joinToString("|") { Regex.escape(it) }})(?:\?[^\"'<>\s]*)?"""
        )

        absoluteRegex.findAll(cleaned).forEach { match ->
            val media = normalizeMediaUrl(
                cleanUrl(match.value)
            )

            if (isMediaUrl(media)) {
                result += media
            }
        }

        /* Protocol-relative and relative media paths. */
        val relativeRegex = Regex(
            """(?i)(//[^\"'<>\s]+|/[^\"'<>\s]+)(?:${mediaExtensions.joinToString("|") { Regex.escape(it) }})(?:\?[^\"'<>\s]*)?"""
        )

        relativeRegex.findAll(cleaned).forEach { match ->
            val media = normalizeMediaUrl(
                absoluteUrl(
                    match.value,
                    baseUrl
                )
            )

            if (isMediaUrl(media)) {
                result += media
            }
        }

        return result.toList()
    }

    private data class DiscoverySession(
        val cookies: MutableMap<String, String> = linkedMapOf()
    ) {
        fun headerValue(): String? {
            if (cookies.isEmpty()) return null

            return cookies.entries
                .filter { it.key.isNotBlank() && it.value.isNotBlank() }
                .joinToString("; ") {
                    "${it.key}=${it.value}"
                }
                .ifBlank { null }
        }

        fun capture(setCookieHeaders: List<String>) {
            for (raw in setCookieHeaders) {
                val pair = raw.substringBefore(';').trim()
                val separator = pair.indexOf('=')

                if (separator <= 0) continue

                val name = pair.substring(0, separator).trim()
                val value = pair.substring(separator + 1).trim()

                if (name.isNotBlank() && value.isNotBlank()) {
                    cookies[name] = value
                }
            }
        }
    }

    private data class EpisodePlaybackRequest(
        val mediaUrl: String,
        val referer: String,
        val detailUrl: String? = null
    )

    private fun buildMoviePlaybackData(
        mediaUrl: String,
        detailUrl: String
    ): String {
        return buildEpisodePlaybackData(
            mediaUrl = mediaUrl,
            referer = "$mainUrl/",
            detailUrl = detailUrl
        )
    }

    private fun buildDirectEpisodePlaybackData(
        mediaUrl: String,
        referer: String
    ): String {
        /*
         * The Discovery season HTML itself contains the real CDN .mkv link.
         * Keep that URL as the episode's source-of-truth. The ref marker is
         * preserved only so loadLinks can supply the website Referer; no
         * second episode-page lookup is required.
         */
        val encodedReferer = java.net.URLEncoder.encode(
            referer,
            StandardCharsets.UTF_8.toString()
        )

        return normalizeMediaUrl(mediaUrl) +
            "#discovery_ref=$encodedReferer"
    }

    private fun buildEpisodePlaybackData(
        mediaUrl: String,
        referer: String,
        detailUrl: String? = null
    ): String {
        val encodedReferer = java.net.URLEncoder.encode(
            referer,
            StandardCharsets.UTF_8.toString()        )

        val encodedDetail = detailUrl?.takeIf {
            it.isNotBlank()
        }?.let {
            java.net.URLEncoder.encode(
                it,
                StandardCharsets.UTF_8.toString()
            )
        }

        return normalizeMediaUrl(mediaUrl) +
            "#discovery_ref=$encodedReferer" +
            if (!encodedDetail.isNullOrBlank()) {
                "#discovery_detail=$encodedDetail"
            } else {
                ""
            }
    }

    private fun parseEpisodePlaybackData(
        data: String
    ): EpisodePlaybackRequest {
        val raw = data.trim()
        val refMarker = "#discovery_ref="
        val detailMarker = "#discovery_detail="

        if (!raw.contains(refMarker)) {
            return EpisodePlaybackRequest(
                mediaUrl = raw.substringBefore("#").trim(),
                referer = "$mainUrl/"
            )
        }

        val media = raw.substringBefore("#").trim()

        val refStart = raw.indexOf(refMarker) +
            refMarker.length

        val refEnd = raw.indexOf(
            detailMarker,
            startIndex = refStart
        ).let {
            if (it >= 0) it else raw.length
        }

        val encodedReferer = raw.substring(
            refStart,
            refEnd
        )

        val referer = runCatching {
            URLDecoder.decode(
                encodedReferer,
                StandardCharsets.UTF_8.toString()
            )
        }.getOrDefault("$mainUrl/")

        val detailUrl = if (raw.contains(detailMarker)) {
            runCatching {
                URLDecoder.decode(
                    raw.substringAfter(detailMarker, ""),
                    StandardCharsets.UTF_8.toString()
                )
            }.getOrNull()
        } else {
            null
        }

        return EpisodePlaybackRequest(
            mediaUrl = media,
            referer = referer,
            detailUrl = detailUrl
        )
    }

    private suspend fun selectDiscoveryMediaUrl(
        url: String
    ): String? {
        val normalized = normalizeMediaUrl(url)

        if (normalized.isBlank()) {
            return null
        }

        return normalized
    }

    private suspend fun emitMedia(
        mediaUrl: String,
        callback: (ExtractorLink) -> Unit,
        referer: String = "$mainUrl/",
        label: String = "Discovery FTP",
        cookieHeader: String? = null
    ) {
        val normalized = normalizeMediaUrl(mediaUrl)
        if (!isMediaUrl(normalized)) return

        val lower = normalized.lowercase(Locale.ROOT)

        val type = when {
            ".m3u8" in lower -> ExtractorLinkType.M3U8
            ".mpd" in lower -> ExtractorLinkType.DASH
            else -> ExtractorLinkType.VIDEO
        }

        val quality = when {
            "2160" in lower || "4k" in lower -> Qualities.P2160.value
            "1440" in lower -> Qualities.P1440.value
            "1080" in lower -> Qualities.P1080.value
            "720" in lower -> Qualities.P720.value
            "480" in lower -> Qualities.P480.value
            "360" in lower -> Qualities.P360.value
            else -> Qualities.Unknown.value
        }

        /*
         * Browser capture proves the Discovery CDN is playable with the
         * website as Referer. Let CloudStream/ExoPlayer supply its own normal
         * User-Agent and Range handling instead of overriding the datasource
         * with custom Accept-Encoding or duplicate Referer headers.
         *
         * This avoids the regression introduced by the previous build where
         * previously playable files started returning IO_UNSPECIFIED.
         */
        val playbackReferer = if (
            Regex("(?i)^https?://cdn[1-5]\\.discoveryftp\\.net/")
                .containsMatchIn(normalized)
        ) {
            "$mainUrl/"
        } else {
            referer.ifBlank { "$mainUrl/" }
        }

        callback(
            newExtractorLink(
                source = name,
                name = label,
                url = normalized,
                type = type
            ) {
                this.referer = playbackReferer
                this.quality = quality

                /*
                 * Keep the direct-media request minimal, matching the supplied
                 * BasPlay provider. No synthetic Range, Accept-Encoding,
                 * CORS, or forced browser headers are added.
                 *
                 * A real Discovery session cookie is included only when the
                 * non-direct page resolver actually established one.
                 */
                if (!cookieHeader.isNullOrBlank()) {
                    this.headers = mapOf(
                        "Cookie" to cookieHeader
                    )
                }
            }
        )
    }

    /**
     * Select the most appropriate direct media URL from candidates.
     *
     * Container priority:
     * MKV -> MP4 -> M3U8 -> MPD -> other supported video formats.
     *
     * When multiple candidates use the same container, prefer 1080P over 4K.
     * 4K remains a fallback rather than the primary selection.
     */
    private fun pickBestMedia(
        mediaUrls: List<String>
    ): String? {
        val candidates = mediaUrls
            .asSequence()
            .map { normalizeMediaUrl(it) }
            .filter { it.isNotBlank() }
            .filter { isMediaUrl(it) }
            .distinct()

        fun extensionRank(url: String): Int {
            val clean = url
                .substringBefore('?')
                .substringBefore('#')
                .lowercase(Locale.ROOT)

            return when {
                clean.endsWith(".mkv") -> 0
                clean.endsWith(".mp4") -> 1
                clean.endsWith(".m3u8") -> 2
                clean.endsWith(".mpd") -> 3
                clean.endsWith(".webm") -> 4
                clean.endsWith(".mov") -> 5
                clean.endsWith(".m4v") -> 6
                clean.endsWith(".ts") -> 7
                clean.endsWith(".avi") -> 8
                clean.endsWith(".flv") -> 9
                else -> 99
            }
        }

        fun resolutionRank(url: String): Int {
            val lower = url.lowercase(Locale.ROOT)

            return when {
                Regex("""(?<![a-z0-9])1080p(?![a-z0-9])""")
                    .containsMatchIn(lower) -> 0
                Regex("""(?<![a-z0-9])720p(?![a-z0-9])""")
                    .containsMatchIn(lower) -> 1
                Regex("""(?<![a-z0-9])480p(?![a-z0-9])""")
                    .containsMatchIn(lower) -> 2
                Regex("""(?<![a-z0-9])360p(?![a-z0-9])""")
                    .containsMatchIn(lower) -> 3
                Regex("""(?<![a-z0-9])1440p(?![a-z0-9])""")
                    .containsMatchIn(lower) -> 4
                Regex("""(?<![a-z0-9])2160p(?![a-z0-9])""")
                    .containsMatchIn(lower) -> 5
                Regex("""(?<![a-z0-9])4k(?![a-z0-9])""")
                    .containsMatchIn(lower) -> 5
                else -> 4
            }
        }

        fun sourceRank(url: String): Int {
            val lower = url.lowercase(Locale.ROOT)

            return when {
                "web-dl" in lower || "webdl" in lower -> 0
                "dual" in lower -> 1
                "webrip" in lower -> 2
                "bluray" in lower -> 3
                "hd" in lower -> 4
                "cam-rip" in lower || "camrip" in lower -> 8
                else -> 5
            }
        }

        return candidates
            .sortedWith(
                compareBy<String>(
                    ::resolutionRank,
                    ::extensionRank,
                    ::sourceRank
                )
            )
            .firstOrNull()
    }

    private fun extractDetailPlot(
        document: Document
    ): String? {
        val candidates = listOf(
            "p.storyline",
            ".storyline",
            "#movie-related p.storyline",
            ".movie-detail-content .storyline",
            ".movie-detail .storyline",
            "[class*='storyline']"
        )

        for (selector in candidates) {
            val text = document.selectFirst(selector)
                ?.text()
                ?.replace(Regex("\\s+"), " ")
                ?.trim()

            if (!text.isNullOrBlank()) {
                return text
            }
        }

        /*
         * Fallback for pages whose storyline paragraph is not classed but is
         * still present inside the movie detail content.
         */
        val content = document.selectFirst(".movie-detail-content")
        if (content != null) {
            val paragraphs = content.select("p")
                .map { it.text().replace(Regex("\\s+"), " ").trim() }
                .filter { it.isNotBlank() }

            paragraphs.firstOrNull { paragraph ->
                paragraph.length >= 40 &&
                    !paragraph.contains("Search In IMDB", true) &&
                    !paragraph.contains("Report", true)
            }?.let { return it }
        }

        return null
    }

    private fun extractDetailPoster(
        document: Document,
        baseUrl: String
    ): String? {
        val selectors = listOf(
            ".movie-detail-banner img[src]",
            ".movie-detail img[src]",
            ".movie-detail-content img[src]",
            "meta[property='og:image']",
            "img[src]"
        )

        for (selector in selectors) {
            val element = document.selectFirst(selector)
                ?: continue

            val raw = when (element.tagName()) {
                "meta" -> element.attr("content")
                else -> element.attr("src")
            }.trim()

            if (raw.isNotBlank()) {
                return absoluteUrl(raw, baseUrl)
            }
        }

        return null
    }

    private fun isMediaUrl(url: String): Boolean {
        val clean = url
            .substringBefore('?')
            .substringBefore('#')
            .lowercase(Locale.ROOT)

        return mediaExtensions.any { clean.endsWith(it) }
    }

    private fun normalizeMediaUrl(url: String): String {
        var normalized = url
            .trim()
            .replace(" ", "%20")

        /*
         * Discovery's TV season pages publish CDN links as HTTP, while the
         * actual browser playback request is upgraded to HTTPS. Android can
         * reject a clear-text HTTP media URL before it ever reaches the CDN,
         * producing the exact IO_NETWORK/connection failure seen in the app.
         *
         * Always upgrade Discovery CDN media URLs to HTTPS.
         */
        normalized = normalized.replace(
            Regex("(?i)^http://(cdn[1-5]\\.discoveryftp\\.net/)"),
            "https://$1"
        )

        return normalized
    }

    private fun pagedUrl(
        baseUrl: String,
        page: Int
    ): String {
        if (page <= 1) return baseUrl.removeSuffix("/")

        return baseUrl.removeSuffix("/") + "/$page"
    }

    private fun interleave(
        lists: List<List<SiteItem>>
    ): List<SiteItem> {
        if (lists.isEmpty()) return emptyList()

        val result = mutableListOf<SiteItem>()
        var index = 0

        while (true) {
            var added = false

            lists.forEach { list ->
                if (index < list.size) {
                    result += list[index]
                    added = true
                }
            }

            if (!added) break
            index++
        }

        return result
    }

    private fun collapseByTitle(items: List<SiteItem>): List<SiteItem> {
        if (items.isEmpty()) return emptyList()

        val result = LinkedHashMap<String, SiteItem>()

        items.forEach { item ->
            val key = contentKey(item) + "|" + item.type.name
            val existing = result[key]

            if (existing == null || isBetterVariant(item, existing)) {
                result[key] = if (existing == null) {
                    item
                } else {
                    item.copy(order = minOf(item.order, existing.order))
                }
            }
        }

        return result.values.toList()
    }

    private fun isBetterVariant(candidate: SiteItem, existing: SiteItem): Boolean {
        val candidatePriority = variantSelectionPriority(candidate)
        val existingPriority = variantSelectionPriority(existing)

        if (candidatePriority != existingPriority) {
            return candidatePriority > existingPriority
        }

        return candidate.order < existing.order
    }

    private fun variantSelectionPriority(item: SiteItem): Int {
        return variantSelectionPriority(
            qualityLabel = item.qualityLabel,
            qualityRank = item.qualityRank
        )
    }

    private fun variantSelectionPriority(variant: SiteVariant): Int {
        return variantSelectionPriority(
            qualityLabel = variant.qualityLabel,
            qualityRank = variant.qualityRank
        )
    }

    private fun variantSelectionPriority(
        qualityLabel: String,
        qualityRank: Int
    ): Int {
        val lower = qualityLabel.lowercase(Locale.ROOT)

        /*
         * Discovery user preference:
         * 1080P is the normal/default choice.
         * 4K is a last-resort fallback when no other useful resolution exists.
         */
        val resolutionPriority = when {
            "1080" in lower -> 900_000
            "1440" in lower -> 850_000
            "720" in lower -> 800_000
            "480" in lower -> 700_000
            "360" in lower -> 600_000
            lower.contains("hd") -> 500_000
            "2160" in lower || lower.contains("4k") -> 100_000
            else -> 0
        }

        /*
         * Keep the actual numeric quality as a tie-breaker only.
         * It must never allow 4K to outrank a non-4K resolution.
         */
        return resolutionPriority + minOf(qualityRank, 99_999)
    }

    private fun contentKey(item: SiteItem): String =
        normalizeTitleKey(item.title)

    private fun urlKey(url: String): String =
        "url:" + dedupeKey(url)

    private fun normalizeTitleKey(value: String): String {
        return value
            .lowercase(Locale.ROOT)
            .replace(Regex("\\(\\(\\d{4}\\)\\)"), " ")
            .replace(Regex("\\(\\d{4}\\)"), " ")
            .replace(Regex("[^\\p{L}\\p{N}]+"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    private fun normalizeSearchQuery(value: String): String {
        return value
            .lowercase(Locale.ROOT)
            .replace(Regex("\\bseason\\s*0*\\d+\\b"), " ")
            .replace(Regex("\\bs\\s*0*\\d+\\b"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
            .let { normalizeTitleKey(it) }
    }

    private fun cleanQualityLabel(value: String): String {
        return value
            .replace(Regex("\\s+"), " ")
            .trim()
            .replace(Regex("\\s{2,}"), " ")
    }

    private fun qualityLabelOrInfer(
        rawLabel: String,
        url: String
    ): String {
        val lower = "$rawLabel $url".lowercase(Locale.ROOT)

        val resolution = when {
            "2160" in lower || "4k" in lower -> "4K"
            "1440" in lower -> "1440P"
            "1080" in lower -> "1080P"
            "720" in lower -> "720P"
            "480" in lower -> "480P"
            "360" in lower -> "360P"
            "hd" in lower -> "HD"
            else -> ""
        }

        if (resolution.isBlank()) {
            return cleanQualityLabel(rawLabel)
        }

        val web = if (
            "web-dl" in lower ||
            "webdl" in lower
        ) {
            " WEB-DL"
        } else {
            ""
        }

        val dual = if ("dual" in lower) " DUAL" else ""

        return "$resolution$web$dual".trim()
    }

    private fun qualityRank(label: String, url: String): Int {
        val lower = "$label $url".lowercase(Locale.ROOT)

        return when {
            "2160" in lower || "4k" in lower -> 2160
            "1440" in lower -> 1440
            "1080" in lower -> 1080
            "720" in lower -> 720
            "480" in lower -> 480
            "360" in lower -> 360
            "hd" in lower -> 720
            else -> 0
        }
    }

    private suspend fun getProtectedDuplicateKeys(): Set<String> {
        protectedDuplicateKeys?.let { return it }

        return protectedIndexMutex.withLock {
            protectedDuplicateKeys?.let { return@withLock it }

            val sources = buildList {
                addAll(DUAL_SOURCES)
                add(Source("$BASE_URL/s", SourceKind.SERIES))
                addAll(ANIME_SOURCES)
            }

            val keys: MutableSet<String> = mutableSetOf()

            coroutineScope {
                sources
                    .map { source ->
                        async {
                            val local: MutableSet<String> = mutableSetOf()
                            var serverPage = 1

                            while (serverPage <= DUPLICATE_INDEX_MAX_PAGES) {
                                val items = fetchPage(source, serverPage)
                                if (items.isEmpty()) break

                                items.forEach { item ->
                                    local.add(contentKey(item))
                                    local.add(urlKey(item.url))
                                }

                                serverPage++
                            }

                            local
                        }
                    }
                    .awaitAll()
                    .forEach { localKeys ->
                        keys.addAll(localKeys)
                    }
            }

            protectedDuplicateKeys = keys
            keys
        }
    }

    private fun dedupeKey(url: String): String =
        url.substringBefore("#")
            .trim()
            .lowercase(Locale.ROOT)

    private fun episodeKey(
        episode: Episode
    ): String =
        listOf(
            episode.season ?: 1,
            episode.episode ?: 0,
            episode.data
        ).joinToString("|")

    private fun parseSeasonNumber(
        text: String,
        url: String,
        fallback: Int
    ): Int {
        val values = listOf(
            Regex("(?i)\\bS\\s*0*(\\d+)").find(text)
                ?.groupValues?.getOrNull(1),
            Regex("(?i)\\bSeason\\s*0*(\\d+)").find(text)
                ?.groupValues?.getOrNull(1),
            Regex("(?i)\\bS\\s*0*(\\d+)").find(url)
                ?.groupValues?.getOrNull(1),
            Regex("(?i)\\bSeason\\s*0*(\\d+)").find(url)
                ?.groupValues?.getOrNull(1)
        )

        return values.firstNotNullOfOrNull { it?.toIntOrNull() }
            ?: fallback
    }

    private fun parseEpisodeNumber(
        text: String,
        url: String,
        fallback: Int
    ): Int {
        val values = listOf(
            Regex("(?i)\\bEP\\s*0*(\\d+)").find(text)
                ?.groupValues?.getOrNull(1),
            Regex("(?i)\\bEpisode\\s*0*(\\d+)").find(text)
                ?.groupValues?.getOrNull(1),
            Regex("(?i)\\bE\\s*0*(\\d+)").find(url)
                ?.groupValues?.getOrNull(1),
            Regex("(?i)\\bEP\\s*0*(\\d+)").find(url)
                ?.groupValues?.getOrNull(1),
            Regex("(?i)\\bE\\s*0*(\\d+)").find(text)
                ?.groupValues?.getOrNull(1)
        )

        return values.firstNotNullOfOrNull { it?.toIntOrNull() }
            ?: fallback
    }

    private fun cleanEpisodeTitle(header: String): String {
        return header
            .replace(
                Regex("(?i)\\bS\\d+\\s*\\|\\s*EP\\s*\\d+\\b"),
                ""
            )
            .replace(
                Regex("(?i)\\bSeason\\s*\\d+\\s*\\|\\s*Episode\\s*\\d+\\b"),
                ""
            )
            .trim(' ', '|', '-', ':')
    }

    private fun titleFromUrl(url: String): String {
        val last = url
            .substringBefore('?')
            .substringBefore('#')
            .trimEnd('/')
            .substringAfterLast('/')

        if (last.isBlank()) return "Discovery FTP"

        val decoded = runCatching {
            URLDecoder.decode(
                last,
                StandardCharsets.UTF_8.toString()
            )
        }.getOrDefault(last)

        return decoded
            .substringBeforeLast('.')
            .replace('_', ' ')
            .replace('.', ' ')
            .trim()
    }

    private fun cleanTitle(value: String): String =
        value
            .replace(Regex("\\s+"), " ")
            .trim()

    private fun cleanUrl(value: String): String =
        value.trim()
            .trim('"', '\'')

    private fun absoluteUrl(
        raw: String,
        baseUrl: String
    ): String {
        val value = cleanUrl(raw)
        if (value.isBlank()) return ""

        if (
            value.startsWith("http://", true) ||
            value.startsWith("https://", true)
        ) {
            return normalizeMediaUrl(value)
        }

        return runCatching {
            URI(baseUrl)
                .resolve(value)
                .toString()
        }.getOrElse {
            when {
                value.startsWith("//") ->
                    "https:$value"

                value.startsWith("/") ->
                    URI(baseUrl)
                        .resolve(value)
                        .toString()

                else ->
                    baseUrl.trimEnd('/') + "/" + value
            }
        }
    }

    private fun firstNonBlank(
        vararg values: String?
    ): String {
        return values.firstOrNull {
            !it.isNullOrBlank()
        } ?: ""
    }

    private fun searchScore(
        query: String,
        title: String
    ): Int {
        val q = query.lowercase(Locale.ROOT).trim()
        val t = title.lowercase(Locale.ROOT).trim()

        if (t == q) return 100
        if (t.startsWith(q)) return 80
        if (t.contains(q)) return 60

        val queryWords = q.split(Regex("\\s+"))
            .filter { it.isNotBlank() }

        return queryWords.count {
            t.contains(it)
        } * 10
    }

}