plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.movieflick.youtubekids"
    compileSdk = 35
    defaultConfig { minSdk = 21 }
}

version = 11

cloudstream {
    description = "Bengali India YouTube Kids provider for Movie-Flick"
    authors = listOf("Movie-Flick")
    status = 1
    tvTypes = listOf("Other","TvSeries")
    language = "bn-IN"
    iconUrl = "https://upload.wikimedia.org/wikipedia/commons/thumb/f/fb/YouTube_Kids_LogoVector.svg/512px-YouTube_Kids_LogoVector.svg.png"
    isCrossPlatform = true
}

dependencies {
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
}