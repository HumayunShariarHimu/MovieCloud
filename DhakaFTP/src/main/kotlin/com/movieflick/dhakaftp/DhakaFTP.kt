
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
            ?.groupValues