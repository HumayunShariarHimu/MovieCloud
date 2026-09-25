plugins {
    id("com.android.library")
    id("kotlin-android")
    id("com.lagradost.cloudstream3.gradle")
}

cloudstream {
    setRepo(System.getenv("GITHUB_REPOSITORY") ?: "MyselfHumayunShariarHimu/MovieCloud")
    iconUrl = "https://raw.githubusercontent.com/MyselfHumayunShariarHimu/MovieCloud/main/OnlineMovies/icon.svg"
}

android {
    namespace = "com.movieflick.onlinemovies"
    defaultConfig { minSdk = 21; compileSdk = 35; targetSdk = 35 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }
}

dependencies {
    implementation(kotlin("stdlib"))
    implementation("com.github.Blatzar:NiceHttp:0.4.11")
    implementation("org.jsoup:jsoup:1.18.3")
}