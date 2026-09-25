package com.movieflick.mojaloss

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.json.JSONObject
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.Locale

class MojaLoss : MainAPI() {

    override var mainUrl = "https://www.mojaloss.stream"
    override var name = "Moja Loss"
    override var lang = "bn"

    override val hasMainPage = true
    override val hasQuickSearch = true

    override val supportedTypes = setOf(
        TvType.Movie,
        TvType.TvSeries,
        TvType.Anime
    )

    override val mainPage = mainPageOf(
        "mojaloss://recent" to "Recently Released",
        "mojaloss://movies" to "Movies",
        "mojaloss://ott" to "OTT",
        "mojaloss://international" to "International Movies",
        "mojaloss://tv" to "TV Show"
    )

    private data class SiteItem(
        val title: String,
        val url: String,
        val poster: String?,
        val type: TvType
    )

    private data class TvSeason(
        val number: Int,
        val folder: String,
        val episodeFiles: Map<Int, String>,
        val subtitleFiles: Map<Int, String>
    )

    private data class TvConfig(
        val baseUrl: String,
        val mediaToken: String,
        val showFolder: String?,
        val seasons: List<TvSeason>
    )

    private data class EpisodeData(
        val showUrl: String,
        val season: Int,
        val episode: Int,
        val folder: String? = null,
        val filename: String? = null,
        val subtitleFilename: String? = null,
        val baseUrl: String? = null,
        val mediaToken: String? = null,
        val showFolder: String? = null,
        val storedMediaUrl: String? = null
    )

    private data class MovieData(
        val pageUrl: String,
        val defaultSource: String,
        val mediaToken: String? = null,
        val subtitleSource: String? = null,
        val storedMediaUrl: String? = null
    )

    private data class MediaResult(
        val mediaUrl: String,
        val subtitleUrl: String?
    )

    private companion object {
        const val MOVIE_BATCH_SIZE = 30
        const val RECENT_MOVIE_EXCLUSION_LIMIT = 100
        const val RECENT_SCAN_MAX_PAGES = 6
        const val MOVIE_EXTRA_PAGES = 4
    }

    private val ottProviders = listOf(
        "netflix",
        "hbo",
        "hulu",
        "apple",
        "prime",
        "disney"
    )

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        val pageNumber = page.coerceAtLeast(1)

        val items = when (request.data) {
            "mojaloss://recent" -> getPageItems(
                "$mainUrl/page/$pageNumber/"
            )

            "mojaloss://movies" -> getMoviesPageItems(pageNumber)

            "mojaloss://ott" -> getOttPageItems(pageNumber)

            "mojaloss://international" -> getPageItems(
                categoryUrl("international-movies", pageNumber)
            )

            "mojaloss://tv" -> mergeSources(
                listOf(
                    categoryUrl("tv-shows", pageNumber),
                    categoryUrl("tv-shows/korean-tv-shows", pageNumber),
                    categoryUrl("tv-shows/english-tv-shows", pageNumber),
                    categoryUrl("tv-shows/hindi-tv-shows", pageNumber)
                )
            ).map { it.copy(type = TvType.TvSeries) }

            else -> getPageItems(request.data)
        }

        val deduped = linkedMapOf<String, SiteItem>()
        items.forEach { item ->
            deduped.putIfAbsent(canonicalPageKey(item.url), item)
        }

        val finalItems = deduped.values.take(30)

        return newHomePageResponse(
            request,
            list = finalItems.map { it.toSearchResponse() },
            hasNext = items.isNotEmpty() && pageNumber < 500
        )
    }

    private fun categoryUrl(category: String, page: Int): String {
        val base = "$mainUrl/category/$category/"
        return if (page <= 1) base else "$base/page/$page/"
    }

    private suspend fun getMoviesPageItems(page: Int): List<SiteItem> {
        val excluded = getRecentMovieExclusionUrls()
        val result = linkedMapOf<String, SiteItem>()

        var currentPage = page.coerceAtLeast(1)
        var attempts = 0

        while (result.size < MOVIE_BATCH_SIZE && attempts < MOVIE_EXTRA_PAGES) {
            mergeSources(
                listOf(
                    categoryUrl("movies", currentPage),
                    categoryUrl("hindi", currentPage)
                )
            ).forEach { item ->
                if (item.type == TvType.Movie && !excluded.contains(canonicalPageKey(item.url))) {
                    result.putIfAbsent(canonicalPageKey(item.url), item)
                }
            }

            currentPage++
            attempts++
        }

        return result.values.take(MOVIE_BATCH_SIZE)
    }

    private suspend fun getRecentMovieExclusionUrls(): Set<String> {
        val result = linkedSetOf<String>()
        var count = 0

        for (page in 1..RECENT_SCAN_MAX_PAGES) {
            if (count >= RECENT_MOVIE_EXCLUSION_LIMIT) break

            val items = getPageItems("$mainUrl/page/$page/")
            if (items.isEmpty()) break

            for (item in items) {
                if (count >= RECENT_MOVIE_EXCLUSION_LIMIT) break
                if (item.type == TvType.Movie) {
                    result += canonicalPageKey(item.url)
                    count++
                }
            }
        }

        return result
    }

    private suspend fun getOttPageItems(page: Int): List<SiteItem> {
        val result = mutableListOf<SiteItem>()

        ottProviders.forEach { provider ->
            getPageItems(categoryUrl(provider, page))
                .filter { it.type == TvType.Movie || it.type == TvType.TvSeries }
                .take(5)
                .forEach { result += it }
        }

        return result
            .distinctBy { canonicalPageKey(it.url) }
            .take(30)
    }

    private suspend fun mergeSources(sources: List<String>): List<SiteItem> {
        val result = linkedMapOf<String, SiteItem>()
        sources.forEach { source ->
            getPageItems(source).forEach { item ->
                result.putIfAbsent(canonicalPageKey(item.url), item)
            }
        }
        return result.values.toList()
    }

    private fun requestHeaders(referer: String? = null): Map<String, String> = buildMap {
        put(
            "User-Agent",
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) " +
                "AppleWebKit/537.36 (KHTML, like Gecko) " +
                "Chrome/131.0.0.0 Safari/537.36"
        )
        put("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
        put("Accept-Language", "en-US,en;q=0.9,bn;q=0.8")
        put("Cache-Control", "no-cache")
        put("Pragma", "no-cache")
        if (!referer.isNullOrBlank()) put("Referer", referer)
    }

    private suspend fun getPageItems(url: String): List<SiteItem> {
        val document = runCatching {
            app.get(
                url,
                headers = requestHeaders(url),
                timeout = 20_000
            ).document
        }.getOrNull() ?: return emptyList()

        return parseCards(document)
    }

    private fun parseCards(document: Document): List<SiteItem> {
        val result = linkedMapOf<String, SiteItem>()

        document.select("a.mj-fp-card, a.movie-card").forEach { card ->
            val href = card.attr("href").trim()
            if (href.isBlank()) return@forEach

            val url = absoluteUrl(href, document.location())
            if (!isMojaPageUrl(url)) return@forEach

            val title = firstNonBlank(
                card.attr("data-prev-title"),
                card.selectFirst(".mj-fp-card-title")?.text(),
                card.selectFirst(".movie-title")?.text(),
                card.text()
            ).trim()

            if (title.isBlank()) return@forEach

            val typeText = firstNonBlank(
                card.attr("data-prev-type"),
                card.selectFirst(".mj-fp-card-badge, .movie-card-badge")?.text()
            ).lowercase(Locale.ROOT)

            val type = if (
                typeText.contains("tv") || typeText.contains("series")
            ) TvType.TvSeries else TvType.Movie

            result.putIfAbsent(
                canonicalPageKey(url),
                SiteItem(
                    title = title,
                    url = url,
                    poster = extractPoster(card),
                    type = type
                )
            )
        }

        return result.values.toList()
    }

    private fun extractPoster(card: Element): String? {
        val image = card.selectFirst("img")
        val direct = firstNonBlank(
            image?.attr("src"),
            image?.attr("data-src"),
            image?.attr("data-lazy-src")
        )
        if (direct.isNotBlank()) return absoluteUrl(direct, mainUrl)

        val style = card.selectFirst(
            ".mj-fp-card-art, .movie-card-art"
        )?.attr("style").orEmpty()

        return Regex("""url\(['\"]?([^'\")]+)['\"]?\)""")
            .find(style)
            ?.groupValues
            ?.getOrNull(1)
            ?.let { absoluteUrl(it, mainUrl) }
    }

    private fun SiteItem.toSearchResponse(): SearchResponse {
        return if (type == TvType.TvSeries) {
            newTvSeriesSearchResponse(title, url, TvType.TvSeries) {
                posterUrl = poster
            }
        } else {
            newMovieSearchResponse(title, url, type) {
                posterUrl = poster
            }
        }
    }

    override suspend fun search(query: String, page: Int): SearchResponseList {
        val q = query.trim()
        if (q.isBlank()) return newSearchResponseList(emptyList(), false)

        val pool = linkedMapOf<String, SiteItem>()
        for (scanPage in 1..4) {
            val sources = listOf(
                "$mainUrl/page/$scanPage/",
                categoryUrl("movies", scanPage),
                categoryUrl("hindi", scanPage),
                categoryUrl("international-movies", scanPage),
                categoryUrl("tv-shows", scanPage),
                categoryUrl("tv-shows/korean-tv-shows", scanPage),
                categoryUrl("tv-shows/english-tv-shows", scanPage),
                categoryUrl("tv-shows/hindi-tv-shows", scanPage)
            )
            mergeSources(sources).forEach { item ->
                pool.putIfAbsent(canonicalPageKey(item.url), item)
            }
        }

        val ranked = pool.values
            .map { it to searchScore(q, it.title) }
            .filter { it.second >= 0.30 }
            .sortedByDescending { it.second }
            .map { it.first }

        val perPage = 30
        val start = (page.coerceAtLeast(1) - 1) * perPage
        val pageItems = ranked.drop(start).take(perPage).map { it.toSearchResponse() }

        return newSearchResponseList(
            pageItems,
            start + perPage < ranked.size
        )
    }

    private fun searchScore(query: String, title: String): Double {
        val q = normalizeSearchText(query)
        val t = normalizeSearchText(title)
        if (q.isBlank() || t.isBlank()) return 0.0
        if (q == t) return 1.0
        if (t.contains(q)) return 0.95

        val qTokens = q.split(" ").filter { it.length >= 2 }
        val tTokens = t.split(" ").filter { it.length >= 2 }
        if (qTokens.isEmpty() || tTokens.isEmpty()) return 0.0

        return qTokens.map { qt ->
            tTokens.maxOfOrNull { tt ->
                when {
                    qt == tt -> 1.0
                    tt.startsWith(qt) || qt.startsWith(tt) -> 0.90
                    else -> similarity(qt, tt)
                }
            } ?: 0.0
        }.average().coerceIn(0.0, 1.0)
    }

    private fun normalizeSearchText(value: String): String {
        return java.text.Normalizer.normalize(
            value,
            java.text.Normalizer.Form.NFKC
        )
            .lowercase(Locale.ROOT)
            .replace("&", " and ")
            .replace(Regex("[^a-z0-9\\p{L}]+"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    private fun similarity(a: String, b: String): Double {
        if (a == b) return 1.0
        if (a.isBlank() || b.isBlank()) return 0.0
        return 1.0 - levenshtein(a, b).toDouble() / maxOf(a.length, b.length)
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

    override suspend fun load(url: String): LoadResponse {
        val cleanUrl = url.substringBefore("#").trim()

        val response = runCatching {
            app.get(
                cleanUrl,
                headers = requestHeaders(cleanUrl),
                timeout = 20_000
            )
        }.getOrNull()

        val document = response?.document
            ?: return newMovieLoadResponse(
                titleFromUrl(cleanUrl),
                cleanUrl,
                TvType.Movie,
                cleanUrl
            )

        val html = response.text
        val title = firstNonBlank(
            document.selectFirst("meta[property='og:title']")?.attr("content"),
            document.selectFirst("h1")?.text(),
            document.title(),
            titleFromUrl(cleanUrl)
        ).trim()

        val plot = firstNonBlank(
            document.selectFirst("meta[property='og:description']")?.attr("content"),
            document.selectFirst("meta[name='description']")?.attr("content")
        ).takeIf { it.isNotBlank() }

        val poster = firstNonBlank(
            document.selectFirst("meta[property='og:image']")?.attr("content"),
            document.selectFirst("meta[name='twitter:image']")?.attr("content"),
            document.selectFirst("img")?.attr("src")
        ).takeIf { it.isNotBlank() }?.let { absoluteUrl(it, cleanUrl) }

        val backdrop = findBackdrop(document, cleanUrl)
        val year = extractYear(title, html)

        val tvConfig = extractTvConfig(document, html)
        val isTv = looksLikeTvPage(document, html)

        if (isTv) {
            val episodes = linkedMapOf<String, Episode>()

            tvConfig?.let { config ->
                buildEpisodes(config, cleanUrl).forEach { episode ->
                    val key = "${episode.season ?: 1}-${episode.episode ?: 0}"
                    episodes.putIfAbsent(key, episode)
                }
            }

            if (episodes.isEmpty()) {
                parseTvProgressEpisodes(document, cleanUrl, tvConfig)
                    .forEach { episode ->
                        val key = "${episode.season ?: 1}-${episode.episode ?: 0}"
                        episodes.putIfAbsent(key, episode)
                    }
            }

            if (episodes.isEmpty()) {
                parseTvSelectorEpisodes(document, html, cleanUrl, tvConfig)
                    .forEach { episode ->
                        val key = "${episode.season ?: 1}-${episode.episode ?: 0}"
                        episodes.putIfAbsent(key, episode)
                    }
            }

            val episodeList = episodes.values.sortedWith(
                compareBy<Episode> { it.season ?: 1 }
                    .thenBy { it.episode ?: Int.MAX_VALUE }
            )

            return newTvSeriesLoadResponse(
                title,
                cleanUrl,
                TvType.TvSeries,
                episodeList
            ) {
                posterUrl = poster
                backgroundPosterUrl = backdrop
                this.plot = plot
                this.year = year
            }
        }

        val moviePlayer = extractMoviePlayerData(document, html)
        val movieData = moviePlayer?.let {
            encodeMovieData(
                MovieData(
                    pageUrl = cleanUrl,
                    defaultSource = it.defaultSource,
                    mediaToken = it.mediaToken,
                    subtitleSource = it.subtitleSource,
                    storedMediaUrl = buildMovieMedia(
                        it.defaultSource,
                        it.mediaToken.orEmpty(),
                        it.subtitleSource
                    )?.mediaUrl
                )
            )
        } ?: cleanUrl

        return newMovieLoadResponse(
            title,
            cleanUrl,
            TvType.Movie,
            movieData
        ) {
            posterUrl = poster
            backgroundPosterUrl = backdrop
            this.plot = plot
            this.year = year
        }
    }

    private fun looksLikeTvPage(
        document: Document,
        html: String
    ): Boolean {
        if (document.select("#plyr-tv-config, .plyr-tv-container, .mj-tv-progress-row-wrap").isNotEmpty()) {
            return true
        }
        val ogType = document.selectFirst("meta[property='og:type']")
            ?.attr("content")
            .orEmpty()
            .lowercase(Locale.ROOT)
        if (ogType.contains("tv_show")) return true

        val lower = html.lowercase(Locale.ROOT)
        return lower.contains("id=\"plyr-tv-config\"") ||
            lower.contains("id='plyr-tv-config'") ||
            lower.contains("data-mj-season=") ||
            lower.contains("class=\"plyr-tv-container")
    }

    private fun extractTvConfig(
        document: Document,
        rawHtml: String
    ): TvConfig? {
        val candidates = linkedSetOf<String>()

        document.select("script#plyr-tv-config, script[type='application/json']#plyr-tv-config")
            .forEach { script ->
                script.data().trim().takeIf { it.isNotBlank() }?.let(candidates::add)
                script.html().trim().takeIf { it.isNotBlank() }?.let(candidates::add)
                script.text().trim().takeIf { it.isNotBlank() }?.let(candidates::add)
            }

        val normalized = normalizeHtml(rawHtml)
        Regex(
            "(?is)<script\\b[^>]*\\bid=[\"']plyr-tv-config[\"'][^>]*>(.*?)</script\\s*>"
        ).find(normalized)?.groupValues?.getOrNull(1)
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?.let(candidates::add)

        for (candidate in candidates) {
            val json = extractJsonObject(candidate) ?: continue
            val parsed = runCatching { parseTvConfigJson(json) }.getOrNull()
            if (parsed != null && parsed.seasons.any { it.episodeFiles.isNotEmpty() }) {
                return parsed
            }
        }

        return null
    }

    private fun parseTvConfigJson(raw: String): TvConfig? {
        val root = JSONObject(raw)
        val baseUrl = root.optString("baseUrl").trim()
        val token = root.optString("mediaToken").trim()
        val showFolder = root.optString("showFolder").trim().takeIf { it.isNotBlank() }
        if (baseUrl.isBlank()) return null

        val seasons = mutableListOf<TvSeason>()
        val seasonArray = root.optJSONArray("seasons") ?: return null

        for (index in 0 until seasonArray.length()) {
            val obj = seasonArray.optJSONObject(index) ?: continue
            val number = obj.optInt("num", 0)
            val folder = obj.optString("folder").trim()
            if (number <= 0 || folder.isBlank()) continue

            val files = mutableMapOf<Int, String>()
            obj.optJSONObject("episode_files")?.let { filesObj ->
                val keys = filesObj.keys()
                while (keys.hasNext()) {
                    val key = keys.next()
                    val episode = key.toIntOrNull() ?: continue
                    val file = filesObj.optString(key).trim()
                    if (episode > 0 && file.isNotBlank()) files[episode] = file
                }
            }

            val subs = mutableMapOf<Int, String>()
            obj.optJSONObject("subtitle_files")?.let { subObj ->
                val keys = subObj.keys()
                while (keys.hasNext()) {
                    val key = keys.next()
                    val episode = key.toIntOrNull() ?: continue
                    val file = subObj.optString(key).trim()
                    if (episode > 0 && file.isNotBlank()) subs[episode] = file
                }
            }

            seasons += TvSeason(
                number = number,
                folder = folder,
                episodeFiles = files,
                subtitleFiles = subs
            )
        }

        if (seasons.none { it.episodeFiles.isNotEmpty() }) return null

        return TvConfig(
            baseUrl = baseUrl,
            mediaToken = token,
            showFolder = showFolder,
            seasons = seasons
        )
    }

    private fun buildEpisodes(
        config: TvConfig,
        showUrl: String
    ): List<Episode> {
        val result = mutableListOf<Episode>()

        config.seasons.sortedBy { it.number }.forEach { season ->
            season.episodeFiles.toSortedMap().forEach { (episodeNumber, filename) ->
                val label = episodeLabel(filename, episodeNumber)
                val storedMedia = buildTvMedia(
                    config.baseUrl,
                    config.mediaToken,
                    config.showFolder,
                    season.folder,
                    filename,
                    season.subtitleFiles[episodeNumber]
                )?.mediaUrl

                val data = encodeEpisodeData(
                    EpisodeData(
                        showUrl = showUrl,
                        season = season.number,
                        episode = episodeNumber,
                        folder = season.folder,
                        filename = filename,
                        subtitleFilename = season.subtitleFiles[episodeNumber],
                        baseUrl = config.baseUrl,
                        mediaToken = config.mediaToken,
                        showFolder = config.showFolder,
                        storedMediaUrl = storedMedia
                    )
                )

                result += newEpisode(data) {
                    name = label
                    this.season = season.number
                    this.episode = episodeNumber
                }
            }
        }

        return result
    }

    private fun parseTvProgressEpisodes(
        document: Document,
        showUrl: String,
        config: TvConfig?
    ): List<Episode> {
        val result = mutableListOf<Episode>()

        document.select(
            ".mj-tv-progress-row-wrap[data-mj-season], [data-mj-row-wrap][data-mj-season]"
        ).forEach { row ->
            val seasonNumber = row.attr("data-mj-season").toIntOrNull()
                ?: extractSeasonNumber(row.text())
                ?: return@forEach

            row.select(".mj-tv-progress-ep[data-mj-ep], [data-mj-ep]")
                .forEach { button ->
                    val episodeNumber = button.attr("data-mj-ep").toIntOrNull()
                        ?: return@forEach
                    val seasonConfig = config?.seasons?.firstOrNull { it.number == seasonNumber }
                    val filename = seasonConfig?.episodeFiles?.get(episodeNumber)
                    val subtitle = seasonConfig?.subtitleFiles?.get(episodeNumber)
                    val folder = seasonConfig?.folder

                    val label = firstNonBlank(
                        button.attr("aria-label"),
                        button.attr("title"),
                        button.text()
                    ).ifBlank { "Episode $episodeNumber" }

                    val storedMedia = if (filename != null && config != null) {
                        buildTvMedia(
                            config.baseUrl,
                            config.mediaToken,
                            config.showFolder,
                            folder.orEmpty(),
                            filename,
                            subtitle
                        )?.mediaUrl
                    } else null

                    val data = encodeEpisodeData(
                        EpisodeData(
                            showUrl = showUrl,
                            season = seasonNumber,
                            episode = episodeNumber,
                            folder = folder,
                            filename = filename,
                            subtitleFilename = subtitle,
                            baseUrl = config?.baseUrl,
                            mediaToken = config?.mediaToken,
                            showFolder = config?.showFolder,
                            storedMediaUrl = storedMedia
                        )
                    )

                    result += newEpisode(data) {
                        name = label
                        this.season = seasonNumber
                        this.episode = episodeNumber
                    }
                }
        }

        return result.sortedWith(
            compareBy<Episode> { it.season ?: 1 }
                .thenBy { it.episode ?: Int.MAX_VALUE }
        )
    }

    private fun parseTvSelectorEpisodes(
        document: Document,
        rawHtml: String,
        showUrl: String,
        config: TvConfig?
    ): List<Episode> {
        val counts = linkedMapOf<Int, Int>()

        document.select(
            "#plyr-season-select option, .plyr-season-btn[data-season]"
        ).forEach { element ->
            val season = element.attr("value").toIntOrNull()
                ?: element.attr("data-season").toIntOrNull()
                ?: extractSeasonNumber(element.text())
                ?: return@forEach

            val count = Regex("(?i)(\\d+)\\s*episodes?\\b")
                .find(element.text())
                ?.groupValues
                ?.getOrNull(1)
                ?.toIntOrNull()
                ?: 0

            if (season > 0 && count > 0) {
                counts[season] = maxOf(counts[season] ?: 0, count)
            }
        }

        document.select(
            ".mj-tv-progress-row-wrap[data-mj-season]"
        ).forEach { row ->
            val season = row.attr("data-mj-season").toIntOrNull() ?: return@forEach
            val count = row.attr("data-mj-season-total").toIntOrNull() ?: 0
            if (season > 0 && count > 0) counts[season] = maxOf(counts[season] ?: 0, count)
        }

        if (counts.isEmpty()) {
            Regex(
                "(?i)Season\\s*(\\d+)[^<]{0,100}?\\((\\d+)\\s*episodes?\\)"
            ).findAll(normalizeHtml(rawHtml)).forEach { match ->
                val season = match.groupValues.getOrNull(1)?.toIntOrNull() ?: return@forEach
                val count = match.groupValues.getOrNull(2)?.toIntOrNull() ?: return@forEach
                if (season > 0 && count > 0) counts[season] = count
            }
        }

        if (counts.isEmpty()) {
            config?.seasons?.forEach { season ->
                val count = season.episodeFiles.keys.maxOrNull() ?: 0
                if (count > 0) counts[season.number] = count
            }
        }

        val result = mutableListOf<Episode>()

        counts.toSortedMap().forEach { (seasonNumber, count) ->
            val seasonConfig = config?.seasons?.firstOrNull { it.number == seasonNumber }

            for (episodeNumber in 1..count) {
                val filename = seasonConfig?.episodeFiles?.get(episodeNumber)
                val subtitle = seasonConfig?.subtitleFiles?.get(episodeNumber)
                val folder = seasonConfig?.folder

                val storedMedia = if (filename != null && config != null) {
                    buildTvMedia(
                        config.baseUrl,
                        config.mediaToken,
                        config.showFolder,
                        folder.orEmpty(),
                        filename,
                        subtitle
                    )?.mediaUrl
                } else null

                result += newEpisode(
                    encodeEpisodeData(
                        EpisodeData(
                            showUrl = showUrl,
                            season = seasonNumber,
                            episode = episodeNumber,
                            folder = folder,
                            filename = filename,
                            subtitleFilename = subtitle,
                            baseUrl = config?.baseUrl,
                            mediaToken = config?.mediaToken,
                            showFolder = config?.showFolder,
                            storedMediaUrl = storedMedia
                        )
                    )
                ) {
                    name = "Episode $episodeNumber"
                    this.season = seasonNumber
                    this.episode = episodeNumber
                }
            }
        }

        return result
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val input = data.trim()
        if (input.isBlank()) return false

        val episodeData = decodeEpisodeData(input)
        if (episodeData != null) {
            return loadTvEpisode(
                episodeData,
                subtitleCallback,
                callback
            )
        }

        val movieData = decodeMovieData(input)
        if (movieData != null) {
            return loadMovie(
                movieData,
                subtitleCallback,
                callback
            )
        }

        if (isMediaUrl(input) || isDirectLinkUrl(input)) {
            emitMediaLink(
                input,
                callback,
                "Moja Loss Direct",
                mainUrl
            )
            return true
        }

        val fresh = getFreshMoviePlayerData(input)
            ?: return false

        val movie = MovieData(
            pageUrl = input.substringBefore("#"),
            defaultSource = fresh.defaultSource,
            mediaToken = fresh.mediaToken,
            subtitleSource = fresh.subtitleSource,
            storedMediaUrl = buildMovieMedia(
                fresh.defaultSource,
                fresh.mediaToken.orEmpty(),
                fresh.subtitleSource
            )?.mediaUrl
        )

        return loadMovie(movie, subtitleCallback, callback)
    }

    private suspend fun loadTvEpisode(
        episode: EpisodeData,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val freshResponse = runCatching {
            app.get(
                addCacheBuster(episode.showUrl),
                headers = requestHeaders(episode.showUrl),
                timeout = 20_000
            )
        }.getOrNull() ?: runCatching {
            app.get(
                episode.showUrl,
                headers = requestHeaders(episode.showUrl),
                timeout = 20_000
            )
        }.getOrNull()

        val freshConfig = freshResponse?.let {
            extractTvConfig(it.document, it.text)
        }

        val seasonConfig = freshConfig?.seasons?.firstOrNull {
            it.number == episode.season
        }

        val folder = seasonConfig?.folder
            ?: episode.folder
            ?: return emitStoredEpisodeFallback(episode, callback)

        val filename = seasonConfig?.episodeFiles?.get(episode.episode)
            ?: episode.filename
            ?: return emitStoredEpisodeFallback(episode, callback)

        val baseUrl = freshConfig?.baseUrl?.takeIf { it.isNotBlank() }
            ?: episode.baseUrl
            ?: return emitStoredEpisodeFallback(episode, callback)

        val token = freshConfig?.mediaToken?.takeIf { it.isNotBlank() }
            ?: episode.mediaToken
            ?: return emitStoredEpisodeFallback(episode, callback)

        val showFolder = freshConfig?.showFolder ?: episode.showFolder
        val subtitleFilename = seasonConfig?.subtitleFiles?.get(episode.episode)
            ?: episode.subtitleFilename

        val media = buildTvMedia(
            baseUrl = baseUrl,
            token = token,
            showFolder = showFolder,
            folder = folder,
            filename = filename,
            subtitleFilename = subtitleFilename
        ) ?: return emitStoredEpisodeFallback(episode, callback)

        subtitleCallbackForUrl(
            media.subtitleUrl,
            subtitleCallback
        )

        emitMediaLink(
            media.mediaUrl,
            callback,
            "Moja Loss TV",
            episode.showUrl
        )

        val alternate = buildTvDirectUrl(
            baseUrl,
            token,
            showFolder,
            folder,
            filename
        )

        if (alternate != media.mediaUrl) {
            emitMediaLink(
                alternate,
                callback,
                "Moja Loss TV Direct",                episode.showUrl
            )
        }

        return true
    }

    private suspend fun emitStoredEpisodeFallback(
        episode: EpisodeData,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val stored = episode.storedMediaUrl
            ?: return false

        emitMediaLink(
            stored,
            callback,
            "Moja Loss TV Stored",
            episode.showUrl
        )
        return true
    }

    private suspend fun loadMovie(
        movie: MovieData,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val fresh = getFreshMoviePlayerData(movie.pageUrl)

        val source = fresh?.defaultSource
            ?.takeIf { it.isNotBlank() }
            ?: movie.defaultSource

        val token = fresh?.mediaToken
            ?.takeIf { it.isNotBlank() }
            ?: movie.mediaToken

        val subtitle = fresh?.subtitleSource
            ?: movie.subtitleSource

        if (source.isBlank()) {
            return movie.storedMediaUrl?.let {
                emitMediaLink(it, callback, "Moja Loss Stored", movie.pageUrl)
                true
            } ?: false
        }

        if (!token.isNullOrBlank()) {
            val media = buildMovieMedia(
                source,
                token,
                subtitle
            )

            if (media != null) {
                subtitleCallbackForUrl(media.subtitleUrl, subtitleCallback)

                emitMediaLink(
                    media.mediaUrl,
                    callback,
                    "Moja Loss CDN",
                    movie.pageUrl
                )

                val direct = appendToken(source, token)
                if (direct != media.mediaUrl) {
                    emitMediaLink(
                        direct,
                        callback,
                        "Moja Loss Direct",
                        movie.pageUrl
                    )
                }

                return true
            }
        }

        if (isDirectLinkUrl(source) || isMediaUrl(source)) {
            emitMediaLink(
                source,
                callback,
                "Moja Loss Direct",
                movie.pageUrl
            )
            return true
        }

        return movie.storedMediaUrl?.let {
            emitMediaLink(
                it,
                callback,
                "Moja Loss Stored",
                movie.pageUrl
            )
            true
        } ?: false
    }

    private data class MoviePlayerData(
        val defaultSource: String,
        val mediaToken: String?,
        val subtitleSource: String?
    )

    private suspend fun getFreshMoviePlayerData(
        pageUrl: String
    ): MoviePlayerData? {
        val cleanUrl = pageUrl.substringBefore("#").trim()
        if (cleanUrl.isBlank()) return null

        val urls = linkedSetOf(
            addCacheBuster(cleanUrl),
            cleanUrl
        )

        for (url in urls) {
            val response = runCatching {
                app.get(
                    url,
                    headers = requestHeaders(cleanUrl),
                    timeout = 20_000
                )
            }.getOrNull() ?: continue

            val player = extractMoviePlayerData(
                response.document,
                response.text
            )

            if (player != null) return player
        }

        return null
    }

    private fun extractMoviePlayerData(
        document: Document,
        rawHtml: String
    ): MoviePlayerData? {
        val video = document.selectFirst(
            "video[data-default-src][data-media-token], " +
                "#movie-video[data-default-src], " +
                "video[data-default-src]"
        ) ?: document.selectFirst("#movie-video, video")

        val defaultSource = firstNonBlank(
            video?.attr("data-default-src"),
            video?.selectFirst("source[src]")?.attr("src"),
            extractRawAttribute(rawHtml, "data-default-src"),
            extractRawPlayableSource(rawHtml)
        ).let(::decodeHtmlEntities).trim()

        if (defaultSource.isBlank()) return null

        val token = firstNonBlank(
            video?.attr("data-media-token"),
            extractRawAttribute(rawHtml, "data-media-token"),
            extractRawJsonString(rawHtml, "mediaToken")
        ).let(::decodeHtmlEntities).trim().takeIf { it.isNotBlank() }

        val subtitle = firstNonBlank(
            video?.attr("data-default-subtitle-src"),
            extractRawAttribute(rawHtml, "data-default-subtitle-src")
        ).let(::decodeHtmlEntities).trim().takeIf { it.isNotBlank() }

        return MoviePlayerData(
            defaultSource = defaultSource,
            mediaToken = token,
            subtitleSource = subtitle
        )
    }

    private fun buildMovieMedia(
        source: String,
        token: String,
        subtitleSource: String?
    ): MediaResult? {
        if (source.isBlank() || token.isBlank()) return null

        val direct = appendToken(source, token)
        val playable = normalizeDirectMediaUrl(direct)
        val subtitle = subtitleSource
            ?.takeIf { it.isNotBlank() }
            ?.let { normalizeDirectMediaUrl(appendToken(it, token)) }

        return MediaResult(
            mediaUrl = playable,
            subtitleUrl = subtitle
        )
    }

    private fun buildTvMedia(
        baseUrl: String,
        token: String,
        showFolder: String?,
        folder: String,
        filename: String,
        subtitleFilename: String?
    ): MediaResult? {
        if (baseUrl.isBlank() || token.isBlank() || folder.isBlank() || filename.isBlank()) {
            return null
        }

        val pathParts = mutableListOf<String>()
        showFolder?.takeIf { it.isNotBlank() }?.let { pathParts += encodePathPart(it) }
        pathParts += encodePathPart(folder)
        pathParts += encodePathPart(filename)

        val direct = appendToken(
            baseUrl.trim().trimEnd('/') + "/" + pathParts.joinToString("/"),
            token
        )

        val subtitle = subtitleFilename
            ?.takeIf { it.isNotBlank() }
            ?.let {
                val subParts = mutableListOf<String>()
                showFolder?.takeIf { it.isNotBlank() }?.let { folderName ->
                    subParts += encodePathPart(folderName)
                }
                subParts += encodePathPart(folder)
                subParts += encodePathPart(it)
                normalizeDirectMediaUrl(
                    appendToken(
                        baseUrl.trim().trimEnd('/') + "/" + subParts.joinToString("/"),
                        token
                    )
                )
            }

        return MediaResult(
            mediaUrl = normalizeDirectMediaUrl(direct),
            subtitleUrl = subtitle
        )
    }

    private fun buildTvDirectUrl(
        baseUrl: String,
        token: String,
        showFolder: String?,
        folder: String,
        filename: String
    ): String {
        val parts = mutableListOf<String>()
        showFolder?.takeIf { it.isNotBlank() }?.let { parts += encodePathPart(it) }
        parts += encodePathPart(folder)
        parts += encodePathPart(filename)
        return appendToken(
            normalizeDirectMediaUrl(
                baseUrl.trim().trimEnd('/') + "/" + parts.joinToString("/")
            ),
            token
        )
    }

    private suspend fun emitMediaLink(
        mediaUrl: String,
        callback: (ExtractorLink) -> Unit,
        linkName: String,
        referer: String?
    ) {
        val lower = mediaUrl.lowercase(Locale.ROOT)
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

        callback(
            newExtractorLink(
                source = name,
                name = linkName,
                url = mediaUrl,
                type = type
            ) {
                this.quality = quality
                this.referer = referer?.takeIf { it.isNotBlank() } ?: "$mainUrl/"
                this.headers = mapOf(
                    "User-Agent" to
                        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) " +
                            "AppleWebKit/537.36 (KHTML, like Gecko) " +
                            "Chrome/131.0.0.0 Safari/537.36",
                    "Accept" to "*/*"
                )
            }
        )
    }

    private fun subtitleCallbackForUrl(
        url: String?,
        callback: (SubtitleFile) -> Unit
    ) {
        if (url.isNullOrBlank()) return
        callback(
            SubtitleFile(
                lang = "English",
                url = url
            )
        )
    }

    private fun encodeEpisodeData(data: EpisodeData): String {
        val json = JSONObject().apply {
            put("kind", "episode")
            put("showUrl", data.showUrl)
            put("season", data.season)
            put("episode", data.episode)
            putNullable("folder", data.folder)
            putNullable("filename", data.filename)
            putNullable("subtitleFilename", data.subtitleFilename)
            putNullable("baseUrl", data.baseUrl)
            putNullable("mediaToken", data.mediaToken)
            putNullable("showFolder", data.showFolder)
            putNullable("storedMediaUrl", data.storedMediaUrl)
        }
        return "MOJALOSS_EP:$json"
    }

    private fun decodeEpisodeData(input: String): EpisodeData? {
        if (!input.startsWith("MOJALOSS_EP:")) return null
        return runCatching {
            val json = JSONObject(input.removePrefix("MOJALOSS_EP:"))
            EpisodeData(
                showUrl = json.optString("showUrl").trim(),
                season = json.optInt("season", 0),
                episode = json.optInt("episode", 0),
                folder = json.optNullableString("folder"),
                filename = json.optNullableString("filename"),
                subtitleFilename = json.optNullableString("subtitleFilename"),
                baseUrl = json.optNullableString("baseUrl"),
                mediaToken = json.optNullableString("mediaToken"),
                showFolder = json.optNullableString("showFolder"),
                storedMediaUrl = json.optNullableString("storedMediaUrl")
            ).takeIf {
                it.showUrl.isNotBlank() && it.season > 0 && it.episode > 0
            }
        }.getOrNull()
    }

    private fun encodeMovieData(data: MovieData): String {
        val json = JSONObject().apply {
            put("kind", "movie")
            put("pageUrl", data.pageUrl)
            put("defaultSource", data.defaultSource)
            putNullable("mediaToken", data.mediaToken)
            putNullable("subtitleSource", data.subtitleSource)
            putNullable("storedMediaUrl", data.storedMediaUrl)
        }
        return "MOJALOSS_MOV:$json"
    }

    private fun decodeMovieData(input: String): MovieData? {
        if (!input.startsWith("MOJALOSS_MOV:")) return null
        return runCatching {
            val json = JSONObject(input.removePrefix("MOJALOSS_MOV:"))
            MovieData(
                pageUrl = json.optString("pageUrl").trim(),
                defaultSource = json.optString("defaultSource").trim(),
                mediaToken = json.optNullableString("mediaToken"),
                subtitleSource = json.optNullableString("subtitleSource"),
                storedMediaUrl = json.optNullableString("storedMediaUrl")
            ).takeIf {
                it.pageUrl.isNotBlank() && it.defaultSource.isNotBlank()
            }
        }.getOrNull()
    }

    private fun JSONObject.putNullable(
        key: String,
        value: String?
    ) {
        if (value != null) put(key, value)
    }

    private fun JSONObject.optNullableString(
        key: String
    ): String? {
        if (!has(key) || isNull(key)) return null
        return optString(key).takeIf { it.isNotBlank() }
    }

    private fun extractJsonObject(value: String): String? {
        val normalized = normalizeHtml(value).trim()
        if (normalized.isBlank()) return null
        val start = normalized.indexOf('{')
        val end = normalized.lastIndexOf('}')
        if (start < 0 || end <= start) return null
        return normalized.substring(start, end + 1)
    }

    private fun normalizeHtml(value: String): String {
        return value
            .replace("\\/", "/")
            .replace("\\u002F", "/")
            .replace("\\u002f", "/")
            .replace("\\u0026", "&")
            .replace("\\u003A", ":")
            .replace("\\u003a", ":")
            .replace("&amp;", "&")
            .replace("&quot;", "\"")
            .replace("&#34;", "\"")
            .replace("&#39;", "'")
    }

    private fun extractRawAttribute(
        html: String,
        attribute: String
    ): String {
        if (html.isBlank()) return ""
        return runCatching {
            Regex(
                "(?is)\\b${Regex.escape(attribute)}\\s*=\\s*[\\\"']([^\\\"']+)[\\\"']"
            ).find(html)?.groupValues?.getOrNull(1).orEmpty()
        }.getOrDefault("")
    }

    private fun extractRawJsonString(
        html: String,
        key: String
    ): String {
        if (html.isBlank()) return ""
        return runCatching {
            Regex(
                "(?is)[\\\"']${Regex.escape(key)}[\\\"']\\s*:\\s*[\\\"']([^\\\"']+)[\\\"']"
            ).find(normalizeHtml(html))?.groupValues?.getOrNull(1).orEmpty()
        }.getOrDefault("")
    }

    private fun extractRawPlayableSource(html: String): String {
        if (html.isBlank()) return ""
        return runCatching {
            Regex(
                "(?is)https?://(?:www\\.)?mojaloss\\.stream/directlink/[^\"'<>\\s]+\\.(?:mp4|mkv|webm)(?:\\?[^\"'<>\\s]*)?"
            ).find(html)?.value.orEmpty()
                .ifBlank {
                    Regex(
                        "(?is)https?://media\\.mojaloss\\.stream/dl/[^\"'<>\\s]+\\.(?:mp4|mkv|webm)(?:\\?[^\"'<>\\s]*)?"
                    ).find(html)?.value.orEmpty()
                }
        }.getOrDefault("")
    }

    private fun decodeHtmlEntities(value: String): String {
        return value
            .replace("&amp;", "&")
            .replace("&#38;", "&")
            .replace("&#x26;", "&")
            .replace("\\/", "/")
            .replace("\\u002F", "/")
            .replace("\\u002f", "/")
    }

    private fun addCacheBuster(url: String): String {
        val separator = if (url.contains("?")) "&" else "?"
        return "$url${separator}mj_cs_refresh=${System.currentTimeMillis()}"
    }

    private fun appendToken(url: String, token: String): String {
        val cleanToken = token.trim().removePrefix("?").replace("&amp;", "&")
        if (cleanToken.isBlank()) return url
        return if (url.contains("?")) "$url&$cleanToken" else "$url?$cleanToken"
    }

    private fun normalizeDirectMediaUrl(url: String): String {
        return url.replace(
            Regex("(?i)^https?://(?:www\\.)?mojaloss\\.stream/directlink/"),
            "https://media.mojaloss.stream/dl/"
        )
    }

    private fun encodePathPart(value: String): String {
        return URLEncoder.encode(
            value,
            StandardCharsets.UTF_8.toString()
        ).replace("+", "%20")
    }

    private fun isMediaUrl(url: String): Boolean {
        val lower = url.lowercase(Locale.ROOT)
        return lower.contains("media.mojaloss.stream/dl/") &&
            (".mp4" in lower || ".mkv" in lower || ".webm" in lower || ".m3u8" in lower || ".mpd" in lower)
    }

    private fun isDirectLinkUrl(url: String): Boolean {
        val lower = url.lowercase(Locale.ROOT)
        return lower.contains("mojaloss.stream/directlink/") &&
            (".mp4" in lower || ".mkv" in lower || ".webm" in lower || ".m3u8" in lower || ".mpd" in lower)
    }

    private fun canonicalPageKey(url: String): String {
        return url.substringBefore("#").trim().trimEnd('/').lowercase(Locale.ROOT)
    }

    private fun isMojaPageUrl(url: String): Boolean {
        return runCatching {
            val uri = URI(url)
            uri.host.orEmpty().lowercase(Locale.ROOT) ==
                URI(mainUrl).host.orEmpty().lowercase(Locale.ROOT)
        }.getOrDefault(false)
    }

    private fun absoluteUrl(raw: String, base: String): String {
        val value = raw.trim()
        if (value.isBlank()) return value
        if (value.startsWith("http://", true) || value.startsWith("https://", true)) return value
        return runCatching { URI(base).resolve(value).toString() }.getOrDefault(value)
    }

    private fun titleFromUrl(url: String): String {
        val path = runCatching { URI(url).path.orEmpty() }.getOrDefault("")
        return path.trim('/')
            .substringAfterLast('/')
            .ifBlank { "Moja Loss" }
            .replace('-', ' ')
            .replace('_', ' ')
            .replace(Regex("\\b\\d{4}\\b"), "")
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    private fun episodeLabel(filename: String, episode: Int): String {
        val base = filename.substringBeforeLast('.', filename)
        val display = base.substringAfterLast('/').trim()
        return if (display.isBlank()) "Episode $episode" else "E$episode $display"
    }

    private fun extractSeasonNumber(text: String): Int? {
        return Regex("(?i)\\bseason\\s*[-._ ]?(\\d+)\\b")
            .find(text)?.groupValues?.getOrNull(1)?.toIntOrNull()
    }

    private fun extractYear(title: String, html: String): Int? {
        Regex("\\b(19|20)\\d{2}\\b").find(title)?.value?.toIntOrNull()?.let { return it }
        return Regex("\\b(19|20)\\d{2}\\b").find(html)?.value?.toIntOrNull()
    }

    private fun findBackdrop(document: Document, base: String): String? {
        val meta = firstNonBlank(
            document.selectFirst("meta[property='og:image:secure_url']")?.attr("content"),
            document.selectFirst("meta[property='og:image']")?.attr("content")
        )
        if (meta.isNotBlank()) return absoluteUrl(meta, base)

        val style = document.selectFirst(
            "[style*='background-image'], .hero-backdrop, .backdrop, .hero-bg"
        )?.attr("style").orEmpty()

        return Regex("""url\(['\"]?([^'\")]+)['\"]?\)""")
            .find(style)
            ?.groupValues
            ?.getOrNull(1)
            ?.let { absoluteUrl(it, base) }
    }

    private fun firstNonBlank(vararg values: String?): String {
        return values.firstOrNull { !it.isNullOrBlank() } ?: ""
    }
}