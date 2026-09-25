package com.movieflick.movielinkbd

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.json.JSONObject
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URI
import java.net.URLEncoder
import java.util.Locale
import kotlin.math.max
import kotlin.math.min
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

/**
 * Movie Link BD CloudStream provider.
 *
 * Design goals:
 * - Fast Home loading with real page-based lazy pagination.
 * - Stable six-category Home layout.
 * - Recently / Ongoing / Dual Audio form an overlapping protected group.
 * - Lower Home sections exclude content belonging to that protected group.
 * - Primary domain first, then exact-path mirror failover.
 * - Every Play action performs a fresh page/player-data request.
 * - Signed/tokenized CDN URLs are never cached by this provider.
 * - Playback resolves only the first successful mirror in priority order.
 * - Direct media is returned; webpage advertisements/iframes are not loaded.
 * - Fuzzy, order-independent search with typo tolerance and local fallback.
 */
class MovieLinkBD : MainAPI() {

    private companion object {
        const val PRIMARY = "https://movielinkbd.tv"
        const val FALLBACK_1 = "https://vacj4n.movielinkbd.li"
        const val FALLBACK_2 = "https://movielinkbd.one"

        val DOMAINS = listOf(PRIMARY, FALLBACK_1, FALLBACK_2)

        const val RECENTLY = "mlbd_recently"
        const val MOVIES = "mlbd_movies"
        const val DUAL_AUDIO = "mlbd_dual_audio"
        const val ONGOING = "mlbd_ongoing"
        const val TV_SHOW = "mlbd_tv_show"
        const val ANIME = "mlbd_anime"

        const val PLAYER_JSON_SELECTOR = "#mlbdInlinePlayerData"

        // Priority cache is only for category deduplication, never for media URLs.
        const val PRIORITY_CACHE_MS = 120_000L

        // Fast Home bootstrap: only this many cards are prepared per category
        // before the first Home response is returned.
        const val INITIAL_HOME_LIMIT = 6

        // Normal lazy page size after the initial bootstrap.
        const val HOME_LIMIT = 25

        // Prevent unlimited scraping while still allowing useful pagination.
        const val RECENTLY_MAX_PAGES = 12
        const val SEARCH_SITEMAP_LIMIT = 4000 // retained for legacy helpers; not used by fast search
        const val SEARCH_RESULT_LIMIT = 50
        const val PRIORITY_SCAN_MAX_PAGES = 2000
        const val SEARCH_NATIVE_MAX_PAGES = 8

        // Background indexing starts after the initial Home bootstrap.
        const val BACKGROUND_PRIORITY_TTL_MS = 120_000L

        // Fuzzy-search cutoffs.
        const val STRONG_SEARCH_SCORE = 0.78
        const val NORMAL_SEARCH_SCORE = 0.42
    }

    override var name: String = "Movie Link BD"
    override var mainUrl: String = PRIMARY
    override var lang: String = "bn"

    override val hasMainPage: Boolean = true
    override val hasQuickSearch: Boolean = true

    override val supportedTypes = setOf(
        TvType.Movie,
        TvType.TvSeries,
        TvType.Anime
    )

    /**
     * CloudStream expects: DATA KEY -> DISPLAY NAME.
     */
    override val mainPage = mainPageOf(
        RECENTLY to "Recently Uploads",
        MOVIES to "Movies",
        DUAL_AUDIO to "Dual Audio",
        ONGOING to "Ongoing Series",
        TV_SHOW to "Tv Show",
        ANIME to "Anime"
    )

    private data class SiteItem(
        val title: String,
        val url: String,
        val poster: String?,
        val type: TvType
    )

    private data class CategoryResult(
        val items: List<SiteItem>,
        val hasNext: Boolean
    )

    private data class PageResult(
        val domain: String,
        val path: String,
        val document: Document
    ) {
        fun absoluteUrl(): String = domain + path
    }

    private data class FreshSource(
        val streamUrl: String,
        val quality: Int,
        val provider: String,
        val audio: String,
        val referer: String,
        val mirrorIndex: Int,
        val sourceName: String
    )

    private var priorityCacheAt = 0L
    private val recentlyKeys = linkedSetOf<String>()
    private val ongoingKeys = linkedSetOf<String>()
    private val dualAudioKeys = linkedSetOf<String>()

    // Fast Home cache: first 6 items for every Home category.
    private val fastHomeCache = mutableMapOf<String, List<SiteItem>>()
    private val fastHomeHasNext = mutableMapOf<String, Boolean>()
    private var fastHomeWarmupAt = 0L

    // Background priority indexing is deliberately detached from the initial
    // Home response so the app can render immediately.
    private val priorityScope =
        CoroutineScope(SupervisorJob() + Dispatchers.IO)
    @Volatile private var priorityIndexStarted = false

    // ---------------------------------------------------------------------
    // HOME
    // ---------------------------------------------------------------------

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        val category = request.data
        val currentPage = page.coerceAtLeast(1)

        /*
         * IMPORTANT:
         * Never perform a full protected-category scan before rendering Home.
         * The old implementation could walk thousands of pages here and block
         * the first screen for a very long time.
         *
         * We now:
         *   1) warm the first 6 cards of all six categories in parallel;
         *   2) immediately return the requested category;
         *   3) start the complete priority index in the background.
         *
         * Later pages remain lazy and can progressively benefit from the
         * background index as it fills.
         */
        if (currentPage == 1) {
            warmInitialHomeInParallel()
            startBackgroundPriorityIndex()
        }

        val fastItems = fastHomeCache[category]
        val fastNext = fastHomeHasNext[category] ?: false

        val result = if (currentPage == 1 && fastItems != null) {
            CategoryResult(
                items = fastItems,
                hasNext = fastNext
            )
        } else {
            when (category) {
                RECENTLY -> loadRecently(currentPage)

                MOVIES -> loadMergedCategory(
                    currentPage,
                    listOf(
                        "/type/movies",
                        "/bollywood",
                        "/language/hindi-dubbed",
                        "/genre/action"
                    )
                )

                DUAL_AUDIO -> loadMergedCategory(
                    currentPage,
                    listOf("/language/dual-audio")
                )

                ONGOING -> loadMergedCategory(
                    currentPage,
                    listOf("/ongoing")
                )

                TV_SHOW -> loadMergedCategory(
                    currentPage,
                    listOf(
                        "/drama",
                        "/type/series"
                    )
                )

                ANIME -> loadMergedCategory(
                    currentPage,
                    listOf(
                        "/anime",
                        "/genre/animation"
                    )
                )

                else -> CategoryResult(emptyList(), false)
            }
        }

        val filtered = applyPriorityRules(category, result.items)
            .distinctBy { contentKey(it.url) }
            .take(
                if (currentPage == 1) {
                    INITIAL_HOME_LIMIT
                } else {
                    HOME_LIMIT
                }
            )

        val hasNext = result.hasNext && filtered.isNotEmpty()

        return newHomePageResponse(
            request,
            filtered.map { it.toSearchResponse() },
            hasNext
        )
    }

    private suspend fun warmInitialHomeInParallel() {
        val now = System.currentTimeMillis()

        if (
            now - fastHomeWarmupAt < BACKGROUND_PRIORITY_TTL_MS &&
            fastHomeCache.size >= mainPage.size
        ) {
            return
        }

        coroutineScope {
            val jobs = listOf(
                async {
                    fetchInitialCategory(
                        RECENTLY,
                        listOf("/")
                    )
                },
                async {
                    fetchInitialCategory(
                        MOVIES,
                        listOf("/type/movies")
                    )
                },
                async {
                    fetchInitialCategory(
                        DUAL_AUDIO,
                        listOf("/language/dual-audio")
                    )
                },
                async {
                    fetchInitialCategory(
                        ONGOING,
                        listOf("/ongoing")
                    )
                },
                async {
                    fetchInitialCategory(
                        TV_SHOW,
                        listOf("/drama")
                    )
                },
                async {
                    fetchInitialCategory(
                        ANIME,
                        listOf("/anime")
                    )
                }
            )

            jobs.awaitAll()
        }

        fastHomeWarmupAt = now
    }

    private suspend fun fetchInitialCategory(
        category: String,
        routes: List<String>
    ) {
        val merged = linkedMapOf<String, SiteItem>()
        var hasNext = false

        for (route in routes) {
            val result = getDocumentWithFallback(route) ?: continue

            val cards = if (category == RECENTLY && route == "/") {
                recentlyUpdatedCards(result.document)
            } else {
                result.document.select(
                    ".movie-cards-container .movie-card"
                )
            }

            val parsed = cards.mapNotNull(::parseCard)

            parsed.forEach { item ->
                merged.putIfAbsent(contentKey(item.url), item)
            }

            hasNext = hasNext ||
                hasNextPage(result.document, 1) ||
                parsed.size >= INITIAL_HOME_LIMIT

            if (merged.size >= INITIAL_HOME_LIMIT) break
        }

        /*
         * Cache only the small bootstrap set. This cache is intentionally
         * separate from the large background priority index.
         */
        fastHomeCache[category] = merged.values
            .take(INITIAL_HOME_LIMIT)

        fastHomeHasNext[category] =
            hasNext && fastHomeCache[category].orEmpty().isNotEmpty()
    }

    private fun startBackgroundPriorityIndex() {
        if (priorityIndexStarted) return

        priorityIndexStarted = true

        priorityScope.launch {
            try {
                rebuildPriorityIndexInBackground()
            } finally {
                priorityIndexStarted = false
            }
        }
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

            TvType.Anime -> newMovieSearchResponse(
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

    private suspend fun loadRecently(page: Int): CategoryResult {
        val candidates = if (page <= 1) {
            listOf("/")
        } else {
            listOf(
                "/page/$page/",
                "/page/$page"
            )
        }

        for (route in candidates) {
            val result = getDocumentWithFallback(route) ?: continue

            val cards = if (page <= 1) {
                recentlyUpdatedCards(result.document)
            } else {
                result.document.select(".movie-cards-container .movie-card")
            }

            val items = cards
                .mapNotNull(::parseCard)
                .distinctBy { contentKey(it.url) }

            if (items.isEmpty()) continue

            val next = if (page < RECENTLY_MAX_PAGES) {
                hasNextPage(result.document, page) || items.size >= HOME_LIMIT
            } else {
                false
            }

            return CategoryResult(items, next)
        }

        return CategoryResult(emptyList(), false)
    }

    private suspend fun loadMergedCategory(
        page: Int,
        routes: List<String>
    ): CategoryResult {
        val merged = linkedMapOf<String, SiteItem>()
        var anyNext = false
        var successfulRoute = false

        for (baseRoute in routes) {
            val route = pageRoute(baseRoute, page)
            val result = getDocumentWithFallback(route) ?: continue
            successfulRoute = true

            val cards = result.document
                .select(".movie-cards-container .movie-card")

            cards.mapNotNull(::parseCard).forEach { item ->
                merged.putIfAbsent(contentKey(item.url), item)
            }

            if (hasNextPage(result.document, page) || cards.size >= HOME_LIMIT) {
                anyNext = true
            }
        }

        return CategoryResult(
            merged.values.toList(),
            successfulRoute && anyNext
        )
    }

    private fun applyPriorityRules(
        category: String,
        items: List<SiteItem>
    ): List<SiteItem> {
        val recent = synchronized(this) { recentlyKeys.toSet() }
        val ongoing = synchronized(this) { ongoingKeys.toSet() }
        val dual = synchronized(this) { dualAudioKeys.toSet() }

        return when (category) {
            // Recently Uploads is the highest-priority section.
            RECENTLY -> items

            // Ongoing may overlap with Dual Audio.
            // Only remove items already shown in Recently Uploads.
            ONGOING -> items.filter {
                contentKey(it.url) !in recent
            }

            // Dual Audio is an independent section and MAY overlap with Ongoing.
            // Only remove items already shown in Recently Uploads.
            DUAL_AUDIO -> items.filter {
                contentKey(it.url) !in recent
            }

            // Lower-priority sections stay clear of Recently, Ongoing and
            // Dual Audio so those dedicated sections retain their priority.
            MOVIES, TV_SHOW, ANIME -> items.filter {
                val key = contentKey(it.url)
                key !in recent &&
                    key !in ongoing &&
                    key !in dual
            }

            else -> items
        }
    }

    // ---------------------------------------------------------------------
    // SEARCH
    // ---------------------------------------------------------------------

    override suspend fun search(query: String): List<SearchResponse> {
        val original = query.trim()
        if (original.isBlank()) return emptyList()

        /*
         * FAST MIRROR-FIRST SEARCH
         *
         * The mirrors are a failover chain, not three search targets that must
         * all be scanned. We query .tv first and return immediately as soon as
         * that mirror gives useful results. Only when .tv is unavailable or has
         * no useful matches do we try .li, then .one.
         *
         * The previous implementation walked native search pages and then
         * scanned large sitemap indexes (up to thousands of URLs) on every
         * query. That made a simple search such as "pokemon" take a very long
         * time even though /search?q=pokemon returns the site's results directly.
         */
        for (domain in DOMAINS) {
            val results = fastSearchSingleMirror(domain, original)
            if (results.isNotEmpty()) {
                return results
            }
        }

        return emptyList()
    }

    private suspend fun fastSearchSingleMirror(
        domain: String,
        original: String
    ): List<SearchResponse> {
        /*
         * First request: the site's real native search endpoint. This is the
         * only request normally needed for a successful search.
         */
        val variants = buildSearchVariants(original).toList()
        val attempts = linkedSetOf<String>()

        // Exact user query always has first priority for speed.
        attempts += original

        // Only use a couple of lightweight fallbacks if the exact query returns
        // no useful result on this mirror. Never crawl sitemap/search pages here.
        variants.forEach { variant ->
            if (attempts.size >= 3) return@forEach
            attempts += variant
        }

        for (variant in attempts) {
            val encoded = URLEncoder.encode(variant, "UTF-8")
            val result = fetchDocument(
                domain = domain,
                path = "/search?q=$encoded"
            ) ?: continue

            val cards = result.document.select(
                ".movie-cards-container .movie-card"
            )

            if (cards.isEmpty()) continue

            val parsed = cards
                .mapNotNull(::parseCard)
                .distinctBy { contentKey(it.url) }

            if (parsed.isEmpty()) continue

            val ranked = rankSearchResults(original, parsed)
            return if (ranked.isNotEmpty()) {
                ranked
            } else {
                // The website itself has already confirmed these as search matches.
                // Do not waste time probing another mirror just because local
                // fuzzy scoring was too strict for an unusual title spelling.
                parsed.take(SEARCH_RESULT_LIMIT).map { it.toSearchResponse() }
            }
        }

        return emptyList()
    }

    private suspend fun fetchDocument(
        domain: String,
        path: String
    ): PageResult? {
        val normalized = normalizePath(path)

        return try {
            val response = app.get(
                domain + normalized,
                headers = browserHeaders(domain + "/")
            )

            if (response.code !in 200..399) {
                null
            } else {
                PageResult(
                    domain = domain,
                    path = normalized,
                    document = response.document
                )
            }
        } catch (_: Throwable) {
            null
        }
    }

    private fun discoverSearchRoutes(
        home: Document?,
        domain: String,
        variants: Set<String>
    ): List<String> {
        if (home == null) return emptyList()

        val routes = linkedSetOf<String>()

        home.select("form").forEach { form ->
            val action = firstNonBlank(
                form.attr("action"),
                "/search"
            ) ?: return@forEach

            val method = form.attr("method")
                .trim()
                .uppercase(Locale.ROOT)

            /*
             * CloudStream provider search is easiest through GET. If the site
             * exposes a POST form, we keep the fixed compatibility routes
             * below rather than attempting a guessed POST contract.
             */
            if (method.isNotBlank() && method != "GET") {
                return@forEach
            }

            val inputNames = form.select(
                "input[name], textarea[name]"
            ).mapNotNull { input ->
                input.attr("name").trim().takeIf { it.isNotBlank() }
            }

            val queryName = inputNames.firstOrNull {
                it.equals("q", true) ||
                    it.equals("query", true) ||
                    it.equals("search", true) ||
                    it.equals("s", true) ||
                    it.contains("search", true)
            } ?: "q"

            for (variant in variants) {
                val encoded = URLEncoder.encode(variant, "UTF-8")
                val separator = if (action.contains("?")) "&" else "?"
                val route = normalizePath(
                    action.removePrefix(domain)
                )

                routes += "$route$separator$queryName=$encoded"
            }
        }

        return routes.toList()
    }

    private fun searchPageRoute(
        route: String,
        page: Int
    ): String {
        if (page <= 1) return route

        return if (route.contains("?")) {
            "$route&page=$page"
        } else {
            "$route?page=$page"
        }
    }

    private fun rankSearchResults(
        original: String,
        items: List<SiteItem>
    ): List<SearchResponse> {
        return items
            .map { item ->
                item to searchScore(original, item.title)
            }
            .filter { it.second >= NORMAL_SEARCH_SCORE }
            .sortedWith(
                compareByDescending<Pair<SiteItem, Double>> { it.second }
                    .thenBy {
                        it.first.title.lowercase(Locale.ROOT)
                    }
            )
            .take(SEARCH_RESULT_LIMIT)
            .map { it.first.toSearchResponse() }
    }

    private suspend fun fetchSitemapUrls(
        domain: String
    ): List<String> {
        val sitemapCandidates = listOf(
            "/sitemap.xml",
            "/sitemap_index.xml",
            "/sitemap-index.xml"
        )

        val discovered = linkedSetOf<String>()

        for (rootPath in sitemapCandidates) {
            val response = try {
                app.get(
                    domain + rootPath,
                    headers = browserHeaders(domain + "/")
                )
            } catch (_: Throwable) {
                continue
            }

            if (response.code !in 200..399) continue

            val document = response.document

            /*
             * Standard XML sitemap:
             *   <loc>https://host/path</loc>
             */
            document.select("loc").forEach { loc ->
                val value = loc.text().trim()
                if (!isHttpUrl(value)) return@forEach

                val path = pathFromUrl(value)
                if (isContentPath(path)) {
                    discovered += normalizePath(path)
                } else if (
                    path.endsWith(".xml", true) ||
                    path.contains("sitemap", true)
                ) {
                    /*
                     * A sitemap index may point to child sitemaps. We collect
                     * those paths and fetch them below.
                     */
                    discovered += "@@SITEMAP@@$path"
                }
            }

            if (discovered.isNotEmpty()) break
        }

        val nested = discovered
            .filter { it.startsWith("@@SITEMAP@@") }
            .map { it.removePrefix("@@SITEMAP@@") }

        val result = linkedSetOf<String>()
        result.addAll(
            discovered.filterNot { it.startsWith("@@SITEMAP@@") }
        )

        for (nestedPath in nested.take(20)) {
            val response = try {
                app.get(
                    domain + normalizePath(nestedPath),
                    headers = browserHeaders(domain + "/")
                )
            } catch (_: Throwable) {
                continue
            }

            if (response.code !in 200..399) continue

            response.document.select("loc").forEach { loc ->
                val value = loc.text().trim()
                if (!isHttpUrl(value)) return@forEach

                val path = pathFromUrl(value)
                if (isContentPath(path)) {
                    result += normalizePath(path)
                }
            }

            if (result.size >= SEARCH_SITEMAP_LIMIT) break
        }

        return result.take(SEARCH_SITEMAP_LIMIT)
    }

    private fun discoverGlobalSearchCandidates(
        domain: String,
        query: String,
        sitemapUrls: List<String>
    ): List<String> {
        val normalizedQuery = normalizeSearch(query)
        if (normalizedQuery.isBlank()) return emptyList()

        return sitemapUrls
            .asSequence()
            .filter { isContentPath(it) }
            .map { path ->
                val slug = normalizeSearch(
                    path.substringAfterLast('/')
                )
                path to searchScore(normalizedQuery, slug)
            }
            .filter { it.second >= NORMAL_SEARCH_SCORE }
            .sortedByDescending { it.second }
            .map { it.first }
            .distinct()
            .toList()
    }

    private fun parseDetailAsSearchItem(
        path: String,
        document: Document
    ): SiteItem? {
        val title = cleanTitle(
            parsePlayerJson(document)
                ?.optString("title")
                ?.trim()
                .takeUnless { it.isNullOrBlank() }
                ?: document.selectFirst("h1")?.text()?.trim()
                ?: document.selectFirst(
                    "meta[property=og:title]"
                )?.attr("content")?.trim()
                ?: document.title().substringBefore("•").trim()
        )

        if (title.isBlank()) return null

        val poster = firstUsefulUrl(
            parsePlayerJson(document)
                ?.optString("poster"),
            document.selectFirst(
                "meta[property=og:image]"
            )?.attr("content"),
            document.selectFirst(
                "meta[name=twitter:image]"
            )?.attr("content")
        )

        val lowerPath = path.lowercase(Locale.ROOT)
        val type = when {
            lowerPath.startsWith("/anime/") -> TvType.Anime
            lowerPath.startsWith("/series/") ||
                lowerPath.startsWith("/drama/") -> TvType.TvSeries
            else -> TvType.Movie
        }

        return SiteItem(
            title = title,
            url = canonicalPrimaryUrl(path),
            poster = poster,
            type = type
        )
    }

    private fun buildSearchVariants(query: String): LinkedHashSet<String> {
        val normalized = normalizeSearch(query)
        val compact = normalized.replace(" ", "")

        return linkedSetOf<String>().apply {
            add(query)
            if (normalized.isNotBlank()) add(normalized)

            /*
             * Remove very common metadata terms so searches like
             * "Mirzapur movie 2026 hd" still find the actual title.
             */
            val reduced = normalized
                .split(' ')
                .filterNot {
                    it in setOf(
                        "movie",
                        "movies",
                        "film",
                        "films",
                        "series",
                        "season",
                        "episode",
                        "ep",
                        "hd",
                        "hdtc",
                        "web",
                        "webdl",
                        "webrip",
                        "bluray",
                        "dual",
                        "audio"
                    )
                }
                .joinToString(" ")

            if (reduced.isNotBlank()) add(reduced)
            if (compact.isNotBlank()) add(compact)
        }
    }

    private fun searchScore(
        query: String,
        title: String
    ): Double {
        val q = normalizeSearch(query)
        val t = normalizeSearch(title)

        if (q.isBlank() || t.isBlank()) return 0.0
        if (q == t) return 1.0

        val qCompact = q.replace(" ", "")
        val tCompact = t.replace(" ", "")

        var score = 0.0

        if (t.contains(q)) {
            score = max(score, 0.98)
        }

        if (
            qCompact.isNotBlank() &&
            tCompact.contains(qCompact)
        ) {
            val ratio = qCompact.length.toDouble() /
                tCompact.length.coerceAtLeast(1).toDouble()

            score = max(
                score,
                0.88 + min(0.10, ratio * 0.10)
            )
        }

        val qTokens = q.split(' ').filter { it.length >= 2 }
        val tTokens = t.split(' ').filter { it.length >= 2 }

        if (qTokens.isEmpty() || tTokens.isEmpty()) {
            return score.coerceIn(0.0, 1.0)
        }

        /*
         * Each query word finds its best matching title word.
         * This makes word order irrelevant.
         */
        val tokenScores = qTokens.map { queryToken ->
            tTokens.maxOfOrNull { titleToken ->
                when {
                    queryToken == titleToken -> 1.0

                    titleToken.startsWith(queryToken) ||
                        queryToken.startsWith(titleToken) -> 0.93

                    titleToken.contains(queryToken) ||
                        queryToken.contains(titleToken) -> 0.88

                    else -> stringSimilarity(queryToken, titleToken)
                }
            } ?: 0.0
        }

        val averageTokenScore = tokenScores.average()
        score = max(score, averageTokenScore * 0.94)

        /*
         * Coverage bonus: reward searches where most query words were found
         * reasonably well.
         */
        val covered = tokenScores.count { it >= 0.60 }
        if (qTokens.isNotEmpty()) {
            val coverage = covered.toDouble() / qTokens.size.toDouble()
            score = max(score, 0.50 + coverage * 0.45)
        }

        /*
         * Year-aware bonus.
         */
        val queryYear = Regex("\\b(19|20)\\d{2}\\b")
            .find(q)
            ?.value
            ?.toIntOrNull()

        if (queryYear != null) {
            val titleYear = Regex("\\b(19|20)\\d{2}\\b")
                .find(t)
                ?.value
                ?.toIntOrNull()

            when {
                titleYear == queryYear -> score = max(score, 0.97)
                titleYear != null -> score *= 0.92
            }
        }

        return score.coerceIn(0.0, 1.0)
    }

    private fun stringSimilarity(a: String, b: String): Double {
        if (a == b) return 1.0
        if (a.isBlank() || b.isBlank()) return 0.0

        val distance = levenshtein(a, b)
        val longest = max(a.length, b.length)

        return if (longest == 0) {
            1.0
        } else {
            1.0 - distance.toDouble() / longest.toDouble()
        }
    }

    private fun levenshtein(a: String, b: String): Int {
        if (a == b) return 0
        if (a.isEmpty()) return b.length
        if (b.isEmpty()) return a.length

        var previous = IntArray(b.length + 1) { it }
        var current = IntArray(b.length + 1)

        for (i in a.indices) {
            current[0] = i + 1

            for (j in b.indices) {
                val cost = if (a[i] == b[j]) 0 else 1

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

        return previous[b.length]
    }

    // ---------------------------------------------------------------------
    // DETAIL / EPISODES
    // ---------------------------------------------------------------------

    override suspend fun load(url: String): LoadResponse? {
        val path = pathFromUrl(url)
        val result = getDocumentWithFallback(path) ?: return null
        val document = result.document
        val json = parsePlayerJson(document)

        val title = cleanTitle(
            json?.optString("title")
                ?.trim()
                .takeUnless { it.isNullOrBlank() }
                ?: document.selectFirst("h1")?.text()?.trim()
                ?: document.title().substringBefore("•").trim()
        ).ifBlank {
            "Movie Link BD"
        }

        val poster = firstUsefulUrl(
            json?.optString("poster"),
            document
                .selectFirst("meta[property=og:image]")
                ?.attr("content"),
            document
                .selectFirst("meta[name=twitter:image]")
                ?.attr("content"),
            document.selectFirst(".movie-card img")
                ?.let(::extractImageUrl)
        )

        val contentType = json
            ?.optString("content_type")
            ?.trim()
            ?.lowercase(Locale.ROOT)
            ?.takeIf { it.isNotBlank() }
            ?: detectContentType(path, document)

        if (contentType == "movie" || path.startsWith("/movie/")) {
            return newMovieLoadResponse(
                title,
                canonicalPrimaryUrl(path),
                TvType.Movie,
                canonicalPrimaryUrl(path)
            ) {
                posterUrl = poster
                plot = extractPlot(document)
                year = extractYear(title, document)
            }
        }

        val tvType = if (
            contentType == "anime" ||
            path.startsWith("/anime/", true)
        ) {
            TvType.Anime
        } else {
            TvType.TvSeries
        }

        val episodes = parseEpisodes(
            json,
            canonicalPrimaryUrl(path)
        )

        return newTvSeriesLoadResponse(
            title,
            canonicalPrimaryUrl(path),
            tvType,
            episodes
        ) {
            posterUrl = poster
            plot = extractPlot(document)
            year = extractYear(title, document)
        }
    }

    // ---------------------------------------------------------------------
    // LINKS / FRESH TOKEN RESOLUTION
    // ---------------------------------------------------------------------

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val separator = data.indexOf("||")

        val pageUrl = if (separator >= 0) {
            data.substring(0, separator)
        } else {
            data
        }

        val episodeId = if (separator >= 0) {
            data.substring(separator + 2).trim()
        } else {
            null
        }

        val path = pathFromUrl(pageUrl)
        if (path.isBlank()) return false

        /*
         * Playback failover is deliberately sequential.
         *
         * .tv -> fresh playable source found -> STOP
         * .tv -> no playable source        -> try .li
         * .li -> no playable source        -> try .one
         *
         * We never collect sources from multiple mirrors for one Play action.
         * The playable URL is always obtained from a fresh page request.
         */
        for ((mirrorIndex, domain) in DOMAINS.withIndex()) {
            val page = fetchPlaybackPage(
                domain = domain,
                path = path
            ) ?: continue

            val extractedSources = extractPlayableSourcesForEpisode(
                page = page,
                episodeId = episodeId,
                mirrorIndex = mirrorIndex
            )

            // A URL is not considered successful merely because it looks like
            // media. Verify the fresh URL against the current mirror first.
            // If every candidate fails, the next mirror gets its own fresh
            // chance.
            val freshSources = extractedSources.filter { source ->
                isPlayableSource(source)
            }

            if (freshSources.isEmpty()) {
                continue
            }

            /*
             * Only this mirror is emitted. As soon as one mirror gives a valid
             * source, later mirrors are not queried.
             */
            emitPlayableSources(
                sources = freshSources,
                callback = callback
            )

            emitFreshSubtitles(
                pages = listOf(page),
                episodeId = episodeId,
                subtitleCallback = subtitleCallback
            )

            return true
        }

        return false
    }

    private suspend fun fetchPlaybackPage(
        domain: String,
        path: String
    ): PageResult? {
        val normalized = normalizePath(path)
        val pageUrl = domain + normalized

        return try {
            /*
             * no-cache headers make every Play attempt re-read the current
             * player page instead of reusing an expired tokenized response.
             */
            val response = app.get(
                pageUrl,
                headers = browserHeaders(domain + "/")
            )

            if (response.code !in 200..399) {
                null
            } else {
                val document = response.document

                if (!isUsablePlaybackDocument(document)) {
                    null
                } else {
                    PageResult(
                        domain = domain,
                        path = normalized,
                        document = document
                    )
                }
            }
        } catch (_: Throwable) {
            null
        }
    }

    private fun extractPlayableSourcesForEpisode(
        page: PageResult,
        episodeId: String?,
        mirrorIndex: Int
    ): List<FreshSource> {
        val json = parsePlayerJson(page.document)

        /*
         * First choice: the current player JSON, because that is where
         * MovieLinkBD publishes the current tokenized source.
         */
        if (json != null) {
            val jsonSources = extractJsonSources(
                json = json,
                episodeId = episodeId,
                page = page,
                mirrorIndex = mirrorIndex
            )

            if (jsonSources.isNotEmpty()) {
                return jsonSources
                    .distinctBy { sourceDedupKey(it) }
            }
        }

        /*
         * Defensive fallback for markup revisions that expose the current
         * playable URL directly in the document.
         */
        return extractFallbackMediaSources(
            document = page.document,
            baseUrl = page.absoluteUrl(),
            mirrorIndex = mirrorIndex
        ).filter { source ->
            episodeId.isNullOrBlank() ||
                episodeId == source.sourceName ||
                source.sourceName.isBlank()
        }
    }

    private suspend fun isPlayableSource(
        source: FreshSource
    ): Boolean {
        return try {
            val response = app.get(
                source.streamUrl,
                headers = mapOf(
                    "User-Agent" to (
                        "Mozilla/5.0 (Linux; Android 13; Mobile) " +
                            "AppleWebKit/537.36 (KHTML, like Gecko) " +
                            "Chrome/131.0.0.0 Mobile Safari/537.36"
                    ),
                    "Accept" to "*/*",
                    "Range" to "bytes=0-1",
                    "Referer" to source.referer,
                    "Cache-Control" to "no-cache, no-store, max-age=0",
                    "Pragma" to "no-cache"
                )
            )

            response.code in 200..399
        } catch (_: Throwable) {
            false
        }
    }

    private suspend fun emitPlayableSources(
        sources: List<FreshSource>,
        callback: (ExtractorLink) -> Unit
    ) {
        for (source in sources.distinctBy { sourceDedupKey(it) }) {
            val linkName = buildString {
                append(source.provider)

                if (
                    source.quality > 0 &&
                    source.quality != Qualities.Unknown.value
                ) {
                    append(" - ${source.quality}p")
                }

                if (source.audio.isNotBlank()) {
                    append(" - ${source.audio}")
                }
            }

            callback(
                newExtractorLink(
                    name,
                    linkName,
                    source.streamUrl,
                    ExtractorLinkType.VIDEO
                ) {
                    this.quality = source.quality
                    this.referer = source.referer
                }
            )
        }
    }

    private fun extractJsonSources(
        json: JSONObject,
        episodeId: String?,
        page: PageResult,
        mirrorIndex: Int
    ): List<FreshSource> {
        val episodes = json.optJSONArray("episodes")
            ?: return emptyList()

        val output = ArrayList<FreshSource>()

        for (index in 0 until episodes.length()) {
            val episode = episodes.optJSONObject(index) ?: continue

            if (
                !episodeId.isNullOrBlank() &&
                episode.optString("id") != episodeId
            ) {
                continue
            }

            /*
             * For a normal movie, the JSON contains a single kind=movie
             * episode. For series, episodeId narrows it to one episode.
             */
            val sources = episode.optJSONArray("sources") ?: continue

            for (sourceIndex in 0 until sources.length()) {
                val source = sources.optJSONObject(sourceIndex) ?: continue

                val streamUrl = source.optString("url").trim()
                if (!isHttpUrl(streamUrl)) continue

                val provider = source.optString("provider")
                    .trim()
                    .ifBlank { "MLBD CDN" }

                val audio = source.optString("audio")
                    .trim()
                    .ifBlank {
                        source.optJSONArray("audio_languages")?.let { array ->
                            buildString {
                                for (i in 0 until array.length()) {
                                    val language = array.optString(i).trim()
                                    if (language.isBlank()) continue
                                    if (isNotEmpty()) append(", ")
                                    append(language)
                                }
                            }
                        }.orEmpty()
                    }

                val quality = parseQuality(
                    source.opt("quality"),
                    source.optString("name")
                )

                val sourceName = episode.optString("id")
                    .trim()
                    .ifBlank {
                        episode.optString("label")
                            .trim()
                            .ifBlank { "episode-$index" }
                    }

                output += FreshSource(
                    streamUrl = streamUrl,
                    quality = quality,
                    provider = provider,
                    audio = audio,
                    referer = page.absoluteUrl(),
                    mirrorIndex = mirrorIndex,
                    sourceName = sourceName
                )
            }
        }

        return output
    }

    private fun sourceDedupKey(
        source: FreshSource
    ): String {
        /*
         * Keep URLs distinct because tokenized URLs can represent different
         * fresh sessions. The same exact URL from two mirrors only needs one
         * emitted candidate.
         */
        return source.streamUrl.trim()
    }

    private suspend fun emitFreshSubtitles(
        pages: List<PageResult>,
        episodeId: String?,
        subtitleCallback: (SubtitleFile) -> Unit
    ) {
        val emitted = linkedSetOf<String>()

        for (page in pages) {
            val json = parsePlayerJson(page.document) ?: continue
            val episodes = json.optJSONArray("episodes") ?: continue

            for (i in 0 until episodes.length()) {
                val episode = episodes.optJSONObject(i) ?: continue

                if (
                    !episodeId.isNullOrBlank() &&
                    episode.optString("id") != episodeId
                ) {
                    continue
                }

                val sources = episode.optJSONArray("sources") ?: continue

                for (j in 0 until sources.length()) {
                    val source = sources.optJSONObject(j) ?: continue
                    val subs = source.optJSONArray("external_subtitles")
                        ?: continue

                    for (k in 0 until subs.length()) {
                        val subtitle = subs.optJSONObject(k) ?: continue
                        val url = subtitle.optString("url").trim()

                        if (!isHttpUrl(url)) continue
                        if (!emitted.add(url)) continue

                        val language = subtitle.optString("label")
                            .trim()
                            .ifBlank {
                                subtitle.optString("language").trim()
                            }
                            .ifBlank { "Subtitle" }

                        subtitleCallback(
                            newSubtitleFile(language, url)
                        )
                    }
                }
            }
        }
    }

    private fun extractFallbackMediaSources(
        document: Document,
        baseUrl: String,
        mirrorIndex: Int
    ): List<FreshSource> {
        val found = linkedSetOf<String>()

        fun add(raw: String?) {
            if (raw.isNullOrBlank()) return

            val clean = raw
                .trim()
                .replace("\\/", "/")
                .replace("\\u0026", "&")
                .replace("&amp;", "&")
                .trim('"', '\'', '`', ',', ';', ')', ']', '}')

            if (!isHttpUrl(clean)) return
            found.add(clean)
        }

        /*
         * DOM-based fallback.
         */
        document.select(
            "video[src], video source[src], source[src], " +
                "[data-src], [data-video], [data-file], [data-url], " +
                "[data-source], [data-stream], [data-manifest]"
        ).forEach { element ->
            add(element.attr("src"))
            add(element.attr("data-src"))
            add(element.attr("data-video"))
            add(element.attr("data-file"))
            add(element.attr("data-url"))
            add(element.attr("data-source"))
            add(element.attr("data-stream"))
            add(element.attr("data-manifest"))
        }

        /*
         * Raw HTML fallback for the site's known CDN host and generic media
         * URLs. We do not extract advertising iframe URLs.
         */
        val html = document.html()
            .replace("\\/", "/")
            .replace("\\u0026", "&")
            .replace("&amp;", "&")

        val mediaRegex = Regex(
            """https?://[^"'<>\s\\]+(?:\.m3u8|\.mp4|\.mkv|\.webm|\.mov|\.m4v|\.avi|\.flv|\.ts)(?:\?[^"'<>\s\\]*)?""",
            RegexOption.IGNORE_CASE
        )

        mediaRegex.findAll(html).forEach {
            add(it.value)
        }

        return found.map { url ->
            FreshSource(
                streamUrl = url,
                quality = getQualityFromName(url),
                provider = "MLBD Direct",
                audio = "",
                referer = baseUrl,
                mirrorIndex = mirrorIndex,
                sourceName = ""
            )
        }
    }

    // ---------------------------------------------------------------------
    // CARD PARSING / POSTERS
    // ---------------------------------------------------------------------

    private fun parseCard(card: Element): SiteItem? {
        val titleElement = card.selectFirst(
            ".content a.title, " +
                "a.title, " +
                ".content .title, " +
                "h2.title, " +
                "h3.title"
        )

        val href = firstNonBlank(
            titleElement?.attr("href"),
            card.selectFirst(
                "a[href*='/movie/'], " +
                    "a[href*='/series/'], " +
                    "a[href*='/drama/'], " +
                    "a[href*='/anime/']"
            )?.attr("href")
        ) ?: return null

        val title = firstNonBlank(
            titleElement?.text(),
            card.selectFirst("img")?.attr("alt"),
            card.selectFirst("img")?.attr("title")
        )?.let(::cleanTitle)
            ?.takeIf { it.isNotBlank() }
            ?: return null

        val url = absolutePrimary(href)
        val path = pathFromUrl(url)

        if (!isContentPath(path)) return null

        val poster = extractCardPoster(card)

        val type = when {
            path.startsWith("/anime/", true) -> TvType.Anime
            path.startsWith("/series/", true) -> TvType.TvSeries
            path.startsWith("/drama/", true) -> TvType.TvSeries
            else -> TvType.Movie
        }

        return SiteItem(
            title = title,
            url = url,
            poster = poster,
            type = type
        )
    }

    private fun extractCardPoster(card: Element): String? {
        val pictureSource = card.selectFirst(
            "picture source[srcset], " +
                "picture source[data-srcset]"
        )

        val picturePoster = firstNonBlank(
            pictureSource?.attr("data-srcset"),
            pictureSource?.attr("srcset")
        )
            ?.split(',')
            ?.firstOrNull()
            ?.trim()
            ?.substringBefore(' ')

        val image = card.selectFirst(
            ".image-container img, img"
        )

        /*
         * MovieLinkBD currently publishes the real image in data-src while
         * src points to /images/mlbd_load.svg.
         */
        val raw = firstNonBlank(
            image?.attr("data-src"),
            image?.attr("data-lazy-src"),
            image?.attr("data-original"),
            image?.attr("data-image"),
            image?.attr("data-poster"),
            image?.attr("data-url"),
            image?.attr("srcset"),
            picturePoster,
            image?.attr("src")
        ) ?: return null

        /*
         * If srcset was selected, keep only the actual URL portion.
         */
        val cleanedRaw = raw
            .trim()
            .split(',')
            .firstOrNull()
            ?.trim()
            ?.substringBefore(' ')
            ?: raw

        val resolved = absoluteResourceUrl(cleanedRaw)

        if (isPlaceholderImage(resolved)) {
            /*
             * One more pass over all known image attributes. This protects
             * against markup variants where the first candidate is the
             * placeholder.
             */
            val alternatives = listOf(
                image?.attr("data-src"),
                image?.attr("data-lazy-src"),
                image?.attr("data-original"),
                image?.attr("data-image"),
                image?.attr("data-poster"),
                picturePoster
            )

            for (candidate in alternatives) {
                val alternative = candidate
                    ?.trim()
                    ?.split(',')
                    ?.firstOrNull()
                    ?.trim()
                    ?.substringBefore(' ')
                    ?: continue

                val alternativeResolved =
                    absoluteResourceUrl(alternative)

                if (
                    alternativeResolved.isNotBlank() &&
                    !isPlaceholderImage(alternativeResolved)
                ) {
                    return alternativeResolved
                }
            }

            return null
        }

        return resolved
    }

    private fun recentlyUpdatedCards(
        document: Document
    ): List<Element> {
        val heading = document
            .select(".mlbd-page-head")
            .firstOrNull {
                it.selectFirst("h1")
                    ?.text()
                    ?.trim()
                    ?.equals(
                        "RECENTLY UPDATED",
                        ignoreCase = true
                    ) == true
            }

        if (heading != null) {
            var node = heading.nextElementSibling()
            var steps = 0

            while (node != null && steps < 6) {
                val cards = node.select(".movie-card")
                if (cards.isNotEmpty()) return cards

                node = node.nextElementSibling()
                steps++
            }
        }

        return document.select(
            ".movie-cards-container .movie-card"
        )
    }

    // ---------------------------------------------------------------------
    // PRIORITY CACHE
    // ---------------------------------------------------------------------

    private suspend fun rebuildPriorityIndexInBackground() {
        val now = System.currentTimeMillis()

        synchronized(this) {
            if (
                now - priorityCacheAt < PRIORITY_CACHE_MS &&
                (
                    recentlyKeys.isNotEmpty() ||
                    ongoingKeys.isNotEmpty() ||
                    dualAudioKeys.isNotEmpty()
                )
            ) {
                return
            }
        }

        /*
         * Full indexing is intentionally done in the background. It can be
         * expensive on a large site, but it must never block the first Home
         * render.
         *
         * Recently, Ongoing and Dual Audio are indexed independently. Their
         * sets are never merged into one another, because those three sections
         * are allowed to overlap.
         */
        val recentDeferred = priorityScope.async {
            collectAllCategoryKeys(
                baseRoute = null,
                recently = true
            )
        }

        val ongoingDeferred = priorityScope.async {
            collectAllCategoryKeys(
                baseRoute = "/ongoing",
                recently = false
            )
        }

        val dualDeferred = priorityScope.async {
            collectAllCategoryKeys(
                baseRoute = "/language/dual-audio",
                recently = false
            )
        }

        val recent = recentDeferred.await()
        val ongoing = ongoingDeferred.await()
        val dual = dualDeferred.await()

        synchronized(this) {
            recentlyKeys.clear()
            recentlyKeys.addAll(recent)

            ongoingKeys.clear()
            ongoingKeys.addAll(ongoing)

            dualAudioKeys.clear()
            dualAudioKeys.addAll(dual)

            priorityCacheAt = System.currentTimeMillis()
        }
    }

    private suspend fun collectAllCategoryKeys(
        baseRoute: String?,
        recently: Boolean
    ): Set<String> {
        val keys = linkedSetOf<String>()
        var page = 1

        while (page <= PRIORITY_SCAN_MAX_PAGES) {
            val route = if (recently && page == 1) {
                "/"
            } else {
                pageRoute(baseRoute ?: "/", page)
            }

            val result = getDocumentWithFallback(route) ?: break

            val cards = if (recently && page == 1) {
                recentlyUpdatedCards(result.document)
            } else {
                result.document.select(
                    ".movie-cards-container .movie-card"
                )
            }

            val parsed = cards.mapNotNull(::parseCard)
            if (parsed.isEmpty()) break

            parsed.forEach { item ->
                keys += contentKey(item.url)
            }

            if (
                !hasNextPage(result.document, page) &&
                parsed.size < HOME_LIMIT
            ) {
                break
            }

            page++
        }

        return keys
    }

    // ---------------------------------------------------------------------
    // NETWORK / MIRROR FAILOVER
    // ---------------------------------------------------------------------

    private suspend fun getDocumentWithFallback(
        path: String
    ): PageResult? {
        val normalized = normalizePath(path)

        for (domain in DOMAINS) {
            val url = domain + normalized

            try {
                val response = app.get(
                    url,
                    headers = browserHeaders(domain + "/")
                )

                if (response.code !in 200..399) continue

                val document = response.document

                if (
                    isUsableDocument(
                        normalized,
                        document
                    )
                ) {
                    return PageResult(
                        domain,
                        normalized,
                        document
                    )
                }
            } catch (_: Throwable) {
                // Exact same path goes to the next mirror.
            }
        }

        return null
    }

    private fun browserHeaders(
        referer: String
    ): Map<String, String> = mapOf(
        "User-Agent" to (
            "Mozilla/5.0 (Linux; Android 13; Mobile) " +
                "AppleWebKit/537.36 (KHTML, like Gecko) " +
                "Chrome/131.0.0.0 Mobile Safari/537.36"
            ),
        "Accept" to (
            "text/html,application/xhtml+xml," +
                "application/xml;q=0.9,*/*;q=0.8"
            ),
        "Accept-Language" to "en-US,en;q=0.9",
        "Cache-Control" to "no-cache, no-store, max-age=0",
        "Pragma" to "no-cache",
        "Referer" to referer
    )

    private fun isUsableDocument(
        path: String,
        document: Document
    ): Boolean {
        if (path == "/") {
            return document.selectFirst(
                ".movie-cards-container, " +
                    ".mlbd-page-head, " +
                    "body"
            ) != null
        }

        return document.selectFirst(
            "#mlbdInlinePlayerData, " +
                ".movie-cards-container .movie-card, " +
                ".movie-cards-container, " +
                "video, source"
        ) != null
    }

    private fun isUsablePlaybackDocument(
        document: Document
    ): Boolean {
        return document.selectFirst(
            "#mlbdInlinePlayerData, " +
                "video, " +
                "video source, " +
                "source[src]"
        ) != null
    }

    // ---------------------------------------------------------------------
    // JSON / EPISODES
    // ---------------------------------------------------------------------

    private fun parsePlayerJson(
        document: Document
    ): JSONObject? {
        val script = document.selectFirst(
            PLAYER_JSON_SELECTOR
        ) ?: return null

        val raw = script.data()
            .ifBlank { script.html() }
            .trim()

        if (raw.isBlank()) return null

        return runCatching {
            JSONObject(raw)
        }.getOrNull()
    }

    private fun parseEpisodes(
        json: JSONObject?,
        pageUrl: String
    ): List<Episode> {
        val array = json?.optJSONArray("episodes")
            ?: return emptyList()

        val result = arrayListOf<Episode>()

        for (i in 0 until array.length()) {
            val episode = array.optJSONObject(i) ?: continue

            if (
                episode.optString("kind")
                    .equals("movie", true)
            ) {
                continue
            }

            val id = episode.optString("id").trim()
            if (id.isBlank()) continue

            val label = episode
                .optString("label")
                .trim()
                .ifBlank {
                    "Episode ${i + 1}"
                }

            val number = episode
                .optInt("number", 0)
                .takeIf { it > 0 }
                ?: extractEpisodeNumber(
                    label,
                    i + 1
                )

            val season = episode
                .optInt("season", 0)
                .takeIf { it > 0 }

            result += newEpisode(
                "$pageUrl||$id"
            ) {
                this.name = label
                this.episode = number
                this.season = season
            }
        }

        return result.sortedWith(
            compareBy<Episode> {
                it.season ?: Int.MAX_VALUE
            }.thenBy {
                it.episode ?: Int.MAX_VALUE
            }
        )
    }

    // ---------------------------------------------------------------------
    // UTILITY / URL
    // ---------------------------------------------------------------------

    private fun pageRoute(
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

    private fun hasNextPage(
        document: Document,
        currentPage: Int
    ): Boolean {
        if (
            document.select(".movie-card").size >=
                HOME_LIMIT
        ) {
            return true
        }

        return document
            .select("a[href]")
            .any { anchor ->
                val text = anchor
                    .text()
                    .trim()
                    .lowercase(Locale.ROOT)

                val rel = anchor
                    .attr("rel")
                    .trim()
                    .lowercase(Locale.ROOT)

                val href = anchor.attr("href")

                text == "next" ||
                    text.contains("next") ||
                    rel == "next" ||
                    anchor
                        .attr("aria-label")
                        .contains("next", true) ||
                    href.contains(
                        "page=${currentPage + 1}",
                        true
                    ) ||
                    href.contains(
                        "/page/${currentPage + 1}",
                        true
                    )
            }
    }

    private fun detectContentType(
        path: String,
        document: Document
    ): String {
        return when {
            path.startsWith("/movie/", true) -> "movie"
            path.startsWith("/anime/", true) -> "anime"
            path.startsWith("/series/", true) -> "series"
            path.startsWith("/drama/", true) -> "series"

            document.selectFirst(
                "#mlbdEpisodeDownloadGrid, .ep-card"
            ) != null -> "series"

            else -> "movie"
        }
    }

    private fun isContentPath(
        path: String
    ): Boolean {
        val lower = path.lowercase(Locale.ROOT)

        return lower.startsWith("/movie/") ||
            lower.startsWith("/series/") ||
            lower.startsWith("/drama/") ||
            lower.startsWith("/anime/")
    }

    private fun contentKey(
        url: String
    ): String {
        return pathFromUrl(url)
            .substringBefore('#')
            .substringBefore('?')
            .removeSuffix("/")
            .lowercase(Locale.ROOT)
    }

    private fun pathFromUrl(
        url: String
    ): String {
        return runCatching {
            val uri = URI(url)

            buildString {
                append(
                    uri.rawPath.ifBlank {
                        "/"
                    }
                )

                if (
                    !uri.rawQuery
                        .isNullOrBlank()
                ) {
                    append("?")
                    append(uri.rawQuery)
                }
            }.let(::normalizePath)
        }.getOrElse {
            val noScheme = url
                .substringAfter("://", url)

            val slash = noScheme.indexOf('/')

            normalizePath(
                if (slash >= 0) {
                    noScheme.substring(slash)
                } else {
                    "/"
                }
            )
        }
    }

    private fun normalizePath(
        path: String
    ): String {
        val clean = path.trim()

        if (clean.isBlank()) return "/"

        if (
            clean.startsWith("http://", true) ||
            clean.startsWith("https://", true)
        ) {
            return pathFromUrl(clean)
        }

        return if (
            clean.startsWith("/")
        ) {
            clean
        } else {
            "/$clean"
        }
    }

    private fun absolutePrimary(
        href: String
    ): String {
        if (
            href.startsWith("http://", true) ||
            href.startsWith("https://", true)
        ) {
            return href
        }

        return PRIMARY + normalizePath(href)
    }

    private fun canonicalPrimaryUrl(
        path: String
    ): String = absolutePrimary(
        normalizePath(path)
    )

    private fun absoluteResourceUrl(
        value: String
    ): String {
        val clean = value
            .trim()
            .replace("&amp;", "&")
            .replace("\\/", "/")

        if (clean.isBlank()) return clean

        if (
            clean.startsWith("http://", true) ||
            clean.startsWith("https://", true)
        ) {
            return clean
        }

        if (clean.startsWith("//")) {
            return "https:$clean"
        }

        return runCatching {
            URI(PRIMARY + "/")
                .resolve(clean)
                .toString()
        }.getOrElse {
            absolutePrimary(clean)
        }
    }

    // ---------------------------------------------------------------------
    // POSTER / METADATA
    // ---------------------------------------------------------------------

    private fun firstUsefulUrl(
        vararg values: String?
    ): String? {
        for (value in values) {
            if (value.isNullOrBlank()) continue

            val resolved = absoluteResourceUrl(value)

            if (resolved.isBlank()) continue
            if (isPlaceholderImage(resolved)) continue

            return resolved
        }

        return null
    }

    private fun extractImageUrl(
        element: Element
    ): String? {
        return firstUsefulUrl(
            element.attr("data-src"),
            element.attr("data-lazy-src"),
            element.attr("data-original"),
            element.attr("data-image"),
            element.attr("data-poster"),
            element.attr("src")
        )
    }

    private fun isPlaceholderImage(
        url: String
    ): Boolean {
        val lower = url.lowercase(Locale.ROOT)

        return lower.endsWith("mlbd_load.svg") ||
            lower.contains(
                "/images/mlbd_load.svg"
            ) ||
            lower.contains("placeholder") ||
            lower.contains("spacer.gif") ||
            lower.startsWith("data:image/")
    }

    private fun extractPlot(
        document: Document
    ): String? {
        val selectors = listOf(
            ".story-text",
            ".storyline-box .story-text",
            ".movie-extra-info",
            ".description",
            ".plot",
            "meta[name=description]"
        )

        for (selector in selectors) {
            val element = document
                .selectFirst(selector)
                ?: continue

            val text = if (
                element
                    .tagName()
                    .equals("meta", true)
            ) {
                element.attr("content")
            } else {
                element.text()
            }.trim()

            if (text.isNotBlank()) {
                return text
            }
        }

        return null
    }

    private fun extractYear(
        title: String,
        document: Document
    ): Int? {
        val texts = buildList {
            add(title)

            document
                .select(
                    "meta[property=og:title], h1, title"
                )
                .forEach {
                    add(
                        it.attr("content")
                            .ifBlank { it.text() }
                    )
                }
        }

        return texts
            .asSequence()
            .mapNotNull {
                Regex("\\b(19|20)\\d{2}\\b")
                    .find(it)
                    ?.value
                    ?.toIntOrNull()
            }
            .firstOrNull()
    }

    private fun cleanTitle(
        value: String
    ): String {
        return value
            .replace(
                "&nbsp;",
                " ",
                ignoreCase = true
            )
            .replace(
                Regex("\\s+"),
                " "
            )
            .trim()
    }

    // ---------------------------------------------------------------------
    // QUALITY / BASIC SEARCH NORMALIZATION
    // ---------------------------------------------------------------------

    private fun parseQuality(
        raw: Any?,
        name: String
    ): Int {
        when (raw) {
            is Number -> {
                val value = raw.toInt()
                if (value > 0) return value
            }

            is String -> {
                val value = raw.toIntOrNull()

                if (
                    value != null &&
                    value > 0
                ) {
                    return value
                }

                getQualityFromName(raw).let {
                    if (
                        it != Qualities.Unknown.value
                    ) {
                        return it
                    }
                }
            }
        }

        return getQualityFromName(name)
    }

    private fun normalizeSearch(
        value: String
    ): String {
        return java.text.Normalizer
            .normalize(
                value,
                java.text.Normalizer.Form.NFKC
            )
            .lowercase(Locale.ROOT)
            .replace('&', ' ')
            .replace(
                Regex(
                    "[\\u2010-\\u2015\\u2212\\u2043\\u30A0\\u30FC]"
                ),
                "-"
            )
            .replace(
                Regex(
                    "[^a-z0-9\\p{L}]+"
                ),
                " "
            )
            .replace(
                Regex("\\s+"),
                " "
            )
            .trim()
    }

    private fun firstNonBlank(
        vararg values: String?
    ): String? {
        return values
            .firstOrNull {
                !it.isNullOrBlank()
            }
            ?.trim()
    }

    private fun isHttpUrl(
        value: String
    ): Boolean {
        return value.startsWith(
            "http://",
            true
        ) || value.startsWith(
            "https://",
            true
        )
    }

    private fun extractEpisodeNumber(
        text: String,
        fallback: Int
    ): Int {
        return Regex(
            "(?:episode|ep|e)\\s*[-._#]?\\s*(\\d+)",
            RegexOption.IGNORE_CASE
        )
            .find(text)
            ?.groupValues
            ?.getOrNull(1)
            ?.toIntOrNull()
            ?: fallback
    }
}
