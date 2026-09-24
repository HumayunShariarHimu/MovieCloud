pluginManagement {
 repositories { google(); mavenCentral(); gradlePluginPortal(); maven("https://jitpack.io") }
}
dependencyResolutionManagement {
 repositoriesMode.set(RepositoriesMode.PREFER_SETTINGS)
 repositories { google(); mavenCentral(); maven("https://jitpack.io") }
}
rootProject.name = "Movie-Flick"
fileTree(rootDir) { include("*/build.gradle.kts") }.files.forEach { file -> include(file.parentFile.name) }