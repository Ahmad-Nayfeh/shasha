plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.roborazzi)
}

// Release signing, versionCode and the updater's token come from CI (contract documented at the top of
// .github/workflows/android.yml), the same variables the TV app reads. Without them (local builds, Dependabot PRs)
// the release build still succeeds, unsigned, and the in-app updater explains that it has no token.
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
    namespace = "com.ahmadnayfeh.shasha.mobile"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.ahmadnayfeh.shasha.mobile"
        minSdk = 26
        targetSdk = 35
        versionCode = ciEnv("SHASHA_VERSION_CODE")
            ?.let { it.toIntOrNull() ?: error("SHASHA_VERSION_CODE must be an integer, got '$it'") }
            ?: 1
        versionName = "1.5.0"
        // The updater reads this repository's latest release. The repository is private, so it needs a read-only
        // token (CI's SHASHA_UPDATE_TOKEN); empty = the updater says that updates are unavailable in this build.
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
    testOptions {
        unitTests.isIncludeAndroidResources = true
        unitTests.isReturnDefaultValues = true
    }
    // Arabic and English only: library translations of other languages would only add size.
    androidResources { localeFilters += listOf("ar", "en") }
    // The app switches between Arabic and English itself (Settings): an app bundle keeps both languages.
    bundle { language { enableSplit = false } }
}

kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

roborazzi {
    // Commit-able reference screenshots, which the guide and the video reuse (record: ./gradlew :mobile:recordRoborazziDebug).
    outputDir.set(file("src/test/screenshots"))
}

// Robolectric downloads its android-all jars at test time. Maven Central (repo1) rate-limits this build
// environment, so use Google's mirror of it, the same one settings.gradle.kts uses.
tasks.withType<Test>().configureEach {
    systemProperty("robolectric.dependency.repo.url", "https://maven-central.storage-download.googleapis.com/maven2")
    // The screenshot tests draw phone-sized bitmaps (as :app does at 3g).
    maxHeapSize = "3g"
}

dependencies {
    coreLibraryDesugaring(libs.desugar.jdk.libs.nio)
    implementation(project(":youtube"))

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.foundation)
    implementation(libs.compose.animation)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons.extended)
    implementation(libs.compose.ui.tooling.preview)
    debugImplementation(libs.compose.ui.tooling)
    implementation(libs.activity.compose)
    implementation(libs.core.ktx)
    implementation(libs.lifecycle.runtime.compose)
    implementation(libs.lifecycle.viewmodel.compose)
    implementation(libs.lifecycle.service)
    implementation(libs.navigation.compose)

    implementation(libs.media3.exoplayer)
    implementation(libs.media3.exoplayer.dash)
    implementation(libs.media3.exoplayer.hls)
    implementation(libs.media3.session)
    implementation(libs.media3.ui.compose)

    implementation(libs.okhttp)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)
    implementation(libs.datastore.preferences)
    implementation(libs.coil.compose)
    implementation(libs.coil.network.okhttp)

    testImplementation(libs.junit)
    testImplementation(libs.truth)
    testImplementation(libs.robolectric)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.okhttp.mockwebserver)
    testImplementation(libs.androidx.test.ext.junit)
    testImplementation(libs.room.testing)
    // The screens are composed in a few smoke tests (Robolectric): every tab opens, English and Arabic.
    testImplementation(platform(libs.compose.bom))
    testImplementation(libs.compose.ui.test.junit4)
    debugImplementation(libs.compose.ui.test.manifest)
    // Screenshots of the main screens with fake data (src/test/screenshots), checked by verifyRoborazziDebug.
    testImplementation(libs.roborazzi)
    testImplementation(libs.roborazzi.compose)
}
