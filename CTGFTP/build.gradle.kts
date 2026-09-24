plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.movieflick.ctgftp"
    compileSdk = 35

    defaultConfig {
        minSdk = 21
    }
}

version = 12

cloudstream {
    description = "CTG FTP provider for Movie-Flick"
    authors = listOf("Movie-Flick")
    status = 1
    tvTypes = listOf(
        "Movie",
        "TvSeries",
        "Anime"
    )
    language = "bn"
    iconUrl = "https://i.postimg.cc/QMxBDF9T/favicon-V2.png"
    isCrossPlatform = true
}

dependencies {
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
}