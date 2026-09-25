package com.zoryva.zoryva

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URI
import java.net.URLEncoder
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Zoryva CloudStream provider.
 *
 * Design goals:
 * - Keep the Home layout limited to the four requested sections.
 * - Read the Zoryva website structure instead of inventing a second catalog.
 * - Treat Movies as movies and TV/Anime as episode-based series.
 * - Read fresh detail/player pages on every Play action.
 * - Never persist signed/tokenized media URLs between Play actions.
 * - Discover multiple playable servers and prefer the fastest usable source.
 * - Keep additional working servers available as fallbacks.
 * - Preserve the upstream Referer/Origin context when using Zoryva's proxy.
 * - Detect real HLS qualities from playlist metadata or URL paths.
 * - Prefer HLS masters when they contain audio/subtitle track groups.
 * - Collect external subtitle tracks when the source exposes them.
 * - Reject trailers, teasers, previews, clips and obvious promotional media.
 * - Keep the parser defensive because Zoryva is a Next.js application.
 */
class Zoryva : MainAPI() {

    private companion object {
        const val BASE_URL = "https://zoryva.me"

        const val TRENDING = "$BASE_URL/browse/trending"
        const val MOVIES = "$BASE_URL/browse/movie"
        const val TV_SHOW = "$BASE_URL/browse/tv"
        const val ANIME = "$BASE_URL/browse/anime"

        /* Home is intentionally page-sized: the provider itself never caps the
         * catalogue at an artificial item count. CloudStream asks for page 1,
         * page 2, page 3, ... until the real catalogue is exhausted. */
        const val INITIAL_HOME_ITEMS = 6
        const val HOME_PAGE_SIZE = 6
        const val HOME_INITIAL_ROUTE_TIMEOUT_MS = 3500L
        const val HOME_INITIAL_FALLBACK_TIMEOUT_MS = 2500L
        const val HOME_MAX_HTML_CHARS = 2_000_000
        const val HOME_RSC_CHUNK_MAX_CHARS = 700_000
        const val HOME_SITEMAP_CACHE_TTL_MS = 120_000L
        const val HOME_SITEMAP_CHILD_TIMEOUT_MS = 6000L
        const val HOME_SITEMAP_CHILD_CONCURRENCY = 4
        const val HOME_BROWSE_CACHE_TTL_MS = 60_000L
        const val HOME_BROWSE_PAGE_TIMEOUT_MS = 5500L
        const val HOME_PAGE_FETCH_CONCURRENCY = 1
        const val MAX_SEARCH_ITEMS = 50
        const val MAX_SERVER_PAGES = 12
        const val MAX_CRAWL_DEPTH = 2
        const val VIDROCK_ORIGIN = "https://vidrock.net"
        const val SOURCE_PROBE_TIMEOUT_MS = 7000L
        const val SERVER_PAGE_TIMEOUT_MS = 9000L
        const val HOME_PREFETCH_TIMEOUT_MS = 5000L
        const val HOME_CATALOG_CACHE_TTL_MS = 15000L

        // Playback resolver tuning. These values are intentionally bounded so
        // one broken server cannot block the whole player indefinitely.
        const val VIDROCK_KEY_TIMEOUT_MS = 5000L
        const val VIDROCK_API_TIMEOUT_MS = 7000L
        const val VIDROCK_SCRIPT_TIMEOUT_MS = 3500L
        const val VIDROCK_SCRIPT_LIMIT = 18
        const val VIDROCK_MAX_SERVERS = 12
        const val ZORYVA_EXTRACT_TIMEOUT_MS = 20000L
        const val ZORYVA_SCRAPED_TIMEOUT_MS = 7000L
        const val PLAYABILITY_GET_TIMEOUT_MS = 5000L
        const val PLAYABILITY_HEAD_TIMEOUT_MS = 3500L
        const val HLS_SEGMENT_PROBE_TIMEOUT_MS = 3000L
        const val MAX_EMITTED_FALLBACKS = 64

        // Advanced search tuning. Normal searches should finish from the
        // website's native search page; the heavier global fallback is used
        // only when native search cannot produce a useful match.
        const val SEARCH_RESULT_LIMIT = 50
        const val SEARCH_NATIVE_TIMEOUT_MS = 4500L
        const val SEARCH_HOME_TIMEOUT_MS = 5000L
        const val SEARCH_FALLBACK_TIMEOUT_MS = 8500L
        const val SEARCH_SITEMAP_LIMIT = 5000
        const val SEARCH_SITEMAP_CHILD_LIMIT = 16
        const val SEARCH_VERIFY_LIMIT = 12
        const val SEARCH_LOCAL_SCAN_LIMIT = 1600
        const val DETAIL_FETCH_ATTEMPTS = 3
        const val DETAIL_RETRY_DELAY_MS = 350L
        const val STRONG_SEARCH_SCORE = 0.78
        const val NORMAL_SEARCH_SCORE = 0.42

        const val USER_AGENT =
            "Mozilla/5.0 (Linux; Android 13; Mobile) " +
                "AppleWebKit/537.36 (KHTML, like Gecko) " +
                "Chrome/131.0.0.0 Mobile Safari/537.36"

        const val ACCEPT = "*/*"

        val PAGE_HEADERS = mapOf(
            "User-Agent" to USER_AGENT,
            "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
            "Accept-Language" to "en-US,en;q=0.9",
            "Cache-Control" to "no-cache",
            "Pragma" to "no-cache"
        )

        val MEDIA_EXTENSIONS = listOf(
            ".m3u8",
            ".mp4",
            ".mkv",
            ".webm",
            ".m4v",
            ".mov",
            ".avi",
            ".flv"
        )

        val TRAILER_WORDS = listOf(
            "trailer",
            "teaser",
            "preview",
            "clip",
            "promo",
            "promotional",
            "featurette",
            "behind-the-scenes",
            "behind the scenes"
        )

        val SEARCH_METADATA_WORDS = setOf(
            "movie", "movies", "film", "films", "series",
            "season", "seasons", "episode", "episodes", "ep",
            "hd", "hdtc", "web", "webdl", "webrip", "bluray",
            "dual", "audio", "dub", "dublado", "sub"
        )

        val IGNORED_HOST_PARTS = listOf(
            "youtube.com",
            "youtu.be",
            "youtube-nocookie.com",
            "vimeo.com",
            "facebook.com",
            "instagram.com",
            "twitter.com",
            "x.com",
            "googlevideo.com"
        )

        val PLAYER_WORDS = listOf(
            "player",
            "watch",
            "embed",
            "stream",
            "source",
            "server",
            "play",
            "video"
        )
    }

    override var mainUrl: String = BASE_URL
    override var name: String = "Zoryva"
    override var lang: String = "hi"

    override val hasMainPage: Boolean = true
    override val hasQuickSearch: Boolean = true

    override val supportedTypes = setOf(
        TvType.Movie,
        TvType.TvSeries,
        TvType.Anime
    )

    /**
     * Zoryva Home deliberately exposes only the four requested sections.
     */
    override val mainPage = mainPageOf(
        TRENDING to "Trending Now",
        MOVIES to "Movies",
        TV_SHOW to "TV Show",
        ANIME to "Anime"
    )

    private data class SiteItem(
        val title: String,
        val url: String,
        val poster: String?,
        val type: TvType,
        val aliases: List<String> = emptyList(),
        val lastModified: Long? = null
    )

    private data class SitemapEntry(
        val path: String,
        val lastModified: Long? = null,
        val sourceOrder: Long = 0L
    )

    private data class EpisodeInfo(
        val id: String?,
        val name: String,
        val season: Int,
        val episode: Int,
        val overview: String?,
        val poster: String?,
        val runtime: Int?,
        val airDate: String? = null,
        val score: Double? = null,
        val url: String? = null
    )

    private data class DetailInfo(
        val title: String,
        val poster: String?,
        val plot: String?,
        val year: Int?,
        val type: TvType,
        val episodes: List<EpisodeInfo>
    )

    private data class AudioTrackCandidate(
        val url: String,
        val label: String = "",
        val headers: Map<String, String> = emptyMap()
    )

    private data class MediaCandidate(
        val url: String,
        val referer: String,
        val origin: String,
        val quality: Int,
        val label: String,
        val server: String,
        val latencyMs: Long,
        val isHlsMaster: Boolean,
        val audioLabel: String,
        val isHls: Boolean = false,
        val audioTracks: List<AudioTrackCandidate> = emptyList(),
        val headers: Map<String, String> = emptyMap()
    )

    private data class PlaybackOutput(
        val url: String,
        val referer: String,
        val headers: Map<String, String>
    )

    private data class ServerResult(
        val serverUrl: String,
        val serverName: String,
        val latencyMs: Long,
        val sources: List<MediaCandidate>,
        val subtitles: List<Pair<String, String>>
    )

    private data class HlsInfo(
        val isMaster: Boolean,
        val hasAudioTracks: Boolean,
        val hasSubtitleTracks: Boolean,
        val maxQuality: Int,
        val audioLabel: String,
        val audioTracks: List<AudioTrackCandidate>,
        val subtitles: List<Pair<String, String>>,
        val variants: List<Pair<String, Int>>
    )

    private data class VidrockServerTarget(
        val url: String,
        val name: String,
        val referer: String = VIDROCK_ORIGIN + "/"
    )

    private data class VidrockResolution(
        val directSources: List<MediaCandidate>,
        val servers: List<VidrockServerTarget>
    )

    private data class ZoryvaExtractResolution(
        val directSources: List<MediaCandidate>,
        val servers: List<VidrockServerTarget>,
        val subtitles: List<Pair<String, String>> = emptyList(),
        val audioTracks: List<AudioTrackCandidate> = emptyList()
    )

    private data class MediaProbe(
        val status: Int,
        val contentType: String,
        val acceptRanges: String,
        val contentRange: String,
        val bodyPrefix: String
    )

    private data class PrimarySourceCheck(
        val source: MediaCandidate,
        val directPlayable: Boolean,
        val proxy: MediaCandidate?,
        val proxyPlayable: Boolean,
        val hls: HlsInfo? = null,
        val proxyHls: HlsInfo? = null
    )

    private data class CachedCatalog(
        val createdAt: Long,
        val items: List<SiteItem>
    )

    private data class CachedBrowsePage(
        val createdAt: Long,
        val items: List<SiteItem>
    )

    private val catalogCache = ConcurrentHashMap<String, CachedCatalog>()
    private val browsePageCache = ConcurrentHashMap<String, CachedBrowsePage>()
    private val catalogPrefetchMutex = Mutex()
    private val sitemapEntryCache = mutableListOf<SitemapEntry>()
    @Volatile private var sitemapCacheAt: Long = 0L
    private val sitemapMutex = Mutex()

    private val homeRoutes = listOf(
        TRENDING,
        MOVIES,
        TV_SHOW,
        ANIME
    )

    // ---------------------------------------------------------------------
    // HOME
    // ---------------------------------------------------------------------

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        val route = request.data
        val currentPage = page.coerceAtLeast(1)

        /*
         * Home must be cheap enough to run on low-memory devices.
         *
         * IMPORTANT:
         * - Never scrape all four categories for a single row request.
         * - Never crawl the sitemap while opening Home.
         * - Never verify detail pages just to render posters.
         * - Keep every request page-sized (6 items).
         *
         * CloudStream can ask several rows at once, so each row is isolated and
         * cached. A row only fetches its own browse page and returns immediately.
         */
        val items = loadHomePageLight(route, currentPage)

        return newHomePageResponse(
            request,
            items.map { it.toSearchResponse() },
            hasNext = items.size == HOME_PAGE_SIZE
        )
    }

    private suspend fun loadHomePageLight(
        route: String,
        page: Int
    ): List<SiteItem> {
        if (route !in homeRoutes) return emptyList()

        val cacheKey = "$route|$page"
        val now = System.currentTimeMillis()
        browsePageCache[cacheKey]?.let { cached ->
            if (now - cached.createdAt <= HOME_BROWSE_CACHE_TTL_MS) {
                return cached.items
            }
        }

        val pageUrl = buildBrowseUrl(route, page)

        /*
         * Do NOT use fetchPage() for Home. fetchPage() constructs a full Jsoup
         * Document from the complete response. On a Next.js page that can be a
         * very large HTML/RSC payload, and several Home rows may be requested at
         * the same time by CloudStream. That is unnecessary memory pressure.
         *
         * Home therefore reads only the response text and parses a bounded prefix.
         */
        val document = withTimeoutOrNull(
            if (page == 1) HOME_INITIAL_ROUTE_TIMEOUT_MS
            else HOME_BROWSE_PAGE_TIMEOUT_MS
        ) {
            runCatching {
                val response = app.get(
                    pageUrl,
                    headers = PAGE_HEADERS
                )
                if (response.code !in 200..399) {
                    null
                } else {
                    val text = response.text
                    val bounded = if (text.length > HOME_MAX_HTML_CHARS) {
                        text.substring(0, HOME_MAX_HTML_CHARS)
                    } else {
                        text
                    }
                    Jsoup.parse(bounded, pageUrl)
                }
            }.getOrNull()
        }

        if (document == null) {
            browsePageCache[cacheKey] = CachedBrowsePage(
                createdAt = System.currentTimeMillis(),
                items = emptyList()
            )
            return emptyList()
        }

        /*
         * First choice: ordinary DOM cards. This is by far the cheapest path.
         */
        var items = parseBrowseItemsLight(
            document = document,
            baseUrl = pageUrl,
            limit = HOME_PAGE_SIZE
        )

        /*
         * Next.js can render cards only inside Flight/RSC. We use a bounded RSC
         * reader that decodes one small Flight chunk at a time and stops as soon
         * as six matching media objects are found. It never concatenates the
         * complete homepage payload.
         */
        if (items.size < HOME_PAGE_SIZE) {
            items = mergeHomeRscItems(
                base = items,
                document = document,
                route = route,
                limit = HOME_PAGE_SIZE
            )
        }

        val clean = items
            .distinctBy { cleanUrl(it.url) }
            .take(HOME_PAGE_SIZE)

        browsePageCache[cacheKey] = CachedBrowsePage(
            createdAt = System.currentTimeMillis(),
            items = clean
        )

        if (clean.isNotEmpty()) {
            storeCatalog(route, clean)
        }

        return clean
    }

    private fun mergeHomeRscItems(
        base: List<SiteItem>,
        document: Document,
        route: String,
        limit: Int
    ): List<SiteItem> {
        if (base.size >= limit) return base.take(limit)

        val wantedType = when (route) {
            MOVIES -> TvType.Movie
            TV_SHOW -> TvType.TvSeries
            ANIME -> TvType.Anime
            TRENDING -> null
            else -> return base
        }

        val result = linkedMapOf<String, SiteItem>()
        base.forEach { result[cleanUrl(it.url)] = it }

        for (script in document.select("script")) {
            if (result.size >= limit) break

            val data = script.data().ifBlank { script.html() }
            if (!data.contains("__next_f", true)) continue

            val pushMarker = "push([1,\""
            var searchStart = 0

            while (result.size < limit) {
                val markerIndex = data.indexOf(pushMarker, searchStart)
                if (markerIndex < 0) break

                val quoteStart = markerIndex + "push([1,".length
                val decoded = decodeQuotedJsStringBounded(
                    text = data,
                    openingQuoteIndex = quoteStart,
                    maxChars = HOME_RSC_CHUNK_MAX_CHARS
                ) ?: break

                var mediaSearch = 0
                val mediaMarker = "\"mediaType\":\""

                while (result.size < limit) {
                    val typeIndex = decoded.indexOf(mediaMarker, mediaSearch)
                    if (typeIndex < 0) break

                    val objectStart = decoded.lastIndexOf('{', typeIndex)
                    if (objectStart < 0) break

                    val objectJson = extractBalancedJson(
                        decoded,
                        objectStart,
                        '{',
                        '}'
                    )

                    if (objectJson != null) {
                        val media = runCatching {
                            JSONObject(objectJson)
                        }.getOrNull()

                        if (media != null) {
                            val hint = when {
                                media.optString("mediaType").equals("movie", true) -> "movie"
                                media.optString("mediaType").equals("tv", true) -> "tv"
                                media.optString("mediaType").equals("series", true) -> "tv"
                                media.optString("mediaType").equals("anime", true) -> "anime"
                                else -> ""
                            }

                            val item = rscMediaToSiteItem(media, hint)
                            if (item != null &&
                                (wantedType == null || item.type == wantedType)
                            ) {
                                val key = cleanUrl(item.url)
                                if (key.isNotBlank()) result.putIfAbsent(key, item)
                            }
                        }
                    }

                    mediaSearch = typeIndex + mediaMarker.length
                }

                searchStart = quoteStart + 1
            }
        }

        return result.values.take(limit)
    }

    private fun decodeQuotedJsStringBounded(
        text: String,
        openingQuoteIndex: Int,
        maxChars: Int
    ): String? {
        if (openingQuoteIndex !in text.indices || text[openingQuoteIndex] != '"') {
            return null
        }

        val out = StringBuilder(minOf(maxChars, 32_768))
        var index = openingQuoteIndex + 1

        while (index < text.length && out.length <= maxChars) {
            val c = text[index]

            if (c == '"') return out.toString()

            if (c != '\\') {
                out.append(c)
                index++
                continue
            }

            if (index + 1 >= text.length) return null
            val escaped = text[index + 1]

            when (escaped) {
                '"' -> { out.append('"'); index += 2 }
                '\\' -> { out.append('\\'); index += 2 }
                '/' -> { out.append('/'); index += 2 }
                'b' -> { out.append('\b'); index += 2 }
                'f' -> { out.append('\u000C'); index += 2 }
                'n' -> { out.append('\n'); index += 2 }
                'r' -> { out.append('\r'); index += 2 }
                't' -> { out.append('\t'); index += 2 }
                'u' -> {
                    if (index + 5 >= text.length) return null
                    val hex = text.substring(index + 2, index + 6)
                    val code = hex.toIntOrNull(16) ?: return null
                    out.append(code.toChar())
                    index += 6
                }
                else -> {
                    out.append(escaped)
                    index += 2
                }
            }
        }

        return null
    }

    private fun parseBrowseItemsLight(
        document: Document,
        baseUrl: String,
        limit: Int
    ): List<SiteItem> {
        if (limit <= 0) return emptyList()

        val result = linkedMapOf<String, SiteItem>()

        for (anchor in document.select("a[href]")) {
            if (result.size >= limit) break

            val rawHref = anchor.attr("href").trim()
            val absolute = normalizeExtractedUrl(rawHref, baseUrl) ?: continue
            val path = runCatching { URI(absolute).path.orEmpty() }
                .getOrDefault("")

            val type = when {
                isMoviePath(path) -> TvType.Movie
                isAnimePath(path) && !isEpisodePath(path) -> TvType.Anime
                isTvPath(path) && !isEpisodePath(path) -> TvType.TvSeries
                else -> continue
            }

            val title = extractCardTitle(anchor)
                .ifBlank { titleFromPath(path) }
                .ifBlank { continue }

            val clean = cleanUrl(absolute)
            if (clean.isBlank()) continue

            result.putIfAbsent(
                clean,
                SiteItem(
                    title = title,
                    url = clean,
                    poster = extractCardPoster(anchor, baseUrl),
                    type = type
                )
            )
        }

        return result.values.toList()
    }

    /*
     * Kept for search warm-up compatibility, but it is now deliberately small.
     * Search has its own sitemap fallback and must not trigger a four-route Home
     * crawl that can compete with the UI for memory.
     */
    private suspend fun prefetchHomeCatalogs() {
        /*
         * Search warm-up is intentionally small. Home itself never calls this
         * function; search may use it to populate the four row caches gradually.
         */
        for (route in homeRoutes) {
            if (catalogCache[route]?.items.isNullOrEmpty()) {
                loadHomePageLight(route, 1)
            }
        }
    }

    private fun parseHomepageRscCatalogs(
        document: Document
    ): Map<String, List<SiteItem>> {
        /*
         * IMPORTANT: Zoryva's home catalog is transported through Next.js
         * Flight/RSC scripts. Those scripts contain a JavaScript string inside
         * self.__next_f.push([1, "..."]). A normal HTML/JSON unescape is not
         * enough here because quotes are escaped inside the nested JS string.
         * Decode the actual quoted Flight payload first, then parse initialRows.
         */
        val decodedPayload = extractNextFlightPayload(document)
        val decoded = if (decodedPayload.contains("\"initialRows\":", true)) {
            decodedPayload
        } else {
            /* Keep the legacy path as a defensive fallback for SSR changes. */
            decodeRscForCatalog(document.html())
        }

        if (!decoded.contains("\"initialRows\":", true)) {
            return emptyMap()
        }

        val heroItems = extractRscArray(decoded, "\"initialHeroItems\":")
            ?.let(::parseRscMediaArray)
            .orEmpty()

        val rows = extractRscArray(decoded, "\"initialRows\":") ?: return emptyMap()
        val rowsArray = runCatching { JSONArray(rows) }.getOrNull() ?: return emptyMap()

        val routeRows = linkedMapOf<String, MutableList<SiteItem>>()

        for (i in 0 until rowsArray.length()) {
            val row = rowsArray.optJSONObject(i) ?: continue
            val key = row.optString("key").trim().lowercase(Locale.ROOT)
            if (key.isBlank()) continue

            val items = row.optJSONArray("items") ?: continue
            val target = when {
                key == "trending" -> TRENDING
                key == "popular-movies" ||
                    key == "new-releases" ||
                    key == "top-rated" ||
                    key == "recently-added" ||
                    key == "recommended" -> MOVIES
                key == "popular-tv" ||
                    key == "trending-tv" ||
                    key == "latest-tv" -> TV_SHOW
                key == "popular-anime" ||
                    key == "trending-anime" ||
                    key == "latest-anime" -> ANIME
                else -> null
            } ?: continue

            val output = routeRows.getOrPut(target) { mutableListOf() }

            for (j in 0 until items.length()) {
                val value = items.opt(j)
                val media = when (value) {
                    is JSONObject -> value
                    is String -> resolveRscHeroReference(value, heroItems)
                    else -> null
                } ?: continue

                val item = rscMediaToSiteItem(media, key) ?: continue

                val belongsToCategory = when (target) {
                    MOVIES -> item.type == TvType.Movie
                    TV_SHOW -> item.type == TvType.TvSeries
                    ANIME -> item.type == TvType.Anime
                    TRENDING -> true
                    else -> false
                }
                if (!belongsToCategory) continue

                if (output.none { cleanUrl(it.url) == cleanUrl(item.url) }) {
                    output += item
                }
            }
        }

        return routeRows
            .mapValues { (_, items) -> items.distinctBy { cleanUrl(it.url) } }
    }

    /**
     * Decode the Next.js Flight payloads embedded in <script> tags.
     * The website uses:
     *
     *   self.__next_f.push([1,"..."])
     *
     * The second argument is a JavaScript quoted string and may contain nested
     * JSON/RSC escapes. We parse that quoted string explicitly instead of doing
     * a blind replace operation which would leave \" sequences behind.
     */
    private fun extractNextFlightPayload(
        document: Document
    ): String {
        val output = StringBuilder()
        val marker = "push([1,\""

        document.select("script").forEach { script ->
            val data = script.data().ifBlank { script.html() }
            if (!data.contains("__next_f", true)) return@forEach

            var searchStart = 0
            while (true) {
                val markerIndex = data.indexOf(marker, searchStart)
                if (markerIndex < 0) break

                val quoteStart = markerIndex + "push([1,".length
                val decoded = decodeQuotedJsString(data, quoteStart)
                    ?: break

                if (decoded.contains("initialRows") ||
                    decoded.contains("initialHeroItems") ||
                    decoded.contains("initialMedia")) {
                    output.append(decoded).append('\n')
                }

                val nextIndex = quoteStart + 1
                searchStart = nextIndex
                if (searchStart >= data.length) break
            }
        }

        return output.toString()
    }

    private fun decodeQuotedJsString(
        text: String,
        openingQuoteIndex: Int
    ): String? {
        if (openingQuoteIndex !in text.indices || text[openingQuoteIndex] != '"') {
            return null
        }

        val out = StringBuilder()
        var index = openingQuoteIndex + 1

        while (index < text.length) {
            val c = text[index]

            if (c == '"') {
                return out.toString()
            }

            if (c != '\\') {
                out.append(c)
                index++
                continue
            }

            if (index + 1 >= text.length) return null
            val escaped = text[index + 1]

            when (escaped) {
                '"' -> { out.append('"'); index += 2 }
                '\\' -> { out.append('\\'); index += 2 }
                '/' -> { out.append('/'); index += 2 }
                'b' -> { out.append('\b'); index += 2 }
                'f' -> { out.append('\u000C'); index += 2 }
                'n' -> { out.append('\n'); index += 2 }
                'r' -> { out.append('\r'); index += 2 }
                't' -> { out.append('\t'); index += 2 }
                'u' -> {
                    if (index + 5 >= text.length) return null
                    val hex = text.substring(index + 2, index + 6)
                    val code = hex.toIntOrNull(16) ?: return null
                    out.append(code.toChar())
                    index += 6
                }
                else -> {
                    /* JavaScript permits escaped non-special characters. */
                    out.append(escaped)
                    index += 2
                }
            }
        }

        return null
    }

    private fun resolveRscHeroReference(
        value: String,
        heroItems: List<JSONObject>
    ): JSONObject? {
        val marker = "initialHeroItems:"
        val index = value.lastIndexOf(marker)
        if (index < 0) return null
        val heroIndex = value.substring(index + marker.length).toIntOrNull() ?: return null
        return heroItems.getOrNull(heroIndex)
    }

    private fun rscMediaToSiteItem(
        media: JSONObject,
        rowKey: String
    ): SiteItem? {
        val title = media.optString("title")
            .trim()
            .takeIf { it.isNotBlank() }
            ?: return null

        val aliases = buildList {
            media.optString("originalTitle")
                .trim()
                .takeIf { it.isNotBlank() && !it.equals(title, true) }
                ?.let(::add)
            media.optString("name")
                .trim()
                .takeIf { it.isNotBlank() && !it.equals(title, true) }
                ?.let(::add)
            media.optString("slug")
                .trim()
                .takeIf { it.isNotBlank() }
                ?.replace('-', ' ')
                ?.replace('_', ' ')
                ?.let(::add)
        }

        val mediaType = media.optString("mediaType")
            .trim()
            .lowercase(Locale.ROOT)

        val type = when {
            mediaType == "movie" -> TvType.Movie
            mediaType == "tv" || mediaType == "series" -> TvType.TvSeries
            mediaType == "anime" || rowKey.contains("anime") -> TvType.Anime
            else -> return null
        }

        val externalId = when (val raw = media.opt("externalId")) {
            is Number -> raw.toLong().toString()
            is String -> raw.trim().takeIf { it.isNotBlank() }
            else -> null
        } ?: media.optString("id")
            .substringAfterLast("-")
            .takeIf { it.matches(Regex("\\d+")) }
            ?: return null

        val path = when (type) {
            TvType.Movie -> "movie"
            TvType.TvSeries -> "tv"
            TvType.Anime -> "anime"
            else -> return null
        }

        val poster = media.optString("posterPath")
            .trim()
            .takeIf { it.startsWith("http", true) }

        return SiteItem(
            title = cleanCardTitle(title),
            url = "$BASE_URL/$path/$externalId",
            poster = poster,
            type = type,
            aliases = aliases.map(::cleanCardTitle),
            lastModified = parseSitemapTimestamp(
                firstNonBlank(
                    media.optString("updatedAt"),
                    media.optString("modifiedAt"),
                    media.optString("lastModified"),
                    media.optString("publishedAt"),
                    media.optString("createdAt"),
                    media.optString("addedAt"),
                    media.optString("dateAdded"),
                    media.optString("uploadDate"),
                    media.optString("uploadedAt")
                )
            )
        )
    }

    private fun extractRscArray(
        decoded: String,
        marker: String
    ): String? {
        val index = decoded.indexOf(marker)
        if (index < 0) return null
        val start = decoded.indexOf('[', index + marker.length)
        if (start < 0) return null
        return extractBalancedJson(decoded, start, '[', ']')
    }

    private fun parseRscMediaArray(
        arrayJson: String
    ): List<JSONObject> {
        val array = runCatching { JSONArray(arrayJson) }.getOrNull() ?: return emptyList()
        return buildList {
            for (i in 0 until array.length()) {
                array.optJSONObject(i)?.let(::add)
            }
        }
    }

    private fun decodeRscForCatalog(
        input: String
    ): String {
        var text = input
        repeat(2) {
            text = decodeRscOneLayer(text)
                .replace("\\:", ":")
                .replace("\\.", ".")
        }
        return text
    }

    private fun buildBrowseItemsFromDocument(
        document: Document,
        baseUrl: String
    ): List<SiteItem> {
        return parseBrowseItems(document, baseUrl)
            .distinctBy { cleanUrl(it.url) }
    }

    private suspend fun getSitemapMediaEntries(): List<SitemapEntry> {
        val now = System.currentTimeMillis()
        if (now - sitemapCacheAt <= HOME_SITEMAP_CACHE_TTL_MS) {
            return synchronized(sitemapEntryCache) {
                sitemapEntryCache.toList()
            }
        }

        return sitemapMutex.withLock {
            val lockedNow = System.currentTimeMillis()
            if (lockedNow - sitemapCacheAt <= HOME_SITEMAP_CACHE_TTL_MS) {
                return@withLock synchronized(sitemapEntryCache) {
                    sitemapEntryCache.toList()
                }
            }

            val roots = listOf(
                "$BASE_URL/sitemap.xml",
                "$BASE_URL/sitemap_index.xml",
                "$BASE_URL/sitemap-index.xml"
            )

            var rootDocument: Document? = null
            for (root in roots) {
                val candidate = runCatching { getDocument(root) }.getOrNull()
                if (candidate != null && candidate.select("loc").isNotEmpty()) {
                    rootDocument = candidate
                    break
                }
            }

            val root = rootDocument ?: return@withLock emptyList()
            var orderCounter = 0L

            fun parseUrlEntries(document: Document): List<SitemapEntry> {
                val result = ArrayList<SitemapEntry>()
                val urlNodes = document.select("url")

                if (urlNodes.isNotEmpty()) {
                    urlNodes.forEach { node ->
                        val loc = node.selectFirst("loc")?.text()?.trim().orEmpty()
                        if (loc.isBlank()) return@forEach

                        val path = runCatching { URI(loc).path.orEmpty() }
                            .getOrDefault("")
                        if (!isSearchContentPath(path)) return@forEach

                        result += SitemapEntry(
                            path = normalizePathForSearch(path),
                            lastModified = parseSitemapTimestamp(
                                node.selectFirst("lastmod")?.text()
                            ),
                            sourceOrder = orderCounter++
                        )
                    }
                } else {
                    /* Defensive support for feeds that omit <url> wrappers. */
                    document.select("loc").forEach { element ->
                        val loc = element.text().trim()
                        if (loc.isBlank()) return@forEach
                        val path = runCatching { URI(loc).path.orEmpty() }
                            .getOrDefault("")
                        if (!isSearchContentPath(path)) return@forEach

                        result += SitemapEntry(
                            path = normalizePathForSearch(path),
                            lastModified = null,
                            sourceOrder = orderCounter++
                        )
                    }
                }

                return result
            }

            val directEntries = parseUrlEntries(root).toMutableList()
            val childUrls = linkedSetOf<String>()

            root.select("sitemap > loc, sitemap loc").forEach { element ->
                val loc = element.text().trim()
                if (loc.isBlank()) return@forEach
                val path = runCatching { URI(loc).path.orEmpty() }
                    .getOrDefault("")
                if (
                    path.endsWith(".xml", true) ||
                    path.contains("sitemap", true)
                ) {
                    childUrls += cleanUrl(loc)
                }
            }

            /* Fetch every child sitemap, but cap concurrency rather than the
             * number of children. This removes the old 512-child artificial
             * ceiling without opening hundreds of simultaneous connections. */
            childUrls.chunked(HOME_SITEMAP_CHILD_CONCURRENCY).forEach { batch ->
                val children = coroutineScope {
                    batch.map { url ->
                        async {
                            withTimeoutOrNull(HOME_SITEMAP_CHILD_TIMEOUT_MS) {
                                getDocument(url)
                            }
                        }
                    }.awaitAll()
                }

                children.forEach { document ->
                    document ?: return@forEach
                    directEntries += parseUrlEntries(document)
                }
            }

            val merged = linkedMapOf<String, SitemapEntry>()
            directEntries.forEach { entry ->
                val existing = merged[entry.path]
                if (existing == null) {
                    merged[entry.path] = entry
                } else if (
                    existing.lastModified == null && entry.lastModified != null
                ) {
                    merged[entry.path] = existing.copy(
                        lastModified = entry.lastModified
                    )
                } else if (
                    existing.lastModified != null &&
                        entry.lastModified != null &&
                        entry.lastModified > existing.lastModified
                ) {
                    merged[entry.path] = entry
                }
            }

            val result = merged.values.sortedWith(
                compareByDescending<SitemapEntry> {
                    it.lastModified ?: Long.MIN_VALUE
                }.thenBy { it.sourceOrder }
            )

            synchronized(sitemapEntryCache) {
                sitemapEntryCache.clear()
                sitemapEntryCache.addAll(result)
            }
            sitemapCacheAt = lockedNow
            result
        }
    }

    private fun getCachedCatalog(
        route: String
    ): CachedCatalog? {
        /* Freshness controls refetching; it must not erase pagination state. */
        return catalogCache[route]
    }

    private fun storeCatalog(
        route: String,
        items: List<SiteItem>
    ) {
        if (items.isEmpty()) return

        val merged = linkedMapOf<String, SiteItem>()
        (catalogCache[route]?.items.orEmpty() + items).forEach { item ->
            val key = cleanUrl(item.url)
            if (key.isBlank()) return@forEach

            val existing = merged[key]
            if (existing == null) {
                merged[key] = item
            } else {
                merged[key] = existing.copy(
                    poster = existing.poster ?: item.poster,
                    aliases = (existing.aliases + item.aliases)
                        .filter { it.isNotBlank() }
                        .distinct(),
                    lastModified = item.lastModified ?: existing.lastModified
                )
            }
        }

        catalogCache[route] = CachedCatalog(
            createdAt = System.currentTimeMillis(),
            items = merged.values.toList()
        )
    }

    private fun hasExplicitNextPage(
        document: Document?
    ): Boolean {
        if (document == null) return false

        return document.select("a[href]").any { anchor ->
            val text = anchor.text().trim().lowercase(Locale.ROOT)
            val aria = anchor.attr("aria-label").trim().lowercase(Locale.ROOT)
            text == "next" ||
                text.contains("next page") ||
                aria.contains("next")
        }
    }

    private fun sameCatalog(
        first: List<SiteItem>,
        second: List<SiteItem>
    ): Boolean {
        if (first.isEmpty() || second.isEmpty()) return false

        val firstKeys = first.take(12).map { cleanUrl(it.url) }.toSet()
        val secondKeys = second.take(12).map { cleanUrl(it.url) }.toSet()
        if (firstKeys.isEmpty() || secondKeys.isEmpty()) return false

        val overlap = firstKeys.intersect(secondKeys).size
        return overlap >= maxOf(1, (minOf(firstKeys.size, secondKeys.size) * 0.75).toInt())
    }

    private fun buildBrowseUrl(
        route: String,
        page: Int
    ): String {
        if (page <= 1) return route

        val separator = if (route.contains("?")) "&" else "?"
        return "$route${separator}page=$page"
    }

    private fun parseBrowseItems(
        document: Document,
        baseUrl: String = BASE_URL
    ): List<SiteItem> {
        val result = linkedMapOf<String, SiteItem>()

        /* Standard SSR/DOM links. */
        document.select("a[href]").forEach { anchor ->
            val rawHref = anchor.attr("href").trim()
            val absolute = normalizeExtractedUrl(rawHref, baseUrl) ?: return@forEach
            val path = runCatching { URI(absolute).path.orEmpty() }.getOrDefault("")

            val type = when {
                isMoviePath(path) -> TvType.Movie
                isAnimePath(path) -> TvType.Anime
                isTvPath(path) && !isEpisodePath(path) -> TvType.TvSeries
                else -> return@forEach
            }

            val title = extractCardTitle(anchor)
                .ifBlank { titleFromPath(path) }
                .ifBlank { return@forEach }

            result.putIfAbsent(
                cleanUrl(absolute),
                SiteItem(
                    title = title,
                    url = absolute,
                    poster = extractCardPoster(anchor, baseUrl),
                    type = type
                )
            )
        }

        /*
         * Next.js client-rendered browse pages may contain no media anchors at
         * all. The RSC payload can still expose canonical media objects.
         */
        val decoded = decodeRscForCatalog(document.html())
        addDecodedRscMediaObjects(
            decoded = decoded,
            defaultRoute = baseUrl,
            result = result
        )

        /*
         * Some captured/escaped payloads contain literal markdown-wrapped
         * hrefs. Accept those too, but always normalize them back to the real
         * canonical Zoryva URL before emitting a result.
         */
        val html = document.html()
        val markdownHrefRegex = Regex(
            """(?i)href=\"\[/((?:movie|tv|anime)/[^\"\\]]+)\]\((https?://[^\)]+)\)\""""
        )
        markdownHrefRegex.findAll(html).forEach { match ->
            val absolute = normalizeExtractedUrl(match.groupValues[2], baseUrl) ?: return@forEach
            val path = runCatching { URI(absolute).path.orEmpty() }.getOrDefault("")
            val type = when {
                isMoviePath(path) -> TvType.Movie
                isAnimePath(path) && !isEpisodePath(path) -> TvType.Anime
                isTvPath(path) && !isEpisodePath(path) -> TvType.TvSeries
                else -> return@forEach
            }
            val nearby = html.substring(
                maxOf(0, match.range.first - 1800),
                minOf(html.length, match.range.last + 1800)
            )
            val title = Regex("(?is)(?:alt|title)=\\\"([^\\\"]+)\\\"")
                .find(nearby)
                ?.groupValues
                ?.getOrNull(1)
                ?.let(::cleanCardTitle)
                .orEmpty()
                .ifBlank { titleFromPath(path) }

            if (title.isNotBlank()) {
                result.putIfAbsent(
                    cleanUrl(absolute),
                    SiteItem(
                        title = title,
                        url = absolute,
                        poster = null,
                        type = type
                    )
                )
            }
        }

        return result.values.toList()
    }

    private fun addDecodedRscMediaObjects(
        decoded: String,
        defaultRoute: String,
        result: MutableMap<String, SiteItem>
    ) {
        val seenStarts = HashSet<Int>()
        val marker = "\"mediaType\":\""
        var searchStart = 0

        while (true) {
            val mediaTypeIndex = decoded.indexOf(marker, searchStart)
            if (mediaTypeIndex < 0) break

            val objectStart = decoded.lastIndexOf('{', mediaTypeIndex)
            if (objectStart < 0 || !seenStarts.add(objectStart)) {
                searchStart = mediaTypeIndex + marker.length
                continue
            }

            val json = extractBalancedJson(decoded, objectStart, '{', '}')
            if (json != null) {
                val media = runCatching { JSONObject(json) }.getOrNull()
                val typeHint = runCatching { URI(defaultRoute).path.orEmpty() }
                    .getOrDefault("")
                    .trim('/')
                    .split('/')
                    .firstOrNull()
                    .orEmpty()
                if (media != null) {
                    val item = rscMediaToSiteItem(media, typeHint)
                    if (item != null) {
                        result.putIfAbsent(cleanUrl(item.url), item)
                    }
                }
            }

            searchStart = mediaTypeIndex + marker.length
        }
    }

    private fun hasMoreBrowsePage(
        document: Document,
        items: List<SiteItem>
    ): Boolean {
        if (items.isEmpty()) return false
        return hasExplicitNextPage(document)
    }

    // ---------------------------------------------------------------------
    // SEARCH
    // ---------------------------------------------------------------------

    override suspend fun search(query: String): List<SearchResponse> {
        val original = query.trim()
        if (original.isBlank()) return emptyList()

        /*
         * Professional search pipeline:
         *
         * 1) Search Zoryva's own native search endpoint.
         * 2) In parallel, refresh the four-section RSC catalog so an item that
         *    is already visible on Home is searchable even when /search is a
         *    client-rendered shell.
         * 3) Merge both real-Zoryva result sets and rank them locally.
         * 4) Use Jaro-Winkler + token similarity so queries such as
         *    "research" can still match the real title "Reacher".
         * 5) Only then use the sitemap/detail-page fallback.
         *
         * No outside catalog is introduced and no fake media URL is generated.
         */
        val (warm, native) = coroutineScope {
            val warmJob = async {
                withTimeoutOrNull(SEARCH_HOME_TIMEOUT_MS) {
                    prefetchHomeCatalogs()
                    homeRoutes
                        .flatMap { route -> getCachedCatalog(route)?.items.orEmpty() }
                        .distinctBy { cleanUrl(it.url) }
                }.orEmpty()
            }

            val nativeJob = async {
                withTimeoutOrNull(SEARCH_NATIVE_TIMEOUT_MS) {
                    searchNative(original)
                }.orEmpty()
            }

            warmJob.await() to nativeJob.await()
        }

        val localRanked = rankSearchItems(original, warm)

        val nativeItems = native.mapNotNull { response ->
            response.url?.takeIf { it.startsWith(BASE_URL, true) }?.let { url ->
                val parsedType = when {
                    isTvPath(runCatching { URI(url).path.orEmpty() }.getOrDefault("")) -> TvType.TvSeries
                    isAnimePath(runCatching { URI(url).path.orEmpty() }.getOrDefault("")) -> TvType.Anime
                    isMoviePath(runCatching { URI(url).path.orEmpty() }.getOrDefault("")) -> TvType.Movie
                    else -> return@let null
                }
                val title = response.name
                    .trim()
                    .takeIf { it.isNotBlank() }
                    ?: return@let null
                SiteItem(title, cleanUrl(url), null, parsedType)
            }
        }

        val merged = linkedMapOf<String, Pair<SiteItem, Double>>()
        localRanked.forEach { (item, score) ->
            merged[cleanUrl(item.url)] = item to score
        }
        nativeItems.forEach { item ->
            val score = searchScore(original, item.title)
            val key = cleanUrl(item.url)
            val existing = merged[key]
            if (existing == null || score > existing.second) {
                merged[key] = item to score
            }
        }

        val ranked = merged.values
            .filter { it.second >= NORMAL_SEARCH_SCORE }
            .sortedWith(
                compareByDescending<Pair<SiteItem, Double>> { it.second }
                    .thenBy { it.first.title.lowercase(Locale.ROOT) }
            )

        if (ranked.isNotEmpty()) {
            return ranked
                .take(SEARCH_RESULT_LIMIT)
                .map { it.first.toSearchResponse() }
        }

        return withTimeoutOrNull(SEARCH_FALLBACK_TIMEOUT_MS) {
            searchFallback(original)
        }.orEmpty()
    }

    private suspend fun searchNative(
        original: String
    ): List<SearchResponse> {
        val exactUrl = buildSearchUrl(original)
        val exactDocument = getDocument(exactUrl)

        if (exactDocument != null) {
            val exactItems = parseBrowseItems(exactDocument, exactUrl)
                .distinctBy { cleanUrl(it.url) }
            val ranked = rankSearchItems(original, exactItems)

            /*
             * Strong native matches are trusted immediately. This keeps normal
             * searches fast and avoids unnecessary extra network requests.
             */
            if (ranked.any { it.second >= STRONG_SEARCH_SCORE }) {
                return ranked
                    .take(SEARCH_RESULT_LIMIT)
                    .map { it.first.toSearchResponse() }
            }

            /*
             * The site returned real results, even if the local scorer was not
             * confident enough. Keep those results rather than manufacturing a
             * fuzzy hit from unrelated pages.
             */
            if (exactItems.isNotEmpty()) {
                val useful = ranked
                    .filter { it.second >= NORMAL_SEARCH_SCORE }
                    .take(SEARCH_RESULT_LIMIT)

                if (useful.isNotEmpty()) {
                    return useful.map { it.first.toSearchResponse() }
                }
            }
        }

        /*
         * Small concurrent variant pass. These are normalization variants only;
         * they are not a full-site crawl and therefore remain quick.
         */
        val variants = buildSearchVariants(original)
            .drop(1)
            .take(3)
            .toList()

        if (variants.isEmpty()) return emptyList()

        val documents = coroutineScope {
            variants.map { variant ->
                async {
                    getDocument(buildSearchUrl(variant))
                }
            }.awaitAll()
        }

        val merged = linkedMapOf<String, SiteItem>()
        documents.forEachIndexed { index, document ->
            document ?: return@forEachIndexed
            val variantUrl = buildSearchUrl(variants.getOrNull(index) ?: original)
            parseBrowseItems(document, variantUrl).forEach { item ->
                merged.putIfAbsent(cleanUrl(item.url), item)
            }
        }

        val ranked = rankSearchItems(original, merged.values.toList())
        if (ranked.isEmpty()) return emptyList()

        return ranked
            .take(SEARCH_RESULT_LIMIT)
            .map { it.first.toSearchResponse() }
    }

    private suspend fun searchFallback(
        original: String
    ): List<SearchResponse> {
        val paths = fetchSearchSitemapPaths()
        if (paths.isEmpty()) return emptyList()

        val rankedPaths = paths
            .map { path ->
                val titleGuess = titleFromPath(path)
                path to searchScore(original, titleGuess)
            }
            .filter { it.second >= NORMAL_SEARCH_SCORE }
            .sortedWith(
                compareByDescending<Pair<String, Double>> { it.second }
                    .thenBy { it.first }
            )
            .take(SEARCH_VERIFY_LIMIT * 2)

        if (rankedPaths.isEmpty()) return emptyList()

        /*
         * Verify the highest-confidence sitemap candidates against their real
         * Zoryva detail pages. This prevents a fuzzy slug-only match from being
         * returned unless the actual page exists and exposes a usable title.
         */
        val verified = coroutineScope {
            rankedPaths.map { (path, score) ->
                async {
                    val item = verifySitemapCandidate(path)
                    if (item == null) null else item to score
                }
            }.awaitAll().filterNotNull()
        }

        return verified
            .sortedWith(
                compareByDescending<Pair<SiteItem, Double>> { pair ->
                    /* Re-score against the verified title for better ordering. */
                    maxOf(pair.second, searchScore(original, pair.first.title))
                }.thenBy { pair ->
                    pair.first.title.lowercase(Locale.ROOT)
                }
            )
            .take(SEARCH_RESULT_LIMIT)
            .map { it.first.toSearchResponse() }
    }

    private fun buildSearchUrl(
        query: String
    ): String {
        return "$BASE_URL/search?q=${encode(query)}"
    }

    private fun rankSearchItems(
        original: String,
        items: List<SiteItem>
    ): List<Pair<SiteItem, Double>> {
        return items
            .map { item ->
                val aliasScore = item.aliases.maxOfOrNull { alias ->
                    searchScore(original, alias)
                } ?: 0.0
                item to maxOf(
                    searchScore(original, item.title),
                    aliasScore
                )
            }
            .filter { it.second >= NORMAL_SEARCH_SCORE }
            .sortedWith(
                compareByDescending<Pair<SiteItem, Double>> { it.second }
                    .thenBy { it.first.title.lowercase(Locale.ROOT) }
            )
    }

    private suspend fun fetchSearchSitemapPaths(): List<String> {
        val roots = listOf(
            "$BASE_URL/sitemap.xml",
            "$BASE_URL/sitemap_index.xml",
            "$BASE_URL/sitemap-index.xml"
        )

        var indexDocument: Document? = null
        for (root in roots) {
            val document = getDocument(root) ?: continue
            if (document.select("loc").isNotEmpty()) {
                indexDocument = document
                break
            }
        }

        val document = indexDocument ?: return emptyList()
        val locs = document.select("loc")
            .mapNotNull { it.text().trim().takeIf(String::isNotBlank) }

        val directPaths = linkedSetOf<String>()
        val childSitemaps = ArrayList<String>()

        for (loc in locs) {
            val path = runCatching {
                URI(loc).path.orEmpty()
            }.getOrDefault("")

            if (isSearchContentPath(path)) {
                directPaths += normalizePathForSearch(path)
            } else if (
                path.endsWith(".xml", true) ||
                    path.contains("sitemap", true)
            ) {
                childSitemaps += cleanUrl(loc)
            }
        }

        if (directPaths.size >= SEARCH_SITEMAP_LIMIT || childSitemaps.isEmpty()) {
            return directPaths.take(SEARCH_SITEMAP_LIMIT)
        }

        val children = childSitemaps
            .distinct()
            .take(SEARCH_SITEMAP_CHILD_LIMIT)

        val childDocuments = coroutineScope {
            children.map { child ->
                async { getDocument(child) }
            }.awaitAll()
        }

        childDocuments.forEach { child ->
            child ?: return@forEach
            child.select("loc").forEach { loc ->
                val value = loc.text().trim()
                if (value.isBlank()) return@forEach

                val path = runCatching {
                    URI(value).path.orEmpty()
                }.getOrDefault("")

                if (isSearchContentPath(path)) {
                    directPaths += normalizePathForSearch(path)
                }
            }
        }

        return directPaths.take(SEARCH_SITEMAP_LIMIT)
    }

    private suspend fun verifySitemapCandidate(
        path: String
    ): SiteItem? {
        val url = "$BASE_URL${normalizePathForSearch(path)}"
        val document = getDocument(url) ?: return null

        val parsed = parseBrowseItems(document, url)
            .firstOrNull { cleanUrl(it.url) == cleanUrl(url) }
            ?: run {
                parseDetail(document, url)?.let { info ->
                    SiteItem(
                        title = info.title,
                        url = url,
                        poster = info.poster,
                        type = info.type
                    )
                }
            }

        return parsed ?: run {
            val title = firstNonBlank(
                extractMeta(document, "property=og:title"),
                document.selectFirst("h1")?.text(),
                document.title()
            )?.let(::cleanDetailTitle)
                ?.takeIf { it.isNotBlank() }
                ?: return null

            SiteItem(
                title = title,
                url = url,
                poster = firstNonBlank(
                    extractMeta(document, "property=og:image"),
                    extractMeta(document, "name=twitter:image")
                ),
                type = typeFromContentPath(path)
            )
        }
    }

    private fun buildSearchVariants(
        query: String
    ): LinkedHashSet<String> {
        val normalized = normalizeSearch(query)
        val compact = normalized.replace(" ", "")
        val reduced = normalized
            .split(' ')
            .filterNot {
                it in SEARCH_METADATA_WORDS
            }
            .joinToString(" ")

        val punctuationFolded = searchComparisonText(query)

        return linkedSetOf<String>().apply {
            add(query)
            if (normalized.isNotBlank()) add(normalized)
            if (punctuationFolded.isNotBlank()) add(punctuationFolded)
            if (reduced.isNotBlank()) add(reduced)
            if (compact.isNotBlank()) add(compact)
        }
    }


    private fun searchComparisonText(
        value: String
    ): String {
        return value
            .lowercase(Locale.ROOT)
            .replace("&", " and ")
            .replace(Regex("""[\u2010\u2011\u2012\u2013\u2014\u2212]"""), "-")
            .replace(Regex("""[^\p{L}\p{N}\s]+"""), " ")
            .replace(Regex("""\s+"""), " ")
            .trim()
    }

    private fun searchScore(
        query: String,
        title: String
    ): Double {
        val q = normalizeSearch(query)
        val t = normalizeSearch(title)

        if (q.isBlank() || t.isBlank()) return 0.0
        if (q == t) return 1.0

        val qp = searchComparisonText(query)
        val tp = searchComparisonText(title)

        if (qp.isNotBlank() && qp == tp) {
            return 0.995
        }

        var score = fullStringSimilarity(qp, tp)
        val qCompact = q.replace(" ", "")
        val tCompact = t.replace(" ", "")

        if (t.contains(q)) {
            val ratio = q.length.toDouble() / t.length.coerceAtLeast(1)
            score = maxOf(score, 0.96 + minOf(0.04, ratio * 0.04))
        }

        if (qCompact.isNotBlank() && tCompact.contains(qCompact)) {
            val ratio = qCompact.length.toDouble() /
                tCompact.length.coerceAtLeast(1).toDouble()
            score = maxOf(score, 0.88 + minOf(0.10, ratio * 0.10))
        }

        /* Human typos are often better captured by Jaro-Winkler than raw edit distance.
         * Example: "research" vs "reacher" gets a strong similarity despite several
         * insertions/deletions, while exact/substring matches above still dominate.
         */
        if (q.length >= 4 && t.length >= 4) {
            score = maxOf(
                score,
                jaroWinkler(q, t),
                jaroWinkler(qCompact, tCompact)
            )
        }

        val qTokens = q.split(' ').filter { it.length >= 2 }
        val tTokens = t.split(' ').filter { it.length >= 2 }

        if (qTokens.isEmpty() || tTokens.isEmpty()) {
            return maxOf(score, fullStringSimilarity(q, t))
                .coerceIn(0.0, 1.0)
        }

        val tokenScores = qTokens.map { qt ->
            tTokens.maxOfOrNull { tt ->
                when {
                    qt == tt -> 1.0
                    tt.startsWith(qt) || qt.startsWith(tt) -> 0.94
                    tt.contains(qt) || qt.contains(tt) -> 0.90
                    else -> tokenSimilarity(qt, tt)
                }
            } ?: 0.0
        }

        val average = tokenScores.average()
        score = maxOf(score, average * 0.94)

        val covered = tokenScores.count { it >= 0.62 }
        val coverage = covered.toDouble() / qTokens.size.toDouble()
        score = maxOf(score, 0.45 + coverage * 0.50)

        /* Reward order-independent adjacency of the main query words. */
        val orderedQuery = qTokens.joinToString("")
        val orderedTitle = tTokens.joinToString("")
        if (orderedTitle.contains(orderedQuery)) {
            score = maxOf(score, 0.91)
        }

        /* Acronym/initials support for titles such as "Game of Thrones". */
        val initials = tTokens
            .mapNotNull { it.firstOrNull() }
            .joinToString("")
        if (qCompact == initials || initials.startsWith(qCompact)) {
            score = maxOf(score, 0.90)
        }

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
                titleYear == queryYear -> score = maxOf(score, 0.97)
                titleYear != null -> score *= 0.92
            }
        }

        return score.coerceIn(0.0, 1.0)
    }

    private fun tokenSimilarity(
        a: String,
        b: String
    ): Double {
        if (a == b) return 1.0
        if (a.isBlank() || b.isBlank()) return 0.0

        val distance = levenshtein(a, b)
        val longest = maxOf(a.length, b.length)
        if (longest == 0) return 1.0

        var result = 1.0 - distance.toDouble() / longest.toDouble()

        /* One adjacent transposition is a very common human typo. */
        if (result < 0.85 && a.length == b.length && a.length >= 2) {
            var mismatch = -1
            for (i in 0 until a.length) {
                if (a[i] != b[i]) {
                    mismatch = i
                    break
                }
            }

            if (
                mismatch >= 0 &&
                    mismatch + 1 < a.length &&
                    a[mismatch] == b[mismatch + 1] &&
                    a[mismatch + 1] == b[mismatch]
            ) {
                result = maxOf(result, 0.92)
            }
        }

        return result.coerceIn(0.0, 1.0)
    }

    private fun jaroWinkler(
        first: String,
        second: String
    ): Double {
        if (first == second) return 1.0
        if (first.isBlank() || second.isBlank()) return 0.0

        val a = first
        val b = second
        val matchDistance = (maxOf(a.length, b.length) / 2) - 1
        if (matchDistance < 0) return 0.0

        val aMatches = BooleanArray(a.length)
        val bMatches = BooleanArray(b.length)
        var matches = 0

        for (i in a.indices) {
            val start = maxOf(0, i - matchDistance)
            val end = minOf(i + matchDistance + 1, b.length)
            for (j in start until end) {
                if (bMatches[j] || a[i] != b[j]) continue
                aMatches[i] = true
                bMatches[j] = true
                matches++
                break
            }
        }

        if (matches == 0) return 0.0

        val aSequence = CharArray(matches)
        val bSequence = CharArray(matches)
        var ai = 0
        var bi = 0

        for (i in a.indices) {
            if (aMatches[i]) aSequence[ai++] = a[i]
        }
        for (j in b.indices) {
            if (bMatches[j]) bSequence[bi++] = b[j]
        }

        var transpositions = 0
        for (i in 0 until matches) {
            if (aSequence[i] != bSequence[i]) transpositions++
        }

        val m = matches.toDouble()
        val jaro = (
            m / a.length.toDouble() +
                m / b.length.toDouble() +
                (m - transpositions / 2.0) / m
            ) / 3.0

        var prefix = 0
        val prefixLimit = minOf(4, minOf(a.length, b.length))
        while (prefix < prefixLimit && a[prefix] == b[prefix]) {
            prefix++
        }

        return (jaro + prefix * 0.1 * (1.0 - jaro)).coerceIn(0.0, 1.0)
    }

    private fun fullStringSimilarity(
        a: String,
        b: String
    ): Double {
        if (a == b) return 1.0
        if (a.isBlank() || b.isBlank()) return 0.0

        val distance = levenshtein(a, b)
        val longest = maxOf(a.length, b.length)
        return if (longest == 0) {
            1.0
        } else {
            1.0 - distance.toDouble() / longest.toDouble()
        }
    }

    private fun typeFromContentPath(
        path: String
    ): TvType {
        return when {
            isAnimePath(path) -> TvType.Anime
            isTvPath(path) -> TvType.TvSeries
            else -> TvType.Movie
        }
    }

    private fun isSearchContentPath(
        path: String
    ): Boolean {
        if (path.isBlank()) return false
        if (isEpisodePath(path)) return false
        return isMoviePath(path) || isTvPath(path) || isAnimePath(path)
    }

    private fun normalizePathForSearch(
        path: String
    ): String {
        val normalized = path.trim()
            .removePrefix(BASE_URL)
            .trim()
        return if (normalized.startsWith("/")) normalized else "/$normalized"
    }


    // ---------------------------------------------------------------------
    // DETAIL / EPISODES
    // ---------------------------------------------------------------------

    override suspend fun load(url: String): LoadResponse? {
        val clean = cleanUrl(url)
        if (clean.isBlank()) return null

        val document = getDocument(clean) ?: return null
        val info = parseDetail(document, clean) ?: return null

        when (info.type) {
            TvType.Movie -> {
                return newMovieLoadResponse(
                    info.title,
                    clean,
                    TvType.Movie,
                    clean
                ) {
                    posterUrl = info.poster
                    plot = info.plot
                    year = info.year
                }
            }

            TvType.Anime,
            TvType.TvSeries -> {
                val hydratedInfo = hydrateEpisodeMetadata(
                    seriesInfo = info,
                    seriesUrl = clean
                )

                val seriesImdbId = extractImdbId(document.html())

                val episodes = hydratedInfo.episodes.map { ep ->
                    val candidateEpisodeUrl =
                        ep.url?.takeIf { it.startsWith("http", true) }

                    val episodeUrl = candidateEpisodeUrl
                        ?.takeIf { candidate ->
                            val candidateContext = parsePlaybackContext(candidate)
                            candidateContext?.season == ep.season &&
                                candidateContext.episode == ep.episode
                        }
                        ?: buildEpisodeContextUrl(
                            seriesUrl = clean,
                            season = ep.season,
                            episode = ep.episode
                        )

                    val playbackJson = JSONObject().apply {
                        ep.id?.let { put("episodeId", it) }
                        put("episodeTitle", ep.name)
                        put("seriesTitle", hydratedInfo.title)
                        firstNonBlank(
                            extractInitialMediaObject(document)?.optString("originalTitle"),
                            extractMeta(document, "property=og:title"),
                            hydratedInfo.title
                        )?.let { put("originalTitle", it) }
                        seriesImdbId?.let { put("imdbId", it) }
                        ep.runtime?.let { put("runtime", it) }
                        ep.airDate?.let { put("airDate", it) }
                        put("season", ep.season)
                        put("episode", ep.episode)
                        put("episodeUrl", episodeUrl)
                    }.toString()

                    newEpisode(
                        buildString {
                            append(episodeUrl)
                            append("||")
                            append(ep.id.orEmpty())
                            append("||")
                            append(encode(playbackJson))
                        }
                    ) {
                        name = ep.name
                        season = ep.season
                        episode = ep.episode
                        posterUrl = ep.poster
                        description = ep.overview
                        runTime = ep.runtime
                        date = ep.airDate?.let(::parseEpisodeDateMillis)
                        score = ep.score?.let { Score.from10(it) }
                    }
                }

                return newTvSeriesLoadResponse(
                    hydratedInfo.title,
                    clean,
                    info.type,
                    episodes
                ) {
                    posterUrl = hydratedInfo.poster
                    plot = hydratedInfo.plot
                    year = hydratedInfo.year
                }
            }

            else -> return null
        }
    }

    private fun parseDetail(
        document: Document,
        pageUrl: String
    ): DetailInfo? {
        val path = runCatching { URI(pageUrl).path.orEmpty() }.getOrDefault("")

        val mediaObject = extractInitialMediaObject(document)

        val mediaType = firstNonBlank(
            mediaObject?.optString("mediaType"),
            mediaObject?.optString("contentType")
        )?.lowercase(Locale.ROOT)

        val type = when {
            isAnimePath(path) || mediaType == "anime" -> TvType.Anime
            isTvPath(path) || mediaType == "tv" || mediaType == "series" -> TvType.TvSeries
            isMoviePath(path) || mediaType == "movie" -> TvType.Movie
            else -> return null
        }

        val title = firstNonBlank(
            mediaObject?.optString("title"),
            extractMeta(document, "property=og:title"),
            document.selectFirst("h1")?.text(),
            titleFromPath(path)
        )?.let(::cleanDetailTitle)
            ?: return null

        val poster = firstNonBlank(
            mediaObject?.optString("posterPath"),
            extractMeta(document, "property=og:image"),
            extractMeta(document, "name=twitter:image")
        )

        val plot = firstNonBlank(
            mediaObject?.optString("overview"),
            extractMeta(document, "property=og:description"),
            document.selectFirst("[class*=overview], [class*=description], p")?.text()
        )

        val year = mediaObject?.optString("releaseYear")
            ?.toIntOrNull()
            ?: Regex("\\b(19|20)\\d{2}\\b")
                .find(document.title())
                ?.value
                ?.toIntOrNull()

        val episodes = if (type == TvType.Movie) {
            emptyList()
        } else {
            val embedded = parseEpisodes(mediaObject)

            val needsPageEpisodeRecovery =
                embedded.isEmpty() ||
                    embedded.any {
                        it.name.matches(Regex("""(?i)Episode\s+\d+""")) ||
                            it.id.isNullOrBlank() ||
                            it.overview.isNullOrBlank() ||
                            it.runtime == null ||
                            it.airDate.isNullOrBlank()
                    }

            val pageEmbedded = if (needsPageEpisodeRecovery) {
                parseEpisodeObjectsFromPage(document)
            } else {
                emptyList()
            }

            mergeEpisodeMetadata(
                embedded = embedded,
                linked = parseEpisodeLinks(document, pageUrl),
                pageEmbedded = pageEmbedded
            )
        }

        return DetailInfo(
            title = title,
            poster = poster,
            plot = plot,
            year = year,
            type = type,
            episodes = episodes
        )
    }

    private fun parseEpisodes(
        mediaObject: JSONObject?
    ): List<EpisodeInfo> {
        val seasons = mediaObject?.optJSONArray("seasons") ?: return emptyList()
        val result = ArrayList<EpisodeInfo>()

        for (i in 0 until seasons.length()) {
            val season = seasons.optJSONObject(i) ?: continue
            val seasonNumber = season.optInt("seasonNumber", -1)
            if (seasonNumber < 0) continue

            val episodes = season.optJSONArray("episodes") ?: continue

            for (j in 0 until episodes.length()) {
                val episode = episodes.optJSONObject(j) ?: continue
                val number = episode.optInt("episodeNumber", -1)
                if (number < 0) continue

                result += EpisodeInfo(
                    id = episode.optString("id").takeIf { it.isNotBlank() },
                    name = firstNonBlank(
                        episode.optString("name"),
                        "Episode $number"
                    ) ?: "Episode $number",
                    season = seasonNumber,
                    episode = number,
                    overview = firstNonBlank(
                        episode.optString("overview"),
                        episode.optString("description")
                    )?.takeIf { it.isNotBlank() },
                    poster = firstNonBlank(
                        episode.optString("stillPath"),
                        episode.optString("posterPath")
                    )?.takeIf { it.isNotBlank() },
                    runtime = episode.optInt("runtime", 0).takeIf { it > 0 },
                    airDate = normalizeEpisodeDate(
                        firstNonBlank(
                            episode.optString("airDate"),
                            episode.optString("releaseDate"),
                            episode.optString("air_date")
                        )
                    ),
                    score = firstNonBlank(
                        episode.optString("voteAverage"),
                        episode.optString("rating"),
                        episode.optString("score")
                    )?.toDoubleOrNull()?.takeIf { it in 0.0..10.0 }
                )
            }
        }

        return result.sortedWith(
            compareBy<EpisodeInfo> { it.season }
                .thenBy { it.episode }
        )
    }


    /**
     * Recover explicit episode objects from the decoded Next.js/Flight page.
     * Only objects that explicitly contain seasonNumber + episodeNumber are
     * accepted. No item is created from episodeCount alone.
     */
    private fun parseEpisodeObjectsFromPage(
        document: Document
    ): List<EpisodeInfo> {
        val result = linkedMapOf<String, EpisodeInfo>()

        val payloads = linkedSetOf<String>()
        listOf(
            extractNextFlightPayload(document),
            decodeRscForCatalog(document.html()),
            decodeRscOneLayer(document.html()),
            normalizeEmbeddedText(document.html())
        ).forEach { payload ->
            if (payload.isNotBlank()) payloads += payload
        }

        fun addObject(rawObject: String) {
            val obj = runCatching { JSONObject(rawObject) }.getOrNull()
                ?: return

            val season = firstNonBlank(
                obj.optString("seasonNumber"),
                obj.optString("season")
            )?.toIntOrNull()?.takeIf { it >= 0 }
                ?: return

            val episode = firstNonBlank(
                obj.optString("episodeNumber"),
                obj.optString("episode")
            )?.toIntOrNull()?.takeIf { it > 0 }
                ?: return

            val explicitUrl = firstNonBlank(
                obj.optString("url"),
                obj.optString("href"),
                obj.optString("link"),
                obj.optString("episodeUrl")
            )?.trim()?.let { raw ->
                normalizeExtractedUrl(raw, document.baseUri())
            }

            val item = EpisodeInfo(
                id = firstNonBlank(
                    obj.optString("id"),
                    obj.optString("episodeId")
                )?.trim()?.takeIf { it.isNotBlank() },
                name = firstNonBlank(
                    obj.optString("name"),
                    obj.optString("title")
                )?.trim()?.takeIf { it.isNotBlank() }
                    ?: "Episode $episode",
                season = season,
                episode = episode,
                overview = firstNonBlank(
                    obj.optString("overview"),
                    obj.optString("description")
                )?.trim()?.takeIf { it.isNotBlank() },
                poster = firstNonBlank(
                    obj.optString("stillPath"),
                    obj.optString("posterPath"),
                    obj.optString("image"),
                    obj.optString("poster")
                )?.trim()?.takeIf { it.startsWith("http", true) },
                runtime = firstNonBlank(
                    obj.optString("runtime"),
                    obj.optString("duration")
                )?.toIntOrNull()?.takeIf { it > 0 },
                airDate = normalizeEpisodeDate(
                    firstNonBlank(
                        obj.optString("airDate"),
                        obj.optString("releaseDate"),
                        obj.optString("air_date")
                    )
                ),
                score = firstNonBlank(
                    obj.optString("voteAverage"),
                    obj.optString("rating"),
                    obj.optString("score")
                )?.toDoubleOrNull()?.takeIf { it in 0.0..10.0 },
                url = explicitUrl
            )

            val key = "$season|$episode"
            result[key] = result[key]?.let { mergeEpisodeInfo(it, item) } ?: item
        }

        val marker = Regex("""(?i)"episodeNumber"\s*:\s*\d+""")

        payloads.forEach { payload ->
            marker.findAll(payload)
                .take(1200)
                .forEach { match ->
                    var cursor = match.range.first
                    repeat(10) {
                        val objectStart = payload.lastIndexOf('{', cursor)
                        if (objectStart < 0) return@repeat

                        val objectJson = extractBalancedJson(
                            payload,
                            objectStart,
                            '{',
                            '}'
                        )

                        if (
                            objectJson != null &&
                            objectJson.contains("\"episodeNumber\"", true)
                        ) {
                            addObject(objectJson)
                            return@repeat
                        }

                        cursor = objectStart - 1
                    }
                }
        }

        return result.values
            .sortedWith(
                compareBy<EpisodeInfo> { it.season }
                    .thenBy { it.episode }
            )
    }

    private fun parseEpisodeLinks(
        document: Document,
        pageUrl: String
    ): List<EpisodeInfo> {
        val result = linkedMapOf<String, EpisodeInfo>()

        fun cleanEpisodeTitle(value: String): String {
            return value
                .replace(Regex("""^\s*\d+\s*[.)-]\s*"""), "")
                .replace(
                    Regex("""(?i)^\s*Episode\s*#?\d+\s*[:.)-]?\s*"""),
                    ""
                )
                .replace(Regex("""\s+"""), " ")
                .trim()
        }

        fun runtimeFromText(value: String): Int? {
            val text = value.replace(Regex("""\s+"""), " ").trim()

            Regex("""(?i)\b(\d{1,2})\s*h\s*(\d{1,2})\s*m\b""")
                .find(text)
                ?.let {
                    val hours = it.groupValues[1].toIntOrNull() ?: 0
                    val minutes = it.groupValues[2].toIntOrNull() ?: 0
                    return hours * 60 + minutes
                }

            return Regex("""(?i)\b(\d{1,3})\s*(?:m|min|minutes)\b""")
                .find(text)
                ?.groupValues
                ?.getOrNull(1)
                ?.toIntOrNull()
                ?.takeIf { it > 0 }
        }

        fun scoreFromText(value: String): Double? {
            return Regex(
                """(?<![\d.])(?:10(?:\.0)?|[0-9]\.[0-9])(?=\s|$)"""
            )
                .find(value.replace(Regex("""\s+"""), " "))
                ?.value
                ?.toDoubleOrNull()
                ?.takeIf { it in 0.0..10.0 }
        }

        fun dateFromText(value: String): String? {
            return Regex(
                """(?i)\b(?:Jan(?:uary)?|Feb(?:ruary)?|Mar(?:ch)?|Apr(?:il)?|May|Jun(?:e)?|Jul(?:y)?|Aug(?:ust)?|Sep(?:t(?:ember)?)?|Oct(?:ober)?|Nov(?:ember)?|Dec(?:ember)?)\s+\d{1,2},\s+\d{4}\b"""
            )
                .find(value)
                ?.value
                ?.let(::normalizeEpisodeDate)
        }

        document.select("a[href]").forEach { anchor ->
            val href = normalizeExtractedUrl(
                anchor.attr("href"),
                pageUrl
            ) ?: return@forEach

            val path = runCatching { URI(href).path.orEmpty() }
                .getOrDefault("")

            if (!isEpisodePath(path)) return@forEach

            val parts = path.trim('/')
                .split('/')
                .filter { it.isNotBlank() }

            if (parts.size < 4) return@forEach

            val season = parts[parts.size - 2].toIntOrNull()
                ?: return@forEach
            val episode = parts.lastOrNull()?.toIntOrNull()
                ?: return@forEach

            if (season < 0 || episode <= 0) return@forEach

            var current: Element? = anchor
            var name: String? = null
            var overview: String? = null
            var poster: String? = extractCardPoster(anchor, pageUrl)
            var runtime: Int? = null
            var airDate: String? = null
            var score: Double? = null

            repeat(6) {
                val container = current ?: return@repeat

                if (name == null) {
                    name = firstNonBlank(
                        container.attr("data-title"),
                        container.attr("data-name"),
                        container.select("h2,h3,h4,h5,h6,strong")
                            .map { cleanEpisodeTitle(it.text()) }
                            .firstOrNull {
                                it.isNotBlank() &&
                                    !it.equals("Episodes", true) &&
                                    !it.matches(Regex("""(?i)Episode\s+\d+"""))
                            },
                        anchor.attr("aria-label"),
                        anchor.attr("title"),
                        anchor.text()
                    )
                        ?.let(::cleanEpisodeTitle)
                        ?.takeIf { it.isNotBlank() }
                }

                if (overview == null) {
                    overview = firstNonBlank(
                        container.attr("data-overview"),
                        container.attr("data-description"),
                        container.select("[data-overview],[data-description]")
                            .map { it.text() }
                            .firstOrNull(),
                        container.select("p, [class*=overview], [class*=description]")
                            .map {
                                it.text()
                                    .replace(Regex("""\s+"""), " ")
                                    .trim()
                            }
                            .filter { it.length >= 30 }
                            .maxByOrNull { it.length }
                    )
                        ?.takeIf { it.length >= 20 }
                }

                if (poster == null) {
                    poster = container.select("img")
                        .mapNotNull {
                            firstNonBlank(
                                it.absUrl("src"),
                                it.attr("src"),
                                it.attr("data-src")
                            )
                        }
                        .firstOrNull { it.startsWith("http", true) }
                }

                val text = container.text()
                    .replace(Regex("""\s+"""), " ")
                    .trim()

                if (runtime == null) {
                    runtime = firstNonBlank(
                        container.attr("data-runtime"),
                        container.attr("data-duration")
                    )?.toIntOrNull()?.takeIf { it > 0 }
                        ?: runtimeFromText(text)
                }

                if (airDate == null) {
                    airDate = firstNonBlank(
                        container.attr("data-air-date"),
                        container.attr("data-date"),
                        container.attr("datetime")
                    )?.let(::normalizeEpisodeDate)
                        ?: dateFromText(text)
                }

                if (score == null) {
                    score = firstNonBlank(
                        container.attr("data-rating"),
                        container.attr("data-score")
                    )?.toDoubleOrNull()?.takeIf { it in 0.0..10.0 }
                        ?: scoreFromText(text)
                }

                if (
                    name != null &&
                    overview != null &&
                    runtime != null &&
                    airDate != null
                ) {
                    return@repeat
                }

                current = container.parent()
            }

            val item = EpisodeInfo(
                id = null,
                name = name?.takeIf { it.isNotBlank() }
                    ?: "Episode $episode",
                season = season,
                episode = episode,
                overview = overview,
                poster = poster,
                runtime = runtime,
                airDate = airDate,
                score = score,
                url = href
            )

            val key = "$season|$episode"
            result[key] = result[key]?.let { mergeEpisodeInfo(it, item) } ?: item
        }

        return result.values
            .sortedWith(
                compareBy<EpisodeInfo> { it.season }
                    .thenBy { it.episode }
            )
    }

    private fun mergeEpisodeInfo(
        first: EpisodeInfo,
        second: EpisodeInfo
    ): EpisodeInfo {
        val firstGeneric = first.name.matches(
            Regex("""(?i)Episode\s+\d+""")
        )
        val secondGeneric = second.name.matches(
            Regex("""(?i)Episode\s+\d+""")
        )

        return first.copy(
            id = first.id ?: second.id,
            name = if (firstGeneric && !secondGeneric) {
                second.name
            } else {
                first.name
            },
            overview = first.overview
                ?.takeIf { it.isNotBlank() }
                ?: second.overview,
            poster = first.poster
                ?.takeIf { it.isNotBlank() }
                ?: second.poster,
            runtime = first.runtime
                ?.takeIf { it > 0 }
                ?: second.runtime,
            airDate = first.airDate
                ?.takeIf { it.isNotBlank() }
                ?: second.airDate,
            score = first.score ?: second.score,
            url = first.url
                ?.takeIf { it.isNotBlank() }
                ?: second.url
        )
    }

    private fun mergeEpisodeMetadata(
        embedded: List<EpisodeInfo>,
        linked: List<EpisodeInfo>,
        pageEmbedded: List<EpisodeInfo>
    ): List<EpisodeInfo> {
        val result = linkedMapOf<String, EpisodeInfo>()

        fun add(item: EpisodeInfo) {
            val key = "${item.season}|${item.episode}"
            val existing = result[key]
            result[key] = if (existing == null) {
                item
            } else {
                mergeEpisodeInfo(existing, item)
            }
        }

        embedded.forEach(::add)
        pageEmbedded.forEach(::add)
        linked.forEach(::add)

        return result.values
            .sortedWith(
                compareBy<EpisodeInfo> { it.season }
                    .thenBy { it.episode }
            )
    }


    private suspend fun hydrateEpisodeMetadata(
        seriesInfo: DetailInfo,
        seriesUrl: String
    ): DetailInfo {
        if (seriesInfo.episodes.isEmpty()) return seriesInfo

        val incomplete = seriesInfo.episodes.filter { episode ->
            episode.name.matches(Regex("""(?i)Episode\s+\d+""")) ||
                episode.overview.isNullOrBlank() ||
                episode.poster.isNullOrBlank() ||
                episode.runtime == null ||
                episode.airDate.isNullOrBlank()
        }

        if (incomplete.isEmpty()) return seriesInfo

        val hydrated = coroutineScope {
            incomplete.map { episode ->
                async {
                    val episodeUrl =
                        episode.url?.takeIf { it.startsWith("http", true) }
                            ?: buildEpisodeUrl(
                                baseUrl = seriesUrl,
                                title = seriesInfo.title,
                                season = episode.season,
                                episode = episode.episode
                            )

                    val page = withTimeoutOrNull(4500L) {
                        getDocument(episodeUrl)
                    } ?: return@async episode

                    hydrateSingleEpisode(
                        baseEpisode = episode,
                        page = page,
                        pageUrl = episodeUrl
                    )
                }
            }.awaitAll()
        }

        val byKey = hydrated.associateBy {
            "${it.season}|${it.episode}"
        }

        return seriesInfo.copy(
            episodes = seriesInfo.episodes.map { episode ->
                byKey["${episode.season}|${episode.episode}"]
                    ?.let { mergeEpisodeInfo(episode, it) }
                    ?: episode
            }
        )
    }

    private fun hydrateSingleEpisode(
        baseEpisode: EpisodeInfo,
        page: Document,
        pageUrl: String
    ): EpisodeInfo {
        val mediaObject = extractInitialMediaObject(page)

        val rscEpisode = parseEpisodeObjectsFromPage(page)
            .firstOrNull {
                it.season == baseEpisode.season &&
                    it.episode == baseEpisode.episode
            }

        val embeddedEpisode = parseEpisodes(mediaObject)
            .firstOrNull {
                it.season == baseEpisode.season &&
                    it.episode == baseEpisode.episode
            }

        val detailTitle = firstNonBlank(
            mediaObject?.optString("episodeTitle"),
            mediaObject?.optString("name"),
            rscEpisode?.name,
            embeddedEpisode?.name,
            extractMeta(page, "property=og:title"),
            page.selectFirst("h1")?.text()
        )
            ?.let(::cleanDetailTitle)
            ?.takeIf { it.isNotBlank() && !it.matches(Regex("""(?i)Episode\s+\d+""")) }

        val detailPlot = firstNonBlank(
            rscEpisode?.overview,
            embeddedEpisode?.overview,
            mediaObject?.optString("overview"),
            extractMeta(page, "property=og:description")
        )

        val detailPoster = firstNonBlank(
            rscEpisode?.poster,
            embeddedEpisode?.poster,
            mediaObject?.optString("stillPath"),
            mediaObject?.optString("posterPath"),
            extractMeta(page, "property=og:image")
        )

        val detailRuntime =
            rscEpisode?.runtime
                ?: embeddedEpisode?.runtime
                ?: mediaObject?.optInt("runtime", 0)?.takeIf { it > 0 }

        val detailAirDate =
            rscEpisode?.airDate
                ?: embeddedEpisode?.airDate
                ?: normalizeEpisodeDate(
                    firstNonBlank(
                        mediaObject?.optString("airDate"),
                        mediaObject?.optString("releaseDate"),
                        mediaObject?.optString("air_date")
                    )
                )

        val detailScore =
            rscEpisode?.score
                ?: embeddedEpisode?.score
                ?: firstNonBlank(
                    mediaObject?.optString("voteAverage"),
                    mediaObject?.optString("rating"),
                    mediaObject?.optString("score")
                )?.toDoubleOrNull()?.takeIf { it in 0.0..10.0 }

        return baseEpisode.copy(
            id = baseEpisode.id ?: rscEpisode?.id ?: embeddedEpisode?.id,
            name = if (
                baseEpisode.name.matches(Regex("""(?i)Episode\s+\d+""")) &&
                    !detailTitle.isNullOrBlank()
            ) {
                detailTitle
            } else {
                baseEpisode.name
            },
            overview = baseEpisode.overview
                ?.takeIf { it.isNotBlank() }
                ?: detailPlot?.takeIf { it.isNotBlank() },
            poster = baseEpisode.poster
                ?.takeIf { it.isNotBlank() }
                ?: detailPoster?.takeIf { it.startsWith("http", true) },
            runtime = baseEpisode.runtime?.takeIf { it > 0 } ?: detailRuntime,
            airDate = baseEpisode.airDate
                ?.takeIf { it.isNotBlank() }
                ?: detailAirDate,
            score = baseEpisode.score ?: detailScore,
            url = baseEpisode.url
                ?.takeIf { it.isNotBlank() }
                ?: pageUrl
        )
    }

    private fun buildEpisodeContextUrl(
        seriesUrl: String,
        season: Int,
        episode: Int
    ): String {
        val separator = if (seriesUrl.contains("?")) "&" else "?"
        return "${seriesUrl}${separator}season=$season&episode=$episode"
    }

    private fun buildEpisodeUrl(
        baseUrl: String,
        title: String,
        season: Int,
        episode: Int
    ): String {
        val uri = runCatching { URI(baseUrl) }.getOrNull()
            ?: return baseUrl

        val parts = uri.path.orEmpty()
            .trim('/')
            .split('/')
            .filter { it.isNotBlank() }
            .toMutableList()

        if (parts.size >= 3 &&
            (parts[0].equals("tv", true) || parts[0].equals("anime", true))
        ) {
            parts[0] = parts[0]
            parts.truncateAfter(2)
        }

        if (parts.size < 2) return baseUrl

        val mediaType = parts[0]
        val id = parts[1]
        val slug = parts.getOrNull(2)
            ?: slugify(title).ifBlank { id }

        return "$BASE_URL/$mediaType/$id/$slug/$season/$episode"
    }

    // ---------------------------------------------------------------------
    // LINK LOADER / FRESH SOURCE RESOLUTION
    // ---------------------------------------------------------------------

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val parts = data.split("||", limit = 3)
        val pageUrl = parts.firstOrNull()?.trim().orEmpty()
        val rawEpisodeId = parts.getOrNull(1)?.trim().orEmpty()
            .takeIf { it.isNotBlank() }
        val episodeMetadata = parts.getOrNull(2)?.let { rawMetadata ->
            runCatching {
                val decoded = URLDecoder.decode(rawMetadata, "UTF-8")
                val json = JSONObject(decoded)
                EpisodePlaybackMetadata(
                    episodeId = firstNonBlank(
                        json.optString("episodeId"),
                        rawEpisodeId
                    ),
                    episodeTitle = json.optString("episodeTitle")
                        .takeIf { it.isNotBlank() },
                    seriesTitle = json.optString("seriesTitle")
                        .takeIf { it.isNotBlank() },
                    originalTitle = json.optString("originalTitle")
                        .takeIf { it.isNotBlank() },
                    imdbId = json.optString("imdbId")
                        .takeIf { it.isNotBlank() },
                    runtime = json.optInt("runtime", 0).takeIf { it > 0 },
                    airDate = normalizeEpisodeDate(
                        json.optString("airDate")
                    ),
                    season = json.optInt("season", -1).takeIf { it >= 0 },
                    episode = json.optInt("episode", -1).takeIf { it > 0 },
                    episodeUrl = json.optString("episodeUrl")
                        .takeIf { it.startsWith("http", true) }
                )
            }.getOrNull()
        }
        val episodeId = episodeMetadata?.episodeId ?: rawEpisodeId

        if (!pageUrl.startsWith("http", true)) return false

        val playbackContext = parsePlaybackContext(pageUrl)
            ?: episodeMetadata?.let { meta ->
                val type = when {
                    pageUrl.contains("/anime/", true) -> "anime"
                    pageUrl.contains("/tv/", true) -> "tv"
                    else -> null
                }
                val tmdbId = Regex("/(?:tv|anime)/(\\d+)")
                    .find(pageUrl)
                    ?.groupValues
                    ?.getOrNull(1)
                if (type != null && tmdbId != null) {
                    PlaybackContext(
                        type = type,
                        tmdbId = tmdbId,
                        season = meta.season,
                        episode = meta.episode
                    )
                } else null
            }
            ?: return false

        val episodeBasedPlayback =
            playbackContext.season != null &&
                playbackContext.episode != null

        /*
         * TV/Anime playback needs the same metadata-aware sequence as the real
         * Zoryva browser: begin the fresh /api/extract call immediately, while
         * also fetching the exact page context in parallel. We then ALWAYS run
         * the metadata-aware episode retry for TV/Anime, even when the first
         * request happened to return a partial/incorrect source set. This avoids
         * the historical failure where the first generic response short-circuited
         * before the exact episode parameters were available.
         */
        val (initialExtract, loadedPages) = coroutineScope {
            val extractJob = async {
                withTimeoutOrNull(
                    if (episodeBasedPlayback) 30000L else ZORYVA_EXTRACT_TIMEOUT_MS
                ) {
                    resolveZoryvaExtract(
                        document = null,
                        pageUrl = pageUrl,
                        context = playbackContext,
                        preferredTitle = episodeMetadata?.seriesTitle
                            ?: titleFromPath(URI(pageUrl).path.orEmpty()),
                        allowSoftFallback = false,
                        episodeId = episodeId,
                        episodeMetadata = episodeMetadata
                    )
                }
            }

            val pagesJob = async {
                val directPageJob = async {
                    withTimeoutOrNull(
                        if (episodeBasedPlayback) 20000L else 7000L
                    ) {
                        getDocument(pageUrl)
                    }
                }

                val seriesPageJob = async {
                    if (!episodeBasedPlayback) {
                        null
                    } else {
                        val mediaPath = if (
                            playbackContext.type.equals("anime", true)
                        ) "anime" else "tv"

                        withTimeoutOrNull(12000L) {
                            getDocument(
                                "$BASE_URL/$mediaPath/${playbackContext.tmdbId}"
                            )
                        }
                    }
                }

                directPageJob.await() to seriesPageJob.await()
            }

            extractJob.await() to pagesJob.await()
        }

        var extractResolution =
            initialExtract ?: ZoryvaExtractResolution(emptyList(), emptyList())

        val directPage = loadedPages.first
        val seriesPage = loadedPages.second

        val pageEpisode = if (episodeBasedPlayback) {
            directPage
                ?.let { parseEpisodeObjectsFromPage(it) }
                ?.firstOrNull {
                    it.season == playbackContext.season &&
                        it.episode == playbackContext.episode
                }
                ?: seriesPage
                    ?.let { parseEpisodeObjectsFromPage(it) }
                    ?.firstOrNull {
                        it.season == playbackContext.season &&
                            it.episode == playbackContext.episode
                    }
        } else null

        val effectiveEpisodeId =
            episodeId ?: pageEpisode?.id

        if (episodeBasedPlayback) {
            val retryDocument = directPage ?: seriesPage
            val retryResolution = withTimeoutOrNull(30000L) {
                resolveZoryvaExtract(
                    document = retryDocument,
                    pageUrl = pageUrl,
                    context = playbackContext,
                    preferredTitle = episodeMetadata?.seriesTitle,
                    allowSoftFallback = true,
                    episodeId = effectiveEpisodeId,
                    seriesDocument = seriesPage,
                    episodeMetadata = episodeMetadata
                )
            }

            if (retryResolution != null) {
                extractResolution = mergeZoryvaExtractResolutions(
                    extractResolution,
                    retryResolution
                )
            }
        } else if (extractResolution.directSources.isEmpty()) {
            val retryResolution = directPage?.let { page ->
                withTimeoutOrNull(15000L) {
                    resolveZoryvaExtract(
                        document = page,
                        pageUrl = pageUrl,
                        context = playbackContext,
                        preferredTitle = null,
                        allowSoftFallback = true,
                        episodeId = effectiveEpisodeId,
                        episodeMetadata = episodeMetadata
                    )
                }
            }

            if (retryResolution != null) {
                extractResolution = mergeZoryvaExtractResolutions(
                    extractResolution,
                    retryResolution
                )
            }
        }

        /*
         * PRIMARY EXTRACTED SOURCES
         *
         * /api/extract is the website's source of truth for the current media
         * URLs, but an API-level `status=ok` does not guarantee that the exact
         * HTTP request made by CloudStream will return a usable response.
         * Media3 reports that situation as ERROR_CODE_IO_BAD_HTTP_STATUS (2004).
         *
         * Probe the exact extracted candidates with the same safe headers
         * that will be attached to the player link. Probe results are used to
         * order sources and prefer confirmed direct/proxy candidates; they do
         * not erase the website's own status=ok sources when the probe is
         * inconclusive.
         */
        var primaryEmitted = 0
        val emittedKeys = linkedSetOf<String>()

        /*
         * FAST PLAYBACK PATH:
         * /api/extract has already returned a real source. Give CloudStream that
         * exact URL immediately instead of waiting for HLS inspection or probes.
         * This is especially important for signed/extensionless worker URLs.
         */
        suspend fun emitImmediateSource(
            source: MediaCandidate,
            labelSuffix: String
        ) {
            if (primaryEmitted >= MAX_EMITTED_FALLBACKS) return

            val normalizedIdentity = normalizeMediaIdentity(source.url)
            val key = "${labelSuffix.lowercase(Locale.ROOT)}|$normalizedIdentity"
            if (!emittedKeys.add(key)) return

            emitExtractorSource(
                source = source,
                output = buildDirectPlaybackOutput(source, pageUrl),
                callback = callback,
                labelSuffix = labelSuffix,
                typeSourceUrl = source.url
            )
            primaryEmitted++
        }

        extractResolution.directSources
            .distinctBy { normalizeMediaIdentity(it.url) }
            .forEach { source ->
                if (
                    primaryEmitted < MAX_EMITTED_FALLBACKS &&
                        requiresZoryvaProxy(source.url)
                ) {
                    buildZoryvaProxyCandidate(source, pageUrl)?.let { proxy ->
                        emitImmediateSource(proxy, "Zoryva Proxy")
                    }
                }

                emitImmediateSource(source, "Direct")
            }

        if (extractResolution.directSources.isNotEmpty()) {
            /*
             * Inspect the exact API-returned HLS manifests first so we can
             * recognize adaptive masters and their audio groups. Inspection is
             * enrichment, not a hard gate: a CDN can legitimately behave
             * differently for a small probe than for the real player request.
             */
            val checks = coroutineScope {
                extractResolution.directSources
                    .distinctBy { normalizeMediaIdentity(it.url) }
                    .map { source ->
                        async {
                            val hlsInfo = if (source.isHls || looksLikeHlsUrl(source.url)) {
                                withTimeoutOrNull(SOURCE_PROBE_TIMEOUT_MS) {
                                    inspectHls(source)
                                }
                            } else {
                                null
                            }

                            val preparedSource = if (hlsInfo != null) {
                                source.copy(
                                    quality = maxOf(source.quality, hlsInfo.maxQuality),
                                    isHlsMaster = hlsInfo.isMaster,
                                    audioLabel = hlsInfo.audioLabel,
                                    isHls = true,
                                    audioTracks = mergeAudioTrackCandidates(
                                        source.audioTracks,
                                        hlsInfo.audioTracks
                                    )
                                )
                            } else {
                                source
                            }

                            /*
                             * The website/API has already declared this exact
                             * URL as a source. Do not spend another network
                             * round-trip on HEAD/range validation: signed media
                             * hosts may reject probes while accepting the real
                             * player request. HLS inspection below is metadata
                             * enrichment only.
                             */
                            val directPlayable = true

                            val proxyCandidate0 =
                                if (requiresZoryvaProxy(preparedSource.url)) {
                                    buildZoryvaProxyCandidate(
                                        source = preparedSource,
                                        pageUrl = pageUrl
                                    )
                                } else {
                                    null
                                }

                            val proxyHls = if (
                                proxyCandidate0 != null &&
                                (
                                    proxyCandidate0.isHls ||
                                        looksLikeHlsUrl(proxyCandidate0.url)
                                )
                            ) {
                                withTimeoutOrNull(4000L) {
                                    inspectHls(proxyCandidate0)
                                }
                            } else {
                                null
                            }

                            val proxyCandidate =
                                if (proxyCandidate0 != null && proxyHls != null) {
                                    proxyCandidate0.copy(
                                        quality = maxOf(
                                            proxyCandidate0.quality,
                                            proxyHls.maxQuality
                                        ),
                                        isHlsMaster = proxyHls.isMaster,
                                        isHls = true,
                                        audioLabel = proxyHls.audioLabel,
                                        audioTracks = mergeAudioTrackCandidates(
                                            proxyCandidate0.audioTracks,
                                            proxyHls.audioTracks
                                        )
                                    )
                                } else {
                                    proxyCandidate0
                                }

                            val proxyPlayable = proxyCandidate != null

                            PrimarySourceCheck(
                                source = preparedSource,
                                directPlayable = directPlayable,
                                proxy = proxyCandidate,
                                proxyPlayable = proxyPlayable,
                                hls = hlsInfo,
                                proxyHls = proxyHls
                            )
                        }
                    }
                    .awaitAll()
            }

            /*
             * The site's HLS master is the best representation for adaptive
             * resolution and embedded multi-audio/subtitle groups. Keep it ahead
             * of fixed-resolution children, while still exposing exact
             * 2160/1440/1080/720/480/etc sources when Zoryva actually returned
             * those URLs.
             */
            val enrichedChecks = checks.map { check ->
                val sourceTracks = mergeAudioTrackCandidates(
                    check.source.audioTracks,
                    check.hls?.audioTracks.orEmpty()
                )

                val globalTracks = if (checks.size == 1) {
                    extractResolution.audioTracks
                } else {
                    emptyList()
                }

                val finalTracks = mergeAudioTrackCandidates(
                    sourceTracks,
                    globalTracks
                )

                if (finalTracks.isEmpty()) {
                    check
                } else {
                    check.copy(
                        source = check.source.copy(
                            audioTracks = finalTracks
                        )
                    )
                }
            }

            val orderedChecks = enrichedChecks.sortedWith(
                compareByDescending<PrimarySourceCheck> { it.directPlayable }
                    .thenByDescending { it.proxyPlayable }
                    .thenByDescending { it.source.isHlsMaster }
                    .thenByDescending { it.source.audioLabel.isNotBlank() }
                    .thenByDescending { it.source.quality }
                    .thenBy { it.source.label.lowercase(Locale.ROOT) }
                    .thenBy { it.source.url }
            )

            var emitted = primaryEmitted

            val seenSubtitle = linkedSetOf<String>()
            extractResolution.subtitles.forEach { (language, url) ->
                val key = "$language|$url"
                if (seenSubtitle.add(key)) {
                    subtitleCallback(newSubtitleFile(language, url))
                }
            }

            checks.forEach { check ->
                listOfNotNull(check.hls, check.proxyHls)
                    .flatMap { it.subtitles }
                    .forEach { (language, url) ->
                        val key = "$language|$url"
                        if (seenSubtitle.add(key)) {
                            subtitleCallback(newSubtitleFile(language, url))
                        }
                    }
            }

            /*
             * Pass 1: emit confirmed direct/proxy sources. For an HLS master,
             * also emit every exact variant URL found in that manifest so the
             * CloudStream Source selector exposes the real resolutions.
             */
            suspend fun emitCheckedSource(
                check: PrimarySourceCheck,
                labelSuffix: String,
                outputSource: MediaCandidate
            ) {
                if (emitted >= MAX_EMITTED_FALLBACKS) return

                val source = outputSource
                val sourceKey = "${labelSuffix.lowercase(Locale.ROOT)}|${normalizeMediaIdentity(source.url)}"
                if (emittedKeys.add(sourceKey)) {
                    emitExtractorSource(
                        source = source,
                        output = buildDirectPlaybackOutput(source, pageUrl),
                        callback = callback,
                        labelSuffix = labelSuffix,
                        typeSourceUrl = source.url
                    )
                    emitted++
                    primaryEmitted = maxOf(primaryEmitted, emitted)
                }
            }

            suspend fun emitMasterWithVariants(
                check: PrimarySourceCheck,
                source: MediaCandidate,
                labelSuffix: String
            ) {
                if (emitted >= MAX_EMITTED_FALLBACKS) return

                /* Keep the adaptive master first. It preserves the playlist's
                 * own resolution/audio/subtitle group selection. */
                emitCheckedSource(
                    check = check,
                    labelSuffix = labelSuffix,
                    outputSource = source
                )

                val hls = if (
                    check.proxy != null &&
                    normalizeMediaIdentity(source.url) ==
                        normalizeMediaIdentity(check.proxy.url)
                ) {
                    check.proxyHls
                } else {
                    check.hls
                } ?: return

                if (!hls.isMaster) return

                for ((variantUrl, variantQuality) in hls.variants
                    .distinctBy { normalizeMediaIdentity(it.first) }
                    .sortedByDescending { it.second }
                ) {
                    if (emitted >= MAX_EMITTED_FALLBACKS) break

                    val variantBase = source.copy(
                        url = variantUrl,
                        quality = maxOf(variantQuality, qualityFromUrl(variantUrl, "")),
                        isHlsMaster = false,
                        isHls = true,
                        /* Standalone variant playlists may omit master-level
                         * audio groups; attach the exact HLS audio playlist URLs
                         * discovered above so alternate audio remains available. */
                        audioTracks = mergeAudioTrackCandidates(
                            source.audioTracks,
                            hls.audioTracks
                        )
                    )

                    val variant = if (
                        labelSuffix.equals("Zoryva Proxy", true) &&
                        check.source.url != source.url
                    ) {
                        buildZoryvaProxyCandidate(
                            source = check.source.copy(
                                url = variantUrl,
                                quality = variantBase.quality,
                                isHlsMaster = false,
                                isHls = true,
                                audioTracks = variantBase.audioTracks
                            ),
                            pageUrl = pageUrl
                        ) ?: variantBase
                    } else {
                        variantBase
                    }

                    emitCheckedSource(
                        check = check,
                        labelSuffix = labelSuffix,
                        outputSource = variant
                    )
                }
            }

            for (check in orderedChecks) {
                if (emitted >= MAX_EMITTED_FALLBACKS) break

                val workerProxyPreferred =
                    check.proxy != null &&
                        check.source.url.contains(
                            "workers.dev/",
                            true
                        )

                if (workerProxyPreferred) {
                    emitMasterWithVariants(
                        check = check,
                        source = check.proxy!!,
                        labelSuffix = "Zoryva Proxy"
                    )
                }

                if (emitted >= MAX_EMITTED_FALLBACKS) break

                if (check.directPlayable) {
                    emitMasterWithVariants(
                        check = check,
                        source = check.source,
                        labelSuffix = "Direct"
                    )
                }

                if (emitted >= MAX_EMITTED_FALLBACKS) break

                if (
                    check.proxy != null &&
                    !workerProxyPreferred &&
                    (
                        check.proxyPlayable ||
                            requiresZoryvaProxy(check.source.url)
                        )
                ) {
                    emitMasterWithVariants(
                        check = check,
                        source = check.proxy,
                        labelSuffix = "Zoryva Proxy"
                    )
                }
            }

            /*
             * Final structured-source fallback. A probe is advisory only; the
             * website's returned source is never silently discarded.
             */
            if (emitted == 0) {
                for (check in orderedChecks) {
                    if (emitted >= MAX_EMITTED_FALLBACKS) break

                    emitMasterWithVariants(
                        check = check,
                        source = check.proxy ?: check.source,
                        labelSuffix = "API Source"
                    )
                }
            }

            primaryEmitted = emitted

            /*
             * TV/Anime continue into the episode-page/server discovery
             * pass because the exact player can be separate from /api/extract.
             * Movies continue when fewer than two links were produced so the
             * page can contribute additional real resolutions.
             */
            /* Once the structured API has produced at least one real source,
             * that source set is authoritative enough to hand playback to the
             * player. TV/Anime especially must not be converted into No Links
             * Found merely because a secondary crawl is slow or fails. */
            if (primaryEmitted > 0) return true
        }

        /*
         * SECONDARY/AUGMENTATION PATHS
         *
         * TV/Anime always reach this augmentation pass so the exact episode
         * player can contribute sources. Movies reach it when the structured
         * extraction returned fewer than two source links, allowing additional
         * real page/player resolutions to be added without disturbing the
         * working direct path.
         */
        val recoveredPage = directPage ?: if (episodeBasedPlayback) {
            withTimeoutOrNull(12000L) {
                getDocument(pageUrl)
            }
        } else {
            null
        }

        val directText = recoveredPage?.html().orEmpty()
        val directDocument = recoveredPage ?: return primaryEmitted > 0

        val scrapedResolution = withTimeoutOrNull(ZORYVA_SCRAPED_TIMEOUT_MS) {
            resolveZoryvaScraped(
                document = directDocument,
                pageUrl = pageUrl,
                context = playbackContext
            )
        } ?: ZoryvaExtractResolution(emptyList(), emptyList())

        val directResult = inspectPageForSources(
            page = directDocument,
            rawText = directText,
            pageUrl = pageUrl,
            serverName = "Zoryva direct"
        )

        val discoveredServerPages = discoverServerPages(
            document = directDocument,
            rawText = directText,
            pageUrl = pageUrl,
            episodeId = effectiveEpisodeId
        )

        val vidrock = withTimeoutOrNull(VIDROCK_API_TIMEOUT_MS) {
            resolveVidrock(playbackContext)
        } ?: VidrockResolution(emptyList(), emptyList())

        val targetMap = linkedMapOf<String, VidrockServerTarget>()

        extractResolution.servers.forEach { target ->
            val clean = cleanUrl(target.url)
            if (clean.isNotBlank()) targetMap.putIfAbsent(clean, target)
        }

        scrapedResolution.servers.forEach { target ->
            val clean = cleanUrl(target.url)
            if (clean.isNotBlank()) targetMap.putIfAbsent(clean, target)
        }

        discoveredServerPages.forEach { (url, label) ->
            val clean = cleanUrl(url)
            if (clean.isNotBlank()) {
                targetMap.putIfAbsent(
                    clean,
                    VidrockServerTarget(
                        url = clean,
                        name = label.ifBlank { "Zoryva Server" },
                        referer = pageUrl
                    )
                )
            }
        }

        buildZoryvaEmbedUrls(playbackContext).forEach { embedUrl ->
            targetMap.putIfAbsent(
                embedUrl,
                VidrockServerTarget(
                    url = embedUrl,
                    name = "Zoryva Embedded Player",
                    referer = pageUrl
                )
            )
        }

        vidrock.servers.forEach { target ->
            targetMap.putIfAbsent(cleanUrl(target.url), target)
        }

        val results = ArrayList<ServerResult>()
        if (directResult.sources.isNotEmpty()) results += directResult

        coroutineScope {
            val jobs = targetMap.values
                .filter { it.url.isNotBlank() }
                .take(VIDROCK_MAX_SERVERS)
                .map { target ->
                    async {
                        inspectServerTarget(
                            url = target.url,
                            name = target.name,
                            depth = 0,
                            episodeId = effectiveEpisodeId,
                            referer = target.referer
                        )
                    }
                }
            results += jobs.awaitAll().flatten()
        }

        if (scrapedResolution.directSources.isNotEmpty()) {
            val scrapedServer = inspectCandidatesAsServer(
                candidates = scrapedResolution.directSources,
                serverName = "Zoryva Scraped"
            )
            if (scrapedServer.sources.isNotEmpty()) results += scrapedServer
        }

        if (vidrock.directSources.isNotEmpty()) {
            val directServer = inspectCandidatesAsServer(
                candidates = vidrock.directSources,
                serverName = "Vidrock API"
            )
            if (directServer.sources.isNotEmpty()) results += directServer
        }

        val usable = results
            .filter { it.sources.isNotEmpty() }
            .flatMap { result -> result.sources.map { source -> result to source } }
            .sortedWith(
                compareBy<Pair<ServerResult, MediaCandidate>> { it.first.latencyMs }
                    .thenByDescending { it.second.quality }
                    .thenBy { it.second.server.lowercase(Locale.ROOT) }
            )

        if (usable.isEmpty()) return primaryEmitted > 0

        val emitted = linkedSetOf<String>()
        val subtitleSeen = linkedSetOf<String>()

        for ((server, rawSource) in usable) {
            val source = if (
                rawSource.isHls ||
                looksLikeHlsUrl(rawSource.url)
            ) {
                val hls = withTimeoutOrNull(SOURCE_PROBE_TIMEOUT_MS) {
                    inspectHls(rawSource)
                }

                if (hls != null) {
                    rawSource.copy(
                        quality = maxOf(rawSource.quality, hls.maxQuality),
                        isHlsMaster = hls.isMaster,
                        audioLabel = hls.audioLabel,
                        isHls = true,
                        audioTracks = mergeAudioTrackCandidates(
                            rawSource.audioTracks,
                            hls.audioTracks
                        )
                    )
                } else {
                    rawSource
                }
            } else {
                rawSource
            }

            for ((language, subtitleUrl) in server.subtitles) {
                val key = "$language|$subtitleUrl"
                if (subtitleSeen.add(key)) {
                    subtitleCallback(newSubtitleFile(language, subtitleUrl))
                }
            }

            if (!emitted.add(normalizeMediaIdentity(source.url))) continue
            if (emitted.size > MAX_EMITTED_FALLBACKS) break

            val output = buildDirectPlaybackOutput(source, pageUrl)
            emitExtractorSource(
                source = source,
                output = output,
                callback = callback,
                labelSuffix = "Fallback",
                typeSourceUrl = source.url
            )
        }

        return emitted.isNotEmpty()
    }

    private suspend fun emitExtractorSource(
        source: MediaCandidate,
        output: PlaybackOutput,
        callback: (ExtractorLink) -> Unit,
        labelSuffix: String,
        typeSourceUrl: String
    ) {
        val linkType = when {
            source.isHls || looksLikeHlsUrl(typeSourceUrl) || output.url.contains(".m3u8", true) -> ExtractorLinkType.M3U8
            typeSourceUrl.substringBefore('?').endsWith(".mpd", true) -> ExtractorLinkType.DASH
            else -> ExtractorLinkType.VIDEO
        }

        val audioFiles = mutableListOf<AudioFile>()
        for (track in source.audioTracks
            .filter { it.url.isNotBlank() }
            .distinctBy { cleanUrl(it.url) }
        ) {
            try {
                val audioFile = newAudioFile(track.url)

                /*
                 * AudioFile is deliberately created through CloudStream's
                 * supported factory. Some CloudStream builds expose optional
                 * metadata/header properties while others keep the model lean.
                 * We therefore attach only what the concrete runtime object
                 * actually exposes, without hard-coding a constructor.
                 */
                val runtimeClass = audioFile::class.java

                runCatching {
                    val labelField = runtimeClass.declaredFields.firstOrNull {
                        it.name.equals("name", true) ||
                            it.name.equals("label", true) ||
                            it.name.equals("language", true)
                    }

                    if (labelField != null && track.label.isNotBlank()) {
                        labelField.isAccessible = true
                        runCatching { labelField.set(audioFile, track.label) }
                    }
                }

                runCatching {
                    val headersField = runtimeClass.declaredFields.firstOrNull {
                        it.name.equals("headers", true)
                    }

                    if (headersField != null && track.headers.isNotEmpty()) {
                        headersField.isAccessible = true
                        runCatching { headersField.set(audioFile, track.headers) }
                    }
                }

                if (audioFiles.none { it.url == audioFile.url }) {
                    audioFiles.add(audioFile)
                }
            } catch (_: Exception) {
                // Keep the video source even if one alternate audio URL fails.
            }
        }

        callback(
            newExtractorLink(
                name,
                cleanSourceDisplayName(source, labelSuffix),
                output.url,
                linkType
            ) {
                quality = if (source.quality > 0) source.quality else Qualities.Unknown.value
                referer = output.referer
                headers = output.headers
                audioTracks = audioFiles
            }
        )
    }

    private suspend fun inspectCandidatesAsServer(
        candidates: List<MediaCandidate>,
        serverName: String
    ): ServerResult {
        val started = System.currentTimeMillis()

        val inspected = coroutineScope {
            candidates
                .distinctBy { normalizeMediaIdentity(it.url) }
                .map { candidate ->
                    async {
                        val hls = if (candidate.isHls || looksLikeHlsUrl(candidate.url)) {
                            inspectHls(candidate)
                        } else {
                            null
                        }

                        val playable = withTimeoutOrNull(SOURCE_PROBE_TIMEOUT_MS) {
                            probeSource(candidate, hls)
                        } ?: false

                        if (!playable) return@async null

                        if (hls != null) {
                            candidate.copy(
                                quality = maxOf(candidate.quality, hls.maxQuality),
                                isHlsMaster = hls.isMaster,
                                audioLabel = hls.audioLabel,
                                isHls = true,
                                audioTracks = mergeAudioTrackCandidates(
                                    candidate.audioTracks,
                                    hls.audioTracks
                                )
                            ) to hls
                        } else {
                            candidate to null
                        }
                    }
                }
                .awaitAll()
                .filterNotNull()
        }

        val subtitles = linkedSetOf<Pair<String, String>>()
        inspected.forEach { pair ->
            pair.second?.let { subtitles.addAll(it.subtitles) }
        }

        return ServerResult(
            serverUrl = serverName,
            serverName = serverName,
            latencyMs = System.currentTimeMillis() - started,
            sources = inspected.map { it.first },
            subtitles = subtitles.toList()
        )
    }

    private fun playbackHeadersFor(
        source: MediaCandidate
    ): Map<String, String> {
        val result = linkedMapOf<String, String>()

        /*
         * Keep the request deliberately small. Only public transport headers
         * needed by player/CDN requests are accepted from the website.
         * Cookies, Authorization and session/CSRF material are never propagated.
         */
        result["User-Agent"] = USER_AGENT
        result["Accept"] = if (source.isHls || looksLikeHlsUrl(source.url)) {
            "application/vnd.apple.mpegurl,application/x-mpegURL,text/plain,*/*;q=0.8"
        } else {
            ACCEPT
        }
        result["Accept-Language"] = "en-US,en;q=0.9"
        result["Cache-Control"] = "no-cache"
        result["Pragma"] = "no-cache"

        val merged = mergePlaybackHeaders(
            result,
            source.headers
        ).toMutableMap()

        val referer = firstNonBlank(
            headerValue(merged, "Referer"),
            source.referer.takeIf { it.startsWith("http", true) },
            if (source.url.contains("peakstorm.top/", true)) {
                mediaRefererFor(source.url, source.referer)
            } else {
                null
            }
        )

        if (!referer.isNullOrBlank()) {
            putHeaderCaseInsensitive(merged, "Referer", referer)
        }

        val origin = firstNonBlank(
            headerValue(merged, "Origin"),
            source.origin.takeIf { it.startsWith("http", true) },
            if (source.url.contains("peakstorm.top/", true)) {
                originOf(referer.orEmpty())
            } else {
                null
            }
        )

        if (!origin.isNullOrBlank()) {
            putHeaderCaseInsensitive(merged, "Origin", origin)
        }

        return sanitizePlaybackHeaders(merged)
    }

    private data class PlaybackContext(
        val type: String,
        val tmdbId: String,
        val season: Int?,
        val episode: Int?
    )

    private data class EpisodePlaybackMetadata(
        val episodeId: String? = null,
        val episodeTitle: String? = null,
        val seriesTitle: String? = null,
        val originalTitle: String? = null,
        val imdbId: String? = null,
        val runtime: Int? = null,
        val airDate: String? = null,
        val season: Int? = null,
        val episode: Int? = null,
        val episodeUrl: String? = null
    )

    private fun parsePlaybackContext(
        pageUrl: String
    ): PlaybackContext? {
        val path = runCatching { URI(pageUrl).path.orEmpty() }
            .getOrDefault("")

        val parts = path
            .trim('/')
            .split('/')
            .filter { it.isNotBlank() }

        if (parts.size < 2) return null

        val type = when {
            parts[0].equals("movie", true) -> "movie"
            parts[0].equals("tv", true) -> "tv"
            parts[0].equals("anime", true) -> "anime"
            else -> return null
        }

        val tmdbId = parts[1]
            .takeIf { it.all(Char::isDigit) }
            ?: Regex("^(\\d+)")
                .find(parts[1])
                ?.groupValues
                ?.getOrNull(1)
            ?: return null

        if (type == "movie") {
            return PlaybackContext(
                type = "movie",
                tmdbId = tmdbId,
                season = null,
                episode = null
            )
        }

        /* Some website/player links carry season/episode in the query instead of
         * the path. Accept both forms so a copied canonical player URL remains
         * episode-aware. */
        val queryParameters = runCatching {
            URI(pageUrl).rawQuery.orEmpty()
                .split('&')
                .mapNotNull { pair ->
                    val equals = pair.indexOf('=')
                    if (equals <= 0) return@mapNotNull null
                    val key = runCatching {
                        URLDecoder.decode(
                            pair.substring(0, equals),
                            StandardCharsets.UTF_8.name()
                        )
                    }.getOrNull()?.lowercase(Locale.ROOT) ?: return@mapNotNull null
                    val value = runCatching {
                        URLDecoder.decode(
                            pair.substring(equals + 1),
                            StandardCharsets.UTF_8.name()
                        )
                    }.getOrNull() ?: return@mapNotNull null
                    key to value
                }
                .toMap()
        }.getOrDefault(emptyMap())

        val querySeason = firstNonBlank(
            queryParameters["season"],
            queryParameters["seasonnumber"],
            queryParameters["season_number"]
        )?.toIntOrNull()

        val queryEpisode = firstNonBlank(
            queryParameters["episode"],
            queryParameters["episodenumber"],
            queryParameters["episode_number"],
            queryParameters["ep"]
        )?.toIntOrNull()

        if (querySeason != null && queryEpisode != null) {
            return PlaybackContext(
                type = type,
                tmdbId = tmdbId,
                season = querySeason,
                episode = queryEpisode
            )
        }

        /* Canonical Zoryva episode URL: /tv/{id}/{slug}/{season}/{episode} */
        if (parts.size >= 5) {
            val season = parts[parts.size - 2].toIntOrNull()
            val episode = parts[parts.size - 1].toIntOrNull()

            if (season != null && episode != null) {
                return PlaybackContext(
                    type = type,
                    tmdbId = tmdbId,
                    season = season,
                    episode = episode
                )
            }
        }

        /* Accept /tv/{id}/{season}/{episode} as a secondary form. */
        if (parts.size >= 4) {
            val season = parts[parts.size - 2].toIntOrNull()
            val episode = parts[parts.size - 1].toIntOrNull()
            if (season != null && episode != null) {
                return PlaybackContext(
                    type = type,
                    tmdbId = tmdbId,
                    season = season,
                    episode = episode
                )
            }
        }

        return PlaybackContext(
            type = type,
            tmdbId = tmdbId,
            season = null,
            episode = null
        )
    }

    private fun buildZoryvaEmbedUrls(
        context: PlaybackContext?
    ): List<String> {
        context ?: return emptyList()

        val type = when (context.type.lowercase(Locale.ROOT)) {
            "movie" -> "movie"
            "anime" -> "anime"
            else -> "tv"
        }
        val base = "$BASE_URL/Api/Embedded"
        val query = StringBuilder()
            .append("?id=")
            .append(URLEncoder.encode(context.tmdbId, "UTF-8"))
            .append("&type=")
            .append(URLEncoder.encode(type, "UTF-8"))

        if (type == "tv" && context.season != null && context.episode != null) {
            query.append("&season=").append(context.season)
            query.append("&episode=").append(context.episode)
        }

        return listOf(base + query.toString())
    }

    private suspend fun resolveZoryvaExtract(
        document: Document?,
        pageUrl: String,
        context: PlaybackContext?,
        preferredTitle: String? = null,
        allowSoftFallback: Boolean = true,
        episodeId: String? = null,
        seriesDocument: Document? = null,
        episodeMetadata: EpisodePlaybackMetadata? = null
    ): ZoryvaExtractResolution {
        context ?: return ZoryvaExtractResolution(emptyList(), emptyList())

        val mediaObject = document?.let { extractInitialMediaObject(it) }
        val seriesMediaObject = seriesDocument?.let { extractInitialMediaObject(it) }

        val exactEpisode = if (
            context.season != null &&
            context.episode != null &&
            document != null
        ) {
            parseEpisodeObjectsFromPage(document)
                .firstOrNull {
                    it.season == context.season &&
                        it.episode == context.episode
                }
                ?: parseEpisodes(mediaObject)
                    .firstOrNull {
                        it.season == context.season &&
                            it.episode == context.episode
                    }
        } else {
            null
        }

        val mediaType = when (context.type.lowercase(Locale.ROOT)) {
            "movie" -> "movie"
            "tv" -> "tv"
            "anime" -> "anime"
            else -> return ZoryvaExtractResolution(emptyList(), emptyList())
        }

        val title = firstNonBlank(
            episodeMetadata?.seriesTitle,
            seriesMediaObject?.optString("title"),
            mediaObject?.optString("title"),
            preferredTitle,
            document?.selectFirst("h1")?.text(),
            titleFromPath(URI(pageUrl).path.orEmpty())
        ).orEmpty()

        val originalTitle = firstNonBlank(
            episodeMetadata?.originalTitle,
            seriesMediaObject?.optString("originalTitle"),
            mediaObject?.optString("originalTitle"),
            title
        ).orEmpty()

        val imdbId = firstNonBlank(
            episodeMetadata?.imdbId,
            seriesMediaObject?.optString("imdbId"),
            mediaObject?.optString("imdbId"),
            extractImdbId(seriesDocument?.html().orEmpty()),
            extractImdbId(document?.html().orEmpty())
        )

        val runtime = episodeMetadata?.runtime
            ?: exactEpisode?.runtime
            ?: mediaObject?.optInt("runtime", 0)?.takeIf { it > 0 }
            ?: Regex("""(?i)\\"runtime\\"\\s*:\\s*(\\d+)""")
                .find(document?.html().orEmpty())
                ?.groupValues
                ?.getOrNull(1)
                ?.toIntOrNull()

        val airDate = episodeMetadata?.airDate
            ?: exactEpisode?.airDate
            ?: normalizeEpisodeDate(
                firstNonBlank(
                    mediaObject?.optString("airDate"),
                    mediaObject?.optString("releaseDate"),
                    mediaObject?.optString("air_date")
                )
            )

        /*
         * Use the exact browser-style request first: /api/extract with no
         * synthetic soft flag. The captured website response proves this route
         * returns structured videos[]/servers[] data. soft=1 remains a bounded
         * compatibility retry for deployments that require it.
         */
        val exactQuery = buildPlaybackQuery(
            mediaType = mediaType,
            externalId = context.tmdbId,
            title = title,
            originalTitle = originalTitle,
            imdbId = imdbId,
            runtime = runtime,
            airDate = airDate,
            season = context.season,
            episode = context.episode,
            includeSoft = false,
            refresh = context.season != null && context.episode != null
        )

        val softQuery = buildPlaybackQuery(
            mediaType = mediaType,
            externalId = context.tmdbId,
            title = title,
            originalTitle = originalTitle,
            imdbId = imdbId,
            runtime = runtime,
            airDate = airDate,
            season = context.season,
            episode = context.episode,
            includeSoft = true,
            refresh = context.season != null && context.episode != null
        )

        val endpoints = buildList {
            add("$BASE_URL/api/extract?$exactQuery")

            if (
                context.season == null &&
                context.episode == null &&
                allowSoftFallback
            ) {
                /* Some movie extractions are regenerated only when the site
                 * receives an explicit refresh. Keep that as a fallback, never
                 * as the primary request, so already-working movie playback is
                 * not disturbed. */
                val refreshedMovieQuery = buildPlaybackQuery(
                    mediaType = mediaType,
                    externalId = context.tmdbId,
                    title = title,
                    originalTitle = originalTitle,
                    imdbId = imdbId,
                    runtime = runtime,
                    airDate = airDate,
                    season = null,
                    episode = null,
                    includeSoft = false,
                    refresh = true
                )
                add("$BASE_URL/api/extract?$refreshedMovieQuery")
            }

            if (
                context.season != null &&
                context.episode != null
            ) {
                /*
                 * The website embeds a real episode id such as s161571-e1.
                 * Pass it as an additional bounded attempt so an extractor that
                 * indexes episodes independently can resolve the exact episode.
                 */
                if (!episodeId.isNullOrBlank()) {
                    add(
                        "$BASE_URL/api/extract?" +
                            exactQuery +
                            "&episodeId=" +
                            encode(episodeId)
                    )
                }

                /*
                 * ID-only compatibility request. Keep the real section type
                 * for Anime; a TV retry is still retained because some backend
                 * deployments store anime/TV under the same resolver.
                 */
                val minimalQuery = buildString {
                    append("mediaType=").append(mediaType)
                    append("&externalId=").append(encode(context.tmdbId))
                    append("&season=").append(context.season)
                    append("&episode=").append(context.episode)
                    if (!episodeId.isNullOrBlank()) {
                        append("&episodeId=").append(encode(episodeId))
                    }
                }
                add("$BASE_URL/api/extract?$minimalQuery")

                if (mediaType != "tv") {
                    val tvCompatibilityQuery = buildString {
                        append("mediaType=tv")
                        append("&externalId=").append(encode(context.tmdbId))
                        append("&season=").append(context.season)
                        append("&episode=").append(context.episode)
                        if (!episodeId.isNullOrBlank()) {
                            append("&episodeId=").append(encode(episodeId))
                        }
                    }
                    add("$BASE_URL/api/extract?$tvCompatibilityQuery")
                }
            }

            /*
             * The soft retry remains last so the normal exact request always
             * wins when it returns the website's structured source list.
             */
            if (allowSoftFallback) {
                add("$BASE_URL/api/extract?$softQuery")
            }

            /* Last bounded compatibility form: the website's captured TV
             * response used title/type/season/episode/originalTitle as input. */
            if (
                context.season != null &&
                context.episode != null &&
                title.isNotBlank()
            ) {
                val nativeEpisodeQuery = buildString {
                    append("title=").append(encode(title))
                    append("&type=").append(encode(mediaType))
                    append("&season=").append(context.season)
                    append("&episode=").append(context.episode)
                    if (originalTitle.isNotBlank()) {
                        append("&originalTitle=").append(encode(originalTitle))
                    }
                }
                add("$BASE_URL/api/extract?$nativeEpisodeQuery")
            }

        }.distinct()

        val endpointTimeout = if (
            context.season != null &&
            context.episode != null
        ) {
            30000L
        } else {
            ZORYVA_EXTRACT_TIMEOUT_MS
        }

        /*
         * Query all bounded endpoint variants concurrently. The browser/backend
         * may return a usable source from one variant while another returns a
         * valid-but-empty response. Returning the first non-empty response was
         * the reason later movie/TV entries could appear as "No Links Found".
         */
        val parsedResults = coroutineScope {
            endpoints.map { endpoint ->
                async {
                    withTimeoutOrNull(endpointTimeout) {
                        runCatching {
                            app.get(
                                endpoint,
                                headers = PAGE_HEADERS + mapOf(
                                    "Referer" to pageUrl,
                                    "Accept" to "application/json,text/plain,*/*;q=0.8",
                                    "Cache-Control" to "no-cache",
                                    "Pragma" to "no-cache"
                                )
                            )
                        }.getOrNull()
                    }?.takeIf { it.code in 200..399 }
                        ?.let {
                            parseZoryvaExtractResponse(
                                raw = it.text,
                                referer = pageUrl
                            )
                        }
                }
            }.awaitAll().filterNotNull()
        }

        if (parsedResults.isEmpty()) {
            return ZoryvaExtractResolution(emptyList(), emptyList())
        }

        val mergedDirect = linkedMapOf<String, MediaCandidate>()
        val mergedServers = linkedMapOf<String, VidrockServerTarget>()
        val mergedSubtitles = linkedMapOf<String, Pair<String, String>>()
        val mergedAudio = linkedMapOf<String, AudioTrackCandidate>()

        parsedResults.forEach { result ->
            result.directSources.forEach { source ->
                val key = normalizeMediaIdentity(source.url)
                val existing = mergedDirect[key]
                if (existing == null) {
                    mergedDirect[key] = source
                } else {
                    mergedDirect[key] = existing.copy(
                        referer = firstNonBlank(
                            source.referer,
                            existing.referer
                        ).orEmpty(),
                        origin = firstNonBlank(
                            source.origin,
                            existing.origin
                        ).orEmpty(),
                        quality = maxOf(existing.quality, source.quality),
                        label = firstNonBlank(
                            existing.label,
                            source.label
                        ).orEmpty(),
                        server = firstNonBlank(
                            existing.server,
                            source.server
                        ).orEmpty(),
                        isHlsMaster = existing.isHlsMaster || source.isHlsMaster,
                        audioLabel = firstNonBlank(
                            existing.audioLabel,
                            source.audioLabel
                        ).orEmpty(),
                        isHls = existing.isHls || source.isHls,
                        audioTracks = mergeAudioTrackCandidates(
                            existing.audioTracks,
                            source.audioTracks
                        ),
                        headers = mergePlaybackHeaders(
                            existing.headers,
                            source.headers
                        )
                    )
                }
            }

            result.servers.forEach { target ->
                mergedServers.putIfAbsent(
                    cleanUrl(target.url),
                    target
                )
            }

            result.subtitles.forEach { subtitle ->
                mergedSubtitles.putIfAbsent(
                    "${subtitle.first}|${subtitle.second}",
                    subtitle
                )
            }

            result.audioTracks.forEach { track ->
                mergedAudio[cleanUrl(track.url)] =
                    mergedAudio[cleanUrl(track.url)]?.let { existing ->
                        existing.copy(
                            label = firstNonBlank(
                                existing.label,
                                track.label
                            ).orEmpty(),
                            headers = mergePlaybackHeaders(
                                existing.headers,
                                track.headers
                            )
                        )
                    } ?: track
            }
        }

        return ZoryvaExtractResolution(
            directSources = mergedDirect.values.toList(),
            servers = mergedServers.values.toList(),
            subtitles = mergedSubtitles.values.toList(),
            audioTracks = mergedAudio.values.toList()
        )
    }

    private fun mergeZoryvaExtractResolutions(
        first: ZoryvaExtractResolution,
        second: ZoryvaExtractResolution
    ): ZoryvaExtractResolution {
        val mergedDirect = linkedMapOf<String, MediaCandidate>()
        val mergedServers = linkedMapOf<String, VidrockServerTarget>()
        val mergedSubtitles = linkedMapOf<String, Pair<String, String>>()
        val mergedAudio = linkedMapOf<String, AudioTrackCandidate>()

        (first.directSources + second.directSources).forEach { source ->
            if (source.url.isBlank()) return@forEach

            val key = normalizeMediaIdentity(source.url)
            val existing = mergedDirect[key]

            if (existing == null) {
                mergedDirect[key] = source
            } else {
                mergedDirect[key] = existing.copy(
                    referer = firstNonBlank(
                        existing.referer,
                        source.referer
                    ).orEmpty(),
                    origin = firstNonBlank(
                        existing.origin,
                        source.origin
                    ).orEmpty(),
                    quality = maxOf(
                        existing.quality,
                        source.quality
                    ),
                    label = firstNonBlank(
                        existing.label,
                        source.label
                    ).orEmpty(),
                    server = firstNonBlank(
                        existing.server,
                        source.server
                    ).orEmpty(),
                    latencyMs = minOf(
                        existing.latencyMs,
                        source.latencyMs
                    ),
                    isHlsMaster = existing.isHlsMaster || source.isHlsMaster,
                    audioLabel = firstNonBlank(
                        existing.audioLabel,
                        source.audioLabel
                    ).orEmpty(),
                    isHls = existing.isHls || source.isHls,
                    audioTracks = mergeAudioTrackCandidates(
                        existing.audioTracks,
                        source.audioTracks
                    ),
                    headers = mergePlaybackHeaders(
                        existing.headers,
                        source.headers
                    )
                )
            }
        }

        (first.servers + second.servers).forEach { target ->
            if (target.url.isBlank()) return@forEach
            mergedServers.putIfAbsent(cleanUrl(target.url), target)
        }

        (first.subtitles + second.subtitles).forEach { subtitle ->
            if (subtitle.second.isBlank()) return@forEach
            mergedSubtitles.putIfAbsent(
                "${subtitle.first}|${subtitle.second}",
                subtitle
            )
        }

        (first.audioTracks + second.audioTracks).forEach { track ->
            if (track.url.isBlank()) return@forEach

            val key = cleanUrl(track.url)
            mergedAudio[key] = mergedAudio[key]?.let { existing ->
                existing.copy(
                    label = firstNonBlank(
                        existing.label,
                        track.label
                    ).orEmpty(),
                    headers = mergePlaybackHeaders(
                        existing.headers,
                        track.headers
                    )
                )
            } ?: track
        }

        return ZoryvaExtractResolution(
            directSources = mergedDirect.values.toList(),
            servers = mergedServers.values.toList(),
            subtitles = mergedSubtitles.values.toList(),
            audioTracks = mergedAudio.values.toList()
        )
    }

    private suspend fun resolveZoryvaScraped(
        document: Document?,
        pageUrl: String,
        context: PlaybackContext?
    ): ZoryvaExtractResolution {
        context ?: return ZoryvaExtractResolution(emptyList(), emptyList())
        val pageDocument = document ?: return ZoryvaExtractResolution(emptyList(), emptyList())

        val mediaObject = extractInitialMediaObject(pageDocument)
        val mediaType = when (context.type.lowercase(Locale.ROOT)) {
            "movie" -> "movie"
            "tv" -> "tv"
            "anime" -> "anime"
            else -> return ZoryvaExtractResolution(emptyList(), emptyList())
        }

        val title = firstNonBlank(
            mediaObject?.optString("title"),
            document?.selectFirst("h1")?.text(),
            titleFromPath(URI(pageUrl).path.orEmpty())
        ).orEmpty()

        val originalTitle = firstNonBlank(
            mediaObject?.optString("originalTitle"),
            title
        ).orEmpty()

        val imdbId = firstNonBlank(
            mediaObject?.optString("imdbId"),
            extractImdbId(document?.html().orEmpty())
        )

        val runtime = mediaObject?.optInt("runtime", 0)?.takeIf { it > 0 }

        val query = buildPlaybackQuery(
            mediaType = mediaType,
            externalId = context.tmdbId,
            title = title,
            originalTitle = originalTitle,
            imdbId = imdbId,
            runtime = runtime,
            season = context.season,
            episode = context.episode
        )

        val endpoint = "$BASE_URL/api/scraped?$query"
        val response = runCatching {
            app.get(
                endpoint,
                headers = PAGE_HEADERS + mapOf(
                    "Referer" to pageUrl,
                    "Accept" to "application/json,text/plain,*/*;q=0.8",
                    "Cache-Control" to "no-cache",
                    "Pragma" to "no-cache"
                )
            )
        }.getOrNull() ?: return ZoryvaExtractResolution(emptyList(), emptyList())

        if (response.code !in 200..399) {
            return ZoryvaExtractResolution(emptyList(), emptyList())
        }

        return parseZoryvaExtractResponse(
            raw = response.text,
            referer = pageUrl
        )
    }

    private fun buildPlaybackQuery(
        mediaType: String,
        externalId: String,
        title: String,
        originalTitle: String,
        imdbId: String?,
        runtime: Int?,
        season: Int?,
        episode: Int?,
        includeSoft: Boolean = true,
        airDate: String? = null,
        refresh: Boolean = false
    ): String {
        val parts = ArrayList<String>()
        fun add(name: String, value: String?) {
            if (!value.isNullOrBlank()) {
                parts += "${name}=${encode(value)}"
            }
        }

        add("mediaType", mediaType)
        add("externalId", externalId)
        add("title", title)
        add("originalTitle", originalTitle)
        add("imdbId", imdbId)
        runtime?.takeIf { it > 0 }?.let { parts += "runtime=$it" }
        airDate?.takeIf { it.isNotBlank() }?.let { add("airDate", it) }
        season?.let { parts += "season=$it" }
        episode?.let { parts += "episode=$it" }
        if (refresh) {
            parts += "refresh=1"
        }
        if (includeSoft) {
            parts += "soft=1"
        }

        return parts.joinToString("&")
    }

    private fun extractImdbId(
        raw: String
    ): String? {
        if (raw.isBlank()) return null
        return Regex("""(?i)\"imdbId\"\s*:\s*\"(tt\d{4,12})\""")
            .find(raw)
            ?.groupValues
            ?.getOrNull(1)
            ?: Regex("""(?i)(?:imdb\s*id|imdbId)[\s:=\"']+(tt\d{4,12})""")
                .find(raw)
                ?.groupValues
                ?.getOrNull(1)
    }

    private fun parseZoryvaExtractResponse(
        raw: String,
        referer: String
    ): ZoryvaExtractResolution {
        val direct = linkedMapOf<String, MediaCandidate>()
        val servers = linkedMapOf<String, VidrockServerTarget>()
        val subtitles = linkedMapOf<String, Pair<String, String>>()
        val audioTracks = linkedMapOf<String, AudioTrackCandidate>()

        fun isRejectedStatus(value: String): Boolean {
            val status = value.trim().lowercase(Locale.ROOT)
            if (status.isBlank()) return false

            val explicitFailures = setOf(
                "error",
                "failed",
                "failure",
                "broken",
                "disabled",
                "offline",
                "unavailable",
                "no-sources",
                "no_source",
                "dead",
                "invalid",
                "rejected"
            )

            return status in explicitFailures ||
                status.contains("failed") ||
                status.contains("error") ||
                status.contains("broken") ||
                status.contains("disabled") ||
                status.contains("unavailable")
        }

        fun isSubtitleUrl(url: String): Boolean {
            val lower = url.lowercase(Locale.ROOT).substringBefore('?')
            return lower.endsWith(".vtt") ||
                lower.endsWith(".srt") ||
                lower.endsWith(".ass") ||
                lower.endsWith(".ssa")
        }

        fun addSubtitle(
            rawUrl: String,
            language: String,
            fallbackName: String = language
        ) {
            val clean = normalizeExtractedUrl(rawUrl, referer) ?: return
            if (!isSubtitleUrl(clean)) return
            val lang = language.ifBlank { fallbackName.ifBlank { "Subtitles" } }
            subtitles.putIfAbsent("$lang|$clean", lang to clean)
        }

        fun addAudio(
            rawUrl: String,
            label: String = "",
            sourceHeaders: Map<String, String> = emptyMap()
        ) {
            val clean = normalizeExtractedUrl(rawUrl, referer) ?: return
            if (!clean.startsWith("http", true)) return
            if (isObviouslyPromotional(clean) || isIgnoredHost(clean)) return
            if (clean.lowercase(Locale.ROOT).substringBefore('?').endsWith(".vtt") ||
                clean.lowercase(Locale.ROOT).substringBefore('?').endsWith(".srt") ||
                clean.lowercase(Locale.ROOT).substringBefore('?').endsWith(".ass") ||
                clean.lowercase(Locale.ROOT).substringBefore('?').endsWith(".ssa")
            ) return

            val identity = cleanUrl(clean)
            audioTracks.putIfAbsent(
                identity,
                AudioTrackCandidate(
                    url = clean,
                    label = label.trim(),
                    headers = sanitizePlaybackHeaders(sourceHeaders)
                )
            )
        }

        fun addMedia(
            rawUrl: String,
            label: String = "Zoryva Extract",
            sourceReferer: String = referer,
            sourceOrigin: String = "",
            qualityHint: String = "",
            height: Int? = null,
            sourceHeaders: Map<String, String> = emptyMap(),
            mediaTypeHint: String = "",
            audioTrackCandidates: List<AudioTrackCandidate> = emptyList(),
            audioLabelHint: String = "",
            hlsMasterHint: Boolean = false
        ) {
            val clean0 = cleanUrl(rawUrl)
            if (clean0.isBlank()) return

            val upstream = if (clean0.startsWith("$BASE_URL/api/proxy?", true)) {
                extractProxyParameter(clean0, "url") ?: return
            } else {
                clean0
            }

            val clean = cleanUrl(upstream)
            if (!isPlayableMedia(clean, mediaTypeHint)) return
            if (isObviouslyPromotional(clean) || isIgnoredHost(clean)) return

            val safeSourceHeaders = sanitizePlaybackHeaders(sourceHeaders)

            val headerReferer = headerValue(safeSourceHeaders, "Referer")
            val headerOrigin = headerValue(safeSourceHeaders, "Origin")

            val finalReferer = if (clean.contains("peakstorm.top/", true)) {
                firstNonBlank(
                    extractProxyParameter(clean0, "referer"),
                    headerReferer,
                    sourceReferer.takeUnless { it.contains("zoryva.me", true) },
                    "https://speedracelight.com/"
                ).orEmpty()
            } else {
                firstNonBlank(
                    headerReferer,
                    sourceReferer
                ).orEmpty()
            }

            val finalOrigin = if (clean.contains("peakstorm.top/", true)) {
                firstNonBlank(
                    extractProxyParameter(clean0, "origin"),
                    headerOrigin,
                    sourceOrigin,
                    originOf(finalReferer)
                ).orEmpty()
            } else {
                firstNonBlank(
                    headerOrigin,
                    sourceOrigin
                ).orEmpty()
            }

            val quality = when {
                height != null && height > 0 -> qualityFromHeight(height)
                else -> qualityFromUrl(clean, qualityHint.ifBlank { label })
            }

            /*
             * Do not manufacture additional quality URLs here. The /api/extract
             * response already gives us the exact current playable URL for each
             * source (for example the real 1080p, 720p and 480p playlists).
             * Synthetic variants can be stale or nonexistent, so only emit the
             * exact URL that the website returned.
             */
            direct.putIfAbsent(
                normalizeMediaIdentity(clean),
                MediaCandidate(
                    url = clean,
                    referer = finalReferer,
                    origin = finalOrigin,
                    quality = quality,
                    label = qualityHint.ifBlank { label },
                    server = label.ifBlank { "Zoryva Extract" },
                    latencyMs = 0L,
                    isHlsMaster = hlsMasterHint ||
                        qualityHint.trim().equals("auto", true) ||
                        label.trim().equals("auto", true),
                    audioLabel = audioLabelHint.trim(),
                    isHls = looksLikeHlsUrl(clean, mediaTypeHint),
                    audioTracks = audioTrackCandidates.distinctBy { cleanUrl(it.url) },
                    headers = safeSourceHeaders
                )
            )
        }

        fun addServer(
            rawUrl: String,
            label: String = "Zoryva Server",
            sourceReferer: String = referer
        ) {
            val clean = normalizeExtractedUrl(rawUrl, sourceReferer) ?: return
            if (clean.isBlank()) return
            if (isPlayableMedia(clean)) {
                addMedia(clean, label, sourceReferer)
                return
            }
            if (isObviouslyPromotional(clean) || isIgnoredHost(clean)) return

            servers.putIfAbsent(
                "$label|$clean",
                VidrockServerTarget(
                    url = clean,
                    name = label.ifBlank { "Zoryva Server" },
                    referer = sourceReferer
                )
            )
        }

        fun walkJson(
            value: Any?,
            parentKey: String = "",
            parentReferer: String = referer,
            parentOrigin: String = "",
            parentHeaders: Map<String, String> = emptyMap(),
            parentLabel: String = "Zoryva Extract",
            parentApproved: Boolean = true
        ) {
            when (value) {
                is JSONObject -> {
                    val nestedHeaders = buildMap<String, String> {
                        val keys = value.keys()
                        while (keys.hasNext()) {
                            val key = keys.next()
                            if (!key.equals("headers", true)) continue
                            val headerObject = value.optJSONObject(key) ?: continue
                            val headerKeys = headerObject.keys()
                            while (headerKeys.hasNext()) {
                                val headerKey = headerKeys.next()
                                val headerValue = headerObject.optString(headerKey).trim()
                                if (headerValue.isNotBlank()) {
                                    put(headerKey, headerValue)
                                }
                            }
                        }
                    }

                    val explicitHeaders = buildMap<String, String> {
                        listOf("referer", "referrer", "httpReferer", "origin", "httpOrigin").forEach { key ->
                            val valueText = value.optString(key).trim()
                            if (valueText.isNotBlank()) {
                                put(
                                    when {
                                        key.equals("referer", true) ||
                                            key.equals("referrer", true) ||
                                            key.equals("httpReferer", true) -> "Referer"
                                        else -> "Origin"
                                    },
                                    valueText
                                )
                            }
                        }
                    }

                    val objectHeaders = mergePlaybackHeaders(
                        parentHeaders,
                        nestedHeaders + explicitHeaders
                    )

                    val objectReferer = firstNonBlank(
                        value.optString("referer"),
                        value.optString("referrer"),
                        value.optString("httpReferer"),
                        headerValue(objectHeaders, "Referer"),
                        parentReferer
                    ).orEmpty()

                    val objectOrigin = firstNonBlank(
                        value.optString("origin"),
                        value.optString("httpOrigin"),
                        headerValue(objectHeaders, "Origin"),
                        parentOrigin
                    ).orEmpty()

                    val objectLabel = firstNonBlank(
                        value.optString("name"),
                        value.optString("server"),
                        value.optString("provider"),
                        value.optString("sourceName"),
                        value.optString("source"),
                        value.optString("quality"),
                        parentKey,
                        parentLabel
                    ).orEmpty()

                    val broken = value.optBoolean("broken", false)
                    val disabled = value.optBoolean("disabled", false)
                    val rejected = isRejectedStatus(value.optString("status"))
                    /*
                     * Unknown/in-progress statuses must not veto a child object
                     * that already contains an exact media URL. Only explicit
                     * failure states propagate down the JSON tree.
                     */
                    val approved = parentApproved && !broken && !disabled && !rejected

                    val qualityHint = firstNonBlank(
                        value.optString("quality"),
                        value.optString("resolution"),
                        value.optString("label")
                    ).orEmpty()
                    val height = value.optInt("height", 0).takeIf { it > 0 }

                    val explicitLanguage = firstNonBlank(
                        value.optString("language"),
                        value.optString("lang"),
                        value.optString("name")
                    ).orEmpty()

                    val jsonMediaType = value.optString("type").trim()
                    val objectIsAudio =
                        jsonMediaType.equals("audio", true) ||
                            parentKey.contains("audio", true)

                    if (approved) {
                        val directKeys = listOf(
                            "url", "src", "file", "link", "streamUrl",
                            "stream_url", "videoUrl", "video_url", "playUrl",
                            "play_url", "mediaUrl", "media_url", "sourceUrl",
                            "source_url", "manifest", "playlist", "m3u8", "mp4"
                        )

                        directKeys.forEach { key ->
                            val candidate = value.optString(key).trim()
                            if (candidate.isBlank()) return@forEach

                            if (isSubtitleUrl(candidate) || parentKey.contains("subtitle", true) || key.contains("subtitle", true)) {
                                addSubtitle(
                                    candidate,
                                    firstNonBlank(
                                        value.optString("language"),
                                        value.optString("lang"),
                                        explicitLanguage,
                                        key
                                    ).orEmpty(),
                                    objectLabel
                                )
                            } else if (objectIsAudio || key.contains("audio", true)) {
                                addAudio(
                                    candidate,
                                    firstNonBlank(
                                        value.optString("language"),
                                        value.optString("lang"),
                                        explicitLanguage,
                                        objectLabel
                                    ).orEmpty(),
                                    objectHeaders
                                )
                            } else if (
                                isPlayableMedia(
                                    candidate,
                                    jsonMediaType.ifBlank { key }
                                )
                            ) {
                                addMedia(
                                    candidate,
                                    objectLabel.ifBlank { key },
                                    objectReferer,
                                    objectOrigin,
                                    qualityHint.ifBlank { key },
                                    height,
                                    objectHeaders,
                                    jsonMediaType,
                                    emptyList()
                                )
                            } else if (
                                key.contains("server", true) ||
                                key.contains("source", true) ||
                                key.contains("player", true) ||
                                key.contains("embed", true)
                            ) {
                                addServer(candidate, objectLabel.ifBlank { key }, objectReferer)
                            }
                        }
                    }

                    val iterator = value.keys()
                    while (iterator.hasNext()) {
                        val key = iterator.next()
                        val child = value.opt(key)
                        if (approved && key.contains("subtitle", true) && child is String) {
                            addSubtitle(child, explicitLanguage, objectLabel)
                        }
                        walkJson(
                            child,
                            parentKey = key,
                            parentReferer = objectReferer,
                            parentOrigin = objectOrigin,
                            parentHeaders = objectHeaders,
                            parentLabel = objectLabel,
                            parentApproved = approved
                        )
                    }
                }

                is JSONArray -> {
                    for (i in 0 until value.length()) {
                        walkJson(
                            value.opt(i),
                            parentKey = parentKey,
                            parentReferer = parentReferer,
                            parentOrigin = parentOrigin,
                            parentHeaders = parentHeaders,
                            parentLabel = parentLabel,
                            parentApproved = parentApproved
                        )
                    }
                }

                is String -> {
                    val text = value.trim()
                    if (text.isBlank()) return

                    if (parentApproved && (parentKey.contains("subtitle", true) || isSubtitleUrl(text))) {
                        addSubtitle(text, parentLabel, parentLabel)
                    }

                    extractUrlsFromRawText(text).forEach { found ->
                        if (!parentApproved) return@forEach
                        if (isSubtitleUrl(found)) {
                            addSubtitle(found, parentLabel, parentLabel)
                        } else if (parentKey.contains("audio", true)) {
                            addAudio(found, parentLabel, parentHeaders)
                        } else if (isPlayableMedia(found)) {
                            addMedia(
                                found,
                                parentLabel,
                                parentReferer,
                                parentOrigin,
                                sourceHeaders = parentHeaders,
                                mediaTypeHint = parentKey
                            )
                        } else if (
                            parentKey.contains("server", true) ||
                            parentKey.contains("source", true) ||
                            parentKey.contains("player", true) ||
                            parentKey.contains("embed", true)
                        ) {
                            addServer(found, parentLabel, parentReferer)
                        }
                    }
                }
            }
        }

        /*
         * Deterministic parse of the exact /api/extract contract observed on
         * Zoryva. This is intentionally before the generic recursive walker.
         * The live TV response may expose an extensionless workers.dev URL with
         * `type: mp4`; relying on the URL suffix alone loses that source.
         */
        runCatching {
            val root = JSONObject(raw)
            val resultObject = root.optJSONObject("result") ?: root

            val responseHeaders = buildMap<String, String> {
                val headerObject = resultObject.optJSONObject("headers") ?: return@buildMap
                val keys = headerObject.keys()
                while (keys.hasNext()) {
                    val key = keys.next()
                    val value = headerObject.optString(key).trim()
                    if (value.isNotBlank()) put(key, value)
                }
            }

            fun mergeObjectHeaders(value: JSONObject?): Map<String, String> {
                if (value == null) return responseHeaders
                return buildMap {
                    responseHeaders.forEach { (key, item) -> put(key, item) }
                    val keys = value.keys()
                    while (keys.hasNext()) {
                        val key = keys.next()
                        val item = value.optString(key).trim()
                        if (item.isNotBlank()) put(key, item)
                    }
                }
            }

            fun addStructuredVideo(video: JSONObject, server: JSONObject? = null) {
                val url = video.optString("url").trim()
                if (url.isBlank()) return

                val typeHint = video.optString("type").trim()
                val qualityHint = firstNonBlank(
                    video.optString("quality"),
                    server?.optString("quality"),
                    video.optString("resolution"),
                    server?.optString("resolution")
                ).orEmpty()

                val headers = mergeObjectHeaders(video.optJSONObject("headers"))
                val sourceReferer = firstNonBlank(
                    headerValue(headers, "Referer"),
                    video.optString("referer"),
                    server?.optString("referer"),
                    referer
                ).orEmpty()
                val sourceOrigin = firstNonBlank(
                    headerValue(headers, "Origin"),
                    video.optString("origin"),
                    server?.optString("origin")
                ).orEmpty()

                val providerLabel = firstNonBlank(
                    server?.optString("provider"),
                    video.optString("provider"),
                    server?.optString("name"),
                    video.optString("name"),
                    qualityHint,
                    "Zoryva API"
                ).orEmpty()

                val audioLabelHint = firstNonBlank(
                    server?.optString("audioLabel"),
                    video.optString("audioLabel"),
                    server?.optString("audioLanguage"),
                    video.optString("audioLanguage")
                ).orEmpty()

                val hlsMasterHint =
                    video.optBoolean("auto", false) ||
                        server?.optBoolean("auto", false) == true ||
                        qualityHint.equals("auto", true)

                addMedia(
                    rawUrl = url,
                    label = providerLabel,
                    sourceReferer = sourceReferer,
                    sourceOrigin = sourceOrigin,
                    qualityHint = qualityHint,
                    height = video.optInt("height", 0).takeIf { it > 0 },
                    sourceHeaders = headers,
                    mediaTypeHint = typeHint,
                    audioLabelHint = audioLabelHint,
                    hlsMasterHint = hlsMasterHint
                )
            }

            resultObject.optJSONArray("videos")?.let { videos ->
                for (i in 0 until videos.length()) {
                    videos.optJSONObject(i)?.let { addStructuredVideo(it) }
                }
            }

            resultObject.optJSONArray("servers")?.let { serversArray ->
                for (i in 0 until serversArray.length()) {
                    val server = serversArray.optJSONObject(i) ?: continue
                    val status = server.optString("status").trim().lowercase(Locale.ROOT)
                    if (
                        server.optBoolean("broken", false) ||
                        server.optBoolean("disabled", false) ||
                        status in setOf("error", "failed", "failure", "broken", "disabled", "offline", "unavailable", "invalid", "rejected")
                    ) continue

                    server.optJSONObject("video")?.let { addStructuredVideo(it, server) }
                        ?: firstNonBlank(
                            server.optString("url"),
                            server.optString("src"),
                            server.optString("link")
                        )?.let { serverUrl ->
                            addServer(
                                serverUrl,
                                firstNonBlank(
                                    server.optString("name"),
                                    server.optString("provider"),
                                    "Zoryva Server"
                                ).orEmpty(),
                                referer
                            )
                        }
                }
            }
        }

        runCatching { walkJson(JSONObject(raw)) }
        runCatching { walkJson(JSONArray(raw)) }

        /*
         * The normal response is JSON, but raw-text URL extraction is kept as a
         * defensive fallback for escaped/serialized variants.
         */
        extractUrlsFromRawText(raw).forEach { found ->
            if (isSubtitleUrl(found)) {
                subtitles.putIfAbsent("Subtitles|$found", "Subtitles" to found)
            } else if (isPlayableMedia(found)) {
                addMedia(found)
            } else if (
                found.contains("server", true) ||
                found.contains("embed", true) ||
                found.contains("player", true)
            ) {
                addServer(found)
            }
        }

        return ZoryvaExtractResolution(
            directSources = direct.values.toList(),
            servers = servers.values.toList(),
            subtitles = subtitles.values.toList(),
            audioTracks = audioTracks.values.toList()
        )
    }

    private suspend fun resolveVidrock(
        context: PlaybackContext?
    ): VidrockResolution {
        context ?: return VidrockResolution(emptyList(), emptyList())

        val key = withTimeoutOrNull(VIDROCK_KEY_TIMEOUT_MS) {
            fetchVidrockKey()
        } ?: return VidrockResolution(emptyList(), emptyList())

        if (key.length < 16) return VidrockResolution(emptyList(), emptyList())

        val mediaKind = when {
            context.type == "movie" -> "movie"
            (context.type == "tv" || context.type == "anime") &&
                context.season != null && context.episode != null -> "tv"
            else -> "movie"
        }

        val plainIdentifier = if (mediaKind == "movie") {
            context.tmdbId
        } else {
            "${context.tmdbId}_${context.season}_${context.episode}"
        }

        val encoded = encryptVidrockIdentifier(
            plain = plainIdentifier,
            key = key
        ) ?: return VidrockResolution(emptyList(), emptyList())

        val apiUrl =
            "$VIDROCK_ORIGIN/api/$mediaKind/$encoded"

        val response = withTimeoutOrNull(VIDROCK_API_TIMEOUT_MS) {
            runCatching {
                app.get(
                    apiUrl,
                    headers = mapOf(
                        "User-Agent" to USER_AGENT,
                        "Accept" to "application/json,text/plain,*/*;q=0.8",
                        "Accept-Language" to "en-US,en;q=0.9",
                        "Cache-Control" to "no-cache",
                        "Pragma" to "no-cache",
                        "Origin" to VIDROCK_ORIGIN,
                        "Referer" to "$VIDROCK_ORIGIN/"
                    )
                )
            }.getOrNull()
        } ?: return VidrockResolution(emptyList(), emptyList())

        if (response.code !in 200..399) {
            return VidrockResolution(emptyList(), emptyList())
        }

        return parseVidrockResponse(
            raw = response.text,
            key = key
        )
    }

    private suspend fun fetchVidrockKey(): String? {
        val page = withTimeoutOrNull(VIDROCK_SCRIPT_TIMEOUT_MS) {
            runCatching {
                app.get(
                    "$VIDROCK_ORIGIN/",
                    headers = mapOf(
                        "User-Agent" to USER_AGENT,
                        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
                        "Accept-Language" to "en-US,en;q=0.9",
                        "Cache-Control" to "no-cache",
                        "Pragma" to "no-cache",
                        "Referer" to "$VIDROCK_ORIGIN/"
                    )
                )
            }.getOrNull()
        } ?: return null

        if (page.code !in 200..399) return null

        val scriptUrls = linkedSetOf<String>()
        page.document?.select("script[src]")?.forEach { script ->
            val absolute = script.absUrl("src").trim()
            if (absolute.startsWith("http", true)) {
                scriptUrls.add(absolute)
            }
        }

        /* Some crawlers expose chunk URLs only in raw HTML. */
        Regex(
            """(?:https?:)?//[^\"'\\s]+(?:\.js(?:\?[^\"'\\s]*)?)""",
            RegexOption.IGNORE_CASE
        ).findAll(page.text).forEach { match ->
            val raw = match.value
            val absolute = if (raw.startsWith("//")) {
                "https:$raw"
            } else {
                raw
            }
            scriptUrls.add(cleanUrl(absolute))
        }

        val bounded = scriptUrls
            .take(VIDROCK_SCRIPT_LIMIT)
            .toList()

        val scriptBodies = coroutineScope {
            bounded.map { url ->
                async {
                    withTimeoutOrNull(VIDROCK_SCRIPT_TIMEOUT_MS) {
                        runCatching {
                            app.get(
                                url,
                                headers = mapOf(
                                    "User-Agent" to USER_AGENT,
                                    "Accept" to "*/*",
                                    "Referer" to "$VIDROCK_ORIGIN/",
                                    "Cache-Control" to "no-cache",
                                    "Pragma" to "no-cache"
                                )
                            ).text
                        }.getOrNull()
                    }
                }
            }.awaitAll().filterNotNull()
        }

        val keyPatterns = listOf(
            Regex("""(?i)(?:const|let|var)\\s+qw\\s*=\\s*[\"']([^\"']+)[\"']"""),
            Regex("""(?i)(?:^|[,{;])\\s*qw\\s*[:=]\\s*[\"']([^\"']+)[\"']"""),
            Regex("""(?i)[\"']qw[\"']\\s*[:=]\\s*[\"']([^\"']+)[\"']""")
        )

        for (body in scriptBodies) {
            for (pattern in keyPatterns) {
                val match = pattern.find(body) ?: continue
                val key = match.groupValues.getOrNull(1)?.trim().orEmpty()
                if (key.length >= 16) return key
            }
        }

        return null
    }

    private fun encryptVidrockIdentifier(
        plain: String,
        key: String
    ): String? {
        return runCatching {
            val keyBytes = key.toByteArray(StandardCharsets.UTF_8)
            if (keyBytes.size < 16) return null

            val aesKey = when {
                keyBytes.size == 16 || keyBytes.size == 24 || keyBytes.size == 32 -> keyBytes
                else -> MessageDigest
                    .getInstance("SHA-256")
                    .digest(keyBytes)
            }

            val ivBytes = key
                .toByteArray(StandardCharsets.UTF_8)
                .copyOfRange(0, 16)

            val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
            cipher.init(
                Cipher.ENCRYPT_MODE,
                SecretKeySpec(aesKey, "AES"),
                IvParameterSpec(ivBytes)
            )

            base64UrlNoPadding(
                cipher.doFinal(
                    plain.toByteArray(StandardCharsets.UTF_8)
                )
            )
        }.getOrNull()
    }

    private fun parseVidrockResponse(
        raw: String,
        key: String
    ): VidrockResolution {
        val direct = linkedMapOf<String, MediaCandidate>()
        val servers = linkedMapOf<String, VidrockServerTarget>()

        fun addDirect(
            url: String,
            label: String = "Vidrock",
            referer: String = "$VIDROCK_ORIGIN/"
        ) {
            val clean = normalizeExtractedUrl(url, referer) ?: return
            if (!isPlayableMedia(clean)) return
            if (isObviouslyPromotional(clean)) return
            if (isIgnoredHost(clean)) return

            val resolvedReferer = mediaRefererFor(clean, referer)

            val candidate = MediaCandidate(
                url = clean,
                referer = resolvedReferer,
                origin = originOf(resolvedReferer),
                quality = qualityFromUrl(clean, label),
                label = label,
                server = label,
                latencyMs = 0L,
                isHlsMaster = false,
                audioLabel = ""
            )

            direct.putIfAbsent(
                normalizeMediaIdentity(clean),
                candidate
            )
        }

        fun addServer(
            url: String,
            name: String,
            referer: String = "$VIDROCK_ORIGIN/"
        ) {
            val clean = cleanUrl(url)
            if (clean.isBlank()) return
            if (isPlayableMedia(clean)) {
                addDirect(clean, name, referer)
                return
            }
            if (isIgnoredHost(clean) || isObviouslyPromotional(clean)) return

            val keyId = "$name|$clean"
            servers.putIfAbsent(
                keyId,
                VidrockServerTarget(
                    url = clean,
                    name = name.ifBlank { "Vidrock Server" },
                    referer = referer
                )
            )
        }

        fun normalizePossibleUrl(value: String): String? {
            val cleaned = value
                .trim()
                .trim('"', '\'', '`')
                .replace("\\/", "/")
                .replace("\\u002F", "/")
                .replace("\\u002f", "/")
                .replace("\\u0026", "&")
                .replace("&amp;", "&")

            return when {
                cleaned.startsWith("http://", true) -> cleaned
                cleaned.startsWith("https://", true) -> cleaned
                cleaned.startsWith("//") -> "https:$cleaned"
                cleaned.startsWith("/") -> "$VIDROCK_ORIGIN$cleaned"
                else -> null
            }
        }

        fun walk(value: Any?, parentKey: String = "") {
            when (value) {
                is JSONObject -> {
                    val iterator = value.keys()
                    while (iterator.hasNext()) {
                        val keyName = iterator.next()
                        val child = value.opt(keyName)

                        if (child is String) {
                            val str = child.trim()
                            val lowerKey = keyName.lowercase(Locale.ROOT)
                            val probableServerName = firstNonBlank(
                                value.optString("name"),
                                value.optString("title"),
                                value.optString("server"),
                                value.optString("provider"),
                                keyName
                            ).orEmpty()

                            normalizePossibleUrl(str)?.let { possible ->
                                when {
                                    isPlayableMedia(possible) ->
                                        addDirect(
                                            possible,
                                            probableServerName.ifBlank { "Vidrock" }
                                        )
                                    lowerKey.contains("server") ||
                                        lowerKey.contains("embed") ||
                                        lowerKey.contains("player") ||
                                        lowerKey.contains("source") ||
                                        lowerKey == "url" ||
                                        lowerKey == "link" ->
                                        addServer(
                                            possible,
                                            probableServerName.ifBlank { "Vidrock Server" }
                                        )
                                }
                            }

                            if (lowerKey.contains("m3u8") || lowerKey.contains("mp4")) {
                                normalizePossibleUrl(str)?.let { possible ->
                                    addDirect(
                                        possible,
                                        probableServerName.ifBlank { "Vidrock" }
                                    )
                                }
                            }
                        }

                        walk(child, keyName)
                    }
                }

                is JSONArray -> {
                    for (i in 0 until value.length()) {
                        walk(value.opt(i), parentKey)
                    }
                }

                is String -> {
                    extractUrlsFromRawText(value).forEach { url ->
                        addDirect(url, parentKey.ifBlank { "Vidrock" })
                    }
                }
            }
        }

        /* Plain JSON response first. */
        runCatching {
            walk(JSONObject(raw))
        }

        runCatching {
            val array = JSONArray(raw)
            walk(array)
        }

        /* Always scan the raw body for direct/disguised URLs. */
        extractUrlsFromRawText(raw).forEach { url ->
            addDirect(url)
        }

        /*
         * Current Vidrock implementations can return an encrypted payload.
         * Try JSON-declared encrypted fields first, then bounded candidate
         * strings. The crypto helper supports the common AES-256-GCM layouts
         * without storing any key or decrypted stream between plays.
         */
        val encryptedBlobs = collectEncryptedBlobs(raw)
        for (blob in encryptedBlobs.take(24)) {
            val plaintext = decryptVidrockGcm(
                blob = blob,
                key = key
            ) ?: continue

            parseVidrockDecryptedPayload(
                plaintext,
                direct,
                servers
            )
        }

        /* Some responses may use the older AES-CBC payload format. */
        for (blob in encryptedBlobs.take(12)) {
            val plaintext = decryptVidrockCbc(
                encrypted = blob.data,
                key = key,
                ivOverride = blob.iv
            ) ?: continue

            parseVidrockDecryptedPayload(
                plaintext,
                direct,
                servers
            )
        }

        return VidrockResolution(
            directSources = direct.values.toList(),
            servers = servers.values.toList()
        )
    }

    private data class EncryptedBlob(
        val data: String,
        val iv: String? = null,
        val tag: String? = null
    )

    private fun collectEncryptedBlobs(
        raw: String
    ): List<EncryptedBlob> {
        val result = linkedSetOf<String>()
        val blobs = mutableListOf<EncryptedBlob>()

        fun add(data: String?, iv: String? = null, tag: String? = null) {
            val clean = data?.trim().orEmpty()
            if (clean.length < 20) return
            val fingerprint = "$clean|${iv.orEmpty()}|${tag.orEmpty()}"
            if (result.add(fingerprint)) {
                blobs += EncryptedBlob(clean, iv, tag)
            }
        }

        fun walk(value: Any?) {
            when (value) {
                is JSONObject -> {
                    val dataKeys = listOf(
                        "data", "payload", "encrypted", "ciphertext",
                        "content", "result", "response"
                    )

                    for (dataKey in dataKeys) {
                        val data = value.optString(dataKey, "")
                        if (data.isBlank()) continue

                        val iv = firstNonBlank(
                            value.optString("iv", ""),
                            value.optString("nonce", ""),
                            value.optString("initializationVector", "")
                        )

                        val tag = firstNonBlank(
                            value.optString("tag", ""),
                            value.optString("authTag", "")
                        )

                        add(data, iv, tag)
                    }

                    val iterator = value.keys()
                    while (iterator.hasNext()) {
                        walk(value.opt(iterator.next()))
                    }
                }

                is JSONArray -> {
                    for (i in 0 until value.length()) {
                        walk(value.opt(i))
                    }
                }
            }
        }

        runCatching { walk(JSONObject(raw)) }
        runCatching { walk(JSONArray(raw)) }

        /* Bounded fallback: look for long base64/base64url strings in raw JSON. */
        Regex(
            """(?<![A-Za-z0-9_-])[A-Za-z0-9_+/=-]{32,}(?![A-Za-z0-9_-])"""
        ).findAll(raw).take(24).forEach { match ->
            add(match.value)
        }

        return blobs
    }

    private fun decryptVidrockGcm(
        blob: EncryptedBlob,
        key: String
    ): String? {
        val cipherBytes = base64DecodeAny(blob.data) ?: return null

        val extraTag = blob.tag?.let(::base64DecodeAny)
        val fullCipher = if (extraTag != null) {
            cipherBytes + extraTag
        } else {
            cipherBytes
        }

        val explicitIv = blob.iv?.let(::base64DecodeAny)
        val ivCandidates = linkedSetOf<ByteArray>()
        if (explicitIv != null) ivCandidates.add(explicitIv)
        if (fullCipher.size >= 12) ivCandidates.add(fullCipher.copyOfRange(0, 12))
        if (fullCipher.size >= 16) ivCandidates.add(fullCipher.copyOfRange(0, 16))
        if (fullCipher.size >= 12) ivCandidates.add(fullCipher.copyOfRange(fullCipher.size - 12, fullCipher.size))
        if (fullCipher.size >= 16) ivCandidates.add(fullCipher.copyOfRange(fullCipher.size - 16, fullCipher.size))

        val keyBytes = key.toByteArray(StandardCharsets.UTF_8)
        val keyCandidates = linkedSetOf<ByteArray>()
        if (keyBytes.size == 16 || keyBytes.size == 24 || keyBytes.size == 32) {
            keyCandidates.add(keyBytes)
        }
        keyCandidates.add(MessageDigest.getInstance("SHA-256").digest(keyBytes))

        for (iv in ivCandidates) {
            if (iv.size !in 12..16) continue

            val payloadCandidates = linkedSetOf<ByteArray>()
            payloadCandidates.add(fullCipher)
            if (explicitIv == null && fullCipher.size > iv.size) {
                if (fullCipher.copyOfRange(0, iv.size).contentEquals(iv)) {
                    payloadCandidates.add(fullCipher.copyOfRange(iv.size, fullCipher.size))
                }
                if (fullCipher.copyOfRange(fullCipher.size - iv.size, fullCipher.size).contentEquals(iv)) {
                    payloadCandidates.add(fullCipher.copyOfRange(0, fullCipher.size - iv.size))
                }
            }

            for (payload in payloadCandidates) {
                if (payload.size < 17) continue
                for (secret in keyCandidates) {
                    val result = runCatching {
                        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                        cipher.init(
                            Cipher.DECRYPT_MODE,
                            SecretKeySpec(secret, "AES"),
                            GCMParameterSpec(128, iv)
                        )
                        String(
                            cipher.doFinal(payload),
                            StandardCharsets.UTF_8
                        )
                    }.getOrNull() ?: continue

                    if (looksLikeUsefulVidrockPayload(result)) return result
                }
            }
        }

        return null
    }

    private fun decryptVidrockCbc(
        encrypted: String,
        key: String,
        ivOverride: String?
    ): String? {
        val all = base64DecodeAny(encrypted) ?: return null
        if (all.size < 17) return null

        val salt = all.copyOfRange(0, 16)
        val ciphertext = all.copyOfRange(16, all.size)

        val derived = ArrayList<Byte>(48)
        var previous = ByteArray(0)

        while (derived.size < 48) {
            val md5 = MessageDigest.getInstance("MD5")
            md5.update(previous)
            md5.update(key.toByteArray(StandardCharsets.UTF_8))
            md5.update(salt)
            previous = md5.digest()
            derived.addAll(previous.toList())
        }

        val aesKey = ByteArray(32) { index -> derived[index] }
        val iv = ivOverride?.let(::base64DecodeAny)?.takeIf { it.size == 16 }
            ?: ByteArray(16) { index -> derived[32 + index] }

        return runCatching {
            val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
            cipher.init(
                Cipher.DECRYPT_MODE,
                SecretKeySpec(aesKey, "AES"),
                IvParameterSpec(iv)
            )
            val plain = String(
                cipher.doFinal(ciphertext),
                StandardCharsets.UTF_8
            )
            plain.takeIf(::looksLikeUsefulVidrockPayload)
        }.getOrNull()
    }

    private fun parseVidrockDecryptedPayload(
        plaintext: String,
        direct: MutableMap<String, MediaCandidate>,
        servers: MutableMap<String, VidrockServerTarget>
    ) {
        val normalized = plaintext
            .replace("\\/", "/")
            .replace("\\u002F", "/")
            .replace("\\u002f", "/")
            .trim()

        val urls = extractUrlsFromRawText(normalized)
        urls.forEach { url ->
            val clean = cleanUrl(url)
            if (!isPlayableMedia(clean)) {
                servers.putIfAbsent(
                    clean,
                    VidrockServerTarget(
                        url = clean,
                        name = "Vidrock Server",
                        referer = "$VIDROCK_ORIGIN/"
                    )
                )
            } else if (!isObviouslyPromotional(clean) && !isIgnoredHost(clean)) {
                direct.putIfAbsent(
                    normalizeMediaIdentity(clean),
                    MediaCandidate(
                        url = clean,
                        referer = "$VIDROCK_ORIGIN/",
                        origin = VIDROCK_ORIGIN,
                        quality = qualityFromUrl(clean, "Vidrock"),
                        label = "Vidrock",
                        server = "Vidrock",
                        latencyMs = 0L,
                        isHlsMaster = false,
                        audioLabel = ""
                    )
                )
            }
        }

        runCatching {
            val obj = JSONObject(normalized)
            val iterator = obj.keys()
            while (iterator.hasNext()) {
                val keyName = iterator.next()
                val child = obj.opt(keyName)
                if (child is String) {
                    val url = child.trim()
                    val possible = normalizeVidrockUrl(url) ?: continue
                    if (isPlayableMedia(possible)) {
                        if (!isObviouslyPromotional(possible) && !isIgnoredHost(possible)) {
                            direct.putIfAbsent(
                                normalizeMediaIdentity(possible),
                                MediaCandidate(
                                    url = possible,
                                    referer = "$VIDROCK_ORIGIN/",
                                    origin = VIDROCK_ORIGIN,
                                    quality = qualityFromUrl(possible, keyName),
                                    label = keyName,
                                    server = keyName,
                                    latencyMs = 0L,
                                    isHlsMaster = false,
                                    audioLabel = ""
                                )
                            )
                        }
                    } else if (
                        keyName.contains("server", true) ||
                        keyName.contains("source", true) ||
                        keyName.contains("embed", true) ||
                        keyName.contains("player", true)
                    ) {
                        servers.putIfAbsent(
                            possible,
                            VidrockServerTarget(
                                url = possible,
                                name = keyName,
                                referer = "$VIDROCK_ORIGIN/"
                            )
                        )
                    }
                }
            }
        }
    }

    private fun normalizeVidrockUrl(
        value: String
    ): String? {
        val cleaned = value
            .trim()
            .trim('"', '\'', '`')
            .replace("\\/", "/")
            .replace("\\u002F", "/")
            .replace("\\u002f", "/")
            .replace("\\u0026", "&")
            .replace("&amp;", "&")

        return when {
            cleaned.startsWith("https://", true) -> cleaned
            cleaned.startsWith("http://", true) -> cleaned
            cleaned.startsWith("//") -> "https:$cleaned"
            cleaned.startsWith("/") -> "$VIDROCK_ORIGIN$cleaned"
            else -> null
        }
    }

    private fun extractUrlsFromRawText(
        raw: String
    ): List<String> {
        if (raw.isBlank()) return emptyList()

        val result = linkedSetOf<String>()
        val normalized = raw
            .replace("\\/", "/")
            .replace("\\u002F", "/")
            .replace("\\u002f", "/")
            .replace("\\u0026", "&")
            .replace("&amp;", "&")

        Regex(
            """https?://[^\"'<>\\s\\]+""",
            RegexOption.IGNORE_CASE
        ).findAll(normalized).forEach { match ->
            val clean = cleanUrl(match.value)
            if (clean.isNotBlank()) result.add(clean)
        }

        Regex(
            """(?i)(?:^|[\"'=:,])(\\/[^\"'<>\\s]+)(?=$|[\"'`,}])"""
        ).findAll(normalized).forEach { match ->
            val value = match.groupValues[1]
            if (
                value.contains(".m3u8", true) ||
                value.contains(".mp4", true) ||
                value.contains("staticreverie", true) ||
                value.contains("vidrock", true) ||
                value.contains("embed", true) ||
                value.contains("player", true)
            ) {
                result.add(
                    if (value.startsWith("/")) {
                        "$VIDROCK_ORIGIN$value"
                    } else {
                        value
                    }
                )
            }
        }

        return result.toList()
    }

    private fun looksLikeUsefulVidrockPayload(
        plaintext: String
    ): Boolean {
        val value = plaintext.trim()
        if (value.isBlank()) return false
        if (value.startsWith("{") || value.startsWith("[")) return true
        return value.contains("http://", true) ||
            value.contains("https://", true) ||
            value.contains("m3u8", true) ||
            value.contains("mp4", true) ||
            value.contains("staticreverie", true)
    }

    private suspend fun inspectServerTarget(
        url: String,
        name: String,
        depth: Int,
        episodeId: String? = null,
        referer: String = "$VIDROCK_ORIGIN/"
    ): List<ServerResult> {
        if (depth > MAX_CRAWL_DEPTH) return emptyList()
        if (url.isBlank()) return emptyList()
        if (isIgnoredHost(url)) return emptyList()
        if (isObviouslyPromotional(url)) return emptyList()

        val started = System.currentTimeMillis()

        val fetched = withTimeoutOrNull(SERVER_PAGE_TIMEOUT_MS) {
            fetchPage(
                url = url,
                attempts = 2,
                refererOverride = referer
            )
        } ?: return emptyList()

        val document = fetched.document
        val current = inspectPageForSources(
            page = document,
            rawText = fetched.text,
            pageUrl = url,
            serverName = name
        ).copy(
            latencyMs = System.currentTimeMillis() - started
        )

        if (current.sources.isNotEmpty()) {
            return listOf(current)
        }

        val nested = discoverNestedPlayerPages(
            document = document,
            rawText = fetched.text,
            pageUrl = url
        ).take(MAX_SERVER_PAGES)

        if (nested.isEmpty()) return emptyList()

        return coroutineScope {
            nested.map { (nestedUrl, nestedName) ->
                async {
                    inspectServerTarget(
                        url = nestedUrl,
                        name = nestedName,
                        depth = depth + 1,
                        episodeId = episodeId,
                        referer = url
                    )
                }
            }.awaitAll().flatten()
        }
    }

    private suspend fun inspectPageForSources(
        page: Document?,
        rawText: String,
        pageUrl: String,
        serverName: String
    ): ServerResult {
        val started = System.currentTimeMillis()
        val candidates = extractMediaCandidates(
            document = page,
            rawText = rawText,
            pageUrl = pageUrl,
            serverName = serverName
        ).distinctBy { normalizeMediaIdentity(it.url) }
            .take(40)

        val inspected = coroutineScope {
            candidates.map { candidate ->
                async {
                    val info = if (candidate.isHls || looksLikeHlsUrl(candidate.url)) {
                        inspectHls(candidate)
                    } else {
                        null
                    }

                    /*
                     * A direct page/server source is already a concrete media
                     * URL exposed by Zoryva. In this secondary path, do not
                     * impose another network probe gate: the primary structured
                     * resolver already performed the authoritative source
                     * selection, and CDN anti-bot/range behavior can make a
                     * lightweight probe disagree with the actual player.
                     */
                    val finalCandidate = if (info != null) {
                        candidate.copy(
                            quality = maxOf(candidate.quality, info.maxQuality),
                            isHlsMaster = info.isMaster,
                            audioLabel = info.audioLabel
                        )
                    } else {
                        candidate
                    }

                    finalCandidate to info
                }
            }.awaitAll()
        }.filterNotNull()

        val valid = inspected.map { it.first }
        val subtitles = linkedSetOf<Pair<String, String>>()
        inspected.forEach { pair ->
            pair.second?.let { subtitles.addAll(it.subtitles) }
        }

        if (page != null) {
            subtitles.addAll(
                extractExternalSubtitles(
                    page = page,
                    baseUrl = pageUrl
                )
            )
        }

        return ServerResult(
            serverUrl = pageUrl,
            serverName = serverName,
            latencyMs = System.currentTimeMillis() - started,
            sources = valid,
            subtitles = subtitles.toList()
        )
    }

    private fun extractMediaCandidates(
        document: Document?,
        rawText: String,
        pageUrl: String,
        serverName: String
    ): List<MediaCandidate> {
        val result = linkedMapOf<String, MediaCandidate>()

        fun add(
            rawUrl: String?,
            label: String? = null,
            referer: String = pageUrl,
            origin: String = originOf(pageUrl)
        ) {
            val url = normalizeExtractedUrl(rawUrl, pageUrl) ?: return
            if (!isPlayableMedia(url)) return
            if (isObviouslyPromotional(url)) return
            if (isIgnoredHost(url)) return

            val quality = qualityFromUrl(
                url,
                label.orEmpty()
            )

            val key = normalizeMediaIdentity(url)

            result.putIfAbsent(
                key,
                MediaCandidate(
                    url = url,
                    referer = referer,
                    origin = origin,
                    quality = quality,
                    label = label.orEmpty(),
                    server = serverName,
                    latencyMs = 0L,
                    isHlsMaster = false,
                    audioLabel = "",
                    isHls = looksLikeHlsUrl(url, label.orEmpty())
                )
            )
        }

        /*
         * First read actual HTML media elements and data attributes.
         * Some player/server endpoints return JSON instead of HTML, therefore
         * the raw response text is also scanned below.
         */
        document?.select(
            "video[src], video source[src], source[src], " +
                "[data-src], [data-video], [data-file], [data-url], " +
                "[data-source], [data-stream], [data-manifest], " +
                "a[href*='.m3u8'], a[href*='.mp4'], a[href*='.mpd']"
        )?.forEach { element ->
            val label = firstNonBlank(
                element.attr("label"),
                element.attr("data-quality"),
                element.attr("data-resolution"),
                element.attr("data-quality-label"),
                element.text().takeIf { it.length <= 40 }
            )

            add(element.attr("src"), label)
            add(element.attr("href"), label)
            add(element.attr("data-src"), label)
            add(element.attr("data-video"), label)
            add(element.attr("data-file"), label)
            add(element.attr("data-url"), label)
            add(element.attr("data-source"), label)
            add(element.attr("data-stream"), label)
            add(element.attr("data-manifest"), label)
        }

        /*
         * Zoryva's browser uses /api/proxy?... for playback. If a current proxy
         * URL is already present in a rendered document, keep it as-is instead
         * of inventing a different source.
         */
        val normalizedHtml = buildString {
            if (document != null) append(normalizeEmbeddedText(document.html())).append('\n')
            append(normalizeEmbeddedText(rawText)).append('\n')
            if (document != null) {
                append(extractNextFlightPayload(document)).append('\n')
            }
        }

        val proxyRegex = Regex(
            """https?://[^\"'<>\s]+/api/proxy\?[^\"'<>\s]+""",
            RegexOption.IGNORE_CASE
        )

        proxyRegex.findAll(normalizedHtml).forEach { match ->
            val proxy = cleanUrl(match.value)
            val upstream = extractProxyParameter(proxy, "url")
            val ref = extractProxyParameter(proxy, "referer")
            val org = extractProxyParameter(proxy, "origin")

            if (isPlayableMedia(upstream ?: "")) {
                add(
                    upstream,
                    "Zoryva upstream",
                    ref ?: pageUrl,
                    org ?: originOf(ref ?: pageUrl)
                )
            }
        }

        /*
         * Absolute media URLs may be stored inside Next.js RSC/JSON payloads.
         */
        val absoluteMediaRegex = Regex(
            """https?://[^\"'<>\s]+(?:\.m3u8|\.mp4|\.mkv|\.webm|\.m4v|\.mov)(?:\?[^\"'<>\s]*)?""",
            RegexOption.IGNORE_CASE
        )

        val extensionlessPeakstormHlsRegex = Regex(
            """https?://[^\"'<>\s]+/r6(?:/s)?/[^\"'<>\s]+""",
            RegexOption.IGNORE_CASE
        )

                absoluteMediaRegex.findAll(normalizedHtml).forEach { match ->
            add(match.value)
        }

        extensionlessPeakstormHlsRegex.findAll(normalizedHtml).forEach { match ->
            add(match.value)
        }

        /*
         * Relative media paths are useful when a player page exposes a
         * same-origin playlist.
         */
        /* Also capture referer-bound MPEG-TS pages whose suffix is .html. */
        Regex(
            """https?://[^\"'<>\\s]+/file(?:1|2)/[^\"'<>\\s]+?/\\d{3,4}p/page-\\d+\\.html(?:\\?[^\"'<>\\s]*)?""",
            RegexOption.IGNORE_CASE
        ).findAll(normalizedHtml).forEach { match ->
            add(match.value)
        }

        val relativeMediaRegex = Regex(
            """(?i)(/[^\"'<>\s]+(?:\.m3u8|\.mp4|\.mkv|\.webm|\.m4v|\.mov)(?:\?[^\"'<>\s]*)?)"""
        )

        relativeMediaRegex.findAll(normalizedHtml).forEach { match ->
            add(match.groupValues[1])
        }

        return result.values.toList()
    }

    private suspend fun inspectHls(
        candidate: MediaCandidate
    ): HlsInfo? {
        val response = withTimeoutOrNull(SOURCE_PROBE_TIMEOUT_MS) {
            runCatching {
                app.get(
                    candidate.url,
                    headers = playbackHeadersFor(candidate)
                )
            }.getOrNull()
        } ?: return null

        if (response.code !in 200..399) return null

        val body = response.text
        if (!body.contains("#EXTM3U", true)) return null

        val isMaster = body.contains("#EXT-X-STREAM-INF", true)
        val hasAudio = body.contains("TYPE=AUDIO", true)
        val hasSubs = body.contains("TYPE=SUBTITLES", true)

        val audioLabels = Regex(
            """(?i)#EXT-X-MEDIA:[^\r\n]*TYPE=AUDIO[^\r\n]*"""
        ).findAll(body).mapNotNull { match ->
            val line = match.value
            firstNonBlank(
                regexAttribute(line, "LANGUAGE"),
                regexAttribute(line, "NAME")
            )
        }.toList().distinct()

        val audioLabel = when {
            audioLabels.size >= 2 -> "Dual Audio (${audioLabels.joinToString(", ")})"
            audioLabels.size == 1 -> "Audio (${audioLabels.first()})"
            hasAudio -> "Audio"
            else -> ""
        }

        val hlsAudioTracks = Regex(
            """(?i)#EXT-X-MEDIA:[^\r\n]*TYPE=AUDIO[^\r\n]*"""
        ).findAll(body).mapNotNull { match ->
            val line = match.value
            val rawUri = regexAttribute(line, "URI") ?: return@mapNotNull null
            val absolute = absoluteUrlLocal(rawUri, candidate.url) ?: return@mapNotNull null
            AudioTrackCandidate(
                url = absolute,
                label = firstNonBlank(
                    regexAttribute(line, "NAME"),
                    regexAttribute(line, "LANGUAGE")
                ).orEmpty(),
                headers = candidate.headers
            )
        }.distinctBy { cleanUrl(it.url) }.toList()

        val subtitles = parseHlsSubtitleTracks(
            body = body,
            baseUrl = candidate.url
        )

        val variants = ArrayList<Pair<String, Int>>()
        val lines = body.lines()

        for (i in lines.indices) {
            val line = lines[i].trim()
            if (!line.startsWith("#EXT-X-STREAM-INF", true)) continue

            val resolution = Regex(
                """(?i)RESOLUTION\s*=\s*(\d+)\s*x\s*(\d+)"""
            ).find(line)

            val quality = if (resolution != null) {
                resolution.groupValues[2].toIntOrNull()?.let(::qualityFromHeight)
                    ?: qualityFromUrl(line, line)
            } else {
                qualityFromUrl(line, line)
            }

            var j = i + 1
            while (j < lines.size && lines[j].trim().isBlank()) j++

            if (j < lines.size && !lines[j].trim().startsWith("#")) {
                val absolute = absoluteUrlLocal(lines[j].trim(), candidate.url)
                if (!absolute.isNullOrBlank()) {
                    variants += absolute to quality
                }
            }
        }

        return HlsInfo(
            isMaster = isMaster,
            hasAudioTracks = hasAudio,
            hasSubtitleTracks = hasSubs,
            maxQuality = variants.maxOfOrNull { it.second } ?: qualityFromUrl(candidate.url, candidate.label),
            audioLabel = audioLabel,
            audioTracks = hlsAudioTracks,
            subtitles = subtitles,
            variants = variants
        )
    }

    private suspend fun probeSource(
        candidate: MediaCandidate,
        hlsInfo: HlsInfo?
    ): Boolean {
        val clean = cleanUrl(candidate.url)
        if (clean.isBlank()) return false

        val headers = playbackHeadersFor(candidate).toMutableMap()

        return try {
            if (candidate.isHls || looksLikeHlsUrl(clean)) {
                val response = withTimeoutOrNull(PLAYABILITY_GET_TIMEOUT_MS) {
                    app.get(clean, headers = headers)
                } ?: return false

                if (response.code !in 200..399) return false

                val manifest = response.text
                if (!manifest.contains("#EXTM3U", true)) return false

                /*
                 * A manifest can return HTTP 200 while still being unusable by
                 * CloudStream/Media3. In particular, some HLS providers expose
                 * pseudo-segments ending in .html. CloudStream has a documented
                 * 2004 failure mode for this pattern even when browser/VLC
                 * playback succeeds. Validate the first actual child playlist /
                 * segment instead of trusting the manifest status alone.
                 */
                isCloudStreamCompatibleHls(
                    manifestUrl = clean,
                    manifestBody = manifest,
                    headers = headers
                )
            } else {
                val head = withTimeoutOrNull(PLAYABILITY_HEAD_TIMEOUT_MS) {
                    runCatching {
                        app.head(
                            clean,
                            headers = headers + ("Range" to "bytes=0-0")
                        )
                    }.getOrNull()
                }

                if (head != null && head.code in 200..399) {
                    val contentType = head.headers["Content-Type"].orEmpty()
                    val acceptRanges = head.headers["Accept-Ranges"].orEmpty()
                    val contentRange = head.headers["Content-Range"].orEmpty()

                    val looksVideoType =
                        contentType.startsWith("video/", true) ||
                            contentType.startsWith("audio/", true) ||
                            contentType.contains("mpegurl", true) ||
                            contentType.contains("octet-stream", true)

                    val hasRangeSupport =
                        head.code == 206 ||
                            acceptRanges.contains("bytes", true) ||
                            contentRange.startsWith("bytes ", true)

                    val disguisedStaticReverie = clean.contains("staticreverie.site/", true) &&
                        Regex("""/file(?:1|2)/[^?#\s]*?/\d{3,4}p/page-\d+\.html""", RegexOption.IGNORE_CASE)
                            .containsMatchIn(clean)

                    val signedPeakStorm = clean.contains("peakstorm.top/", true) &&
                        Regex("""/r6/s/[^/?#\s]+""", RegexOption.IGNORE_CASE)
                            .containsMatchIn(clean)

                    if (looksVideoType || hasRangeSupport || disguisedStaticReverie || signedPeakStorm || MEDIA_EXTENSIONS.any { clean.contains(it, true) }) {
                        return true
                    }
                }

                val response = withTimeoutOrNull(PLAYABILITY_GET_TIMEOUT_MS) {
                    app.get(
                        clean,
                        headers = headers + ("Range" to "bytes=0-1")
                    )
                } ?: return false

                if (response.code !in 200..399) return false

                val contentType = response.headers["Content-Type"].orEmpty()
                val acceptRanges = response.headers["Accept-Ranges"].orEmpty()
                val contentRange = response.headers["Content-Range"].orEmpty()

                val looksVideoType =
                    contentType.startsWith("video/", true) ||
                        contentType.startsWith("audio/", true) ||
                        contentType.contains("mpegurl", true) ||
                        contentType.contains("octet-stream", true)

                val hasRangeSupport =
                    response.code == 206 ||
                        acceptRanges.contains("bytes", true) ||
                        contentRange.startsWith("bytes ", true)

                val signedPeakStorm = clean.contains("peakstorm.top/", true) &&
                    Regex("""/r6/s/[^/?#\s]+""", RegexOption.IGNORE_CASE)
                        .containsMatchIn(clean)

                val disguisedStaticReverie = clean.contains("staticreverie.site/", true) &&
                    Regex("""/file(?:1|2)/[^?#\s]*?/\d{3,4}p/page-\d+\.html""", RegexOption.IGNORE_CASE)
                        .containsMatchIn(clean)

                if (looksVideoType || hasRangeSupport || signedPeakStorm || disguisedStaticReverie) {
                    return true
                }

                val prefix = response.text
                    .trimStart()
                    .take(120)
                    .lowercase(Locale.ROOT)

                if (
                    prefix.startsWith("<!doctype html") ||
                    prefix.startsWith("<html") ||
                    prefix.startsWith("<head") ||
                    prefix.startsWith("{\"error") ||
                    prefix.startsWith("{\"message")
                ) {
                    return false
                }

                false
            }
        } catch (_: Throwable) {
            false
        }
    }

    private suspend fun isCloudStreamCompatibleHls(
        manifestUrl: String,
        manifestBody: String,
        headers: Map<String, String>,
        depth: Int = 0
    ): Boolean {
        if (depth > 2) return true

        val manifestBaseUrl =
            if (manifestUrl.startsWith("$BASE_URL/api/proxy?", true)) {
                manifestUrl
            } else {
                extractProxyParameter(
                    manifestUrl,
                    "url"
                )?.takeIf { it.startsWith("http", true) } ?: manifestUrl
            }

        val lines = manifestBody
            .replace("\r", "")
            .lines()
            .map { it.trim() }

        if (lines.none { it.isNotBlank() && !it.startsWith("#") }) {
            return false
        }

        /*
         * Master playlist:
         * fetch one real child playlist and inspect that playlist's first
         * media segment. This catches 200-OK master playlists whose child
         * segments are rejected by Media3.
         */
        val variantUrl = lines
            .asSequence()
            .filter { it.isNotBlank() && !it.startsWith("#") }
            .mapNotNull { absoluteUrlLocal(it, manifestBaseUrl) }
            .firstOrNull { it.startsWith("http", true) }

        val isMaster = manifestBody.contains("#EXT-X-STREAM-INF", true)

        if (isMaster) {
            variantUrl ?: return false

            val variant = withTimeoutOrNull(PLAYABILITY_GET_TIMEOUT_MS) {
                runCatching {
                    app.get(
                        variantUrl,
                        headers = headers
                    )
                }.getOrNull()
            } ?: return false

            if (variant.code !in 200..399) return false
            if (!variant.text.contains("#EXTM3U", true)) return false

            return isCloudStreamCompatibleHls(
                manifestUrl = variantUrl,
                manifestBody = variant.text,
                headers = headers,
                depth = depth + 1
            )
        }

        /*
         * Media playlist:
         * find the first media URI. CloudStream's known 2004 case uses
         * pseudo-segment URLs ending in .html; reject those before they reach
         * ExoPlayer because VLC/browser can accept them while Media3 cannot.
         */
        val segmentUrl = lines
            .asSequence()
            .filter { it.isNotBlank() && !it.startsWith("#") }
            .mapNotNull { absoluteUrlLocal(it, manifestBaseUrl) }
            .firstOrNull { it.startsWith("http", true) }

        segmentUrl ?: return false

        val segmentPath = runCatching {
            URI(segmentUrl).path.orEmpty().lowercase(Locale.ROOT)
        }.getOrDefault("")

        val pseudoHtmlSegment =
            segmentPath.endsWith(".html") ||
                segmentPath.endsWith(".htm") ||
                Regex(
                    """/page-\d+\.html$""",
                    RegexOption.IGNORE_CASE
                ).containsMatchIn(segmentPath)

        if (pseudoHtmlSegment) return false

        /*
         * Do a small HEAD check for the first normal media segment. A 4xx/5xx
         * here means the manifest itself is not enough to make the link playable.
         * HEAD is used intentionally so we do not download an actual segment.
         *
         * A server that does not implement HEAD is not rejected solely for that
         * reason; the filename/manifest checks above remain authoritative.
         */
        val segmentHead = withTimeoutOrNull(HLS_SEGMENT_PROBE_TIMEOUT_MS) {
            runCatching {
                app.head(
                    segmentUrl,
                    headers = headers + ("Range" to "bytes=0-0")
                )
            }.getOrNull()
        }

        if (segmentHead != null) {
            if (segmentHead.code in 400..599) return false

            val contentType = segmentHead.headers["Content-Type"].orEmpty()
            if (contentType.contains("text/html", true)) return false

            val bodyLength = segmentHead.headers["Content-Length"]
                ?.toLongOrNull()

            if (bodyLength == 0L) return false
        }

        /*
         * HEAD is only a compatibility hint. Some CDNs disable HEAD even though
         * GET is valid, so a missing HEAD response must not erase an API-approved
         * HLS source.
         */
        return true
    }

    private fun isSafePlaybackHeader(
        name: String
    ): Boolean = when (name.lowercase(Locale.ROOT)) {
        "user-agent",
        "accept",
        "accept-language",
        "origin",
        "referer",
        "cache-control",
        "pragma",
        "sec-fetch-dest",
        "sec-fetch-mode",
        "sec-fetch-site",
        "x-requested-with" -> true
        else -> false
    }

    private fun sanitizePlaybackHeaders(
        headers: Map<String, String>
    ): Map<String, String> {
        val result = linkedMapOf<String, String>()
        headers.forEach { (key, value) ->
            val safeKey = key.trim()
            val safeValue = value.replace("\r", "").replace("\n", "").trim()
            if (safeKey.isBlank() || safeValue.isBlank()) return@forEach
            if (!isSafePlaybackHeader(safeKey)) return@forEach
            putHeaderCaseInsensitive(
                result,
                safeKey,
                safeValue
            )
        }
        return result
    }

    private fun putHeaderCaseInsensitive(
        headers: MutableMap<String, String>,
        name: String,
        value: String
    ) {
        val existing = headers.keys.firstOrNull { it.equals(name, true) }
        if (existing != null && existing != name) {
            headers.remove(existing)
        }
        headers[name] = value
    }

    private fun mergePlaybackHeaders(
        base: Map<String, String>,
        extra: Map<String, String>
    ): Map<String, String> {
        val result = linkedMapOf<String, String>()
        sanitizePlaybackHeaders(base).forEach { (key, value) ->
            putHeaderCaseInsensitive(result, key, value)
        }
        sanitizePlaybackHeaders(extra).forEach { (key, value) ->
            putHeaderCaseInsensitive(result, key, value)
        }
        return result
    }

    private fun headerValue(
        headers: Map<String, String>,
        name: String
    ): String? = headers.entries
        .firstOrNull { it.key.equals(name, ignoreCase = true) }
        ?.value

    private fun base64DecodeAny(
        input: String
    ): ByteArray? {
        val clean = input
            .trim()
            .replace("-", "+")
            .replace("_", "/")
            .filterNot { it.isWhitespace() }

        if (clean.isBlank()) return null

        val padded = clean + "=".repeat((4 - clean.length % 4) % 4)
        val alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"
        val output = ByteArray(padded.length / 4 * 3)
        var outIndex = 0
        var i = 0

        fun value(c: Char): Int {
            return alphabet.indexOf(c)
        }

        while (i < padded.length) {
            val c0 = padded[i]
            val c1 = padded.getOrNull(i + 1) ?: return null
            val c2 = padded.getOrNull(i + 2) ?: '='
            val c3 = padded.getOrNull(i + 3) ?: '='

            val v0 = value(c0)
            val v1 = value(c1)
            val v2 = if (c2 == '=') 0 else value(c2)
            val v3 = if (c3 == '=') 0 else value(c3)

            if (v0 < 0 || v1 < 0 || (c2 != '=' && v2 < 0) || (c3 != '=' && v3 < 0)) {
                return null
            }

            output[outIndex++] = ((v0 shl 2) or (v1 ushr 4)).toByte()
            if (c2 != '=') {
                output[outIndex++] = (((v1 and 0x0F) shl 4) or (v2 ushr 2)).toByte()
            }
            if (c3 != '=') {
                output[outIndex++] = (((v2 and 0x03) shl 6) or v3).toByte()
            }

            i += 4
        }

        return output.copyOf(outIndex)
    }

    /**
     * Small dependency-free URL-safe Base64 encoder.
     *
     * This deliberately avoids android.util.Base64 because the CloudStream
     * build also validates Zoryva's cross-platform JAR.
     */
    private fun base64UrlNoPadding(input: ByteArray): String {
        val alphabet =
            "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_"
        val output = StringBuilder(((input.size + 2) / 3) * 4)
        var index = 0

        while (index + 2 < input.size) {
            val b0 = input[index].toInt() and 0xFF
            val b1 = input[index + 1].toInt() and 0xFF
            val b2 = input[index + 2].toInt() and 0xFF

            output.append(alphabet[b0 ushr 2])
            output.append(alphabet[((b0 and 0x03) shl 4) or (b1 ushr 4)])
            output.append(alphabet[((b1 and 0x0F) shl 2) or (b2 ushr 6)])
            output.append(alphabet[b2 and 0x3F])

            index += 3
        }

        val remaining = input.size - index
        if (remaining == 1) {
            val b0 = input[index].toInt() and 0xFF
            output.append(alphabet[b0 ushr 2])
            output.append(alphabet[(b0 and 0x03) shl 4])
        } else if (remaining == 2) {
            val b0 = input[index].toInt() and 0xFF
            val b1 = input[index + 1].toInt() and 0xFF
            output.append(alphabet[b0 ushr 2])
            output.append(alphabet[((b0 and 0x03) shl 4) or (b1 ushr 4)])
            output.append(alphabet[(b1 and 0x0F) shl 2])
        }

        return output.toString()
    }

    private fun buildZoryvaProxyCandidate(
        source: MediaCandidate,
        pageUrl: String
    ): MediaCandidate? {
        if (!requiresZoryvaProxy(source.url)) return null

        val proxyUrl = buildZoryvaProxyUrl(
            sourceUrl = source.url,
            referer = source.referer.ifBlank { mediaRefererFor(source.url, pageUrl) },
            origin = source.origin.ifBlank {
                originOf(
                    source.referer.ifBlank { mediaRefererFor(source.url, pageUrl) }
                )
            },
            sourceHeaders = source.headers
        )

        return source.copy(
            url = proxyUrl,
            referer = pageUrl,
            origin = originOf(pageUrl),
            isHls = source.isHls ||
                source.url.contains("workers.dev/", true) ||
                source.url.contains("peakstorm.top/", true) ||
                source.url.contains("staticreverie.site/", true),
            headers = emptyMap()
        )
    }

    private fun buildZoryvaProxyUrl(
        sourceUrl: String,
        referer: String,
        origin: String,
        sourceHeaders: Map<String, String> = emptyMap()
    ): String {
        /* Never wrap an already-valid Zoryva proxy URL a second time. */
        if (sourceUrl.startsWith("$BASE_URL/api/proxy?", true)) {
            return sourceUrl
        }

        val safeReferer = referer.ifBlank { BASE_URL + "/" }
        val safeOrigin = origin.ifBlank { originOf(safeReferer) }

        val proxyHeaders = linkedMapOf<String, String>(
            "Referer" to safeReferer,
            "Origin" to safeOrigin,
            "User-Agent" to USER_AGENT,
            "Accept" to ACCEPT,
            "Accept-Language" to "en-US,en;q=0.9,bn;q=0.8"
        )

        sanitizePlaybackHeaders(sourceHeaders).forEach { (key, value) ->
            putHeaderCaseInsensitive(proxyHeaders, key, value)
        }

        putHeaderCaseInsensitive(proxyHeaders, "Referer", safeReferer)
        putHeaderCaseInsensitive(proxyHeaders, "Origin", safeOrigin)

        val headersJson = JSONObject()
        proxyHeaders.forEach { (key, value) ->
            headersJson.put(key, value)
        }

        val safeUserAgent = headerValue(proxyHeaders, "User-Agent")
            .takeUnless { it.isNullOrBlank() }
            ?: USER_AGENT

        val h = base64UrlNoPadding(
            headersJson.toString().toByteArray(Charsets.UTF_8)
        )

        return buildString {
            append(BASE_URL)
            append("/api/proxy?url=")
            append(encode(sourceUrl))
            append("&referer=")
            append(encode(safeReferer))
            append("&origin=")
            append(encode(safeOrigin))
            append("&ua=")
            append(encode(safeUserAgent))
            append("&h=")
            append(h)
        }
    }

    private fun buildSourceName(
        source: MediaCandidate
    ): String {
        val quality = when {
            source.isHlsMaster -> "Auto"
            source.quality > 0 && source.quality != Qualities.Unknown.value ->
                if (source.quality == Qualities.P2160.value) "4K" else "${source.quality}p"
            source.label.contains("4K", true) || source.label.contains("2160", true) -> "4K"
            source.label.contains("HD", true) -> "HD"
            else -> "Unknown"
        }

        val format = when {
            source.isHls || looksLikeHlsUrl(source.url) -> "HLS"
            source.url.substringBefore('?').endsWith(".mp4", true) -> "MP4"
            source.url.substringBefore('?').endsWith(".mkv", true) -> "MKV"
            source.url.substringBefore('?').endsWith(".webm", true) -> "WebM"
            else -> ""
        }

        val cleanedServer = source.server
            .trim()
            .replace(Regex("""(?i)^source\s*\d+\s*[•·:|-]?\s*"""), "")
            .replace(Regex("""(?i)\b(?:auto|adaptive|direct|proxy|4320p|2160p|1440p|1080p|720p|576p|540p|480p|400p|360p|240p|144p|hd)\b"""), " ")
            .replace(Regex("""\s*[•·|:/-]\s*"""), " ")
            .replace(Regex("""\s+"""), " ")
            .trim()

        val server = cleanedServer
            .takeUnless {
                it.isBlank() ||
                    it.equals("zoryva", true) ||
                    it.equals("zoryva extract", true) ||
                    it.equals("zoryva source", true) ||
                    it.equals("zoryva server", true) ||
                    it.equals("zoryva direct", true) ||
                    it.equals("auto", true)
            }

        val audio = source.audioLabel
            .trim()
            .takeIf { it.isNotBlank() }

        return buildString {
            append("Zoryva • ")
            append(quality)
            if (format.isNotBlank()) {
                append(" • ")
                append(format)
            }
            if (audio != null) {
                append(" • ")
                append(audio)
            } else if (server != null) {
                append(" • ")
                append(server)
            }
        }
    }

    private fun cleanSourceDisplayName(
        source: MediaCandidate,
        labelSuffix: String
    ): String {
        val base = buildSourceName(source)
        return when {
            labelSuffix.contains("proxy", true) &&
                !base.contains("Proxy", true) -> "$base • Proxy"
            labelSuffix.contains("fallback", true) &&
                !base.contains("Fallback", true) -> "$base • Fallback"
            else -> base
        }
    }

    // ---------------------------------------------------------------------
    // SERVER / PLAYER DISCOVERY
    // ---------------------------------------------------------------------

    private fun discoverServerPages(
        document: Document?,
        rawText: String,
        pageUrl: String,
        episodeId: String? = null
    ): List<Pair<String, String>> {
        val result = linkedMapOf<String, String>()

        fun add(url: String?, label: String?) {
            val clean = normalizeExtractedUrl(url, pageUrl) ?: return
            if (isIgnoredHost(clean)) return
            if (isObviouslyPromotional(clean)) return
            if (isPlayableMedia(clean)) return

            val text = listOfNotNull(label)
                .joinToString(" ")
                .lowercase(Locale.ROOT)

            val host = hostOf(clean).lowercase(Locale.ROOT)
            val path = runCatching { URI(clean).path.orEmpty().lowercase(Locale.ROOT) }
                .getOrDefault("")

            val likelyPlayer =
                PLAYER_WORDS.any { text.contains(it) } ||
                    PLAYER_WORDS.any { path.contains(it) } ||
                    host.contains("vidrock") ||
                    host.contains("player") ||
                    host.contains("stream")

            if (!likelyPlayer) return

            result.putIfAbsent(
                clean,
                label?.trim().takeUnless { it.isNullOrBlank() } ?: "Zoryva Source"
            )
        }

        document?.select(
            "iframe[src], embed[src], " +
                "button[data-server], button[data-source], " +
                "[data-server-url], [data-source-url], [data-player-url], " +
                "[data-iframe], [data-embed-url], " +
                "[data-server], [data-source]"
        )?.forEach { element ->
            add(
                firstNonBlank(
                    element.absUrl("src"),
                    element.attr("src"),
                    element.absUrl("data-server-url"),
                    element.attr("data-server-url"),
                    element.absUrl("data-source-url"),
                    element.attr("data-source-url"),
                    element.absUrl("data-player-url"),
                    element.attr("data-player-url"),
                    element.absUrl("data-iframe"),
                    element.attr("data-iframe"),
                    element.absUrl("data-embed-url"),
                    element.attr("data-embed-url"),
                    element.attr("data-server"),
                    element.attr("data-source"),
                    element.attr("data-file"),
                    element.attr("data-stream"),
                    element.attr("data-manifest")
                ),
                firstNonBlank(element.attr("title"), element.text())
            )
        }

        document?.select("a[href]")?.forEach { anchor ->
            add(
                anchor.absUrl("href"),
                anchor.text().trim()
            )
        }

        val normalizedHtml = buildString {
            if (document != null) append(normalizeEmbeddedText(document.html())).append('\n')
            append(normalizeEmbeddedText(rawText)).append('\n')
        }
        Regex(
            """https?://[^\"'<>\s]+""",
            RegexOption.IGNORE_CASE
        ).findAll(normalizedHtml).forEach { match ->
            val url = match.value
            val commonMediaHost =
                url.contains("vidrock", true) ||
                    url.contains("staticreverie", true) ||
                    url.contains("file1", true) ||
                    url.contains("file2", true) ||
                    url.contains("player", true) ||
                    url.contains("embed", true) ||
                    url.contains("stream", true) ||
                    url.contains("watch", true)

            if (commonMediaHost) {
                add(url, "Zoryva Source")
            }
        }

        /*
         * Some server buttons expose only an episode/source identifier in the
         * page JSON. Keep the identifier in discovery metadata and prefer URLs
         * that visibly mention it. We do not manufacture a media URL from the
         * identifier; it only improves selection among URLs the site exposed.
         */
        if (!episodeId.isNullOrBlank()) {
            normalizedHtml
                .split('\n')
                .filter { it.contains(episodeId, true) }
                .take(32)
                .forEach { line ->
                    Regex("""https?://[^\"'<>\s]+""", RegexOption.IGNORE_CASE)
                        .findAll(line)
                        .forEach { match ->
                            add(match.value, "Zoryva Episode Source")
                        }
                }
        }

        return result.entries
            .take(MAX_SERVER_PAGES)
            .map { it.key to it.value }
    }

    private fun discoverNestedPlayerPages(
        document: Document?,
        rawText: String,
        pageUrl: String
    ): List<Pair<String, String>> {
        val result = linkedMapOf<String, String>()

        document?.select("iframe[src], embed[src], a[href]")?.forEach { element ->
            val url = when {
                element.hasAttr("src") -> element.absUrl("src")
                else -> element.absUrl("href")
            }.trim()

            if (url.isBlank()) return@forEach
            if (isIgnoredHost(url)) return@forEach
            if (isPlayableMedia(url)) return@forEach

            val text = firstNonBlank(
                element.attr("title"),
                element.text()
            ).orEmpty().lowercase(Locale.ROOT)

            val lowerUrl = url.lowercase(Locale.ROOT)
            if (
                PLAYER_WORDS.any { lowerUrl.contains(it) } ||
                PLAYER_WORDS.any { text.contains(it) }
            ) {
                result.putIfAbsent(
                    cleanUrl(url),
                    if (text.isBlank()) "Nested Source" else text
                )
            }
        }

        Regex("""https?://[^\"'<>\s]+""", RegexOption.IGNORE_CASE)
            .findAll(normalizeEmbeddedText(rawText))
            .forEach { match ->
                val url = match.value
                val lower = url.lowercase(Locale.ROOT)
                if (
                    (lower.contains("player") || lower.contains("embed") ||
                        lower.contains("stream") || lower.contains("watch") ||
                        lower.contains("vidrock") || lower.contains("staticreverie") ||
                        lower.contains("file1") || lower.contains("file2")) &&
                        !isPlayableMedia(url) && !isIgnoredHost(url)
                ) {
                    result.putIfAbsent(cleanUrl(url), "Nested Source")
                }
            }

        return result.entries
            .take(MAX_SERVER_PAGES)
            .map { it.key to it.value }
    }

    // ---------------------------------------------------------------------
    // HLS SUBTITLES
    // ---------------------------------------------------------------------

    private fun parseHlsSubtitleTracks(
        body: String,
        baseUrl: String
    ): List<Pair<String, String>> {
        val result = linkedSetOf<Pair<String, String>>()

        Regex(
            """(?i)#EXT-X-MEDIA:[^\r\n]*TYPE=SUBTITLES[^\r\n]*"""
        ).findAll(body).forEach { match ->
            val line = match.value
            val uri = regexAttribute(line, "URI") ?: return@forEach
            val absolute = absoluteUrlLocal(uri, baseUrl) ?: return@forEach

            val language = firstNonBlank(
                regexAttribute(line, "LANGUAGE"),
                regexAttribute(line, "NAME")
            ) ?: "Subtitle"

            result += language to absolute
        }

        return result.toList()
    }

    private fun extractExternalSubtitles(
        page: Document,
        baseUrl: String
    ): List<Pair<String, String>> {
        val result = linkedSetOf<Pair<String, String>>()

        page.select(
            "a[href], track[src], [data-subtitle], [data-subtitles], " +
                "[data-caption], [data-caption-url]"
        ).forEach { element ->
            val raw = firstNonBlank(
                element.absUrl("href"),
                element.absUrl("src"),
                element.attr("data-subtitle"),
                element.attr("data-subtitles"),
                element.attr("data-caption"),
                element.attr("data-caption-url")
            ) ?: return@forEach

            val url = normalizeExtractedUrl(raw, baseUrl) ?: return@forEach
            if (!url.contains(".vtt", true) &&
                !url.contains(".srt", true) &&
                !url.contains(".ass", true) &&
                !url.contains(".ssa", true)
            ) {
                return@forEach
            }

            val language = firstNonBlank(
                element.attr("srclang"),
                element.attr("label"),
                element.attr("data-language"),
                element.text().takeIf { it.length <= 30 }
            ) ?: "Subtitle"

            result += language to url
        }

        return result.toList()
    }

    // ---------------------------------------------------------------------
    // HTML / RSC HELPERS
    // ---------------------------------------------------------------------

    private fun extractInitialMediaObject(
        document: Document
    ): JSONObject? {
        /*
         * The live Zoryva detail page transports initialMedia inside a quoted
         * Next.js Flight payload. Reuse the same real Flight-string decoder used
         * by Home instead of trying to unescape the whole HTML blindly.
         */
        val flight = extractNextFlightPayload(document)
        val candidates = listOf(
            flight,
            decodeRscForCatalog(document.html()),
            decodeRscOneLayer(document.html())
        )

        for (decoded in candidates) {
            val marker = "\"initialMedia\":"
            val markerIndex = decoded.indexOf(marker)
            if (markerIndex < 0) continue

            val start = decoded.indexOf('{', markerIndex + marker.length)
            if (start < 0) continue

            val json = extractBalancedJson(
                decoded,
                start,
                '{',
                '}'
            ) ?: continue

            val media = runCatching { JSONObject(json) }.getOrNull()
            if (media != null) return media
        }

        return null
    }

    private fun extractBalancedJson(
        text: String,
        start: Int,
        open: Char,
        close: Char
    ): String? {
        var depth = 0
        var inString = false
        var escaped = false

        for (i in start until text.length) {
            val c = text[i]

            if (inString) {
                if (escaped) {
                    escaped = false
                } else if (c == '\\') {
                    escaped = true
                } else if (c == '"') {
                    inString = false
                }
                continue
            }

            when (c) {
                '"' -> inString = true
                open -> depth++
                close -> {
                    depth--
                    if (depth == 0) {
                        return text.substring(start, i + 1)
                    }
                }
            }
        }

        return null
    }

    private fun decodeRscOneLayer(
        input: String
    ): String {
        val placeholder = '\u0000'

        return input
            .replace("\\\\", placeholder.toString())
            .replace("\\\"", "\"")
            .replace(placeholder.toString(), "\\")
            .replace("\\/", "/")
            .replace("\\u0026", "&")
            .replace("&amp;", "&")
    }

    private fun normalizeEmbeddedText(
        input: String
    ): String {
        return input
            .replace("\\/", "/")
            .replace("\\u0026", "&")
            .replace("&amp;", "&")
            .replace("\\\\\"", "\\\"")
    }

    private fun extractMeta(
        document: Document,
        selector: String
    ): String? {
        return document.selectFirst("meta[$selector]")?.attr("content")
            ?.trim()
            ?.takeIf { it.isNotBlank() }
    }

    private fun extractCardTitle(
        anchor: Element
    ): String {
        return firstNonBlank(
            anchor.attr("aria-label"),
            anchor.attr("title"),
            anchor.selectFirst("img")?.attr("alt"),
            anchor.selectFirst("img")?.attr("title"),
            anchor.selectFirst("[title]")?.attr("title"),
            anchor.text().trim()
        )?.let(::cleanCardTitle).orEmpty()
    }

    private fun extractCardPoster(
        anchor: Element,
        baseUrl: String
    ): String? {
        val img = anchor.selectFirst("img") ?: return null

        val raw = firstNonBlank(
            img.attr("src"),
            img.attr("data-src"),
            img.attr("data-lazy-src"),
            img.attr("srcset")?.substringBefore(',')?.substringBefore(' ')
        )

        return normalizeExtractedUrl(raw, baseUrl)
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

    // ---------------------------------------------------------------------
    // URL / TYPE / QUALITY HELPERS
    // ---------------------------------------------------------------------

    private data class FetchedPage(
        val document: Document?,
        val text: String,
        val code: Int
    )

    private suspend fun fetchPage(
        url: String,
        attempts: Int = 2,
        refererOverride: String? = null
    ): FetchedPage? {
        val clean = cleanUrl(url)
        if (clean.isBlank()) return null

        val maxAttempts = attempts.coerceIn(1, DETAIL_FETCH_ATTEMPTS + 1)
        var last: FetchedPage? = null

        repeat(maxAttempts) { attempt ->
            val result = runCatching {
                val pageHost = hostOf(clean).lowercase(Locale.ROOT)
                val contextualOrigin = originOf(clean)
                val pageReferer = refererOverride
                    ?.trim()
                    ?.takeIf { it.isNotBlank() }
                    ?: when {
                        pageHost == "vidrock.net" || pageHost.endsWith(".vidrock.net") ->
                            "$VIDROCK_ORIGIN/"
                        pageHost == hostOf(BASE_URL).lowercase(Locale.ROOT) ->
                            "$BASE_URL/"
                        else ->
                            contextualOrigin.ifBlank { BASE_URL } + "/"
                    }

                val response = app.get(
                    clean,
                    headers = PAGE_HEADERS + mapOf(
                        "Referer" to pageReferer,
                        "Origin" to contextualOrigin,
                        "Sec-Fetch-Dest" to "document",
                        "Sec-Fetch-Mode" to "navigate",
                        "Sec-Fetch-Site" to if (pageHost == "vidrock.net") "same-origin" else "cross-site",
                        "Upgrade-Insecure-Requests" to "1"
                    )
                )

                val document = runCatching { response.document }.getOrNull()?.apply {
                    if (baseUri().isBlank()) setBaseUri(clean)
                }

                FetchedPage(
                    document = document,
                    text = response.text,
                    code = response.code
                )
            }.getOrNull()

            last = result
            if (result != null && result.code in 200..399) {
                return result
            }

            if (attempt + 1 < maxAttempts) {
                delay(DETAIL_RETRY_DELAY_MS * (attempt + 1L))
            }
        }

        return last?.takeIf { it.code in 200..399 }
    }

    private suspend fun getDocument(
        url: String
    ): Document? {
        return fetchPage(
            url = url,
            attempts = 2
        )?.document
    }

    private fun normalizeExtractedUrl(
        raw: String?,
        baseUrl: String
    ): String? {
        if (raw.isNullOrBlank()) return null

        var value = raw
            .trim()
            .replace("\\/", "/")
            .replace("\\u0026", "&")
            .replace("&amp;", "&")
            .trim('"', '\'', '`', ',', ';', ')', ']', '}')

        if (value.isBlank()) return null

        if (value.startsWith("//")) {
            value = "https:$value"
        }

        if (value.startsWith("/")) {
            value = absoluteUrlLocal(value, baseUrl) ?: return null
        }

        if (!value.startsWith("http://", true) &&
            !value.startsWith("https://", true)
        ) {
            value = absoluteUrlLocal(value, baseUrl) ?: return null
        }

        return cleanUrl(value)
    }

    private fun cleanUrl(
        url: String
    ): String {
        return url.trim()
            .trim('"', '\'', '`', ',', ';', ')', ']', '}')
    }

    private fun absoluteUrl(
        url: String?
    ): String? {
        if (url.isNullOrBlank()) return null
        return cleanUrl(url)
    }

    private fun absoluteUrlLocal(
        value: String,
        base: String
    ): String? {
        return runCatching {
            URI(base).resolve(value).toString()
        }.getOrNull()
    }

    private fun originOf(
        url: String
    ): String {
        return runCatching {
            val uri = URI(url)
            val port = if (uri.port > 0) ":${uri.port}" else ""
            "${uri.scheme}://${uri.host}$port"
        }.getOrDefault(BASE_URL)
    }

    private fun hostOf(
        url: String
    ): String {
        return runCatching {
            URI(url).host.orEmpty()
        }.getOrDefault("")
    }

    private fun isMoviePath(
        path: String
    ): Boolean {
        return path.trim('/').split('/').firstOrNull()
            ?.equals("movie", true) == true
    }

    private fun isTvPath(
        path: String
    ): Boolean {
        return path.trim('/').split('/').firstOrNull()
            ?.equals("tv", true) == true
    }

    private fun isAnimePath(
        path: String
    ): Boolean {
        return path.trim('/').split('/').firstOrNull()
            ?.equals("anime", true) == true
    }

    private fun isEpisodePath(
        path: String
    ): Boolean {
        val parts = path.trim('/').split('/').filter { it.isNotBlank() }
        return parts.size >= 4 &&
            (parts[0].equals("tv", true) || parts[0].equals("anime", true)) &&
            parts[parts.size - 1].toIntOrNull() != null
    }

    private fun titleFromPath(
        path: String
    ): String {
        val parts = path.trim('/').split('/').filter { it.isNotBlank() }
        val slug = when {
            parts.size >= 3 -> parts[2]
            parts.isNotEmpty() -> parts.last()
            else -> ""
        }

        return slug
            .replace('-', ' ')
            .replace('_', ' ')
            .split(' ')
            .filter { it.isNotBlank() }
            .joinToString(" ") { token ->
                token.replaceFirstChar {
                    if (it.isLowerCase()) it.titlecase(Locale.ROOT) else it.toString()
                }
            }
    }


    private fun parseSitemapTimestamp(
        value: String?
    ): Long? {
        val cleaned = value?.trim()?.takeIf { it.isNotBlank() } ?: return null

        cleaned.toLongOrNull()?.let { raw ->
            return if (raw < 10_000_000_000L) raw * 1000L else raw
        }

        val patterns = listOf(
            "yyyy-MM-dd'T'HH:mm:ss.SSSXXX",
            "yyyy-MM-dd'T'HH:mm:ssXXX",
            "yyyy-MM-dd'T'HH:mm:ss.SSSX",
            "yyyy-MM-dd'T'HH:mm:ssX",
            "yyyy-MM-dd"
        )

        for (pattern in patterns) {
            val parsed = runCatching {
                java.text.SimpleDateFormat(pattern, Locale.US).apply {
                    isLenient = false
                }.parse(cleaned)?.time
            }.getOrNull()

            if (parsed != null) return parsed
        }

        return null
    }

    private fun normalizeEpisodeDate(
        value: String?
    ): String? {
        val cleaned = value
            ?.replace(Regex("""\s+"""), " ")
            ?.trim()
            ?: return null

        if (Regex("""^\d{4}-\d{2}-\d{2}$""").matches(cleaned)) {
            return cleaned
        }

        listOf(
            "MMM d, yyyy",
            "MMMM d, yyyy"
        ).forEach { pattern ->
            val parsed = runCatching {
                java.text.SimpleDateFormat(
                    pattern,
                    Locale.ENGLISH
                ).apply {
                    isLenient = false
                }.parse(cleaned)
            }.getOrNull()

            if (parsed != null) {
                return java.text.SimpleDateFormat(
                    "yyyy-MM-dd",
                    Locale.US
                ).format(parsed)
            }
        }

        return null
    }

    private fun parseEpisodeDateMillis(
        value: String
    ): Long? {
        return runCatching {
            java.text.SimpleDateFormat(
                "yyyy-MM-dd",
                Locale.US
            ).apply {
                isLenient = false
            }.parse(value)?.time
        }.getOrNull()
    }

    private fun cleanCardTitle(
        value: String
    ): String {
        return value
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    private fun cleanDetailTitle(
        value: String
    ): String {
        return value
            .replace(Regex("(?i)^watch\\s+"), "")
            .replace(Regex("(?i)\\s+(online|in hd|hd)$"), "")
            .replace("— Zoryva X", "")
            .replace("| Zoryva X", "")
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    private fun slugify(
        value: String
    ): String {
        return value
            .lowercase(Locale.ROOT)
            .replace(Regex("[^a-z0-9]+"), "-")
            .trim('-')
    }

    private fun normalizeSearch(
        value: String
    ): String {
        return value
            .lowercase(Locale.ROOT)
            .replace(Regex("[^\\p{L}\\p{N}]+"), " ")
            .trim()
            .replace(Regex("\\s+"), " ")
    }

    private fun similarity(
        a: String,
        b: String
    ): Double {
        if (a == b) return 1.0
        if (a.isBlank() || b.isBlank()) return 0.0

        val distance = levenshtein(a, b)
        val longest = maxOf(a.length, b.length)
        return if (longest == 0) {
            1.0
        } else {
            1.0 - distance.toDouble() / longest.toDouble()
        }
    }

    private fun levenshtein(
        a: String,
        b: String
    ): Int {
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

    private fun qualityFromUrl(
        url: String,
        label: String
    ): Int {
        val value = "$url $label".lowercase(Locale.ROOT)

        val explicitHeight = Regex(
            """(?<!\d)(4320|2160|1440|1080|720|576|540|480|400|360|240|144)\s*p(?!\d)"""
        ).find(value)?.groupValues?.getOrNull(1)?.toIntOrNull()

        if (explicitHeight != null) return qualityFromHeight(explicitHeight)

        val resolutionPair = Regex(
            """(?<!\d)\d{2,5}\s*[x×]\s*(\d{2,5})(?!\d)"""
        ).find(value)?.groupValues?.getOrNull(1)?.toIntOrNull()

        if (resolutionPair != null) return qualityFromHeight(resolutionPair)

        return when {
            Regex("(?:2160|4k)").containsMatchIn(value) -> Qualities.P2160.value
            Regex("(?:1440|2k)").containsMatchIn(value) -> Qualities.P1440.value
            Regex("1080").containsMatchIn(value) -> Qualities.P1080.value
            Regex("720").containsMatchIn(value) -> Qualities.P720.value
            Regex("576").containsMatchIn(value) -> 576
            Regex("540").containsMatchIn(value) -> 540
            Regex("480").containsMatchIn(value) -> Qualities.P480.value
            Regex("400").containsMatchIn(value) -> 400
            Regex("360").containsMatchIn(value) -> Qualities.P360.value
            Regex("240").containsMatchIn(value) -> 240
            Regex("144").containsMatchIn(value) -> 144
            else -> Qualities.Unknown.value
        }
    }

    private fun qualityFromHeight(
        height: Int
    ): Int {
        return when {
            height >= 4320 -> 4320
            height >= 2160 -> Qualities.P2160.value
            height >= 1440 -> Qualities.P1440.value
            height >= 1080 -> Qualities.P1080.value
            height >= 720 -> Qualities.P720.value
            height >= 576 -> 576
            height >= 540 -> 540
            height >= 480 -> Qualities.P480.value
            height >= 400 -> 400
            height >= 360 -> Qualities.P360.value
            height >= 240 -> 240
            height >= 144 -> 144
            else -> Qualities.Unknown.value
        }
    }

    private fun isPlayableMedia(
        url: String,
        mediaTypeHint: String = ""
    ): Boolean {
        val lower = url.lowercase(Locale.ROOT)
        val hint = mediaTypeHint.lowercase(Locale.ROOT)

        if (lower.contains("/api/proxy?")) {
            val upstream = extractProxyParameter(url, "url").orEmpty()
            return isPlayableMedia(upstream, mediaTypeHint)
        }

        if (
            hint.contains("m3u8") ||
            hint.contains("mpegurl") ||
            hint.contains("hls")
        ) return true

        if (
            hint == "mp4" ||
            hint == "video" ||
            hint.contains("mp4") ||
            hint.contains("video/mp4") ||
            hint.contains("video/") ||
            hint == "mkv" ||
            hint == "webm" ||
            hint.contains("dash") ||
            hint.contains("mpeg")
        ) return true

        if (MEDIA_EXTENSIONS.any { lower.contains(it) }) return true
        if (looksLikeHlsUrl(url, mediaTypeHint)) return true
        if (lower.contains(".ts?", true) || lower.contains(".ts#", true) || lower.endsWith(".ts", true)) return true
        if (lower.contains(".m4s?", true) || lower.contains(".m4s#", true) || lower.endsWith(".m4s", true)) return true

        val staticReverie =
            lower.contains("staticreverie.site/") &&
                Regex("""/file(?:1|2)/[^?#\s]*?/\d{3,4}p/page-\d+\.html""", RegexOption.IGNORE_CASE)
                    .containsMatchIn(lower)

        /* Current browser playback also uses short-lived extensionless signed
         * media URLs under *.peakstorm.top/r6/s/<token>. */
        val peakStormSigned =
            lower.contains("peakstorm.top/") &&
                Regex("""/r6/s/[^/?#\s]+""", RegexOption.IGNORE_CASE)
                    .containsMatchIn(lower)

        return staticReverie || peakStormSigned
    }

    private fun requiresZoryvaProxy(
        url: String
    ): Boolean {
        val lower = url.lowercase(Locale.ROOT)
        return lower.contains("staticreverie.site/") ||
            lower.contains("peakstorm.top/") ||
            lower.contains("workers.dev/")
    }

    private fun mediaRefererFor(
        url: String,
        currentReferer: String
    ): String {
        return when {
            url.contains("sun.peakstorm.top/", true) ->
                "https://speedracelight.com/"

            url.contains("moon.peakstorm.top/vd/", true) ->
                currentReferer.takeUnless { it.equals("$BASE_URL/", true) }
                    .orEmpty()

            url.contains("peakstorm.top/", true) ->
                "https://speedracelight.com/"

            url.contains("flamingo-e55.workers.dev/", true) ->
                currentReferer
                    .takeUnless { it.contains("zoryva.me", true) }
                    ?.takeIf { it.startsWith("http", true) }
                    ?: "https://vidrock.net/"

            else ->
                currentReferer.ifBlank { "$BASE_URL/" }
        }
    }

    private fun expandPeakstormHlsVariants(
        url: String
    ): List<String> {
        val clean = cleanUrl(url)
        if (!clean.contains("moon.peakstorm.top/vd/", true)) {
            return listOf(clean)
        }

        val marker = Regex(
            """(?i)(.*?/)(index-s)(360|480|720|1080|1440|2160)(p-v1-a1\.m3u8)(?:\?.*)?$"""
        ).find(clean) ?: return listOf(clean)

        val prefix = marker.groupValues[1]
        val middle = marker.groupValues[2]
        val suffix = marker.groupValues[4]
        val query = Regex("""\?.*$""").find(clean)?.value.orEmpty()

        return listOf(
            2160,
            1440,
            1080,
            720,
            480,
            360
        ).map { height ->
            "$prefix$middle${height}$suffix$query"
        }.distinct()
    }

    private fun looksLikeHlsUrl(
        url: String,
        mediaTypeHint: String = ""
    ): Boolean {
        val value = ("$url $mediaTypeHint").lowercase(Locale.ROOT)
        if (value.contains(".m3u8")) return true
        if (value.contains("m3u8")) return true
        if (value.contains("type=hls") || value.contains("type=\"hls\"")) return true
        if (value.contains("format=hls") || value.contains("format=\"hls\"")) return true
        if (value.contains("mime=m3u8") || value.contains("mime=\"m3u8\"")) return true

        /* Current Zoryva/browser playback also returns extensionless signed
         * HLS under peakstorm /r6 (and especially /r6/s/<token>). */
        if (value.contains("peakstorm.top/") && value.contains("/r6/")) return true
        return false
    }

    private fun mergeAudioTrackCandidates(
        first: List<AudioTrackCandidate>,
        second: List<AudioTrackCandidate>
    ): List<AudioTrackCandidate> {
        return (first + second)
            .filter { it.url.isNotBlank() }
            .distinctBy { cleanUrl(it.url) }
    }

    private fun buildDirectPlaybackOutput(
        source: MediaCandidate,
        pageUrl: String
    ): PlaybackOutput {
        val baseHeaders = playbackHeadersFor(source).toMutableMap()

        val fallbackReferer = when {
            source.referer.startsWith("http", true) -> source.referer
            source.url.contains("peakstorm.top/", true) -> mediaRefererFor(source.url, pageUrl)
            else -> pageUrl
        }

        if (headerValue(baseHeaders, "Referer").isNullOrBlank()) {
            putHeaderCaseInsensitive(
                baseHeaders,
                "Referer",
                fallbackReferer
            )
        }

        val sourceOrigin = firstNonBlank(
            headerValue(source.headers, "Origin"),
            source.origin.takeIf { it.startsWith("http", true) },
            if (source.url.contains("peakstorm.top/", true)) {
                originOf(
                    headerValue(baseHeaders, "Referer").orEmpty()
                )
            } else {
                null
            }
        )

        if (!sourceOrigin.isNullOrBlank()) {
            putHeaderCaseInsensitive(
                baseHeaders,
                "Origin",
                sourceOrigin
            )
        }

        val finalHeaders = sanitizePlaybackHeaders(baseHeaders)
        val resolvedReferer = headerValue(finalHeaders, "Referer")
            .orEmpty()
            .ifBlank { pageUrl }

        return PlaybackOutput(
            url = source.url,
            referer = resolvedReferer,
            headers = finalHeaders
        )
    }

    private fun buildPlaybackOutput(
        source: MediaCandidate,
        pageUrl: String
    ): PlaybackOutput = buildDirectPlaybackOutput(source, pageUrl)

    private fun isObviouslyPromotional(
        url: String
    ): Boolean {
        val lower = url.lowercase(Locale.ROOT)
        return TRAILER_WORDS.any { lower.contains(it) }
    }

    private fun isIgnoredHost(
        url: String
    ): Boolean {
        val host = hostOf(url).lowercase(Locale.ROOT)
        return IGNORED_HOST_PARTS.any { host == it || host.endsWith(".$it") }
    }

    private fun normalizeMediaIdentity(
        url: String
    ): String {
        val proxyUpstream = extractProxyParameter(url, "url")
        if (!proxyUpstream.isNullOrBlank()) {
            return cleanUrl(proxyUpstream)
        }

        return cleanUrl(url)
    }

    private fun extractProxyParameter(
        url: String,
        name: String
    ): String? {
        return runCatching {
            val uri = URI(url)
            val query = uri.rawQuery ?: return@runCatching null
            query.split('&').firstNotNullOfOrNull { part ->
                val pieces = part.split('=', limit = 2)
                if (pieces.size != 2) return@firstNotNullOfOrNull null
                if (!pieces[0].equals(name, true)) return@firstNotNullOfOrNull null
                URLDecoder.decode(pieces[1], "UTF-8")
            }
        }.getOrNull()
    }

    private fun regexAttribute(
        line: String,
        attribute: String
    ): String? {
        return Regex(
            """(?i)$attribute\s*=\s*(?:\"([^\"]+)\"|([^,\s]+))"""
        ).find(line)?.let {
            firstNonBlank(
                it.groupValues.getOrNull(1),
                it.groupValues.getOrNull(2)
            )
        }
    }

    private fun encode(
        value: String
    ): String = URLEncoder.encode(value, "UTF-8")

    private fun firstNonBlank(
        vararg values: String?
    ): String? {
        return values.firstOrNull { !it.isNullOrBlank() }
            ?.trim()
            ?.takeIf { it.isNotBlank() }
    }

    private fun MutableList<String>.truncateAfter(
        size: Int
    ) {
        while (this.size > size) removeAt(lastIndex)
    }
}
