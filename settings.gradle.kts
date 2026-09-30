pluginManagement {
    repositories {
        google()
        // Google's mirror of Maven Central (same artifacts, no HTTP 429 rate limits).
        maven("https://maven-central.storage-download.googleapis.com/maven2/")
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        // Google's mirror of Maven Central (same artifacts, no HTTP 429 rate limits).
        maven("https://maven-central.storage-download.googleapis.com/maven2/")
        // NewPipeExtractor and its nanojson fork are only published on JitPack.
        exclusiveContent {
            forRepository { maven("https://jitpack.io") }
            filter { includeGroup("com.github.TeamNewPipe") }
        }
    }
}

rootProject.name = "shasha"

include(":app", ":youtube", ":policy", ":remote")
