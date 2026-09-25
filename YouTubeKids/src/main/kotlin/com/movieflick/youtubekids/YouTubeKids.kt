package com.movieflick.youtubekids

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.nicehttp.RequestBodyTypes
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.toRequestBody
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.schabi.newpipe.extractor.InfoItem
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.stream.StreamInfo
import org.schabi.newpipe.extractor.stream.StreamInfoItem
import org.schabi.newpipe.extractor.stream.StreamType
import java.net.URLDecoder
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.random.Random

class YouTubeKids : MainAPI() {

    override var mainUrl = "https://www.youtube.com"
    override var name = "YouTube Kids"
    override var lang = "bn-IN"

    override val hasMainPage = true
    override val hasQuickSearch = true

    override val supportedTypes = setOf(
        TvType.Others,
        TvType.TvSeries
    )

    private val service = ServiceList.YouTube

    /*
     * ============================================================
     * HOME
     * ============================================================
     *
     * The devotional religion row is intentionally separate from general kids music.
     * "Religion". This is a separate Hindu/Sanatan kids-devotional
     * feed, not a generic music feed.
     */

    override val mainPage = mainPageOf(
        "recommended" to "Recommended for Kids",
        "bangla" to "বাংলা Kids",
        "learning" to "Learning",
        "religion" to "Religion",
        "cartoons" to "Cartoons",
        "stories" to "Stories",
        "music" to "Kids Music",
        "animals" to "Animals & Nature",
        "cars" to "Cars, Trucks & Machines",
        "play" to "Play & Create",
        "explore" to "Explore"
    )

    /*
     * ============================================================
     * PERFORMANCE / CACHE
     * ============================================================
     */

    private companion object {
        const val FAST_VISIBLE_COUNT = 6
        const val FULL_CACHE_COUNT = 40

        const val CACHE_TTL_MS = 15 * 60 * 1000L
        const val FAST_TOTAL_TIMEOUT_MS = 2_200L
        const val FAST_QUERY_TIMEOUT_MS = 1_700L
        const val BACKGROUND_QUERY_TIMEOUT_MS = 8_000L
        const val BACKGROUND_SECTION_STAGGER_MS = 150L

        // Give CloudStream time to paint the first screen before the
        // all-category background worker starts.
        const val BACKGROUND_START_DELAY_MS = 1_200L

        const val WEB_USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) " +
                "AppleWebKit/537.36 (KHTML, like Gecko) " +
                "Chrome/131.0.0.0 Safari/537.36"

        const val TVHTML5_USER_AGENT =
            "Mozilla/5.0 (ChromiumStylePlatform) " +
                "Cobalt/Version"

        val PREWARM_STARTED = AtomicBoolean(false)

        /*
         * Specialized sections run before Recommended in the
         * background owner pass. This reduces cross-category
         * duplication because specific content gets claimed by the
         * specific row before the broad Recommended row is enriched.
         */
        val BACKGROUND_SECTION_ORDER = listOf(
            "bangla",
            "learning",
            "religion",
            "cartoons",
            "stories",
            "music",
            "animals",
            "cars",
            "play",
            "explore",
            "recommended"
        )
    }

    private val backgroundScope =
        CoroutineScope(
            SupervisorJob() + Dispatchers.IO
        )

    private data class CachedSection(
        val expiresAt: Long,
        val items: List<SearchResponse>
    )

    private data class ProgressiveCandidate(
        val section: String,
        val id: String,
        val score: Int,
        val item: StreamInfoItem
    )

    private val cache =
        ConcurrentHashMap<String, CachedSection>()

    private val refreshRunning =
        ConcurrentHashMap<String, Boolean>()

    private val backgroundProgress =
        ConcurrentHashMap<String, Int>()

    /* Per-section serialization prevents background and on-demand pagination
     * from fetching the same query simultaneously. */
    private val sectionLocks =
        ConcurrentHashMap<String, Mutex>()

    private fun sectionLock(section: String): Mutex =
        sectionLocks.getOrPut(section) { Mutex() }

    /*
     * ============================================================
     * CHANNEL SIGNALS
     * ============================================================
     */

    private val preferredBanglaChannels = listOf(
        "Shemaroo Bangla",
        "Sony AATH",
        "Green Gold",
        "Motu Patlu Bangla",
        "ChuChu TV Bangla",
        "Kiddiestv Bangla",
        "Bangla Kids",
        "Bangla Rhymes",
        "Bangla Nursery Rhymes",
        "শিশুদের গান",
        "শিশুদের ছড়া",
        "বাংলা কার্টুন"
    )

    private val preferredKidsChannels = listOf(
        "Cocomelon",
        "CoComelon",
        "Super Simple Songs",
        "Little Baby Bum",
        "Pinkfong",
        "Baby Shark",
        "ChuChu TV",
        "Peppa Pig",
        "Blippi",
        "Sesame Street",
        "Pocoyo",
        "Numberblocks",
        "Alphablocks",
        "Khan Academy Kids",
        "PBS Kids",
        "Daniel Tiger",
        "Bluey",
        "Thomas & Friends",
        "Thomas and Friends",
        "Gecko's Garage",
        "Gracie's Corner",
        "Ms Rachel",
        "Mother Goose Club",
        "Dave and Ava",
        "Bebefinn",
        "BabyBus",
        "LooLoo Kids",
        "Masha and the Bear",
        "WildBrain Kids"
    )

    /*
     * ============================================================
     * CONTENT SAFETY
     * ============================================================
     */

    private val unsafeSignals = listOf(
        "18+",
        "adult",
        "nsfw",
        "violence",
        "violent",
        "fight",
        "fighting",
        "weapon",
        "weapons",
        "gun",
        "guns",
        "blood",
        "murder",
        "crime",
        "horror",
        "scary",
        "scary movie",
        "terror",
        "terrorist",
        "war",
        "death",
        "dead",
        "kill",
        "killing",
        "drugs",
        "alcohol",
        "beer",
        "casino",
        "gambling",
        "dating",
        "sexy",
        "sex",
        "xxx",
        "porn",
        "prank",
        "reaction",
        "roast",
        "politics",
        "news",
        "breaking news",
        "true crime",
        "podcast",
        "vlog",
        "live stream",
        "shorts",
        "short video"
    )

    private val lowQualitySignals = listOf(
        "reupload",
        "re-upload",
        "fan made",
        "fanmade",
        "mega compilation",
        "24/7",
        "full movie",
        "movie explained",
        "episode explained",
        "reaction",
        "review",
        "commentary",
        "clickbait"
    )

    private val ageSignals = listOf(
        "kids",
        "kid",
        "children",
        "child",
        "toddler",
        "preschool",
        "preschoolers",
        "nursery",
        "rhymes",
        "rhyme",
        "abc",
        "alphabet",
        "phonics",
        "numbers",
        "counting",
        "learn",
        "learning",
        "educational",
        "education",
        "colors",
        "colours",
        "shapes",
        "animals",
        "cars",
        "trucks",
        "vehicles",
        "cartoon",
        "cartoons",
        "animation",
        "animated",
        "story",
        "stories",
        "bedtime",
        "fairy tale",
        "fairytale",
        "music",
        "song",
        "songs",
        "dance",
        "play",
        "pretend",
        "drawing",
        "craft",
        "science for kids",
        "bangla",
        "bengali",
        "বাংলা",
        "শিশু",
        "বাচ্চা",
        "ছড়া",
        "গান",
        "কার্টুন",
        "গল্প",
        "শেখা"
    )

    /*
     * ============================================================
     * HINDU / SANATAN RELIGION SIGNALS
     * ============================================================
     *
     * This feed is intentionally narrower than ordinary kids music.
     */

    private val religionSignals = listOf(
        "hindu",
        "hinduism",
        "sanatan",
        "sanatana",
        "devotional",
        "devotion",
        "bhajan",
        "bhajans",
        "kirtan",
        "kirtana",
        "mantra",
        "stotram",
        "stotra",
        "vandana",
        "aarti",
        "arti",
        "krishna",
        "radha",
        "govinda",
        "hare krishna",
        "ganesh",
        "ganesha",
        "shiva",
        "shiv",
        "mahadev",
        "om namah shivaya",
        "durga",
        "maa durga",
        "lakshmi",
        "laxmi",
        "saraswati",
        "saraswathi",
        "hanuman",
        "rama",
        "ram",
        "sita",
        "ramayana",
        "ramayan",
        "kali",
        "kalika",
        "jagannath",
        "narayan",
        "vishnu",
        "bal gopal",
        "kanha",
        "murlidhar",
        "অঞ্জলি",
        "ভক্তিগীতি",
        "ভক্তিগান",
        "ভজন",
        "কীর্তন",
        "মন্ত্র",
        "আরতি",
        "আরাধনা",
        "কৃষ্ণ",
        "রাধা",
        "গোবিন্দ",
        "গণেশ",
        "শিব",
        "মহাদেব",
        "ওঁ নমঃ শিবায়",
        "দুর্গা",
        "লক্ষ্মী",
        "সরস্বতী",
        "হনুমান",
        "রাম",
        "সীতা",
        "রামায়ণ",
        "কালী",
        "জগন্নাথ",
        "নারায়ণ",
        "বিষ্ণু"
    )

    /*
     * ============================================================
     * SECTION QUERIES
     * ============================================================
     *
     * Each row has several independent search intents.
     * The first two are the fast path; the remainder are
     * progressively fetched one query at a time in background.
     */

    private val sectionQueries = mapOf(
        "recommended" to listOf(
            "Bangla kids learning cartoons age 3 4 5 6",
            "Bangla kids songs stories learning preschool",
            "Bengali kids educational videos preschool",
            "safe educational cartoons for kids 3 4 5 6",
            "kids animals learning preschool",
            "kids cars trucks machines preschool",
            "kids drawing craft play preschool",
            "kids science space nature preschool"
        ),

        "bangla" to listOf(
            "Bangla kids cartoon",
            "বাংলা শিশুদের গান কার্টুন",
            "বাংলা শিশুদের গল্প",
            "বাংলা শিশুশিক্ষা ABC সংখ্যা",
            "Bangla baby learning preschool",
            "বাংলা শিশুদের ছড়া গান",
            "Bangla kids bedtime story",
            "Bangla preschool educational videos"
        ),

        "learning" to listOf(
            "kids learning ABC phonics age 3 4 5 6",
            "kids learning numbers counting colors shapes preschool",
            "preschool educational videos 3 4 5 6",
            "kids science learning preschool",
            "kids vocabulary learning English preschool",
            "Bangla kids learning ABC numbers",
            "kids problem solving learning preschool",
            "kids general knowledge learning age 3 4 5 6"
        ),

        "religion" to listOf(
            "Hindu devotional songs for kids",
            "Sanatan bhajan children",
            "Krishna bhajan kids",
            "Bal Krishna songs for children",
            "Ganesh bhajan kids",
            "Saraswati vandana kids",
            "Hanuman bhajan children",
            "Shiva bhajan kids",
            "Durga bhajan kids",
            "Lakshmi bhajan kids",
            "Rama bhajan kids",
            "বাংলা হিন্দু শিশুদের ভক্তিগীতি",
            "বাংলা কৃষ্ণের গান শিশুদের",
            "বাংলা গণেশের গান শিশুদের",
            "বাংলা সরস্বতী বন্দনা শিশু"
        ),

        "cartoons" to listOf(
            "Bangla kids cartoon",
            "safe cartoons for preschool kids",
            "educational cartoons age 3 4 5 6",
            "Peppa Pig kids",
            "Pocoyo kids",
            "Bluey kids",
            "Masha and the Bear kids",
            "Bebefinn kids cartoon"
        ),

        "stories" to listOf(
            "Bangla kids stories",
            "Bangla bedtime stories for children",
            "kids story age 3 4 5 6",
            "preschool bedtime stories",
            "fairy tales for kids",
            "educational stories for children",
            "moral stories for kids",
            "Panchatantra stories for children"
        ),

        "music" to listOf(
            "Bangla kids songs",
            "Bangla baby songs",
            "kids songs preschool",
            "Cocomelon kids songs",
            "Super Simple Songs",
            "Pinkfong kids songs",
            "Baby Shark kids",
            "Bebefinn songs kids"
        ),

        "animals" to listOf(
            "animals for kids preschool",
            "animal sounds for kids",
            "Bangla animals for children",
            "wild animals learning kids",
            "farm animals for kids",
            "ocean animals for kids",
            "dinosaur learning kids",
            "birds insects nature for kids"
        ),

        "cars" to listOf(
            "cars trucks machines for kids",
            "construction vehicles for kids",
            "excavator truck tractor kids",
            "toy cars for kids",
            "Gecko's Garage",
            "Thomas and Friends kids",
            "vehicle learning preschool",
            "garbage truck fire truck ambulance kids"
        ),

        "play" to listOf(
            "kids drawing craft preschool",
            "play and learn kids age 3 4 5 6",
            "pretend play kids",
            "kids toys educational play",
            "easy crafts for preschool kids",
            "kids coloring learning",
            "lego building kids preschool",
            "sensory play kids"
        ),

        "explore" to listOf(
            "kids science experiments preschool",
            "space for kids preschool",
            "nature for kids educational",
            "world for kids learning",
            "dinosaurs for kids educational",
            "how things work for kids",
            "weather for kids preschool",
            "simple geography for kids"
        )
    )

    /*
     * ============================================================
     * STARTUP BACKGROUND ENRICHMENT
     * ============================================================
     *
     * Delayed round-robin:
     *   category A query 1
     *   category B query 1
     *   category C query 1
     *   ...
     *   category A query 2
     *   category B query 2
     *   ...
     *
     * This avoids the old failure mode where only one category was
     * allowed to consume all background work first.
     */

    init {
        if (PREWARM_STARTED.compareAndSet(false, true)) {
            backgroundScope.launch {
                delay(BACKGROUND_START_DELAY_MS)
                runRoundRobinBackgroundFill()
            }
        }
    }

    private suspend fun runRoundRobinBackgroundFill() = coroutineScope {
        /*
         * Every category receives its own background worker. They start with
         * a tiny stagger so startup does not create one giant HTTP burst, but
         * after startup they progress independently toward FULL_CACHE_COUNT.
         */
        BACKGROUND_SECTION_ORDER.forEachIndexed { index, section ->
            launch {
                delay(index * BACKGROUND_SECTION_STAGGER_MS)
                runCatching {
                    fillSectionToTarget(
                        section = section,
                        targetCount = FULL_CACHE_COUNT
                    )
                }
            }
        }
    }

    private suspend fun fillSectionToTarget(
        section: String,
        targetCount: Int
    ) {
        val queries =
            sectionQueries[section] ?: return

        while (
            (cache[section]?.items?.size ?: 0) < targetCount
        ) {
            val queryIndex =
                backgroundProgress[section] ?: 0

            if (queryIndex >= queries.size) {
                break
            }

            backgroundFetchOneQuery(
                section = section,
                queryIndex = queryIndex
            )

            kotlinx.coroutines.yield()
        }
    }

    /*
     * ============================================================
     * CACHE
     * ============================================================
     */

    private fun getCached(section: String): List<SearchResponse> {
        val entry = cache[section] ?: return emptyList()

        if (entry.expiresAt <= System.currentTimeMillis()) {
            return emptyList()
        }

        return entry.items.take(FULL_CACHE_COUNT)
    }

    private fun putCached(
        section: String,
        items: List<SearchResponse>
    ) {
        val unique =
            items
                .filter { it.url.isNotBlank() }
                .distinctBy {
                    videoIdFromUrl(it.url) ?: it.url
                }
                .take(FULL_CACHE_COUNT)

        if (unique.isEmpty()) {
            return
        }

        cache[section] =
            CachedSection(
                expiresAt =
                    System.currentTimeMillis() +
                        CACHE_TTL_MS,
                items = unique
            )
    }

    /*
     * ============================================================
     * FAST SEARCH
     * ============================================================
     */

    private suspend fun fetchSearchItemsFast(
        queries: List<String>
    ): List<StreamInfoItem> {
        val clean =
            queries
                .map { it.trim() }
                .filter { it.isNotBlank() }
                .distinct()
                .take(2)

        if (clean.isEmpty()) {
            return emptyList()
        }

        val grouped =
            withTimeoutOrNull(
                FAST_TOTAL_TIMEOUT_MS
            ) {
                coroutineScope {
                    clean.map { query ->
                        async(Dispatchers.IO) {
                            withTimeoutOrNull(
                                FAST_QUERY_TIMEOUT_MS
                            ) {
                                runCatching {
                                    fetchSearchItems(
                                        query = query
                                    )
                                }.getOrElse {
                                    emptyList()
                                }
                            } ?: emptyList()
                        }
                    }.awaitAll()
                }
            } ?: emptyList()

        return grouped
            .flatten()
            .filter { isSupportedStreamItem(it) }
            .distinctBy {
                videoIdFromUrl(it.url) ?: it.url
            }
    }

    private suspend fun fetchSearchItems(
        query: String
    ): List<StreamInfoItem> {
        return runCatching {
            val extractor =
                service.getSearchExtractor(query)

            extractor.fetchPage()

            extractor
                .initialPage
                .items
                .filterIsInstance<StreamInfoItem>()
                .toList()
        }.getOrElse {
            emptyList()
        }
    }

    /*
     * ============================================================
     * BACKGROUND SINGLE-QUERY FETCH
     * ============================================================
     *
     * This is intentionally different from the old "fetch everything,
     * then cache once" design. The cache is updated after each query.
     */

    private suspend fun backgroundFetchOneQuery(
        section: String,
        queryIndex: Int
    ) {
        sectionLock(section).withLock {
            val queries =
                sectionQueries[section] ?: return

            if (queryIndex >= queries.size) {
                return
            }

            val currentProgress =
                backgroundProgress[section] ?: 0

            if (queryIndex < currentProgress) {
                return
            }

            val query =
                queries[queryIndex]

            val fresh =
                withTimeoutOrNull(
                    BACKGROUND_QUERY_TIMEOUT_MS
                ) {
                    fetchSearchItems(query)
                } ?: emptyList()

            val candidates =
                fresh.filter {
                    isSectionCandidate(
                        item = it,
                        section = section
                    )
                }

            mergeCandidatesIntoSection(
                section = section,
                candidates = candidates
            )

            backgroundProgress.compute(section) { _, old ->
                maxOf(
                    old ?: 0,
                    queryIndex + 1
                )
            }
        }
    }

    /*
     * ============================================================
     * SECTION CANDIDATE FILTERING
     * ============================================================
     */

    private fun isSupportedStreamItem(
        item: StreamInfoItem
    ): Boolean {
        if (
            item.streamType !=
            StreamType.VIDEO_STREAM
        ) {
            return false
        }

        if (item.isShortFormContent) {
            return false
        }

        val title =
            item.name
                ?.trim()
                .orEmpty()

        if (title.isBlank()) {
            return false
        }

        return true
    }

    private fun isSectionCandidate(
        item: StreamInfoItem,
        section: String
    ): Boolean {
        if (!isSupportedStreamItem(item)) {
            return false
        }

        val title =
            item.name
                ?.trim()
                .orEmpty()

        val uploader =
            item.uploaderName
                ?.trim()
                .orEmpty()

        val combined =
            "$title $uploader"
                .lowercase()

        if (containsAny(combined, unsafeSignals)) {
            return false
        }

        if (containsAny(combined, lowQualitySignals)) {
            return false
        }

        val duration =
            item.duration

        if (duration in 1L..25L) {
            return false
        }

        if (duration > 60L * 60L) {
            return false
        }

        return when (section) {
            "religion" ->
                isReligionCandidate(
                    combined = combined,
                    duration = duration
                )

            "learning" ->
                isKidsCandidateGeneral(
                    combined = combined,
                    uploader = uploader
                ) &&
                    (
                        containsAny(
                            combined,
                            listOf(
                                "learn",
                                "learning",
                                "education",
                                "educational",
                                "abc",
                                "alphabet",
                                "phonics",
                                "numbers",
                                "counting",
                                "colors",
                                "colours",
                                "shapes",
                                "science"
                            )
                        ) ||
                            preferredKids(uploader)
                    )

            "cartoons" ->
                isKidsCandidateGeneral(
                    combined = combined,
                    uploader = uploader
                ) &&
                    (
                        containsAny(
                            combined,
                            listOf(
                                "cartoon",
                                "cartoons",
                                "animation",
                                "animated",
                                "peppa",
                                "pocoyo",
                                "bluey",
                                "masha",
                                "bebefinn"
                            )
                        ) ||
                            preferredKids(uploader)
                    )

            "stories" ->
                isKidsCandidateGeneral(
                    combined = combined,
                    uploader = uploader
                ) &&
                    containsAny(
                        combined,
                        listOf(
                            "story",
                            "stories",
                            "bedtime",
                            "fairy tale",
                            "fairytale",
                            "panchatantra",
                            "moral story"
                        )
                    )

            "animals" ->
                isKidsCandidateGeneral(
                    combined = combined,
                    uploader = uploader
                ) &&
                    containsAny(
                        combined,
                        listOf(
                            "animal",
                            "animals",
                            "dinosaur",
                            "farm",
                            "ocean",
                            "wildlife",
                            "bird",
                            "birds",
                            "nature"
                        )
                    )

            "cars" ->
                isKidsCandidateGeneral(
                    combined = combined,
                    uploader = uploader
                ) &&
                    containsAny(
                        combined,
                        listOf(
                            "car",
                            "cars",
                            "truck",
                            "trucks",
                            "tractor",
                            "excavator",
                            "construction",
                            "vehicle",
                            "ambulance",
                            "fire truck",
                            "garbage truck"
                        )
                    )

            "play" ->
                isKidsCandidateGeneral(
                    combined = combined,
                    uploader = uploader
                ) &&
                    containsAny(
                        combined,
                        listOf(
                            "play",
                            "drawing",
                            "craft",
                            "coloring",
                            "colouring",
                            "toy",
                            "lego",
                            "sensory"
                        )
                    )

            "explore" ->
                isKidsCandidateGeneral(
                    combined = combined,
                    uploader = uploader
                ) &&
                    containsAny(
                        combined,
                        listOf(
                            "science",
                            "space",
                            "nature",
                            "dinosaur",
                            "how things work",
                            "weather",
                            "geography"
                        )
                    )

            "music" ->
                isKidsCandidateGeneral(
                    combined = combined,
                    uploader = uploader
                ) &&
                    containsAny(
                        combined,
                        listOf(
                            "song",
                            "songs",
                            "music",
                            "sing",
                            "dance"
                        )
                    )

            "bangla" ->
                containsAny(
                    combined,
                    listOf(
                        "bangla",
                        "bengali",
                        "বাংলা",
                        "শিশু",
                        "বাচ্চা"
                    )
                ) &&
                    isKidsCandidateGeneral(
                        combined = combined,
                        uploader = uploader
                    )

            "recommended" ->
                isKidsCandidateGeneral(
                    combined = combined,
                    uploader = uploader
                )

            else ->
                isKidsCandidateGeneral(
                    combined = combined,
                    uploader = uploader
                )
        }
    }

    private fun isReligionCandidate(
        combined: String,
        duration: Long
    ): Boolean {
        if (!containsAny(combined, religionSignals)) {
            return false
        }

        /*
         * Religion items are allowed to omit words such as "kids"
         * because many genuine child-friendly bhajans do not put
         * "kids" in the title. Safety and duration are still enforced.
         */
        if (duration > 60L * 60L) {
            return false
        }

        return true
    }

    private fun isKidsCandidateGeneral(
        combined: String,
        uploader: String
    ): Boolean {
        return containsAny(
            combined,
            ageSignals
        ) ||
            preferredKids(uploader) ||
            preferredBangla(uploader)
    }

    /*
     * ============================================================
     * SCORING
     * ============================================================
     */

    private fun scoreItem(
        item: StreamInfoItem,
        section: String
    ): Int {
        val title =
            item.name
                ?.trim()
                .orEmpty()

        val uploader =
            item.uploaderName
                ?.trim()
                .orEmpty()

        val combined =
            "$title $uploader"
                .lowercase()

        var score = 100

        if (
            containsAny(
                combined,
                listOf(
                    "bangla",
                    "bengali",
                    "বাংলা"
                )
            )
        ) {
            score += 90
        }

        if (preferredBangla(uploader)) {
            score += 180
        }

        if (preferredKids(uploader)) {
            score += 140
        }

        if (
            containsAny(
                combined,
                listOf(
                    "preschool",
                    "toddler",
                    "3 year old",
                    "4 year old",
                    "5 year old",
                    "6 year old",
                    "ages 3",
                    "ages 4",
                    "ages 5",
                    "ages 6"
                )
            )
        ) {
            score += 80
        }

        when (section) {
            "religion" -> {
                if (
                    containsAny(
                        combined,
                        religionSignals
                    )
                ) {
                    score += 350
                }

                if (
                    containsAny(
                        combined,
                        listOf(
                            "kids",
                            "children",
                            "child",
                            "bal",
                            "baby"
                        )
                    )
                ) {
                    score += 80
                }
            }

            "learning" -> {
                if (
                    containsAny(
                        combined,
                        listOf(
                            "learn",
                            "learning",
                            "education",
                            "educational",
                            "abc",
                            "alphabet",
                            "phonics",
                            "numbers",
                            "counting",
                            "colors",
                            "colours",
                            "shapes",
                            "science"
                        )
                    )
                ) {
                    score += 220
                }
            }

            "cartoons" -> {
                if (
                    containsAny(
                        combined,
                        listOf(
                            "cartoon",
                            "cartoons",
                            "animation",
                            "animated",
                            "peppa",
                            "pocoyo",
                            "bluey",
                            "masha",
                            "bebefinn"
                        )
                    )
                ) {
                    score += 220
                }
            }

            "stories" -> {
                if (
                    containsAny(
                        combined,
                        listOf(
                            "story",
                            "stories",
                            "bedtime",
                            "fairy tale",
                            "fairytale",
                            "panchatantra",
                            "moral story"
                        )
                    )
                ) {
                    score += 220
                }
            }

            "music" -> {
                if (
                    containsAny(
                        combined,
                        listOf(
                            "song",
                            "songs",
                            "music",
                            "sing",
                            "dance"
                        )
                    )
                ) {
                    score += 180
                }
            }

            "animals" -> {
                if (
                    containsAny(
                        combined,
                        listOf(
                            "animal",
                            "animals",
                            "dinosaur",
                            "farm",
                            "ocean",
                            "wildlife",
                            "birds",
                            "nature"
                        )
                    )
                ) {
                    score += 220
                }
            }

            "cars" -> {
                if (
                    containsAny(
                        combined,
                        listOf(
                            "car",
                            "cars",
                            "truck",
                            "trucks",
                            "tractor",
                            "excavator",
                            "construction",
                            "vehicle",
                            "ambulance",
                            "fire truck",
                            "garbage truck"
                        )
                    )
                ) {
                    score += 220
                }
            }

            "play" -> {
                if (
                    containsAny(
                        combined,
                        listOf(
                            "play",
                            "drawing",
                            "craft",
                            "coloring",
                            "colouring",
                            "toy",
                            "lego",
                            "sensory"
                        )
                    )
                ) {
                    score += 220
                }
            }

            "explore" -> {
                if (
                    containsAny(
                        combined,
                        listOf(
                            "science",
                            "space",
                            "nature",
                            "dinosaur",
                            "how things work",
                            "weather",
                            "geography"
                        )
                    )
                ) {
                    score += 220
                }
            }

            "bangla" -> {
                score +=
                    if (
                        containsAny(
                            combined,
                            listOf(
                                "bangla",
                                "bengali",
                                "বাংলা"
                            )
                        )
                    ) {
                        220
                    } else {
                        0
                    }
            }

            "recommended" -> {
                /*
                 * Broad feed: do not over-reward highly specialized
                 * titles. Specialized rows get first ownership in the
                 * background round-robin.
                 */
                if (
                    containsAny(
                        combined,
                        listOf(
                            "learning",
                            "cartoon",
                            "story",
                            "bhajan",
                            "krishna",
                            "ganesh",
                            "shiva",
                            "durga",
                            "craft",
                            "vehicle"
                        )
                    )
                ) {
                    score -= 25
                }
            }
        }

        val duration =
            item.duration

        if (duration in 60L..12L * 60L) {
            score += 35
        }

        if (duration in 12L * 60L..30L * 60L) {
            score += 20
        }

        return score
    }

    /*
     * ============================================================
     * OWNERSHIP / DEDUP
     * ============================================================
     */

    private fun mergeCandidatesIntoSection(
        section: String,
        candidates: List<StreamInfoItem>
    ) {
        if (candidates.isEmpty()) {
            return
        }

        val existingResponses =
            cache[section]
                ?.items
                .orEmpty()

        val existingIds =
            existingResponses
                .mapNotNull {
                    videoIdFromUrl(it.url)
                }
                .toSet()

        /*
         * De-duplicate inside the category only. A video appearing in one
         * category must NOT consume that same video's slot in another category;
         * otherwise overlapping YouTube search results can prevent entire rows
         * from ever reaching the requested 40-item cache.
         */
        val available =
            candidates
                .filter {
                    val id =
                        videoIdFromUrl(it.url)

                    id != null &&
                        id !in existingIds
                }
                .sortedByDescending {
                    scoreItem(
                        item = it,
                        section = section
                    )
                }

        if (available.isEmpty()) {
            return
        }

        val newItems =
            available
                .take(
                    maxOf(
                        0,
                        FULL_CACHE_COUNT -
                            existingResponses.size
                    )
                )

        val newResponses =
            newItems.mapNotNull {
                infoItemToSearchResponse(it)
            }

        if (newResponses.isEmpty()) {
            return
        }

        val merged =
            existingResponses + newResponses

        val unique =
            merged.distinctBy {
                videoIdFromUrl(it.url) ?: it.url
            }

        putCached(
            section = section,
            items = unique
        )
    }

    private fun rotateSearchResponses(
        items: List<SearchResponse>,
        section: String
    ): List<SearchResponse> {
        if (items.size <= FAST_VISIBLE_COUNT) {
            return items.take(FULL_CACHE_COUNT)
        }

        val stableCount =
            minOf(
                4,
                items.size
            )

        val stable =
            items.take(stableCount)

        val rotating =
            items
                .drop(stableCount)
                .toMutableList()

        val bucket =
            System.currentTimeMillis() /
                (10 * 60 * 1000L)

        rotating.shuffle(
            Random(
                section.hashCode()
                    .toLong()
                    xor bucket
            )
        )

        return (
            stable + rotating
            ).take(FULL_CACHE_COUNT)
    }

    /*
     * ============================================================
     * FAST SECTION BUILD
     * ============================================================
     */

    private suspend fun buildFastSection(
        section: String
    ): HomePageResponse {
        val queries =
            sectionQueries[section]
                ?: return emptyHomePage()

        val items =
            fetchSearchItemsFast(queries)
                .filter {
                    isSectionCandidate(
                        item = it,
                        section = section
                    )
                }
                .distinctBy {
                    videoIdFromUrl(it.url) ?: it.url
                }
                .sortedByDescending {
                    scoreItem(
                        item = it,
                        section = section
                    )
                }

        val selected =
            rotateResults(
                ranked = items,
                section = section
            ).take(FAST_VISIBLE_COUNT)

        val results =
            selected.mapNotNull {
                infoItemToSearchResponse(it)
            }

        if (results.isNotEmpty()) {
            putCached(
                section = section,
                items = results
            )
        }

        /*
         * The first two search intents have now been consumed by the
         * fast path. Background starts from query #3.
         */
        backgroundProgress[section] = 2

        return newHomePageResponse(
            listOf(
                HomePageList(
                    sectionDisplayName(section),
                    results,
                    false
                )
            ),
            false
        )
    }

    /*
     * ============================================================
     * ROTATION
     * ============================================================
     */

    private fun rotateResults(
        ranked: List<StreamInfoItem>,
        section: String
    ): List<StreamInfoItem> {
        if (ranked.size <= FAST_VISIBLE_COUNT) {
            return ranked
        }

        val stableCount =
            minOf(
                4,
                ranked.size
            )

        val stable =
            ranked.take(stableCount)

        val rotating =
            ranked
                .drop(stableCount)
                .toMutableList()

        val bucket =
            System.currentTimeMillis() /
                (10 * 60 * 1000L)

        rotating.shuffle(
            Random(
                section.hashCode()
                    .toLong()
                    xor bucket
            )
        )

        return stable + rotating
    }

    /*
     * ============================================================
     * HOME PAGE
     * ============================================================
     */

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        val section =
            request.data

        if (page <= 1) {
            val cached =
                getCached(section)

            if (cached.isEmpty()) {
                val fast =
                    buildFastSection(section)

                startSectionBackgroundRefresh(section)
                return fast
            }

            startSectionBackgroundRefresh(section)

            return newHomePageResponse(
                request.name,
                cached.take(FAST_VISIBLE_COUNT),
                cached.size > FAST_VISIBLE_COUNT
            )
        }

        /*
         * Native CloudStream home pagination: page 2, 3, ... each returns the
         * next six cached items. Background workers normally fill all 40 items
         * before the user reaches them, while early scrolling can still trigger
         * a small on-demand fill instead of showing an empty row.
         */
        val requiredCount =
            page.coerceAtLeast(1) * FAST_VISIBLE_COUNT

        if (
            (cache[section]?.items?.size ?: 0) < requiredCount
        ) {
            runCatching {
                fillSectionToTarget(
                    section = section,
                    targetCount = minOf(
                        requiredCount,
                        FULL_CACHE_COUNT
                    )
                )
            }
        }

        val cached =
            getCached(section)

        val startIndex =
            (page - 1) * FAST_VISIBLE_COUNT

        val pageItems =
            cached
                .drop(startIndex)
                .take(FAST_VISIBLE_COUNT)

        startSectionBackgroundRefresh(section)

        val hasMore =
            cached.size > startIndex + pageItems.size ||
                (backgroundProgress[section] ?: 0) <
                    (sectionQueries[section]?.size ?: 0)

        return newHomePageResponse(
            request.name,
            pageItems,
            hasMore
        )
    }

    private fun startSectionBackgroundRefresh(
        section: String
    ) {
        if (
            sectionQueries[section].isNullOrEmpty()
        ) {
            return
        }

        if (
            refreshRunning.putIfAbsent(
                section,
                true
            ) != null
        ) {
            return
        }

        backgroundScope.launch {
            try {
                fillSectionToTarget(
                    section = section,
                    targetCount = FULL_CACHE_COUNT
                )
            } catch (_: Exception) {
                // Background work must never break the visible UI.
            } finally {
                refreshRunning.remove(section)
            }
        }
    }

    private fun emptyHomePage(): HomePageResponse {
        return newHomePageResponse(
            emptyList(),
            false
        )
    }

    /*
     * ============================================================
     * SEARCH
     * ============================================================
     */

    private val searchPageCache =
        ConcurrentHashMap<
            String,
            org.schabi.newpipe.extractor.Page?
            >()

    override suspend fun search(
        query: String,
        page: Int
    ): SearchResponseList {
        val clean =
            query.trim()

        if (clean.isBlank()) {
            return newSearchResponseList(
                emptyList(),
                false
            )
        }

        val cacheKey =
            clean.lowercase()

        val extractor =
            try {
                service.getSearchExtractor(
                    clean
                )
            } catch (_: Exception) {
                return newSearchResponseList(
                    emptyList(),
                    false
                )
            }

        val pageData =
            try {
                if (
                    page == 1 ||
                    !searchPageCache.containsKey(cacheKey)
                ) {
                    extractor.fetchPage()

                    extractor
                        .initialPage
                        .also {
                            searchPageCache[
                                cacheKey
                            ] =
                                it.nextPage
                        }
                } else {
                    val next =
                        searchPageCache[
                            cacheKey
                        ]
                            ?: return newSearchResponseList(
                                emptyList(),
                                false
                            )

                    extractor
                        .getPage(next)
                        .also {
                            searchPageCache[
                                cacheKey
                            ] =
                                it.nextPage
                        }
                }
            } catch (_: Exception) {
                return newSearchResponseList(
                    emptyList(),
                    false
                )
            }

        val results =
            pageData
                .items
                .filterIsInstance<StreamInfoItem>()
                .filter {
                    isKidsCandidateForSearch(it)
                }
                .mapNotNull {
                    infoItemToSearchResponse(it)
                }

        return newSearchResponseList(
            results,
            pageData.hasNextPage()
        )
    }

    private fun isKidsCandidateForSearch(
        item: StreamInfoItem
    ): Boolean {
        if (!isSupportedStreamItem(item)) {
            return false
        }

        val title =
            item.name
                ?.trim()
                .orEmpty()

        val uploader =
            item.uploaderName
                ?.trim()
                .orEmpty()

        val combined =
            "$title $uploader"
                .lowercase()

        if (containsAny(combined, unsafeSignals)) {
            return false
        }

        if (containsAny(combined, lowQualitySignals)) {
            return false
        }

        return isKidsCandidateGeneral(
            combined = combined,
            uploader = uploader
        )
    }

    override suspend fun quickSearch(
        query: String
    ): List<SearchResponse> {
        return search(
            query,
            1
        ).items
    }

    /*
     * ============================================================
     * INFO ITEM -> SEARCH RESPONSE
     * ============================================================
     *
     * IMPORTANT:
     * Home/search cards now store a canonical YouTube watch URL.
     * This prevents player failures caused by short or parameterized
     * search-result URLs.
     */

    private fun infoItemToSearchResponse(
        item: StreamInfoItem
    ): SearchResponse? {
        val title =
            item.name
                ?.trim()
                ?: return null

        val rawUrl =
            item.url
                ?.trim()
                ?: return null

        val url =
            canonicalYouTubeUrl(
                rawUrl
            )

        if (
            title.isBlank() ||
            url.isBlank()
        ) {
            return null
        }

        return newMovieSearchResponse(
            title,
            url,
            TvType.Others
        ) {
            posterUrl =
                item.thumbnails
                    .lastOrNull()
                    ?.url
                    ?.takeIf {
                        it.isNotBlank()
                    }
        }
    }

    /*
     * ============================================================
     * LOAD
     * ============================================================
     */

    override suspend fun load(
        url: String
    ): LoadResponse {
        return when {
            isVideoUrl(url) ->
                loadVideo(
                    canonicalYouTubeUrl(url)
                )

            isChannelUrl(url) ->
                loadChannel(url)

            isPlaylistUrl(url) ->
                loadPlaylist(url)

            else ->
                throw RuntimeException(
                    "Unsupported YouTube Kids URL"
                )
        }
    }

    private fun isVideoUrl(
        url: String
    ): Boolean {
        return videoIdFromUrl(url) != null
    }

    private fun isChannelUrl(
        url: String
    ): Boolean {
        val value =
            url.lowercase()

        return value.contains("/channel/") ||
            value.contains("/@") ||
            value.contains("/c/") ||
            value.contains("/user/")
    }

    private fun isPlaylistUrl(
        url: String
    ): Boolean {
        return url
            .lowercase()
            .contains(
                "/playlist?list="
            )
    }

    /*
     * ============================================================
     * VIDEO LOAD
     * ============================================================
     */

    private suspend fun loadVideo(
        url: String
    ): LoadResponse {
        val clean =
            canonicalYouTubeUrl(url)

        val extractor =
            service.getStreamExtractor(
                clean
            )

        extractor.fetchPage()

        val info =
            StreamInfo.getInfo(
                extractor
            )

        val isLive =
            info.streamType
                ?.name
                ?.contains(
                    "LIVE",
                    ignoreCase = true
                ) == true

        return newMovieLoadResponse(
            info.name,
            clean,
            if (isLive) {
                TvType.Live
            } else {
                TvType.Others
            },
            clean
        ) {
            plot =
                info.description
                    .content
                    .toString()

            posterUrl =
                info.thumbnails
                    .lastOrNull()
                    ?.url

            if (info.duration > 0) {
                duration =
                    info.duration
                        .toInt()
            }

            info.uploaderName
                ?.takeIf {
                    it.isNotBlank()
                }
                ?.let { uploader ->
                    actors =
                        listOf(
                            ActorData(
                                Actor(
                                    uploader,
                                    info.uploaderAvatars
                                        .lastOrNull()
                                        ?.url
                                        ?: ""
                                )
                            )
                        )
                }

            tags =
                listOf(
                    "Kids",
                    "Age 3-6"
                )
        }
    }

    /*
     * ============================================================
     * CHANNEL
     * ============================================================
     */

    private suspend fun loadChannel(
        url: String
    ): LoadResponse {
        val extractor =
            service.getChannelExtractor(
                url
            )

        extractor.fetchPage()

        val channelName =
            extractor.name

        val description =
            extractor.description

        val avatar =
            extractor.avatars
                .lastOrNull()
                ?.url

        val banner =
            extractor.banners
                .lastOrNull()
                ?.url

        val videosTab =
            extractor.tabs
                .firstOrNull {
                    it.url.contains(
                        "/videos"
                    )
                }
                ?: extractor.tabs.firstOrNull()
                ?: throw RuntimeException(
                    "No videos tab found"
                )

        val videosExtractor =
            service.getChannelTabExtractor(
                videosTab
            )

        val episodes =
            mutableListOf<Episode>()

        var pageData =
            videosExtractor.initialPage

        var pages = 0

        while (pages < 5) {
            pageData
                .items
                .filterIsInstance<StreamInfoItem>()
                .filter {
                    isKidsCandidateForSearch(it)
                }
                .forEach { item ->
                    val itemUrl =
                        item.url
                            ?.trim()
                            .orEmpty()

                    if (itemUrl.isNotBlank()) {
                        episodes.add(
                            newEpisode(
                                canonicalYouTubeUrl(
                                    itemUrl
                                )
                            ) {
                                name =
                                    item.name

                                posterUrl =
                                    item.thumbnails
                                        .lastOrNull()
                                        ?.url
                            }
                        )
                    }
                }

            if (
                !pageData.hasNextPage()
            ) {
                break
            }

            pageData =
                videosExtractor.getPage(
                    pageData.nextPage
                )

            pages++
        }

        return newTvSeriesLoadResponse(
            channelName,
            url,
            TvType.TvSeries,
            episodes
        ) {
            plot =
                description

            posterUrl =
                banner
                    ?: avatar

            backgroundPosterUrl =
                banner

            tags =
                listOf(
                    "Kids",
                    "Age 3-6",
                    "Channel"
                )

            actors =
                listOf(
                    ActorData(
                        Actor(
                            channelName,
                            avatar ?: ""
                        )
                    )
                )
        }
    }

    /*
     * ============================================================
     * PLAYLIST
     * ============================================================
     */

    private suspend fun loadPlaylist(
        url: String
    ): LoadResponse {
        val extractor =
            service.getPlaylistExtractor(
                url
            )

        extractor.fetchPage()

        val playlistName =
            extractor.name

        val description =
            extractor.description
                .content
                .toString()

        val thumbnail =
            extractor.thumbnails
                .lastOrNull()
                ?.url

        val uploader =
            extractor.uploaderName

        val episodes =
            mutableListOf<Episode>()

        var pageData =
            extractor.getInitialPage()

        var pages = 0

        while (pages < 10) {
            pageData
                .items
                .filterIsInstance<StreamInfoItem>()
                .filter {
                    isKidsCandidateForSearch(it)
                }
                .forEach { item ->
                    val itemUrl =
                        item.url
                            ?.trim()
                            .orEmpty()

                    if (itemUrl.isNotBlank()) {
                        episodes.add(
                            newEpisode(
                                canonicalYouTubeUrl(
                                    itemUrl
                                )
                            ) {
                                name =
                                    item.name

                                posterUrl =
                                    item.thumbnails
                                        .lastOrNull()
                                        ?.url
                            }
                        )
                    }
                }

            if (
                !pageData.hasNextPage()
            ) {
                break
            }

            pageData =
                extractor.getPage(
                    pageData.nextPage
                )

            pages++
        }

        return newTvSeriesLoadResponse(
            playlistName,
            url,
            TvType.TvSeries,
            episodes
        ) {
            plot =
                description

            posterUrl =
                thumbnail

            tags =
                listOf(
                    "Kids",
                    "Age 3-6",
                    "Playlist"
                )

            if (uploader.isNotBlank()) {
                actors =
                    listOf(
                        ActorData(
                            Actor(
                                uploader,
                                extractor
                                    .uploaderAvatars
                                    .lastOrNull()
                                    ?.url
                                    ?: ""
                            )
                        )
                    )
            }
        }
    }

    /*
     * ============================================================
     * PLAYBACK
     * ============================================================
     *
     * There are now THREE independent layers:
     *
     *   1) Delegate to the already-working main YouTube provider.
     *   2) Direct NewPipe extraction in this provider.
     *      - LIVE HLS
     *      - DASH first for VOD
     *      - direct per-resolution VIDEO links
     *      - HLS fallback
     *   3) CloudStream's registered YouTube extractors.
     *
     * Stream URLs are always resolved when the user clicks Play.
     * No signed media URL is stored in the home cache.
     */

    /*
     * ============================================================
     * YOUTUBE HIGH-QUALITY PLAYBACK
     * ============================================================
     *
     * The important part of this implementation is that it does NOT
     * depend on NewPipe's current YouTube stream lists for high quality.
     *
     * YouTube has been moving more playback traffic to SABR / client-
     * specific responses. In that situation an extractor can appear to
     * work perfectly while exposing only the legacy 360p muxed stream.
     *
     * We therefore ask YouTube's InnerTube player endpoint directly with
     * several compatible clients, collect every direct video/audio format
     * that is actually returned, and publish one CloudStream source per
     * video height.
     *
     * Result:
     *   source contains 360p/480p/720p/1080p/1440p/2160p/4320p
     *   only when that exact resolution exists for this video.
     *
     * Adaptive video-only formats receive CloudStream AudioFile tracks,
     * so HD/4K/8K video can keep normal sound.
     */

    private data class InnerTubeClient(
        val name: String,
        val version: String,
        val userAgent: String,
        val isEmbedded: Boolean = false
    )

    private data class InnerTubeVideoCandidate(
        val url: String,
        val height: Int,
        val bitrate: Int,
        val hasAudio: Boolean,
        val userAgent: String,
        val clientName: String
    )

    private data class InnerTubeAudioCandidate(
        val url: String,
        val bitrate: Int,
        val userAgent: String
    )

    private data class InnerTubeResult(
        val videoCandidates: List<InnerTubeVideoCandidate>,
        val audioCandidates: List<InnerTubeAudioCandidate>,
        val dashManifestUrl: String?,
        val hadResponse: Boolean
    )

    /*
     * These models intentionally contain only the InnerTube fields used by
     * the provider. CloudStream's parsedSafe() ignores the large number of
     * unrelated player-response fields returned by YouTube.
     */
    private data class InnerTubePlayerResponse(
        val streamingData: InnerTubeStreamingData? = null
    )

    private data class InnerTubeStreamingData(
        val formats: List<InnerTubeFormat>? = null,
        val adaptiveFormats: List<InnerTubeFormat>? = null,
        val dashManifestUrl: String? = null
    )

    private data class InnerTubeFormat(
        val url: String? = null,
        val mimeType: String? = null,
        val width: Int? = null,
        val height: Int? = null,
        val bitrate: Int? = null
    )

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        if (data.isBlank()) {
            return false
        }

        val original = data.trim()
        val canonical = canonicalYouTubeUrl(original)
        val directUrls = linkedSetOf(canonical, original)

        /*
         * PRIMARY PATH
         *
         * Keep the same proven playback architecture as the working
         * YouTube provider: NewPipe -> StreamInfo -> DASH/HLS.
         *
         * The critical resolution source is the DASH MPD. When it exists,
         * publish it directly and stop there. This prevents a legacy 360p
         * progressive/InnerTube source from becoming the preferred source.
         */
        for (videoUrl in directUrls) {
            try {
                val extractor = service.getStreamExtractor(videoUrl)
                extractor.fetchPage()

                val info = StreamInfo.getInfo(extractor)

                val isLive =
                    info.streamType
                        ?.name
                        ?.contains("LIVE", ignoreCase = true) == true

                if (isLive) {
                    val hls =
                        runCatching { info.hlsUrl }
                            .getOrNull()

                    if (!hls.isNullOrBlank()) {
                        callback(
                            newExtractorLink(
                                source = name,
                                name = "$name Live",
                                url = hls,
                                type = ExtractorLinkType.M3U8
                            ) {
                                referer = "https://www.youtube.com/"
                                headers = mapOf(
                                    "User-Agent" to USER_AGENT
                                )
                                quality = Qualities.Unknown.value
                            }
                        )
                        return true
                    }

                    continue
                }

                /*
                 * VOD -> DASH.
                 * This exactly follows the working YouTube provider's
                 * resolution path.
                 */
                val dash =
                    runCatching { info.dashMpdUrl }
                        .getOrNull()

                if (!dash.isNullOrBlank()) {
                    callback(
                        newExtractorLink(
                            source = name,
                            name = "$name Adaptive (YouTube)",
                            url = dash,
                            type = ExtractorLinkType.DASH
                        ) {
                            referer = "https://www.youtube.com/"
                            headers = mapOf(
                                "User-Agent" to USER_AGENT
                            )
                            quality = Qualities.Unknown.value
                        }
                    )
                    return true
                }

                /*
                 * HLS fallback only when the adaptive DASH manifest is not
                 * available for this response.
                 */
                val hls =
                    runCatching { info.hlsUrl }
                        .getOrNull()

                if (!hls.isNullOrBlank()) {
                    callback(
                        newExtractorLink(
                            source = name,
                            name = "$name HLS",
                            url = hls,
                            type = ExtractorLinkType.M3U8
                        ) {
                            referer = "https://www.youtube.com/"
                            headers = mapOf(
                                "User-Agent" to USER_AGENT
                            )
                            quality = Qualities.Unknown.value
                        }
                    )
                    return true
                }
            } catch (_: Exception) {
                // Continue to the next URL/fallback.
            }
        }

        /*
         * SECONDARY RESOLUTION PATH
         *
         * Only reached when NewPipe could not expose a DASH/HLS source.
         * Keep the existing InnerTube implementation from the stable file
         * so direct 1080p/1440p/2160p/4320p streams can still be used when
         * YouTube actually returns them.
         */
        val videoId = videoIdFromUrl(canonical)

        if (!videoId.isNullOrBlank()) {
            val innerTube =
                runCatching {
                    extractInnerTubeFormats(
                        videoId = videoId
                    )
                }.getOrNull()

            if (innerTube != null) {
                val emitted =
                    runCatching {
                        emitInnerTubeResolutionSources(
                            result = innerTube,
                            callback = callback
                        )
                    }.getOrDefault(false)

                if (emitted) {
                    return true
                }
            }
        }

        /*
         * FINAL PLAYBACK FALLBACK
         *
         * Preserve the original provider's CloudStream extractor fallback.
         */
        val fallbackUrls =
            linkedSetOf(
                canonical,
                mobileYouTubeUrl(canonical),
                noCookieYouTubeUrl(canonical),
                original
            )

        for (videoUrl in fallbackUrls) {
            var found = false

            runCatching {
                loadExtractor(
                    videoUrl,
                    subtitleCallback
                ) { link ->
                    found = true
                    callback(link)
                }
            }

            if (found) {
                return true
            }
        }

        return false
    }
    private suspend fun extractInnerTubeFormats(
        videoId: String
    ): InnerTubeResult? {
        /*
         * Read the watch page only to obtain the current public InnerTube
         * key/client version/visitor data. If the page request fails, the
         * player requests still continue with a keyless/default context.
         */
        val page =
            runCatching {
                app.get(
                    "https://www.youtube.com/watch?v=$videoId",
                    headers =
                        mapOf(
                            "User-Agent" to
                                WEB_USER_AGENT,
                            "Accept-Language" to
                                "en-US,en;q=0.9"
                        )
                ).text
            }.getOrNull()

        val apiKey =
            page?.let {
                extractQuotedPageValue(
                    html = it,
                    key = "INNERTUBE_API_KEY"
                )
            }

        val webVersion =
            page?.let {
                extractQuotedPageValue(
                    html = it,
                    key = "INNERTUBE_CLIENT_VERSION"
                )
            }
                ?: "2.20260101.00.00"

        val visitorData =
            page?.let {
                extractQuotedPageValue(
                    html = it,
                    key = "VISITOR_DATA"
                )
            }


        val clients =
            listOf(
                InnerTubeClient(
                    name = "WEB_EMBEDDED_PLAYER",
                    version = webVersion,
                    userAgent = WEB_USER_AGENT,
                    isEmbedded = true
                ),
                InnerTubeClient(
                    name = "TVHTML5",
                    version = "7.20250129.15.00",
                    userAgent = TVHTML5_USER_AGENT
                ),
                InnerTubeClient(
                    name = "WEB",
                    version = webVersion,
                    userAgent = WEB_USER_AGENT
                ),
                InnerTubeClient(
                    name = "ANDROID",
                    version = "21.08.266",
                    userAgent =
                        "com.google.android.youtube/21.08.266 " +
                            "(Linux; U; Android 11) gzip"
                )
            )

        val videoCandidates =
            mutableListOf<InnerTubeVideoCandidate>()

        val audioCandidates =
            mutableListOf<InnerTubeAudioCandidate>()

        var dashManifestUrl:
            String? = null

        var hadResponse =
            false

        for (client in clients) {
            val result =
                runCatching {
                    requestInnerTubePlayer(
                        videoId = videoId,
                        apiKey = apiKey,
                        visitorData = visitorData,
                        client = client
                    )
                }.getOrNull()
                    ?: continue

            hadResponse =
                hadResponse || result.hadResponse

            videoCandidates.addAll(
                result.videoCandidates
            )

            audioCandidates.addAll(
                result.audioCandidates
            )

            if (
                dashManifestUrl.isNullOrBlank() &&
                !result.dashManifestUrl.isNullOrBlank()
            ) {
                dashManifestUrl =
                    result.dashManifestUrl
            }

            /*
             * Do not stop after 1080p. Different InnerTube clients can
             * expose different adaptive representations. All client
             * responses are therefore merged before publishing sources.
             */
        }

        if (
            !hadResponse &&
            videoCandidates.isEmpty() &&
            audioCandidates.isEmpty()
        ) {
            return null
        }

        return InnerTubeResult(
            videoCandidates =
                videoCandidates
                    .distinctBy {
                        Triple(
                            it.url,
                            it.height,
                            it.hasAudio
                        )
                    },
            audioCandidates =
                audioCandidates
                    .distinctBy {
                        it.url
                    },
            dashManifestUrl =
                dashManifestUrl,
            hadResponse =
                hadResponse
        )
    }

    private suspend fun requestInnerTubePlayer(
        videoId: String,
        apiKey: String?,
        visitorData: String?,
        client: InnerTubeClient
    ): InnerTubeResult {
        val endpoint =
            buildString {
                append(
                    "https://www.youtube.com/youtubei/v1/player"
                )
                append(
                    "?prettyPrint=false"
                )

                if (!apiKey.isNullOrBlank()) {
                    append(
                        "&key="
                    )
                    append(
                        java.net.URLEncoder.encode(
                            apiKey,
                            "UTF-8"
                        )
                    )
                }
            }

        val visitorPart =
            if (!visitorData.isNullOrBlank()) {
                ",\"visitorData\":\"${escapeJson(visitorData)}\""
            } else {
                ""
            }

        val embeddedPart =
            if (client.isEmbedded) {
                ",\"clientScreen\":\"EMBED\""
            } else {
                ""
            }

        val thirdPartyPart =
            if (client.isEmbedded) {
                ",\"thirdParty\":{\"embedUrl\":\"https://www.youtube.com/embed/$videoId\"}"
            } else {
                ""
            }

        val payload =
            """
            {
              "context": {
                "client": {
                  "hl": "en",
                  "gl": "IN",
                  "clientName": "${escapeJson(client.name)}",
                  "clientVersion": "${escapeJson(client.version)}"$embeddedPart$visitorPart
                }$thirdPartyPart
              },
              "videoId": "${escapeJson(videoId)}",
              "contentCheckOk": true,
              "racyCheckOk": true,
              "playbackContext": {
                "contentPlaybackContext": {
                  "html5Preference": "HTML5_PREF_WANTS"
                }
              }
            }
            """.trimIndent()

        val body =
            payload.toRequestBody(
                RequestBodyTypes.JSON
                    .toMediaTypeOrNull()
            )

        val response =
            app.post(
                endpoint,
                requestBody = body,
                headers =
                    mapOf(
                        "Content-Type" to
                            "application/json",
                        "User-Agent" to
                            client.userAgent,
                        "Accept-Language" to
                            "en-US,en;q=0.9",
                        "Origin" to
                            "https://www.youtube.com",
                        "Referer" to
                            "https://www.youtube.com/"
                    )
            )

        val parsed =
            runCatching {
                response.parsedSafe<InnerTubePlayerResponse>()
            }.getOrNull()

        val streamingData =
            parsed?.streamingData
                ?: return InnerTubeResult(
                    videoCandidates = emptyList(),
                    audioCandidates = emptyList(),
                    dashManifestUrl = null,
                    hadResponse = true
                )

        val videoCandidates =
            mutableListOf<InnerTubeVideoCandidate>()

        val audioCandidates =
            mutableListOf<InnerTubeAudioCandidate>()

        fun consumeFormats(
            formats: List<InnerTubeFormat>?
        ) {
            for (item in formats.orEmpty()) {
                val url =
                    item.url
                        ?.trim()
                        .orEmpty()

                if (
                    url.isBlank() ||
                    !url.startsWith(
                        "http",
                        ignoreCase = true
                    )
                ) {
                    continue
                }

                val mimeType =
                    item.mimeType
                        ?.trim()
                        .orEmpty()

                val bitrate =
                    item.bitrate
                        ?: 0

                val height =
                    item.height
                        ?: 0

                if (
                    mimeType.startsWith(
                        "audio/",
                        ignoreCase = true
                    )
                ) {
                    audioCandidates.add(
                        InnerTubeAudioCandidate(
                            url = url,
                            bitrate = bitrate,
                            userAgent =
                                client.userAgent
                        )
                    )
                    continue
                }

                if (
                    !mimeType.startsWith(
                        "video/",
                        ignoreCase = true
                    ) ||
                    height <= 0
                ) {
                    continue
                }

                val codecs =
                    mimeType
                        .substringAfter(
                            "codecs=",
                            ""
                        )
                        .lowercase()

                val hasAudio =
                    codecs.contains(
                        "mp4a"
                    ) ||
                        codecs.contains(
                            "opus"
                        ) ||
                        codecs.contains(
                            "vorbis"
                        )

                videoCandidates.add(
                    InnerTubeVideoCandidate(
                        url = url,
                        height = height,
                        bitrate = bitrate,
                        hasAudio = hasAudio,
                        userAgent =
                            client.userAgent,
                        clientName =
                            client.name
                    )
                )
            }
        }

        consumeFormats(
            streamingData.formats
        )

        consumeFormats(
            streamingData.adaptiveFormats
        )

        return InnerTubeResult(
            videoCandidates =
                videoCandidates,
            audioCandidates =
                audioCandidates,
            dashManifestUrl =
                streamingData.dashManifestUrl
                    ?.trim()
                    ?.ifBlank {
                        null
                    },
            hadResponse =
                true
        )
    }

    /*
     * ============================================================
     * PUBLISH INNER TUBE SOURCES
     * ============================================================
     */

    private suspend fun emitInnerTubeResolutionSources(
        result: InnerTubeResult,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        if (
            result.videoCandidates.isEmpty() &&
            result.dashManifestUrl.isNullOrBlank()
        ) {
            return false
        }

        /*
         * Build audio tracks once, preferring the highest bitrate URL
         * per exact URL and limiting duplicates.
         */
        /*
         * IMPORTANT: newAudioFile() is a suspend function. Do not call it
         * from a collection transformation lambda. Build the list inside
         * this suspend function instead so Kotlin can keep the coroutine
         * context correctly.
         */
        val audioFiles =
            mutableListOf<AudioFile>()

        val audioCandidates =
            result.audioCandidates
                .asSequence()
                .sortedByDescending {
                    it.bitrate
                }
                .distinctBy {
                    it.url
                }
                .toList()

        for (audio in audioCandidates) {
            try {
                val audioFile =
                    newAudioFile(
                        audio.url
                    )

                if (audioFiles.none {
                        it.url == audioFile.url
                    }) {
                    audioFiles.add(audioFile)
                }
            } catch (_: Exception) {
                // Ignore a bad audio variant and keep all valid variants.
            }
        }

        val bestPerHeight =
            result.videoCandidates
                .asSequence()
                .filter {
                    it.height > 0
                }
                .groupBy {
                    it.height
                }
                .mapNotNull { (height, sameHeight) ->
                    sameHeight
                        .sortedWith(
                            compareByDescending<InnerTubeVideoCandidate> {
                                it.hasAudio
                            }
                                .thenByDescending {
                                    it.bitrate
                                }
                        )
                        .firstOrNull()
                        ?.let {
                            height to it
                        }
                }
                .sortedBy {
                    it.first
                }
                .toList()

        var emitted =
            false

        for ((height, candidate) in bestPerHeight) {
            /*
             * A video-only format must have a usable audio stream.
             * Muxed formats work without one.
             */
            if (
                !candidate.hasAudio &&
                audioFiles.isEmpty()
            ) {
                continue
            }

            val label =
                when {
                    height >= 4320 ->
                        "8K (${height}p)"

                    height >= 2160 ->
                        "4K (${height}p)"

                    height >= 1440 ->
                        "2K (${height}p)"

                    else ->
                        "${height}p"
                }

            val fullName =
                if (candidate.hasAudio) {
                    "$name $label"
                } else {
                    "$name $label Adaptive"
                }

            callback(
                newExtractorLink(
                    source = name,
                    name = fullName,
                    url = candidate.url,
                    type = ExtractorLinkType.VIDEO
                ) {
                    referer =
                        "https://www.youtube.com/"

                    headers =
                        mapOf(
                            "User-Agent" to
                                candidate.userAgent
                        )

                    quality =
                        height

                    if (!candidate.hasAudio) {
                        this.audioTracks =
                            audioFiles
                    }
                }
            )

            emitted =
                true
        }

        /*
         * If InnerTube supplied a valid adaptive manifest but direct URLs
         * were not exposed, keep the DASH source as a final adaptive path.
         */
        if (
            !emitted &&
            !result.dashManifestUrl.isNullOrBlank()
        ) {
            callback(
                newExtractorLink(
                    source = name,
                    name = "$name Adaptive (YouTube)",
                    url =
                        result.dashManifestUrl,
                    type = ExtractorLinkType.DASH
                ) {
                    referer =
                        "https://www.youtube.com/"

                    headers =
                        mapOf(
                            "User-Agent" to
                                WEB_USER_AGENT
                        )

                    quality =
                        Qualities.Unknown.value
                }
            )

            emitted =
                true
        }

        return emitted
    }

    private fun escapeJson(
        value: String
    ): String {
        return buildString {
            for (char in value) {
                when (char) {
                    '\\' ->
                        append("\\\\")
                    '"' ->
                        append("\\\"")
                    '\n' ->
                        append("\\n")
                    '\r' ->
                        append("\\r")
                    '\t' ->
                        append("\\t")
                    else ->
                        append(char)
                }
            }
        }
    }

    private fun extractQuotedPageValue(
        html: String,
        key: String
    ): String? {
        val patterns =
            listOf(
                Regex(
                    "\"$key\"\\s*:\\s*\"([^\"]+)\""
                ),
                Regex(
                    "\\\"$key\\\"\\s*:\\s*\\\"([^\\\"]+)\\\""
                ),
                Regex(
                    "$key\\s*:\\s*\"([^\"]+)\""
                )
            )

        for (pattern in patterns) {
            val value =
                pattern
                    .find(html)
                    ?.groupValues
                    ?.getOrNull(1)
                    ?.trim()
                    ?.takeIf {
                        it.isNotBlank()
                    }

            if (value != null) {
                return value
            }
        }

        return null
    }

    /*
     * ============================================================
     * URL NORMALIZATION
     * ============================================================
     *
     * Handles:
     *   watch?v=
     *   &v=
     *   youtu.be/
     *   /shorts/
     *   /live/
     *   /embed/
     *   URL-encoded attribution links
     */

    private fun canonicalYouTubeUrl(
        url: String
    ): String {
        var value =
            url.trim()

        if (value.isBlank()) {
            return value
        }

        value =
            runCatching {
                URLDecoder.decode(
                    value,
                    "UTF-8"
                )
            }.getOrElse {
                value
            }

        val id =
            videoIdFromUrl(value)

        return if (
            !id.isNullOrBlank()
        ) {
            "https://www.youtube.com/watch?v=$id"
        } else {
            value
        }
    }

    private fun videoIdFromUrl(
        url: String?
    ): String? {
        if (url.isNullOrBlank()) {
            return null
        }

        var value =
            url.trim()

        value =
            runCatching {
                URLDecoder.decode(
                    value,
                    "UTF-8"
                )
            }.getOrElse {
                value
            }

        val patterns =
            listOf(
                Regex(
                    "[?&]v=([A-Za-z0-9_-]{6,})"
                ),
                Regex(
                    "youtu\\.be/([A-Za-z0-9_-]{6,})"
                ),
                Regex(
                    "/shorts/([A-Za-z0-9_-]{6,})"
                ),
                Regex(
                    "/live/([A-Za-z0-9_-]{6,})"
                ),
                Regex(
                    "/embed/([A-Za-z0-9_-]{6,})"
                )
            )

        for (pattern in patterns) {
            val id =
                pattern
                    .find(value)
                    ?.groupValues
                    ?.getOrNull(1)
                    ?.trim()

            if (
                !id.isNullOrBlank()
            ) {
                return id
            }
        }

        return null
    }

    private fun mobileYouTubeUrl(
        canonical: String
    ): String {
        val id =
            videoIdFromUrl(canonical)
                ?: return canonical

        return "https://m.youtube.com/watch?v=$id"
    }

    private fun noCookieYouTubeUrl(
        canonical: String
    ): String {
        val id =
            videoIdFromUrl(canonical)
                ?: return canonical

        return "https://www.youtube-nocookie.com/embed/$id"
    }

    /*
     * ============================================================
     * BASIC HELPERS
     * ============================================================
     */

    private fun containsAny(
        text: String,
        words: List<String>
    ): Boolean {
        val value =
            text.lowercase()

        return words.any {
            value.contains(
                it.lowercase()
            )
        }
    }

    private fun preferredBangla(
        uploader: String
    ): Boolean {
        return uploader.isNotBlank() &&
            preferredBanglaChannels.any {
                uploader.contains(
                    it,
                    ignoreCase = true
                )
            }
    }

    private fun preferredKids(
        uploader: String
    ): Boolean {
        return uploader.isNotBlank() &&
            preferredKidsChannels.any {
                uploader.contains(
                    it,
                    ignoreCase = true
                )
            }
    }

    private fun sectionDisplayName(
        section: String
    ): String {
        return when (section) {
            "recommended" ->
                "Recommended for Kids"

            "bangla" ->
                "বাংলা Kids"

            "learning" ->
                "Learning"

            "religion" ->
                "Religion"

            "cartoons" ->
                "Cartoons"

            "stories" ->
                "Stories"

            "music" ->
                "Kids Music"

            "animals" ->
                "Animals & Nature"

            "cars" ->
                "Cars, Trucks & Machines"

            "play" ->
                "Play & Create"

            "explore" ->
                "Explore"

            else ->
                section
        }
    }
}
