plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.roborazzi)
}

// Release signing and versionCode come from CI (contract documented in .github/workflows/android.yml).
// Without them (local builds, Dependabot PRs) the release build still succeeds, unsigned.
fun ciEnv(name: String): String? = providers.environmentVariable(name).orNull?.takeIf { it.isNotBlank() }
val releaseKeystore = ciEnv("SHASHA_KEYSTORE_FILE")

android {
    namespace = "com.ahmadnayfeh.shasha"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.ahmadnayfeh.shasha"
        minSdk = 28
        targetSdk = 35
        versionCode = ciEnv("SHASHA_VERSION_CODE")
            ?.let { it.toIntOrNull() ?: error("SHASHA_VERSION_CODE must be an integer, got '$it'") }
            ?: 1
        versionName = "0.1.0"
    }

    signingConfigs {
        if (releaseKeystore != null) {
            create("release") {
                storeFile = file(releaseKeystore)
                storePassword = ciEnv("SHASHA_KEYSTORE_PASSWORD")
                keyAlias = ciEnv("SHASHA_KEY_ALIAS")
                keyPassword = ciEnv("SHASHA_KEY_PASSWORD")
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
}

roborazzi {
    // Commit-able reference screenshots (record: ./gradlew :app:recordRoborazziDebug).
    outputDir.set(file("src/test/screenshots"))
}

// Real-network tests (FeedSyncRealNetworkTest) are skipped unless run with -Dyoutube.realNetwork=true, as in :youtube.
val realNetwork = System.getProperty("youtube.realNetwork") ?: "false"

tasks.withType<Test>().configureEach {
    // Robolectric downloads its android-all jars at test time. Maven Central (repo1) rate-limits this
    // build environment, so use Google's mirror of it, the same one settings.gradle.kts uses.
    systemProperty("robolectric.dependency.repo.url", "https://maven-central.storage-download.googleapis.com/maven2")
    maxHeapSize = "3g"
    systemProperty("youtube.realNetwork", realNetwork)
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
    testImplementation(platform(libs.compose.bom))
    testImplementation(libs.compose.ui.test.junit4)
    debugImplementation(libs.compose.ui.test.manifest)
    testImplementation(libs.room.testing)
    testImplementation(libs.work.testing)
    testImplementation(libs.androidx.test.ext.junit)
    testImplementation(libs.roborazzi)
    testImplementation(libs.roborazzi.compose)
}
