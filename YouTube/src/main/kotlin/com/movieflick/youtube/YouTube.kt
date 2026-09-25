package com.movieflick.youtube

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.schabi.newpipe.extractor.InfoItem
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.kiosk.KioskExtractor
import org.schabi.newpipe.extractor.playlist.PlaylistInfoItem
import org.schabi.newpipe.extractor.stream.StreamInfo
import org.schabi.newpipe.extractor.stream.StreamInfoItem
import org.schabi.newpipe.extractor.stream.StreamType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

class YouTube : MainAPI() {

    override var mainUrl = "https://www.youtube.com"
    override var name = "YouTube"
    override var lang = "hi"

    override val hasMainPage = true
    override val hasQuickSearch = true

    override val supportedTypes = setOf(
        TvType.Others,
        TvType.Live,
        TvType.TvSeries
    )

    private val service = ServiceList.YouTube

    /*
     * --------------------------------------------------
     * HOME
     * --------------------------------------------------
     */

    override val mainPage = mainPageOf(
        "trending_movies_and_shows" to "Movie Trailers",
        "music_india" to "Trending Music Videos",
        "movies" to "Movies",
        "hindi_movies" to "Hindi Movies",
        "live" to "Live",
        "religion" to "Religion"
    )

    /*
     * --------------------------------------------------
     * LIVE CHANNELS
     * --------------------------------------------------
     */

    private val allowedLiveChannels = listOf(

        // Indian Bengali News
        "Republic Bangla",
        "ABP Ananda",
        "News18 Bangla",
        "TV9 Bangla",
        "Kolkata TV",
        "Calcutta News",
        "R Plus News",
        "News Time Bangla",
        "Zee 24 Ghanta",
        "Ei Samay",

        // Bangladeshi Bengali News
        "Jamuna TV",
        "Somoy TV",
        "Ekattor TV",
        "ATN News",
        "Channel 24",
        "DBC News",
        "Independent Television",
        "News24",
        "RTV",
        "NTV",
        "Desh TV",
        "BanglaVision",
        "Nagorik TV",
        "Maasranga TV",

        // Indian Hindi News
        "Aaj Tak",
        "Republic Bharat",
        "ABP News",
        "News18 India",
        "Zee News",
        "TV9 Bharatvarsh",
        "India TV",
        "Times Now Navbharat",
        "NDTV India",
        "DD News",
        "News24",
        "News Nation",
        "India News",
        "Good News Today",

        // Indian English News
        "NDTV 24x7",
        "Times Now",
        "CNN-News18",
        "India Today",
        "WION"
    )

    /*
     * --------------------------------------------------
     * INDIAN MUSIC
     * --------------------------------------------------
     */

    private val indianMusicQueries = listOf(
        // First-pass queries are deliberately restricted to major official labels.
        "T-Series latest Hindi official music video 2026",
        "Zee Music Company latest Hindi official music video 2026",

        // Background queries add variety after the first paint.
        "Sony Music India latest Hindi official music video 2026",
        "Tips Official latest Hindi official music video 2026",
        "Saregama Music latest Hindi official music video 2026",
        "Times Music latest Hindi official music video 2026",
        "Universal Music India latest Hindi official music video 2026",
        "Panorama Music latest Hindi official music video 2026",
        "9XM latest Hindi official music video 2026",
        "Mastiii latest Hindi official music video 2026",
        "latest Bollywood movie song official video 2026",
        "latest Indian Bengali official music video 2026",
        "Bengali bhakti official music video 2026"
    )

    private val preferredMusicChannels = listOf(
        "T-Series",
        "Zee Music Company",
        "Sony Music India",
        "Sony Music South",
        "Tips Official",
        "Tips Music",
        "Saregama Music",
        "Times Music",
        "Universal Music India",
        "Panorama Music",
        "Ishtar Music",
        "Desi Music Factory",
        "Play DM Official",
        "9XM",
        "9XM Music",
        "Mastiii",
        "B4U Music",
        "Venus Music"
    )

    private val musicNonVideoKeywords = listOf(
        "audio",
        "official audio",
        "lyrical",
        "lyric video",
        "jukebox",
        "mix",
        "mashup",
        "medley",
        "nonstop",
        "full album",
        "album songs",
        "all songs",
        "collection",
        "playlist",
        "radio",
        "24/7",
        "lofi",
        "slowed",
        "reverb",
        "8d",
        "karaoke",
        "instrumental",
        "cover",
        "remix",
        "dj mix",
        "bass boosted",
        "only audio",
        "audio song"
    )

    private val musicVideoKeywords = listOf(
        "official video",
        "official music video",
        "video song",
        "music video",
        "full video",
        "official mv",
        "song video"
    )

    private fun isPreferredMusicChannel(name: String): Boolean {
        if (name.isBlank()) return false

        return preferredMusicChannels.any { preferred ->
            isSameChannel(name, preferred) ||
                name.contains(preferred, ignoreCase = true)
        }
    }

    private fun isMusicVideoCandidate(
        item: StreamInfoItem
    ): Boolean {
        if (item.streamType != StreamType.VIDEO_STREAM) return false
        if (item.isShortFormContent) return false

        val title = item.name
            ?.trim()
            .orEmpty()

        val uploader = item.uploaderName
            ?.trim()
            .orEmpty()

        if (title.isBlank()) return false

        if (containsAny(title, bangladeshKeywords)) return false
        if (containsAny(uploader, bangladeshKeywords)) return false
        if (containsAny(title, pakistanMusicKeywords)) return false
        if (containsAny(uploader, pakistanMusicKeywords)) return false
        if (containsAny(title, musicNonVideoKeywords)) return false

        // Avoid long compilations/mixes and tiny audio snippets.
        val duration = item.duration
        if (duration > 12 * 60L) return false
        if (duration in 1L..89L) return false

        val hasMusicVideoSignal =
            containsAny(title, musicVideoKeywords)

        val officialSignal =
            isPreferredMusicChannel(uploader)

        return hasMusicVideoSignal || officialSignal
    }

    private fun musicVideoScore(
        item: StreamInfoItem
    ): Int {
        val title = item.name
            ?.trim()
            .orEmpty()

        val uploader = item.uploaderName
            ?.trim()
            .orEmpty()

        val combined =
            "$title $uploader"

        var score = 0

        if (isPreferredMusicChannel(uploader)) score += 300
        if (containsAny(title, musicVideoKeywords)) score += 120

        if (combined.contains("2026", ignoreCase = true)) score += 60
        if (combined.contains("latest", ignoreCase = true)) score += 50
        if (combined.contains("new song", ignoreCase = true)) score += 40
        if (combined.contains("from the movie", ignoreCase = true)) score += 35
        if (combined.contains("full video", ignoreCase = true)) score += 25

        // Prefer normal music-video duration without touching DateWrapper.
        val duration = item.duration
        if (duration in 150L..480L) score += 20
        if (duration in 481L..720L) score += 5

        return score
    }

    /*
     * --------------------------------------------------
     * MOVIES
     * --------------------------------------------------
     */

    private val movieQueries = listOf(
        "Kolkata Bengali full movie",
        "Indian Bengali full movie",
        "Bengali dubbed full movie",
        "Bangla dubbed full movie",
        "Hindi full movie"
    )

    /*
     * --------------------------------------------------
     * HINDI MOVIES
     * --------------------------------------------------
     *
     * Priority:
     *
     * 1. South Indian Hindi Dubbed
     * 2. Popular / Blockbuster / Superhit
     * 3. Action
     * 4. Full Movie
     * 5. Latest / New Release
     * 6. General Hindi Movies
     */

    private val hindiMovieQueries = listOf(
        "South Indian Hindi dubbed full movie",
        "South Hindi dubbed blockbuster full movie",
        "South Indian new Hindi dubbed movie",
        "latest South Hindi dubbed full movie",
        "Hindi dubbed action movie full",
        "Goldmines Hindi dubbed full movie",
        "Goldmines new South Hindi dubbed movie",
        "latest Hindi full movie",
        "Hindi blockbuster full movie",
        "new Hindi dubbed movie 2026"
    )

    private val bangladeshKeywords = listOf(
        "bangladesh",
        "bangladeshi",
        "dhallywood",
        "dhaka movie",
        "bd movie",
        "bangla natok"
    )

    private val pakistanMusicKeywords = listOf(
        "pakistan",
        "pakistani",
        "lollywood",
        "pakistani song",
        "pakistani music",
        "coke studio pakistan"
    )

    /*
     * --------------------------------------------------
     * BENGALI DUBBED RELIGIOUS SERIALS
     * --------------------------------------------------
     *
     * ONLY Hindi-origin religious/mythological shows
     * that have Bengali dubbed versions are targeted here.
     *
     * Direct Kolkata-original religious serials are NOT
     * the priority.
     *
     * Bengali candidates are always collected first.
     */

    private data class BengaliDubbedShow(
        val key: String,
        val bengaliNames: List<String>,
        val hindiNames: List<String>,
        val officialChannels: List<String>
    )

    private val bengaliDubbedShows = listOf(

        BengaliDubbedShow(
            key = "mahabharat",
            bengaliNames = listOf(
                "মহাভারত",
                "Mahabharat Bangla",
                "Mahabharat Bengali",
                "Mahabharat Bengali dubbed",
                "Mahabharat Bangla dubbed"
            ),
            hindiNames = listOf(
                "Mahabharat 2013",
                "Mahabharat Star Plus"
            ),
            officialChannels = listOf(
                "Star Jalsha",
                "Star Plus",
                "Star Bharat"
            )
        ),

        BengaliDubbedShow(
            key = "siya_ke_ram",
            bengaliNames = listOf(
                "সীতা",
                "Sita Bangla",
                "Sita Bengali",
                "Siya Ke Ram Bengali",
                "Siya Ke Ram Bangla"
            ),
            hindiNames = listOf(
                "Siya Ke Ram",
                "Siya Ke Ram Star Plus"
            ),
            officialChannels = listOf(
                "Star Jalsha",
                "Star Plus"
            )
        ),

        BengaliDubbedShow(
            key = "jai_shri_krishna",
            bengaliNames = listOf(
                "জয় শ্রী কৃষ্ণ",
                "জয় শ্রী কৃষ্ণ",
                "Jai Shri Krishna Bengali",
                "Jai Shri Krishna Bangla"
            ),
            hindiNames = listOf(
                "Jai Shri Krishna",
                "Jai Shri Krishna Colors"
            ),
            officialChannels = listOf(
                "Colors Bangla",
                "Colors TV",
                "Sagar Pictures"
            )
        ),

        BengaliDubbedShow(
            key = "devadidev_mahadev",
            bengaliNames = listOf(
                "দেবাদিদেব মহাদেব",
                "দেবাদিদেব মহাদেব বাংলা",
                "Devadidev Mahadev Bangla",
                "Devon Ke Dev Mahadev Bengali",
                "Devon Ke Dev Mahadev Bangla"
            ),
            hindiNames = listOf(
                "Devon Ke Dev Mahadev",
                "Devon Ke Dev Mahadev Life OK"
            ),
            officialChannels = listOf(
                "Star Jalsha",
                "Life OK",
                "Star Bharat"
            )
        ),

        BengaliDubbedShow(
            key = "sankatmochan_hanuman",
            bengaliNames = listOf(
                "মহাবলী হনুমান",
                "মহাবলী হনুমান বাংলা",
                "Sankatmochan Mahabali Hanuman Bengali",
                "Sankatmochan Mahabali Hanuman Bangla"
            ),
            hindiNames = listOf(
                "Sankatmochan Mahabali Hanuman",
                "Mahabali Hanuman Sony"
            ),
            officialChannels = listOf(
                "Sony AATH",
                "Sony Entertainment Television"
            )
        ),

        BengaliDubbedShow(
            key = "radhakrishn",
            bengaliNames = listOf(
                "রাধা কৃষ্ণ",
                "রাধাকৃষ্ণ",
                "Radha Krishna Bengali",
                "RadhaKrishn Bangla",
                "RadhaKrishn Bengali"
            ),
            hindiNames = listOf(
                "RadhaKrishn",
                "Radha Krishn Star Bharat"
            ),
            officialChannels = listOf(
                "Star Jalsha",
                "Star Bharat"
            )
        ),

        BengaliDubbedShow(
            key = "karmaphal_shani",
            bengaliNames = listOf(
                "কর্মফল দাতা শনি",
                "কর্মফলদাতা শনি",
                "জয় জয় শনি দেব",
                "Karmaphal Daata Shani Bengali",
                "Karmaphal Daata Shani Bangla"
            ),
            hindiNames = listOf(
                "Karmaphal Daata Shani",
                "Shani Colors TV"
            ),
            officialChannels = listOf(
                "Colors Bangla",
                "Colors TV"
            )
        ),

        BengaliDubbedShow(
            key = "vighnaharta_ganesh",
            bengaliNames = listOf(
                "বিঘ্নহর্তা শ্রী গণেশ",
                "বিঘ্নহর্তা গণেশ",
                "Vighnaharta Ganesh Bengali",
                "Vighnaharta Ganesh Bangla"
            ),
            hindiNames = listOf(
                "Vighnaharta Ganesh",
                "Vighnaharta Shree Ganesh"
            ),
            officialChannels = listOf(
                "Sony AATH",
                "Sony Entertainment Television"
            )
        ),

        BengaliDubbedShow(
            key = "shrimad_ramayan",
            bengaliNames = listOf(
                "শ্রীমদ রামায়ণ",
                "শ্রীমদ রামায়ণ",
                "Shrimad Ramayan Bangla",
                "Shrimad Ramayan Bengali"
            ),
            hindiNames = listOf(
                "Shrimad Ramayan",
                "Shrimad Ramayan Sony"
            ),
            officialChannels = listOf(
                "Sony AATH",
                "Sony Entertainment Television",
                "Sony LIV"
            )
        ),

        BengaliDubbedShow(
            key = "legend_of_hanuman",
            bengaliNames = listOf(
                "The Legend of Hanuman Bengali",
                "The Legend of Hanuman Bangla",
                "Legend of Hanuman Bangla"
            ),
            hindiNames = listOf(
                "The Legend of Hanuman",
                "Legend of Hanuman Hindi"
            ),
            officialChannels = listOf(
                "Disney",
                "DisneyPlusHotstar",
                "Hotstar"
            )
        ),

        BengaliDubbedShow(
            key = "suryaputra_karn",
            bengaliNames = listOf(
                "সূর্যপুত্র কর্ণ",
                "Suryaputra Karn Bengali",
                "Suryaputra Karn Bangla"
            ),
            hindiNames = listOf(
                "Suryaputra Karn"
            ),
            officialChannels = listOf(
                "Sony AATH",
                "Sony Entertainment Television",
                "Sony Pal"
            )
        ),

        BengaliDubbedShow(
            key = "dwarkadheesh",
            bengaliNames = listOf(
                "দ্বারকাধীশ",
                "দ্বারকাধীশ ভগবান শ্রী কৃষ্ণ",
                "Dwarkadheesh Bengali",
                "Dwarkadheesh Bangla"
            ),
            hindiNames = listOf(
                "Dwarkadheesh Bhagwaan Shree Krishna",
                "Dwarkadheesh"
            ),
            officialChannels = listOf(
                "Life OK",
                "Star Bharat"
            )
        ),

        BengaliDubbedShow(
            key = "mahakali",
            bengaliNames = listOf(
                "মহাকালী",
                "মহাকালী অন্ত হি আরম্ভ হ্যায়",
                "Mahakali Bengali",
                "Mahakali Bangla"
            ),
            hindiNames = listOf(
                "Mahakali Anth Hi Aarambh Hai",
                "Mahakali Colors"
            ),
            officialChannels = listOf(
                "Colors Bangla",
                "Colors TV"
            )
        ),

        BengaliDubbedShow(
            key = "jai_hanuman",
            bengaliNames = listOf(
                "জয় হনুমান",
                "জয় হনুমান",
                "Jai Hanuman Bengali",
                "Jai Hanuman Bangla"
            ),
            hindiNames = listOf(
                "Jai Hanuman"
            ),
            officialChannels = listOf(
                "Sony AATH",
                "Sony Entertainment Television"
            )
        ),

        BengaliDubbedShow(
            key = "mahima_shani",
            bengaliNames = listOf(
                "জয় জয় শনি দেব",
                "মহিমা শনি দেব কি বাংলা",
                "Mahima Shani Dev Ki Bengali",
                "Mahima Shani Dev Ki Bangla"
            ),
            hindiNames = listOf(
                "Mahima Shani Dev Ki"
            ),
            officialChannels = listOf(
                "Colors Bangla",
                "Colors TV"
            )
        ),

        BengaliDubbedShow(
            key = "bal_krishna",
            bengaliNames = listOf(
                "বাল কৃষ্ণ",
                "Bal Krishna Bengali",
                "Bal Krishna Bangla"
            ),
            hindiNames = listOf(
                "Bal Krishna",
                "Bal Krishna Hindi serial"
            ),
            officialChannels = listOf(
                "Colors Bangla",
                "Colors TV"
            )
        ),

        BengaliDubbedShow(
            key = "yashomati_nandlala",
            bengaliNames = listOf(
                "যশোমতী মাইয়া কে নন্দলালা",
                "Yashomati Maiyaa Ke Nandlala Bengali",
                "Yashomati Maiyaa Ke Nandlala Bangla"
            ),
            hindiNames = listOf(
                "Yashomati Maiyaa Ke Nandlala"
            ),
            officialChannels = listOf(
                "Colors Bangla",
                "Colors TV"
            )
        )
    )

    /*
     * --------------------------------------------------
     * ADDITIONAL HINDI RELIGIOUS SERIALS
     * --------------------------------------------------
     */

    private val additionalHindiReligionShows = listOf(
        "Ramayan Ramanand Sagar",
        "Shri Krishna Ramanand Sagar",
        "Mahabharat 1988",
        "Om Namah Shivay",
        "Vishnu Puran",
        "Jai Shri Krishna",
        "Devon Ke Dev Mahadev",
        "Vighnaharta Ganesh",
        "Jai Hanuman",
        "RadhaKrishn",
        "Siya Ke Ram",
        "Suryaputra Karn",
        "Karmaphal Daata Shani",
        "Mahakali Anth Hi Aarambh Hai",
        "Sankatmochan Mahabali Hanuman",
        "Dwarkadheesh Bhagwaan Shree Krishna",
        "Shani Dev",
        "Karamphal Data Shani",
        "Ganesh Leela",
        "Ram Siya Ke Luv Kush",
        "Jag Janani Maa Durga",
        "Jai Jag Janani Maa Durga",
        "Santoshi Maa",
        "Sita",
        "Sita Ram",
        "Luv Kush",
        "Radha Krishna",
        "Krishna Arjun",
        "Kahat Hanuman Jai Shri Ram",
        "Sharabha",
        "Paramavatar Shri Krishna",
        "RadhaKrishn full episodes",
        "Mahabharat full episodes Hindi",
        "Ramayan full episodes Hindi"
    )

    /*
     * --------------------------------------------------
     * RELIGION EXCLUDE
     * --------------------------------------------------
     */

    private val religionExcludeKeywords = listOf(
        "bhajan",
        "bhajans",
        "aarti",
        "aartis",
        "mantra",
        "mantras",
        "song",
        "songs",
        "music",
        "devotional songs",
        "playlist songs",
        "status",
        "shorts",
        "remix",
        "dj",
        "edit",
        "fan edit",
        "fan made",
        "fanmade",
        "reaction",
        "review",
        "explained",
        "story explained",
        "recap"
    )

    /*
     * --------------------------------------------------
     * BANGLADESH EXCLUDE
     * --------------------------------------------------
     */

    private val religionBangladeshKeywords = listOf(
        "bangladesh",
        "bangladeshi",
        "dhallywood",
        "dhaka",
        "bd",
        "bangla natok"
    )

    /*
     * --------------------------------------------------
     * RELIGION SERIES ALIASES
     * --------------------------------------------------
     */

    private val religionSeriesAliases = mapOf(

        "mahabharat" to listOf(
            "mahabharat",
            "mahabharata",
            "মহাভারত"
        ),

        "ramayan" to listOf(
            "ramayan",
            "ramayana",
            "রামায়ণ",
            "রামায়ণ"
        ),

        "siya_ke_ram" to listOf(
            "siya ke ram",
            "sita",
            "সীতা"
        ),

        "jai_shri_krishna" to listOf(
            "jai shri krishna",
            "shree krishna",
            "shri krishna",
            "জয় শ্রী কৃষ্ণ",
            "জয় শ্রী কৃষ্ণ"
        ),

        "devadidev_mahadev" to listOf(
            "devon ke dev mahadev",
            "devadidev mahadev",
            "devadidev",
            "mahadev",
            "দেবাদিদেব মহাদেব",
            "মহাদেব"
        ),

        "sankatmochan_hanuman" to listOf(
            "sankatmochan mahabali hanuman",
            "mahabali hanuman",
            "মহাবলী হনুমান"
        ),

        "radhakrishn" to listOf(
            "radhakrishn",
            "radha krishna",
            "radha krishn",
            "রাধাকৃষ্ণ",
            "রাধা কৃষ্ণ"
        ),

        "karmaphal_shani" to listOf(
            "karmaphal daata shani",
            "karmphal data shani",
            "karmaphal shani",
            "কর্মফল দাতা শনি"
        ),

        "vighnaharta_ganesh" to listOf(
            "vighnaharta ganesh",
            "shree ganesh",
            "shri ganesh",
            "বিঘ্নহর্তা গণেশ",
            "গণেশ"
        ),

        "shrimad_ramayan" to listOf(
            "shrimad ramayan",
            "শ্রীমদ রামায়ণ",
            "শ্রীমদ রামায়ণ"
        ),

        "legend_of_hanuman" to listOf(
            "legend of hanuman"
        ),

        "suryaputra_karn" to listOf(
            "suryaputra karn",
            "suryaputra karna",
            "সূর্যপুত্র কর্ণ"
        ),

        "dwarkadheesh" to listOf(
            "dwarkadheesh",
            "dwarkadhish",
            "দ্বারকাধীশ"
        ),

        "mahakali" to listOf(
            "mahakali",
            "mahakali anth hi aarambh hai",
            "মহাকালী"
        ),

        "jai_hanuman" to listOf(
            "jai hanuman",
            "জয় হনুমান",
            "জয় হনুমান"
        ),

        "mahima_shani" to listOf(
            "mahima shani dev ki",
            "mahima shani",
            "জয় জয় শনি দেব"
        ),

        "bal_krishna" to listOf(
            "bal krishna",
            "বাল কৃষ্ণ"
        ),

        "yashomati_nandlala" to listOf(
            "yashomati maiyaa ke nandlala",
            "yashomati",
            "যশোমতী"
        ),

        "om_namah_shivay" to listOf(
            "om namah shivay",
            "om namah shivaya"
        ),

        "vishnu_puran" to listOf(
            "vishnu puran",
            "বিষ্ণু পুরাণ"
        ),

        "ram_siyaa_ke_luv_kush" to listOf(
            "ram siya ke luv kush",
            "ram siya ke luvkush"
        ),

        "jag_janani_durga" to listOf(
            "jag janani maa durga",
            "jag janani durga"
        ),

        "santoshi_maa" to listOf(
            "santoshi maa",
            "santoshi ma"
        ),

        "paramavatar_shri_krishna" to listOf(
            "paramavatar shri krishna",
            "paramavatar krishna"
        ),

        "kahat_hanuman" to listOf(
            "kahat hanuman jai shri ram"
        ),

        "shani_dev" to listOf(
            "shani dev",
            "shani"
        ),

        "krishna_arjun" to listOf(
            "krishna arjun"
        ),

        "ganesh_leela" to listOf(
            "ganesh leela"
        )
    )

    /*
     * --------------------------------------------------
     * CACHE
     * --------------------------------------------------
     */

    private val pageCache =
        mutableMapOf<String, org.schabi.newpipe.extractor.Page?>()

    private val searchPageCache =
        mutableMapOf<String, org.schabi.newpipe.extractor.Page?>()

    private data class TimedHomeCache(
        val expiresAt: Long,
        val response: HomePageResponse
    )

    private val musicHomeCache =
        ConcurrentHashMap<String, TimedHomeCache>()

    private val movieHomeCache =
        ConcurrentHashMap<String, TimedHomeCache>()

    private val liveHomeCache =
        ConcurrentHashMap<String, TimedHomeCache>()

    private val religionHomeCache =
        ConcurrentHashMap<String, TimedHomeCache>()

    private fun getCachedHomePage(
        cache: ConcurrentHashMap<String, TimedHomeCache>,
        key: String
    ): HomePageResponse? {

        val cached =
            cache[key]
                ?: return null

        return if (
            cached.expiresAt >
            System.currentTimeMillis()
        ) {
            cached.response
        } else {
            cache.remove(
                key,
                cached
            )
            null
        }
    }

    private fun putCachedHomePage(
        cache: ConcurrentHashMap<String, TimedHomeCache>,
        key: String,
        response: HomePageResponse,
        ttlMs: Long
    ) {

        cache[key] =
            TimedHomeCache(
                System.currentTimeMillis() +
                    ttlMs,
                response
            )
    }

    /*
     * --------------------------------------------------
     * FAST SEARCH
     * --------------------------------------------------
     *
     * Maximum 6 simultaneous searches.
     * Individual search timeout = 8 seconds.
     */

    private suspend fun fetchSearchItemsInBatches(
        queries: List<String>,
        batchSize: Int = 6,
        fastMode: Boolean = false,
        continuationSection: String? = null
    ): List<List<InfoItem>> {

        val cleanQueries =
            queries
                .map { it.trim() }
                .filter { it.isNotBlank() }
                .distinct()

        if (cleanQueries.isEmpty()) {
            return emptyList()
        }

        /*
         * Fast path:
         * - only the first two high-value queries
         * - both in parallel
         * - tight total timeout
         *
         * This is the important latency guard for first paint.
         */
        if (fastMode) {
            val fastQueries =
                cleanQueries.take(2)

            return withTimeoutOrNull(
                1_650L
            ) {
                coroutineScope {
                    fastQueries.map { query ->
                        async(
                            Dispatchers.IO
                        ) {
                            withTimeoutOrNull(
                                1_350L
                            ) {
                                runCatching {
                                    val extractor =
                                        service.getSearchExtractor(
                                            query
                                        )

                                    extractor.fetchPage()

                                    val initialPage =
                                        extractor.initialPage

                                    if (continuationSection != null) {
                                        val continuationKey =
                                            "$continuationSection|$query"
                                        if (initialPage.nextPage != null) {
                                            searchContinuationCache[
                                                continuationKey
                                            ] = initialPage.nextPage
                                        } else {
                                            searchContinuationCache.remove(
                                                continuationKey
                                            )
                                        }
                                    }

                                    initialPage
                                        .items
                                        .toList()
                                }.getOrElse {
                                    emptyList()
                                }
                            } ?: emptyList()
                        }
                    }.awaitAll()
                }
            } ?: emptyList()
        }

        val results =
            mutableListOf<List<InfoItem>>()

        for (
            batch in cleanQueries.chunked(
                batchSize
            )
        ) {
            val batchResults =
                coroutineScope {
                    batch.map { query ->
                        async(
                            Dispatchers.IO
                        ) {
                            withTimeoutOrNull(
                                8_000L
                            ) {
                                try {
                                    val extractor =
                                        service.getSearchExtractor(
                                            query
                                        )

                                    extractor.fetchPage()

                                    val initialPage =
                                        extractor.initialPage

                                    if (continuationSection != null) {
                                        val continuationKey =
                                            "$continuationSection|$query"
                                        if (initialPage.nextPage != null) {
                                            searchContinuationCache[
                                                continuationKey
                                            ] = initialPage.nextPage
                                        } else {
                                            searchContinuationCache.remove(
                                                continuationKey
                                            )
                                        }
                                    }

                                    initialPage
                                        .items
                                        .toList()
                                } catch (
                                    _: Exception
                                ) {
                                    emptyList()
                                }
                            } ?: emptyList()
                        }
                    }.awaitAll()
                }

            results.addAll(
                batchResults
            )
        }

        return results
    }

    /*
     * --------------------------------------------------
     * HOME PAGE
     * --------------------------------------------------
     */

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {

        when (request.data) {

            "music_india" ->
                return getIndianMusicPage(
                    page
                )

            "movies" ->
                return getMoviesPage(
                    page
                )

            "hindi_movies" ->
                return getHindiMoviesPage(
                    page
                )

            "live" ->
                return getCuratedLivePage(
                    page
                )

            "religion" ->
                return getReligionPage(
                    page
                )
        }

        val key =
            request.data

        val cacheSection =
            "generic_" + key

        if (page > 1) {
            return getCachedHomePageChunk(
                cacheSection,
                request.name,
                page
            )
        }

        val cached =
            cachedResponses(
                cacheSection,
                "generic",
                HOME_CACHE_LIMIT
            )

        if (cached.isNotEmpty()) {
            startCoreBackgroundWarmup()

            return newHomePageResponse(
                listOf(
                    HomePageList(
                        request.name,
                        cached.take(FAST_VISIBLE_COUNT),
                        true
                    )
                ),
                true
            )
        }

        val fast =
            buildGenericKioskFast(
                request.data,
                request.name,
                cacheSection
            )

        startCoreBackgroundWarmup()

        return fast
    }

    /*
     * --------------------------------------------------
     * INDIAN MUSIC
     * --------------------------------------------------
     */

    /*
     * --------------------------------------------------
     * FAST HOME + STALE-WHILE-REVALIDATE CACHE
     * --------------------------------------------------
     *
     * CloudStream's getMainPage() returns a snapshot; it has no reliable
     * public API for a plugin to append items to the already-rendered UI
     * after returning. Therefore:
     *
     * 1) Show a small fast snapshot (6 items) on the first ever open.
     * 2) Refresh the complete row in the background.
     * 3) Persist the refreshed row so the next open is immediate.
     * 4) Keep recent older items in the cache so a newly changing feed does
     *    not erase everything at once.
     */
    private companion object {
        const val FAST_VISIBLE_COUNT = 6
        const val HOME_CACHE_LIMIT = 50
        const val MAX_CACHED_ITEMS = 2000
        const val BACKGROUND_BATCH_SIZE = 50
        const val BACKGROUND_REFRESH_COOLDOWN_MS = 15_000L
        const val GENERIC_BACKGROUND_PAGES = 6
        const val CORE_BACKGROUND_START_DELAY_MS = 500L
        const val CACHE_GROWTH_WAIT_MS = 1800L

        // Starts the four heavy home sections together only after the first
        // visible home content has had a chance to render.
        val CORE_BACKGROUND_STARTED = AtomicBoolean(false)
    }

    private val backgroundRefreshScope =
        CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val refreshRunning =
        ConcurrentHashMap<String, Boolean>()

    private val refreshLastStarted =
        ConcurrentHashMap<String, Long>()

    /*
     * Fast in-memory cache for repeated navigation.
     * App-level storage helpers from the host application are not part of the
     * plugin compile classpath, so this extension intentionally stays on the
     * library-safe in-memory cache path.
     *
     * Expired snapshots are intentionally still usable: home returns them
     * immediately while a background refresh replaces them.
     */
    private data class CachedResponses(
        val expiresAt: Long,
        val items: List<SearchResponse>
    )

    private val fastContentCache =
        ConcurrentHashMap<String, CachedResponses>()

    // Search continuation state for the four expandable home sections.
    private val searchContinuationCache =
        ConcurrentHashMap<String, org.schabi.newpipe.extractor.Page?>()

    // Kiosk continuation state for Movie Trailers.
    private val genericContinuationCache =
        ConcurrentHashMap<String, org.schabi.newpipe.extractor.Page?>()

    // Prevents two background 50-item batches for the same section from
    // running at the same time.
    private val sectionBatchRunning =
        ConcurrentHashMap<String, Boolean>()

    private fun cachedResponses(
        section: String,
        kind: String,
        limit: Int = HOME_CACHE_LIMIT
    ): List<SearchResponse> {
        // Stale-while-revalidate: a usable old snapshot is always preferable
        // to an empty screen. The caller separately schedules a refresh.
        val cached = fastContentCache[section] ?: return emptyList()
        return cached.items.take(limit)
    }

    private fun putCachedResponses(
        section: String,
        items: List<SearchResponse>,
        ttlMs: Long
    ) {
        if (items.isEmpty()) return
        fastContentCache[section] = CachedResponses(
            expiresAt = System.currentTimeMillis() + ttlMs,
            items = items
                .distinctBy { it.url }
                .take(MAX_CACHED_ITEMS)
        )
    }

    private fun scheduleBackgroundRefresh(
        section: String,
        job: suspend () -> Unit
    ) {
        val now = System.currentTimeMillis()
        val last = refreshLastStarted[section] ?: 0L

        if (now - last < BACKGROUND_REFRESH_COOLDOWN_MS) return
        if (refreshRunning.putIfAbsent(section, true) != null) return

        refreshLastStarted[section] = now

        backgroundRefreshScope.launch {
            try {
                job()
            } catch (_: Exception) {
                // Background refresh must never break the visible home page.
            } finally {
                refreshRunning.remove(section)
            }
        }
    }

    private suspend fun getCachedHomePageChunk(
        section: String,
        title: String,
        page: Int
    ): HomePageResponse {

        if (page <= 1) {
            return newHomePageResponse(
                emptyList(),
                false
            )
        }

        var all =
            cachedResponses(
                section,
                section,
                MAX_CACHED_ITEMS
            )

        val start =
            (page - 1) *
                FAST_VISIBLE_COUNT

        /*
         * When the user reaches the end of the currently cached 50-item
         * window, immediately start the next 50-item background batch.
         * This only happens because the user requested a later page by
         * scrolling/continuing; it never runs continuously while idle.
         */
        if (
            isExpandableSection(section) &&
            start + FAST_VISIBLE_COUNT * 2 >= all.size
        ) {
            scheduleNextSectionBatch(section)
        }

        /*
         * If CloudStream asks for the next page before the background batch
         * has finished, wait only briefly for the cache to grow. This keeps
         * scrolling smooth without making the initial home page wait.
         */
        if (
            isExpandableSection(section) &&
            start >= all.size
        ) {

            val deadline =
                System.currentTimeMillis() +
                    CACHE_GROWTH_WAIT_MS

            while (
                System.currentTimeMillis() < deadline
            ) {

                all =
                    cachedResponses(
                        section,
                        section,
                        MAX_CACHED_ITEMS
                    )

                if (start < all.size) {
                    break
                }

                kotlinx.coroutines.delay(80L)
            }
        }

        if (
            all.isEmpty() ||
            start >= all.size
        ) {
            return newHomePageResponse(
                emptyList(),
                false
            )
        }

        val chunk =
            all
                .drop(start)
                .take(FAST_VISIBLE_COUNT)

        val hasMore =
            start + chunk.size <
                all.size ||
                isExpandableSection(section)

        return newHomePageResponse(
            listOf(
                HomePageList(
                    title,
                    chunk,
                    hasMore
                )
            ),
            hasMore
        )
    }

    private suspend fun getIndianMusicPage(
        page: Int
    ): HomePageResponse {
        if (page > 1) {
            return getCachedHomePageChunk(
                "music",
                "Trending Music Videos",
                page
            )
        }

        val cached =
            cachedResponses("music", "movie")

        if (cached.isNotEmpty()) {
            startCoreBackgroundWarmup()

            return newHomePageResponse(
                listOf(
                    HomePageList(
                        "Trending Music Videos",
                        cached.take(FAST_VISIBLE_COUNT),
                        true
                    )
                ),
                true
            )
        }

        // No cache: use the smallest fast path and immediately refresh in background.
        val fast =
            buildIndianMusicPageFull(
                page = 1,
                fastMode = true,
                forceRefresh = true
            )

        startCoreBackgroundWarmup()

        return fast
    }

    private suspend fun buildIndianMusicPageFull(
        page: Int,
        fastMode: Boolean = false,
        forceRefresh: Boolean = false
    ): HomePageResponse {

        if (page > 1) {
            return newHomePageResponse(
                emptyList(),
                false
            )
        }

        if (!fastMode && !forceRefresh) {
            getCachedHomePage(
                musicHomeCache,
                "music"
            )?.let {
                return it
            }
        }

        val candidates =
            mutableListOf<Pair<Int, StreamInfoItem>>()

        val seenUrls =
            mutableSetOf<String>()

        for (
            items in fetchSearchItemsInBatches(
                indianMusicQueries,
                6,
                fastMode,
                continuationSection = "music"
            )
        ) {
            for (item in items) {
                if (item !is StreamInfoItem) continue
                if (!isMusicVideoCandidate(item)) continue

                val url =
                    item.url
                        ?.trim()
                        ?: continue

                if (
                    url.isBlank() ||
                    !seenUrls.add(url)
                ) {
                    continue
                }

                candidates.add(
                    musicVideoScore(item) to item
                )
            }
        }

        val resultLimit =
            if (fastMode) FAST_VISIBLE_COUNT else HOME_CACHE_LIMIT

        val selected =
            candidates
                .sortedByDescending {
                    it.first
                }
                .take(resultLimit)

        val results =
            selected.mapNotNull { (_, item) ->
                val title =
                    item.name
                        ?.trim()
                        ?: return@mapNotNull null

                val url =
                    item.url
                        ?.trim()
                        ?: return@mapNotNull null

                if (
                    title.isBlank() ||
                    url.isBlank()
                ) {
                    return@mapNotNull null
                }

                newMovieSearchResponse(
                    title,
                    url,
                    TvType.Movie
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

        val response =
            newHomePageResponse(
                listOf(
                    HomePageList(
                        "Trending Music Videos",
                        results.take(FAST_VISIBLE_COUNT),
                        true
                    )
                ),
                true
            )

        // Fresh content stays available for a generous window; background refresh
        // keeps it current without making the next open wait for YouTube.
        putCachedHomePage(
            musicHomeCache,
            "music",
            response,
            30 * 60 * 1000L
        )

        putCachedResponses(
            "music",
            results,
            30 * 60 * 1000L
        )

        return response
    }

    /*
     * --------------------------------------------------
     * MOVIES
     * --------------------------------------------------
     */


    private suspend fun getMoviesPage(page: Int): HomePageResponse {
        if (page > 1) {
            return getCachedHomePageChunk(
                "movies",
                "Movies",
                page
            )
        }

        val cached = cachedResponses("movies", "movie")
        if (cached.isNotEmpty()) {
            startCoreBackgroundWarmup()
            return newHomePageResponse(
                listOf(
                    HomePageList(
                        "Movies",
                        cached.take(FAST_VISIBLE_COUNT),
                        true
                    )
                ),
                true
            )
        }

        val fast =
            buildMoviesPageFull(
                1,
                fastMode = true,
                forceRefresh = true
            )

        startCoreBackgroundWarmup()

        return fast
    }

    private suspend fun buildMoviesPageFull(
        page: Int,
        fastMode: Boolean = false,
        forceRefresh: Boolean = false
    ): HomePageResponse {

        if (page > 1) {

            return newHomePageResponse(
                emptyList(),
                false
            )
        }

        if (!fastMode && !forceRefresh) {
            getCachedHomePage(
                movieHomeCache,
                "movies"
            )?.let {
                return it
            }
        }

        val results =
            mutableListOf<SearchResponse>()

        val resultLimit = if (fastMode) FAST_VISIBLE_COUNT else HOME_CACHE_LIMIT

        val seenUrls =
            mutableSetOf<String>()

        for (
            items in fetchSearchItemsInBatches(
                movieQueries,
                5,
                fastMode,
                continuationSection = "movies"
            )
        ) {

            if (results.size >= resultLimit) {
                break
            }

            for (item in items) {

                if (results.size >= resultLimit) {
                    break
                }

                if (
                    item !is StreamInfoItem
                ) {
                    continue
                }

                if (
                    item.streamType !=
                    StreamType.VIDEO_STREAM
                ) {
                    continue
                }

                if (
                    item.isShortFormContent
                ) {
                    continue
                }

                val url =
                    item.url
                        ?.trim()
                        ?: continue

                if (
                    url.isBlank() ||
                    !seenUrls.add(url)
                ) {
                    continue
                }

                val title =
                    item.name
                        ?.trim()
                        ?: continue

                if (
                    title.isBlank()
                ) {
                    continue
                }

                if (
                    containsAny(
                        title,
                        bangladeshKeywords
                    )
                ) {
                    continue
                }

                val uploader =
                    item.uploaderName
                        ?.trim()
                        ?: ""

                if (
                    containsAny(
                        uploader,
                        bangladeshKeywords
                    )
                ) {
                    continue
                }

                results.add(
                    newMovieSearchResponse(
                        title,
                        url,
                        TvType.Movie
                    ) {

                        posterUrl =
                            item.thumbnails
                                .lastOrNull()
                                ?.url
                                ?.takeIf {
                                    it.isNotBlank()
                                }
                    }
                )
            }
        }

        val response =
            newHomePageResponse(
                listOf(
                    HomePageList(
                        "Movies",
                        results.take(FAST_VISIBLE_COUNT),
                        true
                    )
                ),
                true
            )

        putCachedHomePage(
            movieHomeCache,
            "movies",
            response,
            20 * 60 * 1000L
        )

        putCachedResponses("movies", results, 20 * 60 * 1000L)

        return response
    }

    /*
     * --------------------------------------------------
     * HINDI MOVIES
     * --------------------------------------------------
     */

    private fun looksLikeNonMovieUpload(
        title: String
    ): Boolean {

        val t =
            title.lowercase()

        return listOf(
            "trailer",
            "teaser",
            "scene",
            "scenes",
            "clip",
            "short",
            "song",
            "lyric",
            "lyrics",
            "review",
            "reaction",
            "interview",
            "recap",
            "explained",
            "promo",
            "making"
        ).any {
            t.contains(it)
        }
    }

    private fun scoreHindiMovie(
        title: String,
        uploader: String
    ): Int {

        val t =
            "$title $uploader"
                .lowercase()

        var score = 100

        /*
         * South Indian priority.
         */

        if (
            listOf(
                "south",
                "tamil",
                "telugu",
                "kannada",
                "malayalam"
            ).any {
                t.contains(it)
            }
        ) {
            score += 90
        }

        if (
            t.contains("hindi dubbed") ||
            t.contains("hindi dub")
        ) {
            score += 80
        }

        /*
         * Popularity.
         */

        if (
            t.contains("blockbuster")
        ) {
            score += 60
        }

        if (
            t.contains("superhit") ||
            t.contains("super hit")
        ) {
            score += 45
        }

        /*
         * Action.
         */

        if (
            t.contains("action")
        ) {
            score += 35
        }

        /*
         * Full movie.
         */

        if (
            t.contains("full movie") ||
            t.contains("full film")
        ) {
            score += 35
        }

        /*
         * Recent / new.
         */

        if (
            t.contains("new release") ||
            t.contains("new released") ||
            t.contains("latest")
        ) {
            score += 25
        }

        if (
            t.contains("2026")
        ) {
            score += 20
        }

        /*
         * Goldmines signal.
         */

        if (
            t.contains("goldmines")
        ) {
            score += 25
        }

        /*
         * Official.
         */

        if (
            t.contains("official")
        ) {
            score += 20
        }

        return score
    }


    private suspend fun getHindiMoviesPage(page: Int): HomePageResponse {
        if (page > 1) {
            return getCachedHomePageChunk(
                "hindi_movies",
                "Hindi Movies",
                page
            )
        }

        val cached = cachedResponses("hindi_movies", "movie")
        if (cached.isNotEmpty()) {
            startCoreBackgroundWarmup()
            return newHomePageResponse(
                listOf(
                    HomePageList(
                        "Hindi Movies",
                        cached.take(FAST_VISIBLE_COUNT),
                        true
                    )
                ),
                true
            )
        }

        val fast =
            buildHindiMoviesPageFull(
                1,
                fastMode = true,
                forceRefresh = true
            )

        startCoreBackgroundWarmup()

        return fast
    }

    private suspend fun buildHindiMoviesPageFull(
        page: Int,
        fastMode: Boolean = false,
        forceRefresh: Boolean = false
    ): HomePageResponse {

        if (page > 1) {

            return newHomePageResponse(
                emptyList(),
                false
            )
        }

        if (!fastMode && !forceRefresh) {
            getCachedHomePage(
                movieHomeCache,
                "hindi_movies"
            )?.let {
                return it
            }
        }

        val candidates =
            mutableListOf<SearchResponse>()

        val resultLimit = if (fastMode) FAST_VISIBLE_COUNT else HOME_CACHE_LIMIT

        val scores =
            mutableMapOf<String, Int>()

        val seenUrls =
            mutableSetOf<String>()

        for (
            items in fetchSearchItemsInBatches(
                hindiMovieQueries,
                6,
                fastMode,
                continuationSection = "hindi_movies"
            )
        ) {

            for (item in items) {

                if (
                    item !is StreamInfoItem
                ) {
                    continue
                }

                if (
                    item.streamType !=
                    StreamType.VIDEO_STREAM
                ) {
                    continue
                }

                if (
                    item.isShortFormContent
                ) {
                    continue
                }

                val url =
                    item.url
                        ?.trim()
                        ?: continue

                if (
                    url.isBlank() ||
                    !seenUrls.add(url)
                ) {
                    continue
                }

                val title =
                    item.name
                        ?.trim()
                        ?: continue

                if (
                    title.isBlank()
                ) {
                    continue
                }

                val uploader =
                    item.uploaderName
                        ?.trim()
                        ?: ""

                val combined =
                    "$title $uploader"

                if (
                    containsAny(
                        combined,
                        bangladeshKeywords
                    )
                ) {
                    continue
                }

                if (
                    looksLikeNonMovieUpload(
                        title
                    )
                ) {
                    continue
                }

                val response =
                    newMovieSearchResponse(
                        title,
                        url,
                        TvType.Movie
                    ) {

                        posterUrl =
                            item.thumbnails
                                .lastOrNull()
                                ?.url
                                ?.takeIf {
                                    it.isNotBlank()
                                }
                    }

                candidates.add(
                    response
                )

                scores[url] =
                    scoreHindiMovie(
                        title,
                        uploader
                    )
            }
        }

        val ranked =
            candidates
                .sortedByDescending {
                    scores[it.url] ?: 0
                }

        /*
         * First 12 stay relatively stable.
         * Remaining results rotate every 15 minutes.
         *
         * This keeps popular movies near the front while
         * allowing newer/random results to enter the row.
         */

        val fixedCount =
            12.coerceAtMost(
                ranked.size
            )

        val fixed =
            ranked.take(
                fixedCount
            )

        val rotating =
            ranked
                .drop(fixedCount)
                .toMutableList()

        val rotationBucket =
            System.currentTimeMillis() /
                (15 * 60 * 1000L)

        rotating.shuffle(
            java.util.Random(
                (
                    "hindi_movies".hashCode()
                        .toLong()
                        shl 32
                ) xor rotationBucket
            )
        )

        val finalResults =
            (
                fixed + rotating
                ).take(resultLimit)

        val response =
            newHomePageResponse(
                listOf(
                    HomePageList(
                        "Hindi Movies",
                        finalResults.take(FAST_VISIBLE_COUNT),
                        true
                    )
                ),
                true
            )

        putCachedHomePage(
            movieHomeCache,
            "hindi_movies",
            response,
            15 * 60 * 1000L
        )

        putCachedResponses("hindi_movies", candidates, 15 * 60 * 1000L)

        return response
    }

    /*
     * --------------------------------------------------
     * CORE BACKGROUND HOME LOADER
     * --------------------------------------------------
     *
     * After the first visible home content is returned, the four expandable
     * sections are refreshed together:
     *
     *   1. Movie Trailers
     *   2. Trending Music Videos
     *   3. Movies
     *   4. Hindi Movies
     *
     * Each section grows independently in 50-item background batches.
     */
    private fun startCoreBackgroundWarmup() {

        if (
            !CORE_BACKGROUND_STARTED.compareAndSet(
                false,
                true
            )
        ) {
            return
        }

        backgroundRefreshScope.launch {

            kotlinx.coroutines.delay(
                CORE_BACKGROUND_START_DELAY_MS
            )

            scheduleBackgroundRefresh(
                "generic_trending_movies_and_shows"
            ) {
                refreshGenericKioskHome(
                    "trending_movies_and_shows",
                    "Movie Trailers",
                    "generic_trending_movies_and_shows"
                )
            }

            scheduleBackgroundRefresh("music") {
                buildIndianMusicPageFull(
                    page = 1,
                    fastMode = false,
                    forceRefresh = true
                )
            }

            scheduleBackgroundRefresh("movies") {
                buildMoviesPageFull(
                    page = 1,
                    fastMode = false,
                    forceRefresh = true
                )
            }

            scheduleBackgroundRefresh("hindi_movies") {
                buildHindiMoviesPageFull(
                    page = 1,
                    fastMode = false,
                    forceRefresh = true
                )
            }
        }
    }

    private fun isExpandableSection(
        section: String
    ): Boolean {

        return section == "music" ||
            section == "movies" ||
            section == "hindi_movies" ||
            section == "generic_trending_movies_and_shows"
    }

    private fun scheduleNextSectionBatch(
        section: String
    ) {

        if (!isExpandableSection(section)) {
            return
        }

        if (
            sectionBatchRunning.putIfAbsent(
                section,
                true
            ) != null
        ) {
            return
        }

        backgroundRefreshScope.launch {

            try {

                when (section) {

                    "music" ->
                        appendIndianMusicBatch()

                    "movies" ->
                        appendMoviesBatch()

                    "hindi_movies" ->
                        appendHindiMoviesBatch()

                    "generic_trending_movies_and_shows" ->
                        appendGenericKioskBatch(
                            "trending_movies_and_shows",
                            "Movie Trailers",
                            section
                        )
                }

            } catch (_: Exception) {
                // Background pagination must never break the visible UI.
            } finally {
                sectionBatchRunning.remove(
                    section
                )
            }
        }
    }

    /*
     * --------------------------------------------------
     * SEARCH PAGE CONTINUATION
     * --------------------------------------------------
     */
    private suspend fun fetchNextSearchItemsBatch(
        section: String,
        queries: List<String>,
        batchSize: Int
    ): List<InfoItem> {

        val cleanQueries =
            queries
                .map {
                    it.trim()
                }
                .filter {
                    it.isNotBlank()
                }
                .distinct()

        if (cleanQueries.isEmpty()) {
            return emptyList()
        }

        val results =
            mutableListOf<InfoItem>()

        for (
            batch in cleanQueries.chunked(
                batchSize
            )
        ) {

            val batchResults =
                coroutineScope {

                    batch.map { query ->

                        async(
                            Dispatchers.IO
                        ) {

                            withTimeoutOrNull(
                                8_000L
                            ) {

                                runCatching {

                                    val key =
                                        "$section|$query"

                                    val extractor =
                                        service.getSearchExtractor(
                                            query
                                        )

                                    var next =
                                        searchContinuationCache[
                                            key
                                        ]

                                    /*
                                     * If the initial background build did not
                                     * get a continuation, initialize it and
                                     * immediately request the second page.
                                     */
                                    if (next == null) {

                                        extractor.fetchPage()

                                        next =
                                            extractor
                                                .initialPage
                                                .nextPage
                                    }

                                    if (next == null) {
                                        return@runCatching emptyList<InfoItem>()
                                    }

                                    val page =
                                        extractor.getPage(
                                            next
                                        )

                                    if (page.nextPage != null) {
                                        searchContinuationCache[
                                            key
                                        ] = page.nextPage
                                    } else {
                                        searchContinuationCache.remove(key)
                                    }

                                    page.items.toList()

                                }.getOrElse {
                                    emptyList()
                                }

                            } ?: emptyList()

                        }
                    }.awaitAll()
                }

            results.addAll(
                batchResults.flatten()
            )
        }

        return results
    }

    /*
     * --------------------------------------------------
     * MUSIC NEXT 50
     * --------------------------------------------------
     */
    private suspend fun appendIndianMusicBatch() {

        val current =
            cachedResponses(
                "music",
                "movie",
                MAX_CACHED_ITEMS
            )

        val seenUrls =
            current
                .mapNotNull {
                    it.url
                }
                .toMutableSet()

        val nextItems =
            fetchNextSearchItemsBatch(
                "music",
                indianMusicQueries,
                6
            )

        val candidates =
            nextItems
                .filterIsInstance<StreamInfoItem>()
                .filter {
                    isMusicVideoCandidate(it)
                }
                .mapNotNull { item ->

                    val url =
                        item.url
                            ?.trim()
                            ?: return@mapNotNull null

                    if (
                        url.isBlank() ||
                        !seenUrls.add(url)
                    ) {
                        return@mapNotNull null
                    }

                    val title =
                        item.name
                            ?.trim()
                            ?: return@mapNotNull null

                    if (title.isBlank()) {
                        return@mapNotNull null
                    }

                    newMovieSearchResponse(
                        title,
                        url,
                        TvType.Movie
                    ) {

                        posterUrl =
                            item
                                .thumbnails
                                .lastOrNull()
                                ?.url
                    }
                }

        if (candidates.isEmpty()) {
            return
        }

        val merged =
            (
                current +
                    candidates
                )
                .distinctBy {
                    it.url
                }

        putCachedResponses(
            "music",
            merged,
            30 * 60 * 1000L
        )
    }

    /*
     * --------------------------------------------------
     * MOVIES NEXT 50
     * --------------------------------------------------
     */
    private suspend fun appendMoviesBatch() {

        val current =
            cachedResponses(
                "movies",
                "movie",
                MAX_CACHED_ITEMS
            )

        val seenUrls =
            current
                .mapNotNull {
                    it.url
                }
                .toMutableSet()

        val nextItems =
            fetchNextSearchItemsBatch(
                "movies",
                movieQueries,
                5
            )

        val newResults =
            mutableListOf<SearchResponse>()

        for (
            item in nextItems
        ) {

            if (
                item !is StreamInfoItem
            ) {
                continue
            }

            if (
                item.streamType !=
                    StreamType.VIDEO_STREAM
            ) {
                continue
            }

            if (
                item.isShortFormContent
            ) {
                continue
            }

            val url =
                item.url
                    ?.trim()
                    ?: continue

            if (
                url.isBlank() ||
                !seenUrls.add(url)
            ) {
                continue
            }

            val title =
                item.name
                    ?.trim()
                    ?: continue

            if (title.isBlank()) {
                continue
            }

            if (
                containsAny(
                    title,
                    bangladeshKeywords
                )
            ) {
                continue
            }

            val uploader =
                item
                    .uploaderName
                    ?.trim()
                    ?: ""

            if (
                containsAny(
                    uploader,
                    bangladeshKeywords
                )
            ) {
                continue
            }

            newResults.add(
                newMovieSearchResponse(
                    title,
                    url,
                    TvType.Movie
                ) {

                    posterUrl =
                        item
                            .thumbnails
                            .lastOrNull()
                            ?.url
                }
            )

            if (
                newResults.size >=
                    BACKGROUND_BATCH_SIZE
            ) {
                break
            }
        }

        if (newResults.isEmpty()) {
            return
        }

        putCachedResponses(
            "movies",
            (
                current +
                    newResults
                ).distinctBy {
                    it.url
                },
            20 * 60 * 1000L
        )
    }

    /*
     * --------------------------------------------------
     * HINDI MOVIES NEXT 50
     * --------------------------------------------------
     */
    private suspend fun appendHindiMoviesBatch() {

        val current =
            cachedResponses(
                "hindi_movies",
                "movie",
                MAX_CACHED_ITEMS
            )

        val seenUrls =
            current
                .mapNotNull {
                    it.url
                }
                .toMutableSet()

        val nextItems =
            fetchNextSearchItemsBatch(
                "hindi_movies",
                hindiMovieQueries,
                6
            )

        val candidatePairs =
            mutableListOf<Pair<SearchResponse, Int>>()

        for (
            item in nextItems
        ) {

            if (
                item !is StreamInfoItem
            ) {
                continue
            }

            if (
                item.streamType !=
                    StreamType.VIDEO_STREAM
            ) {
                continue
            }

            if (
                item.isShortFormContent
            ) {
                continue
            }

            val url =
                item.url
                    ?.trim()
                    ?: continue

            if (
                url.isBlank() ||
                !seenUrls.add(url)
            ) {
                continue
            }

            val title =
                item.name
                    ?.trim()
                    ?: continue

            if (title.isBlank()) {
                continue
            }

            val uploader =
                item
                    .uploaderName
                    ?.trim()
                    ?: ""

            val combined =
                "$title $uploader"

            if (
                containsAny(
                    combined,
                    bangladeshKeywords
                )
            ) {
                continue
            }

            if (
                looksLikeNonMovieUpload(
                    title
                )
            ) {
                continue
            }

            val response =
                newMovieSearchResponse(
                    title,
                    url,
                    TvType.Movie
                ) {

                    posterUrl =
                        item
                            .thumbnails
                            .lastOrNull()
                            ?.url
                }

            candidatePairs.add(
                response to
                    scoreHindiMovie(
                        title,
                        uploader
                    )
            )

            if (
                candidatePairs.size >=
                    BACKGROUND_BATCH_SIZE
            ) {
                break
            }
        }

        if (candidatePairs.isEmpty()) {
            return
        }

        val merged =
            (
                current +
                    candidatePairs
                        .sortedByDescending {
                            it.second
                        }
                        .map {
                            it.first
                        }
                )
                .distinctBy {
                    it.url
                }

        putCachedResponses(
            "hindi_movies",
            merged,
            15 * 60 * 1000L
        )
    }

    /*
     * --------------------------------------------------
     * MOVIE TRAILERS NEXT 50
     * --------------------------------------------------
     */
    private suspend fun appendGenericKioskBatch(
        kioskId: String,
        sectionName: String,
        cacheSection: String
    ) {

        val current =
            cachedResponses(
                cacheSection,
                "generic",
                MAX_CACHED_ITEMS
            )

        val extractor =
            try {
                getKioskExtractor(
                    kioskId
                )
            } catch (_: Exception) {
                return
            }

        var next =
            genericContinuationCache[
                kioskId
            ]

        if (next == null) {

            try {
                extractor.fetchPage()
                next =
                    extractor
                        .initialPage
                        .nextPage
            } catch (_: Exception) {
                return
            }
        }

        if (next == null) {
            return
        }

        val collected =
            mutableListOf<SearchResponse>()

        var currentPage =
            next

        repeat(
            GENERIC_BACKGROUND_PAGES
        ) {

            val pageData =
                try {
                    extractor.getPage(
                        currentPage
                    )
                } catch (_: Exception) {
                    return@repeat
                }

            collected.addAll(
                pageData.items.map {
                    it.toSearchResponse()
                }
            )

            if (pageData.nextPage != null) {
                genericContinuationCache[
                    kioskId
                ] = pageData.nextPage
            } else {
                genericContinuationCache.remove(kioskId)
            }

            val following =
                pageData.nextPage
                    ?: return@repeat

            currentPage =
                following
        }

        if (collected.isEmpty()) {
            return
        }

        putCachedResponses(
            cacheSection,
            (
                current +
                    collected
                ).distinctBy {
                    it.url
                },
            15 * 60 * 1000L
        )
    }

    /*
     * --------------------------------------------------
     * LIVE
     * --------------------------------------------------
     */


    private suspend fun getCuratedLivePage(page: Int): HomePageResponse {
        if (page > 1) {
            return getCachedHomePageChunk(
                "live",
                "Live",
                page
            )
        }

        val cached = cachedResponses("live", "live")
        if (cached.isNotEmpty()) {
            scheduleBackgroundRefresh("live") {
                buildCuratedLivePageFull(1, fastMode = false, forceRefresh = true)
            }
            return newHomePageResponse(
                listOf(HomePageList("Live", cached.take(FAST_VISIBLE_COUNT), cached.size > FAST_VISIBLE_COUNT)),
                false
            )
        }

        val fast = getCuratedLiveFastPage()
        scheduleBackgroundRefresh("live") {
            buildCuratedLivePageFull(1, fastMode = false, forceRefresh = true)
        }
        return fast
    }

    private suspend fun getCuratedLiveFastPage(): HomePageResponse {
        val results = mutableListOf<SearchResponse>()
        val seen = mutableSetOf<String>()

        val found =
            withTimeoutOrNull(1_800L) {
                coroutineScope {
                    allowedLiveChannels
                        .take(12)
                        .map { channel ->
                            async(Dispatchers.IO) {
                                withTimeoutOrNull(1_400L) {
                                    runCatching {
                                        val extractor =
                                            service.getSearchExtractor("$channel live")

                                        extractor.fetchPage()

                                        selectOldestLiveForChannel(
                                            channel,
                                            extractor.initialPage.items.toList()
                                        )
                                    }.getOrNull()
                                }
                            }
                        }
                        .awaitAll()
                }
            } ?: emptyList()

        found.filterNotNull().forEach { selected ->
            val url = selected.url?.trim() ?: return@forEach
            val title = selected.name?.trim() ?: return@forEach
            if (url.isBlank() || title.isBlank() || !seen.add(url)) return@forEach

            results.add(
                newMovieSearchResponse(title, url, TvType.Live) {
                    posterUrl =
                        selected
                            .uploaderAvatars
                            .lastOrNull()
                            ?.url
                            ?.takeIf {
                                it.isNotBlank()
                            }
                            ?: selected.thumbnails
                                .lastOrNull()
                                ?.url
                }
            )

            if (results.size >= FAST_VISIBLE_COUNT) return@forEach
        }

        return newHomePageResponse(
            listOf(HomePageList("Live", results.take(FAST_VISIBLE_COUNT), true)),
            true
        )
    }

    private suspend fun buildCuratedLivePageFull(
        page: Int,
        fastMode: Boolean = false,
        forceRefresh: Boolean = false
    ): HomePageResponse {

        if (page > 1) {

            return newHomePageResponse(
                emptyList(),
                false
            )
        }

        if (!fastMode && !forceRefresh) {
            getCachedHomePage(
                liveHomeCache,
                "live"
            )?.let {
                return it
            }
        }

        val results =
            mutableListOf<SearchResponse>()

        val seenUrls =
            mutableSetOf<String>()

        /*
         * Live status is checked in small parallel batches.
         *
         * Cache = 60 seconds.
         */

        for (
            batch in allowedLiveChannels.chunked(
                8
            )
        ) {

            val found =
                coroutineScope {

                    batch.map { channel ->

                        async(
                            Dispatchers.IO
                        ) {

                            withTimeoutOrNull(
                                8_000L
                            ) {

                                try {

                                    val extractor =
                                        service.getSearchExtractor(
                                            "$channel live"
                                        )

                                    extractor.fetchPage()

                                    selectOldestLiveForChannel(
                                        channel,
                                        extractor
                                            .initialPage
                                            .items
                                    )

                                } catch (
                                    _: Exception
                                ) {

                                    null
                                }

                            }
                        }

                    }.awaitAll()
                }

            for (
                selected in found.filterNotNull()
            ) {

                val url =
                    selected.url
                        ?.trim()
                        ?: continue

                if (
                    url.isBlank() ||
                    !seenUrls.add(url)
                ) {
                    continue
                }

                val title =
                    selected.name
                        ?.trim()
                        ?: continue

                if (
                    title.isBlank()
                ) {
                    continue
                }

                results.add(
                    newMovieSearchResponse(
                        title,
                        url,
                        TvType.Live
                    ) {

                        posterUrl =
                            selected
                                .uploaderAvatars
                                .lastOrNull()
                                ?.url
                                ?.takeIf {
                                    it.isNotBlank()
                                }
                                ?: selected
                                    .thumbnails
                                    .lastOrNull()
                                    ?.url
                    }
                )
            }
        }

        val response =
            newHomePageResponse(
                listOf(
                    HomePageList(
                        "Live",
                        results.take(FAST_VISIBLE_COUNT),
                        true
                    )
                ),
                true
            )

        putCachedHomePage(
            liveHomeCache,
            "live",
            response,
            60 * 1000L
        )

        putCachedResponses("live", results, 60 * 1000L)

        return response
    }

    /*
     * --------------------------------------------------
     * OLDEST LIVE
     * --------------------------------------------------
     */

    private fun selectOldestLiveForChannel(
        allowedChannel: String,
        items: List<InfoItem>
    ): StreamInfoItem? {

        val candidates =
            mutableListOf<StreamInfoItem>()

        for (item in items) {

            if (
                item !is StreamInfoItem
            ) {
                continue
            }

            if (
                item.streamType !=
                StreamType.LIVE_STREAM
            ) {
                continue
            }

            val uploader =
                item.uploaderName
                    ?.trim()
                    ?: continue

            if (
                !isSameChannel(
                    uploader,
                    allowedChannel
                )
            ) {
                continue
            }

            val url =
                item.url
                    ?: continue

            if (
                url.isBlank()
            ) {
                continue
            }

            candidates.add(
                item
            )
        }

        if (
            candidates.isEmpty()
        ) {
            return null
        }

        // Keep this runtime-compatible with CloudStream builds whose bundled
        // NewPipeExtractor does not expose DateWrapper.getInstant().
        // Search ordering supplies the available live candidate; no DateWrapper
        // method is invoked here.
        return candidates.firstOrNull()
    }

    /*
     * --------------------------------------------------
     * RELIGION
     * --------------------------------------------------
     *
     * FINAL ORDER:
     *
     * 1. Bengali dubbed religious serials
     * 2. Hindi versions of those serials
     * 3. Other Hindi religious/mythological serials
     *
     * Bengali and Hindi are deduplicated separately.
     */

    private enum class ReligionLanguage {
        BENGALI,
        HINDI
    }

    private data class ReligionPlaylistCandidate(
        val title: String,
        val url: String,
        val thumbnail: String?,
        val uploader: String,
        val language: ReligionLanguage,
        val seriesKey: String,
        val score: Int
    )

    private fun religionCandidateToSearchResponse(
        candidate: ReligionPlaylistCandidate
    ): SearchResponse {

        return newMovieSearchResponse(
            candidate.title,
            candidate.url,
            TvType.TvSeries
        ) {

            posterUrl =
                candidate.thumbnail
        }
    }


    private suspend fun getReligionPage(page: Int): HomePageResponse {
        if (page > 1) {
            return getCachedHomePageChunk(
                "religion",
                "Religion",
                page
            )
        }

        val cached = cachedResponses("religion", "religion")
        if (cached.isNotEmpty()) {
            scheduleBackgroundRefresh("religion") {
                buildReligionPageFull(1, fastMode = false, forceRefresh = true)
            }
            return newHomePageResponse(
                listOf(HomePageList("Religion", cached.take(FAST_VISIBLE_COUNT), cached.size > FAST_VISIBLE_COUNT)),
                false
            )
        }

        val fast = getReligionFastPage()
        scheduleBackgroundRefresh("religion") {
            buildReligionPageFull(1, fastMode = false, forceRefresh = true)
        }
        return fast
    }

    private suspend fun getReligionFastPage(): HomePageResponse {
        val results = mutableListOf<SearchResponse>()
        val seenKeys = mutableSetOf<String>()

        suspend fun findFastBengali(show: BengaliDubbedShow): ReligionPlaylistCandidate? {
            val queries = show.bengaliNames.take(1).map { "$it playlist" }
            val items = fetchSearchItemsInBatches(queries, 1, fastMode = true).flatten()

            return items.asSequence()
                .filterIsInstance<PlaylistInfoItem>()
                .mapNotNull { item ->
                    val title = item.name?.trim().orEmpty()
                    val url = item.url?.trim().orEmpty()
                    val uploader = item.uploaderName?.trim().orEmpty()
                    if (title.isBlank() || url.isBlank() || !url.contains("playlist?list=", true)) null
                    else if (!isValidBengaliDubbedPlaylist(title, uploader, show)) null
                    else ReligionPlaylistCandidate(
                        title,
                        url,
                        item.thumbnails.lastOrNull()?.url,
                        uploader,
                        ReligionLanguage.BENGALI,
                        show.key,
                        calculateBengaliPlaylistScore(title, uploader, show)
                    )
                }
                .maxByOrNull { it.score }
        }

        suspend fun findFastHindi(show: BengaliDubbedShow): ReligionPlaylistCandidate? {
            val queries = show.hindiNames.take(1).map { "$it playlist" }
            val items = fetchSearchItemsInBatches(queries, 1, fastMode = true).flatten()

            return items.asSequence()
                .filterIsInstance<PlaylistInfoItem>()
                .mapNotNull { item ->
                    val title = item.name?.trim().orEmpty()
                    val url = item.url?.trim().orEmpty()
                    val uploader = item.uploaderName?.trim().orEmpty()
                    if (title.isBlank() || url.isBlank() || !url.contains("playlist?list=", true)) null
                    else if (!isValidHindiPlaylist(title, uploader, show)) null
                    else ReligionPlaylistCandidate(
                        title,
                        url,
                        item.thumbnails.lastOrNull()?.url,
                        uploader,
                        ReligionLanguage.HINDI,
                        show.key,
                        calculateHindiPlaylistScore(title, uploader, show)
                    )
                }
                .maxByOrNull { it.score }
        }

        val bengali =
            withTimeoutOrNull(2_400L) {
                coroutineScope {
                    bengaliDubbedShows
                        .take(3)
                        .map { show -> async { findFastBengali(show) } }
                        .awaitAll()
                        .filterNotNull()
                }
            } ?: emptyList()

        val hindi =
            withTimeoutOrNull(2_400L) {
                coroutineScope {
                    bengaliDubbedShows
                        .take(3)
                        .map { show -> async { findFastHindi(show) } }
                        .awaitAll()
                        .filterNotNull()
                }
            } ?: emptyList()

        (bengali + hindi).forEach { candidate ->
            if (!seenKeys.add(candidate.seriesKey)) {
                // Keep Bengali and Hindi entries separately in the cache/UI.
                // Use the full URL as the uniqueness key when language differs.
                if (candidate.language == ReligionLanguage.BENGALI) return@forEach
            }

            results.add(religionCandidateToSearchResponse(candidate))
        }

        return newHomePageResponse(
            listOf(HomePageList("Religion", results.take(FAST_VISIBLE_COUNT), true)),
            true
        )
    }

    private suspend fun buildReligionPageFull(
        page: Int,
        fastMode: Boolean = false,
        forceRefresh: Boolean = false
    ): HomePageResponse {

        if (page > 1) {

            return newHomePageResponse(
                emptyList(),
                false
            )
        }

        if (!fastMode && !forceRefresh) {
            getCachedHomePage(
                religionHomeCache,
                "religion"
            )?.let {
                return it
            }
        }

        val bengaliResults =
            mutableListOf<ReligionPlaylistCandidate>()

        val hindiResults =
            mutableListOf<ReligionPlaylistCandidate>()

        /*
         * Bengali first.
         */

        for (
            batch in bengaliDubbedShows.chunked(
                4
            )
        ) {

            val found =
                coroutineScope {

                    batch.map { show ->

                        async {

                            findBestBengaliPlaylist(
                                show
                            )
                        }

                    }.awaitAll()
                }

            found
                .filterNotNull()
                .forEach {
                    bengaliResults.add(
                        it
                    )
                }
        }

        /*
         * Hindi versions of the same shows.
         */

        for (
            batch in bengaliDubbedShows.chunked(
                4
            )
        ) {

            val found =
                coroutineScope {

                    batch.map { show ->

                        async {

                            findBestHindiPlaylist(
                                show
                            )
                        }

                    }.awaitAll()
                }

            found
                .filterNotNull()
                .forEach {
                    hindiResults.add(
                        it
                    )
                }
        }

        /*
         * Additional Hindi shows.
         */

        val existingHindiKeys =
            hindiResults
                .map {
                    it.seriesKey
                }
                .toMutableSet()

        for (
            batch in additionalHindiReligionShows.chunked(
                6
            )
        ) {

            if (
                hindiResults.size >= 30
            ) {
                break
            }

            val found =
                coroutineScope {

                    batch.map { query ->

                        async {

                            findBestAdditionalHindiPlaylist(
                                query
                            )
                        }

                    }.awaitAll()
                }

            for (
                candidate in found.filterNotNull()
            ) {

                if (
                    hindiResults.size >= 30
                ) {
                    break
                }

                if (
                    !existingHindiKeys.add(
                        candidate.seriesKey
                    )
                ) {
                    continue
                }

                hindiResults.add(
                    candidate
                )
            }
        }

        val finalResults =
            mutableListOf<SearchResponse>()

        /*
         * Bengali first.
         */

        finalResults.addAll(
            bengaliResults
                .distinctBy {
                    it.seriesKey
                }
                .map {
                    religionCandidateToSearchResponse(
                        it
                    )
                }
        )

        /*
         * Hindi second.
         */

        finalResults.addAll(
            hindiResults
                .distinctBy {
                    it.seriesKey
                }
                .map {
                    religionCandidateToSearchResponse(
                        it
                    )
                }
        )

        val response =
            newHomePageResponse(
                listOf(
                    HomePageList(
                        "Religion",
                        finalResults.take(FAST_VISIBLE_COUNT),
                        true
                    )
                ),
                true
            )

        putCachedHomePage(
            religionHomeCache,
            "religion",
            response,
            30 * 60 * 1000L
        )

        putCachedResponses("religion", finalResults, 30 * 60 * 1000L)

        return response
    }

    /*
     * --------------------------------------------------
     * BEST BENGALI PLAYLIST
     * --------------------------------------------------
     */

    private suspend fun findBestBengaliPlaylist(
        show: BengaliDubbedShow
    ): ReligionPlaylistCandidate? {

        val queries =
            show.bengaliNames
                .take(3)
                .flatMap {
                    listOf(
                        "$it playlist",
                        "$it full episodes playlist"
                    )
                }

        val candidates =
            mutableListOf<ReligionPlaylistCandidate>()

        val seenUrls =
            mutableSetOf<String>()

        for (
            items in fetchSearchItemsInBatches(
                queries,
                4
            )
        ) {

            for (item in items) {

                if (
                    item !is PlaylistInfoItem
                ) {
                    continue
                }

                val url =
                    item.url
                        ?.trim()
                        ?: continue

                if (
                    url.isBlank() ||
                    !url.contains(
                        "playlist?list=",
                        ignoreCase = true
                    )
                ) {
                    continue
                }

                if (
                    !seenUrls.add(
                        url
                    )
                ) {
                    continue
                }

                val title =
                    item.name
                        ?.trim()
                        ?: continue

                if (
                    title.isBlank()
                ) {
                    continue
                }

                val uploader =
                    item.uploaderName
                        ?.trim()
                        ?: ""

                if (
                    !isValidBengaliDubbedPlaylist(
                        title,
                        uploader,
                        show
                    )
                ) {
                    continue
                }

                candidates.add(
                    ReligionPlaylistCandidate(
                        title = title,
                        url = url,
                        thumbnail =
                            item.thumbnails
                                .lastOrNull()
                                ?.url,
                        uploader = uploader,
                        language =
                            ReligionLanguage.BENGALI,
                        seriesKey =
                            show.key,
                        score =
                            calculateBengaliPlaylistScore(
                                title,
                                uploader,
                                show
                            )
                    )
                )
            }
        }

        return candidates.maxWithOrNull(
            compareBy<ReligionPlaylistCandidate> {
                it.score
            }.thenByDescending {
                it.title.length
            }
        )
    }

    /*
     * --------------------------------------------------
     * BEST HINDI PLAYLIST
     * --------------------------------------------------
     */

    private suspend fun findBestHindiPlaylist(
        show: BengaliDubbedShow
    ): ReligionPlaylistCandidate? {

        val queries =
            show.hindiNames
                .take(2)
                .flatMap {
                    listOf(
                        "$it playlist",
                        "$it full episodes playlist"
                    )
                }

        val candidates =
            mutableListOf<ReligionPlaylistCandidate>()

        val seenUrls =
            mutableSetOf<String>()

        for (
            items in fetchSearchItemsInBatches(
                queries,
                4
            )
        ) {

            for (item in items) {

                if (
                    item !is PlaylistInfoItem
                ) {
                    continue
                }

                val url =
                    item.url
                        ?.trim()
                        ?: continue

                if (
                    url.isBlank() ||
                    !url.contains(
                        "playlist?list=",
                        ignoreCase = true
                    )
                ) {
                    continue
                }

                if (
                    !seenUrls.add(
                        url
                    )
                ) {
                    continue
                }

                val title =
                    item.name
                        ?.trim()
                        ?: continue

                if (
                    title.isBlank()
                ) {
                    continue
                }

                val uploader =
                    item.uploaderName
                        ?.trim()
                        ?: ""

                if (
                    !isValidHindiPlaylist(
                        title,
                        uploader,
                        show
                    )
                ) {
                    continue
                }

                candidates.add(
                    ReligionPlaylistCandidate(
                        title = title,
                        url = url,
                        thumbnail =
                            item.thumbnails
                                .lastOrNull()
                                ?.url,
                        uploader = uploader,
                        language =
                            ReligionLanguage.HINDI,
                        seriesKey =
                            show.key,
                        score =
                            calculateHindiPlaylistScore(
                                title,
                                uploader,
                                show
                            )
                    )
                )
            }
        }

        return candidates.maxWithOrNull(
            compareBy<ReligionPlaylistCandidate> {
                it.score
            }.thenByDescending {
                it.title.length
            }
        )
    }

    /*
     * --------------------------------------------------
     * ADDITIONAL HINDI PLAYLIST
     * --------------------------------------------------
     */

    private suspend fun findBestAdditionalHindiPlaylist(
        query: String
    ): ReligionPlaylistCandidate? {

        val items =
            fetchSearchItemsInBatches(
                listOf(
                    "$query playlist",
                    "$query full episodes playlist"
                ),
                2
            ).flatten()

        val candidates =
            mutableListOf<ReligionPlaylistCandidate>()

        val seenUrls =
            mutableSetOf<String>()

        for (item in items) {

            if (
                item !is PlaylistInfoItem
            ) {
                continue
            }

            val url =
                item.url
                    ?.trim()
                    ?: continue

            if (
                url.isBlank() ||
                !url.contains(
                    "playlist?list=",
                    ignoreCase = true
                )
            ) {
                continue
            }

            if (
                !seenUrls.add(
                    url
                )
            ) {
                continue
            }

            val title =
                item.name
                    ?.trim()
                    ?: continue

            if (
                title.isBlank()
            ) {
                continue
            }

            val uploader =
                item.uploaderName
                    ?.trim()
                    ?: ""

            if (
                !isValidGeneralHindiReligionPlaylist(
                    title,
                    uploader
                )
            ) {
                continue
            }

            val seriesKey =
                detectReligionSeriesKey(
                    "$query $title"
                ) ?: continue

            candidates.add(
                ReligionPlaylistCandidate(
                    title = title,
                    url = url,
                    thumbnail =
                        item.thumbnails
                            .lastOrNull()
                            ?.url,
                    uploader = uploader,
                    language =
                        ReligionLanguage.HINDI,
                    seriesKey =
                        seriesKey,
                    score =
                        calculateGeneralHindiScore(
                            title,
                            uploader
                        )
                )
            )
        }

        return candidates.maxWithOrNull(
            compareBy<ReligionPlaylistCandidate> {
                it.score
            }.thenByDescending {
                it.title.length
            }
        )
    }

    /*
     * --------------------------------------------------
     * BENGALI VALIDATION
     * --------------------------------------------------
     */

    private fun isValidBengaliDubbedPlaylist(
        title: String,
        uploader: String,
        show: BengaliDubbedShow
    ): Boolean {

        val combined =
            "$title $uploader"
                .lowercase()

        if (
            containsAny(
                combined,
                religionExcludeKeywords
            )
        ) {
            return false
        }

        if (
            containsAny(
                combined,
                religionBangladeshKeywords
            )
        ) {
            return false
        }

        val matchesShow =
            show.bengaliNames.any {
                combined.contains(
                    it.lowercase()
                )
            }

        val officialChannel =
            show.officialChannels.any {
                combined.contains(
                    it.lowercase()
                )
            }

        if (
            !matchesShow &&
            !officialChannel
        ) {
            return false
        }

        return true
    }

    /*
     * --------------------------------------------------
     * HINDI VALIDATION
     * --------------------------------------------------
     */

    private fun isValidHindiPlaylist(
        title: String,
        uploader: String,
        show: BengaliDubbedShow
    ): Boolean {

        val combined =
            "$title $uploader"
                .lowercase()

        if (
            containsAny(
                combined,
                religionExcludeKeywords
            )
        ) {
            return false
        }

        if (
            containsAny(
                combined,
                religionBangladeshKeywords
            )
        ) {
            return false
        }

        val matchesShow =
            show.hindiNames.any {
                combined.contains(
                    it.lowercase()
                )
            }

        val officialChannel =
            show.officialChannels.any {
                combined.contains(
                    it.lowercase()
                )
            }

        if (
            !matchesShow &&
            !officialChannel
        ) {
            return false
        }

        return true
    }

    /*
     * --------------------------------------------------
     * GENERAL HINDI RELIGION VALIDATION
     * --------------------------------------------------
     */

    private fun isValidGeneralHindiReligionPlaylist(
        title: String,
        uploader: String
    ): Boolean {

        val combined =
            "$title $uploader"
                .lowercase()

        if (
            containsAny(
                combined,
                religionExcludeKeywords
            )
        ) {
            return false
        }

        if (
            containsAny(
                combined,
                religionBangladeshKeywords
            )
        ) {
            return false
        }

        val hasHindiSignal =
            combined.contains(
                "hindi"
            ) ||
                combined.contains(
                    "india"
                ) ||
                combined.contains(
                    "serial"
                ) ||
                combined.contains(
                    "episodes"
                ) ||
                combined.contains(
                    "star plus"
                ) ||
                combined.contains(
                    "colors"
                ) ||
                combined.contains(
                    "sony"
                ) ||
                combined.contains(
                    "life ok"
                )

        if (
            !hasHindiSignal
        ) {
            return false
        }

        return detectReligionSeriesKey(
            combined
        ) != null
    }

    /*
     * --------------------------------------------------
     * BENGALI SCORE
     * --------------------------------------------------
     */

    private fun calculateBengaliPlaylistScore(
        title: String,
        uploader: String,
        show: BengaliDubbedShow
    ): Int {

        val combined =
            "$title $uploader"
                .lowercase()

        var score = 100

        if (
            combined.contains(
                "bangla"
            )
        ) {
            score += 35
        }

        if (
            combined.contains(
                "bengali"
            )
        ) {
            score += 35
        }

        if (
            combined.contains(
                "বাংলা"
            )
        ) {
            score += 40
        }

        if (
            combined.contains(
                "dub"
            )
        ) {
            score += 20
        }

        if (
            combined.contains(
                "ডাব"
            )
        ) {
            score += 20
        }

        if (
            combined.contains(
                "full episodes"
            )
        ) {
            score += 25
        }

        if (
            combined.contains(
                "complete episodes"
            )
        ) {
            score += 25
        }

        if (
            combined.contains(
                "all episodes"
            )
        ) {
            score += 20
        }

        if (
            combined.contains(
                "সম্পূর্ণ"
            )
        ) {
            score += 20
        }

        for (
            channel in show.officialChannels
        ) {

            if (
                combined.contains(
                    channel.lowercase()
                )
            ) {
                score += 80
            }
        }

        if (
            combined.contains(
                "official"
            )
        ) {
            score += 100
        }

        if (
            combined.contains(
                "my playlist"
            )
        ) {
            score -= 100
        }

        if (
            combined.contains(
                "fan made"
            )
        ) {
            score -= 100
        }

        if (
            combined.contains(
                "fanmade"
            )
        ) {
            score -= 100
        }

        if (
            combined.contains(
                "collection"
            )
        ) {
            score -= 60
        }

        if (
            combined.contains(
                "saved"
            )
        ) {
            score -= 60
        }

        if (
            combined.contains(
                "backup"
            )
        ) {
            score -= 60
        }

        if (
            combined.contains(
                "reupload"
            )
        ) {
            score -= 80
        }

        if (
            combined.contains(
                "archive"
            )
        ) {
            score -= 50
        }

        return score
    }

    /*
     * --------------------------------------------------
     * HINDI SCORE
     * --------------------------------------------------
     */

    private fun calculateHindiPlaylistScore(
        title: String,
        uploader: String,
        show: BengaliDubbedShow
    ): Int {

        val combined =
            "$title $uploader"
                .lowercase()

        var score = 100

        if (
            combined.contains(
                "hindi"
            )
        ) {
            score += 25
        }

        if (
            combined.contains(
                "full episodes"
            )
        ) {
            score += 25
        }

        if (
            combined.contains(
                "complete episodes"
            )
        ) {
            score += 25
        }

        if (
            combined.contains(
                "all episodes"
            )
        ) {
            score += 20
        }

        for (
            channel in show.officialChannels
        ) {

            if (
                combined.contains(
                    channel.lowercase()
                )
            ) {
                score += 80
            }
        }

        if (
            combined.contains(
                "official"
            )
        ) {
            score += 100
        }

        if (
            combined.contains(
                "my playlist"
            )
        ) {
            score -= 100
        }

        if (
            combined.contains(
                "fan made"
            )
        ) {
            score -= 100
        }

        if (
            combined.contains(
                "fanmade"
            )
        ) {
            score -= 100
        }

        if (
            combined.contains(
                "collection"
            )
        ) {
            score -= 60
        }

        if (
            combined.contains(
                "saved"
            )
        ) {
            score -= 60
        }

        if (
            combined.contains(
                "backup"
            )
        ) {
            score -= 60
        }

        if (
            combined.contains(
                "reupload"
            )
        ) {
            score -= 80
        }

        return score
    }

    /*
     * --------------------------------------------------
     * GENERAL HINDI SCORE
     * --------------------------------------------------
     */

    private fun calculateGeneralHindiScore(
        title: String,
        uploader: String
    ): Int {

        val combined =
            "$title $uploader"
                .lowercase()

        var score = 100

        if (
            combined.contains(
                "official"
            )
        ) {
            score += 100
        }

        if (
            combined.contains(
                "full episodes"
            )
        ) {
            score += 30
        }

        if (
            combined.contains(
                "complete episodes"
            )
        ) {
            score += 30
        }

        if (
            combined.contains(
                "all episodes"
            )
        ) {
            score += 20
        }

        if (
            combined.contains(
                "star plus"
            )
        ) {
            score += 70
        }

        if (
            combined.contains(
                "colors"
            )
        ) {
            score += 70
        }

        if (
            combined.contains(
                "sony"
            )
        ) {
            score += 70
        }

        if (
            combined.contains(
                "life ok"
            )
        ) {
            score += 60
        }

        if (
            combined.contains(
                "star bharat"
            )
        ) {
            score += 70
        }

        if (
            combined.contains(
                "fan"
            )
        ) {
            score -= 80
        }

        if (
            combined.contains(
                "collection"
            )
        ) {
            score -= 60
        }

        if (
            combined.contains(
                "reupload"
            )
        ) {
            score -= 80
        }

        return score
    }

    /*
     * --------------------------------------------------
     * DETECT RELIGION SERIES KEY
     * --------------------------------------------------
     */

    private fun detectReligionSeriesKey(
        text: String
    ): String? {

        val normalized =
            normalizeReligionText(
                text
            )

        val ordered =
            religionSeriesAliases.entries
                .sortedByDescending {
                    it.value.maxOfOrNull {
                        alias ->
                        alias.length
                    } ?: 0
                }

        for (
            (key, aliases)
            in ordered
        ) {

            for (
                alias in aliases
            ) {

                val normalizedAlias =
                    normalizeReligionText(
                        alias
                    )

                if (
                    normalized.contains(
                        normalizedAlias
                    )
                ) {
                    return key
                }
            }
        }

        return null
    }

    /*
     * --------------------------------------------------
     * NORMALIZE RELIGION TEXT
     * --------------------------------------------------
     */

    private fun normalizeReligionText(
        value: String
    ): String {

        return value
            .lowercase()
            .replace(
                Regex(
                    "[^\\p{L}\\p{N}]+"
                ),
                ""
            )
    }

    /*
     * --------------------------------------------------
     * STRING HELPERS
     * --------------------------------------------------
     */

    private fun containsAny(
        text: String,
        keywords: List<String>
    ): Boolean {

        val lower =
            text.lowercase()

        return keywords.any {
            lower.contains(
                it.lowercase()
            )
        }
    }

    /*
     * --------------------------------------------------
     * CHANNEL COMPARISON
     * --------------------------------------------------
     */

    private fun isSameChannel(
        first: String,
        second: String
    ): Boolean {

        return normalizeChannelName(
            first
        ) ==
            normalizeChannelName(
                second
            )
    }

    private fun normalizeChannelName(
        value: String
    ): String {

        return value
            .lowercase()
            .replace(
                Regex(
                    "[^a-z0-9]+"
                ),
                ""
            )
    }

    /*
     * --------------------------------------------------
     * NORMAL SEARCH
     * --------------------------------------------------
     */

    private suspend fun buildGenericKioskFast(
        kioskId: String,
        sectionName: String,
        cacheSection: String
    ): HomePageResponse {
        val pageData =
            try {
                val extractor = getKioskExtractor(kioskId)

                withTimeoutOrNull(1_650L) {
                    extractor.fetchPage()
                    extractor.initialPage
                }
            } catch (_: Exception) {
                null
            }

        if (pageData != null) {
            pageCache[kioskId] = pageData.nextPage

            if (pageData.nextPage != null) {
                genericContinuationCache[kioskId] = pageData.nextPage
            } else {
                genericContinuationCache.remove(kioskId)
            }
        }

        val results =
            pageData
                ?.items
                ?.map { it.toSearchResponse() }
                ?.take(FAST_VISIBLE_COUNT)
                ?: emptyList()

        if (results.isNotEmpty()) {
            putCachedResponses(cacheSection, results, 15 * 60 * 1000L)
        }

        return newHomePageResponse(
            listOf(
                HomePageList(
                    sectionName,
                    results.take(FAST_VISIBLE_COUNT),
                    true
                )
            ),
            true
        )
    }

    private suspend fun refreshGenericKioskHome(
        kioskId: String,
        sectionName: String,
        cacheSection: String
    ) {
        val fresh =
            try {
                withTimeoutOrNull(8_000L) {
                    val extractor =
                        getKioskExtractor(kioskId)

                    extractor.fetchPage()

                    val collected =
                        mutableListOf<SearchResponse>()

                    var page =
                        extractor.initialPage

                    repeat(
                        GENERIC_BACKGROUND_PAGES
                    ) {
                        collected.addAll(
                            page.items.map {
                                it.toSearchResponse()
                            }
                        )

                        if (
                            collected.size >=
                            HOME_CACHE_LIMIT ||
                            !page.hasNextPage()
                        ) {
                            return@repeat
                        }

                        page =
                            extractor.getPage(
                                page.nextPage
                            )
                    }

                    if (page.nextPage != null) {
                        genericContinuationCache[
                            kioskId
                        ] = page.nextPage
                    } else {
                        genericContinuationCache.remove(kioskId)
                    }

                    collected
                        .distinctBy {
                            it.url
                        }
                        .take(
                            HOME_CACHE_LIMIT
                        )
                } ?: emptyList()
            } catch (_: Exception) {
                emptyList()
            }

        if (fresh.isEmpty()) return

        putCachedResponses(
            cacheSection,
            fresh,
            15 * 60 * 1000L
        )
    }

    override suspend fun search(
        query: String,
        page: Int
    ): SearchResponseList {

        val cleanQuery =
            query.trim()

        if (
            cleanQuery.isBlank()
        ) {

            return newSearchResponseList(
                emptyList(),
                false
            )
        }

        val cacheKey =
            cleanQuery.lowercase()

        val extractor =
            service.getSearchExtractor(
                cleanQuery
            )

        val pageData =
            try {

                if (
                    page == 1 ||
                    !searchPageCache.containsKey(
                        cacheKey
                    )
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
                        ] ?: return newSearchResponseList(
                            emptyList(),
                            false
                        )

                    extractor
                        .getPage(
                            next
                        )
                        .also {

                            searchPageCache[
                                cacheKey
                            ] =
                                it.nextPage
                        }
                }

            } catch (
                _: Exception
            ) {

                return newSearchResponseList(
                    emptyList(),
                    false
                )
            }

        val results =
            pageData.items.map {
                it.toSearchResponse()
            }

        return newSearchResponseList(
            results,
            pageData.hasNextPage()
        )
    }

    /*
     * --------------------------------------------------
     * QUICK SEARCH
     * --------------------------------------------------
     */

    override suspend fun quickSearch(
        query: String
    ): List<SearchResponse> {

        return search(
            query,
            1
        ).items
    }

    /*
     * --------------------------------------------------
     * KIOSK
     * --------------------------------------------------
     */

    private fun getKioskExtractor(
        kioskId: String?
    ): KioskExtractor<out InfoItem> {

        return if (
            kioskId.isNullOrBlank()
        ) {

            service.kioskList
                .getDefaultKioskExtractor(
                    null
                )

        } else {

            service.kioskList
                .getExtractorById(
                    kioskId,
                    null
                )
        }
    }

    /*
     * --------------------------------------------------
     * INFO ITEM
     * --------------------------------------------------
     */

    private fun InfoItem.toSearchResponse():
        SearchResponse {

        val itemName =
            name ?: "Unknown"

        val itemUrl =
            url ?: ""

        return newMovieSearchResponse(
            itemName,
            itemUrl,
            TvType.Others
        ) {

            posterUrl =
                thumbnails
                    .lastOrNull()
                    ?.url
        }
    }

    /*
     * --------------------------------------------------
     * LOAD
     * --------------------------------------------------
     */

    override suspend fun load(
        url: String
    ): LoadResponse {

        return when (
            getUrlType(
                url
            )
        ) {

            UrlType.Video ->
                loadVideo(
                    url
                )

            UrlType.Channel ->
                loadChannel(
                    url
                )

            UrlType.Playlist ->
                loadPlaylist(
                    url
                )

            UrlType.Unknown ->
                throw RuntimeException(
                    "Unsupported YouTube URL"
                )
        }
    }

    private enum class UrlType {
        Video,
        Channel,
        Playlist,
        Unknown
    }

    private fun getUrlType(
        url: String
    ): UrlType {

        val cleanUrl =
            url.lowercase()

        return when {

            cleanUrl.contains(
                "/watch?v="
            ) ->
                UrlType.Video

            cleanUrl.contains(
                "youtu.be/"
            ) ->
                UrlType.Video

            cleanUrl.contains(
                "/shorts/"
            ) ->
                UrlType.Video

            cleanUrl.contains(
                "/channel/"
            ) ->
                UrlType.Channel

            cleanUrl.contains(
                "/@"
            ) ->
                UrlType.Channel

            cleanUrl.contains(
                "/c/"
            ) ->
                UrlType.Channel

            cleanUrl.contains(
                "/user/"
            ) ->
                UrlType.Channel

            cleanUrl.contains(
                "/playlist?list="
            ) ->
                UrlType.Playlist

            else ->
                UrlType.Unknown
        }
    }

    /*
     * --------------------------------------------------
     * VIDEO
     * --------------------------------------------------
     */

    private suspend fun loadVideo(
        url: String
    ): LoadResponse {

        val extractor =
            service.getStreamExtractor(
                url
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
                    "LIVE"
                ) == true

        return newMovieLoadResponse(
            info.name,
            url,
            if (isLive) {
                TvType.Live
            } else {
                TvType.Others
            },
            url
        ) {

            plot =
                info.description
                    .content
                    .toString()

            posterUrl =
                info.thumbnails
                    .lastOrNull()
                    ?.url

            if (
                info.duration > 0
            ) {

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
                info.tags
                    ?.take(5)
                    ?.toList()
        }
    }

    /*
     * --------------------------------------------------
     * CHANNEL
     * --------------------------------------------------
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

        val channelDescription =
            extractor.description

        val channelAvatar =
            extractor.avatars
                .lastOrNull()
                ?.url

        val channelBanner =
            extractor.banners
                .lastOrNull()
                ?.url

        val tabs =
            extractor.tabs

        val videosTab =
            tabs.firstOrNull {
                it.url.contains(
                    "/videos"
                )
            }
                ?: tabs.firstOrNull()
                ?: throw RuntimeException(
                    "No videos tab found"
                )

        val videosExtractor =
            service.getChannelTabExtractor(
                videosTab
            )

        val episodes =
            mutableListOf<Episode>()

        var page =
            videosExtractor.initialPage

        episodes.addAll(
            page.items.map { item ->

                newEpisode(
                    item.url
                ) {

                    name =
                        item.name

                    posterUrl =
                        item.thumbnails
                            .lastOrNull()
                            ?.url
                }
            }
        )

        var pagesLoaded =
            1

        val maxPages =
            5

        while (
            page.hasNextPage() &&
            pagesLoaded < maxPages
        ) {

            page =
                videosExtractor.getPage(
                    page.nextPage
                )

            episodes.addAll(
                page.items.map { item ->

                    newEpisode(
                        item.url
                    ) {

                        name =
                            item.name

                        posterUrl =
                            item.thumbnails
                                .lastOrNull()
                                ?.url
                    }
                }
            )

            pagesLoaded++
        }

        return newTvSeriesLoadResponse(
            channelName,
            url,
            TvType.TvSeries,
            episodes
        ) {

            plot =
                channelDescription

            posterUrl =
                channelBanner

            backgroundPosterUrl =
                channelBanner

            tags =
                listOf(
                    "Channel"
                )

            actors =
                listOf(
                    ActorData(
                        Actor(
                            channelName,
                            channelAvatar ?: ""
                        )
                    )
                )
        }
    }

    /*
     * --------------------------------------------------
     * PLAYLIST
     * --------------------------------------------------
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

        val playlistDescription =
            extractor.description
                .content
                .toString()

        val playlistThumbnail =
            extractor.thumbnails
                .lastOrNull()
                ?.url

        val uploaderName =
            extractor.uploaderName

        val episodes =
            mutableListOf<Episode>()

        /*
         * First playlist page.
         */

        var page =
            extractor.getInitialPage()

        episodes.addAll(
            page.items.map { item ->

                newEpisode(
                    item.url
                ) {

                    name =
                        item.name

                    posterUrl =
                        item.thumbnails
                            .lastOrNull()
                            ?.url
                }
            }
        )

        /*
         * Additional playlist pages.
         */

        var pagesLoaded =
            1

        /*
         * Up to 25 pages so long playlists can expose
         * many episodes.
         */

        val maxPages =
            25

        while (
            page.hasNextPage() &&
            pagesLoaded < maxPages
        ) {

            page =
                extractor.getPage(
                    page.nextPage
                )

            episodes.addAll(
                page.items.map { item ->

                    newEpisode(
                        item.url
                    ) {

                        name =
                            item.name

                        posterUrl =
                            item.thumbnails
                                .lastOrNull()
                                ?.url
                    }
                }
            )

            pagesLoaded++
        }

        return newTvSeriesLoadResponse(
            playlistName,
            url,
            TvType.TvSeries,
            episodes
        ) {

            plot =
                playlistDescription

            posterUrl =
                playlistThumbnail

            tags =
                if (
                    uploaderName.isNotBlank()
                ) {

                    listOf(
                        "Channel: $uploaderName"
                    )

                } else {

                    listOf(
                        "Playlist"
                    )
                }

            if (
                uploaderName.isNotBlank()
            ) {

                actors =
                    listOf(
                        ActorData(
                            Actor(
                                uploaderName,
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
     * --------------------------------------------------
     * PLAYBACK
     * --------------------------------------------------
     *
     * IMPORTANT:
     *
     * VOD  -> DASH
     * LIVE -> HLS
     *
     * Direct playback is kept.
     * No proxy/server is added.
     */

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {

        if (
            data.isBlank()
        ) {
            return false
        }

        val extractor =
            try {

                service.getStreamExtractor(
                    data
                )

            } catch (
                _: Exception
            ) {

                return loadExtractor(
                    data,
                    subtitleCallback,
                    callback
                )
            }

        try {

            extractor.fetchPage()

            val info =
                StreamInfo.getInfo(
                    extractor
                )

            val isLive =
                info.streamType
                    ?.name
                    ?.contains(
                        "LIVE"
                    ) == true

            /*
             * LIVE -> HLS
             */

            if (
                isLive
            ) {

                val hlsUrl =
                    runCatching {
                        info.hlsUrl
                    }.getOrNull()

                if (
                    !hlsUrl.isNullOrBlank()
                ) {

                    callback(
                        newExtractorLink(
                            source = name,
                            name = "$name Live",
                            url = hlsUrl,
                            type =
                                ExtractorLinkType.M3U8
                        ) {

                            referer =
                                "https://www.youtube.com/"

                            quality =
                                Qualities
                                    .Unknown
                                    .value
                        }
                    )

                    return true
                }
            }

            /*
             * VOD -> DASH
             */

            val dashUrl =
                runCatching {
                    info.dashMpdUrl
                }.getOrNull()

            if (
                !dashUrl.isNullOrBlank()
            ) {

                callback(
                    newExtractorLink(
                        source = name,
                        name = "$name Adaptive",
                        url = dashUrl,
                        type =
                            ExtractorLinkType.DASH
                    ) {

                        referer =
                            "https://www.youtube.com/"

                        quality =
                            Qualities
                                .Unknown
                                .value
                    }
                )

                return true
            }

            /*
             * HLS fallback.
             */

            val hlsUrl =
                runCatching {
                    info.hlsUrl
                }.getOrNull()

            if (
                !hlsUrl.isNullOrBlank()
            ) {

                callback(
                    newExtractorLink(
                        source = name,
                        name = "$name HLS",
                        url = hlsUrl,
                        type =
                            ExtractorLinkType.M3U8
                    ) {

                        referer =
                            "https://www.youtube.com/"

                        quality =
                            Qualities
                                .Unknown
                                .value
                    }
                )

                return true
            }

        } catch (
            _: Exception
        ) {
            /*
             * Fall back to CloudStream's working
             * YouTube extractor.
             */
        }

        return loadExtractor(
            data,
            subtitleCallback,
            callback
        )
    }
}
