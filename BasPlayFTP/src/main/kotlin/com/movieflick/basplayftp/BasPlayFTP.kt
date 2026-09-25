package com.movieflick.basplayftp

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

class BasPlayFTP : MainAPI() {
    override var mainUrl = "http://10.20.30.40"
    override var name = "Bas Play FTP"
    override var lang = "bn"

    override val hasMainPage = true
    override val hasQuickSearch = true

    override val supportedTypes = setOf(
        TvType.Movie,
        TvType.TvSeries,
        TvType.Anime
    )

    override val mainPage = mainPageOf(
        "basplay://movies" to "Movies",
        "basplay://tv" to "Tv Show",
        "basplay://anime" to "Anime"
    )

    private companion object {
        const val BASE_URL = "http://10.20.30.40"

        const val INITIAL_BATCH = 6
        const val CONTINUE_BATCH = 10
        const val CATEGORY_PAGE_SIZE = 24

        const val MOVIES_URL = "$BASE_URL/index.php"
        const val TV_URL = "$BASE_URL/tv.php"
        const val ANIME_MOVIES_URL = "$BASE_URL/category.php?category=Animation"
        const val ANIME_TV_URL = "$BASE_URL/tv.php?category=ANIMATED+TV+SERIES"
    }

    private data class SiteItem(
        val title: String,
        val url: String,
        val poster: String?,
        val type: TvType,
        val sortTime: Long = 0L,
        val order: Long = 0L
    )

    private data class PageResult(
        val items: List<SiteItem>,
        val hasNext: Boolean
    )

    private data class CachedCategoryPage(
        val items: List<SiteItem>,
        val hasNext: Boolean
    )

    private val categoryPageCache = ConcurrentHashMap<String, CachedCategoryPage>()
    private val prefetchScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private fun pageHeaders(referer: String = "$mainUrl/"): Map<String, String> = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/153.0.0.0 Safari/537.36",
        "Accept" to "*/*",
        "Accept-Encoding" to "identity;q=1, *;q=0",
        "Accept-Language" to "en-US,en;q=0.9,bn;q=0.8",
        "Cache-Control" to "no-cache",
        "Pragma" to "no-cache",
        "Referer" to referer
    )

    private data class PageFetch(
        val document: Document,
        val responseHeaders: Map<String, String> = emptyMap()
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val pageNumber = page.coerceAtLeast(1)
        val result = when (request.data) {
            "basplay://movies" -> loadMovies(pageNumber)
            "basplay://tv" -> loadTv(pageNumber)
            "basplay://anime" -> loadAnime(pageNumber)
            else -> PageResult(emptyList(), false)
        }

        return newHomePageResponse(
            request,
            result.items.map { it.toSearchResponse() },
            result.hasNext
        )
    }

    private suspend fun loadMovies(page: Int): PageResult {
        val (items, sourceHasNext) = getCategoryItemsUpTo(
            source = MOVIES_URL,
            requiredCount = homeRequiredCount(page),
            defaultType = TvType.Movie,
            forceSeries = false
        )

        val movieOnly = items
            .filterNot { isSeriesUrl(it.url) }
            .distinctBy { movieDedupeKey(it) }

        return pageSlice(movieOnly, page, sourceHasNext)
    }

    private suspend fun loadTv(page: Int): PageResult {
        val (items, sourceHasNext) = getCategoryItemsUpTo(
            source = TV_URL,
            requiredCount = homeRequiredCount(page),
            defaultType = TvType.TvSeries,
            forceSeries = true
        )

        val tvOnly = items
            .filter { isSeriesUrl(it.url) }
            .map { it.copy(type = TvType.TvSeries) }
            .distinctBy { itemKey(it) }

        return pageSlice(tvOnly, page, sourceHasNext)
    }

    private suspend fun loadAnime(page: Int): PageResult = coroutineScope {
        val firstDeferred = async {
            getCategoryItemsUpTo(
                source = ANIME_MOVIES_URL,
                requiredCount = homeRequiredCount(page),
                defaultType = TvType.Anime,
                forceSeries = false
            )
        }

        val secondDeferred = async {
            getCategoryItemsUpTo(
                source = ANIME_TV_URL,
                requiredCount = homeRequiredCount(page),
                defaultType = TvType.TvSeries,
                forceSeries = true
            )
        }

        val (first, firstHasNext) = firstDeferred.await()
        val (second, secondHasNext) = secondDeferred.await()

        val merged = mergeInterleaved(
            first.distinctBy { itemKey(it) },
            second.distinctBy { itemKey(it) }
        ).distinctBy { itemKey(it) }

        pageSlice(
            merged,
            page,
            firstHasNext || secondHasNext
        )
    }

    private suspend fun getCategoryItemsUpTo(
        source: String,
        requiredCount: Int,
        defaultType: TvType,
        forceSeries: Boolean
    ): Pair<List<SiteItem>, Boolean> = coroutineScope {
        if (requiredCount <= 0) return@coroutineScope emptyList<SiteItem>() to false

        val pagesNeeded = ((requiredCount + CATEGORY_PAGE_SIZE - 1) / CATEGORY_PAGE_SIZE).coerceAtLeast(1)

        val pages = (1..pagesNeeded).map { serverPage ->
            async {
                fetchCategoryPage(
                    source = source,
                    page = serverPage,
                    defaultType = defaultType,
                    forceSeries = forceSeries
                )
            }
        }.awaitAll()

        val merged = pages.asSequence()
            .flatMap { it.items.asSequence() }
            .distinctBy { itemKey(it) }
            .toList()

        val hasSourceNext = pages.any { it.hasNext }

        // Prepare the next server page without blocking the currently visible row.
        prefetchCategoryPage(
            source = source,
            page = pagesNeeded + 1,
            defaultType = defaultType,
            forceSeries = forceSeries
        )

        merged.take(requiredCount) to (hasSourceNext || merged.size > requiredCount)
    }

    private suspend fun fetchCategoryPage(
        source: String,
        page: Int,
        defaultType: TvType,
        forceSeries: Boolean
    ): CachedCategoryPage {
        val url = pagedUrl(source, page)
        categoryPageCache[url]?.let { return it }

        val document = getDocument(url)
        val selectors = if (forceSeries) {
            ".cp-card, .movie-card, a[href*='tview.php']"
        } else {
            ".cp-card, .movie-card, .movie-grid .movie-card, " +
                "a[href*='view.php'], a[href*='player.php']"
        }

        val items = document?.let {
            parseItems(
                candidates = it.select(selectors),
                sourceUrl = url,
                defaultType = defaultType,
                forceSeries = forceSeries
            )
        }.orEmpty()

        val result = CachedCategoryPage(
            items = items,
            hasNext = document?.let { detectNextPage(it, page) } == true ||
                items.size >= CATEGORY_PAGE_SIZE
        )

        categoryPageCache.putIfAbsent(url, result)
        return categoryPageCache[url] ?: result
    }

    private fun prefetchCategoryPage(
        source: String,
        page: Int,
        defaultType: TvType,
        forceSeries: Boolean
    ) {
        val url = pagedUrl(source, page)
        if (categoryPageCache.containsKey(url)) return

        prefetchScope.launch {
            runCatching {
                fetchCategoryPage(
                    source = source,
                    page = page,
                    defaultType = defaultType,
                    forceSeries = forceSeries
                )
            }
        }
    }

    private fun pageSlice(
        items: List<SiteItem>,
        page: Int,
        sourceHasNext: Boolean
    ): PageResult {
        if (items.isEmpty()) return PageResult(emptyList(), false)

        val offset = homeOffset(page)
        val take = homeTake(page)
        val batch = items.drop(offset).take(take)

        return PageResult(
            items = batch,
            hasNext = sourceHasNext && batch.isNotEmpty()
        )
    }

    private fun homeRequiredCount(page: Int): Int =
        if (page <= 1) INITIAL_BATCH
        else INITIAL_BATCH + ((page - 1) * CONTINUE_BATCH)

    private fun homeOffset(page: Int): Int =
        if (page <= 1) 0
        else INITIAL_BATCH + ((page - 2) * CONTINUE_BATCH)

    private fun homeTake(page: Int): Int =
        if (page <= 1) INITIAL_BATCH else CONTINUE_BATCH

    private fun parseItems(
        candidates: List<Element>,
        sourceUrl: String,
        defaultType: TvType,
        forceSeries: Boolean
    ): List<SiteItem> {
        val result = linkedMapOf<String, SiteItem>()
        var order = 0L

        for (element in candidates) {
            val href = extractContentUrl(element) ?: continue
            val absolute = absoluteUrl(href, sourceUrl)
            if (!isUsefulContentUrl(absolute)) continue

            val card = findCard(element)
            val title = cleanTitle(
                extractCardTitle(element, card)
                    .ifBlank { titleFromUrl(absolute) }
            )
            if (title.isBlank() || isNavigationTitle(title)) continue

            val series = forceSeries || isSeriesUrl(absolute)
            val type = when {
                series -> TvType.TvSeries
                defaultType == TvType.Anime -> TvType.Anime
                else -> TvType.Movie
            }

            val poster = extractPoster(card ?: element, sourceUrl)
            val key = itemKeyFromUrl(absolute)
            result.putIfAbsent(
                key,
                SiteItem(
                    title = title,
                    url = absolute,
                    poster = poster,
                    type = type,
                    order = order++
                )
            )
        }

        // Never alphabetize or randomize. Preserve the source page's order,
        // which is the ordering supplied by BAS PLAY for newest uploads.
        return result.values.sortedBy { it.order }
    }

    override suspend fun search(query: String, page: Int): SearchResponseList {
        val q = query.trim()
        if (q.isBlank()) return newSearchResponseList(emptyList(), false)

        // BAS PLAY exposes a real server-side search endpoint: search.php?q=...
        // Prefer it so search is dynamic and does not depend only on the first
        // page of the category indexes.
        val searchUrl = setQueryParam("$BASE_URL/search.php", "q", q)
        val searchDocument = getDocument(searchUrl)

        val ranked = linkedMapOf<String, SiteItem>()
        if (searchDocument != null) {
            parseItems(
                candidates = searchDocument.select(
                    ".cp-card, .movie-card, .trend-card, a[href*='view.php'], " +
                        "a[href*='player.php'], a[href*='tview.php']"
                ),
                sourceUrl = searchUrl,
                defaultType = TvType.Movie,
                forceSeries = false
            ).forEach { item ->
                // Search results themselves determine whether an item is a TV
                // series by its URL; movies never absorb tview.php results.
                if (item.type == TvType.TvSeries || !isSeriesUrl(item.url)) {
                    val key = if (isSeriesUrl(item.url)) itemKey(item) else movieDedupeKey(item)
                    ranked.putIfAbsent(key, item.copy(type = if (isSeriesUrl(item.url)) TvType.TvSeries else item.type))
                }
            }
        }

        // Defensive fallback for deployments where search.php is unavailable
        // or returns an empty page. Keep it bounded so search stays fast.
        if (ranked.isEmpty()) {
            val fallbackSources = listOf(
                SearchSource(MOVIES_URL, TvType.Movie, false),
                SearchSource(TV_URL, TvType.TvSeries, true),
                SearchSource(ANIME_MOVIES_URL, TvType.Anime, false),
                SearchSource(ANIME_TV_URL, TvType.TvSeries, true)
            )

            for (source in fallbackSources) {
                val document = getDocument(source.url) ?: continue
                parseItems(
                    candidates = document.select(
                        ".cp-card, .movie-card, .trend-card, a[href*='view.php'], " +
                            "a[href*='player.php'], a[href*='tview.php']"
                    ),
                    sourceUrl = source.url,
                    defaultType = source.type,
                    forceSeries = source.forceSeries
                )
                    .filter { source.forceSeries || !isSeriesUrl(it.url) }
                    .filter { searchScore(q, it.title) >= 0.25 }
                    .forEach { item ->
                        val key = if (item.type == TvType.Movie) movieDedupeKey(item) else itemKey(item)
                        ranked.putIfAbsent(key, item)
                    }
            }
        }

        val ordered = ranked.values
            .map { it to searchScore(q, it.title) }
            .filter { it.second >= 0.20 }
            .sortedWith(
                compareByDescending<Pair<SiteItem, Double>> { it.second }
                    .thenBy { it.first.title.lowercase(Locale.ROOT) }
            )
            .map { it.first }

        val pageSize = 24
        val offset = (page - 1).coerceAtLeast(0) * pageSize
        val pageItems = ordered.drop(offset).take(pageSize)

        return newSearchResponseList(
            pageItems.map { it.toSearchResponse() },
            offset + pageItems.size < ordered.size
        )
    }

    private data class SearchSource(
        val url: String,
        val type: TvType,
        val forceSeries: Boolean
    )

    private fun SiteItem.toSearchResponse(): SearchResponse {
        return when (type) {
            TvType.TvSeries -> newTvSeriesSearchResponse(title, url, TvType.TvSeries) {
                posterUrl = poster
            }
            TvType.Anime -> newMovieSearchResponse(title, url, TvType.Anime) {
                posterUrl = poster
            }
            else -> newMovieSearchResponse(title, url, TvType.Movie) {
                posterUrl = poster
            }
        }
    }

    override suspend fun load(url: String): LoadResponse {
        val input = absoluteUrl(url, mainUrl)

        // Direct media is accepted as a playable movie. We never use a Trailer
        // URL here; trailer links are filtered during extraction.
        if (isMediaUrl(input)) {
            return newMovieLoadResponse(
                titleFromUrl(input),
                input,
                inferTvType(input),
                input
            )
        }

        val document = getDocument(input)
        if (document == null) {
            return fallbackLoadResponse(input)
        }

        val title = extractPageTitle(document).ifBlank { titleFromUrl(input) }
        val poster = extractPoster(document, input)
        val series = isSeriesUrl(input) || looksLikeSeriesPage(document)

        if (series) {
            val episodes = parseAllSeasonEpisodes(document, input, poster)
            if (episodes.isNotEmpty()) {
                return newTvSeriesLoadResponse(
                    title,
                    input,
                    TvType.TvSeries,
                    episodes
                ) {
                    posterUrl = poster
                }
            }
        }

        // The movie detail/player page contains a direct <video><source>
        // playable URL. Pass the detail page to loadLinks() so the latest source
        // (including a rotated token/signature) is discovered at play time.
        return newMovieLoadResponse(
            title,
            input,
            inferTvType(input),
            "basplay:movie:${encodeToken(input)}"
        ) {
            posterUrl = poster
        }
    }

    private suspend fun fallbackLoadResponse(url: String): LoadResponse {
        return newMovieLoadResponse(
            titleFromUrl(url),
            url,
            inferTvType(url),
            url
        )
    }

    private suspend fun parseAllSeasonEpisodes(
        firstDocument: Document,
        seriesUrl: String,
        fallbackPoster: String?
    ): List<Episode> {
        val seasonLinks = linkedSetOf<String>()

        // The source page exposes the real season selector. Build the exact
        // season URLs from those options instead of inventing season numbers.
        firstDocument.select("#seasonSelect option, select[name*=season] option").forEach { option ->
            val value = option.attr("value").trim()
            if (value.isNotBlank()) {
                seasonLinks += setQueryParam(seriesUrl, "season", value).let {
                    removeQueryParam(it, "episode")
                }
            }
        }

        if (seasonLinks.isEmpty()) seasonLinks += removeQueryParam(seriesUrl, "episode")

        val documents = linkedMapOf<String, Document>()
        documents[removeQueryParam(seriesUrl, "episode")] = firstDocument

        for (seasonUrl in seasonLinks) {
            if (documents.containsKey(seasonUrl)) continue
            getDocument(seasonUrl)?.let { documents[seasonUrl] = it }
        }

        val result = mutableListOf<Episode>()
        for ((seasonUrl, document) in documents) {
            result += parseEpisodesFromDocument(document, seasonUrl, fallbackPoster)
        }

        return result
            .distinctBy { "${it.season ?: 1}:${it.episode ?: 0}:${it.data}" }
            .sortedWith(
                compareBy<Episode> { it.season ?: Int.MAX_VALUE }
                    .thenBy { it.episode ?: Int.MAX_VALUE }
                    .thenBy { (it.name ?: "").lowercase(Locale.ROOT) }
            )
    }

    private fun parseEpisodesFromDocument(
        document: Document,
        seriesUrl: String,
        fallbackPoster: String?
    ): List<Episode> {
        val episodes = mutableListOf<Episode>()
        val selectors = listOf(
            ".ep-item[data-src]",
            "a[data-epnum][data-src]",
            "[data-episode][data-src]",
            "a[href*=episode]"
        )

        val elements = linkedSetOf<Element>()
        selectors.forEach { selector -> elements.addAll(document.select(selector)) }

        for (element in elements) {
            val directRaw = element.attr("data-src").trim()
            val directMedia = if (directRaw.isNotBlank()) {
                absoluteUrl(directRaw, seriesUrl)
            } else {
                extractDirectMediaFromElement(element, seriesUrl).orEmpty()
            }

            val hrefRaw = element.attr("href").trim()
            val episodePage = if (hrefRaw.isNotBlank() && hrefRaw != "#") {
                absoluteUrl(hrefRaw, seriesUrl)
            } else {
                setQueryParam(
                    removeQueryParam(seriesUrl, "episode"),
                    "episode",
                    element.attr("data-epnum").trim()
                )
            }

            val pageFallback = episodePage.takeIf { it.contains("tview.php", true) }
            val playable = when {
                isPlayableMedia(directMedia) && !isTrailerUrl(directMedia) -> directMedia
                else -> ""
            }

            if (playable.isBlank() && pageFallback == null) continue
            if (isTrailerUrl(playable) || isDownloadOnlyUrl(playable)) continue

            val text = cleanTitle(
                element.attr("title")
                    .ifBlank { element.selectFirst(".text-sm, .cp-title, h1,h2,h3,h4,h5")?.text().orEmpty() }
                    .ifBlank { element.text() }
                    .ifBlank { titleFromUrl(playable.ifBlank { episodePage }) }
            )
            if (text.isBlank() || isNavigationTitle(text)) continue

            val explicitEpisode = element.attr("data-epnum").toIntOrNull()
            val episodeNumber = explicitEpisode ?: extractEpisodeNumber(text, playable.ifBlank { episodePage }) ?: continue
            val season = element.attr("data-season").toIntOrNull()
                ?: extractSeasonNumber(text, playable.ifBlank { episodePage })
                ?: extractSeasonNumber("$seriesUrl", seriesUrl)
                ?: 1

            // Keep the episode page as the CloudStream episode data.
            // loadLinks() will fetch this exact page again when the user presses
            // Play, then collect that episode's current real <video><source> /
            // data-src media URL. This mirrors the working Movie Haat pattern: the
            // episode hands loadLinks() the source-of-truth endpoint, and the
            // playable media link is emitted only at Play time.
            val data = episodePage

            episodes += newEpisode(data) {
                name = text
                this.season = season
                this.episode = episodeNumber
                posterUrl = extractPoster(element, seriesUrl) ?: fallbackPoster
            }
        }

        return episodes
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val input = data.trim()
        if (input.isBlank()) return false

        // Movie Haat-style direct media path: when an episode resolves to its
        // actual media URL, emit that URL directly with the same simple
        // ExtractorLink construction used by the working Movie implementation.
        // Do not attach the previous TV-specific header profile here.
        if (isMediaUrl(input) && !isTrailerUrl(input) && !isDownloadOnlyUrl(input)) {
            emitDirectMedia(input, callback)
            return true
        }

        when {
            input.startsWith("basplay:direct:") -> {
                val media = decodeToken(input.substringAfter("basplay:direct:"))
                if (media.isBlank()) return false
                emitMedia(media, mainUrl, mediaHeaders(mainUrl), callback)
                return true
            }

            input.startsWith("basplay:movie:") -> {
                val pageUrl = decodeToken(input.substringAfter("basplay:movie:"))
                return resolvePlayableFromPage(pageUrl, callback)
            }

            input.startsWith("basplay:episode:") -> {
                val payload = input.substringAfter("basplay:episode:")
                val parts = payload.split(":", limit = 2)
                val directMedia = decodeToken(parts.getOrNull(0).orEmpty())
                val episodePage = decodeToken(parts.getOrNull(1).orEmpty())

                if (directMedia.isNotBlank() && isPlayableMedia(directMedia)) {
                    emitDirectMedia(directMedia, callback)
                    return true
                }

                if (episodePage.isNotBlank()) {
                    return resolveTvEpisodeDirect(episodePage, callback)
                }
                return false
            }
        }

        if (isMediaUrl(input) && !isTrailerUrl(input)) {
            emitMedia(input, mainUrl, mediaHeaders(mainUrl), callback)
            return true
        }

        // BAS PLAY TV pages are episode source-of-truth pages. Fetch the
        // selected tview.php page at Play time, find that episode's actual
        // <video><source>/data-src, then emit the direct media URL using the
        // same minimal ExtractorLink pattern as Movie Haat.
        if (input.contains("tview.php", true)) {
            return resolveTvEpisodeDirect(input, callback)
        }

        if (looksLikePlayerOrContentPage(input)) {
            return resolvePlayableFromPage(input, callback)
        }

        return false
    }

    /**
     * TV episode playback follows the source-of-truth tview.php page. The page
     * is fetched every time the user presses Play so the current episode media
     * URL is always discovered fresh. Once found, the URL is emitted using the
     * same minimal direct ExtractorLink pattern used by Movie Haat.
     */
    private suspend fun resolveTvEpisodeDirect(
        episodePage: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val normalized = episodePage.trim()
        if (normalized.isBlank()) return false

        val episodeNumber = Regex("(?i)[?&]episode=(\\d+)")
            .find(normalized)?.groupValues?.getOrNull(1)?.toIntOrNull()

        // IMPORTANT: keep the old working architecture.
        // We do NOT trust a cached/extracted media URL. Every Play request
        // fetches the BAS PLAY TV page again and resolves the current source.
        val pageCandidates = linkedSetOf<String>()
        pageCandidates += normalized
        pageCandidates += removeQueryParam(normalized, "episode")

        // Also try an explicit rebuilt episode URL so malformed/duplicate
        // query parameters cannot prevent the server from selecting the
        // requested episode.
        if (episodeNumber != null) {
            pageCandidates += setQueryParam(
                removeQueryParam(normalized, "episode"),
                "episode",
                episodeNumber.toString()
            )
        }

        for (pageUrl in pageCandidates) {
            val page = getPage(pageUrl) ?: continue
            val document = page.document
            val mediaCandidates = linkedSetOf<String>()

            // 1) BAS PLAY's canonical per-episode source.
            //    data-epnum tells us exactly which episode the data-src belongs to.
            val episodeElements = document.select(
                ".ep-item[data-src], " +
                    "a[data-epnum][data-src], " +
                    "[data-episode][data-src]"
            )

            for (element in episodeElements) {
                val number = element.attr("data-epnum").toIntOrNull()
                    ?: element.attr("data-episode").toIntOrNull()

                if (episodeNumber != null && number != null && number != episodeNumber) {
                    continue
                }

                val raw = element.attr("data-src").trim()
                if (raw.isBlank()) continue

                val absolute = absoluteUrl(raw, pageUrl)
                if (
                    isPlayableMedia(absolute) &&
                    !isTrailerUrl(absolute) &&
                    !isDownloadOnlyUrl(absolute)
                ) {
                    mediaCandidates += absolute
                }
            }

            // 2) On the selected tview page the browser's actual player may
            //    expose the current episode through <video><source> instead of
            //    only data-src. Prefer these as another exact source of truth.
            document.select(
                "video source[src], " +
                    "video source[data-src], " +
                    "video[src], " +
                    "source[src], " +
                    "source[data-src]"
            ).forEach { element ->
                listOf(
                    element.attr("src"),
                    element.attr("data-src")
                ).forEach { raw ->
                    if (raw.isBlank()) return@forEach
                    val absolute = absoluteUrl(raw, pageUrl)
                    if (
                        isPlayableMedia(absolute) &&
                        !isTrailerUrl(absolute) &&
                        !isDownloadOnlyUrl(absolute)
                    ) {
                        mediaCandidates += absolute
                    }
                }
            }

            // 3) Some BAS PLAY pages embed the source in JavaScript/HTML.
            //    Use filename/episode matching before accepting a generic media
            //    URL so Episode 5 never accidentally receives Episode 1's file.
            val html = document.html()
                .replace("\\/", "/")
                .replace("\\u002F", "/")
                .replace("\\u002f", "/")
                .replace("\\u003A", ":")
                .replace("\\u003a", ":")
                .replace("\\u0026", "&")
                .replace("&amp;", "&")

            Regex(
                """(?i)(?:https?://|/|\\.\\.?/)[^\"'<>\\s]+\\.(?:m3u8|mpd|mp4|mkv|webm|mov|m4v|avi|flv|ts)(?:\\?[^\"'<>\\s]*)?"""
            ).findAll(html).forEach { match ->
                val absolute = absoluteUrl(match.value, pageUrl)
                if (!isPlayableMedia(absolute)) return@forEach
                if (isTrailerUrl(absolute) || isDownloadOnlyUrl(absolute)) return@forEach

                if (episodeNumber == null || matchesEpisodeNumber(absolute, episodeNumber)) {
                    mediaCandidates += absolute
                }
            }

            if (mediaCandidates.isNotEmpty()) {
                val selected = mediaCandidates
                    .sortedWith(
                        compareByDescending<String> { url ->
                            if (episodeNumber != null && matchesEpisodeNumber(url, episodeNumber)) 10000 else 0
                        }.thenByDescending(::mediaScore)
                    )
                    .firstOrNull()

                if (!selected.isNullOrBlank()) {
                    emitDirectMedia(selected, callback)
                    return true
                }
            }
        }

        return false
    }

    private fun matchesEpisodeNumber(
        url: String,
        episodeNumber: Int
    ): Boolean {
        val decoded = runCatching {
            URLDecoder.decode(url, StandardCharsets.UTF_8.toString())
        }.getOrDefault(url)

        val source = decoded.lowercase(Locale.ROOT)
        val ep = episodeNumber.toString()
        val padded = episodeNumber.toString().padStart(2, '0')

        return Regex("(?i)\\bs\\d{1,2}e(?:$padded|$ep)\\b").containsMatchIn(source) ||
            Regex("(?i)\\b(?:episode|ep)[ ._-]*(?:$ep|$padded)\\b").containsMatchIn(source)
    }

    private suspend fun resolvePlayableFromPage(
        pageUrl: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val normalized = pageUrl.trim()
        if (normalized.isBlank()) return false

        // Movie detail pages (view.php) do not contain the actual media source.
        // Their real WATCH NOW target is player.php?id=..., which is what the
        // browser opens before requesting the .mkv file.
        if (normalized.contains("view.php", true)) {
            val detail = getPage(normalized) ?: return false
            val playerUrls = linkedSetOf<String>()

            detail.document.select("a[href*='player.php']").forEach { a ->
                val href = a.attr("href").trim()
                if (href.isNotBlank()) {
                    playerUrls += absoluteUrl(href, normalized)
                }
            }

            // Defensive fallback for detail pages where the watch button is
            // generated without a normal anchor in the parsed HTML.
            if (playerUrls.isEmpty()) {
                val id = Regex("[?&]id=(\\d+)", RegexOption.IGNORE_CASE)
                    .find(normalized)?.groupValues?.getOrNull(1)
                if (!id.isNullOrBlank()) {
                    playerUrls += "$BASE_URL/player.php?id=$id"
                }
            }

            for (playerUrl in playerUrls) {
                if (resolvePlayableFromPlayerPage(playerUrl, normalized, callback)) {
                    return true
                }
            }

            // Some pages can expose a source directly even when the primary
            // WATCH NOW link is unavailable.
            if (resolvePlayableDocument(detail.document, normalized, detail.responseHeaders, callback)) {
                return true
            }

            return false
        }

        val page = getPage(normalized) ?: return false
        val referer = canonicalTvReferer(normalized)
        return resolvePlayableDocument(
            page.document,
            normalized,
            page.responseHeaders,
            callback,
            preferredReferer = referer
        )
    }

    private suspend fun resolvePlayableFromPlayerPage(
        playerUrl: String,
        referer: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val page = getPage(playerUrl) ?: return false
        return resolvePlayableDocument(
            page.document,
            playerUrl,
            page.responseHeaders,
            callback,
            preferredReferer = playerUrl.ifBlank { referer }
        )
    }

    private suspend fun resolvePlayableDocument(
        document: Document,
        pageUrl: String,
        responseHeaders: Map<String, String>,
        callback: (ExtractorLink) -> Unit,
        preferredReferer: String = pageUrl
    ): Boolean {
        val mediaCandidates = linkedSetOf<String>()

        // Highest-confidence source: the actual HTML5 video source used by
        // BAS PLAY's browser player.
        document.select(
            "video source[src], video source[data-src], video[src], source[src], source[data-src], " +
                "[data-src], [data-video], [data-file], [data-url], [data-source], " +
                "[data-stream], [data-video-url], [data-manifest]"
        ).forEach { element ->
            listOf(
                element.attr("src"),
                element.attr("data-src"),
                element.attr("data-video"),
                element.attr("data-file"),
                element.attr("data-url"),
                element.attr("data-source"),
                element.attr("data-stream"),
                element.attr("data-video-url"),
                element.attr("data-manifest")
            ).forEach { value ->
                val absolute = absoluteUrl(value, pageUrl)
                if (isPlayableMedia(absolute) && !isTrailerUrl(absolute) && !isDownloadOnlyUrl(absolute)) {
                    mediaCandidates += absolute
                }
            }
        }

        // Player JS fallback. This catches sources assigned dynamically.
        val html = document.html()
            .replace("\\/", "/")
            .replace("\\u002F", "/")
            .replace("\\u002f", "/")
            .replace("\\u003A", ":")
            .replace("\\u003a", ":")
            .replace("\\u0026", "&")
            .replace("&amp;", "&")

        Regex(
            """(?i)(?:https?://|/|\.\.?/)[^"'<>\s]+\.(?:m3u8|mpd|mp4|mkv|webm|mov|m4v|avi|flv|ts)(?:\?[^"'<>\s]*)?"""
        ).findAll(html).forEach { match ->
            val absolute = absoluteUrl(match.value, pageUrl)
            if (isPlayableMedia(absolute) && !isTrailerUrl(absolute) && !isDownloadOnlyUrl(absolute)) {
                mediaCandidates += absolute
            }
        }

        Regex(
            """(?i)(?:file|src|source|url|video|videoUrl|media|mediaUrl|fileUrl|video_url|stream|streamUrl|manifest|hls|dash)\s*[:=]\s*["']([^"']+)["']"""
        ).findAll(html).forEach { match ->
            val absolute = absoluteUrl(match.groupValues[1], pageUrl)
            if (isPlayableMedia(absolute) && !isTrailerUrl(absolute) && !isDownloadOnlyUrl(absolute)) {
                mediaCandidates += absolute
            }
        }

        val selected = mediaCandidates
            .filterNot(::isTrailerUrl)
            .filterNot(::isDownloadOnlyUrl)
            .sortedByDescending(::mediaScore)
            .firstOrNull()
            ?: return false

        // For TV, match the real browser media request captured from BAS PLAY.
        // The captured request had no Cookie header, so do not manufacture or
        // replay Set-Cookie attributes from the HTML response. This is important
        // because Cookie attributes such as "Path=/" must never be sent back as
        // request cookies and can cause the server to reject the media request.
        val headers = if (preferredReferer.contains("tview.php", true) || pageUrl.contains("tview.php", true)) {
            tvMediaHeaders(preferredReferer)
        } else {
            mediaHeaders(preferredReferer, null)
        }

        emitMedia(selected, preferredReferer, headers, callback)
        return true
    }
    private fun canonicalTvReferer(url: String): String {
        if (!url.contains("tview.php", true)) return url
        return removeQueryParam(url, "episode")
            .substringBefore("#")
    }

    // BAS PLAY TV media requests observed in Chrome are intentionally minimal:
    // Referer + browser UA + identity encoding. Keep this separate from Movie
    // headers so the already-working Movie playback path is not altered.
    private fun tvMediaHeaders(
        referer: String
    ): Map<String, String> = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/153.0.0.0 Safari/537.36",
        "Accept" to "*/*"
    )

    private fun mediaHeaders(
        referer: String,
        cookieHeader: String? = null
    ): Map<String, String> {
        val headers = linkedMapOf(
            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/153.0.0.0 Safari/537.36",
            "Accept" to "*/*",
            "Accept-Encoding" to "identity;q=1, *;q=0",
            "Accept-Language" to "en-US,en;q=0.9,bn;q=0.8",
            "Cache-Control" to "no-cache",
            "Pragma" to "no-cache",
            "Referer" to referer
        )
        if (!cookieHeader.isNullOrBlank()) headers["Cookie"] = cookieHeader
        return headers
    }

    private fun cookieHeaderFromResponse(headers: Map<String, String>): String? {
        val raw = headers.entries.firstOrNull { it.key.equals("Set-Cookie", true) }?.value ?: return null
        val cookies = raw.split(Regex("(?i)(?<=\\;)\\s*(?=[A-Za-z0-9_!%.-]+=)"))
            .map { it.substringBefore(';').trim() }
            .filter { it.contains('=') }
        return cookies.joinToString("; ").ifBlank { null }
    }

    /**
     * Exact direct-link emitter modeled on the working Movie Haat provider:
     * no invented headers, no cookie replay, no extra TV-specific request profile.
     */
    private suspend fun emitDirectMedia(
        url: String,
        callback: (ExtractorLink) -> Unit
    ) {
        val clean = url.substringBefore("#").trim()
        if (clean.isBlank() || isTrailerUrl(clean) || isDownloadOnlyUrl(clean)) return

        val lowered = clean.substringBefore("?").lowercase(Locale.ROOT)
        val type = when {
            lowered.endsWith(".m3u8") -> ExtractorLinkType.M3U8
            lowered.endsWith(".mpd") -> ExtractorLinkType.DASH
            else -> ExtractorLinkType.VIDEO
        }

        callback(
            newExtractorLink(
                source = name,
                name = "Bas Play Direct",
                url = clean,
                type = type
            ) {
                quality = detectQuality(lowered)
            }
        )
    }

    private suspend fun emitMedia(
        mediaUrl: String,
        referer: String,
        headers: Map<String, String>,
        callback: (ExtractorLink) -> Unit
    ) {
        val clean = mediaUrl.substringBefore("#").trim()
        if (clean.isBlank() || isTrailerUrl(clean) || isDownloadOnlyUrl(clean)) return

        val lower = clean.substringBefore("?").lowercase(Locale.ROOT)
        val type = when {
            lower.endsWith(".m3u8") -> ExtractorLinkType.M3U8
            lower.endsWith(".mpd") -> ExtractorLinkType.DASH
            else -> ExtractorLinkType.VIDEO
        }

        val isTvMedia = referer.contains("tview.php", true) || clean.contains("/TV%20", true)

        // Use the same VIDEO link type for direct .mkv/.mp4 playback in TV and
        // Movies. The Movie path is already proven to work in CloudStream, and
        // leaving the type explicit avoids handing a null/ambiguous MIME hint to
        // Media3 for TV episodes.
        callback(
            newExtractorLink(
                source = name,
                name = if (isTvMedia) "Bas Play TV" else "Bas Play Direct",
                url = clean,
                type = type
            ) {
                this.referer = referer
                this.headers = headers
                this.quality = detectQuality(lower)
            }
        )
    }

    private fun detectQuality(value: String): Int = when {
        Regex("(?i)\\b2160p\\b|\\b4k\\b").containsMatchIn(value) -> Qualities.P2160.value
        Regex("(?i)\\b1440p\\b").containsMatchIn(value) -> Qualities.P1440.value
        Regex("(?i)\\b1080p\\b").containsMatchIn(value) -> Qualities.P1080.value
        Regex("(?i)\\b720p\\b").containsMatchIn(value) -> Qualities.P720.value
        Regex("(?i)\\b480p\\b").containsMatchIn(value) -> Qualities.P480.value
        Regex("(?i)\\b360p\\b").containsMatchIn(value) -> Qualities.P360.value
        else -> Qualities.Unknown.value
    }

    private fun mediaScore(url: String): Int {
        val lower = url.lowercase(Locale.ROOT)
        var score = 0
        if ("trailer" in lower) score -= 5000
        if ("teaser" in lower) score -= 4000
        if ("preview" in lower) score -= 4000
        if ("sample" in lower) score -= 3500
        if ("clip" in lower) score -= 3000
        if ("download" in lower) score -= 2000
        if (lower.endsWith(".m3u8")) score += 1000
        if (lower.endsWith(".mpd")) score += 900
        if (lower.endsWith(".mp4")) score += 800
        if (lower.contains("2160") || lower.contains("4k")) score += 40
        else if (lower.contains("1440")) score += 35
        else if (lower.contains("1080")) score += 30
        else if (lower.contains("720")) score += 20
        else if (lower.contains("480")) score += 10
        return score
    }

    private suspend fun getPage(url: String): PageFetch? {
        val normalized = url.trim()
        if (normalized.isBlank()) return null

        val candidates = linkedSetOf<String>()
        candidates.add(normalized)
        if (normalized.startsWith("https://", true)) {
            candidates.add("http://" + normalized.removePrefix("https://"))
        } else if (normalized.startsWith("http://", true)) {
            candidates.add("https://" + normalized.removePrefix("http://"))
        }

        for (candidate in candidates) {
            val page = runCatching {
                val response = app.get(candidate, headers = pageHeaders(candidate))
                PageFetch(
                    document = response.document,
                    responseHeaders = mapOf(
                        "Set-Cookie" to (response.headers["Set-Cookie"] ?: ""),
                        "ETag" to (response.headers["ETag"] ?: "")
                    ).filterValues { it.isNotBlank() }
                )
            }.getOrNull()
            if (page != null) return page
        }
        return null
    }

    private suspend fun getDocument(url: String): Document? = getPage(url)?.document

    private fun pagedUrl(base: String, page: Int): String {
        val clean = base.substringBefore("#")
        if (page <= 1 && !clean.contains("page=", true)) return clean
        val separator = if (clean.contains("?")) "&" else "?"
        val withoutPage = clean.replace(Regex("([&?])page=\\d+"), "")
        val sep = if (withoutPage.contains("?")) "&" else "?"
        return "$withoutPage${sep}page=$page"
    }

    private fun detectNextPage(document: Document, page: Int): Boolean {
        val next = document.select(
            "a[href*='page=${page + 1}'], a[rel='next'], a:matchesOwn((?i)^Next$)"
        )
        return next.isNotEmpty()
    }

    private fun extractContentUrl(element: Element): String? {
        val attrs = listOf(
            element.attr("href"),
            element.attr("data-href"),
            element.attr("data-url"),
            element.attr("data-link"),
            element.attr("onclick")
        )

        for (raw in attrs) {
            if (raw.isBlank()) continue
            val extracted = extractUrlFromAttribute(raw) ?: raw
            if (extracted.isBlank()) continue
            if (
                extracted.contains("tview.php", true) ||
                extracted.contains("player.php", true) ||
                extracted.contains("download.php", true) ||
                extracted.contains("view.php", true)
            ) {
                return extracted
            }
        }
        return null
    }

    private fun extractUrlFromAttribute(value: String): String? {
        val patterns = listOf(
            Regex("[\\\"']((?:https?://|/|\\.\\.?/)[^\\\"']+)[\\\"']"),
            Regex("(?i)(?:location\\.href|window\\.location|openMovie|openSeries)\\s*\\(?[\\\"']?([^\\\"')]+)")
        )
        for (pattern in patterns) {
            pattern.find(value)?.groupValues?.getOrNull(1)?.let { return it }
        }
        return null
    }

    private fun findCard(element: Element): Element? {
        return element.closest(".cp-card")
            ?: element.closest(".movie-card")
            ?: element.closest(".trend-card")
            ?: element.closest("a[href]")
    }

    private fun extractCardTitle(element: Element, card: Element?): String {
        val root = card ?: element
        return sequenceOf(
            root.selectFirst(".cp-title")?.text(),
            root.attr("title").takeIf { it.isNotBlank() },
            root.attr("aria-label").takeIf { it.isNotBlank() },
            root.selectFirst("img[alt]")?.attr("alt"),
            root.selectFirst("h1,h2,h3,h4,h5")?.text(),
            element.text()
        ).filterNotNull().map { it.trim() }.firstOrNull { it.isNotBlank() }.orEmpty()
    }

    private fun extractPoster(element: Element, baseUrl: String): String? {
        val img = element.selectFirst(
            "img[src], img[data-src], img[data-lazy-src], img[data-original], img[srcset]"
        ) ?: return null

        val candidates = buildList {
            add(img.attr("src"))
            add(img.attr("data-src"))
            add(img.attr("data-lazy-src"))
            add(img.attr("data-original"))
            val srcset = img.attr("srcset")
            if (srcset.isNotBlank()) {
                add(srcset.substringBefore(',').trim().substringBefore(' '))
            }
        }

        return candidates
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .map { absoluteUrl(it, baseUrl) }
            .firstOrNull { it.isNotBlank() && !it.contains("dummyimage", true) && !it.startsWith("data:", true) }
    }

    private fun extractPageTitle(document: Document): String {
        return sequenceOf(
            document.selectFirst("h1")?.text(),
            document.selectFirst(".title")?.text(),
            document.selectFirst(".movie-title")?.text(),
            document.selectFirst("title")?.text()
        ).filterNotNull().map { cleanTitle(it) }.firstOrNull { it.isNotBlank() }.orEmpty()
    }

    private fun cleanTitle(value: String): String {
        return value
            .replace(Regex("\\s+"), " ")
            .trim()
            .removeSuffix(" • BAS PLAY")
            .removeSuffix(" - Player")
            .trim()
    }

    private fun titleFromUrl(url: String): String {
        return runCatching {
            val path = URI(url).path.orEmpty()
            URLDecoder.decode(path.substringAfterLast('/'), StandardCharsets.UTF_8.toString())
                .substringBeforeLast('.')
                .replace(Regex("[_-]+"), " ")
                .trim()
        }.getOrDefault("Untitled")
    }

    private fun setQueryParam(url: String, key: String, value: String): String {
        val clean = removeQueryParam(url, key)
        val separator = if (clean.contains("?")) "&" else "?"
        return "$clean${separator}${URLEncoder.encode(key, StandardCharsets.UTF_8.toString())}=${URLEncoder.encode(value, StandardCharsets.UTF_8.toString())}"
    }

    private fun removeQueryParam(url: String, key: String): String {
        val hash = url.substringAfter("#", "")
        val base = url.substringBefore("#")
        val queryIndex = base.indexOf('?')
        if (queryIndex < 0) return url
        val path = base.substring(0, queryIndex)
        val query = base.substring(queryIndex + 1)
        val filtered = query.split('&')
            .filter { it.isNotBlank() }
            .filterNot { part -> part.substringBefore('=').equals(key, true) }
        val rebuilt = if (filtered.isEmpty()) path else "$path?${filtered.joinToString("&")}"
        return if (hash.isBlank()) rebuilt else "$rebuilt#$hash"
    }

    private fun absoluteUrl(raw: String, base: String): String {
        val value = raw.trim().replace("&amp;", "&")
        if (value.isBlank()) return ""
        if (value.startsWith("http://", true) || value.startsWith("https://", true)) return value
        val safeValue = value.replace(" ", "%20")
        return runCatching { URI(base).resolve(safeValue).toString() }
            .getOrElse { "$mainUrl/${safeValue.trimStart('/')}" }
    }

    private fun isUsefulContentUrl(url: String): Boolean {
        val lower = url.lowercase(Locale.ROOT)
        if (lower.isBlank()) return false
        if (lower.contains("javascript:")) return false
        if (lower.contains("#")) return false
        return lower.contains("tview.php") ||
            lower.contains("player.php") ||
            lower.contains("view.php")
    }

    private fun isSeriesUrl(url: String): Boolean {
        val lower = url.lowercase(Locale.ROOT)
        return lower.contains("tview.php") || lower.contains("tv.php")
    }

    private fun looksLikeSeriesPage(document: Document): Boolean {
        return document.select(
            "[class*=episode], [id*=episode], [class*=season], [id*=season], a[href*=tview.php]"
        ).isNotEmpty()
    }

    private fun isPlayableOrPlayerUrl(url: String): Boolean {
        val lower = url.lowercase(Locale.ROOT)
        return lower.contains("player.php") || lower.contains("watch.php") || isPlayableMedia(lower)
    }

    private fun looksLikePlayerOrContentPage(url: String): Boolean {
        val lower = url.lowercase(Locale.ROOT)
        return lower.contains("player.php") || lower.contains("tview.php") || lower.contains("view.php") || lower.contains("download.php")
    }

    private fun isPlayableMedia(url: String): Boolean {
        val lower = url.lowercase(Locale.ROOT).substringBefore("?")
        return lower.endsWith(".m3u8") ||
            lower.endsWith(".mpd") ||
            lower.endsWith(".mp4") ||
            lower.endsWith(".mkv") ||
            lower.endsWith(".webm") ||
            lower.endsWith(".mov") ||
            lower.endsWith(".m4v") ||
            lower.endsWith(".avi") ||
            lower.endsWith(".flv") ||
            lower.endsWith(".ts")
    }

    private fun isMediaUrl(url: String): Boolean = isPlayableMedia(url)

    private fun isTrailerUrl(url: String): Boolean {
        val lower = url.lowercase(Locale.ROOT)
        return listOf("trailer", "teaser", "preview", "sample", "clip").any { it in lower }
    }

    private fun isDownloadOnlyUrl(url: String): Boolean {
        val lower = url.lowercase(Locale.ROOT)
        return lower.contains("download.php") && !isPlayableMedia(lower)
    }

    private fun extractDirectMediaFromElement(element: Element, baseUrl: String): String? {
        val candidates = listOf(
            element.attr("src"),
            element.attr("data-src"),
            element.attr("data-video"),
            element.attr("data-file"),
            element.attr("data-url"),
            element.attr("data-source"),
            element.attr("data-stream"),
            element.attr("data-video-url"),
            element.attr("data-manifest")
        )
        return candidates
            .map { absoluteUrl(it, baseUrl) }
            .firstOrNull { isPlayableMedia(it) && !isTrailerUrl(it) }
    }

    private fun extractPoster(document: Document, baseUrl: String): String? =
        extractPoster(document.body(), baseUrl)

    private fun itemKey(item: SiteItem): String = itemKeyFromUrl(item.url)

    private fun itemKeyFromUrl(url: String): String {
        return runCatching {
            val uri = URI(url)
            "${uri.host.orEmpty().lowercase(Locale.ROOT)}:${uri.path.orEmpty().lowercase(Locale.ROOT)}:${uri.query.orEmpty()}"
        }.getOrDefault(url.lowercase(Locale.ROOT))
    }

    private fun mergeInterleaved(
        first: List<SiteItem>,
        second: List<SiteItem>
    ): List<SiteItem> {
        val out = mutableListOf<SiteItem>()
        val max = maxOf(first.size, second.size)
        for (index in 0 until max) {
            first.getOrNull(index)?.let(out::add)
            second.getOrNull(index)?.let(out::add)
        }
        return out
    }

    private fun movieDedupeKey(item: SiteItem): String {
        val normalizedTitle = normalizeSearch(item.title)
        if (normalizedTitle.isNotBlank()) return "movie:title:$normalizedTitle"
        return "movie:url:${itemKey(item)}"
    }

    private fun searchScore(query: String, title: String): Double {
        val q = normalizeSearch(query)
        val t = normalizeSearch(title)
        if (q.isBlank() || t.isBlank()) return 0.0
        if (q == t) return 1.0
        if (t.contains(q)) return 0.96

        val qTokens = q.split(' ').filter { it.length >= 2 }
        val tTokens = t.split(' ').filter { it.length >= 2 }
        if (qTokens.isEmpty() || tTokens.isEmpty()) return 0.0

        val tokenScore = qTokens.map { qt ->
            tTokens.maxOfOrNull { tt ->
                when {
                    tt == qt -> 1.0
                    tt.startsWith(qt) || qt.startsWith(tt) -> 0.90
                    else -> similarity(qt, tt)
                }
            } ?: 0.0
        }.average()

        return tokenScore.coerceIn(0.0, 1.0)
    }

    private fun normalizeSearch(value: String): String =
        value.lowercase(Locale.ROOT)
            .replace("&", " and ")
            .replace(Regex("[^a-z0-9\\p{L}]+"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()

    private fun similarity(a: String, b: String): Double {
        if (a == b) return 1.0
        if (a.isBlank() || b.isBlank()) return 0.0
        val distance = levenshtein(a, b)
        return 1.0 - distance.toDouble() / maxOf(a.length, b.length)
    }

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
            val tmp = prev
            prev = curr
            curr = tmp
        }
        return prev[b.length]
    }

    private fun inferTvType(url: String): TvType =
        if (isSeriesUrl(url)) TvType.TvSeries else TvType.Movie

    private fun encodeToken(value: String): String =
        URLEncoder.encode(value, StandardCharsets.UTF_8.toString())

    private fun decodeToken(value: String): String =
        runCatching {
            URLDecoder.decode(value, StandardCharsets.UTF_8.toString())
        }.getOrDefault(value)

    private fun extractSeasonNumber(text: String, url: String): Int? {
        val source = "$text $url"
        val match = Regex("(?i)(?:season|s)[ ._-]*(\\d{1,2})").find(source)
        return match?.groupValues?.getOrNull(1)?.toIntOrNull()
    }

    private fun extractEpisodeNumber(text: String, url: String): Int? {
        val source = "$text $url"
        val patterns = listOf(
            Regex("(?i)(?:episode|ep|e)[ ._-]*(\\d{1,4})"),
            Regex("(?i)s\\d{1,2}e(\\d{1,4})")
        )
        return patterns.asSequence()
            .mapNotNull { it.find(source)?.groupValues?.lastOrNull()?.toIntOrNull() }
            .firstOrNull()
    }

    private fun isNavigationTitle(title: String): Boolean {
        return title.equals("next", true) ||
            title.equals("previous", true) ||
            title.equals("home", true) ||
            title.equals("movies", true) ||
            title.equals("tv", true) ||
            title.equals("anime", true)
    }
}