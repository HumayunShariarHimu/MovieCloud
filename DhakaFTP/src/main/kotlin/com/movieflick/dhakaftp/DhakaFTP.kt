
package com.movieflick.dhakaftp

import com.lagradost.cloudstream3.Episode
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageData
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SearchResponseList
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.newEpisode
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newMovieLoadResponse
import com.lagradost.cloudstream3.newMovieSearchResponse
import com.lagradost.cloudstream3.newSearchResponseList
import com.lagradost.cloudstream3.newTvSeriesLoadResponse
import com.lagradost.cloudstream3.newTvSeriesSearchResponse
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.fasterxml.jackson.databind.ObjectMapper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import org.jsoup.nodes.Element
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.PriorityQueue
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlin.math.max

class DhakaFTP : MainAPI() {

    override var mainUrl = "http://172.16.50.7/"
    override var name = "DhakaFTP"
    override var lang = "bn"

    override val hasMainPage = true
    override val hasQuickSearch = true

    override val supportedTypes = setOf(
        TvType.Movie,
        TvType.TvSeries,
        TvType.Anime
    )

    /*
     * FINAL HOMEPAGE:
     *
     * English Movies
     * Hindi Movies
     * Kolkata Bangla Movies
     * South Indian Hindi Dubbed
     * TV Show
     * Anime
     *
     * TV Show is one CloudStream section backed by two real roots.
     */
    override val mainPage = listOf(
        MainPageData(
            "English Movies",
            ROOT_ENGLISH
        ),
        MainPageData(
            "Hindi Movies",
            ROOT_HINDI
        ),
        MainPageData(
            "Kolkata Bangla Movies",
            ROOT_KOLKATA
        ),
        MainPageData(
            "South Indian Hindi Dubbed",
            ROOT_SOUTH
        ),
        MainPageData(
            "TV Show",
            TV_SHOW_ROOTS
        ),
        MainPageData(
            "Anime",
            ROOT_ANIME
        )
    )

    private companion object {

        const val ROOT_ENGLISH =
            "http://172.16.50.7/DHAKA-FLIX-7/English%20Movies/"

        const val ROOT_HINDI =
            "http://172.16.50.14/DHAKA-FLIX-14/Hindi%20Movies/"

        const val ROOT_KOLKATA =
            "http://172.16.50.7/DHAKA-FLIX-7/Kolkata%20Bangla%20Movies/"

        const val ROOT_SOUTH =
            "http://172.16.50.14/DHAKA-FLIX-14/SOUTH%20INDIAN%20MOVIES/Hindi%20Dubbed/"

        const val ROOT_TV_1 =
            "http://172.16.50.12/DHAKA-FLIX-12/TV-WEB-Series/"

        const val ROOT_TV_2 =
            "http://172.16.50.14/DHAKA-FLIX-14/KOREAN%20TV%20%26%20WEB%20Series/"

        const val ROOT_ANIME =
            "http://172.16.50.14/DHAKA-FLIX-14/Animation%20Movies/"

        const val TV_SHOW_PREFIX =
            "__DHakaFTP_TV_SHOW__::"

        const val TV_SHOW_SEPARATOR =
            "\u001F"

        val TV_SHOW_ROOTS =
            TV_SHOW_PREFIX +
                ROOT_TV_1 +
                TV_SHOW_SEPARATOR +
                ROOT_TV_2

        const val MOVIE_HOME_SIZE = 6
        const val TV_HOME_SIZE = 6
        const val TV_SOURCE_BATCH = 3
        const val SEARCH_PAGE_SIZE = 50

        /* Search-only performance / fallback tuning. */
        const val QUICK_SEARCH_NATIVE_TIMEOUT_MS = 2200L
        const val QUICK_SEARCH_DIRECTORY_TIMEOUT_MS = 700L
        const val SEARCH_FALLBACK_TIMEOUT_MS = 4500L
        const val SEARCH_FALLBACK_DIRECTORY_TIMEOUT_MS = 650L
        const val SEARCH_FALLBACK_MAX_DIRECTORIES = 300
        const val SEARCH_FALLBACK_MAX_DEPTH = 12
        const val SEARCH_FALLBACK_BATCH_SIZE = 12

        const val SEARCH_NATIVE_ACCEPT_SCORE = 5600
        const val SEARCH_FALLBACK_MATCH_SCORE = 500
        const val SEARCH_STRONG_MATCH_SCORE = 6500
        const val SEARCH_MIN_RESULT_SCORE = 450
        const val SEARCH_DIRECTORY_EXPANSION_SCORE = 3200
        const val SEARCH_DIRECTORY_HIT_BONUS = 120
        const val SEARCH_VIDEO_HIT_BONUS = 220
        const val SEARCH_FOLDER_NAME_BOOST = 240
        const val SEARCH_DUAL_AUDIO_BOOST = 150
        const val SEARCH_DEPTH_PENALTY = 25
        const val SEARCH_MAX_PARENT_FETCHES = 140
        const val SEARCH_MAX_MATCHING_DIRECTORIES_TO_EXPAND = 16
        const val SEARCH_FALLBACK_MAX_RESULTS = 96
        const val SEARCH_TARGETED_SUPPLEMENT_TIMEOUT_MS = 3200L
        const val SEARCH_STRONG_MATCH_STOP_COUNT = 3

        /*
         * Compatibility alias. This does not remove or change the existing
         * search timeout; it only supplies the name used by the deep loader.
         */
        const val SEARCH_DIRECTORY_TIMEOUT_MS =
            QUICK_SEARCH_DIRECTORY_TIMEOUT_MS

        /*
         * The first synchronous homepage request is intentionally
         * lightweight. The full recursive index has no folder-depth cap.
         */
        const val QUICK_SCAN_DIRECTORY_LIMIT = 36

        /* First-page synchronous probe budget. */
        const val QUICK_DIRECTORY_TIMEOUT_MS = 850L

        /* Directories fetched concurrently during a quick probe. */
        const val QUICK_SCAN_BATCH_SIZE = 8

        /*
         * Fast first-screen bootstrap only. The full recursive index remains
         * independent and continues in the background.
         */
        const val FAST_HOME_REQUEST_TIMEOUT_MS = 650L
        const val FAST_HOME_BATCH_SIZE = 6
        const val FAST_HOME_MAX_DIRECTORIES = 144
        const val FAST_HOME_MAX_DEPTH = 8

        const val CACHE_MINUTES = 10L

        val VIDEO_EXTENSIONS = setOf(
            ".mkv",
            ".mp4",
            ".webm",
            ".avi",
            ".mov",
            ".m4v",
            ".m3u8"
        )

        val IMAGE_EXTENSIONS = setOf(
            ".jpg",
            ".jpeg",
            ".png",
            ".webp"
        )
    }

    private enum class ContentKind {
        MOVIE,
        ANIME,
        SERIES
    }

    private data class FtpEntry(
        val name: String,
        val url: String,
        val isVideo: Boolean,
        val isImage: Boolean,
        val isDirectory: Boolean,
        val modifiedAt: Long?,
        val sizeBytes: Long?,
        val order: Long
    )

    private data class CrawlNode(
        val url: String,
        val inheritedPoster: String?,
        val inheritedModifiedAt: Long?,
        val collectionRoot: String?,
        val seasonHint: Int?
    )

    private data class FtpVideo(
        val title: String,
        val url: String,
        val posterUrl: String?,
        val modifiedAt: Long,
        val sizeBytes: Long?,
        val season: Int?,
        val episode: Int?,
        val dualAudio: Boolean,
        val resolution: Int,
        val bitrateMbps: Double?,
        val order: Long
    )

    private data class FtpGroup(
        val title: String,
        val url: String,
        val posterUrl: String?,
        val modifiedAt: Long,
        val videos: List<FtpVideo>,
        val kind: ContentKind,
        val isSeasonCard: Boolean = false,
        val seasonNumber: Int? = null
    ) {
        /*
         * Movie categories NEVER turn multiple files in one folder into
         * CloudStream episodes. TV Show is the episode-based category.
         * Anime remains episode-based only when multiple real files exist.
         */
        val isSeries: Boolean
            get() =
                when (kind) {
                    ContentKind.SERIES ->
                        isSeasonCard ||
                            videos.size > 1 ||
                            videos.any {
                                it.season != null ||
                                    it.episode != null
                            }

                    ContentKind.ANIME ->
                        videos.size >= 3

                    ContentKind.MOVIE ->
                        false
                }

        val hasDualAudio: Boolean
            get() =
                videos.any {
                    it.dualAudio
                }

        val maxResolution: Int
            get() =
                videos.maxOfOrNull {
                    it.resolution
                } ?: 0

        val maxSizeBytes: Long
            get() =
                videos.maxOfOrNull {
                    it.sizeBytes ?: 0L
                } ?: 0L
    }

    private data class CacheEntry(
        val createdAt: Long,
        val complete: Boolean,
        val groups: List<FtpGroup>
    )

    private data class SearchMatch(
        val group: FtpGroup,
        val score: Int
    )

    private class GroupBuilder(
        val title: String,
        val url: String,
        val kind: ContentKind
    ) {
        var posterUrl: String? = null
        var modifiedAt: Long = 0L
        val videos = mutableListOf<FtpVideo>()
    }

    private val scope =
        CoroutineScope(
            SupervisorJob() +
                Dispatchers.IO
        )

    private val cache =
        ConcurrentHashMap<String, CacheEntry>()

    /* Reuse posters already discovered for Season cards. */
    private val posterCache =
        ConcurrentHashMap<String, String>()

    private val scanJobs =
        ConcurrentHashMap<String, Deferred<List<FtpGroup>>>()

    /*
     * Dynamic Home cursors.
     *
     * First page = 6 items.
     * Later horizontal pages = 10 items.
     *
     * The cursor keeps a directory queue so a category is continued from
     * the exact point reached previously instead of rescanning the root.
     */
    private data class DynamicMovieCursor(
        val root: String,
        val kind: ContentKind,
        val queue:
            java.util.ArrayDeque<FtpEntry> =
            java.util.ArrayDeque(),
        val emitted:
            MutableSet<String> =
            HashSet(),
        val pages:
            ConcurrentHashMap<Int, List<FtpGroup>> =
            ConcurrentHashMap(),
        var initialized: Boolean = false,
        var exhausted: Boolean = false
    )

    private data class DynamicTvCursor(
        val root: String,
        val queue:
            java.util.ArrayDeque<FtpEntry> =
            java.util.ArrayDeque(),
        val emitted:
            MutableSet<String> =
            HashSet(),
        val pages:
            ConcurrentHashMap<Int, List<FtpGroup>> =
            ConcurrentHashMap(),
        var initialized: Boolean = false,
        var exhausted: Boolean = false
    )

    private val dynamicMovieCursors =
        ConcurrentHashMap<String, DynamicMovieCursor>()

    private val dynamicTvCursors =
        ConcurrentHashMap<String, DynamicTvCursor>()

    private val searchRoots by lazy {
        listOf(
            ROOT_ENGLISH,
            ROOT_HINDI,
            ROOT_KOLKATA,
            ROOT_SOUTH,
            ROOT_TV_1,
            ROOT_TV_2,
            ROOT_ANIME
        ).distinct()
    }

    /*
     * ---------------------------------------------------------------
     * HOMEPAGE
     * ---------------------------------------------------------------
     */




    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {

        if (
            request.data.startsWith(
                TV_SHOW_PREFIX
            )
        ) {
            return getTvShowHomePage(
                page,
                request
            )
        }

        val root =
            normalizeDirectoryUrl(
                request.data
            )

        val kind =
            detectKind(
                root
            )

        val pageSize =
            if (
                page <= 1
            ) {
                6
            } else {
                10
            }

        /*
         * Each main-page category owns its own cursor.
         * Page 1 returns six; later horizontal pages return ten.
         * No complete-library crawl is required for Home.
         */
        val items =
            loadDynamicMoviePage(
                root = root,
                kind = kind,
                page = page,
                pageSize = pageSize
            )

        /*
         * Full indexing is strictly background work.
         */
        scope.launch {
            kotlinx.coroutines.delay(
                20000L
            )
            prewarm(root)
        }

        val state =
            dynamicMovieCursors[root]

        return newHomePageResponse(
            request,
            items.map {
                toSearchResponse(it)
            },
            state?.exhausted != true ||
                items.isNotEmpty()
        )
    }

    private suspend fun loadDynamicMoviePage(
        root: String,
        kind: ContentKind,
        page: Int,
        pageSize: Int
    ): List<FtpGroup> {

        val state =
            dynamicMovieCursors.computeIfAbsent(
                root
            ) {
                DynamicMovieCursor(
                    root = root,
                    kind = kind
                )
            }

        state.pages[page]?.let {
            return it
        }

        if (!state.initialized) {

            val rootEntries =
                safeDirectoryEntries(
                    root,
                    1800L
                )

            state.initialized = true

            if (
                rootEntries.isEmpty()
            ) {
                state.exhausted = true
                state.pages[page] =
                    emptyList()
                return emptyList()
            }

            /*
             * Direct files: fastest possible path.
             */
            val direct =
                rootEntries
                    .filter {
                        it.isVideo
                    }
                    .sortedByDescending {
                        it.modifiedAt ?: 0L
                    }

            if (
                direct.isNotEmpty()
            ) {

                val groups =
                    normalizeQuickBootstrapGroups(
                        buildGroupsFromVideoEntries(
                            folderUrl =
                                root,
                            entries =
                                direct,
                            poster =
                                pickPoster(
                                    rootEntries
                                ),
                            kind =
                                kind
                        )
                    )

                val first =
                    emitDynamicMovieGroups(
                        state,
                        groups
                    ).take(
                        pageSize
                    )

                if (
                    first.isEmpty()
                ) {
                    state.exhausted = true
                }

                state.pages[page] =
                    first

                return first
            }

            /*
             * LIVE ORDER:
             * newest year first, then modification time.
             * No fixed 2023 assumption here.
             */
            rootEntries
                .filter {
                    it.isDirectory
                }
                .sortedWith(
                    latestLiveFolderComparator()
                )
                .forEach {
                    state.queue.addLast(
                        it
                    )
                }
        }

        val collected =
            mutableListOf<FtpGroup>()

        while (
            collected.size <
                pageSize &&
            !state.exhausted
        ) {

            val orderedQueue =
                state.queue
                    .toList()
                    .sortedWith(
                        latestLiveFolderComparator()
                    )

            state.queue.clear()

            orderedQueue.forEach {
                state.queue.addLast(it)
            }

            if (
                state.queue.isEmpty()
            ) {
                state.exhausted = true
                break
            }

            val batch =
                mutableListOf<FtpEntry>()

            repeat(
                minOf(
                    pageSize,
                    state.queue.size
                )
            ) {
                batch.add(
                    state.queue.removeFirst()
                )
            }

            val results =
                coroutineScope {
                    batch.map {
                        directory ->
                        async {

                            val folderUrl =
                                normalizeDirectoryUrl(
                                    directory.url
                                )

                            val entries =
                                safeDirectoryEntries(
                                    folderUrl,
                                    2200L
                                )

                            val poster =
                                pickPoster(
                                    entries
                                )

                            val videos =
                                entries
                                    .filter {
                                        it.isVideo
                                    }
                                    .sortedByDescending {
                                        it.modifiedAt ?: 0L
                                    }

                            val groups =
                                if (
                                    videos.isNotEmpty()
                                ) {
                                    buildGroupsFromVideoEntries(
                                        folderUrl =
                                            folderUrl,
                                        entries =
                                            videos,
                                        poster =
                                            poster,
                                        kind =
                                            kind
                                    )
                                } else {
                                    emptyList()
                                }

                            val children =
                                if (
                                    videos.isEmpty()
                                ) {
                                    entries
                                        .filter {
                                            it.isDirectory
                                        }
                                        .sortedWith(
                                            latestLiveFolderComparator()
                                        )
                                } else {
                                    emptyList()
                                }

                            Pair(
                                groups,
                                children
                            )
                        }
                    }.awaitAll()
                }

            results.forEach {
                result ->

                collected.addAll(
                    emitDynamicMovieGroups(
                        state,
                        result.first
                    )
                )

                result.second.forEach {
                    child ->
                    state.queue.addLast(
                        child
                    )
                }
            }
        }

        val result =
            collected
                .sortedWith(
                    groupComparator()
                )
                .take(
                    pageSize
                )

        state.pages[page] =
            result

        return result
    }

    private fun emitDynamicMovieGroups(
        state: DynamicMovieCursor,
        groups: List<FtpGroup>
    ): List<FtpGroup> {

        return deduplicateGroups(
            groups
        )
            .sortedWith(
                groupComparator()
            )
            .filter { group ->

                val video =
                    group.videos.firstOrNull()

                if (
                    video == null
                ) {
                    return@filter state.emitted.add(
                        group.kind.name +
                            ":EMPTY:" +
                            logicalMovieDuplicateKey(
                                group.title
                            )
                    )
                }

                val folder =
                    parentDirectory(
                        video.url
                    ) ?: group.url

                val folderTitle =
                    getFolderTitle(
                        folder
                    ).ifBlank {
                        group.title
                    }

                val key =
                    group.kind.name +
                        ":" +
                        logicalMovieDuplicateKey(
                            folderTitle
                        ) +
                        ":R" +
                        video.resolution

                state.emitted.add(
                    key
                )
            }
    }

    private fun latestLiveFolderComparator():
        Comparator<FtpEntry> {

        return compareByDescending<FtpEntry> {
            yearValueFromName(
                it.name
            ) ?: -1
        }
            .thenByDescending {
                it.modifiedAt ?: 0L
            }
            .thenByDescending {
                directoryPriority(it)
            }
            .thenBy {
                it.order
            }
    }



    private suspend fun getTvShowHomePage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {

        val roots =
            decodeTvShowRoots(
                request.data
            ) ?: return newHomePageResponse(
                request,
                emptyList(),
                false
            )

        if (
            roots.size < 2
        ) {
            return newHomePageResponse(
                request,
                emptyList(),
                false
            )
        }

        val first =
            coroutineScope {

                val a =
                    async {
                        loadDynamicTvPage(
                            roots[0],
                            page,
                            6
                        )
                    }

                val b =
                    async {
                        loadDynamicTvPage(
                            roots[1],
                            page,
                            6
                        )
                    }

                a.await() to
                    b.await()
            }

        val homeGroups =
            collapseLatestTvAcrossSources(
                first.first +
                    first.second
            )
                .take(
                    6
                )

        /*
         * Background full index only.
         */
        scope.launch {
            kotlinx.coroutines.delay(
                20000L
            )
            prewarm(roots[0])
            prewarm(roots[1])
        }

        val more =
            dynamicTvCursors[
                roots[0]
            ]?.exhausted != true ||
                dynamicTvCursors[
                    roots[1]
                ]?.exhausted != true

        return newHomePageResponse(
            request,
            homeGroups.map {
                toSearchResponse(it)
            },
            more ||
                homeGroups.isNotEmpty()
        )
    }

    private fun logicalTvShowHomeKey(
        card: FtpGroup
    ): String {

        val showFolder =
            if (
                card.isSeasonCard
            ) {
                parentDirectory(
                    card.url
                )
            } else {
                card.url
            }

        val title =
            showFolder?.let {
                getFolderTitle(it)
            } ?: card.title

        return "TV:" +
            logicalTvShowKey(
                title
            )
    }


    private fun episodeSortKey(
        name: String
    ): Int {

        val normalized =
            decodeSafely(
                name
            ).uppercase(
                Locale.getDefault()
            )

        /*
         * Preferred explicit TV numbering:
         * S01E01, S02E03, etc.
         */
        Regex(
            "(?<![A-Z0-9])S\\d{1,3}E(\\d{1,4})(?![A-Z0-9])"
        )
            .find(
                normalized
            )
            ?.groupValues
            ?.getOrNull(1)
            ?.toIntOrNull()
            ?.let {
                return it
            }

        /*
         * Standalone E01 / E1 patterns.
         */
        Regex(
            "(?<![A-Z0-9])E(\\d{1,4})(?![A-Z0-9])"
        )
            .find(
                normalized
            )
            ?.groupValues
            ?.getOrNull(1)
            ?.toIntOrNull()
            ?.let {
                return it
            }

        /*
         * EP01 / EP 01 / Episode 01 variants.
         */
        Regex(
            "(?<![A-Z0-9])(?:EP|EPISODE)[ ._-]*(\\d{1,4})(?![A-Z0-9])"
        )
            .find(
                normalized
            )
            ?.groupValues
            ?.getOrNull(1)
            ?.toIntOrNull()
            ?.let {
                return it
            }

        /*
         * Numeric fallback. This is intentionally conservative so that
         * years, resolutions, and unrelated digits do not become episode
         * numbers when a real episode marker was not found.
         */
        Regex(
            "(?i)(?:^|[ ._\\-])(?:e|ep|episode)[ ._-]*(\\d{1,4})(?:$|[^0-9])"
        )
            .find(
                normalized
            )
            ?.groupValues            ?.getOrNull(1)
            ?.toIntOrNull()
            ?.let {
                return it
            }

        return Int.MAX_VALUE
    }

    private suspend fun loadDynamicTvPage(
        rootRaw: String,
        page: Int,
        pageSize: Int
    ): List<FtpGroup> {

        val root =
            normalizeDirectoryUrl(
                rootRaw
            )

        val state =
            dynamicTvCursors.computeIfAbsent(
                root
            ) {
                DynamicTvCursor(
                    root = root
                )
            }

        state.pages[page]?.let {
            return it
        }

        if (!state.initialized) {

            val entries =
                safeDirectoryEntries(
                    root,
                    1600L
                )

            state.initialized = true

            if (
                entries.isEmpty()
            ) {
                state.exhausted = true
                state.pages[page] =
                    emptyList()
                return emptyList()
            }

            entries
                .filter {
                    it.isDirectory
                }
                .sortedWith(
                    latestLiveFolderComparator()
                )
                .forEach {
                    state.queue.addLast(it)
                }
        }

        val found =
            mutableListOf<FtpGroup>()

        while (
            found.size <
                pageSize &&
            !state.exhausted
        ) {

            if (
                state.queue.isEmpty()
            ) {
                state.exhausted = true
                break
            }

            val orderedQueue =
                state.queue
                    .toList()
                    .sortedWith(
                        latestLiveFolderComparator()
                    )

            state.queue.clear()

            orderedQueue.forEach {
                state.queue.addLast(it)
            }

            val batch =
                mutableListOf<FtpEntry>()

            repeat(
                minOf(
                    pageSize,
                    state.queue.size
                )
            ) {
                batch.add(
                    state.queue.removeFirst()
                )
            }

            val fetched =
                coroutineScope {
                    batch.map {
                        candidate ->
                        async {

                            val folder =
                                normalizeDirectoryUrl(
                                    candidate.url
                                )

                            val entries =
                                safeDirectoryEntries(
                                    folder,
                                    1900L
                                )

                            val poster =
                                pickPoster(
                                    entries
                                )

                            val seasons =
                                entries
                                    .filter {
                                        it.isDirectory &&
                                            isSeasonDirectory(
                                                it.name
                                            )
                                    }
                                    .sortedWith(
                                        compareByDescending<FtpEntry> {
                                            it.modifiedAt
                                                ?: 0L
                                        }.thenByDescending {
                                            extractSeasonNumber(
                                                it.name
                                            ) ?: 0
                                        }
                                    )

                            val directEpisodes =
                                entries
                                    .filter {
                                        it.isVideo
                                    }
                                    .sortedBy {
                                        episodeSortKey(
                                            it.name
                                        )
                                    }

                            val seasonCards =
                                seasons.map {
                                    seasonEntry ->

                                    val season =
                                        extractSeasonNumber(
                                            seasonEntry.name
                                        ) ?: 1

                                    val seasonUrl =
                                        normalizeDirectoryUrl(
                                            seasonEntry.url
                                        )

                                    if (
                                        poster != null
                                    ) {
                                        posterCache[
                                            seasonUrl
                                        ] =
                                            poster
                                    }

                                    FtpGroup(
                                        title =
                                            "${
                                                getFolderTitle(
                                                    folder
                                                )
                                            } Season $season",
                                        url =
                                            seasonUrl,
                                        posterUrl =
                                            poster,
                                        modifiedAt =
                                            seasonEntry.modifiedAt
                                                ?: 0L,
                                        videos =
                                            emptyList(),
                                        kind =
                                            ContentKind.SERIES,
                                        isSeasonCard =
                                            true,
                                        seasonNumber =
                                            season
                                    )
                                }

                            val directSeries =
                                if (
                                    seasonCards.isEmpty() &&
                                    directEpisodes.isNotEmpty()
                                ) {

                                    val videos =
                                        directEpisodes.mapIndexed {
                                            index,
                                            entry ->
                                            makeVideo(
                                                entry =
                                                    entry,
                                                poster =
                                                    poster,
                                                seasonHint =
                                                    1,
                                                order =
                                                    index.toLong()
                                            )
                                        }

                                    FtpGroup(
                                        title =
                                            "${
                                                getFolderTitle(
                                                    folder
                                                )
                                            } Season 1",
                                        url =
                                            folder,
                                        posterUrl =
                                            poster,
                                        modifiedAt =
                                            maxOf(
                                                candidate.modifiedAt
                                                    ?: 0L,
                                                directEpisodes.maxOfOrNull {
                                                    it.modifiedAt
                                                        ?: 0L
                                                } ?: 0L
                                            ),
                                        videos =
                                            videos,
                                        kind =
                                            ContentKind.SERIES,
                                        isSeasonCard =
                                            true,
                                        seasonNumber =
                                            1
                                    )
                                } else {
                                    null
                                }

                            /*
                             * Wrapper/year/category folder:
                             * continue deeper automatically.
                             */
                            val children =
                                if (
                                    seasonCards.isEmpty() &&
                                    directEpisodes.isEmpty()
                                ) {
                                    entries
                                        .filter {
                                            it.isDirectory
                                        }
                                        .sortedWith(
                                            latestLiveFolderComparator()
                                        )
                                } else {
                                    emptyList()
                                }

                            Triple(
                                seasonCards,
                                directSeries,
                                children
                            )
                        }
                    }.awaitAll()
                }

            fetched.forEach {
                (seasonCards,
                 directSeries,
                 children) ->

                children.forEach {
                    child ->
                    state.queue.addLast(
                        child
                    )
                }

                val candidates =
                    if (
                        seasonCards.isNotEmpty()
                    ) {
                        seasonCards
                    } else {
                        directSeries?.let {
                            listOf(it)
                        }.orEmpty()
                    }

                /*
                 * Within one source cursor, keep one latest card per
                 * logical show. Older seasons stay searchable.
                 */
                candidates
                    .sortedWith(
                        compareByDescending<FtpGroup> {
                            it.modifiedAt
                        }.thenByDescending {
                            it.seasonNumber
                                ?: 0
                        }
                    )
                    .forEach {
                        card ->

                        val key =
                            logicalTvShowHomeKey(
                                card
                            )

                        if (
                            state.emitted.add(
                                key
                            )
                        ) {
                            found.add(
                                card
                            )
                        }
                    }
            }
        }

        val final =
            collapseLatestTvAcrossSources(
                found
            )
                .sortedWith(
                    tvHomeComparator()
                )
                .take(
                    pageSize
                )

        state.pages[page] =
            final

        return final
    }

    private suspend fun waitForPartialIndex(
        root: String,
        requiredCount: Int,
        maxWaitMs: Long
    ) {

        val start =
            System.currentTimeMillis()

        while (
            System.currentTimeMillis() -
                start <
            maxWaitMs
        ) {

            val cached =
                validCache(root)

            if (
                cached != null &&
                (
                    cached.complete ||
                        cached.groups.size >=
                        requiredCount
                    )
            ) {
                return
            }

            kotlinx.coroutines.delay(
                60L
            )
        }
    }

    /*
     * Locate the logical show folder above a Season folder without making
     * any network request. This keeps Season 1/2/3 under one show key even
     * when a source inserts a non-Season wrapper directory.
     */
    private fun findLogicalShowRoot(
        seasonUrlRaw: String
    ): String? {

        var current =
            normalizeDirectoryUrl(
                seasonUrlRaw
            )

        repeat(5) {

            val parent =
                parentDirectory(
                    current
                ) ?: return null

            val title =
                getFolderTitle(
                    parent
                )

            if (
                !isSeasonDirectory(
                    title
                )
            ) {
                return parent
            }

            current =
                parent
        }

        return null
    }

    private fun latestSeasonPerShow(
        groups: List<FtpGroup>
    ): List<FtpGroup> {

        /*
         * TV homepage rule:
         * - if a show has Season folders, show ONLY its newest Season card
         * - older Seasons remain searchable
         * - if no Season folder exists, keep its direct-episode show card
         */
        val seasonCards =
            groups.filter {
                it.kind == ContentKind.SERIES &&
                    it.isSeasonCard
            }

        val latestByShow =
            LinkedHashMap<String, FtpGroup>()

        seasonCards.forEach { card ->

            val showRoot =
                normalizeDirectoryUrl(
                    findLogicalShowRoot(
                        card.url
                    ) ?: (
                        parentDirectory(card.url)
                            ?: card.url
                    )
                )

            val existing =
                latestByShow[showRoot]

            if (
                existing == null ||
                card.modifiedAt > existing.modifiedAt ||
                (
                    card.modifiedAt == existing.modifiedAt &&
                    (card.seasonNumber ?: 0) >
                        (existing.seasonNumber ?: 0)
                )
            ) {
                latestByShow[showRoot] = card
            }
        }

        val seasonShowRoots =
            latestByShow.keys.map {
                normalizeDirectoryUrl(it)
            }.toSet()

        val fallback =
            groups.filter { group ->

                if (
                    group.kind != ContentKind.SERIES ||
                    group.isSeasonCard
                ) {
                    true
                } else {
                    normalizeDirectoryUrl(group.url) !in
                        seasonShowRoots
                }
            }

        return (latestByShow.values + fallback)
            .distinctBy {
                it.url.lowercase(Locale.getDefault())
            }
            .sortedWith(groupComparator())
    }

    private suspend fun getInitialGroups(
        root: String,
        limit: Int
    ): List<FtpGroup> {

        val cached =
            validCache(root)

        if (
            cached?.groups?.isNotEmpty() == true
        ) {
            return cached.groups.take(limit)
        }

        /*
         * Collect a few extra candidates because duplicate filtering or
         * latest-season collapsing may remove some of them.
         */
        val probeLimit =
            maxOf(limit, limit * 2)

        val initial =
            if (
                detectKind(root) == ContentKind.SERIES
            ) {
                fastTvBootstrap(
                    root,
                    probeLimit
                )
            } else {
                fastMovieBootstrap(
                    root,
                    probeLimit
                )
            }

        if (
            initial.isNotEmpty()
        ) {
            updatePartialCache(
                root,
                initial
            )
        }

        return initial.take(limit)
    }



    private fun collapseLatestTvAcrossSources(
        groups: List<FtpGroup>
    ): List<FtpGroup> {

        val latestByShow =
            LinkedHashMap<String, FtpGroup>()

        /*
         * Home-only TV dedup:
         * one logical show -> one Home card.
         *
         * Use the actual show-folder identity, not the rendered title.
         * This prevents duplicate SWAT cards when two source folders use
         * slightly different display metadata.
         *
         * Choose the newest Season by modification time; season number breaks
         * equal timestamps. Older seasons remain searchable.
         */
        groups
            .filter {
                it.kind == ContentKind.SERIES
            }
            .forEach { card ->

                val key =
                    logicalTvShowHomeKey(
                        card
                    )

                val current =
                    latestByShow[key]

                if (
                    current == null ||
                    tvCardIsNewer(
                        card,
                        current
                    )
                ) {
                    latestByShow[key] =
                        card
                }
            }

        return latestByShow.values
            .sortedWith(
                tvHomeComparator()
            )
    }

    private fun logicalTvShowKey(
        titleRaw: String
    ): String {

        var value =
            normalizeSearchText(
                titleRaw
            )

        value =
            value.replace(
                Regex(
                    "(?i)\\bseason\\s*\\d{1,3}\\b"
                ),
                " "
            )

        value =
            value.replace(
                Regex(
                    "(?i)\\bS\\d{1,3}\\b"
                ),
                " "
            )

        value =
            value.replace(
                Regex(
                    "(?i)\\b(tv\\s*series|tv\\s*show|series)\\b"
                ),
                " "
            )

        value =
            value.replace(
                Regex(
                    "\\[[^]]*]"
                ),
                " "
            )

        value =
            value.replace(
                Regex(
                    "\\([^)]*\\)"
                ),
                " "
            )

        value =
            value.replace(
                Regex(
                    "\\s+"
                ),
                " "
            )
            .trim()

        return compactSearchText(
            value
        )
    }

    private fun tvCardIsNewer(
        candidate: FtpGroup,
        current: FtpGroup
    ): Boolean {

        if (
            candidate.modifiedAt !=
            current.modifiedAt
        ) {
            return candidate.modifiedAt >
                current.modifiedAt
        }

        return (
            candidate.seasonNumber
                ?: 0
        ) >
            (
                current.seasonNumber
                    ?: 0
            )
    }

    private fun tvHomeComparator():
        Comparator<FtpGroup> {

        return compareByDescending<FtpGroup> {
            it.modifiedAt
        }
            .thenByDescending {
                it.seasonNumber
                    ?: 0
            }
            .thenBy {
                it.title.lowercase(
                    Locale.getDefault()
                )
            }
    }

    private fun mixThreeAndThree(
        first: List<FtpGroup>,
        second: List<FtpGroup>
    ): List<FtpGroup> {

        val result =
            mutableListOf<FtpGroup>()

        var a = 0
        var b = 0

        while (
            a < first.size ||
            b < second.size
        ) {

            repeat(
                TV_SOURCE_BATCH
            ) {
                if (
                    a < first.size
                ) {
                    result.add(
                        first[a++]
                    )
                }
            }

            repeat(
                TV_SOURCE_BATCH
            ) {
                if (
                    b < second.size
                ) {
                    result.add(
                        second[b++]
                    )
                }
            }
        }

        return result
    }

    /*
     * ---------------------------------------------------------------
     * SEARCH
     * ---------------------------------------------------------------
     */


    override suspend fun search(
        query: String,
        page: Int
    ): SearchResponseList {

        val normalizedQuery =
            normalizeSearchText(
                query
            )

        if (
            normalizedQuery.isBlank()
        ) {
            return newSearchResponseList(
                emptyList(),
                false
            )
        }

        /*
         * TMDB-FIRST SEARCH:
         *
         * 1. Ask TMDB for the best media identity first.
         * 2. Route to the most likely DhakaFTP branch.
         * 3. Search the targeted branch before touching the legacy engine.
         * 4. If TMDB is unavailable, returns no usable identity, or the routed
         *    branches do not contain the title, fall back to the old search.
         *
         * IMPORTANT:
         * A valid TMDB response with zero results is NOT treated as an API
         * credential failure. Credential rotation only happens for actual
         * request/API failures inside TmdbHelper.
         */
        val tmdbOutcome =
            TmdbHelper.search(
                normalizedQuery
            )

        if (
            tmdbOutcome.isApiUsable &&
                tmdbOutcome.items.isNotEmpty()
        ) {

            val smartResults =
                searchByTmdbRouting(
                    query = normalizedQuery,
                    tmdbItems = tmdbOutcome.items
                )

            if (
                smartResults.isNotEmpty()
            ) {
                return paginateSearchResults(
                    smartResults,
                    page
                )
            }
        }

        /*
         * HARD SAFETY FALLBACK:
         *
         * TMDB outage, quota exhaustion, invalid credentials, timeout, or
         * a routed search miss must never break DhakaFTP search.
         * The existing legacy engine remains the final source of truth.
         */
        return legacySearch(
            normalizedQuery,
            page
        )
    }

    private suspend fun searchByTmdbRouting(
        query: String,
        tmdbItems: List<TmdbMedia>
    ): List<NativeSearchResult> {

        val candidates =
            tmdbItems
                .take(3)

        val candidateResults =
            coroutineScope {
                candidates
                    .map { media ->
                        async {
                            val roots =
                                when (media.mediaType) {
                                    TmdbMediaType.MOVIE ->
                                        movieRootsForTmdb(
                                            media
                                        )

                                    TmdbMediaType.TV ->
                                        tvRootsForTmdb(
                                            media
                                        )
                                }

                            if (
                                media.mediaType ==
                                TmdbMediaType.MOVIE
                            ) {
                                searchMovieTmdbCandidate(
                                    query = query,
                                    media = media,
                                    roots = roots
                                )
                            } else {
                                searchTvTmdbCandidate(
                                    query = query,
                                    media = media,
                                    roots = roots
                                )
                            }
                        }
                    }
                    .awaitAll()
                    .flatten()
            }

        return candidateResults
            .filter {
                maxOf(
                    scoreSearchCandidate(
                        query,
                        it.title
                    ),
                    searchTitleFromNativeResult(
                        it,
                        query
                    )
                ) >= SEARCH_MIN_RESULT_SCORE
            }
            .sortedWith(
                compareByDescending<NativeSearchResult> {
                    /*
                     * Exact/near-exact user-query matches outrank a broader
                     * related TMDB result such as "Doom at Your Service" when
                     * the query is simply "Doom".
                     */
                    scoreSearchCandidate(
                        query,
                        it.title
                    )
                }
                    .thenByDescending {
                        it.score
                    }
                    .thenByDescending {
                        it.modifiedAt
                    }
                    .thenBy {
                        it.title.lowercase(
                            Locale.ROOT
                        )
                    }
            )
            .distinctBy {
                it.url.lowercase(
                    Locale.ROOT
                )
            }
    }

    private fun searchTitleFromNativeResult(
        result: NativeSearchResult,
        query: String
    ): Int {
        return maxOf(
            scoreSearchCandidate(
                query,
                result.title
            ),
            scoreSearchCandidate(
                compactSearchText(query),
                compactSearchText(result.title)
            )
        )
    }

    private suspend fun searchMovieTmdbCandidate(
        query: String,
        media: TmdbMedia,
        roots: List<String>
    ): List<NativeSearchResult> {

        val orderedRoots =
            roots
                .distinct()
                .take(4)

        /*
         * Satyajit Ray has a dedicated Kolkata collection. When TMDB confirms
         * the director, try that collection first. If the collection misses,
         * the normal Kolkata/year route is still attempted.
         */
        val specialRoots =
            if (
                media.isSatyajitRay
            ) {
                findSpecialCollectionRoots(
                    ROOT_KOLKATA,
                    listOf(
                        "Satyajit Ray Films",
                        "Satyajit Ray"
                    )
                )
            } else {
                emptyList()
            }

        val firstRoots =
            specialRoots +
                orderedRoots

        firstRoots
            .distinct()
            .forEach { root ->

                val targetRoot =
                    resolveMovieTargetRoot(
                        categoryRoot = root,
                        year = media.year
                    )

                val searchRoots =
                    listOf(
                        targetRoot,
                        normalizeDirectoryUrl(
                            root
                        )