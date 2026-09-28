plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "com.ahmadnayfeh.shasha.youtube"
    compileSdk = 37

    defaultConfig {
        minSdk = 28
        consumerProguardFiles("consumer-rules.pro")
        buildConfigField("String", "EXTRACTOR_VERSION", "\"${libs.versions.newpipeExtractor.get()}\"")
    }
    buildFeatures { buildConfig = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        isCoreLibraryDesugaringEnabled = true
    }
    testOptions { unitTests.isReturnDefaultValues = true }
}

kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }

dependencies {
    coreLibraryDesugaring(libs.desugar.jdk.libs.nio)
    // Only this module may see NewPipeExtractor. Keep it `implementation`, never `api`.
    implementation(libs.newpipe.extractor)
    implementation(libs.okhttp)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.media3.datasource)
    implementation(libs.media3.exoplayer)
    implementation(libs.media3.exoplayer.dash)
    implementation(libs.media3.exoplayer.hls)

    testImplementation(libs.junit)
    testImplementation(libs.truth)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.okhttp.mockwebserver)
    // Playback tests parse MPDs with Media3 and use android.net.Uri: they run under Robolectric.
    testImplementation(libs.robolectric)
}

// Real-network tests (RealNetworkVideoSourceTest) are skipped unless run with -Dyoutube.realNetwork=true.
val realNetwork = System.getProperty("youtube.realNetwork") ?: "false"
tasks.withType<Test>().configureEach {
    systemProperty("youtube.realNetwork", realNetwork)
    // Robolectric downloads its android-all jars at test time. Maven Central (repo1) rate-limits this
    // build environment, so use Google's mirror of it, the same one settings.gradle.kts uses.
    systemProperty("robolectric.dependency.repo.url", "https://maven-central.storage-download.googleapis.com/maven2")
    if (realNetwork == "true") {
        // YouTube's answer changes over time: never reuse a cached or up-to-date result.
        outputs.upToDateWhen { false }
        outputs.cacheIf { false }
    }
    testLogging {
        events("passed", "skipped", "failed", "standardOut", "standardError")
        showStandardStreams = true
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}
