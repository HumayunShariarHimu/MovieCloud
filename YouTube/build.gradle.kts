version = 1

cloudstream {
    description = "YouTube provider for Movie-Flick"
    authors = listOf("Movie-Flick")
    status = 1
    tvTypes = listOf("Other","Live","TvSeries")
    language = "hi"
    iconUrl = "https://raw.githubusercontent.com/MyselfHumayunShariarHimu/MovieCloud/main/YouTube/icon.svg"
    isCrossPlatform = true
}

dependencies {
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
}