plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.roborazzi)
}

// Release signing, versionCode and the updater's token come from CI (contract documented at the top of
// .github/workflows/android.yml). Without them (local builds, Dependabot PRs) the release build still succeeds,
// unsigned, and the in-app updater explains that it has no token.
fun ciEnv(name: String): String? = providers.environmentVariable(name).orNull?.takeIf { it.isNotBlank() }

/** All four signing values, or none of them: a half-configured key would only fail deep inside packaging. */
val releaseSigning: List<String>? =
    listOf("SHASHA_KEYSTORE_FILE", "SHASHA_KEYSTORE_PASSWORD", "SHASHA_KEY_ALIAS", "SHASHA_KEY_PASSWORD")
        .map(::ciEnv)
        .takeIf { values -> values.all { it != null } }
        ?.map { it!! }

/** A Kotlin/Java string literal for buildConfigField. */
fun javaString(value: String): String = "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

android {
    namespace = "com.ahmadnayfeh.shasha"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.ahmadnayfeh.shasha"
        minSdk = 25
        targetSdk = 35
        versionCode = ciEnv("SHASHA_VERSION_CODE")
            ?.let { it.toIntOrNull() ?: error("SHASHA_VERSION_CODE must be an integer, got '$it'") }
            ?: 1
        versionName = "1.5.0"
        // SPEC §6's updater reads this repository's GitHub Releases. The repository is private, so it needs a
        // read-only token (CI's SHASHA_UPDATE_TOKEN); empty = the updater explains that it has none.
        buildConfigField("String", "UPDATE_REPO", javaString("Ahmad-Nayfeh/shasha"))
        buildConfigField("String", "UPDATE_TOKEN", javaString(ciEnv("SHASHA_UPDATE_TOKEN")?.trim() ?: ""))
    }

    signingConfigs {
        val signing = releaseSigning
        if (signing != null) {
            create("release") {
                storeFile = file(signing[0])
                storePassword = signing[1]
                keyAlias = signing[2]
                keyPassword = signing[3]
            }
        }
    }

    buildTypes {
        release {
            signingConfig = signingConfigs.findByName("release")
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        isCoreLibraryDesugaringEnabled = true
    }
    testOptions { unitTests.isIncludeAndroidResources = true }
    // The bundled explainer videos (assets/video) stay uncompressed: Media3 reads and seeks them in place.
    androidResources { noCompress += "mp4" }
    // The app switches between Arabic and English itself (Settings → المظهر واللغة): an app bundle keeps both languages.
    bundle { language { enableSplit = false } }
}

roborazzi {
    // Commit-able reference screenshots (record: ./gradlew :app:recordRoborazziDebug).
    outputDir.set(file("src/test/screenshots"))
}

// Real-network tests (FeedSyncRealNetworkTest) are skipped unless run with -Dyoutube.realNetwork=true, as in :youtube.
val realNetwork = System.getProperty("youtube.realNetwork") ?: "false"

// The explainer video's English captures (promo/remotion): -PshashaPromoEnglish makes the screenshot tests record them,
// in English, into promo/screens-en (see PromoEnglish in the tests). Off, the tests are untouched.
val promoEnglish = providers.gradleProperty("shashaPromoEnglish").map { it != "false" }.getOrElse(false)

tasks.withType<Test>().configureEach {
    // Robolectric downloads its android-all jars at test time. Maven Central (repo1) rate-limits this
    // build environment, so use Google's mirror of it, the same one settings.gradle.kts uses.
    systemProperty("robolectric.dependency.repo.url", "https://maven-central.storage-download.googleapis.com/maven2")
    maxHeapSize = "3g"
    systemProperty("youtube.realNetwork", realNetwork)
    if (promoEnglish) systemProperty("shasha.promoEnglish", "true")
    if (realNetwork == "true") {
        // YouTube's answer changes over time: never reuse a cached or up-to-date result.
        outputs.upToDateWhen { false }
        outputs.cacheIf { false }
        testLogging {
            events("passed", "skipped", "failed")
            showStandardStreams = true
            exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
        }
    }
}

kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    coreLibraryDesugaring(libs.desugar.jdk.libs.nio)
    implementation(project(":youtube"))
    implementation(project(":policy"))
    implementation(project(":remote"))

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.foundation)
    implementation(libs.compose.animation)
    implementation(libs.compose.ui.tooling.preview)
    debugImplementation(libs.compose.ui.tooling)
    implementation(libs.tv.material)
    implementation(libs.activity.compose)
    implementation(libs.core.ktx)
    implementation(libs.core.splashscreen)
    implementation(libs.lifecycle.runtime.compose)
    implementation(libs.lifecycle.viewmodel.compose)
    implementation(libs.lifecycle.service)
    implementation(libs.lifecycle.process)
    implementation(libs.navigation.compose)

    implementation(libs.media3.exoplayer)
    implementation(libs.media3.exoplayer.dash)
    implementation(libs.media3.exoplayer.hls)
    implementation(libs.media3.ui.compose)

    implementation(libs.okhttp)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)
    implementation(libs.datastore.preferences)
    implementation(libs.work.runtime.ktx)
    implementation(libs.coil.compose)
    implementation(libs.coil.network.okhttp)
    implementation(libs.zxing.core)

    testImplementation(libs.junit)
    testImplementation(libs.truth)
    testImplementation(libs.robolectric)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.okhttp.mockwebserver)
    testImplementation(platform(libs.compose.bom))
    testImplementation(libs.compose.ui.test.junit4)
    debugImplementation(libs.compose.ui.test.manifest)
    testImplementation(libs.room.testing)
    testImplementation(libs.work.testing)
    testImplementation(libs.androidx.test.ext.junit)
    testImplementation(libs.roborazzi)
    testImplementation(libs.roborazzi.compose)
}
