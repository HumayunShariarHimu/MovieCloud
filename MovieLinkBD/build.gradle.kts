plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
    id("com.lagradost.cloudstream3.gradle")
}

version = 1

cloudstream {
    description = "Movie Link BD provider for Movie-Flick"
    authors = listOf("Movie-Flick")
    status = 1
    tvTypes = listOf("Movie","TvSeries","Anime")
    language = "bn"
    iconUrl = "https://raw.githubusercontent.com/MyselfHumayunShariarHimu/MovieCloud/main/MovieLinkBD/icon.svg"
    isCrossPlatform = true
    setRepo(System.getenv("GITHUB_REPOSITORY") ?: "MyselfHumayunShariarHimu/MovieCloud")
}

android {
    namespace = "com.movieflick.movielinkbd"
    compileSdk = 35
    defaultConfig { minSdk = 21 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }
}

dependencies {
    implementation(kotlin("stdlib"))
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")
    implementation("com.github.Blatzar:NiceHttp:0.4.11")
    implementation("org.jsoup:jsoup:1.18.3")
}