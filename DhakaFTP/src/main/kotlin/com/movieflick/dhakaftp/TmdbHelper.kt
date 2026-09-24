package com.movieflick.dhakaftp

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.lagradost.cloudstream3.app
import kotlinx.coroutines.withTimeoutOrNull
import java.net.URLEncoder
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

internal enum class TmdbMediaType {
    MOVIE,
    TV
}

internal data class TmdbMedia(
    val id: Int,
    val mediaType: TmdbMediaType,
    val title: String,
    val originalTitle: String,
    val year: Int?,
    val originalLanguage: String,
    val originCountries: List<String>,
    val isSatyajitRay: Boolean
) {
    val searchTitle: String
        get() = title.ifBlank { originalTitle }
}

internal data class TmdbSearchOutcome(
    val isApiUsable: Boolean,
    val items: List<TmdbMedia>
)

/**
 * DhakaFTP-only TMDB client.
 *
 * Credential rotation is pair-based:
 * - A successful HTTP/API response is considered a usable credential even if
 *   TMDB returns zero search results.
 * - Authentication/rate-limit/network/timeout failures mark that credential
 *   temporarily unhealthy and move to the next pair.
 * - Once the cooldown expires, the earlier credential becomes eligible again.
 */
internal object TmdbHelper {

    private const val BASE_URL = "https://api.themoviedb.org/3"
    private const val REQUEST_TIMEOUT_MS = 2200L
    private const val DETAIL_TIMEOUT_MS = 1800L
    private const val FAILURE_COOLDOWN_MS = 5 * 60 * 1000L
    private const val MAX_SEARCH_RESULTS = 6

    private data class Credential(
        val apiKey: String,
        val accessToken: String
    )

    private val mapper = ObjectMapper()

    private val unhealthyUntil =
        ConcurrentHashMap<Int, Long>()

    private fun credentials(): List<Credential> =
        listOf(
            Credential(
                TmdbSecrets.PRIMARY_API_KEY,
                TmdbSecrets.PRIMARY_ACCESS_TOKEN
            ),
            Credential(
                TmdbSecrets.SECONDARY_API_KEY,
                TmdbSecrets.SECONDARY_ACCESS_TOKEN
            ),
            Credential(
                TmdbSecrets.TERTIARY_API_KEY,
                TmdbSecrets.TERTIARY_ACCESS_TOKEN
            )
        )

    suspend fun search(
        query: String
    ): TmdbSearchOutcome {

        val cleaned =
            query.trim()

        if (cleaned.isBlank()) {
            return TmdbSearchOutcome(
                isApiUsable = false,
                items = emptyList()
            )
        }

        var sawUsableApi = false

        credentials().forEachIndexed { index, credential ->

            if (credential.apiKey.isBlank() && credential.accessToken.isBlank()) {
                return@forEachIndexed
            }

            if (isTemporarilyUnhealthy(index)) {
                return@forEachIndexed
            }

            val response =
                trySearchWithCredential(
                    credential,
                    cleaned
                )

            when (response) {
                is SearchAttempt.Success -> {
                    sawUsableApi = true

                    val parsed =
                        parseSearchResults(
                            response.body
                        )

                    if (parsed.isNotEmpty()) {
                        val enriched =
                            enrichTopMovieMetadata(
                                parsed
                            )

                        return TmdbSearchOutcome(
                            isApiUsable = true,
                            items = enriched
                        )
                    }

                    /*
                     * Important: valid TMDB response + zero results is NOT an
                     * API failure. Do not rotate credentials in this case.
                     */
                    return TmdbSearchOutcome(
                        isApiUsable = true,
                        items = emptyList()
                    )
                }

                SearchAttempt.Failed -> {
                    markUnhealthy(index)
                }
            }
        }

        return TmdbSearchOutcome(
            isApiUsable = sawUsableApi,
            items = emptyList()
        )
    }

    private sealed class SearchAttempt {
        data class Success(
            val body: String
        ) : SearchAttempt()

        data object Failed : SearchAttempt()
    }

    private suspend fun trySearchWithCredential(
        credential: Credential,
        query: String
    ): SearchAttempt {

        if (credential.accessToken.isBlank() && credential.apiKey.isBlank()) {
            return SearchAttempt.Failed
        }

        val encodedQuery =
            URLEncoder.encode(
                query,
                "UTF-8"
            )

        val url =
            if (credential.accessToken.isBlank()) {
                "$BASE_URL/search/multi" +
                    "?api_key=${credential.apiKey}" +
                    "&language=en-US" +
                    "&include_adult=false" +
                    "&page=1" +
                    "&query=$encodedQuery"
            } else {
                "$BASE_URL/search/multi" +
                    "?language=en-US" +
                    "&include_adult=false" +
                    "&page=1" +
                    "&query=$encodedQuery"
            }

        return try {
            val response =
                withTimeoutOrNull(
                    REQUEST_TIMEOUT_MS
                ) {
                    if (credential.accessToken.isBlank()) {
                        app.get(
                            url,
                            headers = mapOf(
                                "accept" to "application/json"
                            )
                        )
                    } else {
                        app.get(
                            url,
                            headers = mapOf(
                                "Authorization" to
                                    "Bearer ${credential.accessToken}",
                                "accept" to "application/json"
                            )
                        )
                    }
                }
                    ?: return SearchAttempt.Failed

            SearchAttempt.Success(
                response.text
            )
        } catch (_: Throwable) {
            SearchAttempt.Failed
        }
    }

    private suspend fun enrichTopMovieMetadata(
        items: List<TmdbMedia>
    ): List<TmdbMedia> {

        val limited =
            items.take(MAX_SEARCH_RESULTS)

        if (limited.isEmpty()) {
            return emptyList()
        }

        val first = limited.first()

        /*
         * One detail request is enough for the routing decision in the fast
         * path. We only need it for Indian/Bengali movie candidates because
         * Satyajit Ray is a special Kolkata collection rule. All other TMDB
         * results keep the original fast search response metadata.
         */
        val needsDetail =
            first.mediaType == TmdbMediaType.MOVIE &&
                (
                    first.originalLanguage == "bn" ||
                        first.originCountries.any { it.equals("IN", true) }
                    )

        if (!needsDetail) {
            return limited
        }

        val enriched =
            enrichMovie(first)

        return listOf(enriched) +
            limited.drop(1)
    }

    private suspend fun enrichMovie(
        item: TmdbMedia
    ): TmdbMedia {

        val credentialIndexes =
            credentials()
                .mapIndexed { index, _ -> index }
                .filterNot(::isTemporarilyUnhealthy)

        for (index in credentialIndexes) {

            val credential =
                credentials()[index]

            if (credential.apiKey.isBlank() && credential.accessToken.isBlank()) {
                continue
            }

            try {
                val url =
                    if (credential.accessToken.isBlank()) {
                        "$BASE_URL/movie/${item.id}" +
                            "?api_key=${credential.apiKey}" +
                            "&language=en-US" +
                            "&append_to_response=credits"
                    } else {
                        "$BASE_URL/movie/${item.id}" +
                            "?language=en-US" +
                            "&append_to_response=credits"
                    }

                val response =
                    withTimeoutOrNull(
                        DETAIL_TIMEOUT_MS
                    ) {
                        if (credential.accessToken.isBlank()) {
                            app.get(
                                url,
                                headers = mapOf(
                                    "accept" to "application/json"
                                )
                            )
                        } else {
                            app.get(
                                url,
                                headers = mapOf(
                                    "Authorization" to
                                        "Bearer ${credential.accessToken}",
                                    "accept" to "application/json"
                                )
                            )
                        }
                    }
                        ?: return item

                val json =
                    mapper.readTree(
                        response.text
                    )

                val language =
                    json.path("original_language")
                        .asText(
                            item.originalLanguage
                        )

                val countries =
                    json.path("production_countries")
                        .toList()
                        .mapNotNull {
                            it.path("iso_3166_1")
                                .asText()
                                .takeIf(String::isNotBlank)
                        }

                val directors =
                    json.path("credits")
                        .path("crew")
                        .toList()
                        .filter {
                            it.path("job")
                                .asText()
                                .equals(
                                    "Director",
                                    true
                                )
                        }
                        .map {
                            it.path("name")
                                .asText()
                        }

                val satyajit =
                    directors.any {
                        normalizePersonName(it) ==
                            "satyajitray"
                    }

                val releaseYear =
                    json.path("release_date")
                        .asText()
                        .take(4)
                        .toIntOrNull()
                        ?: item.year

                return item.copy(
                    year = releaseYear,
                    originalLanguage = language.ifBlank {
                        item.originalLanguage
                    },
                    originCountries =
                        (countries + item.originCountries)
                            .distinct(),
                    isSatyajitRay =
                        satyajit ||
                            item.isSatyajitRay
                )
            } catch (_: Throwable) {
                markUnhealthy(index)
            }
        }

        return item
    }

    private fun parseSearchResults(
        body: String
    ): List<TmdbMedia> {

        return try {
            val json =
                mapper.readTree(
                    body
                )

            val results =
                json.path("results")

            if (!results.isArray) {
                return emptyList()
            }

            results.toList()
                .mapNotNull(::parseSearchItem)
                .take(MAX_SEARCH_RESULTS)
        } catch (_: Throwable) {
            emptyList()
        }
    }

    private fun parseSearchItem(
        node: JsonNode
    ): TmdbMedia? {

        val type =
            node.path("media_type")
                .asText()
                .lowercase(
                    Locale.ROOT
                )

        val mediaType =
            when (type) {
                "movie" -> TmdbMediaType.MOVIE
                "tv" -> TmdbMediaType.TV
                else -> return null
            }

        val title =
            if (mediaType == TmdbMediaType.MOVIE) {
                node.path("title").asText()
            } else {
                node.path("name").asText()
            }

        val originalTitle =
            if (mediaType == TmdbMediaType.MOVIE) {
                node.path("original_title").asText()
            } else {
                node.path("original_name").asText()
            }

        val date =
            if (mediaType == TmdbMediaType.MOVIE) {
                node.path("release_date").asText()
            } else {
                node.path("first_air_date").asText()
            }

        val countries =
            node.path("origin_country")
                .toList()
                .map {
                    it.asText()
                }
                .filter(String::isNotBlank)

        return TmdbMedia(
            id = node.path("id").asInt(-1),
            mediaType = mediaType,
            title = title,
            originalTitle = originalTitle,
            year = date.take(4).toIntOrNull(),
            originalLanguage =
                node.path("original_language")
                    .asText()
                    .lowercase(
                        Locale.ROOT
                    ),
            originCountries = countries,
            isSatyajitRay = false
        ).takeIf {
            it.id > 0 &&
                it.title.isNotBlank()
        }
    }

    private fun compactQuery(
        value: String
    ): String =
        value.lowercase(
            Locale.ROOT
        ).filter(Char::isLetterOrDigit)

    private fun normalizePersonName(
        value: String
    ): String =
        compactQuery(value)

    private fun isTemporarilyUnhealthy(
        index: Int
    ): Boolean {
        val until =
            unhealthyUntil[index]
                ?: return false

        if (until <= System.currentTimeMillis()) {
            unhealthyUntil.remove(index)
            return false
        }

        return true
    }

    private fun markUnhealthy(
        index: Int
    ) {
        unhealthyUntil[index] =
            System.currentTimeMillis() +
                FAILURE_COOLDOWN_MS
    }
}