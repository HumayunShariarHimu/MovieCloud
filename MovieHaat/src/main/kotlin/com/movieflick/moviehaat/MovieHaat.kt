package com.movieflick.moviehaat

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.lagradost.cloudstream3.Episode
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SearchResponseList
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.newEpisode
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newMovieLoadResponse
import com.lagradost.cloudstream3.newMovieSearchResponse
import com.lagradost.cloudstream3.newSearchResponseList
import com.lagradost.cloudstream3.newTvSeriesLoadResponse
import com.lagradost.cloudstream3.newTvSeriesSearchResponse
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.mainPageOf
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink
import java.net.URI
import java.net.URLEncoder
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

class MovieHaat : MainAPI() {

    override var mainUrl = "https://moviehaat.net"
    override var name = "Movie Haat"
    override var lang = "bn"

    override val hasMainPage = true
    override val hasQuickSearch = true

    override val supportedTypes = setOf(
        TvType.Movie,
        TvType.TvSeries
    )

    /*
     * HOME CATEGORIES
     *
     * Only these three rows are exposed to CloudStream:
     *
     * 1) Indian Movies
     * 2) Hollywood Movies
     * 3) Tv Show
     *
     * The website shortcuts supplied for these rows map to the backend
     * category/subcategory parameters below.
     */
    override val mainPage = mainPageOf(
        "mh://indian" to "Indian Movies",
        "mh://hollywood" to "Hollywood Movies",
        "mh://tv" to "Tv Show"
    )

    private companion object {
        const val API_BASE = "https://moviehaat.net:8989/api/"
        const val MEDIA_BASE = "https://moviehaat.net:8081/"

        const val MOVIE_PAGE_SIZE = 24
        const val TV_PAGE_SIZE = 24

        /*
         * This is the key requirement from the requested architecture:
         * every configured source contributes exactly two items to each
         * logical home batch. Batch 1 => first 2 from every source,
         * batch 2 => next 2 from every source, etc.
         */
        // CloudStream should initially expose six items per logical row.
        // Subsequent row pages are loaded lazily as the user keeps scrolling.
        const val ITEMS_PER_CATEGORY_PER_BATCH = 6

        const val MOVIE_LIST_API = "viewallmovies"
        const val MOVIE_SEARCH_API = "searchmovies"
        const val MOVIE_DETAIL_API = "getmoviebyid"

        const val TV_LIST_API = "recentlyaddedseriesall"
        const val TV_EPISODES_API = "seriesepisodes"
        const val TV_EPISODE_API = "seriesepisode"

        val MOVIE_SOURCES = listOf(
            Source("Indian", "Bollywood"),
            Source("Indian", "Indian Bangla"),
            Source("Indian", "Tamil"),
            Source("Indian", "Telugu")
        )

        val HOLLYWOOD_SOURCES = listOf(
            Source("English", "Hollywood")
        )

        val TV_SOURCES = listOf(
            Source("English TV Series", "null"),
            Source("Korean Drama", "null"),
            Source("Indian Series", "null")
        )
    }

    private data class Source(
        val category: String,
        val subcategory: String
    )

    private data class HomeItem(
        val id: String,
        val title: String,
        val posterUrl: String?,
        val videoPath: String?,
        val runtime: String?,
        val imdbRating: String?,
        val source: Source?,
        val isSeries: Boolean
    )

    private data class SourcePageKey(
        val source: Source,
        val page: Int
    )

    private val objectMapper = ObjectMapper()

    /*
     * Bounded page cache.
     *
     * We cache the website's API pages, then slice them into CloudStream
     * batches of two per source. This prevents the "take 2 from API page 1,
     * then jump to API page 2" problem and preserves continuous ordering.
     */
    private val moviePageCache =
        ConcurrentHashMap<SourcePageKey, List<HomeItem>>()

    private val tvPageCache =
        ConcurrentHashMap<SourcePageKey, List<HomeItem>>()

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        val pageNumber = page.coerceAtLeast(1)

        return when (request.data) {
            "mh://indian" -> {
                buildMovieHome(
                    request = request,
                    page = pageNumber,
                    sources = MOVIE_SOURCES
                )
            }

            "mh://hollywood" -> {
                buildMovieHome(
                    request = request,
                    page = pageNumber,
                    sources = HOLLYWOOD_SOURCES
                )
            }

            "mh://tv" -> {
                buildTvHome(
                    request = request,
                    page = pageNumber,
                    sources = TV_SOURCES
                )
            }

            else -> {
                newHomePageResponse(
                    request,
                    emptyList(),
                    false
                )
            }
        }
    }

    private suspend fun buildMovieHome(
        request: MainPageRequest,
        page: Int,
        sources: List<Source>
    ): HomePageResponse {
        return buildLazyHome(
            request = request,
            page = page,
            sources = sources,
            isSeries = false
        )
    }

    private suspend fun buildTvHome(
        request: MainPageRequest,
        page: Int,
        sources: List<Source>
    ): HomePageResponse {
        return buildLazyHome(
            request = request,
            page = page,
            sources = sources,
            isSeries = true
        )
    }

    /*
     * Build one logical CloudStream row page at a time.
     *
     * Page 1 => six items
     * Page 2 => next six items
     * Page 3 => next six items
     * ...
     *
     * Items are interleaved across all configured sources so one source does
     * not monopolize the row. CloudStream requests the next page when the user
     * reaches the end of the current horizontal row.
     */
    private suspend fun buildLazyHome(
        request: MainPageRequest,
        page: Int,
        sources: List<Source>,
        isSeries: Boolean
    ): HomePageResponse {
        val pageNumber = page.coerceAtLeast(1)
        val pageSize = ITEMS_PER_CATEGORY_PER_BATCH

        if (sources.isEmpty()) {
            return newHomePageResponse(
                request,
                emptyList(),
                false
            )
        }

        val targetEnd = pageNumber * pageSize

        /*
         * Because items are interleaved source-by-source, each source only
         * needs approximately targetEnd / sourceCount items. We fetch one
         * extra item from each source to make the next-page decision reliable
         * when source lengths are uneven.
         */
        val requiredPerSource =
            ((targetEnd + sources.size - 1) / sources.size) + 1

        val sourceItems = coroutineScope {
            sources.map { source ->
                async {
                    if (isSeries) {
                        getTvItemsUpTo(
                            source = source,
                            requiredCount = requiredPerSource
                        )
                    } else {
                        getMovieItemsUpTo(
                            source = source,
                            requiredCount = requiredPerSource
                        )
                    }
                }
            }.awaitAll()
        }

        val interleaved = interleaveSources(sourceItems)
        val pageStart = (pageNumber - 1) * pageSize
        val pageItems = interleaved
            .drop(pageStart)
            .take(pageSize)

        val hasNext = interleaved.size > pageNumber * pageSize

        return newHomePageResponse(
            request,
            pageItems.map(::toSearchResponse),
            hasNext
        )
    }

    /*
     * Round-robin merge:
     * source1 item1, source2 item1, source3 item1, ...
     * source1 item2, source2 item2, source3 item2, ...
     *
     * Exhausted sources are skipped, so short categories do not block the
     * remaining sources from continuing.
     */
    private fun interleaveSources(
        sourceItems: List<List<HomeItem>>
    ): List<HomeItem> {
        if (sourceItems.isEmpty()) return emptyList()

        val result = mutableListOf<HomeItem>()
        var index = 0

        while (true) {
            var added = false

            sourceItems.forEach { items ->
                if (index < items.size) {
                    result += items[index]
                    added = true
                }
            }

            if (!added) break
            index++
        }

        return result
            .distinctBy(::homeDedupKey)
    }

    private suspend fun getMovieItemsUpTo(
        source: Source,
        requiredCount: Int
    ): List<HomeItem> {
        if (requiredCount <= 0) return emptyList()

        val pagesNeeded =
            ((requiredCount - 1) / MOVIE_PAGE_SIZE) + 1

        for (page in 1..pagesNeeded) {
            val key = SourcePageKey(source, page)

            if (!moviePageCache.containsKey(key)) {
                moviePageCache[key] =
                    fetchMoviePage(source, page)
            }
        }

        return buildList {
            for (page in 1..pagesNeeded) {
                addAll(
                    moviePageCache[
                        SourcePageKey(source, page)
                    ].orEmpty()
                )

                if (size >= requiredCount) break
            }
        }.take(requiredCount)
    }

    private suspend fun getTvItemsUpTo(
        source: Source,
        requiredCount: Int
    ): List<HomeItem> {
        if (requiredCount <= 0) return emptyList()

        val pagesNeeded =
            ((requiredCount - 1) / TV_PAGE_SIZE) + 1

        for (page in 1..pagesNeeded) {
            val key = SourcePageKey(source, page)

            if (!tvPageCache.containsKey(key)) {
                tvPageCache[key] =
                    fetchTvPage(source, page)
            }
        }

        return buildList {
            for (page in 1..pagesNeeded) {
                addAll(
                    tvPageCache[
                        SourcePageKey(source, page)
                    ].orEmpty()
                )

                if (size >= requiredCount) break
            }
        }.take(requiredCount)
    }

    private suspend fun fetchMoviePage(
        source: Source,
        page: Int
    ): List<HomeItem> {
        val response = runCatching {
            app.post(
                "$API_BASE$MOVIE_LIST_API",
                data = mapOf(
                    "category" to source.category,
                    "subcategory" to source.subcategory,
                    "pageNumber" to page.toString()
                )
            )
        }.getOrNull() ?: return emptyList()

        return parseMovieItems(
            root = parseJson(response.text),
            source = source
        )
    }

    private suspend fun fetchTvPage(
        source: Source,
        page: Int
    ): List<HomeItem> {
        val response = runCatching {
            app.post(
                "$API_BASE$TV_LIST_API",
                data = mapOf(
                    "category" to source.category,
                    "subcategory" to source.subcategory,
                    "pageNumber" to page.toString()
                )
            )
        }.getOrNull() ?: return emptyList()

        return parseTvItems(
            root = parseJson(response.text),
            source = source
        )
    }

    override suspend fun search(
        query: String,
        page: Int
    ): SearchResponseList {
        val q = query.trim()

        if (q.isBlank()) {
            return newSearchResponseList(
                emptyList(),
                false
            )
        }

        val response = runCatching {
            app.post(
                "$API_BASE$MOVIE_SEARCH_API",
                data = mapOf(
                    "search" to q
                )
            )
        }.getOrNull() ?: return newSearchResponseList(
            emptyList(),
            false
        )

        val root = parseJson(response.text)

        val results = mutableListOf<HomeItem>()

        arrayFrom(
            root,
            "movies"
        ).forEach { node ->
            parseHomeItem(
                node = node,
                source = null,
                isSeries = false
            )?.let(results::add)
        }

        arrayFrom(
            root,
            "series"
        ).forEach { node ->
            parseHomeItem(
                node = node,
                source = null,
                isSeries = true
            )?.let(results::add)
        }

        val ranked = results
            .distinctBy {
                homeDedupKey(it)
            }
            .sortedWith(
                compareByDescending<HomeItem> {
                    searchScore(
                        query = q,
                        title = it.title
                    )
                }.thenBy {
                    it.title.lowercase(Locale.ROOT)
                }
            )

        val pageSize = 24
        val offset =
            (page - 1).coerceAtLeast(0) * pageSize

        val pageItems =
            ranked
                .drop(offset)
                .take(pageSize)

        return newSearchResponseList(
            pageItems.map(::toSearchResponse),
            offset + pageSize < ranked.size
        )
    }

    override suspend fun load(
        url: String
    ): LoadResponse {
        val value = url.trim()

        // Internal links are kept for the final playback hand-off.
        if (value.startsWith("movie:", true)) {
            val id = value.substringAfter(":", "").trim()
            return loadMovieById(id)
        }

        if (value.startsWith("tv:", true)) {
            val id = value.substringAfter(":", "").trim()
            return loadTvById(id)
        }

        // Use the real Movie Haat routes as SearchResponse URLs. This lets
        // CloudStream invoke this provider's load() reliably instead of
        // treating a custom scheme such as movie:18605 as a generic URL.
        val path = runCatching {
            URI(value).path.orEmpty()
        }.getOrDefault("")

        val moviePrefix = "/movies-detail/"
        if (path.startsWith(moviePrefix, true)) {
            val id = path.substringAfter(moviePrefix).trim('/')
            if (id.isNotBlank()) return loadMovieById(id)
        }

        val tvPrefix = "/episodes/"
        if (path.startsWith(tvPrefix, true)) {
            val id = path.substringAfter(tvPrefix).trim('/')
            if (id.isNotBlank()) return loadTvById(id)
        }

        if (looksLikeDirectMedia(value)) {
            return newMovieLoadResponse(
                titleFromUrl(value),
                value,
                TvType.Movie,
                value
            )
        }

        return newMovieLoadResponse(
            titleFromUrl(value),
            value,
            TvType.Movie,
            value
        )
    }

    private suspend fun loadMovieById(
        id: String
    ): LoadResponse {
        if (id.isBlank()) {
            return newMovieLoadResponse(
                "Movie",
                id,
                TvType.Movie,
                id
            )
        }

        val detailUrl =
            "$API_BASE$MOVIE_DETAIL_API/${urlPath(id)}"

        val response = runCatching {
            app.get(detailUrl)
        }.getOrNull()

        val root =
            response?.let { parseJson(it.text) }

        val node =
            root?.let {
                firstObjectFrom(
                    it,
                    "results"
                )
            }

        val title =
            node?.textOrNull("title")
                ?: "Movie"

        val poster =
            node?.let(::posterFromNode)

        val directVideo =
            node?.textOrNull("video_path")
                ?.let(::mediaUrl)

        return newMovieLoadResponse(
            title,
            "movie:$id",
            TvType.Movie,
            directVideo ?: "movie:$id"
        ) {
            posterUrl = poster
            plot = node?.textOrNull("plot")
        }
    }

    private suspend fun loadTvById(
        id: String
    ): LoadResponse {
        if (id.isBlank()) {
            return newTvSeriesLoadResponse(
                "TV Show",
                "tv:$id",
                TvType.TvSeries,
                emptyList()
            )
        }

        val response = runCatching {
            app.post(
                "$API_BASE$TV_EPISODES_API",
                data = mapOf(
                    "series_id" to id
                )
            )
        }.getOrNull()

        val root =
            response?.let { parseJson(it.text) }

        val episodeNodes =
            root?.let {
                arrayFrom(
                    it,
                    "results"
                )
            }.orEmpty()

        val first =
            episodeNodes.firstOrNull()

        val title =
            first?.textOrNull("series_title")
                ?: first?.textOrNull("show_title")
                ?: first?.textOrNull("series_name")
                ?: first?.textOrNull("title")
                ?: "TV Show"

        val poster =
            first?.let(::posterFromNode)

        val episodes =
            episodeNodes
                .mapNotNull {
                    episodeFromNode(
                        node = it,
                        fallbackSeriesId = id,
                        fallbackPoster = poster
                    )
                }
                .sortedWith(
                    compareBy<Episode> {
                        it.season ?: Int.MAX_VALUE
                    }.thenBy {
                        it.episode ?: Int.MAX_VALUE
                    }.thenBy {
                        (it.name ?: "").lowercase(Locale.ROOT)
                    }
                )

        if (episodes.isEmpty()) {
            return newTvSeriesLoadResponse(
                title,
                "tv:$id",
                TvType.TvSeries,
                emptyList()
            ) {
                posterUrl = poster
            }
        }

        return newTvSeriesLoadResponse(
            title,
            "tv:$id",
            TvType.TvSeries,
            episodes
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
        val value = data.trim()

        if (value.isBlank()) return false

        if (looksLikeDirectMedia(value)) {
            emitMedia(
                value,
                callback
            )
            return true
        }

        if (value.startsWith("episode:", true)) {
            val payload =
                value.substringAfter(":", "").split("|")

            val seriesId =
                payload.getOrNull(0).orEmpty()

            val season =
                payload.getOrNull(1)?.toIntOrNull() ?: 1

            val episode =
                payload.getOrNull(2)?.toIntOrNull() ?: 1

            val response = runCatching {
                app.post(
                    "$API_BASE$TV_EPISODE_API",
                    data = mapOf(
                        "series_id" to seriesId,
                        "seasonNumber" to season.toString(),
                        "episodeNumber" to episode.toString()
                    )
                )
            }.getOrNull() ?: return false

            val root = parseJson(response.text)

            val nodes =
                arrayFrom(
                    root,
                    "results"
                )

            var emitted = false

            nodes.forEach { node ->
                val raw =
                    node.textOrNull("video_file_1")
                        ?: node.textOrNull("video_path")
                        ?: node.textOrNull("video_file")
                        ?: node.textOrNull("video")

                val direct =
                    raw?.let(::mediaUrl)

                if (!direct.isNullOrBlank()) {
                    emitMedia(
                        direct,
                        callback
                    )
                    emitted = true
                }
            }

            return emitted
        }

        if (value.startsWith("movie:", true)) {
            val id =
                value.substringAfter(":", "").trim()

            if (id.isBlank()) return false

            val response = runCatching {
                app.get(
                    "$API_BASE$MOVIE_DETAIL_API/${urlPath(id)}"
                )
            }.getOrNull() ?: return false

            val node =
                firstObjectFrom(
                    parseJson(response.text),
                    "results"
                )

            val direct =
                node?.textOrNull("video_path")
                    ?.let(::mediaUrl)
                    ?: return false

            emitMedia(
                direct,
                callback
            )

            return true
        }

        return false
    }

    private fun parseMovieItems(
        root: JsonNode?,
        source: Source
    ): List<HomeItem> {
        return arrayFrom(
            root,
            "data",
            "results"
        ).mapNotNull { node ->
            parseHomeItem(
                node = node,
                source = source,
                isSeries = false
            )
        }
    }

    private fun parseTvItems(
        root: JsonNode?,
        source: Source
    ): List<HomeItem> {
        return arrayFrom(
            root,
            "data",
            "results"
        ).mapNotNull { node ->
            parseHomeItem(
                node = node,
                source = source,
                isSeries = true
            )
        }
    }

    private fun parseHomeItem(
        node: JsonNode,
        source: Source?,
        isSeries: Boolean
    ): HomeItem? {
        val id =
            firstText(
                node,
                "movie_id",
                "series_id",
                "id"
            ) ?: return null

        val title =
            node.textOrNull("title")
                ?: node.textOrNull("name")
                ?: return null

        val poster =
            posterFromNode(node)

        val video =
            node.textOrNull("video_path")
                ?: node.textOrNull("video_file_1")
                ?: node.textOrNull("video_file")

        return HomeItem(
            id = id,
            title = cleanTitle(title),
            posterUrl = poster,
            videoPath = video,
            runtime = node.textOrNull("runtime_str"),
            imdbRating =
                node.textOrNull("imdb_rating")
                    ?: node.textOrNull("imDbRating"),
            source = source,
            isSeries = isSeries
        )
    }

    private fun episodeFromNode(
        node: JsonNode,
        fallbackSeriesId: String,
        fallbackPoster: String?
    ): Episode? {
        val seriesId =
            node.textOrNull("series_id")
                ?: fallbackSeriesId

        val season =
            firstInt(
                node,
                "seasonNumber",
                "season",
                "season_number"
            ) ?: 1

        val episode =
            firstInt(
                node,
                "episodeNumber",
                "episode",
                "episode_number"
            ) ?: return null

        val title =
            node.textOrNull("title")
                ?: "Episode $episode"

        val poster =
            posterFromNode(node)
                ?: fallbackPoster

        val direct =
            node.textOrNull("video_path")
                ?: node.textOrNull("video_file_1")
                ?: node.textOrNull("video_file")
                ?: node.textOrNull("video")

        val data =
            if (!direct.isNullOrBlank()) {
                mediaUrl(direct)
            } else {
                "episode:$seriesId|$season|$episode"
            }

        return newEpisode(data) {
            name = title
            this.season = season
            this.episode = episode
            posterUrl = poster
            description =
                node.textOrNull("plot")
                    ?: node.textOrNull("description")
            date =
                parseTimestamp(
                    firstText(
                        node,
                        "released",
                        "release_date",
                        "created_at",
                        "date"
                    )
                )
        }
    }

    private fun toSearchResponse(
        item: HomeItem
    ): SearchResponse {
        // Real site routes are used as the public SearchResponse URL.
        // For movies with a media path already present in the list API, we
        // can hand the direct media URL to CloudStream immediately.
        val publicUrl =
            if (item.isSeries) {
                "$mainUrl/episodes/${urlPath(item.id)}"
            } else {
                "$mainUrl/movies-detail/${urlPath(item.id)}"
            }

        return if (item.isSeries) {
            newTvSeriesSearchResponse(
                item.title,
                publicUrl,
                TvType.TvSeries
            ) {
                posterUrl = item.posterUrl
            }
        } else {
            newMovieSearchResponse(
                item.title,
                publicUrl,
                TvType.Movie
            ) {
                posterUrl = item.posterUrl
            }
        }
    }

    private suspend fun emitMedia(
        url: String,
        callback: (ExtractorLink) -> Unit
    ) {
        val clean =
            url.substringBefore("#").trim()

        if (clean.isBlank()) return

        val lowered =
            clean
                .substringBefore("?")
                .lowercase(Locale.ROOT)

        val type =
            when {
                lowered.endsWith(".m3u8") ->
                    ExtractorLinkType.M3U8

                lowered.endsWith(".mpd") ->
                    ExtractorLinkType.DASH

                else ->
                    ExtractorLinkType.VIDEO
            }
