import java.io.File

version = 2

cloudstream {
    description = "Dhaka FTP"
    authors = listOf("Movie-Flick")
    status = 1

    tvTypes = listOf(
        "Movie",
        "TvSeries",
        "Anime"
    )

    language = "bn"
    iconUrl = "https://raw.githubusercontent.com/MyselfHumayunShariarHimu/MovieCloud/main/DhakaFTP/icon.svg"
}

/*
 * TMDB credentials are injected only during CI build time from GitHub
 * Repository Secrets. No credential is committed to the repository.
 *
 * When the secrets are not present, TmdbSecrets is generated with empty
 * strings and DhakaFTP automatically falls back to the legacy search engine.
 */

fun envSecret(
    name: String
): String =
    System.getenv(name).orEmpty()

fun kotlinStringLiteral(
    value: String
): String =
    value
        .replace("\\", "\\\\")
        .replace("\"", "\\\"")
        .replace("\r", "")
        .replace("\n", "")

val tmdbGeneratedDir =
    layout.buildDirectory.dir(
        "generated/source/tmdbSecrets/main/kotlin"
    )

val generateTmdbSecrets =
    tasks.register(
        "generateTmdbSecrets"
    ) {
        outputs.dir(
            tmdbGeneratedDir
        )

        doLast {
            val packageDir =
                tmdbGeneratedDir
                    .get()
                    .asFile
                    .resolve(
                        "com/movieflick/dhakaftp"
                    )

            packageDir.mkdirs()

            File(
                packageDir,
                "TmdbSecrets.kt"
            ).writeText(
                """
                package com.movieflick.dhakaftp

                internal object TmdbSecrets {
                    const val PRIMARY_API_KEY = "${kotlinStringLiteral(envSecret("TMDB_PRIMARY_API_KEY"))}"
                    const val PRIMARY_ACCESS_TOKEN = "${kotlinStringLiteral(envSecret("TMDB_PRIMARY_ACCESS_TOKEN"))}"

                    const val SECONDARY_API_KEY = "${kotlinStringLiteral(envSecret("TMDB_SECONDARY_API_KEY"))}"
                    const val SECONDARY_ACCESS_TOKEN = "${kotlinStringLiteral(envSecret("TMDB_SECONDARY_ACCESS_TOKEN"))}"

                    const val TERTIARY_API_KEY = "${kotlinStringLiteral(envSecret("TMDB_TERTIARY_API_KEY"))}"
                    const val TERTIARY_ACCESS_TOKEN = "${kotlinStringLiteral(envSecret("TMDB_TERTIARY_ACCESS_TOKEN"))}"
                }
                """.trimIndent()
            )
        }
    }

android {
    sourceSets["main"].java.srcDir(
        tmdbGeneratedDir
    )
}

tasks.configureEach {
    if (
        name == "preBuild"
    ) {
        dependsOn(
            generateTmdbSecrets
        )
    }
}