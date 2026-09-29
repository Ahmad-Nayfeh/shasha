package com.ahmadnayfeh.shasha.ui.settings

import androidx.annotation.StringRes
import androidx.compose.runtime.Immutable
import com.ahmadnayfeh.shasha.R

/** One line of «حول» → المكتبات والرخص: the project, its licence (SPDX name, as the project states it) and what it does here. */
@Immutable
data class LicenseEntry(val name: String, val license: String, @StringRes val role: Int)

/**
 * What the APK contains and under which licence, checked on 2026-09-29 against each dependency's POM in the
 * release runtime classpath (`:app:dependencies --configuration releaseRuntimeClasspath`) and, where a POM says
 * nothing, the project's sources: nanojson's files carry the Apache-2.0 header (upstream is MIT or Apache-2.0).
 * Update it with the version catalog when a library is added.
 */
internal object Licenses {
    val libraries: List<LicenseEntry> = listOf(
        LicenseEntry("NewPipeExtractor", "GPL-3.0-or-later", R.string.license_role_newpipe),
        LicenseEntry("AndroidX · Jetpack Compose · Media3", "Apache-2.0", R.string.license_role_androidx),
        LicenseEntry("OkHttp", "Apache-2.0", R.string.license_role_okhttp),
        LicenseEntry("Ktor", "Apache-2.0", R.string.license_role_ktor),
        LicenseEntry("Kotlin · kotlinx", "Apache-2.0", R.string.license_role_kotlinx),
        LicenseEntry("Coil", "Apache-2.0", R.string.license_role_coil),
        LicenseEntry("ZXing", "Apache-2.0", R.string.license_role_zxing),
        LicenseEntry("Mozilla Rhino", "MPL-2.0", R.string.license_role_rhino),
        LicenseEntry("jsoup", "MIT", R.string.license_role_jsoup),
        LicenseEntry("nanojson", "Apache-2.0", R.string.license_role_nanojson),
        LicenseEntry("protobuf-javalite", "BSD-3-Clause", R.string.license_role_protobuf),
        LicenseEntry("SLF4J", "MIT", R.string.license_role_slf4j),
        LicenseEntry("Okio · Guava · Typesafe Config · JSpecify", "Apache-2.0", R.string.license_role_helpers),
        LicenseEntry("desugar_jdk_libs", "GPL-2.0 WITH Classpath-exception-2.0", R.string.license_role_desugar),
        LicenseEntry("IBM Plex Sans Arabic", "OFL-1.1", R.string.license_role_plex),
    )

    /** Projects whose ideas the app follows, in its own code. */
    val ideas: List<LicenseEntry> = listOf(
        LicenseEntry("SmartTube", "MIT", R.string.license_role_smarttube),
        LicenseEntry("tv-app-lock", "MIT", R.string.license_role_tv_app_lock),
    )
}
