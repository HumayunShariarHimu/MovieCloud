package com.movieflick.hianime

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.json.JSONObject
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URI
import java.net.URLEncoder
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.util.Locale
import java.util.Base64
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import okhttp3.Headers

class HiAnime : MainAPI() {

    override var mainUrl = "https://hianime.at"
    override var name = "Hi Anime"
    override var lang = "en"

    override val hasMainPage = true
    override val hasQuickSearch = true

    override val supportedTypes = setOf(
        TvType.Movie,
        TvType.TvSeries,
        TvType.Anime
    )

    override val mainPage = mainPageOf(
        "$mainUrl/recently-updated" to "Latest Episode",
        "$mainUrl/dubbed-anime" to "Dubbed Anime",
        "$mainUrl/movie" to "Anime",
        "$mainUrl/tv" to "TV Show"
    )

    private val movieMarker = "mf_movie=1"
    private val tvMarker = "mf_tv=1"

    private val pageHeaders: Map<String, String>
        get() = mapOf(
            "User-Agent" to
                "Mozilla/5.0 (Linux; Android 13; Mobile) " +
                    "AppleWebKit/537.36 (KHTML, like Gecko) " +
                    "Chrome/131.0.0.0 Mobile Safari/537.36",
            "Accept" to
                "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
            "Accept-Language" to "en-US,en;q=0.9",
            "Cache-Control" to "no-cache",
            "Pragma" to "no-cache"
        )

    private fun cleanText(value: String?): String =
        value
            ?.replace(Regex("\\s+"), " ")
            ?.trim()
            .orEmpty()

    private fun isMediaUrl(url: String): Boolean {
        val value = url
            .trim()
            .lowercase(Locale.ROOT)
            .substringBefore("#")

        if (
            !value.startsWith("http://") &&
            !value.startsWith("https://")
        ) {
            return false
        }

        val path = value.substringBefore("?")

        return path.endsWith(".m3u8") ||
            path.endsWith(".mp4") ||
            path.endsWith(".m3u") ||
            path.endsWith(".mpd") ||
            path.endsWith(".webm") ||
            path.endsWith(".m4v") ||
            path.endsWith(".mov") ||
            path.endsWith(".mkv") ||
            path.contains("/hls/") ||
            path.contains("/dash/") ||
            path.contains(".m3u8/") ||
            value.contains("hls2.aniwatchtv.uk/") &&
                (
                    value.contains("/master.m3u8") ||
                        value.contains("/index.m3u8")
                )
    }

    private fun cleanTitle(value: String?): String =
        cleanText(value)
            .replace(
                Regex("""\s*\|\s*HiAnime\s*$""", RegexOption.IGNORE_CASE),
                ""
            )
            .replace(
                Regex("""\s*[-–—]\s*HiAnime\s*$""", RegexOption.IGNORE_CASE),
                ""
            )
            .trim()

    private fun absoluteUrl(
        raw: String,
        base: String = mainUrl
    ): String {
        val value = raw
            .trim()
            .replace("\\/", "/")
            .replace("\\u002F", "/")
            .replace("\\u002f", "/")
            .replace("\\u0026", "&")
            .replace("&amp;", "&")

        if (value.isBlank()) return value

        return when {
            value.startsWith("http://", true) ||
                value.startsWith("https://", true) -> value

            value.startsWith("//") -> "https:$value"

            value.startsWith("/") ->
                base.trimEnd('/') + value

            else ->
                base.trimEnd('/') + "/" + value
        }
    }

    private fun posterFrom(element: Element?): String? {
        if (element == null) return null

        return listOf(
            element.attr("src"),
            element.attr("data-src"),
            element.attr("data-original"),
            element.attr("data-lazy-src")
        )
            .firstOrNull { it.isNotBlank() }
            ?.let(::absoluteUrl)
    }

    private fun extractPoster(document: Document): String? =
        listOf(
            document.selectFirst("meta[property=og:image]")
                ?.attr("content"),
            posterFrom(
                document.selectFirst(
                    ".anisc-poster img, .film-poster img, img.film-poster-img"
                )
            )
        )
            .mapNotNull {
                it?.trim()?.takeIf(String::isNotBlank)
            }
            .firstOrNull()
            ?.let(::absoluteUrl)

    private fun inferType(
        url: String,
        element: Element? = null
    ): TvType {
        val path = runCatching {
            URI(url).path.orEmpty().lowercase(Locale.ROOT)
        }.getOrDefault("")

        val text = cleanText(
            element
                ?.selectFirst(".fd-infor, .film-stats, .fdi-item, .item")
                ?.text()
        ).uppercase(Locale.ROOT)

        return when {
            text.contains("MOVIE") ||
                path.matches(
                    Regex(""".*/movie/.*""")
                ) ->
                TvType.Movie

            text.contains("TV") ||
                path.contains("/tv") ->
                TvType.TvSeries

            else ->
                TvType.Anime
        }
    }

    private fun markUrl(
        url: String,
        type: TvType
    ): String {
        return when (type) {
            TvType.Movie -> {
                if (url.contains("?")) "$url&$movieMarker"
                else "$url?$movieMarker"
            }

            TvType.TvSeries -> {
                if (url.contains("?")) "$url&$tvMarker"
                else "$url?$tvMarker"
            }

            else -> url
        }
    }

    private fun hasMarker(
        url: String,
        marker: String
    ): Boolean =
        Regex(
            """(?:\?|&)${Regex.escape(marker)}(?:&|$)"""
        ).containsMatchIn(url)

    private fun stripMarkers(url: String): String {
        return url
            .replace(Regex("""([?&])mf_movie=1(?=&|$)"""), "$1")
            .replace(Regex("""([?&])mf_tv=1(?=&|$)"""), "$1")
            .replace(Regex("""\?&"""), "?")
            .replace(Regex("""[?&]$"""), "")
    }

    private fun cardSearchResponse(
        title: String,
        url: String,
        poster: String?,
        type: TvType
    ): SearchResponse {
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
                url
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

    private fun parseCards(
        document: Document,
        forceType: TvType? = null
    ): List<SearchResponse> {
        val cards = document.select(
            ".flw-item, " +
                ".film_list-grid .flw-item, " +
                ".film_list-wrap .flw-item"
        )

        return cards.mapNotNull { card ->
            val link =
                card.selectFirst("a[href*='/watch/'], a.dynamic-name[href]")
                    ?: return@mapNotNull null

            val href = link.attr("href").trim()
            if (href.isBlank()) return@mapNotNull null

            val url = absoluteUrl(href)

            val title = cleanTitle(
                link.attr("title").ifBlank {
                    link.text()
                }
            )

            if (title.isBlank()) return@mapNotNull null

            val poster = posterFrom(
                card.selectFirst(
                    "img.film-poster-img, .film-poster img, img"
                )
            )

            val type = forceType ?: inferType(url, card)

            cardSearchResponse(
                title = title,
                url = markUrl(url, type),
                poster = poster,
                type = type
            )
        }.distinctBy { it.url }
    }

    private fun hasNextPage(document: Document): Boolean {
        return document.select(
            "a.page-link[href], .pagination a[href], a[href].page-link"
        ).any {
            val text = cleanText(it.text())
            text.equals("Next", true) ||
                text == "›" ||
                text == "»"
        }
    }

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        val base = request.data.trim()
        if (base.isBlank()) {
            return newHomePageResponse(
                request,
                emptyList(),
                false
            )
        }

        val pageNumber = page.coerceAtLeast(1)

        val target = when {
            pageNumber == 1 ->
                base

            base.contains("?") ->
                "$base&page=$pageNumber"

            else ->
                "$base?page=$pageNumber"
        }

        val response = runCatching {
            app.get(
                target,
                headers = pageHeaders + mapOf(
                    "Referer" to "$mainUrl/"
                )
            )
        }.getOrNull()
            ?: return newHomePageResponse(
                request,
                emptyList(),
                false
            )

        /*
         * TV must contain series cards, not episode cards.
         * The supplied HiAnime TV page identifies entries as TV series
         * and their detail pages provide the episode list.
         */
        val normalizedBase = base
            .trimEnd('/')
            .lowercase(Locale.ROOT)

        val forcedType = when {
            normalizedBase == "$mainUrl/movie".lowercase(Locale.ROOT) ->
                TvType.Movie

            normalizedBase == "$mainUrl/tv".lowercase(Locale.ROOT) ->
                TvType.TvSeries

            else ->
                null
        }

        val items = parseCards(
            response.document,
            forceType = forcedType
        )

        return newHomePageResponse(
            request,
            items,
            hasNextPage(response.document)
        )
    }

    /*
     * HiAnime's public search navigation uses /browse?keyword=...
     * Keep /search?keyword=... only as a compatibility fallback.
     */
    private suspend fun searchDocument(
        query: String,
        page: Int
    ): Document? {
        val encoded = URLEncoder.encode(
            query.trim(),
            StandardCharsets.UTF_8.toString()
        )

        val candidates = linkedSetOf(
            "$mainUrl/browse?keyword=$encoded" +
                if (page > 1) "&page=$page" else "",

            "$mainUrl/search?keyword=$encoded" +
                if (page > 1) "&page=$page" else ""
        )

        for (url in candidates) {
            val document = runCatching {
                app.get(
                    url,
                    headers = pageHeaders + mapOf(
                        "Referer" to "$mainUrl/"
                    )
                ).document
            }.getOrNull()

            if (document != null) {
                val cards = parseCards(document)
                if (cards.isNotEmpty()) {
                    return document
                }
            }
        }

        return null
    }

    private fun searchScore(
        query: String,
        title: String
    ): Int {
        val q = query
            .lowercase(Locale.ROOT)
            .replace(Regex("[^a-z0-9\\p{L}]+"), "")
        val t = title
            .lowercase(Locale.ROOT)
            .replace(Regex("[^a-z0-9\\p{L}]+"), "")

        if (q.isBlank() || t.isBlank()) return 0
        if (q == t) return 1000
        if (t.startsWith(q)) return 900
        if (t.contains(q)) return 800

        val qTokens = query
            .lowercase(Locale.ROOT)
            .split(Regex("\\s+"))
            .filter { it.length >= 2 }

        val tLower = title.lowercase(Locale.ROOT)

        return qTokens.count {
            tLower.contains(it)
        } * 100
    }

    override suspend fun search(
        query: String,
        page: Int
    ): SearchResponseList {
        val q = cleanText(query)

        if (q.isBlank()) {
            return newSearchResponseList(
                emptyList(),
                false
            )
        }

        val document = searchDocument(
            query = q,
            page = page.coerceAtLeast(1)
        ) ?: return newSearchResponseList(
            emptyList(),
            false
        )

        val results = parseCards(document)
            .sortedByDescending {
                searchScore(q, it.name)
            }

        return newSearchResponseList(
            results,
            hasNextPage(document)
        )
    }

    private fun animeIdFromDocument(
        document: Document
    ): String? {
        val selectors = listOf(
            "#ani_detail[data-anime-id]",
            ".anis-content[data-anime-id]",
            "[data-anime-id]"
        )

        for (selector in selectors) {
            document
                .selectFirst(selector)
                ?.attr("data-anime-id")
                ?.trim()
                ?.takeIf { it.isNotBlank() }
                ?.let { return it }
        }

        /*
         * Standard HiAnime watch pages also expose:
         * <script id="syncData">{"anime_id":"..." ...}</script>
         */
        document.select("script").forEach { script ->
            val raw = script.data().trim()
            if (raw.isBlank()) return@forEach

            val match = Regex(
                """"anime_id"\s*:\s*"?(\d+)"?""",
                RegexOption.IGNORE_CASE
            ).find(raw)

            match
                ?.groupValues
                ?.getOrNull(1)
                ?.takeIf { it.isNotBlank() }
                ?.let { return it }
        }

        return null
    }

    private fun episodeIdFromUrl(
        url: String
    ): String? {
        return runCatching {
            URI(url).rawQuery
                ?.split('&')
                ?.firstOrNull {
                    it.substringBefore('=')
                        .equals("ep", true)
                }
                ?.substringAfter('=')
                ?.takeIf { it.isNotBlank() }
        }.getOrNull()
    }

    private fun parseEpisodeItems(
        html: String,
        seasonNumber: Int = 1
    ): List<Episode> {
        if (html.isBlank()) return emptyList()

        val document = Jsoup.parse(
            html,
            "$mainUrl/"
        )

        val links = document.select(
            "a.ssl-item[data-id][href], " +
                "a.ep-item[data-id][href], " +
                "a[data-id][href*='/watch/'], " +
                "#episodes-content a[data-id][href]"
        )

        return links.mapNotNull { link ->
            val episodeId = link.attr("data-id").trim()
            val href = link.attr("href").trim()

            if (episodeId.isBlank() || href.isBlank()) {
                return@mapNotNull null
            }

            val number =
                link.attr("data-number").toIntOrNull()
                    ?: link.attr("data-episode-number").toIntOrNull()
                    ?: Regex(
                        """(?:episode|ep\.?)\s*(\d+)""",
                        RegexOption.IGNORE_CASE
                    )
                        .find(
                            cleanText(
                                link.attr("title").ifBlank {
                                    link.text()
                                }
                            )
                        )
                        ?.groupValues
                        ?.getOrNull(1)
                        ?.toIntOrNull()

            val title = cleanText(
                link.selectFirst(
                    ".ep-name.e-dynamic-name, " +
                        ".ep-name, " +
                        ".e-dynamic-name"
                )?.text()
            ).ifBlank {
                cleanText(link.attr("title"))
            }.ifBlank {
                cleanText(link.text())
            }.ifBlank {
                number?.let { "Episode $it" } ?: "Episode"
            }

            newEpisode(
                absoluteUrl(href)
            ) {
                name = title
                episode = number
                season = seasonNumber
                data = "${absoluteUrl(href)}||$episodeId"
            }
        }
            .distinctBy { it.data }
            .sortedWith(
                compareBy<Episode> {
                    it.season ?: seasonNumber
                }.thenBy {
                    it.episode ?: Int.MAX_VALUE
                }
            )
    }

    private fun episodesFromJson(
        rawJson: String,
        seasonNumber: Int
    ): List<Episode> {
        val result = ArrayList<Episode>()

        val json = runCatching {
            org.json.JSONObject(rawJson)
        }.getOrNull() ?: return result

        fun readArray(obj: org.json.JSONObject): org.json.JSONArray? {
            val direct = obj.optJSONArray("episodes")
            if (direct != null) return direct

            val data = obj.optJSONObject("data")
            data?.optJSONArray("episodes")?.let { return it }

            val resultObj = obj.optJSONObject("result")
            resultObj?.optJSONArray("episodes")?.let { return it }

            return null
        }

        val array = readArray(json) ?: return result

        for (index in 0 until array.length()) {
            val item = array.optJSONObject(index) ?: continue

            val id = item.optString(
                "id",
                item.optString(
                    "episodeId",
                    item.optString("episode_id")
                )
            ).trim()

            if (id.isBlank()) continue

            val number =
                item.optInt(
                    "number",
                    item.optInt(
                        "episodeNumber",
                        item.optInt("episode_number", index + 1)
                    )
                )

            val title =
                cleanText(
                    item.optString(
                        "title",
                        item.optString(
                            "name",
                            "Episode $number"
                        )
                    )
                ).ifBlank {
                    "Episode $number"
                }

            val href =
                item.optString(
                    "href",
                    item.optString("url")
                ).trim().ifBlank {
                    null
                }

            val episodeUrl = if (href != null) {
                absoluteUrl(href)
            } else {
                /*
                 * Some API wrappers return the episode id but not href.
                 * Only build the canonical watch URL when the response also
                 * supplies a usable anime slug/path elsewhere.
                 */
                continue
            }

            result += newEpisode(episodeUrl) {
                name = title
                episode = number
                season = seasonNumber
                data = "$episodeUrl||$id"
            }
        }

        return result
            .distinctBy { it.data }
            .sortedBy { it.episode ?: Int.MAX_VALUE }
    }

    private suspend fun getEpisodesFromApi(
        animeId: String,
        seasonNumber: Int = 1,
        referer: String = "$mainUrl/"
    ): List<Episode> {

        /*
         * The HTML source supplied for hianime.at exposes both:
         *   window.hianime_ajax.rest_url
         *   window.hianime_ep_ajax.rest_url
         * and the page loads watch.min.js.
         *
         * The deployed site is not guaranteed to expose the old hianime.to
         * AJAX paths unchanged, so try the standard endpoint first and then
         * the site's /api/theme/ REST-style variants.
         */
        val endpoints = listOf(
            "$mainUrl/ajax/v2/episode/list/$animeId",
            "$mainUrl/api/theme/episode/list/$animeId",
            "$mainUrl/api/theme/episodes/$animeId",
            "$mainUrl/api/theme/anime/$animeId/episodes",
            "$mainUrl/api/theme/episode/list?anime_id=$animeId",
            "$mainUrl/api/theme/episodes?anime_id=$animeId"
        ).distinct()

        val headers = pageHeaders + mapOf(
            "Referer" to referer,
            "Origin" to mainUrl,
            "Accept" to "application/json, text/javascript, */*; q=0.01",
            "X-Requested-With" to "XMLHttpRequest"
        )

        for (url in endpoints) {
            val response = runCatching {
                app.get(
                    url,
                    headers = headers
                )
            }.getOrNull() ?: continue

            val raw = response.text.trim()
            if (raw.isBlank()) continue

            /*
             * First: an HTML fragment returned by the classical HiAnime
             * episode endpoint.
             */
            val htmlCandidates = linkedSetOf<String>()

            runCatching {
                val json = org.json.JSONObject(raw)

                json.optString("html")
                    .takeIf { it.isNotBlank() }
                    ?.let { htmlCandidates.add(it) }

                json.optJSONObject("data")
                    ?.optString("html")
                    ?.takeIf { it.isNotBlank() }
                    ?.let { htmlCandidates.add(it) }

                json.keys().forEach { key ->
                    val value = json.optString(key)
                    if (
                        value.contains(
                            "ssl-item",
                            ignoreCase = true
                        ) ||
                        value.contains(
                            "data-id",
                            ignoreCase = true
                        )
                    ) {
                        htmlCandidates.add(value)
                    }
                }
            }

            if (
                raw.contains(
                    "ssl-item",
                    ignoreCase = true
                )
            ) {
                htmlCandidates.add(raw)
            }

            for (html in htmlCandidates) {
                val episodes = parseEpisodeItems(
                    html = html,
                    seasonNumber = seasonNumber
                )

                if (episodes.isNotEmpty()) {
                    return episodes
                }
            }

            /*
             * Second: some API wrappers return a JSON array of episode
             * objects instead of the HTML fragment.
             */
            val jsonEpisodes = episodesFromJson(
                rawJson = raw,
                seasonNumber = seasonNumber
            )

            if (jsonEpisodes.isNotEmpty()) {
                return jsonEpisodes
            }
        }

        return emptyList()
    }

    override suspend fun load(
        url: String
    ): LoadResponse? {
        val rawInput = url.substringBefore("||").trim()

        val forcedMovie = hasMarker(
            rawInput,
            movieMarker
        )
        val forcedTv = hasMarker(
            rawInput,
            tvMarker
        )

        val rawPageUrl = absoluteUrl(
            stripMarkers(rawInput)
        )

        val pageUrl = rawPageUrl
            .substringBefore("#")
            .let { value ->
                value
                    .substringBefore("?ep=")
                    .substringBefore("&ep=")
            }

        val response = runCatching {
            app.get(
                rawPageUrl,
                headers = pageHeaders + mapOf(
                    "Referer" to "$mainUrl/"
                )
            )
        }.getOrNull() ?: return null

        val document = response.document

        val canonical = document
            .selectFirst("link[rel=canonical]")
            ?.attr("href")
            ?.takeIf { it.isNotBlank() }
            ?.let(::absoluteUrl)
            ?: pageUrl

        val title = cleanTitle(
            document
                .selectFirst("meta[property=og:title]")
                ?.attr("content")
        ).ifBlank {
            cleanTitle(
                document.selectFirst(
                    ".film-name, h1"
                )?.text()
            )
        }.ifBlank {
            cleanTitle(document.title())
        }

        if (title.isBlank()) return null

        val poster = extractPoster(document)

        val plot = cleanText(
            document
                .selectFirst("meta[property=og:description]")
                ?.attr("content")
        ).ifBlank {
            cleanText(
                document.selectFirst(
                    ".film-description, " +
                        ".description-content, " +
                        ".description"
                )?.text()
            )
        }

        val year = extractYear(document)

        /*
         * HiAnime's actual watch-page source identifies TV episodes with:
         *
         *   #ani_detail[data-anime-id="240"][data-id="4402"]
         *   [data-episode-number="1"]
         *
         * and the page's film stats contain "TV".
         *
         * Therefore an episode URL such as:
         *   /watch/attack-on-titan-240?ep=4402
         * MUST resolve to the parent TV series, not a Movie response.
         */
        val aniDetail = document.selectFirst("#ani_detail")
        val animeId = animeIdFromDocument(document)
        val episodeNumber =
            aniDetail
                ?.attr("data-episode-number")
                ?.toIntOrNull()
                ?: episodeIdFromUrl(canonical)
                    ?.let { null }

        val isTvPage =
            !forcedMovie && (
                forcedTv ||
                    document
                        .selectFirst(".film-stats")
                        ?.text()
                        ?.contains("TV", true) == true ||
                    document
                        .selectFirst("#main-wrapper")
                        ?.classNames()
                        ?.contains("layout-page-watchtv") == true ||
                    aniDetail?.attr("data-episode-number")
                        ?.isNotBlank() == true
            )

        val currentEpisodeId =
            aniDetail
                ?.attr("data-id")
                ?.trim()
                ?.takeIf { it.isNotBlank() }
                ?: episodeIdFromUrl(canonical)

        /*
         * For TV:
         *
         * 1. Strip ?ep=... so CloudStream's series URL is stable.
         * 2. Fetch the complete episode list from the site's episode API.
         * 3. Always return TvSeries.
         *
         * This is the key fix for the "Play Movie / No Links Found" screen
         * shown in the user's test.
         */
        if (
            isTvPage &&
            animeId != null
        ) {
            val seriesUrl = pageUrl

            val seasonNumber =
                document
                    .selectFirst(
                        ".other-season .os-item.active .title"
                    )
                    ?.text()
                    ?.let { seasonText ->
                        Regex(
                            """Season\s+(\d+)""",
                            RegexOption.IGNORE_CASE
                        )
                            .find(seasonText)
                            ?.groupValues
                            ?.getOrNull(1)
                            ?.toIntOrNull()
                    }
                    ?: 1

            /*
             * First try the live episode AJAX source. This is the
             * authoritative source for the actual episode IDs.
             */
            var episodes =
                getEpisodesFromApi(
                    animeId = animeId,
                    seasonNumber = seasonNumber,
                    referer = seriesUrl
                )

            /*